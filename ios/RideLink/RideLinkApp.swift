import RideLinkCore
import RideLinkPlatform
import SwiftUI

@main
struct RideLinkApp: App {
    /// Built once at launch. `SessionCoordinator.init()` creates or loads the device identity
    /// (ADR-017) and assembles the TLS 1.3 control channel; if that fails there is deliberately no
    /// unencrypted fallback to offer, so the app says what failed and stops.
    @State private var session: Result<SessionCoordinator, Error>

    /// Phase 3's local music stack — built independently of `session` (this phase's brief §30: a
    /// player failure must not affect the control session, and the reverse). A failure here (the
    /// database could not be opened) is shown rather than silently dropping local music, the same
    /// "say what failed and stop" honesty `SecureTransportUnavailableView` already gives `session`.
    @State private var music: Result<MusicCoordinator, Error>

    /// ARCHITECTURE §6.2's lock-screen/Control-Center integration — built once, alongside `music`,
    /// wrapping the exact same `MusicCoordinator` instance rather than a second one (CLAUDE.md rule
    /// 8). `nil` when `music` failed to construct: there is then no coordinator to route remote
    /// commands to, and `MainScreen`'s own `.failure` branch already tells the user local music is
    /// unavailable. Nothing reads this back — it is held only so it stays alive for the app's
    /// lifetime instead of deinitializing (and tearing down its `MPRemoteCommandCenter` targets)
    /// the moment `init` returns.
    @State private var nowPlayingController: NowPlayingController?

    /// Phase 5's synchronisation plane, as SwiftUI sees it (ADR-004, ADR-024). `nil` when either
    /// stack failed to construct — the coordinator drives the *one* `MusicCoordinator` and needs the
    /// *one* `SessionCoordinator`, and neither failure is a reason to disable the other.
    @State private var syncPlayback: SyncPlaybackPresenter?

    init() {
        let sessionResult = Result { try SessionCoordinator() }
        _session = State(initialValue: sessionResult)
        let musicResult = Result { try MusicCoordinator() }
        _music = State(initialValue: musicResult)
        _nowPlayingController = State(initialValue: (try? musicResult.get()).map { NowPlayingController(musicCoordinator: $0) })

        // Phase 4 (ADR-023): only once both stacks exist, since the shared library needs Phase
        // 3's own `LibraryRepository`/database queue/indexer (CLAUDE.md rule 8 — one repository,
        // not a second one). Neither failure disables the other: a broken shared library is not a
        // reason to refuse local music or the control session, and the reverse.
        if let coordinator = try? sessionResult.get(), let musicCoordinator = try? musicResult.get() {
            coordinator.attachSharedLibrary(
                libraryRepository: musicCoordinator.libraryRepositoryForSharedLibrary,
                libraryDatabaseQueue: musicCoordinator.libraryDatabaseQueueForSharedLibrary,
                libraryIndexer: musicCoordinator.libraryIndexerForSharedLibrary,
                // Finding I: never evict a cache-only file the player currently has open.
                activeCacheHash: { [weak musicCoordinator] in musicCoordinator?.activeExternalCacheHash }
            )
        }

        // Phase 5 (ADR-004, ADR-024): after Phase 4, since the availability gate reads the shared
        // library's catalogue and verified cache. It owns no player — `MusicCoordinatorPlayerPort`
        // is the one `MusicCoordinator` above, and `syncGate` routes the in-app and lock-screen
        // controls into the leader-ordered path rather than adding a second one (brief §39).
        if let coordinator = try? sessionResult.get(),
           let musicCoordinator = try? musicResult.get(),
           let sharedLibrary = coordinator.sharedLibrary,
           let sync = coordinator.attachSyncPlayback(
               player: MusicCoordinatorPlayerPort(music: musicCoordinator),
               content: SharedLibraryContentPort(music: musicCoordinator, sharedLibrary: sharedLibrary),
               nextQueueItemId: { Ulid.generate() }
           ) {
            let presenter = SyncPlaybackPresenter(coordinator: sync)
            _syncPlayback = State(initialValue: presenter)
            musicCoordinator.syncGate = SyncPlaybackGateAdapter(
                sync: sync,
                isActive: { [weak presenter] in presenter?.isSynchronizedModeActive ?? false },
                role: { [weak presenter] in presenter?.role }
            )
        }
    }

    var body: some Scene {
        WindowGroup {
            switch session {
            case .success(let coordinator):
                MainScreen(
                    coordinator: coordinator,
                    music: music,
                    syncPlayback: syncPlayback,
                    deviceDescription: UIDevice.current.name
                )
            case .failure(let error):
                SecureTransportUnavailableView(reason: String(describing: error))
            }
        }
    }
}
