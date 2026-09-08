import Foundation
import XCTest

@testable import RideLinkCore

/// Runs `protocol/vectors/ordering/ordering_vectors.json` — PROTOCOL §2.1/§5's command ordering.
/// The mirror is `com.ridelink.core.playback.CommandOrderingVectorTest`, running the **same file**.
final class CommandOrderingVectorTests: XCTestCase {
    private func document() throws -> [String: Any] {
        // swiftlint:disable:next force_cast
        try Vectors.loadJSON("ordering/ordering_vectors.json") as! [String: Any]
    }

    private func role(_ name: String) -> PlaybackRole { name == "LEADER" ? .leader : .follower }

    func testTheFullCrossProduct() throws {
        let rows = try document().array("cross_product")
        for element in rows {
            guard let row = element as? [String: Any] else { continue }
            let input = row.dict("input")
            let decision = CommandOrderGate.decide(
                role: role(input.str("role")),
                lastAppliedSeq: input.int64Opt("last_applied_seq"),
                incomingSeq: input.int64("incoming_seq")
            )
            XCTAssertEqual(
                CommandOrderDecision(rawValue: row.dict("expected").str("decision")),
                decision,
                "vector \(row.str("name"))"
            )
        }
        XCTAssertEqual(rows.count, 60, "the cross product is 2 roles x 5 last-applied values x 6 incoming values")
    }

    func testWholeStreams() throws {
        let rows = try document().array("streams")
        for element in rows {
            guard let row = element as? [String: Any] else { continue }
            let name = row.str("name")
            let input = row.dict("input")
            let expected = row.dict("expected")
            let playbackRole = role(input.str("role"))
            var lastApplied: Int64?
            var decisions: [String] = []
            var applied: [Int64] = []
            // swiftlint:disable:next force_cast
            for seqValue in input["incoming_seqs"] as! [Any] {
                // swiftlint:disable:next force_cast
                let seq = (seqValue as! NSNumber).int64Value
                let decision = CommandOrderGate.decide(role: playbackRole, lastAppliedSeq: lastApplied, incomingSeq: seq)
                decisions.append(decision.rawValue)
                if decision == .accept {
                    applied.append(seq)
                    lastApplied = seq
                }
            }
            // swiftlint:disable:next force_cast
            XCTAssertEqual(expected["decisions"] as! [String], decisions, "\(name): decisions")
            // swiftlint:disable:next force_cast
            XCTAssertEqual((expected["applied_seqs"] as! [Any]).map { ($0 as! NSNumber).int64Value }, applied, "\(name): applied set")
            XCTAssertEqual(expected.int64Opt("final_last_applied_seq"), lastApplied, "\(name): final last-applied")
        }
        XCTAssertFalse(rows.isEmpty)
    }
}
