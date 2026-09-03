import XCTest

@testable import RideLinkCore

/// `VoiceSetupTimeline` / `VoiceSetupTimer` — the software timing instrumentation this phase adds.
///
/// **Read the type's own doc before reading anything into these numbers.** They are *setup* times: how
/// long the app took to bring voice up. Mouth-to-ear latency (TEST_PLAN A-09/V-11) is a different
/// quantity that requires a real microphone, a real earbud and a recorder, and nothing here bears on
/// it. The mirror is `com.ridelink.core.voice.VoiceSetupTimelineTest`.
final class VoiceSetupTimelineTests: XCTestCase {
    func testAnEmptyTimelineReportsNoMeasurementRatherThanZero() {
        let timeline = VoiceSetupTimeline()
        XCTAssertNil(timeline.captureOpenMs)
        XCTAssertNil(timeline.localDescriptionMs)
        XCTAssertNil(timeline.signallingMs)
        XCTAssertNil(timeline.mediaConnectedMs)
        XCTAssertNil(timeline.setupMs, "no remote track yet means no setup figure, not a zero")
    }

    func testAFullSequenceProducesOneFigurePerStageInMonotonicMicroseconds() {
        var timeline = VoiceSetupTimer.restart(atMonoUs: 1_000_000)
        timeline = VoiceSetupTimer.mark(timeline, .captureOpen, atMonoUs: 2_500_000)
        timeline = VoiceSetupTimer.mark(timeline, .localDescription, atMonoUs: 2_600_000)
        timeline = VoiceSetupTimer.mark(timeline, .remoteDescription, atMonoUs: 2_800_000)
        timeline = VoiceSetupTimer.mark(timeline, .mediaConnected, atMonoUs: 3_100_000)
        timeline = VoiceSetupTimer.mark(timeline, .remoteTrack, atMonoUs: 3_200_000)

        XCTAssertEqual(1_500.0, timeline.captureOpenMs, "the route/profile switch dominates this on Bluetooth")
        XCTAssertEqual(1_600.0, timeline.localDescriptionMs)
        XCTAssertEqual(1_800.0, timeline.signallingMs)
        XCTAssertEqual(2_100.0, timeline.mediaConnectedMs)
        XCTAssertEqual(2_200.0, timeline.setupMs)
    }

    /// First-write-wins per milestone. WebRTC reports `connected` more than once across a session's
    /// disconnect/reconnect cycles, and the setup figure is about the *first* time each milestone was
    /// reached — otherwise a long ride's numbers would drift upward for no reason.
    func testARepeatedMarkDoesNotMoveTheFigure() {
        var timeline = VoiceSetupTimer.restart(atMonoUs: 0)
        timeline = VoiceSetupTimer.mark(timeline, .mediaConnected, atMonoUs: 500_000)
        timeline = VoiceSetupTimer.mark(timeline, .mediaConnected, atMonoUs: 9_000_000)
        XCTAssertEqual(500.0, timeline.mediaConnectedMs, "the first connect is the one that counts")
    }

    /// A restart is per negotiation, which is what makes the figure per-generation (PROTOCOL §7.8).
    func testRestartingClearsEveryEarlierMark() {
        var timeline = VoiceSetupTimer.restart(atMonoUs: 0)
        timeline = VoiceSetupTimer.mark(timeline, .remoteTrack, atMonoUs: 1_000_000)
        XCTAssertEqual(1_000.0, timeline.setupMs)

        timeline = VoiceSetupTimer.restart(atMonoUs: 5_000_000)
        XCTAssertNil(timeline.setupMs, "a rebuild starts a fresh measurement")
        XCTAssertEqual(5_000_000, timeline.startRequestedAtMonoUs)
    }

    /// Marks recorded out of order would mean a bug in the caller, not a fast connection. Reported as
    /// absent rather than as a negative number — the same discipline `ClockSync` applies to an
    /// implausible sample.
    func testAnOutOfOrderMarkReportsNoMeasurementRatherThanANegativeOne() {
        var timeline = VoiceSetupTimer.restart(atMonoUs: 5_000_000)
        timeline = VoiceSetupTimer.mark(timeline, .remoteTrack, atMonoUs: 1_000_000)
        XCTAssertNil(timeline.setupMs, "a negative span is a caller bug, not a measurement")
    }

    func testMarkingStartTwiceKeepsTheFirstInstant() {
        var timeline = VoiceSetupTimer.restart(atMonoUs: 1_000)
        timeline = VoiceSetupTimer.mark(timeline, .startRequested, atMonoUs: 9_000)
        XCTAssertEqual(1_000, timeline.startRequestedAtMonoUs)
    }

    /// Every mark is reachable, so none can be silently dropped by a `switch` that forgot one.
    func testEveryMarkCanBeRecorded() {
        for mark in VoiceSetupMark.allCases {
            let timeline = VoiceSetupTimer.mark(VoiceSetupTimeline(), mark, atMonoUs: 42)
            let recorded: Int64?
            switch mark {
            case .startRequested: recorded = timeline.startRequestedAtMonoUs
            case .captureOpen: recorded = timeline.captureOpenAtMonoUs
            case .localDescription: recorded = timeline.localDescriptionAtMonoUs
            case .remoteDescription: recorded = timeline.remoteDescriptionAtMonoUs
            case .mediaConnected: recorded = timeline.mediaConnectedAtMonoUs
            case .remoteTrack: recorded = timeline.remoteTrackAtMonoUs
            }
            XCTAssertEqual(42, recorded, "\(mark) was not recorded")
        }
    }
}
