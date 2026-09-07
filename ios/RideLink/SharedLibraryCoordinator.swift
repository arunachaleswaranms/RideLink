import Foundation
import GRDB
import Observation
import RideLinkCore
import RideLinkPlatform

/// Adapts a closure to `ManifestSink`/`TransferSink`, whose `submit` is called synchronously from
/// the relay actor and must not block — the same hop-onto-`@MainActor` shape `SessionCoordinator`'s
/// own `PeerAudioStateSink` already uses.
private struct ManifestSinkAdapter: ManifestSink {
    let onMessage: @Sendable (ManifestMessage) -> Void
    func submit(_ message: ManifestMessage) { onMessage(message) }
}

private struct TransferSinkAdapter: TransferSink {
    let onMessage: @Sendable (TransferMessage, Int64) -> Void
    let epoch: @Sendable () -> Int64
    func submit(_ message: TransferMessage) { onMessage(message, epoch()) }
}

/// What the UI shows for one `ContentHash`'s transfer, if any is or ever was in flight this
/// session. Mirrors `com.ridelink.app.library.DownloadState`.
public struct DownloadState: Sendable, Equatable {
    public var status: TransferStatus
    public var bytesReceived: Int64
    public var totalBytes: Int64
    public var error: TransferError?

    public init(status: TransferStatus, bytesReceived: Int64 = 0, totalBytes: Int64 = 0, error: TransferError? = nil) {
        self.status = status
        self.bytesReceived = bytesReceived
        self.totalBytes = totalBytes
        self.error = error
    }
}

/// A `TRANSFER_OFFER` the requester is waiting on, or the negotiation-timeout's "none arrived"
/// answer — a plain local type rather than reusing `TransferMessage.offer`'s associated values
/// loose, so `withOfferTimeout` has one concrete return type to hand back.
private struct PendingOffer {
    let transferId: TransferId
    let sizeBytes: Int64
    let bulkPort: Int
    let bulkToken: String
}

/// The single owner of Phase 4 shared-library state (CLAUDE.md rule 8), mirroring
/// `com.ridelink.app.library.SharedLibraryCoordinator` on Android. No SwiftUI view holds
/// remote-catalogue or transfer state of its own.
///
/// **Session/peer scoping (brief §6/§22).** `remoteEntries` is cleared on every
/// [handleConnected]/[handleLinkLost] — a peer's manifest never survives past its own session.
/// The verified transfer cache is **not** cleared here: it is independent of any session
/// (ADR-023).
///
/// **Concurrency (brief §20).** One active transfer at a time; further requests queue FIFO —
/// `downloadQueue` enforces this at this layer, and `TransferManager`'s own explicit
/// `transferInProgress` gate enforces it again at the bulk-transport layer (Finding E — actor
/// reentrancy alone does not).
///
/// **Playback (brief §19)** of a verified cached or Phase-4 cache-only file goes through the
/// existing `MusicCoordinator`/queue/player — this class never creates a second player or queue,
/// and never synchronizes the peer's playback. It only ever hands back a file URL via
/// [cachedFile]; the composition root is what wires that into `MusicCoordinator`.
///
/// **Operation ownership (closure audit, ADR-023 Amendment A1).** [transferFence] is the "small
/// authoritative operation-generation guard" this pass introduces in place of routing every step
/// through `TransferReducer` (brief §16): a superseded transfer operation — cancelled by the user,
/// invalidated by a session boundary, or replaced by a fresher one — can never again mutate
/// `downloadStates`/`activeDownload`, no matter how late its own `Task`'s cleanup eventually runs.
/// Inbound `MANIFEST_*` handling gets the equivalent guard from `sessionEpoch`, a small lock-backed
/// counter captured at message-dispatch time and re-checked at apply time (Finding S) — a plain
/// generation read cannot be used there directly since the dispatch closure runs synchronously,
/// off `@MainActor`, and cannot `await` across into `ControlSessionManager`'s own actor.
///
/// **Provider operation ownership (Phase 4 closure-audit follow-up, ADR-023 Amendment A2).**
/// [bulkGate] replaces the plain `activeServeTransferId` var this pass found insufficient: a
/// second `TRANSFER_REQUEST` arriving before the first's `TransferManager.serve` call actually
/// completes used to silently overwrite that var, so a peer `TRANSFER_CANCEL` could be routed to
/// the wrong transfer or ignored outright (Finding A). `bulkGate` is also the single shared owner
/// across **both** roles — requester (a local download) and provider (serving a peer's request) —
/// matching `TransferManager`'s own `transferInProgress` gate, which already serializes
/// `serve`/`fetch` across both roles onto one real socket at a time (Finding A's cross-role
/// counterpart, brief §17/§18).
///
/// **Inbound `TRANSFER_*` session binding (Finding B).** [handleTransferMessage] is now guarded by
/// the same live-epoch check [handleManifestMessage] already had — every `TRANSFER_REQUEST`/
/// `TRANSFER_OFFER`/`TRANSFER_PROGRESS`/`TRANSFER_RESULT`/`TRANSFER_CANCEL` dispatched under a
/// session that has since been superseded is dropped before it can touch [bulkGate],
/// `pendingOfferTransferId`, or any provider state.
///
/// **Suspension-point re-authorisation (ADR-023 Amendment A3).** The dispatch-entry check above
/// only proves the request was current the instant it was read off the wire — `serveTransferRequest`
/// itself suspends repeatedly afterward (every `await`, `bulkTransport.ensureListening`,
/// `transferRelay().send`), and a session boundary landing inside any of those used to go completely
/// unnoticed: [onSessionBoundary] frees [bulkGate] and bumps [sessionEpoch], so a stale request
/// could otherwise acquire the freed slot and go on to mint a token and send an offer under the
/// *new* session, potentially serving old peer A's file to whichever peer is connected now.
/// `RideLinkCore.Transfer.ProviderSessionContext` captures the authorising epoch/peer once and
/// [stillAuthorised] re-proves it (via [bulkGate] ownership, once acquired) at every one of those
/// suspension points. The same amendment also makes `TransferManager.cancelActive` operation-aware
/// (`transferId`-scoped, closing a delayed-cancel race that could otherwise close a *different*,
/// newer operation's socket) and makes [onSessionBoundary] `async`, so the old session's transport
/// teardown fully finishes before [bulkGate] is invalidated and a new session's activity can start.
@Observable
@MainActor
public final class SharedLibraryCoordinator {
    public private(set) var remoteEntries: [ManifestEntry] = []
    public private(set) var downloadStates: [String: DownloadState] = [:]

    private let controlSessionManager: ControlSessionManager
    private let bulkTransport: TransferManager
    private let libraryRepository: LibraryRepository
    private let manifestGenerator: ManifestGenerator
    private let cacheStorage: CacheStorage
    private let cacheRepository: TransferCacheRepository
    private let contentResolver: LocalContentResolver
    private let monotonicNowUs: @Sendable () -> Int64
    /// Finding I: the hash of whatever cache-only track `MusicCoordinator`'s player currently has
    /// open, if any — included in every cache-commit's `locked` set so an actively playing cache
    /// entry is never evicted out from under the player.
    private let activeCacheHash: @MainActor () -> ContentHash?

    private var syncState = ManifestSyncState(liveRevision: 0)
    private var downloadQueue: [ContentHash] = []
    private var activeDownload: ContentHash?
    private var activeDownloadTask: Task<Void, Never>?
    private var pendingOfferTransferId: TransferId?
    private var pendingOfferContinuation: CheckedContinuation<PendingOffer?, Never>?

    /// Finding A / brief §17-18: the one bulk operation — requester or provider — this session may
    /// have active at once. Replaces the plain `activeServeTransferId` var this pass found
    /// insufficient: acquisition is refused outright while another operation holds the slot, so
    /// ownership can never be silently overwritten, and `TRANSFER_CANCEL` routing
    /// ([handlePeerCancel]) asks *this* gate rather than trusting a var that may already be stale.
    private let bulkGate = BulkOperationGate()

    /// Finding R: a real, monotonically-increasing catalogue revision — bumped only when the
    /// generated entry set actually differs from what was last served, never a hardcoded constant.
    /// `kind` remains always `.full` and `sinceRevision` is still ignored: delta sync itself stays
    /// out of V1 scope (brief §22's explicitly permitted "correct V1 simplification"), disclosed in
    /// ADR-023 Amendment A1 rather than silently implied by a meaningless-but-present revision.
    private var catalogueRevision: Int64 = 0
    private var lastServedEntries: [ManifestEntry]?

    private let transferFence = OperationFence()

    /// Finding S: bumped on every session boundary so a manifest message dispatched under an old
    /// session — read off the wire and handed to `ManifestSinkAdapter` a moment before the boundary
    /// — is provably distinguishable from one belonging to whatever session comes after it. See the
    /// `SessionEpoch` type below for why this cannot simply be `ControlSessionManager.currentAuthGeneration`
    /// read directly at dispatch time.
    private let sessionEpoch = SessionEpoch()

    private static let negotiationTimeoutNs: UInt64 = 10_000_000_000
    private static let chunkSizeBytes = TransferBounds.chunkSize

    public init(
        controlSessionManager: ControlSessionManager,
        bulkTransport: TransferManager,
        libraryRepository: LibraryRepository,
        libraryDatabaseQueue: DatabaseQueue,
        libraryIndexer: LibraryIndexer,
        monotonicNowUs: @escaping @Sendable () -> Int64,
        activeCacheHash: @escaping @MainActor () -> ContentHash? = { nil }
    ) {
        self.controlSessionManager = controlSessionManager
        self.bulkTransport = bulkTransport
        self.libraryRepository = libraryRepository
        self.manifestGenerator = ManifestGenerator(libraryRepository: libraryRepository)
        let root = Self.cacheRoot()
        let storage = CacheStorage(root: root)
        self.cacheStorage = storage
        self.cacheRepository = TransferCacheRepository(storage: storage, dbQueue: libraryDatabaseQueue)
        self.contentResolver = LocalContentResolver(
            libraryRepository: libraryRepository,
            libraryIndexer: libraryIndexer,
            cacheRepository: cacheRepository
        )
        self.monotonicNowUs = monotonicNowUs
        self.activeCacheHash = activeCacheHash

        let manager = controlSessionManager
        let epoch = sessionEpoch
        Task { [weak self] in
            let manifestRelay = await manager.manifestRelay()
            await manifestRelay.setSink(ManifestSinkAdapter { message in
                // Finding S: `ManifestSink.submit` is a synchronous, non-async protocol requirement
                // (it must not block the relay actor's read loop), so it cannot `await` across into
                // `ControlSessionManager`'s own actor to read a generation directly. `epoch.current()`
                // is the same lock-protected-counter pattern `ReceivedCounter` already uses elsewhere
                // in this file for exactly this "safely read from outside MainActor" need — captured
                // here, at message-dispatch time, and re-checked once the scheduled `@MainActor` Task
                // below actually runs.
                let capturedEpoch = epoch.current()
                Task { @MainActor in self?.handleManifestMessage(message, epoch: capturedEpoch) }
            })
            let transferRelay = await manager.transferRelay()
            await transferRelay.setSink(TransferSinkAdapter(
                onMessage: { message, capturedEpoch in
                    Task { @MainActor in self?.handleTransferMessage(message, epoch: capturedEpoch) }
                },
                epoch: { epoch.current() }
            ))
        }
        // Brief §12: nothing partial ever survives a process restart as a candidate to resume.
        storage.sweepIncomplete()
    }

    private static func cacheRoot() -> URL {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        let root = support.appendingPathComponent("RideLink/transfer_cache", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }

    /// Combines Phase 3 local presence, the verified cache and the peer's current manifest into one
    /// display fact (brief §7) — never inferred from manifest presence alone, and never `true` for
    /// `hasCached` before a transfer's atomic commit.
    public func availability(for entry: ManifestEntry) -> Availability {
        let hasRemote = entry.contentHash.map { hash in remoteEntries.contains { $0.contentHash == hash } } ?? false
        let hasLocal = entry.contentHash.flatMap { try? libraryRepository.findByContentHash($0) } != nil
        let hasCached = entry.contentHash.map(isVerifiedCached) ?? false
        return Availability(hasLocal: hasLocal, hasCached: hasCached, hasRemote: hasRemote)
    }

    /// True once bytes have arrived, been whole-file verified, **and** committed — never merely queued or transferring.
    public func isVerifiedCached(_ contentHash: ContentHash) -> Bool {
        (try? cacheRepository.isVerifiedCached(contentHash)) ?? false
    }

    /// brief §19: hands a verified cached file's location to the caller, which plays it through the *existing* player.
    public func cachedFile(_ contentHash: ContentHash) -> URL? {
        try? cacheRepository.open(contentHash, nowMonoUs: monotonicNowUs())
    }

    /// Called by `SessionCoordinator` on `.connected` — see that type's doc comment on why this is
    /// forwarded rather than self-subscribed. `async`: see [onSessionBoundary]'s doc comment for why
    /// the old session's transport teardown must fully finish before this returns.
    public func handleConnected() async {
        await onSessionBoundary()
        requestCatalogue()
    }

    /// Called by `SessionCoordinator` on `.linkLost`. ADR-023 §1: the bulk listener never outlives
    /// the session that opened it.
    public func handleLinkLost() async {
        await onSessionBoundary()
    }

    /// Closure-audit Findings B/D: one explicit lifecycle owner for everything a session boundary
    /// must invalidate. `bulkTransport.close()` (ADR-023 §1) tears down the listener *and* clears
    /// every outstanding token; `transferFence` supersedes so a stale transfer completion dispatched
    /// just before this boundary can't mutate whatever comes after it (brief §17), and `sessionEpoch`
    /// bumping gives manifest handling the equivalent protection (brief §23); the active download's
    /// Task is cancelled, its `.part` removed and its state marked terminal rather than left dangling.
    ///
    /// **ADR-023 Amendment A3 — `await`ed, not fire-and-forget.** This used to schedule
    /// `Task { await transport.close() }` and immediately, synchronously, invalidate `bulkGate` —
    /// which could let a brand-new session's provider/requester operation acquire the gate and start
    /// using the transport (`ensureListening()`, a fresh `serve`/`fetch`) *before* the old session's
    /// `close()` had actually finished, and — because `close()` unconditionally clears whatever
    /// `TransferManager.listener`/`activeSocket` is current — that stale, late-finishing `close()`
    /// could then tear down the *new* session's freshly opened listener or socket instead of the old
    /// one. `async` here, and at both call sites above, makes `close()` complete before `bulkGate`
    /// is invalidated and before [requestCatalogue] (or anything else) can start new activity, so
    /// there is no window left for a newer session to be torn down by an older session's teardown.
    private func onSessionBoundary() async {
        // brief §6/§22: a peer's catalogue is session/peer-scoped and must never leak across a
        // reconnect or a different peer — replaced wholesale, never merged with what came before.
        remoteEntries = []
        syncState = ManifestSyncState(liveRevision: 0)
        sessionEpoch.bump()
        await bulkTransport.close()

        // Finding A: unconditionally frees the provider/requester ownership slot no matter who
        // holds it — `transport.close()` above already finished force-closing whatever real socket
        // that holder's `serve`/`fetch` call was blocked on, so its own eventual cleanup finds
        // nothing left to (mis)clear.
        bulkGate.invalidate()
        let hashToClear = activeDownload
        transferFence.supersede()
        pendingOfferContinuation?.resume(returning: nil)
        pendingOfferContinuation = nil
        activeDownloadTask?.cancel()
        activeDownloadTask = nil
        downloadQueue = []
        activeDownload = nil
        if let hash = hashToClear {
            cacheStorage.deletePart(hash)
            downloadStates[hash.value] = DownloadState(status: .failed, error: .connectionLost)
        }
    }

    /// Requests the peer's full catalogue — called once per session, on [handleConnected].
    public func requestCatalogue() {
        let relay = controlSessionManager
        Task {
            let manifestRelay = await relay.manifestRelay()
            _ = await manifestRelay.send(.request(sinceRevision: nil, maxPageBytes: ManifestPaging.manifestPageSoftLimitBytes))
        }
    }

    /// brief §17: never re-transfers content already held locally or in the verified cache.
    public func requestDownload(_ entry: ManifestEntry) {
        guard let hash = entry.contentHash else { return }
        guard !isVerifiedCached(hash) else { return }
        guard !downloadQueue.contains(hash), activeDownload != hash else { return }
        downloadQueue.append(hash)
        // No fence token exists yet (one is minted in pumpQueue when this hash is actually
        // dequeued) — a direct write is safe: nothing is "in flight" for this hash to race with,
        // since the two guards above just proved it was neither queued nor active.
        downloadStates[hash.value] = DownloadState(status: .queued)
        pumpQueue()
    }

    /// Closure-audit Findings C/N: a real, terminal cancellation — not merely a UI-state change.
    /// Supersedes the active operation's fence token (so its own late `finishDownload`/`setState`
    /// calls become inert no matter how far the original `Task` has already run), cancels the
    /// tracked `Task`, force-closes whatever bulk socket that task might be blocked on, deletes the
    /// `.part` file so a re-request never races a still-writing old task (brief §18), and — PROTOCOL
    /// §8.2 — tells the peer with `TRANSFER_CANCEL` rather than relying on the connection merely
    /// dropping.
    public func cancelDownload(_ contentHash: ContentHash) {
        downloadQueue.removeAll { $0 == contentHash }
        guard activeDownload == contentHash else { return }
        let transferId = pendingOfferTransferId
        transferFence.supersede()
        pendingOfferContinuation?.resume(returning: nil)
        pendingOfferContinuation = nil
        activeDownloadTask?.cancel()
        activeDownloadTask = nil
        // Finding A: only force-close the shared bulk transport if this transfer is still its real
        // owner — `activeDownload`/`pendingOfferTransferId` alone are coordinator/queue
        // bookkeeping, not proof this operation ever actually acquired the transport's one active
        // slot (e.g. cancelling during `.negotiating`, before any bulk socket opened, or while the
        // shared slot happens to belong to this session's own provider operation).
        if let transferId, bulkGate.isOwner(transferId) {
            let transport = bulkTransport
            // ADR-023 Amendment A3: transferId-aware — even though this Task is not awaited before
            // bulkGate is released just below (so a queued download can start immediately rather
            // than wait on a cancellation that may take a moment to actually unblock a socket),
            // TransferManager.cancelActive(transferId:) re-checks its own live activeTransferId at
            // the moment it actually runs, on its own actor — a late-running cancel for this
            // transferId can therefore never close a different, newer operation's socket, no matter
            // how much later it executes.
            Task { await transport.cancelActive(transferId: transferId) }
        }
        if let transferId { bulkGate.releaseIfOwner(transferId) }
        cacheStorage.deletePart(contentHash)
        // Authoritative terminal write: cancellation itself is never subject to the fence check
        // that guards a late completion — this *is* the write that must win.
        downloadStates[contentHash.value] = DownloadState(status: .cancelled)
        activeDownload = nil
        if let transferId {
            let relay = controlSessionManager
            Task { _ = await relay.transferRelay().send(.cancel(transferId: transferId, reason: "user_cancelled")) }
        }
        pumpQueue()
    }

    // MARK: - manifest: both roles, since both peers are symmetric

    /// Closure-audit Finding S: `epoch` is `sessionEpoch`'s value as it was the moment this message
    /// was read off the wire, captured in the `sink` closure at `init`. If a session boundary has
    /// since bumped `sessionEpoch` by the time this `Task` actually runs, the message is a stale
    /// artefact of a torn-down session and is dropped before it can touch
    /// `syncState`/`remoteEntries` — the concrete mechanism behind "a late PAGE/END from session A
    /// must never mutate session B's catalogue."
    private func handleManifestMessage(_ message: ManifestMessage, epoch: Int64) {
        guard epoch == sessionEpoch.current() else { return }
        switch message {
        case .request(let sinceRevision, let maxPageBytes):
            serveManifestRequest(sinceRevision: sinceRevision, maxPageBytes: maxPageBytes)
        case .begin, .page, .end, .abort:
            applyManifestSyncEvent(message)
        }
    }

    private func applyManifestSyncEvent(_ message: ManifestMessage) {
        let event: ManifestSyncEvent
        switch message {
        case .begin(let manifestId, let kind, let manifestRevision, let baseRevision, let totalEntries, let totalRemoved, _, _):
            event = .begin(
                manifestId: manifestId, kind: kind, manifestRevision: manifestRevision,
                baseRevision: baseRevision, totalEntries: totalEntries, totalRemoved: totalRemoved
            )
        case .page(let manifestId, let manifestRevision, let pageIndex, let entries, let removed):
            event = .page(manifestId: manifestId, manifestRevision: manifestRevision, pageIndex: pageIndex, entries: entries, removed: removed)
        case .end(let manifestId, let manifestRevision, let pageCount, let totalEntries, let totalRemoved, let digest):
            event = .end(
                manifestId: manifestId, manifestRevision: manifestRevision, pageCount: pageCount,
                totalEntries: totalEntries, totalRemoved: totalRemoved, digest: digest
            )
        case .abort(let manifestId, let reason):
            event = .abort(manifestId: manifestId, reason: reason)
        case .request:
            return
        }
        let (nextState, result) = ManifestSync.apply(event, to: syncState)
        syncState = nextState
        if case .committed(_, let entries, _) = result {
            // Atomic swap, exactly ADR-013's rule: the whole catalogue replaces the old one at once.
            remoteEntries = entries
        }
    }

    private func serveManifestRequest(sinceRevision: Int64?, maxPageBytes: Int) {
        let generator = manifestGenerator
        let relayHolder = controlSessionManager
        Task {
            guard let entries = try? await generator.generate() else { return }
            if lastServedEntries == nil || entries != lastServedEntries {
                catalogueRevision += 1
                lastServedEntries = entries
            }
            let revision = catalogueRevision
            let budget = min(ManifestPaging.manifestPageSoftLimitBytes, maxPageBytes)
            let pages = ManifestPaging.paginate(entries, budgetBytes: budget)
            let manifestId = ManifestId(Ulid.generate())
            let relay = await relayHolder.manifestRelay()
            _ = await relay.send(.begin(
                manifestId: manifestId, kind: .full, manifestRevision: revision, baseRevision: nil,
                totalEntries: entries.count, totalRemoved: 0, pageCount: pages.count, digestAlg: "ridelink-manifest-v1"
            ))
            for (index, page) in pages.enumerated() {
                _ = await relay.send(.page(manifestId: manifestId, manifestRevision: revision, pageIndex: index, entries: page, removed: []))
            }
            let clamped = entries.map(ManifestPaging.clampEntry)
            _ = await relay.send(.end(
                manifestId: manifestId, manifestRevision: revision, pageCount: pages.count,
                totalEntries: entries.count, totalRemoved: 0, digest: ManifestPaging.digest(entries: clamped, removed: [])
            ))
        }
    }

    // MARK: - transfer: requester side

    private func pumpQueue() {
        guard activeDownload == nil, let next = downloadQueue.first else { return }
        let transferId = TransferId(Ulid.generate())
        // Finding A cross-role (brief §17/§18): the shared bulk slot may be held right now by this
        // device's own *provider* operation (serving a peer's request). Leave `next` queued rather
        // than start a download the transport cannot actually honour yet — the provider
        // operation's own cleanup calls pumpQueue() again once it releases the slot, so this
        // retries automatically, no new queue needed.
        guard bulkGate.tryAcquire(.requester(transferId: transferId, contentHash: next)) else { return }
        downloadQueue.removeFirst()
        activeDownload = next
        let opToken = transferFence.begin()
        activeDownloadTask = Task { await runDownload(next, transferId: transferId, opToken: opToken) }
    }

    private func runDownload(_ hash: ContentHash, transferId: TransferId, opToken: Int64) async {
        pendingOfferTransferId = transferId
        defer {
            // Finding A: released here for every exit path below (idempotent — a no-op if
            // cancelDownload or a session boundary already released/invalidated the slot first).
            bulkGate.releaseIfOwner(transferId)
        }
        setState(hash, DownloadState(status: .negotiating), opToken: opToken)
        _ = await controlSessionManager.transferRelay().send(.request(contentHash: hash, transferId: transferId))

        guard let offer = await withOfferTimeout() else {
            finishDownload(hash, DownloadState(status: .failed, error: .notFound), opToken: opToken)
            return
        }
        guard let peerSpki = await controlSessionManager.currentPeerSpki, let peerHost = await controlSessionManager.currentPeerHost else {
            finishDownload(hash, DownloadState(status: .failed, error: .connectionLost), opToken: opToken)
            return
        }

        setState(hash, DownloadState(status: .transferring, totalBytes: offer.sizeBytes), opToken: opToken)
        guard let handle = try? cacheStorage.openPartForWrite(hash) else {
            finishDownload(hash, DownloadState(status: .failed, error: .ioError), opToken: opToken)
            return
        }
        let received = ReceivedCounter()
        let expectedChunkCount = (offer.sizeBytes + Int64(Self.chunkSizeBytes) - 1) / Int64(Self.chunkSizeBytes)
        let sink = DownloadChunkSink(handle: handle, storage: cacheStorage, received: received) { [weak self] bytes in
            Task { @MainActor in
                self?.setState(hash, DownloadState(status: .transferring, bytesReceived: bytes, totalBytes: offer.sizeBytes), opToken: opToken)
            }
        }
        let outcome: BulkFetchOutcome
        if let port = UInt16(exactly: offer.bulkPort) {
            outcome = await bulkTransport.fetch(
                transferId: transferId, host: peerHost, port: port, token: offer.bulkToken,
                expectedPeerSpki: peerSpki, expectedChunkCount: expectedChunkCount, sink: sink
            )
        } else {
            outcome = .protocolError
        }
        try? handle.close()

        guard outcome == .ok else {
            cacheStorage.deletePart(hash)
            finishDownload(hash, DownloadState(status: .failed, error: outcome.asTransferError), opToken: opToken)
            return
        }
        setState(hash, DownloadState(status: .verifying, totalBytes: offer.sizeBytes), opToken: opToken)
        switch cacheStorage.promote(hash, expectedSizeBytes: offer.sizeBytes) {
        case .promoted:
            // Closure-audit Finding P: promote-then-commit must not report success unless the
            // metadata commit itself actually succeeded — `try?` here used to silently swallow a
            // commit failure and still send `TRANSFER_RESULT(ok: true)`/mark `.complete`.
            do {
                try cacheRepository.commit(hash, sizeBytes: offer.sizeBytes, nowMonoUs: monotonicNowUs(), locked: Set([activeCacheHash(), hash].compactMap { $0 }))
                _ = await controlSessionManager.transferRelay().send(.result(transferId: transferId, ok: true, sha256: hash))
                finishDownload(hash, DownloadState(status: .complete, totalBytes: offer.sizeBytes), opToken: opToken)
            } catch {
                _ = await controlSessionManager.transferRelay().send(.result(transferId: transferId, ok: false, sha256: nil))
                finishDownload(hash, DownloadState(status: .failed, error: .ioError), opToken: opToken)
            }
        case let failure:
            _ = await controlSessionManager.transferRelay().send(.result(transferId: transferId, ok: false, sha256: nil))
            finishDownload(hash, DownloadState(status: .failed, error: failure.asTransferError), opToken: opToken)
        }
    }

    /// Awaits the pending `TRANSFER_OFFER`, or `nil` after a 10 s negotiation timeout — mirroring
    /// Android's `withTimeoutOrNull`. Whichever of "an offer arrived" (`handleTransferMessage`'s
    /// `.offer` case) or "the timeout fired" (below) runs first resumes the continuation and clears
    /// it; both run on `@MainActor` with no suspension between the check and the resume, so exactly
    /// one of them ever wins.
    private func withOfferTimeout() async -> PendingOffer? {
        await withCheckedContinuation { (continuation: CheckedContinuation<PendingOffer?, Never>) in
            pendingOfferContinuation = continuation
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: Self.negotiationTimeoutNs)
                guard let self, let pending = self.pendingOfferContinuation else { return }
                self.pendingOfferContinuation = nil
                pending.resume(returning: nil)
            }
        }
    }

    private func finishDownload(_ hash: ContentHash, _ state: DownloadState, opToken: Int64) {
        setState(hash, state, opToken: opToken)
        guard transferFence.isCurrent(opToken) else { return } // superseded — cancellation/session-boundary already cleaned up
        if activeDownload == hash { activeDownload = nil }
        activeDownloadTask = nil
        pumpQueue()
    }

    /// Closure-audit Findings C/D/O/P/S (terminal-state rule, brief §17): `opToken` must still be
    /// the fence's current operation for this write to apply. A cancelled or session-superseded
    /// operation's own in-flight `Task` keeps running (cooperative cancellation is not
    /// instantaneous), but every state write it attempts after being superseded is silently dropped
    /// — `CANCELLED -> COMPLETE`, `FAILED -> COMPLETE`, and "old-session COMPLETE mutating new-session
    /// state" are all made structurally impossible by this one check, not by timing.
    private func setState(_ hash: ContentHash, _ state: DownloadState, opToken: Int64) {
        guard transferFence.isCurrent(opToken) else { return }
        downloadStates[hash.value] = state
    }

    // MARK: - transfer: provider side

    /// Closure-audit Finding B: `epoch` is `sessionEpoch`'s value as it was the moment this message
    /// was read off the wire, captured in the `sink` closure at `init` — exactly the guard
    /// `handleManifestMessage` already had. Every `TRANSFER_REQUEST`/`TRANSFER_OFFER`/
    /// `TRANSFER_PROGRESS`/`TRANSFER_RESULT`/`TRANSFER_CANCEL` dispatched under a session that has
    /// since been superseded is dropped here, before it can touch `bulkGate`,
    /// `pendingOfferTransferId`, or any provider/requester state — a stale `REQUEST` cannot be
    /// served under the new peer's SPKI/generation, a stale `OFFER` cannot satisfy a new session's
    /// pending request, and a stale `CANCEL` cannot cancel a new session's transfer.
    private func handleTransferMessage(_ message: TransferMessage, epoch: Int64) {
        guard epoch == sessionEpoch.current() else { return }
        switch message {
        case .request(let contentHash, let transferId):
            Task { await serveTransferRequest(contentHash: contentHash, transferId: transferId, authorisingEpoch: epoch) }
        case .offer(let transferId, let sizeBytes, _, _, let bulkPort, let bulkToken):
            if pendingOfferTransferId == transferId {
                pendingOfferContinuation?.resume(returning: PendingOffer(
                    transferId: transferId, sizeBytes: sizeBytes, bulkPort: bulkPort, bulkToken: bulkToken
                ))
                pendingOfferContinuation = nil
            }
        case .progress, .result:
            // brief §28: peer-reported progress is never trusted or displayed; the requester
            // already knows its own outcome from its own verification.
            break
        case .cancel(let transferId, _):
            handlePeerCancel(transferId)
        }
    }

    /// Closure-audit Finding N, hardened by Finding A: PROTOCOL §8.2 — `TRANSFER_CANCEL` is valid
    /// from either side at any time and both drop the bulk connection. Only honoured if
    /// `transferId` names the transfer `bulkGate` currently holds — not a plain
    /// `activeServeTransferId` var that a second, not-yet-actually-serving request could have
    /// silently overwritten — so a cancel for a stale, foreign, queued, or already-finished
    /// transfer_id is a no-op, never a way to disrupt an unrelated (possibly requester-role)
    /// transfer sharing the same underlying bulk socket.
    private func handlePeerCancel(_ transferId: TransferId) {
        guard bulkGate.isOwner(transferId) else { return }
        let transport = bulkTransport
        // ADR-023 Amendment A3: transferId-aware, for the same reason cancelDownload's call is.
        Task { await transport.cancelActive(transferId: transferId) }
    }

    /// ADR-023 Amendment A3: `authorisingEpoch` is the same value [handleTransferMessage] already
    /// checked against [sessionEpoch] at dispatch time — not re-read here — because this whole
    /// function is riddled with suspension points (every `await`, including the actor hops just to
    /// read `currentPeerSpki`/`currentAuthGeneration`, plus `bulkTransport.ensureListening` and
    /// `transferRelay().send`) a session boundary can land inside. Amendment A2's dispatch-entry
    /// check alone proves nothing about what the session looks like by the time any of *those* run:
    /// [ProviderSessionContext.isStillCurrent] and [stillAuthorised] are the re-checks that close
    /// that gap.
    private func serveTransferRequest(contentHash: ContentHash, transferId: TransferId, authorisingEpoch: Int64) async {
        guard let peerSpki = await controlSessionManager.currentPeerSpki else { return }
        // Finding A §12 / Amendment A3: the session that authorises this operation for its whole
        // lifetime, not merely a value read once and forgotten — bulkGate.tryAcquire below records
        // it into BulkOperationOwner.provider, and isStillCurrent is what every suspension point
        // after that re-proves against.
        let authorisation = ProviderSessionContext(authorisingGeneration: authorisingEpoch, authorisedPeerSpki: peerSpki)
        guard let resolution = try? contentResolver.resolve(contentHash: contentHash, nowMonoUs: monotonicNowUs()) else { return }
        guard case .found(let fileURL, let sizeBytes) = resolution else {
            // No wire message exists for "cannot serve this request" (PROTOCOL §8.2 has no
            // rejection shape for TRANSFER_REQUEST itself) — the requester's own negotiation
            // timeout is what resolves this, exactly as an unreachable peer would.
            return
        }
        // Amendment A3 — the primary gap this amendment closes: every `await` above is a real
        // suspension point. A session boundary landing inside one of them frees bulkGate
        // (onSessionBoundary's invalidate()) and bumps sessionEpoch — without this check, a stale
        // request from an old peer could then acquire the slot and go on to mint a token and send an
        // offer under the *new* live session, serving old peer A's requested file to whichever peer
        // is connected now.
        guard authorisation.isStillCurrent(liveGeneration: sessionEpoch.current(), livePeerSpki: await controlSessionManager.currentPeerSpki) else {
            return
        }
        // Closure-audit Finding Q: never construct/send an offer the peer's own codec would have to
        // reject — check the bound here, on the sender, rather than relying solely on the
        // receiver's `TransferCodec.parseOffer` size check.
        guard sizeBytes <= TransferBounds.maxTransferSizeBytes else { return }
        // Finding A: acquire the one shared bulk-operation slot — across *both* provider and
        // requester roles (brief §17/§18) — before ever sending an offer. A second concurrent
        // TRANSFER_REQUEST, or a local download already in flight, must not overwrite ownership or
        // receive an offer this coordinator cannot yet honour; the requester's own negotiation
        // timeout resolves this (brief §19 — no BUSY wire shape).
        guard bulkGate.tryAcquire(.provider(
            transferId: transferId, contentHash: contentHash, peerSpki: peerSpki, sessionGeneration: authorisingEpoch
        )) else { return }
        guard let port = try? await bulkTransport.ensureListening(), let bulkPort = Int(exactly: port) else {
            bulkGate.releaseIfOwner(transferId)
            pumpQueue() // cross-role: wake a local download left queued behind this attempt
            return
        }
        // Amendment A3: every suspension point from here on re-checks stillAuthorised(transferId)
        // instead of re-deriving epoch/peerSpki — onSessionBoundary() unconditionally invalidates
        // bulkGate on *every* boundary, and transfer_id is a fresh ULID never reused (ADR-023 §2),
        // so "do I still own my own slot" is an equally correct, strictly simpler proxy for "has a
        // boundary happened since I acquired" than re-reading epoch/peerSpki again at each step.
        guard stillAuthorised(transferId) else { return }
        // Closure-audit Finding A: read the *live* current authenticated generation both at
        // issuance and again, independently, at consumption time — never a value captured once and
        // replayed. A stale closure over a captured `let` would defeat ADR-023 §3's whole
        // "reconnect invalidates every outstanding token" guarantee. Amendment A3 does not touch
        // this: the outer authorisation check above is a different, additional guard, layered on
        // top of — never a replacement for — this live re-read.
        let generation = await controlSessionManager.currentAuthGeneration
        guard let token = await bulkTransport.tryIssueToken(transferId: transferId, generation: generation) else {
            bulkGate.releaseIfOwner(transferId)
            pumpQueue() // cross-role: wake a local download left queued behind this attempt
            return
        }
        guard stillAuthorised(transferId) else {
            bulkGate.releaseIfOwner(transferId)
            pumpQueue()
            return
        }
        let chunkCount = Int((sizeBytes + Int64(Self.chunkSizeBytes) - 1) / Int64(Self.chunkSizeBytes))
        _ = await controlSessionManager.transferRelay().send(.offer(
            transferId: transferId, sizeBytes: sizeBytes, chunkSize: Self.chunkSizeBytes,
            chunkCount: chunkCount, bulkPort: bulkPort, bulkToken: token
        ))
        guard stillAuthorised(transferId) else {
            bulkGate.releaseIfOwner(transferId)
            pumpQueue()
            return
        }
        guard let handle = try? FileHandle(forReadingFrom: fileURL) else {
            bulkGate.releaseIfOwner(transferId)
            pumpQueue() // cross-role: wake a local download left queued behind this attempt
            return
        }
        defer {
            // Finding A: idempotent by construction — a no-op if a session boundary already called
            // bulkGate.invalidate(), and never clears a fresher operation that has since acquired
            // the slot under a different transfer_id.
            bulkGate.releaseIfOwner(transferId)
            // Cross-role (brief §17/§18): wake a local download that was left queued behind this
            // provider operation — a harmless no-op if none is queued.
            pumpQueue()
        }
        let source = FileChunkSource(handle: handle, chunkSize: Self.chunkSizeBytes)
        let manager = controlSessionManager
        _ = await bulkTransport.serve(
            transferId: transferId, expectedPeerSpki: peerSpki,
            currentGeneration: { await manager.currentAuthGeneration }, source: source
        )
        try? handle.close()
    }

    /// ADR-023 Amendment A3: whether `transferId` still holds `bulkGate` — the post-acquisition
    /// proxy for "no session boundary has run since this operation acquired the slot," used at
    /// every suspension point [serveTransferRequest] passes through after `bulkGate.tryAcquire`
    /// succeeds. Valid because [onSessionBoundary] unconditionally invalidates `bulkGate` on *every*
    /// boundary and a `transfer_id` is a fresh ULID never reused (ADR-023 §2), so no other operation
    /// can ever be mistaken for this one.
    private func stillAuthorised(_ transferId: TransferId) -> Bool {
        bulkGate.isOwner(transferId)
    }
}

/// Closure-audit Finding S: a tiny thread-safe counter, mirroring `ReceivedCounter` below, so the
/// non-async `ManifestSinkAdapter` closure (called synchronously from `ManifestRelay`'s own actor,
/// which cannot `await` back into `ControlSessionManager`) can read "which session is this message
/// being dispatched under" without an actor hop, then have the scheduled `@MainActor` `Task`
/// re-check it once it actually runs.
private final class SessionEpoch: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Int64 = 0

    /// Called only from `onSessionBoundary()`, on `@MainActor` — invalidates whatever epoch was
    /// current, exactly like `OperationFence.supersede()`.
    @discardableResult
    func bump() -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        value += 1
        return value
    }

    func current() -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        return value
    }
}

/// A tiny thread-safe counter, so `DownloadChunkSink.onChunk` (called off the main actor, from
/// `TransferManager`) can safely accumulate a running byte total without racing the UI reads.
private final class ReceivedCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Int64 = 0

    func add(_ n: Int64) -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        value += n
        return value
    }
}

private struct DownloadChunkSink: ChunkSink {
    let handle: FileHandle
    let storage: CacheStorage
    let received: ReceivedCounter
    let onProgress: @Sendable (Int64) -> Void

    func onChunk(index: Int64, bytes: [UInt8]) async {
        try? storage.appendChunk(handle, bytes: Data(bytes))
        onProgress(received.add(Int64(bytes.count)))
    }
}

private struct FileChunkSource: ChunkSource {
    let handle: FileHandle
    let chunkSize: Int

    func nextChunk() async -> [UInt8]? {
        guard let data = try? handle.read(upToCount: chunkSize), !data.isEmpty else { return nil }
        return [UInt8](data)
    }
}

private extension BulkFetchOutcome {
    var asTransferError: TransferError {
        switch self {
        case .ok: return .protocolError // unreachable: only read on a non-ok outcome
        case .notAuthorized: return .notAuthorized
        case .connectionLost: return .connectionLost
        case .ioError: return .ioError
        case .protocolError: return .protocolError
        }
    }
}

private extension PromoteResult {
    var asTransferError: TransferError {
        switch self {
        case .promoted: return .protocolError // unreachable: only read on a non-promoted result
        case .sizeMismatch: return .sizeMismatch
        case .hashMismatch: return .hashMismatch
        case .ioError: return .ioError
        }
    }
}
