import Foundation

/// PROTOCOL §3: `AUDIO_STATE` is a Session-group message, present from Phase 1's message catalogue.
public enum AudioStateMessageTypes {
    public static let audioState = "AUDIO_STATE"
}

/// PROTOCOL §4.4 — the **effective duplex state right now**, as a value.
///
/// This is the wire projection of `AudioRouteSnapshot` and is deliberately narrower than it:
/// `interrupted`, `lastChangeReason` and `lastTransitionDurationUs` are diagnostics that ADR-016's §4.4
/// field table does not carry, and `AudioStateCodec` has an explicit field list so they cannot leak onto
/// the wire by accident. `AudioStateCodecTests` asserts the encoded key set is exactly §4.4's.
///
/// **No platform vocabulary reaches this type.** Every enum here is ADR-016's shared vocabulary, and the
/// only place a platform profile name is translated into it is each platform's single route mapper
/// (PROTOCOL §4.3.1).
public struct AudioStateMessage: Sendable, Equatable {
    /// Strictly increasing per sender per session (§4.4). A receiver drops a lower or equal value.
    public var revision: Int64
    public var endpointClass: EndpointClass
    /// Whether the capture *device* is open — **not** whether speech is being transmitted. PTT, VOX and
    /// mute gate transmission, not the device (ARCHITECTURE §6.3); `VOICE_STATE.mic_muted` is the field
    /// that reports transmission.
    public var microphoneOpen: Bool
    public var effectiveOutputProfile: AudioProfile
    public var effectiveInputProfile: AudioProfile
    public var effectiveOutputSampleRateHz: Int?
    public var effectiveInputSampleRateHz: Int?
    /// Derived from `effectiveOutputProfile` by ADR-016 Amendment A1, never measured from the audio.
    public var mediaQuality: MediaQuality
    public var routeState: RouteState
    public var intercomMode: IntercomMode
    public var confidence: AudioConfidence

    public init(
        revision: Int64,
        endpointClass: EndpointClass,
        microphoneOpen: Bool,
        effectiveOutputProfile: AudioProfile,
        effectiveInputProfile: AudioProfile,
        effectiveOutputSampleRateHz: Int?,
        effectiveInputSampleRateHz: Int?,
        mediaQuality: MediaQuality,
        routeState: RouteState,
        intercomMode: IntercomMode,
        confidence: AudioConfidence
    ) {
        self.revision = revision
        self.endpointClass = endpointClass
        self.microphoneOpen = microphoneOpen
        self.effectiveOutputProfile = effectiveOutputProfile
        self.effectiveInputProfile = effectiveInputProfile
        self.effectiveOutputSampleRateHz = effectiveOutputSampleRateHz
        self.effectiveInputSampleRateHz = effectiveInputSampleRateHz
        self.mediaQuality = mediaQuality
        self.routeState = routeState
        self.intercomMode = intercomMode
        self.confidence = confidence
    }

    /// Builds the wire projection of a route snapshot. `mediaQuality` is taken from the snapshot's own
    /// derivation so the two cannot disagree about what the user is told, on either platform.
    public static func from(
        revision: Int64,
        snapshot: AudioRouteSnapshot,
        intercomMode: IntercomMode
    ) -> AudioStateMessage {
        AudioStateMessage(
            revision: revision,
            endpointClass: snapshot.endpointClass,
            microphoneOpen: snapshot.microphoneOpen,
            effectiveOutputProfile: snapshot.effectiveOutputProfile,
            effectiveInputProfile: snapshot.effectiveInputProfile,
            effectiveOutputSampleRateHz: snapshot.effectiveOutputSampleRateHz,
            effectiveInputSampleRateHz: snapshot.effectiveInputSampleRateHz,
            mediaQuality: snapshot.mediaQuality,
            routeState: snapshot.routeState,
            intercomMode: intercomMode,
            confidence: snapshot.confidence
        )
    }
}

/// Why an `AUDIO_STATE` payload was refused. Recorded in diagnostics; never sent back verbatim.
public enum AudioStateRejection: String, Sendable, Equatable {
    case missingField = "MISSING_FIELD"
    case wrongFieldType = "WRONG_FIELD_TYPE"
    case revisionOutOfRange = "REVISION_OUT_OF_RANGE"
    case sampleRateOutOfRange = "SAMPLE_RATE_OUT_OF_RANGE"
}

/// Parses, bounds-checks and encodes `AUDIO_STATE` (PROTOCOL §4.4).
///
/// **Total and non-trapping**, exactly like `VoiceSignalCodec`: every peer-controlled field is read
/// through an accessor that returns nil rather than trapping, so a malformed frame is dropped and the
/// control read loop survives — the rule the §2e hardening pass established for `PING`/`PONG`.
///
/// Unrecognised enum values are tolerated as `unknown` rather than making the frame malformed, per
/// §4.3.1's forward-compatibility rule for audio vocabulary. A *structural* problem — a missing key, a
/// wrong JSON type, a negative revision — is a rejection.
///
/// The Kotlin mirror is `com.ridelink.core.protocol.AudioStateCodec`, and both run
/// `protocol/vectors/audio-state/`.
public enum AudioStateCodec {
    public enum Result: Sendable, Equatable {
        case parsed(AudioStateMessage)
        case rejected(AudioStateRejection)
    }

    public static let fieldRevision = "revision"
    public static let fieldEndpointClass = "endpoint_class"
    public static let fieldMicrophoneOpen = "microphone_open"
    public static let fieldEffectiveOutputProfile = "effective_output_profile"
    public static let fieldEffectiveInputProfile = "effective_input_profile"
    public static let fieldEffectiveOutputSampleRateHz = "effective_output_sample_rate_hz"
    public static let fieldEffectiveInputSampleRateHz = "effective_input_sample_rate_hz"
    public static let fieldMediaQuality = "media_quality"
    public static let fieldRouteState = "route_state"
    public static let fieldIntercomMode = "intercom_mode"
    public static let fieldConfidence = "confidence"

    /// 768 kHz is beyond any audio endpoint that exists; this bounds the field without guessing.
    public static let maxSampleRateHz: Int64 = 768_000

    /// PROTOCOL §4.4 types `revision` as a uint64, but a JSON number is a `Double` in this decoder
    /// (`JSONValue`), so anything above 2^53 - 1 cannot round-trip identically on both platforms. Bounding
    /// it here rather than discovering it on a ride is the same reasoning `maxMlineIndex` follows: a bound
    /// both platforms enforce beats a range only one of them can represent. A revision counts observable
    /// audio-state changes in one session, so 2^53 is roughly 285 million years of one change per
    /// microsecond — the bound costs nothing real.
    public static let maxRevision: Int64 = 9_007_199_254_740_991

    /// The complete PROTOCOL §4.4 field list, in spec order. Both the encoder and the "no platform
    /// vocabulary on the wire" test read this, so an added field cannot escape either.
    public static let fields: [String] = [
        fieldRevision,
        fieldEndpointClass,
        fieldMicrophoneOpen,
        fieldEffectiveOutputProfile,
        fieldEffectiveInputProfile,
        fieldEffectiveOutputSampleRateHz,
        fieldEffectiveInputSampleRateHz,
        fieldMediaQuality,
        fieldRouteState,
        fieldIntercomMode,
        fieldConfidence,
    ]

    /// The wire form as a `JSONValue` object.
    ///
    /// A nil sample rate is an explicit JSON null rather than an absent key (§4.4: "int, or `null` if
    /// unknown") — the same distinction `VOICE_ICE.sdp_mid` draws, and for the same reason: a null is the
    /// sender saying "not known", not the sender having forgotten the field.
    public static func encode(_ message: AudioStateMessage) -> [String: JSONValue] {
        [
            fieldRevision: .number(Double(message.revision)),
            fieldEndpointClass: .string(message.endpointClass.wire),
            fieldMicrophoneOpen: .bool(message.microphoneOpen),
            fieldEffectiveOutputProfile: .string(message.effectiveOutputProfile.wire),
            fieldEffectiveInputProfile: .string(message.effectiveInputProfile.wire),
            fieldEffectiveOutputSampleRateHz: message.effectiveOutputSampleRateHz
                .map { JSONValue.number(Double($0)) } ?? .null,
            fieldEffectiveInputSampleRateHz: message.effectiveInputSampleRateHz
                .map { JSONValue.number(Double($0)) } ?? .null,
            fieldMediaQuality: .string(message.mediaQuality.wire),
            fieldRouteState: .string(message.routeState.wire),
            fieldIntercomMode: .string(message.intercomMode.wire),
            fieldConfidence: .string(message.confidence.wire),
        ]
    }

    public static func parse(_ payload: [String: JSONValue]) -> Result {
        guard let revision = audioStateInt64Field(payload, fieldRevision) else {
            return missingOrWrongType(payload, fieldRevision)
        }
        if revision < 0 || revision > maxRevision { return .rejected(.revisionOutOfRange) }

        guard let endpointClass = audioStateStringField(payload, fieldEndpointClass) else {
            return missingOrWrongType(payload, fieldEndpointClass)
        }
        guard let microphoneOpen = audioStateBoolField(payload, fieldMicrophoneOpen) else {
            return missingOrWrongType(payload, fieldMicrophoneOpen)
        }
        guard let outputProfile = audioStateStringField(payload, fieldEffectiveOutputProfile) else {
            return missingOrWrongType(payload, fieldEffectiveOutputProfile)
        }
        guard let inputProfile = audioStateStringField(payload, fieldEffectiveInputProfile) else {
            return missingOrWrongType(payload, fieldEffectiveInputProfile)
        }

        let outputRate = nullableRate(payload, fieldEffectiveOutputSampleRateHz)
        if case .rejected(let reason) = outputRate { return .rejected(reason) }
        let inputRate = nullableRate(payload, fieldEffectiveInputSampleRateHz)
        if case .rejected(let reason) = inputRate { return .rejected(reason) }

        guard let mediaQuality = audioStateStringField(payload, fieldMediaQuality) else {
            return missingOrWrongType(payload, fieldMediaQuality)
        }
        guard let routeState = audioStateStringField(payload, fieldRouteState) else {
            return missingOrWrongType(payload, fieldRouteState)
        }
        guard let intercomMode = audioStateStringField(payload, fieldIntercomMode) else {
            return missingOrWrongType(payload, fieldIntercomMode)
        }
        guard let confidence = audioStateStringField(payload, fieldConfidence) else {
            return missingOrWrongType(payload, fieldConfidence)
        }

        guard case .accepted(let outputRateValue) = outputRate,
              case .accepted(let inputRateValue) = inputRate
        else {
            return .rejected(.wrongFieldType)
        }

        return .parsed(
            AudioStateMessage(
                revision: revision,
                endpointClass: EndpointClass.parse(endpointClass),
                microphoneOpen: microphoneOpen,
                effectiveOutputProfile: AudioProfile.parse(outputProfile),
                effectiveInputProfile: AudioProfile.parse(inputProfile),
                effectiveOutputSampleRateHz: outputRateValue,
                effectiveInputSampleRateHz: inputRateValue,
                mediaQuality: MediaQuality.parse(mediaQuality),
                routeState: RouteState.parse(routeState),
                intercomMode: IntercomMode.parse(intercomMode),
                confidence: AudioConfidence.parse(confidence)
            )
        )
    }

    private enum RateResult {
        case accepted(Int?)
        case rejected(AudioStateRejection)
    }

    /// A sample rate is nullable, so a missing key and an explicit JSON null both mean "unknown". A
    /// present-but-implausible value is rejected rather than carried: a negative or absurd rate on a
    /// diagnostics screen is worse than no rate at all, and `maxSampleRateHz` is far above any real audio
    /// endpoint while still bounding what a peer can put in an int field.
    private static func nullableRate(_ payload: [String: JSONValue], _ key: String) -> RateResult {
        guard let entry = payload[key], entry != .null else { return .accepted(nil) }
        guard case .number(let value) = entry else { return .rejected(.wrongFieldType) }
        guard let exact = Int64(exactly: value.rounded(.towardZero)), Double(exact) == value else {
            return .rejected(.wrongFieldType)
        }
        if exact < 0 || exact > maxSampleRateHz { return .rejected(.sampleRateOutOfRange) }
        return .accepted(Int(exact))
    }

    private static func missingOrWrongType(_ payload: [String: JSONValue], _ key: String) -> Result {
        payload[key] != nil ? .rejected(.wrongFieldType) : .rejected(.missingField)
    }
}

// The field readers below are file-private free functions with distinct names for the same reason
// `VoiceSignalCodec`'s are: `RideLinkPlatform` carries `stringValue`/`int64Value`/`boolValue` extensions
// of its own, and a second set with the same names visible in both modules would be an ambiguity at every
// use site in the platform layer.

private func audioStateStringField(_ payload: [String: JSONValue], _ key: String) -> String? {
    guard case .string(let value)? = payload[key] else { return nil }
    return value
}

/// PROTOCOL fields are typed, not stringly-typed: a quoted number is a wrong type, not an int.
/// `Int64(exactly:)`, never a trapping conversion — a peer-chosen `Double` can be NaN, infinite or out of
/// range, and a `precondition` on wire input is a remotely triggerable crash.
private func audioStateInt64Field(_ payload: [String: JSONValue], _ key: String) -> Int64? {
    guard case .number(let value)? = payload[key] else { return nil }
    return Int64(exactly: value)
}

private func audioStateBoolField(_ payload: [String: JSONValue], _ key: String) -> Bool? {
    guard case .bool(let value)? = payload[key] else { return nil }
    return value
}

/// Owns the sender's side of PROTOCOL §4.4: the monotonic `revision`, and the decision that there is
/// anything new to say.
///
/// Pure and mirrored. `next` returns nil when nothing observable changed, which is what stops a chatty
/// route layer from spending the control plane on identical frames — and, more importantly, what makes
/// `revision` mean "the state changed" rather than "a callback fired".
///
/// `revision` is **strictly increasing and never reset within a session**, including across a route
/// transition and across a voice rebuild. A receiver drops anything not greater than what it holds
/// (`AudioStateInbox`), so reordering cannot resurrect a stale route.
public struct AudioStatePublisher: Sendable {
    private var revision: Int64
    private var last: AudioStateMessage?

    public init(revision: Int64 = 0) {
        self.revision = revision
        self.last = nil
    }

    /// The last message `next` or `forceNext` actually produced, or nil before the first one.
    public var published: AudioStateMessage? { last }

    public var currentRevision: Int64 { revision }

    /// - Returns: the message to send, or nil when this state is identical to the last published one apart
    ///   from its revision — in which case nothing is sent and the revision does not move.
    public mutating func next(
        snapshot: AudioRouteSnapshot,
        intercomMode: IntercomMode
    ) -> AudioStateMessage? {
        let candidate = AudioStateMessage.from(
            revision: revision + 1,
            snapshot: snapshot,
            intercomMode: intercomMode
        )
        if var previous = last {
            previous.revision = candidate.revision
            if previous == candidate { return nil }
        }
        revision = candidate.revision
        last = candidate
        return candidate
    }

    /// Publishes unconditionally, for the two moments PROTOCOL §4.4 names explicitly regardless of whether
    /// anything changed: reaching `CONNECTED`, and ride start. A peer that has just connected has never
    /// seen any of our state, so "nothing changed" is not a reason to stay silent.
    public mutating func forceNext(
        snapshot: AudioRouteSnapshot,
        intercomMode: IntercomMode
    ) -> AudioStateMessage {
        revision += 1
        let message = AudioStateMessage.from(
            revision: revision,
            snapshot: snapshot,
            intercomMode: intercomMode
        )
        last = message
        return message
    }

    /// A new control **session**, not a new connection: §4.4's revision is per sender per session.
    public mutating func resetForNewSession() {
        revision = 0
        last = nil
    }
}

/// Owns the receiver's side of PROTOCOL §4.4's revision rule.
///
/// "Receiver drops a lower revision" is implemented as "drops anything not strictly greater", which also
/// drops an exact retransmit. Pure and mirrored, so a reordering bug fails a laptop test rather than
/// showing up as a peer's route apparently going backwards on a ride.
public struct AudioStateInbox: Sendable {
    public private(set) var current: AudioStateMessage?
    public private(set) var droppedStale = 0

    public init() {}

    /// - Returns: true if `message` was accepted and `current` now holds it.
    @discardableResult
    public mutating func accept(_ message: AudioStateMessage) -> Bool {
        if let held = current, message.revision <= held.revision {
            droppedStale += 1
            return false
        }
        current = message
        return true
    }

    public mutating func reset() {
        current = nil
        droppedStale = 0
    }
}
