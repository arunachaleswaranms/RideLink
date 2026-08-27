import XCTest
@testable import RideLinkCore
@testable import RideLinkPlatform

/// This session's brief §7: a well-framed envelope with a missing/malformed PING or PONG payload
/// field must never crash the read loop. `testInt64ValueNeverTraps` covers the root cause
/// directly (`Int64(_:)` on an extreme `Double` traps rather than returning `nil`); the rest
/// drive a real `ControlSessionManager` (the system under test) against a hand-crafted "fake
/// peer" socket that completes a genuine handshake and then sends deliberately malformed frames.
final class MalformedPingPongTests: XCTestCase {
    // MARK: - Root cause: JSONValue.int64Value must never trap

    func testInt64ValueNeverTrapsOnExtremeOrNonFiniteNumbers() {
        XCTAssertNil(JSONValue.number(.greatestFiniteMagnitude * 2).int64Value, "overflow must return nil, not trap")
        XCTAssertNil(JSONValue.number(.nan).int64Value)
        XCTAssertNil(JSONValue.number(.infinity).int64Value)
        XCTAssertNil(JSONValue.number(-.infinity).int64Value)
        XCTAssertEqual(JSONValue.number(88_123_456_789).int64Value, 88_123_456_789)
    }

    func testInt64ValueRejectsNonNumericTypes() {
        XCTAssertNil(JSONValue.string("not-a-number").int64Value)
        XCTAssertNil(JSONValue.bool(true).int64Value)
        XCTAssertNil(JSONValue.null.int64Value)
        XCTAssertNil(JSONValue.object([:]).int64Value)
    }

    // MARK: - End-to-end: the real read loop survives malformed PING/PONG

    private func localIdentity(_ name: String) -> LocalHandshakeIdentity {
        LocalHandshakeIdentity(displayName: name, platform: "ios", osVersion: "test", appVersion: "test", connTiebreak: ConnTiebreakGenerator.generate())
    }

    private func clock() -> @Sendable () -> Int64 {
        let counter = LockedCounter(1_000_000)
        return { counter.incrementAndGet(by: 1_000) }
    }

    private func malformedPingPayloads() -> [(String, JSONObject)] {
        [
            ("PING missing t1_mono_us", [:]),
            ("PING string instead of integer", ["t1_mono_us": .string("not-a-number")]),
            ("PING null t1_mono_us", ["t1_mono_us": .null]),
            ("PING boolean t1_mono_us", ["t1_mono_us": .bool(true)]),
        ]
    }

    private func malformedPongPayloads() -> [(String, JSONObject)] {
        [
            ("PONG missing t2_mono_us", ["t1_mono_us": .number(1), "t3_mono_us": .number(3)]),
            (
                "PONG invalid numeric type for t3_mono_us",
                ["t1_mono_us": .number(1), "t2_mono_us": .number(2), "t3_mono_us": .string("nope")]
            ),
            (
                "PONG extreme/overflow value",
                // Finite (and therefore encodable as real JSON on the wire) but far beyond
                // Int64's range — unlike Double.infinity, which JSONEncoder itself refuses to
                // encode at all, so it could never reach the SUT to test its own parsing.
                ["t1_mono_us": .number(1), "t2_mono_us": .number(2), "t3_mono_us": .number(.greatestFiniteMagnitude)]
            ),
            (
                "PONG t2/t3 individually valid Int64s whose difference overflows rtt arithmetic",
                // Both values are powers-of-two-aligned and therefore *exactly* representable as
                // Double, so both pass int64Value's Int64(exactly:) gate individually — this is
                // this session's brief §11's case: `t3 - t2` alone overflows Int64. Before the
                // isPlausibleClockSample guard, ClockSync.Sample.rttUs's plain (trapping) Int64
                // subtraction would have crashed the process on exactly this wire input.
                ["t1_mono_us": .number(1), "t2_mono_us": .number(9_223_372_036_854_774_784), "t3_mono_us": .number(-9_223_372_036_854_775_808)]
            ),
        ]
    }

    func testMalformedPingVariantsNeverCrashTheReadLoopAndAValidPingAfterwardStillGetsAPong() async throws {
        try await withConnectedFakePeer { sut, fake, sessionId, fakeSeq in
            for (label, payload) in self.malformedPingPayloads() {
                try await fake.writeFrame(self.envelope(type: "PING", sessionId: sessionId, payload: payload, seqCounter: fakeSeq))
                try await Task.sleep(nanoseconds: 100_000_000)
                let state = await sut.diagnostics.controlState
                XCTAssertEqual(state, .connected, "connection must survive: \(label)")
            }

            // A subsequent well-formed PING must still be answered. The SUT's own keepalive/
            // clock-sync loop is independently sending it PINGs on this same socket, so skip
            // over those and find the PONG that actually answers ours (matched by echoed
            // t1_mono_us) rather than assuming the very next frame.
            try await fake.writeFrame(self.envelope(type: "PING", sessionId: sessionId, payload: ["t1_mono_us": .number(42)], seqCounter: fakeSeq))
            var found: Envelope?
            let deadline = Date().addingTimeInterval(5)
            while found == nil, Date() < deadline {
                guard case .frame(let envelope, _) = await fake.readFrame() else { continue }
                if envelope.type == "PONG", envelope.payload["t1_mono_us"]?.int64Value == 42 {
                    found = envelope
                }
            }
            XCTAssertEqual(found?.type, "PONG")
        }
    }

    func testMalformedPongVariantsNeverCrashTheReadLoopAndAValidPongAfterwardStillUpdatesDiagnostics() async throws {
        try await withConnectedFakePeer { sut, fake, sessionId, fakeSeq in
            for (label, payload) in self.malformedPongPayloads() {
                try await fake.writeFrame(self.envelope(type: "PONG", sessionId: sessionId, payload: payload, seqCounter: fakeSeq))
                try await Task.sleep(nanoseconds: 100_000_000)
                let state = await sut.diagnostics.controlState
                XCTAssertEqual(state, .connected, "connection must survive: \(label)")
            }

            let before = await sut.diagnostics.rttMs
            let t1 = self.clock()()
            let validPong: JSONObject = ["t1_mono_us": .number(Double(t1)), "t2_mono_us": .number(Double(t1 + 1000)), "t3_mono_us": .number(Double(t1 + 2000))]
            try await fake.writeFrame(self.envelope(type: "PONG", sessionId: sessionId, payload: validPong, seqCounter: fakeSeq))

            let deadline = Date().addingTimeInterval(5)
            var rttChanged = false
            while !rttChanged, Date() < deadline {
                try await Task.sleep(nanoseconds: 20_000_000)
                let current = await sut.diagnostics.rttMs
                rttChanged = current != nil && current != before
            }
            XCTAssertTrue(rttChanged, "a valid PONG after the malformed ones must still update diagnostics")
        }
    }

    private func envelope(type: String, sessionId: SessionId, payload: JSONObject, seqCounter: SeqCounter) -> Envelope {
        Envelope(
            v: 1,
            type: type,
            sessionId: sessionId.value,
            senderId: "1111111111111111",
            msgId: "m\(seqCounter.nextSeq())",
            seq: seqCounter.nextSeq(),
            sentAtMonoUs: clock()(),
            payload: payload
        )
    }

    private func withConnectedFakePeer(
        _ block: (ControlSessionManager, ControlConnection, SessionId, SeqCounter) async throws -> Void
    ) async throws {
        let sut = ControlSessionManager(localPeerId: PeerId("9999999999999999"), monotonicNowUs: clock())
        let port = try await sut.startListening(local: localIdentity("SUT"))

        let fakePeerId = PeerId("1111111111111111")
        let fakeSeq = SeqCounter()
        let fake = try await ControlConnection.connect(host: "127.0.0.1", port: port)
        let outcome = try await ControlHandshake.performAsInitiator(
            socket: fake, localPeerId: fakePeerId, seqCounter: fakeSeq, monotonicNowUs: clock(), local: localIdentity("fake")
        )
        guard case .success(_, _, let sessionId, _) = outcome else {
            XCTFail("fake peer handshake must succeed: \(outcome)")
            fake.close()
            return
        }

        let deadline = Date().addingTimeInterval(5)
        var state = await sut.diagnostics.controlState
        while state != .connected, Date() < deadline {
            try await Task.sleep(nanoseconds: 20_000_000)
            state = await sut.diagnostics.controlState
        }
        XCTAssertEqual(state, .connected)

        try await block(sut, fake, sessionId, fakeSeq)

        await sut.shutdown()
        fake.close()
    }
}

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
