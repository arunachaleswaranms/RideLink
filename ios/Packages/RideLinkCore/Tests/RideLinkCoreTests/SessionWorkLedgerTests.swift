import XCTest
@testable import RideLinkCore

/// ADR-024 **Amendment A11**'s bound, in isolation.
///
/// The coordinator suites prove that the *right callers* ask this the right question at the right
/// instant; this proves the answers themselves — bounded, exactly-once, immune to ABA, and unable to
/// let a retired lifetime's cleanup free a successor's capacity.
///
/// The mirror is `com.ridelink.core.playback.SessionWorkLedgerTest`.
final class SessionWorkLedgerTests: XCTestCase {
    func testCapacityIsAHardBoundAndARefusalChangesNothing() throws {
        let ledger = SessionWorkLedger(capacity: 3)
        let held = try (1 ... 3).map { _ in try XCTUnwrap(ledger.reserve(generation: 1)) }
        XCTAssertEqual(ledger.liveCount, 3)
        XCTAssertNil(ledger.reserve(generation: 1), "the fourth is refused")
        XCTAssertEqual(ledger.liveCount, 3, "and a refusal consumes nothing")
        ledger.leavePhase(held[0])
        XCTAssertEqual(ledger.liveCount, 2)
        XCTAssertNotNil(ledger.reserve(generation: 1), "released capacity is genuinely reusable")
    }

    func testIdsAreNeverReusedSoAStaleReleaseCannotFreeASuccessorsCapacity() throws {
        let ledger = SessionWorkLedger(capacity: 1)
        let first = try XCTUnwrap(ledger.reserve(generation: 1))
        ledger.leavePhase(first)
        let second = try XCTUnwrap(ledger.reserve(generation: 2))
        XCTAssertGreaterThan(second.id, first.id, "monotonic, so no ABA")
        // The retired holder hands its token back a second time, late.
        ledger.leavePhase(first)
        XCTAssertEqual(ledger.liveCount, 1, "the successor's obligation is untouched")
        XCTAssertTrue(ledger.isLive(second))
        XCTAssertNil(ledger.reserve(generation: 2), "and the bound still holds")
    }

    func testADoubleReleaseFreesExactlyOneObligation() throws {
        let ledger = SessionWorkLedger(capacity: 2)
        let a = try XCTUnwrap(ledger.reserve(generation: 1))
        let b = try XCTUnwrap(ledger.reserve(generation: 1))
        ledger.leavePhase(a)
        ledger.leavePhase(a)
        ledger.leavePhase(a)
        XCTAssertEqual(ledger.liveCount, 1)
        XCTAssertTrue(ledger.isLive(b))
    }

    func testAnObligationSurvivesUntilItsLastPhaseLeaves() throws {
        let ledger = SessionWorkLedger(capacity: 1)
        let reservation = try XCTUnwrap(ledger.reserve(generation: 1))
        XCTAssertTrue(ledger.enterPhase(reservation), "the scheduled node joins the apply's obligation")
        ledger.leavePhase(reservation) // the apply finished
        XCTAssertEqual(ledger.liveCount, 1, "the armed effect still owes work")
        XCTAssertNil(ledger.reserve(generation: 1))
        ledger.leavePhase(reservation) // the armed effect ran
        XCTAssertEqual(ledger.liveCount, 0)
    }

    func testAPhaseCannotJoinAnObligationABoundaryHasAlreadyReleased() throws {
        let ledger = SessionWorkLedger(capacity: 2)
        let reservation = try XCTUnwrap(ledger.reserve(generation: 1))
        ledger.retire(throughGeneration: 1)
        XCTAssertFalse(ledger.enterPhase(reservation), "so the caller must not create the work either")
        XCTAssertEqual(ledger.liveCount, 0)
    }

    func testRetiringALifetimeReleasesItsOwnObligationsAndNeverANewerOnes() throws {
        let ledger = SessionWorkLedger(capacity: 8)
        let old = try [ledger.reserve(generation: 1), ledger.reserve(generation: 1), ledger.reserve(generation: 2)]
            .map { try XCTUnwrap($0) }
        let fresh = try XCTUnwrap(ledger.reserve(generation: 3))
        XCTAssertEqual(ledger.liveCount, 4)

        // Generation 2 ended. Generations strictly increase, so everything at or below it ended too.
        XCTAssertEqual(ledger.retire(throughGeneration: 2), 3)
        XCTAssertEqual(ledger.liveCount, 1)
        XCTAssertTrue(ledger.isLive(fresh), "the successor keeps the capacity it had already reserved")
        for reservation in old { XCTAssertFalse(ledger.isLive(reservation)) }

        // And the retired lifetime's late releases still free nothing of the successor's.
        for reservation in old { ledger.leavePhase(reservation) }
        XCTAssertEqual(ledger.liveCount, 1)
    }

    func testClearIsTerminal() {
        let ledger = SessionWorkLedger(capacity: 4)
        for _ in 0 ..< 4 { _ = ledger.reserve(generation: 7) }
        ledger.clear()
        XCTAssertEqual(ledger.liveCount, 0)
        XCTAssertNotNil(ledger.reserve(generation: 8))
    }

    func testALongAlternatingRunNeverExceedsTheBoundAndNeverLeaks() {
        let ledger = SessionWorkLedger(capacity: 16)
        var generation: Int64 = 1
        var live: [WorkReservation] = []
        for step in 0 ..< 10_000 {
            if step % 500 == 499 {
                ledger.retire(throughGeneration: generation)
                live.removeAll()
                generation += 1
            }
            if let reservation = ledger.reserve(generation: generation) {
                ledger.enterPhase(reservation)
                ledger.leavePhase(reservation) // the apply phase
                live.append(reservation) // the armed phase is still outstanding
            } else {
                // At the bound: discharge the oldest exactly as a completed effect does.
                ledger.leavePhase(live.removeFirst())
            }
            XCTAssertLessThanOrEqual(ledger.liveCount, 16, "step \(step) exceeded the bound")
        }
        ledger.retire(throughGeneration: generation)
        XCTAssertEqual(ledger.liveCount, 0, "nothing is left holding capacity")
    }
}
