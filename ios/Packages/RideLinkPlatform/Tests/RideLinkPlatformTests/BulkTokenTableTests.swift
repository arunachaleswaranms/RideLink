import Foundation
import RideLinkCore
import XCTest
@testable import RideLinkPlatform

/// Closure-audit Findings A/L/M, isolated from `TransferManager`'s socket/TLS plumbing —
/// `TransferManagerTests` already proves the end-to-end real-loopback behaviour; these prove the
/// token table's own rules directly and fast. The Kotlin mirror is `BulkTokenTableTest`.
final class BulkTokenTableTests: XCTestCase {
    private func table(now: @escaping @Sendable () -> Int64 = { 0 }) -> BulkTokenTable {
        BulkTokenTable(monotonicNowUs: now)
    }

    func testATokenIssuedUnderGenerationNIsRejectedOnceTheLiveGenerationMovesToNPlusOne() async {
        // Finding A's real bug lived in the *production caller* (a captured `let generation`
        // replayed into a closure), not in this table — but the invariant the fix depends on is
        // this table's own: validation must be checked against whatever `currentGeneration` the
        // caller supplies at consumption time, not stored anywhere here at issuance time.
        let t = table()
        let transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5C")
        let token = await t.issue(transferId: transferId, generation: 5)

        let ok = await t.validateAndConsume(transferId: transferId, presentedToken: token, currentGeneration: 6)
        XCTAssertFalse(ok, "a token minted under generation 5 must fail once the live generation is 6")
    }

    func testATokenIssuedAndValidatedUnderTheSameStillCurrentGenerationSucceeds() async {
        let t = table()
        let transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5C")
        let token = await t.issue(transferId: transferId, generation: 5)
        let ok = await t.validateAndConsume(transferId: transferId, presentedToken: token, currentGeneration: 5)
        XCTAssertTrue(ok)
    }

    func testSingleUseASecondPresentationOfTheSameTokenIsRejected() async {
        let t = table()
        let transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5C")
        let token = await t.issue(transferId: transferId, generation: 1)
        let firstOk = await t.validateAndConsume(transferId: transferId, presentedToken: token, currentGeneration: 1)
        XCTAssertTrue(firstOk)
        let secondOk = await t.validateAndConsume(transferId: transferId, presentedToken: token, currentGeneration: 1)
        XCTAssertFalse(secondOk, "a consumed token must never validate again")
    }

    func testAnExpiredTokenIsRejectedEvenWithTheRightGenerationAndTokenBytes() async {
        final class Now: @unchecked Sendable {
            var value: Int64 = 0
        }
        let now = Now()
        let t = table(now: { now.value })
        let transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5C")
        let token = await t.issue(transferId: transferId, generation: 1)

        now.value = 31_000_000 // past the 30s TTL
        let ok = await t.validateAndConsume(transferId: transferId, presentedToken: token, currentGeneration: 1)
        XCTAssertFalse(ok)
    }

    func testTheWrongTokenIsRejectedEvenWithTheRightTransferIdAndGeneration() async {
        let t = table()
        let transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5C")
        _ = await t.issue(transferId: transferId, generation: 1)
        let ok = await t.validateAndConsume(transferId: transferId, presentedToken: String(repeating: "0", count: 64), currentGeneration: 1)
        XCTAssertFalse(ok)
    }

    // MARK: - Finding M: reissue collision

    func testTryIssueRefusesToOverwriteAStillLiveUnconsumedEntryForTheSameTransferId() async {
        let t = table()
        let transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5C")
        let first = await t.issue(transferId: transferId, generation: 1)

        let second = await t.tryIssue(transferId: transferId, generation: 1)
        XCTAssertNil(second, "a live unconsumed entry must not be silently replaced")
        let ok = await t.validateAndConsume(transferId: transferId, presentedToken: first, currentGeneration: 1)
        XCTAssertTrue(ok)
    }

    func testTryIssueSucceedsOnceThePriorEntryForThatTransferIdHasBeenConsumed() async {
        let t = table()
        let transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5C")
        let first = await t.issue(transferId: transferId, generation: 1)
        let ok = await t.validateAndConsume(transferId: transferId, presentedToken: first, currentGeneration: 1)
        XCTAssertTrue(ok)

        let second = await t.tryIssue(transferId: transferId, generation: 1)
        XCTAssertNotNil(second, "a transfer id whose prior token was already consumed may be reissued")
    }

    func testTryIssueSucceedsOnceThePriorEntryForThatTransferIdHasExpired() async {
        final class Now: @unchecked Sendable {
            var value: Int64 = 0
        }
        let now = Now()
        let t = table(now: { now.value })
        let transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5C")
        _ = await t.issue(transferId: transferId, generation: 1)

        now.value = 31_000_000
        let second = await t.tryIssue(transferId: transferId, generation: 1)
        XCTAssertNotNil(second, "an expired prior entry does not block reissue")
    }

    func testSweepBelowRemovesOnlyEntriesFromAnEarlierGeneration() async {
        let t = table()
        let old = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5C")
        let fresh = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5D")
        let oldToken = await t.issue(transferId: old, generation: 1)
        let freshToken = await t.issue(transferId: fresh, generation: 2)

        await t.sweepBelow(2)

        let oldOk = await t.validateAndConsume(transferId: old, presentedToken: oldToken, currentGeneration: 2)
        XCTAssertFalse(oldOk)
        let freshOk = await t.validateAndConsume(transferId: fresh, presentedToken: freshToken, currentGeneration: 2)
        XCTAssertTrue(freshOk)
    }
}
