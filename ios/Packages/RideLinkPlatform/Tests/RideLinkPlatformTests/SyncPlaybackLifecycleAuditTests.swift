import Foundation
import XCTest

@testable import RideLinkCore
@testable import RideLinkPlatform

/// The regressions ADR-024 **Amendment A3** — the third Phase 5 closure audit — exists for.
///
/// A2 established that an authoritative frame commits nothing until the transport says it went out.
/// A3 is about what happens to the *local* half of that commit when the session dies between the
/// send succeeding and the apply running: **an apply-chain or scheduled-chain node created under
/// Session A could still be waiting when Session B authenticated, and then ran against Session B's
/// queue, timeline, playback epoch, player and diagnostics.**
///
/// Every test below builds that interleaving and asserts **zero** Session-B effects. Each one fails
/// on the code as A2 left it:
///
/// - `applyChain`/`scheduledChain` were *detached* at a session boundary (`= nil`) and never
///   cancelled, so the nodes already created went on existing;
/// - `applyStep`, `applyTransport` and `applySeek` mutated the live queue, the live timeline and the
///   live playback epoch **before** proving anything about the session that authorised them;
/// - a retired scheduled action wrote and published `lastScheduleErrorUs` *before* its ownership
///   proof.
///
/// **This platform is where the interleaving is real rather than modelled.** The coordinator is an
/// `actor`, so every `await` in it is a re-entrancy point, and the blocking seam
/// (`FakeSyncPlayer.gateCalls`) is a `withCheckedContinuation` — which ignores cancellation by
/// nature, exactly as `AVAudioEngine`'s callbacks do. What these tests prove is therefore the
/// generation fence, not merely that cancellation happened.
///
/// The mirror is `com.ridelink.app.sync.SyncPlaybackLifecycleAuditTest`.
final class SyncPlaybackLifecycleAuditTests: XCTestCase {
    private var session: FakeSyncSession!
    private var player: FakeSyncPlayer!
    private var content: FakeSyncContent!
    private var clock: FakeMonotonicClock!
    private var routeState: FakeRouteState!
    private var coordinator: SyncPlaybackCoordinator!
    private var idSeed = 1_800

    private static let hashA = SyncTestValues.hash(11)
    private static let hashX = SyncTestValues.hash(12)
    private static let hashY = SyncTestValues.hash(13)
    private static let hashZ = SyncTestValues.hash(14)

    /// Deliberately far from anything Session B ever anchors at.
    private static let seekTargetMs: Int64 = 600_000
    private static let pausePositionMs: Int64 = 450_000
    /// `PlaybackBounds.positionReportIntervalMs`, in microseconds.
    private static let positionReportUs: Int64 = 5_000_000
    /// How late a deadline is let arrive, so the measured error is an exact, distinguishable number.
    private static let lateByUs: Int64 = 2_000
    /// `4 x rtt_p95` past `SessionClock.maxLeadUs`, so Session A's `LEAD` is the 2 s clamp — see
    /// `connectAsLeader` for why the audit needs it.
    private static let longLeadRttUs: Int64 = 500_000

    private func build() async {
        session = FakeSyncSession()
        player = FakeSyncPlayer()
        content = FakeSyncContent()
        clock = FakeMonotonicClock()
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
    }

    override func tearDown() async throws {
        await player?.releaseGate()
        await session?.releaseSendGate()
        await coordinator?.shutdown()
        coordinator = nil
    }

    /// Session A: leader, clock ready, every track this test needs playable on both devices.
    private func connectAsLeader() async {
        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
        // Session A is given the **maximum** `LEAD = max(120 ms, 4 x rtt_p95)` (clamped at 2 s by
        // `SessionClock.maxLeadUs`), so every deadline it stamps is still in the future after
        // Session B has authenticated and started its own playback. Without that, Session A's
        // scheduled nodes fire while Session B is being built and the race these tests exist for
        // would be over before the assertions began.
        await session.setRtt(Self.longLeadRttUs)
        await coordinator.handleConnected(isLocalLeader: true)
        for hash in [Self.hashA, Self.hashX, Self.hashY, Self.hashZ] {
            await content.addLocal(hash)
            await content.addPeer(hash)
        }
        await awaitOutboundQuiescent()
        await player.clearCalls()
        await session.clearSent()
    }

    /// Session A's leader issues an authoritative `PLAY`, the transport confirms it went out, and its
    /// **local** apply then parks inside the decoder `load` — the exact position ADR-024 Amendment A2's
    /// commit point creates and A3 is about.
    private func sentPlayBlockedInPrepare() async {
        await player.gateCalls { call in
            if case .load(let hash) = call { return hash == Self.hashA }
            return false
        }
        await coordinator.playSynchronized(Self.hashA)
        await expect("PLAY A reached the wire, so A2 committed its command_seq") { [self] in
            await !session.playbackMessages().filter(\.isPlay).isEmpty
        }
        await expect("its local apply is parked inside the decoder load") { [self] in await player.isGateParked }
        let calls = await player.calls
        XCTAssertEqual(calls, [.select(Self.hashA), .load(Self.hashA)], "nothing beyond the load has happened yet")
    }

    /// A full authentication boundary: the link drops, the generation moves, a new session opens.
    private func boundary(generation: Int64) async {
        await coordinator.handleLinkLost()
        await session.setGeneration(generation)
        // Session B stamps ordinary 120 ms deadlines, so its own work lands well before any
        // Session-A deadline arrives.
        await session.setRtt(8_000)
        await coordinator.handleConnected(isLocalLeader: true)
        await awaitOutboundQuiescent()
    }

    /// Session B, built to be distinguishable from Session A at every point A could touch: a
    /// three-item queue whose **current item is the middle one**, a live timeline for it, and a live
    /// playback epoch.
    ///
    /// - Returns: the session instant B's `PLAY` was stamped for.
    private func establishSessionB(startPlayback: Bool = true, currentIsLast: Bool = false) async -> Int64 {
        await enqueueAndSettle(Self.hashX)
        await coordinator.playSynchronized(Self.hashY)
        await expect("Session B's PLAY reached the wire") { [self] in
            await session.playbackMessages().contains { message in
                if case .play(_, let hash, _, _) = message { return hash == Self.hashY }
                return false
            }
        }
        await awaitOutboundQuiescent()
        // **The real "Session B is established" signal.** A quiescent outbound path only says the
        // frame was written; the local apply runs on the ordered apply chain *after* the commit hook,
        // so the selection is what proves it has actually happened. Waiting on the send instead left
        // this helper returning before Session B had any current item — which is a fact about this
        // platform's task scheduling, not about the coordinator.
        await expect("Session B selected its current item") { [self] in
            await coordinator.queueState.currentItem?.trackHash == Self.hashY
        }
        if !currentIsLast { await enqueueAndSettle(Self.hashZ) }
        let deadline = await playDeadline(for: Self.hashY)
        if startPlayback {
            // Counted rather than merely contained (ADR-024 Amendment A4): Session A has often
            // already started something, and `contains(.start)` would then answer true before
            // Session B had done anything — leaving the "before" snapshot taken too early.
            let startsBefore = await player.calls.filter { $0 == .start }.count
            clock.advance(to: deadline)
            await expect("Session B started") { [self] in
                await player.calls.filter { $0 == .start }.count > startsBefore
            }
            // `.start` is recorded *inside* the step; `markSynced` and the schedule-error
            // measurement follow it. Snapshotting between the two produced a 1-in-70 flake where
            // `syncState` moved from `.scheduled` to `.synced` after the "before" reading.
            await expect("and Session B's diagnostics have settled") { [self] in
                await coordinator.diagnostics.syncState == .synced
            }
        }
        return deadline
    }

    private func enqueueAndSettle(_ hash: ContentHash) async {
        let before = await coordinator.queueState.items.count
        await coordinator.enqueue(hash)
        await expect("\(hash.hex.prefix(6)) is in the authoritative queue") { [self] in
            await coordinator.queueState.items.count > before
        }
        await awaitOutboundQuiescent()
    }

    private func playDeadline(for hash: ContentHash) async -> Int64 {
        let deadlines = await session.playbackMessages().compactMap { message -> Int64? in
            guard case .play(let header, let trackHash, _, _) = message, trackHash == hash else { return nil }
            return header.effectiveAtSessionUs
        }
        guard let last = deadlines.last else {
            XCTFail("no PLAY for that track ever reached the wire")
            return 0
        }
        return last
    }

    // MARK: - Regression A — an apply-chain node created under Session A

    /// **The defect.** `resetForNewSession` did `applyChain = nil`. That detaches the *tail
    /// reference*; it cancels nothing and fences nothing. `PLAY(seq n)` parked inside
    /// the decoder `load`, `NEXT(seq n+1)` — already written to the wire, already committed by A2's
    /// outbound consumer — waited behind it in the chain, and when the blocked `PLAY` finally
    /// returned the `NEXT` woke up in **Session B** and ran `applyStep` against Session B's queue.
    ///
    /// `PLAY`'s own continuation was already safe (`owns` after the pre-roll). The command *behind*
    /// it was not, because nothing between "the node ahead finished" and "mutate the queue" ever
    /// asked which session had authorised it.
    func testAnOldSessionsQueuedNextHasZeroEffectOnTheSessionThatReplacedIt() async {
        await build()
        await connectAsLeader()
        await sentPlayBlockedInPrepare()

        await coordinator.next()
        await expect("NEXT A reached the wire too") { [self] in
            await session.playbackMessages().contains(where: \.isNext)
        }
        await awaitOutboundQuiescent()
        let callsWhileBlocked = await player.calls
        XCTAssertEqual(
            callsWhileBlocked, [.select(Self.hashA), .load(Self.hashA)], "NEXT A's apply is queued behind PLAY A"
        )

        await boundary(generation: 2)
        _ = await establishSessionB()

        let queueBefore = await coordinator.queueState
        let diagnosticsBefore = await coordinator.diagnostics
        let callsBefore = await player.calls
        let sentBefore = await session.playbackMessages()
        XCTAssertEqual(queueBefore.currentItem?.trackHash, Self.hashY, "Session B is playing the middle item")

        await player.releaseGate()
        await awaitRetiredWorkSettled()

        let queueAfter = await coordinator.queueState
        XCTAssertEqual(queueAfter, queueBefore, "Session A's NEXT may not step Session B's queue")
        let callsAfter = await player.calls
        XCTAssertEqual(callsAfter, callsBefore, "and may not reach Session B's player")
        let sentAfter = await session.playbackMessages()
        XCTAssertEqual(sentAfter.count, sentBefore.count, "and may not put a frame on Session B's wire")
        let diagnosticsAfter = await coordinator.diagnostics
        XCTAssertEqual(diagnosticsAfter, diagnosticsBefore, "and may not alter one Session-B diagnostic")
    }

    /// Regression B (brief §15): Session B's first apply must not join, or wait behind, Session A's
    /// blocked chain. The chain is *session-owned*; a boundary starts a fresh one.
    ///
    /// Everything Session B does here happens while Session A's apply is still parked.
    func testANewSessionsFirstApplyDoesNotWaitForTheOldSessionsBlockedApplyChain() async {
        await build()
        await connectAsLeader()
        await sentPlayBlockedInPrepare()
        await coordinator.next()
        await awaitOutboundQuiescent()

        await boundary(generation: 2)

        await coordinator.playSynchronized(Self.hashX)
        await expect("Session B's own apply ran while Session A's was still blocked") { [self] in
            await player.calls.contains(.load(Self.hashX))
        }
        await awaitOutboundQuiescent()
        let deadline = await playDeadline(for: Self.hashX)
        clock.advance(to: deadline)
        await expect("and reached its scheduled start") { [self] in await player.calls.contains(.start) }
        await expect("and its diagnostics settled") { [self] in
            await coordinator.diagnostics.syncState == .synced
        }
        let track = await coordinator.diagnostics.currentTrackHash
        XCTAssertEqual(track, Self.hashX)
        let stillParked = await player.isGateParked
        XCTAssertTrue(stillParked, "Session A's apply was parked for all of that")

        let callsBefore = await player.calls
        let queueBefore = await coordinator.queueState
        let diagnosticsBefore = await coordinator.diagnostics
        await player.releaseGate()
        await awaitRetiredWorkSettled()
        let callsAfter = await player.calls
        XCTAssertEqual(callsAfter, callsBefore, "and releasing Session A afterwards changes nothing")
        let queueAfter = await coordinator.queueState
        XCTAssertEqual(queueAfter, queueBefore)
        let diagnosticsAfter = await coordinator.diagnostics
        XCTAssertEqual(diagnosticsAfter, diagnosticsBefore)
    }

    /// Regression C (brief §16): `applySeek` read `currentEpochToken` and re-anchored `timeline`
    /// with **no** ownership proof at all — it was not even `async`. A Session-A `SEEK` waking in
    /// Session B therefore re-anchored Session B's timeline to Session A's target instant, and every
    /// drift measurement afterwards was taken against a timeline no leader had authorised.
    ///
    /// The re-anchor is proved through the one number it changes: `localDriftMs`. Session B's player
    /// is placed exactly on Session B's own timeline, so a correct fence leaves the drift at zero and
    /// a re-anchored timeline cannot.
    func testAnOldSessionsQueuedSeekNeverReanchorsTheNewSessionsTimeline() async {
        await build()
        await connectAsLeader()
        await sentPlayBlockedInPrepare()

        await coordinator.seek(positionMs: Self.seekTargetMs)
        await expect("SEEK A reached the wire and is queued behind the blocked PLAY A") { [self] in
            await session.playbackMessages().contains(where: \.isSeek)
        }
        await awaitOutboundQuiescent()

        await boundary(generation: 2)
        let anchorB = await establishSessionB()

        let diagnosticsBefore = await coordinator.diagnostics
        let deadlinesBefore = clock.pendingDeadlines()
        await player.releaseGate()
        await awaitRetiredWorkSettled()

        let diagnosticsAfter = await coordinator.diagnostics
        XCTAssertEqual(diagnosticsAfter, diagnosticsBefore, "no Session-B diagnostic moved")
        XCTAssertEqual(clock.pendingDeadlines(), deadlinesBefore, "and Session A scheduled nothing into Session B")
        let calls = await player.calls
        XCTAssertFalse(calls.contains(.seek(Self.seekTargetMs)), "Session A's seek target never reached the player")

        // The timeline itself: place B's player exactly where B's own anchor says it should be one
        // cadence tick from now, and assert the measured drift is zero.
        let tickAtUs = clock.now() + Self.positionReportUs
        await player.setState(
            PlayerState(positionMs: (tickAtUs - anchorB) / 1_000, durationMs: 3_600_000, playing: true, rate: 1.0)
        )
        let ticksBefore = await coordinator.diagnostics.correctionTickCount
        clock.advance(to: tickAtUs)
        await expect("one cadence tick completed") { [self] in
            await coordinator.diagnostics.correctionTickCount > ticksBefore
        }
        let drift = await coordinator.diagnostics.localDriftMs
        XCTAssertEqual(drift, 0, "Session B is still measured against Session B's anchor")
        let seeks = await coordinator.diagnostics.hardSeekCount
        XCTAssertEqual(seeks, 0, "so nothing on the ladder fired")
    }

    /// The `applyTransport` half of the same defect: `PAUSE`/`RESUME` read `currentEpochToken` and
    /// re-anchored `timeline` before proving anything.
    func testAnOldSessionsQueuedPauseNeverReanchorsTheNewSessionsTimeline() async {
        await build()
        await connectAsLeader()
        await sentPlayBlockedInPrepare()

        await player.setState(
            PlayerState(positionMs: Self.pausePositionMs, durationMs: 3_600_000, playing: true, rate: 1.0)
        )
        await coordinator.pause()
        await expect("PAUSE A reached the wire and is queued behind the blocked PLAY A") { [self] in
            await session.playbackMessages().contains(where: \.isPause)
        }
        await awaitOutboundQuiescent()

        await boundary(generation: 2)
        let anchorB = await establishSessionB()

        let diagnosticsBefore = await coordinator.diagnostics
        let deadlinesBefore = clock.pendingDeadlines()
        await player.releaseGate()
        await awaitRetiredWorkSettled()

        let diagnosticsAfter = await coordinator.diagnostics
        XCTAssertEqual(diagnosticsAfter, diagnosticsBefore, "no Session-B diagnostic moved")
        XCTAssertEqual(clock.pendingDeadlines(), deadlinesBefore, "and Session A scheduled nothing into Session B")
        let calls = await player.calls
        XCTAssertFalse(calls.contains(.pause), "Session B's player was never paused")

        let tickAtUs = clock.now() + Self.positionReportUs
        await player.setState(
            PlayerState(positionMs: (tickAtUs - anchorB) / 1_000, durationMs: 3_600_000, playing: true, rate: 1.0)
        )
        let ticksBefore = await coordinator.diagnostics.correctionTickCount
        clock.advance(to: tickAtUs)
        await expect("one cadence tick completed") { [self] in
            await coordinator.diagnostics.correctionTickCount > ticksBefore
        }
        let drift = await coordinator.diagnostics.localDriftMs
        XCTAssertEqual(drift, 0, "and B's timeline still says it is playing")
    }

    /// The sharpest form of Regression D (brief §17/§6): a Session-A `NEXT` that runs off the end of
    /// **Session B's** queue took `applyStep`'s `step.selected == nil` branch, and that branch called
    /// `epoch.begin()` — which *supersedes the live playback epoch*. Session B's own scheduled start,
    /// armed against the token `begin()` had just retired, then failed its ownership proof and never
    /// fired.
    ///
    /// So the old session did not merely write state it did not own: it silently disabled the new
    /// session's audio. This test keeps B's start pending across the release for exactly that reason.
    func testAnOldSessionsQueuedNextCannotRetireTheNewSessionsPlaybackEpoch() async {
        await build()
        await connectAsLeader()
        await sentPlayBlockedInPrepare()
        await coordinator.next()
        await awaitOutboundQuiescent()

        await boundary(generation: 2)
        // B's queue is [X, Y] with Y — the last item — current, so an old NEXT runs off the end.
        let deadlineB = await establishSessionB(startPlayback: false, currentIsLast: true)
        let currentB = await coordinator.queueState.currentItem?.trackHash
        XCTAssertEqual(currentB, Self.hashY)
        // Armed *in the sleeper*, which is two task hops past the selection this helper waited for:
        // `applyPlay` selects, then pre-rolls, then arms. A bare assertion here failed 6 runs in 12
        // under this amendment's own stress run — a fact about task scheduling, not about the fence.
        await expect("Session B's start is armed and still waiting for its deadline") { [clock] in
            clock?.pendingDeadlines().contains(deadlineB) ?? false
        }

        await player.releaseGate()
        await awaitRetiredWorkSettled()

        let currentAfter = await coordinator.queueState.currentItem?.trackHash
        XCTAssertEqual(currentAfter, Self.hashY, "B's selection is untouched")
        let calls = await player.calls
        XCTAssertFalse(calls.contains(.stop), "and B was never stopped")

        clock.advance(to: deadlineB)
        await expect("Session B's own scheduled start still fires") { [self] in
            await player.calls.contains(.start)
        }
        await expect("and it is tracking the timeline") { [self] in
            await coordinator.diagnostics.syncState == .synced
        }
        let state = await coordinator.diagnostics.syncState
        XCTAssertEqual(state, .synced, "its playback epoch was never retired")
    }

    // MARK: - Regression E — a scheduled-chain node created under Session A

    /// **The defect.** `scheduleAt`'s node wrote and published `lastScheduleErrorUs` immediately
    /// after its sleep and *before* `runIfCurrent`. The player action was correctly refused, but a
    /// Session-A deadline firing after Session B was live still overwrote Session B's
    /// scheduling-error figure — the FR-023 number a rider reads as "this is how well the last
    /// synchronised command landed".
    func testAnOldSessionsScheduledDeadlineFiringAfterTheBoundaryChangesNothing() async {
        await build()
        await connectAsLeader()
        await coordinator.playSynchronized(Self.hashA)
        await expect("Session A pre-rolled and armed its start") { [self] in
            await player.calls.contains(.load(Self.hashA))
        }
        await awaitOutboundQuiescent()
        let deadlineA = await playDeadline(for: Self.hashA)
        // Armed *in the sleeper*, which is one task hop past the pre-roll: the node proves ownership
        // before it sleeps (Amendment A3 Finding C), so the deadline appears only once it gets there.
        await expect("Session A's start is armed in the sleeper") { [clock] in
            clock?.pendingDeadlines().contains(deadlineA) ?? false
        }

        await boundary(generation: 2)
        _ = await establishSessionB()

        let diagnosticsBefore = await coordinator.diagnostics
        let queueBefore = await coordinator.queueState
        let callsBefore = await player.calls
        let sentBefore = await session.playbackMessages()
        XCTAssertEqual(diagnosticsBefore.lastScheduleErrorUs, 0, "Session B's own start landed exactly on time")

        // Session A's old deadline arrives while Session B is live — measurably late, so what it
        // would have written is a different number from what Session B legitimately wrote.
        clock.advance(to: deadlineA + Self.lateByUs)
        // The apply chain is not what has to drain here — no apply is blocked. What has to happen is
        // that Session A's *scheduled* node actually wakes, which the sleeper releasing its deadline
        // is the only honest evidence for.
        await awaitDeadlineFired(deadlineA)
        await settle()

        let diagnosticsAfter = await coordinator.diagnostics
        XCTAssertEqual(
            diagnosticsAfter.lastScheduleErrorUs, diagnosticsBefore.lastScheduleErrorUs,
            "a retired session's deadline may not write the live session's schedule error"
        )
        XCTAssertEqual(
            diagnosticsAfter.lateCommandCount, diagnosticsBefore.lateCommandCount, "nor its lateness count"
        )
        XCTAssertEqual(diagnosticsAfter, diagnosticsBefore, "nor any other diagnostic")
        let callsAfter = await player.calls
        XCTAssertEqual(callsAfter, callsBefore, "and it may not drive Session B's player")
        let queueAfter = await coordinator.queueState
        XCTAssertEqual(queueAfter, queueBefore)
        let sentAfter = await session.playbackMessages()
        XCTAssertEqual(sentAfter.count, sentBefore.count)

        // And Session B's own next command still works, so the fence retired A rather than B.
        await coordinator.seek(positionMs: Self.seekTargetMs)
        await expect("Session B's own SEEK reached the wire") { [self] in
            await session.playbackMessages().contains(where: \.isSeek)
        }
        await awaitOutboundQuiescent()
        let seekDeadline = await session.playbackMessages().compactMap { message -> Int64? in
            guard case .seek(let header, _) = message else { return nil }
            return header.effectiveAtSessionUs
        }.last
        clock.advance(to: seekDeadline ?? 0)
        await expect("Session B's own scheduled seek still reaches the player") { [self] in
            await player.calls.last == .seek(Self.seekTargetMs)
        }
    }

    /// Brief §18's second half, with **several** nodes on the scheduled chain at the boundary rather
    /// than one: three armed actions, all retired together, none of them able to write anything.
    func testABoundaryWithSeveralScheduledNodesRetiresAllOfThem() async {
        await build()
        await connectAsLeader()
        await coordinator.playSynchronized(Self.hashA)
        await expect("the PLAY is armed") { [self] in await player.calls.contains(.load(Self.hashA)) }
        await awaitOutboundQuiescent()
        await coordinator.seek(positionMs: Self.seekTargetMs)
        await expect("the SEEK is armed") { [self] in
            await session.playbackMessages().contains(where: \.isSeek)
        }
        await awaitOutboundQuiescent()
        await player.setState(
            PlayerState(positionMs: Self.pausePositionMs, durationMs: 3_600_000, playing: true, rate: 1.0)
        )
        await coordinator.pause()
        await expect("the PAUSE is armed") { [self] in
            await session.playbackMessages().contains(where: \.isPause)
        }
        await awaitOutboundQuiescent()
        // The premise is counted in *frames sent*, not in sleeping waiters: the chain parks only its
        // head in the sleeper, and every node behind that one is waiting on the node ahead of it —
        // which is A1 Finding G's ordering property, still intact.
        let deadlinesA = await session.playbackMessages().compactMap { message -> Int64? in
            switch message {
            case .play(let header, _, _, _): return header.effectiveAtSessionUs
            case .seek(let header, _): return header.effectiveAtSessionUs
            case .pause(let header, _): return header.effectiveAtSessionUs
            default: return nil
            }
        }
        XCTAssertEqual(deadlinesA.count, 3, "the premise: three Session-A actions were sent and armed")

        await boundary(generation: 2)
        _ = await establishSessionB()
        await player.setState(PlayerState())

        let diagnosticsBefore = await coordinator.diagnostics
        let callsBefore = await player.calls
        clock.advance(to: (deadlinesA.max() ?? 0) + Self.lateByUs)
        // Only the head of a chain is ever parked in the sleeper, so releasing that one deadline is
        // what lets all three nodes run in turn.
        await awaitDeadlineFired(deadlinesA.min() ?? 0)
        await settle()

        let diagnosticsAfter = await coordinator.diagnostics
        XCTAssertEqual(diagnosticsAfter, diagnosticsBefore, "all three retired without writing anything")
        let callsAfter = await player.calls
        XCTAssertEqual(callsAfter, callsBefore)
    }

    // MARK: - Same-session controls (brief §19/§20)

    /// The control for every test above: **the same interleaving with no boundary still applies both
    /// commands, in `command_seq` order.** A3 adds lifetime fences; it must not disable legitimate
    /// application, and it must not regress A1 Finding G's / A2's apply ordering.
    func testWithinOneSessionACommandQueuedBehindABlockedApplyStillAppliesAndInOrder() async {
        await build()
        await connectAsLeader()
        await sentPlayBlockedInPrepare()
        // Queued *after* A, so A is the current item and NEXT has somewhere to step to.
        await enqueueAndSettle(Self.hashX)

        await coordinator.next()
        await awaitOutboundQuiescent()
        let blocked = await player.calls
        XCTAssertEqual(blocked, [.select(Self.hashA), .load(Self.hashA)], "NEXT's apply is queued behind PLAY's")

        await player.releaseGate()
        // The second apply's *own* effect. `calls.count >= 2` used to mean that, back when a
        // pre-roll was one call; ADR-024 Amendment A4 made a pre-roll three, which turned this
        // wait vacuous and left `awaitApplyChainDrained` racing a node `chainApply` had not
        // created yet (the commit hook runs after the outbound counters move).
        await expect("both applied") { [self] in await player.calls.contains(.load(Self.hashX)) }
        await awaitApplyChainDrained()

        let calls = await player.calls
        XCTAssertEqual(
            calls, FakeSyncPlayer.preRoll(Self.hashA, 0) + FakeSyncPlayer.preRoll(Self.hashX, 0),
            "N's local effect precedes N+1's, and neither is dropped"
        )
        let current = await coordinator.queueState.currentItem?.trackHash
        XCTAssertEqual(current, Self.hashX)
        let track = await coordinator.diagnostics.currentTrackHash
        XCTAssertEqual(track, Self.hashX)
    }

    /// The same control for the scheduled chain: a legitimate deadline still fires and still counts.
    func testWithinOneSessionAScheduledDeadlineStillFiresAndStillRecordsItsError() async {
        await build()
        await connectAsLeader()
        await coordinator.playSynchronized(Self.hashA)
        await expect("the PLAY pre-rolled") { [self] in await player.calls.contains(.seek(0)) }
        await awaitOutboundQuiescent()
        let deadline = await playDeadline(for: Self.hashA)

        clock.advance(to: deadline + Self.lateByUs)
        await expect("the start still happens") { [self] in await player.calls.contains(.start) }
        // `markSynced` follows the recorded `.start`, so asserting the state the instant the call
        // appears flaked ~1 in 200 whole-suite runs *before* ADR-024 Amendment A4 too.
        await expect("and the command counts as landed") { [self] in
            await coordinator.diagnostics.syncState == .synced
        }

        let error = await coordinator.diagnostics.lastScheduleErrorUs
        XCTAssertEqual(error, Self.lateByUs, "and the measurement it exists to produce is still taken")
        let state = await coordinator.diagnostics.syncState
        XCTAssertEqual(state, .synced)
    }

    // MARK: - Helpers

    /// Gives work released from a **retired** chain its full opportunity to run before a
    /// "nothing changed" assertion is taken.
    ///
    /// **This is a bounded yield budget, and it is deliberately not dressed up as anything better.**
    /// A retired Session-A node is unreachable by construction — the boundary clears both chain tails
    /// *and* the live-node registry, in the fixed code and in the pre-A3 code alike — and its only
    /// correct behaviour is to produce no observable effect at all. So there is nothing to wait *on*:
    /// any signal precise enough to await would be an effect the fence is supposed to prevent.
    ///
    /// What makes the assertions credible is therefore not this budget but the **pre-fix run**: with
    /// the A3 fences reverted, seven of these nine cases fail at this exact budget, on both platforms.
    /// A signal that cannot distinguish "correctly fenced" from "has not run yet" is only as good as
    /// the demonstration that it does distinguish them in practice, and that demonstration exists.
    ///
    /// `awaitApplyChainDrained` below *is* exact, and is used where the tail genuinely is the node
    /// under test — the same-session control, where no boundary intervenes.
    private func awaitRetiredWorkSettled() async {
        await settle(400)
    }

    /// Awaits the apply chain's tail exactly. Each node awaits its predecessor, so the tail completes
    /// only after every node ahead of it has — and it also guarantees any `scheduleAt` the released
    /// work performs has already happened, which is what makes "no new deadline appeared" real.
    ///
    /// Only usable **within one session**: a boundary clears the tail, so after one this awaits the
    /// *new* session's chain and says nothing about the retired one.
    private func awaitApplyChainDrained() async {
        guard let tail = await coordinator.applyChain else { return }
        await tail.value
    }

    /// Waits until the sleeper has released `deadlineUs`, which is what proves a scheduled node
    /// actually woke rather than that the clock merely moved.
    private func awaitDeadlineFired(_ deadlineUs: Int64) async {
        let limit = Date().addingTimeInterval(5)
        while Date() < limit {
            if !clock.pendingDeadlines().contains(deadlineUs) { return }
            await Task.yield()
        }
        XCTFail("the sleeper never released the deadline at \(deadlineUs)")
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

    private func expect(_ description: String, _ condition: @escaping () async -> Bool) async {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            if await condition() { return }
            await Task.yield()
        }
        XCTFail("timed out waiting for: \(description)")
    }
}

/// Cheap shape predicates, so the tests read as "a SEEK reached the wire" rather than as a `switch`.
private extension PlaybackMessage {
    var isPlay: Bool { if case .play = self { return true }; return false }
    var isSeek: Bool { if case .seek = self { return true }; return false }
    var isPause: Bool { if case .pause = self { return true }; return false }
    var isNext: Bool { if case .next = self { return true }; return false }
}
