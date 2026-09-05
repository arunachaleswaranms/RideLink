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
 * (PROTOCOL §8.1: `"quick_id": "sha256:77bd…"`) and available immediately, unlike the full hash.
 *
 * **Not authoritative identity, and never used to decide two files are the same content** (ADR-005
 * Amendment A1, this phase's closure-audit hardening pass). Two files over 128 KiB with the same
 * size and identical first/last 64 KiB but a different middle would collide onto the same
 * [QuickId] while being genuinely different content — not a SHA-256 collision, just a consequence
 * of only sampling part of the file. `QuickId`'s only legitimate roles are indexing/change
 * detection (has *this same, already-known* location's file changed since last seen?) and
 * catalogue display; [LocalEntryId] is the real per-row identity, and [ContentHash] is the only
 * thing authoritative enough to ever justify merging two rows.
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

/**
 * This phone's own local identity for one library row (ADR-005 Amendment A1). Generated once, the
 * moment a location is first indexed, and never recomputed or derived from the file's bytes — unlike
 * [QuickId] (a lossy 128 KiB sample) or [ContentHash] (authoritative but lazy), a [LocalEntryId] has
 * no relationship to content at all, so two distinct files can never collide onto the same one. It
 * is what a player/queue/artwork cache must key on to unambiguously mean *this specific row*, now
 * that [QuickId] is no longer guaranteed unique across rows.
 *
 * Never sent over the wire and never persisted past a reindex-from-scratch — this is local
 * bookkeeping only, the same way [LocalTrackLocation][com.ridelink.core.library.LocalTrackLocation]
 * is.
 */
@JvmInline
value class LocalEntryId(
    val value: String,
) {
    init {
        require(FORMAT.matches(value)) { "LocalEntryId must be a lowercase UUID" }
    }

    companion object {
        private val FORMAT = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        fun parse(value: String): LocalEntryId? = if (FORMAT.matches(value)) LocalEntryId(value) else null
    }
}

/** Crockford base32, 26 characters — the ULID shape PROTOCOL's schema already pins for `session_id`/`msg_id`. */
private val ULID_FORMAT = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")

/**
 * PROTOCOL §8.1 — identifies one manifest synchronisation attempt. Fresh per attempt (ADR-013
 * rule 10: a restart is always from a fresh id, which is what makes a restart unambiguous). Never
 * derived from `manifest_revision` or from library content.
 */
@JvmInline
value class ManifestId(
    val value: String,
) {
    init {
        require(ULID_FORMAT.matches(value)) { "ManifestId must be a 26-character Crockford-base32 ULID" }
    }

    companion object {
        /** Non-throwing constructor for a value that arrives off the wire. */
        fun parse(value: String): ManifestId? = if (ULID_FORMAT.matches(value)) ManifestId(value) else null
    }
}

/**
 * PROTOCOL §8.2 / ADR-023 — identifies one file transfer. Minted by the requester, unpredictable,
 * never derived from [ContentHash] alone (ADR-023 §2/§24 of the brief: a transfer_id must not be
 * guessable from the content it names). Scoped to the control session that issued it — a
 * reconnect's new session never honours an old session's [TransferId] (ADR-023 §3).
 */
@JvmInline
value class TransferId(
    val value: String,
) {
    init {
        require(ULID_FORMAT.matches(value)) { "TransferId must be a 26-character Crockford-base32 ULID" }
    }

    companion object {
        /** Non-throwing constructor for a value that arrives off the wire. */
        fun parse(value: String): TransferId? = if (ULID_FORMAT.matches(value)) TransferId(value) else null
    }
}
