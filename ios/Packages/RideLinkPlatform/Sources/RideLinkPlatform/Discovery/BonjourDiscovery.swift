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

private let instanceNameHandlePrefixLength = 8

/// Neutral, ephemeral Bonjour/mDNS **instance name** — deliberately not the device model, a
/// user-chosen name, username, `peer_id`, SPKI or any other durable identifier (this session's
/// brief §6). This matters on iOS specifically because `NWListener.Service(name: nil, ...)`
/// falls back to the device's Bonjour name, which is tied to `UIDevice.current.name` — leaving
/// `name` unset here would silently leak it. Derived from the same rotating `DiscoveryHandle`
/// the TXT record's `dh` carries, truncated to 8 hex characters — long enough to disambiguate
/// concurrent advertisements on one LAN, short enough that the instance name itself isn't just a
/// second copy of the full 32-character handle. Rotates exactly when `dh` rotates, by
/// construction: this function is pure and carries no state of its own. Mirrors Android's
/// `instanceServiceName`.
public func instanceServiceName(discoveryHandle: String) -> String {
    "RideLink-\(discoveryHandle.prefix(instanceNameHandlePrefixLength))"
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
///
/// **`@unchecked Sendable` invariant (reviewed this session, brief §8):** every stored mutable
/// property (`browser`, `activeListener`, `rotationTask`, `selfHandles`'s internal state) is read
/// and written **only** while executing on `queue`. Two nuances that made this easy to violate
/// without noticing:
/// - The public API surface (`startAdvertising`, `stopAdvertising`, `startBrowsing`,
///   `stopBrowsing`) may be called from *any* thread/actor — every one of them must therefore
///   dispatch its own body onto `queue` rather than touching state directly on the caller's
///   thread. (Two call sites did not, before this review — see the change history in git blame
///   for `stopAdvertising`/`startBrowsing`/`stopBrowsing`.)
/// - `listener` in `startAdvertising` is **not** started on this type's `queue` — it is owned and
///   started by `ControlSessionManager` on its own listener queue (`Framing.swift`). Any callback
///   `listener` invokes (`serviceRegistrationUpdateHandler`, `stateUpdateHandler`) therefore fires
///   on *that* queue, not `queue`, and must hop onto `queue` before touching this type's state —
///   `stateUpdateHandler` never touches it (safe as-is); `serviceRegistrationUpdateHandler` reads
///   `selfHandles.currentHandle` and does, so it hops explicitly.
public final class BonjourDiscovery: @unchecked Sendable {
    private var browser: NWBrowser?
    private let queue = DispatchQueue(label: "com.ridelink.platform.discovery")
    private var rotationTask: Task<Void, Never>?

    private var activeListener: NWListener?

    /// The dh(s) this instance currently considers self, so `startBrowsing` can filter out
    /// self-discovery even mid-rotation (this session's brief §8). Read/written only on `queue`.
    private let selfHandles = SelfDiscoveryHandles()

    /// How long the just-superseded `dh` is still recognised as self after a rotation.
    /// `NWListener.service` reassignment is documented as a single "live update", not Android's
    /// two-step unregister-then-register, so there is no OS callback confirming the old
    /// advertisement is gone — this bounded window (same order of magnitude as
    /// `DuplicateConnectionArbiter.gracePeriodNs`) stands in for that confirmation.
    private static let selfHandleGracePeriodNs: UInt64 = 1_000_000_000 // 1s

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

            // `listener` is started on ControlSessionManager's own listener queue, not `queue` —
            // this handler fires there, not here, so reading `selfHandles` must hop onto `queue`
            // explicitly rather than touching it directly on whatever thread this runs on.
            listener.serviceRegistrationUpdateHandler = { [weak self] change in
                guard let self else { return }
                if case .add(let endpoint) = change, case .service(let name, _, _, _) = endpoint {
                    self.queue.async {
                        onStateChange(.advertising(serviceName: name, discoveryHandle: self.selfHandles.currentHandle ?? ""))
                    }
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
        // The old dh stays recognised as self for a bounded grace period after this — see
        // `selfHandleGracePeriodNs`'s doc comment for why iOS needs a timer rather than an OS
        // confirmation callback (this session's brief §8).
        selfHandles.rotate(dh)
        var txt = NWTXTRecord()
        for (key, value) in buildTxtRecord(discoveryHandle: dh) {
            txt[key] = value
        }
        // Re-assigning `.service` on an already-started listener is a live TXT-record update in
        // Network.framework, not a rebind — the accepting socket (and any connection already
        // accepted through it) is untouched. `name:` is explicit and never left to default —
        // see `instanceServiceName`'s doc comment for why that default is unsafe here.
        listener.service = NWListener.Service(name: instanceServiceName(discoveryHandle: dh), type: serviceType, txtRecord: txt)

        Task { [weak self] in
            try? await Task.sleep(nanoseconds: Self.selfHandleGracePeriodNs)
            guard let self else { return }
            self.queue.async { self.selfHandles.clearPrevious() }
        }
    }

    public func stopAdvertising() {
        // `rotationTask` must be cancelled on `queue`, same as every other stored property here —
        // cancelling it directly on the caller's thread (the bug this review found, brief §8)
        // raced against `startAdvertising`'s queue-confined write of the same property.
        queue.async { [weak self] in
            self?.rotationTask?.cancel()
            self?.rotationTask = nil
            self?.activeListener?.service = nil
            self?.activeListener = nil
            self?.selfHandles.reset()
        }
    }

    public func startBrowsing(onEvent: @escaping @Sendable (DiscoveryEvent) -> Void) {
        // `browser` must be written on `queue`, same as every other stored property here —
        // mutating it directly on the caller's thread (the bug this review found, brief §8) had
        // no synchronization against `stopBrowsing`'s own direct-on-caller-thread mutation.
        queue.async { [weak self] in
            guard let self else { return }
            let params = NWParameters()
            params.includePeerToPeer = false
            let browser = NWBrowser(for: .bonjour(type: serviceType, domain: nil), using: params)
            browser.browseResultsChangedHandler = { [weak self] _, changes in
                guard let self else { return }
                for change in changes {
                    self.handle(change, onEvent: onEvent)
                }
            }
            browser.start(queue: self.queue)
            self.browser = browser
        }
    }

    public func stopBrowsing() {
        queue.async { [weak self] in
            self?.browser?.cancel()
            self?.browser = nil
        }
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
        !selfHandles.isSelf(peer.discoveryHandle)
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
