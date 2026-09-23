import Foundation
import RideLinkCore

/// Who owns this phone's transport controls right now (ADR-024 Amendment A14): Phase 3's local
/// player, or a synchronised session under an ADR-010 role.
///
/// It answers exactly one question — *may a fresh local transport control become synchronised
/// authority?* — and it is a projection of `SyncPlaybackCoordinator.syncEnabled` and
/// `SyncPlaybackCoordinator.role`, never of `SyncState`. Three things are kept apart on purpose:
///
/// - **transport ownership** (this) — whether a local control is intercepted at all;
/// - **the role** — how a synchronised command is serialised *if* it is. A role survives End Ride,
///   because the control connection does, so `role != nil` is never evidence of ownership;
/// - **`SyncState`** — operational diagnostics. An already-distributed obligation that finishes
///   after End Ride legitimately reports `.scheduled` and then `.synced` while ownership stays
///   `.local`, which is precisely why the pre-A14 derivation `role != nil && syncState != .inactive`
///   stopped being equivalent to `syncEnabled && role != nil`.
///
/// The role travels *inside* the synchronised case, so "owned, with no role" cannot be expressed
/// and a reader can never pair one publication's ownership with another's role.
public enum TransportOwnership: Sendable, Equatable {
    /// A local control is a local control: Phase 3 behaviour, bit for bit.
    case local
    /// The synchronised session owns transport control, serialised under this role.
    case synchronized(PlaybackRole)

    public var isSynchronizedModeActive: Bool {
        if case .synchronized = self { return true }
        return false
    }
}

/// The synchronous mirror of `SyncPlaybackCoordinator`'s transport ownership (ADR-024 Amendment
/// A14), shaped exactly like `RideEpochBox`.
///
/// `MusicCoordinator`'s callers — `MPRemoteCommandCenter` handlers included — are synchronous and
/// cannot await an actor, so the gate needs an answer it can read without suspending. The pre-A14
/// answer was *reconstructed* on the main actor from published diagnostics, one hop late and from
/// the wrong fields. This one is **written by the coordinator itself**, under a lock, in the same
/// actor-isolated step that changes `syncEnabled` or `role` (their `didSet`), so there is no hop to
/// lose, reorder or lag behind, and no diagnostics publication can write it at all.
///
/// Only the coordinator stores into it; everyone else reads. It is a copy of one source, not a
/// second one.
public final class TransportOwnershipBox: @unchecked Sendable {
    private let lock = NSLock()
    private var value: TransportOwnership = .local

    init() {}

    /// The ownership the coordinator last established. Read at the instant a control is pressed;
    /// the coordinator re-proves it at admission, so a press that races a boundary is refused there
    /// rather than trusted from this read.
    public var current: TransportOwnership {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    func store(_ ownership: TransportOwnership) {
        lock.lock()
        defer { lock.unlock() }
        value = ownership
    }
}
