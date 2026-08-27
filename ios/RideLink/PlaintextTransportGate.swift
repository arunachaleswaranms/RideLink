import SwiftUI

/// Release-transport guard (this session's brief §4). `SessionCoordinator.init()` constructs
/// `ControlSessionManager` — `PlainControlTransportPhase1a`, the plaintext, debug-only Phase 1a
/// transport (PROTOCOL §1's TLS 1.3 requirement is Phase 1b's job, not implemented yet). `#if
/// DEBUG` is a **compile-time** condition (`SWIFT_ACTIVE_COMPILATION_CONDITIONS = DEBUG` is set
/// only for the Debug build configuration in `RideLink.xcodeproj` — confirmed, not assumed): in a
/// Release build the `SessionCoordinator()` call below, and therefore the entire plaintext
/// transport, is not merely skipped at runtime, it is not compiled into the binary at all.
enum PlaintextTransportGate {
    @MainActor
    static func makeSessionCoordinator() -> SessionCoordinator? {
        #if DEBUG
        return SessionCoordinator()
        #else
        return nil
        #endif
    }
}

/// Shown instead of `MainScreen` whenever `PlaintextTransportGate.makeSessionCoordinator()`
/// returns `nil` — always true in a Release build. Phase 1b's TLS transport is what turns this
/// back into a working screen; there is deliberately no fallback to the plaintext path here.
struct SecureTransportUnavailableView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("RideLink")
                .font(.largeTitle)
            Text("Secure transport not implemented")
                .font(.title3)
            Text(
                "Phase 1a's control transport is plaintext and debug-only. This release build " +
                    "will not start it. Secure transport (TLS 1.3) arrives in Phase 1b."
            )
            .font(.body)
            .foregroundStyle(.secondary)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}
