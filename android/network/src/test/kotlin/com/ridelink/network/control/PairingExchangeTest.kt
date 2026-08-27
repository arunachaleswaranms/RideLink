package com.ridelink.network.control

import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SpkiHash
import com.ridelink.core.security.InMemoryTrustedPeerStore
import com.ridelink.core.security.TrustedPeer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * PROTOCOL §4.5 pairing as a state machine, exhausted without a socket in the way.
 *
 * The properties under test are the ones that make the six-digit code mean something. A pin
 * written on one user's say-so, a code that was never bound to the TLS session, or a replayed
 * `PAIR_RESULT` writing a second pin would each turn the confirmation into decoration — and none
 * of them would be visible in an end-to-end test that only checks "pairing succeeded".
 */
class PairingExchangeTest {
    private val remotePeerId = PeerId("bbbbbbbbbbbbbbbb")
    private val peerSpki = SpkiHash("sha256:" + "ab".repeat(32))
    private val otherSpki = SpkiHash("sha256:" + "cd".repeat(32))
    private val sas = "042137"

    private fun exchange(
        isInitiator: Boolean,
        store: InMemoryTrustedPeerStore = InMemoryTrustedPeerStore(),
    ) = PairingExchange(
        remotePeerId = remotePeerId,
        peerIdentitySpkiSha256 = peerSpki,
        isInitiator = isInitiator,
        trustedPeers = store,
        nowEpochSeconds = { NOW },
    )

    @Test
    fun `no pin is written until both sides have confirmed`() {
        val store = InMemoryTrustedPeerStore()
        val acceptor = exchange(isInitiator = false, store = store)
        acceptor.begin(sas)
        acceptor.onPairRequest("Rider", peerSpki)

        // This user says yes. The peer has not.
        assertIs<PairingExchange.Step.Wait>(acceptor.onLocalDecision(accepted = true))
        assertNull(store.byPeerId(remotePeerId), "one screen's confirmation is not a pairing")

        // Now the peer's confirmation arrives.
        assertIs<PairingExchange.Step.SendPairResultAccepted>(acceptor.onPairConfirm(accepted = true))
        assertEquals(peerSpki, assertNotNull(store.byPeerId(remotePeerId)).identitySpkiSha256)
    }

    @Test
    fun `the peer confirming first still waits for this user`() {
        val store = InMemoryTrustedPeerStore()
        val acceptor = exchange(isInitiator = false, store = store)
        acceptor.begin(sas)

        assertIs<PairingExchange.Step.Wait>(acceptor.onPairConfirm(accepted = true))
        assertNull(store.byPeerId(remotePeerId), "the remote user cannot pair on this user's behalf")

        assertIs<PairingExchange.Step.SendPairResultAccepted>(acceptor.onLocalDecision(accepted = true))
        assertNotNull(store.byPeerId(remotePeerId))
    }

    @Test
    fun `the initiator sends PAIR_CONFIRM and settles on PAIR_RESULT`() {
        val store = InMemoryTrustedPeerStore()
        val initiator = exchange(isInitiator = true, store = store)
        initiator.begin(sas)

        assertIs<PairingExchange.Step.SendPairConfirm>(initiator.onLocalDecision(accepted = true))
        assertNull(store.byPeerId(remotePeerId))

        val step = initiator.onPairResult(accepted = true, advertisedSpki = peerSpki)
        assertIs<PairingExchange.Step.Succeeded>(step)
        assertEquals(peerSpki, step.peer.identitySpkiSha256)
        assertEquals(NOW, step.peer.pairedAtEpochSeconds)
    }

    @Test
    fun `either side rejecting fails the exchange and writes nothing`() {
        val store = InMemoryTrustedPeerStore()

        val localReject = exchange(isInitiator = true, store = store)
        localReject.begin(sas)
        assertEquals("pairing_rejected", refusalCode(localReject.onLocalDecision(accepted = false)))

        val remoteReject = exchange(isInitiator = false, store = store)
        remoteReject.begin(sas)
        remoteReject.onLocalDecision(accepted = true)
        assertEquals("pairing_rejected", refusalCode(remoteReject.onPairConfirm(accepted = false)))

        assertNull(store.byPeerId(remotePeerId), "a rejected pairing must leave no trace")
    }

    @Test
    fun `a peer whose PAIR_REQUEST contradicts its certificate is refused`() {
        val exchange = exchange(isInitiator = false)
        exchange.begin(sas)
        // PROTOCOL §4.1's rule applied to §4.5: the certificate is authoritative, the field is not.
        assertEquals("identity_mismatch", refusalCode(exchange.onPairRequest("Rider", otherSpki)))
    }

    @Test
    fun `a PAIR_RESULT advertising a different identity is refused`() {
        val exchange = exchange(isInitiator = true)
        exchange.begin(sas)
        exchange.onLocalDecision(accepted = true)
        assertEquals("identity_mismatch", refusalCode(exchange.onPairResult(accepted = true, advertisedSpki = otherSpki)))
    }

    @Test
    fun `a missing exporter fails the exchange rather than showing an unbound code`() {
        // ADR-007 Amendment A1: without a channel binding the six digits prove nothing, and the
        // response is to stop — never to display something that looks like a verification.
        val exchange = exchange(isInitiator = true)
        assertEquals("internal", refusalCode(exchange.begin(derivedSas6 = null)))
        assertNull(exchange.sas6, "no code may be displayed when the exporter is unavailable")
    }

    @Test
    fun `a replayed PAIR_RESULT cannot pair twice`() {
        val store = InMemoryTrustedPeerStore()
        val initiator = exchange(isInitiator = true, store = store)
        initiator.begin(sas)
        initiator.onLocalDecision(accepted = true)

        assertIs<PairingExchange.Step.Succeeded>(initiator.onPairResult(accepted = true, advertisedSpki = peerSpki))
        // A duplicated or replayed frame must not re-enter the success path.
        assertIs<PairingExchange.Step.Wait>(initiator.onPairResult(accepted = true, advertisedSpki = peerSpki))
        assertEquals(1, store.all().size)
    }

    @Test
    fun `pairing never overwrites an existing pin for the same peer`() {
        // The concrete shape of ADR-012's "never auto re-pair": if a record already exists for this
        // peer_id under a different key, remembering must refuse rather than substitute.
        val store =
            InMemoryTrustedPeerStore(
                listOf(TrustedPeer(remotePeerId, otherSpki, "old", NOW, NOW)),
            )
        val exchange = exchange(isInitiator = true, store = store)
        exchange.begin(sas)
        exchange.onLocalDecision(accepted = true)

        assertEquals(
            "pin_mismatch",
            refusalCode(exchange.onPairResult(accepted = true, advertisedSpki = peerSpki)),
            "replacing a stored pin must be refused, and reported as pin_mismatch",
        )
        assertEquals(otherSpki, assertNotNull(store.byPeerId(remotePeerId)).identitySpkiSha256)
    }

    private fun refusalCode(step: PairingExchange.Step): String {
        assertIs<PairingExchange.Step.Failed>(step)
        return step.code
    }

    private companion object {
        const val NOW = 1_787_832_000L
    }
}
