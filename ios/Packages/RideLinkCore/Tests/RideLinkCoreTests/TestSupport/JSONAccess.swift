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
    func hasKey(_ key: String) -> Bool { self[key] != nil }
}
