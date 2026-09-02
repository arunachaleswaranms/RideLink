import Foundation
import RideLinkCore

#if os(iOS)
import AVFoundation
#endif

/// A platform-neutral name for the kind of audio port the route is on.
///
/// It exists because `AVAudioSession` is **iOS-only** and this target also builds for macOS — which is
/// not an accident to work around but the mechanism that makes `swift test` able to run the shared
/// vectors and the real WebRTC loopback on a laptop (ADR-020). A mapper written directly over
/// `AVAudioSession.Port` would not compile there, and the ADR-016 semantics — which profile a route
/// carries, and whether opening the microphone drags the output with it — are exactly the part worth
/// testing.
///
/// So the translation happens in two steps **in this one file**: `AVAudioSession.Port` to
/// `AudioPortKind` (a mechanical rename, iOS-only, immediately below), then `AudioPortKind` to ADR-016
/// vocabulary (the semantics, platform-free, and unit-tested). ADR-016's "exactly one place per
/// platform" is satisfied by the file, not by a single function.
public enum AudioPortKind: Sendable, Equatable, CaseIterable {
    case bluetoothHandsFree
    case bluetoothMedia
    case bluetoothLowEnergy
    case wiredHeadsetWithMic
    case wiredOutputOnly
    case usbAudio
    case builtInSpeaker
    case builtInReceiver
    case other
}

/// **The one place** an Apple audio route becomes ADR-016 wire vocabulary.
///
/// ADR-016 requires exactly one such place per platform, for two reasons: no `A2DP`, `HFP`,
/// `AVAudioSession` or port-type string may ever reach the wire, and Phase 0's measured hardware
/// behaviour has to have somewhere to land. Filling in `docs/PHASE0_RESULTS.md` changes this file and
/// this file only — it flips `confidence` from `.assumed` to `.measured` and corrects the profile for
/// the real TWS earbuds, with no protocol change anywhere.
///
/// ### What is assumed, and why saying so matters
///
/// Every value below marked `assumed` is a **reasoned guess about hardware nobody has measured**:
///
/// - `.bluetoothHandsFree` is mapped to `.duplexWideband` because a modern TWS set almost certainly
///   negotiates mSBC/wideband speech rather than CVSD. If the iPhone 17 Pro Max and the real earbuds
///   settle on narrowband, the honest value is `.duplexNarrowband` and this line is what changes.
/// - `profileCoupling` is `.inputForcesOutput` for every Bluetooth route, which is ADR-016's central
///   claim and the product's highest risk. It is asserted here as `assumed`, not as fact.
/// - `.bluetoothLowEnergy` is mapped to `.duplexWideband` rather than `.duplexWideStereo`: LE Audio
///   *can* carry media-quality output with usable input, which would make it the one Bluetooth route
///   that does not degrade music — but claiming that before measuring it would be exactly the false
///   reassurance ADR-016 exists to prevent.
public enum IosAudioRouteMapper {
    /// - Parameters:
    ///   - outputPort: the current route's output port kind, or nil when the platform has not told us —
    ///     which is a representable answer, not a reason to guess.
    ///   - hasInput: whether the current route has any input port at all.
    ///   - microphoneOpen: whether the voice audio session is open. Distinct from whether speech is
    ///     being transmitted: PTT, VOX and mute gate transmission, not the device (ARCHITECTURE §6.3).
    ///   - duplexCategoryActive: whether the session is in the `.playAndRecord`/`.voiceChat`
    ///     configuration (ARCHITECTURE §6.2). When it is not, no duplex profile is in force whatever is
    ///     attached.
    public static func map(
        outputPort: AudioPortKind?,
        hasInput: Bool,
        microphoneOpen: Bool,
        duplexCategoryActive: Bool,
        sampleRateHz: Int?,
        lastChangeReason: AudioRouteChangeReason,
        routeState: RouteState = .stable,
        interrupted: Bool = false
    ) -> AudioRouteSnapshot {
        let endpoint = endpointClass(outputPort)
        let duplex = duplexProfile(outputPort, hasInput: hasInput)

        // Without the duplex category there is no duplex profile in force. A Bluetooth endpoint is then
        // on its media profile, which is output-only — reporting a duplex profile here would be the
        // "everything is fine" lie ADR-016 was written to remove, inverted.
        let effectiveOutput: AudioProfile
        if outputPort == nil {
            effectiveOutput = .unknown
        } else if !duplexCategoryActive {
            effectiveOutput = mediaProfile(endpoint)
        } else {
            effectiveOutput = duplex
        }

        let effectiveInput: AudioProfile
        if outputPort == nil {
            effectiveInput = .unknown
        } else if !microphoneOpen || !hasInput {
            effectiveInput = .none
        } else {
            effectiveInput = duplex
        }

        return AudioRouteSnapshot(
            endpointClass: endpoint,
            microphoneOpen: microphoneOpen,
            effectiveOutputProfile: effectiveOutput,
            effectiveInputProfile: effectiveInput,
            effectiveOutputSampleRateHz: effectiveRate(effectiveOutput, sampleRateHz),
            effectiveInputSampleRateHz: effectiveRate(effectiveInput, sampleRateHz),
            routeState: routeState,
            profileCoupling: coupling(endpoint),
            // ADR-016 choice 4: `measured` only once real hardware behaviour is recorded in
            // docs/PHASE0_RESULTS.md. Until then `assumed` is the truth.
            confidence: .assumed,
            interrupted: interrupted,
            lastChangeReason: lastChangeReason
        )
    }

    private static func endpointClass(_ port: AudioPortKind?) -> EndpointClass {
        guard let port else { return .unknown }
        switch port {
        case .bluetoothHandsFree, .bluetoothMedia, .bluetoothLowEnergy:
            return .bluetooth
        case .wiredHeadsetWithMic, .wiredOutputOnly, .usbAudio:
            return .wired
        case .builtInSpeaker:
            return .builtinSpeaker
        case .builtInReceiver:
            return .builtinEarpiece
        case .other:
            return .other
        }
    }

    /// ADR-016's load-bearing field. `inputForcesOutput` for Bluetooth: opening the microphone drags the
    /// output onto the duplex profile too, so the pillion's music collapses to narrowband at exactly the
    /// moment the intercom starts working.
    private static func coupling(_ endpoint: EndpointClass) -> ProfileCoupling {
        switch endpoint {
        case .bluetooth:
            return .inputForcesOutput
        // A wired headset and the built-in speaker/mic carry input without moving the output, so the two
        // directions genuinely are independent there.
        case .wired, .builtinSpeaker, .builtinEarpiece:
            return .independent
        case .other, .unknown:
            return .unknown
        }
    }

    /// What the route carries **with the microphone open**. See the type doc for what is assumed.
    private static func duplexProfile(_ port: AudioPortKind?, hasInput: Bool) -> AudioProfile {
        guard let port else { return .unknown }
        switch port {
        case .bluetoothHandsFree, .bluetoothLowEnergy:
            return .duplexWideband
        case .wiredHeadsetWithMic, .usbAudio:
            return .duplexWideStereo
        case .builtInSpeaker, .builtInReceiver:
            return .builtin
        // Output-only devices have no duplex form: headphones with no microphone, A2DP, line out. With
        // the mic open the platform moves off them entirely — unless the route happens to carry an
        // input from somewhere else, in which case output quality is genuinely retained.
        case .wiredOutputOnly, .bluetoothMedia:
            return hasInput ? .duplexWideStereo : .mediaStereo
        case .other:
            return .unknown
        }
    }

    /// What the route carries with the microphone closed — the music-only case.
    private static func mediaProfile(_ endpoint: EndpointClass) -> AudioProfile {
        switch endpoint {
        case .bluetooth, .wired: return .mediaStereo
        case .builtinSpeaker, .builtinEarpiece: return .builtin
        case .other, .unknown: return .unknown
        }
    }

    /// The profile, not the platform, decides the rate a duplex Bluetooth route actually carries:
    /// `AVAudioSession.sampleRate` reports the session's rate (typically 48 kHz) whatever the endpoint is
    /// doing, so trusting it for a wideband HFP link would overstate the quality by 3x.
    private static func effectiveRate(_ profile: AudioProfile, _ platformRateHz: Int?) -> Int? {
        switch profile {
        case .duplexNarrowband: return narrowbandHz
        case .duplexWideband: return widebandHz
        case .mediaStereo, .duplexWideStereo, .builtin: return platformRateHz
        case .none, .unknown: return nil
        }
    }

    private static let narrowbandHz = 8_000
    private static let widebandHz = 16_000
}

#if os(iOS)
public extension IosAudioRouteMapper {
    /// The mechanical half: `AVAudioSession.Port` to `AudioPortKind`. No ADR-016 semantics here — the
    /// meaning of each kind is decided above, once, where it can be tested without a device.
    static func portKind(_ port: AVAudioSession.Port) -> AudioPortKind {
        switch port {
        case .bluetoothHFP: return .bluetoothHandsFree
        case .bluetoothA2DP: return .bluetoothMedia
        case .bluetoothLE: return .bluetoothLowEnergy
        case .headsetMic: return .wiredHeadsetWithMic
        case .headphones, .lineOut: return .wiredOutputOnly
        case .usbAudio: return .usbAudio
        case .builtInSpeaker: return .builtInSpeaker
        case .builtInReceiver: return .builtInReceiver
        default: return .other
        }
    }

    /// Maps `AVAudioSession.RouteChangeReason` into the platform-neutral vocabulary. A device *name* is
    /// personally identifying (ADR-016), and the platform's own reason values are not parseable by the
    /// peer, so this is the only form that crosses out of the route layer.
    static func changeReason(_ raw: AVAudioSession.RouteChangeReason) -> AudioRouteChangeReason {
        switch raw {
        case .newDeviceAvailable: return .newDeviceAvailable
        case .oldDeviceUnavailable: return .oldDeviceUnavailable
        case .categoryChange: return .categoryChange
        case .override: return .override
        case .wakeFromSleep: return .wakeFromSleep
        case .noSuitableRouteForCategory: return .noSuitableRoute
        case .routeConfigurationChange: return .configurationChange
        case .unknown: return .unknown
        @unknown default: return .unknown
        }
    }
}
#endif
