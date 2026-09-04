import Foundation
import XCTest
@testable import RideLinkCore

private let sampleHash = ContentHash("sha256:" + String(repeating: "ab", count: 32))

final class PlayerStateTests: XCTestCase {
    func testAFreshStateHasNotEnded() {
        XCTAssertFalse(PlayerState().ended)
    }

    func testReachingDurationWhilePlayingHasNotEndedYet() {
        XCTAssertFalse(PlayerState(contentHash: sampleHash, positionMs: 1000, durationMs: 1000, playing: true).ended)
    }

    func testStoppedExactlyAtDurationHasEnded() {
        XCTAssertTrue(PlayerState(contentHash: sampleHash, positionMs: 1000, durationMs: 1000, playing: false).ended)
    }

    func testAZeroLengthDurationNeverReportsEnded() {
        XCTAssertFalse(PlayerState(contentHash: sampleHash, positionMs: 0, durationMs: 0, playing: false).ended)
    }

    func testNoLoadedTrackNeverReportsEnded() {
        XCTAssertFalse(PlayerState(contentHash: nil, positionMs: 0, durationMs: 0, playing: false).ended)
    }
}
