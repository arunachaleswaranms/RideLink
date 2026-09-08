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
import com.ridelink.core.transfer.BulkOperationGate
import com.ridelink.core.transfer.BulkOperationOwner
import com.ridelink.core.transfer.OperationFence
import com.ridelink.core.transfer.ProviderSessionContext
import com.ridelink.core.transfer.TransferError
import com.ridelink.core.transfer.TransferStatus
import com.ridelink.data.transfer.CacheStorage
import com.ridelink.data.transfer.ContentResolution
import com.ridelink.data.transfer.ManifestGenerator
import com.ridelink.data.transfer.PromoteResult
import com.ridelink.data.transfer.TransferCacheRepository
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.manifest.ManifestSink
import com.ridelink.network.transfer.BulkFetchOutcome
import com.ridelink.network.transfer.ChunkSink
import com.ridelink.network.transfer.InputStreamChunkSource
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
 * **Provider operation ownership (Phase 4 closure-audit follow-up, ADR-023 Amendment A2).**
 * [bulkGate] replaces the plain `activeServeTransferId` var this pass found insufficient: a second
 * `TRANSFER_REQUEST` arriving before the first's [BulkTransportManager.serve] call actually
 * completes used to silently overwrite that var, so a peer `TRANSFER_CANCEL` could be routed to
 * the wrong transfer or ignored outright (Finding A). [bulkGate] is also the single shared owner
 * across **both** roles — requester (a local download) and provider (serving a peer's request) —
 * matching [BulkTransportManager]'s own `activeTransferMutex`, which already serializes `serve`/
 * `fetch` across both roles onto one real socket at a time (Finding A's cross-role counterpart,
 * brief §17/§18).
 *
 * **Inbound `TRANSFER_*` session binding (Finding B).** [handleTransferMessage] is now guarded by
 * the same live-generation check [handleManifestMessage] already had — every `TRANSFER_REQUEST`/
 * `TRANSFER_OFFER`/`TRANSFER_PROGRESS`/`TRANSFER_RESULT`/`TRANSFER_CANCEL` dispatched under a
 * session that has since been superseded is dropped before it can touch [bulkGate],
 * [pendingOfferTransferId], or any provider state.
 *
 * **Suspension-point re-authorisation (ADR-023 Amendment A3).** The dispatch-entry check above
 * only proves the request was current the instant it was read off the wire — [serveTransferRequest]
 * itself suspends repeatedly afterward (`contentResolver.resolve`, `bulkTransport.ensureListening`,
 * `controlSessionManager.transfer.send`), and a session boundary landing inside any of those used
 * to go completely unnoticed: [onSessionBoundary] frees [bulkGate] and moves the live
 * generation/peer on, so a stale request could otherwise acquire the freed slot and go on to mint a
 * token and send an offer under the *new* session, potentially serving old peer A's file to
 * whichever peer is connected now. [com.ridelink.core.transfer.ProviderSessionContext] captures the
 * authorising generation/peer once and [stillAuthorised] re-proves it at every one of those
 * suspension points.
 *
 * **Post-acquisition authorisation is both halves (ADR-023 Amendment A5).** A3 used
 * [BulkOperationGate.isOwner] alone after acquisition, on the reasoning that a session boundary
 * always invalidates the gate. It does — but not at the same instant the live session moves:
 * [ControlSessionManager.currentAuthGeneration] is bumped before the `Connected` event that runs
 * [onSessionBoundary] is even dispatched, and on iOS the boundary `await`s the transport close
 * between bumping its epoch and invalidating the gate. In both windows the gate still names this
 * transfer while the session that authorised it is already gone. [stillAuthorised] therefore joins
 * gate ownership *and* [com.ridelink.core.transfer.ProviderSessionContext.isStillCurrent], through
 * the pure [BulkOperationGate.stillAuthorises].
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
    private val contentResolver: ContentResolverPort,
    private val bulkTransport: BulkTransportPort,
    private val controlSessionManager: TransferSessionPort,
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

    /**
     * Phase 5 (ADR-024 §7): content this session's peer is known to hold **because it told us so** —
     * a `TRANSFER_RESULT { ok: true }` for a transfer *we* served, whose recorded `content_hash`
     * matches the one the peer reports having verified.
     *
     * This exists because a peer's manifest is generated from its Phase 3 *library* only
     * ([ManifestGenerator]), so a track it received by transfer and holds in its verified Phase 4
     * cache appears in no manifest and would otherwise be invisible to the availability gate — the
     * exact case UJ-05 describes. No wire change was needed: the requester already sends this
     * message and the provider already receives it; Phase 4 simply ignored it.
     *
     * Session-scoped and cleared on every boundary, exactly like [remoteEntries]: it describes one
     * peer under one authenticated session and must never leak into the next.
     *
     * Trusting it is safe because of what it is used for. It gates whether a *synchronised* `PLAY`
     * may be scheduled — nothing else. A peer that lied about having a file simply does not play;
     * no local storage, no local state and no security decision depends on it. The stronger half of
     * the claim is ours anyway: we only record a hash we ourselves served over the bulk plane in
     * this session.
     */
    private val _peerVerifiedHashes = MutableStateFlow<Set<String>>(emptySet())
    val peerVerifiedHashes: StateFlow<Set<String>> = _peerVerifiedHashes.asStateFlow()

    /**
     * Phase 5 (ADR-024 Amendment A1 Finding E): the one observer notified whenever **verified**
     * availability changes — locally, when a transfer's `TransferCacheRepository.commit` succeeds,
     * or on the peer, when it reports having verified a transfer we served it.
     *
     * A notification, not a third source of truth: the two facts it announces are still
     * [cachedHashes] and [peerVerifiedHashes], and a listener re-asks rather than being handed a
     * hash. It exists so one press of synchronised Play can survive a Phase 4 transfer instead of
     * silently costing the user a second press, and so that waiting does not become polling.
     *
     * Set by `AppContainer` (via `SharedLibraryContentPort`) and by nothing else. Deliberately not a
     * `StateFlow`: Amendment A4 Finding U is the recorded reason this codebase distrusts a flow
     * whose only consumer reads `.value`.
     */
    var onAvailabilityChanged: (() -> Unit)? = null

    /** `transfer_id -> content_hash` for transfers **we** are serving, so a peer's `TRANSFER_RESULT`
     *  is matched against what we actually sent rather than against whatever hash it names. */
    private val servedHashes = java.util.concurrent.ConcurrentHashMap<String, ContentHash>()

    private var syncMachine: ManifestSyncStateMachine? = null
    private val transferMutex = Mutex()
    private val downloadQueue = ArrayDeque<ContentHash>()
    private var activeDownload: ContentHash? = null
    private var activeDownloadJob: Job? = null
    private var pendingOffer: CompletableDeferred<TransferMessage.Offer>? = null
    private var pendingOfferTransferId: TransferId? = null

    /** Finding A / brief §17-18: the one bulk operation — requester or provider — this session may
     *  have active at once. Replaces the plain `activeServeTransferId` var this pass found
     *  insufficient: acquisition is refused outright while another operation holds the slot, so
     *  ownership can never be silently overwritten, and `TRANSFER_CANCEL` routing
     *  ([handlePeerCancel]) asks *this* gate rather than trusting a var that may already be stale. */
    private val bulkGate = BulkOperationGate()

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
        controlSessionManager.transfer.sink =
            TransferSink { message ->
                // Finding B: captured here, at dispatch time, exactly like ManifestSink above —
                // re-checked once the launched coroutine actually runs (handleTransferMessage).
                val generation = controlSessionManager.currentAuthGeneration
                scope.launch { handleTransferMessage(message, generation) }
            }
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

    /**
     * Phase 5's peer half of the brief §19 availability gate (ADR-024 §7): the peer holds this
     * content if its synced manifest advertises it, **or** if it verified a transfer we served it in
     * this session. Session-scoped both ways — [onSessionBoundary] clears both sources.
     */
    fun peerHasContent(contentHash: ContentHash): Boolean =
        _remoteEntries.value.any { it.contentHash == contentHash } || contentHash.value in _peerVerifiedHashes.value

    /**
     * brief §19: hands a verified cached file's location to the caller, which plays it through the
     * *existing* player.
     *
     * Amendment A4: a `null` here can mean [TransferCacheRepository.open] just found a row claiming
     * verified but no file behind it (storage cleared under the app) and dropped that row — so
     * refresh [cachedHashes], or the UI would keep offering "Play" for content that no longer
     * exists and never offer "Download" to get it back.
     */
    suspend fun cachedFile(contentHash: ContentHash): File? {
        val file = cacheRepository.open(contentHash, monotonicNowUs())
        if (file == null && contentHash.value in _cachedHashes.value) refreshCachedHashes()
        return file
    }

    private suspend fun refreshCachedHashes() {
        val refreshed = cacheRepository.verifiedHashes().map { it.value }.toSet()
        val changed = refreshed != _cachedHashes.value
        _cachedHashes.value = refreshed
        // Only on a real change: a re-query that found the same set is not news, and Phase 5's
        // listener re-resolves content when it hears this.
        if (changed) onAvailabilityChanged?.invoke()
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
            // Finding A: only force-close the shared bulk transport if this transfer is still its
            // real owner — activeDownload/pendingOfferTransferId alone are coordinator/queue
            // bookkeeping, not proof this operation ever actually acquired the transport's one
            // active slot (e.g. cancelling during NEGOTIATING, before any bulk socket opened, or
            // while the shared slot happens to belong to this session's own provider operation).
            if (transferId != null && bulkGate.isOwner(transferId)) {
                bulkTransport.cancelActive(transferId)
            }
            if (transferId != null) bulkGate.releaseIfOwner(transferId)
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
        // ADR-024 §7: what the peer verified belongs to the session it verified it under, exactly
        // like the catalogue above. A reconnect re-earns it.
        _peerVerifiedHashes.value = emptySet()
        servedHashes.clear()
        syncMachine = null
        val hashToClear = activeDownload
        // Amendment A4 Finding V — supersede *before* closing the transport, not after. Closing
        // force-closes whatever socket the active operation is parked on, which is precisely what
        // lets that operation resume; if the fence were still current at that instant, the resumed
        // operation could run all the way through promote/commit/`TRANSFER_RESULT{ok: true}` while
        // this very function was still executing, and the `activeDownloadJob.cancel()` below — only
        // *scheduled*, not yet run — would arrive far too late to stop it. Superseding first makes
        // the ordering a property of the code rather than of the dispatcher.
        transferFence.supersede()
        // ADR-023 §1: the bulk listener and every outstanding token die with the session that
        // opened them — never sprinkled as ad hoc close() calls elsewhere (brief §2).
        bulkTransport.close()
        // Finding A: unconditionally frees the provider/requester ownership slot no matter who
        // holds it — bulkTransport.close() above already force-closed whatever real socket that
        // holder's serve()/fetch() call was blocked on, so its own eventual cleanup finds nothing
        // left to (mis)clear.
        bulkGate.invalidate()
        scope.launch {
            pendingOffer?.cancel()
            pendingOffer = null
            activeDownloadJob?.cancel()
            activeDownloadJob = null
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
                    val head = downloadQueue.firstOrNull() ?: return@withLock null
                    val transferId = nextTransferId()
                    // Finding A cross-role (brief §17/§18): the shared bulk slot may be held right
                    // now by this device's own *provider* operation (serving a peer's request).
                    // Leave head queued rather than start a download the transport cannot actually
                    // honour yet — the provider operation's own cleanup calls pumpQueue() again
                    // once it releases the slot, so this retries automatically, no new queue needed.
                    if (!bulkGate.tryAcquire(BulkOperationOwner.Requester(transferId, head))) return@withLock null
                    downloadQueue.removeFirst()
                    activeDownload = head
                    head to transferId
                }
            if (next != null) {
                val (hash, transferId) = next
                val opToken = transferFence.begin()
                activeDownloadJob = scope.launch { runDownload(hash, transferId, opToken) }
            }
        }
    }

    @Suppress("ReturnCount", "LongMethod")
    private suspend fun runDownload(
        hash: ContentHash,
        transferId: TransferId,
        opToken: Long,
    ) {
        pendingOfferTransferId = transferId
        try {
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
            val outcome =
                try {
                    val sink =
                        ChunkSink { _, bytes ->
                            cacheStorage.appendChunk(stream, bytes)
                            received += bytes.size
                            val progress =
                                DownloadState(TransferStatus.TRANSFERRING, bytesReceived = received, totalBytes = offer.sizeBytes)
                            setState(hash, progress, opToken)
                        }
                    bulkTransport.fetch(
                        transferId,
                        peerHost,
                        offer.bulkPort,
                        offer.bulkToken,
                        peerSpki,
                        offer.chunkCount.toLong(),
                        sink,
                    )
                } finally {
                    // Amendment A4 Finding V: `finally`, not a plain call after `fetch` returns —
                    // a cancelled Job throws CancellationException out of `fetch`'s own suspension
                    // points, which used to skip this close() entirely and leak the `.part` file
                    // descriptor once per cancelled transfer.
                    runCatching { stream.close() }
                }

            // Amendment A4 Finding V: everything below writes to storage the `content_hash` is the
            // *only* key for, so a superseded operation must stop here — not merely have its state
            // writes dropped by the fence. Its own deletePart(hash) would otherwise delete the
            // `.part` file a *newer* operation for the same hash has already begun writing (the very
            // race brief §18 names), and its promote/commit would publish a verified cache entry and
            // a TRANSFER_RESULT for a transfer the user cancelled or a session boundary killed.
            if (!transferFence.isCurrent(opToken)) return

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
        } finally {
            // Finding A: released here for every normal exit path above (idempotent — a no-op if
            // cancelDownload or a session boundary already released/invalidated the slot first).
            bulkGate.releaseIfOwner(transferId)
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

    @Suppress("ReturnCount") // one early-out per condition the claim must satisfy before it is believed
    private fun onPeerTransferResult(message: TransferMessage.Result) {
        val served = servedHashes.remove(message.transferId.value) ?: return
        if (!message.ok) return
        // Both must agree: what we sent, and what the peer says it verified.
        if (message.sha256 != served) return
        val known = served.value in _peerVerifiedHashes.value
        _peerVerifiedHashes.update { it + served.value }
        // Phase 5 (Amendment A1 Finding E): the peer half of the availability gate just became
        // true, which may be the last precondition a retained synchronised Play was waiting on.
        if (!known) onAvailabilityChanged?.invoke()
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

    /**
     * Closure-audit Finding B: [generation] is [ControlSessionManager.currentAuthGeneration] as it
     * was the moment this message was read off the wire, captured in the `sink` lambda at [init] —
     * exactly the guard [handleManifestMessage] already had. Every `TRANSFER_REQUEST`/
     * `TRANSFER_OFFER`/`TRANSFER_PROGRESS`/`TRANSFER_RESULT`/`TRANSFER_CANCEL` dispatched under a
     * session that has since been superseded is dropped here, before it can touch [bulkGate],
     * [pendingOfferTransferId], or any provider/requester state — a stale `REQUEST` cannot be
     * served under the new peer's SPKI/generation, a stale `OFFER` cannot satisfy a new session's
     * pending request, and a stale `CANCEL` cannot cancel a new session's transfer.
     */
    private suspend fun handleTransferMessage(
        message: TransferMessage,
        generation: Long,
    ) {
        if (generation != controlSessionManager.currentAuthGeneration) return
        when (message) {
            is TransferMessage.Request -> serveTransferRequest(message, generation)
            is TransferMessage.Offer -> onOfferReceived(message)
            is TransferMessage.Progress -> Unit // brief §28: peer-reported progress is never trusted or displayed
            // The *requester* already knows its own outcome from its own verification, so this is
            // never read as a download result. What it does carry, for the **provider**, is the one
            // signal Phase 4 had no consumer for: the peer has verified and committed the file we
            // just served it (ADR-024 §7). That is what makes Phase 5's availability gate able to
            // see a track the peer holds only in its verified cache, which appears in no manifest.
            is TransferMessage.Result -> onPeerTransferResult(message)
            is TransferMessage.Cancel -> handlePeerCancel(message)
        }
    }

    /**
     * Closure-audit Finding N, hardened by Finding A: PROTOCOL §8.2 — `TRANSFER_CANCEL` is valid
     * from either side at any time and both drop the bulk connection. Only honoured if [message]
     * names the transfer [bulkGate] currently holds — not a plain `activeServeTransferId` var that
     * a second, not-yet-actually-serving request could have silently overwritten — so a cancel for
     * a stale, foreign, queued, or already-finished transfer_id is a no-op, never a way to disrupt
     * an unrelated (possibly requester-role) transfer sharing the same underlying bulk socket.
     */
    private fun handlePeerCancel(message: TransferMessage.Cancel) {
        if (bulkGate.isOwner(message.transferId)) {
            bulkTransport.cancelActive(message.transferId)
        }
    }

    /**
     * ADR-023 Amendment A3: [authorisingGeneration] is the same value [handleTransferMessage]
     * already checked against [ControlSessionManager.currentAuthGeneration] at dispatch time — not
     * re-read here — because this whole function is riddled with suspension points
     * (`contentResolver.resolve`, `bulkTransport.ensureListening`, `controlSessionManager.transfer.send`)
     * a session boundary can land inside. Amendment A2's dispatch-entry check alone proves nothing
     * about what the session looks like by the time any of *those* run: [stillAuthorised] and
     * [ProviderSessionContext.isStillCurrent] are the re-checks that close that gap.
     */
    @Suppress("ReturnCount") // one early-out per guard, in the order each condition can first fail
    private suspend fun serveTransferRequest(
        request: TransferMessage.Request,
        authorisingGeneration: Long,
    ) {
        val peerSpki = controlSessionManager.currentPeerSpki ?: return
        // Finding A §12 / Amendment A3: the session that authorises this operation for its whole
        // lifetime, not merely a value read once and forgotten — bulkGate.tryAcquire below records
        // it into BulkOperationOwner.Provider, and isStillCurrent is what every suspension point
        // after that re-proves against.
        val authorisation = ProviderSessionContext(authorisingGeneration, peerSpki)
        when (val resolution = contentResolver.resolve(request.contentHash, monotonicNowUs())) {
            is ContentResolution.Found -> {
                // Amendment A3 — the primary gap this amendment closes: contentResolver.resolve()
                // above is a real suspension point. A session boundary landing inside it frees
                // bulkGate (onSessionBoundary's invalidate()) and moves the live generation/peer
                // on — without this check, a stale request from an old peer could then acquire the
                // slot and go on to mint a token and send an offer under the *new* live session,
                // serving old peer A's requested file to whichever peer is connected now.
                if (!authorisation.isStillCurrent(controlSessionManager.currentAuthGeneration, controlSessionManager.currentPeerSpki)) {
                    return
                }
                // Closure-audit Finding Q: never construct/send an offer the peer's own codec would
                // have to reject — check the bound here, on the sender, rather than relying solely
                // on the receiver's TransferCodec.parseOffer size check.
                if (resolution.sizeBytes > TransferBounds.MAX_TRANSFER_SIZE_BYTES) return
                // Finding A: acquire the one shared bulk-operation slot — across *both* provider and
                // requester roles (brief §17/§18) — before ever sending an offer. A second
                // concurrent TRANSFER_REQUEST, or a local download already in flight, must not
                // overwrite ownership or receive an offer this coordinator cannot yet honour; the
                // requester's own negotiation timeout resolves this (brief §19 — no BUSY wire shape).
                val owner = BulkOperationOwner.Provider(request.transferId, request.contentHash, peerSpki, authorisingGeneration)
                if (!bulkGate.tryAcquire(owner)) return
                // ADR-024 §7: remember what this transfer_id actually carries, so a later
                // TRANSFER_RESULT is matched against what we served rather than against a hash the
                // peer chose to name.
                servedHashes[request.transferId.value] = request.contentHash
                // Amendment A5 Finding B: ensureListening() now fails rather than publishing a
                // listener bound under a lifetime a session boundary already ended — an outcome
                // this path must handle, or the gate would leak and block every later transfer in
                // both roles. Mirrors iOS's `try? await bulkTransport.ensureListening()`.
                @Suppress("SwallowedException") // one outcome for every bind failure: give the slot back
                val port =
                    try {
                        bulkTransport.ensureListening()
                    } catch (io: java.io.IOException) {
                        bulkGate.releaseIfOwner(request.transferId)
                        pumpQueue()
                        return
                    }
                // Amendment A3/A5: every suspension point from here on re-checks both halves of the
                // authorisation — that this operation still owns the slot, and that the session
                // which authorised it is still live. See [stillAuthorised] for why gate ownership
                // alone (A3's proxy) is not sufficient.
                if (!stillAuthorised(request.transferId, authorisation)) return
                // Closure-audit Finding A: read the *live* current authenticated generation both at
                // issuance and again, independently, at consumption time — never a value captured
                // once and replayed. A stale closure over a captured `val` would defeat ADR-023 §3's
                // whole "reconnect invalidates every outstanding token" guarantee. Amendment A3 does
                // not touch this: the outer authorisation check above is a different, additional
                // guard, layered on top of — never a replacement for — this live re-read.
                val token = bulkTransport.tryIssueToken(request.transferId, controlSessionManager.currentAuthGeneration)
                if (token == null) {
                    bulkGate.releaseIfOwner(request.transferId)
                    pumpQueue() // cross-role: wake a local download left queued behind this attempt
                    return
                }
                if (!stillAuthorised(request.transferId, authorisation)) {
                    bulkGate.releaseIfOwner(request.transferId)
                    pumpQueue()
                    return
                }
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
                    // Amendment A3: the launched coroutine itself may not start immediately —
                    // re-check once more, as the very first thing it does, before ever opening the
                    // local file or calling serve().
                    if (!stillAuthorised(request.transferId, authorisation)) {
                        pumpQueue()
                        return@launch
                    }
                    // Finding A: resolution.open() lives inside this try/finally too — a failure to
                    // open the local file must not leak the gate (which would otherwise block every
                    // future transfer, both roles, until the next session boundary).
                    var input: java.io.InputStream? = null
                    try {
                        input = resolution.open()
                        // Closure-audit Amendment A4 Finding T: [InputStreamChunkSource] fills each
                        // frame to exactly CHUNK_SIZE_BYTES, because the TRANSFER_OFFER sent above
                        // already declared `chunk_size` and `chunk_count` — see that class's own
                        // KDoc for why one `read` per frame was a real bug on `content://` sources.
                        val source = InputStreamChunkSource(input, CHUNK_SIZE_BYTES.toInt())
                        bulkTransport.serve(
                            request.transferId,
                            peerSpki,
                            { controlSessionManager.currentAuthGeneration },
                            chunkCount,
                            source,
                        )
                    } finally {
                        input?.close()
                        // Finding A: idempotent by construction — a no-op if a session boundary
                        // already called bulkGate.invalidate(), and never clears a fresher operation
                        // that has since acquired the slot under a different transfer_id.
                        bulkGate.releaseIfOwner(request.transferId)
                        // Cross-role (brief §17/§18): wake a local download that was left queued
                        // behind this provider operation — a harmless no-op if none is queued.
                        pumpQueue()
                    }
                }
            }
            // No wire message exists for "cannot serve this request" (PROTOCOL §8.2 has no
            // rejection shape for TRANSFER_REQUEST itself) — the requester's own negotiation
            // timeout is what resolves this, exactly as an unreachable peer would.
            ContentResolution.NotFound, ContentResolution.FileChanged, ContentResolution.IoError -> Unit
        }
    }

    /**
     * ADR-023 Amendment A5: the post-acquisition authorisation check, now **both** halves — the
     * slot is still [transferId]'s *and* the session that authorised the request is still the live
     * one. The decision itself is [BulkOperationGate.stillAuthorises], pure and mirrored, so it is
     * unit-testable on both platforms rather than only on the one whose coordinator has a test
     * target.
     *
     * A3 used [BulkOperationGate.isOwner] alone here, reasoning that [onSessionBoundary]
     * unconditionally invalidates the gate on every boundary. A5 found that the two are not
     * simultaneous: [ControlSessionManager.currentAuthGeneration] is bumped by the session layer
     * *before* the `Connected` event that triggers [onSessionBoundary] is dispatched, so between
     * those two moments the gate still names this transfer while the live session has already moved
     * to the next peer — and gate ownership alone answers "yes, still authorised" for an operation
     * that is already stale. Re-checking [authorisation] closes that window; the gate check stays,
     * because it is what stops an operation acting after it has *lost the slot* to a fresher one.
     */
    private fun stillAuthorised(
        transferId: TransferId,
        authorisation: ProviderSessionContext,
    ): Boolean =
        bulkGate.stillAuthorises(
            transferId,
            authorisation,
            controlSessionManager.currentAuthGeneration,
            controlSessionManager.currentPeerSpki,
        )

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
