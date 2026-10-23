package com.ridelink.app.session

import com.ridelink.app.ui.SessionAction
import com.ridelink.app.ui.forgetPeerUnavailableHint
import com.ridelink.app.ui.sessionAction
import com.ridelink.core.logging.InMemoryLogSink
import com.ridelink.core.model.ConnTiebreak
import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.model.SpkiHash
import com.ridelink.core.security.InMemoryTrustedPeerStore
import com.ridelink.core.security.TrustedPeer
import com.ridelink.core.security.TrustedPeerStore
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * STATUS §4 problem 126 at the real [SessionCoordinator] (ADR-012 Amendment A1): *Forget peer* removes
 * exactly one pin, only while no session exists, and never becomes a second way to end a session.
 *
 * The transport half — that a forgotten peer is offered the six-digit code and cannot authenticate on
 * a pin read before the forget — is `ForgetPeerTrustGateTest` in `:network`, over real TLS.
 */
class SessionCoordinatorForgetPeerTest {
    @Test
    fun `forgetting in IDLE removes only the selected pin and leaves this phone's identity alone`() =
        withCoordinator { coordinator, store ->
            val identityBefore = coordinator.localIdentityPrefix
            assertEquals(listOf(rider, pillion), coordinator.trustedPeerList.value, "most recently seen first")

            assertEquals(ForgetPeerResult.FORGOTTEN, coordinator.forgetPeer(rider))

            assertNull(store.byPeerId(rider.peerId))
            assertEquals(pillion, store.byPeerId(pillion.peerId), "another phone's record is untouched")
            assertEquals(listOf(pillion), coordinator.trustedPeerList.value)
            assertEquals(identityBefore, coordinator.localIdentityPrefix)
            assertEquals(SessionStatus.IDLE, coordinator.state.value.status, "a forget is not a transition")
        }

    @Test
    fun `forget clears the pin_mismatch alert that asked for it`() =
        withCoordinator { coordinator, _ ->
            coordinator.handleControlEvent(ControlEvent.HandshakeRefused("pin_mismatch"))
            assertEquals("pin_mismatch", coordinator.securityAlert.value)

            coordinator.forgetPeer(rider)

            assertNull(coordinator.securityAlert.value)
        }

    /**
     * The active-session design: refused, with nothing changed and nothing torn down. The session is
     * ended by the existing control, whose single teardown owner emits `TeardownComplete`; only then,
     * in `IDLE`, does the forget go through.
     */
    @Test
    fun `forget during an authenticated session is refused and the session ends only through End session`() =
        withCoordinator { coordinator, store ->
            walkToConnected(coordinator)

            assertEquals(ForgetPeerResult.REFUSED_SESSION_ACTIVE, coordinator.forgetPeer(rider))
            assertEquals(rider, store.byPeerId(rider.peerId), "the pin is untouched")
            assertEquals(SessionStatus.CONNECTED, coordinator.state.value.status, "a refused forget ends nothing")

            assertEquals(true, coordinator.applyEvent(SessionEvent.StartRide))
            assertEquals(ForgetPeerResult.REFUSED_SESSION_ACTIVE, coordinator.forgetPeer(rider))
            coordinator.handleControlEvent(ControlEvent.LinkLost(LinkLossReason.NETWORK))
            assertEquals(SessionStatus.RECONNECTING, coordinator.state.value.status)
            assertEquals(ForgetPeerResult.REFUSED_SESSION_ACTIVE, coordinator.forgetPeer(rider), "nor while reconnecting")
            assertEquals(2, store.all().size)

            coordinator.endSession()
            // ENDING -> IDLE is opened only by `TeardownComplete`, which only `retireSession` emits.
            awaitStatus(coordinator, SessionStatus.IDLE)

            assertEquals(ForgetPeerResult.FORGOTTEN, coordinator.forgetPeer(rider))
            assertNull(store.byPeerId(rider.peerId))
            assertEquals(SessionStatus.IDLE, coordinator.state.value.status)
        }

    @Test
    fun `forget is refused while searching or comparing a code`() =
        withCoordinator { coordinator, store ->
            assertEquals(true, coordinator.applyEvent(SessionEvent.StartDiscovery))
            assertEquals(ForgetPeerResult.REFUSED_SESSION_ACTIVE, coordinator.forgetPeer(rider))
            assertEquals(true, coordinator.applyEvent(SessionEvent.PeerSelected))
            assertEquals(ForgetPeerResult.REFUSED_SESSION_ACTIVE, coordinator.forgetPeer(rider))
            assertEquals(2, store.all().size)
        }

    /**
     * ADR-012 Amendment A2: recovery from `pin_mismatch` through the controls the screen actually
     * offers. The refusal leaves this phone searching. *Stop searching* is the session button offered
     * there, and *Forget* is disabled with its reason. The button reaches `IDLE`, where *Forget* is
     * enabled and succeeds, and *Find other phone* is offered again for the fresh code.
     */
    @Test
    fun `recovery from pin_mismatch runs through Stop searching to IDLE and then Forget`() =
        withCoordinator { coordinator, store ->
            assertEquals(SessionAction.START, sessionAction(coordinator.state.value.status))
            coordinator.startDiscovery()
            assertEquals(true, coordinator.applyEvent(SessionEvent.PeerSelected))
            coordinator.handleControlEvent(ControlEvent.HandshakeRefused("pin_mismatch"))

            val refused = coordinator.state.value.status
            assertEquals(SessionStatus.DISCOVERING, refused, "pin_mismatch is never resolved by pairing")
            assertEquals("pin_mismatch", coordinator.securityAlert.value)
            assertEquals(SessionAction.STOP_DISCOVERY, sessionAction(refused))
            assertNotNull(forgetPeerUnavailableHint(refused))
            assertEquals(ForgetPeerResult.REFUSED_SESSION_ACTIVE, coordinator.forgetPeer(rider))
            assertEquals(rider, store.byPeerId(rider.peerId))

            coordinator.cancelDiscovery()
            awaitStatus(coordinator, SessionStatus.IDLE)

            assertNull(forgetPeerUnavailableHint(SessionStatus.IDLE))
            assertEquals(ForgetPeerResult.FORGOTTEN, coordinator.forgetPeer(rider))
            assertNull(store.byPeerId(rider.peerId))
            assertNull(coordinator.securityAlert.value, "the alert asked for this forget, and it is done")
            assertEquals(SessionAction.START, sessionAction(coordinator.state.value.status))
        }

    /**
     * ADR-012 Amendment A2's Android half. The iOS coordinator reads the policy and then hops to another
     * actor to write, and *Find other phone* could start a session in between. Android's [SessionCoordinator.forgetPeer]
     * reads the policy and writes under [com.ridelink.network.control.TrustRevocationGate]'s monitor
     * in one non-suspending call, so there is no window. This reference compiles only while that stays
     * true: making `forgetPeer` `suspend` would reopen the iOS race here, and it breaks this test first.
     */
    @Test
    fun `forgetPeer has no suspension point between its policy read and its write`() =
        withCoordinator { coordinator, _ ->
            val nonSuspending: (TrustedPeer) -> ForgetPeerResult = coordinator::forgetPeer
            assertEquals(ForgetPeerResult.FORGOTTEN, nonSuspending(rider))
        }

    /** A forget that did not persist must not be reported as one: the phone is still trusted. */
    @Test
    fun `a forget that cannot be persisted reports failure and keeps the phone listed`() {
        val store = FailingForgetStore(InMemoryTrustedPeerStore(listOf(rider, pillion)))
        withCoordinator(store) { coordinator, _ ->
            assertEquals(ForgetPeerResult.FAILED, coordinator.forgetPeer(rider))
            assertEquals(rider, store.byPeerId(rider.peerId))
            assertEquals(listOf(rider, pillion), coordinator.trustedPeerList.value)
        }
    }

    /** A successful pairing appears in the list, so the user can find the phone to forget it later. */
    @Test
    fun `a new pairing is listed`() =
        withCoordinator(InMemoryTrustedPeerStore()) { coordinator, _ ->
            assertEquals(emptyList(), coordinator.trustedPeerList.value)
            assertEquals(true, coordinator.applyEvent(SessionEvent.StartDiscovery))
            assertEquals(true, coordinator.applyEvent(SessionEvent.PeerSelected))
            coordinator.handleControlEvent(ControlEvent.PairingSucceeded(rider))
            assertEquals(listOf(rider.peerId), coordinator.trustedPeerList.value.map { it.peerId })
        }

    private fun walkToConnected(coordinator: SessionCoordinator) {
        assertEquals(true, coordinator.applyEvent(SessionEvent.StartDiscovery))
        assertEquals(true, coordinator.applyEvent(SessionEvent.PeerSelected))
        coordinator.handleControlEvent(ControlEvent.PeerTrusted(rider.peerId))
        coordinator.handleControlEvent(
            ControlEvent.Connected(rider.peerId, SessionId("test-session"), isLocalLeader = false, authGeneration = 1L),
        )
        assertEquals(SessionStatus.CONNECTED, coordinator.state.value.status)
    }

    private suspend fun awaitStatus(
        coordinator: SessionCoordinator,
        target: SessionStatus,
    ) {
        withTimeout(TIMEOUT_MS) { while (coordinator.state.value.status != target) delay(POLL_MS) }
    }

    private val rider =
        TrustedPeer(PeerId("0123456789abcdef"), SpkiHash("sha256:" + "cd".repeat(32)), "Rider", 10L, 200L)
    private val pillion =
        TrustedPeer(PeerId("1111222233334444"), SpkiHash("sha256:" + "ef".repeat(32)), "Pillion", 10L, 100L)

    private class FailingForgetStore(
        private val delegate: TrustedPeerStore,
    ) : TrustedPeerStore by delegate {
        override fun forget(peerId: PeerId): Unit = throw IOException("disk full")
    }

    private fun withCoordinator(body: suspend (SessionCoordinator, TrustedPeerStore) -> Unit) =
        withCoordinator(InMemoryTrustedPeerStore(listOf(pillion, rider)), body)

    private fun withCoordinator(
        trustedPeers: TrustedPeerStore,
        body: suspend (SessionCoordinator, TrustedPeerStore) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
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

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 10L
    }
}
