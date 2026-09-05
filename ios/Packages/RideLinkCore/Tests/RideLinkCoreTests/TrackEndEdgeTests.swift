import Foundation
import XCTest
@testable import RideLinkCore

private let sampleId = LocalEntryId("3fa85f64-5717-4562-b3fc-2c963f66afa6")
private let durationMs: Int64 = 509
private let positionMidMs: Int64 = 200

private func playing(_ positionMs: Int64 = 0) -> PlayerState {
    PlayerState(localEntryId: sampleId, positionMs: positionMs, durationMs: durationMs, playing: true)
}

private func ended() -> PlayerState {
    PlayerState(localEntryId: sampleId, positionMs: durationMs, durationMs: durationMs, playing: false)
}

private func missing() -> PlayerState {
    PlayerState(localEntryId: sampleId, error: .fileMissing)
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
        let freshLoad = PlayerState(localEntryId: sampleId)
        XCTAssertFalse(TrackEndEdge.advancedNow(previous: ended(), current: freshLoad))
    }

    func testNotDoneToFileMissingFiresExactlyOnce() {
        XCTAssertTrue(TrackEndEdge.advancedNow(previous: playing(positionMidMs), current: missing()))
    }

    func testARepeatedFileMissingEmissionDoesNotFireAgain() {
        XCTAssertFalse(TrackEndEdge.advancedNow(previous: missing(), current: missing()))
    }

    func testADecodeFailureDoesNotCountAsDone() {
        let decodeFailed = PlayerState(localEntryId: sampleId, error: .decodeFailed)
        XCTAssertFalse(TrackEndEdge.advancedNow(previous: playing(0), current: decodeFailed))
    }
}
