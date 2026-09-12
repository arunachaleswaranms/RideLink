import Foundation
import RideLinkCore
import XCTest
@testable import RideLinkPlatform

/// The regression ADR-024 **Amendment A7** — the seventh Phase 5 closure audit — exists for.
///
/// **A6 bound every Phase 5 *loss* to the generation that caused it. A7 is the observation that the
/// generation a frame arrives with was itself read from live state, one layer above.**
///
/// Every consumer of an inbound Phase 5 frame already took the generation as a *value*:
/// `PlaybackRelay.deliverPlayback`, `PlaybackSink.submit`, the coordinator's guard and
/// `Phase5FrameQueue`'s loss ledger. None of them looked one up. But the value they were handed came
/// from `handleFrame` reading the manager's live `authenticationGeneration` at *dispatch* time — so
/// the contract the whole chain was built on was never met at its origin.
///
/// **The interleaving, before the fix.** `ControlSessionManager` is an actor, so
/// `await socket.readFrame()` in `readLoop` is a re-entrancy point: while that task is suspended,
/// other actor-isolated work runs to completion, and `endConnection` does **not** cancel the read
/// loop. So:
///
/// 1. Session A is authenticated as generation 1;
/// 2. Session A's read loop reads a valid `PAUSE` off connection A and suspends on the resume;
/// 3. link loss is detected, `endConnection` runs, connection A is closed and the session ends;
/// 4. a reconnect completes and Session B authenticates as generation 2;
/// 5. *only then* does the read-loop task resume — and read `authenticationGeneration` as **2**.
///
/// Session A's frame is now, to everything downstream, Session B's authority. A6's retired-loss
/// accounting cannot help: the frame was relabelled before it ever reached `Phase5FrameQueue`.
///
/// **Why A6's own suite could not see it.** Every A6 regression supplies the generation itself
/// (`session.deliver(message, generation: 1)` against a `FakeSyncSession`), which is exactly right
/// for asserting what the coordinator does with a generation — and exactly blind to where that
/// number comes from. The defect is entirely above that seam.
///
/// **How this test produces the park.** Nothing a test controls can suspend a task between
/// `ControlConnection.readFrame()` returning and the dispatch that follows it. So the two halves of
/// that one step are called as two statements with a real session boundary in between —
/// `currentReadBinding()` is the capture `readLoop` performs, and `handleFrame(binding:envelope:)`
/// is the very function it calls. Everything under test is production: two real TLS 1.3 sessions on
/// one real `ControlSessionManager`, the real trust gate, the real allowlist, the real
/// `PlaybackCodec` and the real `PlaybackRelay`.
///
/// The Kotlin mirror is `com.ridelink.network.playback.StaleReadGenerationTest`.
final class StaleReadGenerationTests: XCTestCase {
    /// **The defect, in full.** A frame bound to Session A and dispatched after Session B is live
    /// must still be Session A's — and connection A must never be readable as Session B's
    /// generation.
    ///
    /// Pre-fix, against unmodified `a0b81c1` production sources, the `PAUSE` below is delivered to
    /// the sink with generation **2**.
    func testAFrameBoundToSessionAIsNeverDeliveredAsSessionBsGeneration() async throws {
        try await twoSessionsOnOneManager { sut, spy in
            // Captured while Session A is live: exactly what its read loop holds for a frame it has
            // just read off connection A.
            let captured = await sut.manager.currentReadBinding()
            let parked = try XCTUnwrap(captured, "Session A must have a connection")
            XCTAssertEqual(parked.generation, 1, "Session A is generation 1")

            try await sut.boundaryToSecondSession()

            let live = await sut.manager.currentAuthGeneration
            XCTAssertEqual(live, 2, "Session B is generation 2")
            // The core invariant, asked of the production function that answers it: connection A's
            // authorisation went to *nothing*, never to Session B's number.
            let record = await sut.manager.authenticatedRecord
            let rebound = ReadFrameBinding.of(
                authenticated: record, connection: parked.connection, sessionId: parked.sessionId)
            XCTAssertNil(
                rebound.generation,
                "connection A must never be readable as an authenticated connection again"
            )

            // The parked read-loop task finally resumes.
            await sut.manager.handleFrame(binding: parked, envelope: Self.pauseEnvelope())

            XCTAssertEqual(
                spy.generations, [1],
                "a Session A frame must arrive as Session A's generation or not at all — never as Session B's"
            )
            guard case .pause = spy.messages.first else {
                return XCTFail("and it is still the frame that was read")
            }
        }
    }

    /// The other half, and the one a naive fix breaks (requirement 3 of this amendment's brief):
    /// **a frame whose own session is still live is still delivered, with that session's
    /// generation** — being late is not being stale. Binding to the connection must not turn every
    /// scheduling delay into a dropped command.
    func testAFrameDispatchedLateWithinTheSameLiveSessionIsStillDeliveredNormally() async throws {
        try await twoSessionsOnOneManager { sut, spy in
            let captured = await sut.manager.currentReadBinding()
            let parked = try XCTUnwrap(captured)
            // No boundary: only other work running, which is the ordinary case every ride produces.
            try await Task.sleep(nanoseconds: Self.settleNs)

            await sut.manager.handleFrame(binding: parked, envelope: Self.pauseEnvelope())

            XCTAssertEqual(spy.generations, [1], "the live session's own frame is delivered, tagged its own")
            XCTAssertEqual(spy.messages.count, 1)
        }
    }

    /// Session B's own ingress is untouched by any of this: its next frame carries its own
    /// generation, through the same production path, with no trace of Session A in the counters.
    func testSessionBsOwnPhase5AuthorityIsUnaffected() async throws {
        try await twoSessionsOnOneManager { sut, spy in
            let captured = await sut.manager.currentReadBinding()
            let parked = try XCTUnwrap(captured)
            try await sut.boundaryToSecondSession()
            await sut.manager.handleFrame(binding: parked, envelope: Self.pauseEnvelope())

            let current = await sut.manager.currentReadBinding()
            let live = try XCTUnwrap(current, "Session B must have a connection")
            await sut.manager.handleFrame(binding: live, envelope: Self.pauseEnvelope())

            XCTAssertEqual(
                spy.generations, [1, 2],
                "each frame carries the generation of the connection it came from"
            )
            let drops = await sut.manager.playbackRelay().droppedPreAuthentication()
            XCTAssertEqual(
                drops, 0,
                "neither frame was refused — both connections were authenticated when their frame was read"
            )
            let rejections = await sut.manager.playbackRelay().playbackRejectionCounts()
            XCTAssertTrue(rejections.isEmpty, "and neither was malformed")
        }
    }

    /// The complementary outcome the amendment's brief also accepts: a frame *read* from a
    /// connection whose session has already ended carries no authorisation at all, so the
    /// pre-authentication gate refuses it — for the same reason and by the same construction that
    /// refuses an unpaired peer's `PAUSE` (PROTOCOL §5's absence from the allowlist).
    ///
    /// This is the read-loop iteration that follows the boundary rather than the one that precedes
    /// it: `endConnection` does not cancel the read loop, so it genuinely runs once more.
    func testAFrameReadFromAConnectionWhoseSessionHasEndedIsRefusedNotRelabelled() async throws {
        try await twoSessionsOnOneManager { sut, spy in
            let captured = await sut.manager.currentReadBinding()
            let connectionA = try XCTUnwrap(captured).connection
            try await sut.boundaryToSecondSession()

            // What `readLoop` would capture on connection A's next iteration, produced by `bindRead`
            // itself rather than hand-built.
            let current = await sut.manager.currentReadBinding()
            let liveSessionId = try XCTUnwrap(current).sessionId
            let record = await sut.manager.authenticatedRecord
            let retired = ReadFrameBinding.of(
                authenticated: record, connection: connectionA, sessionId: liveSessionId)
            await sut.manager.handleFrame(binding: retired, envelope: Self.pauseEnvelope())

            XCTAssertEqual(spy.generations, [], "a retired connection's frame reaches no sink")
            let drops = await sut.manager.playbackRelay().droppedPreAuthentication()
            XCTAssertEqual(drops, 1, "and is counted as the refusal it is")
        }
    }

    // MARK: - Harness

    /// One `ControlSessionManager` under test, kept alive across a session boundary — which is the
    /// whole point, since the defect is about a generation moving *underneath* a live manager.
    private final class Sut: @unchecked Sendable {
        let manager: ControlSessionManager
        let session: FsmSession
        private let peer: TestPeer
        private let counterpart: TestPeer
        private let port: UInt16
        private let clock: StaleReadClock
        private var counterparts: [ControlSessionManager] = []

        init(
            manager: ControlSessionManager, session: FsmSession, peer: TestPeer,
            counterpart: TestPeer, port: UInt16, clock: StaleReadClock
        ) {
            self.manager = manager
            self.session = session
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

    private func twoSessionsOnOneManager(
        _ body: (Sut, GenerationSpy) async throws -> Void
    ) async throws {
        let clock = StaleReadClock(1_000_000)
        let (a, b) = try TestSessions.pairedPeers("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb")
        let manager = a.manager(monotonicNowUs: { clock.next() })
        let session = FsmSession(peer: a, manager: manager)
        await session.attach()

        let spy = GenerationSpy()
        await manager.playbackRelay().setPlaybackSink(spy)

        let port = try await manager.startListening(local: a.local)
        let sut = Sut(manager: manager, session: session, peer: a, counterpart: b, port: port, clock: clock)
        try await sut.connectFirstSession()
        do {
            try await body(sut, spy)
        } catch {
            await sut.shutdownAll()
            throw error
        }
        await sut.shutdownAll()
    }

    /// A valid PROTOCOL §5 `PAUSE`, so a rejection can only ever be the gate, never the codec.
    private static func pauseEnvelope() -> Envelope {
        Envelope(
            v: ProtocolVersion.current,
            type: PlaybackMessageTypes.pause,
            sessionId: "test-session",
            senderId: "bbbbbbbbbbbbbbbb",
            msgId: UUID().uuidString,
            seq: 1,
            sentAtMonoUs: 1,
            requiresAck: false,
            payload: [
                "command_seq": .number(1),
                "effective_at_session_us": .number(90_210_500_000),
                "issued_by": .string("bbbbbbbbbbbbbbbb"),
                "queue_revision": .number(0),
                "position_ms": .number(1_000),
            ]
        )
    }

    private static let settleNs: UInt64 = 100_000_000
}

/// Records **which generation** each message arrived with — the only fact this amendment is about.
/// `PlaybackSpy` deliberately discards it, which is why this exists alongside it.
final class GenerationSpy: PlaybackSink, @unchecked Sendable {
    private let lock = NSLock()
    private var messageLog: [PlaybackMessage] = []
    private var generationLog: [Int64] = []

    var messages: [PlaybackMessage] { lock.withLock { messageLog } }
    var generations: [Int64] { lock.withLock { generationLog } }

    func submit(_ message: PlaybackMessage, generation: Int64) {
        lock.withLock {
            messageLog.append(message)
            generationLog.append(generation)
        }
    }
}

private final class StaleReadClock: @unchecked Sendable {
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
