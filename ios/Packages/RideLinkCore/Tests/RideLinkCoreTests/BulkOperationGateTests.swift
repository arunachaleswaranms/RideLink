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

    // MARK: - ADR-023 Amendment A5: gate ownership is only half of "still authorised"

    private func heldGate() -> BulkOperationGate {
        let gate = BulkOperationGate()
        gate.tryAcquire(.provider(transferId: transferA, contentHash: hashA, peerSpki: spki, sessionGeneration: 7))
        return gate
    }

    private var authorisationA: ProviderSessionContext {
        ProviderSessionContext(authorisingGeneration: 7, authorisedPeerSpki: spki)
    }

    func testStillAuthorisedWhileTheSlotIsHeldAndTheAuthorisingSessionIsStillLive() {
        XCTAssertTrue(heldGate().stillAuthorises(
            transferId: transferA, authorisation: authorisationA, liveGeneration: 7, livePeerSpki: spki))
    }

    /// **The A5 Finding C property.** This is the exact state `SharedLibraryCoordinator`'s
    /// `onSessionBoundary()` is in while it `await`s `TransferManager.close()`: `sessionEpoch` has
    /// already been bumped, and `bulkGate.invalidate()` has not run yet — so gate ownership alone
    /// still says "yes". It must not, because the operation is authorised by a session that no
    /// longer exists.
    func testGateOwnershipAloneIsNotAuthorisationOnceTheLiveGenerationHasMovedOn() {
        let gate = heldGate()
        XCTAssertTrue(gate.isOwner(transferA), "the precondition: the gate has NOT been invalidated yet")
        XCTAssertFalse(gate.stillAuthorises(
            transferId: transferA, authorisation: authorisationA, liveGeneration: 8, livePeerSpki: spki))
    }

    /// The same, for the peer half of the context — defence in depth per `ProviderSessionContext`.
    func testGateOwnershipAloneIsNotAuthorisationOnceTheLivePeerHasChanged() {
        let gate = heldGate()
        let otherPeer = SpkiHash("sha256:" + String(repeating: "ef", count: 32))
        XCTAssertTrue(gate.isOwner(transferA))
        XCTAssertFalse(gate.stillAuthorises(
            transferId: transferA, authorisation: authorisationA, liveGeneration: 7, livePeerSpki: otherPeer))
    }

    /// A disconnected session has no live peer at all; that is not "still current" either.
    func testANilLivePeerIsNeverStillCurrent() {
        XCTAssertFalse(heldGate().stillAuthorises(
            transferId: transferA, authorisation: authorisationA, liveGeneration: 7, livePeerSpki: nil))
    }

    /// The gate half is not dropped: a live session does not authorise an operation that has since
    /// lost the slot to a fresher one, which is precisely what A3's check was protecting.
    func testALiveSessionDoesNotAuthoriseAnOperationThatNoLongerOwnsTheSlot() {
        let gate = heldGate()
        gate.invalidate()
        XCTAssertFalse(gate.stillAuthorises(
            transferId: transferA, authorisation: authorisationA, liveGeneration: 7, livePeerSpki: spki))
        gate.tryAcquire(.requester(transferId: transferB, contentHash: hashB))
        XCTAssertFalse(gate.stillAuthorises(
            transferId: transferA, authorisation: authorisationA, liveGeneration: 7, livePeerSpki: spki))
    }
}
