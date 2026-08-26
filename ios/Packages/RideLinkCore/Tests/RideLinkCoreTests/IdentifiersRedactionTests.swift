import Foundation
import XCTest
@testable import RideLinkCore

/// Defense in depth: even code that bypasses `RideLinkCore.Redactor` entirely and does
/// `"\(peerId)"` string interpolation directly must still not leak the full value, because these
/// types redact their own `description`.
final class IdentifiersRedactionTests: XCTestCase {
    func testPeerIdDescriptionNeverContainsTheFullValue() {
        let id = PeerId("b7c1e0d9a4f28356")
        XCTAssertFalse(id.description.contains(id.value))
        XCTAssertTrue(id.description.hasPrefix("peer:b7c1e0"))
    }

    func testSpkiHashDescriptionNeverContainsTheFullHash() {
        let hash = SpkiHash("sha256:2488a4e8a6347f0ca5e9befd679f5fe0d293de2f2cc28caf98392dfdc98aea1a")
        XCTAssertFalse(hash.description.contains(hash.hex))
        XCTAssertTrue(hash.description.hasPrefix("spki:2488a4"))
    }

    func testConnTiebreakDescriptionNeverContainsTheFullValue() {
        let tiebreak = ConnTiebreak("5e2a9c40b7f13d86e0a4c95b28f7d613")
        XCTAssertFalse(tiebreak.description.contains(tiebreak.value))
        XCTAssertTrue(tiebreak.description.hasPrefix("tiebreak:5e2a9c"))
    }
}
