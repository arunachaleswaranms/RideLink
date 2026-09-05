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

private let validLocalEntryId = "3fa85f64-5717-4562-b3fc-2c963f66afa6"

/// `LocalEntryId` (ADR-005 Amendment A1) is not a `sha256:`-shaped hash — a lowercase UUID instead —
/// so it gets its own assertions here, alongside its sibling identity types.
final class LocalEntryIdTests: XCTestCase {
    func testAcceptsAWellFormedLowercaseUuid() {
        XCTAssertEqual(validLocalEntryId, LocalEntryId(validLocalEntryId).value)
        XCTAssertEqual(validLocalEntryId, LocalEntryId.parse(validLocalEntryId)?.value)
    }

    func testParseReturnsNilInsteadOfTrappingOnMalformedInput() {
        XCTAssertNil(LocalEntryId.parse("not-a-uuid"))
        XCTAssertNil(LocalEntryId.parse(""))
    }

    func testParseRejectsUppercaseHex() {
        XCTAssertNil(LocalEntryId.parse(validLocalEntryId.uppercased()))
    }

    func testParseRejectsTheWrongGroupLengths() {
        XCTAssertNil(LocalEntryId.parse("3fa85f6-5717-4562-b3fc-2c963f66afa6"))
        XCTAssertNil(LocalEntryId.parse("3fa85f64-571-4562-b3fc-2c963f66afa6"))
        XCTAssertNil(LocalEntryId.parse("3fa85f64-5717-4562-b3fc-2c963f66afa"))
    }

    func testParseRejectsASha256ShapedValue() {
        // A LocalEntryId must never be mistaken for a QuickId/ContentHash — the two identity systems
        // (content-sampled vs. random-per-row) must never validate each other's format.
        XCTAssertNil(LocalEntryId.parse(validHash))
    }
}
