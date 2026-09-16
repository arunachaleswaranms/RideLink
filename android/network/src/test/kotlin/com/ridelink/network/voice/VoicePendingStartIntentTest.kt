package com.ridelink.network.voice

import com.ridelink.core.protocol.VoiceSessionId
import com.ridelink.core.protocol.VoiceSignal
import com.ridelink.core.voice.VoiceEngineEvent
import com.ridelink.core.voice.VoiceStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Android's start is synchronous; these prove the shared controller semantics, not iOS reachability. */
class VoicePendingStartIntentTest {
    @Test
    fun `both orders start exactly once and bind all outbound effects to B`() {
        for (leader in listOf(true, false)) {
            for (connectedFirst in listOf(true, false)) {
                withHarness(leader) { h ->
                    if (connectedFirst) h.voice.controlAuthenticated(2)
                    h.voice.start(null)
                    h.drain()
                    if (!connectedFirst) {
                        assertEquals(VoiceStatus.IDLE, h.voice.diagnostics.value.status)
                        assertTrue(h.audio.isOpen)
                        assertTrue(h.transport.sent.isEmpty())
                        h.voice.controlAuthenticated(2)
                    }
                    h.drain()
                    assertEquals(VoiceStatus.NEGOTIATING, h.voice.diagnostics.value.status)
                    val session = id(2)
                    if (leader) h.engine.emit(VoiceEngineEvent.OfferCreated(session, SDP))
                    h.drain()
                    val sentBeforeDuplicate = h.transport.sent.toList()
                    h.voice.controlAuthenticated(2)
                    h.voice.start(2)
                    h.drain()
                    assertEquals(if (leader) 1 else 0, h.engine.calls.count { it == "createOffer" })
                    assertEquals(sentBeforeDuplicate, h.transport.sent)
                    assertEquals(if (leader) 1 else 0, h.transport.sent.count { it is VoiceSignal.Offer })
                    assertTrue(h.transport.sent.isNotEmpty())
                    assertTrue(h.transport.sentGenerations.all { it == 2L })
                    h.voice.onControlLinkLost(1)
                    h.drain()
                    assertEquals(VoiceStatus.NEGOTIATING, h.voice.diagnostics.value.status)
                    h.voice.onControlLinkLost(2)
                    h.drain()
                    assertEquals(VoiceStatus.IDLE, h.voice.diagnostics.value.status)
                    assertTrue(h.audio.isOpen)
                }
            }
        }
    }

    @Test
    fun `failed critical sends do not create retries for either role`() {
        for (leader in listOf(true, false)) {
            withHarness(leader) { h ->
                h.voice.start(null)
                h.drain()
                if (!leader) h.transport.accept = false
                h.voice.controlAuthenticated(2)
                h.drain()
                if (leader) {
                    h.transport.accept = false
                    h.engine.emit(VoiceEngineEvent.OfferCreated(id(2), SDP))
                    h.drain()
                }
                assertEquals(VoiceStatus.IDLE, h.voice.diagnostics.value.status)
                assertTrue(h.audio.isOpen)
                val count = h.transport.attempted.size
                val offers = h.engine.calls.count { it == "createOffer" }
                assertEquals(if (leader) 2 else 1, count)
                repeat(5) {
                    h.voice.controlAuthenticated(2)
                    h.drain()
                    assertEquals(count, h.transport.attempted.size)
                    assertEquals(offers, h.engine.calls.count { it == "createOffer" })
                }
                h.live = 3
                h.transport.accept = true
                h.voice.controlAuthenticated(3)
                h.drain()
                assertEquals(VoiceStatus.NEGOTIATING, h.voice.diagnostics.value.status)
                assertEquals(3L, h.transport.sentGenerations.last())
            }
        }
    }

    @Test
    fun `B retired before consuming intent leaves only C work`() =
        withHarness(true) { h ->
            h.voice.start(null)
            h.drain()
            h.voice.controlAuthenticated(2)
            h.voice.onControlLinkLost(2)
            h.live = 3
            h.voice.controlAuthenticated(3)
            h.drain()
            assertEquals(1, h.engine.calls.count { it == "createOffer" })
            assertTrue(h.transport.sentGenerations.all { it == 3L })
            assertEquals(id(3).toString(), h.voice.diagnostics.value.voiceSessionPrefix)
        }

    @Test
    fun `a suspended B offer cannot execute through C`() =
        withHarness(true) { h ->
            h.voice.start(null)
            h.drain()
            h.voice.controlAuthenticated(2)
            h.drain()
            h.transport.parkWhen = { it is VoiceSignal.Offer }
            h.engine.emit(VoiceEngineEvent.OfferCreated(id(2), SDP))
            h.drain()
            assertTrue(h.transport.parked)
            h.live = 3
            h.voice.onControlLinkLost(2)
            h.voice.controlAuthenticated(3)
            h.transport.release(true)
            h.drain()
            assertFalse(h.transport.sent.any { it is VoiceSignal.Offer && it.voiceSessionId == id(2) })
            assertEquals(
                listOf(2L),
                h.transport.attempted
                    .filter { it.first is VoiceSignal.Offer }
                    .map { it.second },
            )
            assertEquals(2, h.engine.calls.count { it == "createOffer" })
        }

    @Test
    fun `Stop clears a gap press before B`() =
        withHarness(true) { h ->
            h.voice.start(null)
            h.drain()
            h.voice.stop()
            h.drain()
            assertFalse(h.audio.isOpen)
            h.voice.controlAuthenticated(2)
            h.drain()
            assertTrue(h.transport.sent.isEmpty())
            assertEquals(0, h.engine.calls.count { it == "createOffer" })
        }

    private class Harness(
        leader: Boolean,
    ) {
        val dispatcher = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val engine = FakeVoiceEngine()
        val audio = FakeVoiceAudioSession()
        val transport = RecordingVoiceTransport()
        var live: Long? = 2
        private var ids = 0
        val voice =
            VoiceController(
                scope = scope,
                engine = engine,
                audioSession = audio,
                transport = transport,
                isLocalLeader = leader,
                localTrackId = "ridelink-voice",
                newVoiceSessionId = { id(++ids) },
            )

        init {
            transport.liveGeneration = { live }
        }

        fun drain() = dispatcher.runAll()
    }

    private class ManualDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            synchronized(tasks) { tasks.addLast(block) }
        }

        fun runAll() {
            while (true) {
                val next = synchronized(tasks) { if (tasks.isEmpty()) null else tasks.removeFirst() }
                next?.run() ?: return
            }
        }
    }

    private fun withHarness(
        leader: Boolean,
        body: (Harness) -> Unit,
    ) {
        val h = Harness(leader)
        try {
            body(h)
        } finally {
            h.scope.cancel()
        }
    }

    private companion object {
        const val SDP = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\n"

        fun id(n: Int): VoiceSessionId = VoiceSessionId(n.toString().repeat(32))
    }
}
