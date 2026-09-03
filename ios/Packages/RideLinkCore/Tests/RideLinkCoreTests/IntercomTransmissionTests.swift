import XCTest

@testable import RideLinkCore

/// Properties of `IntercomTransmission` that are not row-shaped, so the shared vectors cannot carry
/// them. The mirror is `com.ridelink.core.audiopolicy.IntercomTransmissionTest`.
///
/// The one that matters most is `testPttPressedFiftyTimesNeverProducesACaptureOperation`: it is the
/// laptop half of TEST_PLAN A-10, which will later assert the same property against a real helmet
/// unit's recorded output.
final class IntercomTransmissionTests: XCTestCase {
    private let pressCount = 50
    private let voxHangoverMs: Int64 = 700
    private let microsPerMs: Int64 = 1_000

    private func open(_ policy: IntercomPolicy) -> TransmissionState {
        TransmissionState(policy: policy, captureOpen: true)
    }

    /// **The invariant this whole phase exists to protect.** 50 press/release cycles produce exactly
    /// 100 transmission flips and **zero** capture operations — which is structurally guaranteed here,
    /// because `IntercomAction` has no capture case at all. The platform-level half of the same
    /// invariant, counting real `open`/`close` calls on a fake audio session, is
    /// `VoiceControllerIntercomTests`; the hardware half is A-10.
    func testPttPressedFiftyTimesNeverProducesACaptureOperation() {
        var state = open(.modeC)
        var flips = 0
        for _ in 0..<pressCount {
            for held in [true, false] {
                let outcome = IntercomTransmission.reduce(state: state, input: .pttHeld(held))
                state = outcome.state
                for action in outcome.actions {
                    guard case .setTransmitting = action else {
                        return XCTFail("a PTT edge produced \(action), which is not a transmission change")
                    }
                    flips += 1
                }
            }
        }
        XCTAssertEqual(pressCount * 2, flips, "each press and each release must flip transmission exactly once")
        XCTAssertTrue(state.captureOpen, "capture must still be open after \(pressCount) presses")
        XCTAssertFalse(state.transmitting, "the last release must leave transmission off")
    }

    /// The two inputs that deliberately do **not** commute, pinned as a property rather than left as an
    /// assumption.
    ///
    /// A policy switch and a capture close both reset the gate's transient state, so applying either of
    /// them after a `.pttHeld` clears the press while applying it before does not. That asymmetry is the
    /// reason `IntercomCommandMailbox` drains kinds in a fixed order with those two first, and the
    /// reason arrival-order independence is proved there rather than claimed here.
    func testAPolicySwitchAndACaptureCloseDoNotCommuteWithAPttPress() {
        let start = TransmissionState(policy: .modeC, captureOpen: true)
        let resetting: [IntercomInput] = [.policySelected(.modeC), .captureOpen(false)]
        for reset in resetting {
            let pressThenReset = IntercomTransmission.reduce(
                state: IntercomTransmission.reduce(state: start, input: .pttHeld(true)).state,
                input: reset
            ).state
            let resetThenPress = IntercomTransmission.reduce(
                state: IntercomTransmission.reduce(state: start, input: reset).state,
                input: .pttHeld(true)
            ).state
            XCTAssertFalse(pressThenReset.pttHeld, "\(reset) must clear a held press applied before it")
            XCTAssertTrue(resetThenPress.pttHeld, "\(reset) must not clear a press applied after it")
        }
    }

    /// Mode A and Mode D are the full-duplex policies, and nothing gates them but mute or capture.
    func testFullDuplexTransmitsWithNoGateInputAtAll() {
        for policy in [IntercomPolicy.modeA, IntercomPolicy.modeD] {
            XCTAssertTrue(policy.fullDuplex, "\(policy.id) must be full duplex")
            let outcome = IntercomTransmission.reduce(
                state: TransmissionState(policy: policy),
                input: .captureOpen(true)
            )
            XCTAssertTrue(outcome.state.transmitting, "\(policy.id) must transmit as soon as capture opens")
            XCTAssertEqual([.setTransmitting(true)], outcome.actions, "\(policy.id) actions")
        }
    }

    /// PTT and VOX are fallbacks over the same live capture path, never a different transport.
    func testPttAndVoxAreNotFullDuplexButStillRequireAnOpenCapturePath() {
        for policy in [IntercomPolicy.modeB, IntercomPolicy.modeC] {
            XCTAssertFalse(policy.fullDuplex, "\(policy.id) is a gated policy")
            XCTAssertTrue(policy.intercomEnabled, "\(policy.id) still has an intercom")
            XCTAssertFalse(policy.micAlwaysOpen, "\(policy.id) gates transmission, not the device")
        }
    }

    /// `VOICE_STATE.mic_muted` means "transmitting silence" (PROTOCOL §7.4), not "device closed".
    func testTheWireMuteFlagIsTheNegationOfTransmitting() {
        var pressed = open(.modeC)
        pressed.pttHeld = true
        var muted = open(.modeA)
        muted.userMuted = true
        let states = [
            TransmissionState(policy: .modeC),
            open(.modeC),
            pressed,
            open(.modeA),
            muted,
            open(.modeE),
        ]
        for state in states {
            XCTAssertEqual(!state.transmitting, state.micMutedForWire, "wire mute for \(state)")
        }
    }

    /// The VOX hangover, driven only by monotonic microseconds the caller supplies. No clock is read
    /// anywhere in `RideLinkCore` (CLAUDE.md rules 5 and 9), which is exactly why this is deterministic.
    func testVoxHangoverClosesTheGateExactlyAtItsDeadlineAndNotBefore() {
        let hangoverUs = voxHangoverMs * microsPerMs
        var state = open(.modeB)
        state = IntercomTransmission.reduce(
            state: state,
            input: .speechLevel(levelDbfs: -10.0, atMonoUs: 1_000_000)
        ).state
        XCTAssertTrue(state.voxOpen, "a loud level opens the gate")
        XCTAssertEqual(1_000_000 + hangoverUs, state.voxHangoverUntilMonoUs, "the deadline is level + hangover")

        state = IntercomTransmission.reduce(state: state, input: .voxTick(atMonoUs: 1_000_000 + hangoverUs - 1)).state
        XCTAssertTrue(state.voxOpen, "one microsecond before the deadline the gate is still open")

        let outcome = IntercomTransmission.reduce(state: state, input: .voxTick(atMonoUs: 1_000_000 + hangoverUs))
        XCTAssertFalse(outcome.state.voxOpen, "at the deadline the gate closes")
        XCTAssertNil(outcome.state.voxHangoverUntilMonoUs, "and the deadline is cleared")
        XCTAssertEqual([.setTransmitting(false)], outcome.actions, "closing stops transmission")
    }

    /// A VOX level under a non-VOX gate changes nothing at all — not even the hangover bookkeeping.
    func testASpeechLevelIsInertUnderEveryGateButVox() {
        for policy in [IntercomPolicy.modeA, IntercomPolicy.modeC, IntercomPolicy.modeD, IntercomPolicy.modeE] {
            let before = open(policy)
            let outcome = IntercomTransmission.reduce(
                state: before,
                input: .speechLevel(levelDbfs: 0.0, atMonoUs: 5_000_000)
            )
            XCTAssertEqual(before, outcome.state, "\(policy.id) changed state on a speech level")
            XCTAssertTrue(outcome.actions.isEmpty, "\(policy.id) emitted actions on a speech level")
        }
    }

    /// This phase's brief §25: backgrounding while PTT is held must not leave transmission stuck on. The
    /// UI expresses that as the same absolute assignment a release does, which is why there is no
    /// separate input for it — and why the two cannot diverge.
    func testBackgroundingWhileHeldIsTheSameAssignmentAsReleasing() {
        var held = open(.modeC)
        held.pttHeld = true
        XCTAssertTrue(held.transmitting, "the precondition is a live transmission")
        let released = IntercomTransmission.reduce(state: held, input: .pttHeld(false))
        XCTAssertFalse(released.state.transmitting, "releasing stops transmission")
        XCTAssertEqual([.setTransmitting(false)], released.actions, "and says so once")
    }

    /// Mode E's `VOICE_STATE.mode` is `ptt` and its `AUDIO_STATE.intercom_mode` is `disabled` (ADR-021 §3).
    func testModeEReportsPttOnTheVoicePlaneAndDisabledOnTheAudioPlane() {
        XCTAssertEqual(VoiceMode.ptt, IntercomPolicy.modeE.voiceWireMode)
        XCTAssertEqual(IntercomMode.disabled, IntercomPolicy.modeE.intercomWireMode)
        XCTAssertFalse(IntercomPolicy.modeE.intercomEnabled)
    }

    /// A policy change announces itself on both planes only when the value each carries changed.
    func testSwitchingBetweenTwoPoliciesWithTheSameGateAnnouncesNothing() {
        let outcome = IntercomTransmission.reduce(state: open(.modeA), input: .policySelected(.modeD))
        for action in outcome.actions {
            switch action {
            case .announceVoiceMode, .publishAudioState:
                XCTFail("Modes A and D differ only in fields no wire field carries")
            case .setTransmitting:
                continue
            }
        }
    }
}
