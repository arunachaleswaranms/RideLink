package com.ridelink.app.session

import com.ridelink.core.logging.InMemoryLogSink
import com.ridelink.core.model.ConnTiebreak
import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.model.SpkiHash
import com.ridelink.core.security.InMemoryTrustedPeerStore
import com.ridelink.core.security.TrustedPeer
import com.ridelink.core.sessionfsm.SessionEvent
import com.ridelink.core.sessionfsm.SessionStatus
import com.ridelink.core.voice.AudioProcessingConfig
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.control.ControlSessionManager
import com.ridelink.network.control.LinkLossReason
import com.ridelink.network.control.LocalHandshakeIdentity
import com.ridelink.network.voice.VoiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * STATUS §4 problem 124, found on two physical phones (Phase 9B-A): **a pairing that completed on a
 * connection the peer dialled, after this device's own `PAIRING` had ended, never reached
 * `CONNECTED`.**
 *
 * Device A dialled, showed the six digits, and the link was lost before anyone answered
 * (`PAIRING -> DISCOVERING`). A kept advertising, so when the peer dialled again A accepted that
 * connection and paired on it. Both users confirmed and the pin was written, but `SessionGate` mapped
 * `PairingSucceeded` to nothing in `DISCOVERING`. The control session was authenticated (diagnostics:
 * *Connected*) while the session layer still said *Searching…*, with no way forward. A trusted peer
 * dialling in at that point lands in the same place through `PeerTrusted`.
 *
 * These drive the real [SessionCoordinator], with the real `SessionGate` and `SessionFsm` behind
 * `handleControlEvent`, in the order the phones produced. ADR-019 Amendment A2 is the fix: the gate's
 * verdict opens `CONNECTING` from `DISCOVERING` as well. `Connected` on its own still opens nothing.
 */
class SessionCoordinatorDiscoveringTrustGateTest {
    @Test
    fun `a pairing completed on the peer's dial after a link loss ended PAIRING reaches CONNECTED`() =
        withCoordinator { coordinator, trustedPeers ->
            dialAndLoseTheLink(coordinator)

            // The peer dials again; this device accepts it, and the six digits go up on that connection.
            coordinator.handleControlEvent(ControlEvent.PairingRequired(remotePeerId))
            assertEquals(SessionStatus.DISCOVERING, coordinator.state.value.status, "a code on screen is not a transition")

            // Both users confirmed; PairingExchange wrote the pin.
            coordinator.handleControlEvent(ControlEvent.PairingSucceeded(remotePeer))
            assertEquals(SessionStatus.CONNECTING, coordinator.state.value.status, "the trust gate passed")
            assertNotNull(trustedPeers.bySpki(remotePeer.identitySpkiSha256), "the pin is behind the transition")

            coordinator.handleControlEvent(connected())
            assertEquals(SessionStatus.CONNECTED, coordinator.state.value.status)
        }

    @Test
    fun `a trusted peer accepted while DISCOVERING reaches CONNECTED`() =
        withCoordinator { coordinator, _ ->
            dialAndLoseTheLink(coordinator)

            coordinator.handleControlEvent(ControlEvent.PeerTrusted(remotePeerId))
            assertEquals(SessionStatus.CONNECTING, coordinator.state.value.status, "the stored pin matched")

            coordinator.handleControlEvent(connected())
            assertEquals(SessionStatus.CONNECTED, coordinator.state.value.status)
        }

    /** Rule 15, unchanged by the amendment: a connection that has not passed the gate opens nothing. */
    @Test
    fun `Connected alone in DISCOVERING still opens nothing`() =
        withCoordinator { coordinator, _ ->
            dialAndLoseTheLink(coordinator)

            coordinator.handleControlEvent(ControlEvent.PairingRequired(remotePeerId))
            coordinator.handleControlEvent(connected())

            assertEquals(SessionStatus.DISCOVERING, coordinator.state.value.status)
        }

    /** The phones' opening: dial, code on screen, link lost before the users answered. */
    private fun dialAndLoseTheLink(coordinator: SessionCoordinator) {
        assertTrue(coordinator.applyEvent(SessionEvent.StartDiscovery))
        assertTrue(coordinator.applyEvent(SessionEvent.PeerSelected))
        coordinator.handleControlEvent(ControlEvent.PairingRequired(remotePeerId))
        coordinator.handleControlEvent(ControlEvent.LinkLost(LinkLossReason.NETWORK))
        assertEquals(SessionStatus.DISCOVERING, coordinator.state.value.status, "a link lost mid-pairing is a pairing that did not happen")
    }

    private fun connected() = ControlEvent.Connected(remotePeerId, SessionId("test-session"), isLocalLeader = false, authGeneration = 1L)

    private val remotePeerId = PeerId("0123456789abcdef")

    private val remotePeer =
        TrustedPeer(
            peerId = remotePeerId,
            identitySpkiSha256 = SpkiHash("sha256:" + "cd".repeat(32)),
            displayName = "peer",
            pairedAtEpochSeconds = 0L,
            lastSeenAtEpochSeconds = 0L,
        )

    private fun withCoordinator(body: suspend (SessionCoordinator, InMemoryTrustedPeerStore) -> Unit) =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob())
            try {
                val trustedPeers = InMemoryTrustedPeerStore()
                val controlSessionManager =
                    ControlSessionManager(
                        scope = scope,
                        monotonicNowUs = { 0L },
                        localPeerId = PeerId("fedcba9876543210"),
                        channel = ParkingControlChannel(),
                        trustedPeers = trustedPeers,
                    )
                val coordinator =
                    SessionCoordinator(
                        discovery = SilentDiscoveryController(),
                        controlSessionManager = controlSessionManager,
                        localIdentity =
                            LocalHandshakeIdentity(
                                displayName = "test-device",
                                platform = "android",
                                osVersion = "test",
                                appVersion = "test",
                                connTiebreak = ConnTiebreak("1".repeat(32)),
                                identitySpkiSha256 = SpkiHash("sha256:" + "ab".repeat(32)),
                            ),
                        scope = scope,
                        logSink = InMemoryLogSink(),
                        trustedPeers = trustedPeers,
                        environment =
                            SessionEnvironment(
                                monotonicNowUs = { 0L },
                                nowEpochSeconds = { 0L },
                                audioEndpointPresent = { true },
                            ),
                        foregroundService = FakeForegroundService(),
                        buildVoiceController = { isLocalLeader ->
                            VoiceController(
                                scope = scope,
                                engine = FakeVoiceEngine(),
                                audioSession = FakeVoiceAudioSession(),
                                transport = NoOpVoiceTransport(),
                                isLocalLeader = isLocalLeader,
                                localTrackId = "test-track",
                                audioProcessing = AudioProcessingConfig(),
                            )
                        },
                    )
                body(coordinator, trustedPeers)
            } finally {
                scope.cancel()
            }
        }
}
