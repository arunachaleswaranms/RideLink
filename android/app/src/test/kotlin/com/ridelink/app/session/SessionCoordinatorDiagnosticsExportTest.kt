package com.ridelink.app.session

import com.ridelink.core.logging.DiagnosticsExportSource
import com.ridelink.core.logging.ExportProvenance
import com.ridelink.core.logging.InMemoryLogSink
import com.ridelink.core.model.ConnTiebreak
import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.model.SpkiHash
import com.ridelink.core.security.InMemoryTrustedPeerStore
import com.ridelink.core.security.TrustedPeer
import com.ridelink.core.sessionfsm.SessionEvent
import com.ridelink.core.voice.AudioProcessingConfig
import com.ridelink.network.control.ControlChannel
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.control.ControlListener
import com.ridelink.network.control.ControlSessionManager
import com.ridelink.network.control.ControlSocket
import com.ridelink.network.control.LocalHandshakeIdentity
import com.ridelink.network.voice.VoiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * NFR-08 (ADR-029 Amendment A2): what leaves the phone when the user exports diagnostics.
 *
 * The export reads the one process sink, and the only production writer into that sink is
 * [SessionCoordinator]. So the proof has two halves, and both run against production code:
 *
 * 1. every identifier-bearing log site in [SessionCoordinator], driven with fabricated **full-length**
 *    values, reaches the rendered export redacted to 6 characters and never whole; and
 * 2. a source scan pins the premise — [SessionCoordinator] is the only production logger, and no
 *    log call interpolates a SAS/pairing prompt, token, secret, exporter output or key material.
 *
 * Every value here is fabricated (CLAUDE.md: never a real key, token or pairing code).
 */
class SessionCoordinatorDiagnosticsExportTest {
    private val remotePeerHex = "a1b2c3d4e5f60718"
    private val remoteSpkiHex = "c0ffee" + "0123456789abcdef".repeat(3) + "0123456789"
    private val localTiebreakHex = "d00dfeed" + "9".repeat(24)

    @Test
    fun `an export of a real pairing, refusal and connect carries no full identifier`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val sink = InMemoryLogSink()
            val coordinator = coordinator(scope, sink)
            val peer = PeerId(remotePeerHex)

            // Every identifier-bearing SessionCoordinator log site, through its real entry point.
            assertTrue(coordinator.applyEvent(SessionEvent.StartDiscovery))
            assertTrue(coordinator.applyEvent(SessionEvent.PeerSelected))
            coordinator.handleControlEvent(ControlEvent.PairingRequired(peer))
            coordinator.handleControlEvent(ControlEvent.HandshakeRefused("pin_mismatch"))
            coordinator.handleControlEvent(
                ControlEvent.PairingSucceeded(
                    TrustedPeer(peer, SpkiHash("sha256:$remoteSpkiHex"), "Fabricated Phone", 0L, 0L),
                ),
            )
            coordinator.handleControlEvent(ControlEvent.PeerTrusted(peer))
            coordinator.handleControlEvent(ControlEvent.Connected(peer, SessionId("fabricated-session"), true, 1L))
            assertFalse(coordinator.applyEvent(SessionEvent.EndRide), "EndRide outside a ride is the \"rejected\" site")

            val export =
                DiagnosticsExportSource(sink, ExportProvenance("android", "test", null)) { 0L }.render()

            // The sites actually ran: their redacted forms are present.
            assertTrue("peer:a1b2c3…" in export, export)
            assertTrue("spki:c0ffee…" in export, export)
            assertTrue("handshake refused: pin_mismatch" in export, export)
            assertTrue("rejected" in export, export)

            // …and no full identifier, nor any 7-character prefix of one, is.
            listOf(remotePeerHex, remoteSpkiHex, localTiebreakHex, LOCAL_PEER_HEX, LOCAL_SPKI_HEX).forEach { full ->
                assertFalse(full.take(7) in export, "more than 6 characters of ${full.take(6)}… reached the export")
            }
            assertFalse("Fabricated Phone" in export, "a peer display name must not reach the export")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `SessionCoordinator is the only production logger and never logs a secret-bearing value`() {
        val mainSources =
            listOf("src/main/kotlin", "../network/src/main/kotlin", "../audio/src/main/kotlin", "../data/src/main/kotlin")
                .flatMap { root -> File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue(mainSources.size > 20, "expected to scan the production sources, found ${mainSources.size}")

        assertEquals(
            listOf("SessionCoordinator.kt"),
            mainSources.filter { "StructuredLogger(" in it.readText() }.map { it.name },
            "a new production logger is a new path into the exportable sink; audit it and extend this test",
        )

        val calls =
            LOG_CALL
                .findAll(mainSources.single { it.name == "SessionCoordinator.kt" }.readText())
                .map { match -> match.value }
                .toList()
        assertTrue(calls.size >= 10, "expected SessionCoordinator's log calls, found ${calls.size}")
        calls.forEach { call ->
            assertFalse(SECRET_BEARING.containsMatchIn(call), "log call interpolates a secret-bearing value: $call")
        }
    }

    private fun coordinator(
        scope: CoroutineScope,
        sink: InMemoryLogSink,
    ): SessionCoordinator {
        val localIdentity =
            LocalHandshakeIdentity(
                displayName = "test-device",
                platform = "android",
                osVersion = "test",
                appVersion = "test",
                connTiebreak = ConnTiebreak(localTiebreakHex),
                identitySpkiSha256 = SpkiHash("sha256:$LOCAL_SPKI_HEX"),
            )
        val audio = FakeVoiceAudioSession()
        return SessionCoordinator(
            discovery = SilentDiscoveryController(),
            controlSessionManager =
                ControlSessionManager(
                    scope = scope,
                    monotonicNowUs = { 0L },
                    localPeerId = PeerId(LOCAL_PEER_HEX),
                    channel = UnusedControlChannel(),
                    trustedPeers = InMemoryTrustedPeerStore(),
                ),
            localIdentity = localIdentity,
            scope = scope,
            logSink = sink,
            trustedPeers = InMemoryTrustedPeerStore(),
            environment = SessionEnvironment(monotonicNowUs = { 0L }, nowEpochSeconds = { 0L }, audioEndpointPresent = { true }),
            foregroundService = FakeForegroundService(),
            buildVoiceController = { isLocalLeader ->
                VoiceController(
                    scope = scope,
                    engine = FakeVoiceEngine(),
                    audioSession = audio,
                    transport = NoOpVoiceTransport(),
                    isLocalLeader = isLocalLeader,
                    localTrackId = "test-track",
                    audioProcessing = AudioProcessingConfig(),
                )
            },
        )
    }

    private class UnusedControlChannel : ControlChannel {
        override val transportLabel: String = "test"
        override val isSecure: Boolean = true

        override suspend fun bind(): ControlListener = error("not used by this test")

        override suspend fun connect(
            host: String,
            port: Int,
        ): ControlSocket = error("not used by this test")
    }

    private companion object {
        const val LOCAL_PEER_HEX = "fedcba9876543210"
        const val LOCAL_SPKI_HEX = "beefcafe00112233445566778899aabbccddeeff00112233445566778899aabb"

        /** From the call through the line that closes it; enough to see every interpolation. */
        val LOG_CALL = Regex("""logger\.(debug|info|warn|error)\((.|\n)*?\)\n""")
        val SECRET_BEARING = Regex("(?i)sas|prompt|token|secret|exporter|privatekey|keymaterial|password")
    }
}
