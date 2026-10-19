import XCTest
@testable import RideLinkPlatform

/// PROTOCOL §10: `0.5, 1, 2, 4, 8, 8, 8 … s`, ±20% jitter, 120s budget. Pure — no real
/// `Task.sleep` anywhere (CLAUDE.md rule 12).
final class ReconnectPolicyTests: XCTestCase {
    func testLadderMatchesProtocolSection10Exactly() {
        XCTAssertEqual(ReconnectPolicy.baseDelayMs(0), 500)
        XCTAssertEqual(ReconnectPolicy.baseDelayMs(1), 1000)
        XCTAssertEqual(ReconnectPolicy.baseDelayMs(2), 2000)
        XCTAssertEqual(ReconnectPolicy.baseDelayMs(3), 4000)
        XCTAssertEqual(ReconnectPolicy.baseDelayMs(4), 8000)
        XCTAssertEqual(ReconnectPolicy.baseDelayMs(5), 8000)
        XCTAssertEqual(ReconnectPolicy.baseDelayMs(50), 8000)
    }

    func testJitterStaysWithinPlusMinus20PercentOfTheBaseDelay() {
        for attempt in 0...6 {
            let base = ReconnectPolicy.baseDelayMs(attempt)
            let min = ReconnectPolicy.jitteredDelayMs(attempt, randomFraction: -1.0)
            let max = ReconnectPolicy.jitteredDelayMs(attempt, randomFraction: 1.0)
            XCTAssertEqual(min, Int64(Double(base) * 0.8))
            XCTAssertEqual(max, Int64(Double(base) * 1.2))
        }
    }

    func testJitteredDelayIsNeverNegative() {
        let delay = ReconnectPolicy.jitteredDelayMs(0, randomFraction: -1.0)
        XCTAssertGreaterThan(delay, 0, "even worst-case negative jitter on the smallest rung must stay positive")
    }

    func testControllerNeverBusyLoopsEveryAttemptPrecededByANonzeroRecordedDelay() async throws {
        actor Recorder {
            var delays: [Int64] = []
            func record(_ ms: Int64) { delays.append(ms) }
        }
        let recorder = Recorder()
        let controller = ReconnectController(randomFraction: { -0.3 }, delayMs: { ms in await recorder.record(ms) })

        actor Attempts {
            var count = 0
            func increment() -> Int { count += 1; return count }
        }
        let attempts = Attempts()

        try await withTimeout(seconds: 5) {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                Task {
                    await controller.start(
                        onAttempt: {
                            let n = await attempts.increment()
                            if n >= 3 { continuation.resume() }
                            return n >= 3
                        },
                        onExhausted: { continuation.resume() }
                    )
                }
            }
        }

        let finalCount = await attempts.count
        let reconnectCount = await controller.reconnectCount
        let delays = await recorder.delays
        XCTAssertEqual(finalCount, 3)
        XCTAssertEqual(reconnectCount, 3)
        XCTAssertTrue(delays.allSatisfy { $0 > 0 }, "every attempt must be preceded by a positive delay: \(delays)")
    }

    // MARK: - STATUS §4 problem 125: a promotion ends the run, whichever connection it promoted

    func testAPromotionWhileTheLadderIsParkedEndsTheRunAndTheNextRunHasAFullBudget() async throws {
        let gate = DelayGate()
        let controller = ReconnectController(randomFraction: { 0.1 }, delayMs: { _ in await gate.wait() })
        let first = Tally()
        await controller.start(onAttempt: { await first.attempt(); return false }, onExhausted: { await first.exhaust() })
        try await poll { await gate.parkedCount >= 1 }

        await controller.connectionPromoted() // e.g. the peer dialled in
        await gate.openForever()
        try await Task.sleep(nanoseconds: Self.settleNs)
        let firstAttempts = await first.attempts
        let firstExhausted = await first.exhausted
        XCTAssertEqual(firstAttempts, 0, "the ended run made no attempt")
        XCTAssertFalse(firstExhausted, "and reported no exhaustion")

        let second = Tally()
        await controller.start(onAttempt: { await second.attempt(); return false }, onExhausted: { await second.exhaust() })
        try await poll { await second.exhausted }
        let secondAttempts = await second.attempts
        XCTAssertGreaterThanOrEqual(secondAttempts, 16, "the next link loss gets a full budget")
        let firstExhaustedLater = await first.exhausted
        XCTAssertFalse(firstExhaustedLater)
    }

    func testAPromotionDuringAnAttemptRetiresTheRunWithoutCancellingTheAttempt() async throws {
        let controller = ReconnectController(randomFraction: { 0.1 }, delayMs: { _ in })
        let gate = DelayGate()
        let tally = Tally()
        await controller.start(
            onAttempt: {
                await tally.attempt()
                await gate.wait() // e.g. a handshake whose connection only this attempt closes
                await tally.complete(cancelled: Task.isCancelled)
                return false // this dial lost to the peer's, which was promoted meanwhile
            },
            onExhausted: { await tally.exhaust() }
        )
        try await poll { await gate.parkedCount >= 1 }
        await controller.connectionPromoted()
        await gate.openForever()
        try await poll { await tally.completions >= 1 }
        try await Task.sleep(nanoseconds: Self.settleNs)

        let attempts = await tally.attempts
        let cancelled = await tally.sawCancellation
        let exhausted = await tally.exhausted
        XCTAssertFalse(cancelled, "an in-flight attempt is never cancelled")
        XCTAssertEqual(attempts, 1, "no attempt after the promotion")
        XCTAssertFalse(exhausted, "a retired run never reports exhaustion")
    }

    func testAPromotionPerformedByTheLaddersOwnAttemptDoesNotCancelThatAttempt() async throws {
        let box = ControllerBox()
        let controller = ReconnectController(randomFraction: { 0.1 }, delayMs: { _ in })
        await box.set(controller)
        let tally = Tally()
        await controller.start(
            onAttempt: {
                await tally.attempt()
                await box.get()?.connectionPromoted() // promote() runs on this very task
                await tally.complete(cancelled: Task.isCancelled)
                return true
            },
            onExhausted: { await tally.exhaust() }
        )
        try await poll { await tally.completions >= 1 }
        try await Task.sleep(nanoseconds: Self.settleNs)
        let cancelled = await tally.sawCancellation
        let attempts = await tally.attempts
        let exhausted = await tally.exhausted
        XCTAssertFalse(cancelled, "the promoting attempt is not cancelled under itself")
        XCTAssertEqual(attempts, 1)
        XCTAssertFalse(exhausted)
    }

    private static let settleNs: UInt64 = 200_000_000

    private func poll(_ condition: @escaping @Sendable () async -> Bool) async throws {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            if await condition() { return }
            try await Task.sleep(nanoseconds: 5_000_000)
        }
        throw ControlTransportError.notReady
    }

    private func withTimeout<T: Sendable>(seconds: Double, _ operation: @escaping @Sendable () async -> T) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask { await operation() }
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                throw ControlTransportError.notReady
            }
            let result = try await group.next()!
            group.cancelAll()
            return result
        }
    }
}

private actor Tally {
    private(set) var attempts = 0
    private(set) var completions = 0
    private(set) var sawCancellation = false
    private(set) var exhausted = false

    func attempt() { attempts += 1 }
    func complete(cancelled: Bool) {
        completions += 1
        if cancelled { sawCancellation = true }
    }
    func exhaust() { exhausted = true }
}

/// Parks every caller until `openForever()`; afterwards every call returns at once.
private actor DelayGate {
    private var open = false
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private(set) var parkedCount = 0

    func wait() async {
        if open { return }
        parkedCount += 1
        await withCheckedContinuation { waiters.append($0) }
    }

    func openForever() {
        open = true
        let pending = waiters
        waiters = []
        pending.forEach { $0.resume() }
    }
}

private actor ControllerBox {
    private var controller: ReconnectController?
    func set(_ value: ReconnectController) { controller = value }
    func get() -> ReconnectController? { controller }
}
