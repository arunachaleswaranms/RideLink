import XCTest
@testable import RideLinkPlatform

/// Pure logic only — the live NWListener/NWBrowser wiring in BonjourDiscovery cannot be
/// meaningfully unit tested without a real peer on the network; it is verified on-device
/// (docs/STATUS.md problem 11's Android equivalent, and its iOS counterpart once run).
final class DiscoveryHandleTests: XCTestCase {
    func testGeneratesThirtyTwoLowercaseHexCharacters() {
        let handle = DiscoveryHandle.generate()
        XCTAssertEqual(handle.count, 32)
        XCTAssertTrue(handle.allSatisfy { $0.isHexDigit && !$0.isUppercase })
    }

    func testTwoSuccessiveHandlesDiffer() {
        let a = DiscoveryHandle.generate()
        let b = DiscoveryHandle.generate()
        XCTAssertNotEqual(a, b, "handles must be freshly random, never a fixed/derived value")
    }
}
