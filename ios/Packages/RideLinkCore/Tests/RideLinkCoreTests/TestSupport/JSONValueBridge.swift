import Foundation

@testable import RideLinkCore

/// Converts `JSONSerialization`'s `Any` tree into the `JSONValue` a codec takes.
///
/// `NSNull` must survive as `.null` rather than becoming an absent key, since several Phase 5 rows
/// distinguish "sent as null" from "not sent at all" — `PLAYBACK_STATE.track_hash` and
/// `QUEUE_SNAPSHOT.current_index` both do, and the codecs answer differently for the two.
///
/// The same conversion is written privately inside `TransferMessagesVectorTests`; this shared copy
/// exists so the four Phase 5 vector suites do not repeat it four more times.
enum JSONValueBridge {
    static func payload(_ raw: [String: Any]) -> [String: JSONValue] {
        raw.mapValues(value)
    }

    static func value(_ raw: Any) -> JSONValue {
        switch raw {
        case is NSNull: return .null
        case let string as String: return .string(string)
        case let number as NSNumber:
            if CFGetTypeID(number) == CFBooleanGetTypeID() { return .bool(number.boolValue) }
            return .number(number.doubleValue)
        case let array as [Any]: return .array(array.map(value))
        case let object as [String: Any]: return .object(object.mapValues(value))
        default: return .null
        }
    }

    /// Structural equality by *value*, so `9007199254740991` and `9.007199254740991e15` compare
    /// equal — Swift necessarily produces the second, and a textual comparison would make the shared
    /// vector file un-shareable between the platforms.
    static func equal(_ expected: JSONValue, _ actual: JSONValue) -> Bool {
        switch (expected, actual) {
        case (.null, .null): return true
        case (.bool(let a), .bool(let b)): return a == b
        case (.number(let a), .number(let b)): return a == b
        case (.string(let a), .string(let b)): return a == b
        case (.array(let a), .array(let b)):
            return a.count == b.count && zip(a, b).allSatisfy(equal)
        case (.object(let a), .object(let b)):
            return Set(a.keys) == Set(b.keys) && a.allSatisfy { key, value in equal(value, b[key]!) }
        default: return false
        }
    }

    /// True if `key` appears anywhere in the tree — used to prove an encoder never emits a field the
    /// wire shape no longer has (ADR-024 §6's removal of `QUEUE_SNAPSHOT.status`).
    static func containsKeyAnywhere(_ value: JSONValue, _ key: String) -> Bool {
        switch value {
        case .object(let object):
            return object.keys.contains(key) || object.values.contains { containsKeyAnywhere($0, key) }
        case .array(let array):
            return array.contains { containsKeyAnywhere($0, key) }
        default:
            return false
        }
    }
}
