import Foundation
import XCTest
@testable import RideLinkCore

private let sampleHash = QuickId("sha256:" + String(repeating: "aa", count: 32))
private let durationMs: Int64 = 509
private let positionMidMs: Int64 = 200

private func playing(_ positionMs: Int64 = 0) -> PlayerState {
    PlayerState(quickId: sampleHash, positionMs: positionMs, durationMs: durationMs, playing: true)
}

private func ended() -> PlayerState {
    PlayerState(quickId: sampleHash, positionMs: durationMs, durationMs: durationMs, playing: false)
}

private func missing() -> PlayerState {
    PlayerState(quickId: sampleHash, error: .fileMissing)
}

/// Exhausts `TrackEndEdge`. The mirror is `TrackEndEdgeTest` on Android; both exist because this
/// type fixes a real restart-loop bug (`TrackEndEdge`'s own doc comment has the full account).
final class TrackEndEdgeTests: XCTestCase {
    func testNotDoneToEndedFiresExactlyOnce() {
        XCTAssertTrue(TrackEndEdge.advancedNow(previous: playing(positionMidMs), current: ended()))
    }

    func testARepeatedEndedEmissionDoesNotFireAgain() {
        XCTAssertFalse(TrackEndEdge.advancedNow(previous: ended(), current: ended()))
    }

    func testTwoIdenticalEmissionsForOneFinishFireOnlyOnTheFirst() {
        let fromFirstCallback = ended()
        let fromSecondCallback = ended()
        XCTAssertTrue(TrackEndEdge.advancedNow(previous: playing(positionMidMs), current: fromFirstCallback))
        XCTAssertFalse(TrackEndEdge.advancedNow(previous: fromFirstCallback, current: fromSecondCallback))
    }

    func testNotDoneToNotDoneNeverFires() {
        XCTAssertFalse(TrackEndEdge.advancedNow(previous: playing(0), current: playing(positionMidMs)))
    }

    func testEndedToAFreshLoadsResetStateNeverFires() {
        let freshLoad = PlayerState(quickId: sampleHash)
        XCTAssertFalse(TrackEndEdge.advancedNow(previous: ended(), current: freshLoad))
    }

    func testNotDoneToFileMissingFiresExactlyOnce() {
        XCTAssertTrue(TrackEndEdge.advancedNow(previous: playing(positionMidMs), current: missing()))
    }

    func testARepeatedFileMissingEmissionDoesNotFireAgain() {
        XCTAssertFalse(TrackEndEdge.advancedNow(previous: missing(), current: missing()))
    }

    func testADecodeFailureDoesNotCountAsDone() {
        let decodeFailed = PlayerState(quickId: sampleHash, error: .decodeFailed)
        XCTAssertFalse(TrackEndEdge.advancedNow(previous: playing(0), current: decodeFailed))
    }
}
