package com.ridelink.data.trustedpeers

import com.ridelink.core.model.PeerId
import java.io.File
import java.security.SecureRandom

/**
 * This device's own durable `peer_id` (PROTOCOL §2: "16-hex string, durable, assigned at pairing").
 *
 * Self-assigned on first run rather than handed out by whichever peer happened to accept the first
 * connection, because with two symmetric peers there is no authority to assign one and inventing
 * a negotiation would add a failure mode for nothing. PROTOCOL §2's "the sender's provisional
 * `peer_id` proposal" before pairing and the durable value after are then the same 16 hex
 * characters — the pairing step fixes the peer's *view* of it, which is what the trusted-peer
 * record stores.
 *
 * **Not a secret and not an identity.** Trust is `identity_spki_sha256` (ADR-012); `peer_id` is
 * only a stable label to hang a pin on and to elect a leader by (ADR-010). It is CSPRNG-generated
 * rather than derived from anything about the device, so it carries no hardware or user
 * information, and it never appears in an mDNS TXT record (ADR-002 Amendment A1).
 */
class LocalPeerIdStore(
    private val file: File,
    private val random: SecureRandom = SecureRandom(),
) {
    private val lock = Any()

    fun loadOrCreate(): PeerId =
        synchronized(lock) {
            val existing = runCatching { file.readText().trim() }.getOrNull()
            if (existing != null && FORMAT.matches(existing)) return PeerId(existing)
            val fresh = generate()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(fresh.value)
            if (!temporary.renameTo(file)) temporary.delete()
            fresh
        }

    private fun generate(): PeerId {
        val bytes = ByteArray(PEER_ID_BYTES)
        random.nextBytes(bytes)
        return PeerId(bytes.joinToString("") { "%02x".format(it) })
    }

    private companion object {
        const val PEER_ID_BYTES = 8 // 16 hex characters
        val FORMAT = Regex("^[0-9a-f]{16}$")
    }
}
