package com.ridelink.network.security

import com.ridelink.core.model.ConnTiebreak
import com.ridelink.core.model.PeerId
import com.ridelink.core.security.InMemoryTrustedPeerStore
import com.ridelink.core.security.TrustedPeer
import com.ridelink.core.security.UtcTime
import com.ridelink.network.control.ControlHandshake
import com.ridelink.network.control.ControlSocket
import com.ridelink.network.control.ERROR_CODE_CERTIFICATE_INVALID
import com.ridelink.network.control.ERROR_CODE_IDENTITY_MISMATCH
import com.ridelink.network.control.ERROR_CODE_PIN_MISMATCH
import com.ridelink.network.control.HandshakeOutcome
import com.ridelink.network.control.LocalHandshakeIdentity
import com.ridelink.network.control.SeqCounter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Phase 1b secure control channel, end to end, over **real loopback TCP with a real TLS 1.3
 * handshake** (ADR-007, ADR-012, ADR-017, ADR-018).
 *
 * This is the test that would have caught the failure the six-digit SAS exists to prevent: two
 * phones deriving *different* codes from one handshake is, to the two users, indistinguishable
 * from a man-in-the-middle — and the correct response to seeing it would be to refuse to pair. So
 * it has to fail on a laptop, never on a bike.
 *
 * What is real here: the certificates (encoded by the production `IdentityCertificate`, signed by
 * a real EC key), the TLS 1.3 handshake, mutual authentication, the exporter computation, the SPKI
 * derivation, the pin decision and the HELLO exchange. What is substituted is documented on
 * [TestTlsSupport]: where the private key lives, and which call frame reaches the exporter.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TlsControlChannelTest {
    // runBlocking, not runTest: `runTest` drives a *virtual* clock, so a `withTimeout` around a
    // real TLS handshake fires instantly instead of waiting for the socket. Every test here is
    // deliberately doing real I/O on real loopback sockets, which is the only way to prove the
    // exporter and the pin behave — so real time is the correct clock.

    private val alice = TestTlsSupport.freshIdentity()
    private val bob = TestTlsSupport.freshIdentity()

    private val alicePeerId = PeerId("aaaaaaaaaaaaaaaa")
    private val bobPeerId = PeerId("bbbbbbbbbbbbbbbb")

    private fun channel(
        identity: TestTlsSupport.TestIdentity,
        provider: TlsProvider = TestTlsSupport.ConscryptTlsProvider,
    ) = TlsControlChannel(
        identity = identity.identity,
        ioDispatcher = Dispatchers.IO,
        provider = provider,
        secureRandom = SecureRandom(),
    )

    private fun localIdentity(
        identity: TestTlsSupport.TestIdentity,
        name: String,
    ) = LocalHandshakeIdentity(
        displayName = name,
        platform = "android",
        osVersion = "test",
        appVersion = "0.1.0",
        connTiebreak = ConnTiebreak("0".repeat(32)),
        identitySpkiSha256 = identity.identity.identitySpkiSha256,
    )

    /** Runs one full connect + mutual TLS handshake and hands both ends to [body]. */
    private suspend fun connected(
        serverIdentity: TestTlsSupport.TestIdentity = alice,
        clientIdentity: TestTlsSupport.TestIdentity = bob,
        serverProvider: TlsProvider = TestTlsSupport.ConscryptTlsProvider,
        clientProvider: TlsProvider = TestTlsSupport.ConscryptTlsProvider,
        body: suspend (server: ControlSocket, client: ControlSocket) -> Unit,
    ) = coroutineScope {
        val listener = channel(serverIdentity, serverProvider).bind()
        try {
            val accepted = async(Dispatchers.IO) { listener.accept() }
            val client =
                withContext(Dispatchers.IO) {
                    channel(clientIdentity, clientProvider).connect("127.0.0.1", listener.localPort)
                }
            val server = withTimeout(HANDSHAKE_BUDGET_MS) { accepted.await() }
            try {
                body(server, client)
            } finally {
                server.close()
                client.close()
            }
        } finally {
            listener.close()
        }
    }

    @Test
    fun `a real TLS 1_3 handshake completes and both ends see each other's SPKI`() =
        runBlocking {
            connected { server, client ->
                val serverSecurity = assertNotNull(server.security, "a TLS socket must carry ChannelSecurity")
                val clientSecurity = assertNotNull(client.security)

                // ADR-018 §3: assert the cipher suite, never SSLSession.getProtocol() — Conscrypt's
                // server-side session misreports TLS 1.3 as TLSv1.2 on a genuine TLS 1.3 connection.
                assertTrue(
                    serverSecurity.negotiatedCipherSuite.startsWith("TLS_"),
                    "unexpected cipher suite ${serverSecurity.negotiatedCipherSuite}",
                )
                assertEquals(serverSecurity.negotiatedCipherSuite, clientSecurity.negotiatedCipherSuite)

                // Mutual authentication: each side derived the *other's* pinned identity.
                assertEquals(bob.identity.identitySpkiSha256, serverSecurity.peerIdentitySpkiSha256)
                assertEquals(alice.identity.identitySpkiSha256, clientSecurity.peerIdentitySpkiSha256)
                assertNotEquals(alice.identity.identitySpkiSha256, bob.identity.identitySpkiSha256)

                assertTrue(serverSecurity.peerCertificateStructurallyValid)
                assertTrue(clientSecurity.peerCertificateStructurallyValid)
            }
        }

    @Test
    fun `both ends derive the same six-digit SAS from one handshake`() =
        runBlocking {
            connected { server, client ->
                val serverSas = assertNotNull(serverSecurityOf(server).deriveSas6(), "server exporter unavailable")
                val clientSas = assertNotNull(client.security?.deriveSas6(), "client exporter unavailable")

                assertEquals(serverSas, clientSas, "the two screens must show the same six digits")
                assertEquals(6, serverSas.length, "PROTOCOL §4.5.1: exactly six characters, always")
                assertTrue(serverSas.all { it in '0'..'9' })

                // Stable across repeated reads of the same handshake — the code a user is looking
                // at must not change while they read it out.
                assertEquals(serverSas, serverSecurityOf(server).deriveSas6())
            }
        }

    @Test
    fun `a different handshake produces a different SAS`() =
        runBlocking {
            val first = mutableListOf<String>()
            repeat(2) {
                connected { server, _ -> first += assertNotNull(server.security?.deriveSas6()) }
            }
            // Two independent handshakes agreeing would mean the code is not bound to the session,
            // which is the entire property the SAS relies on. (A genuine 1-in-10^6 collision is
            // possible; a repeated value here in practice means the binding is broken.)
            assertNotEquals(first[0], first[1], "the SAS must be bound to a specific TLS session")
        }

    @Test
    fun `an unknown peer requires pairing and a known peer connects silently`() =
        runBlocking {
            connected { server, client ->
                val emptyStore = InMemoryTrustedPeerStore()
                val outcome = handshake(server, client, emptyStore, emptyStore)
                assertEquals("pairing_required", outcome.decisionName(), "an unknown peer must not connect silently")
            }

            connected { server, client ->
                val serverStore = InMemoryTrustedPeerStore(listOf(trusted(bobPeerId, bob)))
                val clientStore = InMemoryTrustedPeerStore(listOf(trusted(alicePeerId, alice)))
                val outcome = handshake(server, client, serverStore, clientStore)
                assertEquals("trusted", outcome.decisionName(), "a pinned peer must connect with no prompt")
            }
        }

    @Test
    fun `a re-issued certificate around the same key stays trusted`() =
        runBlocking {
            // ADR-012's central behaviour, and the reason RideLink encodes its own certificate
            // rather than using Android's auto-issued one (ADR-017 §3): a new serial, a new
            // validity window and a new self-signature, around an unchanged key, must be a
            // non-event. If this ever regresses, users get trained to click through a re-pair
            // prompt, which is exactly the reflex an attacker needs.
            val bobReissued = TestTlsSupport.reissue(bob)
            assertEquals(
                bob.identity.identitySpkiSha256,
                bobReissued.identity.identitySpkiSha256,
                "re-issuing must not change the pinned identity",
            )
            assertNotEquals(
                bob.identity.certificate.serialNumber,
                bobReissued.identity.certificate.serialNumber,
                "the fixture must actually produce a different certificate",
            )

            connected(clientIdentity = bobReissued) { server, client ->
                val serverStore = InMemoryTrustedPeerStore(listOf(trusted(bobPeerId, bob)))
                val clientStore = InMemoryTrustedPeerStore(listOf(trusted(alicePeerId, alice)))
                val outcome = handshake(server, client, serverStore, clientStore, clientIdentity = bobReissued)
                assertEquals("trusted", outcome.decisionName())
            }
        }

    @Test
    fun `a changed identity key is refused with pin_mismatch and never auto re-paired`() =
        runBlocking {
            val impostor = TestTlsSupport.freshIdentity()
            connected(clientIdentity = impostor) { server, client ->
                // The server has a pin for bobPeerId, but the peer presenting that peer_id now has
                // a different key. ADR-012: an unknown peer wearing a familiar name.
                val serverStore = InMemoryTrustedPeerStore(listOf(trusted(bobPeerId, bob)))
                val clientStore = InMemoryTrustedPeerStore(listOf(trusted(alicePeerId, alice)))
                // The acceptor blocks reading HELLO, so the initiator has to already be running —
                // ordering these the other way round deadlocks rather than failing.
                val clientOutcome =
                    async(Dispatchers.IO) {
                        ControlHandshake.performAsInitiator(
                            client,
                            bobPeerId,
                            SeqCounter(),
                            { 0L },
                            localIdentity(impostor, "Impostor"),
                            clientStore,
                        )
                    }
                val serverOutcome =
                    ControlHandshake.performAsAcceptor(
                        server,
                        alicePeerId,
                        SeqCounter(),
                        { 0L },
                        localIdentity(alice, "Alice"),
                        serverStore,
                    )
                assertEquals(
                    HandshakeOutcome.Rejected(ERROR_CODE_PIN_MISMATCH),
                    serverOutcome,
                    "a changed key must be refused, not silently re-pinned",
                )
                clientOutcome.await()
                assertEquals(
                    bob.identity.identitySpkiSha256,
                    serverStore.byPeerId(bobPeerId)?.identitySpkiSha256,
                    "the stored pin must be untouched by a failed handshake",
                )
            }
        }

    @Test
    fun `a HELLO whose advisory identity contradicts the certificate is refused`() =
        runBlocking {
            connected { server, client ->
                val store = InMemoryTrustedPeerStore()
                // The initiator advertises somebody else's SPKI while presenting its own
                // certificate. PROTOCOL §4.1: trust never derives from a field a peer can choose.
                val lying = localIdentity(bob, "Liar").copy(identitySpkiSha256 = alice.identity.identitySpkiSha256)
                val serverOutcome =
                    async(Dispatchers.IO) {
                        ControlHandshake.performAsAcceptor(server, alicePeerId, SeqCounter(), { 0L }, localIdentity(alice, "Alice"), store)
                    }
                ControlHandshake.performAsInitiator(client, bobPeerId, SeqCounter(), { 0L }, lying, store)
                assertEquals(HandshakeOutcome.Rejected(ERROR_CODE_IDENTITY_MISMATCH), serverOutcome.await())
            }
        }

    @Test
    fun `an expired certificate is refused as certificate_invalid, not as an attack`() =
        runBlocking {
            // Issued far enough in the past that its ten-year window has closed. ADR-012 requires a
            // distinct code here so a phone with a wrong clock is not reported to its owner as a
            // security incident.
            val ancient = TestTlsSupport.freshIdentity(now = UtcTime(EXPIRED_ISSUE_EPOCH_SECONDS))
            connected(clientIdentity = ancient) { server, client ->
                val store = InMemoryTrustedPeerStore()
                // The initiator's own peer (Alice) is fine, so it sends HELLO and waits for a reply
                // that never comes — the acceptor rejected the expired certificate before
                // answering. In production `ControlSessionManager.refuse` sends ERROR and closes,
                // which is what unblocks the peer; here the test closes the socket itself.
                val clientOutcome =
                    async(Dispatchers.IO) {
                        ControlHandshake.performAsInitiator(client, bobPeerId, SeqCounter(), { 0L }, localIdentity(ancient, "Old"), store)
                    }
                val serverOutcome =
                    ControlHandshake.performAsAcceptor(server, alicePeerId, SeqCounter(), { 0L }, localIdentity(alice, "Alice"), store)
                assertEquals(HandshakeOutcome.Rejected(ERROR_CODE_CERTIFICATE_INVALID), serverOutcome)
                // The initiator is left waiting for a HELLO_ACK that will never come. Closing the
                // socket is what production does (`ControlSessionManager.refuse`), and the
                // initiator must report that as ConnectionClosed rather than throwing — a write
                // racing the close is exactly how this surfaced.
                client.close()
                assertEquals(HandshakeOutcome.ConnectionClosed, clientOutcome.await())
            }
        }

    @Test
    fun `pairing fails closed when the exporter is unavailable`() =
        runBlocking {
            // ADR-007 Amendment A1: no channel binding means no SAS, and the response is to stop —
            // never to show six digits that are not bound to this TLS session.
            connected(serverProvider = TestTlsSupport.ExporterlessTlsProvider) { server, _ ->
                assertEquals(null, server.security?.deriveSas6())
            }
        }

    private suspend fun handshake(
        server: ControlSocket,
        client: ControlSocket,
        serverStore: InMemoryTrustedPeerStore,
        clientStore: InMemoryTrustedPeerStore,
        clientIdentity: TestTlsSupport.TestIdentity = bob,
    ): HandshakeOutcome.Success =
        coroutineScope {
            val serverOutcome =
                async(Dispatchers.IO) {
                    ControlHandshake.performAsAcceptor(
                        server,
                        alicePeerId,
                        SeqCounter(),
                        { 0L },
                        localIdentity(alice, "Alice"),
                        serverStore,
                    )
                }
            val clientOutcome =
                ControlHandshake.performAsInitiator(
                    client,
                    bobPeerId,
                    SeqCounter(),
                    { 0L },
                    localIdentity(clientIdentity, "Bob"),
                    clientStore,
                )
            val server0 = serverOutcome.await()
            assertTrue(server0 is HandshakeOutcome.Success, "server handshake failed: $server0")
            assertTrue(clientOutcome is HandshakeOutcome.Success, "client handshake failed: $clientOutcome")
            // Both sides must reach the same verdict about each other, or one would pair while the
            // other connected silently.
            assertEquals(server0.decisionName(), clientOutcome.decisionName())
            server0
        }

    private fun HandshakeOutcome.Success.decisionName(): String =
        when (val decision = pinDecision) {
            is com.ridelink.core.security.PinDecision.Trusted -> "trusted"
            is com.ridelink.core.security.PinDecision.PairingRequired -> "pairing_required"
            is com.ridelink.core.security.PinDecision.Refused -> decision.code
        }

    private fun trusted(
        peerId: PeerId,
        identity: TestTlsSupport.TestIdentity,
    ) = TrustedPeer(
        peerId = peerId,
        identitySpkiSha256 = identity.identity.identitySpkiSha256,
        displayName = "peer",
        pairedAtEpochSeconds = TestTlsSupport.NOW_EPOCH_SECONDS,
        lastSeenAtEpochSeconds = TestTlsSupport.NOW_EPOCH_SECONDS,
    )

    /** Non-null on every TLS socket by construction; asserting it once keeps the call sites clean. */
    private fun serverSecurityOf(socket: ControlSocket) = assertNotNull(socket.security, "a TLS socket must carry ChannelSecurity")

    private companion object {
        const val HANDSHAKE_BUDGET_MS = 20_000L

        /** 1 Jan 2000: ten years' validity from here closed long before the fixed "now". */
        const val EXPIRED_ISSUE_EPOCH_SECONDS = 946_684_800L
    }
}
