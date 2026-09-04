package com.ridelink.core.audiopolicy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exhausts [AudioSessionLifecycle] and [RouteTransitionTracker].
 *
 * These exist because neither `AVAudioSession` nor `AudioManager` can be executed off-device
 * (docs/STATUS.md §4 problem 23), so every *decision* either of them would make lives here where a
 * laptop can reach it. The mirror is `RideLinkCoreTests.AudioSessionLifecycleTests`.
 *
 * **None of this is evidence about a device.** It proves the reducer; whether `AVAudioSession`
 * actually delivers `.categoryChange` when RideLink switches configuration, and how long the switch
 * takes, is TEST_PLAN IA-02/IA-03 and remains pending.
 */
class AudioSessionLifecycleTest {
    // --- the route-transition lifecycle -------------------------------------------------------

    /**
     * PROTOCOL §4.4's `stable -> transitioning -> stable`, and the measurement that comes out of it.
     * The settle is driven by the platform's own callback, never by elapsed time — the timeout below
     * is tested separately and is failure protection only.
     */
    @Test
    fun `opening publishes transitioning and the platform's confirmation publishes stable`() {
        var state = AudioSessionState()
        // `OpenRequested` begins the transition; `Opened` (the platform call it precedes actually
        // succeeding) only confirms the session — this phase's final hardening pass, Issue 1.
        state = AudioSessionLifecycle.reduce(state, AudioSessionEvent.OpenRequested(0, atMonoUs = 1_000_000)).state
        val opened = AudioSessionLifecycle.reduce(state, AudioSessionEvent.Opened(0, atMonoUs = 1_000_000))
        state = opened.state
        assertTrue(state.open)
        assertTrue(state.transition.transitioning, "a configuration switch is a transition, not an instant")
        assertEquals(
            listOf(AudioSessionAction.PublishSnapshot(RouteState.TRANSITIONING)),
            opened.actions,
        )

        val settled =
            AudioSessionLifecycle.reduce(
                state,
                AudioSessionEvent.RouteChanged(0, AudioRouteChangeReason.CATEGORY_CHANGE, atMonoUs = 2_500_000, settles = true),
            )
        state = settled.state
        assertFalse(state.transition.transitioning)
        assertEquals(listOf(AudioSessionAction.PublishSnapshot(RouteState.STABLE)), settled.actions)
        assertEquals(1_500_000L, state.transition.lastDurationUs, "the measured duration, in microseconds")
        assertEquals(1_500.0, state.transition.lastDurationMs, "and in milliseconds, for the diagnostics screen")
        assertEquals(0, state.transition.timedOutCount, "the platform settled it, not a timer")
    }

    /** A route change we did not ask for starts a transition of its own (TEST_PLAN IA-05). */
    @Test
    fun `an unsolicited route change begins a transition rather than settling one`() {
        val state = AudioSessionState(open = true)
        val outcome =
            AudioSessionLifecycle.reduce(
                state,
                AudioSessionEvent.RouteChanged(
                    0,
                    AudioRouteChangeReason.OLD_DEVICE_UNAVAILABLE,
                    atMonoUs = 5_000,
                    settles = false,
                ),
            )
        assertTrue(outcome.state.transition.transitioning)
        assertEquals(listOf(AudioSessionAction.PublishSnapshot(RouteState.TRANSITIONING)), outcome.actions)
    }

    /** A burst of callbacks measures one transition rather than resetting the clock each time. */
    @Test
    fun `a burst of route callbacks keeps the original start instant`() {
        var transition = RouteTransitionTracker.begin(RouteTransitionState(), nowMonoUs = 1_000)
        transition = RouteTransitionTracker.begin(transition, nowMonoUs = 2_000)
        transition = RouteTransitionTracker.begin(transition, nowMonoUs = 3_000)
        assertEquals(1_000L, transition.startedAtMonoUs, "the first instant is the one that counts")
        transition = RouteTransitionTracker.settle(transition, nowMonoUs = 4_000)
        assertEquals(3_000L, transition.lastDurationUs, "so the whole transition is measured, not its tail")
    }

    // --- OpenRequested / CloseRequested / OpenAborted (this phase's final hardening pass, Issue 1) --

    /**
     * The whole point of [AudioSessionEvent.OpenRequested]: a transition begun by it is already
     * live when a settling [AudioSessionEvent.RouteChanged] arrives, so the confirmation is not
     * silently dropped by [RouteTransitionTracker.settle]'s own guard — the exact failure mode a
     * platform call able to confirm synchronously (or very shortly after returning) produced before
     * this event existed.
     */
    @Test
    fun `a route change settling between OpenRequested and Opened is not lost`() {
        var state = AudioSessionState()
        val requested = AudioSessionLifecycle.reduce(state, AudioSessionEvent.OpenRequested(0, atMonoUs = 1_000))
        state = requested.state
        assertTrue(state.transition.transitioning, "the transition must already be live")
        assertFalse(state.open, "OpenRequested alone must not claim the session")

        // The platform's confirmation arrives before `Opened` ever runs.
        val settled =
            AudioSessionLifecycle.reduce(
                state,
                AudioSessionEvent.RouteChanged(0, AudioRouteChangeReason.CATEGORY_CHANGE, atMonoUs = 2_000, settles = true),
            )
        state = settled.state
        assertFalse(state.transition.transitioning, "the confirmation must not be silently dropped")
        assertEquals(1_000L, state.transition.lastDurationUs)

        // `Opened` still runs afterward (the platform call itself succeeded) and must not resurrect
        // a transition that has already settled.
        val opened = AudioSessionLifecycle.reduce(state, AudioSessionEvent.Opened(0, atMonoUs = 2_500))
        assertTrue(opened.state.open)
        assertFalse(opened.state.transition.transitioning, "Opened must not restart an already-settled transition")
    }

    @Test
    fun `OpenRequested and Opened together begin the same transition once, not twice`() {
        var state = AudioSessionState()
        state = AudioSessionLifecycle.reduce(state, AudioSessionEvent.OpenRequested(0, atMonoUs = 1_000)).state
        val opened = AudioSessionLifecycle.reduce(state, AudioSessionEvent.Opened(0, atMonoUs = 9_000))
        assertEquals(1_000L, opened.state.transition.startedAtMonoUs, "the first instant is the one that counts")
    }

    /**
     * The platform call [AudioSessionEvent.OpenRequested] began refused before it could complete.
     * The transition settles right away — never left latched for the failure-protection timeout to
     * notice — and the session was never actually handed to the platform.
     */
    @Test
    fun `OpenAborted settles the transition immediately and leaves the session closed`() {
        var state = AudioSessionState()
        state = AudioSessionLifecycle.reduce(state, AudioSessionEvent.OpenRequested(0, atMonoUs = 1_000)).state
        val aborted =
            AudioSessionLifecycle.reduce(state, AudioSessionEvent.OpenAborted(0, atMonoUs = 1_500, VoiceFailure.ROUTE_SELECTION_FAILED))
        assertFalse(aborted.state.open, "the platform never actually took the session")
        assertFalse(aborted.state.transition.transitioning, "settled immediately, not left for the timeout")
        assertEquals(VoiceFailure.ROUTE_SELECTION_FAILED, aborted.state.lastFailure)
        assertEquals(0, aborted.state.transition.timedOutCount, "an abort is not a timeout")
        assertTrue(aborted.actions.contains(AudioSessionAction.ReportFailure(VoiceFailure.ROUTE_SELECTION_FAILED)))
        assertTrue(aborted.actions.contains(AudioSessionAction.PublishSnapshot(RouteState.STABLE)))
    }

    /** Mirrors the open-side proof for the closing direction. */
    @Test
    fun `CloseRequested begins a transition without yet claiming the session closed`() {
        val state = AudioSessionState(open = true)
        val outcome = AudioSessionLifecycle.reduce(state, AudioSessionEvent.CloseRequested(0, atMonoUs = 500))
        assertTrue(outcome.state.transition.transitioning)
        assertTrue(outcome.state.open, "open stays true until Closed actually confirms it")
    }

    @Test
    fun `a route change settling between CloseRequested and Closed is not lost`() {
        var state = AudioSessionState(open = true)
        state = AudioSessionLifecycle.reduce(state, AudioSessionEvent.CloseRequested(0, atMonoUs = 1_000)).state
        val settled =
            AudioSessionLifecycle.reduce(
                state,
                AudioSessionEvent.RouteChanged(0, AudioRouteChangeReason.CATEGORY_CHANGE, atMonoUs = 1_800, settles = true),
            )
        assertFalse(settled.state.transition.transitioning, "the restoring confirmation must not be dropped")
        val closed = AudioSessionLifecycle.reduce(settled.state, AudioSessionEvent.Closed(0, atMonoUs = 1_900))
        assertFalse(closed.state.open)
        assertFalse(closed.state.transition.transitioning, "Closed must not restart an already-settled transition")
    }

    /**
     * **A timeout is failure protection, never the definition of success** (this phase's brief §15).
     * It settles a transition the platform never confirmed, and it is counted so the diagnostics can
     * say the number came from a timer rather than from the platform.
     */
    @Test
    fun `a timeout settles a transition the platform never confirmed, and says so`() {
        var state = AudioSessionLifecycle.reduce(AudioSessionState(), AudioSessionEvent.OpenRequested(0, atMonoUs = 0)).state
        state = AudioSessionLifecycle.reduce(state, AudioSessionEvent.Opened(0, atMonoUs = 0)).state
        val early =
            AudioSessionLifecycle.reduce(
                state,
                AudioSessionEvent.TransitionTimeoutCheck(0, atMonoUs = RouteTransitionTracker.DEFAULT_TIMEOUT_US - 1),
            )
        assertEquals(state, early.state, "before the window nothing changes")
        assertTrue(early.actions.isEmpty(), "and nothing is published")

        val late =
            AudioSessionLifecycle.reduce(
                state,
                AudioSessionEvent.TransitionTimeoutCheck(0, atMonoUs = RouteTransitionTracker.DEFAULT_TIMEOUT_US),
            )
        state = late.state
        assertFalse(state.transition.transitioning, "at the window it is settled")
        assertEquals(1, state.transition.timedOutCount, "and counted as a timeout, not a measurement")
        assertEquals(listOf(AudioSessionAction.PublishSnapshot(RouteState.STABLE)), late.actions)
    }

    @Test
    fun `a timeout check with no transition in progress does nothing`() {
        val state = AudioSessionState(open = true)
        val outcome =
            AudioSessionLifecycle.reduce(state, AudioSessionEvent.TransitionTimeoutCheck(0, atMonoUs = 9_999_999))
        assertEquals(state, outcome.state)
        assertTrue(outcome.actions.isEmpty())
    }

    // --- interruptions (TEST_PLAN IA-06) -------------------------------------------------------

    @Test
    fun `an interruption beginning marks the route interrupted without rebuilding anything`() {
        val state = AudioSessionState(open = true)
        val outcome = AudioSessionLifecycle.reduce(state, AudioSessionEvent.InterruptionBegan(0, atMonoUs = 1))
        assertTrue(outcome.state.interrupted)
        assertTrue(outcome.state.open, "an interruption does not close the session")
        assertEquals(VoiceFailure.INTERRUPTED, outcome.state.lastFailure)
        assertTrue(
            outcome.actions.none { it == AudioSessionAction.RebuildAfterReset || it == AudioSessionAction.Reactivate },
            "an interruption must not create a duplicate session",
        )
    }

    @Test
    fun `an interruption ending with shouldResume reactivates`() {
        val state = AudioSessionState(open = true, interrupted = true)
        val outcome =
            AudioSessionLifecycle.reduce(state, AudioSessionEvent.InterruptionEnded(0, shouldResume = true, atMonoUs = 2))
        assertFalse(outcome.state.interrupted)
        assertFalse(outcome.state.awaitingResume)
        assertNull(outcome.state.lastFailure)
        assertTrue(outcome.actions.contains(AudioSessionAction.Reactivate))
    }

    /**
     * ARCHITECTURE §6.2 / IA-06: an interruption that ends **without** `shouldResume` leaves the
     * session inactive. Reactivating anyway is how an app ends up fighting the platform for a route
     * the user gave to something else.
     */
    @Test
    fun `an interruption ending without shouldResume stays interrupted and does not reactivate`() {
        val state = AudioSessionState(open = true, interrupted = true)
        val outcome =
            AudioSessionLifecycle.reduce(state, AudioSessionEvent.InterruptionEnded(0, shouldResume = false, atMonoUs = 2))
        assertTrue(outcome.state.interrupted, "still interrupted")
        assertTrue(outcome.state.awaitingResume, "and recorded as waiting for the user")
        assertFalse(outcome.actions.contains(AudioSessionAction.Reactivate), "must not reactivate")
    }

    // --- media-services reset (TEST_PLAN IA-07) ------------------------------------------------

    /**
     * The third notification, and the one most implementations forget. Every audio object this
     * process holds is invalid, so the generation moves — which is what makes a callback already in
     * flight from the old one inert by comparison rather than by luck.
     */
    @Test
    fun `a media services reset moves the generation and asks for a rebuild`() {
        val state = AudioSessionState(open = true, interrupted = true, generation = 3)
        val outcome = AudioSessionLifecycle.reduce(state, AudioSessionEvent.MediaServicesReset(3, atMonoUs = 7_000))
        assertEquals(4, outcome.state.generation, "the generation must move")
        assertFalse(outcome.state.open, "nothing this process holds is valid any more")
        assertFalse(outcome.state.interrupted, "and the old interruption state is not carried over")
        assertEquals(VoiceFailure.MEDIA_SERVICES_RESET, outcome.state.lastFailure)
        assertTrue(outcome.actions.contains(AudioSessionAction.RebuildAfterReset))
        assertEquals(
            7_000L,
            outcome.state.transition.startedAtMonoUs,
            "timed from when the app learned of the reset — the only instant it observed",
        )
    }

    // --- the strict generation guard (this phase's brief §37) ----------------------------------

    /**
     * ADR-020 Amendment A2's rule, applied to the audio session: a callback from any generation other
     * than the current one is **inert**, including a real one that used to be current.
     */
    @Test
    fun `a callback from a superseded generation cannot affect the current one`() {
        // Generation A is live and mid-transition.
        var state = AudioSessionLifecycle.reduce(AudioSessionState(), AudioSessionEvent.Opened(0, atMonoUs = 1_000)).state
        // A reset installs generation B.
        state = AudioSessionLifecycle.reduce(state, AudioSessionEvent.MediaServicesReset(0, atMonoUs = 2_000)).state
        val afterReset = state
        assertEquals(1, state.generation)

        val staleEvents =
            listOf(
                AudioSessionEvent.RouteChanged(0, AudioRouteChangeReason.CATEGORY_CHANGE, atMonoUs = 3_000, settles = true),
                AudioSessionEvent.InterruptionBegan(0, atMonoUs = 3_000),
                AudioSessionEvent.InterruptionEnded(0, shouldResume = true, atMonoUs = 3_000),
                AudioSessionEvent.Closed(0, atMonoUs = 3_000),
                AudioSessionEvent.MediaServicesReset(0, atMonoUs = 3_000),
                AudioSessionEvent.Failed(0, VoiceFailure.CAPTURE_START_FAILED),
                AudioSessionEvent.TransitionTimeoutCheck(0, atMonoUs = 99_000_000),
                AudioSessionEvent.OpenRequested(0, atMonoUs = 3_000),
                AudioSessionEvent.CloseRequested(0, atMonoUs = 3_000),
                AudioSessionEvent.OpenAborted(0, atMonoUs = 3_000, VoiceFailure.ROUTE_SELECTION_FAILED),
            )
        for (event in staleEvents) {
            val outcome = AudioSessionLifecycle.reduce(afterReset, event)
            assertEquals(afterReset, outcome.state, "$event from generation 0 changed generation 1's state")
            assertTrue(outcome.actions.isEmpty(), "$event from generation 0 produced actions")
        }

        // And generation B's own callbacks still work.
        val live =
            AudioSessionLifecycle.reduce(
                afterReset,
                AudioSessionEvent.RouteChanged(1, AudioRouteChangeReason.CATEGORY_CHANGE, atMonoUs = 3_000, settles = true),
            )
        assertNotEquals(afterReset, live.state, "the current generation must still be able to act")
        assertFalse(live.state.transition.transitioning)
    }

    /** A stale callback must not be able to overwrite the *route* the new generation established. */
    @Test
    fun `a stale route change cannot re-open a transition the new generation settled`() {
        var state = AudioSessionState(generation = 5, open = true)
        state = AudioSessionLifecycle.reduce(state, AudioSessionEvent.Opened(5, atMonoUs = 1_000)).state
        state =
            AudioSessionLifecycle
                .reduce(
                    state,
                    AudioSessionEvent.RouteChanged(5, AudioRouteChangeReason.CATEGORY_CHANGE, atMonoUs = 2_000, settles = true),
                ).state
        assertFalse(state.transition.transitioning)

        val stale =
            AudioSessionLifecycle.reduce(
                state,
                AudioSessionEvent.RouteChanged(4, AudioRouteChangeReason.NEW_DEVICE_AVAILABLE, atMonoUs = 3_000, settles = false),
            )
        assertEquals(state, stale.state, "generation 4's callback must not disturb generation 5")
    }

    // --- failures -----------------------------------------------------------------------------

    /** Each failure is reported by name, never mapped to one generic bucket (this brief's §41). */
    @Test
    fun `every failure is reported distinctly and none closes the session`() {
        for (failure in VoiceFailure.entries) {
            val state = AudioSessionState(open = true)
            val outcome = AudioSessionLifecycle.reduce(state, AudioSessionEvent.Failed(0, failure))
            assertEquals(failure, outcome.state.lastFailure, "$failure must be recorded by name")
            assertTrue(
                outcome.actions.contains(AudioSessionAction.ReportFailure(failure)),
                "$failure must be reported",
            )
            assertTrue(outcome.state.open, "$failure must not close the audio session by itself")
        }
    }

    @Test
    fun `closing publishes a transition and clears the interruption state`() {
        var state = AudioSessionState(open = true, interrupted = true, awaitingResume = true)
        // `CloseRequested` begins the transition; `Closed` only confirms it (this phase's final
        // hardening pass, Issue 1).
        state = AudioSessionLifecycle.reduce(state, AudioSessionEvent.CloseRequested(0, atMonoUs = 10)).state
        val outcome = AudioSessionLifecycle.reduce(state, AudioSessionEvent.Closed(0, atMonoUs = 10))
        assertFalse(outcome.state.open)
        assertFalse(outcome.state.interrupted)
        assertFalse(outcome.state.awaitingResume)
        assertTrue(outcome.state.transition.transitioning, "returning to the music-only configuration is audible too")
        assertEquals(listOf(AudioSessionAction.PublishSnapshot(RouteState.TRANSITIONING)), outcome.actions)
    }

    /** No lifecycle event may ever produce a capture operation — that is the owner's job, not this one's. */
    @Test
    fun `no lifecycle action is a capture operation`() {
        val permitted =
            setOf(
                AudioSessionAction.Reactivate,
                AudioSessionAction.RebuildAfterReset,
            )
        val events =
            listOf(
                AudioSessionEvent.Opened(0, 1),
                AudioSessionEvent.Closed(0, 1),
                AudioSessionEvent.RouteChanged(0, AudioRouteChangeReason.OVERRIDE, 1, settles = true),
                AudioSessionEvent.RouteChanged(0, AudioRouteChangeReason.OVERRIDE, 1, settles = false),
                AudioSessionEvent.InterruptionBegan(0, 1),
                AudioSessionEvent.InterruptionEnded(0, shouldResume = true, atMonoUs = 1),
                AudioSessionEvent.InterruptionEnded(0, shouldResume = false, atMonoUs = 1),
                AudioSessionEvent.MediaServicesReset(0, 1),
                AudioSessionEvent.Failed(0, VoiceFailure.WEBRTC_FAILED),
            )
        for (event in events) {
            val outcome = AudioSessionLifecycle.reduce(AudioSessionState(open = true), event)
            for (action in outcome.actions) {
                assertTrue(
                    action is AudioSessionAction.PublishSnapshot ||
                        action is AudioSessionAction.ReportFailure ||
                        action in permitted,
                    "$event produced $action, which is outside this reducer's vocabulary",
                )
            }
        }
    }
}
