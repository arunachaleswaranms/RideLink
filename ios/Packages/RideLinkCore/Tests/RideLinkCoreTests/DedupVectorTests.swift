import Foundation
import XCTest
@testable import RideLinkCore

/// protocol/vectors/dedup/dedup_vectors.json, PROTOCOL §4.2, ADR-015 (+ this session's Amendment
/// A2), ADR-010 (+ Amendment A2). Deliberately proves `Dedup` and `Leadership` are independent.
final class DedupVectorTests: XCTestCase {
    private func peerTiebreak(_ obj: [String: Any]) -> Dedup.PeerTiebreak {
        Dedup.PeerTiebreak(peerId: PeerId(obj.str("peer_id")), connTiebreak: ConnTiebreak(obj.str("conn_tiebreak")))
    }

    private func side(_ s: String) -> Dedup.Side { s == "a" ? .a : .b }

    func testDedupVectors() throws {
        let root = try Vectors.loadJSON("dedup/dedup_vectors.json") as! [String: Any] // swiftlint:disable:this force_cast
        let vectors = root.array("vectors") as! [[String: Any]] // swiftlint:disable:this force_cast

        for vector in vectors {
            try runVector(name: vector.str("name"), vector: vector)
        }
    }

    // swiftlint:disable:next cyclomatic_complexity function_body_length
    private func runVector(name: String, vector: [String: Any]) throws {
        let input = vector.dict("input")
        let expected = vector.dict("expected")

        switch name {
        case "larger-tiebreak-outbound-survives", "smaller-tiebreak-outbound-loses":
            let a = peerTiebreak(input.dict("peer_a"))
            let b = peerTiebreak(input.dict("peer_b"))
            guard case .survivor(let survivor) = Dedup.resolve(a, b) else {
                XCTFail("[\(name)] expected a survivor, not a tie")
                return
            }
            if let expectedSurvivor = expected.strOpt("survivor") {
                XCTAssertEqual(survivor, side(expectedSurvivor), "[\(name)] survivor mismatch")
            }
            if let expectedInitiator = expected.strOpt("surviving_connection_initiator") {
                XCTAssertEqual(survivor, side(expectedInitiator), "[\(name)] initiator mismatch")
            }

        case "both-peers-derive-same-verdict":
            let fromA = input.dict("from_peer_a_perspective")
            let fromB = input.dict("from_peer_b_perspective")
            guard
                case .survivor(let aVerdict) = Dedup.resolve(
                    Dedup.PeerTiebreak(peerId: PeerId("0000000000000000"), connTiebreak: ConnTiebreak(fromA.str("self_conn_tiebreak"))),
                    Dedup.PeerTiebreak(peerId: PeerId("ffffffffffffffff"), connTiebreak: ConnTiebreak(fromA.str("remote_conn_tiebreak")))
                ),
                case .survivor(let bVerdict) = Dedup.resolve(
                    Dedup.PeerTiebreak(peerId: PeerId("ffffffffffffffff"), connTiebreak: ConnTiebreak(fromB.str("self_conn_tiebreak"))),
                    Dedup.PeerTiebreak(peerId: PeerId("0000000000000000"), connTiebreak: ConnTiebreak(fromB.str("remote_conn_tiebreak")))
                )
            else {
                XCTFail("[\(name)] expected survivors, not ties")
                return
            }
            // A computed (self=A, remote=B): self surviving means .a. B computed (self=B,
            // remote=A): remote surviving means .b. Both must agree on whether peer A's outbound
            // connection is the one that survives.
            let doesASurviveAccordingToA = aVerdict == .a
            let doesASurviveAccordingToB = bVerdict == .b
            XCTAssertEqual(doesASurviveAccordingToA, doesASurviveAccordingToB, "[\(name)] both peers must derive the same physical survivor")

        case "equal-tiebreak-both-close-and-regenerate":
            let a = peerTiebreak(input.dict("peer_a"))
            let b = peerTiebreak(input.dict("peer_b"))
            XCTAssertEqual(Dedup.resolve(a, b), .tie, "[\(name)] expected a tie")

        case "initiator-not-assumed-leader":
            let a = peerTiebreak(input.dict("peer_a"))
            let b = peerTiebreak(input.dict("peer_b"))
            guard case .survivor(let survivor) = Dedup.resolve(a, b) else {
                XCTFail("[\(name)] expected a survivor")
                return
            }
            let leader = Leadership.elect(a.peerId, b.peerId)
            if let expectedInitiator = expected.strOpt("surviving_connection_initiator") {
                XCTAssertEqual(survivor, side(expectedInitiator), "[\(name)] initiator mismatch")
            }
            if let expectedLeader = expected.strOpt("leader") {
                XCTAssertEqual(leader, side(expectedLeader), "[\(name)] leader mismatch")
            }
            XCTAssertNotEqual(survivor, leader, "[\(name)] initiator must NOT equal leader in this vector")

        case "acceptor-not-assumed-leader":
            let a = peerTiebreak(input.dict("peer_a"))
            let b = peerTiebreak(input.dict("peer_b"))
            guard case .survivor(let survivor) = Dedup.resolve(a, b) else {
                XCTFail("[\(name)] expected a survivor")
                return
            }
            let acceptor: Dedup.Side = survivor == .a ? .b : .a
            let leader = Leadership.elect(a.peerId, b.peerId)
            if let expectedAcceptor = expected.strOpt("surviving_connection_acceptor") {
                XCTAssertEqual(acceptor, side(expectedAcceptor), "[\(name)] acceptor mismatch")
            }
            if let expectedLeader = expected.strOpt("leader") {
                XCTAssertEqual(leader, side(expectedLeader), "[\(name)] leader mismatch")
            }
            XCTAssertNotEqual(acceptor, leader, "[\(name)] acceptor must NOT equal leader in this vector")

        default:
            XCTFail("unhandled dedup vector: \(name)")
        }
    }
}
