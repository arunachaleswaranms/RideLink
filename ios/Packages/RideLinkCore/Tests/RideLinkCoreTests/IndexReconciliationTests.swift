import Foundation
import XCTest
@testable import RideLinkCore

private func quickId(_ byte: String) -> QuickId { QuickId("sha256:" + String(repeating: byte, count: 32)) }

private func location(_ name: String) -> LocalTrackLocation { LocalTrackLocation(uri: name) }

/// Mirrors `com.ridelink.core.library.IndexReconciliationTest` — including its ADR-005 Amendment A1
/// additions, "two different locations sharing a quick id are never collapsed" and "renaming a file
/// is no longer invisible".
final class IndexReconciliationTests: XCTestCase {
    private let a = quickId("a1")
    private let b = quickId("b2")
    private let c = quickId("c3")
    private let locA = location("content://a")
    private let locB = location("content://b")
    private let locC = location("content://c")

    func testALocationSeenForTheFirstTimeIsNew() {
        let plan = IndexReconciliation.reconcile(previous: [:], discovered: [locA: a])
        XCTAssertEqual([locA], plan.newLocations)
        XCTAssertTrue(plan.unchangedLocations.isEmpty)
        XCTAssertTrue(plan.changedLocations.isEmpty)
        XCTAssertTrue(plan.missingLocations.isEmpty)
    }

    func testALocationSeenAgainWithAnUnchangedQuickIdIsUnchangedNotReindexed() {
        let plan = IndexReconciliation.reconcile(previous: [locA: a], discovered: [locA: a])
        XCTAssertEqual([locA], plan.unchangedLocations)
        XCTAssertTrue(plan.newLocations.isEmpty)
        XCTAssertTrue(plan.changedLocations.isEmpty)
        XCTAssertTrue(plan.missingLocations.isEmpty)
    }

    func testALocationSeenAgainWithADifferentQuickIdIsChangedEditedInPlace() {
        // Same location, but its content was edited since the last scan — ADR-005's "a file edited
        // in place changes both hashes; quick_id detects it cheaply on rescan," at the location level.
        let plan = IndexReconciliation.reconcile(previous: [locA: a], discovered: [locA: b])
        XCTAssertEqual([locA], plan.changedLocations)
        XCTAssertTrue(plan.newLocations.isEmpty)
        XCTAssertTrue(plan.unchangedLocations.isEmpty)
        XCTAssertTrue(plan.missingLocations.isEmpty)
    }

    func testALocationNoLongerFoundIsMissingNotSilentlyDropped() {
        let plan = IndexReconciliation.reconcile(previous: [locA: a], discovered: [:])
        XCTAssertEqual([locA], plan.missingLocations)
    }

    func testAPreviouslyMissingLocationFoundAgainReturnsToUnchanged() {
        let afterRemoval = IndexReconciliation.reconcile(previous: [locA: a], discovered: [:])
        XCTAssertEqual([locA], afterRemoval.missingLocations)
        // The next scan runs against the same "previously indexed" map (locA is still known, just
        // marked missing) — rediscovering it with the same quick id must clear that, not require
        // re-adding it as new.
        let rediscovered = IndexReconciliation.reconcile(previous: [locA: a], discovered: [locA: a])
        XCTAssertEqual([locA], rediscovered.unchangedLocations)
    }

    func testTwoDifferentLocationsSharingAQuickIdAreNeverCollapsed() {
        // ADR-005 Amendment A1 / the closure-audit CRITICAL finding: QuickId is a 128 KiB sample, not
        // authoritative identity. Two distinct locations that happen to hash to the same QuickId (a
        // false collision, or two genuinely byte-identical files at two paths) must both surface as
        // their own location — real FR-010 dedup happens later, only via ContentHash equality, never
        // here.
        let plan = IndexReconciliation.reconcile(previous: [:], discovered: [locA: a, locB: a])
        XCTAssertEqual(Set([locA, locB]), plan.newLocations)
    }

    func testRenamingAFileIsNoLongerInvisibleItSurfacesAsMissingPlusNew() {
        // ADR-005 Amendment A1: the previous design silently followed a QuickId to its new location,
        // which is exactly the unsafe cross-location comparison this amendment removes. A rename is
        // therefore not free — it costs one reindex — but it can never merge two different files.
        let plan = IndexReconciliation.reconcile(previous: [locA: a], discovered: [locB: a])
        XCTAssertEqual([locA], plan.missingLocations)
        XCTAssertEqual([locB], plan.newLocations)
    }

    func testAMixedScanPartitionsCorrectly() {
        let plan = IndexReconciliation.reconcile(previous: [locA: a, locB: b], discovered: [locB: b, locC: c])
        XCTAssertEqual([locC], plan.newLocations)
        XCTAssertEqual([locB], plan.unchangedLocations)
        XCTAssertEqual([locA], plan.missingLocations)
    }
}
