import Foundation
import RideLinkCore
import XCTest
@testable import RideLinkPlatform

/// The Phase 4 analogue of `VoiceAuthenticationGateTests`: **an unpaired peer must never reach the
/// shared-library catalogue** (brief §22 — "unknown/unpaired peers must never receive the library
/// catalogue"), proven over real TLS with a real unpaired first meeting, not merely asserted about
/// the allowlist's contents.
///
/// The Kotlin mirror is `com.ridelink.network.transfer.ManifestTransferAuthenticationGateTest`.
final class ManifestTransferAuthenticationGateTests: XCTestCase {
    func testAnUnauthenticatedPeersManifestAndTransferFramesNeverReachTheCatalogue() async throws {
        try await twoUnpairedPhones { a, b, sinkA, _ in
            _ = try await a.awaitPairingPrompt()
            _ = try await b.awaitPairingPrompt()
            XCTAssertEqual(a.count { if case .connected = $0 { return true } else { return false } }, 0)

            _ = await b.manager.writeRawFrame(Self.rawEnvelope(from: b, type: ManifestMessageTypes.begin, payload: [
                "manifest_id": .string("01J9Z4M3RT8V2W5X7Y9Z1A3B5C"),
                "kind": .string("full"),
                "manifest_revision": .number(1),
                "base_revision": .null,
                "total_entries": .number(0),
                "total_removed": .number(0),
                "page_count": .number(0),
                "digest_alg": .string("ridelink-manifest-v1"),
            ]))
            _ = await b.manager.writeRawFrame(Self.rawEnvelope(from: b, type: TransferMessageTypes.request, payload: [
                "content_hash": .string("sha256:" + String(repeating: "1f", count: 32)),
                "transfer_id": .string("01J9Z4M3RT8V2W5X7Y9Z1A3B5C"),
            ]))
            try await Task.sleep(nanoseconds: Self.settleNs)

            XCTAssertEqual(sinkA.received.count, 0, "an unauthenticated peer reached the catalogue")
            let manifestDrops = await a.manager.manifestRelay().droppedPreAuthentication()
            XCTAssertGreaterThan(manifestDrops, 0, "must be counted as refused, not merely absent")
            let transferDrops = await a.manager.transferRelay().droppedPreAuthentication()
            XCTAssertGreaterThan(transferDrops, 0)
            XCTAssertTrue(a.trustStore.all().isEmpty, "no pin may have been written")
        }
    }

    func testTheSameFramesAreDeliveredOnceTheTrustGateHasPassed() async throws {
        try await twoUnpairedPhones { a, b, sinkA, _ in
            _ = try await a.awaitPairingPrompt()
            _ = try await b.awaitPairingPrompt()
            await a.manager.confirmPairing(accepted: true)
            await b.manager.confirmPairing(accepted: true)
            try await a.awaitEvent { if case .connected = $0 { return true } else { return false } }

            _ = await b.manager.writeRawFrame(Self.rawEnvelope(from: b, type: ManifestMessageTypes.abort, payload: [
                "manifest_id": .string("01J9Z4M3RT8V2W5X7Y9Z1A3B5C"),
                "reason": .string("cancelled"),
            ]))
            try await Self.awaitCount(sinkA, 1)
            guard case .abort = sinkA.received.first else {
                return XCTFail("expected an abort message, got \(String(describing: sinkA.received.first))")
            }
        }
    }

    func testAMalformedTransferFrameIsDroppedWithoutEndingTheControlConnection() async throws {
        try await twoUnpairedPhones { a, b, _, _ in
            _ = try await a.awaitPairingPrompt()
            _ = try await b.awaitPairingPrompt()
            await a.manager.confirmPairing(accepted: true)
            await b.manager.confirmPairing(accepted: true)
            try await a.awaitEvent { if case .connected = $0 { return true } else { return false } }

            _ = await b.manager.writeRawFrame(
                Self.rawEnvelope(from: b, type: TransferMessageTypes.offer, payload: ["transfer_id": .string("not-a-ulid")]))
            _ = await b.manager.writeRawFrame(Self.rawEnvelope(from: b, type: TransferMessageTypes.progress, payload: [
                "transfer_id": .string("01J9Z4M3RT8V2W5X7Y9Z1A3B5C"),
                "bytes": .number(-1),
                "pct": .number(0),
            ]))
            try await Task.sleep(nanoseconds: Self.settleNs)

            let rejections = await a.manager.transferRelay().rejectionCounts()
            XCTAssertGreaterThanOrEqual(rejections.values.reduce(0, +), 2, "both must be counted")
            try await a.awaitStatus(.connected)
        }
    }

    func testNoManifestOrTransferTypeAppearsInThePreAuthenticationFrameAllowlist() {
        let allowlist = ControlSessionManager.preAuthenticationFrameTypesForTest
        XCTAssertEqual([], ManifestMessageTypes.all.filter { allowlist.contains($0) })
        XCTAssertEqual([], TransferMessageTypes.all.filter { allowlist.contains($0) })
        XCTAssertFalse(allowlist.contains("MANIFEST_BEGIN"))
        XCTAssertFalse(allowlist.contains("TRANSFER_REQUEST"))
    }

    // MARK: - harness (mirrors VoiceAuthenticationGateTests)

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

    private static func awaitCount(_ spy: ManifestSpy, _ expected: Int) async throws {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            if spy.received.count >= expected { return }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTFail("expected \(expected) manifest messages, got \(spy.received.count)")
    }

    private func twoUnpairedPhones(
        _ body: (FsmSession, FsmSession, ManifestSpy, TransferSpy) async throws -> Void
    ) async throws {
        let clock = GateClock(1_000_000)
        let a = try TestSessions.unpairedPeer("aaaaaaaaaaaaaaaa", name: "A")
        let b = try TestSessions.unpairedPeer("bbbbbbbbbbbbbbbb", name: "B")
        let sessionA = FsmSession(peer: a, manager: a.manager(monotonicNowUs: { clock.next() }))
        let sessionB = FsmSession(peer: b, manager: b.manager(monotonicNowUs: { clock.next() }))
        await sessionA.attach()
        await sessionB.attach()

        let sinkA = ManifestSpy()
        let transferSinkA = TransferSpy()
        await sessionA.manager.manifestRelay().setSink(sinkA)
        await sessionA.manager.transferRelay().setSink(transferSinkA)

        let portA = try await sessionA.manager.startListening(local: a.local)
        let portB = try await sessionB.manager.startListening(local: b.local)
        for session in [sessionA, sessionB] {
            session.apply(.startDiscovery)
            session.apply(.peerSelected)
        }
        await sessionA.manager.connectTo(host: "127.0.0.1", port: portB, local: a.local)
        await sessionB.manager.connectTo(host: "127.0.0.1", port: portA, local: b.local)

        try await body(sessionA, sessionB, sinkA, transferSinkA)

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

private final class ManifestSpy: ManifestSink, @unchecked Sendable {
    private let lock = NSLock()
    private var log: [ManifestMessage] = []

    var received: [ManifestMessage] {
        lock.lock()
        defer { lock.unlock() }
        return log
    }

    func submit(_ message: ManifestMessage) {
        lock.lock()
        defer { lock.unlock() }
        log.append(message)
    }
}

private final class TransferSpy: TransferSink, @unchecked Sendable {
    private let lock = NSLock()
    private var log: [TransferMessage] = []

    var received: [TransferMessage] {
        lock.lock()
        defer { lock.unlock() }
        return log
    }

    func submit(_ message: TransferMessage) {
        lock.lock()
        defer { lock.unlock() }
        log.append(message)
    }
}
