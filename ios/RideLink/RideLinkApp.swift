import SwiftUI

@main
struct RideLinkApp: App {
    // `nil` in a Release build — see PlaintextTransportGate's doc comment (this session's brief
    // §4). Never constructed in that configuration, not just unused.
    @State private var coordinator = PlaintextTransportGate.makeSessionCoordinator()

    var body: some Scene {
        WindowGroup {
            if let coordinator {
                MainScreen(coordinator: coordinator, deviceDescription: UIDevice.current.name)
            } else {
                SecureTransportUnavailableView()
            }
        }
    }
}
