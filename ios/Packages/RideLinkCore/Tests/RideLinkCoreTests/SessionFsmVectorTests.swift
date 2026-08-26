import Foundation
import XCTest
@testable import RideLinkCore

/// protocol/vectors/session-fsm/fsm_vectors.json, ARCHITECTURE §3, TEST_PLAN §2.
final class SessionFsmVectorTests: XCTestCase {
    private func parseStatus(_ raw: String) -> SessionStatus {
        switch raw {
        case "IDLE": return .idle
        case "DISCOVERING": return .discovering
        case "PAIRING": return .pairing
        case "CONNECTING": return .connecting
        case "CONNECTED": return .connected
        case "RIDE_ACTIVE": return .rideActive
        case "RECONNECTING": return .reconnecting
        case "DISCONNECTED": return .disconnected
        case "ENDING": return .ending
        case "ERROR": return .error
        default: fatalError("unknown status in vector: \(raw)")
        }
    }

    private func parseState(_ obj: [String: Any]) -> FsmState {
        let status = parseStatus(obj.str("status"))
        let returnTo = obj.strOpt("returnTo").map(parseStatus)
        return FsmState(status: status, returnTo: returnTo)
    }

    // swiftlint:disable:next cyclomatic_complexity
    private func parseEvent(_ obj: [String: Any]) -> SessionEvent {
        switch obj.str("kind") {
        case "StartDiscovery": return .startDiscovery
        case "CancelDiscovery": return .cancelDiscovery
        case "PeerSelected": return .peerSelected
        case "PairingRejectedOrTimeout": return .pairingRejectedOrTimeout
        case "PairingSucceeded": return .pairingSucceeded
        case "ConnectionEstablished": return .connectionEstablished
        case "ConnectionFailed": return .connectionFailed
        case "ReconnectSucceeded": return .reconnectSucceeded
        case "ReconnectBudgetExhausted": return .reconnectBudgetExhausted
        case "RetryRequested": return .retryRequested
        case "StartRide": return .startRide
        case "EndRide": return .endRide
        case "LinkLost": return .linkLost(reason: obj.str("reason") == "BYE" ? .bye : .network)
        case "UserEnded": return .userEnded
        case "FatalError": return .fatalError(reason: obj.str("reason"))
        case "ErrorAcknowledged": return .errorAcknowledged
        case "TeardownComplete": return .teardownComplete
        case "DuplicateConnectionClosed": return .duplicateConnectionClosed
        default: fatalError("unknown event kind in vector: \(obj.str("kind"))")
        }
    }

    func testLegalTransitions() throws {
        let root = try Vectors.loadJSON("session-fsm/fsm_vectors.json") as! [String: Any] // swiftlint:disable:this force_cast
        let vectors = root.array("legal_transitions") as! [[String: Any]] // swiftlint:disable:this force_cast

        for vector in vectors {
            let name = vector.str("name")
            let from = parseState(vector.dict("from"))
            let event = parseEvent(vector.dict("event"))
            let expectedTo = parseState(vector.dict("to"))

            guard case .transitioned(let newState, let effects) = SessionFsm.transition(from, event) else {
                XCTFail("[\(name)] expected a legal transition")
                continue
            }
            XCTAssertEqual(newState, expectedTo, "[\(name)] resulting state mismatch")

            let expectedEffects = (vector["effects"] as? [String]) ?? []
            if expectedEffects.contains("RELEASE_AUDIO_AND_STOP_FOREGROUND_SERVICE") {
                let hasReleaseEffect = effects.contains { if case .releaseAudioAndStopForegroundService = $0 { return true }; return false }
                XCTAssertTrue(hasReleaseEffect, "[\(name)] expected the audio-release effect on entering \(expectedTo.status)")
            }
            let hasLogEffect = effects.contains { if case .logTransition = $0 { return true }; return false }
            XCTAssertTrue(hasLogEffect, "[\(name)] every real transition must be logged (ARCHITECTURE §3 rule 5)")
        }
    }

    func testIllegalTransitions() throws {
        let root = try Vectors.loadJSON("session-fsm/fsm_vectors.json") as! [String: Any] // swiftlint:disable:this force_cast
        let vectors = root.array("illegal_transitions") as! [[String: Any]] // swiftlint:disable:this force_cast

        for vector in vectors {
            let name = vector.str("name")
            let from = parseState(vector.dict("from"))
            let event = parseEvent(vector.dict("event"))

            guard case .rejected(let currentState, _) = SessionFsm.transition(from, event) else {
                XCTFail("[\(name)] expected the transition to be rejected, not applied or crash")
                continue
            }
            XCTAssertEqual(currentState, from, "[\(name)] state must be unchanged after an illegal transition")
        }
    }

    func testNonFaultNonTransitionEvents() throws {
        let root = try Vectors.loadJSON("session-fsm/fsm_vectors.json") as! [String: Any] // swiftlint:disable:this force_cast
        let vectors = root.array("non_fault_non_transition_events") as! [[String: Any]] // swiftlint:disable:this force_cast

        for vector in vectors {
            let name = vector.str("name")
            let from = parseState(vector.dict("from"))
            let event = parseEvent(vector.dict("event"))

            guard case .ignored(let currentState, _, _) = SessionFsm.transition(from, event) else {
                XCTFail("[\(name)] duplicate-connection-closed must be ignored, not rejected or transitioned")
                continue
            }
            XCTAssertEqual(currentState, from, "[\(name)] state must be unchanged")
        }
    }
}
