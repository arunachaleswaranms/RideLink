import XCTest

@testable import RideLinkCore

/// Runs `protocol/vectors/manifest/manifest_vectors.json` — presence classification and delta.
///
/// The mirror is `com.ridelink.core.manifest.ManifestVectorTest`, running the **same file**.
final class ManifestVectorTests: XCTestCase {
    private let expectedRowCount = 13

    private func document() throws -> [String: Any] {
        // swiftlint:disable:next force_cast
        try Vectors.loadJSON("manifest/manifest_vectors.json") as! [String: Any]
    }

    func testEveryRowOfTheSharedFileHolds() throws {
        let doc = try document()
        var checked = 0
        for element in doc.array("rows") {
            guard let row = element as? [String: Any] else { return XCTFail("row is not an object") }
            let name = row.str("name")
            let expect = row.dict("expect")
            if expect.hasKey("presence_by_content_hash") {
                checkPresence(name, row, expect)
            } else if expect.hasKey("added") {
                checkDelta(name, row, expect)
            } else {
                XCTFail("vector \(name): unrecognised expect shape")
            }
            checked += 1
        }
        XCTAssertEqual(expectedRowCount, checked, "expected \(expectedRowCount) rows")
    }

    private func checkPresence(_ name: String, _ row: [String: Any], _ expect: [String: Any]) {
        let local = Set(row.array("local_content_hashes").map { ContentHash($0 as! String) }) // swiftlint:disable:this force_cast
        let peer = Set(row.array("peer_content_hashes").map { ContentHash($0 as! String) }) // swiftlint:disable:this force_cast
        let result = Presence.classify(local: local, peer: peer)
        let expectedMap = expect.dict("presence_by_content_hash")
        XCTAssertEqual(expectedMap.count, result.count, "vector \(name): classification size")
        for (hash, classification) in expectedMap {
            XCTAssertEqual(
                Presence.Classification(rawValue: classification as! String), // swiftlint:disable:this force_cast
                result[ContentHash(hash)],
                "vector \(name): classification of \(hash)"
            )
        }
    }

    private func checkDelta(_ name: String, _ row: [String: Any], _ expect: [String: Any]) {
        let old = row.array("old_manifest").map { entryFrom($0 as! [String: Any]) } // swiftlint:disable:this force_cast
        let new = row.array("new_manifest").map { entryFrom($0 as! [String: Any]) } // swiftlint:disable:this force_cast
        let delta = ManifestDelta.compute(old: old, new: new)
        let expectedAddedHashes = expect.array("added").map { entry -> String in
            (entry as! [String: Any]).str("content_hash") // swiftlint:disable:this force_cast
        }
        XCTAssertEqual(expectedAddedHashes, delta.added.map { $0.contentHash!.value }, "vector \(name): added")
        let expectedRemoved = expect.array("removed").map { $0 as! String } // swiftlint:disable:this force_cast
        XCTAssertEqual(expectedRemoved, delta.removed.map(\.value), "vector \(name): removed")
    }

    /// Only `content_hash`/`quick_id` are read from the vector row; the rest are filler so
    /// `ManifestEntry` can be constructed at all — mirrors the Kotlin test's `entryFrom`.
    private func entryFrom(_ o: [String: Any]) -> ManifestEntry {
        ManifestEntry(
            contentHash: ContentHash(o.str("content_hash")),
            quickId: QuickId(o.str("quick_id")),
            workKey: "k",
            title: o.strOpt("title") ?? "t",
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
}
