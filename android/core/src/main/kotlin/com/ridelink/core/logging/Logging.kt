package com.ridelink.core.logging

/**
 * Structural redaction (CLAUDE.md "Privacy rules"): paths -> basename, peer_id -> first 6 chars,
 * identity_spki_sha256 -> first 6 hex, conn_tiebreak -> first 6 hex.
 *
 * These are pure string functions with no dependency on the model layer's value types, so that
 * `core.logging` never needs to know about [com.ridelink.core.model.PeerId] etc. The model
 * value types additionally redact their own `toString()` (defense in depth): even code that
 * never calls this object still cannot accidentally log a full identifier via string
 * interpolation.
 */
object Redactor {
    private const val PREFIX_LEN = 6

    fun peerId(rawHex16: String): String = "peer:${rawHex16.take(PREFIX_LEN)}…"

    fun spkiHash(rawSha256Formatted: String): String {
        val hex = rawSha256Formatted.removePrefix("sha256:")
        return "spki:${hex.take(PREFIX_LEN)}…"
    }

    fun connTiebreak(rawHex32: String): String = "tiebreak:${rawHex32.take(PREFIX_LEN)}…"

    fun discoveryHandle(rawHex32: String): String = "dh:${rawHex32.take(PREFIX_LEN)}…"

    /** File paths log as basename only, never the full path (which may contain a username). */
    fun path(rawPath: String): String = rawPath.substringAfterLast('/').substringAfterLast('\\')
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEvent(
    val monotonicTimestampUs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

fun interface LogSink {
    fun emit(event: LogEvent)
}

class InMemoryLogSink : LogSink {
    private val _events = mutableListOf<LogEvent>()
    val events: List<LogEvent> get() = _events.toList()

    override fun emit(event: LogEvent) {
        _events.add(event)
    }
}

/**
 * The only logging entry point `core` code should use. There is deliberately no method on this
 * class, and no [LogField] variant, that accepts a SAS code, a TLS secret, exporter output, or a
 * bulk token: those "have no log path at all" (CLAUDE.md), enforced by the absence of an API
 * rather than a runtime check that could be bypassed.
 */
class StructuredLogger(
    private val sink: LogSink,
    private val monotonicNowUs: () -> Long,
) {
    fun debug(
        tag: String,
        message: String,
    ) = emit(LogLevel.DEBUG, tag, message)

    fun info(
        tag: String,
        message: String,
    ) = emit(LogLevel.INFO, tag, message)

    fun warn(
        tag: String,
        message: String,
    ) = emit(LogLevel.WARN, tag, message)

    fun error(
        tag: String,
        message: String,
    ) = emit(LogLevel.ERROR, tag, message)

    private fun emit(
        level: LogLevel,
        tag: String,
        message: String,
    ) {
        sink.emit(LogEvent(monotonicNowUs(), level, tag, message))
    }
}
