import XCTest

@testable import RideLinkCore

/// Runs `protocol/vectors/manifest-paging/manifest_paging_vectors.json` — see ADR-013 / PROTOCOL
/// §8.1.
///
/// The mirror is `com.ridelink.core.manifest.ManifestPagingVectorTest`, running the **same file**.
final class ManifestPagingVectorTests: XCTestCase {
    private let expectedRowCount = 13

    private func document() throws -> [String: Any] {
        // swiftlint:disable:next force_cast
        try Vectors.loadJSON("manifest-paging/manifest_paging_vectors.json") as! [String: Any]
    }

    func testEveryRowOfTheSharedFileHolds() throws {
        let doc = try document()
        var checked = 0
        for element in doc.array("rows") {
            guard let row = element as? [String: Any] else { return XCTFail("row is not an object") }
            let name = row.str("name")
            let expect = row.dict("expect")
            if expect.hasKey("page_count") {
                checkPaging(name, row, expect)
            } else if expect.hasKey("clamped_title_scalar_count") {
                checkClamp(name, row, expect)
            } else if expect.hasKey("content_hash_unchanged") {
                checkIdentityUnchanged(name, row)
            } else if expect.hasKey("digest") {
                checkDigestOnly(name, row, expect)
            } else {
                XCTFail("vector \(name): unrecognised expect shape")
            }
            checked += 1
        }
        XCTAssertEqual(expectedRowCount, checked, "expected \(expectedRowCount) rows")
    }

    /// `manifest_page_soft_limit_bytes`/`max_entries_per_page`/`display_clamp_scalars` are
    /// transcribed independently in the generator and in `ManifestPaging`.
    func testTheVectorFilesConstantsMatchThisPlatforms() throws {
        let constants = try document().dict("constants")
        XCTAssertEqual(constants.int("manifest_page_soft_limit_bytes"), ManifestPaging.manifestPageSoftLimitBytes)
        XCTAssertEqual(constants.int("max_entries_per_page"), ManifestPaging.maxEntriesPerPage)
        XCTAssertEqual(constants.int("display_clamp_scalars"), ManifestPaging.displayClampScalars)
    }

    // MARK: - checks

    private func checkPaging(_ name: String, _ row: [String: Any], _ expect: [String: Any]) {
        let entries = rawEntries(row)
        let budget = row.int("budget_bytes")
        let pages = ManifestPaging.paginate(entries, budgetBytes: budget)
        XCTAssertEqual(expect.int("page_count"), pages.count, "vector \(name): page_count")
        let expectedCounts = expect.array("entries_per_page").map { ($0 as! NSNumber).intValue } // swiftlint:disable:this force_cast
        XCTAssertEqual(expectedCounts, pages.map(\.count), "vector \(name): entries_per_page")
        let expectedPages = expect.array("pages").map { pageArray -> [ManifestEntry] in
            (pageArray as! [Any]).map { entryFrom($0 as! [String: Any]) } // swiftlint:disable:this force_cast
        }
        XCTAssertEqual(expectedPages, pages, "vector \(name): page contents")
        let allClamped = entries.map(ManifestPaging.clampEntry)
        let removed = rawRemoved(row)
        XCTAssertEqual(expect.str("digest"), ManifestPaging.digest(entries: allClamped, removed: removed), "vector \(name): digest")
    }

    private func checkClamp(_ name: String, _ row: [String: Any], _ expect: [String: Any]) {
        let entry = rawEntries(row)[0]
        let clamped = ManifestPaging.clampScalars(entry.title)
        XCTAssertEqual(expect.str("clamped_title"), clamped, "vector \(name): clamped title")
        XCTAssertEqual(expect.int("clamped_title_scalar_count"), clamped.unicodeScalars.count, "vector \(name): scalar count")
    }

    private func checkIdentityUnchanged(_ name: String, _ row: [String: Any]) {
        let entry = rawEntries(row)[0]
        let clamped = ManifestPaging.clampEntry(entry)
        XCTAssertEqual(entry.contentHash, clamped.contentHash, "vector \(name): content_hash")
        XCTAssertEqual(entry.quickId, clamped.quickId, "vector \(name): quick_id")
        XCTAssertEqual(entry.sizeBytes, clamped.sizeBytes, "vector \(name): size_bytes")
        XCTAssertEqual(entry.durationMs, clamped.durationMs, "vector \(name): duration_ms")
    }

    private func checkDigestOnly(_ name: String, _ row: [String: Any], _ expect: [String: Any]) {
        let entries = rawEntries(row).map(ManifestPaging.clampEntry)
        let removed = rawRemoved(row)
        XCTAssertEqual(expect.str("digest"), ManifestPaging.digest(entries: entries, removed: removed), "vector \(name): digest")
    }

    // MARK: - vector decoding

    private func rawEntries(_ row: [String: Any]) -> [ManifestEntry] {
        row.array("entries").map { entryFrom($0 as! [String: Any]) } // swiftlint:disable:this force_cast
    }

    private func rawRemoved(_ row: [String: Any]) -> [ContentHash] {
        (row["removed"] as? [Any])?.map { ContentHash($0 as! String) } ?? [] // swiftlint:disable:this force_cast
    }

    private func entryFrom(_ o: [String: Any]) -> ManifestEntry {
        let contentHash = o.strOpt("content_hash").map(ContentHash.init)
        return ManifestEntry(
            contentHash: contentHash,
            quickId: QuickId(o.str("quick_id")),
            workKey: o.str("work_key"),
            title: o.str("title"),
            artist: o.str("artist"),
            album: o.str("album"),
            durationMs: o.int64("duration_ms"),
            codec: o.str("codec"),
            bitrateKbps: o.int("bitrate_kbps"),
            sizeBytes: o.int64("size_bytes"),
            filename: o.str("filename"),
            hasArtwork: o.boolVal("has_artwork")
        )
    }
}
