package com.ridelink.core.audiopolicy

import com.ridelink.core.protocol.VoiceMode

/**
 * Everything the "am I transmitting?" decision depends on.
 *
 * Every field is an **absolute** value rather than an edge, which is what makes
 * [IntercomCommandMailbox] able to coalesce commands by kind without losing anything: the newest
 * value of a kind is the only one whose effect survives.
 *
 * **The reducer is not commutative across kinds, and it must not be.** An
 * [IntercomInput.PolicySelected] or an [IntercomInput.CaptureOpen] with `open = false` deliberately
 * *resets* the gate's transient state, so neither commutes with an [IntercomInput.PttHeld]. That is
 * why [IntercomCommandMailbox] drains kinds in a fixed order with those two first: the guarantee
 * callers actually need is that a batch of commands lands on the same state whatever order it
 * **arrived** in, and a fixed drain order gives that without pretending the reducer is order-blind.
 * `IntercomCommandMailboxTest` asserts it as a property over every arrival permutation.
 *
 * No clock, no I/O, no platform type. Monotonic microseconds are *passed in* where they are needed
 * (CLAUDE.md rules 5 and 9), exactly as `SessionFsm` takes time as a parameter.
 */
data class TransmissionState(
    val policy: IntercomPolicy = IntercomPolicy.DEFAULT,
    /**
     * The capture device and platform audio session are open for this ride segment. Distinct from
     * whether speech is being transmitted — that is [transmitting] — and it is the distinction
     * PROTOCOL §4.4 draws between `AUDIO_STATE.microphone_open` and `VOICE_STATE.mic_muted`.
     */
    val captureOpen: Boolean = false,
    /** The user pressed Mute. Survives a policy change and a link blip; only the user clears it. */
    val userMuted: Boolean = false,
    /** The PTT control is held down **right now**. Cleared by release, cancel and backgrounding. */
    val pttHeld: Boolean = false,
    /** The VOX gate is open. Driven by [IntercomInput.SpeechLevel]; see [TransmissionGate.Vox]. */
    val voxOpen: Boolean = false,
    /** Monotonic deadline, in microseconds, at which an open VOX gate closes if nothing renews it. */
    val voxHangoverUntilMonoUs: Long? = null,
    /** A platform interruption — a call, Siri, another app taking the session (ADR-016). */
    val interrupted: Boolean = false,
) {
    /**
     * **The one rule.** Every clause is a reason *not* to transmit except the last, which is the
     * policy's own gate.
     *
     * The capture check comes first and is not negotiable: transmission cannot precede an open
     * capture path, and a PTT press must never be what opens one (ARCHITECTURE §6.4).
     */
    val transmitting: Boolean
        get() =
            when {
                !captureOpen -> false
                interrupted -> false
                userMuted -> false
                else ->
                    when (policy.gate) {
                        TransmissionGate.None -> true
                        is TransmissionGate.Vox -> voxOpen
                        TransmissionGate.Ptt -> pttHeld
                        TransmissionGate.Disabled -> false
                    }
            }

    /** What `VOICE_STATE.mic_muted` reports: "this peer is transmitting silence" (PROTOCOL §7.4). */
    val micMutedForWire: Boolean get() = !transmitting
}

/**
 * What drives [IntercomTransmission]. Each is an absolute assignment, never an edge — see
 * [TransmissionState].
 */
sealed class IntercomInput {
    data class PolicySelected(
        val policy: IntercomPolicy,
    ) : IntercomInput()

    data class UserMuted(
        val muted: Boolean,
    ) : IntercomInput()

    /**
     * The PTT control's current position. `false` covers release, touch-cancel, an accessibility
     * gesture that never delivers an "up", and the app being backgrounded — every one of which must
     * leave transmission **off** rather than stuck on (this phase's brief §25).
     */
    data class PttHeld(
        val held: Boolean,
    ) : IntercomInput()

    data class CaptureOpen(
        val open: Boolean,
    ) : IntercomInput()

    data class Interrupted(
        val interrupted: Boolean,
    ) : IntercomInput()

    /**
     * A measured input level, in dBFS, at a monotonic instant. Only meaningful under
     * [TransmissionGate.Vox]; ignored under every other gate rather than silently changing state.
     *
     * **No production code path supplies this yet** — see [TransmissionGate.Vox] for exactly why,
     * and ADR-021 §6. The rule below is implemented and tested; its level source is pending.
     */
    data class SpeechLevel(
        val levelDbfs: Double,
        val atMonoUs: Long,
    ) : IntercomInput()

    /**
     * Time passed with no new level. Closes an open VOX gate whose hangover has expired, so a
     * silent-but-still-open gate is a bounded condition rather than a permanent one.
     */
    data class VoxTick(
        val atMonoUs: Long,
    ) : IntercomInput()
}

/** What the driver must do. Deliberately tiny: this layer decides transmission and nothing else. */
sealed class IntercomAction {
    /**
     * Enable or disable the **outbound audio track**, never the capture device (this phase's brief
     * §6). On Android that is `AudioTrack.setEnabled`; on Apple `RTCAudioTrack.isEnabled`. Emitted
     * only when the value actually changes, so a held PTT does not re-issue it per input.
     */
    data class SetTransmitting(
        val transmitting: Boolean,
    ) : IntercomAction()

    /** The policy's `VOICE_STATE.mode` changed and the peer must be told (PROTOCOL §7.4). */
    data class AnnounceVoiceMode(
        val mode: VoiceMode,
    ) : IntercomAction()

    /** The policy's `AUDIO_STATE.intercom_mode` changed, so a fresh `AUDIO_STATE` is due (§4.4). */
    object PublishAudioState : IntercomAction()
}

data class IntercomOutcome(
    val state: TransmissionState,
    val actions: List<IntercomAction>,
)

/**
 * The intercom transmission gate, as a pure `(state, input) -> (state, actions)` reducer.
 *
 * It is a separate object for the same reason `SessionGate` (ADR-019) and `VoiceNegotiation`
 * (ADR-020) are: the properties that matter here are properties of *this table*, and a table can be
 * exhausted by a laptop unit test on both platforms —
 *
 * - full duplex transmits with no gate at all;
 * - PTT gates transmission and **nothing else**, so 50 presses produce 100 track-enable flips and
 *   zero capture-device operations;
 * - mute wins over an open gate;
 * - an interruption and a closed capture path both win over everything;
 * - a policy switch cannot leave transmission on in a mode that should not be transmitting.
 *
 * `RideLinkCore.IntercomTransmission` is the mirror; the two must agree case for case, and
 * `protocol/vectors/intercom/` is what makes a disagreement fail a build instead of a ride.
 *
 * It owns no session state, holds no trust, reads no clock and knows nothing about WebRTC.
 */
object IntercomTransmission {
    fun reduce(
        state: TransmissionState,
        input: IntercomInput,
    ): IntercomOutcome {
        val next = apply(state, input)
        return IntercomOutcome(next, actionsFor(state, next))
    }

    private fun apply(
        state: TransmissionState,
        input: IntercomInput,
    ): TransmissionState =
        when (input) {
            is IntercomInput.PolicySelected -> policySelected(state, input.policy)
            is IntercomInput.UserMuted -> state.copy(userMuted = input.muted)
            is IntercomInput.PttHeld -> state.copy(pttHeld = input.held)
            is IntercomInput.CaptureOpen -> captureChanged(state, input.open)
            is IntercomInput.Interrupted -> state.copy(interrupted = input.interrupted)
            is IntercomInput.SpeechLevel -> speechLevel(state, input)
            is IntercomInput.VoxTick -> voxTick(state, input.atMonoUs)
        }

    /**
     * A policy change resets the gate's own transient state but never the user's mute and never the
     * capture path. Leaving [TransmissionState.pttHeld] set while switching *into* PTT would open
     * transmission on a button nobody is holding; leaving [TransmissionState.voxOpen] set while
     * switching out of VOX would leave a stale gate deciding nothing.
     */
    private fun policySelected(
        state: TransmissionState,
        policy: IntercomPolicy,
    ): TransmissionState =
        state.copy(
            policy = policy,
            pttHeld = false,
            voxOpen = false,
            voxHangoverUntilMonoUs = null,
        )

    /**
     * Losing the capture path clears the gate too. Keeping `pttHeld` across a capture close would
     * mean a later reopen started transmitting without a fresh press.
     */
    private fun captureChanged(
        state: TransmissionState,
        open: Boolean,
    ): TransmissionState =
        if (open) {
            state.copy(captureOpen = true)
        } else {
            state.copy(captureOpen = false, pttHeld = false, voxOpen = false, voxHangoverUntilMonoUs = null)
        }

    private fun speechLevel(
        state: TransmissionState,
        input: IntercomInput.SpeechLevel,
    ): TransmissionState {
        val gate = state.policy.gate as? TransmissionGate.Vox ?: return state
        return if (input.levelDbfs >= gate.thresholdDbfs) {
            state.copy(
                voxOpen = true,
                voxHangoverUntilMonoUs = input.atMonoUs + gate.hangoverMs * MICROS_PER_MS,
            )
        } else {
            closeIfHangoverExpired(state, input.atMonoUs)
        }
    }

    private fun voxTick(
        state: TransmissionState,
        nowMonoUs: Long,
    ): TransmissionState {
        if (state.policy.gate !is TransmissionGate.Vox) return state
        return closeIfHangoverExpired(state, nowMonoUs)
    }

    private fun closeIfHangoverExpired(
        state: TransmissionState,
        nowMonoUs: Long,
    ): TransmissionState {
        if (!state.voxOpen) return state
        // A gate open with no deadline cannot be reasoned about, so it closes: an unbounded open gate
        // is the one outcome VOX must never produce.
        val expired = state.voxHangoverUntilMonoUs?.let { nowMonoUs >= it } ?: true
        return if (expired) state.copy(voxOpen = false, voxHangoverUntilMonoUs = null) else state
    }

    /**
     * Actions are a **diff**, not a restatement. That is what makes the capture-open invariant
     * checkable: nothing here can ever emit a capture operation, and a repeated input emits no
     * action at all.
     */
    private fun actionsFor(
        before: TransmissionState,
        after: TransmissionState,
    ): List<IntercomAction> {
        val actions = mutableListOf<IntercomAction>()
        if (before.transmitting != after.transmitting) {
            actions += IntercomAction.SetTransmitting(after.transmitting)
        }
        if (before.policy.voiceWireMode != after.policy.voiceWireMode) {
            actions += IntercomAction.AnnounceVoiceMode(after.policy.voiceWireMode)
        }
        if (before.policy.intercomWireMode != after.policy.intercomWireMode) {
            actions += IntercomAction.PublishAudioState
        }
        return actions
    }

    private const val MICROS_PER_MS = 1_000L
}
