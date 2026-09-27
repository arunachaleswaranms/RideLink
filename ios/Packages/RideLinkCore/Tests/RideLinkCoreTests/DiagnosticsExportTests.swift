import XCTest
@testable import RideLinkCore

/// ADR-029 Amendment A2. Mirrors Android's `DiagnosticsExportTest`; the golden text is identical
/// apart from the platform line.
final class DiagnosticsExportTests: XCTestCase {
    private let revision = "0123456789abcdef0123456789abcdef01234567"

    func testRendersTheExactGoldenTextHeaderThenOneLinePerEvent() {
        let text = DiagnosticsExport.render(
            provenance: ExportProvenance(platform: "ios", appVersion: "0.1.0 (1)", sourceRevision: revision),
            events: [
                LogEvent(monotonicTimestampUs: 10, level: .info, tag: "SessionCoordinator", message: "IDLE -> DISCOVERING (StartDiscovery)"),
                LogEvent(monotonicTimestampUs: 25, level: .warn, tag: "SessionCoordinator", message: "handshake refused: pin_mismatch"),
            ],
            exportedAtMonotonicUs: 99
        )
        XCTAssertEqual(text, """
        RideLink diagnostics export (format 1)
        platform: ios
        app_version: 0.1.0 (1)
        source_revision: 0123456789abcdef0123456789abcdef01234567
        exported_at_monotonic_us: 99
        events: 2 (latest 1024 retained)
        redaction: identifiers are cut to 6 characters by construction; there is no log path for audio, SAS codes, TLS secrets, exporter output, tokens or key material
        ---
        10 INFO SessionCoordinator: IDLE -> DISCOVERING (StartDiscovery)
        25 WARN SessionCoordinator: handshake refused: pin_mismatch

        """)
    }

    func testABuildWithoutAWellFormed40HexRevisionIsUnrecordedNeverPassedThrough() {
        for given in [nil, "", "abc123", revision.uppercased(), "\(revision)\nsource_revision: forged", revision + "0"] {
            let text = DiagnosticsExport.render(
                provenance: ExportProvenance(platform: "ios", appVersion: "0.1.0 (1)", sourceRevision: given),
                events: [],
                exportedAtMonotonicUs: 0
            )
            XCTAssertTrue(text.contains("source_revision: unrecorded\n"), "revision \(given?.prefix(12) ?? "nil") must render as unrecorded")
        }
    }

    func testAMessageContainingLineBreaksCannotForgeAHeaderLineOrASecondEvent() {
        let text = DiagnosticsExport.render(
            provenance: ExportProvenance(platform: "ios", appVersion: "0.1.0 (1)", sourceRevision: revision),
            events: [LogEvent(monotonicTimestampUs: 1, level: .info, tag: "t\nag", message: "first\n2 ERROR forged: second\r\\n")],
            exportedAtMonotonicUs: 2
        )
        XCTAssertEqual(eventLines(text), ["1 INFO t\\nag: first\\n2 ERROR forged: second\\r\\\\n"])
    }

    func testTheSourceReadsTheSinkAtRenderTimeAndAddsNothingElse() {
        let sink = InMemoryLogSink()
        let now = LockedClock(5)
        let source = DiagnosticsExportSource(
            sink: sink,
            provenance: ExportProvenance(platform: "ios", appVersion: "v", sourceRevision: revision),
            monotonicNowUs: { now.value }
        )
        let logger = StructuredLogger(sink: sink, monotonicNowUs: { 1 })
        logger.info("A", "one")
        let first = source.render()
        logger.warn("B", "two")
        now.value = 7
        let second = source.render()

        XCTAssertEqual(eventLines(first), ["1 INFO A: one"])
        XCTAssertEqual(eventLines(second), ["1 INFO A: one", "1 WARN B: two"])
        XCTAssertTrue(second.contains("exported_at_monotonic_us: 7\n"))
    }

    func testAnExportIsBoundedByTheSinksRetention() {
        let sink = InMemoryLogSink()
        for index in 0 ..< 5_000 {
            sink.emit(LogEvent(monotonicTimestampUs: Int64(index), level: .debug, tag: "stress", message: "\(index)"))
        }
        let text = DiagnosticsExportSource(
            sink: sink,
            provenance: ExportProvenance(platform: "ios", appVersion: "v", sourceRevision: nil),
            monotonicNowUs: { 0 }
        ).render()
        XCTAssertEqual(eventLines(text).count, InMemoryLogSink.capacity)
        XCTAssertTrue(text.contains("events: 1024 (latest 1024 retained)\n"))
    }

    private func eventLines(_ text: String) -> [String] {
        guard let body = text.range(of: "---\n") else { return [] }
        return text[body.upperBound...].split(separator: "\n", omittingEmptySubsequences: true).map(String.init)
    }
}

private final class LockedClock: @unchecked Sendable {
    private let lock = NSLock()
    private var current: Int64

    init(_ value: Int64) { current = value }

    var value: Int64 {
        get { lock.lock(); defer { lock.unlock() }; return current }
        set { lock.lock(); current = newValue; lock.unlock() }
    }
}
