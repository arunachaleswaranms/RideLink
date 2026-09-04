import Foundation
import XCTest
@testable import RideLinkCore

private func quickId(_ byte: String) -> QuickId { QuickId("sha256:" + String(repeating: byte, count: 32)) }

final class IndexReconciliationTests: XCTestCase {
    private let a = quickId("a1")
    private let b = quickId("b2")
    private let c = quickId("c3")

    func testAFileSeenForTheFirstTimeIsNew() {
        let plan = IndexReconciliation.reconcile(previousQuickIds: [], discoveredQuickIds: [a])
        XCTAssertEqual([a], plan.newQuickIds)
        XCTAssertTrue(plan.stillPresentQuickIds.isEmpty)
        XCTAssertTrue(plan.missingQuickIds.isEmpty)
    }

    func testAFileSeenAgainIsStillPresentNotReindexed() {
        let plan = IndexReconciliation.reconcile(previousQuickIds: [a], discoveredQuickIds: [a])
        XCTAssertEqual([a], plan.stillPresentQuickIds)
        XCTAssertTrue(plan.newQuickIds.isEmpty)
        XCTAssertTrue(plan.missingQuickIds.isEmpty)
    }

    func testAFileNoLongerFoundIsMissingNotSilentlyDropped() {
        let plan = IndexReconciliation.reconcile(previousQuickIds: [a], discoveredQuickIds: [])
        XCTAssertEqual([a], plan.missingQuickIds)
    }

    func testAPreviouslyMissingFileFoundAgainReturnsToStillPresent() {
        let afterRemoval = IndexReconciliation.reconcile(previousQuickIds: [a], discoveredQuickIds: [])
        XCTAssertEqual([a], afterRemoval.missingQuickIds)
        let rediscovered = IndexReconciliation.reconcile(previousQuickIds: [a], discoveredQuickIds: [a])
        XCTAssertEqual([a], rediscovered.stillPresentQuickIds)
    }

    func testTwoFilesWithIdenticalBytesCollapseToOneQuickId() {
        let discovered: Set<QuickId> = [a]
        let plan = IndexReconciliation.reconcile(previousQuickIds: [], discoveredQuickIds: discovered)
        XCTAssertEqual(1, plan.newQuickIds.count)
    }

    func testRenamingAFileIsInvisibleToReconciliation() {
        let plan = IndexReconciliation.reconcile(previousQuickIds: [a, b], discoveredQuickIds: [a, b])
        XCTAssertEqual(Set([a, b]), plan.stillPresentQuickIds)
    }

    func testAMixedScanPartitionsCorrectly() {
        let plan = IndexReconciliation.reconcile(previousQuickIds: [a, b], discoveredQuickIds: [b, c])
        XCTAssertEqual([c], plan.newQuickIds)
        XCTAssertEqual([b], plan.stillPresentQuickIds)
        XCTAssertEqual([a], plan.missingQuickIds)
    }
}
