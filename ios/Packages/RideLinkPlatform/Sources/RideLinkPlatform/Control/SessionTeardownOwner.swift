import Foundation

/// **The one place a session's teardown runs, and the one thing that says when it is over.**
///
/// `SessionFsm` has had `ENDING -> IDLE` on `.teardownComplete` since Phase 1a and nothing in the app
/// ever emitted it (`docs/STATUS.md` §4 problem 53), so a session that ended stayed ended until the
/// user force-quit. Emitting it is easy; emitting it *truthfully* is the whole problem, because the
/// event's name is an ownership claim:
///
/// > **`.teardownComplete` may be emitted only when every effect owned by the ending session is
/// > complete, and no continuation from that session can subsequently mutate coordinator, relay,
/// > voice, playback, discovery or control-session state.**
///
/// That is what this type is for. It holds the **latest** teardown, so:
///
/// 1. ``retire(_:)`` chains — a teardown waits for the previous one before it starts, so two of them
///    can never interleave their steps on the one shared `ControlSessionManager`;
/// 2. ``pending`` is awaitable — a *successor* session awaits it before touching anything shared, so
///    "Session B cannot start until Session A is terminal" is a structural property rather than a
///    claim about which `Task` happens to be scheduled first.
///
/// **Cancellation is not completion.** `Task.cancel()` only *asks*; `defer` blocks, `NonCancellable`
/// -equivalent work and every `withCheckedContinuation`-bridged platform callback still run (ADR-024
/// Amendment A3 records what that cost Phase 5). Every caller therefore awaits `task.value` after
/// cancelling — awaiting is the proof, and cancelling is only what makes the proof arrive promptly.
///
/// `com.ridelink.app.session.SessionTeardownOwner` is the mirror. It lives beside the Android
/// coordinator because `:app` has unit tests that drive it directly; this one lives in the Swift
/// *package* rather than beside `ios/RideLink/SessionCoordinator.swift` because the Xcode project
/// still has no test target over the app's own Swift sources (`docs/STATUS.md` §4 problem 22), and
/// an untestable ownership primitive is exactly the wrong thing to have.
@MainActor
public final class SessionTeardownOwner {
    /// The most recent teardown, or nil if no session has ever been retired. Await it — do not
    /// inspect it: "has it completed" is a question whose answer can change between asking and
    /// acting on it, and `await task.value` is the form that cannot.
    public private(set) var pending: Task<Void, Never>?

    public init() {}

    /// Runs `body` as **the** teardown of the session being retired, after any earlier teardown has
    /// finished.
    ///
    /// The returned task completes only when `body` has returned, so awaiting it is exactly the
    /// guarantee at the top of this file. Callers must have already detached, *synchronously*,
    /// everything the retiring session owns — this runs after a suspension, and anything it read from
    /// live state at that point could belong to a successor.
    @discardableResult
    public func retire(_ body: @escaping @MainActor () async -> Void) -> Task<Void, Never> {
        let previous = pending
        let task = Task { @MainActor in
            await previous?.value
            await body()
        }
        pending = task
        return task
    }
}
