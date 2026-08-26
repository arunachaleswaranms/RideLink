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

public enum AdvertiseState: Sendable, Equatable {
    case starting
    case advertising(serviceName: String)
    case failed(String)
    case stopped
}

public enum DiscoveryError: Error, Sendable {
    case invalidPort
}

/// `Network`-framework-backed implementation of PROTOCOL §4.1 / ARCHITECTURE §4.1 discovery.
///
/// The TXT record carries **exactly** `{v, dh, plat}` (CLAUDE.md privacy rules) — no `peer_id`,
/// no SPKI or certificate hash or prefix, no token, no library size, no device name. Known-peer
/// recognition happens after the TLS handshake (Phase 1b), never here. Mirrors Android's
/// `NsdDiscoveryController`.
public final class BonjourDiscovery: @unchecked Sendable {
    private var listener: NWListener?
    private var browser: NWBrowser?
    private let queue = DispatchQueue(label: "com.ridelink.platform.discovery")

    public init() {}

    public func startAdvertising(port: UInt16, onStateChange: @escaping @Sendable (AdvertiseState) -> Void) throws {
        guard let nwPort = NWEndpoint.Port(rawValue: port) else {
            throw DiscoveryError.invalidPort
        }
        onStateChange(.starting)

        var txt = NWTXTRecord()
        txt["v"] = "1"
        txt["dh"] = DiscoveryHandle.generate()
        txt["plat"] = "ios"

        let listener = try NWListener(using: .tcp, on: nwPort)
        listener.service = NWListener.Service(type: serviceType, txtRecord: txt)
        listener.serviceRegistrationUpdateHandler = { change in
            if case .add(let endpoint) = change, case .service(let name, _, _, _) = endpoint {
                onStateChange(.advertising(serviceName: name))
            }
        }
        listener.stateUpdateHandler = { state in
            switch state {
            case .failed(let error):
                onStateChange(.failed("\(error)"))
            case .cancelled:
                onStateChange(.stopped)
            default:
                break
            }
        }
        listener.start(queue: queue)
        self.listener = listener
    }

    public func stopAdvertising() {
        listener?.cancel()
        listener = nil
    }

    public func startBrowsing(onPeerFound: @escaping @Sendable (DiscoveredPeer) -> Void) {
        let params = NWParameters()
        params.includePeerToPeer = false
        let browser = NWBrowser(for: .bonjour(type: serviceType, domain: nil), using: params)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            for result in results {
                self?.resolve(result, onPeerFound: onPeerFound)
            }
        }
        browser.start(queue: queue)
        self.browser = browser
    }

    public func stopBrowsing() {
        browser?.cancel()
        browser = nil
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
