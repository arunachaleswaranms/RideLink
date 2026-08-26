import Foundation
import XCTest
@testable import RideLinkCore

/// protocol/vectors/clock/clock_vectors.json, ARCHITECTURE §7.1.
final class ClockSyncVectorTests: XCTestCase {
    private func sample(_ obj: [String: Any]) -> ClockSync.Sample {
        ClockSync.Sample(
            t1MonoUs: Int64(obj.int("t1")),
            t2MonoUs: Int64(obj.int("t2")),
            t3MonoUs: Int64(obj.int("t3")),
            t4MonoUs: Int64(obj.int("t4"))
        )
    }

    private func state(_ obj: [String: Any]?) -> ClockSync.EstimatorState? {
        guard let obj else { return nil }
        let offset = Int64(obj.int("offsetUs"))
        let pending = obj.intOpt("pendingOffsetUs").map(Int64.init)
        return ClockSync.EstimatorState(offsetUs: offset, pendingOffsetUs: pending)
    }

    private func status(_ name: String) -> ClockSync.WindowStatus {
        switch name {
        case "accepted": .accepted
        case "rejected_pending_confirmation": .rejectedPendingConfirmation
        case "confirmed": .confirmed
        case "no_estimate": .noEstimate
        default: fatalError("unknown status \(name)")
        }
    }

    func testClockVectors() throws {
        let root = try Vectors.loadJSON("clock/clock_vectors.json") as! [String: Any] // swiftlint:disable:this force_cast
        let vectors = root.array("vectors") as! [[String: Any]] // swiftlint:disable:this force_cast

        for vector in vectors {
            try runVector(vector)
        }
    }

    private func runVector(_ vector: [String: Any]) throws {
        let name = vector.str("name")
        let input = vector.dict("input")
        let expected = vector.dict("expected")

        let previous = state(input.dictOpt("previous_state"))
        let samples = input.array("samples").map { sample($0 as! [String: Any]) } // swiftlint:disable:this force_cast

        let result = ClockSync.applyWindow(previous: previous, samples: samples)

        XCTAssertEqual(result.status, status(expected.str("status")), "[\(name)] status")
        XCTAssertEqual(result.offsetUs, expected.intOpt("offset_us").map(Int64.init), "[\(name)] offset_us")
        XCTAssertEqual(result.rttUs, expected.intOpt("rtt_us").map(Int64.init), "[\(name)] rtt_us")
        XCTAssertEqual(result.jitterUs, expected.intOpt("jitter_us").map(Int64.init), "[\(name)] jitter_us")
        XCTAssertEqual(result.newState, state(expected.dictOpt("new_state")), "[\(name)] new_state")
    }
}
