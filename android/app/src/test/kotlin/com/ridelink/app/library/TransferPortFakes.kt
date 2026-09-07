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
        source: ChunkSource,
    ): BulkServeOutcome {
        serveCalls.add(transferId)
        serveGate?.await()
        return serveOutcome
    }

    override suspend fun fetch(
        host: String,
        port: Int,
        token: String,
        expectedPeerSpki: SpkiHash,
        expectedChunkCount: Long,
        sink: ChunkSink,
    ): BulkFetchOutcome = fetchOutcome

    override fun cancelActive() {
        cancelActiveCallCount += 1
    }

    override fun close() {
        closeCallCount += 1
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
