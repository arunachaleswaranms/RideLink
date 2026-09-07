package com.ridelink.core.transfer

import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.SpkiHash
import com.ridelink.core.model.TransferId

/**
 * Identifies who currently holds the one bulk-operation slot a session may have active at once
 * (ADR-023 Amendment A2) — the requester role (an outbound download, [Requester]) or the provider
 * role (serving a peer's [com.ridelink.core.protocol.TransferMessage.Request], [Provider]). Exactly
 * one owner at a time: this mirrors, one layer up, the same exclusion
 * [com.ridelink.network.transfer.BulkTransportManager]'s `activeTransferMutex` /
 * `RideLinkPlatform.TransferManager`'s `transferInProgress` already enforce around the real socket
 * — the coordinator needs its own copy of that decision so it knows *before* ever sending an offer
 * or opening a fetch which single operation is entitled to own [TransferId]-keyed
 * `TRANSFER_CANCEL` routing and session-boundary invalidation.
 */
sealed class BulkOperationOwner {
    abstract val transferId: TransferId

    data class Requester(
        override val transferId: TransferId,
        val contentHash: ContentHash,
    ) : BulkOperationOwner()

    data class Provider(
        override val transferId: TransferId,
        val contentHash: ContentHash,
        val peerSpki: SpkiHash,
        val sessionGeneration: Long,
    ) : BulkOperationOwner()
}

/**
 * Closure-audit Finding A (provider-side transfer ownership) and its cross-role counterpart
 * (brief §17/§18): a pure, mirrored (`RideLinkCore.Transfer.BulkOperationGate`) gate for the one
 * bulk operation — requester or provider, never both — a session may have active. `transfer_id` is
 * a fresh, unpredictable ULID per ADR-023 §2 and never reused across operations, so keying release
 * on it (rather than a separate opaque token, the way [OperationFence] needs one) is already
 * collision-safe: [tryAcquire] refuses whenever a slot is already held, so two live operations can
 * never share one id, and [releaseIfOwner] silently no-ops for anyone but the current holder — the
 * same "a stale/late release cannot clear a newer operation" property [OperationFence] gives
 * download-state writes, applied here to *ownership* itself.
 *
 * **Not thread-safe by design**, exactly like [OperationFence]: every call site in this codebase
 * confines a given instance to one coroutine dispatcher (`Dispatchers.Main`, `AppContainer.appScope`),
 * and every method here is a single synchronous call with no suspension inside it — so a caller
 * never observes a torn check-then-set, without needing an explicit `Mutex`.
 */
class BulkOperationGate {
    private var owner: BulkOperationOwner? = null

    /** The current holder, if any — read-only, for tests and diagnostics. */
    val current: BulkOperationOwner? get() = owner

    /**
     * Attempts to acquire the slot for [candidate]. Returns `false`, leaving the existing owner
     * untouched, if another operation already holds it — the caller must not overwrite ownership,
     * must not send a `TRANSFER_OFFER`, and must not open a bulk fetch. There is deliberately no
     * queueing here (brief §19/§20): a denied caller either lets its own peer negotiation time out
     * (provider role) or fails this attempt cleanly (requester role).
     */
    fun tryAcquire(candidate: BulkOperationOwner): Boolean {
        if (owner != null) return false
        owner = candidate
        return true
    }

    /**
     * Releases the slot, but only if [transferId] still names the current holder. A late release
     * from a superseded operation — Finding A's "cleanup race" (brief §5) — is silently ignored,
     * never clearing whatever fresher operation has since acquired the slot.
     */
    fun releaseIfOwner(transferId: TransferId) {
        if (owner?.transferId == transferId) owner = null
    }

    /**
     * True if [transferId] is the transfer currently holding the slot — the one check
     * `TRANSFER_CANCEL` routing needs (brief §4/§6): a cancel for a stale, foreign, queued, or
     * already-finished id is never true here, and can therefore never reach the real active socket.
     */
    fun isOwner(transferId: TransferId): Boolean = owner?.transferId == transferId

    /**
     * ADR-023 Amendment A5 — the **whole** post-acquisition authorisation decision for a provider
     * operation, in one pure place: the slot is still [transferId]'s **and** the session that
     * authorised it is still the live one.
     *
     * A3 used [isOwner] alone as a proxy for the second half, on the reasoning that a session
     * boundary always calls [invalidate]. A5 found that reasoning holds only where the boundary is
     * *atomic*. It is not on iOS: `onSessionBoundary()` bumps its session epoch, then `await`s
     * `TransferManager.close()`, and only then calls [invalidate] — so throughout that `await` the
     * gate still names the old transfer while the live session has already moved on, and
     * [isOwner] alone answers `true` for an operation that is already stale. Android's boundary
     * runs the same three steps with no suspension between them, but the live generation is bumped
     * by `ControlSessionManager` *before* the `Connected` event that triggers the boundary is even
     * dispatched, so the same window exists there too — narrower, and reached by a different
     * route, but the same shape.
     *
     * Joining both halves here rather than at each call site is what makes the rule testable as a
     * unit on both platforms, including on iOS where the coordinator that calls it has no test
     * target at all (ADR-023 Amendment A3).
     */
    fun stillAuthorises(
        transferId: TransferId,
        authorisation: ProviderSessionContext,
        liveGeneration: Long,
        livePeerSpki: SpkiHash?,
    ): Boolean = isOwner(transferId) && authorisation.isStillCurrent(liveGeneration, livePeerSpki)

    /**
     * Session boundary (brief §11/§13): unconditionally frees the slot no matter who holds it —
     * paired with [com.ridelink.network.transfer.BulkTransportManager.close] force-closing whatever
     * socket that holder's `serve`/`fetch` call was blocked on, so the old holder's own eventual
     * cleanup call finds nothing left to (mis)clear.
     */
    fun invalidate() {
        owner = null
    }
}
