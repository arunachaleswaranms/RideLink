import Foundation

/// Everything the "may the intercom start now?" decision depends on. All facts, no policy.
public struct RideStartRequest: Sendable, Equatable {
    /// There is a **resumed Activity** (Android) or the app is foreground-active (iOS). Only the UI layer
    /// can honestly claim this, which is why it is a parameter rather than something this policy could
    /// look up: ARCHITECTURE §6.4 step 1 is a precondition, not a query.
    public var appForegroundVisible: Bool
    public var micPermissionGranted: Bool
    /// Costs the lock-screen control surface if absent, not the ride (ARCHITECTURE §6.4).
    public var notificationsPermissionGranted: Bool
    /// The RideLink trust gate has passed (ADR-019). Voice is not permitted before it (§7.1).
    public var sessionAuthenticated: Bool
    /// The platform lists at least one audio endpoint usable for communication.
    public var audioEndpointPresent: Bool
    /// Capture is **already** open for this ride segment. The case this field exists for is a control
    /// reconnect while the screen is locked: the media transport must be rebuilt, and the capture device
    /// must **not** be reopened, because on Android there is no second legal opportunity to open it
    /// (ARCHITECTURE §6.4 step 6, PROTOCOL §7.8).
    public var captureAlreadyOpen: Bool
    /// Mode E has no intercom at all, so nothing is opened and nothing is refused.
    public var intercomEnabled: Bool

    public init(
        appForegroundVisible: Bool,
        micPermissionGranted: Bool,
        notificationsPermissionGranted: Bool,
        sessionAuthenticated: Bool,
        audioEndpointPresent: Bool,
        captureAlreadyOpen: Bool,
        intercomEnabled: Bool = true
    ) {
        self.appForegroundVisible = appForegroundVisible
        self.micPermissionGranted = micPermissionGranted
        self.notificationsPermissionGranted = notificationsPermissionGranted
        self.sessionAuthenticated = sessionAuthenticated
        self.audioEndpointPresent = audioEndpointPresent
        self.captureAlreadyOpen = captureAlreadyOpen
        self.intercomEnabled = intercomEnabled
    }
}

/// Something the user should be told about a start that is nevertheless going ahead.
public enum RideStartWarning: String, Sendable, Equatable, CaseIterable {
    /// `POST_NOTIFICATIONS` denied: the service still runs, but the lock-screen surface is gone.
    case noLockScreenSurface = "NO_LOCK_SCREEN_SURFACE"
}

/// What the app must do — or must not do — about a request to start the intercom.
///
/// The two `allowed` flags are separate because the order between them is the platform rule:
/// ARCHITECTURE §6.4 requires the microphone foreground service to be running **before** the capture path
/// opens, and there is no legal way to do it the other way round.
public enum RideStartDecision: Sendable, Equatable {
    case allowed(startForegroundServiceWithMicrophone: Bool, openCapture: Bool, warnings: Set<RideStartWarning>)
    /// The intercom does not start. The failure is the specific reason, never a generic one, and the
    /// control session is untouched: a refused intercom is not a refused session (§41).
    case refused(VoiceFailure)
    /// Mode E. There is nothing to start and nothing has gone wrong — a distinct outcome from `.refused`,
    /// because an amber "intercom unavailable" would be wrong here.
    case intercomDisabled
}

/// ARCHITECTURE §6.4's start sequence, as a pure decision.
///
/// ```
/// 1  RideLink is visibly open (a resumed Activity)                       <- precondition
/// 2  Permissions granted, or handled if denied
/// 3  Readiness gate: session authenticated, audio endpoint present
/// 4  User taps START INTERCOM
/// 5  While still foreground-visible, start the ride foreground service
/// 6  Still foreground-visible: select the communication device, OPEN capture
/// 7  The user may now lock the screen
/// ```
///
/// The reason this is a shared pure type rather than a few `if`s in the UI is that steps 1 and 5–6 are the
/// ones a device test (AF-01, AF-03, AF-04, AF-09) exists to check, and none of those can run here.
/// Extracting the decision means the *policy* is exhausted by a laptop test on both platforms and what
/// remains untested on a device is only the platform call itself.
///
/// Order of the checks below is deliberate and is asserted by its tests: the background rule is evaluated
/// **before** the permission and endpoint checks, because "bring RideLink to the front" is the actionable
/// answer even when a permission is also missing — a permission dialog cannot be shown from the
/// background either.
public enum RideStartPolicy {
    public static func decide(_ request: RideStartRequest) -> RideStartDecision {
        guard request.intercomEnabled else { return .intercomDisabled }
        guard request.sessionAuthenticated else { return .refused(.sessionNotAuthenticated) }

        // A reconnect while backgrounded: the capture path is already open and must stay open, so there is
        // nothing here that needs foreground visibility. Rebuilding the media transport is permitted;
        // reopening capture is neither needed nor legal (PROTOCOL §7.8).
        if request.captureAlreadyOpen {
            return .allowed(
                startForegroundServiceWithMicrophone: true,
                openCapture: false,
                warnings: warnings(for: request)
            )
        }

        guard request.appForegroundVisible else { return .refused(.backgroundStartRefused) }
        guard request.micPermissionGranted else { return .refused(.micPermissionDenied) }
        guard request.audioEndpointPresent else { return .refused(.noAudioEndpoint) }

        return .allowed(
            startForegroundServiceWithMicrophone: true,
            openCapture: true,
            warnings: warnings(for: request)
        )
    }

    private static func warnings(for request: RideStartRequest) -> Set<RideStartWarning> {
        request.notificationsPermissionGranted ? [] : [.noLockScreenSurface]
    }
}
