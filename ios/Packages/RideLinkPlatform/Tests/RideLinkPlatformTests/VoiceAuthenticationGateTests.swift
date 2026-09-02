import Foundation
import XCTest
@testable import RideLinkCore
@testable import RideLinkPlatform

/// **The Phase 2a security invariant, end to end over real TLS:**
///
/// > A peer that has completed TLS but has not passed the RideLink trust gate cannot start voice. No
/// > `VOICE_*` frame it sends is acted on, no `RTCPeerConnection` is created on its behalf, and no
/// > microphone is opened.
///
/// This is the Phase 2a analogue of `PairingSessionIntegrationTests`, and it exists for the reason
/// STATUS §2g gives: the Phase 1b security bug was a *join* between two correct mechanisms that no test
/// crossed. The join here is "which frame types the read loop acts on before authentication", and
/// PROTOCOL §7.1's whole access-control story is that `VOICE_*` is absent from that list. A test that
/// only asserted the list's *contents* would not notice a second, later branch that acted on a voice
/// frame anyway — so this drives two real `ControlSessionManager`s over real TLS with a real unpaired
/// first meeting, and asserts against the sink the voice subsystem would actually receive on.
///
/// The Kotlin mirror is `com.ridelink.network.voice.VoiceAuthenticationGateTest`.
final class VoiceAuthenticationGateTests: XCTestCase {
    private let vsid = "5e2a9c40b7f13d86e0a4c95b28f7d613"
    private let minimalSdp = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\n"
    private let candidate = "candidate:1 1 udp 1 192.0.2.11 51234 typ host"
    private let settleNs: UInt64 = 400_000_000

    /// The load-bearing test. Two unpaired peers complete TLS and reach `.pairing`, a six-digit code is
    /// on screen and unanswered — and one of them sends every `VOICE_*` frame there is. Not one reaches
    /// the voice sink.
    func testAnUnauthenticatedPeersVoiceFramesNeverReachTheVoiceSubsystem() async throws {
        try await twoUnpairedPhones { a, b, sinkA, sinkB in
            // Both are mid-pairing: TLS is up, the trust gate is not open.
            _ = try await a.awaitPairingPrompt()
            _ = try await b.awaitPairingPrompt()
            XCTAssertEqual(a.status, .pairing)
            XCTAssertEqual(b.status, .pairing)

            try await Self.sendEveryVoiceFrame(from: b)
            // Long enough for four frames to cross loopback and be processed, so "nothing arrived" is a
            // real absence rather than a race this assertion happened to win.
            try await Task.sleep(nanoseconds: settleNs)

            XCTAssertEqual(sinkA.received.count, 0, "an unauthenticated peer reached the voice subsystem")
            XCTAssertEqual(sinkB.received.count, 0)
            let refused = await a.manager.voiceRelay().droppedPreAuthentication()
            XCTAssertGreaterThan(
                refused, 0,
                "the frames must be counted as refused, not merely absent — otherwise this test would "
                    + "also pass if they were never sent"
            )
            XCTAssertEqual(a.count { if case .connected = $0 { return true } else { return false } }, 0,
                           "Connected before the trust gate")
            XCTAssertEqual(a.status, .pairing)
            XCTAssertTrue(a.trustStore.all().isEmpty, "no pin may have been written")
        }
    }

    /// The same peer, the same frames, **after** both users confirm. Now they are acted on. Without this
    /// half, the test above would be satisfied by voice being broken outright.
    func testTheSamePeersVoiceFramesAreActedOnOnceTheTrustGateHasPassed() async throws {
        try await twoUnpairedPhones { a, b, sinkA, _ in
            _ = try await a.awaitPairingPrompt()
            _ = try await b.awaitPairingPrompt()
            await a.manager.confirmPairing(accepted: true)
            await b.manager.confirmPairing(accepted: true)

            try await a.awaitEvent { if case .connected = $0 { return true } else { return false } }
            try await a.awaitStatus(.connected)

            try await Self.sendEveryVoiceFrame(from: b)
            try await Self.awaitCount(sinkA, 4)

            XCTAssertEqual(
                sinkA.received.map(\.kindName),
                ["Offer", "Answer", "IceCandidate", "State"],
                "every VOICE_* type must be delivered, in the order it was sent"
            )
        }
    }

    /// PROTOCOL §7.4: a malformed voice frame is dropped and the **control connection survives**. An
    /// attacker-supplied SDP must not be able to end a ride's control plane.
    func testMalformedAndOversizeVoiceFramesAreDroppedWithoutEndingTheControlConnection() async throws {
        try await twoUnpairedPhones { a, b, sinkA, _ in
            _ = try await a.awaitPairingPrompt()
            _ = try await b.awaitPairingPrompt()
            await a.manager.confirmPairing(accepted: true)
            await b.manager.confirmPairing(accepted: true)
            try await a.awaitEvent { if case .connected = $0 { return true } else { return false } }

            let malformed: [(String, [String: JSONValue])] = [
                (VoiceMessageTypes.offer, ["sdp": .string("no id at all")]),
                (
                    VoiceMessageTypes.offer,
                    [
                        "voice_session_id": .string(vsid),
                        "sdp": .string(String(repeating: "x", count: VoiceBounds.maxSdpBytes + 1)),
                    ]
                ),
                (
                    VoiceMessageTypes.ice,
                    [
                        "voice_session_id": .string(vsid),
                        "candidate": .string(String(repeating: "c", count: VoiceBounds.maxCandidateBytes + 1)),
                        "sdp_mline_index": .number(0),
                    ]
                ),
                (
                    VoiceMessageTypes.state,
                    [
                        "voice_session_id": .string(vsid),
                        "state": .string("active"),
                        "mic_muted": .string("not-a-boolean"),
                        "mode": .string("continuous"),
                    ]
                ),
            ]
            for (type, payload) in malformed {
                _ = await b.manager.writeRawFrame(Self.rawEnvelope(from: b, type: type, payload: payload))
            }
            try await Task.sleep(nanoseconds: settleNs)

            XCTAssertEqual(sinkA.received.count, 0, "no malformed frame may reach the voice subsystem")
            let rejections = await a.manager.voiceRelay().rejectionCounts()
            XCTAssertGreaterThanOrEqual(
                rejections.values.reduce(0, +), 4, "each malformed frame must be counted"
            )

            // The connection is still alive and still authenticated: a well-formed frame sent afterwards
            // still arrives. This is the assertion that would fail if a malformed SDP had closed the
            // socket.
            _ = await b.manager.writeRawFrame(
                ControlMessages.voiceSignal(
                    localPeerId: b.peer.peerId,
                    sessionId: SessionId("test-session"),
                    seq: 99,
                    sentAtMonoUs: 99,
                    signal: .offer(voiceSessionId: VoiceSessionId(vsid), sdp: minimalSdp)
                )
            )
            try await Self.awaitCount(sinkA, 1)
            XCTAssertEqual(a.status, .connected)
        }
    }

    /// A property over the frame-type allowlist itself, to complement the behavioural tests: no `VOICE_*`
    /// type may be in the pre-authentication set. Cheap, and it fails on the *addition* of a voice type
    /// to that list rather than waiting for a behavioural test to notice.
    func testNoVoiceTypeAppearsInThePreAuthenticationFrameAllowlist() {
        let allowlist = ControlSessionManager.preAuthenticationFrameTypesForTest
        let offenders = VoiceMessageTypes.all.filter { allowlist.contains($0) }
        XCTAssertEqual(
            offenders, [],
            "PROTOCOL §7.1: VOICE_* must be inert before the trust gate. Adding one here is a security change"
        )
        // And the allowlist is still exactly PROTOCOL §4.1's list, so this test also fails if some
        // *other* Phase 2 type is quietly added.
        XCTAssertEqual(
            allowlist,
            ["PING", "PONG", "PAIR_REQUEST", "PAIR_CONFIRM", "PAIR_RESULT", "BYE", "ERROR"]
        )
    }

    // MARK: - harness

    /// Writes each `VOICE_*` type straight onto the socket, bypassing `VoiceController` entirely. That is
    /// the point: the question is what the *receiver's* read loop does with a frame a hostile or buggy
    /// peer chose to send, not what a well-behaved local controller would send.
    private static func sendEveryVoiceFrame(from phone: FsmSession) async throws {
        let vsid = VoiceSessionId("5e2a9c40b7f13d86e0a4c95b28f7d613")
        let sdp = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\n"
        let signals: [VoiceSignal] = [
            .offer(voiceSessionId: vsid, sdp: sdp),
            .answer(voiceSessionId: vsid, sdp: sdp),
            .iceCandidate(
                voiceSessionId: vsid,
                candidate: "candidate:1 1 udp 1 192.0.2.11 51234 typ host",
                sdpMid: "0",
                sdpMlineIndex: 0
            ),
            .state(voiceSessionId: vsid, state: .negotiating, micMuted: false, mode: .continuous),
        ]
        for (index, signal) in signals.enumerated() {
            _ = await phone.manager.writeRawFrame(
                ControlMessages.voiceSignal(
                    localPeerId: phone.peer.peerId,
                    sessionId: SessionId("test-session"),
                    seq: Int64(index + 1),
                    sentAtMonoUs: Int64(index + 1),
                    signal: signal
                )
            )
        }
    }

    /// A frame built field by field, so a test can send shapes the real encoder would never produce.
    private static func rawEnvelope(
        from phone: FsmSession,
        type: String,
        payload: [String: JSONValue]
    ) -> Envelope {
        Envelope(
            v: ProtocolVersion.current,
            type: type,
            sessionId: "test-session",
            senderId: phone.peer.peerId.value,
            msgId: UUID().uuidString,
            seq: 1,
            sentAtMonoUs: 1,
            requiresAck: false,
            payload: payload
        )
    }

    private static func awaitCount(_ spy: VoiceSignalSpy, _ expected: Int) async throws {
        let deadline = Date().addingTimeInterval(15.0)
        while Date() < deadline {
            if spy.received.count >= expected { return }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTFail("expected \(expected) voice signals, got \(spy.received.count)")
    }

    private func twoUnpairedPhones(
        _ body: (FsmSession, FsmSession, VoiceSignalSpy, VoiceSignalSpy) async throws -> Void
    ) async throws {
        let clock = GateClock(1_000_000)
        let a = try TestSessions.unpairedPeer("aaaaaaaaaaaaaaaa", name: "A")
        let b = try TestSessions.unpairedPeer("bbbbbbbbbbbbbbbb", name: "B")
        let sessionA = FsmSession(peer: a, manager: a.manager(monotonicNowUs: { clock.next() }))
        let sessionB = FsmSession(peer: b, manager: b.manager(monotonicNowUs: { clock.next() }))
        await sessionA.attach()
        await sessionB.attach()

        let sinkA = VoiceSignalSpy()
        let sinkB = VoiceSignalSpy()
        await sessionA.manager.voiceRelay().setSink(sinkA)
        await sessionB.manager.voiceRelay().setSink(sinkB)

        let portA = try await sessionA.manager.startListening(local: a.local)
        let portB = try await sessionB.manager.startListening(local: b.local)
        for session in [sessionA, sessionB] {
            session.apply(.startDiscovery)
            session.apply(.peerSelected)
        }
        await sessionA.manager.connectTo(host: "127.0.0.1", port: portB, local: a.local)
        await sessionB.manager.connectTo(host: "127.0.0.1", port: portA, local: b.local)

        try await body(sessionA, sessionB, sinkA, sinkB)

        await sessionA.manager.shutdown()
        await sessionB.manager.shutdown()
    }
}

private final class GateClock: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Int64

    init(_ start: Int64) { value = start }

    func next() -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        value += 1_000
        return value
    }
}
