import RideLinkCore
import XCTest

@testable import RideLinkPlatform

/// State-driven tests for Phase 7's Ride Mode presentation (FR-018, ADR-028) — pure derivations,
/// not screenshots. The mirror is Android's Ride Mode presentation mapping test, asserting the same
/// scenarios: connected, ride active, reconnecting, recovered, disconnected, playing, paused, mic
/// muted/unmuted, PTT mode, intercom disabled, audio-route degraded.
final class RideModePresentationTests: XCTestCase {
    // MARK: - Connection health

    func testConnectedReadsAsHealthy() {
        XCTAssertEqual(.healthy, RideModePresentation.connectionHealth(.connected))
    }

    func testRideActiveReadsAsHealthy() {
        XCTAssertEqual(.healthy, RideModePresentation.connectionHealth(.rideActive))
    }

    func testReconnectingReadsAsReconnecting() {
        XCTAssertEqual(.reconnecting, RideModePresentation.connectionHealth(.reconnecting))
    }

    /// "Recovered" is a reconnecting session returning to `.connected`/`.rideActive` — the same
    /// transition `SessionFsm.reconnectSucceeded` already performs; this proves the presentation
    /// reads the recovered status as healthy again, with no separate "recovered" state to track.
    func testRecoveredAfterReconnectReadsAsHealthyAgain() {
        XCTAssertEqual(.reconnecting, RideModePresentation.connectionHealth(.reconnecting))
        XCTAssertEqual(.healthy, RideModePresentation.connectionHealth(.rideActive))
    }

    func testDisconnectedReadsAsDisconnected() {
        XCTAssertEqual(.disconnected, RideModePresentation.connectionHealth(.disconnected))
    }

    func testEveryNonRidingStatusReadsAsDisconnected() {
        for status: SessionStatus in [.idle, .discovering, .pairing, .connecting, .ending, .error] {
            XCTAssertEqual(.disconnected, RideModePresentation.connectionHealth(status), "\(status)")
        }
    }

    func testOnlyConnectedCanStartARide() {
        XCTAssertTrue(RideModePresentation.canStartRide(.connected))
        for status: SessionStatus in [.idle, .discovering, .pairing, .connecting, .rideActive, .reconnecting, .disconnected, .ending, .error] {
            XCTAssertFalse(RideModePresentation.canStartRide(status), "\(status)")
        }
    }

    func testOnlyRideActiveIsARideInProgress() {
        XCTAssertTrue(RideModePresentation.isRideActive(.rideActive))
        XCTAssertFalse(RideModePresentation.isRideActive(.connected))
        XCTAssertFalse(RideModePresentation.isRideActive(.reconnecting))
    }

    // MARK: - Screen visibility (regression: a naive `status == .rideActive` check drops the rider
    // back to the main screen the instant an ordinary reconnect starts, per this file's fix note)

    func testRideActiveIsAlwaysVisibleRegardlessOfPreviousFrame() {
        XCTAssertTrue(RideModePresentation.nextRideModeVisibility(previous: false, status: .rideActive, returnTo: nil))
        XCTAssertTrue(RideModePresentation.nextRideModeVisibility(previous: true, status: .rideActive, returnTo: nil))
    }

    func testReconnectingStaysVisibleWhenReturningToRideActive() {
        XCTAssertTrue(
            RideModePresentation.nextRideModeVisibility(previous: true, status: .reconnecting, returnTo: .rideActive)
        )
    }

    func testReconnectingBackToConnectedNeverShowsRideModeEvenIfSomehowPreviouslyVisible() {
        XCTAssertFalse(
            RideModePresentation.nextRideModeVisibility(previous: true, status: .reconnecting, returnTo: .connected)
        )
    }

    func testDisconnectedPreservesWhateverThePreviousFrameShowed() {
        XCTAssertTrue(RideModePresentation.nextRideModeVisibility(previous: true, status: .disconnected, returnTo: nil))
        XCTAssertFalse(RideModePresentation.nextRideModeVisibility(previous: false, status: .disconnected, returnTo: nil))
    }

    func testEveryOtherStatusHidesRideModeRegardlessOfPreviousFrame() {
        for status: SessionStatus in [.idle, .discovering, .pairing, .connecting, .connected, .ending, .error] {
            XCTAssertFalse(
                RideModePresentation.nextRideModeVisibility(previous: true, status: status, returnTo: nil), "\(status)"
            )
            XCTAssertFalse(
                RideModePresentation.nextRideModeVisibility(previous: false, status: status, returnTo: nil), "\(status)"
            )
        }
    }

    /// The full ride/reconnect/recovery/end cycle brief §15 describes, frame by frame.
    func testAFullRideReconnectRecoveryCycleStaysVisibleThroughout() {
        var visible = false
        visible = RideModePresentation.nextRideModeVisibility(previous: visible, status: .rideActive, returnTo: nil)
        XCTAssertTrue(visible, "ride starts")
        visible = RideModePresentation.nextRideModeVisibility(previous: visible, status: .reconnecting, returnTo: .rideActive)
        XCTAssertTrue(visible, "link blips — must not bounce to the main screen")
        visible = RideModePresentation.nextRideModeVisibility(previous: visible, status: .rideActive, returnTo: nil)
        XCTAssertTrue(visible, "recovered")
        visible = RideModePresentation.nextRideModeVisibility(previous: visible, status: .connected, returnTo: nil)
        XCTAssertFalse(visible, "End Ride, via SessionFsm, ends the ride")
    }

    /// Budget exhaustion (brief §15): the retry banner must appear *inside* Ride Mode, not after the
    /// rider has already been dropped back to the main screen.
    func testBudgetExhaustionAfterAFailedReconnectStaysVisibleForTheRetryBanner() {
        var visible = false
        visible = RideModePresentation.nextRideModeVisibility(previous: visible, status: .rideActive, returnTo: nil)
        visible = RideModePresentation.nextRideModeVisibility(previous: visible, status: .reconnecting, returnTo: .rideActive)
        visible = RideModePresentation.nextRideModeVisibility(previous: visible, status: .disconnected, returnTo: nil)
        XCTAssertTrue(visible, "budget exhausted — retry banner must still be inside Ride Mode")
    }

    /// PROTOCOL §10's 120 s budget: only `DISCONNECTED` may surface an explicit action; an ordinary
    /// `RECONNECTING` — however long it has been running — must stay passive.
    func testTheReconnectBudgetReadsAsExhaustedOnlyOnceDisconnected() {
        XCTAssertFalse(RideModePresentation.reconnectBudgetExhausted(.reconnecting))
        XCTAssertTrue(RideModePresentation.reconnectBudgetExhausted(.disconnected))
    }

    // MARK: - Now playing

    func testNowPlayingReportsThePlayingTrack() {
        let entry = LibraryEntry.fixture(title: "A Song", artist: "An Artist")
        let state = PlayerState(positionMs: 0, durationMs: 1_000, playing: true)
        let result = RideModePresentation.nowPlaying(entry: entry, playerState: state)
        XCTAssertEqual("A Song", result.title)
        XCTAssertEqual("An Artist", result.artist)
        XCTAssertTrue(result.playing)
    }

    func testNowPlayingReportsThePausedTrack() {
        let entry = LibraryEntry.fixture(title: "A Song", artist: "An Artist")
        let state = PlayerState(positionMs: 500, durationMs: 1_000, playing: false)
        let result = RideModePresentation.nowPlaying(entry: entry, playerState: state)
        XCTAssertFalse(result.playing)
    }

    func testNowPlayingWithNoEntryReportsNoTitleOrArtist() {
        let result = RideModePresentation.nowPlaying(entry: nil, playerState: PlayerState())
        XCTAssertNil(result.title)
        XCTAssertNil(result.artist)
    }

    // MARK: - Microphone

    func testMicrophoneUnavailableWhenCaptureIsNotOpen() {
        var voice = VoiceDiagnostics()
        voice.localAudioOpen = false
        XCTAssertEqual(.unavailable, RideModePresentation.microphoneState(voice: voice, policy: .modeA))
    }

    func testMicrophoneMutedUnderFullDuplex() {
        var voice = VoiceDiagnostics()
        voice.localAudioOpen = true
        voice.userMuted = true
        XCTAssertEqual(.mutedIdle, RideModePresentation.microphoneState(voice: voice, policy: .modeA))
    }

    func testMicrophoneUnmutedUnderFullDuplex() {
        var voice = VoiceDiagnostics()
        voice.localAudioOpen = true
        voice.userMuted = false
        XCTAssertEqual(.unmutedIdle, RideModePresentation.microphoneState(voice: voice, policy: .modeA))
    }

    func testPttModeReportsIdleWhenNotHeld() {
        var voice = VoiceDiagnostics()
        voice.localAudioOpen = true
        voice.pttHeld = false
        XCTAssertEqual(.pttIdle, RideModePresentation.microphoneState(voice: voice, policy: .modeC))
    }

    func testPttModeReportsTalkingWhileHeld() {
        var voice = VoiceDiagnostics()
        voice.localAudioOpen = true
        voice.pttHeld = true
        XCTAssertEqual(.pttTalking, RideModePresentation.microphoneState(voice: voice, policy: .modeC))
    }

    /// PTT's mute latch must not leak into the microphone label — the gate decides the shape, not
    /// the ordinary Mute button, which is meaningless under PTT (`VoiceCard`'s own reasoning).
    func testUnderPttTheOrdinaryMuteLatchDoesNotChangeTheLabel() {
        var voice = VoiceDiagnostics()
        voice.localAudioOpen = true
        voice.userMuted = true
        voice.pttHeld = false
        XCTAssertEqual(.pttIdle, RideModePresentation.microphoneState(voice: voice, policy: .modeC))
    }

    // MARK: - Intercom mode

    func testIntercomModeLabelStripsTheModePrefix() {
        XCTAssertEqual("Mode C", RideModePresentation.intercomModeLabel(.modeC))
    }

    func testIntercomDisabledUnderModeE() {
        XCTAssertFalse(IntercomPolicy.modeE.intercomEnabled)
    }

    func testIntercomEnabledUnderTheOtherFourModes() {
        for policy in [IntercomPolicy.modeA, .modeB, .modeC, .modeD] {
            XCTAssertTrue(policy.intercomEnabled, "\(policy.id)")
        }
    }

    // MARK: - Audio/route health

    func testAudioHealthIsUnknownWithNoRouteReportedYet() {
        XCTAssertEqual(.unknown, RideModePresentation.audioHealth(route: nil))
    }

    func testAudioHealthIsNormalOnAFullQualityStableRoute() {
        var route = AudioRouteSnapshot()
        route.routeState = .stable
        route.effectiveOutputProfile = .mediaStereo
        route.effectiveInputProfile = .mediaStereo
        XCTAssertEqual(.normal, RideModePresentation.audioHealth(route: route))
    }

    func testAudioHealthIsDegradedWhileTheRouteIsTransitioning() {
        var route = AudioRouteSnapshot()
        route.routeState = .transitioning
        XCTAssertEqual(.degraded, RideModePresentation.audioHealth(route: route))
    }

    func testAudioHealthIsDegradedOnAReducedQualityRoute() {
        var route = AudioRouteSnapshot()
        route.routeState = .stable
        route.effectiveOutputProfile = .duplexNarrowband
        route.effectiveInputProfile = .duplexNarrowband
        XCTAssertEqual(.degraded, RideModePresentation.audioHealth(route: route))
    }
}

extension LibraryEntry {
    /// A minimal fixture for presentation tests — fabricated values only, matching this file's
    /// no-real-metadata convention. Mirrors `ManifestGeneratorTests`'s own fixture shape.
    static func fixture(title: String, artist: String) -> LibraryEntry {
        LibraryEntry(
            localEntryId: LocalEntryId(UUID().uuidString.lowercased()),
            track: Track(
                contentHash: nil,
                quickId: QuickId("sha256:" + String(repeating: "a", count: 64)),
                title: title,
                artist: artist,
                album: "Album",
                durationMs: 1_000,
                filename: "fixture.m4a",
                codec: "aac",
                bitrateKbps: 192,
                artworkRef: nil,
                sizeBytes: 1_000_000
            ),
            location: LocalTrackLocation(uri: "file:///tmp/fixture.m4a"),
            decodeStatus: .indexed,
            indexedAtMonoUs: 0,
            lastSeenAtMonoUs: 0
        )
    }
}
