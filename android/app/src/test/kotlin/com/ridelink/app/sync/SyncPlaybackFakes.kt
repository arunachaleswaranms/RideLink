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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
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

    /**
     * ADR-024 Amendment A2 Finding C: what the authenticated transport answers. `PlaybackRelay.send`
     * returns false when there is no authenticated writer or the write throws, and A2 exists because
     * the drain used to count that as a send.
     */
    var sendResult: Boolean = true

    /**
     * Amendment A2 Finding B: parks the single outbound consumer **inside** a send, so a test can
     * land a session boundary strictly between one frame reaching the wire and the next being
     * considered — the real "socket is slow, backlog exists" shape, rather than a hoped-for
     * interleaving.
     */
    var sendGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** The authentication generation live at the instant each frame was actually written. */
    val sentGenerations = mutableListOf<Long>()

    private var forward: FakeSyncSession? = null

    override val playback: PlaybackChannelPort =
        object : PlaybackChannelPort {
            override var playbackSink: PlaybackSink? = null
            override var queueSink: QueueSink? = null

            override suspend fun send(
                message: PlaybackMessage,
                authorizingGeneration: Long,
            ): Boolean = write(message, authorizingGeneration) { forward?.deliver(message) }

            override suspend fun send(
                message: QueueMessage,
                authorizingGeneration: Long,
            ): Boolean = write(message, authorizingGeneration) { forward?.deliver(message) }
        }

    /**
     * `PlaybackRelay.write`, modelled exactly: park where the socket would, then refuse the frame
     * unless the generation that authorised it is still the live one (ADR-024 Amendment A2
     * Finding B). A real relay resolves the writer and the `session_id` for that generation and
     * writes to *that* socket; refusing here is the same guarantee expressed the way a fake can.
     */
    @Suppress("ReturnCount") // the gate, the scripted result and the generation check
    private suspend fun write(
        message: Any,
        authorizingGeneration: Long,
        deliver: () -> Unit,
    ): Boolean {
        sendGate?.await()
        if (!sendResult) return false
        if (authorizingGeneration != currentAuthGeneration) return false
        sent.add(message)
        sentGenerations.add(currentAuthGeneration)
        deliver()
        return true
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

    /**
     * Joins this peer's outbound wire to [other]'s inbound one — the in-process stand-in for the
     * control connection in the two-peer test. Delivery is immediate and ordered, which is what a
     * TCP control connection gives; what it deliberately does not model is TLS, framing or loss.
     */
    fun forwardTo(other: FakeSyncSession) {
        forward = other
    }

    fun deliver(message: PlaybackMessage) = playback.playbackSink?.submit(message, currentAuthGeneration)

    fun deliver(message: QueueMessage) = playback.queueSink?.submit(message, currentAuthGeneration)
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

    /** Fires on every recorded call — the two-peer test uses it to stamp *when* a start happened. */
    var onCall: ((Call) -> Unit)? = null
    private val stateFlow = MutableStateFlow(PlayerState())
    override val playerState: StateFlow<PlayerState> = stateFlow.asStateFlow()

    fun setState(state: PlayerState) {
        stateFlow.value = state
    }

    override suspend fun prepare(
        content: SyncPlayableContent,
        positionMs: Long,
    ) {
        record(Call.Prepare(content.contentHash, positionMs))
    }

    override suspend fun start() {
        record(Call.Start)
    }

    override suspend fun pause() {
        record(Call.Pause)
    }

    override suspend fun seek(positionMs: Long) {
        record(Call.Seek(positionMs))
    }

    override suspend fun setRate(rate: Double) {
        record(Call.SetRate(rate))
    }

    override suspend fun stop() {
        record(Call.Stop)
    }

    /**
     * Suspends a matching player call *after* it has been recorded, so a test can land a
     * supersession strictly **inside** it. ADR-024 Amendment A1 Finding F is exactly about what
     * happens after such a call returns, and a gate is the only way to assert it deterministically.
     */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var gateOn: ((Call) -> Boolean)? = null

    private suspend fun record(call: Call) {
        calls.add(call)
        onCall?.invoke(call)
        if (gateOn?.invoke(call) != true) return
        val parked = gate ?: return
        // **Deliberately not cancellable** (ADR-024 Amendment A3). `ExoPlayer.prepare` runs on the
        // application looper and `AVAudioEngine`'s callbacks are C callbacks: neither observes
        // coroutine or `Task` cancellation, so an apply that has been cancelled still returns from
        // them and carries on to its next statement. Modelling that here is what makes the
        // Amendment A3 tests prove the *generation fence* rather than merely proving that
        // cancellation happened — which is the whole point of A3's "cancellation alone is not
        // enough". It mirrors iOS, where `withCheckedContinuation` ignores cancellation by nature.
        withContext(NonCancellable) { parked.await() }
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

    private var availabilityObserver: (() -> Unit)? = null

    override fun observeAvailability(onAvailabilityChanged: () -> Unit) {
        availabilityObserver = onAvailabilityChanged
    }

    /**
     * The Phase 4 seam a test drives: mark content verified-locally and fire the same notification
     * `SharedLibraryCoordinator` fires after a successful `TransferCacheRepository.commit`.
     */
    fun completeTransfer(contentHash: ContentHash) {
        localHashes.add(contentHash.value)
        availabilityObserver?.invoke()
    }

    /** The peer half: it reported verifying a transfer we served (ADR-024 §7). */
    fun peerVerified(contentHash: ContentHash) {
        peerHashes.add(contentHash.value)
        availabilityObserver?.invoke()
    }

    /** A transfer that failed leaves availability exactly as it was, and still notifies. */
    fun failTransfer() {
        availabilityObserver?.invoke()
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
