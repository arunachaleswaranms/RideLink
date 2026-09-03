import Foundation
import RideLinkCore
import XCTest
@testable import RideLinkPlatform

/// `VoiceController` driven entirely by fakes: no WebRTC, no microphone, no network.
///
/// The negotiation *decisions* are pinned by `protocol/vectors/voice-fsm/` on both platforms
/// (`VoiceNegotiation`). What these tests are about is the half a pure table cannot express — that the
/// effects actually happen, in the right order, and that a torn-down generation's callbacks cannot touch
/// the next one.
///
/// The Kotlin mirror is `com.ridelink.network.voice.VoiceControllerTest`, asserting the same behaviours.
final class VoiceControllerTests: XCTestCase {
    private let gen1 = "11111111111111111111111111111111"
    private let gen2 = "22222222222222222222222222222222"
    private let gen3 = "33333333333333333333333333333333"
    private let foreign = "abababababababababababababababab"
    private let offerSdp = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\na=setup:actpass\r\n"
    private let answerSdp = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\na=setup:active\r\n"
    private let candidate = "candidate:1 1 udp 1 192.0.2.11 51234 typ host"

    // MARK: - start / offer / answer

    func testTheLeaderOffersOnStartVoiceAndTheFollowerOnlyStatesIntent() async throws {
        let harness = try await Harness(isLocalLeader: true, ids: gen1, gen2, gen3)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")

        let audioCalls = await harness.audio.recordedCalls()
        XCTAssertEqual(audioCalls, ["open"], "the capture path is opened exactly once")
        let engineCalls = await harness.engine.recordedCalls()
        XCTAssertTrue(engineCalls.contains("start(\(gen1))"), "the peer connection names the generation")
        // Phase 2b: the intercom gate is the single source of `VOICE_STATE.mic_muted`, so opening the
        // capture path changes that field and sends a second `negotiating` frame saying so. Both frames
        // are truthful — before capture opened this side genuinely was transmitting silence — and
        // PROTOCOL §7.4 sends `VOICE_STATE` on change, so the count is not the invariant. What this test
        // is about is that the **offerer offers and the follower does not**, so it asserts the state
        // values rather than how many frames carried them.
        let states = await harness.sentStates()
        XCTAssertEqual(Set(states.map(\.0)), [.negotiating])
        XCTAssertEqual(
            states.map(\.2),
            [true, false],
            "mic_muted goes true (capture not yet open) then false (open, full duplex)"
        )
        let diagnostics = await harness.controller.currentDiagnostics()
        XCTAssertEqual(diagnostics.status, .negotiating)
        XCTAssertEqual(diagnostics.role, .offerer)
        await harness.controller.shutdown()
    }

    func testTheFollowerNeverCreatesAnOffer() async throws {
        let harness = try await Harness(isLocalLeader: false, ids: gen1, gen2, gen3)
        await harness.controller.start()
        try await harness.awaitAudioCall("open")
        try await Task.sleep(nanoseconds: 150_000_000)

        let followerCalls = await harness.engine.recordedCalls()
        XCTAssertFalse(followerCalls.contains("createOffer"), "PROTOCOL §7.3: only the leader offers")
        let states = await harness.sentStates()
        XCTAssertEqual(states.count, 1, "but it must still state its intent")
        XCTAssertEqual(states[0].0, .negotiating)
        XCTAssertNil(states[0].1, "the answerer holds no generation yet")
        await harness.controller.shutdown()
    }

    func testAnOfferCreatedByTheEngineIsSentAndTheAnswerCompletesTheExchange() async throws {
        let harness = try await Harness(isLocalLeader: true, ids: gen1, gen2, gen3)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")

        await harness.engine.emit(.offerCreated(voiceSessionId: VoiceSessionId(gen1), sdp: offerSdp))
        try await harness.awaitSent { if case .offer = $0 { return true } else { return false } }

        harness.controller.submit(.answer(voiceSessionId: VoiceSessionId(gen1), sdp: answerSdp))
        try await harness.awaitEngineCall("applyRemote(ANSWER)")
        let afterAnswer = await harness.controller.currentDiagnostics()
        XCTAssertEqual(afterAnswer.status, .connecting)

        await harness.engine.emit(
            .transportStateChanged(voiceSessionId: VoiceSessionId(gen1), state: .connected)
        )
        try await harness.awaitStatus(.active)
        let states = await harness.sentStates()
        XCTAssertTrue(states.contains { $0.0 == .active }, "the peer is told when voice goes active")
        await harness.controller.shutdown()
    }

    // MARK: - trickle ICE

    func testACandidateArrivingBeforeTheAnswerIsQueuedAndAppliedOnTheAnswer() async throws {
        let harness = try await Harness(isLocalLeader: true, ids: gen1, gen2, gen3)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")
        await harness.engine.emit(.offerCreated(voiceSessionId: VoiceSessionId(gen1), sdp: offerSdp))
        try await harness.awaitSent { if case .offer = $0 { return true } else { return false } }

        harness.controller.submit(
            .iceCandidate(voiceSessionId: VoiceSessionId(gen1), candidate: candidate, sdpMid: "0", sdpMlineIndex: 0)
        )
        try await harness.awaitQueued(1)
        let beforeAnswerCalls = await harness.engine.recordedCalls()
        XCTAssertFalse(
            beforeAnswerCalls.contains { $0.hasPrefix("addRemoteCandidate") },
            "nothing may be applied before the remote description"
        )

        harness.controller.submit(.answer(voiceSessionId: VoiceSessionId(gen1), sdp: answerSdp))
        try await harness.awaitEngineCall("addRemoteCandidate(0)")
        let drained = await harness.controller.currentDiagnostics()
        XCTAssertEqual(drained.queuedCandidates, 0, "the queue is drained, not merely read")
        await harness.controller.shutdown()
    }

    func testACandidateArrivingAfterTheAnswerIsAppliedImmediately() async throws {
        let harness = try await Harness(isLocalLeader: true, ids: gen1, gen2, gen3)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")
        await harness.engine.emit(.offerCreated(voiceSessionId: VoiceSessionId(gen1), sdp: offerSdp))
        harness.controller.submit(.answer(voiceSessionId: VoiceSessionId(gen1), sdp: answerSdp))
        try await harness.awaitEngineCall("applyRemote(ANSWER)")

        harness.controller.submit(
            .iceCandidate(voiceSessionId: VoiceSessionId(gen1), candidate: candidate, sdpMid: nil, sdpMlineIndex: 3)
        )
        try await harness.awaitEngineCall("addRemoteCandidate(3)")
        await harness.controller.shutdown()
    }

    func testALocallyGatheredCandidateIsTrickledToThePeer() async throws {
        let harness = try await Harness(isLocalLeader: true, ids: gen1, gen2, gen3)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")

        await harness.engine.emit(
            .localCandidateGathered(
                voiceSessionId: VoiceSessionId(gen1), candidate: candidate, sdpMid: "0", sdpMlineIndex: 0
            )
        )
        try await harness.awaitSent { if case .iceCandidate = $0 { return true } else { return false } }
        await harness.controller.shutdown()
    }

    /// PROTOCOL §7.6: an `srflx` candidate cannot legitimately occur, so it is surfaced, not ignored.
    func testANonHostCandidateTypeIsReportedInDiagnostics() async throws {
        let harness = try await Harness(isLocalLeader: true, ids: gen1, gen2, gen3)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")
        let beforeSrflx = await harness.controller.currentDiagnostics()
        XCTAssertFalse(beforeSrflx.unexpectedCandidateTypeSeen)

        await harness.engine.emit(
            .localCandidateGathered(
                voiceSessionId: VoiceSessionId(gen1),
                candidate: "candidate:1 1 udp 1 198.51.100.9 3478 typ srflx raddr 192.0.2.11 rport 51234",
                sdpMid: "0",
                sdpMlineIndex: 0
            )
        )
        try await harness.awaitCondition { await harness.controller.currentDiagnostics().unexpectedCandidateTypeSeen }
        await harness.controller.shutdown()
    }

    // MARK: - mute

    func testMuteDisablesTheSenderAndUnmuteRestoresIt() async throws {
        let harness = try await Harness(isLocalLeader: true, ids: gen1, gen2, gen3)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")

        // Awaited on the observable state rather than on an engine call name. Phase 2b tells the engine
        // the gate's value whenever a peer connection is built (so the track's enabled state is a
        // consequence of the policy, not of a constructor default), which means both call names are
        // already in the log by this point and a name-based await would prove nothing.
        await harness.controller.setMicrophoneMuted(true)
        try await harness.awaitCondition { await harness.controller.currentDiagnostics().micMuted }
        let mutedTrue = await harness.engine.mutedState()
        XCTAssertEqual(mutedTrue, true)
        let mutedStates = await harness.sentStates()
        XCTAssertTrue(mutedStates.contains { $0.2 }, "the peer is told the mic is muted")

        await harness.controller.setMicrophoneMuted(false)
        try await harness.awaitCondition { await !harness.controller.currentDiagnostics().micMuted }
        let mutedFalse = await harness.engine.mutedState()
        XCTAssertEqual(mutedFalse, false)
        await harness.controller.shutdown()
    }

    // MARK: - teardown, and the difference between the two kinds

    func testEndVoiceReleasesTheMediaStackAndTheCaptureDevice() async throws {
        let harness = try await Harness(isLocalLeader: true, ids: gen1, gen2, gen3)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")

        await harness.controller.stop()
        try await harness.awaitEngineCall("release")

        let endCalls = await harness.engine.recordedCalls()
        XCTAssertTrue(endCalls.contains("stop"), "the peer connection is closed")
        let endAudioCalls = await harness.audio.recordedCalls()
        XCTAssertEqual(endAudioCalls, ["open", "close"], "capture is released on a deliberate stop")
        let states = await harness.sentStates()
        XCTAssertTrue(
            states.contains { $0.0 == .closed },
            "PROTOCOL §7.4: `closed` is the teardown signal, and the peer is told before the socket goes"
        )
        await harness.controller.shutdown()
    }

    /// ARCHITECTURE §6.3/§6.4, and the reason `VoiceEngine.stop` and `release` are separate calls: a
    /// control blip must not close a capture path whose reopening is an audible Bluetooth route change.
    func testAControlLinkLossDropsMediaButNeverTheCaptureDevice() async throws {
        let harness = try await Harness(isLocalLeader: true, ids: gen1, gen2, gen3)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")
        let sentBefore = await harness.transport.sentSignals().count

        await harness.controller.onControlLinkLost()
        try await harness.awaitEngineCall("stop")
        try await Task.sleep(nanoseconds: 150_000_000)

        let blipEngineCalls = await harness.engine.recordedCalls()
        XCTAssertFalse(blipEngineCalls.contains("release"), "the media factory and capture path must survive")
        let blipAudioCalls = await harness.audio.recordedCalls()
        XCTAssertEqual(blipAudioCalls, ["open"], "the audio session must not be closed by a link blip")
        let stillOpen = await harness.audio.isOpen()
        XCTAssertTrue(stillOpen)
        let sentAfter = await harness.transport.sentSignals().count
        XCTAssertEqual(sentAfter, sentBefore, "there is no link to send a VOICE_STATE on")
        let blipDiagnostics = await harness.controller.currentDiagnostics()
        XCTAssertTrue(blipDiagnostics.localAudioOpen, "the user's consent for this segment survives")
        await harness.controller.shutdown()
    }

    func testVoiceCanBeRebuiltAfterALinkLossWithAFreshGeneration() async throws {
        let harness = try await Harness(isLocalLeader: true, ids: gen1, gen2, gen3)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")
        await harness.controller.onControlLinkLost()
        try await harness.awaitEngineCall("stop")

        await harness.controller.start()
        try await harness.awaitEngineCall("start(\(gen2))")
        let diagnostics = await harness.controller.currentDiagnostics()
        XCTAssertTrue(
            diagnostics.voiceSessionPrefix?.contains(String(gen2.prefix(6))) == true,
            "a rebuild is a fresh negotiation (PROTOCOL §7.8), not a resumed one"
        )
        XCTAssertGreaterThanOrEqual(diagnostics.rebuildCount, 1)
        await harness.controller.shutdown()
    }

    // MARK: - the generation guard, applied to callbacks

    /// PROTOCOL §7.8's stale-callback rule, as a behavioural test rather than a table row.
    func testAStaleEngineCallbackCannotActivateTheNextGeneration() async throws {
        let harness = try await Harness(isLocalLeader: true, ids: gen1, gen2, gen3)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")
        await harness.controller.onControlLinkLost()
        try await harness.awaitEngineCall("stop")
        await harness.controller.start()
        try await harness.awaitEngineCall("start(\(gen2))")
        let sentBefore = await harness.transport.sentSignals().count

        // The old generation's peer connection reports an offer and then a connection.
        await harness.engine.emit(.offerCreated(voiceSessionId: VoiceSessionId(gen1), sdp: offerSdp))
        await harness.engine.emit(
            .transportStateChanged(voiceSessionId: VoiceSessionId(gen1), state: .connected)
        )
        try await Task.sleep(nanoseconds: 200_000_000)

        let sentAfterStale = await harness.transport.sentSignals().count
        XCTAssertEqual(sentAfterStale, sentBefore, "a stale callback must send nothing")
        let diagnostics = await harness.controller.currentDiagnostics()
        XCTAssertEqual(diagnostics.status, .negotiating, "and must not mark the new generation active")
        XCTAssertGreaterThanOrEqual(
            diagnostics.droppedSignals[.staleEngineCallback] ?? 0, 2,
            "each stale callback is counted, not silently discarded"
        )
        await harness.controller.shutdown()
    }

    func testASignalForAForeignGenerationIsDroppedAndCounted() async throws {
        let harness = try await Harness(isLocalLeader: true, ids: gen1, gen2, gen3)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")

        harness.controller.submit(.answer(voiceSessionId: VoiceSessionId(foreign), sdp: answerSdp))
        try await harness.awaitCondition {
            (await harness.controller.currentDiagnostics().droppedSignals[.generationMismatch] ?? 0) > 0
        }
        let foreignCalls = await harness.engine.recordedCalls()
        XCTAssertFalse(foreignCalls.contains("applyRemote(ANSWER)"))
        await harness.controller.shutdown()
    }

    /// PROTOCOL §7.3: the offerer must refuse an offer it should never have received.
    func testAnOfferSentToTheOffererIsRefusedAsARoleViolation() async throws {
        let harness = try await Harness(isLocalLeader: true, ids: gen1, gen2, gen3)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")

        harness.controller.submit(.offer(voiceSessionId: VoiceSessionId(gen1), sdp: offerSdp))
        try await harness.awaitCondition {
            (await harness.controller.currentDiagnostics().droppedSignals[.roleViolation] ?? 0) > 0
        }
        let roleCalls = await harness.engine.recordedCalls()
        XCTAssertFalse(roleCalls.contains("applyRemote(OFFER)"))
        await harness.controller.shutdown()
    }

    // MARK: - graceful degradation

    /// FR-025: a denied microphone must not crash and must not silently pretend to have one.
    func testARefusedAudioSessionDoesNotCrashAndIsVisibleInDiagnostics() async throws {
        let harness = try await Harness(isLocalLeader: true, ids: gen1, gen2, gen3, audioOpens: false)
        await harness.controller.start()
        try await Task.sleep(nanoseconds: 200_000_000)

        let refusedCalls = await harness.audio.recordedCalls()
        XCTAssertEqual(refusedCalls, ["open"])
        let refusedOpen = await harness.audio.isOpen()
        XCTAssertFalse(refusedOpen)
        await harness.controller.shutdown()
    }

    func testTheAudioRouteIsPublishedToDiagnosticsAsThePlatformReportsIt() async throws {
        let harness = try await Harness(isLocalLeader: true, ids: gen1, gen2, gen3)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")

        await harness.audio.publish(
            AudioRouteSnapshot(
                endpointClass: .bluetooth,
                microphoneOpen: true,
                effectiveOutputProfile: .duplexWideband,
                effectiveInputProfile: .duplexWideband,
                profileCoupling: .inputForcesOutput
            )
        )
        try await harness.awaitCondition {
            await harness.controller.currentDiagnostics().route.endpointClass == .bluetooth
        }
        // ADR-016's whole point: with the mic open on Bluetooth the honest answer is "reduced".
        let routeDiagnostics = await harness.controller.currentDiagnostics()
        XCTAssertEqual(routeDiagnostics.route.mediaQuality, .reduced)
        await harness.controller.shutdown()
    }

    // MARK: - harness

    private final class Harness {
        let controller: VoiceController
        let engine: FakeVoiceEngine
        let audio: FakeVoiceAudioSession
        let transport: RecordingVoiceTransport

        /// **`policy` defaults to Mode A (full duplex), not to the production default.**
        ///
        /// These tests are about the negotiation table's *wiring* — who offers, what reaches the engine,
        /// what a stale callback cannot do — and full duplex is the policy in which "Start Voice, then
        /// talk" has exactly the shape Phase 2a's assertions describe: capture opens and outbound audio
        /// flows, so `VOICE_STATE.mic_muted` never changes and the frame counts below are literal.
        ///
        /// Under the production default (Mode C, PTT — ARCHITECTURE §6.3) capture opening leaves this
        /// side transmitting nothing, so the gate correctly sends one more `VOICE_STATE` to say so. That
        /// is Phase 2b behaviour and `VoiceControllerIntercomTests` is where it is asserted, rather than
        /// blurring it into these.
        init(
            isLocalLeader: Bool,
            ids: String...,
            audioOpens: Bool = true,
            policy: IntercomPolicy = .modeA
        ) async throws {
            engine = FakeVoiceEngine()
            audio = FakeVoiceAudioSession()
            if !audioOpens {
                await audio.setOpenResult(.failure(VoiceAudioSessionError(.micPermissionDenied)))
            }
            transport = RecordingVoiceTransport()
            let generator = SequencedVoiceSessionIds(ids[0], ids[1], ids[2])
            controller = VoiceController(
                engine: engine,
                audioSession: audio,
                transport: transport,
                isLocalLeader: isLocalLeader,
                localTrackId: "ridelink-voice",
                newVoiceSessionId: { generator.next() }
            )
            await controller.attach()
            // Offered before any test body runs, and the mailbox drains intercom commands ahead of voice
            // inputs, so the policy is in force before the body's first `start()` is reduced.
            controller.selectPolicy(policy)
        }

        /// `(state, voiceSessionId, micMuted)` for every `VOICE_STATE` the controller decided to send.
        func sentStates() async -> [(VoiceWireState, VoiceSessionId?, Bool)] {
            await transport.sentSignals().compactMap { signal in
                guard case .state(let id, let state, let micMuted, _) = signal else { return nil }
                return (state, id, micMuted)
            }
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

        func awaitQueued(_ count: Int) async throws {
            try await awaitCondition { await self.controller.currentDiagnostics().queuedCandidates >= count }
        }

        func awaitStatus(_ status: VoiceStatus) async throws {
            try await awaitCondition { await self.controller.currentDiagnostics().status == status }
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
}
