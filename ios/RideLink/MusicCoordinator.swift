import Foundation
import GRDB
import Observation
import RideLinkCore
import RideLinkPlatform

/// The single owner of local-music state (CLAUDE.md rule 8, the music-plane mirror of
/// `SessionCoordinator` — same pattern `com.ridelink.app.music.MusicCoordinator` follows on
/// Android). No SwiftUI view holds library, queue or playback state of its own.
///
/// **Independent of `SessionCoordinator` by construction** — no reference to it, and nothing above
/// holds a reference to this. That is this phase's brief §30's graceful-degradation rule made
/// structural: a player failure cannot reach the control session because there is no path for it to,
/// and local music works identically whether or not a peer session exists at all. iOS has no
/// foreground-service concept to wire into either (`SessionCoordinator`'s own comment already notes
/// this): a background-audio-capable app simply keeps its `AVAudioSession` alive.
@Observable
@MainActor
public final class MusicCoordinator {
    public private(set) var query = LibraryQuery()
    public private(set) var libraryEntries: [LibraryEntry] = []
    public private(set) var queueState = LocalQueueState()
    public private(set) var playerState = PlayerState()

    /// The library entry the queue's current item resolves to, if any — the same derived lookup
    /// `MusicSection`'s Android counterpart does at the UI layer, done here once instead of in every
    /// view that needs it.
    ///
    /// Matched by `localEntryId`, not `quickId` (ADR-005 Amendment A1) — `quickId` is not guaranteed
    /// unique across entries, so matching on it could show the wrong track's metadata/artwork here.
    public var currentEntry: LibraryEntry? {
        guard let item = queueState.currentItem else { return nil }
        return libraryEntries.first { $0.localEntryId == item.localEntryId }
    }

    private let repository: LibraryRepository
    private let indexer: LibraryIndexer
    private let dbQueue: DatabaseQueue

    /// Phase 4's `SharedLibraryCoordinator` needs the *same* `LibraryRepository` this coordinator
    /// already owns — a manifest generated from a second, independent repository instance over the
    /// same database would still be correct, but two repository objects for one Phase 3 database is
    /// exactly the kind of duplication CLAUDE.md rule 8 exists to prevent. Forwarding this one
    /// reference is the composition root's job (`RideLinkApp.init`), not a new dependency between
    /// `MusicCoordinator` and `SharedLibraryCoordinator` themselves — neither holds a reference to
    /// the other.
    public var libraryRepositoryForSharedLibrary: LibraryRepository { repository }

    /// The same reasoning as [libraryRepositoryForSharedLibrary]: Phase 4's verified-cache metadata
    /// lives as a second table in this **same** database (one `library.sqlite`, one migrator, two
    /// tables) rather than a second file, so `TransferCacheRepository` needs this same queue.
    public var libraryDatabaseQueueForSharedLibrary: DatabaseQueue { dbQueue }

    /// `LocalContentResolver` resolves a local library hit via the indexer's own app-container
    /// destination path (`LibraryIndexer.resolvedUrl(for:)`), never `LibraryEntry.location.uri` —
    /// see that type's doc comment.
    public var libraryIndexerForSharedLibrary: LibraryIndexer { indexer }
    private let player: any Player
    private let musicAudioSession = MusicAudioSession()
    private var audioSessionActivated = false
    private let monotonicNowUs: @Sendable () -> Int64
    private var libraryObservationTask: Task<Void, Never>?
    /// Guards `completeContentHashingInBackground` against launching a second concurrent pass while
    /// one is already running — not correctness-critical (each pass re-reads the repository and a
    /// row already hashed is simply skipped), but avoids redundant concurrent DB reads.
    private var hashingTask: Task<Void, Never>?

    /// Closure-audit Finding G: a verified Phase-4 cache-only track (never imported into the Phase 3
    /// library — see `playExternalVerifiedCachedTrack`) played through the *existing* queue/player.
    /// Never written to `LibraryRepository` — provenance stays distinct (ADR-023 §6 / brief §19:
    /// LOCAL IMPORTED and VERIFIED PEER CACHE are different storage origins), and this dictionary is
    /// the only place that association exists. The `LocalEntryId` key is a fresh, opaque token
    /// minted at play time purely so the *existing* `LocalQueue`/`Player` can carry an identity for
    /// it — never looked up against `repository`, and never persisted past this process's lifetime.
    private struct ExternalCacheSource {
        let contentHash: ContentHash
        let location: LocalTrackLocation
    }

    private var externalCacheSources: [LocalEntryId: ExternalCacheSource] = [:]

    /// Closure-audit Finding I: the `ContentHash` currently loaded from `externalCacheSources`, if
    /// any — so a caller committing a *new* Phase-4 cache entry (`TransferCacheRepository.commit`)
    /// can include it in that call's `locked` set and never evict the file this coordinator's own
    /// player has open. `nil` whenever nothing playing right now is a cache-only track (including
    /// "nothing is playing" and "a Phase 3 imported track is playing").
    public var activeExternalCacheHash: ContentHash? {
        guard let item = queueState.currentItem else { return nil }
        return externalCacheSources[item.localEntryId]?.contentHash
    }

    public init(monotonicNowUs: @escaping @Sendable () -> Int64 = { Int64(DispatchTime.now().uptimeNanoseconds / 1000) }) throws {
        self.monotonicNowUs = monotonicNowUs
        let directories = try Self.makeDirectories()
        let dbQueue = try DatabaseQueue(path: directories.database.path)
        try LibraryDatabase.makeMigrator().migrate(dbQueue)
        self.dbQueue = dbQueue
        let repository = LibraryRepository(dbQueue: dbQueue)
        self.repository = repository
        self.indexer = LibraryIndexer(
            repository: repository,
            artworkCache: ArtworkCache(cachesDirectory: directories.caches),
            musicDirectory: directories.music,
            monotonicNowUs: monotonicNowUs
        )
        self.player = AVAudioEnginePlayer()

        let sink = MainActorStateSink { [weak self] state in self?.handlePlayerState(state) }
        Task { await self.player.setStateSink(sink.receive) }
        observeLibrary()
        // ADR-005's background pass, actually wired to run (this phase's closure-audit hardening
        // pass — previously this method existed but nothing ever called it). Kicked off once at
        // composition time so rows left unhashed by a previous session's interrupted pass resume,
        // and again after every import below so newly-added rows do not wait for the next app launch.
        completeContentHashingInBackground()
    }

    private static func makeDirectories() throws -> (database: URL, music: URL, caches: URL) {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        let root = support.appendingPathComponent("RideLink/music", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let musicDirectory = root.appendingPathComponent("files", isDirectory: true)
        try FileManager.default.createDirectory(at: musicDirectory, withIntermediateDirectories: true)
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        return (database: root.appendingPathComponent("library.sqlite"), music: musicDirectory, caches: caches)
    }

    // MARK: - Library

    public func setSearchText(_ text: String) {
        query = LibraryQuery(searchText: text, sort: query.sort)
        observeLibrary()
    }

    public func setSort(_ sort: LibrarySort) {
        query = LibraryQuery(searchText: query.searchText, sort: sort)
        observeLibrary()
    }

    private func observeLibrary() {
        libraryObservationTask?.cancel()
        let stream = repository.observe(query: query)
        libraryObservationTask = Task { [weak self] in
            for await entries in stream {
                guard !Task.isCancelled else { return }
                await MainActor.run { self?.libraryEntries = entries }
            }
        }
    }

    public func importFiles(_ urls: [URL]) {
        Task {
            try? await indexer.importFiles(urls)
            completeContentHashingInBackground()
        }
    }

    public func importFolder(_ url: URL) {
        Task {
            try? await indexer.importFolder(url)
            completeContentHashingInBackground()
        }
    }

    /// Fills in the authoritative hash for every row still missing one — the ADR-005 background
    /// pass. Safe to call repeatedly and from any context: `LibraryIndexer.completeContentHashing`
    /// re-queries the repository for rows missing a hash on every call, so it never depends on a
    /// possibly-stale `libraryEntries` snapshot and always resumes exactly the rows a previous,
    /// possibly-cancelled pass had not yet reached (this phase's closure-audit hardening pass — the
    /// method existed before this pass but had no production caller anywhere, so `content_hash`
    /// never actually got filled in). `hashingTask` only prevents launching a redundant *concurrent*
    /// pass; it is never required for correctness.
    public func completeContentHashingInBackground() {
        guard hashingTask == nil else { return }
        hashingTask = Task {
            try? await indexer.completeContentHashing()
            hashingTask = nil
        }
    }

    // MARK: - Queue and playback

    public func addToQueue(_ entry: LibraryEntry) {
        dispatch(.add(newItem(entry)))
    }

    /// Adds `entry` to the queue and starts playing it immediately — the library screen's "tap a
    /// track" affordance, as one atomic queue operation rather than an add followed by a
    /// UI-observed "select the item I just added" that would race a second rapid tap.
    public func playNow(_ entry: LibraryEntry) {
        let item = newItem(entry)
        dispatch(.add(item))
        dispatch(.select(id: item.id))
    }

    private func newItem(_ entry: LibraryEntry) -> LocalQueueItem {
        LocalQueueItem(id: UUID().uuidString, localEntryId: entry.localEntryId, insertedAtMonoUs: monotonicNowUs())
    }

    /// Closure-audit Finding G: plays a verified Phase-4 cache-only file — one that exists only as
    /// `TransferCacheRepository`'s committed, whole-file-SHA-256-verified bytes, never imported into
    /// the Phase 3 library — through the *existing* one player/one queue, exactly like `playNow`
    /// does for an imported `LibraryEntry`. brief §24: local-only playback on *this* device; no
    /// peer command, no synchronized playback, no second player.
    public func playExternalVerifiedCachedTrack(_ contentHash: ContentHash, fileURL: URL) {
        let entryId = LocalEntryId(UUID().uuidString.lowercased())
        externalCacheSources[entryId] = ExternalCacheSource(contentHash: contentHash, location: LocalTrackLocation(uri: fileURL.absoluteString))
        let item = LocalQueueItem(id: UUID().uuidString, localEntryId: entryId, insertedAtMonoUs: monotonicNowUs())
        dispatch(.add(item))
        dispatch(.select(id: item.id))
    }

    public func removeFromQueue(id: String) { dispatch(.remove(id: id)) }
    public func moveInQueue(id: String, toIndex: Int) { dispatch(.move(id: id, toIndex: toIndex)) }
    public func clearQueue() { dispatch(.clear) }
    public func next() { dispatch(.next) }
    public func previous() { dispatch(.previous) }
    public func selectQueueItem(id: String) { dispatch(.select(id: id)) }

    public func play() {
        activateAudioSessionIfNeeded()
        Task { await player.execute(.play) }
    }

    public func pause() {
        Task { await player.execute(.pause) }
    }

    public func seek(positionMs: Int64) {
        Task { await player.execute(.seek(positionMs: positionMs)) }
    }

    /// Activated once, lazily, on the first real play — matching `MainActivity.attemptMusicPlay`'s
    /// "configure before use" discipline on Android, without an iOS equivalent of its
    /// foreground-visible gate (there is no foreground-service start to protect here).
    private func activateAudioSessionIfNeeded() {
        guard !audioSessionActivated else { return }
        do {
            try musicAudioSession.activate()
            audioSessionActivated = true
        } catch {
            // Best-effort, matching MainActivity.attemptMusicPlay's non-fatal treatment of a failed
            // foreground-service start: playback still attempts to proceed, since a session
            // activation failure here should not be a harder stop than the Android equivalent.
        }
    }

    private func dispatch(_ action: LocalQueueAction) {
        let outcome = LocalQueue.reduce(queueState, action)
        queueState = outcome.state
        for effect in outcome.effects {
            switch effect {
            case .loadAndPlay(let localEntryId):
                activateAudioSessionIfNeeded()
                Task { await self.loadAndPlay(localEntryId) }
            case .stopPlayback:
                Task { await self.player.execute(.stop) }
            }
        }
    }

    private func loadAndPlay(_ localEntryId: LocalEntryId) async {
        if let external = externalCacheSources[localEntryId] {
            await player.execute(.load(localEntryId: localEntryId, location: external.location))
            await player.execute(.play)
            return
        }
        guard let entry = try? repository.findByLocalEntryId(localEntryId) else { return }
        let resolvedUri = indexer.resolvedUrl(for: entry).absoluteString
        await player.execute(.load(localEntryId: localEntryId, location: LocalTrackLocation(uri: resolvedUri)))
        await player.execute(.play)
    }

    /// A track ending or its file going missing both mean "move on" — the queue owner's job
    /// (`LocalQueue`'s own doc comment: this is deliberately not a queue-internal concept).
    /// Edge-triggered via `TrackEndEdge`, not level-triggered on every emission — the real
    /// restart-loop bug `TrackEndEdge`'s own doc comment describes, found on Android, applies here
    /// too: `AVAudioPlayerNode`'s completion handler and the position-tick loop can each observe
    /// "reached the end" for the same finish.
    private func handlePlayerState(_ state: PlayerState) {
        let previous = playerState
        playerState = state
        if TrackEndEdge.advancedNow(previous: previous, current: state) {
            dispatch(.next)
        }
    }
}

/// `Player.setStateSink` requires a `@Sendable` closure, and `MusicCoordinator` is `@MainActor` —
/// this hops onto the main actor rather than capturing `self` directly in a `@Sendable` context,
/// the same shape `SessionCoordinator`'s own `Task { @MainActor in self?.foo() }` callbacks use
/// throughout, wrapped once here so every player-state callback does not repeat it.
private final class MainActorStateSink: Sendable {
    private let handler: @MainActor (PlayerState) -> Void

    init(_ handler: @escaping @MainActor (PlayerState) -> Void) {
        self.handler = handler
    }

    nonisolated func receive(_ state: PlayerState) {
        let handler = handler
        Task { @MainActor in handler(state) }
    }
}
