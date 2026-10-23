package com.ridelink.core.security

import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SpkiHash
import com.ridelink.core.sessionfsm.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * STATUS §4 problem 126 / ADR-012 Amendment A1: the two pure pieces *Forget peer* rests on.
 * `RideLinkCoreTests.ForgetPeerTests` is the mirror.
 */
class ForgetPeerTest {
    private val pinned = SpkiHash("sha256:" + "11".repeat(32))
    private val other = SpkiHash("sha256:" + "22".repeat(32))

    @Test
    fun `forgetting is permitted only when no session exists`() {
        SessionStatus.entries.forEach { status ->
            assertEquals(status == SessionStatus.IDLE, ForgetPeerPolicy.permits(status), status.name)
        }
    }

    /** The re-check at the trust gate: a forgotten pin can only ever lead to the six-digit code. */
    @Test
    fun `the pin re-check sends a forgotten peer to pairing and keeps every other verdict`() {
        assertEquals(PinDecision.PairingRequired, PeerTrust.recheck(storedPin = null, presentedSpki = pinned))
        assertEquals(PinDecision.Trusted, PeerTrust.recheck(storedPin = pinned, presentedSpki = pinned))
        assertEquals(PinDecision.PIN_MISMATCH, PeerTrust.recheck(storedPin = other, presentedSpki = pinned))
    }

    /** The re-check agrees with [PeerTrust.decide] on the pin wherever the certificate checks passed. */
    @Test
    fun `the re-check is the pin half of decide`() {
        listOf(null, pinned, other).forEach { stored ->
            assertEquals(
                PeerTrust.decide(stored, pinned, helloAdvertisedSpki = pinned, certificateStructurallyValid = true),
                PeerTrust.recheck(stored, pinned),
                "stored=$stored",
            )
        }
    }

    @Test
    fun `forget removes only the selected record`() {
        val a = PeerId("aaaaaaaaaaaaaaaa")
        val b = PeerId("bbbbbbbbbbbbbbbb")
        val store = InMemoryTrustedPeerStore(listOf(TrustedPeer(a, pinned, "A", 1, 2), TrustedPeer(b, other, "B", 1, 2)))

        store.forget(a)

        assertNull(store.byPeerId(a))
        assertNull(store.bySpki(pinned))
        assertEquals(other, assertNotNull(store.byPeerId(b)).identitySpkiSha256)
        store.forget(PeerId("cccccccccccccccc"))
        assertEquals(listOf(b), store.all().map { it.peerId })
    }
}
