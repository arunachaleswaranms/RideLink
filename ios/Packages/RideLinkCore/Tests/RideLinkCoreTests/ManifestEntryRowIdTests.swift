import XCTest

@testable import RideLinkCore

/// Closure-audit Finding F: `SharedLibraryView`'s `ForEach` used to key rows on `\.quickId.value`
/// alone — `QuickId` is only a 128 KiB sample (ADR-005 Amendment A1) and is not guaranteed unique
/// across files, so two genuinely different manifest entries could collide onto one SwiftUI row,
/// which this codebase's own `LibraryView`/`MusicCoordinator` comments already document as
/// undefined behaviour. `ManifestEntry.rowId` is the fix; this proves it directly, without needing
/// to exercise SwiftUI itself (brief §29's "extract a small pure UI-row identity property").
final class ManifestEntryRowIdTests: XCTestCase {
    private func entry(contentHash: String?, quickId: String, title: String = "t") -> ManifestEntry {
        ManifestEntry(
            contentHash: contentHash.map(ContentHash.init),
            quickId: QuickId(quickId),
            workKey: "k",
            title: title,
            artist: "a",
            album: "al",
            durationMs: 1000,
            codec: "mp3",
            bitrateKbps: 128,
            sizeBytes: 1000,
            filename: "f.mp3",
            hasArtwork: false
        )
    }

    func testSameQuickIdDifferentContentHashProducesDifferentRowIds() {
        let sameQuickId = "sha256:" + String(repeating: "aa", count: 32)
        let a = entry(contentHash: "sha256:" + String(repeating: "11", count: 32), quickId: sameQuickId)
        let b = entry(contentHash: "sha256:" + String(repeating: "22", count: 32), quickId: sameQuickId)

        XCTAssertEqual(a.quickId, b.quickId, "test setup: both entries must share one QuickId")
        XCTAssertNotEqual(a.rowId, b.rowId, "two entries with different ContentHash must never share a row identity")
    }

    func testSameEntryProducesTheSameRowIdTwice() {
        let e = entry(contentHash: "sha256:" + String(repeating: "33", count: 32), quickId: "sha256:" + String(repeating: "44", count: 32))
        XCTAssertEqual(e.rowId, e.rowId)
    }

    func testANilContentHashEntryStillProducesARowId() {
        let e = entry(contentHash: nil, quickId: "sha256:" + String(repeating: "55", count: 32))
        XCTAssertFalse(e.rowId.isEmpty)
    }

    func testTwoDifferentQuickIdsWithNilContentHashAlsoDiffer() {
        let a = entry(contentHash: nil, quickId: "sha256:" + String(repeating: "66", count: 32))
        let b = entry(contentHash: nil, quickId: "sha256:" + String(repeating: "77", count: 32))
        XCTAssertNotEqual(a.rowId, b.rowId)
    }
}
