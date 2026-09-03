package com.ridelink.core.audiopolicy

import com.ridelink.core.protocol.VoiceMode

/**
 * PROTOCOL §4.4 `AUDIO_STATE.intercom_mode`. A **superset** of [VoiceMode] by one value.
 *
 * `disabled` is representable here and not in `VOICE_STATE.mode` because it describes the *absence*
 * of a voice session, which `VOICE_STATE` never has to describe: a peer with the intercom disabled
 * sends `VOICE_STATE { state: idle | closed }` and nothing else, and PROTOCOL §7.4 still requires a
 * `mode` value on those frames — for which [IntercomPolicy.voiceWireMode] reports the underlying
 * gate (Mode E is "PTT, disabled", per ARCHITECTURE §6.3, so `ptt` is the honest answer there).
 *
 * PROTOCOL §4.4 describes this field as mirroring `VOICE_STATE.mode` while listing four values
 * against that field's three. This type is the resolution of that contradiction, recorded in
 * ARCHITECTURE §6.3 and ADR-021 rather than picked silently.
 */
enum class IntercomMode {
    CONTINUOUS,
    VOX,
    PTT,
    DISABLED,

    /** Forward compatibility, exactly as for [VoiceMode.UNKNOWN] and §4.3.1's audio vocabulary. */
    UNKNOWN,
    ;

    val wire: String get() = name.lowercase()

    companion object {
        fun parse(value: String): IntercomMode = entries.firstOrNull { it.wire == value && it != UNKNOWN } ?: UNKNOWN
    }
}

/**
 * ARCHITECTURE §6.3 — **what gates outbound speech**, never what opens or closes the capture device.
 *
 * That distinction is the whole reason this type exists as a policy value rather than five code
 * paths. Two independent reasons force it, and they agree:
 *
 * 1. **Audio quality.** Opening and closing a Bluetooth microphone per utterance thrashes the
 *    endpoint between its media and duplex profiles — the single worst thing this product can do to
 *    music, and the exact failure Phase 0 was built to measure.
 * 2. **Platform rules.** On Android, first-time microphone capture cannot legally begin from the
 *    background (ARCHITECTURE §6.4). A PTT press with the screen locked must not be the moment the
 *    microphone is first opened.
 */
sealed class TransmissionGate {
    /** Full duplex. Nothing gates transmission; both sides can speak at once (Modes A and D). */
    object None : TransmissionGate()

    /**
     * Voice-activated transmission (Mode B). [thresholdDbfs] is the level at or above which the gate
     * opens; [hangoverMs] is how long it stays open after the level falls back below it, so an
     * ordinary pause between words does not chop a sentence in half.
     *
     * **The state machine is implemented and deterministic; the microphone-driven level that would
     * drive it is not wired.** Neither pinned WebRTC distribution exposes a fast per-frame input
     * level through public API — the only level either offers is `audioLevel` on the statistics
     * report, which RideLink polls every 2 s (`VoiceController`), three orders of magnitude too slow
     * to gate speech. Getting one would mean either a raw-PCM samples callback plus a hand-written
     * detector (which ADR-021 declines for this phase) or an upstream API that does not exist today.
     * So [IntercomTransmission] implements and tests the threshold/hangover rule against a supplied
     * level, and the level source is **PENDING REAL AUDIO INPUT / LATER HARDENING** (ADR-021 §6).
     */
    data class Vox(
        val thresholdDbfs: Double = DEFAULT_THRESHOLD_DBFS,
        val hangoverMs: Long = DEFAULT_HANGOVER_MS,
    ) : TransmissionGate() {
        init {
            require(hangoverMs >= 0) { "hangoverMs must not be negative" }
        }

        companion object {
            /**
             * A guess, and labelled as one. Nothing has measured a helmet unit's noise floor at
             * speed, so this is a starting point for A-14, not a tuned value.
             */
            const val DEFAULT_THRESHOLD_DBFS = -35.0
            const val DEFAULT_HANGOVER_MS = 700L
        }
    }

    /** Push-to-talk (Mode C). Transmission follows the button, and **only** the button. */
    object Ptt : TransmissionGate()

    /**
     * Music only (Mode E): there is no intercom for this ride segment, so nothing is ever
     * transmitted. ARCHITECTURE §6.3 spells this mode as "ptt-disabled", which is why
     * [IntercomPolicy.voiceWireMode] reports `ptt` for it while [IntercomPolicy.intercomWireMode]
     * reports `disabled`.
     */
    object Disabled : TransmissionGate()
}

/** ARCHITECTURE §6.3 — what happens to music while the other user is speaking. */
sealed class OnSpeech {
    /**
     * Ramp the music down to [toPercent] of its volume over 150–250 ms (FR-016). The ramp itself is
     * Phase 3+ work — there is no player yet — and this value is the policy it will read.
     */
    data class Duck(
        val toPercent: Int,
    ) : OnSpeech() {
        init {
            require(toPercent in MIN_PERCENT..MAX_PERCENT) { "toPercent must be a percentage" }
        }

        private companion object {
            const val MIN_PERCENT = 0
            const val MAX_PERCENT = 100
        }
    }

    /** Stop the music entirely while speech is present (Mode D). */
    object Pause : OnSpeech()
}

/** ARCHITECTURE §6.3 — which of music quality and intercom availability yields to the other. */
enum class MusicQualityPriority {
    HIGH,
    YIELD_TO_VOICE,
}

/** Which of REQUIREMENTS §8's modes a policy is, for the UI and the diagnostics screen. */
enum class IntercomModeId {
    MODE_A,
    MODE_B,
    MODE_C,
    MODE_D,
    MODE_E,

    /** A policy assembled field by field rather than picked from the five. */
    CUSTOM,
}

/**
 * ARCHITECTURE §6.3's intercom modes as **one policy object, not five code paths**.
 *
 * ```
 * mode := { mic_always_open: Bool,
 *           gate: none | vox(threshold, hangover) | ptt,
 *           on_speech: duck(to_pct) | pause,
 *           music_quality_priority: high | yield_to_voice }
 * ```
 *
 * The policy *describes* behaviour; [IntercomTransmission] interprets it, and the platform layers
 * interpret that. No part of the app branches on `MODE_C`.
 *
 * **[micAlwaysOpen] `false` does not mean the capture device is reopened per utterance.** It means
 * outbound speech is gated. The capture device is opened once, while the app is foreground-visible,
 * and stays open for the whole ride segment (ARCHITECTURE §6.3/§6.4). See [TransmissionGate].
 *
 * **[DEFAULT] is Mode C, and that is an architecture default rather than a measurement.**
 * `docs/PHASE0_RESULTS.md` is still awaiting the user's Phase 0 numbers, so no device measurement
 * has selected a winner. ARCHITECTURE §6.3 and ADR-008 §4 both name Mode C as the safest assumption
 * until it is filled in, because PTT is the only mode that cannot be broken by a duplex-profile
 * switch mid-utterance. Nothing here may be read as evidence that Mode C was validated on hardware.
 */
data class IntercomPolicy(
    val micAlwaysOpen: Boolean,
    val gate: TransmissionGate,
    val onSpeech: OnSpeech,
    val musicQualityPriority: MusicQualityPriority,
    val id: IntercomModeId = IntercomModeId.CUSTOM,
) {
    /**
     * `VOICE_STATE.mode` (PROTOCOL §7.4) — three values, so [TransmissionGate.Disabled] reports the
     * gate it is a disabled form of. See [IntercomMode] for why the two vocabularies differ by one.
     */
    val voiceWireMode: VoiceMode
        get() =
            when (gate) {
                TransmissionGate.None -> VoiceMode.CONTINUOUS
                is TransmissionGate.Vox -> VoiceMode.VOX
                TransmissionGate.Ptt, TransmissionGate.Disabled -> VoiceMode.PTT
            }

    /** `AUDIO_STATE.intercom_mode` (PROTOCOL §4.4) — four values, including `disabled`. */
    val intercomWireMode: IntercomMode
        get() =
            when (gate) {
                TransmissionGate.None -> IntercomMode.CONTINUOUS
                is TransmissionGate.Vox -> IntercomMode.VOX
                TransmissionGate.Ptt -> IntercomMode.PTT
                TransmissionGate.Disabled -> IntercomMode.DISABLED
            }

    /** True when this policy admits an intercom at all. Mode E does not. */
    val intercomEnabled: Boolean get() = gate != TransmissionGate.Disabled

    /**
     * True when transmission is full duplex — both users can speak simultaneously, which is the
     * product's primary intent (REQUIREMENTS §8 Mode A). PTT and VOX are fallbacks layered over the
     * same live capture path, never a different transport.
     */
    val fullDuplex: Boolean get() = gate == TransmissionGate.None

    companion object {
        /** Mode A — continuous intercom + music, ducked on speech. Full duplex. */
        val MODE_A =
            IntercomPolicy(
                micAlwaysOpen = true,
                gate = TransmissionGate.None,
                onSpeech = OnSpeech.Duck(toPercent = DUCK_TO_PCT_A),
                musicQualityPriority = MusicQualityPriority.HIGH,
                id = IntercomModeId.MODE_A,
            )

        /** Mode B — VOX. */
        val MODE_B =
            IntercomPolicy(
                micAlwaysOpen = false,
                gate = TransmissionGate.Vox(),
                onSpeech = OnSpeech.Duck(toPercent = DUCK_TO_PCT_A),
                musicQualityPriority = MusicQualityPriority.HIGH,
                id = IntercomModeId.MODE_B,
            )

        /** Mode C — push-to-talk. [DEFAULT] until `docs/PHASE0_RESULTS.md` says otherwise. */
        val MODE_C =
            IntercomPolicy(
                micAlwaysOpen = false,
                gate = TransmissionGate.Ptt,
                onSpeech = OnSpeech.Duck(toPercent = DUCK_TO_PCT_C),
                musicQualityPriority = MusicQualityPriority.HIGH,
                id = IntercomModeId.MODE_C,
            )

        /** Mode D — intercom priority: continuous voice, music yields. Full duplex. */
        val MODE_D =
            IntercomPolicy(
                micAlwaysOpen = true,
                gate = TransmissionGate.None,
                onSpeech = OnSpeech.Pause,
                musicQualityPriority = MusicQualityPriority.YIELD_TO_VOICE,
                id = IntercomModeId.MODE_D,
            )

        /** Mode E — music only. No intercom microphone session at all. */
        val MODE_E =
            IntercomPolicy(
                micAlwaysOpen = false,
                gate = TransmissionGate.Disabled,
                onSpeech = OnSpeech.Duck(toPercent = UNDUCKED_PCT),
                musicQualityPriority = MusicQualityPriority.HIGH,
                id = IntercomModeId.MODE_E,
            )

        /**
         * **Mode C, by architecture rather than by measurement.** See the class doc: Phase 0's
         * results are not in the repository, so `confidence` stays `assumed` and the default stays
         * the mode that cannot be broken mid-utterance by a profile switch.
         */
        val DEFAULT = MODE_C

        val ALL = listOf(MODE_A, MODE_B, MODE_C, MODE_D, MODE_E)

        fun byId(id: IntercomModeId): IntercomPolicy? = ALL.firstOrNull { it.id == id }

        private const val DUCK_TO_PCT_A = 25
        private const val DUCK_TO_PCT_C = 35

        /** Mode E has no intercom, so there is nothing to duck *for*: music stays at full volume. */
        private const val UNDUCKED_PCT = 100
    }
}
