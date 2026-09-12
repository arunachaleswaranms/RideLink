import Foundation
import RideLinkCore
import XCTest

@testable import RideLinkPlatform

/// The regression **ADR-025** exists for: an inbound frame's authority comes from the connection it
/// was read from, and no subsystem downstream of `ControlSessionManager.handleFrame` may throw that
/// away and reconstruct authority from live session state.
///
/// Mirrors Android's `com.ridelink.network.control.RetiredSessionProvenanceTest` case for case. See
/// that file's header for the four families and the reachable interleaving each one had; the iOS
/// window is wider than Android's in every case, because `await relay.deliver(...)` is a real actor
/// hop out of the manager rather than a thread-preemption point.
///
/// **How this test produces the park** is `StaleReadGenerationTests`' construction, unchanged and
/// for its reason: nothing a test controls can suspend a task between `ControlConnection.readFrame()`
/// returning and the dispatch that follows it. So the two halves of that one step are called as two
/// statements with a **real** session boundary in between — `currentReadBinding()` is the capture
/// `readLoop` performs, and `handleFrame` is the very function it calls.
final class RetiredSessionProvenanceTests: XCTestCase {
    // MARK: - MANIFEST_* (PROTOCOL §8.1)

    /// **`docs/STATUS.md` §4 problem 44, at the seam that contained it.** Against unmodified
    /// `326a145` production sources the message below reaches the sink, and
    /// `SharedLibraryCoordinator`'s adapter reads the *successor's* `sessionEpoch` for it.
    func testAManifestFrameBoundToSessionANeverReachesSessionBsSink() async throws {
        try await twoSessionsOnOneManager { sut in
            let captured_parked = await sut.manager.currentReadBinding()
            let parked = try XCTUnwrap(captured_parked, "Session A must have a connection")
            XCTAssertEqual(parked.generation, 1, "Session A is generation 1")

            try await sut.boundaryToSecondSession()
            XCTAssertEqual(sut.manager.liveAuthenticatedGeneration(), 2, "Session B is generation 2")

            await sut.manager.handleFrame(binding: parked, envelope: Self.manifestPage())

            XCTAssertTrue(sut.manifest.received.isEmpty, "a retired session's MANIFEST_PAGE reaches no sink")
            let dropped = await sut.manager.manifestRelay().droppedRetiredGeneration()
            XCTAssertEqual(dropped, 1, "and is counted as the refusal it is")
            let rejections = await sut.manager.manifestRelay().rejectionCounts()
            XCTAssertTrue(rejections.isEmpty, "it was refused by the gate, not the codec")
        }
    }

    /// The half a naive fix breaks: **being late is not being stale.**
    func testAManifestFrameDispatchedLateWithinItsOwnLiveSessionIsStillDelivered() async throws {
        try await twoSessionsOnOneManager { sut in
            let captured_parked = await sut.manager.currentReadBinding()
            let parked = try XCTUnwrap(captured_parked)
            try await Task.sleep(nanoseconds: Self.settleNs)

            await sut.manager.handleFrame(binding: parked, envelope: Self.manifestPage())

            XCTAssertEqual(sut.manifest.received.count, 1, "the live session's own frame is delivered")
            XCTAssertEqual(sut.manifest.generations, [1], "tagged its own generation, not looked up")
        }
    }

    /// Session B's own ingress is untouched, through the same production path.
    func testSessionBsOwnManifestStillWorks() async throws {
        try await twoSessionsOnOneManager { sut in
            let captured_parked = await sut.manager.currentReadBinding()
            let parked = try XCTUnwrap(captured_parked)
            try await sut.boundaryToSecondSession()
            await sut.manager.handleFrame(binding: parked, envelope: Self.manifestPage())

            let captured_live = await sut.manager.currentReadBinding()
            let live = try XCTUnwrap(captured_live, "Session B must have a connection")
            await sut.manager.handleFrame(binding: live, envelope: Self.manifestPage())

            XCTAssertEqual(sut.manifest.received.count, 1, "B's own frame is delivered")
            XCTAssertEqual(sut.manifest.generations, [2], "as B's, and only B's")
        }
    }

    // MARK: - TRANSFER_* (PROTOCOL §8.2)

    /// `TRANSFER_OFFER` is what satisfies a requester's pending request and carries the bulk port
    /// and one-shot token a fetch is then made against (ADR-023).
    func testATransferOfferBoundToSessionANeverReachesSessionBsSink() async throws {
        try await twoSessionsOnOneManager { sut in
            let captured_parked = await sut.manager.currentReadBinding()
            let parked = try XCTUnwrap(captured_parked)
            try await sut.boundaryToSecondSession()

            await sut.manager.handleFrame(binding: parked, envelope: Self.transferOffer())

            XCTAssertTrue(sut.transfer.received.isEmpty, "a retired session's TRANSFER_OFFER reaches no sink")
            let dropped = await sut.manager.transferRelay().droppedRetiredGeneration()
            XCTAssertEqual(dropped, 1)
            let rejections = await sut.manager.transferRelay().rejectionCounts()
            XCTAssertTrue(rejections.isEmpty, "refused by the gate, not the codec")
        }
    }

    /// The other three state-changing `TRANSFER_*` messages, through the same gate.
    func testEveryStateChangingTransferTypeBoundToSessionAIsRefused() async throws {
        try await twoSessionsOnOneManager { sut in
            let captured_parked = await sut.manager.currentReadBinding()
            let parked = try XCTUnwrap(captured_parked)
            try await sut.boundaryToSecondSession()

            await sut.manager.handleFrame(binding: parked, envelope: Self.transferRequest())
            await sut.manager.handleFrame(binding: parked, envelope: Self.transferCancel())
            await sut.manager.handleFrame(binding: parked, envelope: Self.transferResult())

            XCTAssertTrue(sut.transfer.received.isEmpty)
            let dropped = await sut.manager.transferRelay().droppedRetiredGeneration()
            XCTAssertEqual(dropped, 3)

            let captured_live = await sut.manager.currentReadBinding()
            let live = try XCTUnwrap(captured_live)
            await sut.manager.handleFrame(binding: live, envelope: Self.transferRequest())
            XCTAssertEqual(sut.transfer.received.count, 1, "B's own TRANSFER_REQUEST still works")
            XCTAssertEqual(sut.transfer.generations, [2])
        }
    }

    // MARK: - VOICE_* (PROTOCOL §7)

    /// The harmful one. `VOICE_STATE { state: "closed" }` with no `voice_session_id` carries no
    /// generation claim, so `VoiceNegotiation.peerStateReceived` does **not** treat it as a
    /// mismatch — it is `teardownFromPeer`, which stops the media transport. Delivered to the
    /// retained `VoiceController` after a reconnect, a Session A frame therefore tears down
    /// **Session B's** live voice.
    func testAVoiceStateClosedBoundToSessionANeverReachesSessionBsVoiceSink() async throws {
        try await twoSessionsOnOneManager { sut in
            let captured_parked = await sut.manager.currentReadBinding()
            let parked = try XCTUnwrap(captured_parked)
            try await sut.boundaryToSecondSession()

            await sut.manager.handleFrame(binding: parked, envelope: Self.voiceStateClosed())

            XCTAssertTrue(sut.voice.received.isEmpty, "a retired session's VOICE_STATE reaches no sink")
            let dropped = await sut.manager.voiceRelay().droppedRetiredGeneration()
            XCTAssertEqual(dropped, 1)
            let rejections = await sut.manager.voiceRelay().rejectionCounts()
            XCTAssertTrue(rejections.isEmpty, "refused by the gate, not the codec")
        }
    }

    /// The other harmful one, and the reason `voice_session_id` cannot stand in for this: after
    /// `.controlLinkLost` the reducer resets to `.idle` with `voiceSessionId == nil`, which is
    /// exactly the state in which `offerReceived` **accepts** an offer naming any generation.
    func testAVoiceOfferBoundToSessionANeverReachesSessionBsVoiceSink() async throws {
        try await twoSessionsOnOneManager { sut in
            let captured_parked = await sut.manager.currentReadBinding()
            let parked = try XCTUnwrap(captured_parked)
            try await sut.boundaryToSecondSession()

            await sut.manager.handleFrame(binding: parked, envelope: Self.voiceOffer())

            XCTAssertTrue(sut.voice.received.isEmpty)
            let dropped = await sut.manager.voiceRelay().droppedRetiredGeneration()
            XCTAssertEqual(dropped, 1)

            let captured_live = await sut.manager.currentReadBinding()
            let live = try XCTUnwrap(captured_live)
            await sut.manager.handleFrame(binding: live, envelope: Self.voiceOffer())
            XCTAssertEqual(sut.voice.received.count, 1, "B's own VOICE_OFFER still works")
        }
    }

    // MARK: - AUDIO_STATE (PROTOCOL §4.4)

    /// Reachable, and therefore fixed rather than argued away: the peer-state inbox is reset per
    /// **discovery** session, not per control session, so a stale message whose `revision` exceeds
    /// the held one is accepted by the revision rule and published as the successor's peer state.
    func testAnAudioStateBoundToSessionANeverReachesSessionBsSink() async throws {
        try await twoSessionsOnOneManager { sut in
            let captured_parked = await sut.manager.currentReadBinding()
            let parked = try XCTUnwrap(captured_parked)
            try await sut.boundaryToSecondSession()

            await sut.manager.handleFrame(binding: parked, envelope: Self.audioState(revision: 5))

            XCTAssertTrue(sut.audioState.received.isEmpty, "a retired session's AUDIO_STATE reaches no sink")
            let dropped = await sut.manager.audioStateRelay().droppedRetiredGeneration()
            XCTAssertEqual(dropped, 1)

            let captured_live = await sut.manager.currentReadBinding()
            let live = try XCTUnwrap(captured_live)
            await sut.manager.handleFrame(binding: live, envelope: Self.audioState(revision: 6))
            XCTAssertEqual(sut.audioState.received.count, 1, "B's own AUDIO_STATE still works")
            XCTAssertEqual(sut.audioState.received.first?.revision, 6)
        }
    }

    // MARK: - PONG (PROTOCOL §6)

    /// `PING`/`PONG` are in the pre-authentication allowlist by design, so they never reach the
    /// generation gate — and before ADR-025 nothing else bound them to a connection either. Every
    /// effect of `handlePong` is **manager-level**: `lastPongAtMonoUs` (what `keepaliveLoop`
    /// measures liveness against), `clockTracker.recordRtt` (unconditional — it does not depend on a
    /// matching pending ping) and the `rttMs` diagnostic. `promote` calls `clockTracker.reset()`, so
    /// a retired connection's round trip lands in the **successor's fresh** RTT window, which is
    /// what ARCHITECTURE §7.2's `LEAD = max(120 ms, 4 × rtt_p95)` is computed from.
    ///
    /// A full `ClockSync.rttWindowCapacity` of them is injected so the assertion is on the window's
    /// *whole* contents rather than on where one sample happens to fall in a p95.
    func testAPongReadFromARetiredConnectionCannotTouchTheSuccessorsClock() async throws {
        try await twoSessionsOnOneManager { sut in
            let captured_parked = await sut.manager.currentReadBinding()
            let parked = try XCTUnwrap(captured_parked)
            try await sut.boundaryToSecondSession()

            for index in 0..<Self.rttWindowCapacity {
                await sut.manager.handleFrame(binding: parked, envelope: Self.pong(index: index))
            }

            let refused = await sut.manager.retiredConnectionFrames
            XCTAssertEqual(refused, Self.rttWindowCapacity, "each one refused and counted")
            let p95 = await sut.manager.sessionClockRttP95Us()
            XCTAssertTrue(
                p95 == nil || p95! < Self.unreachableByARealSampleUs,
                "Session B's RTT window must hold only Session B's own round trips, was \(String(describing: p95))"
            )
        }
    }

    /// The other half: Session B's own `PONG` still measures Session B's link.
    func testSessionBsOwnPongStillRecords() async throws {
        try await twoSessionsOnOneManager { sut in
            try await sut.boundaryToSecondSession()
            let captured_live = await sut.manager.currentReadBinding()
            let live = try XCTUnwrap(captured_live)

            await sut.manager.handleFrame(binding: live, envelope: Self.pong(index: 0))

            let refused = await sut.manager.retiredConnectionFrames
            XCTAssertEqual(refused, 0, "nothing was refused")
            let captured_p95 = await sut.manager.sessionClockRttP95Us()
            let p95 = try XCTUnwrap(captured_p95)
            XCTAssertTrue(p95 >= Self.absurdRttUs, "a PONG on the live connection still reaches the window, was \(p95)")
        }
    }

    // MARK: - Harness

    /// One `ControlSessionManager` under test, kept alive across a session boundary — the same shape
    /// `StaleReadGenerationTests` uses for Phase 5, with the four non-Phase-5 sinks attached.
    private final class Sut: @unchecked Sendable {
        let manager: ControlSessionManager
        let session: FsmSession
        let manifest: ManifestGenerationSpy
        let transfer: TransferGenerationSpy
        let voice: VoiceSpy
        let audioState: AudioStateProvenanceSpy
        private let peer: TestPeer
        private let counterpart: TestPeer
        private let port: UInt16
        private let clock: ProvenanceClock
        private var counterparts: [ControlSessionManager] = []

        init(
            manager: ControlSessionManager, session: FsmSession, manifest: ManifestGenerationSpy,
            transfer: TransferGenerationSpy, voice: VoiceSpy, audioState: AudioStateProvenanceSpy,
            peer: TestPeer, counterpart: TestPeer, port: UInt16, clock: ProvenanceClock
        ) {
            self.manager = manager
            self.session = session
            self.manifest = manifest
            self.transfer = transfer
            self.voice = voice
            self.audioState = audioState
            self.peer = peer
            self.counterpart = counterpart
            self.port = port
            self.clock = clock
        }

        func connectFirstSession() async throws { try await connectSession() }

        /// Ends Session A with a real `BYE` and brings a second real TLS session up on the same
        /// manager, so `authenticationGeneration` advances 1 -> 2 exactly as a reconnect does.
        func boundaryToSecondSession() async throws {
            await counterparts.last?.shutdown()
            try await poll { await self.manager.currentReadBinding() == nil }
            try await connectSession()
        }

        private func connectSession() async throws {
            let clockRef = clock
            let target = counterpart.manager(monotonicNowUs: { clockRef.next() })
            counterparts.append(target)
            let before = session.count { if case .connected = $0 { return true } else { return false } }
            let targetPort = try await target.startListening(local: counterpart.local)
            await manager.connectTo(host: "127.0.0.1", port: targetPort, local: peer.local)
            await target.connectTo(host: "127.0.0.1", port: port, local: counterpart.local)
            try await poll {
                self.session.count { if case .connected = $0 { return true } else { return false } } > before
            }
        }

        func shutdownAll() async {
            await manager.shutdown()
            for target in counterparts { await target.shutdown() }
        }

        private func poll(_ condition: @escaping @Sendable () async -> Bool) async throws {
            let deadline = Date().addingTimeInterval(15)
            while Date() < deadline {
                if await condition() { return }
                try await Task.sleep(nanoseconds: 10_000_000)
            }
            XCTFail("condition never became true")
        }
    }

    private func twoSessionsOnOneManager(_ body: (Sut) async throws -> Void) async throws {
        let clock = ProvenanceClock(1_000_000)
        let (a, b) = try TestSessions.pairedPeers("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb")
        let manager = a.manager(monotonicNowUs: { clock.next() })
        let session = FsmSession(peer: a, manager: manager)
        await session.attach()

        let manifest = ManifestGenerationSpy()
        let transfer = TransferGenerationSpy()
        let voice = VoiceSpy()
        let audioState = AudioStateProvenanceSpy()
        await manager.manifestRelay().setSink(manifest)
        await manager.transferRelay().setSink(transfer)
        await manager.voiceRelay().setSink(voice)
        await manager.audioStateRelay().setSink(audioState)

        let port = try await manager.startListening(local: a.local)
        let sut = Sut(
            manager: manager, session: session, manifest: manifest, transfer: transfer,
            voice: voice, audioState: audioState, peer: a, counterpart: b, port: port, clock: clock
        )
        try await sut.connectFirstSession()
        do {
            try await body(sut)
        } catch {
            await sut.shutdownAll()
            throw error
        }
        await sut.shutdownAll()
    }

    // MARK: - Frames (valid by the shared vectors, so a refusal can only ever be the gate)

    private static func envelope(_ type: String, _ payload: [String: JSONValue]) -> Envelope {
        Envelope(
            v: ProtocolVersion.current,
            type: type,
            sessionId: "test-session",
            senderId: "bbbbbbbbbbbbbbbb",
            msgId: UUID().uuidString,
            seq: 1,
            sentAtMonoUs: 1,
            requiresAck: false,
            payload: payload
        )
    }

    private static func manifestPage() -> Envelope {
        envelope(
            ManifestMessageTypes.page,
            [
                "manifest_id": .string(manifestId),
                "manifest_revision": .number(7),
                "page_index": .number(0),
                "entries": .array([]),
                "removed": .array([]),
            ]
        )
    }

    private static func transferOffer() -> Envelope {
        envelope(
            TransferMessageTypes.offer,
            [
                "transfer_id": .string(transferId),
                "size_bytes": .number(1_024),
                "chunk_size": .number(65_536),
                "chunk_count": .number(1),
                "bulk_port": .number(45_001),
                "bulk_token": .string(bulkToken),
            ]
        )
    }

    private static func transferRequest() -> Envelope {
        envelope(
            TransferMessageTypes.request,
            ["content_hash": .string(contentHash), "transfer_id": .string(transferId)]
        )
    }

    private static func transferCancel() -> Envelope {
        envelope(
            TransferMessageTypes.cancel,
            ["transfer_id": .string(transferId), "reason": .string("user_cancelled")]
        )
    }

    private static func transferResult() -> Envelope {
        envelope(
            TransferMessageTypes.result,
            ["transfer_id": .string(transferId), "ok": .bool(true), "sha256": .string(contentHash)]
        )
    }

    /// `voice_session_id` absent, which the codec reads as nil and `VoiceNegotiation` reads as
    /// "carries no generation claim" — the shape that is *not* a generation mismatch and therefore
    /// the one only the control-session generation can refuse.
    private static func voiceStateClosed() -> Envelope {
        envelope(
            VoiceMessageTypes.state,
            ["state": .string("closed"), "mic_muted": .bool(false), "mode": .string("continuous")]
        )
    }

    private static func voiceOffer() -> Envelope {
        envelope(
            VoiceMessageTypes.offer,
            ["voice_session_id": .string(voiceSessionId), "sdp": .string(minimalSdp)]
        )
    }

    private static let audioStateEpoch = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    private static func audioState(revision: Int, epoch: String = audioStateEpoch) -> Envelope {
        envelope(
            AudioStateMessageTypes.audioState,
            [
                "revision": .number(Double(revision)),
                // ADR-021 Amendment A7. A fixed fabricated lifetime, because these rows are about the
                // *connection* a frame was read from and not about which of the peer's counters it came
                // from — holding the epoch still is what keeps them testing only the ADR-025 gate.
                "revision_epoch": .string(epoch),
                "endpoint_class": .string("bluetooth"),
                "microphone_open": .bool(true),
                "effective_output_profile": .string("duplex_wideband"),
                "effective_input_profile": .string("duplex_wideband"),
                "effective_output_sample_rate_hz": .number(16_000),
                "effective_input_sample_rate_hz": .number(16_000),
                "media_quality": .string("reduced"),
                "route_state": .string("stable"),
                "intercom_mode": .string("ptt"),
                "confidence": .string("assumed"),
            ]
        )
    }

    /// A §6-valid `PONG` whose round trip is at least `absurdRttUs`. `t1` is negative and `t2`/`t3`
    /// are equal, so the sample's rtt is exactly `t4 - t1` whatever the harness clock currently
    /// reads, and `isPlausibleClockSample` accepts it — a real peer on a very slow link produces
    /// precisely this shape.
    private static func pong(index: Int) -> Envelope {
        envelope(
            "PONG",
            [
                "t1_mono_us": .number(Double(-absurdRttUs - Int64(index))),
                "t2_mono_us": .number(0),
                "t3_mono_us": .number(0),
            ]
        )
    }

    private static let manifestId = "01J9Z4M3RT8V2W5X7Y9Z1A3B5C"
    private static let transferId = "01J9Z4M3RT8V2W5X7Y9Z1A3B5D"
    private static let contentHash = "sha256:1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f"
    private static let bulkToken = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"
    private static let voiceSessionId = "5e2a9c40b7f13d86e0a4c95b28f7d613"
    private static let minimalSdp = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
    private static let settleNs: UInt64 = 100_000_000

    /// `ClockSync.rttWindowCapacity`, named here so the assertion says why it is that number.
    private static let rttWindowCapacity = 64

    /// Absurd on purpose: ~1.4 hours of round trip, which no loopback sample can be confused with.
    private static let absurdRttUs: Int64 = 5_000_000_000

    /// 100 seconds — deliberately **not** "a plausible loopback RTT". Production bounds every sample
    /// it can record by its own ping timeout (3 s for the §7.1 burst, 2 s for keepalive), so no real
    /// sample can reach this however loaded the machine is — while `absurdRttUs` exceeds it
    /// fifty-fold. Asserting against a ceiling a *real* sample could approach under load would be
    /// asserting the build agent's scheduling, not the gate.
    private static let unreachableByARealSampleUs: Int64 = 100_000_000
}

/// Records **which generation** each message arrived with — the fact this amendment is about.
private final class ManifestGenerationSpy: ManifestSink, @unchecked Sendable {
    private let lock = NSLock()
    private var messageLog: [ManifestMessage] = []
    private var generationLog: [Int64] = []

    var received: [ManifestMessage] { lock.withLock { messageLog } }
    var generations: [Int64] { lock.withLock { generationLog } }

    func submit(_ message: ManifestMessage, generation: Int64) {
        lock.withLock {
            messageLog.append(message)
            generationLog.append(generation)
        }
    }
}

private final class TransferGenerationSpy: TransferSink, @unchecked Sendable {
    private let lock = NSLock()
    private var messageLog: [TransferMessage] = []
    private var generationLog: [Int64] = []

    var received: [TransferMessage] { lock.withLock { messageLog } }
    var generations: [Int64] { lock.withLock { generationLog } }

    func submit(_ message: TransferMessage, generation: Int64) {
        lock.withLock {
            messageLog.append(message)
            generationLog.append(generation)
        }
    }
}

private final class VoiceSpy: VoiceSignalSink, @unchecked Sendable {
    private let lock = NSLock()
    private var log: [VoiceSignal] = []

    var received: [VoiceSignal] { lock.withLock { log } }

    func submit(_ signal: VoiceSignal) {
        lock.withLock { log.append(signal) }
    }
}

private final class AudioStateProvenanceSpy: AudioStateSink, @unchecked Sendable {
    private let lock = NSLock()
    private var log: [AudioStateMessage] = []

    var received: [AudioStateMessage] { lock.withLock { log } }

    func submit(_ message: AudioStateMessage) {
        lock.withLock { log.append(message) }
    }
}

private final class ProvenanceClock: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Int64

    init(_ start: Int64) { value = start }

    func next() -> Int64 {
        lock.withLock {
            value += 1_000
            return value
        }
    }
}
