import Foundation
import XCTest

@testable import RideLinkCore

/// Runs `protocol/vectors/session-clock/session_clock_vectors.json` — ARCHITECTURE §7.1's mapping,
/// §7.2's scheduling lead and PROTOCOL §5 rule 2's schedule-or-apply-immediately decision. The
/// mirror is `com.ridelink.core.sync.SessionClockVectorTest`, running the **same file**.
final class SessionClockVectorTests: XCTestCase {
    private func document() throws -> [String: Any] {
        // swiftlint:disable:next force_cast
        try Vectors.loadJSON("session-clock/session_clock_vectors.json") as! [String: Any]
    }

    func testMappingRoundTrips() throws {
        let rows = try document().array("mapping")
        for element in rows {
            guard let row = element as? [String: Any] else { continue }
            let name = row.str("name")
            let input = row.dict("input")
            let expected = row.dict("expected")
            let offset = input.int64("offset_to_leader_us")
            let sessionUs = SessionClock.sessionUs(localMonoUs: input.int64("local_mono_us"), offsetToLeaderUs: offset)
            XCTAssertEqual(expected.int64("session_us"), sessionUs, "\(name): session_us")
            XCTAssertEqual(
                expected.int64("round_trip_local_mono_us"),
                SessionClock.localMonoUs(sessionUs: sessionUs, offsetToLeaderUs: offset),
                "\(name): round trip back to local monotonic"
            )
        }
        XCTAssertFalse(rows.isEmpty)
    }

    func testRttP95AndLead() throws {
        let rows = try document().array("rtt_p95")
        for element in rows {
            guard let row = element as? [String: Any] else { continue }
            let name = row.str("name")
            // swiftlint:disable:next force_cast
            let rtts = (row.dict("input")["rtts_us"] as! [Any]).map { ($0 as! NSNumber).int64Value }
            let expected = row.dict("expected")
            let p95 = ClockSync.rttP95Us(rtts)
            XCTAssertEqual(expected.int64Opt("rtt_p95_us"), p95, "\(name): rtt_p95_us")
            XCTAssertEqual(expected.int64("lead_us"), SessionClock.leadUs(rttP95Us: p95), "\(name): lead_us")

            // The bounded window must agree with the pure function it delegates to, for every input
            // short enough to fit — that is what makes the window a cache rather than a second rule.
            if rtts.count <= ClockSync.rttWindowCapacity {
                var window = ClockSync.RttWindow()
                for rtt in rtts { window.record(rtt) }
                XCTAssertEqual(p95, window.p95Us(), "\(name): RttWindow agrees with rttP95Us")
            }
        }
        XCTAssertFalse(rows.isEmpty)
    }

    func testLeadBoundaries() throws {
        let rows = try document().array("lead")
        for element in rows {
            guard let row = element as? [String: Any] else { continue }
            let name = row.str("name")
            let rtt = row.dict("input").int64Opt("rtt_p95_us")
            XCTAssertEqual(row.dict("expected").int64("lead_us"), SessionClock.leadUs(rttP95Us: rtt), "\(name): lead_us")
        }
        XCTAssertFalse(rows.isEmpty)
    }

    func testScheduleDecisions() throws {
        let rows = try document().array("schedule")
        for element in rows {
            guard let row = element as? [String: Any] else { continue }
            let name = row.str("name")
            let input = row.dict("input")
            let expected = row.dict("expected")
            let decision = ScheduledCommand.decide(
                effectiveAtSessionUs: input.int64("effective_at_session_us"),
                nowLocalMonoUs: input.int64("now_local_mono_us"),
                offsetToLeaderUs: input.int64("offset_to_leader_us")
            )
            switch expected.str("decision") {
            case "SCHEDULE":
                guard case .schedule(let at) = decision else {
                    XCTFail("\(name): expected SCHEDULE, got \(decision)")
                    continue
                }
                XCTAssertEqual(expected.int64("at_local_mono_us"), at, "\(name): deadline")
            default:
                guard case .applyImmediately(let lateness) = decision else {
                    XCTFail("\(name): expected APPLY_IMMEDIATELY, got \(decision)")
                    continue
                }
                XCTAssertEqual(expected.int64("lateness_us"), lateness, "\(name): lateness")
                XCTAssertGreaterThanOrEqual(lateness, 0, "\(name): lateness is never negative")
            }
        }
        XCTAssertFalse(rows.isEmpty)
    }

    func testTheVectorFilesConstantsMatchThisPlatforms() throws {
        let constants = try document().dict("constants")
        XCTAssertEqual(constants.int64("min_lead_us"), SessionClock.minLeadUs)
        XCTAssertEqual(constants.int64("max_lead_us"), SessionClock.maxLeadUs)
        XCTAssertEqual(constants.int64("lead_rtt_multiplier"), SessionClock.leadRttMultiplier)
    }
}
