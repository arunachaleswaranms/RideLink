import XCTest
@testable import RideLinkCore
@testable import RideLinkPlatform

/// PROTOCOL §4.2 / ADR-015 duplicate-connection resolution wired to real loopback TCP sockets —
/// this session's brief §11: "candidate sockets must NOT enter SessionCoordinator as the active
/// session until duplicate resolution completes" and "exactly one control connection survives".
///
/// Two independent `ControlSessionManager`s (distinct `peer_id`s, distinct `conn_tiebreak`s)
/// stand in for the two phones, both listening and both dialling each other at once — the
/// "normal case on every reconnect" PROTOCOL §4.2 describes, not an exotic race.
final class DuplicateConnectionResolutionTests: XCTestCase {
    private func clock() -> @Sendable () -> Int64 {
        let counter = LockedCounter(1_000_000)
        return { counter.incrementAndGet(by: 1_000) }
    }

    func testSimultaneousMutualConnectLeavesExactlyOneSurvivorOnBothSides() async throws {
        let (a, b) = try TestSessions.pairedPeers("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb")
        let peerA = a.manager(monotonicNowUs: clock())
        let peerB = b.manager(monotonicNowUs: clock())

        let eventsA = EventCollector()
        let eventsB = EventCollector()
        await peerA.setOnEvent { event in Task { await eventsA.record(event) } }
        await peerB.setOnEvent { event in Task { await eventsB.record(event) } }

        let portA = try await peerA.startListening(local: a.local)
        let portB = try await peerB.startListening(local: b.local)

        await peerA.connectTo(host: "127.0.0.1", port: portB, local: a.local)
        await peerB.connectTo(host: "127.0.0.1", port: portA, local: b.local)

        let eventA = try await eventsA.waitForConnected(timeoutSeconds: 5)
        let eventB = try await eventsB.waitForConnected(timeoutSeconds: 5)

        XCTAssertEqual(eventA.remotePeerId.value, "bbbbbbbbbbbbbbbb")
        XCTAssertEqual(eventB.remotePeerId.value, "aaaaaaaaaaaaaaaa")
        XCTAssertEqual(eventA.sessionId, eventB.sessionId)
        XCTAssertNotEqual(eventA.isLocalLeader, eventB.isLocalLeader, "exactly one side must be leader")
        XCTAssertTrue(eventA.isLocalLeader, "aaaa... < bbbb... lexicographically, so A must lead (ADR-010)")

        let diagnosticsA = await peerA.diagnostics
        let diagnosticsB = await peerB.diagnostics
        XCTAssertEqual(diagnosticsA.controlState, .connected)
        XCTAssertEqual(diagnosticsB.controlState, .connected)

        let reconnectCountA = await peerA.reconnectCount
        let reconnectCountB = await peerB.reconnectCount
        XCTAssertEqual(reconnectCountA, 0, "no duplicate close should touch reconnect_count")
        XCTAssertEqual(reconnectCountB, 0)

        await peerA.shutdown()
        await peerB.shutdown()
    }

    func testDuplicateCloseDoesNotIncrementReconnectCount() async throws {
        let (a, b) = try TestSessions.pairedPeers("cccccccccccccccc", "dddddddddddddddd")
        let peerA = a.manager(monotonicNowUs: clock())
        let peerB = b.manager(monotonicNowUs: clock())

        let eventsA = EventCollector()
        await peerA.setOnEvent { event in Task { await eventsA.record(event) } }

        let portA = try await peerA.startListening(local: a.local)
        let portB = try await peerB.startListening(local: b.local)

        await peerA.connectTo(host: "127.0.0.1", port: portB, local: a.local)
        await peerB.connectTo(host: "127.0.0.1", port: portA, local: b.local)

        _ = try await eventsA.waitForConnected(timeoutSeconds: 5)
        try await eventsA.waitForDuplicateClosed(timeoutSeconds: 5)

        let reconnectCountA = await peerA.reconnectCount
        XCTAssertEqual(reconnectCountA, 0, "duplicate_connection must never increment reconnect_count")

        await peerA.shutdown()
        await peerB.shutdown()
    }
}

/// Test-only monotonic-microsecond stand-in, thread-safe for use across two independent
/// `ControlSessionManager` actors dialling each other concurrently.
private final class LockedCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Int64

    init(_ initial: Int64) { value = initial }

    func incrementAndGet(by delta: Int64) -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        value += delta
        return value
    }
}

private actor EventCollector {
    private var events: [ControlEvent] = []
    private var continuations: [(ControlEvent) -> Bool] = []

    func record(_ event: ControlEvent) {
        events.append(event)
    }

    func waitForConnected(timeoutSeconds: Double) async throws -> (remotePeerId: PeerId, sessionId: SessionId, isLocalLeader: Bool) {
        let deadline = Date().addingTimeInterval(timeoutSeconds)
        while Date() < deadline {
            for event in events {
                if case .connected(let remotePeerId, let sessionId, let isLocalLeader) = event {
                    return (remotePeerId, sessionId, isLocalLeader)
                }
            }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        throw ControlTransportError.notReady
    }

    func waitForDuplicateClosed(timeoutSeconds: Double) async throws {
        let deadline = Date().addingTimeInterval(timeoutSeconds)
        while Date() < deadline {
            if events.contains(where: { if case .duplicateConnectionClosed = $0 { return true }; return false }) {
                return
            }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        throw ControlTransportError.notReady
    }
}
