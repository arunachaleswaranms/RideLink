import Foundation
import XCTest

@testable import RideLinkCore

/// Runs `protocol/vectors/queue/queue_vectors.json` — PROTOCOL §9's queue algebra. The mirror is
/// `com.ridelink.core.playback.SharedQueueVectorTest`, running the **same file**.
final class SharedQueueVectorTests: XCTestCase {
    private func document() throws -> [String: Any] {
        // swiftlint:disable:next force_cast
        try Vectors.loadJSON("queue/queue_vectors.json") as! [String: Any]
    }

    func testTheVectorFilesConstantsMatchThisPlatforms() throws {
        let constants = try document().dict("constants")
        XCTAssertEqual(constants.int64("order_step"), PlaybackBounds.queueOrderStep)
        XCTAssertEqual(constants.int("max_queue_items"), PlaybackBounds.maxQueueItems)
    }

    func testScenarios() throws {
        let scenarios = try document().array("scenarios")
        for element in scenarios {
            guard let scenario = element as? [String: Any] else { continue }
            let name = scenario.str("name")
            var carried = SharedQueueState()
            for stepValue in scenario.array("steps") {
                guard let step = stepValue as? [String: Any] else { continue }
                let op = step.dict("op")
                switch op.str("kind") {
                case "Add":
                    let items = op.array("items").compactMap { $0 as? [String: Any] }.map(addItem)
                    let outcome = SharedQueue.apply(state: carried, mutation: .add(items: items))
                    assertOutcome(name, step, outcome)
                    carried = outcome.state
                case "Remove":
                    // swiftlint:disable:next force_cast
                    let ids = op["queue_item_ids"] as! [String]
                    let outcome = SharedQueue.apply(state: carried, mutation: .remove(queueItemIds: ids))
                    assertOutcome(name, step, outcome)
                    carried = outcome.state
                case "Move":
                    let outcome = SharedQueue.apply(
                        state: carried,
                        mutation: .move(queueItemId: op.str("queue_item_id"), toIndex: op.int("to_index"))
                    )
                    assertOutcome(name, step, outcome)
                    carried = outcome.state
                case "Next", "Previous":
                    let result = SharedQueue.step(state: carried, delta: op.str("kind") == "Next" ? 1 : -1)
                    XCTAssertEqual(step.boolVal("moved"), result.moved, "\(name): moved")
                    if let selected = step.dictOpt("selected") {
                        XCTAssertEqual(selected.str("queue_item_id"), result.selected?.queueItemId, "\(name): selected")
                    } else {
                        XCTAssertNil(result.selected, "\(name): selected")
                    }
                    assertState(name, step.dict("state_after"), result.state)
                    carried = result.state
                default:
                    carried = SharedQueue.select(state: carried, queueItemId: op.str("queue_item_id"))
                    assertState(name, step.dict("state_after"), carried)
                }
            }
        }
        XCTAssertFalse(scenarios.isEmpty)
    }

    func testSnapshots() throws {
        let rows = try document().array("snapshots")
        for element in rows {
            guard let row = element as? [String: Any] else { continue }
            let input = row.dict("input")
            let state = SharedQueue.applySnapshot(
                revision: input.int64("revision"),
                items: input.array("items").compactMap { $0 as? [String: Any] }.map(snapshotItem),
                currentIndex: input.intOpt("current_index")
            )
            assertState(row.str("name"), row.dict("expected"), state)
        }
        XCTAssertFalse(rows.isEmpty)
    }

    /// The 1 000-item cap (ADR-024 §6), built from the row's `item_count` rather than 1 000 literals.
    func testCap() throws {
        let rows = try document().array("cap")
        for element in rows {
            guard let row = element as? [String: Any] else { continue }
            let name = row.str("name")
            let input = row.dict("input")
            let expected = row.dict("expected")
            let count = input.int("item_count")
            let adding = input.int("adding")
            let start = SharedQueueState(
                items: (0..<count).map {
                    SharedQueueItem(queueItemId: ulid($0), trackHash: contentHash(for: $0), addedBy: Self.peer,
                                    order: Int64($0 + 1) * PlaybackBounds.queueOrderStep)
                }
            )
            let additions = (count..<(count + adding)).map {
                QueueAddItem(queueItemId: ulid($0), trackHash: contentHash(for: $0), addedBy: Self.peer,
                             position: PlaybackBounds.queuePositionEnd)
            }
            let outcome = SharedQueue.apply(state: start, mutation: .add(items: additions))
            XCTAssertEqual(expected.boolVal("changed"), outcome.changed, "\(name): changed")
            XCTAssertEqual(expected.strOpt("rejection").flatMap(SharedQueueRejection.init(rawValue:)),
                           outcome.rejection, "\(name): rejection")
            XCTAssertEqual(expected.int64("revision"), outcome.state.revision, "\(name): revision")
        }
        XCTAssertFalse(rows.isEmpty)
    }

    private func assertOutcome(_ name: String, _ step: [String: Any], _ outcome: SharedQueueOutcome) {
        XCTAssertEqual(step.boolVal("changed"), outcome.changed, "\(name): changed")
        XCTAssertEqual(step.strOpt("rejection").flatMap(SharedQueueRejection.init(rawValue:)), outcome.rejection, "\(name): rejection")
        assertState(name, step.dict("state_after"), outcome.state)
    }

    private func assertState(_ name: String, _ expected: [String: Any], _ actual: SharedQueueState) {
        XCTAssertEqual(expected.int64("revision"), actual.revision, "\(name): revision")
        XCTAssertEqual(expected.strOpt("current_item_id"), actual.currentItemId, "\(name): current item")
        let expectedItems = expected.array("items").compactMap { $0 as? [String: Any] }
        XCTAssertEqual(expectedItems.count, actual.items.count, "\(name): item count")
        for (index, item) in expectedItems.enumerated() where index < actual.items.count {
            XCTAssertEqual(item.str("queue_item_id"), actual.items[index].queueItemId, "\(name): item \(index) id")
            XCTAssertEqual(item.str("track_hash"), actual.items[index].trackHash.value, "\(name): item \(index) hash")
            XCTAssertEqual(item.str("added_by"), actual.items[index].addedBy.value, "\(name): item \(index) added_by")
            XCTAssertEqual(item.int64("order"), actual.items[index].order, "\(name): item \(index) order")
        }
    }

    private func addItem(_ json: [String: Any]) -> QueueAddItem {
        QueueAddItem(
            queueItemId: json.str("queue_item_id"),
            trackHash: ContentHash(json.str("track_hash")),
            addedBy: PeerId(json.str("added_by")),
            position: json.str("position")
        )
    }

    private func snapshotItem(_ json: [String: Any]) -> SharedQueueItem {
        SharedQueueItem(
            queueItemId: json.str("queue_item_id"),
            trackHash: ContentHash(json.str("track_hash")),
            addedBy: PeerId(json.str("added_by")),
            order: json.int64("order")
        )
    }

    private func ulid(_ n: Int) -> String {
        String(("01J9Z4M0Q7XK2V8R3T6Y1N" + String(format: "%04d", n)).prefix(26)).padding(toLength: 26, withPad: "0", startingAt: 0)
    }

    private func contentHash(for n: Int) -> ContentHash { ContentHash("sha256:" + String(format: "%064x", n)) }

    private static let peer = PeerId("a3f1000000000001")
}
