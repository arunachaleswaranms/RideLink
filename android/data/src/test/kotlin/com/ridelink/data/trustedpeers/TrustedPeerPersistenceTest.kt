package com.ridelink.data.trustedpeers

import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SpkiHash
import com.ridelink.core.security.PinReplacementRefusedException
import com.ridelink.core.security.TrustedPeer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Trust that survives a reboot (PROTOCOL §4.5, ADR-012).
 *
 * The interesting failures here are not "does it round-trip" but what happens when the file is
 * wrong: a corrupted store must cost a re-pair, not a crash at launch, and a stored pin must never
 * be replaced by anything short of an explicit forget.
 */
class TrustedPeerPersistenceTest {
    @TempDir
    lateinit var directory: File

    private val alice = PeerId("aaaaaaaaaaaaaaaa")
    private val bob = PeerId("bbbbbbbbbbbbbbbb")
    private val aliceSpki = SpkiHash("sha256:" + "11".repeat(32))
    private val otherSpki = SpkiHash("sha256:" + "22".repeat(32))

    private fun store() = FileTrustedPeerStore(File(directory, "trusted_peers.json"))

    private fun peer(
        peerId: PeerId = alice,
        spki: SpkiHash = aliceSpki,
        lastSeen: Long = 100,
    ) = TrustedPeer(peerId, spki, "Rider", 50, lastSeen)

    @Test
    fun `a remembered peer survives a new store instance`() {
        store().remember(peer())

        val reloaded = assertNotNull(store().byPeerId(alice), "trust must outlive the process")
        assertEquals(aliceSpki, reloaded.identitySpkiSha256)
        assertEquals("Rider", reloaded.displayName)
        assertEquals(50, reloaded.pairedAtEpochSeconds)
    }

    @Test
    fun `lookup by SPKI finds the same record as lookup by peer id`() {
        store().remember(peer())
        assertEquals(store().byPeerId(alice), store().bySpki(aliceSpki))
        assertNull(store().bySpki(otherSpki))
    }

    @Test
    fun `last_seen_at can be refreshed but the pin cannot be replaced`() {
        val store = store()
        store.remember(peer(lastSeen = 100))
        store.remember(peer(lastSeen = 999)) // same key: a refresh, allowed
        assertEquals(999, assertNotNull(store.byPeerId(alice)).lastSeenAtEpochSeconds)

        // ADR-012: a different key under the same peer_id is a new identity, and adopting it
        // silently is exactly the "auto re-pair" the design forbids.
        assertFailsWith<PinReplacementRefusedException> { store.remember(peer(spki = otherSpki)) }
        assertEquals(aliceSpki, assertNotNull(store.byPeerId(alice)).identitySpkiSha256)
    }

    @Test
    fun `forgetting a peer is what makes re-pairing possible`() {
        val store = store()
        store.remember(peer())
        store.forget(alice)
        assertNull(store.byPeerId(alice))

        store.remember(peer(spki = otherSpki)) // now allowed — the user asked for it
        assertEquals(otherSpki, assertNotNull(store.byPeerId(alice)).identitySpkiSha256)
    }

    @Test
    fun `a corrupt store reads as empty rather than crashing at launch`() {
        val file = File(directory, "trusted_peers.json")
        file.writeText("{ this is not json")
        // Losing trust costs a re-pair with a fresh SAS, which is safe. Throwing here would brick
        // the app over a damaged file — a far worse outcome than a prompt.
        assertTrue(FileTrustedPeerStore(file).all().isEmpty())
    }

    @Test
    fun `a record with a malformed identifier is dropped, not adopted`() {
        val file = File(directory, "trusted_peers.json")
        file.writeText(
            """
            [
              {"peerId":"NOTHEX","identitySpkiSha256":"${aliceSpki.value}","displayName":"x",
               "pairedAtEpochSeconds":1,"lastSeenAtEpochSeconds":2},
              {"peerId":"${bob.value}","identitySpkiSha256":"sha256:TOO-SHORT","displayName":"x",
               "pairedAtEpochSeconds":1,"lastSeenAtEpochSeconds":2},
              {"peerId":"${alice.value}","identitySpkiSha256":"${aliceSpki.value}","displayName":"ok",
               "pairedAtEpochSeconds":1,"lastSeenAtEpochSeconds":2}
            ]
            """.trimIndent(),
        )
        val loaded = FileTrustedPeerStore(file).all()
        assertEquals(1, loaded.size, "only the well-formed record may survive: $loaded")
        assertEquals(alice, loaded.single().peerId)
    }

    @Test
    fun `writes are atomic so a crash cannot leave a half-written pin`() {
        val file = File(directory, "trusted_peers.json")
        val store = FileTrustedPeerStore(file)
        store.remember(peer())
        store.remember(peer(peerId = bob, spki = otherSpki))

        assertEquals(2, FileTrustedPeerStore(file).all().size)
        assertTrue(
            directory.listFiles()!!.none { it.name.endsWith(".tmp") },
            "the temporary file must not be left behind",
        )
    }

    @Test
    fun `the local peer id is generated once and then stable`() {
        val file = File(directory, "peer_id")
        val first = LocalPeerIdStore(file).loadOrCreate()
        val second = LocalPeerIdStore(file).loadOrCreate()
        assertEquals(first, second, "peer_id is durable (PROTOCOL §2) — regenerating it would unpair the device")
        assertTrue(Regex("^[0-9a-f]{16}$").matches(first.value))
    }

    @Test
    fun `a corrupt peer id file is regenerated rather than adopted`() {
        val file = File(directory, "peer_id")
        file.writeText("not-a-peer-id")
        val regenerated = LocalPeerIdStore(file).loadOrCreate()
        assertTrue(Regex("^[0-9a-f]{16}$").matches(regenerated.value))
        assertEquals(regenerated, LocalPeerIdStore(file).loadOrCreate(), "and then it is stable")
    }
}
