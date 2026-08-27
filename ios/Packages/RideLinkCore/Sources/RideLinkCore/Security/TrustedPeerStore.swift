import Foundation

/// Where `TrustedPeer` records live between rides (PROTOCOL §4.5, ADR-012).
///
/// The protocol is in the domain layer and the storage is not, so the pin logic can be exhausted
/// by a laptop test with no device, no file system and no keychain anywhere in the picture.
///
/// **Nothing stored through this protocol is a secret.** A trusted-peer record is a `peer_id`, an
/// `identity_spki_sha256` (a digest of a *public* key the peer hands to anyone who opens a TLS
/// connection to it), a display name and two timestamps. What must stay secret is the private key,
/// and that never comes near here: it lives in the Keychain and is never serialised at all
/// (ADR-017 §1). So an implementation needs *integrity* — an attacker who can rewrite the pin can
/// substitute an identity — rather than confidentiality, and app-private storage supplies it.
///
/// Implementations must be safe to call from multiple tasks. Mirrors Android's `TrustedPeerStore`.
public protocol TrustedPeerStore: Sendable {
    /// The pin lookup PROTOCOL §4.1 specifies: by the `peer_id` the peer claimed in HELLO.
    func byPeerId(_ peerId: PeerId) -> TrustedPeer?

    /// Lookup by identity. Answers "have we met this key before, under any name?".
    func bySpki(_ identitySpkiSha256: SpkiHash) -> TrustedPeer?

    func all() -> [TrustedPeer]

    /// Records a peer as trusted after a successful SAS confirmation, or refreshes `lastSeenAt`
    /// on an existing one.
    ///
    /// Implementations must **not** silently overwrite a stored `identitySpkiSha256` with a
    /// different one for the same `peerId`: that is precisely the "auto re-pair" ADR-012 forbids,
    /// and it would turn a `pin_mismatch` into a silent identity substitution. Changing a pin
    /// requires `forget` first, which is a user action.
    func remember(_ peer: TrustedPeer) throws

    /// The explicit user action that makes a re-pair — and a fresh SAS on both screens — possible.
    func forget(_ peerId: PeerId)
}

/// Thrown when `remember` would change a stored pin without an explicit `forget`.
public struct PinReplacementRefusedError: Error, CustomStringConvertible {
    public let peerId: PeerId

    public init(peerId: PeerId) { self.peerId = peerId }

    public var description: String {
        "refusing to replace the stored identity for \(peerId); forget the peer and pair again (ADR-012)"
    }
}

/// A `TrustedPeerStore` with no persistence. The default until a platform store is wired in, and
/// the one every unit test uses.
///
/// Being in-memory is not a security shortcut — see the protocol's note on why these records are
/// not secret — it simply means trust does not survive a process restart, which a test wants and a
/// ride does not.
public final class InMemoryTrustedPeerStore: TrustedPeerStore, @unchecked Sendable {
    private let lock = NSLock()
    private var peers: [String: TrustedPeer] = [:]

    public init(_ initial: [TrustedPeer] = []) {
        for peer in initial { peers[peer.peerId.value] = peer }
    }

    public func byPeerId(_ peerId: PeerId) -> TrustedPeer? {
        lock.lock(); defer { lock.unlock() }
        return peers[peerId.value]
    }

    public func bySpki(_ identitySpkiSha256: SpkiHash) -> TrustedPeer? {
        lock.lock(); defer { lock.unlock() }
        return peers.values.first { $0.identitySpkiSha256 == identitySpkiSha256 }
    }

    public func all() -> [TrustedPeer] {
        lock.lock(); defer { lock.unlock() }
        return Array(peers.values)
    }

    public func remember(_ peer: TrustedPeer) throws {
        lock.lock(); defer { lock.unlock() }
        if let existing = peers[peer.peerId.value], existing.identitySpkiSha256 != peer.identitySpkiSha256 {
            throw PinReplacementRefusedError(peerId: peer.peerId)
        }
        peers[peer.peerId.value] = peer
    }

    public func forget(_ peerId: PeerId) {
        lock.lock(); defer { lock.unlock() }
        peers.removeValue(forKey: peerId.value)
    }
}
