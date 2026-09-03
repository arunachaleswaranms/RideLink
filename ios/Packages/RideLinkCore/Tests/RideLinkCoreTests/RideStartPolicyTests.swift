import XCTest

@testable import RideLinkCore

/// ARCHITECTURE §6.4's start sequence, exhausted.
///
/// These are the laptop half of TEST_PLAN AF-01, AF-03, AF-04 and AF-09 — the *policy* half. What they
/// cannot touch is whether Android actually accepts the `microphone` foreground-service type or whether
/// capture survives a screen lock; those are device facts and remain pending (docs/STATUS.md §4
/// problem 25). The mirror is `com.ridelink.core.audiopolicy.RideStartPolicyTest`.
final class RideStartPolicyTests: XCTestCase {
    /// Seven independent booleans.
    private let crossProductSize = 128

    private func request(
        appForegroundVisible: Bool = true,
        micPermissionGranted: Bool = true,
        notificationsPermissionGranted: Bool = true,
        sessionAuthenticated: Bool = true,
        audioEndpointPresent: Bool = true,
        captureAlreadyOpen: Bool = false,
        intercomEnabled: Bool = true
    ) -> RideStartRequest {
        RideStartRequest(
            appForegroundVisible: appForegroundVisible,
            micPermissionGranted: micPermissionGranted,
            notificationsPermissionGranted: notificationsPermissionGranted,
            sessionAuthenticated: sessionAuthenticated,
            audioEndpointPresent: audioEndpointPresent,
            captureAlreadyOpen: captureAlreadyOpen,
            intercomEnabled: intercomEnabled
        )
    }

    /// AF-01: everything granted, foreground-visible. The service goes up, then capture opens.
    func testAForegroundVisibleStartWithEverythingGrantedIsAllowed() {
        XCTAssertEqual(
            .allowed(startForegroundServiceWithMicrophone: true, openCapture: true, warnings: []),
            RideStartPolicy.decide(request())
        )
    }

    /// AF-04/AF-09, and the hard platform rule this policy exists for: a first microphone start from the
    /// background is **refused**, and the reason names the actionable answer.
    func testAFirstStartFromTheBackgroundIsRefusedByName() {
        XCTAssertEqual(
            .refused(.backgroundStartRefused),
            RideStartPolicy.decide(request(appForegroundVisible: false))
        )
    }

    /// The background rule is checked **before** the permission and endpoint checks. "Bring RideLink to
    /// the front" is the actionable answer even when a permission is also missing, because a permission
    /// dialog cannot be shown from the background either.
    func testTheBackgroundRuleOutranksAMissingPermissionAndAMissingEndpoint() {
        XCTAssertEqual(
            .refused(.backgroundStartRefused),
            RideStartPolicy.decide(
                request(appForegroundVisible: false, micPermissionGranted: false, audioEndpointPresent: false)
            )
        )
    }

    /// **PROTOCOL §7.8's reconnect case, and the one this field exists for.** Capture is already open for
    /// this ride segment; the media transport must be rebuilt and capture must **not** be reopened,
    /// because on Android there is no second legal opportunity to open it. So a reconnect while the screen
    /// is locked is allowed and opens nothing.
    func testAReconnectWhileBackgroundedIsAllowedAndMustNotReopenCapture() {
        XCTAssertEqual(
            .allowed(startForegroundServiceWithMicrophone: true, openCapture: false, warnings: []),
            RideStartPolicy.decide(request(appForegroundVisible: false, captureAlreadyOpen: true))
        )
    }

    /// And it holds even if the permission has since been revoked: the open device is what matters.
    func testAReconnectWithCaptureAlreadyOpenDoesNotRecheckTheMicrophonePermission() {
        let decision = RideStartPolicy.decide(
            request(appForegroundVisible: false, micPermissionGranted: false, captureAlreadyOpen: true)
        )
        guard case .allowed(_, let openCapture, _) = decision else {
            return XCTFail("an open capture path is the fact that decides this")
        }
        XCTAssertFalse(openCapture)
    }

    /// AF-03: FR-025's graceful degradation. A denied microphone is a named refusal, not a crash.
    func testADeniedMicrophonePermissionIsRefusedByName() {
        XCTAssertEqual(
            .refused(.micPermissionDenied),
            RideStartPolicy.decide(request(micPermissionGranted: false))
        )
    }

    func testNoAudioEndpointIsRefusedByName() {
        XCTAssertEqual(
            .refused(.noAudioEndpoint),
            RideStartPolicy.decide(request(audioEndpointPresent: false))
        )
    }

    /// PROTOCOL §7.1: voice is not permitted at all before the ADR-019 trust gate.
    func testAnUnauthenticatedSessionIsRefusedBeforeAnyOtherCheck() {
        XCTAssertEqual(
            .refused(.sessionNotAuthenticated),
            RideStartPolicy.decide(
                request(appForegroundVisible: false, micPermissionGranted: false, sessionAuthenticated: false)
            )
        )
    }

    /// AF-06: `POST_NOTIFICATIONS` denied costs the lock-screen control surface, not the ride. It is a
    /// warning on an allowed start, not a refusal — and it is a *named* warning so the UI can explain what
    /// was lost.
    func testADeniedNotificationPermissionIsAWarningOnAnAllowedStart() {
        XCTAssertEqual(
            .allowed(
                startForegroundServiceWithMicrophone: true,
                openCapture: true,
                warnings: [.noLockScreenSurface]
            ),
            RideStartPolicy.decide(request(notificationsPermissionGranted: false))
        )
    }

    /// AF-02: Mode E. Nothing to start and nothing wrong — a distinct outcome from a refusal, because an
    /// amber "intercom unavailable" would be the wrong thing to show.
    func testModeEIsNeitherAllowedNorRefused() {
        XCTAssertEqual(.intercomDisabled, RideStartPolicy.decide(request(intercomEnabled: false)))
        // And it outranks everything else, including a missing permission: there is no microphone to ask
        // for.
        XCTAssertEqual(
            .intercomDisabled,
            RideStartPolicy.decide(
                request(appForegroundVisible: false, micPermissionGranted: false, intercomEnabled: false)
            )
        )
    }

    /// The whole request cross-product, as a property: **no decision ever opens capture while the app is
    /// not foreground-visible.** That is ARCHITECTURE §6.4 step 6 stated as an invariant rather than as a
    /// sequence, and it is the one a future refactor is most likely to break.
    func testNoDecisionInTheWholeCrossProductOpensCaptureFromTheBackground() {
        var covered = 0
        for visible in [true, false] {
            for mic in [true, false] {
                for notifications in [true, false] {
                    for authenticated in [true, false] {
                        for endpoint in [true, false] {
                            for alreadyOpen in [true, false] {
                                for enabled in [true, false] {
                                    let decision = RideStartPolicy.decide(
                                        request(
                                            appForegroundVisible: visible,
                                            micPermissionGranted: mic,
                                            notificationsPermissionGranted: notifications,
                                            sessionAuthenticated: authenticated,
                                            audioEndpointPresent: endpoint,
                                            captureAlreadyOpen: alreadyOpen,
                                            intercomEnabled: enabled
                                        )
                                    )
                                    covered += 1
                                    guard case .allowed(_, let openCapture, _) = decision, openCapture else { continue }
                                    XCTAssertTrue(visible, "opened capture from the background")
                                    XCTAssertTrue(mic, "opened capture without a microphone permission")
                                    XCTAssertTrue(endpoint, "opened capture with no audio endpoint")
                                    XCTAssertTrue(authenticated, "opened capture without an authenticated peer")
                                    XCTAssertTrue(enabled, "opened capture with the intercom disabled")
                                }
                            }
                        }
                    }
                }
            }
        }
        XCTAssertEqual(crossProductSize, covered, "the whole cross-product must be visited")
    }
}
