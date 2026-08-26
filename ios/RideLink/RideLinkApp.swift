import SwiftUI

@main
struct RideLinkApp: App {
    @State private var coordinator = SessionCoordinator()

    var body: some Scene {
        WindowGroup {
            MainScreen(coordinator: coordinator, deviceDescription: UIDevice.current.name)
        }
    }
}
