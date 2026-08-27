package com.ridelink.core.security

import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SpkiHash

/**
 * Where [TrustedPeer] records live between rides (PROTOCOL §4.5, ADR-012).
 *
 * The interface is in the domain layer and the storage is not, so the pin logic can be exhausted
 * by a laptop test without a device, a file system or a keystore anywhere in the picture.
 *
 * **Nothing stored through this interface is a secret.** A trusted-peer record is a `peer_id`, an
 * `identity_spki_sha256`, a display name and two timestamps — the SPKI hash is a digest of a
 * *public* key, and the peer hands its certificate to anyone who opens a TLS connection to it.
 * What must stay secret is the private key, and that never comes near here: it lives in Android
 * Keystore / the iOS Keychain and is never serialised at all. So an implementation needs
 * *integrity* (an attacker who can rewrite the pin can substitute an identity) rather than
 * confidentiality, and app-private storage supplies it.
 *
 * Implementations must be safe to call from multiple coroutines/threads.
 */
interface TrustedPeerStore {
    /** The pin lookup PROTOCOL §4.1 specifies: by the `peer_id` the peer claimed in HELLO. */
    fun byPeerId(peerId: PeerId): TrustedPeer?

    /** Lookup by identity. Used to answer "have we met this key before, under any name?". */
    fun bySpki(identitySpkiSha256: SpkiHash): TrustedPeer?

    fun all(): List<TrustedPeer>

    /**
     * Records a peer as trusted after a successful SAS confirmation, or refreshes `last_seen_at`
     * on an existing one.
     *
     * Implementations must **not** silently overwrite a stored `identity_spki_sha256` with a
     * different one for the same `peer_id`: that is precisely the "auto re-pair" ADR-012 forbids,
     * and it would turn a `pin_mismatch` into a silent identity substitution. Changing a pin
     * requires [forget] first, which is a user action.
     */
    fun remember(peer: TrustedPeer)

    /** The explicit user action that makes a re-pair (and a fresh SAS on both screens) possible. */
    fun forget(peerId: PeerId)
}

/** Thrown when [TrustedPeerStore.remember] would change a stored pin without an explicit [TrustedPeerStore.forget]. */
class PinReplacementRefusedException(
    peerId: PeerId,
) : IllegalStateException(
        "refusing to replace the stored identity for $peerId; forget the peer and pair again (ADR-012)",
    )

/**
 * A [TrustedPeerStore] with no persistence. The default until a platform store is wired in, and
 * the one every unit test uses.
 *
 * Being in-memory is not a security shortcut — see the interface's note on why these records are
 * not secret — it simply means trust does not survive a process restart, which a test wants and a
 * ride does not.
 */
class InMemoryTrustedPeerStore(
    initial: List<TrustedPeer> = emptyList(),
) : TrustedPeerStore {
    private val lock = Any()
    private val peers = LinkedHashMap<String, TrustedPeer>()

    init {
        initial.forEach { peers[it.peerId.value] = it }
    }

    override fun byPeerId(peerId: PeerId): TrustedPeer? = synchronized(lock) { peers[peerId.value] }

    override fun bySpki(identitySpkiSha256: SpkiHash): TrustedPeer? =
        synchronized(lock) { peers.values.firstOrNull { it.identitySpkiSha256 == identitySpkiSha256 } }

    override fun all(): List<TrustedPeer> = synchronized(lock) { peers.values.toList() }

    override fun remember(peer: TrustedPeer) {
        synchronized(lock) {
            val existing = peers[peer.peerId.value]
            if (existing != null && existing.identitySpkiSha256 != peer.identitySpkiSha256) {
                throw PinReplacementRefusedException(peer.peerId)
            }
            peers[peer.peerId.value] = peer
        }
    }

    override fun forget(peerId: PeerId) {
        synchronized(lock) { peers.remove(peerId.value) }
    }
}
