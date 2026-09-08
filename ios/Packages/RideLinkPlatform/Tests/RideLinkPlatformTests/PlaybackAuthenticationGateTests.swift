import Foundation
import RideLinkCore
import XCTest
@testable import RideLinkPlatform

/// The Phase 5 analogue of `VoiceAuthenticationGateTests` and
/// `ManifestTransferAuthenticationGateTests`: **an unpaired peer must never be able to move this
/// phone's music** — proven over real TLS with a real unpaired first meeting, not merely asserted
/// about the allowlist's contents.
///
/// This is what makes ADR-024 §8's claim checkable. Every `PLAY`/`PAUSE`/`RESUME`/`SEEK`/`NEXT`/
/// `PREVIOUS`/`POSITION_REPORT`/`PLAYBACK_STATE`/`QUEUE_*` type is **absent** from
/// `preAuthenticationFrameTypes`, and that absence *is* the access control.
///
/// The Kotlin mirror is `com.ridelink.network.playback.PlaybackAuthenticationGateTest`.
final class PlaybackAuthenticationGateTests: XCTestCase {
    func testAnUnauthenticatedPeersPlaybackAndQueueFramesNeverReachTheCoordinator() async throws {
        try await twoUnpairedPhones { a, b, playbackSpy, queueSpy in
            _ = try await a.awaitPairingPrompt()
            _ = try await b.awaitPairingPrompt()
            XCTAssertEqual(a.count { if case .connected = $0 { return true } else { return false } }, 0)

            // Every Phase 5 type, one frame each — a table rather than a sample, so a type added to
            // the allowlist by accident cannot slip through unexercised.
            for type in PlaybackMessageTypes.all.sorted() {
                _ = await b.manager.writeRawFrame(Self.rawEnvelope(from: b, type: type, payload: Self.playbackPayload(type)))
            }
            for type in QueueMessageTypes.all.sorted() {
                _ = await b.manager.writeRawFrame(Self.rawEnvelope(from: b, type: type, payload: Self.queuePayload(type)))
            }
            try await Task.sleep(nanoseconds: Self.settleNs)

            XCTAssertEqual(playbackSpy.received.count, 0, "an unauthenticated peer moved this phone's playback")
            XCTAssertEqual(queueSpy.received.count, 0, "an unauthenticated peer mutated this phone's queue")
            let drops = await a.manager.playbackRelay().droppedPreAuthentication()
            XCTAssertGreaterThanOrEqual(
                drops,
                PlaybackMessageTypes.all.count + QueueMessageTypes.all.count,
                "every refused frame must be counted, not merely absent — otherwise this test could pass vacuously"
            )
            XCTAssertTrue(a.trustStore.all().isEmpty, "no pin may have been written")
        }
    }

    func testTheSameFramesAreDeliveredOnceTheTrustGateHasPassed() async throws {
        try await twoUnpairedPhones { a, b, playbackSpy, _ in
            _ = try await a.awaitPairingPrompt()
            _ = try await b.awaitPairingPrompt()
            await a.manager.confirmPairing(accepted: true)
            await b.manager.confirmPairing(accepted: true)
            try await a.awaitEvent { if case .connected = $0 { return true } else { return false } }

            _ = await b.manager.writeRawFrame(
                Self.rawEnvelope(from: b, type: PlaybackMessageTypes.pause, payload: Self.playbackPayload(PlaybackMessageTypes.pause))
            )
            let deadline = Date().addingTimeInterval(5)
            while Date() < deadline, playbackSpy.received.isEmpty {
                try await Task.sleep(nanoseconds: 10_000_000)
            }
            XCTAssertEqual(playbackSpy.received.count, 1)
            guard case .pause = playbackSpy.received.first else {
                return XCTFail("expected a PAUSE, got \(String(describing: playbackSpy.received.first))")
            }
        }
    }

    func testAMalformedPhase5FrameIsDroppedWithoutEndingTheControlConnection() async throws {
        try await twoUnpairedPhones { a, b, _, _ in
            _ = try await a.awaitPairingPrompt()
            _ = try await b.awaitPairingPrompt()
            await a.manager.confirmPairing(accepted: true)
            await b.manager.confirmPairing(accepted: true)
            try await a.awaitEvent { if case .connected = $0 { return true } else { return false } }

            _ = await b.manager.writeRawFrame(
                Self.rawEnvelope(from: b, type: PlaybackMessageTypes.play, payload: ["command_seq": .number(-1)])
            )
            _ = await b.manager.writeRawFrame(
                Self.rawEnvelope(from: b, type: QueueMessageTypes.move, payload: ["queue_item_id": .string("not-a-ulid")])
            )
            try await Task.sleep(nanoseconds: Self.settleNs)

            let playbackRejections = await a.manager.playbackRelay().playbackRejectionCounts()
            XCTAssertGreaterThanOrEqual(playbackRejections.values.reduce(0, +), 1, "the malformed PLAY must be counted")
            let queueRejections = await a.manager.playbackRelay().queueRejectionCounts()
            XCTAssertGreaterThanOrEqual(queueRejections.values.reduce(0, +), 1, "the malformed QUEUE_MOVE must be counted")
            XCTAssertEqual(a.status, .connected, "the control connection must survive a malformed frame")
        }
    }

    func testNoPhase5TypeAppearsInThePreAuthenticationFrameAllowlist() {
        let allowlist = ControlSessionManager.preAuthenticationFrameTypesForTest
        XCTAssertTrue(PlaybackMessageTypes.all.isDisjoint(with: allowlist))
        XCTAssertTrue(QueueMessageTypes.all.isDisjoint(with: allowlist))
        XCTAssertFalse(allowlist.contains("PLAY"))
        XCTAssertFalse(allowlist.contains("QUEUE_ADD"))
        XCTAssertFalse(allowlist.contains("POSITION_REPORT"))
    }

    // MARK: - Payload builders (valid frames, so a rejection can only be the gate, never the codec)

    private static let trackHash = "sha256:" + String(repeating: "1f", count: 32)
    private static let queueItem = "01J9Z4M3RT8V2W5X7Y9Z1A3B5C"
    private static let peerB = "bbbbbbbbbbbbbbbb"

    private static var header: [String: JSONValue] {
        [
            "command_seq": .number(1),
            "effective_at_session_us": .number(90_210_500_000),
            "issued_by": .string(peerB),
            "queue_revision": .number(0),
        ]
    }

    private static func playbackPayload(_ type: String) -> [String: JSONValue] {
        switch type {
        case PlaybackMessageTypes.positionReport:
            return [
                "track_hash": .string(trackHash), "position_ms": .number(0),
                "at_session_us": .number(90_210_500_000), "playing": .bool(true), "playback_rate": .number(1.0),
            ]
        case PlaybackMessageTypes.playbackState:
            return [
                "command_seq": .number(1), "queue_revision": .number(0), "track_hash": .string(trackHash),
                "queue_item_id": .string(queueItem), "position_ms": .number(0), "playing": .bool(true),
                "at_session_us": .number(90_210_500_000),
            ]
        case PlaybackMessageTypes.play:
            return header.merging([
                "track_hash": .string(trackHash), "position_ms": .number(0), "queue_item_id": .string(queueItem),
            ]) { _, new in new }
        case PlaybackMessageTypes.seek:
            return header.merging(["target_position_ms": .number(1_000)]) { _, new in new }
        case PlaybackMessageTypes.pause, PlaybackMessageTypes.resume:
            return header.merging(["position_ms": .number(1_000)]) { _, new in new }
        default:
            return header
        }
    }

    private static func queuePayload(_ type: String) -> [String: JSONValue] {
        switch type {
        case QueueMessageTypes.add:
            return [
                "command_seq": .number(1), "queue_revision": .number(0),
                "items": .array([.object([
                    "queue_item_id": .string(queueItem), "track_hash": .string(trackHash),
                    "added_by": .string(peerB), "position": .string("end"),
                ])]),
            ]
        case QueueMessageTypes.remove:
            return ["command_seq": .number(1), "queue_revision": .number(0), "queue_item_ids": .array([.string(queueItem)])]
        case QueueMessageTypes.move:
            return [
                "command_seq": .number(1), "queue_revision": .number(0),
                "queue_item_id": .string(queueItem), "to_index": .number(0),
            ]
        default:
            return ["queue_revision": .number(0), "items": .array([]), "current_index": .null]
        }
    }

    // MARK: - Harness (mirrors ManifestTransferAuthenticationGateTests)

    private static let settleNs: UInt64 = 400_000_000

    private static func rawEnvelope(from phone: FsmSession, type: String, payload: [String: JSONValue]) -> Envelope {
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

    private func twoUnpairedPhones(
        _ body: (FsmSession, FsmSession, PlaybackSpy, QueueSpy) async throws -> Void
    ) async throws {
        let clock = Phase5GateClock(1_000_000)
        let a = try TestSessions.unpairedPeer("aaaaaaaaaaaaaaaa", name: "A")
        let b = try TestSessions.unpairedPeer("bbbbbbbbbbbbbbbb", name: "B")
        let sessionA = FsmSession(peer: a, manager: a.manager(monotonicNowUs: { clock.next() }))
        let sessionB = FsmSession(peer: b, manager: b.manager(monotonicNowUs: { clock.next() }))
        await sessionA.attach()
        await sessionB.attach()

        let playbackSpy = PlaybackSpy()
        let queueSpy = QueueSpy()
        await sessionA.manager.playbackRelay().setPlaybackSink(playbackSpy)
        await sessionA.manager.playbackRelay().setQueueSink(queueSpy)

        let portA = try await sessionA.manager.startListening(local: a.local)
        let portB = try await sessionB.manager.startListening(local: b.local)
        for session in [sessionA, sessionB] {
            session.apply(.startDiscovery)
            session.apply(.peerSelected)
        }
        await sessionA.manager.connectTo(host: "127.0.0.1", port: portB, local: a.local)
        await sessionB.manager.connectTo(host: "127.0.0.1", port: portA, local: b.local)

        try await body(sessionA, sessionB, playbackSpy, queueSpy)

        await sessionA.manager.shutdown()
        await sessionB.manager.shutdown()
    }
}

private final class Phase5GateClock: @unchecked Sendable {
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

final class PlaybackSpy: PlaybackSink, @unchecked Sendable {
    private let lock = NSLock()
    private var log: [PlaybackMessage] = []

    var received: [PlaybackMessage] {
        lock.lock()
        defer { lock.unlock() }
        return log
    }

    func submit(_ message: PlaybackMessage) {
        lock.lock()
        defer { lock.unlock() }
        log.append(message)
    }
}

final class QueueSpy: QueueSink, @unchecked Sendable {
    private let lock = NSLock()
    private var log: [QueueMessage] = []

    var received: [QueueMessage] {
        lock.lock()
        defer { lock.unlock() }
        return log
    }

    func submit(_ message: QueueMessage) {
        lock.lock()
        defer { lock.unlock() }
        log.append(message)
    }
}
