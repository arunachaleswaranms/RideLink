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
import com.ridelink.core.protocol.TransferBounds
import com.ridelink.core.protocol.TransferMessage
import com.ridelink.core.transfer.OperationFence
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
import kotlinx.coroutines.Job
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
 *
 * **Operation ownership (closure audit, ADR-023 Amendment A1).** [transferFence] is the "small
 * authoritative operation-generation guard" this pass introduces in place of routing every step
 * through [com.ridelink.core.transfer.TransferReducer] (brief §16): a superseded transfer
 * operation — cancelled by the user, invalidated by a session boundary, or replaced by a fresher
 * one — can never again mutate [downloadStates]/[activeDownload], no matter how late its own
 * coroutine's cleanup eventually runs. Inbound `MANIFEST_*` handling gets the equivalent guard for
 * free from [ControlSessionManager.currentAuthGeneration] itself, captured at message-dispatch
 * time and re-checked at apply time (Finding S). [bulkTransport] is closed on every session
 * boundary (Finding B) and the bulk-token generation supplied to it is re-read live at consumption
 * time, never captured at issuance (Finding A).
 *
 * One collaborator per Phase 4 layer (core/network/data) in the constructor below, matching
 * `AppContainer`'s own composition-root style.
 */
@Suppress("LongParameterList", "TooManyFunctions")
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
    /** Finding I: the hash of whatever cache-only track [com.ridelink.app.music.MusicCoordinator]'s
     *  player currently has open, if any — included in every cache-commit's `locked` set so an
     *  actively playing cache entry is never evicted out from under the player. */
    private val activeCacheHash: () -> ContentHash? = { null },
) {
    private val _remoteEntries = MutableStateFlow<List<ManifestEntry>>(emptyList())
    val remoteEntries: StateFlow<List<ManifestEntry>> = _remoteEntries.asStateFlow()

    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates.asStateFlow()

    /** Finding H: persisted verified-cache truth, independent of [downloadStates] — survives a
     *  process restart, unlike the session-scoped map above. */
    private val _cachedHashes = MutableStateFlow<Set<String>>(emptySet())
    val cachedHashes: StateFlow<Set<String>> = _cachedHashes.asStateFlow()

    private var syncMachine: ManifestSyncStateMachine? = null
    private val transferMutex = Mutex()
    private val downloadQueue = ArrayDeque<ContentHash>()
    private var activeDownload: ContentHash? = null
    private var activeDownloadJob: Job? = null
    private var pendingOffer: CompletableDeferred<TransferMessage.Offer>? = null
    private var pendingOfferTransferId: TransferId? = null

    /** The transfer_id this coordinator is currently *serving* to a peer (provider role), if any —
     *  Finding N: a peer's `TRANSFER_CANCEL` is only honoured if it names this exact transfer. */
    private var activeServeTransferId: TransferId? = null

    /** Finding R: a real, monotonically-increasing catalogue revision — bumped only when the
     *  generated entry set actually differs from what was last served, never a hardcoded constant.
     *  `kind` remains always `FULL` and `since_revision` is still ignored: delta sync itself stays
     *  out of V1 scope (brief §22's explicitly permitted "correct V1 simplification"), disclosed in
     *  ADR-023 Amendment A1 rather than silently implied by a meaningless-but-present revision. */
    private var catalogueRevision = 0L
    private var lastServedEntries: List<ManifestEntry>? = null

    private val transferFence = OperationFence()

    init {
        controlSessionManager.manifest.sink =
            ManifestSink { message ->
                val generation = controlSessionManager.currentAuthGeneration
                scope.launch { handleManifestMessage(message, generation) }
            }
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
        scope.launch { refreshCachedHashes() }
    }

    /** True once bytes have arrived, been whole-file verified, **and** committed — never merely queued or transferring. */
    suspend fun isVerifiedCached(contentHash: ContentHash): Boolean = cacheRepository.isVerifiedCached(contentHash)

    /** brief §19: hands a verified cached file's location to the caller, which plays it through the *existing* player. */
    suspend fun cachedFile(contentHash: ContentHash): File? = cacheRepository.open(contentHash, monotonicNowUs())

    private suspend fun refreshCachedHashes() {
        _cachedHashes.value = cacheRepository.verifiedHashes().map { it.value }.toSet()
    }

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
                // No operation token exists yet (one is minted in pumpQueue when this hash is
                // actually dequeued) — a direct write is safe: nothing is "in flight" for this hash
                // to race with, since the two guards above just proved it was neither queued nor active.
                writeState(hash, DownloadState(TransferStatus.QUEUED))
            }
            pumpQueue()
        }
    }

    /**
     * Closure-audit Findings C/N: a real, terminal cancellation — not merely a UI-state change.
     * Supersedes the active operation's fence token (so its own late `finishDownload`/`setState`
     * calls become inert no matter how far the original coroutine has already run), cancels the
     * tracked [Job], force-closes whatever bulk socket that job might be blocked on, deletes the
     * `.part` file so a re-request never races a still-writing old task (brief §18), and — PROTOCOL
     * §8.2 — tells the peer with `TRANSFER_CANCEL` rather than relying on the connection merely
     * dropping.
     */
    fun cancelDownload(contentHash: ContentHash) {
        scope.launch {
            transferMutex.withLock { downloadQueue.remove(contentHash) }
            if (activeDownload != contentHash) return@launch
            val transferId = pendingOfferTransferId
            transferFence.supersede()
            pendingOffer?.cancel()
            pendingOffer = null
            activeDownloadJob?.cancel()
            activeDownloadJob = null
            bulkTransport.cancelActive()
            cacheStorage.deletePart(contentHash)
            // Authoritative terminal write: cancellation itself is never subject to the fence check
            // that guards a late completion — this *is* the write that must win.
            writeState(contentHash, DownloadState(TransferStatus.CANCELLED))
            transferMutex.withLock { activeDownload = null }
            if (transferId != null) {
                controlSessionManager.transfer.send(TransferMessage.Cancel(transferId, "user_cancelled"))
            }
            pumpQueue()
        }
    }

    /**
     * Closure-audit Findings B/D: one explicit lifecycle owner for everything a session boundary
     * must invalidate. [bulkTransport] closing (ADR-023 §1) tears down the listener *and* clears
     * every outstanding token; [transferFence] supersedes so a stale transfer completion dispatched
     * just before this boundary can't mutate whatever comes after it (brief §17), and the bumped
     * [ControlSessionManager.currentAuthGeneration] a fresh `Connected` implies gives manifest
     * handling the equivalent protection (brief §23); the active download's `.part` is removed and
     * its state marked terminal rather than left dangling.
     */
    private fun onSessionBoundary() {
        // brief §6/§22: a peer's catalogue is session/peer-scoped and must never leak across a
        // reconnect or a different peer — replaced wholesale, never merged with what came before.
        _remoteEntries.value = emptyList()
        syncMachine = null
        // ADR-023 §1: the bulk listener and every outstanding token die with the session that
        // opened them — never sprinkled as ad hoc close() calls elsewhere (brief §2).
        bulkTransport.close()
        val hashToClear = activeDownload
        transferFence.supersede()
        scope.launch {
            pendingOffer?.cancel()
            pendingOffer = null
            activeDownloadJob?.cancel()
            activeDownloadJob = null
            activeServeTransferId = null
            transferMutex.withLock {
                downloadQueue.clear()
                activeDownload = null
            }
            hashToClear?.let { hash ->
                cacheStorage.deletePart(hash)
                writeState(hash, DownloadState(TransferStatus.FAILED, error = TransferError.CONNECTION_LOST))
            }
        }
    }

    // --- manifest: both roles, since both peers are symmetric --------------------------------------

    /**
     * Closure-audit Finding S: [generation] is [ControlSessionManager.currentAuthGeneration] as it
     * was the moment this message was read off the wire, captured in the `sink` lambda at [init].
     * If the session has since moved on to a new authenticated generation by the time this
     * coroutine actually runs, the message is a stale artefact of a torn-down session and is
     * dropped before it can touch [syncMachine]/[remoteEntries] — the concrete mechanism behind
     * "a late PAGE/END from session A must never mutate session B's catalogue."
     */
    private suspend fun handleManifestMessage(
        message: ManifestMessage,
        generation: Long,
    ) {
        if (generation != controlSessionManager.currentAuthGeneration) return
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
        if (lastServedEntries == null || entries != lastServedEntries) {
            catalogueRevision += 1
            lastServedEntries = entries
        }
        val revision = catalogueRevision
        val budget = minOf(ManifestPaging.MANIFEST_PAGE_SOFT_LIMIT_BYTES, request.maxPageBytes)
        val pages = ManifestPaging.paginate(entries, budget)
        val manifestId = nextManifestId()
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
            if (next != null) {
                val opToken = transferFence.begin()
                activeDownloadJob = scope.launch { runDownload(next, opToken) }
            }
        }
    }

    @Suppress("ReturnCount", "LongMethod")
    private suspend fun runDownload(
        hash: ContentHash,
        opToken: Long,
    ) {
        val transferId = nextTransferId()
        pendingOfferTransferId = transferId
        setState(hash, DownloadState(TransferStatus.NEGOTIATING), opToken)
        val deferred = CompletableDeferred<TransferMessage.Offer>()
        pendingOffer = deferred
        controlSessionManager.transfer.send(TransferMessage.Request(hash, transferId))

        val offer = withTimeoutOrNull(NEGOTIATION_TIMEOUT_MS) { deferred.await() }
        if (offer == null) {
            finishDownload(hash, DownloadState(TransferStatus.FAILED, error = TransferError.NOT_FOUND), opToken)
            return
        }
        val peerSpki = controlSessionManager.currentPeerSpki
        val peerHost = controlSessionManager.currentPeerHost
        if (peerSpki == null || peerHost == null) {
            finishDownload(hash, DownloadState(TransferStatus.FAILED, error = TransferError.CONNECTION_LOST), opToken)
            return
        }

        setState(hash, DownloadState(TransferStatus.TRANSFERRING, totalBytes = offer.sizeBytes), opToken)
        val stream = cacheStorage.openPartForWrite(hash)
        var received = 0L
        val sink =
            ChunkSink { _, bytes ->
                cacheStorage.appendChunk(stream, bytes)
                received += bytes.size
                setState(hash, DownloadState(TransferStatus.TRANSFERRING, bytesReceived = received, totalBytes = offer.sizeBytes), opToken)
            }
        val outcome =
            bulkTransport.fetch(peerHost, offer.bulkPort, offer.bulkToken, peerSpki, offer.chunkCount.toLong(), sink)
        stream.close()

        if (outcome != BulkFetchOutcome.OK) {
            cacheStorage.deletePart(hash)
            finishDownload(hash, DownloadState(TransferStatus.FAILED, error = outcome.toTransferError()), opToken)
            return
        }
        setState(hash, DownloadState(TransferStatus.VERIFYING, totalBytes = offer.sizeBytes), opToken)
        when (val promoteResult = cacheStorage.promote(hash, offer.sizeBytes)) {
            PromoteResult.PROMOTED -> {
                // Closure-audit Finding P: promote-then-commit must not report success unless the
                // metadata commit itself actually succeeded — a thrown exception here used to
                // propagate uncaught, permanently wedging the one-active-transfer queue (activeDownload
                // never cleared) rather than surfacing as a clean, terminal FAILED.
                val committed =
                    runCatching {
                        cacheRepository.commit(hash, offer.sizeBytes, monotonicNowUs(), locked = setOfNotNull(activeCacheHash()))
                    }.isSuccess
                if (committed) {
                    refreshCachedHashes()
                    controlSessionManager.transfer.send(TransferMessage.Result(transferId, true, hash))
                    finishDownload(hash, DownloadState(TransferStatus.COMPLETE, totalBytes = offer.sizeBytes), opToken)
                } else {
                    controlSessionManager.transfer.send(TransferMessage.Result(transferId, false, null))
                    finishDownload(hash, DownloadState(TransferStatus.FAILED, error = TransferError.IO_ERROR), opToken)
                }
            }
            else -> {
                controlSessionManager.transfer.send(TransferMessage.Result(transferId, false, null))
                finishDownload(hash, DownloadState(TransferStatus.FAILED, error = promoteResult.toTransferError()), opToken)
            }
        }
    }

    private fun finishDownload(
        hash: ContentHash,
        state: DownloadState,
        opToken: Long,
    ) {
        setState(hash, state, opToken)
        if (!transferFence.isCurrent(opToken)) return // superseded — cancellation/session-boundary already cleaned up
        scope.launch {
            transferMutex.withLock { if (activeDownload == hash) activeDownload = null }
            activeDownloadJob = null
            pumpQueue()
        }
    }

    private fun onOfferReceived(message: TransferMessage.Offer) {
        if (pendingOfferTransferId == message.transferId) pendingOffer?.complete(message)
    }

    /**
     * Closure-audit Findings C/D/O/P/S (terminal-state rule, brief §17): [opToken] must still be
     * the fence's current operation for this write to apply. A cancelled or session-superseded
     * operation's own in-flight coroutine keeps running (cooperative cancellation is not
     * instantaneous), but every state write it attempts after being superseded is silently dropped
     * — `CANCELLED -> COMPLETE`, `FAILED -> COMPLETE`, and "old-session COMPLETE mutating new-session
     * state" are all made structurally impossible by this one check, not by timing.
     */
    private fun setState(
        hash: ContentHash,
        state: DownloadState,
        opToken: Long,
    ) {
        if (!transferFence.isCurrent(opToken)) return
        writeState(hash, state)
    }

    /** The unguarded write itself — used directly only where no fence token exists yet ([requestDownload]'s
     *  `QUEUED`) or where the write *is* the authoritative terminal one the fence exists to protect
     *  ([cancelDownload]'s `CANCELLED`, [onSessionBoundary]'s session-loss `FAILED`). */
    private fun writeState(
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
            is TransferMessage.Cancel -> handlePeerCancel(message)
        }
    }

    /**
     * Closure-audit Finding N: PROTOCOL §8.2 — `TRANSFER_CANCEL` is valid from either side at any
     * time and both drop the bulk connection. Only honoured if [message] names the transfer this
     * coordinator is *currently* serving (provider role) — a cancel for a stale, foreign, or
     * already-finished transfer_id is a no-op, never a way to disrupt an unrelated transfer.
     */
    private fun handlePeerCancel(message: TransferMessage.Cancel) {
        if (activeServeTransferId == message.transferId) {
            bulkTransport.cancelActive()
        }
    }

    @Suppress("ReturnCount") // one early-out per guard (peer identity, size bound, token collision), in that order
    private suspend fun serveTransferRequest(request: TransferMessage.Request) {
        val peerSpki = controlSessionManager.currentPeerSpki ?: return
        when (val resolution = contentResolver.resolve(request.contentHash, monotonicNowUs())) {
            is ContentResolution.Found -> {
                // Closure-audit Finding Q: never construct/send an offer the peer's own codec would
                // have to reject — check the bound here, on the sender, rather than relying solely
                // on the receiver's TransferCodec.parseOffer size check.
                if (resolution.sizeBytes > TransferBounds.MAX_TRANSFER_SIZE_BYTES) return
                val port = bulkTransport.ensureListening()
                // Closure-audit Finding A: read the *live* current authenticated generation both at
                // issuance and again, independently, at consumption time — never a value captured
                // once and replayed. A stale closure over a captured `val` would defeat ADR-023 §3's
                // whole "reconnect invalidates every outstanding token" guarantee.
                val token = bulkTransport.tryIssueToken(request.transferId, controlSessionManager.currentAuthGeneration) ?: return
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
                activeServeTransferId = request.transferId
                scope.launch {
                    val input = resolution.open()
                    try {
                        val source =
                            ChunkSource {
                                val buffer = ByteArray(CHUNK_SIZE_BYTES.toInt())
                                val n = input.read(buffer)
                                if (n <= 0) null else buffer.copyOf(n)
                            }
                        bulkTransport.serve(
                            request.transferId,
                            peerSpki,
                            { controlSessionManager.currentAuthGeneration },
                            source,
                        )
                    } finally {
                        input.close()
                        if (activeServeTransferId == request.transferId) activeServeTransferId = null
                    }
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
