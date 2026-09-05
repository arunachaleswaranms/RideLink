package com.ridelink.network.transfer

import com.ridelink.core.model.TransferId
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * ADR-023 §2/§3 — single-use, 30 s TTL, generation-scoped bulk-transfer authorization tokens.
 *
 * A token is minted per [com.ridelink.core.protocol.TransferMessage.Offer], delivered only inside
 * the already-authenticated control channel, and consumed exactly once by the bulk connection it
 * authorises. It is tagged with the [com.ridelink.network.control.ControlSessionManager]
 * generation active at mint time; [validateAndConsume] fails a token whose generation is not the
 * *current* one, which is what makes a reconnect's re-authentication invalidate every outstanding
 * token without an explicit sweep (ADR-023 §3).
 */
class BulkTokenTable(
    private val monotonicNowUs: () -> Long,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    private data class Entry(
        val token: String,
        val generation: Long,
        val mintedAtMonoUs: Long,
        val consumed: Boolean = false,
    )

    private val entries = ConcurrentHashMap<TransferId, Entry>()

    /**
     * 32 CSPRNG bytes, hex-encoded (64 lowercase hex characters) — PROTOCOL §8.2.
     *
     * Closure-audit Finding M: a [transferId] is minted fresh by the requester (ADR-023 §2), so a
     * second `issue` for one already carrying a still-live, unconsumed entry means a peer resent
     * (or replayed) a `TRANSFER_REQUEST` reusing an id it already used. Silently overwriting that
     * entry would invalidate a still-outstanding token with no signal to whoever was about to
     * consume it. [tryIssue] is the safe entry point; [issue] is kept for callers that have
     * already decided a collision cannot happen and would rather fail loudly than check.
     *
     * @return the new token, or `null` if [transferId] already has a live (unconsumed,
     *   not-yet-expired) entry — the caller must not construct/send an offer in that case.
     */
    fun tryIssue(
        transferId: TransferId,
        generation: Long,
    ): String? {
        val existing = entries[transferId]
        if (existing != null && !existing.consumed && monotonicNowUs() - existing.mintedAtMonoUs <= TTL_US) {
            return null
        }
        return issue(transferId, generation)
    }

    /** See [tryIssue] — this overwrites unconditionally, matching the pre-audit behaviour. */
    fun issue(
        transferId: TransferId,
        generation: Long,
    ): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        entries[transferId] = Entry(token, generation, monotonicNowUs())
        return token
    }

    /**
     * Single-use: a second call for the same [transferId] fails even with the right token,
     * because [entries] is updated by compare-and-swap to the consumed form before any bytes are
     * streamed. [currentGeneration] must be read fresh at validation time, not cached — a stale
     * caller comparing against an old generation would defeat the whole guard.
     */
    @Suppress("ReturnCount") // one early-out per ADR-023 §2/§3 validation rule, in spec order
    fun validateAndConsume(
        transferId: TransferId,
        presentedToken: String,
        currentGeneration: Long,
    ): Boolean {
        val entry = entries[transferId] ?: return false
        if (entry.consumed) return false
        if (entry.generation != currentGeneration) {
            entries.remove(transferId, entry)
            return false
        }
        if (monotonicNowUs() - entry.mintedAtMonoUs > TTL_US) {
            entries.remove(transferId, entry)
            return false
        }
        if (!constantTimeEquals(entry.token, presentedToken)) return false
        return entries.replace(transferId, entry, entry.copy(consumed = true))
    }

    /** Called on every reconnect/re-authentication — the new generation makes old entries dead weight. */
    fun sweepBelow(currentGeneration: Long) {
        entries.entries.removeIf { it.value.generation < currentGeneration }
    }

    fun clear() {
        entries.clear()
    }

    private companion object {
        const val TOKEN_BYTES = 32
        const val TTL_US = 30_000_000L
    }
}

/**
 * Closure-audit Finding L: a hex-encoded, single-use bulk-transfer token is a security-sensitive
 * authorization secret (ADR-023 §2), so its comparison should not leak timing information about
 * how many leading characters matched, even though the practical severity is low — this check
 * runs over an already TLS/SPKI-authenticated local link, not across the open Internet. Fixed-time
 * in the number of characters compared: every character pair is compared, and the result is
 * accumulated with bitwise OR rather than short-circuiting on the first mismatch.
 */
private fun constantTimeEquals(
    a: String,
    b: String,
): Boolean {
    if (a.length != b.length) return false
    var diff = 0
    for (i in a.indices) {
        diff = diff or (a[i].code xor b[i].code)
    }
    return diff == 0
}
