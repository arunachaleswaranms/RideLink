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

    /// Drain priority. `allCases` is declared in this exact order, and `AudioSessionSignalBox.poll()`
    /// walks it in order — a reset invalidates every audio object this process holds, so it is *always*
    /// applied before anything that would act on one, independent of arrival order (this phase's
    /// hardening pass: a raw `AsyncStream` only delivers in arrival order, which is not the same thing).
    enum Kind: Int, CaseIterable {
        case mediaServicesReset
        case interruption
        case routeChanged
    }
}

/// One signal plus the generation it was captured under.
///
/// The generation is stamped **at the platform-callback boundary** — inside the `NotificationCenter`
/// closure, at the moment the notification actually fired — and never re-derived later. That is the
/// whole point: `AudioSessionLifecycle.reduce`'s generation guard can only reject a stale callback if
/// the event it is handed still says which generation produced it. Reading `lifecycle.generation` fresh
/// when the signal is finally *processed* would stamp every event with whatever generation happens to be
/// current at that later moment, which defeats the guard by construction — a route-changed notification
/// queued before a media-services reset, drained after it, would silently be promoted to the new
/// generation instead of being recognised as stale.
public struct GeneratedAudioSessionSignal: Sendable, Equatable {
    public let generation: Int
    public let signal: AudioSessionSignal

    public init(generation: Int, signal: AudioSessionSignal) {
        self.generation = generation
        self.signal = signal
    }
}

/// The one bounded path from `NotificationCenter` into `IosVoiceAudioSession`, for one audio-session open
/// generation.
///
/// ### Why this is a doorbell + bounded slots, not a raw `AsyncStream`
///
/// A `Task` per callback preserves the order the notifications were *created* in, not the order they
/// *run* in — exactly the bug STATUS §2h fixed for control events. The original shape of this type fixed
/// that by draining a single `AsyncStream`, but an `AsyncStream` only delivers in **arrival** order, and
/// the doc comment on `AudioSessionSignal.Kind` had claimed a *safety* order (reset before anything that
/// would act on one) that arrival order does not actually provide: a reset that arrives fractionally
/// after a route-change notification would still be drained after it. This hardening pass makes the
/// safety order real: `offer` only rings a doorbell (`ConflatedSignal`, the same primitive
/// `VoiceController`'s mailbox already uses for this exact reason), and the consumer explicitly polls in
/// `AudioSessionSignal.Kind` priority order — `mediaServicesReset` before `interruption` before
/// `routeChanged` — regardless of which one was offered first.
///
/// ### Why coalescing by kind is still safe
///
/// Every signal answers "what is the platform's audio state **now**", and the handler re-derives the
/// route from `AVAudioSession.currentRoute` when it applies one. So two route changes arriving before a
/// drain are not two facts to preserve — the newer one describes the same current reality. That is what
/// makes the box **bounded by construction** at one slot per `AudioSessionSignal.Kind`: there is no
/// capacity to exceed and no overflow path to get wrong.
///
/// ### One box per open generation
///
/// `IosVoiceAudioSession` creates a fresh box (and a fresh doorbell, and a fresh consumer task) on every
/// `open()`, rather than holding one for its own lifetime. A box that outlives its `close()` is a box
/// that is dead for the rest of the process's life once `finish()` is called on it — `finish()` sets a
/// permanent flag with no way to un-set it — so reusing one across End Intercom → Start Intercom would
/// silently stop delivering route/interruption/reset notifications for every session after the first.
/// Recreating it per generation is what makes `finish()` "stale offers for *this* generation are no-ops"
/// rather than "stale offers forever."
public final class AudioSessionSignalBox: @unchecked Sendable {
    private struct State {
        var slots: [AudioSessionSignal.Kind: GeneratedAudioSessionSignal] = [:]
        var finished = false
    }

    // `@unchecked Sendable` confined to state that is only ever touched under `lock` — the same
    // discipline `VoiceInputMailboxBox` follows in this module.
    private let lock = NSLock()
    private var state = State()

    public init() {}

    /// Offers one signal, stamped with the generation captured at the call site — which must be the
    /// generation read at the moment the platform callback fired, not at some later processing time.
    /// Safe to call from any context — including a `NotificationCenter` callback on an arbitrary queue —
    /// and it never blocks, never suspends and never allocates a `Task`.
    public func offer(_ signal: AudioSessionSignal, generation: Int, doorbell: ConflatedSignal) {
        lock.lock()
        if state.finished {
            lock.unlock()
            return
        }
        // Coalesced by kind: a second offer for a kind already pending replaces it, since both describe
        // "the platform's state now" and the newer one is the truer answer.
        state.slots[signal.kind] = GeneratedAudioSessionSignal(generation: generation, signal: signal)
        lock.unlock()
        doorbell.signal()
    }

    /// Pops the highest-priority pending signal — `mediaServicesReset`, then `interruption`, then
    /// `routeChanged` — independent of the order they were offered in. Returns `nil` once every slot is
    /// empty, which is what lets the consumer drain-to-empty on every doorbell ring.
    public func poll() -> GeneratedAudioSessionSignal? {
        lock.lock()
        defer { lock.unlock() }
        for kind in AudioSessionSignal.Kind.allCases {
            if let value = state.slots.removeValue(forKey: kind) { return value }
        }
        return nil
    }

    /// Ends this generation's box: every pending slot is discarded (a signal still here describes a
    /// session that is being torn down) and every later `offer` is a silent no-op. A **new** box for the
    /// next generation is unaffected — this only poisons the instance it is called on.
    public func finish() {
        lock.lock()
        state.finished = true
        state.slots.removeAll()
        lock.unlock()
    }
}
