import XCTest
@testable import RideLinkPlatform

/// Direct coverage of the abstraction `SessionCoordinator` uses to serialize `ControlEvent`/
/// `PairingPrompt` delivery — see `OrderedEventChannel`'s doc comment for the bug it replaces
/// (one `Task { @MainActor in ... }` per event, which preserves creation order but not execution
/// order). These tests exercise the channel on its own, synchronously enough to be deterministic,
/// rather than relying on a real `ControlSessionManager` race to sometimes reproduce the defect —
/// exactly the "test the abstraction directly" shape this fix calls for.
///
/// A collecting actor, not a plain array behind a lock: the consumer `Task` in every test below
/// hops here with `await`, so isolation is real actor isolation, not a lock standing in for one.
private actor Collector<Element: Sendable> {
    private(set) var received: [Element] = []

    func record(_ element: Element) {
        received.append(element)
    }
}

final class OrderedEventChannelTests: XCTestCase {
    // MARK: - FIFO order

    func testEventsAreConsumedInTheExactOrderTheyWereSent() async throws {
        let channel = OrderedEventChannel<Int>()
        let collector = Collector<Int>()

        let consumer = Task {
            for await value in channel.stream {
                await collector.record(value)
            }
        }

        for value in 0..<200 {
            channel.send(value)
        }
        channel.finish()
        await consumer.value

        let received = await collector.received
        XCTAssertEqual(received, Array(0..<200), "a single ordered channel must never reorder its own sends")
    }

    /// The exact shape of the production bug: two events sent back-to-back, synchronously, with no
    /// `await` between the sends — precisely how `ControlSessionManager.succeedPairing` emits
    /// `.pairingSucceeded` immediately followed by `.connected`, and how `promote` emits
    /// `.peerTrusted` immediately followed by `.connected`.
    func testTwoEventsSentSynchronouslyBackToBackArriveInThatOrder() async throws {
        for _ in 0..<50 {
            let channel = OrderedEventChannel<String>()
            let collector = Collector<String>()
            let consumer = Task {
                for await value in channel.stream {
                    await collector.record(value)
                }
            }

            channel.send("PairingSucceeded")
            channel.send("Connected")
            channel.finish()
            await consumer.value

            let received = await collector.received
            XCTAssertEqual(received, ["PairingSucceeded", "Connected"])
        }
    }

    // MARK: - finish() lifecycle

    func testFinishEndsTheConsumerLoopPromptly() async throws {
        let channel = OrderedEventChannel<Int>()
        channel.send(1)
        channel.finish()

        let consumer = Task { () -> [Int] in
            var values: [Int] = []
            for await value in channel.stream { values.append(value) }
            return values
        }

        // `await consumer.value` would hang forever if `finish()` did not end the stream — this is
        // the "no Task leak on teardown" property, proven by the test itself timing out if it were
        // false rather than by a manual timeout.
        let values = await consumer.value
        XCTAssertEqual(values, [1])
    }

    func testSendAfterFinishIsADroppedNoOp() async throws {
        let channel = OrderedEventChannel<Int>()
        let collector = Collector<Int>()
        let consumer = Task {
            for await value in channel.stream {
                await collector.record(value)
            }
        }

        channel.send(1)
        channel.finish()
        channel.send(2) // must not crash, and must never reach the consumer

        await consumer.value
        let received = await collector.received
        XCTAssertEqual(received, [1], "a stale sender must not be able to mutate a channel that has already finished")
    }

    // MARK: - cancellation

    func testCancellingTheConsumerTaskThenFinishingLeavesNoTaskRunning() async throws {
        let channel = OrderedEventChannel<Int>()
        let collector = Collector<Int>()
        let consumer = Task {
            for await value in channel.stream {
                await collector.record(value)
            }
        }

        channel.send(1)
        consumer.cancel()
        channel.finish() // the actual terminator; cancellation alone does not stop AsyncStream

        // Proves the task actually completed rather than being merely marked cancelled.
        await consumer.value
        XCTAssertTrue(consumer.isCancelled)
    }
}
