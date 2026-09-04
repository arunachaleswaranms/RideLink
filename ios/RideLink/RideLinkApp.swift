import SwiftUI

@main
struct RideLinkApp: App {
    /// Built once at launch. `SessionCoordinator.init()` creates or loads the device identity
    /// (ADR-017) and assembles the TLS 1.3 control channel; if that fails there is deliberately no
    /// unencrypted fallback to offer, so the app says what failed and stops.
    @State private var session: Result<SessionCoordinator, Error> = Result { try SessionCoordinator() }

    /// Phase 3's local music stack — built independently of `session` (this phase's brief §30: a
    /// player failure must not affect the control session, and the reverse). A failure here (the
    /// database could not be opened) is shown rather than silently dropping local music, the same
    /// "say what failed and stop" honesty `SecureTransportUnavailableView` already gives `session`.
    @State private var music: Result<MusicCoordinator, Error> = Result { try MusicCoordinator() }

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
