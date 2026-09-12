import XCTest

@testable import RideLinkCore
@testable import RideLinkPlatform

/// **`docs/STATUS.md` §4 problem 47**, at the seam that contained it: an `AUDIO_STATE` revision floor
/// belongs to exactly one remote sender lifetime, and a floor from a lifetime that is over must not
/// suppress a successor's state.
///
/// PROTOCOL §4.4's `revision` is "per sender per session" and §4.4.1 says outright that it is **not**
/// reset by a duplicate-connection resolution, a control reconnect or a voice rebuild. So the receiver's
/// inbox keeps its floor across a reconnect on purpose. That is right for a peer whose publisher
/// survived the reconnect and wrong for a peer whose publisher restarted: it comes back at `revision` 1
/// and every genuine message is dropped as stale until it climbs past the dead lifetime's number.
/// `AudioStateRelay`'s ADR-025 gate cannot see this — the frame is *legitimate* and carries the live
/// generation — because provenance and revision lifetime are different questions. ADR-021 Amendment A7
/// answers the second one with `revision_epoch`.
///
/// **Everything below the assertions is production.** Two real `ControlSessionManager`s over real TLS
/// 1.3, the real handshake and trust gate, the real `AudioStatePublisher` on the sending side, the real
/// `AudioStateRelay` on both, the real read loop, the real ADR-025 generation gate, the real codec and a
/// real `AudioStateInbox` behind the sink. Nothing here calls `AudioStateInbox.reset()`, and nothing
/// constructs a message the peer did not actually publish: a lifetime restarts by the same
/// `resetForNewSession` call `SessionCoordinator.startDiscovery` makes.
///
/// The mirror is `com.ridelink.network.control.AudioStateSenderLifetimeTest`, whose doc comment carries
/// the fuller reasoning about how a boundary is produced.
final class AudioStateSenderLifetimeTests: XCTestCase {
    // MARK: - case 1: an ordinary reconnect, same sender lifetime

    /// The half a naive fix breaks: the publisher survived the link blip, so the receiver must keep the
    /// floor rather than start a new one — a floor that reset here cannot refuse a straggler.
    func testAnOrdinaryReconnectKeepsTheFloorAndTheSenderKeepsCounting() async throws {
        try await twoPeers { sut in
            try await sut.publishUntil(revision: 10)
            let held = await sut.held()
            XCTAssertEqual(held?.revision, 10, "the first session's state arrived")
            let lifetime = try XCTUnwrap(held?.revisionEpoch)

            try await sut.reconnect()

            _ = try await sut.publish()
            try await sut.awaitRevision(11)
            let after = await sut.held()
            XCTAssertEqual(after?.revisionEpoch, lifetime, "a reconnect does not begin a new lifetime")
            let retired = await sut.droppedRetiredEpoch()
            XCTAssertEqual(retired, 0, "and nothing was treated as retired")
        }
    }

    /// The other half of case 1, and the reason the floor is kept: a delayed frame from *before* the
    /// reconnect, at a revision the floor already covers, is still refused. Sent on the live connection,
    /// so ADR-025's gate admits it and only §4.4's rule can refuse it.
    func testADelayedLowerRevisionFromTheSameLifetimeIsStillRefusedAfterAReconnect() async throws {
        try await twoPeers { sut in
            try await sut.publishUntil(revision: 10)
            let lastOfFirstLifetime = await sut.lastPublished()
            var delayed = try XCTUnwrap(lastOfFirstLifetime)
            delayed.revision = 7

            try await sut.reconnect()
            _ = try await sut.publish()
            try await sut.awaitRevision(11)

            try await sut.sendRaw(delayed)
            try await sut.settle()

            let held = await sut.held()
            XCTAssertEqual(held?.revision, 11, "a revision the floor covers cannot come back")
            let stale = await sut.droppedStale()
            XCTAssertEqual(stale, 1, "refused by §4.4's rule, and counted as stale")
            let retired = await sut.droppedRetiredEpoch()
            XCTAssertEqual(retired, 0, "it is the same lifetime, so not a retired one")
        }
    }

    // MARK: - case 2: a new remote sender lifetime

    /// **The defect.** Against the pre-fix production sources the peer's `revision` 1 is dropped as
    /// stale and the held state stays at the dead lifetime's revision 50 — for 50 more publishes.
    func testARestartedSenderIsAdoptedAtRevisionOneAndDoesNotWaitForTheOldFloor() async throws {
        try await twoPeers { sut in
            try await sut.publishUntil(revision: 50)
            let deadHeld = await sut.held()
            let dead = try XCTUnwrap(deadHeld?.revisionEpoch)

            try await sut.restartSenderLifetime()

            _ = try await sut.publish()
            try await sut.awaitRevision(1)
            let held = await sut.held()
            let live = try XCTUnwrap(held?.revisionEpoch)
            XCTAssertNotEqual(dead, live, "the restart is announced, not inferred")
            XCTAssertEqual(held?.revision, 1, "and it is the new counter's first value")
            let stale = await sut.droppedStale()
            XCTAssertEqual(stale, 0, "nothing of the new lifetime's was refused")
        }
    }

    /// And then it orders normally against itself, which is what keeps §4.4's rule meaningful.
    func testTheNewLifetimeThenOrdersNormallyAgainstItself() async throws {
        try await twoPeers { sut in
            try await sut.publishUntil(revision: 50)
            try await sut.restartSenderLifetime()

            _ = try await sut.publish()
            try await sut.awaitRevision(1)
            _ = try await sut.publish()
            try await sut.awaitRevision(2)

            let lastPublished = await sut.lastPublished()
            var stale = try XCTUnwrap(lastPublished)
            stale.revision = 1
            try await sut.sendRaw(stale)
            try await sut.settle()

            let held = await sut.held()
            XCTAssertEqual(held?.revision, 2, "the new lifetime's own floor still holds")
            let dropped = await sut.droppedStale()
            XCTAssertEqual(dropped, 1)
        }
    }

    // MARK: - case 3: a delayed old-lifetime frame after the new lifetime began

    /// Solving case 2 must not resurrect a stale route. The dead lifetime's frame is sent on the **live**
    /// connection at a revision above where it left off, so ADR-025's gate admits it and the only thing
    /// that can refuse it is the inbox knowing that lifetime is over.
    func testAStragglerFromAReplacedLifetimeCannotOverwriteItsSuccessor() async throws {
        try await twoPeers { sut in
            try await sut.publishUntil(revision: 50)
            let lastOfDeadLifetime = await sut.lastPublished()
            var old = try XCTUnwrap(lastOfDeadLifetime)

            try await sut.restartSenderLifetime()
            _ = try await sut.publish()
            try await sut.awaitRevision(1)
            _ = try await sut.publish()
            try await sut.awaitRevision(2)
            let liveHeld = await sut.held()
            let live = try XCTUnwrap(liveHeld?.revisionEpoch)

            old.revision = 51
            try await sut.sendRaw(old)
            try await sut.settle()

            let held = await sut.held()
            XCTAssertEqual(held?.revision, 2, "the successor's state stands")
            XCTAssertEqual(held?.revisionEpoch, live)
            let retired = await sut.droppedRetiredEpoch()
            XCTAssertEqual(retired, 1, "and the straggler is counted, not merely dropped")
            let byGeneration = await sut.manager.audioStateRelay().droppedRetiredGeneration()
            XCTAssertEqual(byGeneration, 0, "ADR-025 admitted it — this is the other rule")
        }
    }

    /// The same straggler on the connection it actually belongs to: refused one layer earlier, by
    /// ADR-025's gate, before the inbox ever sees it. Both defences are real and neither is the other.
    func testAStragglerReadFromTheRetiredConnectionIsRefusedByTheGenerationGateFirst() async throws {
        try await twoPeers { sut in
            try await sut.publishUntil(revision: 50)
            let parkedBinding = await sut.manager.currentReadBinding()
            let parked = try XCTUnwrap(parkedBinding)

            try await sut.restartSenderLifetime()
            _ = try await sut.publish()
            try await sut.awaitRevision(1)

            let lastOfDeadLifetime = await sut.lastPublished()
            var straggler = try XCTUnwrap(lastOfDeadLifetime)
            straggler.revision = 99
            await sut.manager.handleFrame(binding: parked, envelope: sut.envelopeOf(straggler))

            let held = await sut.held()
            XCTAssertEqual(held?.revision, 1, "the retired connection's frame changed nothing")
            let byGeneration = await sut.manager.audioStateRelay().droppedRetiredGeneration()
            XCTAssertEqual(byGeneration, 1)
            let retired = await sut.droppedRetiredEpoch()
            XCTAssertEqual(retired, 0, "it never reached the inbox to be counted there")
        }
    }

    // MARK: - case 4: a session with no successor yet

    /// ADR-025 already covers this and the regression is kept: a frame read under a session that has
    /// ended, dispatched before any new session exists, mutates nothing. `liveAuthenticatedGeneration()`
    /// is nil here, a different question from "does this generation match".
    func testALateFrameWithNoSuccessorSessionAtAllMutatesNothing() async throws {
        try await twoPeers { sut in
            try await sut.publishUntil(revision: 4)
            let parkedBinding = await sut.manager.currentReadBinding()
            let parked = try XCTUnwrap(parkedBinding)

            try await sut.endSenderWithNoSuccessor()
            XCTAssertNil(sut.manager.liveAuthenticatedGeneration(), "there is no live session")

            let lastOfEndedSession = await sut.lastPublished()
            var late = try XCTUnwrap(lastOfEndedSession)
            late.revision = 5
            await sut.manager.handleFrame(binding: parked, envelope: sut.envelopeOf(late))

            let held = await sut.held()
            XCTAssertEqual(held?.revision, 4, "the held state is untouched")
            let byGeneration = await sut.manager.audioStateRelay().droppedRetiredGeneration()
            XCTAssertEqual(byGeneration, 1)
        }
    }

    // MARK: - case 5: a new authentication generation alone

    /// The architecture's answer to "does a control reconnect define a new revision namespace" is **no**
    /// (PROTOCOL §4.4.1), so changing the authentication generation alone must change nothing about
    /// ordering. Asserted against the generation itself rather than inferred.
    func testANewAuthenticationGenerationAloneDoesNotRestartTheRevisionNamespace() async throws {
        try await twoPeers { sut in
            try await sut.publishUntil(revision: 3)
            let heldBefore = await sut.held()
            let lifetime = try XCTUnwrap(heldBefore?.revisionEpoch)
            let first = try XCTUnwrap(sut.manager.liveAuthenticatedGeneration())

            try await sut.reconnect()
            try await sut.reconnect()

            let third = try XCTUnwrap(sut.manager.liveAuthenticatedGeneration())
            XCTAssertGreaterThan(third, first, "two reconnects really did advance the generation")

            _ = try await sut.publish()
            try await sut.awaitRevision(4)
            let held = await sut.held()
            XCTAssertEqual(held?.revisionEpoch, lifetime, "the namespace is the publisher's, not the connection's")
            let retired = await sut.droppedRetiredEpoch()
            let stale = await sut.droppedStale()
            XCTAssertEqual(retired, 0)
            XCTAssertEqual(stale, 0)
        }
    }

    // MARK: - case 6: the Phase 5 route-transition input

    /// Why this is more than a stale diagnostics row. `SessionRouteStatePort.isRouteTransitioning()` —
    /// the guard that suspends ARCHITECTURE §7.3's drift ladder — reads the peer's last
    /// `AUDIO_STATE.route_state`. Before this amendment a restarted peer's `transitioning` could not be
    /// adopted, so a dead lifetime's value kept deciding whether drift correction ran.
    func testAfterALifetimeRestartThePeersRouteTransitionIsAdoptedAndSoIsTheStableThatFollows() async throws {
        try await twoPeers { sut in
            try await sut.publishUntil(revision: 50, routeState: .stable)
            let before = await sut.held()
            XCTAssertEqual(before?.routeState, .stable)

            try await sut.restartSenderLifetime()

            _ = try await sut.publish(routeState: .transitioning)
            try await sut.awaitRevision(1)
            let transitioning = await sut.held()
            XCTAssertEqual(
                transitioning?.routeState, .transitioning,
                "a restarted peer's route change must reach the Phase 5 drift guard"
            )

            _ = try await sut.publish(routeState: .stable)
            try await sut.awaitRevision(2)
            let settled = await sut.held()
            XCTAssertEqual(settled?.routeState, .stable, "and so must the settle that ends it")
        }
    }

    // MARK: - harness

    /// `manager` is the receiver under test and lives for the whole test — every defect here is about
    /// state surviving underneath it. The publisher is the *sender's*, so a lifetime restart is a real
    /// `resetForNewSession` on a real publisher rather than a hand-written revision.
    private final class Sut: @unchecked Sendable {
        let manager: ControlSessionManager
        let inbox: InboxHolder
        private let session: FsmSession
        private let receiver: TestPeer
        private let sender: TestPeer
        private let port: UInt16
        private let clock: LifetimeClock
        private var senderManagers: [ControlSessionManager] = []
        private var publisher = AudioStatePublisher(epoch: AudioStateEpochGenerator.generate())

        init(
            manager: ControlSessionManager, inbox: InboxHolder, session: FsmSession,
            receiver: TestPeer, sender: TestPeer, port: UInt16, clock: LifetimeClock
        ) {
            self.manager = manager
            self.inbox = inbox
            self.session = session
            self.receiver = receiver
            self.sender = sender
            self.port = port
            self.clock = clock
        }

        func held() async -> AudioStateMessage? { await inbox.current() }
        func droppedStale() async -> Int { await inbox.droppedStale() }
        func droppedRetiredEpoch() async -> Int { await inbox.droppedRetiredEpoch() }
        func lastPublished() async -> AudioStateMessage? { publisher.published }

        /// Publishes one observable change through the real publisher and the real sender relay.
        /// `forceNext` is §4.4's "reaching CONNECTED publishes regardless" path, so a row never has to
        /// invent a state change to move the counter.
        @discardableResult
        func publish(routeState: RouteState = .stable) async throws -> AudioStateMessage {
            let message = publisher.forceNext(
                snapshot: AudioRouteSnapshot(endpointClass: .bluetooth, routeState: routeState),
                intercomMode: .ptt
            )
            try await sendRaw(message)
            return message
        }

        func publishUntil(revision: Int64, routeState: RouteState = .stable) async throws {
            while publisher.currentRevision < revision { _ = try await publish(routeState: routeState) }
            try await awaitRevision(revision)
        }

        /// Sends a message the publisher did not just produce — a straggler, or a revision the floor
        /// already covers. Still the real relay, the real codec and the real wire.
        func sendRaw(_ message: AudioStateMessage) async throws {
            let target = try XCTUnwrap(senderManagers.last)
            let sent = await target.audioStateRelay().send(message)
            XCTAssertTrue(sent, "the sender's relay must accept it")
        }

        /// The wire envelope for `message`, for the two rows that dispatch a parked binding by hand.
        func envelopeOf(_ message: AudioStateMessage) -> Envelope {
            ControlMessages.audioState(
                localPeerId: sender.peerId,
                sessionId: SessionId("retired"),
                seq: 1,
                sentAtMonoUs: clock.next(),
                message: message
            )
        }

        /// A control boundary with the sender's publisher **kept**: the link went, the app did not.
        func reconnect() async throws { try await boundary(restartPublisher: false) }

        /// A control boundary with the sender's publisher **restarted** — the same `resetForNewSession`
        /// call `SessionCoordinator.startDiscovery` makes, which is the only thing in production that
        /// begins a new lifetime.
        func restartSenderLifetime() async throws { try await boundary(restartPublisher: true) }

        private func boundary(restartPublisher: Bool) async throws {
            try await endSenderWithNoSuccessor()
            if restartPublisher {
                publisher.resetForNewSession(epoch: AudioStateEpochGenerator.generate())
            }
            try await connectSender()
        }

        /// Ends the sender's session and brings no successor up, so nothing is authenticated.
        func endSenderWithNoSuccessor() async throws {
            await senderManagers.last?.shutdown()
            try await poll { await self.manager.currentReadBinding() == nil }
        }

        func connectSender() async throws {
            let clockRef = clock
            let target = sender.manager(monotonicNowUs: { clockRef.next() })
            senderManagers.append(target)
            let before = session.count { if case .connected = $0 { return true } else { return false } }
            let targetPort = try await target.startListening(local: sender.local)
            await manager.connectTo(host: "127.0.0.1", port: targetPort, local: receiver.local)
            await target.connectTo(host: "127.0.0.1", port: port, local: sender.local)
            try await poll {
                self.session.count { if case .connected = $0 { return true } else { return false } } > before
            }
        }

        func awaitRevision(_ revision: Int64) async throws {
            try await poll { await self.held()?.revision == revision }
        }

        /// Lets a frame that must change nothing actually arrive, so "nothing happened" is a result.
        func settle() async throws { try await Task.sleep(nanoseconds: 150_000_000) }

        func shutdownAll() async {
            await manager.shutdown()
            for target in senderManagers { await target.shutdown() }
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

    private func twoPeers(_ body: (Sut) async throws -> Void) async throws {
        let clock = LifetimeClock(1_000_000)
        let (receiver, sender) = try TestSessions.pairedPeers("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb")
        let manager = receiver.manager(monotonicNowUs: { clock.next() })
        let session = FsmSession(peer: receiver, manager: manager)
        await session.attach()

        // The production sink `SessionCoordinator.acceptPeerAudioState` is, holding the production
        // inbox. Nothing in this file touches it except through this path.
        let inbox = InboxHolder()
        await manager.audioStateRelay().setSink(inbox)

        let port = try await manager.startListening(local: receiver.local)
        let sut = Sut(
            manager: manager, inbox: inbox, session: session,
            receiver: receiver, sender: sender, port: port, clock: clock
        )
        try await sut.connectSender()
        do {
            try await body(sut)
        } catch {
            await sut.shutdownAll()
            throw error
        }
        await sut.shutdownAll()
    }
}

/// The real `AudioStateInbox`, behind an actor so the read loop and the test read it safely — the
/// Android mirror's `AudioStateInboxHolder` does the same job with a lock. The rule under test is the
/// inbox's; this adds nothing but isolation.
private actor InboxHolder: AudioStateSink {
    private var inbox = AudioStateInbox()

    nonisolated func submit(_ message: AudioStateMessage) {
        Task { await self.accept(message) }
    }

    private func accept(_ message: AudioStateMessage) {
        inbox.accept(message)
    }

    func current() -> AudioStateMessage? { inbox.current }
    func droppedStale() -> Int { inbox.droppedStale }
    func droppedRetiredEpoch() -> Int { inbox.droppedRetiredEpoch }
}

private final class LifetimeClock: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Int64

    init(_ start: Int64) { value = start }

    func next() -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        value += 1
        return value
    }
}
