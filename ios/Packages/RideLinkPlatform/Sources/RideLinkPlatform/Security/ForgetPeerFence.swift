import Foundation
import RideLinkCore

/// Orders a user's *Forget peer* against the start of the next session (ADR-012 Amendment A2, STATUS §4
/// problem 126's review follow-up).
///
/// `SessionCoordinator.forgetPeer` reads `ForgetPeerPolicy` on the main actor and then hops to the
/// `ControlSessionManager` actor to remove the pin. During that hop the main actor is free, so *Find
/// other phone* can start a new session, and that session can dial, read the pin, pass the trust gate
/// and authenticate before the forget's write is scheduled. The write would then remove the pin of a
/// live session's peer, which Amendment A1 point 3 forbids. A forget is admitted by one idle period,
/// and once a session has begun that admission is spent.
///
/// The admission is taken in the **same synchronous step** as the coordinator's status read, and
/// `sessionBegan()` is called in the **same synchronous step** as the FSM's `IDLE -> DISCOVERING`,
/// before the new session does anything. The write runs under this fence's lock only if no session
/// has begun since its admission. So either the write lands first and the new session reads a store
/// without the pin, or the session begins first and the forget is refused with nothing changed. A
/// forget can never land inside a session that has started.
///
/// A plain lock, not an actor: `sessionBegan()` must be synchronous with the FSM transition, and an
/// `await` there would reopen the window this closes. The write it may wait behind is one atomic file
/// replace of the trusted-peer list, and it is held only in the rare case the two calls overlap.
///
/// This adds no teardown path. A refused forget ends nothing. `retireSession` remains the one way a
/// session ends (ADR-026).
public final class ForgetPeerFence: @unchecked Sendable {
    private let lock = NSLock()
    private var sessionsBegun: UInt64 = 0

    public init() {}

    /// The admission for a forget in `status`, or nil when `ForgetPeerPolicy` refuses it. Call it in
    /// the same synchronous step as the status read it is given.
    public func admit(status: SessionStatus) -> ForgetPeerAdmission? {
        guard ForgetPeerPolicy.permits(status) else { return nil }
        return lock.withLock { ForgetPeerAdmission(fence: self, sessionsBegun: sessionsBegun) }
    }

    /// `IDLE` has ended: a session has begun. Every admission taken before this is spent. Call it in
    /// the same synchronous step as the transition, before any of the new session's work starts. It
    /// waits for a forget write that is already in progress, so that write is ordered before the
    /// session.
    public func sessionBegan() {
        lock.withLock { sessionsBegun &+= 1 }
    }

    fileprivate func performIfNoSessionBegan(since admitted: UInt64, _ write: () throws -> Void) rethrows -> Bool {
        try lock.withLock {
            guard sessionsBegun == admitted else { return false }
            try write()
            return true
        }
    }
}

/// One idle period's permission to forget a peer. Captured, compared and never re-read: a forget
/// asks whether *its* idle period is still the current one, not whether some session is live now.
public struct ForgetPeerAdmission: Sendable {
    fileprivate let fence: ForgetPeerFence
    fileprivate let sessionsBegun: UInt64

    /// Runs `write` only if no session has begun since this admission, with the fence held across the
    /// write. Returns false, without running `write`, when one has. Rethrows `write`'s error.
    func perform(_ write: () throws -> Void) rethrows -> Bool {
        try fence.performIfNoSessionBegan(since: sessionsBegun, write)
    }
}
