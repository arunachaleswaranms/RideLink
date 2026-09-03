import XCTest

@testable import RideLinkCore

/// Exhausts `AudioSessionLifecycle` and `RouteTransitionTracker`.
///
/// These exist because neither `AVAudioSession` nor `AudioManager` can be executed off-device
/// (docs/STATUS.md §4 problem 23), so every *decision* either of them would make lives here where a
/// laptop can reach it. The mirror is `com.ridelink.core.audiopolicy.AudioSessionLifecycleTest`.
///
/// **None of this is evidence about a device.** It proves the reducer; whether `AVAudioSession`
/// actually delivers `.categoryChange` when RideLink switches configuration, and how long the switch
/// takes, is TEST_PLAN IA-02/IA-03 and remains pending.
final class AudioSessionLifecycleTests: XCTestCase {
    // MARK: - the route-transition lifecycle

    /// PROTOCOL §4.4's `stable -> transitioning -> stable`, and the measurement that comes out of it.
    /// The settle is driven by the platform's own callback, never by elapsed time — the timeout below is
    /// tested separately and is failure protection only.
    func testOpeningPublishesTransitioningAndThePlatformsConfirmationPublishesStable() {
        var state = AudioSessionState()
        let opened = AudioSessionLifecycle.reduce(state: state, event: .opened(generation: 0, atMonoUs: 1_000_000))
        state = opened.state
        XCTAssertTrue(state.open)
        XCTAssertTrue(state.transition.transitioning, "a configuration switch is a transition, not an instant")
        XCTAssertEqual([.publishSnapshot(routeState: .transitioning)], opened.actions)

        let settled = AudioSessionLifecycle.reduce(
            state: state,
            event: .routeChanged(generation: 0, reason: .categoryChange, atMonoUs: 2_500_000, settles: true)
        )
        state = settled.state
        XCTAssertFalse(state.transition.transitioning)
        XCTAssertEqual([.publishSnapshot(routeState: .stable)], settled.actions)
        XCTAssertEqual(1_500_000, state.transition.lastDurationUs, "the measured duration, in microseconds")
        XCTAssertEqual(1_500.0, state.transition.lastDurationMs, "and in milliseconds, for the diagnostics screen")
        XCTAssertEqual(0, state.transition.timedOutCount, "the platform settled it, not a timer")
    }

    /// A route change we did not ask for starts a transition of its own (TEST_PLAN IA-05).
    func testAnUnsolicitedRouteChangeBeginsATransitionRatherThanSettlingOne() {
        let state = AudioSessionState(open: true)
        let outcome = AudioSessionLifecycle.reduce(
            state: state,
            event: .routeChanged(generation: 0, reason: .oldDeviceUnavailable, atMonoUs: 5_000, settles: false)
        )
        XCTAssertTrue(outcome.state.transition.transitioning)
        XCTAssertEqual([.publishSnapshot(routeState: .transitioning)], outcome.actions)
    }

    /// A burst of callbacks measures one transition rather than resetting the clock each time.
    func testABurstOfRouteCallbacksKeepsTheOriginalStartInstant() {
        var transition = RouteTransitionTracker.begin(RouteTransitionState(), nowMonoUs: 1_000)
        transition = RouteTransitionTracker.begin(transition, nowMonoUs: 2_000)
        transition = RouteTransitionTracker.begin(transition, nowMonoUs: 3_000)
        XCTAssertEqual(1_000, transition.startedAtMonoUs, "the first instant is the one that counts")
        transition = RouteTransitionTracker.settle(transition, nowMonoUs: 4_000)
        XCTAssertEqual(3_000, transition.lastDurationUs, "so the whole transition is measured, not its tail")
    }

    /// **A timeout is failure protection, never the definition of success** (this phase's brief §15). It
    /// settles a transition the platform never confirmed, and it is counted so the diagnostics can say
    /// the number came from a timer rather than from the platform.
    func testATimeoutSettlesATransitionThePlatformNeverConfirmedAndSaysSo() {
        var state = AudioSessionLifecycle.reduce(
            state: AudioSessionState(),
            event: .opened(generation: 0, atMonoUs: 0)
        ).state
        let early = AudioSessionLifecycle.reduce(
            state: state,
            event: .transitionTimeoutCheck(
                generation: 0,
                atMonoUs: RouteTransitionTracker.defaultTimeoutUs - 1,
                timeoutUs: RouteTransitionTracker.defaultTimeoutUs
            )
        )
        XCTAssertEqual(state, early.state, "before the window nothing changes")
        XCTAssertTrue(early.actions.isEmpty, "and nothing is published")

        let late = AudioSessionLifecycle.reduce(
            state: state,
            event: .transitionTimeoutCheck(
                generation: 0,
                atMonoUs: RouteTransitionTracker.defaultTimeoutUs,
                timeoutUs: RouteTransitionTracker.defaultTimeoutUs
            )
        )
        state = late.state
        XCTAssertFalse(state.transition.transitioning, "at the window it is settled")
        XCTAssertEqual(1, state.transition.timedOutCount, "and counted as a timeout, not a measurement")
        XCTAssertEqual([.publishSnapshot(routeState: .stable)], late.actions)
    }

    func testATimeoutCheckWithNoTransitionInProgressDoesNothing() {
        let state = AudioSessionState(open: true)
        let outcome = AudioSessionLifecycle.reduce(
            state: state,
            event: .transitionTimeoutCheck(
                generation: 0,
                atMonoUs: 9_999_999,
                timeoutUs: RouteTransitionTracker.defaultTimeoutUs
            )
        )
        XCTAssertEqual(state, outcome.state)
        XCTAssertTrue(outcome.actions.isEmpty)
    }

    // MARK: - interruptions (TEST_PLAN IA-06)

    func testAnInterruptionBeginningMarksTheRouteInterruptedWithoutRebuildingAnything() {
        let state = AudioSessionState(open: true)
        let outcome = AudioSessionLifecycle.reduce(state: state, event: .interruptionBegan(generation: 0, atMonoUs: 1))
        XCTAssertTrue(outcome.state.interrupted)
        XCTAssertTrue(outcome.state.open, "an interruption does not close the session")
        XCTAssertEqual(.interrupted, outcome.state.lastFailure)
        XCTAssertFalse(
            outcome.actions.contains(.rebuildAfterReset) || outcome.actions.contains(.reactivate),
            "an interruption must not create a duplicate session"
        )
    }

    func testAnInterruptionEndingWithShouldResumeReactivates() {
        let state = AudioSessionState(open: true, interrupted: true)
        let outcome = AudioSessionLifecycle.reduce(
            state: state,
            event: .interruptionEnded(generation: 0, shouldResume: true, atMonoUs: 2)
        )
        XCTAssertFalse(outcome.state.interrupted)
        XCTAssertFalse(outcome.state.awaitingResume)
        XCTAssertNil(outcome.state.lastFailure)
        XCTAssertTrue(outcome.actions.contains(.reactivate))
    }

    /// ARCHITECTURE §6.2 / IA-06: an interruption that ends **without** `shouldResume` leaves the session
    /// inactive. Reactivating anyway is how an app ends up fighting the platform for a route the user
    /// gave to something else.
    func testAnInterruptionEndingWithoutShouldResumeStaysInterruptedAndDoesNotReactivate() {
        let state = AudioSessionState(open: true, interrupted: true)
        let outcome = AudioSessionLifecycle.reduce(
            state: state,
            event: .interruptionEnded(generation: 0, shouldResume: false, atMonoUs: 2)
        )
        XCTAssertTrue(outcome.state.interrupted, "still interrupted")
        XCTAssertTrue(outcome.state.awaitingResume, "and recorded as waiting for the user")
        XCTAssertFalse(outcome.actions.contains(.reactivate), "must not reactivate")
    }

    // MARK: - media-services reset (TEST_PLAN IA-07)

    /// The third notification, and the one most implementations forget. Every audio object this process
    /// holds is invalid, so the generation moves — which is what makes a callback already in flight from
    /// the old one inert by comparison rather than by luck.
    func testAMediaServicesResetMovesTheGenerationAndAsksForARebuild() {
        let state = AudioSessionState(open: true, generation: 3, interrupted: true)
        let outcome = AudioSessionLifecycle.reduce(
            state: state,
            event: .mediaServicesReset(generation: 3, atMonoUs: 7_000)
        )
        XCTAssertEqual(4, outcome.state.generation, "the generation must move")
        XCTAssertFalse(outcome.state.open, "nothing this process holds is valid any more")
        XCTAssertFalse(outcome.state.interrupted, "and the old interruption state is not carried over")
        XCTAssertEqual(.mediaServicesReset, outcome.state.lastFailure)
        XCTAssertTrue(outcome.actions.contains(.rebuildAfterReset))
        XCTAssertEqual(
            7_000,
            outcome.state.transition.startedAtMonoUs,
            "timed from when the app learned of the reset — the only instant it observed"
        )
    }

    // MARK: - the strict generation guard (this phase's brief §37)

    /// ADR-020 Amendment A2's rule, applied to the audio session: a callback from any generation other
    /// than the current one is **inert**, including a real one that used to be current.
    func testACallbackFromASupersededGenerationCannotAffectTheCurrentOne() {
        // Generation A is live and mid-transition.
        var state = AudioSessionLifecycle.reduce(
            state: AudioSessionState(),
            event: .opened(generation: 0, atMonoUs: 1_000)
        ).state
        // A reset installs generation B.
        state = AudioSessionLifecycle.reduce(
            state: state,
            event: .mediaServicesReset(generation: 0, atMonoUs: 2_000)
        ).state
        let afterReset = state
        XCTAssertEqual(1, state.generation)

        let staleEvents: [AudioSessionEvent] = [
            .routeChanged(generation: 0, reason: .categoryChange, atMonoUs: 3_000, settles: true),
            .interruptionBegan(generation: 0, atMonoUs: 3_000),
            .interruptionEnded(generation: 0, shouldResume: true, atMonoUs: 3_000),
            .closed(generation: 0, atMonoUs: 3_000),
            .mediaServicesReset(generation: 0, atMonoUs: 3_000),
            .failed(generation: 0, failure: .captureStartFailed),
            .transitionTimeoutCheck(generation: 0, atMonoUs: 99_000_000, timeoutUs: RouteTransitionTracker.defaultTimeoutUs),
        ]
        for event in staleEvents {
            let outcome = AudioSessionLifecycle.reduce(state: afterReset, event: event)
            XCTAssertEqual(afterReset, outcome.state, "\(event) from generation 0 changed generation 1's state")
            XCTAssertTrue(outcome.actions.isEmpty, "\(event) from generation 0 produced actions")
        }

        // And generation B's own callbacks still work.
        let live = AudioSessionLifecycle.reduce(
            state: afterReset,
            event: .routeChanged(generation: 1, reason: .categoryChange, atMonoUs: 3_000, settles: true)
        )
        XCTAssertNotEqual(afterReset, live.state, "the current generation must still be able to act")
        XCTAssertFalse(live.state.transition.transitioning)
    }

    /// A stale callback must not be able to overwrite the *route* the new generation established.
    func testAStaleRouteChangeCannotReOpenATransitionTheNewGenerationSettled() {
        var state = AudioSessionState(open: true, generation: 5)
        state = AudioSessionLifecycle.reduce(state: state, event: .opened(generation: 5, atMonoUs: 1_000)).state
        state = AudioSessionLifecycle.reduce(
            state: state,
            event: .routeChanged(generation: 5, reason: .categoryChange, atMonoUs: 2_000, settles: true)
        ).state
        XCTAssertFalse(state.transition.transitioning)

        let stale = AudioSessionLifecycle.reduce(
            state: state,
            event: .routeChanged(generation: 4, reason: .newDeviceAvailable, atMonoUs: 3_000, settles: false)
        )
        XCTAssertEqual(state, stale.state, "generation 4's callback must not disturb generation 5")
    }

    // MARK: - failures

    /// Each failure is reported by name, never mapped to one generic bucket (this brief's §41).
    func testEveryFailureIsReportedDistinctlyAndNoneClosesTheSession() {
        for failure in VoiceFailure.allCases {
            let state = AudioSessionState(open: true)
            let outcome = AudioSessionLifecycle.reduce(state: state, event: .failed(generation: 0, failure: failure))
            XCTAssertEqual(failure, outcome.state.lastFailure, "\(failure) must be recorded by name")
            XCTAssertTrue(outcome.actions.contains(.reportFailure(failure)), "\(failure) must be reported")
            XCTAssertTrue(outcome.state.open, "\(failure) must not close the audio session by itself")
        }
    }

    func testClosingPublishesATransitionAndClearsTheInterruptionState() {
        let state = AudioSessionState(open: true, interrupted: true, awaitingResume: true)
        let outcome = AudioSessionLifecycle.reduce(state: state, event: .closed(generation: 0, atMonoUs: 10))
        XCTAssertFalse(outcome.state.open)
        XCTAssertFalse(outcome.state.interrupted)
        XCTAssertFalse(outcome.state.awaitingResume)
        XCTAssertTrue(
            outcome.state.transition.transitioning,
            "returning to the music-only configuration is audible too"
        )
        XCTAssertEqual([.publishSnapshot(routeState: .transitioning)], outcome.actions)
    }

    /// **No lifecycle event may ever produce a capture operation** — that is the owner's job, not this
    /// one's, and `AudioSessionAction` has no such case, so the compiler enforces it. What this test adds
    /// on top of that is the positive half: every one of these events publishes exactly one snapshot, so
    /// no state change can happen without `AUDIO_STATE` being told about it.
    func testEveryLifecycleEventPublishesExactlyOneSnapshot() {
        let events: [AudioSessionEvent] = [
            .opened(generation: 0, atMonoUs: 1),
            .closed(generation: 0, atMonoUs: 1),
            .routeChanged(generation: 0, reason: .override, atMonoUs: 1, settles: true),
            .routeChanged(generation: 0, reason: .override, atMonoUs: 1, settles: false),
            .interruptionBegan(generation: 0, atMonoUs: 1),
            .interruptionEnded(generation: 0, shouldResume: true, atMonoUs: 1),
            .interruptionEnded(generation: 0, shouldResume: false, atMonoUs: 1),
            .mediaServicesReset(generation: 0, atMonoUs: 1),
            .failed(generation: 0, failure: .webRtcFailed),
        ]
        for event in events {
            // `.opened` first, so a settling route change has a transition to settle.
            let base = AudioSessionLifecycle.reduce(
                state: AudioSessionState(),
                event: .opened(generation: 0, atMonoUs: 0)
            ).state
            let outcome = AudioSessionLifecycle.reduce(state: base, event: event)
            let publishes = outcome.actions.filter {
                if case .publishSnapshot = $0 { return true }
                return false
            }
            XCTAssertEqual(1, publishes.count, "\(event) must publish exactly one snapshot")
        }
    }
}
