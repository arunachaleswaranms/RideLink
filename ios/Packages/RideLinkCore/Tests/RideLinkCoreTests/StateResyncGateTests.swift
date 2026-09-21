import XCTest

@testable import RideLinkCore

/// Unit tests for the one new pure decision Phase 7's `STATE_REQUEST` needs (PROTOCOL §10,
/// ADR-028). Mirrors Android's `StateResyncGateTest`.
final class StateResyncGateTests: XCTestCase {
    func testNoPendingRequestSendsOne() {
        XCTAssertEqual(.sendRequest, StateResyncGate.onTrigger(pendingGeneration: nil, liveGeneration: 5))
    }

    func testARequestAlreadyPendingForTheLiveGenerationIsNotResent() {
        XCTAssertEqual(.alreadyPending, StateResyncGate.onTrigger(pendingGeneration: 5, liveGeneration: 5))
    }

    /// A stale pending generation (from a retired session) can never collide with a live one,
    /// because generations strictly increase — this is the reconnect-reset, expressed as ordinary
    /// inequality rather than a separate boundary event.
    func testAPendingRequestForARetiredGenerationDoesNotBlockAFreshOne() {
        XCTAssertEqual(.sendRequest, StateResyncGate.onTrigger(pendingGeneration: 3, liveGeneration: 5))
    }

    func testASnapshotMatchingThePendingGenerationClearsIt() {
        XCTAssertNil(StateResyncGate.onSnapshotObserved(pendingGeneration: 5, snapshotGeneration: 5))
    }

    func testASnapshotFromAForeignGenerationDoesNotClearAPendingRequest() {
        XCTAssertEqual(5, StateResyncGate.onSnapshotObserved(pendingGeneration: 5, snapshotGeneration: 3))
    }

    func testASnapshotWithNoRequestOutstandingStaysNil() {
        XCTAssertNil(StateResyncGate.onSnapshotObserved(pendingGeneration: nil, snapshotGeneration: 5))
    }
}
