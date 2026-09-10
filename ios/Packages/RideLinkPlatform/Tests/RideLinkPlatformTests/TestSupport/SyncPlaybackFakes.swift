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

    /// ADR-024 Amendment A2 Finding C: what the authenticated transport answers. `PlaybackRelay.send`
    /// returns false when there is no authenticated writer or the write throws, and A2 exists because
    /// the drain used to count that as a send.
    private var sendResult = true
    /// Amendment A2 Finding B: parks the single outbound consumer **inside** a write, so a test can
    /// land a session boundary strictly between one frame reaching the wire and the next being
    /// considered — the real "socket is slow, backlog exists" shape.
    private var sendGate: CheckedContinuation<Void, Never>?
    private var sendGateArmed = false
    /// The authentication generation live at the instant each frame was actually written.
    private(set) var sentGenerations: [Int64] = []

    nonisolated var channel: any SyncPlaybackChannel { FakeChannel(session: self) }

    func currentAuthGeneration() async -> Int64 { generation }

    func sessionClockEstimate() async -> SessionClockEstimate? { clock }

    func rttP95Us() async -> Int64? { rtt }

    func setGeneration(_ value: Int64) { generation = value }

    func setClock(_ value: SessionClockEstimate?) { clock = value }

    /// The leader's `LEAD = max(120 ms, 4 x rtt_p95)` comes from here, so a test that needs one
    /// session's deadlines to outlive the next session's work sets it (ADR-024 Amendment A3).
    func setRtt(_ value: Int64?) { rtt = value }

    func record(_ message: any Sendable) { sent.append(message) }

    func clearSent() {
        sent.removeAll()
        sentGenerations.removeAll()
    }

    func attach(playback: (any PlaybackSink)?) { playbackSink = playback }

    func attach(queue: (any QueueSink)?) { queueSink = queue }

    func deliver(_ message: PlaybackMessage) { playbackSink?.submit(message, generation: generation) }

    func deliver(_ message: QueueMessage) { queueSink?.submit(message, generation: generation) }

    /// Delivers a frame tagged with a generation the caller chooses — used to prove that a frame
    /// dispatched under a session that has since ended is inert.
    func deliver(_ message: PlaybackMessage, generation: Int64) { playbackSink?.submit(message, generation: generation) }

    func playbackMessages() -> [PlaybackMessage] { sent.compactMap { $0 as? PlaybackMessage } }

    func queueMessages() -> [QueueMessage] { sent.compactMap { $0 as? QueueMessage } }

    func setSendResult(_ value: Bool) { sendResult = value }

    /// Arms the write gate. Every subsequent write parks until `releaseSendGate`.
    func armSendGate() { sendGateArmed = true }

    func releaseSendGate() {
        sendGateArmed = false
        let parked = sendGate
        sendGate = nil
        parked?.resume()
    }

    var isSendGateParked: Bool { sendGate != nil }

    /// `PlaybackRelay.write`, modelled exactly: park where the socket would, then refuse the frame
    /// unless the generation that authorised it is still the live one (Amendment A2 Finding B). A
    /// real relay resolves the writer and the `session_id` for that generation and writes to *that*
    /// connection; refusing here is the same guarantee expressed the way a fake can.
    fileprivate func write(_ message: any Sendable, authorizingGeneration: Int64) async -> Bool {
        if sendGateArmed {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                sendGate = continuation
            }
        }
        guard sendResult else { return false }
        guard authorizingGeneration == generation else { return false }
        sent.append(message)
        sentGenerations.append(generation)
        return true
    }

    struct FakeChannel: SyncPlaybackChannel {
        let session: FakeSyncSession

        func setPlaybackSink(_ sink: (any PlaybackSink)?) async { await session.attach(playback: sink) }

        func setQueueSink(_ sink: (any QueueSink)?) async { await session.attach(queue: sink) }

        @discardableResult
        func send(_ message: PlaybackMessage, authorizingGeneration: Int64) async -> Bool {
            await session.write(message, authorizingGeneration: authorizingGeneration)
        }

        @discardableResult
        func send(_ message: QueueMessage, authorizingGeneration: Int64) async -> Bool {
            await session.write(message, authorizingGeneration: authorizingGeneration)
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
    /// The local monotonic instant of the **first** `start`, for the two-peer test's start-error
    /// measurement. Only the first: a later drift correction must not move the figure.
    private(set) var firstStartAtMonoUs: Int64?
    private var monotonicNowUs: (@Sendable () -> Int64)?

    /// Supplied only by the two-peer test, which needs the instant a start happened rather than just
    /// the fact that it did.
    func stampStartsWith(_ clock: @escaping @Sendable () -> Int64) { monotonicNowUs = clock }

    func playerState() async -> PlayerState {
        if stateGateArmed {
            if stateGateSkips > 0 {
                stateGateSkips -= 1
            } else {
                stateGateArmed = false
                await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                    stateGateContinuation = continuation
                }
            }
        }
        return state
    }

    // MARK: - The player-state gate (ADR-024 Amendment A2 Finding E)

    private var stateGateArmed = false
    private var stateGateSkips = 0
    private var stateGateContinuation: CheckedContinuation<Void, Never>?

    /// Parks a `playerState()` read after letting `skipping` of them through.
    ///
    /// `emitPlaybackState…` awaits `playerState()` immediately before its ownership proof and its
    /// enqueue, so parking that read is what puts a session or epoch boundary strictly *inside* the
    /// window Amendment A2 Finding E is about. On an actor every `await` is a re-entrancy point, so
    /// this is the real interleaving rather than a stand-in for it.
    func armStateGate(skipping: Int) {
        stateGateArmed = true
        stateGateSkips = skipping
    }

    func releaseStateGate() {
        stateGateArmed = false
        let parked = stateGateContinuation
        stateGateContinuation = nil
        parked?.resume()
    }

    var isStateGateParked: Bool { stateGateContinuation != nil }

    func setState(_ value: PlayerState) { state = value }

    func clearCalls() { calls.removeAll() }

    func prepare(content: SyncPlayableContent, positionMs: Int64) async {
        await record(.prepare(content.contentHash, positionMs))
    }

    func start() async {
        if firstStartAtMonoUs == nil { firstStartAtMonoUs = monotonicNowUs?() }
        await record(.start)
    }

    func pause() async { await record(.pause) }

    func seek(positionMs: Int64) async { await record(.seek(positionMs)) }

    func setRate(_ rate: Double) async { await record(.setRate(rate)) }

    func stop() async { await record(.stop) }

    // MARK: - The gate (ADR-024 Amendment A1 Finding F)

    private var gateContinuation: CheckedContinuation<Void, Never>?
    private var gateMatches: (@Sendable (Call) -> Bool)?

    /// Parks the **first** matching player call, *after* it has been recorded, so a test can land a
    /// supersession strictly inside it. Finding F is exactly about what happens after such a call
    /// returns, and a gate is the only way to assert it deterministically.
    func gateCalls(matching predicate: @escaping @Sendable (Call) -> Bool) { gateMatches = predicate }

    func releaseGate() {
        let parked = gateContinuation
        gateContinuation = nil
        gateMatches = nil
        parked?.resume()
    }

    var isGateParked: Bool { gateContinuation != nil }

    private func record(_ call: Call) async {
        calls.append(call)
        guard gateMatches?(call) == true else { return }
        gateMatches = nil
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            gateContinuation = continuation
        }
    }
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

    private var availabilityObserver: (@Sendable () -> Void)?

    func observeAvailability(_ onAvailabilityChanged: @escaping @Sendable () -> Void) async {
        availabilityObserver = onAvailabilityChanged
    }

    /// The Phase 4 seam a test drives: mark content verified-locally and fire the same notification
    /// `SharedLibraryCoordinator` fires after a successful `TransferCacheRepository.commit`.
    func completeTransfer(_ contentHash: ContentHash) {
        localHashes.insert(contentHash.value)
        availabilityObserver?()
    }

    /// The peer half: it reported verifying a transfer we served (ADR-024 §7).
    func peerVerified(_ contentHash: ContentHash) {
        peerHashes.insert(contentHash.value)
        availabilityObserver?()
    }

    /// A transfer that failed leaves availability exactly as it was, and still notifies.
    func failTransfer() {
        availabilityObserver?()
    }
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
