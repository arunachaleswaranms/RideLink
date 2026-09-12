import Foundation

/// PROTOCOL §3: `AUDIO_STATE` is a Session-group message, present from Phase 1's message catalogue.
public enum AudioStateMessageTypes {
    public static let audioState = "AUDIO_STATE"
}

private let audioStateEpochRedactedPrefixLen = 6

/// PROTOCOL §4.4 — the identity of one sender's `revision` **namespace**.
///
/// 16 CSPRNG bytes as 32 lowercase hex characters, minted when a sender's `AudioStatePublisher` begins a
/// lifetime and constant for the whole of it. §4.4's `revision` is "per sender per session", and this is
/// the only thing on the wire that says *which* session — so a `revision` is comparable only against
/// another one carrying the same epoch.
///
/// **Why a new value and not an existing one** (ADR-021 Amendment A7 §2). `peer_id` is durable across a
/// process restart; `session_id` is minted per *handshake*, so it changes on an ordinary reconnect the
/// publisher deliberately survives; `conn_tiebreak` lives for the `ControlSessionManager` instance and is
/// not reset when a discovery session is; and the receiver's own `authenticationGeneration` answers a
/// question about a *connection*, not about the peer's counter. Reusing one random value for two jobs is
/// the mistake `ConnTiebreak`'s own documentation warns about, so this is a distinct type as well as a
/// distinct value.
///
/// Never persisted, never derived from `peer_id`, `session_id` or the identity key. Redacted to 6 hex in
/// logs, exactly as `conn_tiebreak` and `voice_session_id` are.
public struct AudioStateEpoch: Hashable, Sendable, CustomStringConvertible {
    public let value: String

    public init(_ value: String) {
        precondition(AudioStateEpoch.isValid(value), "AudioStateEpoch must be 32 lowercase hex characters")
        self.value = value
    }

    public var description: String { "epoch:\(value.prefix(audioStateEpochRedactedPrefixLen))\u{2026}" }

    /// Non-trapping constructor for a value that arrives **off the wire**. Uppercase hex is rejected
    /// rather than normalised — one canonical form, as for `voice_session_id`.
    public static func parse(_ value: String) -> AudioStateEpoch? {
        isValid(value) ? AudioStateEpoch(value) : nil
    }

    private static func isValid(_ s: String) -> Bool {
        s.count == 32 && s.allSatisfy { $0.isHexDigit && !$0.isUppercase }
    }
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
    /// Strictly increasing per sender per session (§4.4). A receiver drops a lower or equal value — but
    /// **only against a `revisionEpoch` that matches**, because a number from one sender lifetime orders
    /// nothing against a number from another.
    public var revision: Int64
    /// Which of the sender's `revision` namespaces `revision` belongs to (§4.4, ADR-021 Amendment A7).
    /// Constant for one sender lifetime; a value the receiver has not seen means the sender's counter
    /// restarted and the old floor no longer applies to it.
    public var revisionEpoch: AudioStateEpoch
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
        revisionEpoch: AudioStateEpoch,
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
        self.revisionEpoch = revisionEpoch
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
        revisionEpoch: AudioStateEpoch,
        snapshot: AudioRouteSnapshot,
        intercomMode: IntercomMode
    ) -> AudioStateMessage {
        AudioStateMessage(
            revision: revision,
            revisionEpoch: revisionEpoch,
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
    /// `revision_epoch` was present and a string, but not 32 lowercase hex (§4.4, `AudioStateEpoch`).
    case malformedRevisionEpoch = "MALFORMED_REVISION_EPOCH"
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
    public static let fieldRevisionEpoch = "revision_epoch"
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
        fieldRevisionEpoch,
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
            fieldRevisionEpoch: .string(message.revisionEpoch.value),
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

        guard let epochText = audioStateStringField(payload, fieldRevisionEpoch) else {
            return missingOrWrongType(payload, fieldRevisionEpoch)
        }
        guard let revisionEpoch = AudioStateEpoch.parse(epochText) else {
            return .rejected(.malformedRevisionEpoch)
        }

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
                revisionEpoch: revisionEpoch,
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
///
/// **`epoch` is what makes "within a session" checkable by the receiver** (ADR-021 Amendment A7). The
/// counter restarts only through `resetForNewSession`, which takes a *fresh* epoch, so every message this
/// publisher has ever produced under one epoch is ordered against every other — and a message from a
/// previous lifetime is recognisably from a previous lifetime rather than merely numerically small.
/// Supplying the epoch rather than minting one keeps this type pure (CLAUDE.md rule 9): the CSPRNG lives
/// in each platform's `AudioStateEpochGenerator`.
public struct AudioStatePublisher: Sendable {
    private var epoch: AudioStateEpoch
    private var revision: Int64
    private var last: AudioStateMessage?

    public init(epoch: AudioStateEpoch, revision: Int64 = 0) {
        self.epoch = epoch
        self.revision = revision
        self.last = nil
    }

    /// The last message `next` or `forceNext` actually produced, or nil before the first one.
    public var published: AudioStateMessage? { last }

    public var currentRevision: Int64 { revision }

    /// The lifetime every message this publisher produces is currently stamped with.
    public var currentEpoch: AudioStateEpoch { epoch }

    /// - Returns: the message to send, or nil when this state is identical to the last published one apart
    ///   from its revision — in which case nothing is sent and the revision does not move.
    public mutating func next(
        snapshot: AudioRouteSnapshot,
        intercomMode: IntercomMode
    ) -> AudioStateMessage? {
        let candidate = AudioStateMessage.from(
            revision: revision + 1,
            revisionEpoch: epoch,
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
            revisionEpoch: epoch,
            snapshot: snapshot,
            intercomMode: intercomMode
        )
        last = message
        return message
    }

    /// Begins a new sender lifetime: the counter restarts at 0 and every message from here on names
    /// `epoch` instead of the old one.
    ///
    /// A new **discovery** session, not a new connection — §4.4's `revision` is per sender per session and
    /// is deliberately *not* reset by a duplicate-connection resolution, a control reconnect or a voice
    /// rebuild. The epoch moves with the counter and only with it, which is the whole of the contract: two
    /// messages are comparable exactly when their epochs match.
    ///
    /// - Parameter epoch: a value that has never been used before — see each platform's
    ///   `AudioStateEpochGenerator`. Reusing one would tell a receiver that a restarted counter was a
    ///   continuation of the old one.
    public mutating func resetForNewSession(epoch: AudioStateEpoch) {
        self.epoch = epoch
        revision = 0
        last = nil
    }
}

/// Owns the receiver's side of PROTOCOL §4.4's revision rule — **and of which sender lifetime that rule
/// is being applied within** (ADR-021 Amendment A7).
///
/// "Receiver drops a lower revision" is implemented as "drops anything not strictly greater", which also
/// drops an exact retransmit. Pure and mirrored, so a reordering bug fails a laptop test rather than
/// showing up as a peer's route apparently going backwards on a ride.
///
/// **A revision floor belongs to exactly one `AudioStateEpoch`.** This object deliberately outlives a
/// control-session boundary — §4.4's `revision` keeps climbing across a reconnect, and keeping the floor
/// is what makes a delayed frame from *before* that reconnect still refusable. But the floor says nothing
/// at all about a sender whose counter restarted, and before this amendment it was applied to one anyway:
/// a peer that restarted its process, or merely left and re-entered discovery, came back at `revision` 1
/// and had every genuine message dropped until it climbed past the dead lifetime's number. So:
///
/// - **same epoch** — §4.4's rule, unchanged: strictly greater, or dropped as stale.
/// - **an epoch never seen** — a new sender lifetime. Accepted, and the epoch it replaces is recorded as
///   superseded.
/// - **a superseded epoch** — a straggler from a lifetime that has already been replaced. Refused and
///   counted, so solving the first case cannot resurrect a stale route through the second.
///
/// That last rule is *defence in depth*, not the only defence: a new sender lifetime can only begin after
/// that sender has torn its control session down, so a straggler from the old one is also refused one
/// layer earlier by ADR-025's generation gate. The two answer different questions — "which connection
/// authorised this frame" and "which of the sender's counters is this number from" — and this is
/// deliberately the second one only.
public struct AudioStateInbox: Sendable {
    public private(set) var current: AudioStateMessage?
    public private(set) var droppedStale = 0

    /// How many frames were refused because they named a sender lifetime that has already been replaced.
    /// Counted rather than merely dropped: "it never happened" and "it happened and was refused" are
    /// different facts on a diagnostics screen.
    public private(set) var droppedRetiredEpoch = 0

    /// See `maxSupersededEpochs`. Matches ADR-024 Amendment A6's loss-ledger bound, for the same reason.
    public static let maxSupersededEpochs = 8

    /// Epochs this inbox has held and moved on from, oldest first.
    ///
    /// Bounded because its contents come from a peer: an unbounded set would let a sender that rotated its
    /// epoch grow it without limit. `maxSupersededEpochs` is far above what a ride can produce — a new
    /// epoch costs the sender a full control teardown, re-handshake and re-authentication — and the honest
    /// cost of the bound is that a straggler from a lifetime old enough to have been evicted is no longer
    /// refused *here*. ADR-025's generation gate still refuses it.
    private var superseded: [AudioStateEpoch] = []

    public init() {}

    /// - Returns: true if `message` was accepted and `current` now holds it.
    @discardableResult
    public mutating func accept(_ message: AudioStateMessage) -> Bool {
        guard let held = current else {
            current = message
            return true
        }
        if message.revisionEpoch == held.revisionEpoch {
            if message.revision <= held.revision {
                droppedStale += 1
                return false
            }
            current = message
            return true
        }
        if superseded.contains(message.revisionEpoch) {
            droppedRetiredEpoch += 1
            return false
        }
        supersede(held.revisionEpoch)
        current = message
        return true
    }

    private mutating func supersede(_ epoch: AudioStateEpoch) {
        superseded.append(epoch)
        while superseded.count > Self.maxSupersededEpochs { superseded.removeFirst() }
    }

    /// A new *local* session. Everything held belonged to the old one, superseded epochs included — a
    /// lifetime this device is no longer tracking is not a lifetime it can call retired.
    public mutating func reset() {
        current = nil
        droppedStale = 0
        droppedRetiredEpoch = 0
        superseded.removeAll()
    }
}
