package com.ridelink.app.music

import com.ridelink.core.library.LocalTrackLocation
import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.LocalEntryId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * ADR-023 Amendment A4 Finding U — the cache-eviction lock Finding I introduced was inert on
 * Android: `MusicCoordinator.activeExternalCacheHash` was a
 * `stateIn(scope, SharingStarted.WhileSubscribed(), null)` flow whose only consumer read `.value`,
 * and nothing anywhere collected it, so it reported its initial `null` forever and every
 * `TransferCacheRepository.commit` got an empty `locked` set. These cases pin the replacement's
 * behaviour as a synchronous read of live state, which cannot have that failure mode.
 *
 * (iOS's equivalent was already a plain computed property — see ADR-023 Amendment A4.)
 */
class ExternalCacheSourcesTest {
    private val hashA = ContentHash("sha256:" + "aa".repeat(32))
    private val hashB = ContentHash("sha256:" + "bb".repeat(32))
    private val entryA = LocalEntryId("11111111-1111-4111-8111-111111111111")
    private val entryB = LocalEntryId("22222222-2222-4222-8222-222222222222")
    private val importedEntry = LocalEntryId("33333333-3333-4333-8333-333333333333")

    private fun source(hash: ContentHash) = ExternalCacheSource(hash, LocalTrackLocation("file:///cache/${hash.hex}"), null, null)

    @Test
    fun `a registered cache source is reported as active the moment it is the current item`() {
        val sources = ExternalCacheSources()
        sources.register(entryA, source(hashA))

        // No subscriber, no collection, no scope — the whole point of Finding U's fix.
        assertEquals(hashA, sources.activeHash(entryA))
    }

    @Test
    fun `nothing playing means nothing locked`() {
        val sources = ExternalCacheSources()
        sources.register(entryA, source(hashA))

        assertNull(sources.activeHash(null))
    }

    @Test
    fun `a Phase 3 imported track playing means no cache entry is locked`() {
        val sources = ExternalCacheSources()
        sources.register(entryA, source(hashA))

        // An imported LibraryEntry's LocalEntryId was never registered here — its bytes live in the
        // library, not the transfer cache, so there is nothing for eviction to protect.
        assertNull(sources.activeHash(importedEntry))
    }

    @Test
    fun `the active hash follows the current item, so switching tracks moves the lock`() {
        val sources = ExternalCacheSources()
        sources.register(entryA, source(hashA))
        sources.register(entryB, source(hashB))

        assertEquals(hashA, sources.activeHash(entryA))
        assertEquals(hashB, sources.activeHash(entryB))
    }

    @Test
    fun `a source registered after an earlier read is visible to the next one`() {
        val sources = ExternalCacheSources()

        assertNull(sources.activeHash(entryA))
        sources.register(entryA, source(hashA))
        assertEquals(hashA, sources.activeHash(entryA), "the read is live, never a snapshot taken at construction")
    }

    @Test
    fun `get returns the full source so the player can load it, not merely its hash`() {
        val sources = ExternalCacheSources()
        val registered = source(hashA)
        sources.register(entryA, registered)

        assertEquals(registered, sources[entryA])
        assertNull(sources[entryB])
    }
}
