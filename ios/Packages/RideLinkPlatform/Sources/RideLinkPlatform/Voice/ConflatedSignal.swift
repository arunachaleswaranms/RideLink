import Foundation

/// A conflated wake-up signal: at most one pending "there is work available" notification between
/// drains, no matter how many times `signal()` is called while nothing is consuming.
///
/// This is the doorbell `VoiceController` rings on every `VoiceInputMailbox.offer` -- not an event in
/// its own right, just "go look at the mailbox." `OrderedEventChannel` (`Control/OrderedEventChannel.swift`)
/// is deliberately **not** reused here even though it is the existing FIFO event pipe on this
/// platform: that type exists because `ControlSessionManager` emits `.pairingSucceeded` then
/// `.connected` (and `.peerTrusted` then `.connected`) as **ordered pairs**, and `SessionGate` depends
/// on that order surviving delivery -- every one of `OrderedEventChannel`'s sends is a distinct event
/// that must be individually preserved. A doorbell has no such requirement: it only ever means "there
/// is work available," not "this represents a distinct occurrence," so collapsing a flood of rings
/// into one pending wake-up loses nothing `VoiceController`'s single drain-to-empty consumer loop
/// needs -- `drainMailbox()` already empties every bounded lane on each wake, so a second, third, or
/// hundred-thousandth ring before the first is consumed carries no information the first one did not.
///
/// Before this type existed, `VoiceController`'s doorbell **was** an `OrderedEventChannel<Void>`,
/// which wraps an `AsyncStream` with the default **unbounded** buffering policy. `VoiceInputMailbox`
/// itself was already bounded (ADR-020 Amendment A2), but every lane's `offer` unconditionally rang
/// that unbounded doorbell too -- so a flood of authenticated `VOICE_*` traffic could still grow an
/// arbitrarily large backlog of `Void` wake-ups sitting *behind* the now-bounded mailbox, even though
/// no single wake-up carried any payload. This type closes that gap directly: `AsyncStream`'s
/// `.bufferingNewest(1)` policy keeps at most one buffered element between drains, so however many
/// times `signal()` is called before `stream`'s single consumer is ready, it delivers no more than one
/// pending wake-up (Android's mirror, `Channel<Unit>(Channel.CONFLATED)`, is the same primitive by
/// the standard library's own name).
///
/// `signal`/`finish` are the same contract `OrderedEventChannel.send`/`finish` already give: safe to
/// call from any isolation context, including concurrently and without ever creating a `Task`, and a
/// call to `signal()` after `finish()` is a silent no-op -- so a stale ring from an
/// already-torn-down controller is harmless rather than a leaked wake source that could ever restart
/// a finished consumer loop. Each `VoiceController` owns exactly one, created fresh in its
/// initializer, so a new controller never inherits a signal a previous session already finished.
public final class ConflatedSignal: Sendable {
    public let stream: AsyncStream<Void>
    private let continuation: AsyncStream<Void>.Continuation

    public init() {
        var continuation: AsyncStream<Void>.Continuation!
        stream = AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation = $0 }
        self.continuation = continuation
    }

    /// Rings the doorbell. Never blocks, never suspends, never allocates a `Task` -- safe to call from
    /// the control read loop, a WebRTC callback, or the UI, including concurrently from several of
    /// them at once.
    public func signal() {
        continuation.yield(())
    }

    /// Ends the stream, so a `for await` over `stream` returns instead of waiting forever, and any
    /// `signal()` after this point is dropped. Idempotent.
    public func finish() {
        continuation.finish()
    }
}
