package com.ridelink.core.audiopolicy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ARCHITECTURE §6.4's start sequence, exhausted.
 *
 * These are the laptop half of TEST_PLAN AF-01, AF-03, AF-04 and AF-09 — the *policy* half. What
 * they cannot touch is whether Android actually accepts the `microphone` foreground-service type or
 * whether capture survives a screen lock; those are device facts and remain pending
 * (docs/STATUS.md §4 problem 25). The mirror is `RideLinkCoreTests.RideStartPolicyTests`.
 */
class RideStartPolicyTest {
    private fun request(
        appForegroundVisible: Boolean = true,
        micPermissionGranted: Boolean = true,
        notificationsPermissionGranted: Boolean = true,
        sessionAuthenticated: Boolean = true,
        audioEndpointPresent: Boolean = true,
        captureAlreadyOpen: Boolean = false,
        intercomEnabled: Boolean = true,
    ) = RideStartRequest(
        appForegroundVisible = appForegroundVisible,
        micPermissionGranted = micPermissionGranted,
        notificationsPermissionGranted = notificationsPermissionGranted,
        sessionAuthenticated = sessionAuthenticated,
        audioEndpointPresent = audioEndpointPresent,
        captureAlreadyOpen = captureAlreadyOpen,
        intercomEnabled = intercomEnabled,
    )

    /** AF-01: everything granted, foreground-visible. The service goes up, then capture opens. */
    @Test
    fun `a foreground-visible start with everything granted is allowed`() {
        val decision = RideStartPolicy.decide(request())
        assertEquals(
            RideStartDecision.Allowed(startForegroundServiceWithMicrophone = true, openCapture = true),
            decision,
        )
    }

    /**
     * AF-04/AF-09, and the hard platform rule this policy exists for: a first microphone start from
     * the background is **refused**, and the reason names the actionable answer.
     */
    @Test
    fun `a first start from the background is refused by name`() {
        assertEquals(
            RideStartDecision.Refused(VoiceFailure.BACKGROUND_START_REFUSED),
            RideStartPolicy.decide(request(appForegroundVisible = false)),
        )
    }

    /**
     * The background rule is checked **before** the permission and endpoint checks. "Bring RideLink
     * to the front" is the actionable answer even when a permission is also missing, because a
     * permission dialog cannot be shown from the background either.
     */
    @Test
    fun `the background rule outranks a missing permission and a missing endpoint`() {
        assertEquals(
            RideStartDecision.Refused(VoiceFailure.BACKGROUND_START_REFUSED),
            RideStartPolicy.decide(
                request(appForegroundVisible = false, micPermissionGranted = false, audioEndpointPresent = false),
            ),
        )
    }

    /**
     * **PROTOCOL §7.8's reconnect case, and the one this field exists for.** Capture is already open
     * for this ride segment; the media transport must be rebuilt and capture must **not** be
     * reopened, because on Android there is no second legal opportunity to open it. So a reconnect
     * while the screen is locked is allowed and opens nothing.
     */
    @Test
    fun `a reconnect while backgrounded is allowed and must not reopen capture`() {
        val decision = RideStartPolicy.decide(request(appForegroundVisible = false, captureAlreadyOpen = true))
        assertEquals(
            RideStartDecision.Allowed(startForegroundServiceWithMicrophone = true, openCapture = false),
            decision,
        )
    }

    /** And it holds even if the permission has since been revoked: the open device is what matters. */
    @Test
    fun `a reconnect with capture already open does not re-check the microphone permission`() {
        val decision =
            RideStartPolicy.decide(
                request(appForegroundVisible = false, micPermissionGranted = false, captureAlreadyOpen = true),
            )
        assertTrue(decision is RideStartDecision.Allowed, "an open capture path is the fact that decides this")
        assertEquals(false, decision.openCapture)
    }

    /** AF-03: FR-025's graceful degradation. A denied microphone is a named refusal, not a crash. */
    @Test
    fun `a denied microphone permission is refused by name`() {
        assertEquals(
            RideStartDecision.Refused(VoiceFailure.MIC_PERMISSION_DENIED),
            RideStartPolicy.decide(request(micPermissionGranted = false)),
        )
    }

    @Test
    fun `no audio endpoint is refused by name`() {
        assertEquals(
            RideStartDecision.Refused(VoiceFailure.NO_AUDIO_ENDPOINT),
            RideStartPolicy.decide(request(audioEndpointPresent = false)),
        )
    }

    /** PROTOCOL §7.1: voice is not permitted at all before the ADR-019 trust gate. */
    @Test
    fun `an unauthenticated session is refused before any other check`() {
        assertEquals(
            RideStartDecision.Refused(VoiceFailure.SESSION_NOT_AUTHENTICATED),
            RideStartPolicy.decide(
                request(sessionAuthenticated = false, appForegroundVisible = false, micPermissionGranted = false),
            ),
        )
    }

    /**
     * AF-06: `POST_NOTIFICATIONS` denied costs the lock-screen control surface, not the ride. It is a
     * warning on an allowed start, not a refusal — and it is a *named* warning so the UI can explain
     * what was lost.
     */
    @Test
    fun `a denied notification permission is a warning on an allowed start`() {
        val decision = RideStartPolicy.decide(request(notificationsPermissionGranted = false))
        assertEquals(
            RideStartDecision.Allowed(
                startForegroundServiceWithMicrophone = true,
                openCapture = true,
                warnings = setOf(RideStartWarning.NO_LOCK_SCREEN_SURFACE),
            ),
            decision,
        )
    }

    /**
     * AF-02: Mode E. Nothing to start and nothing wrong — a distinct outcome from a refusal, because
     * an amber "intercom unavailable" would be the wrong thing to show.
     */
    @Test
    fun `mode E is neither allowed nor refused`() {
        assertEquals(
            RideStartDecision.IntercomDisabled,
            RideStartPolicy.decide(request(intercomEnabled = false)),
        )
        // And it outranks everything else, including a missing permission: there is no microphone to
        // ask for.
        assertEquals(
            RideStartDecision.IntercomDisabled,
            RideStartPolicy.decide(
                request(intercomEnabled = false, micPermissionGranted = false, appForegroundVisible = false),
            ),
        )
    }

    /**
     * The whole request cross-product, as a property: **no decision ever opens capture while the app
     * is not foreground-visible.** That is ARCHITECTURE §6.4 step 6 stated as an invariant rather
     * than as a sequence, and it is the one a future refactor is most likely to break.
     */
    @Test
    fun `no decision in the whole cross-product opens capture from the background`() {
        val everyRequest = crossProduct()
        assertEquals(CROSS_PRODUCT_SIZE, everyRequest.size, "the whole cross-product must be visited")

        val openingCapture =
            everyRequest.filter { request ->
                (RideStartPolicy.decide(request) as? RideStartDecision.Allowed)?.openCapture == true
            }
        assertTrue(openingCapture.isNotEmpty(), "some request must open capture, or this proves nothing")
        for (request in openingCapture) {
            assertTrue(request.appForegroundVisible, "opened capture from the background: $request")
            assertTrue(request.micPermissionGranted, "opened capture without a microphone permission: $request")
            assertTrue(request.audioEndpointPresent, "opened capture with no audio endpoint: $request")
            assertTrue(request.sessionAuthenticated, "opened capture without an authenticated peer: $request")
            assertTrue(request.intercomEnabled, "opened capture with the intercom disabled: $request")
        }
    }

    /** All 2^7 requests, flattened so the assertions above read as a property rather than a nest. */
    private fun crossProduct(): List<RideStartRequest> =
        BOOLEANS.flatMap { visible ->
            BOOLEANS.flatMap { mic ->
                BOOLEANS.flatMap { notifications ->
                    BOOLEANS.flatMap { authenticated ->
                        BOOLEANS.flatMap { endpoint ->
                            BOOLEANS.flatMap { alreadyOpen ->
                                BOOLEANS.map { enabled ->
                                    request(visible, mic, notifications, authenticated, endpoint, alreadyOpen, enabled)
                                }
                            }
                        }
                    }
                }
            }
        }

    private companion object {
        val BOOLEANS = listOf(true, false)

        /** Seven independent booleans. */
        const val CROSS_PRODUCT_SIZE = 128
    }
}
