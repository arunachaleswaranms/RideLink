package com.ridelink.network.playback

import com.ridelink.core.playback.PlaybackMessage
import com.ridelink.core.playback.QueueMessage
import com.ridelink.core.protocol.PlaybackMessageTypes
import com.ridelink.core.protocol.QueueMessageTypes
import com.ridelink.core.sessionfsm.SessionEvent
import com.ridelink.core.sessionfsm.SessionStatus
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.control.ControlSessionManager
import com.ridelink.network.control.FsmSession
import com.ridelink.network.control.TestPeer
import com.ridelink.network.control.TestSessions
import com.ridelink.network.voice.rawEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Phase 5 analogue of `VoiceAuthenticationGateTest` and
 * `ManifestTransferAuthenticationGateTest`: **an unpaired peer must never be able to move this
 * phone's music** — proven over real TLS with a real unpaired first meeting, not merely asserted
 * about the allowlist's contents.
 *
 * This is what makes ADR-024 §8's claim checkable. Every `PLAY`/`PAUSE`/`RESUME`/`SEEK`/`NEXT`/
 * `PREVIOUS`/`POSITION_REPORT`/`PLAYBACK_STATE`/`QUEUE_*` type is **absent** from
 * `PRE_AUTHENTICATION_FRAME_TYPES`, and that absence *is* the access control — the same
 * construction PROTOCOL §7.1 gives `VOICE_*`.
 */
class PlaybackAuthenticationGateTest {
    @Test
    fun `an unauthenticated peer's playback and queue frames never reach the coordinator`() =
        twoUnpairedPhones { a, b, spy ->
            a.awaitPairingPrompt()
            b.awaitPairingPrompt()
            assertEquals(0, a.session.countOf { it is ControlEvent.Connected })

            // Every Phase 5 type, one frame each — a table rather than a sample, so a type added to
            // the allowlist by accident cannot slip through unexercised.
            for (type in PlaybackMessageTypes.ALL) b.sendRawFrame(type) { playbackPayload(type) }
            for (type in QueueMessageTypes.ALL) b.sendRawFrame(type) { queuePayload(type) }
            delay(SETTLE_MS)

            assertEquals(emptyList(), spy.playback, "an unauthenticated peer moved this phone's playback")
            assertEquals(emptyList(), spy.queue, "an unauthenticated peer mutated this phone's queue")
            assertTrue(
                a.session.manager.playback.droppedPreAuthentication >= PlaybackMessageTypes.ALL.size + QueueMessageTypes.ALL.size,
                "every refused frame must be counted, not merely absent — otherwise this test could pass vacuously",
            )
            assertTrue(
                a.session.trustStore
                    .all()
                    .isEmpty(),
                "no pin may have been written",
            )
        }

    @Test
    fun `the same frames are delivered once the trust gate has passed`() =
        twoUnpairedPhones { a, b, spy ->
            a.awaitPairingPrompt()
            b.awaitPairingPrompt()
            a.session.manager.confirmPairing(true)
            b.session.manager.confirmPairing(true)
            a.session.awaitEvent { it is ControlEvent.Connected }

            b.sendRawFrame(PlaybackMessageTypes.PAUSE) { playbackPayload(PlaybackMessageTypes.PAUSE) }
            var waited = 0L
            while (spy.playback.isEmpty() && waited < TIMEOUT_MS) {
                delay(POLL_MS)
                waited += POLL_MS
            }
            assertEquals(1, spy.playback.size)
            assertTrue(spy.playback.single() is PlaybackMessage.Pause)
        }

    @Test
    fun `a malformed Phase 5 frame is dropped without ending the control connection`() =
        twoUnpairedPhones { a, b, _ ->
            a.awaitPairingPrompt()
            b.awaitPairingPrompt()
            a.session.manager.confirmPairing(true)
            b.session.manager.confirmPairing(true)
            a.session.awaitEvent { it is ControlEvent.Connected }

            b.sendRawFrame(PlaybackMessageTypes.PLAY) { put("command_seq", -1) }
            b.sendRawFrame(QueueMessageTypes.MOVE) { put("queue_item_id", "not-a-ulid") }
            delay(SETTLE_MS)

            assertTrue(
                a.session.manager.playback.playbackRejectionCounts.values
                    .sum() >= 1,
                "the malformed PLAY must be counted",
            )
            assertTrue(
                a.session.manager.playback.queueRejectionCounts.values
                    .sum() >= 1,
                "the malformed QUEUE_MOVE must be counted",
            )
            assertEquals(
                SessionStatus.CONNECTED,
                a.session.status,
                "the control connection must survive a malformed frame",
            )
        }

    @Test
    fun `no Phase 5 type appears in the pre-authentication frame allowlist`() {
        val allowlist = ControlSessionManager.PRE_AUTHENTICATION_FRAME_TYPES
        assertEquals(emptyList(), PlaybackMessageTypes.ALL.filter { it in allowlist })
        assertEquals(emptyList(), QueueMessageTypes.ALL.filter { it in allowlist })
        assertFalse("PLAY" in allowlist)
        assertFalse("QUEUE_ADD" in allowlist)
        assertFalse("POSITION_REPORT" in allowlist)
    }

    // --- payload builders (valid frames, so a rejection can only be the gate, never the codec) ----

    private fun JsonObjectBuilder.playbackHeader() {
        put("command_seq", 1)
        put("effective_at_session_us", 90_210_500_000)
        put("issued_by", "bbbbbbbbbbbbbbbb")
        put("queue_revision", 0)
    }

    private fun JsonObjectBuilder.playbackPayload(type: String) {
        when (type) {
            PlaybackMessageTypes.POSITION_REPORT -> {
                put("track_hash", TRACK_HASH)
                put("position_ms", 0)
                put("at_session_us", 90_210_500_000)
                put("playing", true)
                put("playback_rate", 1.0)
            }
            PlaybackMessageTypes.PLAYBACK_STATE -> {
                put("command_seq", 1)
                put("queue_revision", 0)
                put("track_hash", TRACK_HASH)
                put("queue_item_id", QUEUE_ITEM)
                put("position_ms", 0)
                put("playing", true)
                put("at_session_us", 90_210_500_000)
            }
            PlaybackMessageTypes.PLAY -> {
                playbackHeader()
                put("track_hash", TRACK_HASH)
                put("position_ms", 0)
                put("queue_item_id", QUEUE_ITEM)
            }
            PlaybackMessageTypes.SEEK -> {
                playbackHeader()
                put("target_position_ms", 1_000)
            }
            PlaybackMessageTypes.PAUSE, PlaybackMessageTypes.RESUME -> {
                playbackHeader()
                put("position_ms", 1_000)
            }
            else -> playbackHeader()
        }
    }

    private fun JsonObjectBuilder.queuePayload(type: String) {
        when (type) {
            QueueMessageTypes.ADD -> {
                put("command_seq", 1)
                put("queue_revision", 0)
                putJsonArray("items") {
                    add(
                        kotlinx.serialization.json.buildJsonObject {
                            put("queue_item_id", QUEUE_ITEM)
                            put("track_hash", TRACK_HASH)
                            put("added_by", "bbbbbbbbbbbbbbbb")
                            put("position", "end")
                        },
                    )
                }
            }
            QueueMessageTypes.REMOVE -> {
                put("command_seq", 1)
                put("queue_revision", 0)
                putJsonArray("queue_item_ids") { add(kotlinx.serialization.json.JsonPrimitive(QUEUE_ITEM)) }
            }
            QueueMessageTypes.MOVE -> {
                put("command_seq", 1)
                put("queue_revision", 0)
                put("queue_item_id", QUEUE_ITEM)
                put("to_index", 0)
            }
            else -> {
                put("queue_revision", 0)
                putJsonArray("items") {}
                put("current_index", null as String?)
            }
        }
    }

    // --- harness (mirrors ManifestTransferAuthenticationGateTest) --------------------------------

    private class Phone(
        val session: FsmSession,
        val peer: TestPeer,
    ) {
        suspend fun awaitPairingPrompt() = session.awaitPairingPrompt()

        suspend fun sendRawFrame(
            type: String,
            build: JsonObjectBuilder.() -> Unit,
        ) {
            session.manager.writeRawFrame(rawEnvelope(peer.peerId, type, build))
        }
    }

    private fun twoUnpairedPhones(body: suspend (Phone, Phone, Phase5Spy) -> Unit) =
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

                val spy = Phase5Spy()
                sessionA.manager.playback.playbackSink = PlaybackSink { spy.playback.add(it) }
                sessionA.manager.playback.queueSink = QueueSink { spy.queue.add(it) }

                val portA = sessionA.manager.startListening(a.local)
                val portB = sessionB.manager.startListening(b.local)
                for (session in listOf(sessionA, sessionB)) {
                    session.apply(SessionEvent.StartDiscovery)
                    session.apply(SessionEvent.PeerSelected)
                }
                sessionA.manager.connectTo("127.0.0.1", portB, a.local)
                sessionB.manager.connectTo("127.0.0.1", portA, b.local)

                body(Phone(sessionA, a), Phone(sessionB, b), spy)

                sessionA.manager.shutdown()
                sessionB.manager.shutdown()
            } finally {
                scope.cancel()
            }
        }

    private class Phase5Spy {
        val playback = CopyOnWriteArrayList<PlaybackMessage>()
        val queue = CopyOnWriteArrayList<QueueMessage>()
    }

    private companion object {
        const val SETTLE_MS = 400L
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 25L
        const val TRACK_HASH = "sha256:1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f"
        const val QUEUE_ITEM = "01J9Z4M3RT8V2W5X7Y9Z1A3B5C"
    }
}
