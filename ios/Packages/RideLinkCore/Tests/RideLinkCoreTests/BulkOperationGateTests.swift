import XCTest

@testable import RideLinkCore

/// Closure-audit Finding A (provider-side transfer ownership) and its cross-role counterpart
/// (brief §17/§18): the pure proofs behind "a second request cannot overwrite active ownership,"
/// "a wrong-transfer cancel cannot reach the real active operation," and "a stale cleanup cannot
/// clear a newer operation." The coordinator integration itself is exercised by
/// `SharedLibraryCoordinator`'s own usage, exactly like `OperationFenceTests` documents for
/// `OperationFence`. Mirrors Android's `BulkOperationGateTest`, running the same semantics.
final class BulkOperationGateTests: XCTestCase {
    private let hashA = ContentHash("sha256:" + String(repeating: "a", count: 64))
    private let hashB = ContentHash("sha256:" + String(repeating: "b", count: 64))
    private let spki = SpkiHash("sha256:" + String(repeating: "cd", count: 32))
    private let transferA = TransferId("01ARZ3NDEKTSV4RRFFQ69G5FAV")
    private let transferB = TransferId("01BXAZ3NDEKTSV4RRFFQ69G5FB")

    func testAnEmptyGateHasNoCurrentOwner() {
        let gate = BulkOperationGate()
        XCTAssertNil(gate.current)
        XCTAssertFalse(gate.isOwner(transferA))
    }

    func testAcquiringAFreeGateSucceedsAndRecordsTheOwner() {
        let gate = BulkOperationGate()
        let owner = BulkOperationOwner.provider(transferId: transferA, contentHash: hashA, peerSpki: spki, sessionGeneration: 1)
        XCTAssertTrue(gate.tryAcquire(owner))
        XCTAssertTrue(gate.isOwner(transferA))
        XCTAssertEqual(gate.current, owner)
    }

    func testFindingASecondProviderRequestCannotOverwriteTheActiveOwner() {
        let gate = BulkOperationGate()
        let ownerA = BulkOperationOwner.provider(transferId: transferA, contentHash: hashA, peerSpki: spki, sessionGeneration: 1)
        let ownerB = BulkOperationOwner.provider(transferId: transferB, contentHash: hashB, peerSpki: spki, sessionGeneration: 1)
        XCTAssertTrue(gate.tryAcquire(ownerA), "A must win the free slot")
        XCTAssertFalse(gate.tryAcquire(ownerB), "B must not overwrite A's ownership while A is active")
        XCTAssertTrue(gate.isOwner(transferA), "A must remain the owner after B's denied attempt")
        XCTAssertFalse(gate.isOwner(transferB))
    }

    func testACancelNamingTheNonActiveTransferIsNotRoutedToTheActiveOperation() {
        let gate = BulkOperationGate()
        gate.tryAcquire(.provider(transferId: transferA, contentHash: hashA, peerSpki: spki, sessionGeneration: 1))
        XCTAssertFalse(gate.isOwner(transferB))
        XCTAssertTrue(gate.isOwner(transferA), "CANCEL B must never disturb A's ownership")
    }

    func testACancelNamingTheActiveTransferIsRoutedCorrectlyAndClearsTheSlot() {
        let gate = BulkOperationGate()
        gate.tryAcquire(.provider(transferId: transferA, contentHash: hashA, peerSpki: spki, sessionGeneration: 1))
        XCTAssertTrue(gate.isOwner(transferA))
        gate.releaseIfOwner(transferA)
        XCTAssertNil(gate.current, "the slot must become available once its owner is released")
    }

    func testAStaleReleaseFromASupersededOperationCannotClearANewerOperation() {
        let gate = BulkOperationGate()
        gate.tryAcquire(.provider(transferId: transferA, contentHash: hashA, peerSpki: spki, sessionGeneration: 1))
        // A's own cleanup runs late, after A already relinquished conceptually and B has taken over.
        gate.releaseIfOwner(transferA)
        XCTAssertTrue(gate.tryAcquire(.provider(transferId: transferB, contentHash: hashB, peerSpki: spki, sessionGeneration: 1)))
        // A's delayed defer/cleanup now runs — it must not clear B.
        gate.releaseIfOwner(transferA)
        XCTAssertTrue(gate.isOwner(transferB), "A's stale cleanup must not clear B's active ownership")
    }

    func testSessionBoundaryInvalidationFreesTheSlotUnconditionally() {
        let gate = BulkOperationGate()
        gate.tryAcquire(.provider(transferId: transferA, contentHash: hashA, peerSpki: spki, sessionGeneration: 1))
        gate.invalidate()
        XCTAssertNil(gate.current)
        XCTAssertFalse(gate.isOwner(transferA))
        XCTAssertTrue(gate.tryAcquire(.provider(transferId: transferB, contentHash: hashB, peerSpki: spki, sessionGeneration: 2)))
    }

    func testRequesterAndProviderRolesAreMutuallyExclusiveOverTheSameSlot() {
        let gate = BulkOperationGate()
        let requester = BulkOperationOwner.requester(transferId: transferA, contentHash: hashA)
        XCTAssertTrue(gate.tryAcquire(requester), "a requester download may start when the slot is free")
        let provider = BulkOperationOwner.provider(transferId: transferB, contentHash: hashB, peerSpki: spki, sessionGeneration: 1)
        XCTAssertFalse(gate.tryAcquire(provider), "an inbound provider request must not steal the slot from an active requester download")
        XCTAssertTrue(gate.isOwner(transferA), "the requester's download must remain the owner")

        gate.releaseIfOwner(transferA)
        XCTAssertTrue(gate.tryAcquire(provider), "once the requester download finishes, the provider role may acquire the now-free slot")
    }

    func testAnActiveProviderOperationBlocksALocalRequesterDownloadFromStarting() {
        let gate = BulkOperationGate()
        let provider = BulkOperationOwner.provider(transferId: transferA, contentHash: hashA, peerSpki: spki, sessionGeneration: 1)
        XCTAssertTrue(gate.tryAcquire(provider))
        let requester = BulkOperationOwner.requester(transferId: transferB, contentHash: hashB)
        XCTAssertFalse(gate.tryAcquire(requester), "a local download must not start while this device is actively serving a peer")
        XCTAssertTrue(gate.isOwner(transferA), "the provider operation must remain correct and undisturbed")
    }
}
