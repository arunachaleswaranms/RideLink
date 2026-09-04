import Foundation
import XCTest
@testable import RideLinkCore

private let validHash = "sha256:1f3ac9d9e5cba5cf5f4c5da8a2b1e6c9d0f3a7b2c4e6f8a0b2c4e6f8a0b2c4e6"

final class ContentHashAndQuickIdTests: XCTestCase {
    func testAcceptsAWellFormedSha256Value() {
        XCTAssertEqual(validHash, ContentHash(validHash).value)
        XCTAssertEqual(validHash, QuickId(validHash).value)
    }

    func testHexStripsThePrefix() {
        XCTAssertEqual(String(validHash.dropFirst("sha256:".count)), ContentHash(validHash).hex)
    }

    func testParseReturnsNilInsteadOfTrappingOnMalformedInput() {
        XCTAssertNil(ContentHash.parse("not-a-hash"))
        XCTAssertNil(QuickId.parse("not-a-hash"))
        XCTAssertEqual(validHash, ContentHash.parse(validHash)?.value)
        XCTAssertEqual(validHash, QuickId.parse(validHash)?.value)
    }

    func testParseRejectsUppercaseHex() {
        XCTAssertNil(ContentHash.parse(validHash.uppercased()))
    }

    func testParseRejectsTheWrongLength() {
        XCTAssertNil(ContentHash.parse("sha256:1234"))
        XCTAssertNil(QuickId.parse("sha256:1234"))
    }

    func testParseRejectsAMissingPrefix() {
        XCTAssertNil(ContentHash.parse(String(validHash.dropFirst("sha256:".count))))
    }
}
