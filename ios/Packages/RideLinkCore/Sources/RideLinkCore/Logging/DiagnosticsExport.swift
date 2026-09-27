import Foundation

/// What an export says about the build that produced it (ADR-029 Amendment A2). It is build
/// provenance and nothing else: no device name, no peer, no identity and no wall-clock time.
///
/// `sourceRevision` is the 40-hex Git commit a sideload build was made from. Anything that is not
/// exactly that — a missing value on an IDE build, or a malformed one — is rendered as
/// `unrecorded`, so a build of unknown provenance can never pass for a qualified one.
public struct ExportProvenance: Sendable, Equatable {
    public let platform: String
    public let appVersion: String
    public let sourceRevision: String?

    public init(platform: String, appVersion: String, sourceRevision: String?) {
        self.platform = platform
        self.appVersion = appVersion
        self.sourceRevision = sourceRevision
    }

    public var recordedSourceRevision: String? {
        guard let revision = sourceRevision, revision.utf8.count == 40,
              revision.utf8.allSatisfy({ (0x30...0x39).contains($0) || (0x61...0x66).contains($0) })
        else { return nil }
        return revision
    }
}

/// NFR-08's export: the redacted process log (ARCHITECTURE §11 item 3), rendered as plain text for
/// the platform share sheet. Mirrors Android's `core.logging.DiagnosticsExportSource` line for line.
///
/// It reads `InMemoryLogSink` and nothing else. It adds no data source, so it adds no log path:
/// SAS codes, TLS secrets, exporter output, tokens and key material have none today (the absence of
/// a `StructuredLogger` API for them), and an export cannot give them one.
public struct DiagnosticsExportSource: Sendable {
    private let sink: InMemoryLogSink
    private let provenance: ExportProvenance
    private let monotonicNowUs: @Sendable () -> Int64

    public init(sink: InMemoryLogSink, provenance: ExportProvenance, monotonicNowUs: @escaping @Sendable () -> Int64) {
        self.sink = sink
        self.provenance = provenance
        self.monotonicNowUs = monotonicNowUs
    }

    public func render() -> String {
        DiagnosticsExport.render(provenance: provenance, events: sink.events, exportedAtMonotonicUs: monotonicNowUs())
    }
}

public enum DiagnosticsExport {
    public static let formatVersion = 1
    public static let fileName = "ridelink-diagnostics.txt"

    public static func render(provenance: ExportProvenance, events: [LogEvent], exportedAtMonotonicUs: Int64) -> String {
        var text = "RideLink diagnostics export (format \(formatVersion))\n"
        text += "platform: \(provenance.platform)\n"
        text += "app_version: \(provenance.appVersion)\n"
        text += "source_revision: \(provenance.recordedSourceRevision ?? "unrecorded")\n"
        text += "exported_at_monotonic_us: \(exportedAtMonotonicUs)\n"
        text += "events: \(events.count) (latest \(InMemoryLogSink.capacity) retained)\n"
        text += redactionNote + "\n"
        text += "---\n"
        for event in events {
            text += "\(event.monotonicTimestampUs) \(levelName(event.level)) \(oneLine(event.tag)): \(oneLine(event.message))\n"
        }
        return text
    }

    /// Android's `LogLevel.name`, so both platforms' exports read identically.
    private static func levelName(_ level: LogLevel) -> String {
        switch level {
        case .debug: "DEBUG"
        case .info: "INFO"
        case .warn: "WARN"
        case .error: "ERROR"
        }
    }

    /// One event is one line, so a message can never forge a header or a second event.
    private static func oneLine(_ text: String) -> String {
        text.replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\r", with: "\\r")
            .replacingOccurrences(of: "\n", with: "\\n")
    }

    private static let redactionNote =
        "redaction: identifiers are cut to 6 characters by construction; there is no log path for "
            + "audio, SAS codes, TLS secrets, exporter output, tokens or key material"
}
