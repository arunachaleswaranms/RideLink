package com.ridelink.core.logging

/**
 * What an export says about the build that produced it (ADR-029 Amendment A2). It is build
 * provenance and nothing else: no device name, no peer, no identity and no wall-clock time.
 *
 * @param sourceRevision the 40-hex Git commit a sideload build was made from. Anything that is not
 *   exactly that — a missing value on an IDE build, or a malformed one — is rendered as
 *   `unrecorded`, so a build of unknown provenance can never pass for a qualified one.
 */
data class ExportProvenance(
    val platform: String,
    val appVersion: String,
    val sourceRevision: String?,
) {
    val recordedSourceRevision: String?
        get() = sourceRevision?.takeIf { SOURCE_REVISION.matches(it) }

    private companion object {
        val SOURCE_REVISION = Regex("^[0-9a-f]{40}$")
    }
}

/**
 * NFR-08's export: the redacted process log (ARCHITECTURE §11 item 3), rendered as plain text for
 * the platform share sheet. Mirrored line for line by `RideLinkCore.DiagnosticsExport`.
 *
 * It reads [InMemoryLogSink] and nothing else. It adds no data source, so it adds no log path:
 * SAS codes, TLS secrets, exporter output, tokens and key material have none today (the absence of
 * a [StructuredLogger] API for them), and an export cannot give them one.
 */
class DiagnosticsExportSource(
    private val sink: InMemoryLogSink,
    private val provenance: ExportProvenance,
    private val monotonicNowUs: () -> Long,
) {
    fun render(): String = DiagnosticsExport.render(provenance, sink.events, monotonicNowUs())
}

object DiagnosticsExport {
    const val FORMAT_VERSION = 1

    fun render(
        provenance: ExportProvenance,
        events: List<LogEvent>,
        exportedAtMonotonicUs: Long,
    ): String =
        buildString {
            append("RideLink diagnostics export (format ").append(FORMAT_VERSION).append(")\n")
            append("platform: ").append(provenance.platform).append('\n')
            append("app_version: ").append(provenance.appVersion).append('\n')
            append("source_revision: ").append(provenance.recordedSourceRevision ?: UNRECORDED).append('\n')
            append("exported_at_monotonic_us: ").append(exportedAtMonotonicUs).append('\n')
            append("events: ").append(events.size).append(" (latest ").append(InMemoryLogSink.MAX_EVENTS)
            append(" retained)\n")
            append(REDACTION_NOTE).append('\n')
            append("---\n")
            events.forEach { event ->
                append(event.monotonicTimestampUs).append(' ')
                append(event.level.name).append(' ')
                append(oneLine(event.tag)).append(": ")
                append(oneLine(event.message)).append('\n')
            }
        }

    /** One event is one line, so a message can never forge a header or a second event. */
    private fun oneLine(text: String): String = text.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n")

    private const val UNRECORDED = "unrecorded"
    private const val REDACTION_NOTE =
        "redaction: identifiers are cut to 6 characters by construction; there is no log path for " +
            "audio, SAS codes, TLS secrets, exporter output, tokens or key material"
}
