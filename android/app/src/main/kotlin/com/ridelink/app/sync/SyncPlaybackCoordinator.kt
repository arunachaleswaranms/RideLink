package com.ridelink.app.sync

import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.PeerId
import com.ridelink.core.playback.CommandOrderDecision
import com.ridelink.core.playback.CommandOrderGate
import com.ridelink.core.playback.DriftAction
import com.ridelink.core.playback.DriftController
import com.ridelink.core.playback.DriftInput
import com.ridelink.core.playback.DriftState
import com.ridelink.core.playback.PlaybackBounds
import com.ridelink.core.playback.PlaybackCommandHeader
import com.ridelink.core.playback.PlaybackMessage
import com.ridelink.core.playback.PlaybackRole
import com.ridelink.core.playback.PlaybackTimeline
import com.ridelink.core.playback.QueueAddItem
import com.ridelink.core.playback.QueueCommandHeader
import com.ridelink.core.playback.QueueMessage
import com.ridelink.core.playback.ScheduledCommand
import com.ridelink.core.playback.ScheduledCommandDecision
import com.ridelink.core.playback.SharedQueue
import com.ridelink.core.playback.SharedQueueMutation
import com.ridelink.core.playback.SharedQueueState
import com.ridelink.core.sync.SessionClockEstimate
import com.ridelink.core.transfer.OperationFence
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.playback.PlaybackSink
import com.ridelink.network.playback.QueueSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single owner of Phase 5 synchronised-playback state (CLAUDE.md rule 8, applied to the
 * synchronisation plane the way `MusicCoordinator` applies it to local music,
 * `SharedLibraryCoordinator` to the catalogue and `SessionCoordinator` to the control plane).
 *
 * **It owns no player and no queue of its own** (brief §21). Every audible effect goes through
 * [SyncPlayerPort], which is the *existing* `MusicCoordinator` — its `LocalQueue`, its one
 * `ExoPlayerMusicPlayer`, its one ADR-022 `MediaSession`. There is no `SyncPlayer`, no `PeerPlayer`
 * and no second coordinator anywhere in this phase.
 *
 * **Every distributed decision is made by a pure, mirrored, vector-pinned type**, never here:
 * ordering by [CommandOrderGate], deadline mapping by [ScheduledCommand], correction by
 * [DriftController], queue algebra by [SharedQueue], timing by
 * [com.ridelink.core.sync.SessionClock]. That is ADR-019's direct lesson — a distributed rule that
 * lives inside a coordinator is a rule no vector can pin — and it is why this class is wiring and
 * lifetime rather than policy.
 *
 * **Session binding (the Phase 4 lesson, ADR-023 §3).** Every inbound frame is tagged at *dispatch*
 * with [SyncSessionPort.currentAuthGeneration], and every step after a suspension re-proves it
 * ([stillCurrent]). ADR-023 Amendment A3 found exactly this class of bug in Phase 4 — a check at
 * handler entry proves nothing about what suspends afterwards — so the re-check is at every
 * transition, not at the top.
 *
 * **Playback epochs.** [playbackFence] is the existing [OperationFence], reused rather than
 * reinvented: a scheduled start, a late `POSITION_REPORT`, a drift correction or a rate restore
 * belonging to a superseded epoch is inert. `content_hash` alone would not do, because the same
 * track can legitimately be played again (brief §32).
 */
@Suppress("TooManyFunctions", "LongParameterList")
class SyncPlaybackCoordinator(
    private val scope: CoroutineScope,
    private val monotonicNowUs: () -> Long,
    private val localPeerId: PeerId,
    private val session: SyncSessionPort,
    private val player: SyncPlayerPort,
    private val content: SyncContentPort,
    private val sleeper: SyncDeadlineSleeper,
    /** True while **either** peer reports `AUDIO_STATE.route_state: "transitioning"` (PROTOCOL §4.4). */
    private val routeTransitioning: () -> Boolean,
    private val nextQueueItemId: () -> String,
) {
    private val _diagnostics = MutableStateFlow(SyncPlaybackDiagnostics())
    val diagnostics: StateFlow<SyncPlaybackDiagnostics> = _diagnostics.asStateFlow()

    private val _queueState = MutableStateFlow(SharedQueueState())

    /** The authoritative replicated queue. `LocalQueue` is untouched and still owns local-only rides. */
    val queueState: StateFlow<SharedQueueState> = _queueState.asStateFlow()

    /**
     * Serialises everything that reads-then-writes command/queue state: the leader's `command_seq`
     * allocation and queue mutation, and a receiver's order gate and apply. One serialisation point
     * per device, under the one serialisation point per session that is the ADR-010 leader.
     */
    private val commandMutex = Mutex()

    private val playbackFence = OperationFence()

    @Volatile
    private var role: PlaybackRole? = null

    @Volatile
    private var syncEnabled = false

    private var lastAppliedSeq: Long? = null
    private var nextSeq: Long = PlaybackBounds.FIRST_COMMAND_SEQ
    private var timeline: PlaybackTimeline? = null
    private var driftState: DriftState = DriftController.reset()
    private var tickJob: Job? = null

    /**
     * The [OperationFence] token of the playback epoch currently in force. A `PAUSE`/`SEEK` schedules
     * against *this* token rather than beginning a new epoch, so it is superseded automatically by a
     * later `PLAY`/`NEXT` without needing to know one happened.
     */
    @Volatile
    private var currentEpochToken: Long = -1L

    init {
        // Both sinks enqueue rather than launch. A coroutine per frame preserves only the order in
        // which coroutines are *started*: `onPlaybackMessage` suspends (content resolution, the
        // decoder pre-roll), so frame N+1 can overtake frame N inside the suspension and a follower
        // would drop the overtaken command as stale — `CommandOrderGate` doing exactly its job on
        // input that reached it out of order. One bounded channel, one consumer, arrival order
        // preserved. Found by stress-running this phase's iOS suites, and fixed identically on both
        // platforms; the iOS mirror uses the `OrderedEventChannel` Phase 1b already introduced for
        // the same hazard (`docs/STATUS.md` §2h).
        session.playback.playbackSink =
            PlaybackSink { message, generation -> enqueue(Inbound.Playback(message, generation)) }
        session.playback.queueSink =
            QueueSink { message, generation -> enqueue(Inbound.Queue(message, generation)) }
        scope.launch { drainInbound() }
        scope.launch {
            session.events.collect { event ->
                when (event) {
                    is ControlEvent.Connected -> onSessionEstablished(event.isLocalLeader)
                    is ControlEvent.LinkLost -> onSessionLost()
                    is ControlEvent.ReconnectBudgetExhausted -> onSessionLost()
                    else -> Unit
                }
            }
        }
    }

    /** One inbound Phase 5 frame, already parsed, with the generation live when it was read. */
    private sealed class Inbound {
        data class Playback(
            val message: PlaybackMessage,
            val generation: Long,
        ) : Inbound()

        data class Queue(
            val message: QueueMessage,
            val generation: Long,
        ) : Inbound()
    }

    /**
     * Bounded, like every other queue this project adds (ADR-021 §5). Dropping the oldest is the
     * right policy here specifically: the command that matters is the newest, [CommandOrderGate]
     * already drops anything stale, and PROTOCOL §5's `PLAYBACK_STATE` is the reconciliation anchor
     * for whatever a drop cost. A drop is counted rather than silent.
     */
    private val inbound =
        Channel<Inbound>(capacity = INBOUND_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private fun enqueue(item: Inbound) {
        if (inbound.trySend(item).isFailure) {
            _diagnostics.update { it.copy(droppedInboundCount = it.droppedInboundCount + 1) }
        }
    }

    /**
     * Drains [inbound], one frame at a time, in arrival order. One consumer, so frame N+1 is never
     * handled before frame N — see the sink wiring above for why that is a correctness property.
     */
    private suspend fun drainInbound() {
        for (item in inbound) {
            when (item) {
                is Inbound.Playback -> onPlaybackMessage(item.message, item.generation)
                is Inbound.Queue -> onQueueMessage(item.message, item.generation)
            }
            _diagnostics.update { it.copy(inboundProcessedCount = it.inboundProcessedCount + 1) }
        }
    }

    // --- session lifecycle -------------------------------------------------------------------

    /**
     * ADR-019: `Connected` means the trust gate passed, so this is the first instant a Phase 5
     * message may be sent or acted on at all. The role comes straight from ADR-010's rule as the
     * handshake already computed it — never from who dialled, who pressed play, or which platform
     * this is.
     */
    private fun onSessionEstablished(isLocalLeader: Boolean) {
        resetForNewSession()
        role = if (isLocalLeader) PlaybackRole.LEADER else PlaybackRole.FOLLOWER
        _diagnostics.update { it.copy(role = role, sessionGeneration = session.currentAuthGeneration) }
        tickJob = scope.launch { tickLoop(session.currentAuthGeneration) }
    }

    /**
     * ADR-004: "A Wi-Fi drop does **not** interrupt music. Both phones keep playing; only
     * synchronisation pauses." Local audio is deliberately left alone here — what is torn down is
     * every *coordination* obligation: scheduled work is superseded, correction stops, and the rate
     * goes back to exactly 1.0 so nothing is left slewing against a peer that is gone (brief §40).
     */
    private fun onSessionLost() {
        resetForNewSession()
        _diagnostics.update { it.copy(role = null, syncState = SyncState.INACTIVE) }
    }

    private fun resetForNewSession() {
        role = null
        syncEnabled = false
        tickJob?.cancel()
        tickJob = null
        // Supersede rather than begin: nothing is current until a new epoch actually starts, so a
        // timer or a report still in flight from the previous session can match no token at all.
        playbackFence.supersede()
        lastAppliedSeq = null
        nextSeq = PlaybackBounds.FIRST_COMMAND_SEQ
        timeline = null
        driftState = DriftController.reset()
        _queueState.value = SharedQueueState()
        scope.launch { restoreRate() }
        _diagnostics.update {
            it.copy(
                syncState = SyncState.INACTIVE,
                lastAppliedCommandSeq = null,
                nextCommandSeq = null,
                queueRevision = 0,
                queueSize = 0,
                currentTrackHash = null,
                localDriftMs = null,
                peerDriftMs = null,
                lastCorrection = SyncCorrection.NONE,
                playbackRate = DriftController.RATE_NORMAL,
                hardSeekCount = 0,
                lastScheduleErrorUs = null,
                correctionTickCount = 0,
                sessionGeneration = session.currentAuthGeneration,
            )
        }
    }

    /** ADR-023 §3's guard, re-proved at every transition rather than once at handler entry. */
    private fun stillCurrent(generation: Long): Boolean = generation == session.currentAuthGeneration && role != null

    // --- the clock ---------------------------------------------------------------------------

    /**
     * The session clock as **this** device sees it.
     *
     * On the leader the session clock *is* the local monotonic clock, so the offset is exactly zero
     * — but readiness is still taken from the estimator. The leader cannot observe whether the
     * follower's own burst has converged, and its own first accepted window (~600 ms after
     * `Connected`, ARCHITECTURE §7.1) is the best available evidence that both sides' bursts have
     * completed on a healthy link. The follower's own [SessionClockEstimate.ready] gate is what
     * actually protects it; this is the leader declining to shout into a link it cannot yet measure.
     */
    private fun estimate(): SessionClockEstimate? {
        val raw = session.clockEstimate.value
        return when (role) {
            PlaybackRole.LEADER -> SessionClockEstimate(0L, session.rttP95Us, ready = raw?.ready == true)
            PlaybackRole.FOLLOWER -> raw
            null -> null
        }
    }

    private fun readyEstimate(): SessionClockEstimate? {
        val estimate = estimate()
        if (estimate == null || !estimate.ready) {
            _diagnostics.update { it.copy(syncState = SyncState.CLOCK_UNREADY, clockReady = false) }
            return null
        }
        return estimate
    }

    private fun sessionNowUs(estimate: SessionClockEstimate): Long = estimate.sessionUs(monotonicNowUs())

    // --- issuing (either user may act; the leader alone assigns order) --------------------------

    /**
     * ADR-010's whole design in one function. The leader stamps and broadcasts; a follower sends the
     * **same message type** with `command_seq: 0` — ADR-024 §3's intent marker — and waits for the
     * leader's authoritative broadcast to arrive back. Neither path lets a follower allocate a
     * sequence number, and neither adds a message type PROTOCOL §3 does not already list.
     *
     * `effective_at_session_us` on an intent is `0` and is ignored by the leader: a follower has no
     * authority to choose when something becomes audible, and expressing that as a real instant
     * would invite an implementation to honour it.
     */
    @Suppress("ReturnCount") // one early-out per role and per readiness gate
    private suspend fun issue(build: (PlaybackCommandHeader) -> PlaybackMessage) {
        val currentRole = role ?: return
        val generation = session.currentAuthGeneration
        if (currentRole == PlaybackRole.FOLLOWER) {
            val header = PlaybackCommandHeader(PlaybackBounds.UNASSIGNED_COMMAND_SEQ, 0L, localPeerId, _queueState.value.revision)
            session.playback.send(build(header))
            return
        }
        val estimate = readyEstimate() ?: return
        val header =
            commandMutex.withLock {
                val seq = nextSeq
                nextSeq += 1
                PlaybackCommandHeader(seq, sessionNowUs(estimate) + estimate.leadUs, localPeerId, _queueState.value.revision)
            }
        _diagnostics.update { it.copy(nextCommandSeq = nextSeq) }
        val message = build(header)
        session.playback.send(message)
        if (!stillCurrent(generation)) return
        // The leader applies its own command exactly as the follower will: same header, same
        // effective instant, same code path. There is no "issuer applies immediately" shortcut,
        // because that shortcut is precisely how two phones end up on two timelines.
        commandMutex.withLock { lastAppliedSeq = header.commandSeq }
        _diagnostics.update { it.copy(lastAppliedCommandSeq = header.commandSeq) }
        applyAuthoritative(message, generation, estimate)
    }

    // --- user-facing actions (also the system media controls' path, brief §39) ------------------

    /**
     * Starts synchronised playback of [contentHash]. Both halves of brief §19's gate are checked
     * **before** any command is issued: this device must be able to play it, and the peer must be
     * known to hold it. A remote-only track cannot begin synchronised playback (REQUIREMENTS §9.4).
     */
    fun playSynchronized(contentHash: ContentHash) {
        scope.launch {
            val currentRole = role ?: return@launch
            syncEnabled = true
            val queueItemId = ensureQueued(contentHash) ?: return@launch
            if (currentRole == PlaybackRole.FOLLOWER) {
                issue { header -> PlaybackMessage.Play(header, contentHash, 0, queueItemId) }
                return@launch
            }
            if (!gateContent(contentHash)) return@launch
            issue { header -> PlaybackMessage.Play(header, contentHash, 0, queueItemId) }
        }
    }

    /** Both sides of brief §19's availability gate, and PROTOCOL §5 rule 4's transfer request. */
    @Suppress("ReturnCount") // one early-out per half of the brief §19 availability gate
    private suspend fun gateContent(contentHash: ContentHash): Boolean {
        val local = content.resolve(contentHash)
        if (local == null) {
            _diagnostics.update { it.copy(syncState = SyncState.WAITING_FOR_CONTENT) }
            if (content.peerHasContent(contentHash)) content.requestTransfer(contentHash)
            return false
        }
        if (!content.peerHasContent(contentHash)) {
            _diagnostics.update { it.copy(syncState = SyncState.WAITING_FOR_CONTENT) }
            return false
        }
        return true
    }

    /** @return the shared-queue item id this hash now occupies, or null if the queue refused it. */
    private suspend fun ensureQueued(contentHash: ContentHash): String? {
        val existing = _queueState.value.items.firstOrNull { it.trackHash == contentHash }
        if (existing != null) return existing.queueItemId
        val id = nextQueueItemId()
        enqueueInternal(listOf(QueueAddItem(id, contentHash, localPeerId, PlaybackBounds.QUEUE_POSITION_END)))
        return if (_queueState.value.items.any { it.queueItemId == id } || role == PlaybackRole.FOLLOWER) id else null
    }

    fun enqueue(contentHash: ContentHash) {
        scope.launch {
            enqueueInternal(listOf(QueueAddItem(nextQueueItemId(), contentHash, localPeerId, PlaybackBounds.QUEUE_POSITION_END)))
        }
    }

    fun removeFromQueue(queueItemId: String) = scope.launch { mutateQueue(SharedQueueMutation.Remove(listOf(queueItemId))) }

    fun moveInQueue(
        queueItemId: String,
        toIndex: Int,
    ) = scope.launch { mutateQueue(SharedQueueMutation.Move(queueItemId, toIndex)) }

    private suspend fun enqueueInternal(items: List<QueueAddItem>) = mutateQueue(SharedQueueMutation.Add(items))

    /**
     * PROTOCOL §9: the leader applies and broadcasts the resulting snapshot; a follower sends the
     * mutation as an intent and waits. **The snapshot is the only way the queue reaches a follower**
     * (ADR-024 §5) — §9's own "the snapshot always wins, there is no merge algorithm to get subtly
     * wrong", taken literally.
     */
    private suspend fun mutateQueue(mutation: SharedQueueMutation) {
        val currentRole = role ?: return
        if (currentRole == PlaybackRole.FOLLOWER) {
            session.playback.send(queueIntent(mutation))
            return
        }
        applyLeaderMutation(mutation)
    }

    private fun queueIntent(mutation: SharedQueueMutation): QueueMessage {
        val header = QueueCommandHeader(PlaybackBounds.UNASSIGNED_COMMAND_SEQ, _queueState.value.revision)
        return when (mutation) {
            is SharedQueueMutation.Add -> QueueMessage.Add(header, mutation.items)
            is SharedQueueMutation.Remove -> QueueMessage.Remove(header, mutation.queueItemIds)
            is SharedQueueMutation.Move -> QueueMessage.Move(header, mutation.queueItemId, mutation.toIndex)
        }
    }

    @Suppress("ReturnCount") // one early-out per reason a mutation produces no broadcast
    private suspend fun applyLeaderMutation(mutation: SharedQueueMutation) {
        val generation = session.currentAuthGeneration
        val outcome =
            commandMutex.withLock {
                SharedQueue.apply(_queueState.value, mutation).also { if (it.changed) _queueState.value = it.state }
            }
        if (outcome.rejection != null) return
        if (!outcome.changed) return
        publishQueue()
        if (!stillCurrent(generation)) return
        session.playback.send(snapshotOf(_queueState.value))
    }

    private fun snapshotOf(state: SharedQueueState): QueueMessage = QueueMessage.Snapshot(state.revision, state.items, state.currentIndex)

    // --- the transport-control actions, shared with the system media controls -------------------

    fun pause() =
        scope.launch {
            val position = player.playerState.value.positionMs
            issue { header -> PlaybackMessage.Pause(header, position.coerceAtLeast(0)) }
        }

    fun resume() =
        scope.launch {
            val position = player.playerState.value.positionMs
            issue { header -> PlaybackMessage.Resume(header, position.coerceAtLeast(0)) }
        }

    fun seek(positionMs: Long) = scope.launch { issue { header -> PlaybackMessage.Seek(header, positionMs.coerceAtLeast(0)) } }

    fun next() = scope.launch { issue { header -> PlaybackMessage.Next(header) } }

    fun previous() = scope.launch { issue { header -> PlaybackMessage.Previous(header) } }

    /**
     * Leaves synchronised mode without ending the control session: local playback continues exactly
     * as a Phase 3 ride, correction stops and the rate goes back to exactly 1.0 (brief §38).
     */
    fun leaveSynchronizedMode() {
        syncEnabled = false
        playbackFence.supersede()
        timeline = null
        driftState = DriftController.reset()
        scope.launch { restoreRate() }
        _diagnostics.update { it.copy(syncState = SyncState.INACTIVE, localDriftMs = null, peerDriftMs = null) }
    }

    /** Whether a synchronised session currently owns transport control (brief §39/§40). */
    fun isSynchronizedModeActive(): Boolean = syncEnabled && role != null

    private suspend fun restoreRate() {
        player.setRate(DriftController.RATE_NORMAL)
        _diagnostics.update { it.copy(playbackRate = DriftController.RATE_NORMAL) }
    }

    // --- receiving ------------------------------------------------------------------------------

    /**
     * PROTOCOL §5/§9's inbound path. [generation] was captured in the sink at *dispatch* time; every
     * transition below re-proves it, because ADR-023 Amendment A3 found in Phase 4 that a check at
     * handler entry says nothing about what suspends afterwards.
     */
    private suspend fun onPlaybackMessage(
        message: PlaybackMessage,
        generation: Long,
    ) {
        if (!stillCurrent(generation)) return
        when (message) {
            is PlaybackMessage.PositionReport -> onPeerPositionReport(message)
            is PlaybackMessage.PlaybackStateSnapshot -> onPeerPlaybackState(message)
            else -> onInboundCommand(message, generation)
        }
    }

    private suspend fun onQueueMessage(
        message: QueueMessage,
        generation: Long,
    ) {
        if (!stillCurrent(generation)) return
        when (message) {
            is QueueMessage.Snapshot -> adoptSnapshot(message)
            is QueueMessage.Add -> onQueueIntent(message.header, SharedQueueMutation.Add(message.items), generation)
            is QueueMessage.Remove -> onQueueIntent(message.header, SharedQueueMutation.Remove(message.queueItemIds), generation)
            is QueueMessage.Move ->
                onQueueIntent(message.header, SharedQueueMutation.Move(message.queueItemId, message.toIndex), generation)
        }
    }

    /**
     * PROTOCOL §9: "The snapshot always wins — there is no merge algorithm to get subtly wrong."
     * A follower adopts it wholesale, revision included; it never increments a revision itself.
     */
    private fun adoptSnapshot(message: QueueMessage.Snapshot) {
        if (role != PlaybackRole.FOLLOWER) return
        _queueState.value = SharedQueue.applySnapshot(message.queueRevision, message.items, message.currentIndex)
        publishQueue()
    }

    /**
     * A follower's queue intent, arriving at the leader. Ordering and the stale-revision rule
     * (PROTOCOL §5 rule 3) are applied here; the leader then serialises the mutation exactly as it
     * would its own user's, which is what makes two simultaneous adds deterministic.
     */
    @Suppress("ReturnCount") // one early-out per PROTOCOL §5/§9 rule the intent must satisfy
    private suspend fun onQueueIntent(
        header: QueueCommandHeader,
        mutation: SharedQueueMutation,
        generation: Long,
    ) {
        val currentRole = role ?: return
        when (CommandOrderGate.decide(currentRole, lastAppliedSeq, header.commandSeq)) {
            CommandOrderDecision.INTENT -> Unit
            CommandOrderDecision.ROLE_VIOLATION -> {
                _diagnostics.update { it.copy(roleViolationCount = it.roleViolationCount + 1) }
                return
            }
            else -> return
        }
        if (header.queueRevision != _queueState.value.revision) {
            _diagnostics.update { it.copy(staleRevisionCount = it.staleRevisionCount + 1) }
            // PROTOCOL §5 rule 3's "the issuer refreshes": the leader re-broadcasts authoritative
            // state rather than waiting to be asked, which is strictly better than a STATE_REQUEST
            // round trip and needs no message type §3 does not already list.
            if (stillCurrent(generation)) session.playback.send(snapshotOf(_queueState.value))
            return
        }
        applyLeaderMutation(mutation)
    }

    @Suppress("ReturnCount") // one early-out per ordering decision reads clearer than nesting
    private suspend fun onInboundCommand(
        message: PlaybackMessage,
        generation: Long,
    ) {
        val header = headerOf(message) ?: return
        val currentRole = role ?: return
        when (CommandOrderGate.decide(currentRole, lastAppliedSeq, header.commandSeq)) {
            CommandOrderDecision.DUPLICATE -> {
                _diagnostics.update { it.copy(duplicateCommandCount = it.duplicateCommandCount + 1) }
                return
            }
            CommandOrderDecision.STALE -> {
                _diagnostics.update { it.copy(staleCommandCount = it.staleCommandCount + 1) }
                return
            }
            CommandOrderDecision.ROLE_VIOLATION -> {
                _diagnostics.update { it.copy(roleViolationCount = it.roleViolationCount + 1) }
                return
            }
            CommandOrderDecision.INTENT -> {
                servePlaybackIntent(message, header, generation)
                return
            }
            CommandOrderDecision.ACCEPT -> Unit
        }
        if (header.queueRevision != _queueState.value.revision) {
            _diagnostics.update { it.copy(staleRevisionCount = it.staleRevisionCount + 1) }
            return
        }
        syncEnabled = true
        commandMutex.withLock { lastAppliedSeq = header.commandSeq }
        _diagnostics.update { it.copy(lastAppliedCommandSeq = header.commandSeq) }
        val estimate = readyEstimate() ?: return
        applyAuthoritative(message, generation, estimate)
    }

    /**
     * A follower's playback intent, arriving at the leader (ADR-024 §3). The leader validates,
     * stamps and broadcasts — one serialisation point, so two users pressing different buttons at
     * the same instant resolve by the leader's arrival order rather than by comparing timestamps.
     */
    @Suppress("ReturnCount") // one early-out per rule an intent must satisfy before it is stamped
    private suspend fun servePlaybackIntent(
        message: PlaybackMessage,
        header: PlaybackCommandHeader,
        generation: Long,
    ) {
        if (header.queueRevision != _queueState.value.revision) {
            _diagnostics.update { it.copy(staleRevisionCount = it.staleRevisionCount + 1) }
            if (stillCurrent(generation)) session.playback.send(snapshotOf(_queueState.value))
            return
        }
        if (message is PlaybackMessage.Play && !gateContent(message.trackHash)) return
        if (!stillCurrent(generation)) return
        syncEnabled = true
        issue { stamped -> restamp(message, stamped) }
    }

    /** The same message, carrying the leader's authoritative header instead of the intent's. */
    private fun restamp(
        message: PlaybackMessage,
        header: PlaybackCommandHeader,
    ): PlaybackMessage =
        when (message) {
            is PlaybackMessage.Play -> message.copy(header = header)
            is PlaybackMessage.Pause -> message.copy(header = header)
            is PlaybackMessage.Resume -> message.copy(header = header)
            is PlaybackMessage.Seek -> message.copy(header = header)
            is PlaybackMessage.Next -> message.copy(header = header)
            is PlaybackMessage.Previous -> message.copy(header = header)
            else -> message
        }

    private fun headerOf(message: PlaybackMessage): PlaybackCommandHeader? =
        when (message) {
            is PlaybackMessage.Play -> message.header
            is PlaybackMessage.Pause -> message.header
            is PlaybackMessage.Resume -> message.header
            is PlaybackMessage.Seek -> message.header
            is PlaybackMessage.Next -> message.header
            is PlaybackMessage.Previous -> message.header
            else -> null
        }

    // --- applying -------------------------------------------------------------------------------

    private suspend fun applyAuthoritative(
        message: PlaybackMessage,
        generation: Long,
        estimate: SessionClockEstimate,
    ) {
        when (message) {
            is PlaybackMessage.Play ->
                applyPlay(message.header, message.trackHash, message.queueItemId, message.positionMs, generation, estimate)
            is PlaybackMessage.Pause ->
                applyTransport(message.header, generation, estimate, playing = false, positionMs = message.positionMs)
            is PlaybackMessage.Resume ->
                applyTransport(message.header, generation, estimate, playing = true, positionMs = message.positionMs)
            is PlaybackMessage.Seek -> applySeek(message.header, message.targetPositionMs, generation, estimate)
            is PlaybackMessage.Next -> applyStep(message.header, delta = 1, generation = generation, estimate = estimate)
            is PlaybackMessage.Previous -> applyStep(message.header, delta = -1, generation = generation, estimate = estimate)
            else -> Unit
        }
    }

    /**
     * ARCHITECTURE §7.2 steps 1-6: resolve, pre-roll the decoder while there is still time, then
     * start at the deadline. The resolve and the pre-roll are both real suspension points, so the
     * session generation and the epoch token are re-proved after each.
     */
    @Suppress("ReturnCount") // one early-out per session/epoch re-proof after a suspension
    private suspend fun applyPlay(
        header: PlaybackCommandHeader,
        trackHash: ContentHash,
        queueItemId: String,
        positionMs: Long,
        generation: Long,
        estimate: SessionClockEstimate,
    ) {
        val playable = content.resolve(trackHash)
        if (!stillCurrent(generation)) return
        if (playable == null) {
            // PROTOCOL §5 rule 4: do not start, request the transfer, let the leader reschedule.
            _diagnostics.update { it.copy(syncState = SyncState.WAITING_FOR_CONTENT, currentTrackHash = trackHash) }
            content.requestTransfer(trackHash)
            return
        }
        val token = playbackFence.begin()
        currentEpochToken = token
        driftState = DriftController.reset()
        _queueState.update { SharedQueue.select(it, queueItemId) }
        timeline = PlaybackTimeline(trackHash, queueItemId, positionMs, header.effectiveAtSessionUs, playing = true, generation = token)
        // A new epoch retires a previous sync failure outright: fresh timeline, fresh drift state,
        // fresh seek budget. Nothing from the failed epoch is still in force to keep reporting.
        _diagnostics.update {
            it.copy(
                currentTrackHash = trackHash,
                hardSeekCount = 0,
                lastCorrection = SyncCorrection.NONE,
                syncState = if (it.syncState == SyncState.SYNC_FAILED) SyncState.SCHEDULED else it.syncState,
            )
        }
        player.prepare(playable, positionMs)
        if (!stillCurrent(generation) || !playbackFence.isCurrent(token)) return
        scheduleAt(header.effectiveAtSessionUs, estimate, generation, token) { player.start() }
    }

    private fun applyTransport(
        header: PlaybackCommandHeader,
        generation: Long,
        estimate: SessionClockEstimate,
        playing: Boolean,
        positionMs: Long,
    ) {
        val token = currentEpochToken
        timeline = timeline?.copy(anchorPositionMs = positionMs, anchorSessionUs = header.effectiveAtSessionUs, playing = playing)
        scheduleAt(header.effectiveAtSessionUs, estimate, generation, token) {
            if (playing) {
                player.seek(positionMs)
                player.start()
            } else {
                player.pause()
                player.seek(positionMs)
            }
        }
    }

    private fun applySeek(
        header: PlaybackCommandHeader,
        targetPositionMs: Long,
        generation: Long,
        estimate: SessionClockEstimate,
    ) {
        val token = currentEpochToken
        timeline = timeline?.copy(anchorPositionMs = targetPositionMs, anchorSessionUs = header.effectiveAtSessionUs)
        scheduleAt(header.effectiveAtSessionUs, estimate, generation, token) { player.seek(targetPositionMs) }
    }

    /**
     * PROTOCOL §5's `NEXT`/`PREVIOUS`, resolved against the **shared** queue (brief §25). Both peers
     * hold identical `SharedQueueState` at the revision the command names, so both resolve the same
     * item without either consulting its own local queue.
     */
    private suspend fun applyStep(
        header: PlaybackCommandHeader,
        delta: Int,
        generation: Long,
        estimate: SessionClockEstimate,
    ) {
        val step = SharedQueue.step(_queueState.value, delta)
        _queueState.value = step.state
        publishQueue()
        val selected = step.selected
        if (selected == null) {
            val token = playbackFence.begin()
            currentEpochToken = token
            timeline = null
            scheduleAt(header.effectiveAtSessionUs, estimate, generation, token) { player.stop() }
            return
        }
        if (!step.moved) return
        applyPlay(header, selected.trackHash, selected.queueItemId, positionMs = 0, generation = generation, estimate = estimate)
    }

    // --- scheduling -----------------------------------------------------------------------------

    /**
     * PROTOCOL §5 rule 2, exactly: a deadline still ahead is waited for on this device's own
     * monotonic clock; a deadline already past is applied **immediately** and its lateness counted.
     * Never skipped, never scheduled backwards.
     */
    private fun scheduleAt(
        effectiveAtSessionUs: Long,
        estimate: SessionClockEstimate,
        generation: Long,
        token: Long,
        action: suspend () -> Unit,
    ) {
        when (val decision = ScheduledCommand.decide(effectiveAtSessionUs, monotonicNowUs(), estimate.offsetToLeaderUs)) {
            is ScheduledCommandDecision.ApplyImmediately -> {
                _diagnostics.update {
                    it.copy(lateCommandCount = it.lateCommandCount + 1, lastScheduleErrorUs = decision.latenessUs)
                }
                scope.launch {
                    runIfCurrent(generation, token) {
                        action()
                        markSynced()
                    }
                }
            }
            is ScheduledCommandDecision.Schedule -> {
                _diagnostics.update { it.copy(syncState = SyncState.SCHEDULED) }
                scope.launch {
                    sleeper.sleepUntil(decision.atLocalMonoUs)
                    // The software scheduling error, measured rather than assumed. It says nothing
                    // about audible alignment: the decoder, the mixer and two Bluetooth hops all sit
                    // between this instant and a listener's ear (brief §23/§66).
                    _diagnostics.update { it.copy(lastScheduleErrorUs = monotonicNowUs() - decision.atLocalMonoUs) }
                    runIfCurrent(generation, token) {
                        action()
                        markSynced()
                    }
                }
            }
        }
    }

    /**
     * Runs [action] only if both the session and the playback epoch that authorised it are still in
     * force. Deliberately writes **no** state of its own: a correction and a scheduled command both
     * need this guard, and only one of them means "we are now synchronised".
     */
    private suspend fun runIfCurrent(
        generation: Long,
        token: Long,
        action: suspend () -> Unit,
    ) {
        if (!stillCurrent(generation) || !playbackFence.isCurrent(token)) return
        action()
    }

    /**
     * A scheduled authoritative command took effect, so this device is tracking the timeline.
     *
     * It will **not** overwrite [SyncState.SYNC_FAILED]: ARCHITECTURE §7.3's fourth tier means
     * correction has given up, and a subsequent `PAUSE` landing on time does not make that untrue.
     * Only a new playback epoch clears it, which [applyPlay] does explicitly — a fresh `PLAY` is a
     * fresh timeline with a fresh drift state, so there is genuinely nothing left in force.
     */
    private fun markSynced() {
        _diagnostics.update { if (it.syncState == SyncState.SYNC_FAILED) it else it.copy(syncState = SyncState.SYNCED) }
    }

    // --- position reporting and drift -----------------------------------------------------------

    private suspend fun tickLoop(generation: Long) {
        while (true) {
            sleeper.sleepUntil(monotonicNowUs() + POSITION_REPORT_INTERVAL_US)
            if (!stillCurrent(generation)) return
            tickOnce(generation)
        }
    }

    /**
     * One PROTOCOL §5 cadence tick: report our own position, then correct **our own** drift against
     * the authoritative timeline.
     *
     * Brief §33: drift is `actual local position - expected position at the current session time`,
     * never one phone's reported position minus the other's — those two numbers are sampled at
     * different session instants and separated by a network delay, so their difference is not a
     * drift. The peer's report produces the *observed peer drift* against the same timeline, which
     * is FR-023 diagnostics, not a correction input.
     */
    @Suppress("ReturnCount") // one early-out per condition that makes a tick meaningless
    private suspend fun tickOnce(generation: Long) {
        val active = timeline ?: return
        val token = currentEpochToken
        val estimate = estimate()
        if (estimate == null || !estimate.ready) {
            // brief §41: a dubious clock stops correction, and local playback simply continues.
            _diagnostics.update { it.copy(syncState = SyncState.CLOCK_UNREADY, clockReady = false) }
            return
        }
        val nowSessionUs = sessionNowUs(estimate)
        val state = player.playerState.value
        val durationMs = state.durationMs.takeIf { it > 0 }
        session.playback.send(
            PlaybackMessage.PositionReport(active.trackHash, state.positionMs.coerceAtLeast(0), nowSessionUs, state.playing, state.rate),
        )
        if (!stillCurrent(generation) || !playbackFence.isCurrent(token)) return
        val transitioning = routeTransitioning()
        val outcome =
            DriftController.evaluate(
                driftState,
                DriftInput(
                    driftMs = active.driftMs(state.positionMs, nowSessionUs, durationMs),
                    nowSessionUs = nowSessionUs,
                    expectedPositionMs = active.expectedPositionMs(nowSessionUs, durationMs),
                    playing = active.playing && state.playing,
                    routeTransitioning = transitioning,
                ),
            )
        driftState = outcome.state
        _diagnostics.update {
            it.copy(
                clockReady = true,
                clockOffsetUs = estimate.offsetToLeaderUs,
                rttP95Us = estimate.rttP95Us,
                leadUs = estimate.leadUs,
                localDriftMs = active.driftMs(state.positionMs, nowSessionUs, durationMs),
                routeTransitioning = transitioning,
            )
        }
        applyCorrection(outcome.action, generation, token)
        // Last, so the counter means "this tick finished" rather than "this tick began".
        _diagnostics.update { it.copy(correctionTickCount = it.correctionTickCount + 1) }
    }

    private suspend fun applyCorrection(
        action: DriftAction,
        generation: Long,
        token: Long,
    ) {
        when (action) {
            DriftAction.None -> Unit
            is DriftAction.Nudge ->
                runIfCurrent(generation, token) {
                    player.setRate(action.rate)
                    _diagnostics.update { it.copy(lastCorrection = SyncCorrection.NUDGE, playbackRate = action.rate) }
                }
            DriftAction.RestoreRate ->
                runIfCurrent(generation, token) {
                    restoreRate()
                    _diagnostics.update { it.copy(lastCorrection = SyncCorrection.RESTORE_RATE) }
                }
            is DriftAction.HardSeek ->
                runIfCurrent(generation, token) {
                    player.seek(action.positionMs)
                    _diagnostics.update {
                        it.copy(lastCorrection = SyncCorrection.HARD_SEEK, hardSeekCount = it.hardSeekCount + 1)
                    }
                    emitPlaybackState()
                }
            DriftAction.DeclareSyncFailure ->
                runIfCurrent(generation, token) {
                    // ARCHITECTURE §7.3 tier four and FR-025: stop correcting, restore exactly 1.0,
                    // surface it — and leave local music playing.
                    restoreRate()
                    _diagnostics.update { it.copy(lastCorrection = SyncCorrection.SYNC_FAILED, syncState = SyncState.SYNC_FAILED) }
                    emitPlaybackState()
                }
        }
    }

    /** PROTOCOL §5: the leader's authoritative snapshot after a correction. Never an incremental update. */
    private suspend fun emitPlaybackState() {
        if (role != PlaybackRole.LEADER) return
        val active = timeline
        val state = player.playerState.value
        val estimate = estimate() ?: return
        session.playback.send(
            PlaybackMessage.PlaybackStateSnapshot(
                commandSeq = lastAppliedSeq ?: (nextSeq - 1).coerceAtLeast(0),
                queueRevision = _queueState.value.revision,
                trackHash = active?.trackHash,
                queueItemId = active?.queueItemId,
                positionMs = state.positionMs.coerceAtLeast(0),
                playing = state.playing,
                atSessionUs = sessionNowUs(estimate),
            ),
        )
    }

    /**
     * The peer's `POSITION_REPORT`. Bound to the current playback epoch by **both** its
     * `track_hash` and its `at_session_us`: a report from a previous play of the *same* track
     * carries a session instant before this epoch's anchor, which is what makes `content_hash`
     * alone insufficient (brief §32) without adding a generation field to the wire.
     *
     * It is never a command and can never outrank one — the only thing it produces is a number on
     * the diagnostics screen.
     */
    @Suppress("ReturnCount") // one early-out per epoch-binding check
    private fun onPeerPositionReport(report: PlaybackMessage.PositionReport) {
        val active = timeline ?: return
        if (report.trackHash != active.trackHash) return
        if (report.atSessionUs < active.anchorSessionUs) return
        val expected =
            active.expectedPositionMs(
                report.atSessionUs,
                player.playerState.value.durationMs
                    .takeIf { it > 0 },
            )
        _diagnostics.update { it.copy(peerDriftMs = report.positionMs - expected) }
    }

    /**
     * PROTOCOL §5's reconciliation anchor. A follower adopts the leader's `command_seq` so ordering
     * continues from the authoritative value, and re-anchors its timeline when the leader's snapshot
     * describes the track it is already playing. It deliberately does **not** start anything: a
     * change of track is a `PLAY`, which the leader sends separately.
     */
    @Suppress("ReturnCount") // one early-out per reconciliation precondition
    private fun onPeerPlaybackState(snapshot: PlaybackMessage.PlaybackStateSnapshot) {
        if (role != PlaybackRole.FOLLOWER) return
        val current = lastAppliedSeq
        if (current == null || snapshot.commandSeq > current) {
            lastAppliedSeq = snapshot.commandSeq
            _diagnostics.update { it.copy(lastAppliedCommandSeq = snapshot.commandSeq) }
        }
        val active = timeline ?: return
        if (snapshot.trackHash != active.trackHash) return
        timeline =
            active.copy(
                anchorPositionMs = snapshot.positionMs,
                anchorSessionUs = snapshot.atSessionUs,
                playing = snapshot.playing,
            )
        driftState = DriftController.reset()
    }

    private fun publishQueue() {
        val state = _queueState.value
        _diagnostics.update { it.copy(queueRevision = state.revision, queueSize = state.items.size) }
    }

    private companion object {
        val POSITION_REPORT_INTERVAL_US = PlaybackBounds.POSITION_REPORT_INTERVAL_MS * 1_000
        const val INBOUND_CAPACITY = 256
    }
}
