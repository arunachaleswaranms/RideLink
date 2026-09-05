import Foundation
import XCTest
@testable import RideLinkCore

private let sampleId = LocalEntryId("3fa85f64-5717-4562-b3fc-2c963f66afa6")

final class PlayerStateTests: XCTestCase {
    func testAFreshStateHasNotEnded() {
        XCTAssertFalse(PlayerState().ended)
    }

    func testReachingDurationWhilePlayingHasNotEndedYet() {
        XCTAssertFalse(PlayerState(localEntryId: sampleId, positionMs: 1000, durationMs: 1000, playing: true).ended)
    }

    func testStoppedExactlyAtDurationHasEnded() {
        XCTAssertTrue(PlayerState(localEntryId: sampleId, positionMs: 1000, durationMs: 1000, playing: false).ended)
    }

    func testAZeroLengthDurationNeverReportsEnded() {
        XCTAssertFalse(PlayerState(localEntryId: sampleId, positionMs: 0, durationMs: 0, playing: false).ended)
    }

    func testNoLoadedTrackNeverReportsEnded() {
        XCTAssertFalse(PlayerState(localEntryId: nil, positionMs: 0, durationMs: 0, playing: false).ended)
    }
}
