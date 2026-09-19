package com.ridelink.app.resync

import com.ridelink.app.sync.SyncPlaybackCoordinator
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

/** The outcome of the most recent `STATE_REQUEST`/`STATE_SNAPSHOT` round trip, for FR-023. */
enum class ResyncOutcome { NONE, REQUESTED, RECONCILED, SEND_FAILED }

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
        scope.launch {
            syncPlaybackCoordinator.diagnostics.collect { diag ->
                if (diag.ingressDesynchronized && isLocalLeader == false) {
                    triggerRequest(session.currentAuthGeneration, desync = true)
                }
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
            val sent = session.resync.send(ResyncMessage.StateRequest)
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
     * A follower's reconciliation. [pendingRequestGeneration] is cleared **before** the reconcile
     * call so a snapshot that itself provokes another trigger (it cannot, today, but a future
     * caller must not find a stale "still pending" flag) never wedges the gate shut.
     *
     * The playback/queue portion is reconciled unconditionally by delegating to
     * [SyncPlaybackCoordinator.onStateSnapshot] — that call's own role/generation checks are what
     * decide whether **this device** may actually apply it (a foreign-generation or non-follower
     * delivery is a safe no-op there, never partial). The manifest portion is reconciled here,
     * gated on an actual revision difference (§20/§21): the first snapshot's revision is recorded
     * but never triggers a refresh of its own — [SharedLibraryCoordinator.requestCatalogue] already
     * ran unconditionally from the same [ControlEvent.Connected] that caused this snapshot to be
     * requested in the first place ([onConnected]), so a second request here would be redundant.
     * A **later** snapshot (a mid-ride desync resync, not the reconnect one) whose revision has
     * moved since is the case this exists for: nothing else would notice that change until the next
     * full session boundary.
     */
    private suspend fun handleStateSnapshot(
        message: ResyncMessage.StateSnapshot,
        generation: Long,
    ) {
        pendingRequestGeneration = StateResyncGate.onSnapshotObserved(pendingRequestGeneration, generation)
        syncPlaybackCoordinator.onStateSnapshot(message, generation)
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
