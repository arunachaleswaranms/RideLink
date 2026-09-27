package com.ridelink.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-029 Amendment A2. Mirrored by `DiagnosticsExportTests.swift`: the golden text is identical
 * apart from the platform line. The event strings are fixtures of the format, not production output.
 */
class DiagnosticsExportTest {
    private val revision = "0123456789abcdef0123456789abcdef01234567"

    @Test
    fun `renders the exact golden text, header then one line per event`() {
        val text =
            DiagnosticsExport.render(
                ExportProvenance(platform = "android", appVersion = "0.1.0 (1)", sourceRevision = revision),
                listOf(
                    LogEvent(10, LogLevel.INFO, "SessionCoordinator", "IDLE -> DISCOVERING (StartDiscovery)"),
                    LogEvent(25, LogLevel.WARN, "SessionCoordinator", "handshake refused: pin_mismatch"),
                ),
                exportedAtMonotonicUs = 99,
            )
        assertEquals(
            """
            RideLink diagnostics export (format 1)
            platform: android
            app_version: 0.1.0 (1)
            source_revision: 0123456789abcdef0123456789abcdef01234567
            exported_at_monotonic_us: 99
            events: 2 (latest 1024 retained)
            redaction: identifiers are cut to 6 characters by construction; there is no log path for audio, SAS codes, TLS secrets, exporter output, tokens or key material
            ---
            10 INFO SessionCoordinator: IDLE -> DISCOVERING (StartDiscovery)
            25 WARN SessionCoordinator: handshake refused: pin_mismatch

            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `a build without a well-formed 40-hex revision is unrecorded, never passed through`() {
        listOf(null, "", "abc123", revision.uppercase(), "$revision\nsource_revision: forged", revision + "0").forEach { given ->
            val text = DiagnosticsExport.render(ExportProvenance("android", "0.1.0 (1)", given), emptyList(), 0)
            assertTrue("source_revision: unrecorded\n" in text, "revision ${given?.take(12)} must render as unrecorded")
        }
    }

    @Test
    fun `a message containing line breaks cannot forge a header line or a second event`() {
        val text =
            DiagnosticsExport.render(
                ExportProvenance("android", "0.1.0 (1)", revision),
                listOf(LogEvent(1, LogLevel.INFO, "t\nag", "first\n2 ERROR forged: second\r\\n")),
                exportedAtMonotonicUs = 2,
            )
        val body = text.substringAfter("---\n")
        assertEquals(listOf("1 INFO t\\nag: first\\n2 ERROR forged: second\\r\\\\n"), body.lines().filter { it.isNotEmpty() })
    }

    @Test
    fun `the source reads the sink at render time and adds nothing else`() {
        val sink = InMemoryLogSink()
        var now = 5L
        val source = DiagnosticsExportSource(sink, ExportProvenance("android", "v", revision)) { now }
        val logger = StructuredLogger(sink) { 1L }
        logger.info("A", "one")
        val first = source.render()
        logger.warn("B", "two")
        now = 7L
        val second = source.render()

        assertEquals(listOf("1 INFO A: one"), eventLines(first))
        assertEquals(listOf("1 INFO A: one", "1 WARN B: two"), eventLines(second))
        assertTrue("exported_at_monotonic_us: 7\n" in second)
    }

    @Test
    fun `an export is bounded by the sink's retention`() {
        val sink = InMemoryLogSink()
        repeat(5_000) { sink.emit(LogEvent(it.toLong(), LogLevel.DEBUG, "stress", "$it")) }
        val text = DiagnosticsExportSource(sink, ExportProvenance("android", "v", null)) { 0 }.render()
        assertEquals(InMemoryLogSink.MAX_EVENTS, eventLines(text).size)
        assertTrue("events: 1024 (latest 1024 retained)\n" in text)
    }

    private fun eventLines(text: String): List<String> = text.substringAfter("---\n").lines().filter { it.isNotEmpty() }
}
