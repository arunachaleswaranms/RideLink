import Foundation
import RideLinkCore

// The Phase 5 coordinator-level seam, mirroring Android's `app.sync.SyncPlaybackPorts` exactly and
// for the same reason: `SyncPlaybackCoordinator` depends on collaborators — the app target's
// `MusicCoordinator`, its library and its Phase 4 cache — that have no reason to know a test double
// exists, so its **exact** call surface on each is a narrow protocol with a zero-behaviour-change
// adapter alongside.
//
// **Why this lives in `RideLinkPlatform` and not in the app target.** The app target has no test
// target (`docs/STATUS.md` §4 problem 20), so a coordinator written there would be untestable on
// this platform while its Android twin has 47 tests. Everything here is platform-free except the
// adapters, and the adapters are what stay in the app.

/// One thing this device can actually play, already resolved to an openable location.
public struct SyncPlayableContent: Sendable, Equatable {
    /// ADR-005: the authoritative identity. Never a filename, never a `quick_id`.
    public let contentHash: ContentHash
    /// The local row identity the one existing player/queue keys on — a library row's id, or the
    /// opaque token minted for a Phase 4 verified-cache-only track. Never on the wire.
    public let localEntryId: LocalEntryId
    public let location: LocalTrackLocation
    public let title: String?
    public let artist: String?

    public init(contentHash: ContentHash, localEntryId: LocalEntryId, location: LocalTrackLocation, title: String?, artist: String?) {
        self.contentHash = contentHash
        self.localEntryId = localEntryId
        self.location = location
        self.title = title
        self.artist = artist
    }
}

/// Resolving a `content_hash` to something playable **on this device**, and what is known about the
/// peer's copy.
///
/// `resolve` returning nil is the whole of the local half of this phase's brief §19 gate: it is nil
/// unless the hash names a Phase 3 library row or a Phase 4 **verified, committed** cache entry —
/// never merely a download that reported complete.
public protocol SyncContentPort: Sendable {
    func resolve(_ contentHash: ContentHash) async -> SyncPlayableContent?

    /// Whether the connected peer is known to hold this content — the peer half of the brief §19
    /// gate. Session-scoped and cleared on every boundary, exactly like the peer's catalogue.
    func peerHasContent(_ contentHash: ContentHash) async -> Bool

    /// PROTOCOL §5 rule 4: a `PLAY` for a track this device lacks must not start, and must request
    /// the transfer — through the **existing** Phase 4 machinery. Phase 5 owns no transfer logic and
    /// no third cache (brief §20).
    func requestTransfer(_ contentHash: ContentHash) async

    /// Installs the one observer notified whenever **verified** availability changes — locally (a
    /// Phase 4 transfer committed) or on the peer (it reported verifying a transfer we served,
    /// ADR-024 §7). Amendment A1 Finding E: this is the seam that lets one press of Play survive a
    /// transfer, and it is deliberately a notification rather than a poll.
    ///
    /// It carries **no** payload: the coordinator holds at most one pending Play and re-asks
    /// `resolve`/`peerHasContent` for exactly that hash, so a hash argument would be a second
    /// source of truth about availability with nothing to gain. A later call replaces the observer.
    func observeAvailability(_ onAvailabilityChanged: @escaping @Sendable () -> Void) async
}

/// The one player/queue Phase 5 drives. Every method lands on the **existing** app-target
/// `MusicCoordinator`, its existing `LocalQueue`, its existing `AVAudioEnginePlayer` and its
/// existing `MPNowPlayingInfoCenter`/`MPRemoteCommandCenter` integration (brief §21).
public protocol SyncPlayerPort: Sendable {
    func playerState() async -> PlayerState

    /// ARCHITECTURE §7.2's pre-roll: load and seek, but do **not** start.
    ///
    /// Also brief §26's materialisation point. The authoritative shared queue is *not* copied into
    /// `LocalQueue` wholesale — only the item that is actually current becomes the local queue's one
    /// selected entry, which keeps Now Playing metadata correct without two queues that could
    /// disagree about an index. `NEXT`/`PREVIOUS` resolve from the *shared* queue (brief §25).
    func prepare(content: SyncPlayableContent, positionMs: Int64) async

    func start() async
    func pause() async
    func seek(positionMs: Int64) async
    /// ADR-004's rate-nudge tier. Always exactly 1.0 when correction ends (brief §38).
    func setRate(_ rate: Double) async
    func stop() async
}

/// `SyncPlaybackCoordinator`'s exact call surface on `PlaybackRelay`.
public protocol SyncPlaybackChannel: Sendable {
    func setPlaybackSink(_ sink: (any PlaybackSink)?) async
    func setQueueSink(_ sink: (any QueueSink)?) async
    @discardableResult func send(_ message: PlaybackMessage) async -> Bool
    @discardableResult func send(_ message: QueueMessage) async -> Bool
}

/// `SyncPlaybackCoordinator`'s exact call surface on `ControlSessionManager`: the Phase 5 channel,
/// the read-only live-session view, and the one session clock. Never the connection-management
/// surface, which stays `ControlSessionManager`'s alone.
///
/// Session **lifecycle** is deliberately not here. `ControlSessionManager.onEvent` is a single
/// mutable callback slot on this platform (unlike Android's multi-collector `SharedFlow`), and the
/// app's `SessionCoordinator` already owns it — so it forwards `.connected`/`.linkLost` explicitly,
/// exactly as it already does for `SharedLibraryCoordinator`.
public protocol SyncSessionPort: Sendable {
    var channel: any SyncPlaybackChannel { get }
    func currentAuthGeneration() async -> Int64
    func sessionClockEstimate() async -> SessionClockEstimate?
    /// The bounded RTT window's p95, available before the first offset estimate exists.
    func rttP95Us() async -> Int64?
}

/// Waiting until a local **monotonic** deadline. The one place Phase 5 touches real time, and the
/// one seam a test replaces to make every scheduling assertion deterministic (brief §47/§50).
public protocol SyncDeadlineSleeper: Sendable {
    func sleep(untilLocalMonoUs: Int64) async
}

/// Whether **either** peer currently reports `AUDIO_STATE.route_state: "transitioning"` (PROTOCOL
/// §4.4) — ARCHITECTURE §7.3's suspension condition for the drift ladder.
public protocol SyncRouteStatePort: Sendable {
    func isRouteTransitioning() async -> Bool
}

/// Zero-behaviour-change wrapper reaching the manager's `PlaybackRelay`.
///
/// The relay is fetched on every call rather than captured once, because `ControlSessionManager` is
/// an actor and reading a property off it requires an `await` the composition root (a synchronous
/// SwiftUI `init`) cannot perform. Fetching per call costs one actor hop and removes the need for
/// the app to hold a reference to an object it has no other use for.
public struct ControlSessionPlaybackChannel: SyncPlaybackChannel {
    private let manager: ControlSessionManager

    public init(manager: ControlSessionManager) { self.manager = manager }

    public func setPlaybackSink(_ sink: (any PlaybackSink)?) async { await manager.playbackRelay().setPlaybackSink(sink) }

    public func setQueueSink(_ sink: (any QueueSink)?) async { await manager.playbackRelay().setQueueSink(sink) }

    @discardableResult
    public func send(_ message: PlaybackMessage) async -> Bool { await manager.playbackRelay().send(message) }

    @discardableResult
    public func send(_ message: QueueMessage) async -> Bool { await manager.playbackRelay().send(message) }
}

/// Zero-behaviour-change wrapper — the app composition root's production call site.
public struct ControlSessionSyncPort: SyncSessionPort {
    private let manager: ControlSessionManager
    public let channel: any SyncPlaybackChannel

    public init(manager: ControlSessionManager) {
        self.manager = manager
        channel = ControlSessionPlaybackChannel(manager: manager)
    }

    public func currentAuthGeneration() async -> Int64 { await manager.currentAuthGeneration }

    public func sessionClockEstimate() async -> SessionClockEstimate? { await manager.sessionClockEstimate() }

    public func rttP95Us() async -> Int64? { await manager.sessionClockRttP95Us() }
}

/// The one place Phase 5 touches real time: waiting on the **monotonic** clock until a deadline.
///
/// `Task.sleep` rather than a spin: a busy-wait would burn a core for up to two seconds of
/// scheduling lead on a phone in someone's pocket, and the scheduling error it would buy back is far
/// below what the decoder and two Bluetooth hops contribute anyway. The residual error is measured
/// rather than assumed — `SyncPlaybackDiagnostics.lastScheduleErrorUs` records `actual - deadline`
/// for every scheduled start, and it is a *software* figure that says nothing about audible
/// alignment (brief §23).
public struct MonotonicDeadlineSleeper: SyncDeadlineSleeper {
    private let monotonicNowUs: @Sendable () -> Int64

    public init(monotonicNowUs: @escaping @Sendable () -> Int64) {
        self.monotonicNowUs = monotonicNowUs
    }

    public func sleep(untilLocalMonoUs deadlineUs: Int64) async {
        while true {
            let remainingUs = deadlineUs - monotonicNowUs()
            if remainingUs <= 0 { return }
            // Long waits sleep in coarse steps; the last stretch is stepped finely so a scheduler
            // that overshoots a single long sleep cannot cost the whole margin.
            let stepUs = min(remainingUs, Self.coarseStepUs)
            try? await Task.sleep(nanoseconds: UInt64(max(stepUs, 1)) * 1_000)
            if Task.isCancelled { return }
        }
    }

    private static let coarseStepUs: Int64 = 20_000
}
