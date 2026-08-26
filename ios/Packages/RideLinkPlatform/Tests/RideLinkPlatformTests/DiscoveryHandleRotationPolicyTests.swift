import XCTest
@testable import RideLinkPlatform

/// ARCHITECTURE §4.1 / this session's brief §4F. Deterministic — an injected clock, never a real
/// 15-minute wait. Mirrors Android's `DiscoveryHandleRotationPolicyTest`.
final class DiscoveryHandleRotationPolicyTests: XCTestCase {
    func testNotDueBeforeTheIntervalElapses() {
        XCTAssertFalse(DiscoveryHandleRotationPolicy.isRotationDue(nowMonoNs: 1_000, lastRotatedAtMonoNs: 0, intervalNs: 900_000))
        XCTAssertFalse(DiscoveryHandleRotationPolicy.isRotationDue(nowMonoNs: 899_999, lastRotatedAtMonoNs: 0, intervalNs: 900_000))
    }

    func testDueAtExactlyTheInterval() {
        XCTAssertTrue(DiscoveryHandleRotationPolicy.isRotationDue(nowMonoNs: 900_000, lastRotatedAtMonoNs: 0, intervalNs: 900_000))
    }

    func testDueWellPastTheInterval() {
        XCTAssertTrue(DiscoveryHandleRotationPolicy.isRotationDue(nowMonoNs: 5_000_000, lastRotatedAtMonoNs: 0, intervalNs: 900_000))
    }

    func testDefaultIntervalIsExactly15Minutes() {
        XCTAssertEqual(DiscoveryHandleRotationPolicy.rotationIntervalNs, 15 * 60 * 1_000_000_000)
    }
}
