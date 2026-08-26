import Foundation

/// Structural redaction (CLAUDE.md "Privacy rules"): paths -> basename, peer_id -> first 6 chars,
/// identity_spki_sha256 -> first 6 hex, conn_tiebreak -> first 6 hex.
///
/// These are pure string functions with no dependency on the model layer's value types, so that
/// `RideLinkCore.Logging` never needs to know about `PeerId` etc. The model value types
/// additionally redact their own `description` (defense in depth): even code that never calls
/// this type still cannot accidentally log a full identifier via string interpolation.
public enum Redactor {
    private static let prefixLen = 6

    public static func peerId(_ rawHex16: String) -> String { "peer:\(rawHex16.prefix(prefixLen))…" }

    public static func spkiHash(_ rawSha256Formatted: String) -> String {
        let hex = rawSha256Formatted.hasPrefix("sha256:") ? String(rawSha256Formatted.dropFirst("sha256:".count)) : rawSha256Formatted
        return "spki:\(hex.prefix(prefixLen))…"
    }

    public static func connTiebreak(_ rawHex32: String) -> String { "tiebreak:\(rawHex32.prefix(prefixLen))…" }

    public static func discoveryHandle(_ rawHex32: String) -> String { "dh:\(rawHex32.prefix(prefixLen))…" }

    /// File paths log as basename only, never the full path (which may contain a username).
    public static func path(_ rawPath: String) -> String { (rawPath as NSString).lastPathComponent }
}

public enum LogLevel: Sendable, Equatable {
    case debug
    case info
    case warn
    case error
}

public struct LogEvent: Sendable, Equatable {
    public let monotonicTimestampUs: Int64
    public let level: LogLevel
    public let tag: String
    public let message: String
}

public protocol LogSink: Sendable {
    func emit(_ event: LogEvent)
}

public final class InMemoryLogSink: LogSink, @unchecked Sendable {
    private let lock = NSLock()
    private var storage: [LogEvent] = []

    public init() {}

    public var events: [LogEvent] {
        lock.lock()
        defer { lock.unlock() }
        return storage
    }

    public func emit(_ event: LogEvent) {
        lock.lock()
        defer { lock.unlock() }
        storage.append(event)
    }
}

/// The only logging entry point `RideLinkCore` code should use. There is deliberately no method
/// on this type that accepts a SAS code, a TLS secret, exporter output, or a bulk token: those
/// "have no log path at all" (CLAUDE.md), enforced by the absence of an API rather than a
/// runtime check that could be bypassed.
public final class StructuredLogger: Sendable {
    private let sink: LogSink
    private let monotonicNowUs: @Sendable () -> Int64

    public init(sink: LogSink, monotonicNowUs: @escaping @Sendable () -> Int64) {
        self.sink = sink
        self.monotonicNowUs = monotonicNowUs
    }

    public func debug(_ tag: String, _ message: String) { emit(.debug, tag, message) }
    public func info(_ tag: String, _ message: String) { emit(.info, tag, message) }
    public func warn(_ tag: String, _ message: String) { emit(.warn, tag, message) }
    public func error(_ tag: String, _ message: String) { emit(.error, tag, message) }

    private func emit(_ level: LogLevel, _ tag: String, _ message: String) {
        sink.emit(LogEvent(monotonicTimestampUs: monotonicNowUs(), level: level, tag: tag, message: message))
    }
}
