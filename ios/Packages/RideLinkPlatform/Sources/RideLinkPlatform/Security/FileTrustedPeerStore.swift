import Foundation
import RideLinkCore
import Security

/// `TrustedPeerStore` backed by one small JSON file in the app container. The mirror of Android's
/// `data/trustedpeers/FileTrustedPeerStore`.
///
/// **Why plain app-container storage and not the Keychain.** A trusted-peer record contains no
/// secret: a `peer_id`, an `identity_spki_sha256` (a digest of a *public* key the peer hands to
/// anyone who opens a TLS connection to it), a display name and two timestamps. What must stay
/// secret is the private identity key, and that is never here — it lives in the Keychain and is
/// never serialised at all (ADR-017 §1). The property this file needs is **integrity**: an
/// attacker who can rewrite a pin can substitute an identity. The app container supplies that
/// against every attacker in the threat model (a passive or active attacker on the same Wi-Fi);
/// it does not defend against an attacker already running as this app, and neither would the
/// Keychain against the same attacker. Recorded rather than left implicit.
///
/// Writes are atomic, because a half-written trust store on a phone that lost power mid-ride would
/// present as `pin_mismatch` — a security warning for a filesystem event.
public final class FileTrustedPeerStore: TrustedPeerStore, @unchecked Sendable {
    private let url: URL
    private let lock = NSLock()
    private var cache: [String: TrustedPeer]?

    public init(url: URL) {
        self.url = url
    }

    public func byPeerId(_ peerId: PeerId) -> TrustedPeer? {
        lock.lock(); defer { lock.unlock() }
        return load()[peerId.value]
    }

    public func bySpki(_ identitySpkiSha256: SpkiHash) -> TrustedPeer? {
        lock.lock(); defer { lock.unlock() }
        return load().values.first { $0.identitySpkiSha256 == identitySpkiSha256 }
    }

    public func all() -> [TrustedPeer] {
        lock.lock(); defer { lock.unlock() }
        return Array(load().values)
    }

    public func remember(_ peer: TrustedPeer) throws {
        lock.lock(); defer { lock.unlock() }
        var peers = load()
        // ADR-012: a stored pin is never silently replaced. That is the difference between
        // "refresh lastSeenAt" and "auto re-pair", and only the first is allowed.
        if let existing = peers[peer.peerId.value], existing.identitySpkiSha256 != peer.identitySpkiSha256 {
            throw PinReplacementRefusedError(peerId: peer.peerId)
        }
        peers[peer.peerId.value] = peer
        try persist(peers)
    }

    public func forget(_ peerId: PeerId) {
        lock.lock(); defer { lock.unlock() }
        var peers = load()
        guard peers.removeValue(forKey: peerId.value) != nil else { return }
        try? persist(peers)
    }

    private func load() -> [String: TrustedPeer] {
        if let cache { return cache }
        // A corrupt or truncated store is treated as empty rather than fatal: the worst outcome is
        // a re-pair with a fresh SAS, which is safe. Throwing here would brick the app at launch
        // over a damaged file.
        let stored = (try? Data(contentsOf: url)).flatMap { try? JSONDecoder().decode([StoredPeer].self, from: $0) } ?? []
        let loaded = Dictionary(uniqueKeysWithValues: stored.compactMap { $0.toDomain() }.map { ($0.peerId.value, $0) })
        cache = loaded
        return loaded
    }

    private func persist(_ peers: [String: TrustedPeer]) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try encoder.encode(peers.values.map(StoredPeer.init(from:)))
        // .atomic is a temp-file-plus-rename, so a crash mid-write leaves the previous file intact
        // rather than a truncated one.
        try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        cache = peers
    }

    /// The on-disk shape, deliberately separate from `TrustedPeer`. Values are read back as plain
    /// strings and re-validated through the non-trapping parsers, so a hand-edited or corrupted
    /// file drops the bad record instead of trapping at launch — the same wire-safety discipline
    /// the control plane applies to a peer-supplied field.
    private struct StoredPeer: Codable {
        let peerId: String
        let identitySpkiSha256: String
        let displayName: String
        let pairedAtEpochSeconds: Int64
        let lastSeenAtEpochSeconds: Int64

        init(from peer: TrustedPeer) {
            peerId = peer.peerId.value
            identitySpkiSha256 = peer.identitySpkiSha256.value
            displayName = peer.displayName
            pairedAtEpochSeconds = peer.pairedAtEpochSeconds
            lastSeenAtEpochSeconds = peer.lastSeenAtEpochSeconds
        }

        func toDomain() -> TrustedPeer? {
            guard let id = PeerId.parse(peerId), let spki = SpkiHash.parse(identitySpkiSha256) else { return nil }
            return TrustedPeer(
                peerId: id,
                identitySpkiSha256: spki,
                displayName: displayName,
                pairedAtEpochSeconds: pairedAtEpochSeconds,
                lastSeenAtEpochSeconds: lastSeenAtEpochSeconds
            )
        }
    }
}

/// This device's own durable `peer_id` (PROTOCOL §2: "16-hex string, durable, assigned at
/// pairing"). The mirror of Android's `LocalPeerIdStore`.
///
/// Self-assigned on first run rather than handed out by whichever peer happened to accept the first
/// connection, because with two symmetric peers there is no authority to assign one and inventing a
/// negotiation would add a failure mode for nothing.
///
/// **Not a secret and not an identity.** Trust is `identity_spki_sha256` (ADR-012); `peer_id` is
/// only a stable label to hang a pin on and to elect a leader by (ADR-010). It is CSPRNG-generated
/// rather than derived from anything about the device, so it carries no hardware or user
/// information, and it never appears in an mDNS TXT record (ADR-002 Amendment A1).
public struct LocalPeerIdStore {
    private let url: URL

    public init(url: URL) { self.url = url }

    public func loadOrCreate() -> PeerId {
        if let existing = (try? String(contentsOf: url, encoding: .utf8))?
            .trimmingCharacters(in: .whitespacesAndNewlines),
            let parsed = PeerId.parse(existing) {
            return parsed
        }
        let fresh = Self.generate()
        try? Data(fresh.value.utf8).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        return fresh
    }

    private static func generate() -> PeerId {
        var bytes = [UInt8](repeating: 0, count: 8) // 16 hex characters
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return PeerId(bytes.map { String(format: "%02x", $0) }.joined())
    }
}
