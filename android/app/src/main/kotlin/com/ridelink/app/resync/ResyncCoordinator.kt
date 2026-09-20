package com.ridelink.app.resync

import com.ridelink.app.sync.SyncPlaybackCoordinator
import com.ridelink.app.sync.SyncPlaybackCoordinator.StateSnapshotOutcome
import com.ridelink.core.model.PeerId
import com.ridelink.core.resync.ResyncMessage
import com.ridelink.core.resync.StateResyncGate
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.resync.ResyncSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The outcome of the most recent `STATE_REQUEST`/`STATE_SNAPSHOT` round trip, for FR-023.
 *
 * [DEFERRED] (independent-review Blocker 2E) is deliberately distinct from [RECONCILED]: a valid,
 * generation-matching snapshot satisfies the *wire* round trip (no resend needed) without
 * necessarily satisfying the *reconciliation* obligation, when the fresh clock is not ready yet.
 * [RECONCILED] is reserved for genuine convergence — either immediately, or once the retained
 * snapshot `deferredReconciliation` owns is genuinely applied.
 */
enum class ResyncOutcome { NONE, REQUESTED, RECONCILED, SEND_FAILED, DEFERRED }

data class ResyncDiagnostics(
    val requestPending: Boolean = false,
    val reconnectRequestCount: Int = 0,
    val desyncRequestCount: Int = 0,
    val roleViolationCount: Int = 0,
    val lastOutcome: ResyncOutcome = ResyncOutcome.NONE,
    val lastSnapshotManifestRevision: Long? = null,
    val lastSnapshotCommandSeq: Long? = null,
)

/**
 * The single owner of PROTOCOL §10's `STATE_REQUEST`/`STATE_SNAPSHOT` exchange (Phase 7,
 * ADR-028) — installed once per process and self-subscribed to [ResyncSessionPort.events],
 * exactly as [SharedLibraryCoordinator] already is for `MANIFEST_*`/`TRANSFER_*` (CLAUDE.md rule
 * 8 extended to this plane; brief §25's "extend an existing pure table rather than creating
 * another owner" — the *decision* of when to ask is [StateResyncGate], the only new pure table
 * this phase adds).
 *
 * **A follower requests; only the leader answers** — the same asymmetry PROTOCOL §5/§9 already
 * enforce for every other authoritative frame (ADR-010), so a follower's stray `STATE_REQUEST`
 * (which should never occur, since leadership is stable across a reconnect — ARCHITECTURE §5) is
 * refused and counted rather than answered with unauthoritative state.
 *
 * **What this type deliberately does not decide.** Whether an inbound `STATE_SNAPSHOT` may be
 * *applied* is entirely [SyncPlaybackCoordinator.onStateSnapshot]'s existing role/generation/hold-
 * gate machinery — this type only translates the wire message into the shapes that machinery
 * already reconciles, and only clears its own local "a request is outstanding" bookkeeping.
 */
class ResyncCoordinator(
    private val scope: CoroutineScope,
    private val session: ResyncSessionPort,
    private val syncPlaybackCoordinator: SyncPlaybackCoordinator,
    /**
     * `SharedLibraryCoordinator.currentCatalogueRevision`, narrowed to the one value this type
     * reads — mirroring `SharedLibraryCoordinator`'s own `activeCacheHash` lambda parameter rather
     * than depending on the whole coordinator, so a deterministic test needs no manifest generator,
     * cache repository or bulk transport double to exercise this type.
     */
    private val currentCatalogueRevision: () -> Long,
    /**
     * `SharedLibraryCoordinator.requestCatalogue`, narrowed the same way [currentCatalogueRevision]
     * is. Called only when a `STATE_SNAPSHOT`'s `manifest_revision` differs from the last one this
     * device observed (§20/§21's "no unnecessary manifest retransmission") — never on the first
     * snapshot of a session, which the existing unconditional `Connected -> requestCatalogue()`
     * already covers (see [handleStateSnapshot]).
     */
    private val requestManifestRefresh: () -> Unit,
    private val localPeerId: PeerId,
) {
    private val _diagnostics = MutableStateFlow(ResyncDiagnostics())
    val diagnostics: StateFlow<ResyncDiagnostics> = _diagnostics.asStateFlow()

    @Volatile
    private var isLocalLeader: Boolean? = null

    /**
     * The `manifest_revision` this device last saw in a `STATE_SNAPSHOT`, or `null` before the
     * first one. Compared, never re-derived: a peer's catalogue revision is a single global counter
     * (PROTOCOL §8.1), so comparing against the last value **this device observed** — regardless of
     * which control generation reported it — is correct without any generation-scoping of its own.
     */
    @Volatile
    private var lastKnownManifestRevision: Long? = null

    /**
     * `true` once this process has completed **any** prior authenticated session. A fresh first
     * pairing has nothing to reconcile — the follower's local Phase 5/Phase 4 state is already
     * empty — so only the *second or later* [ControlEvent.Connected] (a genuine reconnect, or a
     * second ride after a full teardown, brief §28) triggers a request. Deliberately **not** reset
     * on [ControlEvent.LinkLost]: it answers "has this process ever connected", not "is a session
     * live now".
     */
    @Volatile
    private var hasEverConnected = false

    /** [StateResyncGate]'s own state: the generation a `STATE_REQUEST` is outstanding for, if any. */
    @Volatile
    private var pendingRequestGeneration: Long? = null

    /**
     * Independent-review Blocker 2E: the generation and **message** a **reconciliation** (as
     * opposed to the wire round trip [pendingRequestGeneration] tracks) is still genuinely
     * outstanding for — set when [SyncPlaybackCoordinator.onStateSnapshot] answers
     * [StateSnapshotOutcome.DEFERRED_CLOCK] or [StateSnapshotOutcome.DEFERRED_CONTENT], and cleared
     * when [SyncPlaybackCoordinator.onReconciliationApplied] reports that **this generation's**
     * retained snapshot genuinely applied, or when a newer generation supersedes it.
     *
     * Independent-review round 3, Blocker B: this used to be resolved by watching
     * `pendingPlaybackReconciliationGeneration` go null in the diagnostics flow, which cannot
     * distinguish "the obligation converged" from "the obligation was **discarded**" —
     * [SyncPlaybackCoordinator.leaveSynchronizedMode] legitimately does the second. The explicit
     * signal answers the question asked rather than a correlated one, and makes both platforms the
     * same shape. It is also **monotonic**: see [supersededByNewerObligation].
     *
     * The **message** itself is retained, not only its generation: the deferred-then-later-applied
     * transition is observed asynchronously, well after [handleStateSnapshot]'s own stack frame is
     * gone, so [completeReconciliation] needs the original snapshot's `command_seq`/
     * `manifest_revision` to finish the same bookkeeping the immediate path does — without this, a
     * deferred reconciliation's eventual [ResyncOutcome.RECONCILED] would report a stale or absent
     * `lastSnapshotCommandSeq`/`lastSnapshotManifestRevision` and would never trigger a genuinely
     * needed manifest refresh.
     */
    private data class DeferredReconciliation(
        val generation: Long,
        val message: ResyncMessage.StateSnapshot,
    )

    @Volatile
    private var deferredReconciliation: DeferredReconciliation? = null

    init {
        session.resync.sink =
            ResyncSink { message, generation -> scope.launch { handle(message, generation) } }
        scope.launch {
            session.events.collect { event ->
                when (event) {
                    is ControlEvent.Connected -> onConnected(event.isLocalLeader, event.authGeneration)
                    else -> Unit
                }
            }
        }
        // Blocker 2E's deferred-then-later-applied transition: `drainDeferredEvents` applies a held
        // reconciliation without ever going through `handleStateSnapshot` again, so this callback is
        // the one place that later completion is observed.
        //
        // Independent-review round 3, Blocker B (Android half): this used to be inferred from
        // `pendingPlaybackReconciliationGeneration` going null in the diagnostics flow, which cannot
        // distinguish "the obligation converged" from "the obligation was **discarded**" —
        // `leaveSynchronizedMode` legitimately does the second, and would have reported a
        // reconciliation that never happened as `RECONCILED`. An explicit signal, raised only where
        // the apply actually succeeds and carrying the generation that authorised it, answers the
        // question asked rather than a correlated one. It also makes both platforms the same shape.
        syncPlaybackCoordinator.onReconciliationApplied = { generation ->
            val deferred = deferredReconciliation
            if (deferred != null && deferred.generation == generation) {
                deferredReconciliation = null
                completeReconciliation(deferred.message)
            }
        }
        // The **edge**, not the level (independent-review round 3, found by CI). This used to collect
        // [SyncPlaybackCoordinator.diagnostics] and act whenever `ingressDesynchronized` was true —
        // so every later diagnostics emission asked again, unboundedly once Blocker A's retained
        // reconciliation kept the flag set. One signal per latch event is what iOS always had.
        syncPlaybackCoordinator.onDesynchronizedTrigger = {
            if (isLocalLeader == false) {
                triggerRequest(session.currentAuthGeneration, desync = true)
            }
        }
    }

    private fun onConnected(
        isLeader: Boolean,
        generation: Long,
    ) {
        isLocalLeader = isLeader
        val isReconnectOrRestart = hasEverConnected
        hasEverConnected = true
        if (isLeader || !isReconnectOrRestart) return
        triggerRequest(generation, desync = false)
    }

    private fun triggerRequest(
        generation: Long,
        desync: Boolean,
    ) {
        when (StateResyncGate.onTrigger(pendingRequestGeneration, generation)) {
            StateResyncGate.RequestDecision.ALREADY_PENDING -> return
            StateResyncGate.RequestDecision.SEND_REQUEST -> Unit
        }
        pendingRequestGeneration = generation
        _diagnostics.update {
            it.copy(
                requestPending = true,
                lastOutcome = ResyncOutcome.REQUESTED,
                reconnectRequestCount = if (desync) it.reconnectRequestCount else it.reconnectRequestCount + 1,
                desyncRequestCount = if (desync) it.desyncRequestCount + 1 else it.desyncRequestCount,
            )
        }
        scope.launch {
            val sent = session.resync.send(ResyncMessage.StateRequest, generation)
            if (!sent) {
                pendingRequestGeneration = StateResyncGate.onSnapshotObserved(pendingRequestGeneration, generation)
                _diagnostics.update { it.copy(requestPending = pendingRequestGeneration != null, lastOutcome = ResyncOutcome.SEND_FAILED) }
            }
        }
    }

    private suspend fun handle(
        message: ResyncMessage,
        generation: Long,
    ) {
        when (message) {
            is ResyncMessage.StateRequest -> handleStateRequest(generation)
            is ResyncMessage.StateSnapshot -> handleStateSnapshot(message, generation)
        }
    }

    /**
     * Only the leader answers (ADR-010). Construction and the send are no longer this
     * coordinator's own two separate steps: [SyncPlaybackCoordinator.emitStateSnapshot] performs
     * both atomically, under the same [kotlinx.coroutines.sync.Mutex] and onto the same ordered
     * outbound writer `QUEUE_SNAPSHOT`/`PLAYBACK_STATE` already use, closing the ordering gap a
     * separately-timed `session.resync.send(...)` used to leave (ADR-028, `docs/STATUS.md`
     * Stage 5 finding: a `STATE_SNAPSHOT` built from a state read at T could otherwise reach the
     * wire after a concurrently-produced `QUEUE_SNAPSHOT` reflecting a later revision). This
     * function is now only the role gate; [SyncPlaybackCoordinator.emitStateSnapshot] re-proves the
     * generation itself, immediately before admission, for the same reason ADR-024 Amendment A2
     * requires of every other outbound Phase 5 frame.
     */
    private suspend fun handleStateRequest(generation: Long) {
        if (isLocalLeader != true) {
            _diagnostics.update { it.copy(roleViolationCount = it.roleViolationCount + 1) }
            return
        }
        syncPlaybackCoordinator.emitStateSnapshot(generation, localPeerId, currentCatalogueRevision())
    }

    /**
     * A follower's reconciliation, gated on what
     * [SyncPlaybackCoordinator.onStateSnapshot] actually answers (independent-review Blocker 2E) —
     * receiving a valid frame off the wire is not the same as authoritative state converging.
     *
     * [pendingRequestGeneration] (the wire round trip) and [deferredReconciliationGeneration] (the
     * reconciliation obligation itself) are cleared/set independently, per outcome:
     *
     * - [StateSnapshotOutcome.APPLIED]: both the wire round trip and reconciliation are satisfied —
     *   manifest bookkeeping updates (§20/§21, gated on an actual revision difference so a second
     *   snapshot in the same session doesn't redundantly retrigger
     *   [SharedLibraryCoordinator.requestCatalogue], which the reconnect's own
     *   [ControlEvent.Connected] already ran unconditionally), and [ResyncOutcome.RECONCILED].
     * - [StateSnapshotOutcome.DEFERRED_CLOCK]: the wire round trip is satisfied (no resend), but
     *   reconciliation is not — [deferredReconciliationGeneration] records the obligation, and the
     *   [init] block's diagnostics collector is what later observes it actually converging via
     *   `drainDeferredEvents`, which never comes back through this function.
     * - [StateSnapshotOutcome.REJECTED_STALE] / [StateSnapshotOutcome.REJECTED_ROLE]: neither the
     *   wire bookkeeping nor manifest bookkeeping may change — a rejected snapshot must not falsely
     *   complete a request it was never a valid answer to (§20/§21).
     */
    private suspend fun handleStateSnapshot(
        message: ResyncMessage.StateSnapshot,
        generation: Long,
    ) {
        val outcome = syncPlaybackCoordinator.onStateSnapshot(message, generation)
        when (outcome) {
            StateSnapshotOutcome.APPLIED -> {
                pendingRequestGeneration = StateResyncGate.onSnapshotObserved(pendingRequestGeneration, generation)
                if (supersededByNewerObligation(generation)) return
                deferredReconciliation = null
                completeReconciliation(message)
            }
            // `DEFERRED_CONTENT` (section 22) gets identical treatment to `DEFERRED_CLOCK`: the wire
            // round trip is satisfied either way, and since independent-review round 3's Blocker A
            // the retained snapshot is drained — and `onReconciliationApplied` raised — for whichever
            // precondition resolves, so one branch correctly serves both.
            StateSnapshotOutcome.DEFERRED_CLOCK, StateSnapshotOutcome.DEFERRED_CONTENT -> {
                pendingRequestGeneration = StateResyncGate.onSnapshotObserved(pendingRequestGeneration, generation)
                if (supersededByNewerObligation(generation)) return
                deferredReconciliation = DeferredReconciliation(generation, message)
                _diagnostics.update {
                    it.copy(requestPending = pendingRequestGeneration != null, lastOutcome = ResyncOutcome.DEFERRED)
                }
            }
            StateSnapshotOutcome.REJECTED_STALE -> Unit
            StateSnapshotOutcome.REJECTED_ROLE -> _diagnostics.update { it.copy(roleViolationCount = it.roleViolationCount + 1) }
        }
    }

    /**
     * Whether a **newer** reconciliation obligation has already been recorded, making this
     * snapshot's outcome too old to act on (independent-review round 3's own fresh-fix audit, §17).
     *
     * [SyncPlaybackCoordinator.onStateSnapshot] suspends, and a successor generation's
     * [ControlEvent.Connected] can be collected inside that window — so this snapshot's own outcome
     * may arrive after a newer obligation exists. Writing either branch unconditionally would let an
     * older generation's result overwrite a newer generation's obligation, and the newer one could
     * then never complete (its completion signal would find the wrong owner). The obligation is
     * therefore **monotonic**. This compares two recorded owners; it never re-derives one from
     * whatever generation is live now (CLAUDE.md rule 20).
     */
    private fun supersededByNewerObligation(generation: Long): Boolean = (deferredReconciliation?.generation ?: generation) > generation

    /**
     * The completion bookkeeping shared by both routes to [ResyncOutcome.RECONCILED]:
     * [handleStateSnapshot]'s own immediate [StateSnapshotOutcome.APPLIED] branch, and
     * [SyncPlaybackCoordinator.onReconciliationApplied] reporting a deferred one's later success. Manifest
     * bookkeeping only ever runs here, so a snapshot that never genuinely reconciles — rejected, or
     * still deferred — can never trigger a refresh or move [lastKnownManifestRevision] (§20/§21).
     */
    private fun completeReconciliation(message: ResyncMessage.StateSnapshot) {
        val previousManifestRevision = lastKnownManifestRevision
        lastKnownManifestRevision = message.manifestRevision
        if (previousManifestRevision != null && previousManifestRevision != message.manifestRevision) {
            requestManifestRefresh()
        }
        _diagnostics.update {
            it.copy(
                requestPending = pendingRequestGeneration != null,
                lastOutcome = ResyncOutcome.RECONCILED,
                lastSnapshotManifestRevision = message.manifestRevision,
                lastSnapshotCommandSeq = message.commandSeq,
            )
        }
    }
}
