import Foundation

/// PROTOCOL §4.4 `AUDIO_STATE.intercom_mode`. A **superset** of `VoiceMode` by one value.
///
/// `disabled` is representable here and not in `VOICE_STATE.mode` because it describes the *absence*
/// of a voice session, which `VOICE_STATE` never has to describe: a peer with the intercom disabled
/// sends `VOICE_STATE { state: idle | closed }` and nothing else, and PROTOCOL §7.4 still requires a
/// `mode` value on those frames — for which `IntercomPolicy.voiceWireMode` reports the underlying gate
/// (Mode E is "PTT, disabled", per ARCHITECTURE §6.3, so `ptt` is the honest answer there).
///
/// PROTOCOL §4.4 describes this field as mirroring `VOICE_STATE.mode` while listing four values against
/// that field's three. This type is the resolution of that contradiction, recorded in ARCHITECTURE §6.3
/// and ADR-021 rather than picked silently.
public enum IntercomMode: String, Sendable, Equatable, CaseIterable {
    case continuous
    case vox
    case ptt
    case disabled
    /// Forward compatibility, exactly as for `VoiceMode.unknown` and §4.3.1's audio vocabulary.
    case unknown

    public var wire: String { rawValue }

    public static func parse(_ value: String) -> IntercomMode {
        allCases.first { $0.wire == value && $0 != .unknown } ?? .unknown
    }
}

/// ARCHITECTURE §6.3 — **what gates outbound speech**, never what opens or closes the capture device.
///
/// That distinction is the whole reason this type exists as a policy value rather than five code paths.
/// Two independent reasons force it, and they agree:
///
/// 1. **Audio quality.** Opening and closing a Bluetooth microphone per utterance thrashes the endpoint
///    between its media and duplex profiles — the single worst thing this product can do to music, and
///    the exact failure Phase 0 was built to measure.
/// 2. **Platform rules.** On Android, first-time microphone capture cannot legally begin from the
///    background (ARCHITECTURE §6.4). A PTT press with the screen locked must not be the moment the
///    microphone is first opened.
public enum TransmissionGate: Sendable, Equatable {
    /// Full duplex. Nothing gates transmission; both sides can speak at once (Modes A and D).
    case none

    /// Voice-activated transmission (Mode B). `thresholdDbfs` is the level at or above which the gate
    /// opens; `hangoverMs` is how long it stays open after the level falls back below it, so an ordinary
    /// pause between words does not chop a sentence in half.
    ///
    /// **The state machine is implemented and deterministic; the microphone-driven level that would
    /// drive it is not wired.** Neither pinned WebRTC distribution exposes a fast per-frame input level
    /// through public API — the only level either offers is `audioLevel` on the statistics report, which
    /// RideLink polls every 2 s (`VoiceController`), three orders of magnitude too slow to gate speech.
    /// Getting one would mean either a raw-PCM samples callback plus a hand-written detector (which
    /// ADR-021 declines for this phase) or an upstream API that does not exist today. So
    /// `IntercomTransmission` implements and tests the threshold/hangover rule against a supplied level,
    /// and the level source is **PENDING REAL AUDIO INPUT / LATER HARDENING** (ADR-021 §6).
    case vox(thresholdDbfs: Double, hangoverMs: Int64)

    /// Push-to-talk (Mode C). Transmission follows the button, and **only** the button.
    case ptt

    /// Music only (Mode E): there is no intercom for this ride segment, so nothing is ever transmitted.
    /// ARCHITECTURE §6.3 spells this mode as "ptt-disabled", which is why `IntercomPolicy.voiceWireMode`
    /// reports `ptt` for it while `IntercomPolicy.intercomWireMode` reports `disabled`.
    case disabled

    /// A guess, and labelled as one. Nothing has measured a helmet unit's noise floor at speed, so this
    /// is a starting point for A-14, not a tuned value.
    public static let defaultVoxThresholdDbfs = -35.0
    public static let defaultVoxHangoverMs: Int64 = 700

    public static var defaultVox: TransmissionGate {
        .vox(thresholdDbfs: defaultVoxThresholdDbfs, hangoverMs: defaultVoxHangoverMs)
    }
}

/// ARCHITECTURE §6.3 — what happens to music while the other user is speaking.
public enum OnSpeech: Sendable, Equatable {
    /// Ramp the music down to `toPercent` of its volume over 150–250 ms (FR-016). The ramp itself is
    /// Phase 3+ work — there is no player yet — and this value is the policy it will read.
    case duck(toPercent: Int)
    /// Stop the music entirely while speech is present (Mode D).
    case pause
}

/// ARCHITECTURE §6.3 — which of music quality and intercom availability yields to the other.
public enum MusicQualityPriority: String, Sendable, Equatable {
    case high
    case yieldToVoice
}

/// Which of REQUIREMENTS §8's modes a policy is, for the UI and the diagnostics screen.
public enum IntercomModeId: String, Sendable, Equatable, CaseIterable {
    case modeA = "MODE_A"
    case modeB = "MODE_B"
    case modeC = "MODE_C"
    case modeD = "MODE_D"
    case modeE = "MODE_E"
    /// A policy assembled field by field rather than picked from the five.
    case custom = "CUSTOM"
}

/// ARCHITECTURE §6.3's intercom modes as **one policy object, not five code paths**.
///
/// ```
/// mode := { mic_always_open: Bool,
///           gate: none | vox(threshold, hangover) | ptt,
///           on_speech: duck(to_pct) | pause,
///           music_quality_priority: high | yield_to_voice }
/// ```
///
/// The policy *describes* behaviour; `IntercomTransmission` interprets it, and the platform layers
/// interpret that. No part of the app branches on `modeC`.
///
/// **`micAlwaysOpen == false` does not mean the capture device is reopened per utterance.** It means
/// outbound speech is gated. The capture device is opened once, while the app is foreground-visible, and
/// stays open for the whole ride segment (ARCHITECTURE §6.3/§6.4). See `TransmissionGate`.
///
/// **`default` is Mode C, and that is an architecture default rather than a measurement.**
/// `docs/PHASE0_RESULTS.md` is still awaiting the user's Phase 0 numbers, so no device measurement has
/// selected a winner. ARCHITECTURE §6.3 and ADR-008 §4 both name Mode C as the safest assumption until
/// it is filled in, because PTT is the only mode that cannot be broken by a duplex-profile switch
/// mid-utterance. Nothing here may be read as evidence that Mode C was validated on hardware.
public struct IntercomPolicy: Sendable, Equatable {
    public var micAlwaysOpen: Bool
    public var gate: TransmissionGate
    public var onSpeech: OnSpeech
    public var musicQualityPriority: MusicQualityPriority
    public var id: IntercomModeId

    public init(
        micAlwaysOpen: Bool,
        gate: TransmissionGate,
        onSpeech: OnSpeech,
        musicQualityPriority: MusicQualityPriority,
        id: IntercomModeId = .custom
    ) {
        self.micAlwaysOpen = micAlwaysOpen
        self.gate = gate
        self.onSpeech = onSpeech
        self.musicQualityPriority = musicQualityPriority
        self.id = id
    }

    /// `VOICE_STATE.mode` (PROTOCOL §7.4) — three values, so `.disabled` reports the gate it is a
    /// disabled form of. See `IntercomMode` for why the two vocabularies differ by one.
    public var voiceWireMode: VoiceMode {
        switch gate {
        case .none: return .continuous
        case .vox: return .vox
        case .ptt, .disabled: return .ptt
        }
    }

    /// `AUDIO_STATE.intercom_mode` (PROTOCOL §4.4) — four values, including `disabled`.
    public var intercomWireMode: IntercomMode {
        switch gate {
        case .none: return .continuous
        case .vox: return .vox
        case .ptt: return .ptt
        case .disabled: return .disabled
        }
    }

    /// True when this policy admits an intercom at all. Mode E does not.
    public var intercomEnabled: Bool { gate != .disabled }

    /// True when transmission is full duplex — both users can speak simultaneously, which is the
    /// product's primary intent (REQUIREMENTS §8 Mode A). PTT and VOX are fallbacks layered over the
    /// same live capture path, never a different transport.
    public var fullDuplex: Bool { gate == .none }

    /// Mode A — continuous intercom + music, ducked on speech. Full duplex.
    public static let modeA = IntercomPolicy(
        micAlwaysOpen: true,
        gate: .none,
        onSpeech: .duck(toPercent: duckToPctA),
        musicQualityPriority: .high,
        id: .modeA
    )

    /// Mode B — VOX.
    public static let modeB = IntercomPolicy(
        micAlwaysOpen: false,
        gate: TransmissionGate.defaultVox,
        onSpeech: .duck(toPercent: duckToPctA),
        musicQualityPriority: .high,
        id: .modeB
    )

    /// Mode C — push-to-talk. `IntercomPolicy.default` until `docs/PHASE0_RESULTS.md` says otherwise.
    public static let modeC = IntercomPolicy(
        micAlwaysOpen: false,
        gate: .ptt,
        onSpeech: .duck(toPercent: duckToPctC),
        musicQualityPriority: .high,
        id: .modeC
    )

    /// Mode D — intercom priority: continuous voice, music yields. Full duplex.
    public static let modeD = IntercomPolicy(
        micAlwaysOpen: true,
        gate: .none,
        onSpeech: .pause,
        musicQualityPriority: .yieldToVoice,
        id: .modeD
    )

    /// Mode E — music only. No intercom microphone session at all.
    public static let modeE = IntercomPolicy(
        micAlwaysOpen: false,
        gate: .disabled,
        onSpeech: .duck(toPercent: 100),
        musicQualityPriority: .high,
        id: .modeE
    )

    /// **Mode C, by architecture rather than by measurement.** See the type doc: Phase 0's results are
    /// not in the repository, so `confidence` stays `assumed` and the default stays the mode that cannot
    /// be broken mid-utterance by a profile switch.
    public static let `default` = modeC

    public static let all: [IntercomPolicy] = [modeA, modeB, modeC, modeD, modeE]

    public static func byId(_ id: IntercomModeId) -> IntercomPolicy? {
        all.first { $0.id == id }
    }

    private static let duckToPctA = 25
    private static let duckToPctC = 35
}
