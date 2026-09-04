import Foundation
import XCTest
@testable import RideLinkCore

final class MetadataNormalizerTests: XCTestCase {
    func testMissingTitleFallsBackToTheFilenameWithoutItsExtension() {
        XCTAssertEqual("song", MetadataNormalizer.title(nil, filename: "song.mp3"))
        XCTAssertEqual("song", MetadataNormalizer.title("", filename: "song.mp3"))
        XCTAssertEqual("song", MetadataNormalizer.title("   ", filename: "song.mp3"))
    }

    func testAFilenameWithNoExtensionIsUsedAsIs() {
        XCTAssertEqual("song", MetadataNormalizer.title(nil, filename: "song"))
    }

    func testADotfileWithNoRealExtensionKeepsItsLeadingDot() {
        XCTAssertEqual(".hidden", MetadataNormalizer.title(nil, filename: ".hidden"))
    }

    func testAPresentTitleIsUsedUntouched() {
        XCTAssertEqual("Real Title", MetadataNormalizer.title("Real Title", filename: "song.mp3"))
    }

    func testMissingArtistAndAlbumUseFixedDeterministicLiterals() {
        XCTAssertEqual(MetadataNormalizer.unknownArtist, MetadataNormalizer.artist(nil))
        XCTAssertEqual(MetadataNormalizer.unknownArtist, MetadataNormalizer.artist("  "))
        XCTAssertEqual(MetadataNormalizer.unknownAlbum, MetadataNormalizer.album(nil))
    }

    func testUnicodePassesThroughUntouchedApartFromNFCComposition() {
        // 'e' + U+0301 COMBINING ACUTE ACCENT (NFD, 5 scalars) must normalize to the same string as
        // the precomposed U+00E9 (NFC, 4 scalars). Note: Swift's `String == ` already compares by
        // Unicode canonical equivalence, so `nfd == nfc` is true in Swift *before* this type ever
        // runs (unlike Kotlin's raw UTF-16 comparison, which is why `core.library.MetadataNormalizer`
        // needs the same explicit NFC step to agree with this platform). The scalar *count* is what
        // proves the two inputs are genuinely distinct underlying sequences, and what proves
        // `MetadataNormalizer.title` actually composed the NFD input down rather than passing it
        // through as 5 scalars.
        let nfd = "caf" + String(Character(Unicode.Scalar(0x65)!)) + String(Unicode.Scalar(0x0301)!)
        let nfc = "caf" + String(Unicode.Scalar(0x00E9)!)
        XCTAssertEqual(5, nfd.unicodeScalars.count, "test setup bug: nfd must be the 5-scalar decomposed form")
        XCTAssertEqual(4, nfc.unicodeScalars.count, "test setup bug: nfc must be the 4-scalar precomposed form")
        XCTAssertEqual(nfc, MetadataNormalizer.title(nfd, filename: "f"))
        XCTAssertEqual(4, MetadataNormalizer.title(nfd, filename: "f").unicodeScalars.count)
        XCTAssertEqual(MetadataNormalizer.title(nfc, filename: "f"), MetadataNormalizer.title(nfd, filename: "f"))
    }

    func testVeryLongMetadataIsClampedToTheSharedScalarBound() {
        let long = String(repeating: "a", count: MetadataNormalizer.maxFieldLength + 100)
        let result = MetadataNormalizer.artist(long)
        XCTAssertEqual(MetadataNormalizer.maxFieldLength, result.unicodeScalars.count)
    }

    func testClampingCountsUnicodeScalarValuesNotGraphemeClusters() {
        // U+1F3B5 MUSICAL NOTE is one scalar value. Clamping by String.count (grapheme clusters)
        // happens to agree here, but clamping must be on unicodeScalars.count specifically to match
        // PROTOCOL §8.1 and Android's codePointCount exactly.
        let emoji = String(Unicode.Scalar(0x1F3B5)!)
        let long = String(repeating: emoji, count: MetadataNormalizer.maxFieldLength + 10)
        let result = MetadataNormalizer.artist(long)
        XCTAssertEqual(MetadataNormalizer.maxFieldLength, result.unicodeScalars.count)
    }

    func testDuplicateFilenamesWithDistinctTitlesAreNotConflated() {
        XCTAssertEqual("First", MetadataNormalizer.title("First", filename: "same.mp3"))
        XCTAssertEqual("Second", MetadataNormalizer.title("Second", filename: "same.mp3"))
    }
}
