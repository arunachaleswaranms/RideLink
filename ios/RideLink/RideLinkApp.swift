import SwiftUI

@main
struct RideLinkApp: App {
    /// Built once at launch. `SessionCoordinator.init()` creates or loads the device identity
    /// (ADR-017) and assembles the TLS 1.3 control channel; if that fails there is deliberately no
    /// unencrypted fallback to offer, so the app says what failed and stops.
    @State private var session: Result<SessionCoordinator, Error> = Result { try SessionCoordinator() }

    var body: some Scene {
        WindowGroup {
            switch session {
            case .success(let coordinator):
                MainScreen(coordinator: coordinator, deviceDescription: UIDevice.current.name)
            case .failure(let error):
                SecureTransportUnavailableView(reason: String(describing: error))
            }
        }
    }
}
