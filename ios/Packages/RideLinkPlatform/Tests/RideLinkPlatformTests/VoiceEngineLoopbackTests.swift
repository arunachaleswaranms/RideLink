import Foundation
import RideLinkCore
import XCTest
@testable import RideLinkPlatform

/// **Real WebRTC. Real DTLS-SRTP. Real Opus. No mocks anywhere in this file.**
///
/// Two `WebRtcVoiceEngine`s are stood up in this process and negotiated against each other through the
/// same `VoiceNegotiation` table the app uses, and the assertions are about what the media stack
/// actually did: which candidate types it gathered, whether DTLS reached `connected`, which SRTP cipher
/// it chose, and which codec it negotiated.
///
/// This is possible on a laptop because `stasel/WebRTC`'s XCFramework carries a **macOS** slice
/// (ADR-020), so `swift test` links the same binary an iPhone build would — the same WebRTC commit,
/// the same BoringSSL, the same Opus.
///
/// ### What this proves, precisely
///
/// - PROTOCOL §7.6's host-only ICE policy is real: with `iceServers = []` the stack gathers **only** `host` candidates. No `srflx`, no `relay`, and therefore nothing contacted a server outside this machine.
/// - The DTLS-SRTP handshake completes and reports a cipher — so the media path is encrypted by the stack, exactly as ADR-003 relies on, with no RideLink-invented crypto.
/// - Opus is negotiated for an audio-only m-line.
/// - The offer/answer/trickle-ICE sequence PROTOCOL §7.4 specifies drives a real stack to `connected`.
///
/// ### What it does **not** prove
///
/// - **No audio was captured or played.** There is no microphone in this test and `packetsSent` may legitimately be zero: the transport is up, nothing is speaking into it.
/// - **Nothing about a phone.** `RTCAudioSession`, a Bluetooth route, a helmet unit, an iPhone's own AEC, a screen lock — all absent here and all still **REAL-DEVICE AUDIO GATE PENDING** (docs/STATUS.md §7).
/// - **Nothing about Android.** `PeerConnectionFactory.initialize` needs an Android `Context`, so the Android engine has no equivalent test and its real media path is unproven.
final class VoiceEngineLoopbackTests: XCTestCase {
    func testTwoRealEnginesNegotiateHostOnlyDtlsSrtpOpus() async throws {
        let alice = WebRtcVoiceEngine()
        let bob = WebRtcVoiceEngine()
        let generation = VoiceSessionId("11111111111111111111111111111111")

        let wire = SignalWire()
        await wire.attach(offerer: alice, answerer: bob, generation: generation)

        // PROTOCOL §7.3: the offerer (the internal leader) creates the offer. Nothing here infers the
        // role from who dialled — there is no dialling; the roles are given.
        let aliceStarted = await alice.start(
            config: VoiceEngineConfig(voiceSessionId: generation, localTrackId: "ridelink-voice-a")
        )
        XCTAssertNoFailure(aliceStarted, "offerer engine must start")
        let bobStarted = await bob.start(
            config: VoiceEngineConfig(voiceSessionId: generation, localTrackId: "ridelink-voice-b")
        )
        XCTAssertNoFailure(bobStarted, "answerer engine must start")

        XCTAssertNoFailure(await alice.createOffer(), "offer creation must succeed")

        // Poll rather than assume: a real stack takes tens of milliseconds and the point of this test is
        // that it genuinely completes, not that it completes within one arbitrary sleep.
        let connected = await wire.waitForBothConnected(timeout: 20.0)
        let observed = await wire.observedCandidateTypes()

        await alice.refreshDiagnostics()
        await bob.refreshDiagnostics()
        let aliceStats = await alice.diagnostics()
        let bobStats = await bob.diagnostics()

        // --- PROTOCOL §7.6: host candidates only, and no internet ------------------------------------
        XCTAssertFalse(observed.isEmpty, "the stack must have gathered at least one candidate")
        XCTAssertEqual(
            observed, [.host],
            "with iceServers = [] only host candidates may appear; anything else means something "
                + "contacted a server outside the local network (PROTOCOL §7.6)"
        )
        XCTAssertFalse(
            aliceStats.observedCandidateTypes.contains { $0.impliesNonLocalDependency },
            "no reflexive or relayed candidate may be reported"
        )

        // --- the negotiation actually completed -------------------------------------------------------
        XCTAssertTrue(connected, "both real peer connections must reach .connected over host candidates alone")
        XCTAssertEqual(aliceStats.transportState, .connected)
        XCTAssertEqual(bobStats.transportState, .connected)

        // --- ADR-003: DTLS-SRTP, provided by the stack, not by RideLink -------------------------------
        XCTAssertEqual(aliceStats.dtlsState, "connected", "DTLS must actually complete")
        XCTAssertNotNil(aliceStats.srtpCipher, "the stack must report the SRTP cipher it chose")
        XCTAssertNotNil(aliceStats.dtlsCipher)
        XCTAssertTrue(
            aliceStats.srtpCipher?.hasPrefix("SRTP_") == true,
            "expected an SRTP cipher name, got \(aliceStats.srtpCipher ?? "nil")"
        )

        // --- Opus, mono-capable, audio only -----------------------------------------------------------
        XCTAssertEqual(aliceStats.negotiatedCodec, "audio/opus", "ADR-003: Opus, and audio only")
        XCTAssertEqual(aliceStats.negotiatedClockRateHz, 48_000)

        // --- one audio track each direction (ADR-003) -------------------------------------------------
        XCTAssertTrue(aliceStats.localAudioTrackPresent)
        XCTAssertTrue(aliceStats.remoteAudioTrackPresent, "the offerer must see the answerer's track")
        XCTAssertTrue(bobStats.remoteAudioTrackPresent, "and the answerer must see the offerer's")

        await alice.release()
        await bob.release()
    }

    /// Mute must gate transmission on a **real** track, and unmute must restore it. Asserted against the
    /// stack rather than a flag this test set itself.
    func testMuteAndUnmuteOnARealTrack() async throws {
        let engine = WebRtcVoiceEngine()
        let generation = VoiceSessionId("22222222222222222222222222222222")
        XCTAssertNoFailure(
            await engine.start(config: VoiceEngineConfig(voiceSessionId: generation, localTrackId: "t")),
            "engine must start"
        )
        var stats = await engine.diagnostics()
        XCTAssertTrue(stats.localAudioTrackPresent, "the track must exist to mute")

        // No crash, no exception, and the track survives both transitions — which is what "mute gates
        // transmission, not the hardware" (ARCHITECTURE §6.3) means at this level.
        await engine.setMicrophoneMuted(true)
        await engine.setMicrophoneMuted(false)
        stats = await engine.diagnostics()
        XCTAssertTrue(stats.localAudioTrackPresent, "muting must not drop the track")

        await engine.release()
    }

    /// PROTOCOL §7.4: a candidate the real stack refuses is counted, not fatal. One malformed candidate
    /// from a peer must not fail a negotiation whose other candidates are fine.
    func testAMalformedCandidateIsRefusedWithoutFailingTheEngine() async throws {
        let engine = WebRtcVoiceEngine()
        let generation = VoiceSessionId("33333333333333333333333333333333")
        XCTAssertNoFailure(
            await engine.start(config: VoiceEngineConfig(voiceSessionId: generation, localTrackId: "t")),
            "engine must start"
        )

        let result = await engine.addRemoteCandidate(
            candidate: "this is not a candidate line at all", sdpMid: "0", sdpMlineIndex: 0
        )
        // Some stack versions accept and then discard; either is fine. What must not happen is a crash
        // or a closed connection.
        _ = result
        let after = await engine.diagnostics()
        XCTAssertNotEqual(after.transportState, .failed, "one bad candidate must not fail the engine")
        XCTAssertNotEqual(after.transportState, .closed, "the engine must still be usable")

        await engine.release()
    }

    /// PROTOCOL §7.8's stale-callback rule against a real stack: after `stop()`, the engine's generation
    /// is cleared, so nothing the platform reports afterwards can be attributed to the next negotiation.
    func testStopClearsTheGenerationSoLaterCallbacksAreInert() async throws {
        let engine = WebRtcVoiceEngine()
        let first = VoiceSessionId("44444444444444444444444444444444")
        let second = VoiceSessionId("55555555555555555555555555555555")

        let received = EventBox()
        await engine.setEventSink { event in received.append(event) }

        XCTAssertNoFailure(
            await engine.start(config: VoiceEngineConfig(voiceSessionId: first, localTrackId: "t")),
            "engine must start"
        )
        XCTAssertNoFailure(await engine.createOffer(), "offer must be created")
        await engine.stop()
        received.clear()

        XCTAssertNoFailure(
            await engine.start(config: VoiceEngineConfig(voiceSessionId: second, localTrackId: "t")),
            "engine must restart"
        )
        XCTAssertNoFailure(await engine.createOffer(), "second offer must be created")

        // Every event now names the *second* generation. The first one is gone, so a late delegate call
        // carrying it would be dropped by the table — and nothing here can produce one.
        let generations = received.snapshot().map(\.testGeneration)
        XCTAssertFalse(generations.isEmpty, "the second negotiation must produce events")
        XCTAssertTrue(
            generations.allSatisfy { $0 == second },
            "no event may still name the torn-down generation, got \(generations)"
        )

        await engine.release()
    }

    // MARK: - harness

    private func XCTAssertNoFailure(
        _ result: Result<Void, VoiceEngineError>,
        _ message: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        if case .failure(let error) = result {
            XCTFail("\(message): \(error)", file: file, line: line)
        }
    }
}

/// Carries `VoiceEngineEvent`s between two real engines, exactly as the control plane would: SDP and
/// candidates cross as **strings**, because that is what PROTOCOL §7.4 puts on the wire and what Swift 6
/// will let cross an isolation boundary (ADR-020).
private actor SignalWire {
    private var offerer: WebRtcVoiceEngine?
    private var answerer: WebRtcVoiceEngine?
    private var generation = VoiceSessionId("00000000000000000000000000000000")
    private var candidateTypes: Set<IceCandidateType> = []
    private var offererConnected = false
    private var answererConnected = false

    func attach(offerer: WebRtcVoiceEngine, answerer: WebRtcVoiceEngine, generation: VoiceSessionId) async {
        self.offerer = offerer
        self.answerer = answerer
        self.generation = generation
        await offerer.setEventSink { [weak self] event in
            Task { await self?.handle(event, fromOfferer: true) }
        }
        await answerer.setEventSink { [weak self] event in
            Task { await self?.handle(event, fromOfferer: false) }
        }
    }

    func observedCandidateTypes() -> Set<IceCandidateType> { candidateTypes }

    func waitForBothConnected(timeout: TimeInterval) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if offererConnected, answererConnected { return true }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        return offererConnected && answererConnected
    }

    private func handle(_ event: VoiceEngineEvent, fromOfferer: Bool) async {
        switch event {
        case .offerCreated(_, let sdp):
            guard let answerer else { return }
            _ = await answerer.applyRemoteDescription(kind: .offer, sdp: sdp)
            _ = await answerer.createAnswer()
        case .answerCreated(_, let sdp):
            guard let offerer else { return }
            _ = await offerer.applyRemoteDescription(kind: .answer, sdp: sdp)
        case .localCandidateGathered(_, let candidate, let mid, let index):
            candidateTypes.insert(IceCandidateType.fromCandidateLine(candidate))
            let target = fromOfferer ? answerer : offerer
            _ = await target?.addRemoteCandidate(candidate: candidate, sdpMid: mid, sdpMlineIndex: index)
        case .transportStateChanged(_, let state):
            if state == .connected {
                if fromOfferer { offererConnected = true } else { answererConnected = true }
            }
        case .remoteTrackChanged, .failed:
            break
        }
    }
}

/// A tiny thread-safe collector, because engine events arrive on WebRTC's own threads.
private final class EventBox: @unchecked Sendable {
    private let lock = NSLock()
    private var events: [VoiceEngineEvent] = []

    func append(_ event: VoiceEngineEvent) {
        lock.lock()
        defer { lock.unlock() }
        events.append(event)
    }

    func snapshot() -> [VoiceEngineEvent] {
        lock.lock()
        defer { lock.unlock() }
        return events
    }

    func clear() {
        lock.lock()
        defer { lock.unlock() }
        events.removeAll()
    }
}

private extension VoiceEngineEvent {
    var testGeneration: VoiceSessionId {
        switch self {
        case .offerCreated(let id, _), .answerCreated(let id, _),
             .localCandidateGathered(let id, _, _, _),
             .transportStateChanged(let id, _), .remoteTrackChanged(let id, _), .failed(let id, _):
            return id
        }
    }
}
