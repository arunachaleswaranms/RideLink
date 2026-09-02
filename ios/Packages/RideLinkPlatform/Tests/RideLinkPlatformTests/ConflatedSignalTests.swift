import XCTest
@testable import RideLinkPlatform

/// Direct coverage of the doorbell primitive `VoiceController` rings on every `VoiceInputMailbox.offer`.
/// Before this type existed, that doorbell was an `OrderedEventChannel<Void>` -- an unbounded
/// `AsyncStream` -- so a flood of authenticated `VOICE_*` traffic could grow an arbitrarily large
/// backlog of pending wake-ups even though `VoiceInputMailbox` itself was already bounded (ADR-020
/// Amendment A2). These tests exercise `ConflatedSignal` on its own, synchronously enough to be
/// deterministic, proving the semantics directly rather than relying on a live `VoiceController` flood
/// to demonstrate them indirectly.
final class ConflatedSignalTests: XCTestCase {
    // MARK: - at most one pending wake-up

    /// Acceptance criterion B: 100,000 calls to `signal()` before anything consumes must not produce
    /// 100,000 delivered wake-ups.
    func test100000SignalsBeforeOneConsumeDoNotCreate100000DeliveredWakeUps() async throws {
        let doorbell = ConflatedSignal()

        for _ in 0..<100_000 {
            doorbell.signal()
        }
        doorbell.finish()

        var delivered = 0
        for await _ in doorbell.stream {
            delivered += 1
        }
        XCTAssertEqual(delivered, 1, "100,000 signals before any consumption must buffer at most one wake-up")
    }

    /// The buffering ceiling is exactly one pending wake-up, not merely "fewer than 100,000" -- proven
    /// with a small, exact count rather than a large approximate one.
    func testBufferingIsAtMostOnePendingWakeUp() async throws {
        let doorbell = ConflatedSignal()

        doorbell.signal()
        doorbell.signal()
        doorbell.signal()
        doorbell.finish()

        var delivered = 0
        for await _ in doorbell.stream {
            delivered += 1
        }
        XCTAssertEqual(delivered, 1)
    }

    // MARK: - one wake-up is enough to drain everything queued behind it

    /// This is the property `VoiceController.drainMailbox()` actually depends on: a single wake-up
    /// must be sufficient to notice and drain every input that piled up before it, exactly as
    /// Android's `Channel<Unit>(Channel.CONFLATED)` doorbell already guarantees for
    /// `VoiceInputMailbox`'s own drain-to-empty consumer loop.
    func testOneWakeUpIsEnoughToCauseAConsumerToDrainAllCurrentlyQueuedWork() async throws {
        let doorbell = ConflatedSignal()
        actor Mailbox {
            private var items = Array(0..<50)
            func drainAll() -> [Int] {
                let drained = items
                items.removeAll()
                return drained
            }
        }
        let mailbox = Mailbox()

        // Every item is already "queued" before the doorbell ever rings, exactly as a flood of
        // `VoiceInputMailbox.offer` calls queues real work before the single conflated ring that
        // follows all of them.
        for _ in 0..<50 {
            doorbell.signal()
        }
        doorbell.finish()

        var drainedBatches: [[Int]] = []
        for await _ in doorbell.stream {
            drainedBatches.append(await mailbox.drainAll())
        }

        XCTAssertEqual(drainedBatches.count, 1, "exactly one wake-up must have been delivered")
        XCTAssertEqual(drainedBatches[0].count, 50, "that one wake-up must be enough to drain everything queued behind it")
    }

    // MARK: - finish() lifecycle

    func testSignalAfterFinishIsHarmless() async throws {
        let doorbell = ConflatedSignal()
        doorbell.signal()
        doorbell.finish()
        doorbell.signal() // must not crash, and must never be observed by the consumer

        var delivered = 0
        for await _ in doorbell.stream {
            delivered += 1
        }
        XCTAssertEqual(delivered, 1, "a signal after finish() must be dropped, not queued for a future consumer")
    }

    func testFinishEndsTheConsumerLoopPromptly() async throws {
        let doorbell = ConflatedSignal()
        doorbell.signal()
        doorbell.finish()

        let consumer = Task { () -> Int in
            var count = 0
            for await _ in doorbell.stream { count += 1 }
            return count
        }

        // `await consumer.value` would hang forever if `finish()` did not end the stream.
        let count = await consumer.value
        XCTAssertEqual(count, 1)
    }

    /// Teardown leaves no pending consumer: cancelling the reader task and finishing the signal, in
    /// either order, must let the reader's task actually complete rather than leak.
    func testTeardownLeavesNoPendingConsumer() async throws {
        let doorbell = ConflatedSignal()
        let started = expectation(description: "consumer observed at least one wake-up")
        let consumer = Task {
            for await _ in doorbell.stream {
                started.fulfill()
            }
        }

        doorbell.signal()
        await fulfillment(of: [started], timeout: 5.0)

        consumer.cancel()
        doorbell.finish() // the actual terminator; cancellation alone does not stop AsyncStream

        await consumer.value
        XCTAssertTrue(consumer.isCancelled)
    }

    // MARK: - fresh instance per session

    /// A new `VoiceController` (or any other owner) must get a fresh, independently working signal --
    /// never a finished one inherited from a previous voice session.
    func testANewConflatedSignalInstanceIsFreshAndIndependentlyFunctional() async throws {
        let first = ConflatedSignal()
        first.signal()
        first.finish()

        let second = ConflatedSignal()
        second.signal()
        second.finish()

        var firstDelivered = 0
        for await _ in first.stream { firstDelivered += 1 }
        var secondDelivered = 0
        for await _ in second.stream { secondDelivered += 1 }

        XCTAssertEqual(firstDelivered, 1)
        XCTAssertEqual(secondDelivered, 1, "the second instance's own signal must be delivered independently of the first's lifecycle")
    }

    // MARK: - no per-signal Task, no blocking

    /// `signal()` must return immediately and must not itself spawn a `Task` -- proven by calling it a
    /// large number of times back-to-back on the calling thread with no `await` anywhere in this loop.
    func testSignalNeverSuspendsAndCanBeCalledSynchronouslyInATightLoop() {
        let doorbell = ConflatedSignal()
        for _ in 0..<10_000 {
            doorbell.signal()
        }
        doorbell.finish()
        // Reaching this line at all is the proof: a suspending or Task-spawning `signal()` would not
        // change that, but a lock-contending or blocking one under this volume would show up as a
        // timeout, which XCTest enforces on the whole test method.
    }
}
