import XCTest

@testable import RideLinkCore

/// Closure-audit findings C/D/N/P/S: a superseded operation's own late completion must never
/// mutate state again. These are the pure-logic proofs behind that guarantee — the coordinator
/// integration itself is exercised by `SharedLibraryCoordinator`'s own usage, but the fencing rule
/// itself is proven here, once, independent of any Task/socket. Mirrors Android's
/// `OperationFenceTest`, running the same semantics (not the same file — there is no wire shape
/// here to pin with a shared vector).
final class OperationFenceTests: XCTestCase {
    func testAFreshlyBegunTokenIsCurrent() {
        let fence = OperationFence()
        let token = fence.begin()
        XCTAssertTrue(fence.isCurrent(token))
    }

    func testBeginningASecondOperationInvalidatesTheFirstToken() {
        let fence = OperationFence()
        let first = fence.begin()
        let second = fence.begin()
        XCTAssertNotEqual(first, second)
        XCTAssertFalse(fence.isCurrent(first), "a superseded operation's token must stop being current")
        XCTAssertTrue(fence.isCurrent(second))
    }

    func testSupersedeInvalidatesTheActiveTokenWithoutStartingAReplacement() {
        let fence = OperationFence()
        let token = fence.begin()
        fence.supersede()
        XCTAssertFalse(fence.isCurrent(token), "cancellation/session-loss must invalidate the in-flight operation")
        XCTAssertFalse(fence.isCurrent(0))
    }

    func testATokenFromBeforeAnyBeginIsNeverCurrent() {
        let fence = OperationFence()
        XCTAssertFalse(fence.isCurrent(0))
        XCTAssertFalse(fence.isCurrent(1))
    }

    func testTerminalStateDisciplineACancelledOperationsLateCompletionCannotResurrectIt() {
        // Models brief §17 directly: CANCELLED must stay CANCELLED even if the original operation's
        // Task keeps running past the point of cancellation and eventually tries to report
        // COMPLETE/FAILED.
        let fence = OperationFence()
        let token = fence.begin() // operation N starts
        fence.supersede() // user cancels operation N
        XCTAssertFalse(fence.isCurrent(token), "a cancelled operation's own late write must be dropped, not applied")
    }

    func testManySequentialOperationsOnlyEverValidateTheirOwnToken() {
        let fence = OperationFence()
        let tokens = (1...50).map { _ in fence.begin() }
        for token in tokens.dropLast() {
            XCTAssertFalse(fence.isCurrent(token))
        }
        XCTAssertTrue(fence.isCurrent(tokens.last!))
    }
}
