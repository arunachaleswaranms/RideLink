import RideLinkPlatform
import SwiftUI

/// The composition root's refusal to run the control plane in the clear (NFR-06, PROTOCOL §1).
///
/// **This replaces the Phase 1a `PlaintextTransportGate`, and the guarantee is stronger rather
/// than weaker.** Phase 1a shipped a plaintext transport inside `RideLinkPlatform` and used
/// `#if DEBUG` to avoid *constructing* it. Phase 1b deletes that path instead: the only plaintext
/// `ControlChannel` in the repository lives in `RideLinkPlatform`'s **test target**, so it is not
/// compiled into the library at all and no app build — debug or release — contains those bytes.
/// There is nothing left for a compilation condition to gate.
///
/// What remains is this assertion, kept because "no insecure channel exists" is a property of
/// today's tree while this is a property of the code that runs.
enum SecureTransportPolicy {
    static func requireSecure(_ channel: any ControlChannel) {
        precondition(
            channel.isSecure,
            "refusing to start a control session over an insecure transport (\(channel.transportLabel)). " +
                "PROTOCOL §1 and NFR-06 require TLS 1.3; there is no plaintext production path."
        )
    }
}

/// Shown instead of `MainScreen` when the device identity could not be created — which is the only
/// way a session can now fail to assemble, since the transport itself is no longer conditional.
///
/// There is deliberately no "continue without security" affordance here. ADR-007 Amendment A1
/// forbids a plaintext fallback outright, so the honest thing for this screen to do is say what
/// failed and stop.
struct SecureTransportUnavailableView: View {
    let reason: String

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("RideLink")
                .font(.largeTitle)
            Text("Secure transport unavailable")
                .font(.title3)
            Text(
                "RideLink could not create or load this device's identity key, so it cannot open " +
                    "an authenticated connection. There is no unencrypted fallback."
            )
            .font(.body)
            .foregroundStyle(.secondary)
            Text(reason)
                .font(.footnote.monospaced())
                .foregroundStyle(.secondary)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}
