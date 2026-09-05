import Foundation

/// Local availability for one `content_hash`, combining three independent facts: is it in the
/// Phase 3 library (imported by this user), is it in the verified transfer cache (ADR-023 §6), and
/// does the connected peer's manifest currently advertise it. Deliberately three booleans rather
/// than one pre-named enum for every combination — a combination is never invalid, so there is
/// nothing an enum's exhaustiveness would protect against that these three fields don't already
/// guarantee.
///
/// `hasCached` must only ever be set once bytes have been fully received **and** whole-file
/// SHA-256 verified **and** the cache entry committed (ADR-023 §6) — never merely because a
/// transfer reached `.transferring`. `hasRemote` is session/peer-scoped: it must be cleared, not
/// merely left stale, the moment the peer disconnects or a different peer connects.
///
/// The Kotlin mirror is `com.ridelink.core.transfer.Availability`.
public struct Availability: Sendable, Equatable {
    public let hasLocal: Bool
    public let hasCached: Bool
    public let hasRemote: Bool

    public init(hasLocal: Bool, hasCached: Bool, hasRemote: Bool) {
        self.hasLocal = hasLocal
        self.hasCached = hasCached
        self.hasRemote = hasRemote
    }

    /// A UI-facing label. Not a separate source of truth — always derived from the three fields.
    public enum Label: Sendable, Equatable {
        case none
        case local
        case cached
        case localAndCached
        case remoteOnly
        case localAndRemote
        case cachedAndRemote
        case all
    }

    public var label: Label {
        switch (hasLocal, hasCached, hasRemote) {
        case (true, true, true): return .all
        case (true, _, true): return .localAndRemote
        case (_, true, true): return .cachedAndRemote
        case (true, true, _): return .localAndCached
        case (true, _, _): return .local
        case (_, true, _): return .cached
        case (_, _, true): return .remoteOnly
        default: return .none
        }
    }

    /// Playable right now, without any transfer — true for either provenance.
    public var playableLocally: Bool { hasLocal || hasCached }

    /// A transfer would be pointless — never re-transfer content already held.
    public var transferWouldBeRedundant: Bool { hasLocal || hasCached }
}
