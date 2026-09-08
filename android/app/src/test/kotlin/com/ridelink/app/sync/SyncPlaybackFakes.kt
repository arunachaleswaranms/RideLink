package com.ridelink.app.sync

import com.ridelink.core.library.LocalTrackLocation
import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.LocalEntryId
import com.ridelink.core.model.PeerId
import com.ridelink.core.playback.PlaybackMessage
import com.ridelink.core.playback.QueueMessage
import com.ridelink.core.player.PlayerState
import com.ridelink.core.sync.SessionClockEstimate
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.playback.PlaybackSink
import com.ridelink.network.playback.QueueSink
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

// Deterministic fakes for every Phase 5 port. Nothing here reads a real clock, opens a socket or
// touches a decoder — which is exactly what makes the assertions in this package about *ordering*
// and *lifetime* rather than about timing luck (this phase's brief §47/§50).

/** Fabricated identifiers only; no real `peer_id` and no real file's hash appears in this package. */
object SyncTestValues {
    val leaderPeerId = PeerId("a3f1000000000001")
    val followerPeerId = PeerId("b7c1000000000002")

    fun hash(seed: Int): ContentHash = ContentHash("sha256:%064x".format(seed))

    fun ulid(seed: Int): String = "01J9Z4M0Q7XK2V8R3T6Y1N%04d".format(seed).take(26).padEnd(26, '0')

    fun content(seed: Int): SyncPlayableContent =
        SyncPlayableContent(
            contentHash = hash(seed),
            localEntryId = LocalEntryId(UUID.nameUUIDFromBytes("track-$seed".toByteArray()).toString()),
            location = LocalTrackLocation("file:///tmp/track-$seed.m4a"),
            title = "Track $seed",
            artist = "Artist",
        )
}

/** A controllable [SyncSessionPort]: the session generation, the clock and the wire are all writable. */
class FakeSyncSession : SyncSessionPort {
    val sent = mutableListOf<Any>()

    override val playback: PlaybackChannelPort =
        object : PlaybackChannelPort {
            override var playbackSink: PlaybackSink? = null
            override var queueSink: QueueSink? = null

            override suspend fun send(message: PlaybackMessage): Boolean {
                sent.add(message)
                return true
            }

            override suspend fun send(message: QueueMessage): Boolean {
                sent.add(message)
                return true
            }
        }

    private val eventFlow = MutableSharedFlow<ControlEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<ControlEvent> = eventFlow.asSharedFlow()

    override var currentAuthGeneration: Long = 1

    private val clockFlow = MutableStateFlow<SessionClockEstimate?>(null)
    override val clockEstimate: StateFlow<SessionClockEstimate?> = clockFlow.asStateFlow()

    override var rttP95Us: Long? = 8_000

    fun setClock(estimate: SessionClockEstimate?) {
        clockFlow.value = estimate
    }

    suspend fun emit(event: ControlEvent) {
        eventFlow.emit(event)
    }

    /** What the peer would have received, in order, of one type. */
    inline fun <reified T> sentOfType(): List<T> = sent.filterIsInstance<T>()

    fun deliver(message: PlaybackMessage) = playback.playbackSink?.submit(message)

    fun deliver(message: QueueMessage) = playback.queueSink?.submit(message)
}

/** Records every player call in order — the whole assertion surface for "what did the audio do". */
class FakeSyncPlayer : SyncPlayerPort {
    sealed class Call {
        data class Prepare(
            val contentHash: ContentHash,
            val positionMs: Long,
        ) : Call()

        object Start : Call()

        object Pause : Call()

        data class Seek(
            val positionMs: Long,
        ) : Call()

        data class SetRate(
            val rate: Double,
        ) : Call()

        object Stop : Call()
    }

    val calls = mutableListOf<Call>()
    private val stateFlow = MutableStateFlow(PlayerState())
    override val playerState: StateFlow<PlayerState> = stateFlow.asStateFlow()

    fun setState(state: PlayerState) {
        stateFlow.value = state
    }

    override suspend fun prepare(
        content: SyncPlayableContent,
        positionMs: Long,
    ) {
        calls.add(Call.Prepare(content.contentHash, positionMs))
    }

    override suspend fun start() {
        calls.add(Call.Start)
    }

    override suspend fun pause() {
        calls.add(Call.Pause)
    }

    override suspend fun seek(positionMs: Long) {
        calls.add(Call.Seek(positionMs))
    }

    override suspend fun setRate(rate: Double) {
        calls.add(Call.SetRate(rate))
    }

    override suspend fun stop() {
        calls.add(Call.Stop)
    }
}

/** Scripted local/peer availability, and a record of every transfer Phase 5 asked Phase 4 for. */
class FakeSyncContent : SyncContentPort {
    val localHashes = mutableSetOf<String>()
    val peerHashes = mutableSetOf<String>()
    val transferRequests = mutableListOf<ContentHash>()

    /** Set to make `resolve` suspend, so a test can land a session boundary *inside* it. */
    var resolveGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun resolve(contentHash: ContentHash): SyncPlayableContent? {
        resolveGate?.await()
        if (contentHash.value !in localHashes) return null
        val seed = contentHash.value.takeLast(4).toInt(16)
        return SyncTestValues.content(seed).copy(contentHash = contentHash)
    }

    override fun peerHasContent(contentHash: ContentHash): Boolean = contentHash.value in peerHashes

    override fun requestTransfer(contentHash: ContentHash) {
        transferRequests.add(contentHash)
    }
}

/**
 * A virtual monotonic clock plus the sleeper that waits on it. Time only ever moves because a test
 * moved it, so every scheduling assertion is a statement about the algorithm rather than about how
 * busy the machine was.
 */
class FakeMonotonicClock(
    private var nowUs: Long = 1_000_000,
) {
    private val waiters = mutableListOf<Pair<Long, kotlinx.coroutines.CompletableDeferred<Unit>>>()

    val sleeper =
        SyncDeadlineSleeper { deadlineUs ->
            if (deadlineUs <= nowUs) return@SyncDeadlineSleeper
            val waiter = kotlinx.coroutines.CompletableDeferred<Unit>()
            waiters.add(deadlineUs to waiter)
            waiter.await()
        }

    fun nowUs(): Long = nowUs

    /** Advances the virtual clock and releases every waiter whose deadline has now passed. */
    fun advanceTo(instantUs: Long) {
        nowUs = instantUs
        val due = waiters.filter { it.first <= nowUs }
        waiters.removeAll(due)
        due.forEach { it.second.complete(Unit) }
    }

    fun advanceBy(deltaUs: Long) = advanceTo(nowUs + deltaUs)

    val pendingDeadlines: List<Long> get() = waiters.map { it.first }
}
