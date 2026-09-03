package com.ridelink.core.audiopolicy

/** Everything the "may the intercom start now?" decision depends on. All facts, no policy. */
data class RideStartRequest(
    /**
     * There is a **resumed Activity** (Android) or the app is foreground-active (iOS). Only the UI
     * layer can honestly claim this, which is why it is a parameter rather than something this
     * policy could look up: ARCHITECTURE §6.4 step 1 is a precondition, not a query.
     */
    val appForegroundVisible: Boolean,
    val micPermissionGranted: Boolean,
    /** Costs the lock-screen control surface if absent, not the ride (ARCHITECTURE §6.4). */
    val notificationsPermissionGranted: Boolean,
    /** The RideLink trust gate has passed (ADR-019). Voice is not permitted before it (§7.1). */
    val sessionAuthenticated: Boolean,
    /** The platform lists at least one audio endpoint usable for communication. */
    val audioEndpointPresent: Boolean,
    /**
     * Capture is **already** open for this ride segment. The case this field exists for is a control
     * reconnect while the screen is locked: the media transport must be rebuilt, and the capture
     * device must **not** be reopened, because on Android there is no second legal opportunity to
     * open it (ARCHITECTURE §6.4 step 6, PROTOCOL §7.8).
     */
    val captureAlreadyOpen: Boolean,
    /** Mode E has no intercom at all, so nothing is opened and nothing is refused. */
    val intercomEnabled: Boolean = true,
)

/** Something the user should be told about a start that is nevertheless going ahead. */
enum class RideStartWarning {
    /** `POST_NOTIFICATIONS` denied: the service still runs, but the lock-screen surface is gone. */
    NO_LOCK_SCREEN_SURFACE,
}

/**
 * What the app must do — or must not do — about a request to start the intercom.
 *
 * The two `Allowed` flags are separate because the order between them is the platform rule:
 * ARCHITECTURE §6.4 requires the microphone foreground service to be running **before** the capture
 * path opens, and there is no legal way to do it the other way round.
 */
sealed class RideStartDecision {
    data class Allowed(
        /** Start (or keep) the ride foreground service, declaring the `microphone` type. */
        val startForegroundServiceWithMicrophone: Boolean,
        /** Open the platform audio session and capture path. False when it is already open. */
        val openCapture: Boolean,
        val warnings: Set<RideStartWarning> = emptySet(),
    ) : RideStartDecision()

    /**
     * The intercom does not start. [failure] is the specific reason, never a generic one, and the
     * control session is untouched: a refused intercom is not a refused session (§41).
     */
    data class Refused(
        val failure: VoiceFailure,
    ) : RideStartDecision()

    /**
     * Mode E. There is nothing to start and nothing has gone wrong — a distinct outcome from
     * [Refused], because an amber "intercom unavailable" would be wrong here.
     */
    object IntercomDisabled : RideStartDecision()
}

/**
 * ARCHITECTURE §6.4's start sequence, as a pure decision.
 *
 * ```
 * 1  RideLink is visibly open (a resumed Activity)                       <- precondition
 * 2  Permissions granted, or handled if denied
 * 3  Readiness gate: session authenticated, audio endpoint present
 * 4  User taps START INTERCOM
 * 5  While still foreground-visible, start the ride foreground service
 * 6  Still foreground-visible: select the communication device, OPEN capture
 * 7  The user may now lock the screen
 * ```
 *
 * The reason this is a shared pure type rather than a few `if`s in `MainActivity` is that steps 1
 * and 5–6 are the ones a device test (AF-01, AF-03, AF-04, AF-09) exists to check, and none of those
 * can run here. Extracting the decision means the *policy* is exhausted by a JVM test on both
 * platforms and what remains untested on a device is only the platform call itself.
 *
 * Order of the checks below is deliberate and is asserted by its tests: the background rule is
 * evaluated **before** the permission and endpoint checks, because "bring RideLink to the front" is
 * the actionable answer even when a permission is also missing — a permission dialog cannot be shown
 * from the background either.
 */
object RideStartPolicy {
    @Suppress("ReturnCount") // one early-out per ARCHITECTURE §6.4 precondition, in that order
    fun decide(request: RideStartRequest): RideStartDecision {
        if (!request.intercomEnabled) return RideStartDecision.IntercomDisabled
        if (!request.sessionAuthenticated) return RideStartDecision.Refused(VoiceFailure.SESSION_NOT_AUTHENTICATED)

        // A reconnect while backgrounded: the capture path is already open and must stay open, so
        // there is nothing here that needs foreground visibility. Rebuilding the media transport is
        // permitted; reopening capture is neither needed nor legal (PROTOCOL §7.8).
        if (request.captureAlreadyOpen) {
            return RideStartDecision.Allowed(
                startForegroundServiceWithMicrophone = true,
                openCapture = false,
                warnings = warningsFor(request),
            )
        }

        if (!request.appForegroundVisible) return RideStartDecision.Refused(VoiceFailure.BACKGROUND_START_REFUSED)
        if (!request.micPermissionGranted) return RideStartDecision.Refused(VoiceFailure.MIC_PERMISSION_DENIED)
        if (!request.audioEndpointPresent) return RideStartDecision.Refused(VoiceFailure.NO_AUDIO_ENDPOINT)

        return RideStartDecision.Allowed(
            startForegroundServiceWithMicrophone = true,
            openCapture = true,
            warnings = warningsFor(request),
        )
    }

    private fun warningsFor(request: RideStartRequest): Set<RideStartWarning> =
        if (request.notificationsPermissionGranted) {
            emptySet()
        } else {
            setOf(RideStartWarning.NO_LOCK_SCREEN_SURFACE)
        }
}
