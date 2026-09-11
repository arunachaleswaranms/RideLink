import Foundation
import RideLinkCore
import RideLinkPlatform

// The production implementations of Phase 5's ports. Every one is a thin translation onto something
// that already exists — there is no new player, no new queue, no new cache and no new transfer
// machinery in this file, which is the point (brief §20/§21).
//
// They live in the app target, unlike `SyncPlaybackCoordinator` itself, because they are the only
// part that must touch the app's `@MainActor` coordinators.

/// `SyncPlayerPort` over the **one** `MusicCoordinator`: its `LocalQueue`, its one
/// `AVAudioEnginePlayer`, its one `MPNowPlayingInfoCenter`/`MPRemoteCommandCenter` integration.
///
/// ADR-024 Amendment A4: every method below is a single hop onto one `MusicCoordinator` entry point
/// that performs exactly one externally visible effect. Nothing here composes two, and nothing it
/// calls does either — that is what makes `SyncPlaybackCoordinator.runOwnedSteps`' per-step fence
/// the complete story rather than a fence around an opaque compound.
struct MusicCoordinatorPlayerPort: SyncPlayerPort {
    let music: MusicCoordinator

    func playerState() async -> PlayerState { await MainActor.run { music.playerState } }

    func select(content: SyncPlayableContent) async {
        await MainActor.run {
            music.syncSelect(
                contentHash: content.contentHash, localEntryId: content.localEntryId, location: content.location
            )
        }
    }

    func load(content: SyncPlayableContent) async {
        await music.syncLoad(localEntryId: content.localEntryId, location: content.location)
    }

    func clearSelection() async { await MainActor.run { music.syncClearSelection() } }

    func start() async { await music.syncStart() }

    func pause() async { await music.syncPause() }

    func seek(positionMs: Int64) async { await music.syncSeek(positionMs: positionMs) }

    func setRate(_ rate: Double) async { await music.syncSetRate(rate) }

    func stop() async { await music.syncStop() }
}

/// `SyncContentPort` over Phase 3's library and Phase 4's verified cache — the two, and only two,
/// provenances brief §19 admits.
///
/// The verified cache is asked **first** and through `SharedLibraryCoordinator.cachedFile`, which is
/// `TransferCacheRepository.open`: a row claiming verified with no file behind it is dropped rather
/// than trusted (Amendment A4). A download state of complete is never consulted — brief §19 is
/// explicit that download state is not availability.
@MainActor
final class SharedLibraryContentPort: SyncContentPort {
    private let music: MusicCoordinator
    private let sharedLibrary: SharedLibraryCoordinator
    /// One stable `LocalEntryId` per cached hash for this process: the player and the local queue key
    /// on it, and minting a fresh one per resolve would make the same file look like a different row
    /// on every drift tick.
    private var cacheEntryIds: [String: LocalEntryId] = [:]

    init(music: MusicCoordinator, sharedLibrary: SharedLibraryCoordinator) {
        self.music = music
        self.sharedLibrary = sharedLibrary
    }

    nonisolated func resolve(_ contentHash: ContentHash) async -> SyncPlayableContent? {
        await MainActor.run { resolveOnMain(contentHash) }
    }

    nonisolated func peerHasContent(_ contentHash: ContentHash) async -> Bool {
        await MainActor.run { sharedLibrary.peerHasContent(contentHash) }
    }

    nonisolated func requestTransfer(_ contentHash: ContentHash) async {
        await MainActor.run { requestTransferOnMain(contentHash) }
    }

    /// ADR-024 Amendment A1 Finding E: forwards Phase 4's own verified-availability notification.
    ///
    /// `SharedLibraryCoordinator` already owned the two facts that matter — the verified cache, which
    /// only changes after `TransferCacheRepository.commit` succeeds, and `peerVerifiedHashes`, which
    /// is written only on a `TRANSFER_RESULT { ok: true }` for a hash we ourselves served — so this
    /// adds a notification, not a third source of truth, and certainly not a poll.
    nonisolated func observeAvailability(_ onAvailabilityChanged: @escaping @Sendable () -> Void) async {
        await MainActor.run { sharedLibrary.onAvailabilityChanged = { onAvailabilityChanged() } }
    }

    private func resolveOnMain(_ contentHash: ContentHash) -> SyncPlayableContent? {
        if let file = sharedLibrary.cachedFile(contentHash) {
            let entryId = cacheEntryIds[contentHash.value] ?? LocalEntryId(UUID().uuidString.lowercased())
            cacheEntryIds[contentHash.value] = entryId
            return SyncPlayableContent(
                contentHash: contentHash,
                localEntryId: entryId,
                location: LocalTrackLocation(uri: file.absoluteString),
                title: nil,
                artist: nil
            )
        }
        guard let entry = try? music.libraryRepositoryForSharedLibrary.findByContentHash(contentHash) else { return nil }
        // The indexer's own resolved container path, never `entry.location.uri` — the same rule
        // `MusicCoordinator.loadAndPlay` and `LocalContentResolver` already follow.
        let resolved = music.libraryIndexerForSharedLibrary.resolvedUrl(for: entry).absoluteString
        return SyncPlayableContent(
            contentHash: contentHash,
            localEntryId: entry.localEntryId,
            location: LocalTrackLocation(uri: resolved),
            title: entry.track.title,
            artist: entry.track.artist
        )
    }

    private func requestTransferOnMain(_ contentHash: ContentHash) {
        // PROTOCOL §5 rule 4, through the **existing** Phase 4 queue. `requestDownload` already
        // refuses a hash already held and de-duplicates against its own in-flight queue.
        guard let entry = sharedLibrary.remoteEntries.first(where: { $0.contentHash == contentHash }) else { return }
        sharedLibrary.requestDownload(entry)
    }
}

/// ARCHITECTURE §7.3: the drift ladder is suspended while **either** peer's route is transitioning.
/// The local half comes from the voice diagnostics' route snapshot, the peer half from its last
/// `AUDIO_STATE`.
///
/// This is a Phase 5 correction guard reading Phase 2b state — it changes no Bluetooth behaviour and
/// decides no audio policy, which is Phase 6's job and is deliberately untouched here.
struct SessionRouteStatePort: SyncRouteStatePort {
    let session: SessionCoordinator

    func isRouteTransitioning() async -> Bool {
        await MainActor.run {
            session.voiceDiagnostics.route.routeState == .transitioning ||
                session.peerAudioState?.routeState == .transitioning
        }
    }
}

/// Bridges `MusicCoordinator`'s gate to the coordinator that owns synchronisation.
@MainActor
struct SyncPlaybackGateAdapter: SyncPlaybackGate {
    let sync: SyncPlaybackCoordinator
    /// Read synchronously so the gate can answer without suspending — `MusicCoordinator`'s callers
    /// (including `MPRemoteCommandCenter`'s handlers) are synchronous and cannot await an actor.
    let isActive: () -> Bool
    let role: () -> PlaybackRole?

    func interceptPlay() -> Bool {
        // A local `play` during a synchronised ride resumes *both* phones from the position the
        // authoritative timeline is at — never just this one.
        forward { await $0.resume() }
    }

    func interceptPause() -> Bool { forward { await $0.pause() } }

    func interceptSeek(_ positionMs: Int64) -> Bool { forward { await $0.seek(positionMs: positionMs) } }

    func interceptNext() -> Bool { forward { await $0.next() } }

    func interceptPrevious() -> Bool { forward { await $0.previous() } }

    func interceptTrackEnded() -> Bool {
        guard isActive() else { return false }
        // Only the ADR-010 leader may decide what plays next. A follower still *intercepts* — it
        // must not advance its own queue — and then does nothing, waiting for the leader's
        // authoritative NEXT. That is not a stall, it is the single serialisation point doing its
        // job. The role is read here, on the main actor, rather than inside the task below: it is a
        // non-`Sendable` closure and cannot cross into one.
        guard role() == .leader else { return true }
        return forward { await $0.next() }
    }

    /// Only `sync` — an actor, and so `Sendable` — crosses into the task. Nothing else here is,
    /// which is exactly what Swift 6 strict concurrency is for.
    private func forward(_ action: @escaping @Sendable (SyncPlaybackCoordinator) async -> Void) -> Bool {
        guard isActive() else { return false }
        let coordinator = sync
        Task { await action(coordinator) }
        return true
    }
}
