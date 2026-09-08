import Foundation
import XCTest

@testable import RideLinkCore

/// Runs `protocol/vectors/drift/drift_vectors.json` — ARCHITECTURE §7.3 / ADR-004's ladder. The
/// mirror is `com.ridelink.core.playback.DriftVectorTest`, running the **same file**.
final class DriftVectorTests: XCTestCase {
    private func document() throws -> [String: Any] {
        // swiftlint:disable:next force_cast
        try Vectors.loadJSON("drift/drift_vectors.json") as! [String: Any]
    }

    func testTheVectorFilesConstantsMatchThisPlatforms() throws {
        let constants = try document().dict("constants")
        XCTAssertEqual(constants.int64("dead_band_ms"), DriftController.deadBandMs)
        XCTAssertEqual(constants.int64("nudge_max_ms"), DriftController.nudgeMaxMs)
        XCTAssertEqual(constants.int64("fail_ms"), DriftController.failMs)
        XCTAssertEqual(constants.int64("converged_ms"), DriftController.convergedMs)
        XCTAssertEqual(constants.doubleVal("rate_slower"), DriftController.rateSlower)
        XCTAssertEqual(constants.doubleVal("rate_normal"), DriftController.rateNormal)
        XCTAssertEqual(constants.doubleVal("rate_faster"), DriftController.rateFaster)
        XCTAssertEqual(constants.int("max_hard_seeks_in_window"), DriftController.maxHardSeeksInWindow)
        XCTAssertEqual(constants.int64("hard_seek_window_us"), DriftController.hardSeekWindowUs)
    }

    func testSingleEvaluations() throws {
        let rows = try document().array("single")
        for element in rows {
            guard let row = element as? [String: Any] else { continue }
            let name = row.str("name")
            let input = row.dict("input")
            let outcome = DriftController.evaluate(state: state(input.dict("state")), input: driftInput(input))
            let expected = row.dict("expected")
            assertAction(name, expected.dict("action"), outcome.action)
            XCTAssertEqual(state(expected.dict("state")), outcome.state, "\(name): state after")
        }
        XCTAssertFalse(rows.isEmpty)
    }

    func testSequences() throws {
        let sequences = try document().array("sequences")
        for element in sequences {
            guard let sequence = element as? [String: Any] else { continue }
            let name = sequence.str("name")
            var carried = DriftController.reset()
            for stepValue in sequence.array("steps") {
                guard let step = stepValue as? [String: Any] else { continue }
                let outcome = DriftController.evaluate(state: carried, input: driftInput(step.dict("input")))
                assertAction(name, step.dict("action"), outcome.action)
                XCTAssertEqual(state(step.dict("state_after")), outcome.state, "\(name): state after")
                carried = outcome.state
            }
        }
        XCTAssertFalse(sequences.isEmpty)
    }

    private func state(_ json: [String: Any]) -> DriftState {
        DriftState(
            nudging: json.boolVal("nudging"),
            nudgeRate: json.doubleVal("nudge_rate"),
            // swiftlint:disable:next force_cast
            hardSeekAtSessionUs: (json["hard_seek_at_session_us"] as! [Any]).map { ($0 as! NSNumber).int64Value },
            failed: json.boolVal("failed")
        )
    }

    private func driftInput(_ json: [String: Any]) -> DriftInput {
        DriftInput(
            driftMs: json.int64("drift_ms"),
            nowSessionUs: json.int64("now_session_us"),
            expectedPositionMs: json.int64("expected_position_ms"),
            playing: json.boolVal("playing"),
            routeTransitioning: json.boolVal("route_transitioning")
        )
    }

    private func assertAction(_ name: String, _ expected: [String: Any], _ actual: DriftAction) {
        switch expected.str("kind") {
        case "NONE": XCTAssertEqual(actual, .none, "\(name): action")
        case "RESTORE_RATE": XCTAssertEqual(actual, .restoreRate, "\(name): action")
        case "DECLARE_SYNC_FAILURE": XCTAssertEqual(actual, .declareSyncFailure, "\(name): action")
        case "NUDGE": XCTAssertEqual(actual, .nudge(rate: expected.doubleVal("rate")), "\(name): action")
        default: XCTAssertEqual(actual, .hardSeek(positionMs: expected.int64("position_ms")), "\(name): action")
        }
    }
}
