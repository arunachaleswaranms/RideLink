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

        /**
         * Non-throwing constructor for values that arrive **off the wire**
         * (`HELLO.identity_spki_sha256`, a persisted trusted-peer record). The primary
         * constructor's `require` is right for our own values, where a malformed one is a bug —
         * but a peer chooses what it sends, so throwing there would let a remote peer kill a
         * coroutine with one malformed frame. Returns null instead; the caller answers
         * `ERROR/malformed_frame`.
         */
        fun parse(value: String): SpkiHash? = if (FORMAT.matches(value)) SpkiHash(value) else null
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

/**
 * SHA-256 of the whole file — the authoritative track identity (ADR-005).
 * Format: `"sha256:"` + 64 lowercase hex characters (PROTOCOL §8.1's manifest entry shape).
 *
 * Not redacted: unlike [SpkiHash]/[ConnTiebreak], a content hash names no peer and identifies no
 * security material — CLAUDE.md's logging redaction table does not list it, and a music file's
 * hash reveals nothing about the person carrying it.
 */
@JvmInline
value class ContentHash(
    val value: String,
) {
    init {
        require(FORMAT.matches(value)) { "ContentHash must be sha256: followed by 64 lowercase hex characters" }
    }

    val hex: String get() = value.removePrefix("sha256:")

    companion object {
        private val FORMAT = Regex("^sha256:[0-9a-f]{64}$")

        /**
         * Non-throwing constructor for a value read back from storage or computed from possibly
         * unexpected input. Phase 3's indexer runs over arbitrary local files (rule: "even local
         * files are untrusted input"), so a malformed persisted row must not crash a read the way a
         * `require`-throwing primary constructor would.
         */
        fun parse(value: String): ContentHash? = if (FORMAT.matches(value)) ContentHash(value) else null
    }
}

/**
 * `SHA-256(size_bytes ‖ first 64 KiB ‖ last 64 KiB)` (ADR-005) — the cheap tier computed at scan
 * time, before the authoritative [ContentHash] is known. Same wire format as [ContentHash]
 * (PROTOCOL §8.1: `"quick_id": "sha256:77bd…"`) and used as the stable local index key: it survives
 * a rename, unlike a path, and is available immediately, unlike the full hash.
 */
@JvmInline
value class QuickId(
    val value: String,
) {
    init {
        require(FORMAT.matches(value)) { "QuickId must be sha256: followed by 64 lowercase hex characters" }
    }

    companion object {
        private val FORMAT = Regex("^sha256:[0-9a-f]{64}$")

        fun parse(value: String): QuickId? = if (FORMAT.matches(value)) QuickId(value) else null
    }
}
