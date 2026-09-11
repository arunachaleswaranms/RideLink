import Foundation
import RideLinkCore
import XCTest

@testable import RideLinkPlatform

/// The regressions ADR-024 **Amendment A5** — the fifth Phase 5 closure audit — exists for.
///
/// A4 fenced the **player**: every `SyncPlayerPort` method became exactly one externally visible
/// effect, and `runOwnedSteps` re-proves ownership before each of them. A5 is the same question
/// asked about everything that is *not* the player. A coordinator that suspends inside
/// `sessionClockEstimate()`, `playerState()` or `isRouteTransitioning()` and then writes
/// `lastReceivedSeq`, `deferredEvents`, `driftState` or a diagnostics field has already corrupted
/// the session that replaced it — and the player call it may go on to make being correctly refused
/// afterwards does not undo that.
///
/// **A local mutation that has been authorised is not a local mutation that may still happen.**
///
/// Three sites had no post-suspension proof at all:
///
/// - `admitAuthoritativeCommand` — `await estimate()`, then `lastReceivedSeq`/`lastAppliedSeq`/
///   `deferredEvents`, so Session A's `command_seq` 50 became Session B's ordering floor and every
///   command Session B ever issued was then refused as stale by `CommandOrderGate` doing exactly
///   its job;
/// - `tickOnce` — `await player.playerState()`, then an outbound `POSITION_REPORT` enqueue and its
///   counters; and `await routeState.isRouteTransitioning()`, then `driftState` and six diagnostics
///   fields, so a dead session's samples became the live session's ADR-004 correction;
/// - `onPeerPositionReport` — which carried no generation at all, so after
///   `await player.playerState()` there was nothing it *could* prove.
///
/// And one site where the asynchronous proof alone is not enough: `stillCurrent`/`owns` must
/// `await session.currentAuthGeneration()` on this platform, and that `await` is itself an actor
/// re-entrancy point — the window A4 Finding D opened `ownsNow` for. A5 adds `stillCurrentNow` for
/// the operations that legitimately have no playback epoch yet, and pairs the two proofs.
///
/// ## Why Session B is sometimes a leader here
///
/// The inbound path is one ordered consumer by construction (A1 Finding C), so while Session A is
/// parked *inside* a frame's handler nothing else can be delivered — the queue is doing its job.
/// Where the parked operation is an inbound one, Session B is therefore established through the
/// **outbound** path instead, as a leader. A role that differs between sessions is not a contrivance:
/// ADR-010 recomputes it from the two `peer_id`s at every handshake.
///
/// ## What "fails against pre-A5 code" means here
///
/// Every case below was run against unmodified `902f3675` production sources with only this file and
/// the fakes' three new gates added; the recorded failures are in ADR-024 Amendment A5 §F. The one
/// exception is `…OwnSessionProofSuspends…`, which isolates the *synchronous* half of the proof: it
/// needs A5's asynchronous proof to be present in order for there to be a suspension to land in at
/// all, so its pre-fix run reverts exactly one thing — `stillCurrentNow` returning an unconditional
/// true — exactly as A4 did for the two compounds that lived below `SyncPlayerPort`.
///
/// Android is **structurally safe** for all three findings — `estimate()`, `player.playerState.value`,
/// `routeTransitioning()` and `onPeerPositionReport` are all synchronous there, so none of these
/// suspensions exists — and ADR-024 Amendment A5 §G records the reason rather than mirroring churn.
final class SyncPlaybackSessionStateAuditTests: XCTestCase {
    private var session: FakeSyncSession!
    private var player: FakeSyncPlayer!
    private var content: FakeSyncContent!
    private var clock: FakeMonotonicClock!
    private var routeState: FakeRouteState!
    private var coordinator: SyncPlaybackCoordinator!
    private var idSeed = 3_100

    /// `FakeMonotonicClock`'s starting instant, which is also every delivered command's
    /// `effective_at` — already past, so PROTOCOL §5 rule 2 applies it immediately.
    private static let anchorUs: Int64 = 1_000_000
    private static let trackDurationMs: Int64 = 3_600_000

    private static let hashA = SyncTestValues.hash(41)
    private static let hashB = SyncTestValues.hash(42)

    /// Session A's stolen authority, chosen far above anything Session B will ever reach so a
    /// contaminated `lastReceivedSeq` is unmistakable — and so Session B's own next `command_seq` is
    /// refused as stale rather than merely mis-ordered.
    private static let sessionASeq: Int64 = 50

    /// Deliberately nowhere near Session B's own timeline, so a stale peer report is visible.
    private static let stalePeerPositionMs: Int64 = 250_000

    /// Past ADR-004's nudge threshold and well inside its hard-seek one, so tier one fires.
    private static let nudgeDriftMs: Int64 = 60

    // MARK: - A5-IOS-1: authoritative admission parked in the session clock

    /// **A5-IOS-1.** Session A accepts an authoritative command and suspends inside the session
    /// clock read. Session B then authenticates and establishes its own ordering state. When
    /// Session A's clock read finally returns it must write **nothing**: not `lastReceivedSeq`, not
    /// `lastAppliedSeq`, not a deferred event, not one diagnostics field.
    ///
    /// The apply path's own proof (`applyAuthoritative`) is not a defence. It runs *after* the
    /// sequence numbers have been written, so it refuses a command whose damage is already done.
    func testAnOldSessionsParkedAdmissionNeverPoisonsTheNewSessionsOrdering() async {
        await build()
        await connectAsFollower(generation: 1)
        await parkSessionAsAdmissionInsideTheClockRead()

        await boundaryToLeader(generation: 2)
        await establishSessionBAsLeader()

        let before = await snapshot()
        await session.releaseClockGate()
        await awaitRetiredWorkSettled()

        await assertNothingMoved(since: before, because: "a retired admission may not order Session B")
        await assertSessionBStillCommands(nextSeq: 2)
    }

    /// **A5-IOS-1b.** The same suspension resolving to `PendingCommandGate`'s *defer* branch: the
    /// clock has gone untrustworthy by the time Session A's read returns. A retired command must not
    /// join the held authoritative stream of the session that replaced it — that stream is replayed
    /// in arrival order, so a Session-A `PLAY` in it would execute against a Session-B queue.
    func testAnOldSessionsParkedAdmissionNeverJoinsTheNewSessionsHeldStream() async {
        await build()
        await connectAsFollower(generation: 1)
        await parkSessionAsAdmissionInsideTheClockRead()

        await boundaryToLeader(generation: 2)
        await establishSessionBAsLeader()
        // The clock goes untrustworthy while Session A is still parked, so its resumed admission
        // takes the `defer` branch rather than the `apply` one.
        await setClock(ready: false)

        let before = await snapshot()
        await session.releaseClockGate()
        await awaitRetiredWorkSettled()

        let deferred = await coordinator.deferredEvents.count
        XCTAssertEqual(deferred, 0, "a retired command may not be held on Session B's behalf")
        await assertNothingMoved(since: before, because: "a retired admission may not defer into Session B")

        await setClock(ready: true)
        await assertSessionBStillCommands(nextSeq: 2)
    }

    // MARK: - A5-IOS-2: a cadence tick parked in the player state read

    /// **A5-IOS-2.** Session A's tick suspends inside `player.playerState()`. The frame it goes on to
    /// enqueue is correctly refused at the wire — `Phase5Outbound` carries Session A's generation —
    /// but *enqueueing and counting it* is already a Session-B effect, and A3 §D settled that
    /// "diagnostics only" is not an exemption.
    func testAnOldSessionsTickParkedInThePlayerStateReadEnqueuesNothingIntoTheNewSession() async {
        await build()
        await connectAsFollower(generation: 1)
        await startPlayingAsFollower(Self.hashA, seq: 1, generation: 1)

        await parkSessionAsTick(in: .playerState)

        await boundaryToFollower(generation: 2)
        await startPlayingAsFollower(Self.hashB, seq: 1, generation: 2)

        let before = await snapshot()
        await player.releaseStateGate()
        await awaitRetiredWorkSettled()

        await assertNothingMoved(since: before, because: "a retired tick may not report into Session B")
        await assertSessionBTicksNormally()
    }

    // MARK: - A5-IOS-3: a cadence tick parked in the route-state read

    /// **A5-IOS-3.** The second half of the same tick. `isRouteTransitioning()` is the last
    /// suspension before `driftState` and six diagnostics fields are written, and ADR-004's ladder is
    /// evaluated from values Session A sampled — so a retired tick resuming here re-states Session
    /// B's drift, its clock offset, its RTT and its correction from a dead session's measurements.
    func testAnOldSessionsTickParkedInTheRouteStateReadCorrectsNothingInTheNewSession() async {
        await build()
        await connectAsFollower(generation: 1)
        await startPlayingAsFollower(Self.hashA, seq: 1, generation: 1)

        await parkSessionAsTick(in: .routeState)

        await boundaryToFollower(generation: 2)
        await startPlayingAsFollower(Self.hashB, seq: 1, generation: 2)

        let before = await snapshot()
        let driftBefore = await coordinator.driftState
        await routeState.releaseGate()
        await awaitRetiredWorkSettled()

        let driftAfter = await coordinator.driftState
        XCTAssertEqual(driftAfter, driftBefore, "a retired tick may not move Session B's drift state")
        await assertNothingMoved(since: before, because: "a retired tick may not correct Session B")
        await assertSessionBTicksNormally()
    }

    // MARK: - A5-IOS-4: a peer position report parked in the player state read

    /// **A5-IOS-4.** `onPeerPositionReport` carried no generation at all, so after its
    /// `playerState()` suspension there was nothing it *could* prove. One number on the diagnostics
    /// screen — but FR-023's number, and it would be Session A's, computed against Session A's
    /// anchor for a track Session B is not playing, displayed as Session B's.
    func testAnOldSessionsParkedPeerReportNeverUpdatesTheNewSessionsPeerDrift() async {
        await build()
        await connectAsFollower(generation: 1)
        await startPlayingAsFollower(Self.hashA, seq: 1, generation: 1)

        await player.armStateGate(skipping: 0)
        await session.deliver(
            .positionReport(
                trackHash: Self.hashA, positionMs: Self.stalePeerPositionMs,
                atSessionUs: clock.now(), playing: true, playbackRate: 1.0
            ),
            generation: 1
        )
        await expect("Session A's peer report parked inside the player state read") { [self] in
            await player.isStateGateParked
        }

        await boundaryToLeader(generation: 2)
        await establishSessionBAsLeader()

        let before = await snapshot()
        XCTAssertNil(before.diagnostics.peerDriftMs, "Session B has measured no peer drift of its own yet")
        await player.releaseStateGate()
        await awaitRetiredWorkSettled()

        await assertNothingMoved(since: before, because: "a retired peer report may not measure Session B")

        // And Session B's own report still produces the number.
        await deliverPeerReport(positionMs: 4_000, generation: 2)
        let peerDriftAfter = await coordinator.diagnostics.peerDriftMs
        XCTAssertEqual(peerDriftAfter, 4_000, "Session B's own peer report still updates peerDriftMs")
    }

    // MARK: - A5-IOS-6: the asynchronous proof's own suspension

    /// **A5-IOS-6.** The window A4 Finding D opened `ownsNow` for, taken by an operation that has no
    /// playback epoch: `stillCurrent` must `await session.currentAuthGeneration()`, so a boundary can
    /// land *inside the proof itself* and the proof still answers true.
    ///
    /// The generation gate returns the value that was live when the read parked — which is exactly
    /// what a real `SessionCoordinator` forwarding a boundary event a moment later looks like. Only
    /// the synchronous, actor-local `stillCurrentNow` can refuse this one, and reverting it to an
    /// unconditional true is what makes this case fail.
    func testAnAdmissionWhoseOwnSessionProofSuspendsAcrossTheBoundaryStillWritesNothing() async {
        await build()
        await connectAsFollower(generation: 1)

        // Read one is `onPlaybackMessage`'s entry proof; the read that parks is the one A5 adds
        // immediately after the clock estimate, before a byte of ordering state is written.
        await session.armGenerationGate(skipping: 1)
        await session.deliver(playMessage(Self.hashA, seq: Self.sessionASeq), generation: 1)
        await expect("Session A parked inside its own session proof") { [self] in
            await session.isGenerationGateParked
        }

        await boundaryToLeader(generation: 2)
        await establishSessionBAsLeader()

        let before = await snapshot()
        await session.releaseGenerationGate()
        await awaitRetiredWorkSettled()

        await assertNothingMoved(
            since: before, because: "an asynchronous proof that answers true about a dead session is not a proof"
        )
        await assertSessionBStillCommands(nextSeq: 2)
    }

    // MARK: - A5-IOS-5: same-session controls

    /// **A5-IOS-5a.** Every fence above, with **no boundary**: the admission still applies, a held
    /// admission still recovers, and the sequence numbers still move. A5 must not buy its safety by
    /// suppressing legitimate work.
    func testWithinOneSessionAnAdmissionStillAppliesAndAHeldOneStillRecovers() async {
        await build()
        await connectAsFollower(generation: 1)
        await startPlayingAsFollower(Self.hashA, seq: 1, generation: 1)

        await deliverAndAwait(.pause(header: header(seq: 2), positionMs: 30_000), generation: 1)
        var applied = await coordinator.lastAppliedSeq
        XCTAssertEqual(applied, 2, "an ordinary admission still applies immediately")

        // The clock goes untrustworthy, so the next command is held rather than applied…
        await setClock(ready: false)
        await deliverAndAwait(.resume(header: header(seq: 3), positionMs: 30_000), generation: 1)
        let received = await coordinator.lastReceivedSeq
        applied = await coordinator.lastAppliedSeq
        XCTAssertEqual(received, 3, "a held command still takes responsibility for its sequence number")
        XCTAssertEqual(applied, 2, "and still does not claim to have been applied")
        let held = await coordinator.deferredEvents.count
        XCTAssertEqual(held, 1, "and it really is held")

        // …and recovers the instant the estimator does, through the deferred drain's own retry.
        await setClock(ready: true)
        await awaitDeferredRecovery()
        applied = await coordinator.lastAppliedSeq
        XCTAssertEqual(applied, 3, "the held command recovered")
        let remaining = await coordinator.deferredEvents.count
        XCTAssertEqual(remaining, 0, "and the held stream drained")
    }

    /// **A5-IOS-5b.** The tick and the peer report, with no boundary: a `POSITION_REPORT` still
    /// reaches the wire, ADR-004's ladder still nudges the rate, and a peer report still produces
    /// FR-023's number.
    func testWithinOneSessionATickStillReportsAndCorrectsAndAPeerReportStillMeasures() async {
        await build()
        await connectAsFollower(generation: 1)
        await startPlayingAsFollower(Self.hashA, seq: 1, generation: 1)

        await deliverPeerReport(positionMs: 6_000, generation: 1)
        let peerDrift = await coordinator.diagnostics.peerDriftMs
        XCTAssertEqual(peerDrift, 6_000, "a peer report still measures observed peer drift")

        await runTick(driftMs: Self.nudgeDriftMs)

        let reports = await session.playbackMessages().filter(\.isPositionReport)
        XCTAssertFalse(reports.isEmpty, "the cadence tick still put a POSITION_REPORT on the wire")
        let diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.localDriftMs, Self.nudgeDriftMs, "and still measured this device's own drift")
        XCTAssertEqual(diagnostics.lastCorrection, .nudge, "and still applied ADR-004's first tier")
        XCTAssertEqual(diagnostics.correctionTickCount, 1, "and still counted the tick as finished")
        let calls = await player.calls
        XCTAssertTrue(calls.contains { call in
            if case .setRate(let rate) = call { return rate != DriftController.rateNormal }
            return false
        }, "and the nudge still reached the player")
    }

    // MARK: - Fixtures

    private func build() async {
        session = FakeSyncSession()
        player = FakeSyncPlayer()
        content = FakeSyncContent()
        clock = FakeMonotonicClock(startUs: Self.anchorUs)
        routeState = FakeRouteState()
        let clock = clock!
        let ids = IdSequence(start: idSeed)
        idSeed += 50
        coordinator = SyncPlaybackCoordinator(
            monotonicNowUs: { clock.now() },
            localPeerId: SyncTestValues.followerPeerId,
            session: session,
            player: player,
            content: content,
            sleeper: clock,
            routeState: routeState,
            nextQueueItemId: { ids.next() }
        )
        await coordinator.start()
        for hash in [Self.hashA, Self.hashB] {
            await content.addLocal(hash)
            await content.addPeer(hash)
        }
    }

    override func tearDown() async throws {
        await player?.releaseGate()
        await player?.releaseStateGate()
        await session?.releaseSendGate()
        await session?.releaseClockGate()
        await session?.releaseGenerationGate()
        await routeState?.releaseGate()
        await coordinator?.shutdown()
        coordinator = nil
    }

    private func setClock(ready: Bool) async {
        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: ready))
    }

    /// A follower with a trustworthy clock. The receiving side is where all three findings live: an
    /// authoritative command, a cadence tick and a peer report all arrive here.
    private func connectAsFollower(generation: Int64) async {
        await session.setGeneration(generation)
        await setClock(ready: true)
        await coordinator.handleConnected(isLocalLeader: false)
        await awaitIngressIdle()
    }

    /// A full authentication boundary onto a **leader** session — the shape used whenever Session A
    /// is parked inside an inbound frame, because the one ordered ingress consumer is then busy.
    private func boundaryToLeader(generation: Int64) async {
        await coordinator.handleLinkLost()
        await session.setGeneration(generation)
        await coordinator.handleConnected(isLocalLeader: true)
        await awaitOutboundQuiescent()
    }

    private func boundaryToFollower(generation: Int64) async {
        await coordinator.handleLinkLost()
        await session.setGeneration(generation)
        await coordinator.handleConnected(isLocalLeader: false)
        await awaitOutboundQuiescent()
    }

    /// Session B as the leader: its own queue item, its own timeline, its own live playback epoch,
    /// playing, and `command_seq` 1 committed through the real outbound commit path.
    private func establishSessionBAsLeader() async {
        let startsBefore = await player.calls.filter { $0 == .start }.count
        await coordinator.playSynchronized(Self.hashB)
        await expect("Session B's PLAY reached the wire") { [self] in
            await session.playbackMessages().contains { message in
                if case .play(_, let hash, _, _) = message { return hash == Self.hashB }
                return false
            }
        }
        await awaitOutboundQuiescent()
        await expect("and its pre-roll completed") { [self] in
            await player.calls.suffix(3) == FakeSyncPlayer.preRoll(Self.hashB, 0)
        }
        clock.advance(to: await playDeadline(for: Self.hashB))
        await expect("and its scheduled start fired") { [self] in
            await player.calls.filter { $0 == .start }.count > startsBefore
        }
        await expect("and the command counts as landed") { [self] in
            await coordinator.diagnostics.syncState == .synced
        }
        await settleSessionBPlayerState()
    }

    /// An authoritative `PLAY` arriving from the leader, whose deadline has already passed — applied
    /// and started on arrival.
    private func startPlayingAsFollower(_ hash: ContentHash, seq: Int64, generation: Int64) async {
        let startsBefore = await player.calls.filter { $0 == .start }.count
        await deliverAndAwait(playMessage(hash, seq: seq), generation: generation)
        await expect("\(hash.hex.prefix(6)) started") { [self] in
            await player.calls.filter { $0 == .start }.count > startsBefore
        }
        await expect("and the command counts as landed") { [self] in
            await coordinator.diagnostics.syncState == .synced
        }
        await settleSessionBPlayerState()
    }

    /// The player state this device's own timeline implies, so a later tick measures zero drift
    /// unless something moved the player behind the live session's back.
    ///
    /// Outbound quiescence only, deliberately: where Session A is parked *inside* an inbound frame's
    /// handler the one ordered ingress consumer is busy by construction and will not go idle until
    /// that frame is released. Every frame this fixture delivers is already waited for individually
    /// by `deliverAndAwait`.
    private func settleSessionBPlayerState() async {
        await player.setState(
            PlayerState(positionMs: 0, durationMs: Self.trackDurationMs, playing: true, rate: 1.0)
        )
        await awaitOutboundQuiescent()
    }

    /// Session A accepts an authoritative command and suspends inside `estimate()`, strictly before
    /// it writes one byte of ordering state.
    private func parkSessionAsAdmissionInsideTheClockRead() async {
        await session.armClockGate(skipping: 0)
        await session.deliver(playMessage(Self.hashA, seq: Self.sessionASeq), generation: 1)
        await expect("Session A's admission parked inside the session clock read") { [self] in
            await session.isClockGateParked
        }
        let received = await coordinator.lastReceivedSeq
        XCTAssertNil(received, "and it has taken responsibility for nothing yet")
    }

    private enum TickPark { case playerState, routeState }

    /// Drives one cadence tick and parks it at the named suspension.
    private func parkSessionAsTick(in park: TickPark) async {
        await awaitTickArmed()
        guard let nextTickUs = clock.pendingDeadlines().max() else {
            return XCTFail("no cadence tick is armed")
        }
        // Past ADR-004's nudge threshold, so the route-state case has a real correction to suppress.
        let anchorUs = await coordinator.timeline?.anchorSessionUs ?? Self.anchorUs
        let elapsedMs = (nextTickUs - anchorUs) / 1_000
        await player.setState(
            PlayerState(
                positionMs: elapsedMs + Self.nudgeDriftMs, durationMs: Self.trackDurationMs,
                playing: true, rate: 1.0
            )
        )
        switch park {
        case .playerState: await player.armStateGate(skipping: 0)
        case .routeState: await routeState.armGate(skipping: 0)
        }
        clock.advance(to: nextTickUs)
        switch park {
        case .playerState:
            await expect("the tick parked inside the player state read") { [self] in
                await player.isStateGateParked
            }
        case .routeState:
            await expect("the tick parked inside the route state read") { [self] in
                await routeState.isGateParked
            }
            // The tick's own `POSITION_REPORT` was enqueued before this suspension, legitimately,
            // under Session A — so it must reach the wire before any "nothing moved" snapshot.
            await awaitOutboundQuiescent()
        }
    }

    /// Session B's own next command still commits, in order, from its own sequence floor.
    private func assertSessionBStillCommands(nextSeq: Int64) async {
        await coordinator.pause()
        await expect("Session B's own PAUSE reached the wire") { [self] in
            await session.playbackMessages().contains(where: \.isPause)
        }
        await awaitOutboundQuiescent()
        await expect("and committed its own sequence number") { [self] in
            await coordinator.lastAppliedSeq == nextSeq
        }
    }

    /// Session B's own cadence tick still reports and still corrects.
    private func assertSessionBTicksNormally() async {
        let before = await coordinator.diagnostics.correctionTickCount
        await runTick(driftMs: Self.nudgeDriftMs)
        let diagnostics = await coordinator.diagnostics
        XCTAssertEqual(diagnostics.correctionTickCount, before + 1, "Session B's own tick still completes")
        XCTAssertEqual(diagnostics.localDriftMs, Self.nudgeDriftMs, "and still measures its own drift")
        XCTAssertEqual(diagnostics.lastCorrection, .nudge, "and still corrects it")
    }

    /// Advances to the next cadence deadline with the player reporting `expected + driftMs`, then
    /// waits for that tick to actually finish — the counter, not a yield budget.
    private func runTick(driftMs: Int64) async {
        await awaitTickArmed()
        guard let nextTickUs = clock.pendingDeadlines().max() else {
            return XCTFail("no cadence tick is armed")
        }
        let before = await coordinator.diagnostics.correctionTickCount
        let anchorUs = await coordinator.timeline?.anchorSessionUs ?? Self.anchorUs
        let elapsedMs = (nextTickUs - anchorUs) / 1_000
        await player.setState(
            PlayerState(
                positionMs: elapsedMs + driftMs, durationMs: Self.trackDurationMs, playing: true, rate: 1.0
            )
        )
        clock.advance(to: nextTickUs)
        await expect("the cadence tick finished") { [self] in
            await coordinator.diagnostics.correctionTickCount > before
        }
        await awaitOutboundQuiescent()
    }

    private func deliverPeerReport(positionMs: Int64, generation: Int64) async {
        let before = await coordinator.diagnostics.peerDriftMs
        guard let active = await coordinator.timeline else { return XCTFail("no live timeline to report against") }
        await deliverAndAwait(
            .positionReport(
                trackHash: active.trackHash, positionMs: positionMs, atSessionUs: active.anchorSessionUs,
                playing: true, playbackRate: 1.0
            ),
            generation: generation
        )
        await expect("the peer report produced its number") { [self] in
            await coordinator.diagnostics.peerDriftMs != before
        }
    }

    private func deliverAndAwait(_ message: PlaybackMessage, generation: Int64) async {
        let before = await coordinator.diagnostics.inboundProcessedCount
        await session.deliver(message, generation: generation)
        await expect("the frame was considered") { [self] in
            await coordinator.diagnostics.inboundProcessedCount > before
        }
    }

    private func playMessage(_ hash: ContentHash, seq: Int64) -> PlaybackMessage {
        .play(
            header: header(seq: seq), trackHash: hash, positionMs: 0,
            queueItemId: SyncTestValues.ulid(Int(seq))
        )
    }

    private func header(seq: Int64) -> PlaybackCommandHeader {
        PlaybackCommandHeader(
            commandSeq: seq, effectiveAtSessionUs: clock.now(),
            issuedBy: SyncTestValues.leaderPeerId, queueRevision: 0
        )
    }

    private func playDeadline(for hash: ContentHash) async -> Int64 {
        let deadlines = await session.playbackMessages().compactMap { message -> Int64? in
            guard case .play(let header, let trackHash, _, _) = message, trackHash == hash else { return nil }
            return header.effectiveAtSessionUs
        }
        guard let last = deadlines.last else {
            XCTFail("no PLAY for that track ever reached the wire")
            return clock.now()
        }
        return last
    }

    // MARK: - Snapshots

    /// Everything a retired continuation could possibly move, in one value.
    private struct Snapshot: Equatable {
        var lastReceivedSeq: Int64?
        var lastAppliedSeq: Int64?
        var deferredCount: Int
        var diagnostics: SyncPlaybackDiagnostics
        var queueState: SharedQueueState
        var playerCalls: [FakeSyncPlayer.Call]
        var wireCount: Int
        var pendingDeadlines: [Int64]
    }

    private func snapshot() async -> Snapshot {
        // `inboundProcessedCount` is the **pipe's** own accounting, not session state — it counts
        // frames the one ordered consumer has finished considering, refusals included, and
        // `resetForNewSession` deliberately does not reset it (the queues outlive sessions). A
        // parked frame being *counted as considered* when it is finally released is therefore the
        // ingress working, not a Session-B effect, so it is normalised out of the comparison rather
        // than asserted on. Every value that *is* session state stays in.
        var diagnostics = await coordinator.diagnostics
        diagnostics.inboundProcessedCount = 0
        return await Snapshot(
            lastReceivedSeq: coordinator.lastReceivedSeq,
            lastAppliedSeq: coordinator.lastAppliedSeq,
            deferredCount: coordinator.deferredEvents.count,
            diagnostics: diagnostics,
            queueState: coordinator.queueState,
            playerCalls: player.calls,
            wireCount: session.sent.count,
            pendingDeadlines: clock.pendingDeadlines()
        )
    }

    private func assertNothingMoved(since before: Snapshot, because reason: String) async {
        let after = await snapshot()
        XCTAssertEqual(after.lastReceivedSeq, before.lastReceivedSeq, "lastReceivedSeq: \(reason)")
        XCTAssertEqual(after.lastAppliedSeq, before.lastAppliedSeq, "lastAppliedSeq: \(reason)")
        XCTAssertEqual(after.deferredCount, before.deferredCount, "deferredEvents: \(reason)")
        XCTAssertEqual(after.diagnostics, before.diagnostics, "diagnostics: \(reason)")
        XCTAssertEqual(after.queueState, before.queueState, "queueState: \(reason)")
        XCTAssertEqual(after.playerCalls, before.playerCalls, "player: \(reason)")
        XCTAssertEqual(after.wireCount, before.wireCount, "wire: \(reason)")
        XCTAssertEqual(after.pendingDeadlines, before.pendingDeadlines, "scheduled work: \(reason)")
    }

    // MARK: - Waiting

    /// Gives work released from a **retired** operation its full opportunity to run before a
    /// "nothing changed" assertion is taken.
    ///
    /// A bounded yield budget, for the reason ADR-024 Amendment A3 already recorded: correctly
    /// fenced work produces no observable effect, so there is nothing exact to wait *on*. What makes
    /// it credible is the pre-fix run, where each case fails at this exact budget against unmodified
    /// production code.
    private func awaitRetiredWorkSettled() async {
        await settle(400)
    }

    /// The deferred drain re-checks the clock on `Phase5GateBounds.deferredRetryIntervalUs`, and the
    /// sleeper is virtual — so the retry only happens because this moves the clock to it.
    private func awaitDeferredRecovery() async {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            if await coordinator.deferredEvents.isEmpty { return }
            clock.advance(to: clock.now() + Phase5GateBounds.deferredRetryIntervalUs)
            await Task.yield()
        }
        XCTFail("the held authoritative stream never drained")
    }

    private func awaitIngressIdle() async {
        await awaitOutboundQuiescent()
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            if await coordinator.isIngressIdle() { return }
            await Task.yield()
        }
        XCTFail("the ingress never went idle")
    }

    private func awaitOutboundQuiescent() async {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            let diagnostics = await coordinator.diagnostics
            if diagnostics.outboundAttemptCount == diagnostics.outboundEnqueuedCount { return }
            if await session.isSendGateParked { return }
            await Task.yield()
        }
        XCTFail("the ordered outbound path never drained")
    }

    private func awaitTickArmed() async {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            if !clock.pendingDeadlines().isEmpty { return }
            await Task.yield()
        }
        XCTFail("the cadence loop never armed a deadline")
    }

    private func expect(_ description: String, _ condition: @escaping () async -> Bool) async {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            if await condition() { return }
            await Task.yield()
        }
        XCTFail("timed out waiting for: \(description)")
    }
}

private extension PlaybackMessage {
    var isPositionReport: Bool { if case .positionReport = self { return true }; return false }
    var isPause: Bool { if case .pause = self { return true }; return false }
}
