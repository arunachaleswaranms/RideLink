import Foundation

/// The six-digit SAS, exact construction per PROTOCOL §4.5.1. Pure integer/byte math only — the
/// TLS exporter call itself is a Phase 1b concern (ADR-007 Amendment A1); this function takes the
/// already-exported bytes.
public enum Sas {
    /// PROTOCOL §4.5.1: ASCII, 24 bytes, no trailing NUL. Lives here rather than in each
    /// platform's transport so the two cannot drift — a single differing character produces two
    /// different six-digit codes, which to the users is indistinguishable from a
    /// man-in-the-middle.
    public static let exporterLabel = "EXPORTER-RideLink-SAS-v1"

    /// PROTOCOL §4.5.1: a fixed 32 bytes are exported everywhere, even though only the first 4 are
    /// used, so the exporter call itself is identical on both platforms. Bytes 4…31 are reserved.
    public static let exporterLengthBytes = 32

    private static let modulus: UInt32 = 1_000_000
    private static let sas6Digits = 6
    private static let exporterPrefixBytes = 4

    /// - Parameter exporterOutput: at least 4 bytes; only the first 4 (big-endian) are used.
    ///   Bytes beyond index 3 are accepted and ignored (protocol/vectors/sas/ "tail-bytes-ignored").
    public static func deriveSas6(_ exporterOutput: [UInt8]) -> String {
        precondition(exporterOutput.count >= exporterPrefixBytes, "exporter output must be at least 4 bytes")
        let n =
            (UInt32(exporterOutput[0]) << 24) |
            (UInt32(exporterOutput[1]) << 16) |
            (UInt32(exporterOutput[2]) << 8) |
            UInt32(exporterOutput[3])
        let value = Int(n % modulus)
        return String(format: "%0\(sas6Digits)d", value)
    }
}
