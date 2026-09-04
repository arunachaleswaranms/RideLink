import Foundation
import XCTest
@testable import RideLinkCore

private let sampleHash = QuickId("sha256:" + String(repeating: "ab", count: 32))

final class PlayerStateTests: XCTestCase {
    func testAFreshStateHasNotEnded() {
        XCTAssertFalse(PlayerState().ended)
    }

    func testReachingDurationWhilePlayingHasNotEndedYet() {
        XCTAssertFalse(PlayerState(quickId: sampleHash, positionMs: 1000, durationMs: 1000, playing: true).ended)
    }

    func testStoppedExactlyAtDurationHasEnded() {
        XCTAssertTrue(PlayerState(quickId: sampleHash, positionMs: 1000, durationMs: 1000, playing: false).ended)
    }

    func testAZeroLengthDurationNeverReportsEnded() {
        XCTAssertFalse(PlayerState(quickId: sampleHash, positionMs: 0, durationMs: 0, playing: false).ended)
    }

    func testNoLoadedTrackNeverReportsEnded() {
        XCTAssertFalse(PlayerState(quickId: nil, positionMs: 0, durationMs: 0, playing: false).ended)
    }
}
