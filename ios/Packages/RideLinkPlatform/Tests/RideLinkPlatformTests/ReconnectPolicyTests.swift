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
