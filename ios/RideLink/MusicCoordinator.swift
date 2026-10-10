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
    public private(set) var queueState = LocalQueueState() {
        didSet {
            if oldValue.currentItem?.localEntryId != queueState.currentItem?.localEntryId { resolveNowPlayingEntry() }
        }
    }
    /// The library row for the queue's current item, looked up by `LocalEntryId` when the current item
    /// changes (Phase 9A.5). It used to be found by scanning `libraryEntries`, which is filtered by the
    /// library search, so typing in the search box made Now Playing — and the lock screen's
    /// `MPNowPlayingInfoCenter` entry — lose the title of what was playing.
    public private(set) var nowPlayingEntry: LibraryEntry?
    /// The whole library's size, whatever the search says.
    public private(set) var libraryCount = 0
    public private(set) var playerState = PlayerState()
    public private(set) var baseVolumePermille = CoexistenceState.fullGainPermille
    public var coexistenceEvents: (any CoexistenceEventSink)?

    /// The library entry the queue's current item resolves to, if any — the same derived lookup
    /// `MusicSection`'s Android counterpart does at the UI layer, done here once instead of in every
    /// view that needs it.
    ///
    /// Matched by `localEntryId`, not `quickId` (ADR-005 Amendment A1) — `quickId` is not guaranteed
    /// unique across entries, so matching on it could show the wrong track's metadata/artwork here.
    public var currentEntry: LibraryEntry? { nowPlayingEntry }

    /// A queue entry's library row, by id — never by scanning the search-filtered list.
    public func entry(for localEntryId: LocalEntryId) -> LibraryEntry? {
        (try? repository.findByLocalEntryId(localEntryId)) ?? nil
    }

    /// A usable local row holding `contentHash`, if any — the shared library's "play it here" lookup.
    public func localEntry(contentHash: ContentHash) -> LibraryEntry? {
        (try? repository.findByContentHash(contentHash)) ?? nil
    }

    private func resolveNowPlayingEntry() {
        nowPlayingEntry = queueState.currentItem.flatMap { entry(for: $0.localEntryId) }
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
    /// Non-nil once a Phase 5 synchronised session exists. Set once by the composition root
    /// (`RideLinkApp`), never by a view. While it reports ownership of an action, this coordinator
    /// does not touch the player — the leader-ordered command that comes back over the control plane
    /// does, through the `sync*` methods below.
    ///
    /// The cycle between the two coordinators is deliberate and one-directional per call:
    /// `MusicCoordinator` asks the gate, the gate never calls back into these gated methods (it uses
    /// `syncSelect`/`syncLoad`/`syncStart`/… which bypass it), so there is no re-entrancy.
    public var syncGate: (any SyncPlaybackGate)?

    private let player: any Player
    /// ADR-024 Amendment A15 round 2: where every local queue edit's and local transport press's
    /// player effects run, each carrying the admission it was minted with and re-proving it before
    /// audio-session activation, `Load`, `Play`, `Stop`, `Pause` and `Seek`. The authority is the
    /// synchronisation owner's lifetime (`syncGate`), never a fact held here.
    @ObservationIgnored private lazy var localEffects = LocalPlaybackEffects(
        player: player,
        prepare: { [weak self] in await self?.activateAudioSessionIfNeeded() },
        resolve: { [weak self] localEntryId in self?.resolveLocation(localEntryId) },
        stillValid: { [weak self] admission in
            guard let self else { return false }
            return LocalQueueEdits.stillValid(admission, gate: self.syncGate)
        }
    )
    private let musicAudioSession: MusicAudioSession
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

    public init(
        audioSessionCoordinator: IosAudioSessionCoordinator = IosAudioSessionCoordinator(),
        monotonicNowUs: @escaping @Sendable () -> Int64 = { Int64(DispatchTime.now().uptimeNanoseconds / 1000) }
    ) throws {
        self.monotonicNowUs = monotonicNowUs
        self.musicAudioSession = MusicAudioSession(coordinator: audioSessionCoordinator)
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
                await MainActor.run {
                    self?.libraryEntries = entries
                    self?.libraryCount = (try? self?.repository.count()) ?? entries.count
                }
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

    // Phase 9A.5, PR #18 review: every user edit of the local queue — add, play-now, cache play,
    // remove, move, clear, select — is admitted through `LocalQueueEdits`, which refuses it while
    // synchronised transport owns playback (`SyncPlaybackGate.localQueueLocked()`, ADR-024 Amendment
    // A14). Each returns whether it was admitted; a refusal changes nothing — no queue mutation, no
    // load, no play, no stop. Additions are refused too: the synchronised path replaces the local
    // queue on every synchronised selection (`syncSelect`) and clears it on stop
    // (`syncClearSelection`), so an entry "staged" during a synchronised ride would silently vanish.
    // Mirrors Android's `MusicCoordinator`.

    @discardableResult
    public func addToQueue(_ entry: LibraryEntry) -> Bool { edit([.add(newItem(entry))]) }

    /// Whether local queue edits are refused right now — for rendering only. Every edit re-asks at
    /// the moment it is admitted; this answer authorises nothing.
    public var isLocalQueueLocked: Bool { syncGate?.localQueueLocked() == true }

    /// Adds `entry` to the queue and starts playing it immediately — the library screen's "tap a
    /// track" affordance, as one atomic queue operation rather than an add followed by a
    /// UI-observed "select the item I just added" that would race a second rapid tap.
    @discardableResult
    public func playNow(_ entry: LibraryEntry) -> Bool {
        let item = newItem(entry)
        guard let edit = LocalQueueEdits.reduce(queueState, [.add(item), .select(id: item.id)], gate: syncGate) else { return false }
        coexistenceEvents?.onPlaybackIntent(playing: true)
        apply(edit)
        return true
    }

    private func newItem(_ entry: LibraryEntry) -> LocalQueueItem {
        LocalQueueItem(id: UUID().uuidString, localEntryId: entry.localEntryId, insertedAtMonoUs: monotonicNowUs())
    }

    /// Closure-audit Finding G: plays a verified Phase-4 cache-only file — one that exists only as
    /// `TransferCacheRepository`'s committed, whole-file-SHA-256-verified bytes, never imported into
    /// the Phase 3 library — through the *existing* one player/one queue, exactly like `playNow`
    /// does for an imported `LibraryEntry`. brief §24: local-only playback on *this* device; no
    /// peer command, no synchronized playback, no second player.
    @discardableResult
    public func playExternalVerifiedCachedTrack(_ contentHash: ContentHash, fileURL: URL) -> Bool {
        let entryId = LocalEntryId(UUID().uuidString.lowercased())
        let item = LocalQueueItem(id: UUID().uuidString, localEntryId: entryId, insertedAtMonoUs: monotonicNowUs())
        guard let edit = LocalQueueEdits.reduce(queueState, [.add(item), .select(id: item.id)], gate: syncGate) else { return false }
        coexistenceEvents?.onPlaybackIntent(playing: true)
        // Registered before the effects run: `resolveLocation` reads it when the effect executes.
        externalCacheSources[entryId] = ExternalCacheSource(contentHash: contentHash, location: LocalTrackLocation(uri: fileURL.absoluteString))
        apply(edit)
        return true
    }

    @discardableResult
    public func removeFromQueue(id: String) -> Bool { edit([.remove(id: id)]) }
    @discardableResult
    public func moveInQueue(id: String, toIndex: Int) -> Bool { edit([.move(id: id, toIndex: toIndex)]) }
    @discardableResult
    public func clearQueue() -> Bool { edit([.clear]) }

    public func next() {
        if syncGate?.interceptNext() == true { return }
        dispatch(.next)
    }

    public func previous() {
        if syncGate?.interceptPrevious() == true { return }
        dispatch(.previous)
    }

    @discardableResult
    public func selectQueueItem(id: String) -> Bool { edit([.select(id: id)]) }

    /// Play, from the app or the lock screen. A synchronised session owns it first (ADR-024 A14, via
    /// `syncGate`); otherwise the local queue decides: with tracks queued and nothing selected it
    /// starts the first one, and otherwise resumes the player (Phase 9A.5 §11, `LocalQueueAction.play`).
    public func play() {
        coexistenceEvents?.onPlaybackIntent(playing: true)
        if syncGate?.interceptPlay() == true { return }
        dispatch(.play)
    }

    public func pause() {
        coexistenceEvents?.onPlaybackIntent(playing: false)
        if syncGate?.interceptPause() == true { return }
        guard let admission = LocalQueueEdits.admit(gate: syncGate) else { return }
        localEffects.pause(admission: admission)
    }

    public func seek(positionMs: Int64) {
        if syncGate?.interceptSeek(positionMs) == true { return }
        guard let admission = LocalQueueEdits.admit(gate: syncGate) else { return }
        localEffects.seek(positionMs: positionMs, admission: admission)
    }

    // MARK: - Phase 5's own entry points
    //
    // These bypass `syncGate` by construction: they are what the gate's owner calls once the ADR-010
    // leader's authoritative command is due, so routing them back through the gate would be an
    // immediate loop. They drive the same one player and the same one queue as everything above —
    // there is no second player, no second queue and no second Now Playing integration (brief §21).

    // **ADR-024 Amendment A4: one externally visible effect per entry point, and no `await`
    // before the effect within one.** These were two functions — a `syncPrepare` that materialised,
    // loaded and then seeked, and a `syncStop` that stopped the player and then cleared the local
    // queue. Each composed several effects across a real suspension, *below* the port and therefore
    // out of reach of any ownership proof `SyncPlaybackCoordinator` could take: this type is
    // `@MainActor`, so `await player.execute(…)` releases the main actor and a whole replacement
    // session can run its own pre-roll before the first one resumes to seek. Sequencing moved to
    // `SyncPlaybackCoordinator.runOwnedSteps`, which re-proves ownership before every step.

    /// Brief §26's materialisation point: the track that is actually current becomes the local
    /// queue's one selected entry, so Now Playing metadata and the Phase 3 UI describe what is
    /// loaded. The shared queue itself is displayed from `SyncPlaybackCoordinator.queueState`; it is
    /// deliberately not copied wholesale into `LocalQueue`, because two queues that could disagree
    /// about an index is exactly the bug that would produce.
    public func syncSelect(contentHash: ContentHash, localEntryId: LocalEntryId, location: LocalTrackLocation) {
        externalCacheSources[localEntryId] = ExternalCacheSource(contentHash: contentHash, location: location)
        let item = LocalQueueItem(id: UUID().uuidString, localEntryId: localEntryId, insertedAtMonoUs: monotonicNowUs())
        queueState = LocalQueueState(items: [item], currentId: item.id)
    }

    /// ARCHITECTURE §7.2's pre-roll, first half: hand the decoder the file. Never starts.
    public func syncLoad(localEntryId: LocalEntryId, location: LocalTrackLocation) async {
        await activateAudioSessionIfNeeded()
        await player.execute(.load(localEntryId: localEntryId, location: location))
    }

    /// The tail of what used to be inside `syncStop`.
    public func syncClearSelection() { queueState = LocalQueueState() }

    public func syncStart() async {
        coexistenceEvents?.onPlaybackIntent(playing: true)
        await player.execute(.play)
    }

    public func syncPause() async {
        coexistenceEvents?.onPlaybackIntent(playing: false)
        await player.execute(.pause)
    }

    public func syncSeek(positionMs: Int64) async { await player.execute(.seek(positionMs: positionMs)) }

    /// ADR-004's rate-nudge tier. Always exactly 1.0 when correction ends (brief §38).
    public func syncSetRate(_ rate: Double) async { await player.execute(.setRate(rate: rate)) }

    public func syncStop() async {
        coexistenceEvents?.onPlaybackIntent(playing: false)
        await player.execute(.stop)
    }

    public func setBaseVolumePermille(_ volumePermille: Int) {
        precondition((CoexistenceState.minGainPermille...CoexistenceState.fullGainPermille).contains(volumePermille))
        baseVolumePermille = volumePermille
        coexistenceEvents?.onBaseVolumeChanged(volumePermille)
    }

    /// Activated once, lazily, on the first real play — matching `MainActivity.attemptMusicPlay`'s
    /// "configure before use" discipline on Android, without an iOS equivalent of its
    /// foreground-visible gate (there is no foreground-service start to protect here).
    private func activateAudioSessionIfNeeded() async {
        guard !audioSessionActivated else { return }
        do {
            try await musicAudioSession.activate()
            audioSessionActivated = true
        } catch {
            // Best-effort, matching MainActivity.attemptMusicPlay's non-fatal treatment of a failed
            // foreground-service start: playback still attempts to proceed, since a session
            // activation failure here should not be a harder stop than the Android equivalent.
        }
    }

    /// A user's edit of the local queue, admitted or refused as one step — see `LocalQueueEdits`.
    private func edit(_ actions: [LocalQueueAction]) -> Bool {
        guard let edit = LocalQueueEdits.reduce(queueState, actions, gate: syncGate) else { return false }
        apply(edit)
        return true
    }

    /// Transport and track-end actions: already decided by `syncGate`'s intercepts before reaching
    /// here, and admitted now — in the same synchronous step as the queue mutation — for the effects
    /// that follow. A `nil` admission means synchronised ownership arrived between the intercept's
    /// read and this one; the press then does nothing locally (ADR-024 A15 round 2).
    private func dispatch(_ action: LocalQueueAction) {
        guard let admission = LocalQueueEdits.admit(gate: syncGate) else { return }
        apply(AdmittedLocalQueueEdit(outcome: LocalQueue.reduce(queueState, action), admission: admission))
    }

    /// ADR-024 Amendment A15 round 2. The queue mutation is applied **now**, under the admission;
    /// the player effects run later and carry that same admission, re-proved before each step
    /// (`LocalPlaybackEffects`). If an effect is then dropped because synchronised ownership took
    /// over, the admitted local queue state is deliberately **not** rolled back: it was a legitimate
    /// local edit when it was made, and the synchronised path that took over replaces the local queue
    /// wholesale on its first selection (`syncSelect`) and clears it on stop (`syncClearSelection`).
    /// A rollback would itself be a local write racing that authority.
    private func apply(_ edit: AdmittedLocalQueueEdit) {
        queueState = edit.state
        localEffects.run(edit.effects, admission: edit.admission)
    }

    /// Where an entry's file is: a verified cache file, or an imported library entry; `nil` if gone.
    private func resolveLocation(_ localEntryId: LocalEntryId) -> LocalTrackLocation? {
        if let external = externalCacheSources[localEntryId] { return external.location }
        guard let entry = try? repository.findByLocalEntryId(localEntryId) else { return nil }
        return LocalTrackLocation(uri: indexer.resolvedUrl(for: entry).absoluteString)
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
        coexistenceEvents?.onMusicChanged(state)
        if TrackEndEdge.advancedNow(previous: previous, current: state) {
            // In a synchronised session only the ADR-010 leader decides what plays next, and it does
            // so with an authoritative NEXT both phones schedule. Advancing the local queue here as
            // well would put this phone a track ahead of the other.
            if syncGate?.interceptTrackEnded() != true { dispatch(.next) }
        }
    }
}

extension MusicCoordinator: MusicCoexistencePort {
    public var coexistencePlayerState: PlayerState { playerState }
    public var coexistenceBaseVolumePermille: Int { baseVolumePermille }

    public func beginCoexistenceLifetime(_ generation: Int64) async {
        await player.beginCoexistenceLifetime(generation)
    }

    public func applyCoexistenceGain(generation: Int64, volumePermille: Int) async -> Bool {
        await player.setCoexistenceGain(
            Double(volumePermille) / Double(CoexistenceState.fullGainPermille),
            generation: generation
        )
    }

    public func pauseForVoice(generation: Int64, trackToken: String) async -> Bool {
        await player.pauseForVoice(generation: generation, trackToken: trackToken)
    }

    public func resumeAfterVoice(generation: Int64, trackToken: String) async -> Bool {
        await player.resumeAfterVoice(generation: generation, trackToken: trackToken)
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
