import Foundation
import RideLinkCore

/// One platform audio notification, reduced to a `Sendable` value inside the callback that produced it.
///
/// A `Notification` is not `Sendable`, and the values below are the only parts that may leave the route
/// layer anyway: ADR-016 forbids a platform route description — a port name, a device name — from
/// travelling any further, so reducing at the boundary is both a concurrency requirement and the privacy
/// rule.
public enum AudioSessionSignal: Sendable, Equatable {
    case routeChanged(reason: AudioRouteChangeReason)
    case interruptionBegan
    /// `shouldResume` is the platform's own option, read rather than assumed (TEST_PLAN IA-06).
    case interruptionEnded(shouldResume: Bool)
    case mediaServicesReset

    /// Which slot this signal occupies. See `AudioSessionSignalBox` for why one slot per kind is safe.
    var kind: Kind {
        switch self {
        case .routeChanged: return .routeChanged
        case .interruptionBegan, .interruptionEnded: return .interruption
        case .mediaServicesReset: return .mediaServicesReset
        }
    }

    /// Drain order, and it is a safety order rather than an arbitrary one: a reset invalidates every
    /// audio object this process holds, so it is applied before anything that would act on one.
    enum Kind: Int, CaseIterable {
        case mediaServicesReset
        case interruption
        case routeChanged
    }
}

/// The one ordered, bounded path from `NotificationCenter` into `IosVoiceAudioSession`.
///
/// ### Why this exists rather than a `Task` per notification
///
/// A `Task` per callback preserves the order the notifications were *created* in, not the order they
/// *run* in — which is exactly the bug STATUS §2h fixed for control events, and it matters here for the
/// same reason: an interruption ending and a route change arriving together must not be applied
/// backwards, or the session ends up reactivated against a route that has since moved. One stream with
/// one consumer inside the actor is what makes the order real (this phase's brief §39).
///
/// ### Why coalescing by kind is safe here
///
/// Every signal answers "what is the platform's audio state **now**", and the handler re-derives the
/// route from `AVAudioSession.currentRoute` when it applies one. So two route changes arriving before a
/// drain are not two facts to preserve — the newer one describes the same current reality. That is what
/// makes the box **bounded by construction** at one slot per `AudioSessionSignal.Kind` (this phase's
/// brief §38): there is no capacity to exceed and no overflow path to get wrong.
///
/// A media-services reset gets its own slot precisely so it can never be coalesced away by a route
/// change that follows it — it is the one signal whose loss would leave the app holding invalid audio
/// objects and believing they were fine.
public final class AudioSessionSignalBox: @unchecked Sendable {
    private struct State {
        var slots: [AudioSessionSignal.Kind: AudioSessionSignal] = [:]
        var continuation: AsyncStream<AudioSessionSignal>.Continuation?
        var finished = false
    }

    // `@unchecked Sendable` confined to state that is only ever touched under `lock` — the same
    // discipline `VoiceInputMailboxBox` and `SSLInitLatch` follow in this module.
    private let lock = NSLock()
    private var state = State()

    public let stream: AsyncStream<AudioSessionSignal>

    public init() {
        var continuation: AsyncStream<AudioSessionSignal>.Continuation!
        // Unbounded is safe *because the box in front of it is bounded*: `offer` yields at most one
        // element per kind between drains, so at most `AudioSessionSignal.Kind.allCases.count` elements
        // can be in flight at once however fast the platform produces notifications.
        stream = AsyncStream(bufferingPolicy: .unbounded) { continuation = $0 }
        state.continuation = continuation
    }

    /// Offers one signal. Safe to call from any context — including a `NotificationCenter` callback on an
    /// arbitrary queue — and it never blocks, never suspends and never allocates a `Task`.
    public func offer(_ signal: AudioSessionSignal) {
        lock.lock()
        if state.finished {
            lock.unlock()
            return
        }
        let alreadyPending = state.slots[signal.kind] != nil
        state.slots[signal.kind] = signal
        let continuation = state.continuation
        // Only yield when this kind had no pending value: the consumer drains by kind order, so a second
        // yield for the same kind would deliver a duplicate of whatever the newest value turns out to be.
        let shouldYield = !alreadyPending
        lock.unlock()
        if shouldYield { continuation?.yield(signal) }
    }

    /// Takes the newest value for `signal`'s kind, which is what the consumer must act on rather than the
    /// element it was handed — that element may have been superseded while it waited.
    public func take(_ delivered: AudioSessionSignal) -> AudioSessionSignal? {
        lock.lock()
        defer { lock.unlock() }
        return state.slots.removeValue(forKey: delivered.kind)
    }

    /// Ends the stream, so a `for await` over it returns and any later `offer` is a silent no-op — a
    /// stale notification from an already-closed session cannot restart a finished consumer.
    public func finish() {
        lock.lock()
        let continuation = state.continuation
        state.finished = true
        state.continuation = nil
        state.slots.removeAll()
        lock.unlock()
        continuation?.finish()
    }
}
