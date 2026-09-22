import Foundation

/// The immutable identity of one admitted unit of retained local playback work
/// (ADR-024 Amendment A11).
///
/// **It is a token, never a query.** Nothing releases capacity by reading "the current reservation";
/// a holder hands back the exact value it was given. `id` is drawn from a strictly increasing
/// counter that is never reused, so a release arriving from a retired session names an id the ledger
/// no longer holds and is a no-op — it can never decrement the reservation that replaced it. That is
/// this type's whole reason to exist, and it is ADR-024 Amendment A7's rule ("a frame's authority is
/// the binding its read produced, never whatever is live when its work runs") applied to capacity.
///
/// `generation` is the authenticated control lifetime that admitted the work. It is carried so a
/// lifetime boundary can release exactly its own reservations and nothing newer.
public struct WorkReservation: Sendable, Equatable, Hashable {
    public let id: Int64
    public let generation: Int64

    public init(id: Int64, generation: Int64) {
        self.id = id
        self.generation = generation
    }
}

/// The bound on retained local playback work, and — since ADR-024 Amendment A11 — the thing that
/// makes "no authoritative send without local capacity to honour it" a property of the pipeline
/// rather than of a check somewhere downstream.
///
/// ## Why a ledger and not a counter
///
/// The bounded wire queues (`Phase5GateBounds.defaultInboundCapacity` /
/// `defaultOutboundCapacity`) bound what is *on the wire*. They do not bound what a frame leaves
/// behind after it has been drained: a leader's ordered apply node, and the scheduled node that node
/// arms. Phase 8 found that correctly. Its first fix counted live task nodes and refused at the
/// point the node was created — which, on the leader, is **after** the frame reached the peer. A
/// refusal there abandons authority the follower has already applied, which is exactly the
/// divergence ADR-024 Amendment A2 exists to prevent, reached from the opposite direction.
///
/// So capacity is *reserved* at the point responsibility is taken — before the command can be
/// delivered — and the reservation is what the downstream work spends. A command that cannot be
/// honoured locally is therefore never sent.
///
/// ## Phases
///
/// One command's local obligation is not one task. A leader's delivered command occupies an apply
/// node, and that node arms a scheduled node which outlives it. Both belong to the same obligation,
/// so a reservation is refcounted: it is created holding **one** phase, `enterPhase` adds one, and
/// `leavePhase` removes one. The reservation is released when the last phase leaves.
///
/// The refcount cannot leak or double-release, and the reason is structural rather than careful:
/// every `enterPhase` happens **synchronously inside** the phase that is already held (`scheduleAt`
/// arms its node before the apply that called it returns), so the count can never reach zero between
/// a holder deciding to hand work on and that work being represented. A `leavePhase` for an id the
/// ledger no longer holds — released by a boundary, or already at zero — is a no-op.
///
/// ## Not a distributed decision
///
/// Deliberately **no shared vector set**, for the reason ADR-024 Amendments A3, A5, A7 and ADR-025
/// give for theirs: coroutine and `Task` lifetime, and now local work capacity, are properties of
/// one device's own scheduler. Nothing here reaches the wire, and the two platforms agree because
/// they are line-for-line ports with mirrored unit tests, not because a vector says so.
///
/// Pure by CLAUDE.md rule 9: no platform type, no clock read, no I/O. Not thread-safe by itself —
/// every caller is the single actor that owns the coordinator's state.
public final class SessionWorkLedger {
    /// Live obligations admitted at once. Injectable so a test can force the edge deterministically.
    public let capacity: Int

    private var phases: [Int64: Int] = [:]
    private var generations: [Int64: Int64] = [:]
    private var nextId: Int64 = 1

    public init(capacity: Int = Phase5GateBounds.defaultSessionWorkCapacity) {
        self.capacity = capacity
    }

    /// How many obligations are outstanding. The number a boundedness test reads.
    public var liveCount: Int { phases.count }

    /// Takes capacity for one local obligation authorised by `generation`, or answers `nil` when
    /// there is none.
    ///
    /// A `nil` is **not** a failure to be retried silently. Its callers each have an existing,
    /// reviewed answer: the leader refuses the command before it can be sent, a follower declares
    /// itself desynchronised without spending the `command_seq`, and a replay leaves the work where
    /// it already is. What none of them may do is proceed.
    public func reserve(generation: Int64) -> WorkReservation? {
        guard phases.count < capacity else { return nil }
        let id = nextId
        nextId += 1
        phases[id] = 1
        generations[id] = generation
        return WorkReservation(id: id, generation: generation)
    }

    /// A second piece of work joins an obligation that is already held.
    ///
    /// - Returns: false when the reservation is no longer live — a lifetime boundary released it —
    ///   in which case the caller must not create the work either.
    @discardableResult
    public func enterPhase(_ reservation: WorkReservation) -> Bool {
        guard let held = phases[reservation.id] else { return false }
        phases[reservation.id] = held + 1
        return true
    }

    /// One phase of the reservation finished. Releases the obligation when it was the last one.
    public func leavePhase(_ reservation: WorkReservation) {
        guard let held = phases[reservation.id] else { return }
        if held <= 1 {
            phases.removeValue(forKey: reservation.id)
            generations.removeValue(forKey: reservation.id)
        } else {
            phases[reservation.id] = held - 1
        }
    }

    /// Whether the reservation is still held. Diagnostics and assertions only; never an authority.
    public func isLive(_ reservation: WorkReservation) -> Bool { phases[reservation.id] != nil }

    /// An authenticated control lifetime ended: release every obligation it authorised.
    ///
    /// Generations strictly increase per authentication (ADR-023 §3), so "through" is the right
    /// comparison — a boundary naming a newer lifetime proves every older one ended too, which is
    /// ADR-020 Amendment A8's direction applied here. A reservation belonging to a **newer**
    /// generation is never touched, which is what stops a retired session's cleanup from freeing a
    /// successor's capacity and letting it over-commit.
    ///
    /// - Returns: how many obligations were released.
    @discardableResult
    public func retire(throughGeneration: Int64) -> Int {
        let doomed = generations.filter { $0.value <= throughGeneration }.map(\.key)
        for id in doomed {
            phases.removeValue(forKey: id)
            generations.removeValue(forKey: id)
        }
        return doomed.count
    }

    /// Terminal teardown: no lifetime survives, so nothing may hold capacity.
    public func clear() {
        phases.removeAll()
        generations.removeAll()
    }
}
