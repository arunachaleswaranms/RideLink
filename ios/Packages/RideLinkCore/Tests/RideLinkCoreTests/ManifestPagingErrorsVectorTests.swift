import XCTest

@testable import RideLinkCore

/// Runs `protocol/vectors/manifest-paging-errors/manifest_paging_errors_vectors.json` — PROTOCOL
/// §8.1 / ADR-013.
///
/// The mirror is `com.ridelink.core.manifest.ManifestPagingErrorsVectorTest`, running the **same
/// file**.
final class ManifestPagingErrorsVectorTests: XCTestCase {
    private let expectedRowCount = 21

    private func document() throws -> [String: Any] {
        // swiftlint:disable:next force_cast
        try Vectors.loadJSON("manifest-paging-errors/manifest_paging_errors_vectors.json") as! [String: Any]
    }

    func testEveryRowOfTheSharedFileHolds() throws {
        let doc = try document()
        var checked = 0
        for element in doc.array("rows") {
            guard let row = element as? [String: Any] else { return XCTFail("row is not an object") }
            let name = row.str("name")
            let previousRevision = row.int64("previous_revision")
            let events = row.array("events").map { eventFrom($0 as! [String: Any]) } // swiftlint:disable:this force_cast
            let expect = row.dict("expect")

            var state = ManifestSyncState(liveRevision: previousRevision)
            var lastError: ManifestSyncError?
            var committed = false
            for event in events {
                lastError = nil
                let (nextState, result) = ManifestSync.apply(event, to: state)
                state = nextState
                switch result {
                case .aborted(let reason): lastError = reason
                case .committed: committed = true
                case .continue: break
                }
            }

            XCTAssertEqual(expect.strOpt("error"), wireErrorName(lastError), "vector \(name): error")
            XCTAssertEqual(expect.boolVal("committed"), committed, "vector \(name): committed")
            XCTAssertEqual(expect.int64("final_revision"), state.liveRevision, "vector \(name): final_revision")
            checked += 1
        }
        XCTAssertEqual(expectedRowCount, checked, "expected \(expectedRowCount) rows")
    }

    // MARK: - vector decoding

    /// These vectors' entries carry only `content_hash`/`quick_id` (the two fields the digest
    /// actually reads) — everything else is filler so `ManifestEntry` can be constructed at all.
    /// Reading the *real* content_hash/quick_id off the JSON, rather than fabricating a
    /// placeholder, is what makes the reconstructed digest match `DEFAULT_ENTRY_DIGEST` in
    /// `tools/generate_manifest_paging_errors_vectors.py`.
    private func entryFromPartial(_ o: [String: Any]) -> ManifestEntry {
        let contentHash = o.strOpt("content_hash").map(ContentHash.init)
        return ManifestEntry(
            contentHash: contentHash,
            quickId: QuickId(o.str("quick_id")),
            workKey: "k",
            title: "t",
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

    private func eventFrom(_ o: [String: Any]) -> ManifestSyncEvent {
        switch o.str("kind") {
        case "Begin":
            return .begin(
                manifestId: ManifestId(o.str("manifest_id")),
                kind: ManifestKind.parse(o.str("kind_field"))!,
                manifestRevision: o.int64("manifest_revision"),
                baseRevision: o.int64Opt("base_revision"),
                totalEntries: o.int("total_entries"),
                totalRemoved: o.int("total_removed")
            )
        case "Page":
            return .page(
                manifestId: ManifestId(o.str("manifest_id")),
                manifestRevision: o.int64("manifest_revision"),
                pageIndex: o.int("page_index"),
                entries: o.array("entries").map { entryFromPartial($0 as! [String: Any]) }, // swiftlint:disable:this force_cast
                removed: o.array("removed").map { ContentHash($0 as! String) } // swiftlint:disable:this force_cast
            )
        case "End":
            return .end(
                manifestId: ManifestId(o.str("manifest_id")),
                manifestRevision: o.int64("manifest_revision"),
                pageCount: o.int("page_count"),
                totalEntries: o.int("total_entries"),
                totalRemoved: o.int("total_removed"),
                digest: o.str("digest")
            )
        case "Abort":
            return .abort(manifestId: ManifestId(o.str("manifest_id")), reason: o.str("reason"))
        case "Timeout":
            return .timeout
        case "ControlLinkLost":
            return .controlLinkLost
        default:
            fatalError("unknown event kind in vectors: \(o.str("kind"))")
        }
    }

    private func wireErrorName(_ error: ManifestSyncError?) -> String? {
        switch error {
        case nil: return nil
        case .sequenceError: return "manifest_sequence_error"
        case .digestMismatch: return "manifest_digest_mismatch"
        case .incomplete: return "manifest_incomplete"
        }
    }
}
