import Foundation
import XCTest

@testable import RideLinkCore
@testable import RideLinkPlatform

/// The regressions ADR-024 **Amendment A4** — the fourth Phase 5 closure audit — exists for.
///
/// A3 fenced *operations*: an apply-chain or scheduled-chain node created under Session A is
/// retired at a boundary, and every apply path proves its authorising generation before it reads
/// live state. A4 is the narrower hole A3 left underneath that fence: **an operation that passed
/// its ownership proof while Session A was valid, entered a compound effect, suspended inside its
/// first sub-effect, and then performed a *second* sub-effect after Session B was live.**
///
/// One ownership proof authorised two externally visible effects, in three places:
///
/// - `applyTransport`'s scheduled action — `pause` then `seek`, or `seek` then `start` — inside one
///   closure behind one `runIfCurrent`;
/// - `MusicCoordinator.syncPrepare` — materialise, `load`, then `seek` — composed *below*
///   `SyncPlayerPort.prepare`, where no coordinator-level proof could reach;
/// - `MusicCoordinator.syncStop` — the player's `stop`, then clearing the local queue — likewise.
///
/// The fix is structural: `SyncPlayerPort` now has one externally visible effect per method, and
/// `SyncPlaybackCoordinator.runOwnedSteps` re-proves ownership before **every** step. A scheduled
/// action is a `[PlayerStep]` rather than a closure precisely so a second `await` cannot hide in it.
///
/// ## What "fails against pre-A4 code" means for each case here
///
/// Two of these compounds lived in `applyTransport`, which the fake port can already see, and two
/// lived in `MusicCoordinator` — in the app target, which has **no test target at all** on this
/// platform (`docs/STATUS.md` §4 problem 20). The second pair therefore cannot be demonstrated
/// against literally unmodified pre-A4 source, and this file does not pretend otherwise. Three
/// separate pre-fix runs were taken instead, each reverting exactly one thing:
///
/// - **the fence** — `runOwnedSteps` proving ownership once instead of before every step, which is
///   pre-A4's semantics for all four compounds: **5 of these 7 fail** (all but the correction proof
///   and the same-session control);
/// - **`applyTransport` literally** — its two effects back in one closure behind one proof, with
///   everything else fixed: exactly `…ParkedPause…` and `…ParkedResume…` fail, isolating that
///   defect from the two below the port;
/// - **`tickOnce`'s post-correction proof**: `…ACorrection…` fails on `correctionTickCount`,
///   which is Finding F.
///
/// The mirror is `com.ridelink.app.sync.SyncPlaybackOperationLifetimeAuditTest`.
final class SyncPlaybackOperationLifetimeAuditTests: XCTestCase {
    private var session: FakeSyncSession!
    private var player: FakeSyncPlayer!
    private var content: FakeSyncContent!
    private var clock: FakeMonotonicClock!
    private var routeState: FakeRouteState!
    private var coordinator: SyncPlaybackCoordinator!
    private var idSeed = 2_400

    private static let hashA = SyncTestValues.hash(21)
    private static let hashX = SyncTestValues.hash(22)
    private static let hashY = SyncTestValues.hash(23)
    private static let hashZ = SyncTestValues.hash(24)

    /// Deliberately far from anything Session B ever anchors at, so a stale effect is unmistakable.
    private static let resumePositionMs: Int64 = 720_000
    private static let pausePositionMs: Int64 = 540_000
    private static let sessionBSeekMs: Int64 = 90_000
    private static let trackDurationMs: Int64 = 3_600_000
    /// `4 x rtt_p95` past `SessionClock.maxLeadUs`, so Session A's `LEAD` is the 2 s clamp and its
    /// deadlines are still ahead while the boundary and Session B are being built.
    private static let longLeadRttUs: Int64 = 500_000
    /// `PlaybackBounds.positionReportIntervalMs`, in microseconds.
    private static let positionReportUs: Int64 = 5_000_000

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
            localPeerId: SyncTestValues.leaderPeerId,
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

    // MARK: - A4-IOS-1: the pre-roll's own sub-effects

    /// **A4-IOS-1.** Session A's pre-roll parks inside the decoder `load`. Session B then
    /// authenticates and pre-rolls, starts and owns the player. When Session A's load finally
    /// returns it must not perform the `seek` that used to follow it unconditionally — that seek
    /// would move **Session B's** playback to Session A's position, silently, with Session B's own
    /// timeline still saying otherwise.
    func testAnOldSessionsParkedDecoderLoadNeverSeeksTheSessionThatReplacedIt() async {
        await build()
        await connectAsLeader()
        await parkSessionAInsideItsDecoderLoad()

        await boundary(generation: 2)
        _ = await establishSessionB()

        let callsBefore = await player.calls
        let queueBefore = await coordinator.queueState
        let diagnosticsBefore = await coordinator.diagnostics
        let deadlinesBefore = clock.pendingDeadlines()
        XCTAssertTrue(callsBefore.contains(.load(Self.hashY)), "Session B loaded its own track")
        XCTAssertTrue(callsBefore.contains(.start), "and started it")

        await player.releaseGate()
        await awaitRetiredWorkSettled()

        let callsAfter = await player.calls
        XCTAssertEqual(
            callsAfter, callsBefore,
            "Session A's pre-roll may not seek, select, load or start anything after Session B is live"
        )
        let queueAfter = await coordinator.queueState
        XCTAssertEqual(queueAfter, queueBefore, "nor step Session B's shared queue")
        let diagnosticsAfter = await coordinator.diagnostics
        XCTAssertEqual(diagnosticsAfter, diagnosticsBefore, "nor move one Session-B diagnostic")
        XCTAssertEqual(clock.pendingDeadlines(), deadlinesBefore, "and it may not arm a start into Session B")

        await assertSessionBStillWorks()
    }

    /// **A4-IOS-6.** The independence half, with Session A parked *inside* a compound rather than
    /// merely queued behind one: Session B must be able to select, load, seek and start while
    /// Session A's decoder load is still parked, without joining or waiting for it.
    func testANewSessionPreparesAndPlaysWhileTheOldSessionsLoadIsStillParked() async {
        await build()
        await connectAsLeader()
        await parkSessionAInsideItsDecoderLoad()

        await boundary(generation: 2)

        await coordinator.playSynchronized(Self.hashX)
        await expect("Session B pre-rolled while Session A's load was still parked") { [self] in
            await player.calls.contains(.load(Self.hashX))
        }
        await awaitOutboundQuiescent()
        let deadline = await playDeadline(for: Self.hashX)
        clock.advance(to: deadline)
        await expect("and reached its own scheduled start") { [self] in await player.calls.contains(.start) }
        let stillParked = await player.isGateParked
        XCTAssertTrue(stillParked, "Session A was parked for all of that")
        let track = await coordinator.diagnostics.currentTrackHash
        XCTAssertEqual(track, Self.hashX)

        let callsBefore = await player.calls
        let queueBefore = await coordinator.queueState
        let diagnosticsBefore = await coordinator.diagnostics
        await player.releaseGate()
        await awaitRetiredWorkSettled()

        let callsAfter = await player.calls
        XCTAssertEqual(callsAfter, callsBefore, "and releasing Session A afterwards changes nothing it owns")
        let queueAfter = await coordinator.queueState
        XCTAssertEqual(queueAfter, queueBefore)
        let diagnosticsAfter = await coordinator.diagnostics
        XCTAssertEqual(diagnosticsAfter, diagnosticsBefore)
    }

    // MARK: - A4-IOS-2 / A4-IOS-3: the scheduled transport action's two effects

    /// **A4-IOS-2.** A Session-A `RESUME` is `seek` **then** `start`. It parks inside its seek, the
    /// boundary lands, Session B becomes live — and the resumed Session-A action must not start
    /// Session B's player.
    ///
    /// This one fails against unmodified pre-A4 code: that pair sat in one closure in
    /// `applyTransport`, behind a single `runIfCurrent`.
    func testAnOldSessionsParkedResumeSeekNeverStartsTheSessionThatReplacedIt() async {
        await build()
        await connectAsLeader()
        await playAndStart(Self.hashA)

        await player.setState(
            PlayerState(positionMs: Self.resumePositionMs, durationMs: Self.trackDurationMs, playing: true, rate: 1.0)
        )
        await player.gateCalls { call in call == .seek(Self.resumePositionMs) }
        await coordinator.resume()
        await expect("RESUME A reached the wire") { [self] in
            await session.playbackMessages().contains(where: \.isResume)
        }
        await awaitOutboundQuiescent()
        clock.advance(to: await transportDeadline(matching: \.isResume))
        await expect("its scheduled action parked between the seek and the start") { [self] in
            await player.isGateParked
        }
        let parked = await player.calls
        XCTAssertEqual(parked.last, .seek(Self.resumePositionMs), "the seek happened; the start has not")

        await boundary(generation: 2)
        _ = await establishSessionB()

        let callsBefore = await player.calls
        let diagnosticsBefore = await coordinator.diagnostics
        await player.releaseGate()
        await awaitRetiredWorkSettled()

        let callsAfter = await player.calls
        XCTAssertEqual(callsAfter, callsBefore, "a retired RESUME may not start Session B's player")
        let diagnosticsAfter = await coordinator.diagnostics
        XCTAssertEqual(diagnosticsAfter, diagnosticsBefore, "nor move one Session-B diagnostic")

        await assertSessionBStillWorks()
    }

    /// **A4-IOS-3.** A Session-A `PAUSE` is `pause` **then** `seek`. It parks inside its pause, the
    /// boundary lands, Session B establishes its own timeline and playback — and the resumed
    /// Session-A action must not seek Session B.
    ///
    /// The seek is the dangerous half: it moves audio the rider is listening to, and it does so
    /// without touching any state Session B's drift ladder would notice, so the next cadence tick
    /// would measure the resulting error as *Session B's* drift and start correcting against it.
    func testAnOldSessionsParkedPauseNeverSeeksTheSessionThatReplacedIt() async {
        await build()
        await connectAsLeader()
        await playAndStart(Self.hashA)

        await player.setState(
            PlayerState(positionMs: Self.pausePositionMs, durationMs: Self.trackDurationMs, playing: true, rate: 1.0)
        )
        await player.gateCalls { call in call == .pause }
        await coordinator.pause()
        await expect("PAUSE A reached the wire") { [self] in
            await session.playbackMessages().contains(where: \.isPause)
        }
        await awaitOutboundQuiescent()
        clock.advance(to: await transportDeadline(matching: \.isPause))
        await expect("its scheduled action parked between the pause and the seek") { [self] in
            await player.isGateParked
        }
        let parked = await player.calls
        XCTAssertEqual(parked.last, .pause, "the pause happened; the seek has not")

        await boundary(generation: 2)
        let anchorB = await establishSessionB()

        let callsBefore = await player.calls
        let diagnosticsBefore = await coordinator.diagnostics
        await player.releaseGate()
        await awaitRetiredWorkSettled()

        let callsAfter = await player.calls
        XCTAssertEqual(callsAfter, callsBefore, "a retired PAUSE may not seek Session B's player")
        XCTAssertFalse(callsAfter.contains(.seek(Self.pausePositionMs)), "Session A's position never reached it")
        let diagnosticsAfter = await coordinator.diagnostics
        XCTAssertEqual(diagnosticsAfter, diagnosticsBefore)

        // And Session B's own drift measurement is still taken against Session B's own anchor.
        let tickAtUs = clock.now() + Self.positionReportUs
        await player.setState(
            PlayerState(
                positionMs: (tickAtUs - anchorB) / 1_000, durationMs: Self.trackDurationMs, playing: true, rate: 1.0
            )
        )
        let ticksBefore = await coordinator.diagnostics.correctionTickCount
        clock.advance(to: tickAtUs)
        await expect("one cadence tick completed") { [self] in
            await coordinator.diagnostics.correctionTickCount > ticksBefore
        }
        let drift = await coordinator.diagnostics.localDriftMs
        XCTAssertEqual(drift, 0, "Session B is measured against Session B")
        let hardSeeks = await coordinator.diagnostics.hardSeekCount
        XCTAssertEqual(hardSeeks, 0, "so nothing on the ladder fired")
    }

    // MARK: - A4-IOS-4: stop, then clear the local queue

    /// **A4-IOS-4.** A `NEXT` that runs off the end of the queue is `stop` **then** clear the local
    /// selection. Session A parks inside the player's stop; Session B then materialises a track of
    /// its own. The resumed Session-A action must not clear Session B's local selection — which on
    /// a phone is the Now Playing entry, the lock-screen metadata and what the Phase 3 UI shows.
    func testAnOldSessionsParkedStopNeverClearsTheSessionThatReplacedItsSelection() async {
        await build()
        await connectAsLeader()
        await playAndStart(Self.hashA)

        await player.gateCalls { call in call == .stop }
        await coordinator.next()
        await expect("NEXT A reached the wire") { [self] in
            await session.playbackMessages().contains(where: \.isNext)
        }
        await awaitOutboundQuiescent()
        clock.advance(to: await transportDeadline(matching: \.isNext))
        await expect("its scheduled action parked between the stop and the clear") { [self] in
            await player.isGateParked
        }
        let parked = await player.calls
        XCTAssertEqual(parked.last, .stop, "the stop happened; the local-queue clear has not")

        await boundary(generation: 2)
        _ = await establishSessionB()

        let callsBefore = await player.calls
        let queueBefore = await coordinator.queueState
        let diagnosticsBefore = await coordinator.diagnostics
        XCTAssertEqual(queueBefore.currentItem?.trackHash, Self.hashY, "Session B has a materialised track")

        await player.releaseGate()
        await awaitRetiredWorkSettled()

        let callsAfter = await player.calls
        XCTAssertFalse(
            callsAfter.dropFirst(callsBefore.count).contains(.clearSelection),
            "a retired stop may not clear Session B's local selection"
        )
        XCTAssertEqual(callsAfter, callsBefore, "and may perform no other effect either")
        let queueAfter = await coordinator.queueState
        XCTAssertEqual(queueAfter, queueBefore)
        let diagnosticsAfter = await coordinator.diagnostics
        XCTAssertEqual(diagnosticsAfter, diagnosticsBefore)

        await assertSessionBStillWorks()
    }

    // MARK: - A4-IOS-5: the correction ladder

    /// **A4-IOS-5.** The *player* half of ADR-004's ladder was never vulnerable and this test says
    /// so: every `DriftAction` is exactly one effect — `setRate`, `setRate`, `seek`, `setRate` —
    /// and A1 Finding F already put the diagnostics, the hard-seek budget and the outbound
    /// `PLAYBACK_STATE` behind a second proof taken after that effect returns.
    ///
    /// **The tick around it was** (Amendment A4 Finding F). `tickOnce` awaited `applyCorrection`
    /// and then incremented `correctionTickCount` unconditionally, so a tick belonging to a retired
    /// session — parked inside a rate nudge across the whole boundary — woke up and moved the live
    /// session's counter. Exactly A3 Finding C's shape, one function further along.
    func testACorrectionParkedInsideItsOnlyPlayerCallHasNoEffectOnTheSessionThatReplacedIt() async {
        await build()
        await connectAsLeader()
        let deadlineA = await playAndStart(Self.hashA)

        // Enough drift for ADR-004's first tier, and a parked `setRate` to hold the correction open.
        await player.gateCalls { call in
            if case .setRate = call { return true }
            return false
        }
        let tickAtUs = clock.now() + Self.positionReportUs
        let expectedMs = (tickAtUs - deadlineA) / 1_000
        await player.setState(
            PlayerState(
                positionMs: expectedMs + Self.nudgeDriftMs, durationMs: Self.trackDurationMs, playing: true, rate: 1.0
            )
        )
        clock.advance(to: tickAtUs)
        await expect("the correction parked inside its rate nudge") { [self] in await player.isGateParked }

        await boundary(generation: 2)
        _ = await establishSessionB()

        let callsBefore = await player.calls
        let diagnosticsBefore = await coordinator.diagnostics
        let sentBefore = await session.playbackMessages().count
        await player.releaseGate()
        await awaitRetiredWorkSettled()

        let callsAfter = await player.calls
        XCTAssertEqual(callsAfter, callsBefore, "a retired correction drives Session B's player not at all")
        let diagnosticsAfter = await coordinator.diagnostics
        XCTAssertEqual(diagnosticsAfter, diagnosticsBefore, "and writes none of Session B's diagnostics")
        XCTAssertEqual(
            diagnosticsAfter.playbackRate, DriftController.rateNormal, "Session B's rate is exactly 1.0"
        )
        let sentAfter = await session.playbackMessages().count
        XCTAssertEqual(sentAfter, sentBefore, "and puts nothing on Session B's wire")
    }

    // MARK: - Same-session controls

    /// The control every case above needs: **the same seam, with no boundary, still completes.** A4
    /// adds proofs between sub-effects; it must not stop a legitimate pre-roll from seeking, and it
    /// must not stop a legitimate `PAUSE` from seeking after it pauses.
    func testWithinOneSessionEveryStepOfACompoundStillRuns() async {
        await build()
        await connectAsLeader()

        await player.gateCalls { call in call == .load(Self.hashA) }
        await coordinator.playSynchronized(Self.hashA)
        await expect("the pre-roll parked inside its load") { [self] in await player.isGateParked }
        await player.releaseGate()
        await expect("and its seek still followed") { [self] in
            await player.calls == FakeSyncPlayer.preRoll(Self.hashA, 0)
        }
        await awaitOutboundQuiescent()
        clock.advance(to: await playDeadline(for: Self.hashA))
        await expect("and the scheduled start still fired") { [self] in await player.calls.contains(.start) }

        await player.setState(
            PlayerState(positionMs: Self.pausePositionMs, durationMs: Self.trackDurationMs, playing: true, rate: 1.0)
        )
        await player.gateCalls { call in call == .pause }
        await coordinator.pause()
        await awaitOutboundQuiescent()
        clock.advance(to: await transportDeadline(matching: \.isPause))
        await expect("the PAUSE parked between its two effects") { [self] in await player.isGateParked }
        await player.releaseGate()
        await expect("and its seek still followed") { [self] in
            await player.calls.last == .seek(Self.pausePositionMs)
        }
        let state = await coordinator.diagnostics.syncState
        XCTAssertEqual(state, .synced, "both steps ran and the command counts as landed")
    }

    // MARK: - Fixtures

    /// Session A: leader, clock ready, `LEAD` clamped to its 2 s maximum so its deadlines outlive
    /// the boundary, and every track this file needs playable on both devices.
    private func connectAsLeader() async {
        await session.setClock(SessionClockEstimate(offsetToLeaderUs: 0, rttP95Us: 8_000, ready: true))
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

    /// Session A issues a `PLAY`, the transport confirms it, and the local apply parks inside the
    /// **decoder load** — strictly between the materialisation and the seek that used to follow it
    /// unconditionally.
    private func parkSessionAInsideItsDecoderLoad() async {
        await player.gateCalls { call in call == .load(Self.hashA) }
        await coordinator.playSynchronized(Self.hashA)
        await expect("PLAY A reached the wire, so A2 committed its command_seq") { [self] in
            await !session.playbackMessages().filter(\.isPlay).isEmpty
        }
        await expect("its pre-roll is parked inside the decoder load") { [self] in await player.isGateParked }
        let calls = await player.calls
        XCTAssertEqual(calls, [.select(Self.hashA), .load(Self.hashA)], "the seek has not happened yet")
    }

    /// A full `PLAY` that reaches its scheduled start, leaving a live timeline and a live epoch.
    ///
    /// - Returns: the session instant the start was scheduled for.
    @discardableResult
    private func playAndStart(_ hash: ContentHash) async -> Int64 {
        await coordinator.playSynchronized(hash)
        await expect("the pre-roll completed") { [self] in
            await player.calls.contains(.seek(0))
        }
        await awaitOutboundQuiescent()
        let deadline = await playDeadline(for: hash)
        let startsBefore = await player.calls.filter { $0 == .start }.count
        clock.advance(to: deadline)
        await expect("and the start fired") { [self] in
            await player.calls.filter { $0 == .start }.count > startsBefore
        }
        await expect("and the command counts as landed") { [self] in
            await coordinator.diagnostics.syncState == .synced
        }
        return deadline
    }

    /// A full authentication boundary: the link drops, the generation moves, a new session opens.
    private func boundary(generation: Int64) async {
        await coordinator.handleLinkLost()
        await session.setGeneration(generation)
        // Session B stamps ordinary 120 ms deadlines, so its own work lands promptly.
        await session.setRtt(8_000)
        await coordinator.handleConnected(isLocalLeader: true)
        await awaitOutboundQuiescent()
    }

    /// Session B, distinguishable from Session A at every point A could touch: a three-item queue
    /// with the middle item current, its own timeline, its own live playback epoch, and playing.
    ///
    /// - Returns: the session instant Session B's `PLAY` was stamped for.
    @discardableResult
    private func establishSessionB() async -> Int64 {
        await enqueueAndSettle(Self.hashX)
        await coordinator.playSynchronized(Self.hashY)
        await expect("Session B's PLAY reached the wire") { [self] in
            await session.playbackMessages().contains { message in
                if case .play(_, let hash, _, _) = message { return hash == Self.hashY }
                return false
            }
        }
        await awaitOutboundQuiescent()
        await expect("Session B selected its current item") { [self] in
            await coordinator.queueState.currentItem?.trackHash == Self.hashY
        }
        await enqueueAndSettle(Self.hashZ)
        let deadline = await playDeadline(for: Self.hashY)
        // Counted, not merely contained: Session A has usually already started something, so
        // `contains(.start)` would answer true before Session B had done anything at all — and the
        // "before" snapshot every assertion here rests on would then be taken too early.
        let startsBefore = await player.calls.filter { $0 == .start }.count
        clock.advance(to: deadline)
        await expect("Session B started") { [self] in
            await player.calls.filter { $0 == .start }.count > startsBefore
        }
        // `.start` is recorded inside the step; `markSynced` and the schedule-error measurement
        // follow it. Waiting for the state is what makes Session B's diagnostics settled.
        await expect("and Session B is tracking its own timeline") { [self] in
            await coordinator.diagnostics.syncState == .synced
        }
        // The player state Session B's own timeline implies, so a later tick measures zero drift
        // unless something moved the player behind Session B's back.
        await player.setState(PlayerState(positionMs: 0, durationMs: Self.trackDurationMs, playing: true, rate: 1.0))
        return deadline
    }

    /// Session B's own next command still works end to end — the fence retired Session A, not
    /// Session B.
    private func assertSessionBStillWorks() async {
        await coordinator.seek(positionMs: Self.sessionBSeekMs)
        await expect("Session B's own SEEK reached the wire") { [self] in
            await session.playbackMessages().contains(where: \.isSeek)
        }
        await awaitOutboundQuiescent()
        clock.advance(to: await transportDeadline(matching: \.isSeek))
        await expect("and still reaches the player") { [self] in
            await player.calls.last == .seek(Self.sessionBSeekMs)
        }
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

    /// The deadline of the most recent transport command matching `shape`. The leader's offset is
    /// zero, so a session instant is a local monotonic instant.
    private func transportDeadline(matching shape: KeyPath<PlaybackMessage, Bool>) async -> Int64 {
        let deadlines = await session.playbackMessages().compactMap { message -> Int64? in
            guard message[keyPath: shape] else { return nil }
            return SyncPlaybackCoordinator.headerOf(message)?.effectiveAtSessionUs
        }
        guard let last = deadlines.last else {
            XCTFail("no matching command ever reached the wire")
            return 0
        }
        return last
    }

    // MARK: - Waiting

    /// Gives work released from a **retired** operation its full opportunity to run before a
    /// "nothing changed" assertion is taken.
    ///
    /// A bounded yield budget, for the reason ADR-024 Amendment A3 already recorded: correctly
    /// fenced work produces no observable effect, so there is nothing exact to wait *on* — any
    /// signal precise enough to await would itself be an effect the fence exists to prevent. What
    /// makes it credible is the pre-fix run, where the two `applyTransport` cases fail at this exact
    /// budget against unmodified production code.
    private func awaitRetiredWorkSettled() async {
        await settle(400)
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

    /// Past ADR-004's nudge threshold and well inside its hard-seek one, so tier one fires.
    private static let nudgeDriftMs: Int64 = 60
}

/// Cheap shape predicates, so the tests read as "a RESUME reached the wire" rather than as a
/// `switch`.
private extension PlaybackMessage {
    var isPlay: Bool { if case .play = self { return true }; return false }
    var isSeek: Bool { if case .seek = self { return true }; return false }
    var isPause: Bool { if case .pause = self { return true }; return false }
    var isResume: Bool { if case .resume = self { return true }; return false }
    var isNext: Bool { if case .next = self { return true }; return false }
}
