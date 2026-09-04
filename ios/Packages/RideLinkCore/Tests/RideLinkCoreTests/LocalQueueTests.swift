import Foundation
import XCTest
@testable import RideLinkCore

private func contentHashFor(_ byte: String) -> ContentHash { ContentHash("sha256:" + String(repeating: byte, count: 32)) }

private func item(_ id: String, hashByte: String? = nil) -> LocalQueueItem {
    LocalQueueItem(id: id, contentHash: contentHashFor(hashByte ?? id), insertedAtMonoUs: 0)
}

final class LocalQueueTests: XCTestCase {
    func testAddAppendsWithoutTouchingCurrentSelection() {
        let outcome = LocalQueue.reduce(LocalQueueState(), .add(item("a1")))
        XCTAssertEqual([item("a1")], outcome.state.items)
        XCTAssertNil(outcome.state.currentId)
        XCTAssertTrue(outcome.effects.isEmpty)
    }

    func testDuplicateTrackAddedTwiceProducesTwoIndependentQueueEntries() {
        let sameTrack = contentHashFor("aa")
        let first = LocalQueueItem(id: "q1", contentHash: sameTrack, insertedAtMonoUs: 0)
        let second = LocalQueueItem(id: "q2", contentHash: sameTrack, insertedAtMonoUs: 1)
        var state = LocalQueue.reduce(LocalQueueState(), .add(first)).state
        state = LocalQueue.reduce(state, .add(second)).state
        XCTAssertEqual(2, state.items.count)
        let afterRemove = LocalQueue.reduce(state, .remove(id: "q1")).state
        XCTAssertEqual([second], afterRemove.items)
    }

    func testNextFromNoSelectionStartsAtTheFirstItemAndPlaysIt() {
        let state = LocalQueueState(items: [item("a1"), item("b2")])
        let outcome = LocalQueue.reduce(state, .next)
        XCTAssertEqual("a1", outcome.state.currentId)
        XCTAssertEqual([.loadAndPlay(contentHashFor("a1"))], outcome.effects)
    }

    func testPreviousFromNoSelectionIsANoOp() {
        let state = LocalQueueState(items: [item("a1")])
        let outcome = LocalQueue.reduce(state, .previous)
        XCTAssertEqual(state, outcome.state)
        XCTAssertTrue(outcome.effects.isEmpty)
    }

    func testNextPastTheLastItemStopsRatherThanWrapping() {
        let state = LocalQueueState(items: [item("a1"), item("b2")], currentId: "b2")
        let outcome = LocalQueue.reduce(state, .next)
        XCTAssertNil(outcome.state.currentId)
        XCTAssertEqual([.stopPlayback], outcome.effects)
    }

    func testPreviousAtTheFirstItemStaysPut() {
        let state = LocalQueueState(items: [item("a1"), item("b2")], currentId: "a1")
        let outcome = LocalQueue.reduce(state, .previous)
        XCTAssertEqual(state, outcome.state)
        XCTAssertTrue(outcome.effects.isEmpty)
    }

    func testNextAndPreviousMoveBetweenAdjacentItemsAndPlayThem() {
        let state = LocalQueueState(items: [item("a1"), item("b2"), item("c3")], currentId: "a1")
        let toB = LocalQueue.reduce(state, .next)
        XCTAssertEqual("b2", toB.state.currentId)
        XCTAssertEqual([.loadAndPlay(contentHashFor("b2"))], toB.effects)
        let backToA = LocalQueue.reduce(toB.state, .previous)
        XCTAssertEqual("a1", backToA.state.currentId)
        XCTAssertEqual([.loadAndPlay(contentHashFor("a1"))], backToA.effects)
    }

    func testRemovingTheCurrentItemHandsPlaybackToItsSuccessor() {
        let state = LocalQueueState(items: [item("a1"), item("b2"), item("c3")], currentId: "b2")
        let outcome = LocalQueue.reduce(state, .remove(id: "b2"))
        XCTAssertEqual([item("a1"), item("c3")], outcome.state.items)
        XCTAssertEqual("c3", outcome.state.currentId)
        XCTAssertEqual([.loadAndPlay(contentHashFor("c3"))], outcome.effects)
    }

    func testRemovingTheCurrentLastItemStopsPlayback() {
        let state = LocalQueueState(items: [item("a1"), item("b2")], currentId: "b2")
        let outcome = LocalQueue.reduce(state, .remove(id: "b2"))
        XCTAssertEqual([item("a1")], outcome.state.items)
        XCTAssertNil(outcome.state.currentId)
        XCTAssertEqual([.stopPlayback], outcome.effects)
    }

    func testRemovingANonCurrentItemOnlyShiftsPositions() {
        let state = LocalQueueState(items: [item("a1"), item("b2"), item("c3")], currentId: "c3")
        let outcome = LocalQueue.reduce(state, .remove(id: "a1"))
        XCTAssertEqual([item("b2"), item("c3")], outcome.state.items)
        XCTAssertEqual("c3", outcome.state.currentId)
        XCTAssertTrue(outcome.effects.isEmpty)
    }

    func testRemovingAnUnknownIdIsANoOp() {
        let state = LocalQueueState(items: [item("a1")], currentId: "a1")
        let outcome = LocalQueue.reduce(state, .remove(id: "ghost"))
        XCTAssertEqual(state, outcome.state)
        XCTAssertTrue(outcome.effects.isEmpty)
    }

    func testClearingDuringPlaybackStopsPlayback() {
        let state = LocalQueueState(items: [item("a1"), item("b2")], currentId: "a1")
        let outcome = LocalQueue.reduce(state, .clear)
        XCTAssertEqual(LocalQueueState(), outcome.state)
        XCTAssertEqual([.stopPlayback], outcome.effects)
    }

    func testClearingAnIdleQueueEmitsNoEffect() {
        let state = LocalQueueState(items: [item("a1")])
        let outcome = LocalQueue.reduce(state, .clear)
        XCTAssertEqual(LocalQueueState(), outcome.state)
        XCTAssertTrue(outcome.effects.isEmpty)
    }

    func testSelectJumpsDirectlyToAnItemAndPlaysIt() {
        let state = LocalQueueState(items: [item("a1"), item("b2"), item("c3")], currentId: "a1")
        let outcome = LocalQueue.reduce(state, .select(id: "c3"))
        XCTAssertEqual("c3", outcome.state.currentId)
        XCTAssertEqual([.loadAndPlay(contentHashFor("c3"))], outcome.effects)
    }

    func testSelectOfAnUnknownIdIsANoOp() {
        let state = LocalQueueState(items: [item("a1")], currentId: "a1")
        let outcome = LocalQueue.reduce(state, .select(id: "ghost"))
        XCTAssertEqual(state, outcome.state)
        XCTAssertTrue(outcome.effects.isEmpty)
    }

    func testMoveRelocatesAnItemWithoutTouchingCurrentSelection() {
        let state = LocalQueueState(items: [item("a1"), item("b2"), item("c3")], currentId: "a1")
        let outcome = LocalQueue.reduce(state, .move(id: "c3", toIndex: 0))
        XCTAssertEqual([item("c3"), item("a1"), item("b2")], outcome.state.items)
        XCTAssertEqual("a1", outcome.state.currentId)
        XCTAssertTrue(outcome.effects.isEmpty)
    }

    func testMoveClampsAnOutOfRangeTargetIndex() {
        let state = LocalQueueState(items: [item("a1"), item("b2")])
        let outcome = LocalQueue.reduce(state, .move(id: "a1", toIndex: 99))
        XCTAssertEqual([item("b2"), item("a1")], outcome.state.items)
    }

    func testMoveOfAnUnknownIdIsANoOp() {
        let state = LocalQueueState(items: [item("a1")])
        let outcome = LocalQueue.reduce(state, .move(id: "ghost", toIndex: 0))
        XCTAssertEqual(state, outcome.state)
    }

    func testFiftyConsecutiveNextPressesNeverCrashAndAlwaysLandOnARealItemOrStop() {
        let items = (0..<5).map { item("t\($0)", hashByte: "a\($0)") }
        var state = LocalQueueState(items: items)
        for _ in 0..<50 {
            let outcome = LocalQueue.reduce(state, .next)
            state = outcome.state
            for effect in outcome.effects {
                switch effect {
                case .loadAndPlay, .stopPlayback: break
                }
            }
            if state.currentId == nil {
                state = LocalQueue.reduce(state, .next).state
            }
        }
    }
}
