import Foundation

/// A FIFO event pipe from an arbitrary (possibly actor-isolated, possibly non-isolated) producer
/// to exactly one consumer.
///
/// It exists because `ControlSessionManager` deliberately emits **ordered pairs** of events —
/// `.pairingSucceeded` then `.connected`; `.peerTrusted` then `.connected` — and the RideLink
/// trust gate (`SessionGate`) depends on that order surviving delivery: `SessionGate` reads
/// `.connected` differently depending on whether the session has already left `.pairing`, so
/// consuming `.connected` before the event that preceded it would misread an authenticated
/// connection as one still awaiting SAS confirmation, or worse, the reverse.
///
/// Wrapping each event in its own unstructured `Task { @MainActor in ... } ` (the pattern this
/// type replaces) only preserves the order in which those tasks are *created* — Swift makes no
/// guarantee that independently created tasks on the same executor also *run* in creation order.
/// `send` is called synchronously from the producer, in the order events occur; the single
/// consumer drains `stream` with one `for await` loop, so event N+1 is never handled before N.
///
/// `AsyncStream` is `@unchecked Sendable` unconditionally, and its `Continuation.yield` is safe to
/// call concurrently, so this type needs no lock of its own.
public final class OrderedEventChannel<Element: Sendable>: Sendable {
    public let stream: AsyncStream<Element>
    private let continuation: AsyncStream<Element>.Continuation

    public init() {
        var continuation: AsyncStream<Element>.Continuation!
        stream = AsyncStream { continuation = $0 }
        self.continuation = continuation
    }

    /// Enqueues `element` for the consumer. Safe to call from any isolation context, including
    /// concurrently. A call after `finish()` is a silent no-op (the standard `AsyncStream`
    /// contract) — which is exactly what makes an event from an already-torn-down session
    /// harmless rather than a stale mutation of whatever session replaced it.
    public func send(_ element: Element) {
        continuation.yield(element)
    }

    /// Ends the stream, so a `for await` over `stream` returns instead of waiting forever, and any
    /// `send` after this point is dropped. Idempotent.
    public func finish() {
        continuation.finish()
    }
}
