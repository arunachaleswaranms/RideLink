package com.ridelink.network.interop

import com.ridelink.core.model.ContentHash
import com.ridelink.core.playback.PlaybackBounds
import com.ridelink.core.playback.PlaybackCommandHeader
import com.ridelink.core.playback.PlaybackMessage
import com.ridelink.core.playback.QueueMessage
import com.ridelink.core.playback.SharedQueueItem
import com.ridelink.core.resync.ResyncMessage
import com.ridelink.core.resync.ResyncPlaybackSnapshot
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.control.TestSessions
import com.ridelink.network.playback.PlaybackSink
import com.ridelink.network.playback.QueueSink
import com.ridelink.network.resync.ResyncSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **Phase 8's cross-platform software integration gate: the Android/Kotlin half of one real session
 * with the iOS/Swift implementation.**
 *
 * This test does not run under an ordinary `./gradlew test`: with `RIDELINK_CROSS_DIR` unset it
 * returns immediately. It is started by `tools/crossplatform/run.sh`, which runs it and its Swift
 * counterpart (`RideLinkPlatformTests.CrossPlatformInteropTests`) as two processes on one machine,
 * joined by a **real TCP socket carrying the real RideLink protocol**: a real TLS 1.3 handshake with
 * mutual authentication, real ECDSA P-256 identities encoded by the shared DER encoder, the real
 * PROTOCOL §4.5 six-digit pairing exchange, real `PING`/`PONG` clock-sync bursts, and the real
 * Phase 5/Phase 7 relays and codecs.
 *
 * **The six digits are the sharpest assertion in the gate.** Each side derives them from its own
 * TLS exporter over its own stack; the protocol never compares them, because PROTOCOL §4.5 is two
 * humans comparing them out loud. The orchestrator compares the two files, which is the only place
 * that comparison can be made — and it is exactly the claim ADR-018 makes about the exporter being
 * the same computation on both platforms.
 *
 * ## What this gate is not
 *
 * It is not the interactive emulator ↔ simulator UI journey: no UI is driven and no app is launched.
 * It says nothing about Bluetooth, audio, background behaviour or a physical iPhone.
 *
 * And this half runs on the JVM against Conscrypt rather than on a device against Android's own TLS
 * stack — the pre-existing limitation `docs/test-results/phase1b-security-spike-20260827.md` already
 * records. This gate does not close it and does not pretend to.
 */
class CrossPlatformInteropTest {
    @Test
    fun `android half of a cross platform session`() {
        val dir = System.getenv("RIDELINK_CROSS_DIR")?.let(::File) ?: return
        val report = ConcurrentHashMap<String, Any>()
        report["platform"] = "android"
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + job)
        try {
            runBlocking { runSession(dir, scope, report) }
        } finally {
            job.cancel()
            File(dir, "android-report.json").writeText(toJson(report))
            File(dir, "android-done").writeText("done")
        }
    }

    @Suppress("LongMethod") // one linear transcript; splitting it would hide the order it asserts
    private suspend fun runSession(
        dir: File,
        scope: CoroutineScope,
        report: MutableMap<String, Any>,
    ) {
        val peer = TestSessions.unpairedPeer("3333444488889999", name = "RideLink-Android")
        val manager = peer.manager(scope, { System.nanoTime() / 1_000 })
        val events = mutableListOf<ControlEvent>()
        scope.launch { manager.events.collect { synchronized(events) { events.add(it) } } }

        val port = awaitFile(dir, "ios-port")?.trim()?.toIntOrNull() ?: error("the iOS half never published a port")
        report["port"] = port
        manager.startListening(peer.local)
        manager.connectTo("127.0.0.1", port, peer.local)

        val prompt = await(120_000) { manager.pairingPrompt.value } ?: error("no pairing prompt")
        report["sas6"] = prompt.sas6
        report["remotePeerId"] = prompt.remotePeerId.value
        report["peerDisplayName"] = prompt.peerDisplayName
        manager.confirmPairing(accepted = true)

        val connected =
            await(120_000) { connectedEvents(events).firstOrNull() } ?: error("never authenticated")
        report["sessionId"] = connected.sessionId.value
        report["isLocalLeader"] = connected.isLocalLeader
        report["authGeneration"] = connected.authGeneration
        report["connectedRemotePeerId"] = connected.remotePeerId.value
        report["trustedAfterPairing"] = peer.trustedPeers.all().size

        // Sinks first, **before** anything can be sent in either direction: a relay with no sink
        // drops the frame, so installing them after a clock wait would make the transcript depend on
        // which side's estimator converged first.
        val inbox = InteropInbox()
        manager.playback.playbackSink = inbox
        manager.playback.queueSink = inbox
        manager.resync.sink = inbox

        // ARCHITECTURE §7.1's real burst over the real socket.
        val estimate =
            await(60_000) {
                manager.clock.estimate.value
                    ?.takeIf { it.ready }
            }
        report["clockReady"] = estimate != null
        report["rttP95Us"] = estimate?.rttP95Us ?: -1L

        // A two-file rendezvous, so neither side sends into a peer that is not listening yet.
        File(dir, "android-ready").writeText("ready")
        await(120_000) { File(dir, "ios-ready").takeIf { it.exists() } } ?: error("the iOS half never became ready")

        // PROTOCOL §5: an authoritative PLAY, encoded by `PlaybackCodec` here and decoded by Swift's.
        val play =
            PlaybackMessage.Play(
                header =
                    PlaybackCommandHeader(
                        commandSeq = 5,
                        effectiveAtSessionUs = 9_876_543,
                        issuedBy = peer.peerId,
                        queueRevision = 7,
                    ),
                trackHash = ContentHash(HASH),
                positionMs = 2_500,
                queueItemId = QUEUE_ITEM_ID,
            )
        report["sentPlay"] = manager.playback.send(play, connected.authGeneration)

        // PROTOCOL §10: answer the iOS half's STATE_REQUEST with the authoritative payload.
        val requested = await(60_000) { inbox.stateRequestGeneration }
        report["receivedStateRequest"] = requested != null
        val snapshot =
            ResyncMessage.StateSnapshot(
                leaderPeerId = peer.peerId,
                commandSeq = 5,
                queueRevision = 7,
                playback =
                    ResyncPlaybackSnapshot(
                        trackHash = ContentHash(HASH),
                        queueItemId = QUEUE_ITEM_ID,
                        positionMs = 2_500,
                        playing = true,
                        atSessionUs = 9_876_543,
                    ),
                queueItems =
                    listOf(
                        SharedQueueItem(
                            queueItemId = QUEUE_ITEM_ID,
                            trackHash = ContentHash(HASH),
                            addedBy = peer.peerId,
                            order = PlaybackBounds.QUEUE_ORDER_STEP,
                        ),
                    ),
                queueCurrentIndex = 0,
                manifestRevision = 3,
                transfersInFlight = emptyList(),
            )
        report["sentStateSnapshot"] = manager.resync.send(snapshot, connected.authGeneration)

        report["receivedQueueSnapshot"] = await(60_000) { inbox.queueDescription } ?: "none"

        // A link loss and a reconnect. Both sides now hold a pin, so the successor must authenticate
        // **silently** and mint a strictly greater generation.
        await(120_000) { File(dir, "ios-phase").takeIf { it.exists() && it.readText().trim() == "reconnect" } }
        manager.shutdown()
        delay(RECONNECT_SETTLE_MS)
        manager.startListening(peer.freshLocal())
        manager.connectTo("127.0.0.1", port, peer.freshLocal())
        val second =
            await(120_000) { connectedEvents(events).getOrNull(1) } ?: error("never re-authenticated")
        report["reconnectAuthGeneration"] = second.authGeneration
        report["reconnectSessionId"] = second.sessionId.value
        report["pairingPromptCount"] = pairingPromptCount(events)
        assertTrue(second.authGeneration > connected.authGeneration, "a reconnect mints a greater generation")

        manager.playback.playbackSink = inbox
        manager.playback.queueSink = inbox
        manager.resync.sink = inbox
        File(dir, "android-reconnected").writeText("ready")
        report["receivedPlaybackStateAfterReconnect"] =
            await(60_000) { inbox.playbackStateDescription } ?: "none"
        report["ok"] = true
        manager.shutdown()
    }

    private fun connectedEvents(events: MutableList<ControlEvent>): List<ControlEvent.Connected> =
        synchronized(events) { events.filterIsInstance<ControlEvent.Connected>() }

    private fun pairingPromptCount(events: MutableList<ControlEvent>): Int =
        synchronized(events) { events.count { it is ControlEvent.PairingRequired } }

    private suspend fun <T : Any> await(
        timeoutMs: Long,
        probe: () -> T?,
    ): T? =
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                probe()?.let { return@withTimeoutOrNull it }
                delay(POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }

    private suspend fun awaitFile(
        dir: File,
        name: String,
    ): String? = await(120_000) { File(dir, name).takeIf { it.exists() }?.readText() }

    private fun toJson(values: Map<String, Any>): String =
        values.entries
            .sortedBy { it.key }
            .joinToString(prefix = "{\n", postfix = "\n}\n", separator = ",\n") { (key, value) ->
                val encoded =
                    when (value) {
                        is Boolean, is Int, is Long -> value.toString()
                        else -> "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                    }
                "  \"$key\": $encoded"
            }

    /** Records what the iOS half sent, decoded by the **production** codecs on this side. */
    private class InteropInbox :
        PlaybackSink,
        QueueSink,
        ResyncSink {
        @Volatile var queueDescription: String? = null

        @Volatile var playbackStateDescription: String? = null

        @Volatile var stateRequestGeneration: Long? = null

        override fun submit(
            message: PlaybackMessage,
            generation: Long,
        ) {
            val state = message as? PlaybackMessage.PlaybackStateSnapshot ?: return
            playbackStateDescription =
                "seq=${state.commandSeq} rev=${state.queueRevision} track=${state.trackHash?.value ?: "none"} " +
                "item=${state.queueItemId ?: "none"} pos=${state.positionMs} playing=${state.playing} " +
                "at=${state.atSessionUs} gen=$generation"
        }

        override fun submit(
            message: QueueMessage,
            generation: Long,
        ) {
            val snapshot = message as? QueueMessage.Snapshot ?: return
            queueDescription =
                "rev=${snapshot.queueRevision} items=${snapshot.items.size} index=${snapshot.currentIndex ?: -1}"
        }

        override fun submit(
            message: ResyncMessage,
            generation: Long,
        ) {
            if (message is ResyncMessage.StateRequest) stateRequestGeneration = generation
        }
    }

    private companion object {
        const val POLL_MS = 25L
        const val RECONNECT_SETTLE_MS = 300L

        /** Mirrors `SyncTestValues.ulid(8)`/`hash(8)` on the Swift side: valid ULID, valid digest. */
        const val QUEUE_ITEM_ID = "01J9Z4M0Q7XK2V8R3T6Y1N0008"
        const val HASH = "sha256:" + "0000000000000000000000000000000000000000000000000000000000000008"
    }
}
