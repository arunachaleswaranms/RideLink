package com.ridelink.app.sync

import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.PeerId
import com.ridelink.core.playback.CommandAdmission
import com.ridelink.core.playback.CommandOrderDecision
import com.ridelink.core.playback.CommandOrderGate
import com.ridelink.core.playback.DriftAction
import com.ridelink.core.playback.DriftController
import com.ridelink.core.playback.DriftInput
import com.ridelink.core.playback.DriftState
import com.ridelink.core.playback.IngressAdmission
import com.ridelink.core.playback.PendingCommandGate
import com.ridelink.core.playback.PendingPlayDecision
import com.ridelink.core.playback.PendingPlayGate
import com.ridelink.core.playback.Phase5FrameKind
import com.ridelink.core.playback.Phase5GateBounds
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
 * [com.ridelink.core.sync.SessionClock] — and, since ADR-024 Amendment A1, ingress admission by
 * [com.ridelink.core.playback.Phase5Ingress], clock-readiness admission by [PendingCommandGate] and
 * retained-Play readiness by [PendingPlayGate]. That is ADR-019's direct lesson — a distributed rule
 * that lives inside a coordinator is a rule no vector can pin — and it is why this class is wiring
 * and lifetime rather than policy.
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
 *
 * ## What ADR-024 Amendment A1 (the Phase 5 closure audit) changed here
 *
 * - **One outbound serialisation owner** ([outbound]). Allocating a `command_seq` or a
 *   `queue_revision` and handing the resulting frame to the transport now happen inside the *same*
 *   [commandMutex] critical section, and one consumer writes them in that order. Before this, both
 *   allocations were locked and both sends were not, so a `PLAY` stamped for revision *n* could
 *   reach the wire ahead of the `QUEUE_SNAPSHOT` that created revision *n* — and the peer would
 *   refuse the valid command for a revision it had not been told about yet (Finding B).
 * - **A lossless ingress** ([inbound]). See [Phase5FrameQueue] (Finding C).
 * - **Received is not applied** ([lastReceivedSeq] versus [lastAppliedSeq]) (Finding D).
 * - **One press of Play survives its waits** ([pendingPlay]) (Findings A and E).
 * - **A superseded correction has no side effects at all** ([owns]) (Finding F).
 */
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
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
    /** Injectable purely so a test can force the ingress edge at 1 or 2 rather than racing 256 frames. */
    inboundCapacity: Int = Phase5GateBounds.DEFAULT_INBOUND_CAPACITY,
    /** Injectable for the same reason: how many commands may wait for a trustworthy clock. */
    private val deferredCommandCapacity: Int = Phase5GateBounds.DEFAULT_DEFERRED_COMMAND_CAPACITY,
) {
    private val _diagnostics = MutableStateFlow(SyncPlaybackDiagnostics())
    val diagnostics: StateFlow<SyncPlaybackDiagnostics> = _diagnostics.asStateFlow()

    private val _queueState = MutableStateFlow(SharedQueueState())

    /** The authoritative replicated queue. `LocalQueue` is untouched and still owns local-only rides. */
    val queueState: StateFlow<SharedQueueState> = _queueState.asStateFlow()

    /**
     * Serialises everything that reads-then-writes command/queue state **and hands the resulting
     * frame to the ordered outbound queue in the same breath**: the leader's `command_seq`
     * allocation, its queue mutation and revision bump, and a receiver's order gate.
     *
     * Amendment A1 Finding B: the send used to sit *outside* this lock, so two frames whose
     * semantic order this lock had just decided raced each other to the socket. Enqueueing inside
     * the lock is what makes "the leader's semantic order is the wire-visible order" a property
     * rather than a hope — [ControlSocket][com.ridelink.network.control.ControlSocket]'s own write
     * lock serialises *bytes*, which is a different and insufficient guarantee.
     *
     * Nothing suspending is ever done under it: [enqueueOutbound] is a `trySend`, and every real
     * suspension (content resolution, the decoder pre-roll, the socket write) happens outside.
     */
    private val commandMutex = Mutex()

    private val playbackFence = OperationFence()

    /**
     * The fence for the *user's* Play request, distinct from [playbackFence]: a request that is
     * still waiting for a queue revision or for a transfer has no playback epoch yet, and a newer
     * request must supersede it without disturbing whatever is currently playing (brief §18).
     */
    private val playRequestFence = OperationFence()

    @Volatile
    private var role: PlaybackRole? = null

    @Volatile
    private var syncEnabled = false

    /**
     * The highest `command_seq` this device has taken responsibility for — *the* input to
     * [CommandOrderGate]. Amendment A1 Finding D: it advances when a command is accepted, whether
     * that command is applied immediately or held for a trustworthy clock, so a replay of a held
     * command is correctly a duplicate rather than a second copy.
     */
    private var lastReceivedSeq: Long? = null

    /** The highest `command_seq` actually applied. What `PLAYBACK_STATE` reports, and only that. */
    private var lastAppliedSeq: Long? = null
    private var nextSeq: Long = PlaybackBounds.FIRST_COMMAND_SEQ
    private var timeline: PlaybackTimeline? = null
    private var driftState: DriftState = DriftController.reset()
    private var tickJob: Job? = null

    /** Amendment A1 Finding D: authoritative commands held, in order, until the clock is trustworthy. */
    private val deferredCommands = ArrayDeque<DeferredCommand>()
    private var deferredDrainJob: Job? = null

    /**
     * The tail of the **scheduled-action chain** (Amendment A1 Finding G).
     *
     * Every accepted command's audible effect is armed by [scheduleAt] and then waits — for its own
     * deadline, or not at all when that deadline has already passed. Arming one coroutine per command
     * preserves only the order in which they are *started*: each action then suspends inside the
     * player, and the next one runs inside that suspension. Two commands whose deadlines have both
     * passed — `PAUSE(n)` and `RESUME(n+1)`, a pair the leader stamps microseconds apart — could
     * therefore take effect in either order, which is the same defect the *inbound* handoff already
     * had and the exact opposite of what `command_seq` is for.
     *
     * Each armed action now joins the previous one before doing anything. Authoritative deadlines
     * increase with `command_seq` (the leader stamps `session_now + LEAD`), so waiting for the
     * previous action costs nothing and the chain's order *is* the authoritative order. A superseded
     * action fails its ownership proof and returns at once, so it never holds the chain up.
     */
    private var scheduledChain: Job? = null

    /** Amendment A1 Findings A/E: the one retained synchronised-Play request. */
    private var pendingPlay: PendingPlay? = null

    /**
     * The [PendingPlay.token] a Phase 4 transfer has already been requested for. Keyed on the
     * *token* rather than the hash so a superseded request's transfer is never mistaken for the
     * current one's, and so re-evaluating the same request (a queue snapshot, then an availability
     * notification) asks Phase 4 exactly once (brief §20 case 1).
     */
    private var transferRequestedForToken: Long? = null

    /**
     * Amendment A1 Finding C: set on a **follower** when the ingress refused a frame it could not
     * supersede, or when the deferred buffer overflowed. While either is set, no incremental
     * command is applied — only authoritative full state is.
     *
     * `@Volatile` because [enqueue] writes them from the control read loop while the drain consumer
     * reads them on the coordinator's own dispatcher.
     */
    @Volatile
    private var playbackDesynchronized = false

    @Volatile
    private var queueDesynchronized = false

    /**
     * The [OperationFence] token of the playback epoch currently in force. A `PAUSE`/`SEEK` schedules
     * against *this* token rather than beginning a new epoch, so it is superseded automatically by a
     * later `PLAY`/`NEXT` without needing to know one happened.
     */
    @Volatile
    private var currentEpochToken: Long = -1L

    /**
     * The bounded, **lossless**, arrival-ordered handoff from the control read loop.
     *
     * Both sinks enqueue rather than launch. A coroutine per frame preserves only the order in which
     * coroutines are *started*: `onPlaybackMessage` suspends (content resolution, the decoder
     * pre-roll), so frame N+1 could overtake frame N inside the suspension and a follower would drop
     * the overtaken command as stale — `CommandOrderGate` doing exactly its job on input that
     * reached it out of order. One queue, one consumer, arrival order preserved. Found by
     * stress-running this phase's iOS suites, and fixed identically on both platforms.
     *
     * Amendment A1 Finding C replaced the `DROP_OLDEST` channel this originally was: see
     * [Phase5FrameQueue] for why a lossy queue behind reliable ordered TCP is a defect and why
     * its eviction counter could never even fire.
     */
    private val inbound =
        Phase5FrameQueue<Inbound>(
            capacity = inboundCapacity,
            kindOf = Inbound::kind,
            coalesceKeyOf = Inbound::coalesceKey,
        )

    /**
     * The one ordered outbound path (Amendment A1 Finding B). Every Phase 5 frame this device sends
     * — authoritative command, follower intent, queue snapshot, position report, playback state —
     * is enqueued here and written by the single consumer below, in enqueue order.
     *
     * Bounded and **not** lossy: `trySend` failing is counted as
     * [SyncPlaybackDiagnostics.outboundOverflowCount]. Its producer is this device, so a full queue
     * means the control socket is wedged rather than that a peer is misbehaving — which is also why
     * this is strictly better than the previous shape, where each send blocked its own coroutine and
     * an unbounded number of them could pile up behind a stalled socket.
     */
    private val outbound =
        Phase5FrameQueue<Outbound>(
            capacity = OUTBOUND_CAPACITY,
            // Never coalesced: a frame this device has already stamped may not be superseded.
            kindOf = { Phase5FrameKind.COMMAND },
            coalesceKeyOf = { null },
        )

    init {
        session.playback.playbackSink =
            PlaybackSink { message, generation -> enqueue(Inbound.Playback(message, generation)) }
        session.playback.queueSink =
            QueueSink { message, generation -> enqueue(Inbound.Queue(message, generation)) }
        content.observeAvailability { scope.launch { resolvePendingPlay() } }
        scope.launch { drainInbound() }
        scope.launch { drainOutbound() }
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
        abstract val kind: Phase5FrameKind

        /** The latest-wins family this frame belongs to, or null when it is an authoritative command. */
        abstract val coalesceKey: String?

        data class Playback(
            val message: PlaybackMessage,
            val generation: Long,
        ) : Inbound() {
            override val kind: Phase5FrameKind
                get() =
                    when (message) {
                        is PlaybackMessage.PositionReport, is PlaybackMessage.PlaybackStateSnapshot ->
                            Phase5FrameKind.LATEST_WINS
                        else -> Phase5FrameKind.COMMAND
                    }

            override val coalesceKey: String?
                get() =
                    when (message) {
                        is PlaybackMessage.PositionReport -> "POSITION_REPORT"
                        is PlaybackMessage.PlaybackStateSnapshot -> "PLAYBACK_STATE"
                        else -> null
                    }
        }

        data class Queue(
            val message: QueueMessage,
            val generation: Long,
        ) : Inbound() {
            override val kind: Phase5FrameKind
                get() = if (message is QueueMessage.Snapshot) Phase5FrameKind.LATEST_WINS else Phase5FrameKind.COMMAND

            override val coalesceKey: String?
                get() = if (message is QueueMessage.Snapshot) "QUEUE_SNAPSHOT" else null
        }
    }

    /** One outbound Phase 5 frame, waiting its turn on the one ordered outbound path. */
    private sealed class Outbound {
        data class Playback(
            val message: PlaybackMessage,
        ) : Outbound()

        data class Queue(
            val message: QueueMessage,
        ) : Outbound()
    }

    /** An authoritative command accepted for ordering but not yet applied, because the clock is not trusted. */
    private data class DeferredCommand(
        val message: PlaybackMessage,
        val generation: Long,
    )

    /**
     * One press of Play, retained across the waits it has to survive (Amendment A1 Findings A/E).
     *
     * Fenced by [token] rather than keyed on [contentHash]: the same track can legitimately be
     * asked for again in a later epoch, so a hash would let a superseded request resurrect when a
     * transfer it no longer owns completes (brief §18/§32).
     */
    private data class PendingPlay(
        val token: Long,
        val generation: Long,
        val contentHash: ContentHash,
        val queueItemId: String,
        /** The intent's own `position_ms`, so serving a follower's Play never silently rewinds it to 0. */
        val positionMs: Long,
    )

    /**
     * Called from the control read loop. It must not block and must not do work, so it does exactly
     * one thing: hand the frame to the bounded queue. The *consequences* of a refusal are observed
     * by the consumer, at the top of its next iteration, which is where they belong — see
     * [Phase5FrameQueue.stats].
     */
    private fun enqueue(item: Inbound) {
        inbound.offer(item)
    }

    private var reportedInboundOverflows = 0
    private var reportedInboundCoalesces = 0

    /**
     * Reconciles the queue's admission statistics into diagnostics, and latches the halt if a frame
     * was refused. Called once per drained frame, **before** that frame is dispatched, so a refusal
     * can never be followed by an applied command.
     */
    private fun observeIngressStats() {
        val stats = inbound.stats
        val newOverflows = stats.overflowCount - reportedInboundOverflows
        val newCoalesces = stats.coalescedCount - reportedInboundCoalesces
        if (newOverflows == 0 && newCoalesces == 0) return
        reportedInboundOverflows = stats.overflowCount
        reportedInboundCoalesces = stats.coalescedCount
        _diagnostics.update {
            it.copy(
                inboundOverflowCount = it.inboundOverflowCount + newOverflows,
                inboundCoalescedCount = it.inboundCoalescedCount + newCoalesces,
            )
        }
        if (newOverflows > 0) onIngressOverflow()
    }

    /**
     * PROTOCOL §5/§9's frames arrived faster than they could be considered, and the queue held
     * nothing that could be superseded. **The frame is refused, not evicted** — the difference
     * matters, because it means the frames already queued still apply in order and the only thing
     * lost is one we never claimed to have taken.
     *
     * On a **follower** that is a genuine loss of incremental authority, so incremental state stops
     * being trusted until authoritative full state arrives (PROTOCOL §5's `PLAYBACK_STATE`, §9's
     * `QUEUE_SNAPSHOT`) or the session ends.
     *
     * On the **leader** it is not: the only incremental frames a leader accepts are *intents*, which
     * it stamps rather than applies, so its own authoritative state cannot have become incoherent.
     * What was lost is a button press, so the leader re-broadcasts its authoritative state (which is
     * what unsticks a follower whose revision has drifted) and does not halt.
     */
    private fun onIngressOverflow() {
        if (role == PlaybackRole.LEADER) {
            scope.launch { rebroadcastAuthoritativeState() }
            return
        }
        playbackDesynchronized = true
        queueDesynchronized = true
        publishDesynchronized()
    }

    /**
     * Publishes the latch. While it is set, [SyncState.DESYNCHRONIZED] is what the user sees; once it
     * clears, the displayed state is left for the next real transition to set — the restore's
     * [scheduleAt] ([SyncState.SCHEDULED]), [markSynced] ([SyncState.SYNCED]) or [readyEstimate]
     * ([SyncState.CLOCK_UNREADY]) — rather than being guessed at here.
     */
    private fun publishDesynchronized() {
        val desynchronized = playbackDesynchronized || queueDesynchronized
        _diagnostics.update {
            it.copy(
                ingressDesynchronized = desynchronized,
                syncState = if (desynchronized) SyncState.DESYNCHRONIZED else it.syncState,
            )
        }
    }

    /**
     * Drains [inbound], one frame at a time, in arrival order. One consumer, so frame N+1 is never
     * handled before frame N — see the queue's own doc comment for why that is a correctness
     * property.
     */
    private suspend fun drainInbound() {
        while (true) {
            val item = inbound.take() ?: return
            observeIngressStats()
            when (item) {
                is Inbound.Playback -> onPlaybackMessage(item.message, item.generation)
                is Inbound.Queue -> onQueueMessage(item.message, item.generation)
            }
            _diagnostics.update { it.copy(inboundProcessedCount = it.inboundProcessedCount + 1) }
        }
    }

    private fun enqueueOutbound(frame: Outbound) {
        if (outbound.offer(frame) == IngressAdmission.OVERFLOW) {
            _diagnostics.update { it.copy(outboundOverflowCount = it.outboundOverflowCount + 1) }
            return
        }
        _diagnostics.update { it.copy(outboundEnqueuedCount = it.outboundEnqueuedCount + 1) }
    }

    /** The single writer. Enqueue order is wire order, which is the whole of Finding B's invariant. */
    private suspend fun drainOutbound() {
        while (true) {
            val frame = outbound.take() ?: return
            when (frame) {
                is Outbound.Playback -> session.playback.send(frame.message)
                is Outbound.Queue -> session.playback.send(frame.message)
            }
            _diagnostics.update { it.copy(outboundSentCount = it.outboundSentCount + 1) }
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
        deferredDrainJob?.cancel()
        deferredDrainJob = null
        // Nothing new joins the previous session's chain: ordering across a session boundary is
        // meaningless, and every link still in flight is already inert by its ownership proof.
        scheduledChain = null
        // Supersede rather than begin: nothing is current until a new epoch actually starts, so a
        // timer or a report still in flight from the previous session can match no token at all.
        playbackFence.supersede()
        // Amendment A1 Finding E: a session boundary cancels the retained Play outright. A transfer
        // that completes afterwards must never resurrect it — the token it held is already stale.
        playRequestFence.supersede()
        val cancelled = if (pendingPlay != null) 1 else 0
        pendingPlay = null
        deferredCommands.clear()
        lastReceivedSeq = null
        lastAppliedSeq = null
        nextSeq = PlaybackBounds.FIRST_COMMAND_SEQ
        timeline = null
        driftState = DriftController.reset()
        playbackDesynchronized = false
        queueDesynchronized = false
        _queueState.value = SharedQueueState()
        scope.launch { restoreRate() }
        _diagnostics.update {
            it.copy(
                syncState = SyncState.INACTIVE,
                lastAppliedCommandSeq = null,
                lastReceivedCommandSeq = null,
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
                deferredCommandCount = 0,
                ingressDesynchronized = false,
                cancelledPendingPlayCount = it.cancelledPendingPlayCount + cancelled,
                sessionGeneration = session.currentAuthGeneration,
            )
        }
    }

    /** ADR-023 §3's guard, re-proved at every transition rather than once at handler entry. */
    private fun stillCurrent(generation: Long): Boolean = generation == session.currentAuthGeneration && role != null

    /**
     * Both halves of "this work is still authorised": the session that dispatched it, and the
     * playback epoch that armed it. Amendment A1 Finding F made this a named predicate because it
     * has to be re-proved *after* every suspension that precedes an externally visible effect, not
     * only before the first one.
     */
    private fun owns(
        generation: Long,
        token: Long,
    ): Boolean = stillCurrent(generation) && playbackFence.isCurrent(token)

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
            _diagnostics.update {
                if (it.ingressDesynchronized) it else it.copy(syncState = SyncState.CLOCK_UNREADY, clockReady = false)
            }
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
     *
     * Amendment A1 Finding B: the allocation and the hand-off to the ordered outbound queue are one
     * critical section, so nothing can be stamped against a revision that reaches the peer later.
     */
    @Suppress("ReturnCount") // one early-out per role and per readiness gate
    private suspend fun issue(build: (PlaybackCommandHeader) -> PlaybackMessage) {
        val currentRole = role ?: return
        val generation = session.currentAuthGeneration
        if (currentRole == PlaybackRole.FOLLOWER) {
            commandMutex.withLock {
                if (!stillCurrent(generation)) return
                val header =
                    PlaybackCommandHeader(
                        PlaybackBounds.UNASSIGNED_COMMAND_SEQ,
                        0L,
                        localPeerId,
                        _queueState.value.revision,
                    )
                enqueueOutbound(Outbound.Playback(build(header)))
            }
            return
        }
        val estimate = readyEstimate() ?: return
        val message =
            commandMutex.withLock {
                if (!stillCurrent(generation)) return
                val seq = nextSeq
                nextSeq += 1
                val header =
                    PlaybackCommandHeader(
                        seq,
                        sessionNowUs(estimate) + estimate.leadUs,
                        localPeerId,
                        _queueState.value.revision,
                    )
                val built = build(header)
                enqueueOutbound(Outbound.Playback(built))
                // The leader is the assigner, so its own command cannot be lost between accepting
                // and applying it: there is no inbound path that could replay it (an authoritative
                // command arriving at the leader is a role violation), so received and applied move
                // together here. Finding D's split matters on the receiving side.
                lastReceivedSeq = seq
                lastAppliedSeq = seq
                built
            }
        val commandSeq = headerOf(message)?.commandSeq
        _diagnostics.update {
            it.copy(nextCommandSeq = nextSeq, lastAppliedCommandSeq = commandSeq, lastReceivedCommandSeq = commandSeq)
        }
        if (!stillCurrent(generation)) return
        // The leader applies its own command exactly as the follower will: same header, same
        // effective instant, same code path. There is no "issuer applies immediately" shortcut,
        // because that shortcut is precisely how two phones end up on two timelines.
        applyAuthoritative(message, generation, estimate)
    }

    // --- user-facing actions (also the system media controls' path, brief §39) ------------------

    /**
     * Starts synchronised playback of [contentHash] — **one press, one eventual authoritative
     * `PLAY`** (Amendment A1 Findings A and E).
     *
     * The request is *retained*, not attempted-and-forgotten. Two things can legitimately not be
     * ready yet, and before this amendment each of them silently cost the user a second press:
     *
     * - the track may not be in the **authoritative** queue yet, and a `PLAY` stamped against a
     *   revision the leader has already moved past is refused by the leader's own stale-revision
     *   rule (Finding A);
     * - the track may not be playable here yet, in which case Phase 4 is asked for it and the
     *   request waits for the *verified* cache rather than for another button press (Finding E).
     *
     * Neither is fixed by weakening the revision rule or by starting playback early. Both halves of
     * brief §19's gate still hold before anything is issued.
     */
    fun playSynchronized(contentHash: ContentHash) {
        scope.launch {
            role ?: return@launch
            syncEnabled = true
            val generation = session.currentAuthGeneration
            val addition =
                commandMutex.withLock {
                    val existing = _queueState.value.items.firstOrNull { it.trackHash == contentHash }
                    val queueItemId = existing?.queueItemId ?: nextQueueItemId()
                    // begin() supersedes whatever earlier request held the slot, which is what makes
                    // "the user asked for X while H was still transferring" resolve to X and only X.
                    pendingPlay = PendingPlay(playRequestFence.begin(), generation, contentHash, queueItemId, positionMs = 0)
                    if (existing == null) {
                        QueueAddItem(queueItemId, contentHash, localPeerId, PlaybackBounds.QUEUE_POSITION_END)
                    } else {
                        null
                    }
                }
            if (addition != null) mutateQueue(SharedQueueMutation.Add(listOf(addition)))
            resolvePendingPlay()
        }
    }

    /**
     * Re-evaluates the one retained Play against [PendingPlayGate], and issues it the instant every
     * precondition holds. Called on every event that can change one of them: the request itself, an
     * authoritative queue snapshot, the leader's own accepted mutation, and Phase 4's
     * verified-availability notification.
     *
     * Idempotent by construction — the request is cleared inside the same critical section that
     * decides to issue it, so two concurrent triggers cannot both fire it.
     */
    @Suppress("ReturnCount") // one early-out per gate answer
    private suspend fun resolvePendingPlay() {
        val pending = pendingPlay ?: return
        if (!playRequestFence.isCurrent(pending.token)) return
        // Both of these suspend, which is exactly why the gate re-proves the fence and the session
        // afterwards rather than trusting the check above.
        val local = content.resolve(pending.contentHash)
        val peerHas = content.peerHasContent(pending.contentHash)
        val currentRole = role
        val decision =
            PendingPlayGate.decide(
                operationCurrent = playRequestFence.isCurrent(pending.token),
                sessionCurrent = stillCurrent(pending.generation),
                syncEnabled = syncEnabled,
                queueSettled = _queueState.value.items.any { it.queueItemId == pending.queueItemId },
                localContentReady = local != null,
                // PROTOCOL §5 rule 4 makes requesting the transfer the *leader's* job, so a follower
                // that gated on the peer half would withhold the one message that unblocks it.
                peerContentRequired = currentRole == PlaybackRole.LEADER,
                peerHasContent = peerHas,
            )
        when (decision) {
            PendingPlayDecision.CANCEL -> clearPendingPlay(pending, cancelled = true)
            PendingPlayDecision.WAIT_FOR_QUEUE ->
                _diagnostics.update {
                    if (it.ingressDesynchronized) it else it.copy(syncState = SyncState.WAITING_FOR_QUEUE)
                }
            PendingPlayDecision.WAIT_FOR_CONTENT -> {
                _diagnostics.update {
                    if (it.ingressDesynchronized) it else it.copy(syncState = SyncState.WAITING_FOR_CONTENT)
                }
                // PROTOCOL §5 rule 4's transfer request, through the **existing** Phase 4 queue,
                // which already de-duplicates a hash it is holding or has (brief §20) — asked once
                // per retained request all the same, so the diagnostics say what actually happened.
                if (local == null && peerHas && transferRequestedForToken != pending.token) {
                    transferRequestedForToken = pending.token
                    content.requestTransfer(pending.contentHash)
                }
            }
            PendingPlayDecision.ISSUE -> {
                if (!clearPendingPlay(pending, cancelled = false)) return
                issue { header ->
                    PlaybackMessage.Play(header, pending.contentHash, pending.positionMs, pending.queueItemId)
                }
            }
        }
    }

    /** @return true if this call is the one that cleared [pending] — false if something else already had. */
    private suspend fun clearPendingPlay(
        pending: PendingPlay,
        cancelled: Boolean,
    ): Boolean =
        commandMutex.withLock {
            if (pendingPlay?.token != pending.token) return@withLock false
            pendingPlay = null
            _diagnostics.update {
                if (cancelled) {
                    it.copy(cancelledPendingPlayCount = it.cancelledPendingPlayCount + 1)
                } else {
                    it.copy(resumedPendingPlayCount = it.resumedPendingPlayCount + 1)
                }
            }
            true
        }

    fun enqueue(contentHash: ContentHash) {
        scope.launch {
            mutateQueue(
                SharedQueueMutation.Add(
                    listOf(QueueAddItem(nextQueueItemId(), contentHash, localPeerId, PlaybackBounds.QUEUE_POSITION_END)),
                ),
            )
        }
    }

    fun removeFromQueue(queueItemId: String) = scope.launch { mutateQueue(SharedQueueMutation.Remove(listOf(queueItemId))) }

    fun moveInQueue(
        queueItemId: String,
        toIndex: Int,
    ) = scope.launch { mutateQueue(SharedQueueMutation.Move(queueItemId, toIndex)) }

    /**
     * PROTOCOL §9: the leader applies and broadcasts the resulting snapshot; a follower sends the
     * mutation as an intent and waits. **The snapshot is the only way the queue reaches a follower**
     * (ADR-024 §5) — §9's own "the snapshot always wins, there is no merge algorithm to get subtly
     * wrong", taken literally.
     */
    @Suppress("ReturnCount") // one early-out per role, plus the session re-proof inside the critical section
    private suspend fun mutateQueue(mutation: SharedQueueMutation) {
        val currentRole = role ?: return
        if (currentRole == PlaybackRole.FOLLOWER) {
            val generation = session.currentAuthGeneration
            commandMutex.withLock {
                if (!stillCurrent(generation)) return
                enqueueOutbound(Outbound.Queue(queueIntent(mutation)))
            }
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

    /**
     * The leader's queue mutation: apply, bump the revision, and hand the snapshot to the ordered
     * outbound queue — **all inside one critical section** (Amendment A1 Finding B). A playback
     * command stamped for the new revision therefore cannot leave this device ahead of the snapshot
     * that created it, and one stamped for the old revision cannot be overtaken by it.
     */
    private suspend fun applyLeaderMutation(mutation: SharedQueueMutation) {
        val generation = session.currentAuthGeneration
        val changed =
            commandMutex.withLock {
                if (!stillCurrent(generation)) return
                val outcome = SharedQueue.apply(_queueState.value, mutation)
                if (outcome.rejection != null || !outcome.changed) return@withLock false
                _queueState.value = outcome.state
                enqueueOutbound(Outbound.Queue(snapshotOf(outcome.state)))
                true
            }
        if (changed) {
            publishQueue()
            // Finding A: the leader's own mutation is the authoritative queue state, so a Play that
            // was waiting for exactly this revision may now be issued.
            resolvePendingPlay()
        }
    }

    private fun snapshotOf(state: SharedQueueState): QueueMessage = QueueMessage.Snapshot(state.revision, state.items, state.currentIndex)

    /**
     * The leader's answer to having lost an intent to its own bounded ingress: re-state authority.
     * Both frames go through the one ordered outbound path, so the follower sees the queue snapshot
     * and the playback state in the order the leader decided them.
     */
    private suspend fun rebroadcastAuthoritativeState() {
        if (role != PlaybackRole.LEADER) return
        val generation = session.currentAuthGeneration
        commandMutex.withLock {
            if (!stillCurrent(generation)) return
            enqueueOutbound(Outbound.Queue(snapshotOf(_queueState.value)))
        }
        emitPlaybackState()
    }

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
        // Amendment A1 Finding E: leaving synchronised mode cancels the retained Play. A transfer
        // completing afterwards must not start music the user has stopped asking for.
        playRequestFence.supersede()
        val cancelled = if (pendingPlay != null) 1 else 0
        pendingPlay = null
        deferredCommands.clear()
        deferredDrainJob?.cancel()
        deferredDrainJob = null
        timeline = null
        driftState = DriftController.reset()
        scope.launch { restoreRate() }
        _diagnostics.update {
            it.copy(
                syncState = SyncState.INACTIVE,
                localDriftMs = null,
                peerDriftMs = null,
                deferredCommandCount = 0,
                cancelledPendingPlayCount = it.cancelledPendingPlayCount + cancelled,
            )
        }
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
            is PlaybackMessage.PlaybackStateSnapshot -> onPeerPlaybackState(message, generation)
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
     *
     * It is also the queue half of Amendment A1's reconciliation: adopting authoritative queue state
     * is precisely what makes a desynchronised queue coherent again.
     */
    private suspend fun adoptSnapshot(message: QueueMessage.Snapshot) {
        if (role != PlaybackRole.FOLLOWER) return
        _queueState.value = SharedQueue.applySnapshot(message.queueRevision, message.items, message.currentIndex)
        queueDesynchronized = false
        publishDesynchronized()
        publishQueue()
        // Finding A: the authoritative revision this Play was waiting for has arrived.
        resolvePendingPlay()
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
        when (CommandOrderGate.decide(currentRole, lastReceivedSeq, header.commandSeq)) {
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
            commandMutex.withLock {
                if (stillCurrent(generation)) enqueueOutbound(Outbound.Queue(snapshotOf(_queueState.value)))
            }
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
        when (CommandOrderGate.decide(currentRole, lastReceivedSeq, header.commandSeq)) {
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
        // Amendment A1 Finding C: while incremental state is not trusted, an incremental command is
        // refused *without* spending its sequence number, so the authoritative snapshot that
        // reconciles us is what decides where ordering resumes from.
        if (playbackDesynchronized || queueDesynchronized) return
        if (header.queueRevision != _queueState.value.revision) {
            _diagnostics.update { it.copy(staleRevisionCount = it.staleRevisionCount + 1) }
            return
        }
        syncEnabled = true
        admitAuthoritativeCommand(message, header, generation)
    }

    /**
     * Amendment A1 Finding D: what happens between "[CommandOrderGate] accepted it" and "it is
     * scheduled".
     *
     * The old shape recorded the command as *applied* and then consulted the clock — so an
     * estimator that was momentarily untrusted spent the sequence number and applied nothing, and
     * the leader's replay of that same command was then correctly dropped as a duplicate. The
     * command was lost permanently, on a clock condition that resolves itself in milliseconds.
     */
    private suspend fun admitAuthoritativeCommand(
        message: PlaybackMessage,
        header: PlaybackCommandHeader,
        generation: Long,
    ) {
        val estimate = estimate()
        val admission =
            PendingCommandGate.decide(
                clockReady = estimate != null && estimate.ready,
                deferredCount = deferredCommands.size,
                capacity = deferredCommandCapacity,
            )
        when (admission) {
            CommandAdmission.OVERFLOW -> {
                // The same halt-and-reconcile posture as an ingress overflow, and for the same
                // reason: more authority is outstanding than we can honestly account for.
                _diagnostics.update { it.copy(inboundOverflowCount = it.inboundOverflowCount + 1) }
                playbackDesynchronized = true
                queueDesynchronized = true
                publishDesynchronized()
            }
            CommandAdmission.DEFER -> {
                commandMutex.withLock { lastReceivedSeq = header.commandSeq }
                deferredCommands.addLast(DeferredCommand(message, generation))
                _diagnostics.update {
                    it.copy(
                        lastReceivedCommandSeq = header.commandSeq,
                        deferredCommandCount = deferredCommands.size,
                        syncState = if (it.ingressDesynchronized) it.syncState else SyncState.CLOCK_UNREADY,
                        clockReady = false,
                    )
                }
                startDeferredDrain(generation)
            }
            CommandAdmission.APPLY -> {
                commandMutex.withLock {
                    lastReceivedSeq = header.commandSeq
                    lastAppliedSeq = header.commandSeq
                }
                _diagnostics.update {
                    it.copy(lastAppliedCommandSeq = header.commandSeq, lastReceivedCommandSeq = header.commandSeq)
                }
                applyAuthoritative(message, generation, requireNotNull(estimate))
            }
        }
    }

    /**
     * Re-checks the clock on a short cadence while commands wait, so a held `PLAY` becomes audible
     * as soon as the estimator recovers rather than at the next 5 s position-report tick. One loop
     * at a time, ended by the session boundary or by the buffer emptying.
     */
    private fun startDeferredDrain(generation: Long) {
        if (deferredDrainJob?.isActive == true) return
        deferredDrainJob =
            scope.launch {
                while (deferredCommands.isNotEmpty() && stillCurrent(generation)) {
                    sleeper.sleepUntil(monotonicNowUs() + Phase5GateBounds.DEFERRED_RETRY_INTERVAL_US)
                    if (!stillCurrent(generation)) return@launch
                    drainDeferredCommands()
                }
            }
    }

    /**
     * Applies held commands in authoritative order, and only while the clock stays trustworthy.
     * [lastAppliedSeq] moves here — at the point the command actually takes effect — which is the
     * whole of Finding D's "received is not applied".
     */
    @Suppress("ReturnCount") // one early-out per reason draining must stop: halted, untrusted clock, dead session
    private suspend fun drainDeferredCommands() {
        while (deferredCommands.isNotEmpty()) {
            if (playbackDesynchronized || queueDesynchronized) return
            val estimate = estimate()
            if (estimate == null || !estimate.ready) return
            val held = deferredCommands.first()
            if (!stillCurrent(held.generation)) {
                deferredCommands.clear()
                _diagnostics.update { it.copy(deferredCommandCount = 0) }
                return
            }
            deferredCommands.removeFirst()
            val seq = headerOf(held.message)?.commandSeq
            commandMutex.withLock { if (seq != null) lastAppliedSeq = seq }
            _diagnostics.update {
                it.copy(
                    lastAppliedCommandSeq = seq,
                    deferredCommandCount = deferredCommands.size,
                    recoveredCommandCount = it.recoveredCommandCount + 1,
                    clockReady = true,
                )
            }
            applyAuthoritative(held.message, held.generation, estimate)
        }
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
            commandMutex.withLock {
                if (stillCurrent(generation)) enqueueOutbound(Outbound.Queue(snapshotOf(_queueState.value)))
            }
            return
        }
        if (!stillCurrent(generation)) return
        syncEnabled = true
        if (message is PlaybackMessage.Play) {
            // Amendment A1 Finding E, the other user's half: the leader retains the follower's Play
            // exactly as it retains its own user's, so a track neither phone can play yet becomes
            // one authoritative PLAY when the transfer verifies — the leader being the only side
            // with the authority to reschedule it (PROTOCOL §5 rule 4).
            commandMutex.withLock {
                pendingPlay =
                    PendingPlay(
                        playRequestFence.begin(),
                        generation,
                        message.trackHash,
                        message.queueItemId,
                        message.positionMs,
                    )
            }
            resolvePendingPlay()
            return
        }
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
    @Suppress("ReturnCount", "LongParameterList") // one early-out per session/epoch re-proof after a suspension
    private suspend fun applyPlay(
        header: PlaybackCommandHeader,
        trackHash: ContentHash,
        queueItemId: String,
        positionMs: Long,
        generation: Long,
        estimate: SessionClockEstimate,
        playing: Boolean = true,
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
        timeline = PlaybackTimeline(trackHash, queueItemId, positionMs, header.effectiveAtSessionUs, playing, generation = token)
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
        if (!owns(generation, token)) return
        // A snapshot-restored track that the authority says is paused is loaded and left alone:
        // there is no instant to schedule, because nothing is about to become audible.
        if (!playing) {
            markSynced()
            return
        }
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
        // Decided at *arm* time, as PROTOCOL §5 rule 2 requires: the lateness of a command is a fact
        // about when it arrived, not about when this device got round to it.
        val decision = ScheduledCommand.decide(effectiveAtSessionUs, monotonicNowUs(), estimate.offsetToLeaderUs)
        if (decision is ScheduledCommandDecision.ApplyImmediately) {
            _diagnostics.update {
                it.copy(lateCommandCount = it.lateCommandCount + 1, lastScheduleErrorUs = decision.latenessUs)
            }
        } else {
            _diagnostics.update { it.copy(syncState = SyncState.SCHEDULED) }
        }
        // Finding G: joined to the previous armed action, so the authoritative order the leader chose
        // is the order the player is actually driven in.
        val previous = scheduledChain
        scheduledChain =
            scope.launch {
                previous?.join()
                if (decision is ScheduledCommandDecision.Schedule) {
                    sleeper.sleepUntil(decision.atLocalMonoUs)
                    // The software scheduling error, measured rather than assumed. It says nothing
                    // about audible alignment: the decoder, the mixer and two Bluetooth hops all sit
                    // between this instant and a listener's ear (brief §23/§66).
                    _diagnostics.update { it.copy(lastScheduleErrorUs = monotonicNowUs() - decision.atLocalMonoUs) }
                }
                if (runIfCurrent(generation, token) { action() }) markSynced()
            }
    }

    /**
     * Runs [action] only if both the session and the playback epoch that authorised it are still in
     * force. Deliberately writes **no** state of its own: a correction and a scheduled command both
     * need this guard, and only one of them means "we are now synchronised".
     *
     * Amendment A1 Finding F: it **returns whether it ran**. The iOS mirror discarded that fact and
     * then mutated diagnostics, incremented the hard-seek count and emitted a `PLAYBACK_STATE`
     * regardless — so a correction the guard had just refused still had four visible side effects.
     * Both platforms now branch on the answer, and re-prove ownership again after the action's own
     * suspension before anything externally visible happens.
     */
    private suspend fun runIfCurrent(
        generation: Long,
        token: Long,
        action: suspend () -> Unit,
    ): Boolean {
        if (!owns(generation, token)) return false
        action()
        return true
    }

    /**
     * A scheduled authoritative command took effect, so this device is tracking the timeline.
     *
     * It will **not** overwrite [SyncState.SYNC_FAILED]: ARCHITECTURE §7.3's fourth tier means
     * correction has given up, and a subsequent `PAUSE` landing on time does not make that untrue.
     * Only a new playback epoch clears it, which [applyPlay] does explicitly — a fresh `PLAY` is a
     * fresh timeline with a fresh drift state, so there is genuinely nothing left in force.
     *
     * Nor will it overwrite [SyncState.DESYNCHRONIZED], for the stronger version of the same reason:
     * a command landing on time says nothing about the authority we know we are missing.
     */
    private fun markSynced() {
        _diagnostics.update {
            when {
                it.syncState == SyncState.SYNC_FAILED -> it
                // Keyed on the *latch* rather than on the displayed state: once reconciliation has
                // cleared it, a command landing on time is genuinely news again. Guarding on the
                // displayed value instead would leave DESYNCHRONIZED on screen forever, because
                // nothing else would ever be allowed to replace it.
                it.ingressDesynchronized -> it
                else -> it.copy(syncState = SyncState.SYNCED)
            }
        }
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
        // A held command whose clock has recovered is applied before anything is measured against a
        // timeline it may be about to replace.
        drainDeferredCommands()
        val active = timeline ?: return
        val token = currentEpochToken
        val estimate = estimate()
        if (estimate == null || !estimate.ready) {
            // brief §41: a dubious clock stops correction, and local playback simply continues.
            _diagnostics.update {
                if (it.ingressDesynchronized) it else it.copy(syncState = SyncState.CLOCK_UNREADY, clockReady = false)
            }
            return
        }
        val nowSessionUs = sessionNowUs(estimate)
        val state = player.playerState.value
        val durationMs = state.durationMs.takeIf { it > 0 }
        enqueueOutbound(
            Outbound.Playback(
                PlaybackMessage.PositionReport(
                    active.trackHash,
                    state.positionMs.coerceAtLeast(0),
                    nowSessionUs,
                    state.playing,
                    state.rate,
                ),
            ),
        )
        if (!owns(generation, token)) return
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

    /**
     * ADR-004's ladder, applied to **this** device.
     *
     * Amendment A1 Finding F: every visible consequence of a correction — the player call, the
     * diagnostics, the hard-seek budget, the sync-failed state and the outbound `PLAYBACK_STATE` —
     * is now behind the *same* ownership proof, and the proof is taken again after the player's own
     * suspension. A correction belonging to a superseded epoch or a dead session has **zero** side
     * effects, which is a stronger statement than "it does not touch the player".
     */
    @Suppress("ReturnCount") // two ownership proofs per ladder tier, and each must be able to stop everything after it
    private suspend fun applyCorrection(
        action: DriftAction,
        generation: Long,
        token: Long,
    ) {
        when (action) {
            DriftAction.None -> Unit
            is DriftAction.Nudge -> {
                if (!runIfCurrent(generation, token) { player.setRate(action.rate) }) return
                if (!owns(generation, token)) return
                _diagnostics.update { it.copy(lastCorrection = SyncCorrection.NUDGE, playbackRate = action.rate) }
            }
            DriftAction.RestoreRate -> {
                if (!runIfCurrent(generation, token) { player.setRate(DriftController.RATE_NORMAL) }) return
                if (!owns(generation, token)) return
                _diagnostics.update {
                    it.copy(lastCorrection = SyncCorrection.RESTORE_RATE, playbackRate = DriftController.RATE_NORMAL)
                }
            }
            is DriftAction.HardSeek -> {
                if (!runIfCurrent(generation, token) { player.seek(action.positionMs) }) return
                if (!owns(generation, token)) return
                _diagnostics.update {
                    it.copy(lastCorrection = SyncCorrection.HARD_SEEK, hardSeekCount = it.hardSeekCount + 1)
                }
                emitPlaybackState()
            }
            DriftAction.DeclareSyncFailure -> {
                // ARCHITECTURE §7.3 tier four and FR-025: stop correcting, restore exactly 1.0,
                // surface it — and leave local music playing.
                if (!runIfCurrent(generation, token) { player.setRate(DriftController.RATE_NORMAL) }) return
                if (!owns(generation, token)) return
                _diagnostics.update {
                    it.copy(
                        lastCorrection = SyncCorrection.SYNC_FAILED,
                        syncState = SyncState.SYNC_FAILED,
                        playbackRate = DriftController.RATE_NORMAL,
                    )
                }
                emitPlaybackState()
            }
        }
    }

    /** PROTOCOL §5: the leader's authoritative snapshot after a correction. Never an incremental update. */
    @Suppress("ReturnCount") // one early-out per precondition: the role, a usable estimate, and the session
    private suspend fun emitPlaybackState() {
        if (role != PlaybackRole.LEADER) return
        val estimate = estimate() ?: return
        val generation = session.currentAuthGeneration
        val state = player.playerState.value
        commandMutex.withLock {
            if (!stillCurrent(generation)) return
            val active = timeline
            enqueueOutbound(
                Outbound.Playback(
                    PlaybackMessage.PlaybackStateSnapshot(
                        commandSeq = lastAppliedSeq ?: (nextSeq - 1).coerceAtLeast(0),
                        queueRevision = _queueState.value.revision,
                        trackHash = active?.trackHash,
                        queueItemId = active?.queueItemId,
                        positionMs = state.positionMs.coerceAtLeast(0),
                        playing = state.playing,
                        atSessionUs = sessionNowUs(estimate),
                    ),
                ),
            )
        }
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
     * describes the track it is already playing.
     *
     * **In normal operation it deliberately starts nothing**, exactly as PROTOCOL §5 says: a change
     * of track is a `PLAY`, which the leader sends separately.
     *
     * **While this device is desynchronised it does** (ADR-024 Amendment A1 Finding C). That is the
     * one narrow behavioural addition the amendment makes to an existing message: after an ingress
     * overflow, "re-anchor only" would leave the follower coherent about ordering and wrong about
     * what is playing, so the snapshot — which PROTOCOL §5 already calls "the full authoritative
     * snapshot … the reconciliation anchor" — is treated as one. No wire field changed; the
     * snapshot already carries every value needed, and the deadline it names is in the past, so
     * §5 rule 2's "apply immediately and count the lateness" is what happens rather than any reuse
     * of an expired instant as if it were still ahead.
     */
    @Suppress("ReturnCount") // the role gate, the reconciliation branch, then two epoch-binding checks
    private suspend fun onPeerPlaybackState(
        snapshot: PlaybackMessage.PlaybackStateSnapshot,
        generation: Long,
    ) {
        if (role != PlaybackRole.FOLLOWER) return
        commandMutex.withLock {
            val current = lastReceivedSeq
            if (current == null || snapshot.commandSeq > current) {
                lastReceivedSeq = snapshot.commandSeq
                lastAppliedSeq = snapshot.commandSeq
            }
            // Anything held for the clock that the snapshot already accounts for is superseded by
            // it — the authoritative state is strictly newer than the command that produced it.
            deferredCommands.removeAll { held -> (headerOf(held.message)?.commandSeq ?: 0) <= snapshot.commandSeq }
        }
        _diagnostics.update {
            it.copy(
                lastAppliedCommandSeq = lastAppliedSeq,
                lastReceivedCommandSeq = lastReceivedSeq,
                deferredCommandCount = deferredCommands.size,
            )
        }
        val wasDesynchronized = playbackDesynchronized
        playbackDesynchronized = false
        publishDesynchronized()
        if (wasDesynchronized) {
            restoreFromPlaybackState(snapshot, generation)
            return
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

    /** The playback half of Amendment A1's reconciliation. See [onPeerPlaybackState]. */
    @Suppress("ReturnCount") // one early-out per reconciliation precondition
    private suspend fun restoreFromPlaybackState(
        snapshot: PlaybackMessage.PlaybackStateSnapshot,
        generation: Long,
    ) {
        val trackHash = snapshot.trackHash
        val queueItemId = snapshot.queueItemId
        if (trackHash == null || queueItemId == null) {
            // "Nothing is loaded" is a representable authoritative state (ADR-024 §4). Every
            // scheduled effect from the epoch we lost track of is superseded, and nothing replaces it.
            playbackFence.supersede()
            timeline = null
            _diagnostics.update { it.copy(currentTrackHash = null) }
            markSynced()
            return
        }
        val estimate = readyEstimate() ?: return
        val header =
            PlaybackCommandHeader(snapshot.commandSeq, snapshot.atSessionUs, localPeerId, snapshot.queueRevision)
        applyPlay(
            header,
            trackHash,
            queueItemId,
            snapshot.positionMs,
            generation,
            estimate,
            playing = snapshot.playing,
        )
    }

    private fun publishQueue() {
        val state = _queueState.value
        _diagnostics.update { it.copy(queueRevision = state.revision, queueSize = state.items.size) }
    }

    private companion object {
        val POSITION_REPORT_INTERVAL_US = PlaybackBounds.POSITION_REPORT_INTERVAL_MS * 1_000

        /**
         * The ordered outbound queue's bound. Generous relative to what one device can generate: a
         * cadence tick every 5 s plus whatever two people press. Reaching it means the socket is not
         * draining, which is counted rather than hidden.
         */
        const val OUTBOUND_CAPACITY = 256
    }
}
