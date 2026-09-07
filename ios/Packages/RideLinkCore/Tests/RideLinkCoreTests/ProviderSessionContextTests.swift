import XCTest

@testable import RideLinkCore

/// ADR-023 Amendment A3 — pure proofs for the re-authorisation check every suspension point in
/// `SharedLibraryCoordinator.serveTransferRequest` must pass before touching `BulkOperationGate`,
/// minting a bulk token, or sending a `TRANSFER_OFFER`. Mirrors Android's `ProviderSessionContextTest`.
final class ProviderSessionContextTests: XCTestCase {
    private let peerA = SpkiHash("sha256:" + String(repeating: "aa", count: 32))
    private let peerB = SpkiHash("sha256:" + String(repeating: "bb", count: 32))

    func testStillCurrentWhenLiveGenerationAndPeerBothMatchWhatAuthorisedTheOperation() {
        let context = ProviderSessionContext(authorisingGeneration: 10, authorisedPeerSpki: peerA)
        XCTAssertTrue(context.isStillCurrent(liveGeneration: 10, livePeerSpki: peerA))
    }

    func testNotCurrentOnceAReconnectBumpsTheLiveGenerationEvenToTheSamePeer() {
        let context = ProviderSessionContext(authorisingGeneration: 10, authorisedPeerSpki: peerA)
        XCTAssertFalse(context.isStillCurrent(liveGeneration: 11, livePeerSpki: peerA))
    }

    func testNotCurrentOnceTheLivePeerDiffersEvenIfTheGenerationNumberWereSomehowUnchanged() {
        let context = ProviderSessionContext(authorisingGeneration: 10, authorisedPeerSpki: peerA)
        XCTAssertFalse(context.isStillCurrent(liveGeneration: 10, livePeerSpki: peerB))
    }

    func testNotCurrentWhenTheLivePeerIsNilMeaningNoSessionIsAuthenticatedRightNow() {
        let context = ProviderSessionContext(authorisingGeneration: 10, authorisedPeerSpki: peerA)
        XCTAssertFalse(context.isStillCurrent(liveGeneration: 10, livePeerSpki: nil))
    }

    func testNotCurrentWhenBothGenerationAndPeerHaveMovedOnToANewSession() {
        let context = ProviderSessionContext(authorisingGeneration: 10, authorisedPeerSpki: peerA)
        XCTAssertFalse(context.isStillCurrent(liveGeneration: 11, livePeerSpki: peerB))
    }
}
