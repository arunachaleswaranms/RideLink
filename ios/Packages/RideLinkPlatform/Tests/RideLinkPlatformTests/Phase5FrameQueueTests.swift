import Foundation
import XCTest

@testable import RideLinkCore
@testable import RideLinkPlatform

/// `Phase5FrameQueue` on its own, without a coordinator — the mechanics ADR-024 Amendment A1
/// Finding C rests on. The mirror is `com.ridelink.app.sync.Phase5FrameQueueTest`, asserting the
/// same properties, and the *policy* both implementations consult is pinned for both by
/// `protocol/vectors/phase5-gates/`.
///
/// The property that matters: **a frame this queue accepted is a frame the consumer will see, in
/// arrival order.** Its predecessor (`AsyncStream(bufferingPolicy: .bufferingNewest(256))`)
/// satisfied neither half — it evicted accepted frames, and the forwarder discarded the `.dropped`
/// result that would at least have said so.
final class Phase5FrameQueueTests: XCTestCase {
    /// A test frame: a name, plus whether a newer sibling may supersede it.
    private struct Frame: Sendable {
        let name: String
        let family: String?
        /// The authentication generation this frame was produced under — what Amendment A6 makes any
        /// loss it causes belong to.
        let generation: Int64

        init(_ name: String, family: String? = nil, generation: Int64 = 1) {
            self.name = name
            self.family = family
            self.generation = generation
        }
    }

    private func makeQueue(capacity: Int) -> Phase5FrameQueue<Frame> {
        Phase5FrameQueue(
            capacity: capacity,
            kindOf: { $0.family == nil ? .command : .latestWins },
            coalesceKeyOf: { $0.family },
            generationOf: { $0.generation }
        )
    }

    /// Every loss the queue is holding, flattened — the shape the pre-A6 `stats` property reported
    /// without saying whose the losses were.
    private func totals(_ queue: Phase5FrameQueue<Frame>) -> (overflow: Int, coalesced: Int) {
        queue.drainLosses().reduce(into: (overflow: 0, coalesced: 0)) {
            $0.overflow += $1.overflowCount
            $0.coalesced += $1.coalescedCount
        }
    }

    func testFramesAreHandedToTheConsumerInArrivalOrder() async {
        let queue = makeQueue(capacity: 8)
        for name in ["a", "b", "c"] { XCTAssertEqual(queue.offer(Frame(name)), .admit) }
        var drained: [String] = []
        for _ in 0 ..< 3 { drained.append(await queue.take()?.name ?? "-") }
        XCTAssertEqual(drained, ["a", "b", "c"])
    }

    /// The whole of the finding. `.bufferingNewest` would have evicted "a" here; a refusal is the
    /// honest answer and the caller can act on it.
    func testAFullQueueRefusesTheNewcomerRatherThanEvictingWhatItAccepted() async {
        let queue = makeQueue(capacity: 2)
        XCTAssertEqual(queue.offer(Frame("a")), .admit)
        XCTAssertEqual(queue.offer(Frame("b")), .admit)
        XCTAssertEqual(queue.offer(Frame("c")), .overflow)
        XCTAssertEqual(totals(queue).overflow, 1)
        XCTAssertEqual(queue.count, 2, "the bound is real: a refusal never grows the queue")

        var drained: [String] = []
        for _ in 0 ..< 2 { drained.append(await queue.take()?.name ?? "-") }
        XCTAssertEqual(drained, ["a", "b"], "what was accepted is what arrives")
    }

    func testALatestWinsFrameCoalescesOntoItsOwnFamilyAndKeepsTheNewestPosition() async {
        let queue = makeQueue(capacity: 2)
        XCTAssertEqual(queue.offer(Frame("report-1", family: "REPORT")), .admit)
        XCTAssertEqual(queue.offer(Frame("command")), .admit)
        XCTAssertEqual(queue.offer(Frame("report-2", family: "REPORT")), .coalesce)
        let counted = totals(queue)
        XCTAssertEqual(counted.coalesced, 1)
        XCTAssertEqual(counted.overflow, 0)

        var drained: [String] = []
        for _ in 0 ..< 2 { drained.append(await queue.take()?.name ?? "-") }
        XCTAssertEqual(
            drained, ["command", "report-2"],
            "the newest report replaced the oldest, at the newest arrival position"
        )
    }

    func testAFrameOfOneLatestWinsFamilyNeverSupersedesAnother() {
        let queue = makeQueue(capacity: 1)
        XCTAssertEqual(queue.offer(Frame("snapshot", family: "SNAPSHOT")), .admit)
        XCTAssertEqual(
            queue.offer(Frame("state", family: "STATE")), .overflow,
            "a POSITION_REPORT must never supersede a PLAYBACK_STATE, or the reverse"
        )
    }

    func testACommandIsNeverCoalesced() {
        let queue = makeQueue(capacity: 1)
        XCTAssertEqual(queue.offer(Frame("first")), .admit)
        XCTAssertEqual(queue.offer(Frame("second")), .overflow)
    }

    /// A parked consumer is handed the next frame directly, without it ever touching the buffer.
    func testAParkedConsumerIsResumedByTheNextFrame() async {
        let queue = makeQueue(capacity: 4)
        let parked = Task { await queue.take()?.name }
        // The handoff path needs the consumer actually parked, which is the one thing a yield count
        // cannot promise — so wait on the queue's own observable state instead.
        while queue.count == 0, !Task.isCancelled {
            if queue.offer(Frame("late")) == .admit { break }
            await Task.yield()
        }
        let received = await parked.value
        XCTAssertEqual(received, "late")
        XCTAssertEqual(queue.count, 0)
    }

    /// Teardown releases a parked consumer, so the drain loop ends rather than waiting forever.
    func testFinishReleasesAParkedConsumerAndRefusesLaterFrames() async {
        let queue = makeQueue(capacity: 4)
        let parked = Task { await queue.take() }
        await Task.yield()
        queue.finish()
        let received = await parked.value
        XCTAssertNil(received, "a finished queue ends the drain instead of stalling it")
        XCTAssertEqual(queue.offer(Frame("after")), .overflow)
    }

    /// Whatever the offer sequence, the consumer's stream is a subsequence of it, in order.
    func testTheDrainedSequenceIsAlwaysAnInOrderSubsequenceOfWhatWasOffered() async {
        let queue = makeQueue(capacity: 3)
        var offered: [String] = []
        var accepted: Set<String> = []
        for index in 0 ..< 40 {
            let frame = index % 4 == 0 ? Frame("r\(index)", family: "REPORT") : Frame("c\(index)")
            offered.append(frame.name)
            if queue.offer(frame) != .overflow { accepted.insert(frame.name) }
        }
        queue.finish()

        var drained: [String] = []
        while let item = await queue.take() { drained.append(item.name) }

        var cursor = -1
        for name in drained {
            let position = offered.firstIndex(of: name) ?? -1
            XCTAssertGreaterThan(position, cursor, "arrival order must hold: \(name) arrived out of sequence")
            cursor = position
        }
        XCTAssertFalse(drained.isEmpty)
        for name in drained where name.hasPrefix("c") {
            XCTAssertTrue(accepted.contains(name), "no command reaches the consumer that the queue did not accept")
        }
    }

    /// Concurrent producers never lose an accepted frame and never exceed the bound.
    func testConcurrentProducersNeverExceedTheBoundAndNeverLoseAnAcceptedFrame() async {
        let queue = makeQueue(capacity: 64)
        let producers = 8
        let perProducer = 32
        var acceptedTotal = 0
        await withTaskGroup(of: Int.self) { group in
            for producer in 0 ..< producers {
                group.addTask {
                    var accepted = 0
                    for index in 0 ..< perProducer {
                        if queue.offer(Frame("p\(producer)-\(index)")) != .overflow { accepted += 1 }
                    }
                    return accepted
                }
            }
            for await accepted in group { acceptedTotal += accepted }
        }
        XCTAssertLessThanOrEqual(queue.count, 64, "the bound holds under concurrency")
        queue.finish()
        var drained = 0
        while await queue.take() != nil { drained += 1 }
        XCTAssertEqual(drained, acceptedTotal, "every accepted frame reached the consumer, and no other did")
    }
}
