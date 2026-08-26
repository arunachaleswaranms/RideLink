import Foundation
import Network
import RideLinkCore
import Security

private let serviceType = "_ridelink._tcp"

/// ARCHITECTURE §4.1: 16 CSPRNG bytes as 32 lowercase hex characters. Never persisted, never
/// derived from `peer_id` or the identity key. Mirrors Android's `DiscoveryHandle`.
public enum DiscoveryHandle {
    private static let byteCount = 16

    public static func generate() -> String {
        var bytes = [UInt8](repeating: 0, count: byteCount)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed")
        return bytes.map { String(format: "%02x", $0) }.joined()
    }
}

/// This session's brief §4F: the ephemeral discovery handle rotates whenever advertising starts,
/// and at least every 15 minutes while it continues. Pure so the interval decision is testable
/// against an injected clock instead of a real 15-minute wait. Mirrors Android's
/// `DiscoveryHandleRotationPolicy`.
public enum DiscoveryHandleRotationPolicy {
    public static let rotationIntervalNs: UInt64 = 15 * 60 * 1_000_000_000

    public static func isRotationDue(nowMonoNs: UInt64, lastRotatedAtMonoNs: UInt64, intervalNs: UInt64 = rotationIntervalNs) -> Bool {
        nowMonoNs &- lastRotatedAtMonoNs >= intervalNs
    }
}

public enum AdvertiseState: Sendable, Equatable {
    case starting
    case advertising(serviceName: String, discoveryHandle: String)
    case failed(String)
    case stopped
}

public enum DiscoveryError: Error, Sendable {
    case invalidPort
}

/// Platform-neutral discovery lifecycle (this session's brief §4D) — Found/Updated/Lost, never a
/// bare "found". Mirrors Android's `DiscoveryEvent`.
public enum DiscoveryEvent: Sendable {
    case found(DiscoveredPeer)
    case updated(DiscoveredPeer)
    case lost(discoveryHandle: String)
}

/// The complete, exact TXT record content (ARCHITECTURE §4.1 / CLAUDE.md privacy rules): `{v,
/// dh, plat}` and nothing else, ever. The same function `BonjourDiscovery.rotate` uses, so an
/// accidental future addition (`peer_id`, a device name, a library count) fails a laptop unit
/// test instead of shipping. Mirrors Android's `buildTxtRecord`.
public func buildTxtRecord(discoveryHandle: String) -> [String: String] {
    ["v": "1", "dh": discoveryHandle, "plat": "ios"]
}

/// `Network`-framework-backed implementation of PROTOCOL §4.1 / ARCHITECTURE §4.1 discovery.
///
/// The TXT record carries **exactly** `{v, dh, plat}` (CLAUDE.md privacy rules) — no `peer_id`,
/// no SPKI or certificate hash or prefix, no token, no library size, no device name. Known-peer
/// recognition happens after the TLS handshake (Phase 1b), never here. Mirrors Android's
/// `NsdDiscoveryController`.
///
/// **Advertising shares the control listener's socket.** `startAdvertising` takes an already-bound
/// `NWListener` (owned by `ControlSessionManager`) rather than creating its own — binding a
/// second, unused TCP port purely to carry a Bonjour registration would contradict the "one
/// listener, one port" shape the rest of Phase 1a assumes (this session's brief §4B). Rotating the
/// discovery handle re-assigns `NWListener.service` in place, which `Network.framework` supports
/// as a live TXT-record update — it never cancels or rebinds the listener, so an established
/// control connection accepted through it is completely unaffected.
public final class BonjourDiscovery: @unchecked Sendable {
    private var browser: NWBrowser?
    private let queue = DispatchQueue(label: "com.ridelink.platform.discovery")
    private var rotationTask: Task<Void, Never>?

    private var activeListener: NWListener?

    /// The dh this instance is currently advertising, so [startBrowsing] can filter out
    /// self-discovery. Read/written only on `queue`.
    private var activeDiscoveryHandle: String?

    public init() {}

    /// Attaches a Bonjour registration to `listener` — which must already be started (bound and
    /// accepting) — and rotates its TXT-carried discovery handle at least every
    /// `rotationIntervalNs` (default: `DiscoveryHandleRotationPolicy.rotationIntervalNs`).
    public func startAdvertising(
        on listener: NWListener,
        rotationIntervalNs: UInt64 = DiscoveryHandleRotationPolicy.rotationIntervalNs,
        onStateChange: @escaping @Sendable (AdvertiseState) -> Void
    ) {
        queue.async { [weak self] in
            guard let self else { return }
            self.activeListener = listener
            onStateChange(.starting)

            listener.serviceRegistrationUpdateHandler = { change in
                if case .add(let endpoint) = change, case .service(let name, _, _, _) = endpoint {
                    onStateChange(.advertising(serviceName: name, discoveryHandle: self.activeDiscoveryHandle ?? ""))
                }
            }
            let previousStateHandler = listener.stateUpdateHandler
            listener.stateUpdateHandler = { state in
                previousStateHandler?(state)
                switch state {
                case .failed(let error):
                    onStateChange(.failed("\(error)"))
                case .cancelled:
                    onStateChange(.stopped)
                default:
                    break
                }
            }

            self.rotate(listener: listener)
            self.rotationTask = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: rotationIntervalNs)
                    if Task.isCancelled { return }
                    guard let self else { return }
                    self.queue.async { self.rotate(listener: listener) }
                }
            }
        }
    }

    private func rotate(listener: NWListener) {
        let dh = DiscoveryHandle.generate()
        activeDiscoveryHandle = dh
        var txt = NWTXTRecord()
        for (key, value) in buildTxtRecord(discoveryHandle: dh) {
            txt[key] = value
        }
        // Re-assigning `.service` on an already-started listener is a live TXT-record update in
        // Network.framework, not a rebind — the accepting socket (and any connection already
        // accepted through it) is untouched.
        listener.service = NWListener.Service(type: serviceType, txtRecord: txt)
    }

    public func stopAdvertising() {
        rotationTask?.cancel()
        rotationTask = nil
        queue.async { [weak self] in
            self?.activeListener?.service = nil
            self?.activeListener = nil
            self?.activeDiscoveryHandle = nil
        }
    }

    public func startBrowsing(onEvent: @escaping @Sendable (DiscoveryEvent) -> Void) {
        let params = NWParameters()
        params.includePeerToPeer = false
        let browser = NWBrowser(for: .bonjour(type: serviceType, domain: nil), using: params)
        browser.browseResultsChangedHandler = { [weak self] _, changes in
            guard let self else { return }
            for change in changes {
                self.handle(change, onEvent: onEvent)
            }
        }
        browser.start(queue: queue)
        self.browser = browser
    }

    public func stopBrowsing() {
        browser?.cancel()
        browser = nil
    }

    private func handle(_ change: NWBrowser.Result.Change, onEvent: @escaping @Sendable (DiscoveryEvent) -> Void) {
        switch change {
        case .added(let result):
            resolve(result) { peer in
                guard self.isNotSelf(peer) else { return }
                onEvent(.found(peer))
            }
        case .changed(_, let new, _):
            resolve(new) { peer in
                guard self.isNotSelf(peer) else { return }
                onEvent(.updated(peer))
            }
        case .removed(let result):
            // The cached TXT metadata is still present on a `.removed` result, so recovering the
            // discovery handle needs no fresh resolve/connect.
            guard case .bonjour(let txt) = result.metadata, let dh = stringEntry(txt, "dh") else { return }
            onEvent(.lost(discoveryHandle: dh))
        case .identical:
            break
        @unknown default:
            break
        }
    }

    private func isNotSelf(_ peer: DiscoveredPeer) -> Bool {
        peer.discoveryHandle != activeDiscoveryHandle
    }

    private func resolve(_ result: NWBrowser.Result, onPeerFound: @escaping @Sendable (DiscoveredPeer) -> Void) {
        guard case .bonjour(let txt) = result.metadata else { return }
        guard
            let versionString = stringEntry(txt, "v"),
            let version = Int(versionString),
            let discoveryHandle = stringEntry(txt, "dh")
        else { return }

        let platform: Platform? =
            switch stringEntry(txt, "plat") {
            case "android": .android
            case "ios": .ios
            default: nil
            }

        let connection = NWConnection(to: result.endpoint, using: .tcp)
        connection.stateUpdateHandler = { [weak connection] state in
            guard case .ready = state, let connection else { return }
            if case .hostPort(let host, let port) = connection.currentPath?.remoteEndpoint {
                onPeerFound(
                    DiscoveredPeer(
                        discoveryHandle: discoveryHandle,
                        protocolMajorVersion: version,
                        platform: platform,
                        host: "\(host)",
                        port: Int(port.rawValue)
                    )
                )
            }
            connection.cancel()
        }
        connection.start(queue: queue)
    }

    private func stringEntry(_ txt: NWTXTRecord, _ key: String) -> String? {
        guard case .string(let value) = txt.getEntry(for: key) else { return nil }
        return value
    }
}
