import Foundation
import XCTest

@testable import RideLinkCore

/// `PlaybackTimeline`'s extrapolation, which is what every drift measurement in Phase 5 is taken
/// against (this phase's brief §33). Mirrors `com.ridelink.core.playback.PlaybackTimelineTest`.
///
/// Not vector-driven: there is no wire shape here and no cross-platform *encoding* to pin — only
/// arithmetic, which both platforms' own suites assert identically.
final class PlaybackTimelineTests: XCTestCase {
    private let trackHash = ContentHash("sha256:" + String(repeating: "1f3a", count: 16))
    private let item = "01J9Z4M0Q7XK2V8R3T6Y1N5B2C"

    private func timeline(anchorPositionMs: Int64 = 10_000, anchorSessionUs: Int64 = 1_000_000, playing: Bool = true) -> PlaybackTimeline {
        PlaybackTimeline(
            trackHash: trackHash,
            queueItemId: item,
            anchorPositionMs: anchorPositionMs,
            anchorSessionUs: anchorSessionUs,
            playing: playing,
            generation: 1
        )
    }

    func testAPausedTimelineNeverAdvances() {
        let paused = timeline(playing: false)
        XCTAssertEqual(paused.expectedPositionMs(atSessionUs: 1_000_000), 10_000)
        XCTAssertEqual(paused.expectedPositionMs(atSessionUs: 999_000_000), 10_000)
    }

    func testAPlayingTimelineAdvancesOneMillisecondPerThousandMicroseconds() {
        let playing = timeline()
        XCTAssertEqual(playing.expectedPositionMs(atSessionUs: 1_000_000), 10_000)
        XCTAssertEqual(playing.expectedPositionMs(atSessionUs: 1_001_000), 10_001)
        XCTAssertEqual(playing.expectedPositionMs(atSessionUs: 6_000_000), 15_000)
    }

    /// A scheduled command whose deadline has not arrived must not extrapolate backwards.
    func testBeforeTheAnchorTheExpectedPositionIsTheAnchorItself() {
        let playing = timeline(anchorSessionUs: 5_000_000)
        XCTAssertEqual(playing.expectedPositionMs(atSessionUs: 1_000_000), 10_000)
        XCTAssertEqual(playing.expectedPositionMs(atSessionUs: 4_999_999), 10_000)
    }

    func testElapsedMicrosecondsTruncateTowardZeroIdenticallyOnBothPlatforms() {
        let playing = timeline()
        XCTAssertEqual(playing.expectedPositionMs(atSessionUs: 1_000_999), 10_000)
        XCTAssertEqual(playing.expectedPositionMs(atSessionUs: 1_001_999), 10_001)
    }

    func testAKnownDurationClampsTheTop() {
        let playing = timeline()
        XCTAssertEqual(playing.expectedPositionMs(atSessionUs: 999_000_000, durationMs: 12_000), 12_000)
        XCTAssertEqual(playing.expectedPositionMs(atSessionUs: 6_000_000, durationMs: 12_000_000), 15_000)
    }

    func testAnUnknownDurationAppliesNoClamp() {
        XCTAssertEqual(timeline().expectedPositionMs(atSessionUs: 1_001_000_000, durationMs: nil), 1_010_000)
    }

    func testANegativeAnchorPositionFloorsAtZero() {
        XCTAssertEqual(timeline(anchorPositionMs: -500, playing: false).expectedPositionMs(atSessionUs: 1_000_000), 0)
    }

    func testDriftIsActualMinusExpectedAndItsSignSaysWhichWayToCorrect() {
        let playing = timeline()
        // Ahead of the timeline: positive drift, so the ladder must slow this device down.
        XCTAssertEqual(playing.driftMs(actualPositionMs: 15_040, atSessionUs: 6_000_000), 40)
        // Behind: negative drift, so it must speed up.
        XCTAssertEqual(playing.driftMs(actualPositionMs: 14_960, atSessionUs: 6_000_000), -40)
        XCTAssertEqual(playing.driftMs(actualPositionMs: 15_000, atSessionUs: 6_000_000), 0)
    }
}
