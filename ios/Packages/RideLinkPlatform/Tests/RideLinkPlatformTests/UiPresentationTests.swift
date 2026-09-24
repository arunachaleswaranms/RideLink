import RideLinkCore
@testable import RideLinkPlatform
import XCTest

final class UiPresentationTests: XCTestCase {
    func testDebtCompletionNeverLabelsLocalControlsSynchronized() {
        for state in [SyncState.scheduled, .synced] {
            XCTAssertEqual(UiPresentation.rideMusicLabel(status: .connected, state: state, ownsTransport: false), "Local playback")
        }
        XCTAssertEqual(UiPresentation.rideMusicLabel(status: .rideActive, state: .synced, ownsTransport: true), "Synchronized")
    }

    func testConnectionLossOutranksDiagnosticsAndFailureRemainsVisible() {
        for state in [SyncState.inactive, .clockUnready, .waitingForContent, .waitingForQueue, .scheduled,
                      .synced, .syncFailed, .desynchronized, .transportFailed, .localOverload] {
            XCTAssertEqual(UiPresentation.rideMusicLabel(status: .reconnecting, state: state, ownsTransport: true),
                           "Music sync waits for connection")
            XCTAssertEqual(UiPresentation.rideMusicLabel(status: .disconnected, state: state, ownsTransport: true),
                           "Peer unavailable · Local controls")
        }
        XCTAssertEqual(UiPresentation.rideMusicLabel(status: .rideActive, state: .syncFailed, ownsTransport: false), "Music sync paused")
    }

    func testFailuresAndPolicySemantics() {
        for failure in VoiceFailure.allCases {
            XCTAssertFalse(UiPresentation.voiceFailureLabel(failure).contains(failure.rawValue))
        }
        XCTAssertTrue(UiPresentation.voiceFailureLabel(.micPermissionDenied).contains("Settings"))
        XCTAssertEqual(UiPresentation.policyLabel(.modeD), "D · Continuous / pause music")
        XCTAssertEqual(UiPresentation.policyLabel(.modeE), "E · Music only")
    }
}
