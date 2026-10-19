import RideLinkCore
@testable import RideLinkPlatform
import XCTest

final class UiPresentationTests: XCTestCase {
    func testDebtCompletionNeverLabelsLocalControlsSynchronized() {
        for state in [SyncState.scheduled, .synced] {
            XCTAssertEqual(UiPresentation.rideMusicLabel(status: .connected, state: state, ownsTransport: false), "Playing on this phone")
        }
        XCTAssertEqual(UiPresentation.rideMusicLabel(status: .rideActive, state: .synced, ownsTransport: true), "Playing on both phones")
    }

    func testConnectionLossOutranksDiagnosticsAndFailureRemainsVisible() {
        for state in [SyncState.inactive, .clockUnready, .waitingForContent, .waitingForQueue, .scheduled,
                      .synced, .syncFailed, .desynchronized, .transportFailed, .localOverload] {
            XCTAssertEqual(UiPresentation.rideMusicLabel(status: .reconnecting, state: state, ownsTransport: true),
                           "Music sync resumes when reconnected")
            XCTAssertEqual(UiPresentation.rideMusicLabel(status: .disconnected, state: state, ownsTransport: true),
                           "Other phone unavailable · Playing on this phone")
        }
        XCTAssertEqual(UiPresentation.rideMusicLabel(status: .rideActive, state: .syncFailed, ownsTransport: false), "Music sync paused")
    }

    func testFailuresAndPolicySemantics() {
        for failure in VoiceFailure.allCases {
            XCTAssertFalse(UiPresentation.voiceFailureLabel(failure).contains(failure.rawValue))
        }
        XCTAssertTrue(UiPresentation.voiceFailureLabel(.micPermissionDenied).contains("Settings"))
        XCTAssertEqual(UiPresentation.policyLabel(.modeD), "Always on · music paused")
        XCTAssertEqual(UiPresentation.policyLabel(.modeE), "Music only · intercom off")
    }

    /// Phase 9A.5 §4: primary UI says "other phone"; "peer" stays in diagnostics only. Mirrors
    /// Android's `UiLabelsTest`.
    func testPrimaryCopyNeverSaysPeer() {
        let statuses: [SessionStatus] = [.idle, .discovering, .pairing, .connecting, .connected, .rideActive,
                                         .reconnecting, .disconnected, .ending, .error]
        for status in statuses {
            XCTAssertFalse(UiPresentation.connectionTitle(status).localizedCaseInsensitiveContains("peer"))
            XCTAssertFalse(UiPresentation.connectionHint(status).localizedCaseInsensitiveContains("peer"))
        }
        for failure in VoiceFailure.allCases {
            XCTAssertFalse(UiPresentation.voiceFailureLabel(failure).localizedCaseInsensitiveContains("peer"))
        }
        for policy in IntercomPolicy.all {
            XCTAssertFalse(UiPresentation.policyLabel(policy).localizedCaseInsensitiveContains("peer"))
        }
    }

    /// STATUS §4 problem 126: the button and the coordinator read one policy, and the copy says what
    /// forgetting costs. Mirrors Android's `UiLabelsTest`.
    func testForgetIsOfferedOnlyInIdleAndItsConfirmationNamesTheSixDigitCode() {
        let statuses: [SessionStatus] = [.idle, .discovering, .pairing, .connecting, .connected, .rideActive,
                                         .reconnecting, .disconnected, .ending, .error]
        for status in statuses {
            let hint = UiPresentation.forgetPeerUnavailableHint(status)
            XCTAssertEqual(hint == nil, status == .idle, "\(status)")
            XCTAssertFalse(hint?.localizedCaseInsensitiveContains("peer") ?? false)
        }
        let confirmation = UiPresentation.forgetPeerConfirmation("Rider")
        XCTAssertTrue(confirmation.contains("six-digit code"))
        XCTAssertTrue(confirmation.contains("identity"), "it says the identity is kept")
        XCTAssertFalse(confirmation.localizedCaseInsensitiveContains("peer"))
        XCTAssertNil(UiPresentation.forgetPeerResultLabel(.forgotten))
        XCTAssertTrue(UiPresentation.forgetPeerResultLabel(.failed)?.contains("still trusted") ?? false)
        XCTAssertTrue(UiPresentation.pinMismatchExplanation.contains("Paired phones"))
    }
}
