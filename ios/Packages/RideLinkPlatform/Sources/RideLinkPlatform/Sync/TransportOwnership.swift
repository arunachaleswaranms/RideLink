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
    /// ADR-024 Amendment A15 (PR #18 review round 2): the ownership **lifetime**. It advances on every
    /// flip between local and synchronised — never on a role change inside synchronised mode — so
    /// local A, synchronised B and local C are three lifetimes, and an admission minted under A can
    /// never be mistaken for one minted under C.
    private var lifetime: Int64 = 0

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
        if ownership.isSynchronizedModeActive != value.isSynchronizedModeActive { lifetime += 1 }
        value = ownership
        let observer = self.observer
        lock.unlock()
        observer?(ownership)
    }

    /// The current **local** ownership lifetime, or `nil` while synchronised mode owns transport —
    /// one read under the lock, so the answer and the lifetime come from the same instant. This is
    /// what `SyncPlaybackGateAdapter` mints a `LocalQueueEditAdmission` from and compares one against;
    /// nothing else reads it.
    func localLifetime() -> Int64? {
        lock.lock()
        defer { lock.unlock() }
        return value.isSynchronizedModeActive ? nil : lifetime
    }

    private var observer: (@Sendable (TransportOwnership) -> Void)?

    /// Phase 9A.5, PR #18 review: lets `SyncPlaybackPresenter` publish ownership **for rendering** —
    /// which local-queue controls to offer — since `current` is not observable. Called after every
    /// store, outside the lock. **Never authority**: admissions still read `current` at the moment
    /// they act.
    public func setDisplayObserver(_ observer: @escaping @Sendable (TransportOwnership) -> Void) {
        lock.lock()
        self.observer = observer
        let value = self.value
        lock.unlock()
        observer(value)
    }
}
