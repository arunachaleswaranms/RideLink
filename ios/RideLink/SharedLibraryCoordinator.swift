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
    let onMessage: @Sendable (TransferMessage) -> Void
    func submit(_ message: TransferMessage) { onMessage(message) }
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
/// `downloadQueue` enforces this at this layer, and `TransferManager`'s own actor isolation
/// enforces it again at the bulk-transport layer, for the reason that type's doc comment gives.
///
/// **Playback (brief §19)** of a verified cached file goes through the existing
/// `MusicCoordinator`/queue/player — this class never creates a second player or queue, and never
/// synchronizes the peer's playback. It only ever hands back a file URL via [cachedFile]; the
/// composition root is what wires that into `MusicCoordinator`.
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

    private var syncState = ManifestSyncState(liveRevision: 0)
    private var downloadQueue: [ContentHash] = []
    private var activeDownload: ContentHash?
    private var pendingOfferTransferId: TransferId?
    private var pendingOfferContinuation: CheckedContinuation<PendingOffer?, Never>?

    private static let negotiationTimeoutNs: UInt64 = 10_000_000_000
    private static let chunkSizeBytes = TransferBounds.chunkSize

    public init(
        controlSessionManager: ControlSessionManager,
        bulkTransport: TransferManager,
        libraryRepository: LibraryRepository,
        libraryDatabaseQueue: DatabaseQueue,
        libraryIndexer: LibraryIndexer,
        monotonicNowUs: @escaping @Sendable () -> Int64
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

        let manager = controlSessionManager
        Task { [weak self] in
            let manifestRelay = await manager.manifestRelay()
            await manifestRelay.setSink(ManifestSinkAdapter { message in
                Task { @MainActor in self?.handleManifestMessage(message) }
            })
            let transferRelay = await manager.transferRelay()
            await transferRelay.setSink(TransferSinkAdapter { message in
                Task { @MainActor in self?.handleTransferMessage(message) }
            })
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
    /// forwarded rather than self-subscribed.
    public func handleConnected() {
        onSessionBoundary()
        let transport = bulkTransport
        let manager = controlSessionManager
        Task {
            let generation = await manager.currentAuthGeneration
            await transport.onNewGeneration(generation)
        }
        requestCatalogue()
    }

    /// Called by `SessionCoordinator` on `.linkLost`. ADR-023 §1: the bulk listener never outlives
    /// the session that opened it.
    public func handleLinkLost() {
        onSessionBoundary()
        let transport = bulkTransport
        Task { await transport.close() }
    }

    private func onSessionBoundary() {
        // brief §6/§22: a peer's catalogue is session/peer-scoped and must never leak across a
        // reconnect or a different peer — replaced wholesale, never merged with what came before.
        remoteEntries = []
        syncState = ManifestSyncState(liveRevision: 0)
        downloadQueue = []
        activeDownload = nil
        pendingOfferTransferId = nil
        pendingOfferContinuation?.resume(returning: nil)
        pendingOfferContinuation = nil
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
        setState(hash, DownloadState(status: .queued))
        pumpQueue()
    }

    public func cancelDownload(_ contentHash: ContentHash) {
        downloadQueue.removeAll { $0 == contentHash }
        if activeDownload == contentHash {
            pendingOfferContinuation?.resume(returning: nil)
            pendingOfferContinuation = nil
            setState(contentHash, DownloadState(status: .cancelled))
            activeDownload = nil
            pumpQueue()
        }
    }

    // MARK: - manifest: both roles, since both peers are symmetric

    private func handleManifestMessage(_ message: ManifestMessage) {
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
            let budget = min(ManifestPaging.manifestPageSoftLimitBytes, maxPageBytes)
            let pages = ManifestPaging.paginate(entries, budgetBytes: budget)
            let manifestId = ManifestId(Ulid.generate())
            let revision: Int64 = 1
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
        downloadQueue.removeFirst()
        activeDownload = next
        Task { await runDownload(next) }
    }

    private func runDownload(_ hash: ContentHash) async {
        let transferId = TransferId(Ulid.generate())
        setState(hash, DownloadState(status: .negotiating))
        pendingOfferTransferId = transferId
        _ = await controlSessionManager.transferRelay().send(.request(contentHash: hash, transferId: transferId))

        guard let offer = await withOfferTimeout() else {
            finishDownload(hash, DownloadState(status: .failed, error: .notFound))
            return
        }
        guard let peerSpki = await controlSessionManager.currentPeerSpki, let peerHost = await controlSessionManager.currentPeerHost else {
            finishDownload(hash, DownloadState(status: .failed, error: .connectionLost))
            return
        }

        setState(hash, DownloadState(status: .transferring, totalBytes: offer.sizeBytes))
        guard let handle = try? cacheStorage.openPartForWrite(hash) else {
            finishDownload(hash, DownloadState(status: .failed, error: .ioError))
            return
        }
        let received = ReceivedCounter()
        let expectedChunkCount = (offer.sizeBytes + Int64(Self.chunkSizeBytes) - 1) / Int64(Self.chunkSizeBytes)
        let sink = DownloadChunkSink(handle: handle, storage: cacheStorage, received: received) { [weak self] bytes in
            Task { @MainActor in self?.setState(hash, DownloadState(status: .transferring, bytesReceived: bytes, totalBytes: offer.sizeBytes)) }
        }
        let outcome: BulkFetchOutcome
        if let port = UInt16(exactly: offer.bulkPort) {
            outcome = await bulkTransport.fetch(
                host: peerHost, port: port, token: offer.bulkToken,
                expectedPeerSpki: peerSpki, expectedChunkCount: expectedChunkCount, sink: sink
            )
        } else {
            outcome = .protocolError
        }
        try? handle.close()

        guard outcome == .ok else {
            cacheStorage.deletePart(hash)
            finishDownload(hash, DownloadState(status: .failed, error: outcome.asTransferError))
            return
        }
        setState(hash, DownloadState(status: .verifying, totalBytes: offer.sizeBytes))
        switch cacheStorage.promote(hash, expectedSizeBytes: offer.sizeBytes) {
        case .promoted:
            try? cacheRepository.commit(hash, sizeBytes: offer.sizeBytes, nowMonoUs: monotonicNowUs(), locked: [hash])
            _ = await controlSessionManager.transferRelay().send(.result(transferId: transferId, ok: true, sha256: hash))
            finishDownload(hash, DownloadState(status: .complete, totalBytes: offer.sizeBytes))
        case let failure:
            _ = await controlSessionManager.transferRelay().send(.result(transferId: transferId, ok: false, sha256: nil))
            finishDownload(hash, DownloadState(status: .failed, error: failure.asTransferError))
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

    private func finishDownload(_ hash: ContentHash, _ state: DownloadState) {
        setState(hash, state)
        if activeDownload == hash { activeDownload = nil }
        pumpQueue()
    }

    private func setState(_ hash: ContentHash, _ state: DownloadState) {
        downloadStates[hash.value] = state
    }

    // MARK: - transfer: provider side

    private func handleTransferMessage(_ message: TransferMessage) {
        switch message {
        case .request(let contentHash, let transferId):
            Task { await serveTransferRequest(contentHash: contentHash, transferId: transferId) }
        case .offer(let transferId, let sizeBytes, _, _, let bulkPort, let bulkToken):
            if pendingOfferTransferId == transferId {
                pendingOfferContinuation?.resume(returning: PendingOffer(
                    transferId: transferId, sizeBytes: sizeBytes, bulkPort: bulkPort, bulkToken: bulkToken
                ))
                pendingOfferContinuation = nil
            }
        case .progress, .result, .cancel:
            // brief §28: peer-reported progress is never trusted or displayed; the requester
            // already knows its own outcome from its own verification; a cancel is reacted to by
            // the bulk connection dropping, not this message.
            break
        }
    }

    private func serveTransferRequest(contentHash: ContentHash, transferId: TransferId) async {
        guard let peerSpki = await controlSessionManager.currentPeerSpki else { return }
        guard let resolution = try? contentResolver.resolve(contentHash: contentHash, nowMonoUs: monotonicNowUs()) else { return }
        guard case .found(let fileURL, let sizeBytes) = resolution else {
            // No wire message exists for "cannot serve this request" (PROTOCOL §8.2 has no
            // rejection shape for TRANSFER_REQUEST itself) — the requester's own negotiation
            // timeout is what resolves this, exactly as an unreachable peer would.
            return
        }
        guard let port = try? await bulkTransport.ensureListening(), let bulkPort = Int(exactly: port) else { return }
        let generation = await controlSessionManager.currentAuthGeneration
        let token = await bulkTransport.issueToken(transferId: transferId, generation: generation)
        let chunkCount = Int((sizeBytes + Int64(Self.chunkSizeBytes) - 1) / Int64(Self.chunkSizeBytes))
        _ = await controlSessionManager.transferRelay().send(.offer(
            transferId: transferId, sizeBytes: sizeBytes, chunkSize: Self.chunkSizeBytes,
            chunkCount: chunkCount, bulkPort: bulkPort, bulkToken: token
        ))
        guard let handle = try? FileHandle(forReadingFrom: fileURL) else { return }
        let source = FileChunkSource(handle: handle, chunkSize: Self.chunkSizeBytes)
        _ = await bulkTransport.serve(
            transferId: transferId, expectedPeerSpki: peerSpki, currentGeneration: { generation }, source: source
        )
        try? handle.close()
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
