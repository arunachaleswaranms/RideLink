package com.ridelink.core.model

/** Matches CLAUDE.md's logging redaction rule: identifiers show only their first 6 characters. */
private const val REDACTED_PREFIX_LEN = 6

/**
 * Durable peer identifier: 16 lowercase hex characters, assigned at pairing.
 *
 * `toString()` is redacted by construction (CLAUDE.md logging rule: peer_id -> first 6 chars),
 * so an accidental `println(peerId)` or string-templated log call cannot leak the full value.
 * Code that genuinely needs the full value (protocol encoding, storage) must read [value]
 * explicitly.
 */
@JvmInline
value class PeerId(
    val value: String,
) {
    init {
        require(HEX16.matches(value)) { "PeerId must be 16 lowercase hex characters" }
    }

    override fun toString(): String = "peer:${value.take(REDACTED_PREFIX_LEN)}…"

    companion object {
        private val HEX16 = Regex("^[0-9a-f]{16}$")
    }
}

/** Session identifier: a ULID string, regenerated per fresh CONNECTING, preserved across RECONNECTING. */
@JvmInline
value class SessionId(
    val value: String,
)

/**
 * `identity_spki_sha256` — the only pinned identity in the system (ADR-012).
 * Format: `"sha256:"` + 64 lowercase hex characters.
 *
 * `toString()` is redacted to the first 6 hex characters, matching the CLAUDE.md logging rule.
 */
@JvmInline
value class SpkiHash(
    val value: String,
) {
    init {
        require(FORMAT.matches(value)) { "SpkiHash must be sha256: followed by 64 lowercase hex characters" }
    }

    val hex: String get() = value.removePrefix("sha256:")

    override fun toString(): String = "spki:${hex.take(REDACTED_PREFIX_LEN)}…"

    companion object {
        private val FORMAT = Regex("^sha256:[0-9a-f]{64}$")
    }
}

/**
 * Ephemeral per-process, per-discovery-session random value used only for PROTOCOL §4.2 /
 * ADR-015 duplicate-connection resolution. Never persisted, never derived from [PeerId] or
 * [SpkiHash] (see ADR-015 "Why conn_tiebreak and not peer_id").
 */
@JvmInline
value class ConnTiebreak(
    val value: String,
) {
    init {
        require(HEX32.matches(value)) { "ConnTiebreak must be 32 lowercase hex characters" }
    }

    override fun toString(): String = "tiebreak:${value.take(REDACTED_PREFIX_LEN)}…"

    companion object {
        private val HEX32 = Regex("^[0-9a-f]{32}$")
    }
}

/** SHA-256 of the whole file — the authoritative track identity (ADR-005). */
@JvmInline
value class ContentHash(
    val value: String,
)
