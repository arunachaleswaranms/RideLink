package com.ridelink.core.audiopolicy

import com.ridelink.core.protocol.VoiceMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Properties of [IntercomTransmission] that are not row-shaped, so the shared vectors cannot carry
 * them. The mirror is `RideLinkCoreTests.IntercomTransmissionTests`.
 *
 * The one that matters most is [ptt pressed fifty times never produces a capture operation]: it is
 * the laptop half of TEST_PLAN A-10, which will later assert the same property against a real helmet
 * unit's recorded output.
 */
class IntercomTransmissionTest {
    private fun open(policy: IntercomPolicy) = TransmissionState(policy = policy, captureOpen = true)

    /**
     * **The invariant this whole phase exists to protect.** 50 press/release cycles produce exactly
     * 100 transmission flips and **zero** capture operations — which is structurally guaranteed here,
     * because [IntercomAction] has no capture case at all. The platform-level half of the same
     * invariant, counting real `open`/`close` calls on a fake audio session, is
     * `VoiceControllerIntercomTest`; the hardware half is A-10.
     */
    @Test
    fun `ptt pressed fifty times never produces a capture operation`() {
        var state = open(IntercomPolicy.MODE_C)
        var flips = 0
        repeat(PRESS_COUNT) {
            for (held in listOf(true, false)) {
                val outcome = IntercomTransmission.reduce(state, IntercomInput.PttHeld(held))
                state = outcome.state
                for (action in outcome.actions) {
                    assertTrue(
                        action is IntercomAction.SetTransmitting,
                        "a PTT edge produced $action, which is not a transmission change",
                    )
                    flips += 1
                }
            }
        }
        assertEquals(PRESS_COUNT * 2, flips, "each press and each release must flip transmission exactly once")
        assertTrue(state.captureOpen, "capture must still be open after $PRESS_COUNT presses")
        assertFalse(state.transmitting, "the last release must leave transmission off")
    }

    /**
     * The two inputs that deliberately do **not** commute, pinned as a property rather than left as
     * an assumption.
     *
     * A policy switch and a capture close both reset the gate's transient state, so applying either
     * of them after a [IntercomInput.PttHeld] clears the press while applying it before does not.
     * That asymmetry is the reason [IntercomCommandMailbox] drains kinds in a fixed order with those
     * two first, and the reason arrival-order independence is proved there rather than claimed here.
     */
    @Test
    fun `a policy switch and a capture close do not commute with a ptt press`() {
        val start = TransmissionState(policy = IntercomPolicy.MODE_C, captureOpen = true)
        val resetting =
            listOf<IntercomInput>(
                IntercomInput.PolicySelected(IntercomPolicy.MODE_C),
                IntercomInput.CaptureOpen(false),
            )
        for (reset in resetting) {
            val pressThenReset =
                IntercomTransmission
                    .reduce(IntercomTransmission.reduce(start, IntercomInput.PttHeld(true)).state, reset)
                    .state
            val resetThenPress =
                IntercomTransmission
                    .reduce(IntercomTransmission.reduce(start, reset).state, IntercomInput.PttHeld(true))
                    .state
            assertFalse(pressThenReset.pttHeld, "$reset must clear a held press applied before it")
            assertTrue(resetThenPress.pttHeld, "$reset must not clear a press applied after it")
        }
    }

    /** Mode A and Mode D are the full-duplex policies, and nothing gates them but mute or capture. */
    @Test
    fun `full duplex transmits with no gate input at all`() {
        for (policy in listOf(IntercomPolicy.MODE_A, IntercomPolicy.MODE_D)) {
            assertTrue(policy.fullDuplex, "${policy.id} must be full duplex")
            val outcome = IntercomTransmission.reduce(TransmissionState(policy = policy), IntercomInput.CaptureOpen(true))
            assertTrue(outcome.state.transmitting, "${policy.id} must transmit as soon as capture opens")
            assertEquals(listOf(IntercomAction.SetTransmitting(true)), outcome.actions, "${policy.id} actions")
        }
    }

    /** PTT and VOX are fallbacks over the same live capture path, never a different transport. */
    @Test
    fun `ptt and vox are not full duplex but still require an open capture path`() {
        for (policy in listOf(IntercomPolicy.MODE_B, IntercomPolicy.MODE_C)) {
            assertFalse(policy.fullDuplex, "${policy.id} is a gated policy")
            assertTrue(policy.intercomEnabled, "${policy.id} still has an intercom")
            assertFalse(policy.micAlwaysOpen, "${policy.id} gates transmission, not the device")
        }
    }

    /** `VOICE_STATE.mic_muted` means "transmitting silence" (PROTOCOL §7.4), not "device closed". */
    @Test
    fun `the wire mute flag is the negation of transmitting`() {
        val states =
            listOf(
                TransmissionState(policy = IntercomPolicy.MODE_C),
                open(IntercomPolicy.MODE_C),
                open(IntercomPolicy.MODE_C).copy(pttHeld = true),
                open(IntercomPolicy.MODE_A),
                open(IntercomPolicy.MODE_A).copy(userMuted = true),
                open(IntercomPolicy.MODE_E),
            )
        for (state in states) {
            assertEquals(!state.transmitting, state.micMutedForWire, "wire mute for $state")
        }
    }

    /**
     * The VOX hangover, driven only by monotonic microseconds the caller supplies. No clock is read
     * anywhere in `core` (CLAUDE.md rules 5 and 9), which is exactly why this is deterministic.
     */
    @Test
    fun `vox hangover closes the gate exactly at its deadline and not before`() {
        val hangoverUs = VOX_HANGOVER_MS * MICROS_PER_MS
        var state = open(IntercomPolicy.MODE_B)
        state = IntercomTransmission.reduce(state, IntercomInput.SpeechLevel(-10.0, 1_000_000)).state
        assertTrue(state.voxOpen, "a loud level opens the gate")
        assertEquals(1_000_000 + hangoverUs, state.voxHangoverUntilMonoUs, "the deadline is level + hangover")

        state = IntercomTransmission.reduce(state, IntercomInput.VoxTick(1_000_000 + hangoverUs - 1)).state
        assertTrue(state.voxOpen, "one microsecond before the deadline the gate is still open")

        val outcome = IntercomTransmission.reduce(state, IntercomInput.VoxTick(1_000_000 + hangoverUs))
        assertFalse(outcome.state.voxOpen, "at the deadline the gate closes")
        assertNull(outcome.state.voxHangoverUntilMonoUs, "and the deadline is cleared")
        assertEquals(listOf(IntercomAction.SetTransmitting(false)), outcome.actions, "closing stops transmission")
    }

    /** A VOX level under a non-VOX gate changes nothing at all — not even the hangover bookkeeping. */
    @Test
    fun `a speech level is inert under every gate but vox`() {
        for (policy in listOf(IntercomPolicy.MODE_A, IntercomPolicy.MODE_C, IntercomPolicy.MODE_D, IntercomPolicy.MODE_E)) {
            val before = open(policy)
            val outcome = IntercomTransmission.reduce(before, IntercomInput.SpeechLevel(0.0, 5_000_000))
            assertEquals(before, outcome.state, "${policy.id} changed state on a speech level")
            assertTrue(outcome.actions.isEmpty(), "${policy.id} emitted actions on a speech level")
        }
    }

    /**
     * This phase's brief §25: backgrounding while PTT is held must not leave transmission stuck on.
     * The UI expresses that as the same absolute assignment a release does, which is why there is no
     * separate input for it — and why the two cannot diverge.
     */
    @Test
    fun `backgrounding while held is the same assignment as releasing`() {
        val held = open(IntercomPolicy.MODE_C).copy(pttHeld = true)
        assertTrue(held.transmitting, "the precondition is a live transmission")
        val released = IntercomTransmission.reduce(held, IntercomInput.PttHeld(false))
        assertFalse(released.state.transmitting, "releasing stops transmission")
        assertEquals(listOf(IntercomAction.SetTransmitting(false)), released.actions, "and says so once")
    }

    /** Mode E's `VOICE_STATE.mode` is `ptt` and its `AUDIO_STATE.intercom_mode` is `disabled` (ADR-021 §3). */
    @Test
    fun `mode E reports ptt on the voice plane and disabled on the audio plane`() {
        assertEquals(VoiceMode.PTT, IntercomPolicy.MODE_E.voiceWireMode)
        assertEquals(IntercomMode.DISABLED, IntercomPolicy.MODE_E.intercomWireMode)
        assertFalse(IntercomPolicy.MODE_E.intercomEnabled)
    }

    /** A policy change announces itself on both planes only when the value each carries changed. */
    @Test
    fun `switching between two policies with the same gate announces nothing`() {
        val outcome =
            IntercomTransmission.reduce(open(IntercomPolicy.MODE_A), IntercomInput.PolicySelected(IntercomPolicy.MODE_D))
        assertTrue(
            outcome.actions.none { it is IntercomAction.AnnounceVoiceMode || it == IntercomAction.PublishAudioState },
            "Modes A and D differ only in fields no wire field carries",
        )
    }

    private companion object {
        const val PRESS_COUNT = 50
        const val VOX_HANGOVER_MS = 700L
        const val MICROS_PER_MS = 1_000L
    }
}
