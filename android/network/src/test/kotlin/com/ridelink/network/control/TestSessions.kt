package com.ridelink.network.control

import com.ridelink.core.model.PeerId
import com.ridelink.core.security.InMemoryTrustedPeerStore
import com.ridelink.core.security.TrustedPeer
import com.ridelink.network.security.TestTlsSupport
import com.ridelink.network.security.TlsControlChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Builds the pieces a control-session test needs, over the **real production TLS channel**.
 *
 * Phase 1a's suites ran against a plaintext socket, which was the only transport that existed.
 * They now run against [TlsControlChannel] instead, so duplicate-connection resolution, the
 * reconnect ladder and teardown are exercised with real certificates, a real TLS 1.3 handshake and
 * real mutual authentication underneath them. That is slower and it is the point: those are the
 * paths that carry a ride, and a transport-shaped bug that only appears once TLS is in the way
 * would otherwise be invisible until the two phones met.
 *
 * [PlaintextControlChannelFixture] survives for the framing tests alone, which deliberately have no
 * handshake to run.
 *
 * **Peers are pre-paired by default.** A test about reconnect should not also be a test about
 * pairing: with an empty trust store every handshake would correctly stop for a six-digit code and
 * never reach `CONNECTED`. [pairedPeers] seeds each side's store with the other's real
 * `identity_spki_sha256`, which is the "known peer, silent connect" path of PROTOCOL §4.1 —
 * so these tests assert transport behaviour on a trusted link, exactly as a ride would have it.
 */
class TestPeer(
    val peerId: PeerId,
    val identity: TestTlsSupport.TestIdentity,
    val trustedPeers: InMemoryTrustedPeerStore,
    private val displayName: String,
) {
    val local: LocalHandshakeIdentity =
        LocalHandshakeIdentity(
            displayName = displayName,
            platform = "android",
            osVersion = "test",
            appVersion = "test",
            connTiebreak = ConnTiebreakGenerator.generate(),
            identitySpkiSha256 = identity.identity.identitySpkiSha256,
        )

    /** A fresh `LocalHandshakeIdentity` with a *new* `conn_tiebreak`, for a second session. */
    fun freshLocal(): LocalHandshakeIdentity = local.copy(connTiebreak = ConnTiebreakGenerator.generate())

    fun channel(): TlsControlChannel =
        TlsControlChannel(
            identity = identity.identity,
            ioDispatcher = Dispatchers.IO,
            provider = TestTlsSupport.ConscryptTlsProvider,
        )

    /** Adds [other]'s real identity to this peer's trust store, so a handshake connects silently. */
    fun trust(other: TestPeer) {
        trustedPeers.remember(
            TrustedPeer(
                peerId = other.peerId,
                identitySpkiSha256 = other.identity.identity.identitySpkiSha256,
                displayName = "peer",
                pairedAtEpochSeconds = TestTlsSupport.NOW_EPOCH_SECONDS,
                lastSeenAtEpochSeconds = TestTlsSupport.NOW_EPOCH_SECONDS,
            ),
        )
    }

    fun manager(
        scope: CoroutineScope,
        monotonicNowUs: () -> Long,
    ): ControlSessionManager =
        ControlSessionManager(
            scope = scope,
            monotonicNowUs = monotonicNowUs,
            localPeerId = peerId,
            channel = channel(),
            trustedPeers = trustedPeers,
            nowEpochSeconds = { TestTlsSupport.NOW_EPOCH_SECONDS },
        )
}

object TestSessions {
    /**
     * Two peers that already trust each other's identity key, so a handshake between them reaches
     * `CONNECTED` with no pairing prompt.
     */
    fun pairedPeers(
        aPeerId: String,
        bPeerId: String,
        aName: String = "A",
        bName: String = "B",
    ): Pair<TestPeer, TestPeer> {
        val aId = PeerId(aPeerId)
        val bId = PeerId(bPeerId)
        val a = TestTlsSupport.freshIdentity()
        val b = TestTlsSupport.freshIdentity()
        val aStore = InMemoryTrustedPeerStore(listOf(trusted(bId, b)))
        val bStore = InMemoryTrustedPeerStore(listOf(trusted(aId, a)))
        return TestPeer(aId, a, aStore, aName) to TestPeer(bId, b, bStore, bName)
    }

    /** A peer with an empty trust store — for tests that are *about* first-meeting pairing. */
    fun unpairedPeer(
        peerId: String,
        name: String = "peer",
    ): TestPeer = TestPeer(PeerId(peerId), TestTlsSupport.freshIdentity(), InMemoryTrustedPeerStore(), name)

    /**
     * A peer that trusts [counterpart] but whose own identity [counterpart] does not know. Useful
     * where only one side's verdict is under test.
     */
    fun peerTrusting(
        peerId: String,
        counterpartId: String,
        counterpart: TestPeer,
        name: String = "peer",
    ): TestPeer =
        TestPeer(
            PeerId(peerId),
            TestTlsSupport.freshIdentity(),
            InMemoryTrustedPeerStore(listOf(trusted(PeerId(counterpartId), counterpart.identity))),
            name,
        )

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
}
