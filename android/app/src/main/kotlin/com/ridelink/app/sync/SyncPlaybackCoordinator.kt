package com.ridelink.app.sync

import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.PeerId
import com.ridelink.core.playback.AuthoritativeHoldGate
import com.ridelink.core.playback.CommandAdmission
import com.ridelink.core.playback.CommandOrderDecision
import com.ridelink.core.playback.CommandOrderGate
import com.ridelink.core.playback.DriftAction
import com.ridelink.core.playback.DriftController
import com.ridelink.core.playback.DriftInput
import com.ridelink.core.playback.DriftState
import com.ridelink.core.playback.HoldAdmission
import com.ridelink.core.playback.IngressAdmission
import com.ridelink.core.playback.OutboundAuthority
import com.ridelink.core.playback.OutboundCommit
import com.ridelink.core.playback.OutboundCommitGate
import com.ridelink.core.playback.OutboundOutcome
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 *
 * ## What ADR-024 Amendment A2 (the second closure audit) changed here
 *
 * - **Authority is bound to delivery** ([drainOutbound], [OutboundCommitGate]). A leader's
 *   `command_seq`, its `queue_revision` and the local audible effect are committed **only after the
 *   authenticated transport actually accepted the frame**. Admission to [outbound] is not delivery,
 *   and `send` returning false is not a send. Before this, an overflow incremented a counter and
 *   returned, and the drain incremented "sent" for a write that had just failed — either way the
 *   leader played a command the follower never received (Findings A and C).
 * - **Every outbound frame carries the generation that authorised it** ([Outbound.generation]).
 *   The queue deliberately outlives sessions, so resolving the writer at send time meant a Session A
 *   frame could be written under Session B's `session_id` — the session-confusion class Phase 4
 *   Amendments A3/A5 hardened against, on the other end of the pipe (Finding B).
 * - **Nothing overtakes held authoritative work** ([deferredEvents], [AuthoritativeHoldGate]). A1
 *   held a command whose clock was untrusted but let a later `QUEUE_SNAPSHOT` apply straight past
 *   it, so the held `NEXT` resolved against a revision it was never authored against. The whole
 *   authoritative stream is now held in arrival order and replayed in it (Finding D).
 * - **A correction's snapshot keeps the correction's own identity**
 *   ([emitPlaybackStateIfOwned]). Reading the *live* generation inside the emit meant a snapshot
 *   caused by a correction in Session A could be enqueued into Session B (Finding E).
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
    /** Injectable for the same reason: how many authoritative events may wait for a trustworthy clock. */
    private val deferredCommandCapacity: Int = Phase5GateBounds.DEFAULT_DEFERRED_COMMAND_CAPACITY,
    /**
     * Injectable for the same reason again (Amendment A2): a test forces the *outbound* admission
     * edge at 1 rather than producing 256 frames faster than a fake socket drains them.
     */
    outboundCapacity: Int = Phase5GateBounds.DEFAULT_OUTBOUND_CAPACITY,
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

    /**
     * Amendment A1 Finding D, widened by Amendment A2 Finding D: the **authoritative event stream**
     * held in arrival order while the clock is untrustworthy.
     *
     * A1 held commands only, and let a later `QUEUE_SNAPSHOT` apply straight past them — so a held
     * `NEXT` authored against revision 5 executed against revision 6 and stepped to the wrong track.
     * Once anything is held, every later authoritative frame whose semantics could change a held
     * command's meaning joins the queue behind it, and the whole stream replays in the order the
     * leader chose. A `POSITION_REPORT` is never held: it produces one diagnostics number and can
     * change no command's meaning.
     */
    private val deferredEvents = ArrayDeque<DeferredEvent>()
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

    /**
     * The tail of the **leader's authoritative apply chain** (Amendment A2 Finding A).
     *
     * A leader's own command is applied by [drainOutbound]'s commit hook, once the transport has
     * confirmed the frame went out — which is the whole point of A2. Applying it *on* that consumer
     * would stall the outbound path behind a decoder pre-roll, so the apply is launched instead; and
     * a launched coroutine preserves only the order in which coroutines start, which is precisely
     * the defect Amendment A1 Finding G was about. Each apply therefore joins the previous one,
     * exactly as [scheduledChain] does, so commit order — which *is* `command_seq` order, because
     * one consumer commits — is apply order.
     */
    private var applyChain: Job? = null

    /**
     * The parent of every [applyChain] and [scheduledChain] node the **current** session authorised
     * (Amendment A3 Findings A and C).
     *
     * A1 and A2 both stored only the *tail* of each chain, and [resetForNewSession] retired a chain
     * by setting that reference to null. That detaches; it does not cancel. The nodes already
     * created went on existing — one parked inside a decoder pre-roll, the ones behind it parked on
     * `join()` — and when the pre-roll finally returned they ran in whatever session was live by
     * then. Cancelling the tail would not have helped either: the tail is the *newest* node, and the
     * one that matters is the *oldest*, the one actually blocked.
     *
     * A `SupervisorJob` parented to [scope]'s own job gives the whole set one handle. Every node is
     * launched as its child, so one `cancel()` retires all of them, and coordinator shutdown still
     * reaches them because the parent chain is intact. `SupervisorJob` rather than `Job` because
     * these nodes are siblings: one failing must not cancel the others, exactly as before.
     *
     * **Cancellation is defence one, not the correctness boundary.** `ExoPlayer.prepare` runs on the
     * application looper and does not observe coroutine cancellation, so a cancelled node still
     * returns from it and carries on to its next statement. What stops it there is the generation
     * each node captured — see [chainApply] and [scheduleAt]. The iOS mirror says the same thing
     * about `withCheckedContinuation`, which is how every real `AVAudioEngine` callback is bridged.
     */
    private var sessionChains: Job = SupervisorJob(scope.coroutineContext[Job])

    /**
     * Amendment A2 Findings A and C: an authoritative frame this device produced never reached the
     * peer, so Phase 5 authority is over for this authentication generation.
     *
     * Latched rather than retried. There is no protocol message that tells a peer about a command it
     * never received (`STATE_REQUEST` remains unimplemented — ADR-024 Amendment A2 §H), so the only
     * honest options are to continue from a state only this device knows about, which is the
     * divergence A2 exists to close, or to stop being authoritative. This is the second.
     *
     * Synchronised mode is left when it latches, so `MusicCoordinator`'s transport controls go
     * straight back to Phase 3 behaviour rather than being answered by a coordinator that will
     * refuse them. Local music is untouched (ADR-004, FR-025).
     *
     * `@Volatile` because [drainOutbound] writes it while producers read it.
     */
    @Volatile
    private var outboundAuthorityLost = false

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
     * Bounded and **not** lossy: an admission refusal is *returned to the producer*
     * ([enqueueOutbound] answers false) and counted as
     * [SyncPlaybackDiagnostics.outboundOverflowCount]. Its producer is this device, so a full queue
     * means the control socket is wedged rather than that a peer is misbehaving.
     *
     * **Amendment A2 Finding A: a refusal is a failure of the operation, not a statistic.** It used
     * to increment a counter and return `Unit`, so no caller could learn the frame had been refused
     * — and every caller carried straight on to stamp, publish and apply. That is a leader playing a
     * command the follower will never receive. What this queue holds is therefore no longer a bare
     * frame but an [Outbound] envelope: the generation that authorised it (Finding B) and the commit
     * hook the single consumer invokes with the real outcome (Findings A and C).
     */
    private val outbound =
        Phase5FrameQueue<Outbound>(
            capacity = outboundCapacity,
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

    /**
     * One outbound Phase 5 frame, waiting its turn on the one ordered outbound path — with the two
     * things Amendment A2 found missing from it.
     *
     * @param generation the authentication generation that **authorised** this frame, captured when
     *   it was created rather than looked up when it is written (Finding B). The outbound queue
     *   deliberately outlives individual sessions, so a frame stamped under Session A that is still
     *   queued when Session B activates must not be written using Session B's writer and
     *   `session_id`. Looking the generation up at send time is exactly how that happens.
     * @param authority what committing this frame's local effect would mean — see [OutboundAuthority].
     * @param onOutcome the producer's commit hook, invoked by the single consumer with what actually
     *   happened. This is where a leader's `command_seq`, its `queue_revision` and its local audible
     *   effect are committed, and it runs on the one consumer, so commits happen strictly in send
     *   order (Findings A and C).
     */
    private class Outbound(
        val generation: Long,
        val authority: OutboundAuthority,
        val frame: Frame,
        val onOutcome: (suspend (OutboundOutcome) -> Unit)? = null,
    ) {
        sealed class Frame {
            data class Playback(
                val message: PlaybackMessage,
            ) : Frame()

            data class Queue(
                val message: QueueMessage,
            ) : Frame()
        }
    }

    /**
     * One authoritative event accepted for ordering but not yet applied — because the clock is not
     * trusted, or because something ahead of it is not (Amendment A1 Finding D, widened by
     * Amendment A2 Finding D).
     */
    private sealed class DeferredEvent {
        abstract val generation: Long

        /** A `PLAY`/`PAUSE`/`RESUME`/`SEEK`/`NEXT`/`PREVIOUS` the order gate accepted. */
        data class Command(
            val message: PlaybackMessage,
            override val generation: Long,
        ) : DeferredEvent()

        /** PROTOCOL §9's authoritative queue state, held so it cannot change a held command's meaning. */
        data class QueueSnapshot(
            val message: QueueMessage.Snapshot,
            override val generation: Long,
        ) : DeferredEvent()

        /** PROTOCOL §5's reconciliation anchor, held for the same reason. */
        data class PlaybackState(
            val message: PlaybackMessage.PlaybackStateSnapshot,
            override val generation: Long,
        ) : DeferredEvent()
    }

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

    /**
     * Hands one frame to the ordered outbound path.
     *
     * **Amendment A2 Finding A: this answers whether the frame was accepted, and every caller
     * branches on the answer.** It used to return `Unit`, so a refusal was a counter and the caller
     * carried on regardless — stamping a `command_seq`, bumping a `queue_revision` and starting
     * audio for a frame that had just been thrown away.
     *
     * @return true if the frame is now on the one ordered outbound path. Being on it is *still* not
     *   delivery: [drainOutbound] is what learns that, and [Outbound.onOutcome] is what acts on it.
     */
    private fun enqueueOutbound(envelope: Outbound): Boolean {
        if (outbound.offer(envelope) == IngressAdmission.OVERFLOW) {
            _diagnostics.update { it.copy(outboundOverflowCount = it.outboundOverflowCount + 1) }
            return false
        }
        _diagnostics.update { it.copy(outboundEnqueuedCount = it.outboundEnqueuedCount + 1) }
        return true
    }

    /**
     * The single writer, and — since Amendment A2 — the single **commit point** for anything this
     * device says with authority.
     *
     * Enqueue order is wire order, which was the whole of A1 Finding B's invariant. A2 adds the two
     * facts that invariant did not carry:
     *
     * - **the frame is written under the session that authorised it, or not at all** (Finding B).
     *   The generation is the envelope's, captured when the frame was created; resolving the writer
     *   at send time meant a Session A frame could be written with Session B's `session_id`;
     * - **the transport's answer is consumed** (Finding C). `send` returns false when there is no
     *   authenticated writer or the write throws, and that used to increment "sent" anyway.
     *
     * Commits happen here, on this one consumer, so they are strictly in send order — which is
     * `command_seq` order, because this consumer is also what sends. That is what makes
     * "no authoritative local commit without outbound delivery" a property of the pipeline rather
     * than of every call site remembering to check.
     */
    private suspend fun drainOutbound() {
        while (true) {
            val envelope = outbound.take() ?: return
            val outcome =
                when {
                    !outboundUsable(envelope.generation) -> OutboundOutcome.STALE_SESSION
                    sendFrame(envelope.frame, envelope.generation) -> OutboundOutcome.SENT
                    else -> OutboundOutcome.TRANSPORT_FAILED
                }
            // Counted *after* the attempt finished, never before it started: this pair is what a
            // test reads to know the wire has caught up, and a counter incremented ahead of the
            // write would say "drained" while a frame was still inside the socket.
            _diagnostics.update {
                val counted = it.copy(outboundAttemptCount = it.outboundAttemptCount + 1)
                when (outcome) {
                    OutboundOutcome.SENT -> counted.copy(outboundSentCount = counted.outboundSentCount + 1)
                    OutboundOutcome.STALE_SESSION -> counted.copy(outboundStaleCount = counted.outboundStaleCount + 1)
                    else -> counted.copy(outboundFailedCount = counted.outboundFailedCount + 1)
                }
            }
            envelope.onOutcome?.invoke(outcome)
        }
    }

    private suspend fun sendFrame(
        frame: Outbound.Frame,
        generation: Long,
    ): Boolean =
        when (frame) {
            is Outbound.Frame.Playback -> session.playback.send(frame.message, generation)
            is Outbound.Frame.Queue -> session.playback.send(frame.message, generation)
        }

    /**
     * Whether a frame authorised under [generation] may still be written (Amendment A2 Finding B).
     *
     * Two ways it may not. The obvious one is that the session it belonged to is gone — the
     * generation is strictly increasing per authentication (ADR-023 §3), so a mismatch is decisive,
     * and [stillCurrent] additionally refuses a link that has dropped but not yet re-authenticated.
     * The other is that Phase 5 authority for this very generation has already been abandoned: once
     * one authoritative frame failed, sending the ones queued behind it would tell the peer about
     * commands whose predecessor it never got, which is a different divergence rather than a
     * recovery.
     */
    private fun outboundUsable(generation: Long): Boolean = stillCurrent(generation) && !outboundAuthorityLost

    /**
     * What a producer does when the ordered outbound path refuses its frame outright
     * (Amendment A2 Finding A). The frame never existed as far as the peer is concerned, so nothing
     * it would have committed may be committed.
     */
    private fun onOutboundRefused(
        authority: OutboundAuthority,
        generation: Long,
    ) {
        when (OutboundCommitGate.decide(authority, OutboundOutcome.ADMISSION_REFUSED)) {
            OutboundCommit.ABORT_FAIL_CLOSED -> failClosedOutbound(generation)
            else -> Unit
        }
    }

    /**
     * ADR-024 Amendment A2's fail-closed posture: **an authoritative frame this device produced did
     * not reach the peer, so this device stops being authoritative.**
     *
     * Deliberately not a retry and deliberately not a reconciliation. `STATE_REQUEST` is catalogued
     * in PROTOCOL §3 and still unimplemented, and even implemented it would be the *peer* asking for
     * state it knows it is missing — a peer that never received a command does not know to ask. The
     * two honest options are therefore to carry on from a state only this device knows about, which
     * is the divergence this amendment exists to close, or to stop. This is stopping.
     *
     * What it does **not** do is stop the music, and it deliberately does **not** supersede the
     * playback epoch. Synchronised mode is left, so `MusicCoordinator`'s transport controls answer
     * locally again; correction stops and the rate goes back to exactly 1.0 (ADR-004, FR-025). But a
     * frame the transport *did* accept, still in flight when a later one failed, must still commit
     * and still take effect — the peer has it, so refusing to apply it here would manufacture the
     * mirror image of the divergence this whole amendment is closing. Only work that was never
     * delivered is abandoned. Recovery is a new session, which clears the latch in
     * [resetForNewSession].
     */
    private fun failClosedOutbound(generation: Long) {
        // A dead session needs no latch: its authority is already gone, and latching would then
        // survive into the session that replaced it.
        if (generation != session.currentAuthGeneration) return
        if (outboundAuthorityLost) return
        outboundAuthorityLost = true
        syncEnabled = false
        tickJob?.cancel()
        tickJob = null
        playRequestFence.supersede()
        val cancelled = if (pendingPlay != null) 1 else 0
        pendingPlay = null
        transferRequestedForToken = null
        deferredEvents.clear()
        deferredDrainJob?.cancel()
        deferredDrainJob = null
        driftState = DriftController.reset()
        scope.launch { restoreRate() }
        _diagnostics.update {
            it.copy(
                syncState = SyncState.TRANSPORT_FAILED,
                outboundAuthorityLost = true,
                deferredCommandCount = 0,
                localDriftMs = null,
                peerDriftMs = null,
                cancelledPendingPlayCount = it.cancelledPendingPlayCount + cancelled,
            )
        }
    }

    /**
     * Runs a leader's own authoritative apply, in commit order (Amendment A2 Finding A).
     *
     * See [applyChain]: the commit hook runs on [drainOutbound]'s single consumer, so doing the
     * apply there would stall the outbound path behind a decoder pre-roll, and launching it
     * unchained would throw away the very order the commit point exists to establish.
     */
    private fun chainApply(
        generation: Long,
        action: suspend () -> Unit,
    ) {
        val previous = applyChain
        applyChain =
            scope.launch(sessionChains) {
                previous?.join()
                // Amendment A3 Finding A: waiting for the node ahead is a suspension like any other,
                // and an authentication boundary can land inside it — which is the whole defect. The
                // cancellation check is cheap and prompt; the generation is what is *decisive*,
                // because the node ahead may have been parked in a player call that ignored the
                // cancellation entirely.
                currentCoroutineContext().ensureActive()
                if (!stillCurrent(generation)) return@launch
                action()
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
        // Amendment A3 Findings A and C: the previous session's chains are **retired**, not merely
        // detached. Cancelling the shared parent reaches every node of both chains — including the
        // oldest, which is the one actually blocked — and a fresh parent means nothing the next
        // session creates is a sibling of anything this one did.
        sessionChains.cancel()
        sessionChains = SupervisorJob(scope.coroutineContext[Job])
        // Nothing new joins the previous session's chain either: ordering across a session boundary
        // is meaningless, so the new session's first node has no predecessor to wait for. That is
        // what makes "Session B never waits for Session A" true by construction rather than by
        // Session A happening to finish.
        scheduledChain = null
        // Supersede rather than begin: nothing is current until a new epoch actually starts, so a
        // timer or a report still in flight from the previous session can match no token at all.
        playbackFence.supersede()
        // Amendment A1 Finding E: a session boundary cancels the retained Play outright. A transfer
        // that completes afterwards must never resurrect it — the token it held is already stale.
        playRequestFence.supersede()
        val cancelled = if (pendingPlay != null) 1 else 0
        pendingPlay = null
        transferRequestedForToken = null
        deferredEvents.clear()
        // Amendment A2 Finding B: a fresh generation retires everything the previous one authorised.
        // Frames still queued outbound stay physically queued and become inert, because each carries
        // the generation that authorised it and `outboundUsable` refuses to write them.
        outboundAuthorityLost = false
        applyChain = null
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
                outboundAuthorityLost = false,
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
     *
     * **Amendment A2 Finding A: the local commit moved out of this function entirely.** It used to
     * set `lastReceivedSeq`/`lastAppliedSeq` inside the lock and then apply the command — both
     * unconditionally, because [enqueueOutbound] could not report a refusal and [drainOutbound]
     * ignored the transport's answer. A full outbound queue or a dead socket therefore produced a
     * leader playing a command the follower never received. What happens here now is *candidate*
     * work: stamp, try to admit, and consume the sequence number only if the admission succeeded.
     * Everything after that is [onCommandOutcome], which the single outbound consumer calls with
     * what actually happened.
     */
    @Suppress("ReturnCount") // one early-out per role, per readiness gate and per admission refusal
    private suspend fun issue(build: (PlaybackCommandHeader) -> PlaybackMessage) {
        val currentRole = role ?: return
        if (outboundAuthorityLost) return
        val generation = session.currentAuthGeneration
        if (currentRole == PlaybackRole.FOLLOWER) {
            val admitted =
                commandMutex.withLock {
                    if (!stillCurrent(generation)) return
                    val header =
                        PlaybackCommandHeader(
                            PlaybackBounds.UNASSIGNED_COMMAND_SEQ,
                            0L,
                            localPeerId,
                            _queueState.value.revision,
                        )
                    // An intent owns no authority (ADR-024 §3), so nothing local is riding on it and
                    // there is nothing to roll back — but a refusal is still not a send.
                    enqueueOutbound(Outbound(generation, OutboundAuthority.INTENT, Outbound.Frame.Playback(build(header))))
                }
            if (!admitted) onOutboundRefused(OutboundAuthority.INTENT, generation)
            return
        }
        val estimate = readyEstimate() ?: return
        var admitted = false
        commandMutex.withLock {
            if (!stillCurrent(generation) || outboundAuthorityLost) return
            val seq = nextSeq
            val header =
                PlaybackCommandHeader(
                    seq,
                    sessionNowUs(estimate) + estimate.leadUs,
                    localPeerId,
                    _queueState.value.revision,
                )
            val built = build(header)
            admitted =
                enqueueOutbound(
                    Outbound(generation, OutboundAuthority.AUTHORITATIVE, Outbound.Frame.Playback(built)) { outcome ->
                        onCommandOutcome(seq, built, generation, estimate, outcome)
                    },
                )
            // Amendment A2 §6: a `command_seq` becomes authoritative exactly when the frame carrying
            // it enters the outbound authority pipeline, and not a moment earlier. A refused
            // candidate leaves no gap, because it was never assigned.
            if (admitted) nextSeq = seq + 1
        }
        if (!admitted) {
            onOutboundRefused(OutboundAuthority.AUTHORITATIVE, generation)
            return
        }
        _diagnostics.update { it.copy(nextCommandSeq = nextSeq) }
    }

    /**
     * The leader's own command, once the transport has answered (Amendment A2 Findings A and C).
     *
     * The leader is the assigner, so its own command cannot be lost between accepting and applying
     * it — there is no inbound path that could replay it, because an authoritative command arriving
     * at the leader is a role violation. Received and applied therefore still move together here;
     * A1 Finding D's split matters on the receiving side. What changed is *when*: only on
     * [OutboundOutcome.SENT], because a command the follower never received is not a command.
     *
     * The apply itself goes through the leader's ordered [applyChain] rather than running on the
     * outbound consumer, so a decoder pre-roll cannot stall the wire. The leader applies its own
     * command exactly as the follower will — same header, same effective instant, same code path —
     * because an "issuer applies immediately" shortcut is precisely how two phones end up on two
     * timelines.
     */
    @Suppress("ReturnCount") // one per commit decision, plus the session re-proof
    private suspend fun onCommandOutcome(
        seq: Long,
        message: PlaybackMessage,
        generation: Long,
        estimate: SessionClockEstimate,
        outcome: OutboundOutcome,
    ) {
        when (OutboundCommitGate.decide(OutboundAuthority.AUTHORITATIVE, outcome)) {
            OutboundCommit.ABORT_FAIL_CLOSED -> {
                failClosedOutbound(generation)
                return
            }
            OutboundCommit.ABORT_QUIET -> return
            OutboundCommit.COMMIT -> Unit
        }
        var committed = false
        commandMutex.withLock {
            // Deliberately **not** gated on [outboundAuthorityLost]: this frame reached the peer, so
            // the peer will act on it, and the only consistent thing this device can do is act on it
            // too. The latch stops *new* authority; it does not un-send what was sent.
            if (!stillCurrent(generation)) return@withLock
            // maxOf, not assignment: these commit on the outbound consumer, in send order, and a
            // monotone write says the same thing without depending on that ordering twice over.
            lastReceivedSeq = maxOf(lastReceivedSeq ?: seq, seq)
            lastAppliedSeq = maxOf(lastAppliedSeq ?: seq, seq)
            committed = true
        }
        if (!committed) return
        _diagnostics.update {
            it.copy(lastAppliedCommandSeq = lastAppliedSeq, lastReceivedCommandSeq = lastReceivedSeq)
        }
        chainApply(generation) { applyAuthoritative(message, generation, estimate) }
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
            // Amendment A2: authority for this generation is over, so there is nothing to press
            // Play *into*. Local playback is Phase 3's again and answers this on its own.
            if (outboundAuthorityLost) return@launch
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
        if (outboundAuthorityLost) return
        if (currentRole == PlaybackRole.FOLLOWER) {
            val generation = session.currentAuthGeneration
            val admitted =
                commandMutex.withLock {
                    if (!stillCurrent(generation)) return
                    enqueueOutbound(
                        Outbound(generation, OutboundAuthority.INTENT, Outbound.Frame.Queue(queueIntent(mutation))),
                    )
                }
            if (!admitted) onOutboundRefused(OutboundAuthority.INTENT, generation)
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
    @Suppress("ReturnCount") // the session re-proof, the no-op mutation and the admission refusal
    private suspend fun applyLeaderMutation(mutation: SharedQueueMutation) {
        val generation = session.currentAuthGeneration
        var refused = false
        val changed =
            commandMutex.withLock {
                if (!stillCurrent(generation) || outboundAuthorityLost) return
                val outcome = SharedQueue.apply(_queueState.value, mutation)
                if (outcome.rejection != null || !outcome.changed) return@withLock false
                // Amendment A2 Finding A / §7: the candidate state is computed first and becomes
                // authoritative only once the snapshot that carries it has been admitted to the one
                // ordered outbound path. A refused snapshot leaves the revision exactly where it
                // was, so the leader can never sit on a revision the follower has no way to learn.
                val admitted =
                    enqueueOutbound(
                        Outbound(
                            generation,
                            OutboundAuthority.AUTHORITATIVE,
                            Outbound.Frame.Queue(snapshotOf(outcome.state)),
                        ) { result -> onQueueOutcome(generation, result) },
                    )
                if (!admitted) {
                    refused = true
                    return@withLock false
                }
                _queueState.value = outcome.state
                true
            }
        if (refused) {
            onOutboundRefused(OutboundAuthority.AUTHORITATIVE, generation)
            return
        }
        if (changed) publishQueue()
    }

    /**
     * The leader's queue mutation, once the transport has answered (Amendment A2 Findings A and C).
     *
     * A1 Finding A's "the leader's own mutation is the authoritative queue state, so a Play waiting
     * for exactly this revision may now be issued" is still true — but only once the peer has
     * actually been told the revision. Issuing a `PLAY` stamped for a revision the follower never
     * received is the same divergence one layer up, and the follower's own §5 rule 3 check would
     * refuse it.
     */
    private suspend fun onQueueOutcome(
        generation: Long,
        outcome: OutboundOutcome,
    ) {
        when (OutboundCommitGate.decide(OutboundAuthority.AUTHORITATIVE, outcome)) {
            OutboundCommit.ABORT_FAIL_CLOSED -> failClosedOutbound(generation)
            OutboundCommit.ABORT_QUIET -> Unit
            // Launched rather than awaited: resolving a retained Play resolves content, which
            // suspends, and the one outbound consumer must keep draining while it does.
            OutboundCommit.COMMIT -> scope.launch { resolvePendingPlay() }
        }
    }

    private fun snapshotOf(state: SharedQueueState): QueueMessage = QueueMessage.Snapshot(state.revision, state.items, state.currentIndex)

    /**
     * The leader's answer to having lost an intent to its own bounded ingress: re-state authority.
     * Both frames go through the one ordered outbound path, so the follower sees the queue snapshot
     * and the playback state in the order the leader decided them.
     */
    private suspend fun rebroadcastAuthoritativeState() {
        if (role != PlaybackRole.LEADER || outboundAuthorityLost) return
        val generation = session.currentAuthGeneration
        commandMutex.withLock {
            if (!stillCurrent(generation)) return
            // ADVISORY (Amendment A2): it carries no new revision and no new `command_seq`. It
            // re-states authority the peer has already been told about, and PROTOCOL §9's "the
            // snapshot always wins" makes the next one subsume this one, so a failure here is a
            // missed reconciliation attempt rather than a divergence.
            enqueueOutbound(
                Outbound(generation, OutboundAuthority.ADVISORY, Outbound.Frame.Queue(snapshotOf(_queueState.value))),
            )
        }
        emitCurrentPlaybackState()
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
        transferRequestedForToken = null
        deferredEvents.clear()
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
            is QueueMessage.Snapshot -> adoptSnapshot(message, generation)
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
    private suspend fun adoptSnapshot(
        message: QueueMessage.Snapshot,
        generation: Long,
    ) {
        if (role != PlaybackRole.FOLLOWER) return
        // Amendment A2 Finding D: a snapshot must not overtake a command already held for the clock.
        // Applying revision n+1 ahead of a held `NEXT` authored against revision n changes what that
        // `NEXT` means, and no later check can recover the intent it destroyed.
        if (holdIfOvertaking({ gen -> DeferredEvent.QueueSnapshot(message, gen) }, generation)) return
        applyQueueSnapshot(message)
    }

    /** [adoptSnapshot] with the hold gate already answered — the drain's entry point too. */
    private suspend fun applyQueueSnapshot(message: QueueMessage.Snapshot) {
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
        if (outboundAuthorityLost) return
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
                if (stillCurrent(generation)) {
                    enqueueOutbound(
                        Outbound(
                            generation,
                            OutboundAuthority.ADVISORY,
                            Outbound.Frame.Queue(snapshotOf(_queueState.value)),
                        ),
                    )
                }
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
        // Amendment A2 Finding D: the revision rule is checked **against the state this command will
        // actually be applied to**. While an authoritative stream is held, the `QUEUE_SNAPSHOT` that
        // created this command's revision is itself held in front of it, so the revision applied
        // *now* is deliberately the older one and checking here would refuse a perfectly ordered
        // command for a revision it is about to be given. The check moves to the replay, in
        // [drainDeferredEvents].
        if (deferredEvents.isEmpty() && header.queueRevision != _queueState.value.revision) {
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
                deferredCount = deferredEvents.size,
                capacity = deferredCommandCapacity,
            )
        when (admission) {
            CommandAdmission.OVERFLOW -> onHoldOverflow()
            CommandAdmission.DEFER -> {
                commandMutex.withLock { lastReceivedSeq = header.commandSeq }
                deferredEvents.addLast(DeferredEvent.Command(message, generation))
                _diagnostics.update {
                    it.copy(
                        lastReceivedCommandSeq = header.commandSeq,
                        deferredCommandCount = deferredEvents.size,
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
     * More authoritative work is outstanding than may be held. The same explicit halt-and-reconcile
     * posture as an ingress overflow, and for the same reason: more authority is outstanding than we
     * can honestly account for, and applying part of it in the wrong order is worse than admitting
     * we lost track.
     */
    private fun onHoldOverflow() {
        _diagnostics.update { it.copy(inboundOverflowCount = it.inboundOverflowCount + 1) }
        playbackDesynchronized = true
        queueDesynchronized = true
        publishDesynchronized()
    }

    /**
     * A held command's `queue_revision` did not match the revision the replay had reached by the
     * time it came round (Amendment A2 Finding D).
     *
     * In a stream the leader actually produced this cannot happen: replaying its frames in arrival
     * order reproduces the revisions it stamped them against. Reaching here therefore means a frame
     * between them is missing — an ingress overflow, or a leader that failed closed mid-sequence —
     * so the honest answer is PROTOCOL §5 rule 3's refusal *plus* the same halt-and-reconcile
     * posture as every other "we can no longer account for the authority we hold".
     */
    private fun onHeldRevisionMismatch() {
        _diagnostics.update { it.copy(staleRevisionCount = it.staleRevisionCount + 1) }
        playbackDesynchronized = true
        queueDesynchronized = true
        publishDesynchronized()
    }

    /**
     * Amendment A2 Finding D: whether an authoritative **state** frame may be applied now, or must
     * join the held stream so it cannot change the meaning of a command already waiting.
     *
     * @return true when the frame was held (and the caller must stop), false when it may proceed.
     */
    private fun holdIfOvertaking(
        build: (Long) -> DeferredEvent,
        generation: Long,
    ): Boolean =
        when (AuthoritativeHoldGate.decide(deferredEvents.size, deferredCommandCapacity)) {
            HoldAdmission.PROCESS_NOW -> false
            HoldAdmission.OVERFLOW -> {
                onHoldOverflow()
                true
            }
            HoldAdmission.HOLD -> {
                deferredEvents.addLast(build(generation))
                _diagnostics.update { it.copy(deferredCommandCount = deferredEvents.size) }
                startDeferredDrain(generation)
                true
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
                while (deferredEvents.isNotEmpty() && stillCurrent(generation)) {
                    sleeper.sleepUntil(monotonicNowUs() + Phase5GateBounds.DEFERRED_RETRY_INTERVAL_US)
                    if (!stillCurrent(generation)) return@launch
                    drainDeferredEvents()
                }
            }
    }

    /**
     * Replays the held authoritative event stream **in original arrival order** (Amendment A1
     * Finding D, widened by Amendment A2 Finding D).
     *
     * A1 drained commands, and only commands, so a `QUEUE_SNAPSHOT` that arrived while a `NEXT` was
     * held had already been applied by the time the `NEXT` ran — and the `NEXT` then stepped a queue
     * it was never authored against. The fix is *not* to re-check the revision here and drop the
     * command, which would lose an authoritative operation all over again; it is that nothing
     * overtook it in the first place, so replaying the stream reproduces exactly what the leader
     * decided.
     *
     * A command needs a trustworthy clock and stops the drain until it has one. An authoritative
     * state frame does not — it names its own instant and PROTOCOL §5 rule 2 applies it immediately
     * — but it can only ever reach the head of this queue *after* every command in front of it has
     * been applied, so its position in the stream is what preserves the semantics.
     *
     * [lastAppliedSeq] moves here, at the point a command actually takes effect, which is the whole
     * of A1 Finding D's "received is not applied".
     */
    @Suppress("ReturnCount") // one early-out per reason draining must stop: halted, untrusted clock, dead session
    private suspend fun drainDeferredEvents() {
        while (deferredEvents.isNotEmpty()) {
            if (playbackDesynchronized || queueDesynchronized) return
            val held = deferredEvents.first()
            if (!stillCurrent(held.generation)) {
                deferredEvents.clear()
                _diagnostics.update { it.copy(deferredCommandCount = 0) }
                return
            }
            when (held) {
                is DeferredEvent.Command -> {
                    val estimate = estimate()
                    if (estimate == null || !estimate.ready) return
                    val heldHeader = headerOf(held.message)
                    if (heldHeader != null && heldHeader.queueRevision != _queueState.value.revision) {
                        deferredEvents.removeFirst()
                        _diagnostics.update { it.copy(deferredCommandCount = deferredEvents.size) }
                        onHeldRevisionMismatch()
                        return
                    }
                    deferredEvents.removeFirst()
                    val seq = heldHeader?.commandSeq
                    commandMutex.withLock { if (seq != null) lastAppliedSeq = seq }
                    _diagnostics.update {
                        it.copy(
                            lastAppliedCommandSeq = seq,
                            deferredCommandCount = deferredEvents.size,
                            recoveredCommandCount = it.recoveredCommandCount + 1,
                            clockReady = true,
                        )
                    }
                    applyAuthoritative(held.message, held.generation, estimate)
                }
                is DeferredEvent.QueueSnapshot -> {
                    deferredEvents.removeFirst()
                    _diagnostics.update {
                        it.copy(
                            deferredCommandCount = deferredEvents.size,
                            recoveredCommandCount = it.recoveredCommandCount + 1,
                        )
                    }
                    applyQueueSnapshot(held.message)
                }
                is DeferredEvent.PlaybackState -> {
                    deferredEvents.removeFirst()
                    _diagnostics.update {
                        it.copy(
                            deferredCommandCount = deferredEvents.size,
                            recoveredCommandCount = it.recoveredCommandCount + 1,
                        )
                    }
                    applyPeerPlaybackState(held.message, held.generation)
                }
            }
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
                if (stillCurrent(generation)) {
                    enqueueOutbound(
                        Outbound(
                            generation,
                            OutboundAuthority.ADVISORY,
                            Outbound.Frame.Queue(snapshotOf(_queueState.value)),
                        ),
                    )
                }
            }
            return
        }
        if (!stillCurrent(generation) || outboundAuthorityLost) return
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

    /**
     * Amendment A3 Finding B: **every** apply path proves its authorising generation before it
     * mutates anything, and each of them does so for itself rather than trusting this entry point.
     * The proof here is the cheap common case — an apply whose session died while it queued does no
     * work at all — but `applyPlay`, `applyTransport`, `applySeek` and `applyStep` are each
     * reachable from more than one caller and each suspends, so none of them may rely on it.
     */
    private suspend fun applyAuthoritative(
        message: PlaybackMessage,
        generation: Long,
        estimate: SessionClockEstimate,
    ) {
        if (!stillCurrent(generation)) return
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
        // Amendment A3 Finding B: reached from `applyAuthoritative`, from `applyStep` and from
        // `restoreFromPlaybackState`, and `content.resolve` is real I/O. Proved on entry so a Play
        // that only *starts* after a boundary does no work, and again below because the resolve
        // suspends.
        if (!stillCurrent(generation)) return
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

    /**
     * Amendment A3 Finding B: the ownership proof is the **first** statement, before
     * [currentEpochToken] is read and before [timeline] is re-anchored.
     *
     * It had none at all. `PAUSE`/`RESUME` legitimately attach to whatever playback epoch is current
     * — that is why the token is read live rather than carried — so a retired command reading it
     * read the *new* session's epoch and re-anchored the *new* session's timeline to an instant its
     * own dead leader had chosen. Nothing later could recover from that: the scheduled action was
     * correctly refused, but every drift measurement afterwards was taken against a timeline no
     * leader had authorised. There is no suspension between the proof and the writes, so the proof
     * still holds when they happen.
     */
    private fun applyTransport(
        header: PlaybackCommandHeader,
        generation: Long,
        estimate: SessionClockEstimate,
        playing: Boolean,
        positionMs: Long,
    ) {
        if (!stillCurrent(generation)) return
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

    /** [applyTransport]'s proof, for the same reason and in the same position. */
    private fun applySeek(
        header: PlaybackCommandHeader,
        targetPositionMs: Long,
        generation: Long,
        estimate: SessionClockEstimate,
    ) {
        if (!stillCurrent(generation)) return
        val token = currentEpochToken
        timeline = timeline?.copy(anchorPositionMs = targetPositionMs, anchorSessionUs = header.effectiveAtSessionUs)
        scheduleAt(header.effectiveAtSessionUs, estimate, generation, token) { player.seek(targetPositionMs) }
    }

    /**
     * PROTOCOL §5's `NEXT`/`PREVIOUS`, resolved against the **shared** queue (brief §25). Both peers
     * hold identical `SharedQueueState` at the revision the command names, so both resolve the same
     * item without either consulting its own local queue.
     */
    @Suppress("ReturnCount") // the ownership proof, then PROTOCOL §5's two step outcomes
    private suspend fun applyStep(
        header: PlaybackCommandHeader,
        delta: Int,
        generation: Long,
        estimate: SessionClockEstimate,
    ) {
        // Amendment A3 Finding B: the highest-risk path in the phase, and it had no proof at all.
        // Everything below reads or writes *live* state — the shared queue, the selection, the
        // playback epoch, the timeline — so the proof has to come before the first read, not before
        // the first player call. A retired `NEXT` used to step the new session's queue; a retired
        // `NEXT` that ran off the end of it took the `selected == null` branch and called
        // `playbackFence.begin()`, which **retired the live session's playback epoch** and silently
        // stopped its scheduled start from ever firing.
        if (!stillCurrent(generation)) return
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
        } else if (!outboundAuthorityLost) {
            _diagnostics.update { it.copy(syncState = SyncState.SCHEDULED) }
        }
        // Finding G: joined to the previous armed action, so the authoritative order the leader chose
        // is the order the player is actually driven in. Amendment A3 Finding C: launched under
        // [sessionChains], so a boundary retires it rather than leaving it armed.
        val previous = scheduledChain
        scheduledChain =
            scope.launch(sessionChains) {
                previous?.join()
                currentCoroutineContext().ensureActive()
                if (!owns(generation, token)) return@launch
                if (decision is ScheduledCommandDecision.Schedule) {
                    sleeper.sleepUntil(decision.atLocalMonoUs)
                    currentCoroutineContext().ensureActive()
                    // **Amendment A3 Finding C: the ownership proof comes before the measurement.**
                    // It used to come after. The player action itself was correctly refused, but a
                    // Session-A deadline arriving after Session B authenticated still overwrote
                    // Session B's `lastScheduleErrorUs` — the FR-023 figure a rider reads as "this
                    // is how well the last synchronised command landed". "Diagnostics only" is not
                    // an exemption: a superseded action has *zero* effects (A1 Finding F).
                    if (!owns(generation, token)) return@launch
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
                // Amendment A2: a command landing on time says nothing about the authority we know
                // did not reach the peer, and this state is latched for the generation.
                it.outboundAuthorityLost -> it
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
        drainDeferredEvents()
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
        // ADVISORY (Amendment A2): one diagnostics number on the peer's screen. A failed report is
        // one missing sample, superseded by the next tick 5 s later — never a divergence.
        enqueueOutbound(
            Outbound(
                generation,
                OutboundAuthority.ADVISORY,
                Outbound.Frame.Playback(
                    PlaybackMessage.PositionReport(
                        active.trackHash,
                        state.positionMs.coerceAtLeast(0),
                        nowSessionUs,
                        state.playing,
                        state.rate,
                    ),
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
                // Amendment A2 Finding E: the correction's *own* generation and epoch, carried to
                // the enqueue rather than replaced there by whatever is live by then.
                emitPlaybackStateIfOwned(generation, token)
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
                emitPlaybackStateIfOwned(generation, token)
            }
        }
    }

    /**
     * PROTOCOL §5's authoritative snapshot, emitted **because the leader chose to re-state its
     * current state now** — the reconciliation re-broadcast, and nothing else.
     *
     * Amendment A2 Finding E made this a separate function from [emitPlaybackStateIfOwned]. Reading
     * the live generation is correct *here*, because "now" is what this call means; it is exactly
     * wrong for a snapshot that exists as a consequence of some earlier operation, and one function
     * cannot honestly serve both.
     */
    private suspend fun emitCurrentPlaybackState() {
        if (role != PlaybackRole.LEADER) return
        val generation = session.currentAuthGeneration
        emitPlaybackStateFrame(generation) { stillCurrent(generation) }
    }

    /**
     * PROTOCOL §5's authoritative snapshot, emitted **as a consequence of a correction**, and
     * therefore carrying that correction's own authorisation all the way to the enqueue
     * (Amendment A2 Finding E).
     *
     * The old shape took no arguments and read `session.currentAuthGeneration` *inside itself*. A
     * correction that had legitimately proved `owns(generationA, tokenA)` before calling it could
     * therefore have that proof replaced, one suspension later, by whatever generation happened to
     * be live — so a snapshot caused by a correction in Session A could be enqueued into Session B.
     * That is the same "authorising generation versus live generation" distinction ADR-023
     * Amendments A3/A5 drew in Phase 4, and A1 Finding F's "a superseded correction has zero
     * effects" was one read of the live generation short of being true.
     *
     * The proof is taken again *inside* the critical section, immediately before the enqueue, so
     * neither the two reads above it nor the lock acquisition itself is a hole in it.
     */
    private suspend fun emitPlaybackStateIfOwned(
        generation: Long,
        token: Long,
    ) {
        if (role != PlaybackRole.LEADER) return
        emitPlaybackStateFrame(generation) { owns(generation, token) }
    }

    @Suppress("ReturnCount") // one early-out per precondition: a usable estimate, then the ownership proof
    private suspend fun emitPlaybackStateFrame(
        generation: Long,
        stillOwned: () -> Boolean,
    ) {
        if (outboundAuthorityLost) return
        val estimate = estimate() ?: return
        val state = player.playerState.value
        commandMutex.withLock {
            if (!stillOwned()) return
            val active = timeline
            // ADVISORY (Amendment A2): PROTOCOL §5 calls this "the full authoritative snapshot …
            // the reconciliation anchor, not an incremental update", so the next one subsumes it and
            // a failed send costs nothing that cannot be re-stated. It carries no new `command_seq`.
            enqueueOutbound(
                Outbound(
                    generation,
                    OutboundAuthority.ADVISORY,
                    Outbound.Frame.Playback(
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
    private suspend fun onPeerPlaybackState(
        snapshot: PlaybackMessage.PlaybackStateSnapshot,
        generation: Long,
    ) {
        if (role != PlaybackRole.FOLLOWER) return
        // Amendment A2 Finding D: the reconciliation anchor is authoritative state, so it waits its
        // turn behind held commands exactly as a queue snapshot does. Its supersede rule below then
        // runs against whatever is *still* held at that point, which is the honest reading of "the
        // authoritative state is strictly newer than the command that produced it".
        if (holdIfOvertaking({ gen -> DeferredEvent.PlaybackState(snapshot, gen) }, generation)) return
        applyPeerPlaybackState(snapshot, generation)
    }

    /** [onPeerPlaybackState] with the hold gate already answered — the drain's entry point too. */
    @Suppress("ReturnCount") // the reconciliation branch, then two epoch-binding checks
    private suspend fun applyPeerPlaybackState(
        snapshot: PlaybackMessage.PlaybackStateSnapshot,
        generation: Long,
    ) {
        commandMutex.withLock {
            // Amendment A3 Finding B: acquiring the lock is a suspension, and everything below it
            // writes live state — the received/applied sequence numbers, the held stream, the
            // timeline. Re-proved here rather than trusting the dispatch-time check in
            // `onPlaybackMessage`.
            if (!stillCurrent(generation)) return
            val current = lastReceivedSeq
            if (current == null || snapshot.commandSeq > current) {
                lastReceivedSeq = snapshot.commandSeq
                lastAppliedSeq = snapshot.commandSeq
            }
            // Anything held for the clock that the snapshot already accounts for is superseded by
            // it — the authoritative state is strictly newer than the command that produced it.
            deferredEvents.removeAll { held ->
                held is DeferredEvent.Command && (headerOf(held.message)?.commandSeq ?: 0) <= snapshot.commandSeq
            }
        }
        _diagnostics.update {
            it.copy(
                lastAppliedCommandSeq = lastAppliedSeq,
                lastReceivedCommandSeq = lastReceivedSeq,
                deferredCommandCount = deferredEvents.size,
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
        // Amendment A3 Finding B: the null-track branch below supersedes the playback epoch and
        // clears the timeline, so this needs the same pre-mutation proof `applyStep` needs — it is
        // reached through two suspensions (the lock above, and the drain that may call it).
        if (!stillCurrent(generation)) return
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
    }
}
