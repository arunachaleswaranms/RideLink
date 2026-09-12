import Foundation
import RideLinkCore

/// The bounded, **lossless**, order-preserving handoff Phase 5 uses in **both** directions
/// (ADR-024 Amendment A1 Findings B and C). Mirrors `com.ridelink.app.sync.Phase5FrameQueue`
/// decision for decision.
///
/// - **Inbound** it sits between the control read loop and the one Phase 5 consumer, with
///   coalescing enabled for the latest-wins families.
/// - **Outbound** it is the one ordered path every Phase 5 frame this device sends leaves by, with
///   coalescing disabled (`coalesceKeyOf` always nil): a frame this device has already stamped may
///   never be superseded by a later one, which is Finding B's whole invariant.
///
/// **Why this type exists rather than an `AsyncStream`.** Phase 5 shipped with
/// `OrderedEventChannel(bufferingNewest: 256)`, i.e. `AsyncStream(bufferingPolicy:
/// .bufferingNewest(256))`. That is a second, *lossy* queue sitting immediately behind reliable
/// ordered TLS-over-TCP: an authoritative `PLAY` could be evicted while the `PAUSE` behind it
/// survived, `CommandOrderGate` would legitimately accept the `PAUSE`, and the follower would pause
/// a track it never loaded. `Continuation.yield` does report the eviction (`.dropped`), but the
/// forwarder discarded the result — and reporting a silent loss is not the same as not losing it.
/// Both halves are fixed here: nothing is evicted, and a refusal is returned to the caller.
///
/// **The three behaviours, decided by the pure `Phase5Ingress` table and not here:**
///
/// - room ⇒ append, arrival order preserved;
/// - full, and the frame is one whose newest instance subsumes its older ones (`coalesceKey`
///   non-nil — `POSITION_REPORT`, `PLAYBACK_STATE`, `QUEUE_SNAPSHOT`) with an older sibling queued
///   ⇒ that sibling is replaced by this frame *at this frame's arrival position*. Nothing is lost:
///   applying only the newest of such a run reaches the same state as applying all of them in
///   order, which is PROTOCOL §5's "`PLAYBACK_STATE` … the reconciliation anchor, not an
///   incremental update" and §9's "the snapshot always wins" taken literally. Coalescing is what
///   keeps the overflow below from ever firing under a peer's ordinary 5 s report cadence;
/// - otherwise ⇒ `.overflow`, returned to the caller, which counts it and halts.
///
/// **Not blocking the read loop is a hard requirement.** `offer` is synchronous and never awaits,
/// because it is called from the authenticated control read loop — the loop that also carries
/// `PING`/`PONG`, so blocking it would manufacture a false link loss. Genuine TCP backpressure is
/// therefore not available to us, and the explicit refusal is what replaces it.
///
/// **Amendment A6: this pipe outlives sessions; the *identity* of what it lost does not.** The
/// queue object deliberately survives an authentication boundary (see `finish()`) — the boundary is
/// expressed by the generation each frame carries, not by tearing the pipe down. Its loss
/// accounting used to be two cumulative counters the consumer diffed, which carried no such
/// generation at all: a frame refused under Session A, observed after Session B activated, told
/// Session B it had lost a frame and halted it. That is not a diagnostics defect —
/// `playbackDesynchronized`/`queueDesynchronized` decide whether incremental authoritative commands
/// are applied at all. So every loss is now recorded against **the refused frame's own generation**
/// (`generationOf`) and handed to the consumer as `IngressLoss` records, which it drains and
/// attributes itself. Inferring the generation from whatever is live at observation time is
/// precisely the bug.
///
/// `@unchecked Sendable` with one `NSLock` covering every access to all four fields, for the same
/// reason `FakeMonotonicClock` is: `offer` must be callable synchronously from a non-isolated
/// producer, which an `actor` cannot serve.
final class Phase5FrameQueue<Element: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [Element] = []
    /// The parked consumer, if any. Only ever set while `items` is empty, which is what lets `offer`
    /// hand a frame straight across without touching the buffer.
    private var waiter: CheckedContinuation<Element?, Never>?
    private var finished = false
    /// Loss and coalescing events, one bucket per **distinct generation**, in first-arrival order
    /// (Amendment A6, corrected by A7). Every event of one generation shares that generation's
    /// bucket wherever it arrives, so the array length is the number of distinct generations the
    /// consumer has not yet drained — which is what `maxLossGenerations` has always claimed to
    /// bound, and did not while buckets were per adjacency run.
    private var losses: [IngressLoss] = []

    /// Injectable so a deterministic test can force the edge at 1 or 2 rather than racing 256 frames.
    let capacity: Int
    private let kindOf: @Sendable (Element) -> Phase5FrameKind
    /// The latest-wins family a frame belongs to, or nil for a command. Two frames coalesce only
    /// when their keys are equal, so a `POSITION_REPORT` never supersedes a `PLAYBACK_STATE`.
    private let coalesceKeyOf: @Sendable (Element) -> String?
    /// The authentication generation the frame was produced under — the generation that **owns** any
    /// loss this frame causes (Amendment A6).
    private let generationOf: @Sendable (Element) -> Int64

    /// How many distinct generations' losses may be held undrained at once. The consumer drains on
    /// every iteration, so reaching this at all means it has been parked across eight
    /// authentications — far beyond anything a ride produces, and the bound is here so that
    /// "far beyond" is a fact rather than an expectation.
    ///
    /// Must stay greater than one: `evictOldestGeneration` folds into the bucket that remains after
    /// the smallest is removed, and "the fold target is never the largest generation" needs at
    /// least two buckets to be true at all.
    static var maxLossGenerations: Int { 8 }

    init(
        capacity: Int,
        kindOf: @escaping @Sendable (Element) -> Phase5FrameKind,
        coalesceKeyOf: @escaping @Sendable (Element) -> String?,
        generationOf: @escaping @Sendable (Element) -> Int64
    ) {
        self.capacity = capacity
        self.kindOf = kindOf
        self.coalesceKeyOf = coalesceKeyOf
        self.generationOf = generationOf
    }

    var count: Int { lock.withLock { items.count } }

    /// True when a consumer is parked waiting for work — which, because a waiter is only ever stored
    /// while the buffer is empty, means **the consumer has finished dispatching everything offered so
    /// far**. That is a genuine liveness fact worth being able to read, and it is the exact signal a
    /// test needs to know the ingress is idle instead of guessing how many scheduler turns a frame
    /// takes.
    var isConsumerWaiting: Bool { lock.withLock { waiter != nil } }

    /// One generation's ingress losses, as the consumer must consider them: **whose** they are is
    /// part of the fact, not something to be inferred later.
    struct IngressLoss: Sendable, Equatable {
        let generation: Int64
        var overflowCount: Int
        var coalescedCount: Int
    }

    @discardableResult
    func offer(_ item: Element) -> IngressAdmission {
        var handoff: (CheckedContinuation<Element?, Never>, Element)?
        lock.lock()
        var admission = IngressAdmission.overflow
        if !finished {
            let key = coalesceKeyOf(item)
            let hasSameKind = key != nil && items.contains { coalesceKeyOf($0) == key }
            admission = Phase5Ingress.decide(
                kind: kindOf(item), queuedTotal: items.count, capacity: capacity, hasQueuedSameKind: hasSameKind
            )
            switch admission {
            case .admit, .coalesce:
                if admission == .coalesce {
                    // Attributed to the *incoming* frame's generation: it is the frame whose arrival
                    // caused the event, and the one whose session is being told about it.
                    recordLoss(generationOf(item), overflow: false)
                    if let key, let index = items.firstIndex(where: { coalesceKeyOf($0) == key }) {
                        // Remove the *oldest* sibling and append this one, so the newest frame keeps
                        // the newest arrival position relative to the commands around it.
                        items.remove(at: index)
                    }
                }
                if let continuation = waiter {
                    waiter = nil
                    handoff = (continuation, item)
                } else {
                    items.append(item)
                }
            case .overflow:
                recordLoss(generationOf(item), overflow: true)
            }
        } else {
            recordLoss(generationOf(item), overflow: true)
        }
        lock.unlock()
        // Resumed outside the lock: resuming a continuation can run the consumer synchronously, and
        // that consumer may call straight back into `count`.
        if let (continuation, value) = handoff { continuation.resume(returning: value) }
        return admission
    }

    /// Takes every loss recorded so far — one record per generation, in the order each generation
    /// first caused one — and clears them.
    ///
    /// Read by the **consumer** rather than reported to the producer. That direction is deliberate:
    /// `offer` is called from the control read loop, which cannot suspend into an actor and must not
    /// do work; and draining at the top of each iteration puts the observation exactly where it has
    /// to be — *before* the next frame is dispatched, so a halt takes effect ahead of any command
    /// that follows a refusal rather than one frame late.
    ///
    /// Draining rather than diffing a cumulative total is Amendment A6's other half. A baseline the
    /// consumer re-samples at a session boundary cannot be correct: the read loop is still producing
    /// under the *old* generation at that instant, so a loss recorded microseconds after the baseline
    /// was taken would be read back as the new session's. Each record carries its own generation, so
    /// there is nothing to infer and no window to lose.
    func drainLosses() -> [IngressLoss] {
        lock.withLock {
            let drained = losses
            losses.removeAll()
            return drained
        }
    }

    /// - Returns: the next frame in arrival order, or nil once `finish()` has been called and the
    ///   queue is drained.
    func take() async -> Element? {
        await withCheckedContinuation { (continuation: CheckedContinuation<Element?, Never>) in
            lock.lock()
            if !items.isEmpty {
                let item = items.removeFirst()
                lock.unlock()
                continuation.resume(returning: item)
                return
            }
            if finished {
                lock.unlock()
                continuation.resume(returning: nil)
                return
            }
            waiter = continuation
            lock.unlock()
        }
    }

    /// Ends the queue for process/coordinator teardown, releasing a parked consumer. **Not** a
    /// session boundary: a session boundary is expressed by the generation each frame carries, so
    /// the pipe deliberately outlives individual sessions.
    func finish() {
        lock.lock()
        finished = true
        let parked = waiter
        waiter = nil
        lock.unlock()
        parked?.resume(returning: nil)
    }

    /// Adds one event to its generation's bucket, opening one if that generation has none yet.
    /// Called only with `lock` held.
    ///
    /// **Amendment A7 — arrival is not monotonic in the generation, so neither the bucketing nor
    /// the eviction may assume it is.** A6 bucketed by *adjacency*: a new bucket whenever the
    /// incoming generation differed from the newest one. A7 bound every inbound frame to the
    /// connection that authorised its read, which makes the ordering this queue actually sees
    /// explicit — a read loop whose session has ended can still dispatch the one frame it had
    /// already read, and it does so *after* the successor session's own read loop has begun
    /// offering. So `A, B, A` reaches `offer`, and under A6 that was three buckets for two
    /// generations. Two consequences, both fixed here:
    ///
    /// - the cap counted buckets, not generations, so nine buckets could be as few as two
    ///   generations — `A, B, A, B, …`;
    /// - eviction dropped the *oldest by arrival* and folded it into the next oldest by arrival.
    ///   On that same alternating run the fold target was generation `B`, which may be **live** —
    ///   so a loss caused by the dead session A was re-attributed to the live session B, and a
    ///   follower answers a live-generation loss by latching `playbackDesynchronized`. That is the
    ///   very cross-session halt A6 existed to remove, re-entering through the ledger's back door.
    ///
    /// The rule now: one bucket per generation, and eviction removes the bucket with the
    /// **smallest** generation, folding its counts into the next smallest. That is safe for a
    /// reason that does not depend on arrival order at all — generations strictly increase per
    /// authentication (ADR-023 §3), so any bucket whose generation is live must be the **largest**
    /// generation present, and with distinct generations per bucket the fold target is never the
    /// largest. Nothing is discarded, the total is preserved, and no retired loss can ever be
    /// re-attributed to the live generation.
    private func recordLoss(_ generation: Int64, overflow: Bool) {
        // Newest-first, because consecutive events from one generation remain the common case.
        let index: Int
        if losses.last?.generation == generation {
            index = losses.count - 1
        } else if let existing = losses.firstIndex(where: { $0.generation == generation }) {
            index = existing
        } else {
            losses.append(IngressLoss(generation: generation, overflowCount: 0, coalescedCount: 0))
            index = losses.count - 1
        }
        if overflow {
            losses[index].overflowCount += 1
        } else {
            losses[index].coalescedCount += 1
        }
        while losses.count > Self.maxLossGenerations { evictOldestGeneration() }
    }

    /// Removes the smallest generation's bucket and folds its counts into the next smallest.
    /// Called only with `lock` held, and only with at least two buckets present — which the caller
    /// guarantees, since `maxLossGenerations` is greater than one.
    private func evictOldestGeneration() {
        guard let evictedIndex = smallestGenerationIndex() else { return }
        let evicted = losses.remove(at: evictedIndex)
        guard let targetIndex = smallestGenerationIndex() else { return }
        losses[targetIndex].overflowCount += evicted.overflowCount
        losses[targetIndex].coalescedCount += evicted.coalescedCount
    }

    private func smallestGenerationIndex() -> Int? {
        losses.indices.min { losses[$0].generation < losses[$1].generation }
    }
}
