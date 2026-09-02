import Foundation
import RideLinkCore
import XCTest
@testable import RideLinkPlatform

/// `VoiceController` driven through the real `VoiceInputMailbox` under flooding conditions an
/// authenticated-but-compromised peer could actually produce.
///
/// `VoiceInputMailboxTests` (RideLinkCore) already exhausts the mailbox's own bound/coalesce/priority
/// logic in isolation. This file is the half that only shows up once the mailbox is wired into a live
/// actor: that flooding cannot wedge or crash it, that `stop`/`onControlLinkLost` always get through,
/// and that a forced degrade leaves the controller in exactly the same safe state a real control-link
/// blip would. The Kotlin mirror is `com.ridelink.network.voice.VoiceControllerMailboxTest`.
///
/// Several tests build the controller with `attachImmediately: false` and flood it **before** calling
/// `attach()`. That is deliberate, not a workaround: `attach()` is what starts the single consumer
/// `Task`, so nothing is ever consumed until it runs — which is what makes a critical-lane overflow a
/// certainty here rather than a race a fast machine's cooperative thread pool could occasionally win by
/// draining just as fast as the flood arrives (`VoiceInputMailbox.offer` bounds the mailbox
/// synchronously regardless, but *whether an eviction actually happens* depends on that race).
final class VoiceControllerMailboxTests: XCTestCase {
    private let gen1 = "11111111111111111111111111111111"
    private let sdp = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\n"

    /// Acceptance criterion B: ICE buffering stays bounded end to end. The mailbox's own capacity is
    /// exhausted directly in `VoiceInputMailboxTests`; what only shows up with a live controller is
    /// that the live backlog *surfaced in diagnostics* never exceeds the protocol bound, before or
    /// after the answer lets the queue drain -- true regardless of how the flood races the consumer,
    /// since `offer` bounds the mailbox synchronously at every single call.
    func testFloodingVoiceIceBeforeTheAnswerCannotBypassTheProtocolsQueuedCandidateBound() async throws {
        let harness = try await Harness(isLocalLeader: true)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")

        await harness.engine.emit(.offerCreated(voiceSessionId: VoiceSessionId(gen1), sdp: sdp))
        try await harness.awaitSent { if case .offer = $0 { return true } else { return false } }

        // Flooded well before the answer, so every one of these would have queued for trickle ICE
        // under the old, unbounded design.
        for i in 0..<Self.floodCount {
            harness.controller.submit(
                .iceCandidate(voiceSessionId: VoiceSessionId(gen1), candidate: "candidate:\(i) typ host", sdpMid: nil, sdpMlineIndex: 0)
            )
        }
        try await harness.awaitCondition { await harness.controller.currentDiagnostics().queuedCandidates >= 1 }
        try await Task.sleep(nanoseconds: 150_000_000)

        let midFlood = await harness.controller.currentDiagnostics()
        XCTAssertLessThanOrEqual(
            midFlood.queuedCandidates,
            VoiceBounds.maxQueuedCandidates,
            "PendingCandidates' own bound must hold even after a pre-reducer mailbox flood"
        )

        harness.controller.submit(.answer(voiceSessionId: VoiceSessionId(gen1), sdp: sdp))
        try await harness.awaitEngineCall("applyRemote(ANSWER)")
        try await harness.awaitCondition { await harness.controller.currentDiagnostics().queuedCandidates == 0 }

        let candidateCalls = await harness.engine.recordedCalls().filter { $0.hasPrefix("addRemoteCandidate") }
        XCTAssertLessThanOrEqual(
            candidateCalls.count,
            VoiceBounds.maxQueuedCandidates,
            "the mailbox's ICE lane must not have let more than the protocol bound ever reach the engine"
        )
        await harness.controller.shutdown()
    }

    /// Acceptance criteria C and D: the safety valve always gets through, and it never kills capture.
    func testStopRemainsProcessableWhileVoiceStateFloodsIn() async throws {
        let harness = try await Harness(isLocalLeader: false)
        await harness.controller.start()
        try await harness.awaitAudioCall("open")

        for _ in 0..<Self.floodCount {
            harness.controller.submit(.state(voiceSessionId: VoiceSessionId(gen1), state: .active, micMuted: false, mode: .continuous))
        }
        await harness.controller.stop()

        try await harness.awaitAudioCall("close")
        let diagnostics = await harness.controller.currentDiagnostics()
        XCTAssertEqual(diagnostics.status, .idle, "capture was released, so the reducer must have actually run the deliberate-stop path")
        await harness.controller.shutdown()
    }

    /// ADR-020 Amendment A3, acceptance criterion G: a peer's terminal `VOICE_STATE` must reach the
    /// real `VoiceNegotiation` reducer -- not be lost to a later ordinary update coalescing over it in
    /// the mailbox -- and must go through the reducer's *remote*-teardown path (`teardownFromPeer`),
    /// which is distinct from a deliberate local `stop()`: capture survives either way, but only a
    /// local stop's `.releaseLocalAudio` action can ever release it. Kotlin mirror:
    /// `com.ridelink.network.voice.VoiceControllerMailboxTest`'s equivalently named test.
    func testARemoteClosedSurvivesAFloodOfOrdinaryActiveUpdatesAndTearsTheSessionDownToIdle() async throws {
        let harness = try await Harness(isLocalLeader: true)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")

        harness.controller.submit(.state(voiceSessionId: VoiceSessionId(gen1), state: .closed, micMuted: false, mode: .continuous))
        for _ in 0..<Self.floodCount {
            harness.controller.submit(.state(voiceSessionId: VoiceSessionId(gen1), state: .active, micMuted: false, mode: .continuous))
        }
        try await harness.awaitCondition { await harness.controller.currentDiagnostics().status == .idle }

        let engineCalls = await harness.engine.recordedCalls()
        XCTAssertFalse(
            engineCalls.contains("release"),
            "a remote CLOSED must go through teardownFromPeer, never the local stop's capture-release action"
        )
        let audioOpen = await harness.audio.isOpen()
        XCTAssertTrue(audioOpen, "capture survives a remote teardown exactly as it survives a link loss")
        await harness.controller.shutdown()
    }

    /// The FAILED half of the same property, with an ordinary CONNECTING flood behind it instead.
    func testARemoteFailedSurvivesAFloodOfOrdinaryConnectingUpdatesAndYieldsFailed() async throws {
        let harness = try await Harness(isLocalLeader: true)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")

        harness.controller.submit(.state(voiceSessionId: VoiceSessionId(gen1), state: .failed, micMuted: false, mode: .continuous))
        for _ in 0..<Self.floodCount {
            harness.controller.submit(.state(voiceSessionId: VoiceSessionId(gen1), state: .connecting, micMuted: false, mode: .continuous))
        }
        try await harness.awaitCondition { await harness.controller.currentDiagnostics().status == .failed }

        let engineCalls = await harness.engine.recordedCalls()
        XCTAssertFalse(engineCalls.contains("release"))
        let audioOpen = await harness.audio.isOpen()
        XCTAssertTrue(audioOpen)
        await harness.controller.shutdown()
    }

    /// Acceptance criterion H: a flood of terminal peer states, on its own, cannot grow the mailbox
    /// without bound -- it forces the same safe, already-proven degrade a critical-lane overflow does,
    /// never releases capture, and never kills the control session.
    func testAFloodOfTerminalPeerStatesBeyondTheLanesCapacityForcesASafeDegradeNeverReleasingCapture() async throws {
        let harness = try await Harness(isLocalLeader: false, attachImmediately: false)
        await harness.controller.start()

        // Every one of these is submitted before `attach()` is ever called, so nothing can drain and
        // free terminal-lane space between submissions -- deterministic overflow, well past
        // `VoiceInputMailbox.terminalPeerStateCapacity` (8).
        for i in 0..<Self.terminalOverflowFloodCount {
            let wire: VoiceWireState = i % 2 == 0 ? .closed : .failed
            harness.controller.submit(.state(voiceSessionId: VoiceSessionId(gen1), state: wire, micMuted: false, mode: .continuous))
        }
        await harness.controller.attach()
        try await harness.awaitAudioCall("open")

        try await harness.awaitCondition {
            (await harness.controller.currentDiagnostics().droppedSignals[.inputMailboxOverflow] ?? 0) > 0
        }
        let engineCalls = await harness.engine.recordedCalls()
        XCTAssertFalse(engineCalls.contains("release"), "a terminal-lane overflow must degrade like a link loss, never release capture")
        let audioOpen = await harness.audio.isOpen()
        XCTAssertTrue(audioOpen, "this user's consent for the ride segment must survive the degrade")
        await harness.controller.shutdown()
    }

    /// Acceptance criteria A, D and E, together: overflow degrades safely, capture and control both
    /// survive.
    func testAnAuthenticatedFloodOfDistinctOffersTriggersASafeDegradeNotACrashAndNeverReleasesCapture() async throws {
        let harness = try await Harness(isLocalLeader: false, attachImmediately: false)
        await harness.controller.start()

        // Every one of these is submitted before `attach()` is ever called, so nothing can drain and
        // free critical-lane space between submissions -- the flood genuinely outruns the consumer,
        // which is exactly the scenario an authenticated-but-compromised peer flooding faster than
        // this controller can consume would produce.
        for i in 0..<Self.overflowFloodCount {
            harness.controller.submit(.offer(voiceSessionId: Self.genAt(Self.offset + i), sdp: sdp))
        }
        await harness.controller.attach()
        try await harness.awaitAudioCall("open")

        try await harness.awaitCondition {
            (await harness.controller.currentDiagnostics().droppedSignals[.inputMailboxOverflow] ?? 0) > 0
        }
        let diagnostics = await harness.controller.currentDiagnostics()
        let engineCalls = await harness.engine.recordedCalls()
        XCTAssertFalse(engineCalls.contains("release"), "a mailbox overflow must degrade like a link loss, never release capture")
        let audioOpen = await harness.audio.isOpen()
        XCTAssertTrue(audioOpen, "this user's consent for the ride segment must survive the degrade")
        XCTAssertTrue(diagnostics.localAudioOpen)

        // The controller is still alive and useful afterward -- the degrade was not a wedge.
        await harness.controller.start()
        let audioCalls = await harness.audio.recordedCalls()
        XCTAssertGreaterThanOrEqual(audioCalls.filter { $0 == "open" }.count, 1)
        await harness.controller.shutdown()
    }

    /// Acceptance criterion: teardown completes even when the mailbox is under heavy concurrent load.
    ///
    /// Deliberately **not** the `attachImmediately: false` trick the other tests use: `start()`'s
    /// effect (`localAudioOpen = true`) has to have genuinely been applied by the reducer before
    /// `stop()` can do anything — the reducer's own no-op guard correctly treats a stop with nothing
    /// yet started as a no-op, so a `stop()` racing an *unapplied* `start()` proves nothing. Once a
    /// real negotiation is live, the flood itself is safe to race against the consumer: `stop()`
    /// always occupies the always-accepting teardown lane regardless of how the race resolves.
    func testTeardownCompletesEvenWhenTheMailboxIsUnderHeavyConcurrentLoad() async throws {
        let harness = try await Harness(isLocalLeader: true)
        await harness.controller.start()
        try await harness.awaitAudioCall("open")

        for i in 0..<Self.overflowFloodCount {
            harness.controller.submit(.offer(voiceSessionId: Self.genAt(Self.offset + i), sdp: sdp))
        }
        for i in 0..<Self.floodCount {
            harness.controller.submit(.iceCandidate(voiceSessionId: VoiceSessionId(gen1), candidate: "c\(i)", sdpMid: nil, sdpMlineIndex: 0))
        }
        for _ in 0..<Self.floodCount {
            harness.controller.submit(.state(voiceSessionId: VoiceSessionId(gen1), state: .active, micMuted: false, mode: .continuous))
        }
        await harness.controller.stop()

        try await harness.awaitAudioCall("close")
        let diagnostics = await harness.controller.currentDiagnostics()
        XCTAssertEqual(diagnostics.status, .idle)
        await harness.controller.shutdown()
    }

    /// A voice session started after a flood-induced degrade must start clean, not corrupted.
    ///
    /// Uses the `attachImmediately: false` trick to guarantee the overflow deterministically, which
    /// means nothing has genuinely negotiated yet when it fires (`start()`'s own effect has not been
    /// applied by the reducer at flood time either — see the previous test's note). So this proves the
    /// mailbox recovers cleanly for the **first** real Start Voice after a flood, not a rebuild after
    /// an established call; `VoiceControllerTests.testVoiceCanBeRebuiltAfterALinkLossWithAFreshGeneration`
    /// already covers an genuine rebuild's `rebuildCount` behaviour without any mailbox flooding involved.
    func testStartingAfterAPreNegotiationOverflowBeginsACleanNegotiation() async throws {
        let harness = try await Harness(isLocalLeader: true, attachImmediately: false)

        // No start() here: every one of these lands before the consumer exists, so the critical lane
        // is full and overflowing purely from the flood, with nothing yet applied to the state.
        for i in 0..<Self.overflowFloodCount {
            harness.controller.submit(.offer(voiceSessionId: Self.genAt(Self.offset + i), sdp: sdp))
        }
        await harness.controller.attach()

        try await harness.awaitCondition {
            (await harness.controller.currentDiagnostics().droppedSignals[.inputMailboxOverflow] ?? 0) > 0
        }
        // Let the residual backlog (all role-violation drops, since this side never became the
        // answerer) fully settle before judging the state a clean slate. Exactly `criticalCapacity`
        // offers were ever accepted into the lane -- the rest were refused outright by the overflow
        // check above and never reach the reducer at all.
        try await harness.awaitCondition {
            (await harness.controller.currentDiagnostics().droppedSignals[.roleViolation] ?? 0) >= VoiceInputMailbox.criticalCapacity
        }
        let settledStatus = await harness.controller.currentDiagnostics().status
        XCTAssertEqual(settledStatus, .idle, "nothing ever started, so the flood must settle back to idle")

        await harness.controller.start()
        try await harness.awaitCondition { await harness.controller.currentDiagnostics().status == .negotiating }
        let engineCalls = await harness.engine.recordedCalls()
        XCTAssertTrue(engineCalls.contains("createOffer"), "the new negotiation must genuinely reach the engine, not just the state")
        await harness.controller.shutdown()
    }

    // MARK: - harness

    private final class Harness {
        let controller: VoiceController
        let engine: FakeVoiceEngine
        let audio: FakeVoiceAudioSession
        let transport: RecordingVoiceTransport

        init(isLocalLeader: Bool, attachImmediately: Bool = true) async throws {
            engine = FakeVoiceEngine()
            audio = FakeVoiceAudioSession()
            transport = RecordingVoiceTransport()
            let generator = SequencedVoiceSessionIds("11111111111111111111111111111111")
            controller = VoiceController(
                engine: engine,
                audioSession: audio,
                transport: transport,
                isLocalLeader: isLocalLeader,
                localTrackId: "ridelink-voice",
                newVoiceSessionId: { generator.next() }
            )
            if attachImmediately { await controller.attach() }
        }

        func awaitEngineCall(_ call: String) async throws {
            try await awaitCondition { await self.engine.recordedCalls().contains(call) }
        }

        func awaitAudioCall(_ call: String) async throws {
            try await awaitCondition { await self.audio.recordedCalls().contains(call) }
        }

        func awaitSent(_ predicate: @escaping @Sendable (VoiceSignal) -> Bool) async throws {
            try await awaitCondition { await self.transport.sentSignals().contains(where: predicate) }
        }

        func awaitCondition(_ condition: @escaping () async -> Bool) async throws {
            let deadline = Date().addingTimeInterval(5.0)
            while Date() < deadline {
                if await condition() { return }
                try await Task.sleep(nanoseconds: 5_000_000)
            }
            XCTFail("condition not met within the timeout")
        }
    }

    private static let floodCount = 500
    private static let overflowFloodCount = 200
    /// Well past `VoiceInputMailbox.terminalPeerStateCapacity` (8); deterministic overflow.
    private static let terminalOverflowFloodCount = 20
    /// Well clear of `gen1` or anything a real negotiation would otherwise use in these tests.
    private static let offset = 10_000_000

    /// A distinct, deterministic 32-hex-digit id for test index `n` -- never random, never reused.
    private static func genAt(_ n: Int) -> VoiceSessionId {
        VoiceSessionId(String(format: "%032d", n))
    }
}
