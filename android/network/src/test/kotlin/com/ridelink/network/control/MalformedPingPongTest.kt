package com.ridelink.network.control

import com.ridelink.core.model.SessionId
import com.ridelink.core.protocol.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * This session's brief §7: a well-framed envelope with a missing/malformed PING or PONG payload
 * field must never crash the read loop. Drives a real [ControlSessionManager] (the system under
 * test) against a hand-crafted "fake peer" socket that completes a genuine handshake and then
 * sends deliberately malformed frames — the same real read loop production code runs, not a
 * mock.
 */
class MalformedPingPongTest {
    private val clock = AtomicLong(1_000_000L)
    private val monotonicNowUs: () -> Long = { clock.addAndGet(1_000) }

    /** Every payload shape the brief calls out, plus a couple of adjacent malformed cases. */
    private fun malformedPingPayloads() =
        listOf(
            "PING missing t1_mono_us" to JsonObject(emptyMap()),
            "PING string instead of integer" to JsonObject(mapOf("t1_mono_us" to JsonPrimitive("not-a-number"))),
            "PING null t1_mono_us" to JsonObject(mapOf("t1_mono_us" to kotlinx.serialization.json.JsonNull)),
            "PING boolean t1_mono_us" to JsonObject(mapOf("t1_mono_us" to JsonPrimitive(true))),
        )

    private fun malformedPongPayloads() =
        listOf(
            "PONG missing t2_mono_us" to
                JsonObject(mapOf("t1_mono_us" to JsonPrimitive(1L), "t3_mono_us" to JsonPrimitive(3L))),
            "PONG invalid numeric type for t3_mono_us" to
                JsonObject(
                    mapOf(
                        "t1_mono_us" to JsonPrimitive(1L),
                        "t2_mono_us" to JsonPrimitive(2L),
                        "t3_mono_us" to JsonPrimitive("nope"),
                    ),
                ),
            "PONG extreme/overflow value" to
                JsonObject(
                    mapOf(
                        "t1_mono_us" to JsonPrimitive(1L),
                        "t2_mono_us" to JsonPrimitive(2L),
                        "t3_mono_us" to JsonPrimitive(Double.MAX_VALUE),
                    ),
                ),
            // This session's brief §11: t3-t2 alone overflows a signed 64-bit subtraction here.
            // A naive (unchecked) computation would silently wrap to a garbage-but-plausible-
            // looking value and corrupt the clock offset; on iOS the equivalent trapping
            // arithmetic would crash the process outright.
            "PONG t2/t3 chosen to overflow rtt arithmetic" to
                JsonObject(
                    mapOf(
                        "t1_mono_us" to JsonPrimitive(1L),
                        "t2_mono_us" to JsonPrimitive(Long.MAX_VALUE),
                        "t3_mono_us" to JsonPrimitive(Long.MIN_VALUE),
                    ),
                ),
        )

    @Test
    fun `malformed PING variants never crash the read loop, and a valid PING afterward still gets a PONG`() =
        runBlocking {
            withConnectedFakePeer { sut, fake, sessionId, fakeSeq ->
                for ((label, payload) in malformedPingPayloads()) {
                    fake.writeFrame(envelope("PING", sessionId, payload, fakeSeq))
                    delay(100)
                    assertEquals(
                        ControlState.CONNECTED,
                        sut.diagnostics.value.controlState,
                        "connection must survive: $label",
                    )
                }

                // A subsequent well-formed PING must still be answered — the read loop is alive.
                // The SUT's own keepalive/clock-sync loop is independently sending it PINGs on
                // this same socket, so skip over those and find the PONG that actually answers
                // ours (matched by echoed t1_mono_us), rather than assuming the very next frame.
                fake.writeFrame(envelope("PING", sessionId, JsonObject(mapOf("t1_mono_us" to JsonPrimitive(42L))), fakeSeq))
                val pong =
                    withTimeout(5_000) {
                        var found: Envelope? = null
                        while (found == null) {
                            val response = fake.readFrame()
                            assertIs<FrameReadResult.Frame>(response)
                            val envelope = (response as FrameReadResult.Frame).envelope
                            if (envelope.type == "PONG" && envelope.payload["t1_mono_us"]?.jsonPrimitive?.longOrNull == 42L) {
                                found = envelope
                            }
                        }
                        found
                    }
                assertEquals("PONG", pong.type)
            }
        }

    @Test
    fun `malformed PONG variants never crash the read loop, and a valid PONG afterward still updates diagnostics`() =
        runBlocking {
            withConnectedFakePeer { sut, fake, sessionId, fakeSeq ->
                for ((label, payload) in malformedPongPayloads()) {
                    fake.writeFrame(envelope("PONG", sessionId, payload, fakeSeq))
                    delay(100)
                    assertEquals(
                        ControlState.CONNECTED,
                        sut.diagnostics.value.controlState,
                        "connection must survive: $label",
                    )
                }

                val before = sut.diagnostics.value.rttMs
                val t1 = monotonicNowUs()
                val validPong =
                    JsonObject(
                        mapOf(
                            "t1_mono_us" to JsonPrimitive(t1),
                            "t2_mono_us" to JsonPrimitive(t1 + 1_000),
                            "t3_mono_us" to JsonPrimitive(t1 + 2_000),
                        ),
                    )
                fake.writeFrame(envelope("PONG", sessionId, validPong, fakeSeq))

                withTimeout(5_000) {
                    sut.diagnostics.first { it.rttMs != null && it.rttMs != before }
                }
            }
        }

    private fun envelope(
        type: String,
        sessionId: SessionId,
        payload: JsonObject,
        seqCounter: SeqCounter,
    ) = Envelope(
        v = 1,
        type = type,
        sessionId = sessionId.value,
        senderId = "1111111111111111",
        msgId = "m${seqCounter.nextSeq()}",
        seq = seqCounter.nextSeq(),
        sentAtMonoUs = monotonicNowUs(),
        payload = payload,
    )

    private suspend fun withConnectedFakePeer(
        block: suspend (sut: ControlSessionManager, fake: ControlSocket, sessionId: SessionId, fakeSeq: SeqCounter) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var fakeSocket: ControlSocket? = null
        try {
            // The "fake peer" is a real, correctly-behaved TLS peer that then sends deliberately
            // malformed PING/PONG payloads — which is the point: a peer that got through the
            // handshake is exactly the one whose later frames must not be able to kill a coroutine.
            val (sutPeer, fakePeer) = TestSessions.pairedPeers("9999999999999999", "1111111111111111", "SUT", "fake")
            val sut = sutPeer.manager(scope, monotonicNowUs)
            val port = sut.startListening(sutPeer.local)

            val fakeSeq = SeqCounter()
            val fake = fakePeer.channel().connect("127.0.0.1", port)
            fakeSocket = fake
            val outcome =
                ControlHandshake.performAsInitiator(
                    fake,
                    fakePeer.peerId,
                    fakeSeq,
                    monotonicNowUs,
                    fakePeer.local,
                    fakePeer.trustedPeers,
                )
            check(outcome is HandshakeOutcome.Success) { "fake peer handshake must succeed: $outcome" }

            withTimeout(5_000) { sut.diagnostics.first { it.controlState == ControlState.CONNECTED } }

            block(sut, fake, outcome.sessionId, fakeSeq)

            sut.shutdown()
        } finally {
            fakeSocket?.close()
            scope.cancel()
        }
    }
}
