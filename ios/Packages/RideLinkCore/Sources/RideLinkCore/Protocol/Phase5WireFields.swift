import Foundation

// Non-trapping readers for peer-chosen JSON, shared by `PlaybackCodec` and `QueueCodec` — the
// Phase 5 twin of the per-enum `transferStringField`/`manifestStringField` helpers the earlier
// codecs each keep file-private.
//
// Shared here (rather than duplicated once per codec) precisely because the two Phase 5 codecs
// validate the **same** header fields — `command_seq` and `queue_revision` appear in both families
// — and a bound enforced by one reader and not its copy is exactly the divergence
// `protocol/vectors/` exists to catch. The `phase5` prefix keeps them distinguishable from the
// earlier sets at every call site.

/// Crockford base32, 26 characters — PROTOCOL's ULID shape for `queue_item_id`.
private let phase5UlidAlphabet = Set("0123456789ABCDEFGHJKMNPQRSTVWXYZ")

func phase5IsUlid(_ value: String) -> Bool {
    value.count == 26 && value.allSatisfy { phase5UlidAlphabet.contains($0) }
}

/// Non-trapping `PeerId` construction for a value that arrives off the wire — `PeerId`'s own
/// validation is right for our values, where a malformed one is a bug, but a peer chooses what it
/// sends and must not be able to kill a read loop with one bad frame.
func phase5ParsePeerId(_ value: String) -> PeerId? { PeerId.parse(value) }

func phase5StringField(_ payload: [String: JSONValue], _ key: String) -> String? {
    if case .string(let value)? = payload[key] { return value }
    return nil
}

func phase5LongField(_ payload: [String: JSONValue], _ key: String) -> Int64? {
    guard case .number(let value)? = payload[key] else { return nil }
    return Int64(exactly: value)
}

func phase5IntField(_ payload: [String: JSONValue], _ key: String) -> Int? {
    guard case .number(let value)? = payload[key] else { return nil }
    return Int(exactly: value)
}

func phase5DoubleField(_ payload: [String: JSONValue], _ key: String) -> Double? {
    guard case .number(let value)? = payload[key] else { return nil }
    return value
}

func phase5BoolField(_ payload: [String: JSONValue], _ key: String) -> Bool? {
    if case .bool(let value)? = payload[key] { return value }
    return nil
}

/// Distinguishes "absent" from "explicitly `null`" from "present", which `PLAYBACK_STATE`'s two
/// nullable identity fields need — the same three-way distinction `protocol/vectors/audio-state/`
/// already pins for `AUDIO_STATE`'s nullable fields.
///
/// A present-but-wrong-typed value reports `.missing` so the caller's own `missingOrWrongType` can
/// see the key is there and answer `wrongFieldType`.
enum Phase5NullableString: Equatable {
    case missing
    case explicitNull
    case present(String)
}

func phase5NullableStringField(_ payload: [String: JSONValue], _ key: String) -> Phase5NullableString {
    guard let entry = payload[key] else { return .missing }
    if case .null = entry { return .explicitNull }
    if case .string(let value) = entry { return .present(value) }
    return .missing
}
