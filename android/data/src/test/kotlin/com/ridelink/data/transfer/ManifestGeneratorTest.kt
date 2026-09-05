package com.ridelink.data.transfer

import com.ridelink.data.database.TrackEntity
import com.ridelink.data.library.LibraryRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** [ManifestGenerator] over a [FakeTrackDao] — brief §5's manifest-generation rules, pure JVM. */
class ManifestGeneratorTest {
    private fun entity(
        localEntryId: String,
        contentHash: String?,
        quickId: String = "sha256:" + "a".repeat(64),
        decodeStatus: String = "INDEXED",
    ) = TrackEntity(
        localEntryId = localEntryId,
        quickId = quickId,
        contentHash = contentHash,
        title = "Title $localEntryId",
        artist = "Artist",
        album = "Album",
        durationMs = 200_000,
        filename = "f.mp3",
        codec = "mp3",
        bitrateKbps = 192,
        artworkRef = null,
        sizeBytes = 5_000_000,
        locationUri = "content://media/$localEntryId",
        decodeStatus = decodeStatus,
        indexedAtMonoUs = 0,
        lastSeenAtMonoUs = 0,
    )

    @Test
    fun `only indexed rows with a content hash are included`() =
        runTest {
            val dao = FakeTrackDao()
            dao.seed(entity("11111111-1111-1111-1111-111111111111", contentHash = "sha256:" + "1".repeat(64)))
            dao.seed(entity("22222222-2222-2222-2222-222222222222", contentHash = null)) // awaiting background hash
            dao.seed(entity("33333333-3333-3333-3333-333333333333", contentHash = "sha256:" + "3".repeat(64), decodeStatus = "MISSING"))
            val generator = ManifestGenerator(LibraryRepository(dao))

            val entries = generator.generate()

            assertEquals(1, entries.size, "only the content-hashed, INDEXED row is sync-eligible")
            assertEquals("sha256:" + "1".repeat(64), entries.single().contentHash?.value)
        }

    @Test
    fun `entries are ordered by content hash for deterministic output`() =
        runTest {
            val dao = FakeTrackDao()
            dao.seed(entity("11111111-1111-1111-1111-111111111111", contentHash = "sha256:" + "9".repeat(64)))
            dao.seed(entity("22222222-2222-2222-2222-222222222222", contentHash = "sha256:" + "1".repeat(64)))
            val generator = ManifestGenerator(LibraryRepository(dao))

            val first = generator.generate()
            val second = generator.generate()

            assertEquals(first.map { it.contentHash }, second.map { it.contentHash }, "repeated generation is deterministic")
            assertEquals(listOf("1", "9").map { "sha256:" + it.repeat(64) }, first.map { it.contentHash?.value })
        }

    @Test
    fun `has-artwork is derived from artworkRef presence, never loading a blob`() =
        runTest {
            val dao = FakeTrackDao()
            dao.seed(entity("11111111-1111-1111-1111-111111111111", contentHash = "sha256:" + "1".repeat(64)).copy(artworkRef = "art://1"))
            val generator = ManifestGenerator(LibraryRepository(dao))

            assertEquals(true, generator.generate().single().hasArtwork)
        }

    @Test
    fun `an empty library produces an empty manifest`() =
        runTest {
            val generator = ManifestGenerator(LibraryRepository(FakeTrackDao()))
            assertEquals(emptyList(), generator.generate())
        }

    @Test
    fun `work key groups same artist and title without being authoritative identity`() =
        runTest {
            val dao = FakeTrackDao()
            dao.seed(
                entity("11111111-1111-1111-1111-111111111111", contentHash = "sha256:" + "1".repeat(64))
                    .copy(artist = "The Beatles", title = "Come Together", durationMs = 259_000),
            )
            dao.seed(
                entity("22222222-2222-2222-2222-222222222222", contentHash = "sha256:" + "2".repeat(64))
                    .copy(artist = "the beatles", title = "come together", durationMs = 259_100),
            )
            val generator = ManifestGenerator(LibraryRepository(dao))

            val entries = generator.generate()
            assertEquals(2, entries.size, "different content_hash values are always different transferable entries")
            assertEquals(entries[0].workKey, entries[1].workKey, "the same work groups visually despite two different files")
        }
}
