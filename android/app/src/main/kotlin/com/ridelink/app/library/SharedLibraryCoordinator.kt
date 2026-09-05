package com.ridelink.app.library

import com.ridelink.core.manifest.ManifestEntry
import com.ridelink.core.manifest.ManifestKind
import com.ridelink.core.manifest.ManifestPaging
import com.ridelink.core.manifest.ManifestSyncEvent
import com.ridelink.core.manifest.ManifestSyncStateMachine
import com.ridelink.core.manifest.ManifestSyncStepResult
import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.ManifestId
import com.ridelink.core.model.TransferId
import com.ridelink.core.protocol.ManifestMessage
import com.ridelink.core.protocol.TransferMessage
import com.ridelink.core.transfer.TransferError
import com.ridelink.core.transfer.TransferStatus
import com.ridelink.data.transfer.CacheStorage
import com.ridelink.data.transfer.ContentResolution
import com.ridelink.data.transfer.LocalContentResolver
import com.ridelink.data.transfer.ManifestGenerator
import com.ridelink.data.transfer.PromoteResult
import com.ridelink.data.transfer.TransferCacheRepository
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.control.ControlSessionManager
import com.ridelink.network.manifest.ManifestSink
import com.ridelink.network.transfer.BulkFetchOutcome
import com.ridelink.network.transfer.BulkTransportManager
import com.ridelink.network.transfer.ChunkSink
import com.ridelink.network.transfer.ChunkSource
import com.ridelink.network.transfer.TransferSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** What the UI shows for one `ContentHash`'s transfer, if any is or ever was in flight this session. */
data class DownloadState(
    val status: TransferStatus,
    val bytesReceived: Long = 0,
    val totalBytes: Long = 0,
    val error: TransferError? = null,
)

/**
 * The single owner of Phase 4 shared-library state (CLAUDE.md rule 8, applied to the catalogue/
 * transfer plane the way [com.ridelink.app.music.MusicCoordinator] applies it to local music and
 * [com.ridelink.app.session.SessionCoordinator] applies it to the control plane). No screen holds
 * remote-catalogue or transfer state of its own.
 *
 * **Session/peer scoping (brief §6/§22).** [remoteEntries] is cleared to empty on every
 * [ControlEvent.Connected] and [ControlEvent.LinkLost] — a peer's manifest never survives past its
 * own session, and a different peer's catalogue can never appear left over from a previous one.
 * The verified transfer cache ([TransferCacheRepository]) is **not** cleared here: it is
 * independent of any session (ADR-023).
 *
 * **Concurrency (brief §20).** One active transfer at a time; further requests queue FIFO in
 * [downloadQueue]. **Playback (brief §19)** of a verified cached file goes through the existing
 * [com.ridelink.app.music.MusicCoordinator]/queue/player — this class never creates a second
 * player or a second queue, and never synchronizes the peer's playback.
 */
@Suppress("LongParameterList") // one collaborator per Phase 4 layer (core/network/data), matching AppContainer's own composition-root style
class SharedLibraryCoordinator(
    private val scope: CoroutineScope,
    private val monotonicNowUs: () -> Long,
    private val manifestGenerator: ManifestGenerator,
    private val cacheRepository: TransferCacheRepository,
    private val cacheStorage: CacheStorage,
    private val contentResolver: LocalContentResolver,
    private val bulkTransport: BulkTransportManager,
    private val controlSessionManager: ControlSessionManager,
    private val nextTransferId: () -> TransferId,
    private val nextManifestId: () -> ManifestId,
) {
    private val _remoteEntries = MutableStateFlow<List<ManifestEntry>>(emptyList())
    val remoteEntries: StateFlow<List<ManifestEntry>> = _remoteEntries.asStateFlow()

    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates.asStateFlow()

    private var syncMachine: ManifestSyncStateMachine? = null
    private val transferMutex = Mutex()
    private val downloadQueue = ArrayDeque<ContentHash>()
    private var activeDownload: ContentHash? = null
    private var pendingOffer: CompletableDeferred<TransferMessage.Offer>? = null
    private var pendingOfferTransferId: TransferId? = null

    init {
        controlSessionManager.manifest.sink = ManifestSink { message -> scope.launch { handleManifestMessage(message) } }
        controlSessionManager.transfer.sink = TransferSink { message -> scope.launch { handleTransferMessage(message) } }
        scope.launch {
            controlSessionManager.events.collect { event ->
                when (event) {
                    is ControlEvent.Connected -> {
                        onSessionBoundary()
                        requestCatalogue()
                    }
                    is ControlEvent.LinkLost -> onSessionBoundary()
                    else -> Unit
                }
            }
        }
    }

    /** True once bytes have arrived, been whole-file verified, **and** committed — never merely queued or transferring. */
    suspend fun isVerifiedCached(contentHash: ContentHash): Boolean = cacheRepository.isVerifiedCached(contentHash)

    /** brief §19: hands a verified cached file's location to the caller, which plays it through the *existing* player. */
    suspend fun cachedFile(contentHash: ContentHash): File? = cacheRepository.open(contentHash, monotonicNowUs())

    /** Requests the peer's full catalogue — called once per session, on [ControlEvent.Connected]. */
    fun requestCatalogue() {
        scope.launch {
            controlSessionManager.manifest.send(
                ManifestMessage.Request(sinceRevision = null, maxPageBytes = ManifestPaging.MANIFEST_PAGE_SOFT_LIMIT_BYTES),
            )
        }
    }

    /** brief §17: never re-transfers content already held locally or in the verified cache. */
    fun requestDownload(entry: ManifestEntry) {
        val hash = entry.contentHash ?: return
        scope.launch {
            if (cacheRepository.isVerifiedCached(hash)) return@launch
            transferMutex.withLock {
                if (hash in downloadQueue || activeDownload == hash) return@withLock
                downloadQueue.addLast(hash)
                setState(hash, DownloadState(TransferStatus.QUEUED))
            }
            pumpQueue()
        }
    }

    fun cancelDownload(contentHash: ContentHash) {
        scope.launch {
            transferMutex.withLock { downloadQueue.remove(contentHash) }
            if (activeDownload == contentHash) {
                pendingOffer?.cancel()
                setState(contentHash, DownloadState(TransferStatus.CANCELLED))
            }
        }
    }

    private fun onSessionBoundary() {
        // brief §6/§22: a peer's catalogue is session/peer-scoped and must never leak across a
        // reconnect or a different peer — replaced wholesale, never merged with what came before.
        _remoteEntries.value = emptyList()
        syncMachine = null
        scope.launch {
            transferMutex.withLock {
                downloadQueue.clear()
                activeDownload = null
            }
            pendingOffer?.cancel()
        }
    }

    // --- manifest: both roles, since both peers are symmetric --------------------------------------

    private suspend fun handleManifestMessage(message: ManifestMessage) {
        when (message) {
            is ManifestMessage.Request -> serveManifestRequest(message)
            is ManifestMessage.Begin -> {
                val machine = ManifestSyncStateMachine(0)
                syncMachine = machine
                machine.apply(
                    ManifestSyncEvent.Begin(
                        message.manifestId,
                        message.kind,
                        message.manifestRevision,
                        message.baseRevision,
                        message.totalEntries,
                        message.totalRemoved,
                    ),
                )
            }
            is ManifestMessage.Page ->
                syncMachine?.apply(
                    ManifestSyncEvent.Page(
                        message.manifestId,
                        message.manifestRevision,
                        message.pageIndex,
                        message.entries,
                        message.removed,
                    ),
                )
            is ManifestMessage.End -> {
                val result =
                    syncMachine?.apply(
                        ManifestSyncEvent.End(
                            message.manifestId,
                            message.manifestRevision,
                            message.pageCount,
                            message.totalEntries,
                            message.totalRemoved,
                            message.digest,
                        ),
                    )
                if (result is ManifestSyncStepResult.Committed) {
                    // Atomic swap, exactly ADR-013's rule: the whole catalogue replaces the old one at once.
                    _remoteEntries.value = result.entries
                }
                syncMachine = null
            }
            is ManifestMessage.Abort -> syncMachine = null
        }
    }

    private suspend fun serveManifestRequest(request: ManifestMessage.Request) {
        val entries = manifestGenerator.generate()
        val budget = minOf(ManifestPaging.MANIFEST_PAGE_SOFT_LIMIT_BYTES, request.maxPageBytes)
        val pages = ManifestPaging.paginate(entries, budget)
        val manifestId = nextManifestId()
        val revision = 1L
        controlSessionManager.manifest.send(
            ManifestMessage.Begin(manifestId, ManifestKind.FULL, revision, null, entries.size, 0, pages.size, "ridelink-manifest-v1"),
        )
        pages.forEachIndexed { index, page ->
            controlSessionManager.manifest.send(ManifestMessage.Page(manifestId, revision, index, page, emptyList()))
        }
        val clamped = entries.map(ManifestPaging::clampEntry)
        controlSessionManager.manifest.send(
            ManifestMessage.End(manifestId, revision, pages.size, entries.size, 0, ManifestPaging.digest(clamped, emptyList())),
        )
    }

    // --- transfer: requester side --------------------------------------------------------------

    private fun pumpQueue() {
        scope.launch {
            val next =
                transferMutex.withLock {
                    if (activeDownload != null) return@withLock null
                    downloadQueue.removeFirstOrNull()?.also { activeDownload = it }
                }
            if (next != null) runDownload(next)
        }
    }

    @Suppress("ReturnCount")
    private suspend fun runDownload(hash: ContentHash) {
        val transferId = nextTransferId()
        setState(hash, DownloadState(TransferStatus.NEGOTIATING))
        val deferred = CompletableDeferred<TransferMessage.Offer>()
        pendingOffer = deferred
        pendingOfferTransferId = transferId
        controlSessionManager.transfer.send(TransferMessage.Request(hash, transferId))

        val offer = withTimeoutOrNull(NEGOTIATION_TIMEOUT_MS) { deferred.await() }
        if (offer == null) {
            finishDownload(hash, DownloadState(TransferStatus.FAILED, error = TransferError.NOT_FOUND))
            return
        }
        val peerSpki = controlSessionManager.currentPeerSpki
        val peerHost = controlSessionManager.currentPeerHost
        if (peerSpki == null || peerHost == null) {
            finishDownload(hash, DownloadState(TransferStatus.FAILED, error = TransferError.CONNECTION_LOST))
            return
        }

        setState(hash, DownloadState(TransferStatus.TRANSFERRING, totalBytes = offer.sizeBytes))
        val stream = cacheStorage.openPartForWrite(hash)
        var received = 0L
        val sink =
            ChunkSink { _, bytes ->
                cacheStorage.appendChunk(stream, bytes)
                received += bytes.size
                setState(hash, DownloadState(TransferStatus.TRANSFERRING, bytesReceived = received, totalBytes = offer.sizeBytes))
            }
        val outcome =
            bulkTransport.fetch(peerHost, offer.bulkPort, offer.bulkToken, peerSpki, offer.chunkCount.toLong(), sink)
        stream.close()

        if (outcome != BulkFetchOutcome.OK) {
            cacheStorage.deletePart(hash)
            finishDownload(hash, DownloadState(TransferStatus.FAILED, error = outcome.toTransferError()))
            return
        }
        setState(hash, DownloadState(TransferStatus.VERIFYING, totalBytes = offer.sizeBytes))
        when (val promoteResult = cacheStorage.promote(hash, offer.sizeBytes)) {
            PromoteResult.PROMOTED -> {
                cacheRepository.commit(hash, offer.sizeBytes, monotonicNowUs())
                controlSessionManager.transfer.send(TransferMessage.Result(transferId, true, hash))
                finishDownload(hash, DownloadState(TransferStatus.COMPLETE, totalBytes = offer.sizeBytes))
            }
            else -> {
                controlSessionManager.transfer.send(TransferMessage.Result(transferId, false, null))
                finishDownload(hash, DownloadState(TransferStatus.FAILED, error = promoteResult.toTransferError()))
            }
        }
    }

    private fun finishDownload(
        hash: ContentHash,
        state: DownloadState,
    ) {
        setState(hash, state)
        scope.launch {
            transferMutex.withLock { if (activeDownload == hash) activeDownload = null }
            pumpQueue()
        }
    }

    private fun onOfferReceived(message: TransferMessage.Offer) {
        if (pendingOfferTransferId == message.transferId) pendingOffer?.complete(message)
    }

    private fun setState(
        hash: ContentHash,
        state: DownloadState,
    ) {
        _downloadStates.update { it + (hash.value to state) }
    }

    // --- transfer: provider side ----------------------------------------------------------------

    private suspend fun handleTransferMessage(message: TransferMessage) {
        when (message) {
            is TransferMessage.Request -> serveTransferRequest(message)
            is TransferMessage.Offer -> onOfferReceived(message)
            is TransferMessage.Progress -> Unit // brief §28: peer-reported progress is never trusted or displayed
            is TransferMessage.Result -> Unit // the requester already knows its own outcome from its own verification
            is TransferMessage.Cancel -> Unit // the bulk connection dropping is what the provider actually reacts to
        }
    }

    private suspend fun serveTransferRequest(request: TransferMessage.Request) {
        val peerSpki = controlSessionManager.currentPeerSpki ?: return
        when (val resolution = contentResolver.resolve(request.contentHash, monotonicNowUs())) {
            is ContentResolution.Found -> {
                val port = bulkTransport.ensureListening()
                val generation = controlSessionManager.currentAuthGeneration
                val token = bulkTransport.issueToken(request.transferId, generation)
                val chunkCount = (resolution.sizeBytes + CHUNK_SIZE_BYTES - 1) / CHUNK_SIZE_BYTES
                controlSessionManager.transfer.send(
                    TransferMessage.Offer(
                        request.transferId,
                        resolution.sizeBytes,
                        CHUNK_SIZE_BYTES.toInt(),
                        chunkCount.toInt(),
                        port,
                        token,
                    ),
                )
                scope.launch {
                    val input = resolution.open()
                    val source =
                        ChunkSource {
                            val buffer = ByteArray(CHUNK_SIZE_BYTES.toInt())
                            val n = input.read(buffer)
                            if (n <= 0) null else buffer.copyOf(n)
                        }
                    bulkTransport.serve(request.transferId, peerSpki, { generation }, source)
                    input.close()
                }
            }
            // No wire message exists for "cannot serve this request" (PROTOCOL §8.2 has no
            // rejection shape for TRANSFER_REQUEST itself) — the requester's own negotiation
            // timeout is what resolves this, exactly as an unreachable peer would.
            ContentResolution.NotFound, ContentResolution.FileChanged, ContentResolution.IoError -> Unit
        }
    }

    private companion object {
        const val NEGOTIATION_TIMEOUT_MS = 10_000L
        const val CHUNK_SIZE_BYTES = 65_536L
    }
}

private fun BulkFetchOutcome.toTransferError(): TransferError =
    when (this) {
        BulkFetchOutcome.OK -> TransferError.PROTOCOL_ERROR // unreachable: only called on a non-OK outcome
        BulkFetchOutcome.NOT_AUTHORIZED -> TransferError.NOT_AUTHORIZED
        BulkFetchOutcome.CONNECTION_LOST -> TransferError.CONNECTION_LOST
        BulkFetchOutcome.IO_ERROR -> TransferError.IO_ERROR
        BulkFetchOutcome.PROTOCOL_ERROR -> TransferError.PROTOCOL_ERROR
    }

private fun PromoteResult.toTransferError(): TransferError =
    when (this) {
        PromoteResult.PROMOTED -> TransferError.PROTOCOL_ERROR // unreachable: only called on a non-PROMOTED result
        PromoteResult.SIZE_MISMATCH -> TransferError.SIZE_MISMATCH
        PromoteResult.HASH_MISMATCH -> TransferError.HASH_MISMATCH
        PromoteResult.IO_ERROR -> TransferError.IO_ERROR
    }
