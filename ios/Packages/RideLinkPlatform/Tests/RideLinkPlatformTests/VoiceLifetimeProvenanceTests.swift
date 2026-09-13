import Foundation
import XCTest
@testable import RideLinkCore
@testable import RideLinkPlatform

/// **STATUS §4 problem 60, at the two production seams the pure tables cannot see.**
///
/// `VoiceMailboxLifetimeIdentityTests` proves the *policy* — a semantic `VOICE_*` input may affect the
/// negotiation only while the control generation that admitted it is unretired. This file proves the
/// three facts that policy depends on, against real `ControlSessionManager` code:
///
/// 1. **The relay hands the sink the frame's own generation**, not whatever is live when it looks. That
///    is ADR-025's rule applied one layer further down, and it is what makes the mailbox's decision
///    about *provenance* rather than about liveness a second time.
/// 2. **A control generation is strictly increasing and never reset** — including across a
///    `shutdown()`/`startListening()` cycle. That is what makes a monotonic retired floor exact rather
///    than a heuristic, and the mailbox's own doc cites it.
/// 3. **A successor lifetime is authenticated, and its frames are admitted, without waiting for the
///    predecessor's `.linkLost` to be consumed by anything.** This is Window 2, and it is not a race:
///    with the coordinator-shaped consumer deliberately held, the successor still authenticates and its
///    own `VOICE_OFFER` still reaches the voice sink. Before the fix, the link loss that arrived
///    afterwards discarded exactly that frame.
///
/// The mirror is `com.ridelink.network.control.VoiceLifetimeProvenanceTest`.
final class VoiceLifetimeProvenanceTests: XCTestCase {
    // MARK: - 1. the relay passes provenance, never a live read

    /// The discriminator is a `liveGeneration` supplier that **changes between calls**. The gate reads
    /// it once and finds a match; anything that read it a second time to label the frame would see the
    /// successor's number instead. The sink must be told 7.
    ///
    /// This is the same architectural claim as `ReadFrameBinding`'s, one layer lower: a value that has
    /// already authorised a read is the frame's for as long as the frame exists.
    func testDeliverPassesTheFramesOwnGenerationToTheSinkNeverAReReadLiveOne() async {
        let liveReads = Counter()
        let spy = VoiceSignalSpy()
        let relay = Self.relay(liveGeneration: {
            liveReads.next() == 0 ? Self.frameGeneration : Self.successorGeneration
        })
        await relay.setSink(spy)

        await relay.deliver(type: VoiceMessageTypes.offer, payload: Self.offerPayload, generation: Self.frameGeneration)

        XCTAssertEqual(spy.received.count, 1, "the frame's own generation was live, so it is delivered")
        XCTAssertEqual(
            spy.generations, [Self.frameGeneration],
            "the sink must be handed the generation that authorised the read, not the one live afterwards"
        )
    }

    /// And the liveness half is unchanged: a frame whose own generation is not live is refused.
    func testDeliverStillRefusesAFrameWhoseOwnGenerationIsNoLongerLive() async {
        let spy = VoiceSignalSpy()
        let relay = Self.relay(liveGeneration: { Self.successorGeneration })
        await relay.setSink(spy)

        await relay.deliver(type: VoiceMessageTypes.offer, payload: Self.offerPayload, generation: Self.frameGeneration)

        XCTAssertTrue(spy.received.isEmpty)
        let dropped = await relay.droppedRetiredGeneration()
        XCTAssertEqual(dropped, 1)
    }

    // MARK: - 2. generations are monotonic, and never reset

    /// The producer-side half of P60-6. `VoiceInputMailbox`'s retired floor is a single monotonic
    /// number, and that is only exact because `activateAuthenticatedSession` never reuses or resets one.
    /// `shutdown()` un-latches the manager for reuse, which is exactly the path a "full new session"
    /// takes — and the counter must survive it.
    func testAnAuthenticationGenerationStrictlyIncreasesAndSurvivesAShutdownAndRestart() async throws {
        let clock = ProvenanceStepClock(1_000_000)
        let (a, b) = try TestSessions.pairedPeers("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb")
        let manager = a.manager(monotonicNowUs: { clock.next() })
        let session = FsmSession(peer: a, manager: manager)
        await session.attach()
        let before = await manager.currentAuthGeneration
        XCTAssertEqual(before, 0, "nothing has authenticated yet")

        let first = try await Self.connect(manager: manager, session: session, peer: a, counterpart: b, clock: clock)
        var generation = await manager.currentAuthGeneration
        XCTAssertEqual(generation, 1)
        XCTAssertEqual(manager.liveAuthenticatedGeneration(), 1)

        await manager.shutdown()
        await first.shutdown()
        try await Self.poll { manager.liveAuthenticatedGeneration() == nil }
        generation = await manager.currentAuthGeneration
        XCTAssertEqual(generation, 1, "a shutdown un-latches the manager for reuse and must not reset the counter")

        let second = try await Self.connect(manager: manager, session: session, peer: a, counterpart: b, clock: clock)
        generation = await manager.currentAuthGeneration
        XCTAssertEqual(generation, 2, "the successor's generation is strictly greater")
        XCTAssertEqual(manager.liveAuthenticatedGeneration(), 2)
        await manager.shutdown()
        await second.shutdown()
    }

    // MARK: - 3. Window 2, with no race in it

    /// **The Window-2 production trace.** A consumer of `ControlEvent` is held on the `.linkLost` it is
    /// given — a `SessionCoordinator` legitimately takes its time there (it awaits
    /// `SharedLibraryCoordinator.handleLinkLost()` and `SyncPlaybackCoordinator.handleLinkLost()`, and
    /// defers the voice call into a further `launchInSession` `Task`). Nothing in the control plane
    /// waits for it: `promote` requires only that `activeSocket` be nil, which `endConnection` has
    /// already done.
    ///
    /// So while that consumer is still holding generation 1's link loss, generation 2 authenticates and
    /// **its own** `VOICE_OFFER` is admitted and reaches the voice sink. The link loss that is released
    /// afterwards names generation 1, and it is that name — not the order the two arrived in — that
    /// keeps generation 2's offer.
    func testASuccessorsVoiceOfferIsAdmittedWhileThePredecessorsLinkLostIsStillUnconsumed() async throws {
        let clock = ProvenanceStepClock(1_000_000)
        let (a, b) = try TestSessions.pairedPeers("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb")
        let manager = a.manager(monotonicNowUs: { clock.next() })
        let session = FsmSession(peer: a, manager: manager)
        await session.attach()
        let spy = VoiceSignalSpy()
        await manager.voiceRelay().setSink(spy)

        let first = try await Self.connect(manager: manager, session: session, peer: a, counterpart: b, clock: clock)
        XCTAssertEqual(manager.liveAuthenticatedGeneration(), 1)

        // A consumer shaped exactly like `SessionCoordinator`'s — one ordered stream, side effects
        // awaited — held on the first link loss it is handed. `FsmSession` is still the emit-time
        // recorder, which is the manager's own `onEvent`; this is the *consumption* that lags it.
        let consumer = HeldLinkLossConsumer()
        Task { await consumer.run(events: { session.events }) }

        // Generation 1 ends, the way a ride does: the peer goes away and this manager's read loop
        // notices, so the production `endConnection` -> `.linkLost` path runs here.
        await first.shutdown()
        let linkLost = try await consumer.awaitHeld()
        guard case .linkLost(_, let retiredAuthGeneration) = linkLost else {
            return XCTFail("expected a .linkLost")
        }
        XCTAssertEqual(retiredAuthGeneration, 1, "the event names the lifetime that actually ended")
        var consumedLosses = await consumer.consumedLinkLossCount()
        XCTAssertEqual(consumedLosses, 0, "and the consumer is still holding it")

        // Generation 2 authenticates anyway — nothing about `promote` waits on that consumer.
        let second = try await Self.connect(manager: manager, session: session, peer: a, counterpart: b, clock: clock)
        XCTAssertEqual(manager.liveAuthenticatedGeneration(), 2)

        // ...and generation 2's own VOICE_OFFER is admitted, still with the link loss unconsumed.
        let binding = await manager.currentReadBinding()
        let live = try XCTUnwrap(binding, "session B must have a connection")
        await manager.handleFrame(binding: live, envelope: Self.offerEnvelope())

        consumedLosses = await consumer.consumedLinkLossCount()
        XCTAssertEqual(consumedLosses, 0, "the predecessor's loss is *still* unconsumed")
        XCTAssertEqual(spy.received.count, 1, "the successor's offer reaches the voice sink regardless")
        XCTAssertEqual(
            spy.generations, [2],
            "and it is labelled as the successor's own work, which is what lets a later .linkLost(1) spare it"
        )

        await consumer.release()
        await manager.shutdown()
        await second.shutdown()
    }

    /// The other half of the same event: a connection that never passed the trust gate retires no
    /// generation, because it never admitted a `VOICE_*` frame for one to own. `connectTo` to a port
    /// nothing is listening on is the production path that emits exactly that.
    func testALinkLostFromADialThatNeverAuthenticatedNamesNoGeneration() async throws {
        let clock = ProvenanceStepClock(1_000_000)
        let (a, _) = try TestSessions.pairedPeers("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb")
        let manager = a.manager(monotonicNowUs: { clock.next() })
        let session = FsmSession(peer: a, manager: manager)
        await session.attach()

        await manager.connectTo(host: "127.0.0.1", port: Self.unusedPort, local: a.local)

        try await Self.poll {
            session.events.contains { if case .linkLost = $0 { return true } else { return false } }
        }
        let linkLost = try XCTUnwrap(session.events.first { if case .linkLost = $0 { return true } else { return false } })
        guard case .linkLost(_, let retiredAuthGeneration) = linkLost else { return XCTFail("expected a .linkLost") }
        XCTAssertNil(retiredAuthGeneration, "nothing authenticated, so nothing is retired")
        await manager.shutdown()
    }

    // MARK: - harness

    private static func relay(liveGeneration: @escaping @Sendable () -> Int64?) -> VoiceSignalRelay {
        VoiceSignalRelay(
            localPeerId: PeerId("aaaaaaaaaaaaaaaa"),
            monotonicNowUs: { 1 },
            nextSeq: { 1 },
            activeSessionId: { SessionId("00000000000000000000000000000000") },
            authenticatedWriter: { { _ in true } },
            liveGeneration: liveGeneration
        )
    }

    private static func connect(
        manager: ControlSessionManager,
        session: FsmSession,
        peer: TestPeer,
        counterpart: TestPeer,
        clock: ProvenanceStepClock
    ) async throws -> ControlSessionManager {
        let target = counterpart.manager(monotonicNowUs: { clock.next() })
        let before = session.count { if case .connected = $0 { return true } else { return false } }
        let targetPort = try await target.startListening(local: counterpart.local)
        let ownPort = try await manager.startListening(local: peer.local)
        await manager.connectTo(host: "127.0.0.1", port: targetPort, local: peer.local)
        await target.connectTo(host: "127.0.0.1", port: ownPort, local: counterpart.local)
        try await poll {
            session.count { if case .connected = $0 { return true } else { return false } } > before
        }
        return target
    }

    private static func poll(_ condition: @escaping @Sendable () async -> Bool) async throws {
        let deadline = Date().addingTimeInterval(20)
        while Date() < deadline {
            if await condition() { return }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTFail("condition never became true")
    }

    private static func offerEnvelope() -> Envelope {
        Envelope(
            v: ProtocolVersion.current,
            type: VoiceMessageTypes.offer,
            sessionId: "test-session",
            senderId: "bbbbbbbbbbbbbbbb",
            msgId: UUID().uuidString,
            seq: 1,
            sentAtMonoUs: 1,
            requiresAck: false,
            payload: offerPayload
        )
    }

    private static let offerPayload: [String: JSONValue] = [
        "voice_session_id": .string("5e2a9c40b7f13d86e0a4c95b28f7d613"),
        "sdp": .string("v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"),
    ]

    /// A generation a frame was read under, and the one that replaced it.
    private static let frameGeneration: Int64 = 7
    private static let successorGeneration: Int64 = 9

    /// Nothing binds here; the dial fails and the emitted `.linkLost` carries no generation.
    private static let unusedPort: UInt16 = 1
}

/// Counts calls, so a supplier can answer differently the second time it is asked.
private final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var value = 0

    func next() -> Int {
        lock.lock()
        defer { lock.unlock() }
        let current = value
        value += 1
        return current
    }
}

/// A strictly increasing microsecond source, so nothing here reads a real clock (CLAUDE.md rule 5).
private final class ProvenanceStepClock: @unchecked Sendable {
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

/// A `SessionCoordinator`-shaped consumer: it drains the recorded events in order and **holds** on the
/// first `.linkLost`, exactly as the real one can while it awaits its own side effects.
private actor HeldLinkLossConsumer {
    private var held: ControlEvent?
    private var consumedLinkLosses = 0
    private var released = false
    private var seen = 0

    func run(events: @escaping @Sendable () -> [ControlEvent]) async {
        while !Task.isCancelled {
            let all = events()
            while seen < all.count {
                let event = all[seen]
                if case .linkLost = event, !released {
                    held = event
                    while !released {
                        try? await Task.sleep(nanoseconds: 5_000_000)
                    }
                }
                if case .linkLost = event { consumedLinkLosses += 1 }
                seen += 1
            }
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
    }

    func awaitHeld() async throws -> ControlEvent {
        let deadline = Date().addingTimeInterval(20)
        while Date() < deadline {
            if let held { return held }
            try await Task.sleep(nanoseconds: 5_000_000)
        }
        throw ControlTransportError.notReady
    }

    func consumedLinkLossCount() -> Int { consumedLinkLosses }

    func release() { released = true }
}
