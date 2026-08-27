import Foundation
import RideLinkCore

/// Tracks in-flight PING/PONG round-trips awaiting their PONG, keyed by the `t1_mono_us` value
/// echoed back on the wire (PROTOCOL §6). Extracted as its own type so its invariants — id
/// uniqueness, exactly-once resolution, and orphan-timeout cleanup — are provable with fast,
/// synchronous unit tests instead of only against a real actor and real sockets.
///
/// **Thread-safety.** Every operation is serialized by an internal `NSLock`, so this type is
/// genuinely safe under concurrent access on its own — not merely "safe because the only caller
/// happens to be a single actor." `ControlSessionManager` only ever touches it from its own
/// actor-isolated methods in production, but the lock means that is a scheduling convenience, not
/// a correctness requirement, which is what lets `@unchecked Sendable` be honestly justified here
/// (matching the precedent in `Framing.swift`'s `ControlConnection`/`ControlListener`).
///
/// **Root cause this exists to fix (CI stabilization, "Updated Phase 1a" follow-up):**
/// `sendPingAndAwait`'s original timeout `Task` slept out its **full** duration (up to 3s)
/// regardless of whether the PONG had already arrived and resolved the request. Both the
/// keepalive loop (every 2s) and the clock-sync loop (11-sample burst, then every 10s) call
/// `sendPingAndAwait` on the same `ControlSessionManager`, so a burst of quick, successful PINGs
/// left a pile of orphaned timeout `Task`s asleep, all waking up ~3s later at nearly the same
/// wall-clock instant to hop onto the actor and do nothing. Under real CI contention (a shared,
/// throttled runner) that pile-up of near-simultaneous actor hops was observed to delay actual
/// PONG handling long enough to blow a *different*, unrelated PING's 3s timeout —
/// `PingRaceAndReconnectTests.testRepeatedClockBurstsAllCompleteQuickly` failed exactly this way
/// on GitHub Actions (run 33033917411) while passing locally every time. `succeed`/`fail` now
/// cancel the associated timeout `Task` the instant a request resolves, so a fast PONG never
/// leaves anything still sleeping.
final class PingRequestRegistry: @unchecked Sendable {
    struct Request {
        let continuation: CheckedContinuation<ClockSync.Sample, Error>
        var timeoutTask: Task<Void, Never>?
    }

    private let lock = NSLock()
    private var pending: [Int64: Request] = [:]

    var count: Int {
        lock.withLock { pending.count }
    }

    func isPending(_ id: Int64) -> Bool {
        lock.withLock { pending[id] != nil }
    }

    /// Reserves a collision-free `t1_mono_us` and registers the waiter under it **in one locked
    /// operation**, returning the id actually used. This must be atomic: reserving a free id and
    /// registering it as two separate locked calls would leave a window where two concurrent
    /// callers could both be told the same "free" id and then both register under it, silently
    /// overwriting one waiter with the other (exactly the hazard this type exists to close — "two
    /// outstanding PINGs must never receive the same t1").
    ///
    /// Two calls to `monotonicNowUs()` landing on the same microsecond — e.g. the keepalive loop
    /// and the clock-sync burst both issuing a PING around the same actor turn — would otherwise
    /// silently overwrite each other's entry in a plain dictionary keyed by `t1`, permanently
    /// orphaning the first request's continuation (it would never be resumed: its own timeout
    /// task, when it later fires, would resolve whichever request currently occupies that key —
    /// the *second* one — not itself). Bumping by whole microseconds on collision keeps the result
    /// a legitimate monotonic timestamp; PROTOCOL §6 requires only that PONG echo `t1` back
    /// unchanged, not that it be an untouched clock read, so this is not a wire change.
    ///
    /// No timeout task is attached yet — `t1` is only known once this returns, and the timeout
    /// task's own body needs `t1` to know which entry to fail, so `attachTimeout` follows
    /// immediately after in the caller. That gap has no correctness cost: it does not affect the
    /// waiter-before-write ordering (both calls happen synchronously, before either the caller's
    /// `withCheckedThrowingContinuation` closure returns or anything suspends), and a PONG arriving
    /// in that instant simply resolves the request early via `succeed`, same as any other case.
    @discardableResult
    func reserveAndRegister(startingFrom proposed: Int64, continuation: CheckedContinuation<ClockSync.Sample, Error>) -> Int64 {
        lock.withLock {
            var candidate = proposed
            while pending[candidate] != nil {
                candidate += 1
            }
            pending[candidate] = Request(continuation: continuation, timeoutTask: nil)
            return candidate
        }
    }

    /// Attaches the timeout task for a request already registered by `reserveAndRegister`. If the
    /// request already resolved in the brief gap between the two calls, `timeoutTask` is cancelled
    /// immediately instead of being left to sleep out its full duration for nothing.
    func attachTimeout(id: Int64, timeoutTask: Task<Void, Never>) {
        let stillPending = lock.withLock { () -> Bool in
            guard pending[id] != nil else { return false }
            pending[id]?.timeoutTask = timeoutTask
            return true
        }
        if !stillPending { timeoutTask.cancel() }
    }

    /// A PONG arrived: resolve success and cancel the now-unnecessary timeout task so it never
    /// fires and never wakes up later to do nothing (this is the orphan-timeout fix). Safely a
    /// no-op if `id` already resolved — a duplicate/late PONG, or a timeout that raced ahead.
    func succeed(id: Int64, with sample: ClockSync.Sample) {
        guard let request = lock.withLock({ pending.removeValue(forKey: id) }) else { return }
        request.timeoutTask?.cancel()
        request.continuation.resume(returning: sample)
    }

    /// A write failed, or the timeout itself fired: fail exactly once. Safely a no-op if `id`
    /// already resolved. Cancelling `timeoutTask` here is a harmless no-op when this is called
    /// *by* that same task after its own sleep completes; it matters when called from the
    /// write-failure path, where the timeout task is still asleep and must not fire later.
    func fail(id: Int64, with error: Error) {
        guard let request = lock.withLock({ pending.removeValue(forKey: id) }) else { return }
        request.timeoutTask?.cancel()
        request.continuation.resume(throwing: error)
    }

    /// Teardown: fail and cancel every outstanding request. Snapshots and clears under the lock,
    /// then resolves outside it, so resuming a continuation can never happen while the lock is
    /// held.
    func failAll(with error: Error) {
        let all = lock.withLock { () -> [Request] in
            let values = Array(pending.values)
            pending.removeAll()
            return values
        }
        for request in all {
            request.timeoutTask?.cancel()
            request.continuation.resume(throwing: error)
        }
    }
}

extension NSLock {
    fileprivate func withLock<T>(_ body: () throws -> T) rethrows -> T {
        lock()
        defer { unlock() }
        return try body()
    }
}
