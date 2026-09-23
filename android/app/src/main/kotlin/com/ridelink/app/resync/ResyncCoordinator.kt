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
 *
 * [CANCELLED] (independent-review round 4, Blocker 2) is the fourth terminal state a reconciliation
 * can reach, and it exists because the other three could not express it. A retained reconciliation is
 * **discarded** — not applied, not rejected, not still pending — whenever the ride segment or the
 * control lifetime that owns it ends: End Ride, "Play locally", a link loss, a fail-closed outbound
 * path. Reporting that as [NONE] would be indistinguishable from "nothing has ever been asked", and
 * reporting it as [RECONCILED] is the defect. Local state only; no wire change.
 */
enum class ResyncOutcome { NONE, REQUESTED, RECONCILED, SEND_FAILED, DEFERRED, CANCELLED }

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
     * same shape. Ownership is [DeferredReconciliation.id] **and** the generation, and the record is
     * taken before the suspending apply so a cancellation cannot miss it — see [handleStateSnapshot].
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
        val id: Long,
        val generation: Long,
        val message: ResyncMessage.StateSnapshot,
    )

    @Volatile
    private var deferredReconciliation: DeferredReconciliation? = null

    /**
     * Mints [DeferredReconciliation.id] (independent-review round 4, Blocker 2).
     *
     * **The generation is not a unique owner, and this is.** End Ride deliberately does *not* move
     * the authenticated control generation — the connection, the pairing and the session all stay
     * alive on purpose — so two reconciliation obligations can exist one after another under a single
     * generation: S1 accepted and deferred in ride 1, discarded when the user ends that ride, then S2
     * accepted in ride 2 under the very same generation. A generation-keyed completion could not tell
     * them apart, so S2's success completed **S1**, publishing ride 1's
     * `command_seq`/`manifest_revision` as a reconciliation that never happened.
     *
     * Strictly increasing, process-local, never on the wire, and deliberately not derived from any
     * live value — a live value is exactly what cannot identify an obligation whose lifetime has
     * already ended. Starts at 1 so no valid id is the default-initialised zero of anything
     * downstream.
     */
    private var nextObligationId: Long = 1

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
        //
        // Independent-review round 4, Blocker 2: matched on the obligation's **id and** generation.
        // The generation alone is not ownership when End Ride leaves the generation untouched, which
        // it deliberately does — two obligations can then exist sequentially under one generation,
        // and the second one's success completed the first.
        syncPlaybackCoordinator.onReconciliationApplied = { obligation, generation ->
            val deferred = deferredReconciliation
            if (deferred != null && deferred.id == obligation && deferred.generation == generation) {
                deferredReconciliation = null
                completeReconciliation(deferred.message)
            }
        }
        // Independent-review round 4, Blocker 2: the other terminal result. End Ride discards the
        // retained reconciliation inside [SyncPlaybackCoordinator] (`leaveSynchronizedMode` clears
        // `deferredEvents` outright, which is correct — the ride that asked for it is over), and the
        // obligation recorded *here* had no way to learn that. It may **never** produce
        // [ResyncOutcome.RECONCILED]: that is reserved for authoritative state genuinely converging.
        syncPlaybackCoordinator.onReconciliationCancelled = { obligation, generation ->
            val deferred = deferredReconciliation
            if (deferred != null && deferred.id == obligation && deferred.generation == generation) {
                deferredReconciliation = null
                _diagnostics.update {
                    it.copy(requestPending = pendingRequestGeneration != null, lastOutcome = ResyncOutcome.CANCELLED)
                }
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
        // Independent-review round 4, Blocker 2. The obligation is minted **and recorded** before the
        // suspending call below, and that ordering is load-bearing in both directions:
        //
        // - [SyncPlaybackCoordinator.onStateSnapshot] suspends. A cancellation raised inside that
        //   window — an End Ride, a link loss — must find something to cancel, or it would be raised
        //   against an obligation this function records a moment later, and the discarded snapshot
        //   would stay alive.
        // - The cancellation may equally arrive *after* this function resumes. Recording up front
        //   makes both orders converge: the cancel clears the record whenever it lands, and every
        //   branch below acts only if its own id is still the recorded one.
        //
        // Recording early also replaces round 3's `supersededByNewerObligation` generation comparison
        // outright. Ids are minted in arrival order, so a snapshot whose outcome comes back after a
        // newer one has been recorded simply fails its own identity check — the same protection, by
        // comparison of two recorded owners rather than of two generations, and it now covers two
        // obligations that share a generation as well.
        val obligation = nextObligationId
        nextObligationId += 1
        deferredReconciliation = DeferredReconciliation(obligation, generation, message)
        val outcome = syncPlaybackCoordinator.onStateSnapshot(message, generation, obligation)
        when (outcome) {
            StateSnapshotOutcome.APPLIED,
            StateSnapshotOutcome.DEFERRED_CLOCK,
            StateSnapshotOutcome.DEFERRED_CONTENT,
            // ADR-024 Amendment A11: retained for local work capacity, carrying this same
            // obligation id, and re-attempted by the drain — identical treatment for the same
            // reason `DEFERRED_CONTENT` gets it.
            StateSnapshotOutcome.DEFERRED_CAPACITY,
            -> {
                // §21: the **wire** round trip is satisfied by any of the three — a snapshot for the
                // live generation arrived, so there is nothing left to request.
                //
                // **Round 4's own fresh-fix defect, found by CI at the exact head.** This clear was
                // briefly placed *after* the obligation-identity guard below, which made the wire
                // obligation conditional on the **reconciliation** obligation surviving — precisely
                // the conflation round 3's Blocker B existed to remove. A snapshot whose
                // reconciliation was cancelled inside the call above then left `requestPending` true
                // with nothing outstanding left to clear it.
                pendingRequestGeneration = StateResyncGate.onSnapshotObserved(pendingRequestGeneration, generation)
                // Whether *this* obligation is still the one being tracked. A cancellation (End Ride,
                // a lifetime boundary) or a newer snapshot during the call above means it is not, and
                // everything after this belongs to a lifetime that has already been answered.
                if (deferredReconciliation?.id != obligation) {
                    _diagnostics.update { it.copy(requestPending = pendingRequestGeneration != null) }
                    return
                }
                if (outcome == StateSnapshotOutcome.APPLIED) {
                    deferredReconciliation = null
                    completeReconciliation(message)
                } else {
                    // `DEFERRED_CONTENT` (section 22) gets identical treatment to `DEFERRED_CLOCK`:
                    // since round 3's Blocker A the retained snapshot is drained — and
                    // `onReconciliationApplied` raised — for whichever precondition resolves, so one
                    // branch correctly serves both.
                    _diagnostics.update {
                        it.copy(requestPending = pendingRequestGeneration != null, lastOutcome = ResyncOutcome.DEFERRED)
                    }
                }
            }
            // Independent-review round 4, §17: the snapshot **did** arrive for the live generation —
            // it was refused because the ride segment that authorised its reconciliation ended. The
            // wire round trip is satisfied exactly as above, and only the reconciliation is
            // cancelled. Conflating this with REJECTED_STALE (which never answered the outstanding
            // request at all) left `requestPending` true with nothing that could clear it.
            StateSnapshotOutcome.REJECTED_RIDE -> {
                pendingRequestGeneration = StateResyncGate.onSnapshotObserved(pendingRequestGeneration, generation)
                val owned = deferredReconciliation?.id == obligation
                releaseObligation(obligation)
                _diagnostics.update {
                    it.copy(
                        requestPending = pendingRequestGeneration != null,
                        lastOutcome = if (owned) ResyncOutcome.CANCELLED else it.lastOutcome,
                    )
                }
            }
            // A rejected snapshot must not falsely complete the request (§21) or mutate manifest
            // bookkeeping (§20). The obligation recorded up front is released — nothing was retained
            // for it, so nothing will ever report on it — but only if it is still ours to release.
            StateSnapshotOutcome.REJECTED_STALE -> releaseObligation(obligation)
            StateSnapshotOutcome.REJECTED_ROLE -> {
                releaseObligation(obligation)
                _diagnostics.update { it.copy(roleViolationCount = it.roleViolationCount + 1) }
            }
        }
    }

    /** Releases [deferredReconciliation] only if it is still the obligation this call recorded. */
    private fun releaseObligation(obligation: Long) {
        if (deferredReconciliation?.id == obligation) deferredReconciliation = null
    }

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
