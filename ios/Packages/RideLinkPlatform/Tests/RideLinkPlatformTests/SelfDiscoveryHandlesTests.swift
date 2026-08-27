import XCTest
@testable import RideLinkPlatform

/// This session's brief §8: during a `dh` rotation transition, the old local dh must still be
/// treated as self until its unregistration/timeout confirms, the new local dh must be treated
/// as self immediately, and a remote peer's dh must never be treated as self. Mirrors Android's
/// `SelfDiscoveryHandlesTest`.
final class SelfDiscoveryHandlesTests: XCTestCase {
    func testBeforeAnyRotationNothingIsSelf() {
        let handles = SelfDiscoveryHandles()
        XCTAssertFalse(handles.isSelf("anything"))
        XCTAssertNil(handles.currentHandle)
    }

    func testAfterTheFirstRotateTheNewHandleIsSelf() {
        let handles = SelfDiscoveryHandles()
        handles.rotate("aaaa")
        XCTAssertTrue(handles.isSelf("aaaa"))
        XCTAssertEqual(handles.currentHandle, "aaaa")
    }

    func testDuringTheTransitionBothTheNewAndTheJustSupersededOldHandleAreSelf() {
        let handles = SelfDiscoveryHandles()
        handles.rotate("aaaa")
        handles.rotate("bbbb")

        XCTAssertTrue(handles.isSelf("bbbb"), "new local dh must be treated as self")
        XCTAssertTrue(handles.isSelf("aaaa"), "old local dh must still be treated as self until the transition closes")
        XCTAssertEqual(handles.currentHandle, "bbbb")
    }

    func testARemotePeersDhIsNeverTreatedAsSelfEvenMidTransition() {
        let handles = SelfDiscoveryHandles()
        handles.rotate("aaaa")
        handles.rotate("bbbb")

        XCTAssertFalse(handles.isSelf("cccccccc-remote-peer"), "remote dh must not be treated as self")
    }

    func testClearPreviousEndsTheTransitionOnlyTheCurrentHandleRemainsSelf() {
        let handles = SelfDiscoveryHandles()
        handles.rotate("aaaa")
        handles.rotate("bbbb")

        handles.clearPrevious()

        XCTAssertTrue(handles.isSelf("bbbb"))
        XCTAssertFalse(handles.isSelf("aaaa"), "once the transition is confirmed closed, the old dh is no longer self")
    }

    func testAThirdRotationWithoutAnInterveningClearPreviousDropsTheOldestHandle() {
        let handles = SelfDiscoveryHandles()
        handles.rotate("aaaa")
        handles.rotate("bbbb")
        handles.rotate("cccc")

        XCTAssertTrue(handles.isSelf("cccc"))
        XCTAssertTrue(handles.isSelf("bbbb"), "the immediately-previous handle is still self")
        XCTAssertFalse(handles.isSelf("aaaa"), "only one generation back is tracked, matching the real single in-flight transition")
    }

    func testResetClearsBothHandlesNothingIsSelfOnceAdvertisingStops() {
        let handles = SelfDiscoveryHandles()
        handles.rotate("aaaa")
        handles.rotate("bbbb")

        handles.reset()

        XCTAssertFalse(handles.isSelf("aaaa"))
        XCTAssertFalse(handles.isSelf("bbbb"))
        XCTAssertNil(handles.currentHandle)
    }
}
