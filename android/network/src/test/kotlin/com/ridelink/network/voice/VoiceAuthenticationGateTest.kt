package com.ridelink.network.voice

import com.ridelink.core.protocol.VoiceMessageTypes
import com.ridelink.core.protocol.VoiceMode
import com.ridelink.core.protocol.VoiceSessionId
import com.ridelink.core.protocol.VoiceSignal
import com.ridelink.core.protocol.VoiceWireState
import com.ridelink.core.sessionfsm.SessionEvent
import com.ridelink.core.sessionfsm.SessionStatus
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.control.ControlMessages
import com.ridelink.network.control.ControlSessionManager
import com.ridelink.network.control.FsmSession
import com.ridelink.network.control.TestPeer
import com.ridelink.network.control.TestSessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The Phase 2a security invariant, end to end over real TLS:**
 *
 * > A peer that has completed TLS but has not passed the RideLink trust gate cannot start voice.
 * > No `VOICE_*` frame it sends is acted on, no `RTCPeerConnection` is created on its behalf, and
 * > no microphone is opened.
 *
 * This is the Phase 2a analogue of `PairingSessionIntegrationTest`, and it exists for the reason
 * STATUS §2g gives: the Phase 1b security bug was a *join* between two correct mechanisms that no
 * test crossed. The join here is "which frame types the read loop acts on before authentication",
 * and PROTOCOL §7.1's whole access-control story is that `VOICE_*` is absent from that list. A test
 * that only asserted the list's *contents* would not notice a second, later branch that acted on a
 * voice frame anyway — so this drives two real `ControlSessionManager`s over real TLS with a real
 * unpaired first meeting, and asserts against the sink the voice subsystem would actually receive on.
 */
class VoiceAuthenticationGateTest {
    private val clock = AtomicLong(1_000_000L)
    private val monotonicNowUs: () -> Long = { clock.addAndGet(1_000) }

    /**
     * The load-bearing test. Two unpaired peers complete TLS and reach `PAIRING`, a six-digit code is
     * on screen and unanswered — and one of them sends every `VOICE_*` frame there is. Not one
     * reaches the voice sink.
     */
    @Test
    fun `an unauthenticated peer's VOICE frames never reach the voice subsystem`() =
        twoUnpairedPhones { a, b, sinkA, sinkB ->
            // Both are mid-pairing: TLS is up, the trust gate is not open.
            a.awaitPairingPrompt()
            b.awaitPairingPrompt()
            assertEquals(SessionStatus.PAIRING, a.status)
            assertEquals(SessionStatus.PAIRING, b.status)
            assertNoConnected(a, b)

            b.sendRawVoiceFrames()
            // Long enough for four frames to cross loopback and be processed, so "nothing arrived"
            // is a real absence rather than a race this assertion happened to win.
            delay(SETTLE_MS)

            assertEquals(emptyList(), sinkA.received, "an unauthenticated peer reached the voice subsystem")
            assertEquals(emptyList(), sinkB.received)
            assertTrue(
                a.manager.voice.droppedPreAuthentication > 0,
                "the frames must be counted as refused, not merely absent — otherwise this test would " +
                    "also pass if they were never sent",
            )
            // Still no session, still no trust, still no code answered.
            assertEquals(SessionStatus.PAIRING, a.status)
            assertTrue(a.trustStore.all().isEmpty(), "no pin may have been written")
        }

    /**
     * The same peer, the same frames, **after** both users confirm. Now they are acted on. Without
     * this half, the test above would be satisfied by voice being broken outright.
     */
    @Test
    fun `the same peer's VOICE frames are acted on once the trust gate has passed`() =
        twoUnpairedPhones { a, b, sinkA, _ ->
            a.awaitPairingPrompt()
            b.awaitPairingPrompt()
            a.manager.confirmPairing(true)
            b.manager.confirmPairing(true)

            a.awaitEvent { it is ControlEvent.Connected }
            a.awaitStatus(SessionStatus.CONNECTED)

            b.sendRawVoiceFrames()
            a.awaitVoiceSignals(sinkA, expected = 4)

            assertEquals(
                listOf("Offer", "Answer", "IceCandidate", "State"),
                sinkA.received.map { it.kindName() },
                "every VOICE_* type must be delivered, in the order it was sent",
            )
        }

    /**
     * PROTOCOL §7.4: a malformed voice frame is dropped and the **control connection survives**. An
     * attacker-supplied SDP must not be able to end a ride's control plane.
     */
    @Test
    fun `malformed and oversize VOICE frames are dropped without ending the control connection`() =
        twoUnpairedPhones { a, b, sinkA, _ ->
            a.awaitPairingPrompt()
            b.awaitPairingPrompt()
            a.manager.confirmPairing(true)
            b.manager.confirmPairing(true)
            a.awaitEvent { it is ControlEvent.Connected }

            b.sendRawFrame(VoiceMessageTypes.OFFER) { put("sdp", "no id at all") }
            b.sendRawFrame(VoiceMessageTypes.OFFER) {
                put("voice_session_id", VSID)
                put("sdp", "x".repeat(OVERSIZE_SDP_BYTES))
            }
            b.sendRawFrame(VoiceMessageTypes.ICE) {
                put("voice_session_id", VSID)
                put("candidate", "c".repeat(OVERSIZE_CANDIDATE_BYTES))
                put("sdp_mline_index", 0)
            }
            b.sendRawFrame(VoiceMessageTypes.STATE) {
                put("voice_session_id", VSID)
                put("state", "active")
                put("mic_muted", "not-a-boolean")
                put("mode", "continuous")
            }
            delay(SETTLE_MS)

            assertEquals(emptyList(), sinkA.received, "no malformed frame may reach the voice subsystem")
            assertTrue(
                a.manager.voice.rejectionCounts.values
                    .sum() >= 4,
                "each malformed frame must be counted",
            )

            // The connection is still alive and still authenticated: a well-formed frame sent
            // afterwards still arrives. This is the assertion that would fail if a malformed SDP
            // had closed the socket.
            b.sendValidOffer()
            a.awaitVoiceSignals(sinkA, expected = 1)
            assertEquals(SessionStatus.CONNECTED, a.status)
        }

    /**
     * A property over the frame-type allowlist itself, to complement the behavioural tests: no
     * `VOICE_*` type may be in the pre-authentication set. Cheap, and it fails on the *addition* of a
     * voice type to that list rather than waiting for a behavioural test to notice.
     */
    @Test
    fun `no VOICE type appears in the pre-authentication frame allowlist`() {
        val allowlist = ControlSessionManager.PRE_AUTHENTICATION_FRAME_TYPES
        val offenders = VoiceMessageTypes.ALL.filter { it in allowlist }
        assertEquals(
            emptyList(),
            offenders,
            "PROTOCOL §7.1: VOICE_* must be inert before the trust gate. Adding one here is a security change",
        )
        // And the allowlist is still exactly PROTOCOL §4.1's list, so this test also fails if some
        // *other* Phase 2 type is quietly added.
        assertEquals(
            setOf("PING", "PONG", "PAIR_REQUEST", "PAIR_CONFIRM", "PAIR_RESULT", "BYE", "ERROR"),
            allowlist,
        )
    }

    // --- harness --------------------------------------------------------------------------------

    private fun twoUnpairedPhones(body: suspend (Phone, Phone, VoiceSignalSpy, VoiceSignalSpy) -> Unit) =
        runBlocking {
            val a = TestSessions.unpairedPeer("aaaaaaaaaaaaaaaa", "A")
            val b = TestSessions.unpairedPeer("bbbbbbbbbbbbbbbb", "B")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val sessionA = FsmSession(a, a.manager(scope, monotonicNowUs))
                val sessionB = FsmSession(b, b.manager(scope, monotonicNowUs))
                sessionA.collectInto(scope)
                sessionB.collectInto(scope)

                val sinkA = VoiceSignalSpy()
                val sinkB = VoiceSignalSpy()
                sessionA.manager.voice.sink = sinkA
                sessionB.manager.voice.sink = sinkB

                val portA = sessionA.manager.startListening(a.local)
                val portB = sessionB.manager.startListening(b.local)
                for (session in listOf(sessionA, sessionB)) {
                    session.apply(SessionEvent.StartDiscovery)
                    session.apply(SessionEvent.PeerSelected)
                }
                sessionA.manager.connectTo("127.0.0.1", portB, a.local)
                sessionB.manager.connectTo("127.0.0.1", portA, b.local)

                body(Phone(sessionA, a), Phone(sessionB, b), sinkA, sinkB)

                sessionA.manager.shutdown()
                sessionB.manager.shutdown()
            } finally {
                scope.cancel()
            }
        }

    private class Phone(
        val session: FsmSession,
        val peer: TestPeer,
    ) {
        val manager get() = session.manager
        val status get() = session.status
        val trustStore get() = session.trustStore

        suspend fun awaitPairingPrompt() = session.awaitPairingPrompt()

        suspend fun awaitStatus(target: SessionStatus) = session.awaitStatus(target)

        suspend fun awaitEvent(predicate: (ControlEvent) -> Boolean) = session.awaitEvent(predicate)

        fun countOf(predicate: (ControlEvent) -> Boolean) = session.countOf(predicate)

        /**
         * Writes each `VOICE_*` type straight onto the socket, bypassing `VoiceController` entirely.
         * That is the point: the question is what the *receiver's* read loop does with a frame a
         * hostile or buggy peer chose to send, not what a well-behaved local controller would send.
         */
        suspend fun sendRawVoiceFrames() {
            manager.writeRawFrame(
                ControlMessages.voiceSignal(
                    peer.peerId,
                    SESSION,
                    1,
                    1,
                    VoiceSignal.Offer(VoiceSessionId(VSID), MINIMAL_SDP),
                ),
            )
            manager.writeRawFrame(
                ControlMessages.voiceSignal(
                    peer.peerId,
                    SESSION,
                    2,
                    2,
                    VoiceSignal.Answer(VoiceSessionId(VSID), MINIMAL_SDP),
                ),
            )
            manager.writeRawFrame(
                ControlMessages.voiceSignal(
                    peer.peerId,
                    SESSION,
                    3,
                    3,
                    VoiceSignal.IceCandidate(VoiceSessionId(VSID), CANDIDATE, "0", 0),
                ),
            )
            manager.writeRawFrame(
                ControlMessages.voiceSignal(
                    peer.peerId,
                    SESSION,
                    4,
                    4,
                    VoiceSignal.State(VoiceSessionId(VSID), VoiceWireState.NEGOTIATING, false, VoiceMode.CONTINUOUS),
                ),
            )
        }

        suspend fun sendValidOffer() {
            manager.writeRawFrame(
                ControlMessages.voiceSignal(
                    peer.peerId,
                    SESSION,
                    9,
                    9,
                    VoiceSignal.Offer(VoiceSessionId(VSID), MINIMAL_SDP),
                ),
            )
        }

        suspend fun sendRawFrame(
            type: String,
            build: JsonObjectBuilder.() -> Unit,
        ) {
            manager.writeRawFrame(rawEnvelope(peer.peerId, type, build))
        }

        suspend fun awaitVoiceSignals(
            spy: VoiceSignalSpy,
            expected: Int,
        ) {
            withTimeout(FsmSession.TIMEOUT_MS) {
                while (spy.received.size < expected) delay(POLL_MS)
            }
        }
    }

    private suspend fun assertNoConnected(vararg phones: Phone) {
        delay(SETTLE_MS)
        for (phone in phones) {
            assertEquals(0, phone.countOf { it is ControlEvent.Connected }, "Connected before the trust gate")
        }
    }

    private companion object {
        const val VSID = "5e2a9c40b7f13d86e0a4c95b28f7d613"
        val SESSION =
            com.ridelink.core.model
                .SessionId("test-session")
        const val MINIMAL_SDP = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\n"
        const val CANDIDATE = "candidate:1 1 udp 1 192.0.2.11 51234 typ host"
        const val OVERSIZE_SDP_BYTES = 16_385
        const val OVERSIZE_CANDIDATE_BYTES = 513
        const val SETTLE_MS = 400L
        const val POLL_MS = 10L
    }
}
