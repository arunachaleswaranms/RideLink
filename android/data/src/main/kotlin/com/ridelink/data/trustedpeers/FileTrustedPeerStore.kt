package com.ridelink.data.trustedpeers

import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SpkiHash
import com.ridelink.core.security.PinReplacementRefusedException
import com.ridelink.core.security.TrustedPeer
import com.ridelink.core.security.TrustedPeerStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * [TrustedPeerStore] backed by one small JSON file in the app's private storage
 * (ARCHITECTURE §9.1 `data/trustedpeers/`).
 *
 * **Why plain app-private storage and not an encrypted one.** A trusted-peer record contains no
 * secret: a `peer_id`, an `identity_spki_sha256` (a digest of a *public* key the peer hands to
 * anyone who opens a TLS connection to it), a display name and two timestamps. What must stay
 * secret is the private identity key, and that is never here — it lives in Android Keystore and
 * is never serialised at all (ADR-017 §1). The property this file needs is **integrity**: an
 * attacker who can rewrite a pin can substitute an identity. App-private storage supplies that
 * against every attacker in the threat model (a passive or active attacker on the same Wi-Fi);
 * it does not defend against an attacker who is already running as this app or as root, and
 * neither would encrypting the file with a key that same attacker could also reach. Recorded
 * rather than left implicit.
 *
 * Writes are atomic (temp file + rename), because a half-written trust store on a phone that lost
 * power mid-ride would present as `pin_mismatch` — a security warning for a filesystem event.
 */
class FileTrustedPeerStore(
    private val file: File,
) : TrustedPeerStore {
    private val lock = Any()

    @Volatile
    private var cache: Map<String, TrustedPeer>? = null

    override fun byPeerId(peerId: PeerId): TrustedPeer? = synchronized(lock) { load()[peerId.value] }

    override fun bySpki(identitySpkiSha256: SpkiHash): TrustedPeer? =
        synchronized(lock) { load().values.firstOrNull { it.identitySpkiSha256 == identitySpkiSha256 } }

    override fun all(): List<TrustedPeer> = synchronized(lock) { load().values.toList() }

    override fun remember(peer: TrustedPeer) {
        synchronized(lock) {
            val peers = load().toMutableMap()
            val existing = peers[peer.peerId.value]
            // ADR-012: a stored pin is never silently replaced. That is the difference between
            // "refresh last_seen_at" and "auto re-pair", and only the first is allowed.
            if (existing != null && existing.identitySpkiSha256 != peer.identitySpkiSha256) {
                throw PinReplacementRefusedException(peer.peerId)
            }
            peers[peer.peerId.value] = peer
            persist(peers)
        }
    }

    override fun forget(peerId: PeerId) {
        synchronized(lock) {
            val peers = load().toMutableMap()
            if (peers.remove(peerId.value) != null) persist(peers)
        }
    }

    private fun load(): Map<String, TrustedPeer> {
        cache?.let { return it }
        val loaded =
            if (!file.exists()) {
                emptyMap()
            } else {
                // A corrupt or truncated store is treated as empty rather than fatal: the worst
                // outcome is a re-pair with a fresh SAS, which is safe. Throwing here would brick
                // the app at launch over a damaged file.
                runCatching {
                    JSON.decodeFromString<List<StoredPeer>>(file.readText())
                        .mapNotNull { it.toDomainOrNull() }
                        .associateBy { it.peerId.value }
                }.getOrDefault(emptyMap())
            }
        cache = loaded
        return loaded
    }

    private fun persist(peers: Map<String, TrustedPeer>) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(JSON.encodeToString(peers.values.map(StoredPeer::from)))
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IOException("could not replace the trusted-peer store")
        }
        cache = peers
    }

    /**
     * The on-disk shape, deliberately separate from [TrustedPeer]. Values are read back as plain
     * strings and re-validated through the non-throwing parsers, so a hand-edited or corrupted
     * file drops the bad record instead of crashing at launch — the same wire-safety discipline
     * the control plane applies to a peer-supplied field.
     */
    @Serializable
    private data class StoredPeer(
        val peerId: String,
        val identitySpkiSha256: String,
        val displayName: String,
        val pairedAtEpochSeconds: Long,
        val lastSeenAtEpochSeconds: Long,
    ) {
        fun toDomainOrNull(): TrustedPeer? {
            val id = peerId.takeIf { PEER_ID_FORMAT.matches(it) }?.let(::PeerId) ?: return null
            val spki = SpkiHash.parse(identitySpkiSha256) ?: return null
            return TrustedPeer(id, spki, displayName, pairedAtEpochSeconds, lastSeenAtEpochSeconds)
        }

        companion object {
            private val PEER_ID_FORMAT = Regex("^[0-9a-f]{16}$")

            fun from(peer: TrustedPeer): StoredPeer =
                StoredPeer(
                    peerId = peer.peerId.value,
                    identitySpkiSha256 = peer.identitySpkiSha256.value,
                    displayName = peer.displayName,
                    pairedAtEpochSeconds = peer.pairedAtEpochSeconds,
                    lastSeenAtEpochSeconds = peer.lastSeenAtEpochSeconds,
                )
        }
    }

    private companion object {
        val JSON = Json { prettyPrint = true }
    }
}
