import Foundation

/// PROTOCOL §10: `0.5, 1, 2, 4, 8, 8, 8 … s` with ±20% jitter, budget 120s total, then
/// `DISCONNECTED`. Pure — no real clock reads, no `Task.sleep` — so it is testable without
/// sleeping (CLAUDE.md rule 12 / this session's brief §12).
public enum ReconnectPolicy {
    private static let ladderMs: [Int64] = [500, 1000, 2000, 4000, 8000]
    public static let maxTotalBudgetMs: Int64 = 120_000
    public static let jitterFraction = 0.2

    /// `attempt` is 0-indexed; the ladder holds at its last (8s) rung beyond its own length.
    public static func baseDelayMs(_ attempt: Int) -> Int64 {
        attempt < ladderMs.count ? ladderMs[attempt] : ladderMs[ladderMs.count - 1]
    }

    /// - Parameter randomFraction: in `[-1.0, 1.0]`, supplied by the caller's injected RNG.
    public static func jitteredDelayMs(_ attempt: Int, randomFraction: Double) -> Int64 {
        precondition(randomFraction >= -1.0 && randomFraction <= 1.0, "randomFraction must be in [-1, 1]")
        let base = baseDelayMs(attempt)
        let jitter = Int64(Double(base) * jitterFraction * randomFraction)
        return max(0, base + jitter)
    }
}

/// Why a reconnect loop did or did not start (ARCHITECTURE §3 rule 6 / PROTOCOL §4.2, §4.6).
public enum LinkLossReason: Sendable {
    case network
    case bye
    case duplicateConnection
    case userEnded
}

/// One run of the reconnect ladder: the ownership token a run's writes are checked against, and the
/// one an exhaustion hands to its caller so the caller can commit it
/// (`ReconnectController.commitExhaustion`). Compared by identity; it carries nothing, because
/// "whose?" is the only question it answers.
public final class ReconnectRun: Sendable {
    init() {}
}

/// Drives `ReconnectPolicy`'s ladder against real time via an injectable delay function, so unit
/// tests can replace `delayNs` with a no-op recorder instead of sleeping (CLAUDE.md rule 12).
/// `BYE`, `duplicate_connection` and a deliberate user end never reach this controller at all —
/// the caller checks `LinkLossReason` before starting it.
///
/// **A ladder belongs to one run, and a run ends the moment any connection is promoted**
/// (STATUS §4 problem 125; the Android `ReconnectController` is the same rule). The ladder is not
/// the only way a reconnect completes: the peer's own ladder can dial in, and that inbound
/// promotion reconnects the session without this ladder's attempt ever succeeding.
/// `connectionPromoted()` is therefore the one end of a run that is not the ladder's own — it used
/// to be a bare `reset()`, which left the ladder dialling inside the live session with a freshly
/// zeroed budget, exhausting 120 s later into a connected session and leaving the *next* link loss
/// with no budget at all. Every write the loop makes — the budget, the attempt counter,
/// `reconnectCount` — is made only while its run is still the current one, so a run that has ended
/// can touch nothing, whether or not it was cancelled.
///
/// **Exhaustion is a decision here and a commit at the caller.** Deciding the budget is spent and
/// acting on it are two steps, and a promotion can land between them. So `onExhausted` receives the
/// deciding `ReconnectRun` and must commit it with `commitExhaustion(_:)` inside the caller's own
/// connection-ownership critical section — the same synchronous stretch of `ControlSessionManager`'s
/// actor in which `promote` calls `connectionPromoted()`. Exactly one of the two then ends the run.
///
/// **A lock, not an actor** (review round 2). Those two calls must be *synchronous* from the
/// manager's actor: an `await` into a separate actor between the ownership change and the run's end
/// is a re-entrancy point, which is exactly where the stale exhaustion got in. It is the same shape
/// as the Android controller.
public final class ReconnectController: @unchecked Sendable {
    /// Guards every mutable field below.
    private let lock = NSLock()
    private var attempt = 0
    private var elapsedMs: Int64 = 0
    private var task: Task<Void, Never>?
    /// The run that owns the budget, or nil. A run that is not this one may write nothing.
    private var currentRun: ReconnectRun?
    /// The run whose `onAttempt` is executing right now, or nil; see `connectionPromoted()`.
    private var attemptInFlightRun: ReconnectRun?
    private var count = 0

    public var reconnectCount: Int { lock.withLock { count } }

    private let randomFraction: @Sendable () -> Double
    private let delayMs: @Sendable (Int64) async -> Void

    public init(randomFraction: @escaping @Sendable () -> Double, delayMs: @escaping @Sendable (Int64) async -> Void) {
        self.randomFraction = randomFraction
        self.delayMs = delayMs
    }

    /// Starts (or restarts) the retry loop. `onAttempt` returns `true` on success (stops the loop
    /// and resets it for next time) or `false` to keep retrying. `onExhausted` is called once the
    /// 120s budget is spent with no success, never for a run that has already ended, and must
    /// `commitExhaustion(_:)` before acting (see the type's comment).
    ///
    /// A restart continues the budget it finds; only a promoted connection (`connectionPromoted()`)
    /// or `reset()` refreshes it. Every production `start` follows a promotion (`RECONNECTING` is
    /// reachable only from a connected session), so each link loss gets a full budget.
    ///
    /// **Must check `Task.isCancelled` explicitly** (this session's brief §9/§10). `delayMs`'s
    /// production implementation is `try? await Task.sleep(...)` — the `try?` is there so a
    /// timer tick never throws into this loop, but it also swallows the `CancellationError`
    /// `Task.sleep` throws when `cancel()` cancels this task. Without an explicit check, a
    /// cancelled loop would keep running to completion in the background, invisible to
    /// `cancel()`'s caller and still calling `onAttempt` on a schedule no one asked for.
    public func start(
        onAttempt: @escaping @Sendable () async -> Bool,
        onExhausted: @escaping @Sendable (ReconnectRun) async -> Void
    ) {
        cancel()
        let run = ReconnectRun()
        lock.withLock {
            currentRun = run
            task = Task { await self.runLadder(run, onAttempt: onAttempt, onExhausted: onExhausted) }
        }
    }

    private func runLadder(
        _ run: ReconnectRun,
        onAttempt: @Sendable () async -> Bool,
        onExhausted: @Sendable (ReconnectRun) async -> Void
    ) async {
        while let delay = nextDelayIfOwned(run) {
            if Task.isCancelled { return }
            await delayMs(delay)
            if Task.isCancelled || !beginAttemptIfOwned(run, delay: delay) { return }
            let succeeded = await onAttempt()
            let ended: Bool = lock.withLock {
                if attemptInFlightRun === run { attemptInFlightRun = nil }
                if succeeded && currentRun === run { endRunLocked() }
                return succeeded
            }
            if ended { return }
        }
        // Only a decision: the run stays current until the caller commits it.
        guard !Task.isCancelled, owns(run) else { return }
        await onExhausted(run)
    }

    private func owns(_ run: ReconnectRun) -> Bool { lock.withLock { currentRun === run } }

    /// Nil when the budget is spent or `run` has ended.
    private func nextDelayIfOwned(_ run: ReconnectRun) -> Int64? {
        let fraction = randomFraction()
        return lock.withLock {
            guard currentRun === run, elapsedMs < ReconnectPolicy.maxTotalBudgetMs else { return nil }
            return ReconnectPolicy.jitteredDelayMs(attempt, randomFraction: fraction)
        }
    }

    private func beginAttemptIfOwned(_ run: ReconnectRun, delay: Int64) -> Bool {
        lock.withLock {
            guard currentRun === run else { return false }
            elapsedMs += delay
            attempt += 1
            count += 1
            attemptInFlightRun = run
            return true
        }
    }

    /// Ends `run` as exhausted if, and only if, it is still the run that owns the budget, and says
    /// whether it did. False means a promotion, a restart or a cancel ended it first, and the
    /// exhaustion must have no effect. The spent budget is left spent: only a promotion refreshes it.
    public func commitExhaustion(_ run: ReconnectRun) -> Bool {
        lock.withLock {
            guard currentRun === run else { return false }
            currentRun = nil
            task = nil
            return true
        }
    }

    /// A connection was promoted — by this ladder's own attempt, by the peer dialling in, or by a
    /// first dial — so whatever run is in progress is over, and the next link loss starts from a
    /// full budget. The caller calls this in the same synchronous stretch in which it takes
    /// ownership of the connection, so a pending exhaustion either committed before it or cannot.
    ///
    /// A run parked in its delay holds nothing and is cancelled. A run whose attempt is in flight is
    /// **not** cancelled: that attempt may be the very task performing this promotion (its own dial
    /// won), and cancelling it would cancel the rest of `promote`; or it may be partway through a
    /// handshake whose connection only it closes. It is retired instead — its run is no longer
    /// current — and it ends at its next ownership check without writing anything.
    public func connectionPromoted() {
        let parked: Task<Void, Never>? = lock.withLock {
            let running = (currentRun != nil && attemptInFlightRun !== currentRun) ? task : nil
            endRunLocked()
            return running
        }
        parked?.cancel()
    }

    public func cancel() {
        let ending: Task<Void, Never>? = lock.withLock {
            let running = task
            task = nil
            currentRun = nil
            return running
        }
        ending?.cancel()
    }

    /// Refreshes the budget. Call only with no run in progress (after `cancel()`); see
    /// `connectionPromoted()`.
    public func reset() {
        lock.withLock {
            attempt = 0
            elapsedMs = 0
        }
    }

    private func endRunLocked() {
        task = nil
        currentRun = nil
        attempt = 0
        elapsedMs = 0
    }
}
