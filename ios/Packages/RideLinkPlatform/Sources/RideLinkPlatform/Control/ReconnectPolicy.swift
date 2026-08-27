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

/// Drives `ReconnectPolicy`'s ladder against real time via an injectable delay function, so unit
/// tests can replace `delayNs` with a no-op recorder instead of sleeping (CLAUDE.md rule 12).
/// `BYE`, `duplicate_connection` and a deliberate user end never reach this controller at all —
/// the caller checks `LinkLossReason` before starting it.
public actor ReconnectController {
    private var attempt = 0
    private var elapsedMs: Int64 = 0
    private var task: Task<Void, Never>?

    public private(set) var reconnectCount = 0

    private let randomFraction: @Sendable () -> Double
    private let delayMs: @Sendable (Int64) async -> Void

    public init(randomFraction: @escaping @Sendable () -> Double, delayMs: @escaping @Sendable (Int64) async -> Void) {
        self.randomFraction = randomFraction
        self.delayMs = delayMs
    }

    /// Starts (or restarts) the retry loop. `onAttempt` returns `true` on success (stops the loop
    /// and resets it for next time) or `false` to keep retrying. `onExhausted` fires once the
    /// 120s budget is spent with no success.
    ///
    /// **Must check `Task.isCancelled` explicitly** (this session's brief §9/§10). `delayMs`'s
    /// production implementation is `try? await Task.sleep(...)` — the `try?` is there so a
    /// timer tick never throws into this loop, but it also swallows the `CancellationError`
    /// `Task.sleep` throws when `cancel()` cancels this task. Without an explicit check, a
    /// cancelled loop would keep running to completion in the background, invisible to
    /// `cancel()`'s caller and still calling `onAttempt` on a schedule no one asked for.
    public func start(onAttempt: @escaping @Sendable () async -> Bool, onExhausted: @escaping @Sendable () async -> Void) {
        cancel()
        task = Task {
            while self.remainingBudget() {
                if Task.isCancelled { return }
                let fraction = self.randomFraction()
                let (currentAttempt, delay) = self.nextDelay(fraction: fraction)
                await self.delayMs(delay)
                if Task.isCancelled { return }
                self.recordElapsed(delay, attempt: currentAttempt)
                if await onAttempt() {
                    self.reset()
                    return
                }
            }
            if !Task.isCancelled {
                await onExhausted()
            }
        }
    }

    private func remainingBudget() -> Bool { elapsedMs < ReconnectPolicy.maxTotalBudgetMs }

    private func nextDelay(fraction: Double) -> (Int, Int64) {
        let current = attempt
        return (current, ReconnectPolicy.jitteredDelayMs(current, randomFraction: fraction))
    }

    private func recordElapsed(_ delay: Int64, attempt currentAttempt: Int) {
        elapsedMs += delay
        attempt = currentAttempt + 1
        reconnectCount += 1
    }

    public func cancel() {
        task?.cancel()
        task = nil
    }

    public func reset() {
        attempt = 0
        elapsedMs = 0
    }
}
