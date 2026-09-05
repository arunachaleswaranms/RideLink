import MediaPlayer
import XCTest
@testable import RideLinkPlatform

/// Pins `NowPlayingInfoBuilder.build`'s dictionary shape — the untestable half is the actual
/// `MPNowPlayingInfoCenter.default().nowPlayingInfo = ...` assignment in `ios/RideLink`'s
/// `NowPlayingController`, which has no lock screen to observe under `swift test`. This is the pure
/// half: given a `PlayerState`-shaped set of values, does the dictionary carry the right keys with
/// the right values.
final class NowPlayingInfoBuilderTests: XCTestCase {
    func testPlayingTrackCarriesAllFiveKeys() {
        let info = NowPlayingInfoBuilder.build(
            title: "Song", artist: "Artist", durationMs: 180_000, positionMs: 45_000, playing: true
        )

        XCTAssertEqual(info[MPMediaItemPropertyTitle] as? String, "Song")
        XCTAssertEqual(info[MPMediaItemPropertyArtist] as? String, "Artist")
        XCTAssertEqual(info[MPMediaItemPropertyPlaybackDuration] as? Double, 180)
        XCTAssertEqual(info[MPNowPlayingInfoPropertyElapsedPlaybackTime] as? Double, 45)
        XCTAssertEqual(info[MPNowPlayingInfoPropertyPlaybackRate] as? Double, 1.0)
    }

    func testPausedTrackReportsZeroRate() {
        let info = NowPlayingInfoBuilder.build(
            title: "Song", artist: "Artist", durationMs: 180_000, positionMs: 45_000, playing: false
        )

        XCTAssertEqual(info[MPNowPlayingInfoPropertyPlaybackRate] as? Double, 0.0)
    }

    func testMissingTitleAndArtistOmitTheirKeysRatherThanAnEmptyString() {
        let info = NowPlayingInfoBuilder.build(title: nil, artist: nil, durationMs: 180_000, positionMs: 0, playing: false)

        XCTAssertNil(info[MPMediaItemPropertyTitle])
        XCTAssertNil(info[MPMediaItemPropertyArtist])
    }

    func testZeroDurationOmitsTheDurationKeyRatherThanClaimingAZeroLengthTrack() {
        let info = NowPlayingInfoBuilder.build(title: "Song", artist: "Artist", durationMs: 0, positionMs: 0, playing: false)

        XCTAssertNil(info[MPMediaItemPropertyPlaybackDuration])
    }

    func testElapsedTimeIsAlwaysPresentEvenAtZero() {
        let info = NowPlayingInfoBuilder.build(title: nil, artist: nil, durationMs: 0, positionMs: 0, playing: false)

        XCTAssertEqual(info[MPNowPlayingInfoPropertyElapsedPlaybackTime] as? Double, 0)
    }
}
