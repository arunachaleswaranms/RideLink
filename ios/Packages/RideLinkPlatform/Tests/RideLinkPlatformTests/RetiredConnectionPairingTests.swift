import Foundation
import RideLinkCore
import XCTest

@testable import RideLinkPlatform

/// ADR-025 §4's security half, found while sweeping the pre-authentication family for the same
/// defect class: **a retired connection's `PAIR_CONFIRM` could stand in for the remote user's
/// confirmation of a *different* peer's six digits.**
///
/// Mirrors Android's `com.ridelink.network.control.RetiredConnectionPairingTest` case for case; see
/// that file's header for the full reasoning and the reachable interleaving. In short: PROTOCOL §4.5
/// requires **both** users to confirm before any pin is written, `PAIR_REQUEST`/`PAIR_RESULT` each
/// cross-check the advertised `identity_spki_sha256` against the one their exchange was built for,
/// and `onPairConfirm` is a bare boolean with no SPKI to check. `PAIR_*` is in the
/// pre-authentication allowlist, so it never reaches the generation gate, and `handlePairingFrame`
/// reached for whatever exchange was live with no reference to the connection the frame came from.
final class RetiredConnectionPairingTests: XCTestCase {
    /// Against unmodified `326a145` production sources, peer C is in the trust store below and a
    /// `.pairingSucceeded` has been emitted — with C's user never having been asked.
    func testAPairConfirmReadFromARetiredConnectionCannotConfirmTheSuccessorsPairing() async throws {
        try await knownPeerThenUnknownPeer { sut in
            await sut.manager.handleFrame(binding: sut.parked, envelope: Self.pairConfirm(accepted: true))

            // This device's user now says yes to *C's* six digits. That is one half of §4.5's gate;
            // the stale frame above must not have supplied the other.
            await sut.manager.confirmPairing(accepted: true)
            try await Task.sleep(nanoseconds: FsmSession.settleNs)

            XCTAssertNil(
                sut.session.trustStore.bySpki(sut.unknownPeer.identity.identitySpkiSha256),
                "peer C was never confirmed by its own user and must not be trusted"
            )
            XCTAssertEqual(
                sut.session.count { if case .pairingSucceeded = $0 { return true } else { return false } }, 0,
                "no pin may be written while only one user has confirmed"
            )
            XCTAssertEqual(
                sut.session.count { if case .pairingFailed = $0 { return true } else { return false } }, 0,
                "and the exchange was not destroyed either — it is simply still waiting"
            )
            let prompt = await sut.manager.pairingPrompt
            XCTAssertNotNil(prompt, "the six digits stay up until the other user answers")
            let refused = await sut.manager.retiredConnectionFrames
            XCTAssertEqual(refused, 1, "the stale PAIR_CONFIRM was refused and counted")
        }
    }

    /// The half that must keep working: the **live** connection's own `PAIR_CONFIRM` — the real one,
    /// produced by peer C's user tapping confirm — still completes PROTOCOL §4.5 and writes the pin.
    /// A fix that refused every pairing frame would pass the test above and break the app.
    func testTheLiveConnectionsOwnPairConfirmStillCompletesPairing() async throws {
        try await knownPeerThenUnknownPeer { sut in
            await sut.manager.confirmPairing(accepted: true)
            await sut.unknownManager.confirmPairing(accepted: true)

            try await sut.session.awaitEvent {
                if case .pairingSucceeded = $0 { return true } else { return false }
            }

            let refused = await sut.manager.retiredConnectionFrames
            XCTAssertEqual(refused, 0, "nothing on the live connection was refused")
            XCTAssertNotNil(
                sut.session.trustStore.bySpki(sut.unknownPeer.identity.identitySpkiSha256),
                "both users confirmed, so the pin is written"
            )
        }
    }

    /// The other direction of the same defect: `PAIR_RESULT` *does* cross-check the advertised SPKI,
    /// so a retired peer B's frame reaching peer C's exchange is an `identity_mismatch` — which
    /// `failPairing` turns into a closed pairing and a cleared prompt on a session that was fine.
    func testAPairResultReadFromARetiredConnectionCannotFailTheSuccessorsPairing() async throws {
        try await knownPeerThenUnknownPeer { sut in
            await sut.manager.handleFrame(
                binding: sut.parked, envelope: Self.pairResult(advertisedSpki: sut.retiredPeerSpki))
            try await Task.sleep(nanoseconds: FsmSession.settleNs)

            XCTAssertEqual(
                sut.session.count { if case .pairingFailed = $0 { return true } else { return false } }, 0,
                "a retired connection's frame cannot end the successor's pairing"
            )
            let prompt = await sut.manager.pairingPrompt
            XCTAssertNotNil(prompt, "the six digits are still up")
            let refused = await sut.manager.retiredConnectionFrames
            XCTAssertEqual(refused, 1, "the stale PAIR_RESULT was refused and counted")
        }
    }

    /// PROTOCOL §4.6's fatal `ERROR` takes the same `failPairing` path whenever an exchange is live
    /// — "the other user said no". From a retired connection it is a *different* user, about a
    /// *different* code.
    func testAFatalErrorReadFromARetiredConnectionCannotFailTheSuccessorsPairing() async throws {
        try await knownPeerThenUnknownPeer { sut in
            await sut.manager.handleFrame(binding: sut.parked, envelope: Self.fatalError())
            try await Task.sleep(nanoseconds: FsmSession.settleNs)

            XCTAssertEqual(
                sut.session.count { if case .pairingFailed = $0 { return true } else { return false } }, 0,
                "a retired connection's ERROR is not this pairing's answer"
            )
            let prompt = await sut.manager.pairingPrompt
            XCTAssertNotNil(prompt, "the six digits are still up")
            let refused = await sut.manager.retiredConnectionFrames
            XCTAssertEqual(refused, 1, "the stale ERROR was refused and counted")
        }
    }

    // MARK: - Harness

    /// One manager, two sessions: first a silent connect with a peer it already trusts (so a
    /// `ReadFrameBinding` on a genuinely authenticated connection can be parked), then — after that
    /// link has gone — a first-meeting with an **unknown** peer, which is what puts a live
    /// `PairingExchange` on the manager for a stale frame to reach.
    ///
    /// The unknown peer **dials**; this manager only listens. That makes the surviving connection an
    /// accepted one, so this device is PROTOCOL §4.5's *acceptor*, whose `onLocalDecision` settles
    /// the exchange immediately when both halves read confirmed — the step the defect reaches.
    private struct Sut {
        let manager: ControlSessionManager
        let session: FsmSession
        let parked: ReadFrameBinding
        /// The retired connection's own peer identity — what a stale `PAIR_RESULT` would advertise.
        let retiredPeerSpki: String
        let unknownPeer: TestPeer
        let unknownManager: ControlSessionManager
    }

    private func knownPeerThenUnknownPeer(_ body: (Sut) async throws -> Void) async throws {
        let clock = PairingProvenanceClock(1_000_000)
        let (a, b) = try TestSessions.pairedPeers("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb")
        let c = try TestSessions.unpairedPeer("cccccccccccccccc")

        let manager = a.manager(monotonicNowUs: { clock.next() })
        let session = FsmSession(peer: a, manager: manager)
        await session.attach()
        let port = try await manager.startListening(local: a.local)

        // Session 1: the known peer dials, the pin matches, the trust gate passes silently.
        let knownManager = b.manager(monotonicNowUs: { clock.next() })
        _ = try await knownManager.startListening(local: b.local)
        await knownManager.connectTo(host: "127.0.0.1", port: port, local: b.local)
        try await session.awaitEvent { if case .connected = $0 { return true } else { return false } }
        let captured = await manager.currentReadBinding()
        let parked = try XCTUnwrap(captured, "session 1 must have a connection")
        XCTAssertEqual(parked.generation, 1, "session 1 is generation 1")

        // The link goes. `endConnection` does not cancel the read loop, which is what leaves a frame
        // already read off connection B still to be dispatched.
        await knownManager.shutdown()
        try await poll { await manager.currentReadBinding() == nil }

        // Session 2: an unknown peer dials, so PROTOCOL §4.5 pairing starts here.
        let unknownManager = c.manager(monotonicNowUs: { clock.next() })
        _ = try await unknownManager.startListening(local: c.local)
        await unknownManager.connectTo(host: "127.0.0.1", port: port, local: c.local)
        _ = try await session.awaitPairingPrompt()
        try await session.awaitEvent { if case .pairingRequired = $0 { return true } else { return false } }

        let sut = Sut(
            manager: manager, session: session, parked: parked,
            retiredPeerSpki: b.identity.identitySpkiSha256.value,
            unknownPeer: c, unknownManager: unknownManager
        )
        do {
            try await body(sut)
        } catch {
            await manager.shutdown()
            await unknownManager.shutdown()
            throw error
        }
        await manager.shutdown()
        await unknownManager.shutdown()
    }

    private func poll(_ condition: @escaping @Sendable () async -> Bool) async throws {
        let deadline = Date().addingTimeInterval(FsmSession.timeoutSeconds)
        while Date() < deadline {
            if await condition() { return }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTFail("condition never became true")
    }

    private static func envelope(_ type: String, _ payload: [String: JSONValue]) -> Envelope {
        Envelope(
            v: ProtocolVersion.current,
            type: type,
            sessionId: "test-session",
            senderId: "bbbbbbbbbbbbbbbb",
            msgId: UUID().uuidString,
            seq: 1,
            sentAtMonoUs: 1,
            requiresAck: false,
            payload: payload
        )
    }

    private static func pairConfirm(accepted: Bool) -> Envelope {
        envelope("PAIR_CONFIRM", ["sas6_accepted": .bool(accepted)])
    }

    private static func pairResult(advertisedSpki: String) -> Envelope {
        envelope("PAIR_RESULT", ["accepted": .bool(true), "identity_spki_sha256": .string(advertisedSpki)])
    }

    private static func fatalError() -> Envelope {
        envelope(
            "ERROR",
            ["fatal": .bool(true), "code": .string("pairing_rejected"), "message": .string("no")]
        )
    }
}

private final class PairingProvenanceClock: @unchecked Sendable {
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
