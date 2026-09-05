import Foundation
import GRDB
import RideLinkCore
import XCTest
@testable import RideLinkPlatform

/// `ManifestGenerator` over a real, in-memory GRDB database — brief §5's manifest-generation rules,
/// following `LibraryIndexerTests`' "real GRDB, no fakes" convention rather than Android's
/// `FakeTrackDao`. Mirrors `com.ridelink.data.transfer.ManifestGeneratorTest`.
final class ManifestGeneratorTests: XCTestCase {
    private var dbQueue: DatabaseQueue!
    private var repository: LibraryRepository!

    override func setUpWithError() throws {
        dbQueue = try DatabaseQueue()
        try LibraryDatabase.makeMigrator().migrate(dbQueue)
        repository = LibraryRepository(dbQueue: dbQueue)
    }

    private func entry(
        localEntryId: String,
        contentHash: String?,
        quickId: String = "sha256:" + String(repeating: "a", count: 64),
        artist: String = "Artist",
        title: String? = nil,
        durationMs: Int64 = 200_000,
        artworkRef: String? = nil,
        decodeStatus: DecodeStatus = .indexed
    ) -> LibraryEntry {
        LibraryEntry(
            localEntryId: LocalEntryId(localEntryId),
            track: Track(
                contentHash: contentHash.map(ContentHash.init),
                quickId: QuickId(quickId),
                title: title ?? "Title \(localEntryId)",
                artist: artist,
                album: "Album",
                durationMs: durationMs,
                filename: "f.mp3",
                codec: "mp3",
                bitrateKbps: 192,
                artworkRef: artworkRef,
                sizeBytes: 5_000_000
            ),
            location: LocalTrackLocation(uri: "file:///music/\(localEntryId)"),
            decodeStatus: decodeStatus,
            indexedAtMonoUs: 0,
            lastSeenAtMonoUs: 0
        )
    }

    func testOnlyIndexedRowsWithAContentHashAreIncluded() async throws {
        try repository.insertNew(entry(localEntryId: "11111111-1111-1111-1111-111111111111", contentHash: "sha256:" + String(repeating: "1", count: 64)))
        try repository.insertNew(entry(localEntryId: "22222222-2222-2222-2222-222222222222", contentHash: nil)) // awaiting background hash
        try repository.insertNew(
            entry(
                localEntryId: "33333333-3333-3333-3333-333333333333",
                contentHash: "sha256:" + String(repeating: "3", count: 64),
                decodeStatus: .missing
            )
        )
        let generator = ManifestGenerator(libraryRepository: repository)

        let entries = try await generator.generate()

        XCTAssertEqual(entries.count, 1, "only the content-hashed, indexed row is sync-eligible")
        XCTAssertEqual(entries.first?.contentHash?.value, "sha256:" + String(repeating: "1", count: 64))
    }

    func testEntriesAreOrderedByContentHashForDeterministicOutput() async throws {
        try repository.insertNew(entry(localEntryId: "11111111-1111-1111-1111-111111111111", contentHash: "sha256:" + String(repeating: "9", count: 64)))
        try repository.insertNew(entry(localEntryId: "22222222-2222-2222-2222-222222222222", contentHash: "sha256:" + String(repeating: "1", count: 64)))
        let generator = ManifestGenerator(libraryRepository: repository)

        let first = try await generator.generate()
        let second = try await generator.generate()

        XCTAssertEqual(first.map(\.contentHash), second.map(\.contentHash), "repeated generation is deterministic")
        XCTAssertEqual(
            first.map(\.contentHash?.value),
            ["1", "9"].map { "sha256:" + String(repeating: $0, count: 64) }
        )
    }

    func testHasArtworkIsDerivedFromArtworkRefPresenceNeverLoadingABlob() async throws {
        try repository.insertNew(
            entry(
                localEntryId: "11111111-1111-1111-1111-111111111111",
                contentHash: "sha256:" + String(repeating: "1", count: 64),
                artworkRef: "art://1"
            )
        )
        let generator = ManifestGenerator(libraryRepository: repository)

        let entries = try await generator.generate()
        XCTAssertEqual(entries.first?.hasArtwork, true)
    }

    func testAnEmptyLibraryProducesAnEmptyManifest() async throws {
        let generator = ManifestGenerator(libraryRepository: repository)
        let entries = try await generator.generate()
        XCTAssertEqual(entries, [])
    }

    func testWorkKeyGroupsSameArtistAndTitleWithoutBeingAuthoritativeIdentity() async throws {
        try repository.insertNew(
            entry(
                localEntryId: "11111111-1111-1111-1111-111111111111",
                contentHash: "sha256:" + String(repeating: "1", count: 64),
                artist: "The Beatles",
                title: "Come Together",
                durationMs: 259_000
            )
        )
        try repository.insertNew(
            entry(
                localEntryId: "22222222-2222-2222-2222-222222222222",
                contentHash: "sha256:" + String(repeating: "2", count: 64),
                artist: "the beatles",
                title: "come together",
                durationMs: 259_100
            )
        )
        let generator = ManifestGenerator(libraryRepository: repository)

        let entries = try await generator.generate()
        XCTAssertEqual(entries.count, 2, "different content_hash values are always different transferable entries")
        XCTAssertEqual(entries[0].workKey, entries[1].workKey, "the same work groups visually despite two different files")
    }
}
