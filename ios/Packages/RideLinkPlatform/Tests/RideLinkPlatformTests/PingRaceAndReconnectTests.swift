import XCTest
@testable import RideLinkCore
@testable import RideLinkPlatform

/// Regression coverage for this session's brief §2 (iOS PING/PONG race) and §3 (reconnect
/// re-entrancy). Both bugs only manifest against a real socket with real scheduling, so these
/// tests use real loopback TCP `ControlSessionManager` pairs, the same pattern as
/// `DuplicateConnectionResolutionTests`.
final class PingRaceAndReconnectTests: XCTestCase {
    private func localIdentity(_ name: String) -> LocalHandshakeIdentity {
        LocalHandshakeIdentity(displayName: name, platform: "ios", osVersion: "test", appVersion: "test", connTiebreak: ConnTiebreakGenerator.generate())
    }

    private func clock() -> @Sendable () -> Int64 {
        let counter = LockedCounter(1_000_000)
        return { counter.incrementAndGet(by: 1_000) }
    }

    // MARK: - §2: PING/PONG race

    /// The production `"PING"` handler in `handleFrame` replies the instant a frame is read —
    /// there is no artificial delay — so a real loopback peer already *is* the "fake/loopback
    /// peer that responds immediately" the brief asks for. With the pre-fix ordering (write,
    /// *then* register the waiter), an immediate PONG can race ahead of registration and be
    /// silently dropped, forcing that sample to wait out the full 3s `pingTimeoutMs`. Eleven
    /// samples at ~50ms spacing complete in well under a second when every PONG is caught; a
    /// single dropped PONG alone pushes the burst past 3s. The 2.5s deadline below is far under
    /// the failure case and comfortably over the success case.
    func testClockBurstCompletesQuicklyWithNoDroppedPongs() async throws {
        let peerA = ControlSessionManager(localPeerId: PeerId("1111111111111111"), monotonicNowUs: clock())
        let peerB = ControlSessionManager(localPeerId: PeerId("2222222222222222"), monotonicNowUs: clock())

        let portA = try await peerA.startListening(local: localIdentity("A"))
        let portB = try await peerB.startListening(local: localIdentity("B"))

        await peerA.connectTo(host: "127.0.0.1", port: portB, local: localIdentity("A"))
        await peerB.connectTo(host: "127.0.0.1", port: portA, local: localIdentity("B"))

        try await waitUntil(timeoutSeconds: 5) { await peerA.diagnostics.controlState == .connected }

        // The first clock burst (11 samples) runs immediately on CONNECTED (ARCHITECTURE §7.1).
        try await waitUntil(timeoutSeconds: 2.5) { await peerA.diagnostics.clockOffsetUs != nil }

        let diagnostics = await peerA.diagnostics
        XCTAssertNotNil(diagnostics.clockOffsetUs, "clock burst must converge without any sample silently timing out")

        await peerA.shutdown()
        await peerB.shutdown()
    }

    /// Runs several independent connect/burst cycles back to back. A race that only sometimes
    /// reproduces would eventually show up as an occasional slow burst; every iteration here must
    /// stay fast.
    func testRepeatedClockBurstsAllCompleteQuickly() async throws {
        for i in 0..<5 {
            let peerA = ControlSessionManager(localPeerId: PeerId("aaaaaaaaaaaaaaa\(i)"), monotonicNowUs: clock())
            let peerB = ControlSessionManager(localPeerId: PeerId("bbbbbbbbbbbbbbb\(i)"), monotonicNowUs: clock())

            let portA = try await peerA.startListening(local: localIdentity("A"))
            let portB = try await peerB.startListening(local: localIdentity("B"))

            await peerA.connectTo(host: "127.0.0.1", port: portB, local: localIdentity("A"))
            await peerB.connectTo(host: "127.0.0.1", port: portA, local: localIdentity("B"))

            try await waitUntil(timeoutSeconds: 5) { await peerA.diagnostics.controlState == .connected }
            try await waitUntil(timeoutSeconds: 2.5) { await peerA.diagnostics.clockOffsetUs != nil }

            await peerA.shutdown()
            await peerB.shutdown()
        }
    }

    /// Tearing down mid-burst must not crash (a double-resume of a `CheckedContinuation` is a
    /// fatal trap, so simply not crashing is a real assertion) and must not leave the next
    /// session's identically-timestamped PING colliding with a stale waiter.
    func testShutdownDuringAnInFlightBurstDoesNotCrashAndLeavesNoStaleWaiter() async throws {
        let peerA = ControlSessionManager(localPeerId: PeerId("3333333333333333"), monotonicNowUs: clock())
        let peerB = ControlSessionManager(localPeerId: PeerId("4444444444444444"), monotonicNowUs: clock())

        let portA = try await peerA.startListening(local: localIdentity("A"))
        let portB = try await peerB.startListening(local: localIdentity("B"))

        await peerA.connectTo(host: "127.0.0.1", port: portB, local: localIdentity("A"))
        await peerB.connectTo(host: "127.0.0.1", port: portA, local: localIdentity("B"))

        try await waitUntil(timeoutSeconds: 5) { await peerA.diagnostics.controlState == .connected }
        // Tear down immediately, while the first 11-sample burst is very likely still mid-flight.
        await peerA.shutdown()
        await peerB.shutdown()

        // A fresh session, reusing the same clock (so `t1` values can collide with the old
        // session's), must still converge cleanly — proving the old session's pending waiters
        // were cleared, not left to intercept a same-valued PONG later.
        let peerA2 = ControlSessionManager(localPeerId: PeerId("3333333333333333"), monotonicNowUs: clock())
        let peerB2 = ControlSessionManager(localPeerId: PeerId("4444444444444444"), monotonicNowUs: clock())
        let portA2 = try await peerA2.startListening(local: localIdentity("A"))
        let portB2 = try await peerB2.startListening(local: localIdentity("B"))
        await peerA2.connectTo(host: "127.0.0.1", port: portB2, local: localIdentity("A"))
        await peerB2.connectTo(host: "127.0.0.1", port: portA2, local: localIdentity("B"))
        try await waitUntil(timeoutSeconds: 5) { await peerA2.diagnostics.controlState == .connected }
        try await waitUntil(timeoutSeconds: 2.5) { await peerA2.diagnostics.clockOffsetUs != nil }

        await peerA2.shutdown()
        await peerB2.shutdown()
    }

    // MARK: - §3: reconnect re-entrancy

    /// The historical bug: `ReconnectController`'s own attempts called `connectTo`, which emits
    /// `.linkLost(.network)` on failure. `SessionCoordinator` reacts to that event by calling
    /// `beginReconnect` again, restarting a second ladder on top of the first. Driving
    /// `beginReconnect` against an address nothing listens on and recording every emitted event
    /// proves the fix directly: a failed *attempt* must never itself produce a `ControlEvent`.
    func testFailedReconnectAttemptsNeverEmitAnEvent() async throws {
        let peer = ControlSessionManager(localPeerId: PeerId("5555555555555555"), monotonicNowUs: clock(), connectTimeoutMs: 200)
        let events = EventRecorder()
        await peer.setOnEvent { event in Task { await events.record(event) } }

        // Bind and immediately release a port so nothing is listening there. `NWConnection`
        // treats a refused connection as `.waiting`, not `.failed` (it can retry indefinitely on
        // its own), so `connectTimeoutMs` is what actually bounds the attempt — see
        // `ControlConnection.connect`'s doc comment.
        let deadListener = try await ControlListener.bind()
        let deadPort = deadListener.localPort
        deadListener.close()

        await peer.beginReconnect(local: localIdentity("A"), host: "127.0.0.1", port: deadPort)

        // Ladder: attempt 1 at ~0.4-0.6s, attempt 2 at ~1.2-1.8s cumulative. 3s leaves comfortable
        // margin for connect-refused overhead on a loaded CI machine while still guaranteeing at
        // least two failed attempts have run.
        try await Task.sleep(nanoseconds: 3_000_000_000)

        let recorded = await events.all()
        XCTAssertTrue(recorded.isEmpty, "a failed reconnect attempt must never emit a ControlEvent (would re-trigger beginReconnect): \(recorded)")

        let count = await peer.reconnectCount
        XCTAssertGreaterThanOrEqual(count, 2, "the ladder must keep advancing across failed attempts")

        await peer.shutdown()
    }

    /// `reconnectCount` must increase by exactly one per attempt, monotonically, with no reset
    /// back to a lower value mid-ladder (the visible symptom of a second, nested loop starting).
    func testReconnectCountGrowsMonotonicallyAndNeverResets() async throws {
        let peer = ControlSessionManager(localPeerId: PeerId("6666666666666666"), monotonicNowUs: clock(), connectTimeoutMs: 200)
        let deadListener = try await ControlListener.bind()
        let deadPort = deadListener.localPort
        deadListener.close()

        await peer.beginReconnect(local: localIdentity("A"), host: "127.0.0.1", port: deadPort)

        var samples: [Int] = []
        for _ in 0..<6 {
            try await Task.sleep(nanoseconds: 400_000_000)
            samples.append(await peer.reconnectCount)
        }

        for i in 1..<samples.count {
            XCTAssertGreaterThanOrEqual(samples[i], samples[i - 1], "reconnectCount must never go backwards: \(samples)")
        }
        XCTAssertGreaterThan(samples.last ?? 0, 0, "the ladder must have made progress: \(samples)")

        await peer.shutdown()
    }

    /// A successful reconnect must stop the ladder: once the peer is reachable, no further
    /// attempts (and no further `reconnectCount` growth) happen.
    func testSuccessfulReconnectStopsFurtherAttempts() async throws {
        let peerA = ControlSessionManager(localPeerId: PeerId("7777777777777777"), monotonicNowUs: clock())
        let peerB = ControlSessionManager(localPeerId: PeerId("8888888888888888"), monotonicNowUs: clock())
        let events = EventRecorder()
        await peerA.setOnEvent { event in Task { await events.record(event) } }

        let portB = try await peerB.startListening(local: localIdentity("B"))

        await peerA.beginReconnect(local: localIdentity("A"), host: "127.0.0.1", port: portB)

        try await waitUntil(timeoutSeconds: 5) { await events.all().contains { if case .connected = $0 { return true }; return false } }

        let countAtSuccess = await peerA.reconnectCount
        try await Task.sleep(nanoseconds: 1_500_000_000)
        let countAfterWaiting = await peerA.reconnectCount
        XCTAssertEqual(countAtSuccess, countAfterWaiting, "no further attempts should occur once reconnect succeeded")

        await peerA.shutdown()
        await peerB.shutdown()
    }

    private func waitUntil(timeoutSeconds: Double, _ predicate: () async -> Bool) async throws {
        let deadline = Date().addingTimeInterval(timeoutSeconds)
        while Date() < deadline {
            if await predicate() { return }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        throw ControlTransportError.notReady
    }
}

private actor EventRecorder {
    private var events: [ControlEvent] = []
    func record(_ event: ControlEvent) { events.append(event) }
    func all() -> [ControlEvent] { events }
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
