import Foundation
import RideLinkCore

@testable import RideLinkPlatform

// Deterministic fakes for every Phase 5 port, mirroring Android's `app.sync.SyncPlaybackFakes`.
// Nothing here reads a real clock, opens a socket or touches a decoder — which is what makes the
// assertions about *ordering* and *lifetime* rather than about timing luck (brief §47/§50).

/// Fabricated identifiers only; no real `peer_id` and no real file's hash appears in these tests.
enum SyncTestValues {
    static let leaderPeerId = PeerId("a3f1000000000001")
    static let followerPeerId = PeerId("b7c1000000000002")

    static func hash(_ seed: Int) -> ContentHash { ContentHash("sha256:" + String(format: "%064x", seed)) }

    static func ulid(_ seed: Int) -> String {
        String(("01J9Z4M0Q7XK2V8R3T6Y1N" + String(format: "%04d", seed)).prefix(26))
            .padding(toLength: 26, withPad: "0", startingAt: 0)
    }

    static func content(_ seed: Int) -> SyncPlayableContent {
        SyncPlayableContent(
            contentHash: hash(seed),
            localEntryId: LocalEntryId(UUID().uuidString.lowercased()),
            location: LocalTrackLocation(uri: "file:///tmp/track-\(seed).m4a"),
            title: "Track \(seed)",
            artist: "Artist"
        )
    }
}

/// A controllable `SyncSessionPort`: the session generation, the clock and the wire are all writable.
actor FakeSyncSession: SyncSessionPort {
    private(set) var sent: [any Sendable] = []
    private var generation: Int64 = 1
    private var clock: SessionClockEstimate?
    private var rtt: Int64? = 8_000
    private var playbackSink: (any PlaybackSink)?
    private var queueSink: (any QueueSink)?

    nonisolated var channel: any SyncPlaybackChannel { FakeChannel(session: self) }

    func currentAuthGeneration() async -> Int64 { generation }

    func sessionClockEstimate() async -> SessionClockEstimate? { clock }

    func rttP95Us() async -> Int64? { rtt }

    func setGeneration(_ value: Int64) { generation = value }

    func setClock(_ value: SessionClockEstimate?) { clock = value }

    func record(_ message: any Sendable) { sent.append(message) }

    func clearSent() { sent.removeAll() }

    func attach(playback: (any PlaybackSink)?) { playbackSink = playback }

    func attach(queue: (any QueueSink)?) { queueSink = queue }

    func deliver(_ message: PlaybackMessage) { playbackSink?.submit(message) }

    func deliver(_ message: QueueMessage) { queueSink?.submit(message) }

    func playbackMessages() -> [PlaybackMessage] { sent.compactMap { $0 as? PlaybackMessage } }

    func queueMessages() -> [QueueMessage] { sent.compactMap { $0 as? QueueMessage } }

    struct FakeChannel: SyncPlaybackChannel {
        let session: FakeSyncSession

        func setPlaybackSink(_ sink: (any PlaybackSink)?) async { await session.attach(playback: sink) }

        func setQueueSink(_ sink: (any QueueSink)?) async { await session.attach(queue: sink) }

        @discardableResult
        func send(_ message: PlaybackMessage) async -> Bool {
            await session.record(message)
            return true
        }

        @discardableResult
        func send(_ message: QueueMessage) async -> Bool {
            await session.record(message)
            return true
        }
    }
}

/// Records every player call in order — the whole assertion surface for "what did the audio do".
actor FakeSyncPlayer: SyncPlayerPort {
    enum Call: Equatable, Sendable {
        case prepare(ContentHash, Int64)
        case start
        case pause
        case seek(Int64)
        case setRate(Double)
        case stop
    }

    private(set) var calls: [Call] = []
    private var state = PlayerState()

    func playerState() async -> PlayerState { state }

    func setState(_ value: PlayerState) { state = value }

    func clearCalls() { calls.removeAll() }

    func prepare(content: SyncPlayableContent, positionMs: Int64) async { calls.append(.prepare(content.contentHash, positionMs)) }

    func start() async { calls.append(.start) }

    func pause() async { calls.append(.pause) }

    func seek(positionMs: Int64) async { calls.append(.seek(positionMs)) }

    func setRate(_ rate: Double) async { calls.append(.setRate(rate)) }

    func stop() async { calls.append(.stop) }
}

/// Scripted local/peer availability, and a record of every transfer Phase 5 asked Phase 4 for.
actor FakeSyncContent: SyncContentPort {
    private var localHashes: Set<String> = []
    private var peerHashes: Set<String> = []
    private(set) var transferRequests: [ContentHash] = []

    func addLocal(_ hash: ContentHash) { localHashes.insert(hash.value) }

    func addPeer(_ hash: ContentHash) { peerHashes.insert(hash.value) }

    func resolve(_ contentHash: ContentHash) async -> SyncPlayableContent? {
        guard localHashes.contains(contentHash.value) else { return nil }
        return SyncPlayableContent(
            contentHash: contentHash,
            localEntryId: LocalEntryId(UUID().uuidString.lowercased()),
            location: LocalTrackLocation(uri: "file:///tmp/\(contentHash.hex).m4a"),
            title: nil,
            artist: nil
        )
    }

    func peerHasContent(_ contentHash: ContentHash) async -> Bool { peerHashes.contains(contentHash.value) }

    func requestTransfer(_ contentHash: ContentHash) async { transferRequests.append(contentHash) }
}

/// A virtual monotonic clock plus the sleeper that waits on it. Time only ever moves because a test
/// moved it, so every scheduling assertion is a statement about the algorithm rather than about how
/// busy the machine was.
///
/// Lock-backed and `@unchecked Sendable` rather than an actor, for one specific reason: the
/// coordinator takes a **synchronous** `monotonicNowUs` closure (it must, because every real clock
/// read on both platforms is synchronous), and an actor cannot serve one. The lock covers every
/// access to both fields, which is what makes the unchecked conformance true rather than hoped for.
final class FakeMonotonicClock: @unchecked Sendable, SyncDeadlineSleeper {
    private let lock = NSLock()
    private var nowUs: Int64
    private var waiters: [(deadline: Int64, continuation: CheckedContinuation<Void, Never>)] = []

    init(startUs: Int64 = 1_000_000) { nowUs = startUs }

    func now() -> Int64 { lock.withLock { nowUs } }

    func pendingDeadlines() -> [Int64] { lock.withLock { waiters.map(\.deadline) } }

    func sleep(untilLocalMonoUs deadlineUs: Int64) async {
        await withCheckedContinuation { continuation in
            let alreadyPassed = lock.withLock { () -> Bool in
                if deadlineUs <= nowUs { return true }
                waiters.append((deadlineUs, continuation))
                return false
            }
            if alreadyPassed { continuation.resume() }
        }
    }

    /// Advances the virtual clock and releases every waiter whose deadline has now passed.
    func advance(to instantUs: Int64) {
        let due: [CheckedContinuation<Void, Never>] = lock.withLock {
            nowUs = instantUs
            let ready = waiters.filter { $0.deadline <= instantUs }
            waiters.removeAll { $0.deadline <= instantUs }
            return ready.map(\.continuation)
        }
        for continuation in due { continuation.resume() }
    }
}

/// The route-transition supplier, flipped by a test.
actor FakeRouteState: SyncRouteStatePort {
    private var transitioning = false

    func set(_ value: Bool) { transitioning = value }

    func isRouteTransitioning() async -> Bool { transitioning }
}

/// Lets the actor's queued work drain before an assertion. Not a sleep against wall time: it yields
/// the cooperative pool repeatedly, which is what "the tasks this action spawned have run" means in
/// a structured-concurrency test.
func settle(_ rounds: Int = 40) async {
    for _ in 0 ..< rounds { await Task.yield() }
}
