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

    /** 32 CSPRNG bytes, hex-encoded (64 lowercase hex characters) — PROTOCOL §8.2. */
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
        if (entry.token != presentedToken) return false
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
