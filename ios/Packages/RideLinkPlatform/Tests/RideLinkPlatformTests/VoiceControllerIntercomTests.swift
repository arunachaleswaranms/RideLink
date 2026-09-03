import XCTest

@testable import RideLinkCore
@testable import RideLinkPlatform

/// Phase 2b's intercom lifecycle, driven through the **real** `VoiceController` against fakes.
///
/// `IntercomVectorTests` proves the pure gate. This proves the wiring, which is the part a pure table
/// cannot express: that a PTT press reaches the engine's track and **nothing else**, that fifty of them
/// leave the capture device exactly as they found it, that a policy switch is announced on both wire
/// planes, and that a link blip and a rebuild do not reopen capture.
///
/// **None of this is evidence that voice works.** A fake engine proves the controller; real Opus over
/// real DTLS-SRTP is proven separately by `VoiceEngineLoopbackTests`, which is real media but has no
/// microphone, no speaker and no Bluetooth in it. TEST_PLAN A-10 is the hardware form of the central
/// assertion below and remains pending.
///
/// The mirror is `com.ridelink.network.voice.VoiceControllerIntercomTest`.
final class VoiceControllerIntercomTests: XCTestCase {
    private let pressCount = 50
    private let muteCycles = 10

    // MARK: - the capture-open invariant (this phase's brief §32; TEST_PLAN A-10's laptop half)

    /// **The regression test this phase exists to leave behind.**
    ///
    /// Start Intercom opens capture once. Fifty press/release cycles follow. The capture device is never
    /// opened again and never closed, and every press reaches the engine as a track-enable — because
    /// thrashing a Bluetooth endpoint between its media and duplex profiles per utterance is the single
    /// worst thing this product can do to music (ARCHITECTURE §6.3).
    ///
    /// It would fail immediately if a future change routed PTT through `VoiceAudioSession` instead of
    /// through the track.
    func testFiftyPttPressesNeverReopenOrCloseTheCaptureDevice() async throws {
        let harness = try await Harness(policy: .modeC)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")
        var counts = await harness.audio.captureCounts()
        XCTAssertEqual(1, counts.opened, "Start Intercom opens capture exactly once")

        let generationBefore = await harness.controller.currentDiagnostics().voiceSessionPrefix
        await harness.engine.clearCalls()

        for _ in 0..<pressCount {
            harness.controller.setPushToTalkHeld(true)
            try await harness.awaitEngineCall("setMicrophoneMuted(false)")
            harness.controller.setPushToTalkHeld(false)
            try await harness.awaitEngineCall("setMicrophoneMuted(true)")
            await harness.engine.clearCalls()
        }

        counts = await harness.audio.captureCounts()
        XCTAssertEqual(1, counts.opened, "\(pressCount) presses must not reopen capture")
        XCTAssertEqual(0, counts.closed, "\(pressCount) presses must not close capture")
        let stillOpen = await harness.audio.isOpen()
        XCTAssertTrue(stillOpen, "capture is still open for the ride segment")
        let after = await harness.controller.currentDiagnostics()
        XCTAssertEqual(generationBefore, after.voiceSessionPrefix, "PTT must not change voice_session_id")
        let engineCalls = await harness.engine.recordedCalls()
        XCTAssertFalse(
            engineCalls.contains(where: { $0.hasPrefix("start(") || $0 == "stop" || $0 == "release" }),
            "PTT must not rebuild the peer connection"
        )
        await harness.controller.shutdown()
    }

    /// And the same for mute, which is the other thing a naive implementation would route to hardware.
    func testMuteAndUnmuteNeverTouchTheCaptureDeviceOrRebuildMedia() async throws {
        let harness = try await Harness(policy: .modeA)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")
        let generationBefore = await harness.controller.currentDiagnostics().voiceSessionPrefix
        await harness.engine.clearCalls()

        for _ in 0..<muteCycles {
            await harness.controller.setMicrophoneMuted(true)
            try await harness.awaitEngineCall("setMicrophoneMuted(true)")
            await harness.controller.setMicrophoneMuted(false)
            try await harness.awaitEngineCall("setMicrophoneMuted(false)")
        }

        let counts = await harness.audio.captureCounts()
        XCTAssertEqual(1, counts.opened)
        XCTAssertEqual(0, counts.closed)
        let after = await harness.controller.currentDiagnostics()
        XCTAssertEqual(generationBefore, after.voiceSessionPrefix)
        let engineCalls = await harness.engine.recordedCalls()
        XCTAssertFalse(
            engineCalls.contains(where: { $0.hasPrefix("start(") || $0 == "stop" || $0 == "release" }),
            "mute must not rebuild the peer connection"
        )
        await harness.controller.shutdown()
    }

    // MARK: - the gate itself, through the controller

    func testUnderPttNothingIsTransmittedUntilTheButtonIsHeld() async throws {
        let harness = try await Harness(policy: .modeC)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")
        // The gate is the single source of `mic_muted`, so the wire says "transmitting silence" from the
        // moment capture opens rather than claiming otherwise until the first press.
        try await harness.awaitDiagnostics { $0.micMuted && !$0.transmitting }

        harness.controller.setPushToTalkHeld(true)
        try await harness.awaitDiagnostics { $0.transmitting }
        let diagnostics = await harness.controller.currentDiagnostics()
        XCTAssertTrue(diagnostics.pttHeld)
        XCTAssertFalse(diagnostics.micMuted)
        await harness.controller.shutdown()
    }

    func testUnderFullDuplexTransmissionStartsAsSoonAsCaptureOpens() async throws {
        let harness = try await Harness(policy: .modeA)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")
        try await harness.awaitDiagnostics { $0.transmitting && !$0.micMuted }
        let fullDuplex = await harness.controller.currentDiagnostics().policy.fullDuplex
        XCTAssertTrue(fullDuplex)
        await harness.controller.shutdown()
    }

    /// Mode E has no intercom: nothing transmits, whatever the user presses.
    func testUnderModeENoPressTransmits() async throws {
        let harness = try await Harness(policy: .modeE)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")
        harness.controller.setPushToTalkHeld(true)
        await harness.settle()
        let diagnostics = await harness.controller.currentDiagnostics()
        XCTAssertFalse(diagnostics.transmitting)
        XCTAssertEqual(.disabled, diagnostics.intercomMode)
        await harness.controller.shutdown()
    }

    /// This phase's brief §25: backgrounding while held releases the gate, not the device.
    func testBackgroundingWhileHeldStopsTransmittingAndKeepsCaptureOpen() async throws {
        let harness = try await Harness(policy: .modeC)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")
        harness.controller.setPushToTalkHeld(true)
        try await harness.awaitDiagnostics { $0.transmitting }

        harness.controller.onAppBackgrounded()
        try await harness.awaitDiagnostics { !$0.transmitting }
        let diagnostics = await harness.controller.currentDiagnostics()
        XCTAssertFalse(diagnostics.pttHeld)
        let counts = await harness.audio.captureCounts()
        XCTAssertEqual(0, counts.closed, "the ride segment keeps its capture device")
        await harness.controller.shutdown()
    }

    /// A platform interruption wins over a held button, and it is a route fact, not a media failure.
    func testAnInterruptionStopsTransmittingWithoutClosingCapture() async throws {
        let harness = try await Harness(policy: .modeA)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")
        try await harness.awaitDiagnostics { $0.transmitting }

        await harness.audio.publish(
            AudioRouteSnapshot(interrupted: true, lastChangeReason: .interruptionBegan)
        )
        try await harness.awaitDiagnostics { !$0.transmitting }
        var counts = await harness.audio.captureCounts()
        XCTAssertEqual(0, counts.closed)

        await harness.audio.publish(
            AudioRouteSnapshot(interrupted: false, lastChangeReason: .interruptionEnded)
        )
        try await harness.awaitDiagnostics { $0.transmitting }
        counts = await harness.audio.captureCounts()
        XCTAssertEqual(0, counts.closed)
        await harness.controller.shutdown()
    }

    // MARK: - policy switching

    /// A mode change is announced on both wire planes and touches neither capture nor the generation.
    func testSwitchingPolicyAnnouncesTheNewModeOnTheWireWithoutRebuildingAnything() async throws {
        let harness = try await Harness(policy: .modeC)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")
        let generationBefore = await harness.controller.currentDiagnostics().voiceSessionPrefix
        await harness.engine.clearCalls()

        harness.controller.selectPolicy(.modeA)
        // Awaited on the observable state, not on a wire frame carrying `continuous`: the negotiation
        // table's own default mode is `continuous`, so a frame with that value may exist from before the
        // switch and a frame-based await would prove nothing.
        try await harness.awaitDiagnostics { $0.intercomMode == .continuous }
        try await harness.awaitSent { signal in
            guard case .state(_, _, _, let mode) = signal else { return false }
            return mode == .continuous
        }
        let diagnostics = await harness.controller.currentDiagnostics()
        XCTAssertEqual(generationBefore, diagnostics.voiceSessionPrefix)
        let counts = await harness.audio.captureCounts()
        XCTAssertEqual(1, counts.opened)
        XCTAssertEqual(0, counts.closed)
        let engineCalls = await harness.engine.recordedCalls()
        XCTAssertFalse(engineCalls.contains(where: { $0.hasPrefix("start(") || $0 == "stop" || $0 == "release" }))
        await harness.controller.shutdown()
    }

    /// Switching into PTT must not inherit a press from the previous policy.
    func testSwitchingFromFullDuplexToPttStopsTransmitting() async throws {
        let harness = try await Harness(policy: .modeA)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")
        try await harness.awaitDiagnostics { $0.transmitting }

        harness.controller.selectPolicy(.modeC)
        try await harness.awaitDiagnostics { !$0.transmitting && !$0.pttHeld }
        await harness.controller.shutdown()
    }

    // MARK: - reconnect and teardown

    /// PROTOCOL §7.8: a control-link blip drops the media transport and **keeps** capture, then a rebuild
    /// is a fresh generation.
    func testALinkLossKeepsCaptureAndTheRebuildDoesNotReopenIt() async throws {
        let harness = try await Harness(policy: .modeC)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")
        let firstGeneration = await harness.controller.currentDiagnostics().voiceSessionPrefix

        await harness.controller.onControlLinkLost()
        try await harness.awaitEngineCall("stop")
        var counts = await harness.audio.captureCounts()
        XCTAssertEqual(0, counts.closed, "a link blip must not close capture")
        let failureAfterLoss = await harness.controller.currentDiagnostics().lastFailure
        XCTAssertEqual(.controlLinkLost, failureAfterLoss)

        await harness.controller.start()
        try await harness.awaitDiagnostics { $0.voiceSessionPrefix != firstGeneration }
        counts = await harness.audio.captureCounts()
        XCTAssertEqual(1, counts.opened, "the rebuild reuses the open capture device")
        XCTAssertEqual(0, counts.closed)
        await harness.controller.shutdown()
    }

    /// A callback that arrives against a torn-down generation cannot transmit on the next one.
    func testAStaleMediaCallbackCannotReEnableTransmission() async throws {
        let harness = try await Harness(policy: .modeC)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")
        await harness.controller.onControlLinkLost()
        try await harness.awaitEngineCall("stop")

        await harness.engine.emit(
            .transportStateChanged(voiceSessionId: VoiceSessionId(Harness.gen1), state: .connected)
        )
        await harness.settle()
        let diagnostics = await harness.controller.currentDiagnostics()
        XCTAssertEqual(.idle, diagnostics.status, "a stale callback is inert")
        XCTAssertFalse(diagnostics.transmitting)
        await harness.controller.shutdown()
    }

    /// An explicit stop releases capture — the one case that may, because a user is present.
    func testStopIntercomReleasesCaptureAndClosesTheGate() async throws {
        let harness = try await Harness(policy: .modeA)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")
        try await harness.awaitDiagnostics { $0.transmitting }

        await harness.controller.stop()
        try await harness.awaitEngineCall("release")
        try await harness.awaitCondition { await harness.audio.captureCounts().closed == 1 }
        let diagnostics = await harness.controller.currentDiagnostics()
        XCTAssertFalse(diagnostics.transmitting)
        XCTAssertFalse(diagnostics.localAudioOpen)
        await harness.controller.shutdown()
    }

    // MARK: - failures (this phase's brief §41)

    /// A denied microphone is named, the session is untouched, and nothing transmits.
    func testADeniedMicrophoneIsReportedByNameAndNothingTransmits() async throws {
        let harness = try await Harness(policy: .modeA, audioFailure: .micPermissionDenied)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")
        await harness.settle()
        let diagnostics = await harness.controller.currentDiagnostics()
        XCTAssertEqual(.micPermissionDenied, diagnostics.lastFailure)
        XCTAssertFalse(diagnostics.transmitting, "no capture path means no transmission")
        XCTAssertFalse(diagnostics.localAudioOpen)
        let openedAfterDenial = await harness.audio.captureCounts().opened
        XCTAssertEqual(0, openedAfterDenial)
        await harness.controller.shutdown()
    }

    func testAMediaFailureIsReportedAsWebRtcFailedRatherThanAGenericFailure() async throws {
        let harness = try await Harness(policy: .modeA)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")
        await harness.engine.emit(
            .transportStateChanged(voiceSessionId: VoiceSessionId(Harness.gen1), state: .failed)
        )
        try await harness.awaitDiagnostics { $0.lastFailure == .webRtcFailed }
        let closedAfterFailure = await harness.audio.captureCounts().closed
        XCTAssertEqual(0, closedAfterFailure, "a media failure does not close capture")
        await harness.controller.shutdown()
    }

    // MARK: - setup timing

    /// The software setup figures, from a clock the test supplies. **Not latency** — mouth-to-ear is
    /// TEST_PLAN A-09/V-11 and needs hardware.
    func testSetupTimingsAreRecordedFromTheSuppliedMonotonicClock() async throws {
        let harness = try await Harness(policy: .modeA)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")
        // The fake engine records that it was asked for an offer but does not author one, so the test
        // supplies the callbacks a real `WebRtcVoiceEngine` would.
        await harness.engine.emit(.offerCreated(voiceSessionId: VoiceSessionId(Harness.gen1), sdp: "v=0\r\n"))
        await harness.engine.emit(.remoteTrackChanged(voiceSessionId: VoiceSessionId(Harness.gen1), present: true))
        try await harness.awaitDiagnostics { $0.setup.setupMs != nil }

        let setup = await harness.controller.currentDiagnostics().setup
        XCTAssertNotNil(setup.captureOpenMs, "capture-open must be timed")
        XCTAssertNotNil(setup.localDescriptionMs, "the local SDP must be timed")
        XCTAssertGreaterThanOrEqual(
            setup.setupMs ?? 0,
            setup.captureOpenMs ?? 0,
            "the end-to-end figure cannot precede a stage inside it"
        )
        await harness.controller.shutdown()
    }

    // MARK: - harness

    private final class Harness {
        static let gen1 = "11111111111111111111111111111111"
        static let gen2 = "22222222222222222222222222222222"
        static let gen3 = "33333333333333333333333333333333"

        let controller: VoiceController
        let engine: FakeVoiceEngine
        let audio: FakeVoiceAudioSession
        let transport: RecordingVoiceTransport

        init(policy: IntercomPolicy, audioFailure: VoiceFailure? = nil) async throws {
            engine = FakeVoiceEngine()
            audio = FakeVoiceAudioSession()
            if let audioFailure {
                await audio.setOpenResult(.failure(VoiceAudioSessionError(audioFailure)))
            }
            transport = RecordingVoiceTransport()
            let generator = SequencedVoiceSessionIds(Self.gen1, Self.gen2, Self.gen3)
            // A monotonic clock that advances a fixed step per read, so the setup figures are
            // deterministic and non-zero without any real time passing.
            let clock = SteppingClock()
            controller = VoiceController(
                engine: engine,
                audioSession: audio,
                transport: transport,
                isLocalLeader: true,
                localTrackId: "ridelink-voice",
                monotonicNowUs: { clock.next() },
                newVoiceSessionId: { generator.next() }
            )
            await controller.attach()
            controller.selectPolicy(policy)
        }

        func awaitEngineCall(_ call: String) async throws {
            try await awaitCondition { await self.engine.recordedCalls().contains(call) }
        }

        func awaitSent(_ predicate: @escaping @Sendable (VoiceSignal) -> Bool) async throws {
            try await awaitCondition { await self.transport.sentSignals().contains(where: predicate) }
        }

        func awaitDiagnostics(_ predicate: @escaping @Sendable (VoiceDiagnostics) -> Bool) async throws {
            try await awaitCondition { predicate(await self.controller.currentDiagnostics()) }
        }

        /// Polls rather than latches, deliberately: every path under test crosses the controller's single
        /// consumer task, and a poll with a timeout says "this happened" without the test needing to know
        /// how many drains it took.
        func awaitCondition(_ condition: @escaping () async -> Bool) async throws {
            let deadline = Date().addingTimeInterval(5)
            while Date() < deadline {
                if await condition() { return }
                try await Task.sleep(nanoseconds: 2_000_000)
            }
            let engineCalls = await engine.recordedCalls()
            let audioCalls = await audio.recordedCalls()
            XCTFail("condition not met within the timeout — engine: \(engineCalls), audio: \(audioCalls)")
        }

        /// Gives the consumer several turns, for the assertions that are about something *not* happening.
        func settle() async {
            for _ in 0..<25 { try? await Task.sleep(nanoseconds: 2_000_000) }
        }
    }

    /// A monotonic clock that advances one microsecond-step per read.
    private final class SteppingClock: @unchecked Sendable {
        private let lock = NSLock()
        private var value: Int64 = 1_000_000

        func next() -> Int64 {
            lock.lock()
            defer { lock.unlock() }
            value += 1_000
            return value
        }
    }
}
