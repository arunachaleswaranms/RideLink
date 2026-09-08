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
/// `@unchecked Sendable` with one `NSLock` covering every access to all three fields, for the same
/// reason `FakeMonotonicClock` is: `offer` must be callable synchronously from a non-isolated
/// producer, which an `actor` cannot serve.
final class Phase5FrameQueue<Element: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [Element] = []
    /// The parked consumer, if any. Only ever set while `items` is empty, which is what lets `offer`
    /// hand a frame straight across without touching the buffer.
    private var waiter: CheckedContinuation<Element?, Never>?
    private var finished = false
    private var overflows = 0
    private var coalesces = 0

    /// Injectable so a deterministic test can force the edge at 1 or 2 rather than racing 256 frames.
    let capacity: Int
    private let kindOf: @Sendable (Element) -> Phase5FrameKind
    /// The latest-wins family a frame belongs to, or nil for a command. Two frames coalesce only
    /// when their keys are equal, so a `POSITION_REPORT` never supersedes a `PLAYBACK_STATE`.
    private let coalesceKeyOf: @Sendable (Element) -> String?

    init(
        capacity: Int,
        kindOf: @escaping @Sendable (Element) -> Phase5FrameKind,
        coalesceKeyOf: @escaping @Sendable (Element) -> String?
    ) {
        self.capacity = capacity
        self.kindOf = kindOf
        self.coalesceKeyOf = coalesceKeyOf
    }

    var count: Int { lock.withLock { items.count } }

    /// Cumulative admission statistics, read by the **consumer** rather than reported to the
    /// producer.
    ///
    /// That direction is deliberate. `offer` is called from the control read loop, which cannot
    /// suspend into an actor and must not do work; and reading the counters at the top of each drain
    /// iteration puts the observation exactly where it has to be — *before* the next frame is
    /// dispatched, so a halt takes effect ahead of any command that follows a refusal rather than
    /// one frame late.
    var stats: Stats { lock.withLock { Stats(overflowCount: overflows, coalescedCount: coalesces) } }

    /// True when a consumer is parked waiting for work — which, because a waiter is only ever stored
    /// while the buffer is empty, means **the consumer has finished dispatching everything offered so
    /// far**. That is a genuine liveness fact worth being able to read, and it is the exact signal a
    /// test needs to know the ingress is idle instead of guessing how many scheduler turns a frame
    /// takes.
    var isConsumerWaiting: Bool { lock.withLock { waiter != nil } }

    struct Stats: Sendable, Equatable {
        let overflowCount: Int
        let coalescedCount: Int
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
                    coalesces += 1
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
                overflows += 1
            }
        } else {
            overflows += 1
        }
        lock.unlock()
        // Resumed outside the lock: resuming a continuation can run the consumer synchronously, and
        // that consumer may call straight back into `count`.
        if let (continuation, value) = handoff { continuation.resume(returning: value) }
        return admission
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
}
