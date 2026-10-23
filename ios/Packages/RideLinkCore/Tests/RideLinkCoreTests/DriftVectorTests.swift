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
        XCTAssertEqual(constants.int64("reference_settle_us"), DriftController.referenceSettleUs)
        XCTAssertEqual(constants.int64("max_reference_age_us"), DriftController.maxReferenceAgeUs)
        XCTAssertEqual(constants.int64("max_restart_compensation_ms"), DriftController.maxRestartCompensationMs)
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
            XCTAssertEqual(nullableInt64(expected["error_ms"]), outcome.errorMs, "\(name): error")
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
                XCTAssertEqual(nullableInt64(step["error_ms"]), outcome.errorMs, "\(name): error")
                carried = outcome.state
            }
        }
        XCTAssertFalse(sequences.isEmpty)
    }

    func testEpochs() throws {
        let rows = try document().array("epochs")
        for element in rows {
            guard let row = element as? [String: Any] else { continue }
            let name = row.str("name")
            let before = state(row.dict("state"))
            let after: DriftState
            switch row.str("op") {
            case "new_epoch": after = DriftController.newEpoch(before)
            case "reset": after = DriftController.reset()
            default: XCTFail("\(name): unknown op"); continue
            }
            XCTAssertEqual(state(row.dict("expected")), after, "\(name): state after")
        }
        XCTAssertFalse(rows.isEmpty)
    }

    private func nullableInt64(_ value: Any?) -> Int64? {
        (value as? NSNumber)?.int64Value
    }

    private func state(_ json: [String: Any]) -> DriftState {
        let pending = json["pending_seek"] as? [String: Any]
        return DriftState(
            nudging: json.boolVal("nudging"),
            nudgeRate: json.doubleVal("nudge_rate"),
            // swiftlint:disable:next force_cast
            hardSeekAtSessionUs: (json["hard_seek_at_session_us"] as! [Any]).map { ($0 as! NSNumber).int64Value },
            failed: json.boolVal("failed"),
            restartCompensationMs: json.int64("restart_compensation_ms"),
            pendingSeek: pending.map {
                PendingSeek(compensationMs: $0.int64("compensation_ms"), anchorSessionUs: $0.int64("anchor_session_us"))
            },
            routeChangeCount: nullableInt64(json["route_change_count"])
        )
    }

    private func driftInput(_ json: [String: Any]) -> DriftInput {
        let reference = json["reference"] as? [String: Any]
        return DriftInput(
            driftMs: json.int64("drift_ms"),
            nowSessionUs: json.int64("now_session_us"),
            expectedPositionMs: json.int64("expected_position_ms"),
            playing: json.boolVal("playing"),
            routeTransitioning: json.boolVal("route_transitioning"),
            anchorSessionUs: json.int64("anchor_session_us"),
            reference: reference.map {
                DriftReference(
                    driftMs: $0.int64("drift_ms"), atSessionUs: $0.int64("at_session_us"), playing: $0.boolVal("playing"),
                    anchorSessionUs: $0.int64("anchor_session_us"), anchorPositionMs: $0.int64("anchor_position_ms")
                )
            },
            routeChangeCount: json.int64("route_change_count"),
            anchorPositionMs: json.int64("anchor_position_ms")
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
