import Foundation

/// Identifies who currently holds the one bulk-operation slot a session may have active at once
/// (ADR-023 Amendment A2) — the requester role (an outbound download, `.requester`) or the
/// provider role (serving a peer's `TRANSFER_REQUEST`, `.provider`). Exactly one owner at a time:
/// this mirrors, one layer up, the same exclusion `RideLinkPlatform.TransferManager`'s
/// `transferInProgress` (Android: `BulkTransportManager`'s `activeTransferMutex`) already enforces
/// around the real socket — the coordinator needs its own copy of that decision so it knows
/// *before* ever sending an offer or opening a fetch which single operation is entitled to own
/// `TransferId`-keyed `TRANSFER_CANCEL` routing and session-boundary invalidation.
public enum BulkOperationOwner: Equatable, Sendable {
    case requester(transferId: TransferId, contentHash: ContentHash)
    case provider(transferId: TransferId, contentHash: ContentHash, peerSpki: SpkiHash, sessionGeneration: Int64)

    public var transferId: TransferId {
        switch self {
        case .requester(let transferId, _): return transferId
        case .provider(let transferId, _, _, _): return transferId
        }
    }
}

/// Closure-audit Finding A (provider-side transfer ownership) and its cross-role counterpart
/// (brief §17/§18): a pure, mirrored (`com.ridelink.core.transfer.BulkOperationGate` on Android)
/// gate for the one bulk operation — requester or provider, never both — a session may have
/// active. `transfer_id` is a fresh, unpredictable ULID per ADR-023 §2 and never reused across
/// operations, so keying release on it (rather than a separate opaque token, the way
/// `OperationFence` needs one) is already collision-safe: `tryAcquire` refuses whenever a slot is
/// already held, so two live operations can never share one id, and `releaseIfOwner` silently
/// no-ops for anyone but the current holder — the same "a stale/late release cannot clear a newer
/// operation" property `OperationFence` gives download-state writes, applied here to *ownership*
/// itself.
///
/// **Not thread-safe by design**, exactly like `OperationFence`: every call site in this codebase
/// confines a given instance to `@MainActor` (`SharedLibraryCoordinator` is itself `@MainActor`),
/// and every method here is a single synchronous call with no suspension inside it. This is a
/// plain, non-`actor` class — not `Sendable` — deliberately, so a use from off the main actor is a
/// compile error rather than a silent race.
public final class BulkOperationGate {
    private var owner: BulkOperationOwner?

    public init() {}

    /// The current holder, if any — read-only, for tests and diagnostics.
    public var current: BulkOperationOwner? { owner }

    /// Attempts to acquire the slot for `candidate`. Returns `false`, leaving the existing owner
    /// untouched, if another operation already holds it — the caller must not overwrite ownership,
    /// must not send a `TRANSFER_OFFER`, and must not open a bulk fetch. There is deliberately no
    /// queueing here (brief §19/§20): a denied caller either lets its own peer negotiation time out
    /// (provider role) or fails this attempt cleanly (requester role).
    @discardableResult
    public func tryAcquire(_ candidate: BulkOperationOwner) -> Bool {
        guard owner == nil else { return false }
        owner = candidate
        return true
    }

    /// Releases the slot, but only if `transferId` still names the current holder. A late release
    /// from a superseded operation — Finding A's "cleanup race" (brief §5) — is silently ignored,
    /// never clearing whatever fresher operation has since acquired the slot.
    public func releaseIfOwner(_ transferId: TransferId) {
        if owner?.transferId == transferId { owner = nil }
    }

    /// True if `transferId` is the transfer currently holding the slot — the one check
    /// `TRANSFER_CANCEL` routing needs (brief §4/§6): a cancel for a stale, foreign, queued, or
    /// already-finished id is never true here, and can therefore never reach the real active
    /// socket.
    public func isOwner(_ transferId: TransferId) -> Bool {
        owner?.transferId == transferId
    }

    /// Session boundary (brief §11/§13): unconditionally frees the slot no matter who holds it —
    /// paired with `TransferManager.close()` force-closing whatever socket that holder's
    /// `serve`/`fetch` call was blocked on, so the old holder's own eventual cleanup call finds
    /// nothing left to (mis)clear.
    public func invalidate() {
        owner = nil
    }
}
