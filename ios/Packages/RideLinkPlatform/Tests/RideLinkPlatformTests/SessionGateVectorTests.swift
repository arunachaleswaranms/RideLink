import XCTest
@testable import RideLinkCore
@testable import RideLinkPlatform

/// Runs `protocol/vectors/session-gate/gate_vectors.json` — the complete 120-row trust-gate table —
/// against `SessionGate`.
///
/// The mirror is Android's `SessionGateVectorTest`, running the **same file**. A gate that read
/// `.connected` as implicit pairing success on one platform and not the other would otherwise be
/// invisible until two phones met on a ride (ADR-019).
final class SessionGateVectorTests: XCTestCase {
    private let remote = PeerId("bbbbbbbbbbbbbbbb")

    func testEveryRowOfTheSharedTrustGateTableHolds() throws {
        let rows = try loadRows()
        for row in rows {
            let name = try XCTUnwrap(row["name"] as? String)
            let status = try XCTUnwrap(SessionStatus(rawValue: try XCTUnwrap(row["status"] as? String)))
            let event = try controlEvent(try XCTUnwrap(row["control_event"] as? [String: Any]))
            let expected = try (row["session_event"] as? [String: Any]).map { try sessionEvent($0) }
            XCTAssertEqual(SessionGate.sessionEvent(for: event, status: status), expected, "vector \(name)")
        }
        XCTAssertEqual(rows.count, 120, "the table is the complete cross-product; a shrunken file is a bug")
    }

    func testNoRowLetsConnectedMeanPairingSucceeded() throws {
        // The Phase 1b security bug, stated as a property of the shared table rather than of one
        // platform's code.
        let offending = try loadRows().filter { row in
            (row["control_event"] as? [String: Any])?["kind"] as? String == "Connected"
                && (row["session_event"] as? [String: Any])?["kind"] as? String == "PairingSucceeded"
        }
        XCTAssertTrue(offending.isEmpty, "the vectors themselves must not permit it: \(offending)")
    }

    private func loadRows() throws -> [[String: Any]] {
        let document = try XCTUnwrap(try Vectors.loadJSON("session-gate/gate_vectors.json") as? [String: Any])
        return try XCTUnwrap(document["rows"] as? [[String: Any]])
    }

    private func controlEvent(_ spec: [String: Any]) throws -> ControlEvent {
        switch try XCTUnwrap(spec["kind"] as? String) {
        case "Connected":
            return .connected(remotePeerId: remote, sessionId: SessionId("s"), isLocalLeader: true)
        case "PeerTrusted":
            return .peerTrusted(remotePeerId: remote)
        case "PairingRequired":
            return .pairingRequired(remotePeerId: remote)
        case "PairingSucceeded":
            return .pairingSucceeded(peer: TrustedPeer(
                peerId: remote,
                identitySpkiSha256: SpkiHash("sha256:" + String(repeating: "ab", count: 32)),
                displayName: "B", pairedAtEpochSeconds: 1, lastSeenAtEpochSeconds: 1))
        case "PairingFailed":
            return .pairingFailed(code: errorCodePairingRejected)
        case "HandshakeRefused":
            return .handshakeRefused(code: errorCodePinMismatch)
        case "LinkLost":
            return .linkLost(reason: try platformLinkLossReason(try XCTUnwrap(spec["reason"] as? String)))
        case "DuplicateConnectionClosed":
            return .duplicateConnectionClosed
        case "ReconnectBudgetExhausted":
            return .reconnectBudgetExhausted
        case let other:
            throw VectorError.unknown("control event \(other)")
        }
    }

    private func sessionEvent(_ spec: [String: Any]) throws -> SessionEvent {
        switch try XCTUnwrap(spec["kind"] as? String) {
        case "ConnectionEstablished": return .connectionEstablished
        case "ConnectionFailed": return .connectionFailed
        case "ReconnectSucceeded": return .reconnectSucceeded
        case "ReconnectBudgetExhausted": return .reconnectBudgetExhausted
        case "PairingSucceeded": return .pairingSucceeded
        case "PairingRejectedOrTimeout": return .pairingRejectedOrTimeout
        case "DuplicateConnectionClosed": return .duplicateConnectionClosed
        case "LinkLost":
            return .linkLost(reason: try fsmLinkLossReason(try XCTUnwrap(spec["reason"] as? String)))
        case let other:
            throw VectorError.unknown("session event \(other)")
        }
    }

    private func platformLinkLossReason(_ raw: String) throws -> RideLinkPlatform.LinkLossReason {
        switch raw {
        case "NETWORK": return .network
        case "BYE": return .bye
        case "DUPLICATE_CONNECTION": return .duplicateConnection
        case "USER_ENDED": return .userEnded
        default: throw VectorError.unknown("link loss reason \(raw)")
        }
    }

    private func fsmLinkLossReason(_ raw: String) throws -> RideLinkCore.LinkLossReason {
        switch raw {
        case "NETWORK": return .network
        case "BYE": return .bye
        default: throw VectorError.unknown("fsm link loss reason \(raw)")
        }
    }

    private enum VectorError: Error {
        case unknown(String)
    }
}
