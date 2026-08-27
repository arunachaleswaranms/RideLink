import XCTest
@testable import RideLinkCore
@testable import RideLinkPlatform

/// `SessionGate` exhausted from every session status, because it is one half of the Phase 1b
/// security invariant and the half that is cheapest to get wrong by accident.
///
/// The Android mirror is `SessionGateTest` and asserts the same rows.
final class SessionGateTests: XCTestCase {
    private let remote = PeerId("bbbbbbbbbbbbbbbb")
    private let allStatuses: [SessionStatus] = [
        .idle, .discovering, .pairing, .connecting, .connected, .rideActive, .reconnecting,
        .disconnected, .ending, .error,
    ]

    private var connected: ControlEvent {
        .connected(remotePeerId: remote, sessionId: SessionId("s"), isLocalLeader: true)
    }

    private var peerTrusted: ControlEvent { .peerTrusted(remotePeerId: remote) }
    private var pairingRequired: ControlEvent { .pairingRequired(remotePeerId: remote) }
    private var pairingSucceeded: ControlEvent {
        .pairingSucceeded(peer: TrustedPeer(
            peerId: remote,
            identitySpkiSha256: SpkiHash("sha256:" + String(repeating: "ab", count: 32)),
            displayName: "B", pairedAtEpochSeconds: 1, lastSeenAtEpochSeconds: 1))
    }

    func testConnectedNeverProducesPairingSucceededFromAnyStatus() {
        // The whole bug in one assertion. `.connected` used to be read as "pairing must have
        // worked", which let a TLS socket to an unknown peer walk pairing -> connecting -> connected.
        for status in allStatuses {
            XCTAssertNotEqual(
                SessionGate.sessionEvent(for: connected, status: status), .pairingSucceeded,
                "Connected must never imply pairing success (status \(status))")
        }
    }

    func testConnectedOnlyFinishesAWalkThatIsAlreadyPastTheTrustGate() {
        XCTAssertEqual(SessionGate.sessionEvent(for: connected, status: .connecting), .connectionEstablished)
        XCTAssertEqual(SessionGate.sessionEvent(for: connected, status: .reconnecting), .reconnectSucceeded)
        for status in allStatuses where status != .connecting && status != .reconnecting {
            XCTAssertNil(
                SessionGate.sessionEvent(for: connected, status: status),
                "Connected from \(status) implies no transition")
        }
    }

    func testPairingIsLeftForConnectingByTheTrustGateAndByNothingElse() {
        let opens = [connected, peerTrusted, pairingRequired, pairingSucceeded].filter {
            SessionGate.sessionEvent(for: $0, status: .pairing) == .pairingSucceeded
        }
        XCTAssertEqual(opens.count, 2, "only PeerTrusted and PairingSucceeded may open the gate")
        XCTAssertEqual(SessionGate.sessionEvent(for: peerTrusted, status: .pairing), .pairingSucceeded)
        XCTAssertEqual(SessionGate.sessionEvent(for: pairingSucceeded, status: .pairing), .pairingSucceeded)
    }

    func testPairingRequiredHoldsTheSessionExactlyWhereItIs() {
        for status in allStatuses {
            XCTAssertNil(
                SessionGate.sessionEvent(for: pairingRequired, status: status),
                "a code on screen is not a transition")
        }
    }

    func testTheTrustGateOnlyOpensFromPairing() {
        for event in [peerTrusted, pairingSucceeded] {
            for status in allStatuses where status != .pairing {
                XCTAssertNil(SessionGate.sessionEvent(for: event, status: status), "\(event) from \(status)")
            }
        }
    }

    func testAPairingThatEndsWithoutAPinLeavesPairingAndOnlyPairing() {
        let endings: [ControlEvent] = [
            .pairingFailed(code: errorCodePairingRejected),
            .handshakeRefused(code: errorCodePinMismatch),
        ]
        for event in endings {
            XCTAssertEqual(SessionGate.sessionEvent(for: event, status: .pairing), .pairingRejectedOrTimeout)
            for status in allStatuses where status != .pairing {
                XCTAssertNil(SessionGate.sessionEvent(for: event, status: status), "\(event) from \(status)")
            }
        }
    }

    func testALinkThatDiesMidPairingCannotWedgeTheSessionInPairing() {
        // Neither linkLost event is legal in PAIRING, so without these two rows the FSM would sit
        // there with no prompt and no way forward.
        for reason: RideLinkPlatform.LinkLossReason in [.network, .bye] {
            let event = SessionGate.sessionEvent(for: .linkLost(reason: reason), status: .pairing)
            XCTAssertEqual(event, .pairingRejectedOrTimeout)
            guard case .transitioned(let newState, _) =
                SessionFsm.transition(FsmState(status: .pairing), try! XCTUnwrap(event)) else {
                return XCTFail("PAIRING must have a legal exit for a lost link")
            }
            XCTAssertEqual(newState.status, .discovering)
        }
    }

    func testLinkLossMapsToTheFsmsOwnVocabularyEverywhereElse() {
        XCTAssertEqual(SessionGate.sessionEvent(for: .linkLost(reason: .network), status: .connecting), .connectionFailed)
        XCTAssertEqual(
            SessionGate.sessionEvent(for: .linkLost(reason: .network), status: .connected),
            .linkLost(reason: .network))
        XCTAssertEqual(
            SessionGate.sessionEvent(for: .linkLost(reason: .bye), status: .connected),
            .linkLost(reason: .bye))
    }

    func testADuplicateOrDeliberateCloseIsNeverATransition() {
        // ADR-015 / ARCHITECTURE §3 rule 6.
        for reason: RideLinkPlatform.LinkLossReason in [.duplicateConnection, .userEnded] {
            for status in allStatuses {
                XCTAssertNil(
                    SessionGate.sessionEvent(for: .linkLost(reason: reason), status: status),
                    "\(reason) from \(status)")
            }
        }
        // Passed through so the FSM records the no-op rather than it vanishing here.
        XCTAssertEqual(
            SessionGate.sessionEvent(for: .duplicateConnectionClosed, status: .connected),
            .duplicateConnectionClosed)
    }

    func testAnUnknownPeerCannotReachConnectedWithoutPairingSucceeded() {
        // The invariant stated as a walk: feed the FSM every control event an unknown peer can
        // produce before pairing settles, in any order, and CONNECTED must stay unreachable.
        let beforePairing: [ControlEvent] = [
            connected, pairingRequired, connected, .duplicateConnectionClosed, pairingRequired, connected,
        ]
        var state = FsmState(status: .pairing)
        for event in beforePairing {
            if let sessionEvent = SessionGate.sessionEvent(for: event, status: state.status),
               case .transitioned(let newState, _) = SessionFsm.transition(state, sessionEvent) {
                state = newState
            }
            XCTAssertEqual(state.status, .pairing, "left PAIRING on \(event)")
        }
    }
}
