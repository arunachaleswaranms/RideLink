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

    init() {
        _session = State(initialValue: Result { try SessionCoordinator() })
        let musicResult = Result { try MusicCoordinator() }
        _music = State(initialValue: musicResult)
        _nowPlayingController = State(initialValue: (try? musicResult.get()).map { NowPlayingController(musicCoordinator: $0) })
    }

    var body: some Scene {
        WindowGroup {
            switch session {
            case .success(let coordinator):
                MainScreen(coordinator: coordinator, music: music, deviceDescription: UIDevice.current.name)
            case .failure(let error):
                SecureTransportUnavailableView(reason: String(describing: error))
            }
        }
    }
}
