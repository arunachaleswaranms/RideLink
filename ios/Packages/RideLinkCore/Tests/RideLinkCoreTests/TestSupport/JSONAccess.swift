import Foundation

/// Small ergonomic accessors over the `Any` tree returned by `JSONSerialization`, since the
/// vector files are read generically (their shape differs per vector, mirroring the flexibility
/// `kotlinx.serialization.json.JsonElement` gives the Kotlin side).
extension Dictionary where Key == String, Value == Any {
    func str(_ key: String) -> String { self[key] as! String } // swiftlint:disable:this force_cast
    func strOpt(_ key: String) -> String? { self[key] as? String }
    func dict(_ key: String) -> [String: Any] { self[key] as! [String: Any] } // swiftlint:disable:this force_cast
    func dictOpt(_ key: String) -> [String: Any]? { self[key] as? [String: Any] }
    func array(_ key: String) -> [Any] { self[key] as! [Any] } // swiftlint:disable:this force_cast
    func int(_ key: String) -> Int { (self[key] as! NSNumber).intValue } // swiftlint:disable:this force_cast
    func intOpt(_ key: String) -> Int? { (self[key] as? NSNumber)?.intValue }
    func boolVal(_ key: String) -> Bool { (self[key] as! NSNumber).boolValue } // swiftlint:disable:this force_cast
    func boolOpt(_ key: String) -> Bool? { (self[key] as? NSNumber)?.boolValue }
    func int64(_ key: String) -> Int64 { (self[key] as! NSNumber).int64Value } // swiftlint:disable:this force_cast
    func int64Opt(_ key: String) -> Int64? { (self[key] as? NSNumber)?.int64Value }
    func doubleVal(_ key: String) -> Double { (self[key] as! NSNumber).doubleValue } // swiftlint:disable:this force_cast
    func hasKey(_ key: String) -> Bool { self[key] != nil }

    /// A value that must be **present** and may be JSON `null` — the distinction a vector turns on
    /// when null is a meaning rather than an omission (STATUS §4 problem 61's control-lifetime rows).
    ///
    /// `JSONSerialization` maps JSON `null` to `NSNull`, which `hasKey` sees and `int64Opt` does not,
    /// so the two together separate "the row said nothing" from "the row said nobody". A missing key
    /// is a failure rather than a nil, so a future row that forgets to say which lifetime it is about
    /// fails the build instead of silently asserting the wrong thing.
    func requiredInt64Opt(_ key: String) -> Int64? {
        precondition(hasKey(key), "vector row is missing the required key '\(key)'")
        return int64Opt(key)
    }

    func requiredInt64(_ key: String) -> Int64 {
        guard let value = requiredInt64Opt(key) else {
            preconditionFailure("vector row's '\(key)' may not be null")
        }
        return value
    }
}
