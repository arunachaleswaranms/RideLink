import Foundation
@testable import RideLinkCore
@testable import RideLinkPlatform
import XCTest

/// ADR-012 Amendment A2 (STATUS §4 problem 126's review follow-up): a *Forget peer* admitted in `.idle`
/// can never remove a pin once a newer session has begun, and recovery from `pin_mismatch` reaches
/// that `.idle` through the controls the app actually offers.
///
/// `SessionCoordinator.forgetPeer` reads `ForgetPeerPolicy` on the main actor and writes on the
/// `ControlSessionManager` actor. *Find other phone* can run on the main actor between the two. The app
/// target has no test bundle (CLAUDE.md, Phase 7 notes), so `Phone` below performs the coordinator's
/// statements in the coordinator's own order, over a real manager and real loopback TLS 1.3:
/// `beginDiscoverySession` applies `.startDiscovery` and then calls `sessionBegan()` in the same
/// synchronous step; `forgetPeer` reads the status and takes the admission in one step; *Stop
/// searching* applies `.cancelDiscovery` and its teardown awaits `shutdown()`. The session button is
/// the production `UiPresentation.sessionAction` mapping.
final class ForgetPeerSessionRaceTests: XCTestCase {
    private let clock: @Sendable () -> Int64 = { Int64(DispatchTime.now().uptimeNanoseconds / 1000) }

    // MARK: - The fence itself

    func testAForgetIsAdmittedOnlyInIdle() {
        let fence = ForgetPeerFence()
        let statuses: [SessionStatus] = [.idle, .discovering, .pairing, .connecting, .connected, .rideActive,
                                         .reconnecting, .disconnected, .ending, .error]
        for status in statuses {
            XCTAssertEqual(fence.admit(status: status) != nil, status == .idle, "\(status)")
        }
    }

    func testASessionStartSpendsEveryEarlierAdmissionAndTheNextIdleAdmitsAfresh() throws {
        let fence = ForgetPeerFence()
        let first = try XCTUnwrap(fence.admit(status: .idle))
        let second = try XCTUnwrap(fence.admit(status: .idle))
        var writes = 0
        XCTAssertTrue(first.perform { writes += 1 }, "two forgets in one idle period are both admitted")

        fence.sessionBegan()

        XCTAssertFalse(second.perform { writes += 1 }, "spent by the session that began")
        XCTAssertFalse(first.perform { writes += 1 })
        XCTAssertEqual(writes, 1, "a refused admission never runs its write")
        let next = try XCTUnwrap(fence.admit(status: .idle), "the session ended, and a new idle period began")
        XCTAssertTrue(next.perform { writes += 1 })
    }

    /// The other interleaving, at the lock: the write has started when *Find other phone* is tapped. The
    /// session start waits for the write, so the write is ordered first and the new session's handshake
    /// can only ever read a store without the pin.
    func testASessionStartWaitsForAForgetWriteAlreadyInProgress() throws {
        let fence = ForgetPeerFence()
        let admission = try XCTUnwrap(fence.admit(status: .idle))
        let entered = DispatchSemaphore(value: 0)
        let release = DispatchSemaphore(value: 0)
        let began = DispatchSemaphore(value: 0)
        let written = DispatchSemaphore(value: 0)
        let wrote = LockedFlag()

        DispatchQueue.global().async {
            wrote.set(admission.perform {
                entered.signal()
                release.wait()
            })
            written.signal()
        }
        XCTAssertEqual(entered.wait(timeout: .now() + 5), .success)
        DispatchQueue.global().async {
            fence.sessionBegan()
            began.signal()
        }

        XCTAssertEqual(began.wait(timeout: .now() + 0.3), .timedOut, "the session start waits behind the write")
        release.signal()
        XCTAssertEqual(began.wait(timeout: .now() + 5), .success)
        XCTAssertEqual(written.wait(timeout: .now() + 5), .success)
        XCTAssertTrue(wrote.value, "the write that was already in progress completed")
        XCTAssertFalse(admission.perform {}, "and nothing admitted before the session can write after it")
    }

    // MARK: - The reviewed race, over real TLS

    /// The finding: forget is admitted in `.idle`, the user taps *Find other phone* during the hop to the
    /// manager actor, and the new session dials, passes the trust gate on the stored pin and authenticates
    /// before the forget's write is scheduled. Without Amendment A2 that write removed the live session's
    /// pin (a stale forget acting inside a newer session). Now it is refused, the pin is untouched, and the
    /// session carries on.
    func testAForgetAdmittedInIdleCannotRemoveThePinAfterANewerSessionHasAuthenticated() async throws {
        let (a, b) = try TestSessions.pairedPeers("aaaaaaaaaaaaaae1", "bbbbbbbbbbbbbbe1")
        let phoneA = Phone(peer: a, manager: a.manager(monotonicNowUs: clock))
        let sessionB = FsmSession(peer: b, manager: b.manager(monotonicNowUs: clock))
        await phoneA.session.attach()
        await sessionB.attach()
        defer { Task { await phoneA.manager.shutdown(); await sessionB.manager.shutdown() } }

        // `forgetPeer`'s synchronous half: the policy read, in `.idle`.
        let stale = try XCTUnwrap(phoneA.admitForget())
        // The main actor is free during the hop: the user starts a session, and it authenticates.
        XCTAssertTrue(phoneA.tap(.start))
        let port = try await phoneA.manager.startListening(local: a.local)
        try await dial(sessionB, port: port)
        try await phoneA.session.awaitEvent { if case .connected = $0 { return true }; return false }
        XCTAssertEqual(phoneA.session.gateEventNames, ["PeerTrusted", "Connected"], "a silent connect on the pin")

        // `forgetPeer`'s actor-hop half, scheduled only now.
        let wrote = try await phoneA.manager.forgetTrustedPeer(b.peerId, admittedBy: stale)

        XCTAssertFalse(wrote, "a forget admitted before this session began cannot act inside it")
        XCTAssertEqual(a.trustedPeers.byPeerId(b.peerId)?.identitySpkiSha256, b.identity.identitySpkiSha256)
        XCTAssertEqual(phoneA.session.status, .connected, "a refused forget ends nothing")
        XCTAssertNotNil(phoneA.manager.liveAuthenticatedGeneration())
        XCTAssertNil(phoneA.admitForget(), "and a fresh forget is refused by the policy itself")
    }

    /// The legitimate order: the forget is written before the session begins, so the session is a first
    /// meeting and shows the code. It never silently connects on the forgotten pin.
    func testAForgetWrittenBeforeTheSessionBeganSendsThatSessionToTheCode() async throws {
        let (a, b) = try TestSessions.pairedPeers("aaaaaaaaaaaaaae2", "bbbbbbbbbbbbbbe2")
        let phoneA = Phone(peer: a, manager: a.manager(monotonicNowUs: clock))
        let sessionB = FsmSession(peer: b, manager: b.manager(monotonicNowUs: clock))
        await phoneA.session.attach()
        await sessionB.attach()
        defer { Task { await phoneA.manager.shutdown(); await sessionB.manager.shutdown() } }

        let admission = try XCTUnwrap(phoneA.admitForget())
        let wrote = try await phoneA.manager.forgetTrustedPeer(b.peerId, admittedBy: admission)
        XCTAssertTrue(wrote)
        XCTAssertTrue(phoneA.tap(.start))
        let port = try await phoneA.manager.startListening(local: a.local)
        try await dial(sessionB, port: port)

        try await phoneA.session.awaitPairingPrompt()
        try await Task.sleep(nanoseconds: FsmSession.settleNs)
        XCTAssertFalse(phoneA.session.events.contains { if case .peerTrusted = $0 { return true }; return false })
        XCTAssertNil(phoneA.manager.liveAuthenticatedGeneration())
        XCTAssertNil(a.trustedPeers.byPeerId(b.peerId))
    }

    // MARK: - Recovery from pin_mismatch through the available UI

    /// Phone B is reinstalled and comes back with the same `peer_id` and a new key, which is exactly
    /// `pin_mismatch`. Phone A must get from that refusal to a fresh six-digit code using only controls
    /// the app offers. The steps are: the refusal leaves A searching; *Stop searching* is the button
    /// offered there; it reaches `.idle`, where *Forget* is enabled; the forget is written; and *Find
    /// other phone* then meets the new key as a first meeting, confirmed on both screens.
    func testRecoveryFromPinMismatchRunsThroughStopSearchingToIdleThenForgetThenAFreshCode() async throws {
        let (a, b) = try TestSessions.pairedPeers("aaaaaaaaaaaaaae3", "bbbbbbbbbbbbbbe3")
        let reinstalledB = TestPeer(
            peerId: b.peerId, identity: try TestTlsSupport.freshIdentity(),
            trustedPeers: InMemoryTrustedPeerStore(), displayName: "B")
        let phoneA = Phone(peer: a, manager: a.manager(monotonicNowUs: clock))
        let firstB = FsmSession(peer: reinstalledB, manager: reinstalledB.manager(monotonicNowUs: clock))
        await phoneA.session.attach()
        await firstB.attach()

        XCTAssertEqual(UiPresentation.sessionAction(phoneA.session.status), .start)
        XCTAssertTrue(phoneA.tap(.start))
        let port = try await phoneA.manager.startListening(local: a.local)
        try await dial(firstB, port: port)

        try await phoneA.session.awaitEvent {
            if case .handshakeRefused(let code) = $0 { return code == errorCodePinMismatch }
            return false
        }
        try await Task.sleep(nanoseconds: FsmSession.settleNs)
        XCTAssertNil(phoneA.manager.liveAuthenticatedGeneration(), "pin_mismatch never authenticates")
        let prompt = await phoneA.manager.pairingPrompt
        XCTAssertNil(prompt, "and is never resolved by pairing")
        XCTAssertEqual(a.trustedPeers.byPeerId(b.peerId)?.identitySpkiSha256, b.identity.identitySpkiSha256)

        // What the screen offers now: Stop searching, and Forget disabled with the reason.
        XCTAssertEqual(phoneA.session.status, .discovering)
        XCTAssertEqual(UiPresentation.sessionAction(phoneA.session.status), .stopDiscovery)
        XCTAssertNotNil(UiPresentation.forgetPeerUnavailableHint(phoneA.session.status))
        XCTAssertNil(phoneA.admitForget())

        await phoneA.tapStopSearching()
        await firstB.manager.shutdown()
        XCTAssertEqual(phoneA.session.status, .idle)
        XCTAssertNil(UiPresentation.forgetPeerUnavailableHint(.idle), "Forget is enabled in .idle")
        let admission = try XCTUnwrap(phoneA.admitForget())
        let wrote = try await phoneA.manager.forgetTrustedPeer(b.peerId, admittedBy: admission)
        XCTAssertTrue(wrote)
        XCTAssertNil(a.trustedPeers.byPeerId(b.peerId))

        // Find other phone, on both: a first meeting with the new key, and two humans confirm it.
        XCTAssertEqual(UiPresentation.sessionAction(phoneA.session.status), .start)
        XCTAssertTrue(phoneA.tap(.start))
        let secondPort = try await phoneA.manager.startListening(local: a.local)
        let secondB = FsmSession(peer: reinstalledB, manager: reinstalledB.manager(monotonicNowUs: clock))
        await secondB.attach()
        defer { Task { await phoneA.manager.shutdown(); await secondB.manager.shutdown() } }
        try await dial(secondB, port: secondPort)

        let promptA = try await phoneA.session.awaitPairingPrompt()
        let promptB = try await secondB.awaitPairingPrompt()
        XCTAssertEqual(promptA.sas6, promptB.sas6)
        await phoneA.manager.confirmPairing(accepted: true)
        await secondB.manager.confirmPairing(accepted: true)
        try await phoneA.session.awaitStatus(.connected)
        XCTAssertEqual(
            a.trustedPeers.byPeerId(b.peerId)?.identitySpkiSha256, reinstalledB.identity.identitySpkiSha256,
            "the new key is trusted only after both users confirmed the code")
    }

    // MARK: - helpers

    private func dial(_ session: FsmSession, port: UInt16) async throws {
        XCTAssertTrue(session.apply(.startDiscovery))
        XCTAssertTrue(session.apply(.peerSelected))
        _ = try await session.manager.startListening(local: session.peer.local)
        await session.manager.connectTo(host: "127.0.0.1", port: port, local: session.peer.local)
    }
}

/// One phone as the coordinator drives it, limited to the statements Amendment A2 is about, in the
/// coordinator's own order. See the class comment.
private struct Phone {
    let session: FsmSession
    let fence = ForgetPeerFence()
    var manager: ControlSessionManager { session.manager }

    init(peer: TestPeer, manager: ControlSessionManager) {
        session = FsmSession(peer: peer, manager: manager)
    }

    /// `forgetPeer`'s synchronous half.
    func admitForget() -> ForgetPeerAdmission? { fence.admit(status: session.status) }

    /// *Find other phone*: `beginDiscoverySession`'s transition and, in the same step, the fence.
    func tap(_ action: SessionAction) -> Bool {
        XCTAssertEqual(UiPresentation.sessionAction(session.status), action, "the screen offers this button")
        XCTAssertEqual(action, .start, "use tapStopSearching for Stop searching")
        guard session.apply(.startDiscovery) else { return false }
        fence.sessionBegan()
        return true
    }

    /// *Stop searching*: `cancelDiscovery`'s transition, then the teardown it starts, awaited.
    func tapStopSearching() async {
        XCTAssertEqual(UiPresentation.sessionAction(session.status), .stopDiscovery, "the screen offers this button")
        XCTAssertTrue(session.apply(.cancelDiscovery))
        await manager.shutdown()
    }
}

private final class LockedFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var flag = false
    var value: Bool { lock.withLock { flag } }
    func set(_ value: Bool) { lock.withLock { flag = value } }
}
