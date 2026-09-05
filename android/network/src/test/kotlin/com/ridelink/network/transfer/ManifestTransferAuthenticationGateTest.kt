package com.ridelink.network.transfer

import com.ridelink.core.protocol.ManifestMessage
import com.ridelink.core.protocol.ManifestMessageTypes
import com.ridelink.core.protocol.TransferMessageTypes
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.control.ControlSessionManager
import com.ridelink.network.control.FsmSession
import com.ridelink.network.control.TestPeer
import com.ridelink.network.control.TestSessions
import com.ridelink.network.manifest.ManifestSink
import com.ridelink.network.voice.rawEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Phase 4 analogue of `VoiceAuthenticationGateTest`: **an unpaired peer must never reach the
 * shared-library catalogue** (brief §22 — "unknown/unpaired peers must never receive the library
 * catalogue"), proven over real TLS with a real unpaired first meeting, not merely asserted about
 * the allowlist's contents.
 */
class ManifestTransferAuthenticationGateTest {
    @Test
    fun `an unauthenticated peer's MANIFEST and TRANSFER frames never reach the catalogue`() =
        twoUnpairedPhones { a, b, sinkA ->
            a.awaitPairingPrompt()
            b.awaitPairingPrompt()
            assertEquals(0, a.session.countOf { it is ControlEvent.Connected })

            b.sendRawFrame(ManifestMessageTypes.BEGIN) {
                put("manifest_id", "01J9Z4M3RT8V2W5X7Y9Z1A3B5C")
                put("kind", "full")
                put("manifest_revision", 1)
                put("base_revision", null)
                put("total_entries", 0)
                put("total_removed", 0)
                put("page_count", 0)
                put("digest_alg", "ridelink-manifest-v1")
            }
            b.sendRawFrame(TransferMessageTypes.REQUEST) {
                put("content_hash", "sha256:" + "1f".repeat(32))
                put("transfer_id", "01J9Z4M3RT8V2W5X7Y9Z1A3B5C")
            }
            delay(SETTLE_MS)

            assertEquals(emptyList(), sinkA.received, "an unauthenticated peer reached the catalogue")
            assertTrue(
                a.session.manager.manifest.droppedPreAuthentication > 0,
                "must be counted as refused, not merely absent",
            )
            assertTrue(a.session.manager.transfer.droppedPreAuthentication > 0)
            assertTrue(
                a.session.trustStore
                    .all()
                    .isEmpty(),
                "no pin may have been written",
            )
        }

    @Test
    fun `the same frames are delivered once the trust gate has passed`() =
        twoUnpairedPhones { a, b, sinkA ->
            a.awaitPairingPrompt()
            b.awaitPairingPrompt()
            a.session.manager.confirmPairing(true)
            b.session.manager.confirmPairing(true)
            a.session.awaitEvent { it is ControlEvent.Connected }

            b.sendRawFrame(ManifestMessageTypes.ABORT) {
                put("manifest_id", "01J9Z4M3RT8V2W5X7Y9Z1A3B5C")
                put("reason", "cancelled")
            }
            var waited = 0L
            while (sinkA.received.isEmpty() && waited < TIMEOUT_MS) {
                delay(POLL_MS)
                waited += POLL_MS
            }
            assertEquals(1, sinkA.received.size)
            assertTrue(sinkA.received.single() is ManifestMessage.Abort)
        }

    @Test
    fun `a malformed TRANSFER frame is dropped without ending the control connection`() =
        twoUnpairedPhones { a, b, _ ->
            a.awaitPairingPrompt()
            b.awaitPairingPrompt()
            a.session.manager.confirmPairing(true)
            b.session.manager.confirmPairing(true)
            a.session.awaitEvent { it is ControlEvent.Connected }

            b.sendRawFrame(TransferMessageTypes.OFFER) { put("transfer_id", "not-a-ulid") }
            b.sendRawFrame(TransferMessageTypes.PROGRESS) {
                put("transfer_id", "01J9Z4M3RT8V2W5X7Y9Z1A3B5C")
                put("bytes", -1)
                put("pct", 0)
            }
            delay(SETTLE_MS)

            assertTrue(
                a.session.manager.transfer.rejectionCounts.values
                    .sum() >= 2,
                "both must be counted",
            )
            assertEquals(
                com.ridelink.core.sessionfsm.SessionStatus.CONNECTED,
                a.session.status,
                "the control connection must survive a malformed frame",
            )
        }

    @Test
    fun `no MANIFEST or TRANSFER type appears in the pre-authentication frame allowlist`() {
        val allowlist = ControlSessionManager.PRE_AUTHENTICATION_FRAME_TYPES
        assertEquals(emptyList(), ManifestMessageTypes.ALL.filter { it in allowlist })
        assertEquals(emptyList(), TransferMessageTypes.ALL.filter { it in allowlist })
        assertFalse("MANIFEST_BEGIN" in allowlist)
        assertFalse("TRANSFER_REQUEST" in allowlist)
    }

    // --- harness (mirrors VoiceAuthenticationGateTest) -------------------------------------------

    private class Phone(
        val session: FsmSession,
        val peer: TestPeer,
    ) {
        suspend fun awaitPairingPrompt() = session.awaitPairingPrompt()

        suspend fun sendRawFrame(
            type: String,
            build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
        ) {
            session.manager.writeRawFrame(rawEnvelope(peer.peerId, type, build))
        }
    }

    private fun twoUnpairedPhones(body: suspend (Phone, Phone, ManifestSpy) -> Unit) =
        runBlocking {
            val a = TestSessions.unpairedPeer("aaaaaaaaaaaaaaaa", "A")
            val b = TestSessions.unpairedPeer("bbbbbbbbbbbbbbbb", "B")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val monotonic: () -> Long = { System.nanoTime() / 1000 }
                val sessionA = FsmSession(a, a.manager(scope, monotonic))
                val sessionB = FsmSession(b, b.manager(scope, monotonic))
                sessionA.collectInto(scope)
                sessionB.collectInto(scope)

                val sinkA = ManifestSpy()
                sessionA.manager.manifest.sink = sinkA

                val portA = sessionA.manager.startListening(a.local)
                val portB = sessionB.manager.startListening(b.local)
                for (session in listOf(sessionA, sessionB)) {
                    session.apply(com.ridelink.core.sessionfsm.SessionEvent.StartDiscovery)
                    session.apply(com.ridelink.core.sessionfsm.SessionEvent.PeerSelected)
                }
                sessionA.manager.connectTo("127.0.0.1", portB, a.local)
                sessionB.manager.connectTo("127.0.0.1", portA, b.local)

                body(Phone(sessionA, a), Phone(sessionB, b), sinkA)

                sessionA.manager.shutdown()
                sessionB.manager.shutdown()
            } finally {
                scope.cancel()
            }
        }

    private class ManifestSpy : ManifestSink {
        private val log = CopyOnWriteArrayList<ManifestMessage>()
        val received: List<ManifestMessage> get() = log.toList()

        override fun submit(message: ManifestMessage) {
            log.add(message)
        }
    }

    private companion object {
        const val SETTLE_MS = 400L
        const val POLL_MS = 10L
        const val TIMEOUT_MS = 5_000L
    }
}
