import Foundation

/// ADR-016 / PROTOCOL §4.3.1 — the audio vocabulary, and the **only** place platform profile names
/// are allowed to be translated into.
///
/// The rule these enums exist to enforce: no `A2DP`, `HFP`, `SCO`, `AVAudioSession` or `AudioManager`
/// string ever reaches the wire. Each platform's route layer maps its own names into these values in
/// exactly one function, which is also the one place Phase 0's measured results land — they flip
/// `AudioConfidence` from `.assumed` to `.measured` and correct the profile mapping for the real
/// hardware, with no protocol change.
public enum EndpointClass: String, Sendable, Equatable, CaseIterable {
    case bluetooth
    case wired
    case builtinSpeaker = "builtin_speaker"
    case builtinEarpiece = "builtin_earpiece"
    case other
    case unknown

    public var wire: String { rawValue }

    public static func parse(_ value: String) -> EndpointClass {
        allCases.first { $0.wire == value } ?? .unknown
    }
}

/// Named for what a route can *carry*, never for the Bluetooth profile that happens to implement it
/// (ADR-016 choice 3), so a wired headset, the built-in speaker and any future transport are
/// describable in the same field.
public enum AudioProfile: String, Sendable, Equatable, CaseIterable {
    /// Media-quality stereo output only — no usable input. Bluetooth media streaming.
    case mediaStereo = "media_stereo"
    /// Duplex at roughly 8 kHz both ways. Legacy hands-free.
    case duplexNarrowband = "duplex_narrowband"
    /// Duplex at roughly 16 kHz both ways. Modern hands-free — the ordinary helmet-unit case.
    case duplexWideband = "duplex_wideband"
    /// Duplex at media quality *with* usable input. Wired headset, built-in, next-generation BT audio.
    case duplexWideStereo = "duplex_wide_stereo"
    /// The device's own speaker and microphone.
    case builtin
    case none
    case unknown

    public var wire: String { rawValue }

    /// True for every profile that carries input as well as output.
    public var isDuplex: Bool {
        self == .duplexNarrowband || self == .duplexWideband || self == .duplexWideStereo || self == .builtin
    }

    /// True for a duplex profile that carries output at **reduced** bandwidth — the case where opening
    /// the microphone has cost the music something.
    ///
    /// `duplexWideStereo` and `builtin` are duplex but not narrowed: a wired headset and the device's
    /// own speaker carry usable input without moving the output onto a narrowband codec. See ADR-016
    /// Amendment A1 for why this is a distinct predicate rather than "duplex and not wide stereo" — the
    /// shorter phrasing contradicted ADR-016's own representable-states table for `builtin`.
    public var isNarrowedDuplex: Bool {
        self == .duplexNarrowband || self == .duplexWideband
    }

    public static func parse(_ value: String) -> AudioProfile {
        allCases.first { $0.wire == value } ?? .unknown
    }
}

/// **The field ADR-016 exists for.** `inputForcesOutput` states outright that opening the microphone
/// drags the *output* onto the duplex profile too — which is the single highest product risk in
/// RideLink, and the thing the old independent-routes model was wrong about.
public enum ProfileCoupling: String, Sendable, Equatable, CaseIterable {
    case independent
    case inputForcesOutput = "input_forces_output"
    case unknown

    public var wire: String { rawValue }

    public static func parse(_ value: String) -> ProfileCoupling {
        allCases.first { $0.wire == value } ?? .unknown
    }
}

/// Derived from the effective output profile, never measured from the audio (ADR-016 cost note).
public enum MediaQuality: String, Sendable, Equatable, CaseIterable {
    case full
    case reduced
    case unavailable
    case unknown

    public var wire: String { rawValue }

    public static func parse(_ value: String) -> MediaQuality {
        allCases.first { $0.wire == value } ?? .unknown
    }
}

/// A route change is a first-class state, not a moment when every other field is quietly stale.
public enum RouteState: String, Sendable, Equatable, CaseIterable {
    case stable
    case transitioning

    public var wire: String { rawValue }

    public static func parse(_ value: String) -> RouteState {
        allCases.first { $0.wire == value } ?? .stable
    }
}

/// `.measured` only once real hardware behaviour is recorded in `docs/PHASE0_RESULTS.md`. Until then
/// `.assumed` is the truth, and saying so is the whole point of the field.
public enum AudioConfidence: String, Sendable, Equatable, CaseIterable {
    case measured
    case assumed
    case unknown

    public var wire: String { rawValue }

    public static func parse(_ value: String) -> AudioConfidence {
        allCases.first { $0.wire == value } ?? .unknown
    }
}

/// Why the route last changed, in platform-neutral terms.
///
/// A device *name* is personally identifying (ADR-016 rejected free-text route descriptions for
/// exactly that reason), and the platform's own reason strings are neither parseable by the peer nor
/// pinnable by a test. This enum is what each route layer maps its platform reason into.
public enum AudioRouteChangeReason: String, Sendable, Equatable, CaseIterable {
    case newDeviceAvailable
    case oldDeviceUnavailable
    case categoryChange
    case override
    case wakeFromSleep
    case noSuitableRoute
    case configurationChange
    case interruptionBegan
    case interruptionEnded
    case mediaServicesReset
    case unknown
}

/// The effective runtime audio state — ADR-016's `AUDIO_STATE`, as a pure value.
///
/// "Effective" is load-bearing: `effectiveOutputProfile` is what is *actually* active after any
/// `ProfileCoupling` has taken effect, so the honest answer in the ordinary Bluetooth intercom case is
/// a duplex profile at 16 kHz with `.reduced` media quality — and both users can see it. The old model
/// could only produce a false reassurance.
///
/// Phase 2a populates this from each platform's route layer and shows it on the diagnostics screen.
/// The `AUDIO_STATE` **message** that carries it to the peer is Phase 2b/6 work; this is the shared
/// type it will be built from, so the two cannot drift.
public struct AudioRouteSnapshot: Sendable, Equatable {
    public var endpointClass: EndpointClass
    public var microphoneOpen: Bool
    public var effectiveOutputProfile: AudioProfile
    public var effectiveInputProfile: AudioProfile
    public var effectiveOutputSampleRateHz: Int?
    public var effectiveInputSampleRateHz: Int?
    public var routeState: RouteState
    public var profileCoupling: ProfileCoupling
    public var confidence: AudioConfidence
    /// A platform-level interruption is in progress — an incoming call, Siri, another app taking the
    /// session. Deliberately **not** a `VOICE_STATE` value: PROTOCOL §7.4 keeps the WebRTC session and
    /// the local audio route as separate reports, and an interruption is a route fact.
    public var interrupted: Bool
    /// A short, platform-neutral reason for the last route change, for the diagnostics screen. Never a
    /// device name and never free text from the platform — see `AudioRouteChangeReason`.
    public var lastChangeReason: AudioRouteChangeReason

    public init(
        endpointClass: EndpointClass = .unknown,
        microphoneOpen: Bool = false,
        effectiveOutputProfile: AudioProfile = .unknown,
        effectiveInputProfile: AudioProfile = .unknown,
        effectiveOutputSampleRateHz: Int? = nil,
        effectiveInputSampleRateHz: Int? = nil,
        routeState: RouteState = .stable,
        profileCoupling: ProfileCoupling = .unknown,
        confidence: AudioConfidence = .assumed,
        interrupted: Bool = false,
        lastChangeReason: AudioRouteChangeReason = .unknown
    ) {
        self.endpointClass = endpointClass
        self.microphoneOpen = microphoneOpen
        self.effectiveOutputProfile = effectiveOutputProfile
        self.effectiveInputProfile = effectiveInputProfile
        self.effectiveOutputSampleRateHz = effectiveOutputSampleRateHz
        self.effectiveInputSampleRateHz = effectiveInputSampleRateHz
        self.routeState = routeState
        self.profileCoupling = profileCoupling
        self.confidence = confidence
        self.interrupted = interrupted
        self.lastChangeReason = lastChangeReason
    }

    /// ADR-016, as corrected by its Amendment A1: `reduced` whenever the effective output profile is a
    /// **narrowed** duplex profile — `duplex_narrowband` or `duplex_wideband`.
    ///
    /// Derived here, in one place, on both platforms, so the two cannot disagree about what the user is
    /// told. ADR-016's original prose said "a duplex profile that is not `duplex_wide_stereo`", which
    /// contradicted its own representable-states table: `builtin` is duplex, is not
    /// `duplex_wide_stereo`, and is listed there as `full` — correctly, because a phone's own speaker
    /// and microphone do not degrade each other.
    public var mediaQuality: MediaQuality {
        if effectiveOutputProfile == .none { return .unavailable }
        if effectiveOutputProfile == .unknown { return .unknown }
        return effectiveOutputProfile.isNarrowedDuplex ? .reduced : .full
    }
}
