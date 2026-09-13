import AVFoundation
import Foundation
import RideLinkCore
import XCTest

@testable import RideLinkPlatform

/// STATUS §4 problem 41 — the iOS half of Phase 5's scheduled start and drift nudge, against the
/// **production** `AVAudioEnginePlayer` and the **production** `MonotonicDeadlineSleeper`.
///
/// Android's `SyncScheduledPlaybackTest` proves the same ground against a real `ExoPlayer` on the
/// emulator. The iOS equivalent had never run anywhere, and STATUS recorded that as needing the
/// simulator. **It does not.** `AVAudioEngine`, `AVAudioPlayerNode` and `AVAudioUnitVarispeed` are
/// all available on macOS — which is exactly why `AVAudioEnginePlayer` carries no `#if os(iOS)`
/// gate and why `AVAudioEnginePlayerTests` already decodes real AAC under `swift test`. So this runs
/// in CI on every push rather than only where a simulator exists.
///
/// **What this does and does not prove.** It proves the software: that a future deadline does not
/// fire early, that a start lands at its monotonic deadline within a measured error, that a ±0.2 %
/// nudge reaches the real `AVAudioUnitVarispeed` and changes how fast frames are actually consumed,
/// and that correction returns to exactly 1.0. It proves **nothing** about audible alignment between
/// two phones: there is no second device, no Bluetooth hop and no speaker anywhere in it. TEST_PLAN
/// §5.2's S-01…S-12 remain the only thing that can close that, and the schedule error measured here
/// is a *software* figure in the same sense `SyncPlaybackDiagnostics.lastScheduleErrorUs` is.
final class Phase5RealPlayerTests: XCTestCase {
    private var player: AVAudioEnginePlayer!
    private var states: AsyncStream<PlayerState>!
    private var continuation: AsyncStream<PlayerState>.Continuation!

    override func setUp() async throws {
        player = AVAudioEnginePlayer()
        let (stream, continuation) = AsyncStream<PlayerState>.makeStream()
        states = stream
        self.continuation = continuation
        await player.setStateSink { [continuation] state in continuation.yield(state) }
    }

    override func tearDown() async throws {
        await player.release()
        continuation.finish()
    }

    // MARK: - ADR-004's rate-nudge tier, on the real varispeed node

    /// Requirement 4 and 5 together: the ±0.002 the drift ladder asks for reaches the production
    /// node, and correction ends at **exactly** 1.0 — the brief §38 invariant, asserted as exact
    /// equality rather than an epsilon, because "never leave a nudge behind" is the whole rule.
    func testDriftNudgeReachesTheVarispeedNodeAndRestoresToExactlyOne() async throws {
        try await loadNormal()

        for rate in [1.002, 0.998, 1.002] {
            await player.execute(.setRate(rate: rate))
            let nudged = try await firstState { abs($0.rate - rate) < 1e-9 }
            XCTAssertEqual(nudged.rate, rate, accuracy: 1e-9, "the ladder's nudge must reach the real player")
        }

        await player.execute(.setRate(rate: 1.0))
        let restored = try await firstState { $0.rate == 1.0 }
        XCTAssertEqual(restored.rate, 1.0, "correction always ends at exactly 1.0 — not 0.999999 (ADR-004, brief §38)")
    }

    /// The assertion above would pass against a player that merely *stored* the number. This one
    /// would not.
    ///
    /// It measures **wall-clock time to the real segment-completion callback** rather than anything
    /// derived from `positionMs`. That matters: `positionMs` comes from
    /// `playerNode.playerTime(forNodeTime:)`, the player node's own output timeline, which is not a
    /// reliable witness to what a downstream unit does with those frames. Time-to-completion is, and
    /// it is the quantity a drift correction actually manipulates — at rate `r` a segment drains in
    /// `duration / r`.
    ///
    /// The fixture is ~0.5 s, so the window is the track itself; a fixed measurement window longer
    /// than the fixture would compare two runs that had both simply ended.
    func testTheVarispeedNodeIsActuallyInTheSignalPath() async throws {
        let atNormal = try await secondsToPlayOut(rate: 1.0)
        let atDouble = try await secondsToPlayOut(rate: 2.0)
        let atHalf = try await secondsToPlayOut(rate: 0.5)

        print("Phase5RealPlayerTests: play-out seconds — 1.0x=\(atNormal), 2.0x=\(atDouble), 0.5x=\(atHalf)")
        XCTAssertLessThan(
            atDouble, atNormal * 0.7,
            "at rate 2.0 the real AVAudioUnitVarispeed must drain the segment far faster (1.0x=\(atNormal)s, 2.0x=\(atDouble)s)"
        )
        XCTAssertGreaterThan(
            atHalf, atNormal * 1.4,
            "at rate 0.5 it must take far longer (1.0x=\(atNormal)s, 0.5x=\(atHalf)s)"
        )
    }

    /// Loads the fixture fresh, sets `rate` on the production node, plays, and returns how long the
    /// real player took to reach its end-of-segment state.
    private func secondsToPlayOut(rate: Double) async throws -> Double {
        try await loadNormal()
        await player.execute(.setRate(rate: rate))
        let started = Date()
        await player.execute(.play)
        _ = try await firstState { !$0.playing && $0.durationMs > 0 && $0.positionMs >= $0.durationMs }
        let elapsed = Date().timeIntervalSince(started)
        await player.execute(.setRate(rate: 1.0))
        return elapsed
    }

    // MARK: - the ADR-024 A4 step sequence, on the real player

    /// Requirements 6 and 7: a hard seek lands, and `load -> seek -> start` — the order
    /// `SyncPlaybackCoordinator` actually issues once ADR-024 A4 split `prepare` into single-effect
    /// steps — leaves the real player playing from the seeked position, not from zero.
    func testLoadThenSeekThenStartPlaysFromTheSeekedPosition() async throws {
        try await loadNormal()

        await player.execute(.seek(positionMs: 1_500))
        let seeked = try await firstState { $0.positionMs >= 1_400 }
        XCTAssertGreaterThanOrEqual(seeked.positionMs, 1_400, "a hard seek must move the real player's position")

        await player.execute(.play)
        let playing = try await firstState { $0.playing && $0.positionMs >= 1_500 }
        XCTAssertGreaterThanOrEqual(
            playing.positionMs, 1_500,
            "playback must continue from the seek, never restart at zero"
        )
        await player.execute(.pause)
    }

    /// Requirement 8: `stop` followed by a fresh `load` leaves the engine reusable. `clearSelection`
    /// is the local-queue half and has no player effect, so the player-side claim is exactly this.
    func testStopLeavesThePlayerReusable() async throws {
        try await loadNormal()
        await player.execute(.play)
        _ = try await firstState { $0.playing }
        await player.execute(.stop)
        let stopped = try await firstState { !$0.playing && $0.positionMs == 0 }
        XCTAssertEqual(stopped.positionMs, 0, "stop resets position")

        try await loadNormal()
        await player.execute(.play)
        let replaying = try await firstState { $0.playing && $0.positionMs > 0 }
        XCTAssertTrue(replaying.playing, "the engine must still be usable after a stop")
    }

    /// Requirement 9 and 10, as far as a laptop can see them: repeated load/seek/play/stop cycles
    /// neither wedge the engine nor accumulate anything that stops the next cycle working. A leaked
    /// position-tick `Task` or a stale scheduled-segment completion would surface here as a hang or
    /// as a state from the previous cycle.
    func testRepeatedCyclesLeaveTheEngineWorking() async throws {
        for cycle in 0..<8 {
            try await loadNormal()
            await player.execute(.seek(positionMs: 500))
            await player.execute(.play)
            let playing = try await firstState { $0.playing }
            XCTAssertTrue(playing.playing, "cycle \(cycle) must still start")
            await player.execute(.stop)
            _ = try await firstState { !$0.playing }
        }
    }

    // MARK: - the scheduled start, on the production sleeper

    /// Requirements 1, 2 and 3: `MonotonicDeadlineSleeper` is, by its own doc, "the one place Phase 5
    /// touches real time". A start armed for a future monotonic deadline must not fire early, and
    /// must land close enough to it that the residual is a decoder/Bluetooth question rather than a
    /// scheduling one. The error is **measured and reported**, never assumed — the same discipline
    /// Android's emulator run applied (it measured 1.4–3.1 ms).
    func testScheduledStartWaitsForItsDeadlineAndThenStartsTheRealPlayer() async throws {
        try await loadNormal()
        let sleeper = MonotonicDeadlineSleeper(monotonicNowUs: Self.monotonicNowUs)

        let leadUs: Int64 = 300_000
        let deadlineUs = Self.monotonicNowUs() + leadUs

        // Nothing may start before the deadline, and the proof is an observation part-way through the
        // wait rather than the absence of a state we never looked for.
        let earlyCheck = Task { [player] in
            try? await Task.sleep(nanoseconds: 100_000_000)
            return await player!.state.playing
        }

        await sleeper.sleep(untilLocalMonoUs: deadlineUs)
        let wokeAtUs = Self.monotonicNowUs()
        await player.execute(.play)

        let startedEarly = await earlyCheck.value
        XCTAssertFalse(startedEarly, "a future scheduled start must not fire immediately")

        let playing = try await firstState { $0.playing }
        XCTAssertTrue(playing.playing, "the scheduled start must actually reach the real player")

        let scheduleErrorUs = wokeAtUs - deadlineUs
        XCTAssertGreaterThanOrEqual(scheduleErrorUs, 0, "the sleeper must never wake before its deadline")
        XCTAssertLessThan(
            scheduleErrorUs, Self.maxScheduleErrorUs,
            "measured schedule error \(scheduleErrorUs) us — a software figure only, and no claim about audible alignment"
        )
        print("Phase5RealPlayerTests: measured scheduled-start wake error = \(scheduleErrorUs) us")
        await player.execute(.pause)
    }

    /// Repeated arms, because one is not evidence of anything about leaks. Each iteration measures
    /// its own error so a drift in the numbers would be visible rather than averaged away.
    func testRepeatedScheduledStartsStayWithinTheirDeadlines() async throws {
        let sleeper = MonotonicDeadlineSleeper(monotonicNowUs: Self.monotonicNowUs)
        var errors: [Int64] = []
        for _ in 0..<10 {
            let deadlineUs = Self.monotonicNowUs() + 60_000
            await sleeper.sleep(untilLocalMonoUs: deadlineUs)
            errors.append(Self.monotonicNowUs() - deadlineUs)
        }
        // The portable claim, asserted strictly: this is the sleeper's own loop condition.
        XCTAssertTrue(errors.allSatisfy { $0 >= 0 }, "no wake may precede its deadline; errors=\(errors)")
        // The structural claim, deliberately loose — see `maxScheduleErrorUs`.
        XCTAssertTrue(
            errors.allSatisfy { $0 < Self.maxScheduleErrorUs },
            "a wake missed its deadline by more than any scheduling stall explains; errors=\(errors)"
        )
        print("Phase5RealPlayerTests: repeated wake errors (us) = \(errors)")
    }

    /// A deadline already in the past must start immediately rather than waiting a whole cycle —
    /// `ScheduledCommand` maps an overdue command to "now", and the sleeper must not undo that.
    func testAnAlreadyPassedDeadlineDoesNotWait() async throws {
        let sleeper = MonotonicDeadlineSleeper(monotonicNowUs: Self.monotonicNowUs)
        let before = Self.monotonicNowUs()
        await sleeper.sleep(untilLocalMonoUs: before - 1_000_000)
        let elapsed = Self.monotonicNowUs() - before
        XCTAssertLessThan(elapsed, 20_000, "an overdue deadline must return at once, not sleep")
    }

    // MARK: - helpers

    /// **The two claims here are not equally portable, and only one of them is about RideLink.**
    ///
    /// *Never early* is a property of `MonotonicDeadlineSleeper` itself: it loops until
    /// `deadlineUs - now <= 0`, so a wake before the deadline would be a real defect on any machine.
    /// That is asserted strictly, per sample, and must never be relaxed.
    ///
    /// *How late* is a property of the **host scheduler**, not of this code. `Task.sleep` on an idle
    /// laptop lands within 0.2–5.0 ms; on a shared, virtualised GitHub runner the same code measured
    /// **5.8–115.4 ms** — which is what an earlier 50 ms bound here caught, and it was catching the
    /// runner, not a regression. Asserting a tight upper bound would therefore be testing somebody
    /// else's machine.
    ///
    /// So the bound is deliberately far above any plausible scheduling stall and exists only to catch
    /// a **structural** regression — a sleeper that waits a whole extra coarse cycle, ignores the
    /// fine-stepped tail, or sleeps for the wrong quantity entirely. Those miss by hundreds of
    /// milliseconds or more, not by tens. The number that is actually *evidence* is the one every run
    /// prints, and `docs/TEST_PLAN.md` §4.4 records both ranges rather than only the flattering one.
    private static let maxScheduleErrorUs: Int64 = 500_000

    private static let monotonicNowUs: @Sendable () -> Int64 = {
        Int64(DispatchTime.now().uptimeNanoseconds / 1_000)
    }

    private func loadNormal() async throws {
        let url = try TestMedia.url("normal.m4a")
        await player.execute(
            .load(
                localEntryId: LocalEntryId(UUID().uuidString.lowercased()),
                location: LocalTrackLocation(uri: url.absoluteString)
            )
        )
        _ = try await firstState { $0.durationMs > 0 }
    }

    /// How far the reported position moves over `seconds` of wall clock.
    private func measureAdvance(over seconds: TimeInterval) async throws -> Int64 {
        let start = await player.state.positionMs
        try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
        let end = await player.state.positionMs
        return end - start
    }

    private func firstState(
        timeout: TimeInterval = 15,
        where predicate: @escaping @Sendable (PlayerState) -> Bool
    ) async throws -> PlayerState {
        let stream = states!
        let deadline = Date().addingTimeInterval(timeout)
        for await state in stream where predicate(state) {
            return state
        }
        if Date() > deadline { XCTFail("state stream ended before the predicate matched") }
        throw Phase5RealPlayerTestsError.stateNotObserved
    }

    private enum Phase5RealPlayerTestsError: Error { case stateNotObserved }
}
