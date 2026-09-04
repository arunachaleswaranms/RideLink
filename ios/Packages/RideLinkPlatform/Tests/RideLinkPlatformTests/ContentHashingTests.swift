import Foundation
import RideLinkCore
import XCTest
@testable import RideLinkPlatform

/// Cross-checks `ContentHashing.computeContentHash` against `test-media/synthetic/MANIFEST.json`'s
/// own recorded SHA-256 for every fixture — an independent verification of the whole-file hash tier
/// beyond what `LibraryIndexerTests`' dedup behaviour alone proves, and the one place a genuinely
/// wrong `CryptoKit` usage (a byte-order slip, a truncated stream read) would be caught even if the
/// bug happened to be *consistent* enough to still dedup identical files with each other.
final class ContentHashingTests: XCTestCase {
    func testComputeContentHashMatchesTheManifestForEveryFixture() throws {
        let manifest = try loadManifest()
        for (filename, expectedHex) in manifest {
            let hash = try ContentHashing.computeContentHash(fileURL: TestMedia.url(filename))
            XCTAssertEqual(hash.hex, expectedHex, "\(filename): whole-file SHA-256 must match the manifest exactly")
        }
    }

    func testComputeQuickIdIsStableAcrossRepeatedCalls() throws {
        let url = try TestMedia.url("normal.m4a")
        let first = try ContentHashing.computeQuickId(fileURL: url)
        let second = try ContentHashing.computeQuickId(fileURL: url)
        XCTAssertEqual(first, second)
    }

    func testComputeQuickIdDiffersForGenuinelyDifferentContent() throws {
        let a = try ContentHashing.computeQuickId(fileURL: TestMedia.url("same_metadata_different_bytes_a.m4a"))
        let b = try ContentHashing.computeQuickId(fileURL: TestMedia.url("same_metadata_different_bytes_b.m4a"))
        XCTAssertNotEqual(a, b)
    }

    func testComputeQuickIdMatchesForByteIdenticalContentUnderDifferentFilenames() throws {
        let a = try ContentHashing.computeQuickId(fileURL: TestMedia.url("duplicate_a.m4a"))
        let b = try ContentHashing.computeQuickId(fileURL: TestMedia.url("duplicate_b.m4a"))
        XCTAssertEqual(a, b)
    }

    private func loadManifest() throws -> [String: String] {
        let url = try TestMedia.url("MANIFEST.json")
        let data = try Data(contentsOf: url)
        let raw = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: [String: Any]])
        return raw.reduce(into: [:]) { result, entry in
            if let sha256 = entry.value["sha256"] as? String { result[entry.key] = sha256 }
        }
    }
}
