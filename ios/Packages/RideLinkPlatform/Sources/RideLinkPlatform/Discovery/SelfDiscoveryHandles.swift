/// Tracks which discovery handle(s) this process currently considers "itself", across a `dh`
/// rotation (this session's brief §8). Mirrors Android's `SelfDiscoveryHandles`.
///
/// A momentary self-discovery race exists because rotating the advertised `dh` is asynchronous
/// at the OS level: `rotate` flips the "current" handle synchronously, but the previous
/// advertisement can still be resolvable — via this process's own `NWBrowser`, which has its own
/// internal caching/debounce — for a short window afterward. If self-filtering only ever compared
/// against the new "current" handle during that window, a resolution carrying the *old* handle
/// would fail the self-check and RideLink could briefly discover itself as a peer. Keeping the
/// previous handle recognised as self until the transition is confirmed closed (`clearPrevious`)
/// avoids that.
///
/// Not `Sendable`: every real use is confined to `BonjourDiscovery`'s own serial `queue`, the same
/// invariant that type already documents for its other mutable state. Kept as a plain value here
/// so it is trivially constructible and testable with no queue/actor involved at all.
final class SelfDiscoveryHandles {
    private var current: String?
    private var previous: String?

    /// The handle this instance is currently advertising, or `nil` before the first `rotate`.
    var currentHandle: String? { current }

    /// Called when a new `dh` becomes active — the initial advertise, and every later rotation.
    func rotate(_ newHandle: String) {
        previous = current
        current = newHandle
    }

    /// Called once the transition window is considered closed — either an explicit OS
    /// confirmation (Android has one; iOS's `NWListener.service` reassignment does not, so
    /// `BonjourDiscovery` uses a bounded grace period instead) or the caller's own judgement that
    /// enough time has passed for the updated advertisement to have propagated.
    func clearPrevious() {
        previous = nil
    }

    /// `true` for the active handle *or* the immediately-previous one, during a transition.
    func isSelf(_ candidate: String) -> Bool {
        candidate == current || candidate == previous
    }

    /// Advertising stopped entirely: neither handle is self any more.
    func reset() {
        current = nil
        previous = nil
    }
}
