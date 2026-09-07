package com.ridelink.app.library

import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.SpkiHash
import com.ridelink.core.model.TransferId
import com.ridelink.core.protocol.ManifestMessage
import com.ridelink.core.protocol.TransferMessage
import com.ridelink.data.database.LocationQuickIdRow
import com.ridelink.data.database.TrackDao
import com.ridelink.data.database.TrackEntity
import com.ridelink.data.database.TransferCacheDao
import com.ridelink.data.database.TransferCacheEntity
import com.ridelink.data.transfer.ContentResolution
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.manifest.ManifestSink
import com.ridelink.network.transfer.BulkFetchOutcome
import com.ridelink.network.transfer.BulkServeOutcome
import com.ridelink.network.transfer.ChunkSink
import com.ridelink.network.transfer.ChunkSource
import com.ridelink.network.transfer.TransferSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/*
 * ADR-023 Amendment A3 — coordinator-level test support. Everything in this file is a fake, never
 * a mock: it exists to make SharedLibraryCoordinator's real provider-path logic runnable and
 * deterministically controllable from a plain JVM unit test, exactly the way
 * FakeTrackDao/FakeTransferCacheDao (`data` module) do for TransferCacheRepository. Two of the
 * interfaces below (TrackDao, TransferCacheDao) are stubbed with `error(...)` bodies for methods
 * this suite's scenarios never reach — SharedLibraryCoordinator only ever calls
 * `TransferCacheRepository.verifiedHashes()` (via its own `init` block) and never touches the
 * manifest-generation path at all in these tests, since every scenario here dispatches
 * `TRANSFER_*`, never `MANIFEST_*`.
 */

/**
 * A controllable [com.ridelink.app.library.ContentResolverPort.resolve]: suspends on [gate] until
 * the test releases it, and/or runs [onBeforeReturn] synchronously just before returning — the hook
 * that lets a test land a session boundary in the narrow window *after* resolution but *before*
 * [BulkOperationGate][com.ridelink.core.transfer.BulkOperationGate] acquisition, distinct from a
 * boundary landing *during* the suspension itself.
 */
class GatedContentResolver(
    private val gate: CompletableDeferred<Unit>? = null,
    private val onBeforeReturn: (() -> Unit)? = null,
) : ContentResolverPort {
    var resolution: ContentResolution = ContentResolution.NotFound
    var resolveCallCount = 0
        private set

    override suspend fun resolve(
        contentHash: ContentHash,
        nowMonoUs: Long,
    ): ContentResolution {
        resolveCallCount += 1
        gate?.await()
        onBeforeReturn?.invoke()
        return resolution
    }
}

/**
 * Records every call [SharedLibraryCoordinator]'s provider/requester paths make on the bulk
 * transport. [ensureListeningGate]/[serveGate] are real suspension points a test can hold open
 * with a [CompletableDeferred] to land a session boundary inside them, exactly the way the real
 * `BulkTransportManager` methods they stand in for actually suspend in production.
 */
class FakeBulkTransportPort : BulkTransportPort {
    var ensureListeningCallCount = 0
        private set
    val issuedTokenCalls = mutableListOf<Pair<TransferId, Long>>()
    var tokenToIssue: String? = "fake-bulk-token"
    val serveCalls = mutableListOf<TransferId>()
    var serveOutcome = BulkServeOutcome.OK

    /** Held open, [serve] stays genuinely in flight — for proving cross-role exclusion while a
     *  provider operation is still active, rather than one that already finished synchronously. */
    var serveGate: CompletableDeferred<Unit>? = null
    var fetchOutcome = BulkFetchOutcome.OK
    var cancelActiveCallCount = 0
        private set

    /** ADR-023 Amendment A5: every `transfer_id` [cancelActive] was called with, in order — so a
     *  test can assert a cancel was routed to the transfer it actually named. */
    val cancelActiveCalls = mutableListOf<TransferId>()

    /** ADR-023 Amendment A5 Finding B: when set, [ensureListening] throws it instead of returning a
     *  port — the real `BulkTransportManager.ensureListening()` now fails exactly this way when a
     *  session boundary ends the listener lifetime while its `bind()` is still in flight. */
    var ensureListeningFailure: java.io.IOException? = null
    var closeCallCount = 0
        private set

    /** A real suspension point a test can hold open with a [CompletableDeferred] — the same
     *  mechanism [GatedContentResolver] uses for `resolve()` — to land a session boundary inside
     *  `ensureListening()`'s own suspension, exactly as the real `Mutex.withLock` inside
     *  `BulkTransportManager.ensureListening()` allows in production. */
    var ensureListeningGate: CompletableDeferred<Unit>? = null

    override suspend fun ensureListening(): Int {
        ensureListeningCallCount += 1
        ensureListeningGate?.await()
        ensureListeningFailure?.let { throw it }
        return FAKE_PORT
    }

    override fun tryIssueToken(
        transferId: TransferId,
        generation: Long,
    ): String? {
        issuedTokenCalls.add(transferId to generation)
        return tokenToIssue
    }

    override suspend fun serve(
        transferId: TransferId,
        expectedPeerSpki: SpkiHash,
        currentGeneration: () -> Long,
        expectedChunkCount: Long,
        source: ChunkSource,
    ): BulkServeOutcome {
        serveCalls.add(transferId)
        serveGate?.await()
        return serveOutcome
    }

    /** Held open, [fetch] stays genuinely in flight — the requester-role twin of [serveGate], for
     *  landing a user cancellation or a session boundary *during* a real transfer rather than
     *  before or after one. Completed by [cancelActive] too, mirroring how the real
     *  `BulkTransportManager.cancelActive(transferId)` force-closes the socket a blocked `fetch` is parked on. */
    var fetchGate: CompletableDeferred<Unit>? = null
    val fetchCalls = mutableListOf<TransferId>()

    /** Bytes to deliver through the [ChunkSink] as chunk 0 before returning [fetchOutcome] —
     *  `null` delivers nothing. A test that needs `promote()` to actually succeed must set this to
     *  a payload whose SHA-256 really is the requested `content_hash`; the promote path recomputes
     *  the hash from the file on disk (ADR-023 §6) and will not be fooled. */
    var fetchPayload: ByteArray? = null

    @Suppress("LongParameterList")
    override suspend fun fetch(
        transferId: TransferId,
        host: String,
        port: Int,
        token: String,
        expectedPeerSpki: SpkiHash,
        expectedChunkCount: Long,
        sink: ChunkSink,
    ): BulkFetchOutcome {
        fetchCalls.add(transferId)
        fetchPayload?.let { sink.onChunk(0L, it) }
        fetchGate?.await()
        return fetchOutcome
    }

    /**
     * The real `BulkTransportManager.cancelActive(transferId)` force-closes the socket the active
     * `serve`/`fetch` call is parked on, which is what makes that call return promptly instead of
     * hanging. Releasing the gates models exactly that, so a test drives the *production* unblock
     * path. The outcome the unblocked call then returns is the test's to choose ([fetchOutcome]) —
     * a force-closed socket normally yields a failure, but a transfer whose bytes had all already
     * arrived returns `OK`, and that is the case Amendment A4's Finding V is about.
     */
    override fun cancelActive(transferId: TransferId) {
        cancelActiveCallCount += 1
        cancelActiveCalls.add(transferId)
        fetchGate?.complete(Unit)
        serveGate?.complete(Unit)
    }

    /** The real `close()` force-closes whatever operation is live (ADR-023 §1) — so it unblocks
     *  too, and does so *synchronously*, which is the ordering Amendment A4's Finding V turns on.
     *  Unconditional, unlike [cancelActive]: a session boundary tears down whatever is live no
     *  matter whose it is, so this does not record a [cancelActiveCalls] entry. */
    override fun close() {
        closeCallCount += 1
        cancelActiveCallCount += 1
        fetchGate?.complete(Unit)
        serveGate?.complete(Unit)
    }

    private companion object {
        const val FAKE_PORT = 45_000
    }
}

/** A fake [ManifestChannelPort]/[TransferChannelPort]: records every outbound message. */
class FakeManifestChannel : ManifestChannelPort {
    override var sink: ManifestSink? = null
    val sent = mutableListOf<ManifestMessage>()

    override suspend fun send(message: ManifestMessage): Boolean {
        sent.add(message)
        return true
    }
}

class FakeTransferChannel : TransferChannelPort {
    override var sink: TransferSink? = null
    val sent = mutableListOf<TransferMessage>()

    override suspend fun send(message: TransferMessage): Boolean {
        sent.add(message)
        return true
    }
}

/**
 * A fake [TransferSessionPort] whose live generation/peer are plain settable `var`s — a test
 * "moves" the session simply by writing new values and, if it needs [SharedLibraryCoordinator]'s
 * own `init`-installed collector to run `onSessionBoundary()` (exactly as a real reconnect would),
 * emitting a [ControlEvent] through [emitEvent].
 */
class FakeTransferSessionPort(
    generation: Long,
    peerSpki: SpkiHash?,
    peerHost: String? = "192.0.2.1",
) : TransferSessionPort {
    override val manifest = FakeManifestChannel()
    override val transfer = FakeTransferChannel()

    private val _events = MutableSharedFlow<ControlEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<ControlEvent> = _events.asSharedFlow()

    override var currentAuthGeneration: Long = generation
    override var currentPeerSpki: SpkiHash? = peerSpki
    override var currentPeerHost: String? = peerHost

    /** Simulates a session boundary the same way production does: bump the live view, then emit
     *  the event [SharedLibraryCoordinator]'s own `init`-installed collector reacts to. */
    fun emitEvent(event: ControlEvent) {
        check(_events.tryEmit(event)) { "test buffer overflow — increase extraBufferCapacity" }
    }
}

/** Never invoked by any scenario in this suite — every method throws to prove that. */
class UnusedTrackDao : TrackDao {
    override suspend fun findByLocalEntryId(localEntryId: String): TrackEntity? = error("not used by this test")

    override suspend fun findByLocationUri(locationUri: String): TrackEntity? = error("not used by this test")

    override suspend fun findByContentHash(contentHash: String): TrackEntity? = error("not used by this test")

    override suspend fun allLocationsAndQuickIds(): List<LocationQuickIdRow> = error("not used by this test")

    override suspend fun findMissingContentHash(): List<TrackEntity> = error("not used by this test")

    override suspend fun findAllSyncEligible(): List<TrackEntity> = error("not used by this test")

    override suspend fun insertNew(entity: TrackEntity): Long = error("not used by this test")

    @Suppress("LongParameterList")
    override suspend fun updateReindexed(
        localEntryId: String,
        quickId: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        filename: String,
        codec: String,
        bitrateKbps: Int,
        artworkRef: String?,
        sizeBytes: Long,
        decodeStatus: String,
        lastSeenAtMonoUs: Long,
    ): Unit = error("not used by this test")

    override suspend fun updateContentHash(
        localEntryId: String,
        contentHash: String?,
    ): Unit = error("not used by this test")

    override suspend fun touchSeen(
        locationUri: String,
        lastSeenAtMonoUs: Long,
    ): Unit = error("not used by this test")

    override suspend fun markMissing(
        locationUri: String,
        lastSeenAtMonoUs: Long,
    ): Unit = error("not used by this test")

    override suspend fun deleteByLocalEntryId(localEntryId: String): Unit = error("not used by this test")

    override fun observeAll(): Flow<List<TrackEntity>> = error("not used by this test")

    override fun observeSearch(ftsQuery: String): Flow<List<TrackEntity>> = error("not used by this test")

    override suspend fun count(): Int = error("not used by this test")

    override suspend fun deleteAll(): Unit = error("not used by this test")
}

/**
 * A real, working in-memory [TransferCacheDao] — needed by any scenario that lets a transfer run
 * all the way to `TransferCacheRepository.commit`, because [EmptyTransferCacheDao] below throws on
 * `upsertVerified` and would turn a genuine "this committed when it must not have" bug into an
 * indistinguishable commit *failure*. Eviction is never exercised by these suites, so
 * [evictionCandidates] returns nothing rather than implementing an LRU no test reads.
 */
class InMemoryTransferCacheDao : TransferCacheDao {
    private val rows = linkedMapOf<String, TransferCacheEntity>()

    override suspend fun findVerified(contentHash: String): TransferCacheEntity? = rows[contentHash]?.takeIf { it.verified }

    override suspend fun upsertVerified(entity: TransferCacheEntity) {
        rows[entity.contentHash] = entity
    }

    override suspend fun touchAccess(
        contentHash: String,
        atMonoUs: Long,
    ) {
        rows[contentHash]?.let { rows[contentHash] = it.copy(lastAccessAtMonoUs = atMonoUs) }
    }

    override suspend fun delete(contentHash: String) {
        rows.remove(contentHash)
    }

    override suspend fun evictionCandidates(locked: List<String>): List<TransferCacheEntity> = emptyList()

    override suspend fun totalBytes(): Long = rows.values.sumOf { it.sizeBytes }

    override suspend fun all(): List<TransferCacheEntity> = rows.values.toList()

    override suspend fun deleteAll() {
        rows.clear()
    }
}

/**
 * Always reports "nothing cached" — [findVerified] and [all] are legitimately reached
 * ([com.ridelink.data.transfer.TransferCacheRepository.isVerifiedCached] from
 * [SharedLibraryCoordinator.requestDownload], and `verifiedHashes()` from the coordinator's own
 * `init`) — everything else this suite never exercises throws to prove it.
 */
class EmptyTransferCacheDao : TransferCacheDao {
    override suspend fun findVerified(contentHash: String): TransferCacheEntity? = null

    override suspend fun upsertVerified(entity: TransferCacheEntity): Unit = error("not used by this test")

    override suspend fun touchAccess(
        contentHash: String,
        atMonoUs: Long,
    ): Unit = error("not used by this test")

    override suspend fun delete(contentHash: String): Unit = error("not used by this test")

    override suspend fun evictionCandidates(locked: List<String>): List<TransferCacheEntity> = error("not used by this test")

    override suspend fun totalBytes(): Long = error("not used by this test")

    override suspend fun all(): List<TransferCacheEntity> = emptyList()

    override suspend fun deleteAll(): Unit = error("not used by this test")
}
