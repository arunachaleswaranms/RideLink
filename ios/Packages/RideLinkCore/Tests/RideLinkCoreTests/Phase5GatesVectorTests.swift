import Foundation
import XCTest

@testable import RideLinkCore

/// Runs `protocol/vectors/phase5-gates/phase5_gates_vectors.json` — ADR-024 Amendment A1's three
/// decision tables. The mirror is `com.ridelink.core.playback.Phase5GatesVectorTest`, running the
/// **same file**.
///
/// The three tables are the audit's answer to CLAUDE.md rule 18: each was coordinator control flow
/// before this amendment, so no vector could pin it and the two platforms had already drifted.
final class Phase5GatesVectorTests: XCTestCase {
    private func document() throws -> [String: Any] {
        // swiftlint:disable:next force_cast
        try Vectors.loadJSON("phase5-gates/phase5_gates_vectors.json") as! [String: Any]
    }

    func testIngressCrossProduct() throws {
        let rows = try document().array("ingress")
        for element in rows {
            guard let row = element as? [String: Any] else { continue }
            let input = row.dict("input")
            let admission = Phase5Ingress.decide(
                kind: Phase5FrameKind(rawValue: input.str("kind"))!,
                queuedTotal: input.int("queued_total"),
                capacity: input.int("capacity"),
                hasQueuedSameKind: input.boolVal("has_queued_same_kind")
            )
            XCTAssertEqual(
                IngressAdmission(rawValue: row.dict("expected").str("admission")),
                admission,
                "vector \(row.str("name"))"
            )
        }
        XCTAssertEqual(rows.count, 64, "2 kinds x 4 capacities x 4 queue depths x 2 sibling states")
    }

    func testPendingCommandCrossProduct() throws {
        let rows = try document().array("pending_command")
        for element in rows {
            guard let row = element as? [String: Any] else { continue }
            let input = row.dict("input")
            let admission = PendingCommandGate.decide(
                clockReady: input.boolVal("clock_ready"),
                deferredCount: input.int("deferred_count"),
                capacity: input.int("capacity")
            )
            XCTAssertEqual(
                CommandAdmission(rawValue: row.dict("expected").str("admission")),
                admission,
                "vector \(row.str("name"))"
            )
        }
        XCTAssertEqual(rows.count, 36, "2 readiness values x 3 capacities x 6 deferred depths")
    }

    func testPendingPlayFullCrossProduct() throws {
        let rows = try document().array("pending_play")
        for element in rows {
            guard let row = element as? [String: Any] else { continue }
            let input = row.dict("input")
            let decision = PendingPlayGate.decide(
                operationCurrent: input.boolVal("operation_current"),
                sessionCurrent: input.boolVal("session_current"),
                syncEnabled: input.boolVal("sync_enabled"),
                queueSettled: input.boolVal("queue_settled"),
                localContentReady: input.boolVal("local_content_ready"),
                peerContentRequired: input.boolVal("peer_content_required"),
                peerHasContent: input.boolVal("peer_has_content")
            )
            XCTAssertEqual(
                PendingPlayDecision(rawValue: row.dict("expected").str("decision")),
                decision,
                "vector \(row.str("name"))"
            )
        }
        XCTAssertEqual(rows.count, 128, "the complete 2^7 cross product — no precondition is a don't-care")
    }

    /// The invariants the generator asserts about itself, re-asserted against *this* implementation
    /// rather than against the JSON. A vector file that agreed with a wrong implementation would
    /// still pass the three tests above; this one cannot.
    func testTheInvariantsHoldForThisImplementation() {
        for kind in Phase5FrameKind.allCases {
            for capacity in [0, 1, 2, 256] {
                for queued in [0, 1, 2, 256] {
                    for sibling in [false, true] {
                        let admission = Phase5Ingress.decide(
                            kind: kind, queuedTotal: queued, capacity: capacity, hasQueuedSameKind: sibling
                        )
                        if queued >= capacity {
                            XCTAssertNotEqual(admission, .admit, "a full queue must never admit")
                        }
                        if kind == .command {
                            XCTAssertNotEqual(admission, .coalesce, "an authoritative command is never superseded")
                        }
                    }
                }
            }
        }
        for ready in [false, true] {
            for deferred in 0 ... 17 {
                let admission = PendingCommandGate.decide(clockReady: ready, deferredCount: deferred, capacity: 16)
                if !ready { XCTAssertNotEqual(admission, .apply, "never schedule against an untrusted clock") }
                if deferred > 0 { XCTAssertNotEqual(admission, .apply, "a deferred command is never overtaken") }
            }
        }
        for mask in 0 ..< 128 {
            func bit(_ index: Int) -> Bool { (mask >> index) & 1 == 1 }
            func decideWith(peerHasContent: Bool) -> PendingPlayDecision {
                PendingPlayGate.decide(
                    operationCurrent: bit(6),
                    sessionCurrent: bit(5),
                    syncEnabled: bit(4),
                    queueSettled: bit(3),
                    localContentReady: bit(2),
                    peerContentRequired: bit(1),
                    peerHasContent: peerHasContent
                )
            }
            let decision = decideWith(peerHasContent: bit(0))
            if !bit(6) || !bit(5) || !bit(4) {
                XCTAssertEqual(decision, .cancel, "a superseded or session-stale Play never resurrects")
            }
            if decision == .issue {
                XCTAssertTrue(bit(6) && bit(5) && bit(4) && bit(3) && bit(2), "ISSUE needs every local precondition")
                XCTAssertTrue(!bit(1) || bit(0), "ISSUE needs the peer half whenever it is this device's question")
            }
            if !bit(1) {
                XCTAssertEqual(
                    decideWith(peerHasContent: false),
                    decideWith(peerHasContent: true),
                    "the peer half is the leader's question, never a follower's (PROTOCOL §5 rule 4)"
                )
            }
        }
        XCTAssertLessThanOrEqual(
            Phase5GateBounds.defaultDeferredCommandCapacity,
            Phase5GateBounds.defaultInboundCapacity,
            "the deferred buffer is a small back-stop, never larger than the ingress bound"
        )
        XCTAssertEqual(Phase5GateBounds.deferredRetryIntervalUs, 100_000)
    }

    func testTheBoundsMatchTheVectorFile() throws {
        let doc = try document()
        XCTAssertEqual(doc.int("default_inbound_capacity"), Phase5GateBounds.defaultInboundCapacity)
        XCTAssertEqual(doc.int("default_deferred_command_capacity"), Phase5GateBounds.defaultDeferredCommandCapacity)
        XCTAssertEqual(doc.int64("deferred_retry_interval_us"), Phase5GateBounds.deferredRetryIntervalUs)
    }
}
