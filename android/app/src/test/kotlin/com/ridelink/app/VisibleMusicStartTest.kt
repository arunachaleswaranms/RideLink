package com.ridelink.app

import com.ridelink.core.library.DecodeStatus
import com.ridelink.core.library.LibraryEntry
import com.ridelink.core.library.LocalTrackLocation
import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.LocalEntryId
import com.ridelink.core.model.QuickId
import com.ridelink.core.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PR #18 review round 3, blocker 2: `MainActivity`'s shared-music "Play" looked a track up — in the
 * repository and then in the transfer cache, both suspending — after its only visibility check, and
 * started the foreground service when the lookups returned. [VisibleMusicStart] reads visibility and
 * local ownership again immediately before the start. Each case parks a real suspension point (a
 * [CompletableDeferred] lookup on a test dispatcher), changes the world, releases it, and asserts
 * what reached the service and the coordinator. No sleeps.
 */
class VisibleMusicStartTest {
    private val hash = ContentHash.parse("sha256:" + "bb".repeat(32))!!
    private val cacheFile = File("cache-only.mp3")

    private class World {
        var visible = true
        var locked = false
        var serviceGranted = true
        val log = mutableListOf<String>()

        val start =
            VisibleMusicStart(
                foregroundVisible = { visible },
                localQueueLocked = { locked },
                startForegroundService = {
                    log += "start-service"
                    serviceGranted
                },
                onStartRefused = { log += "start-refused" },
            )
    }

    private class ParkedLookups(
        private val local: LibraryEntry?,
        private val file: File?,
    ) {
        val localGate = CompletableDeferred<Unit>()
        val fileGate = CompletableDeferred<Unit>()
        var localReached = false
        var fileReached = false

        suspend fun findLocal(
            @Suppress("UNUSED_PARAMETER") hash: ContentHash,
        ): LibraryEntry? {
            localReached = true
            localGate.await()
            return local
        }

        suspend fun cachedFile(
            @Suppress("UNUSED_PARAMETER") hash: ContentHash,
        ): File? {
            fileReached = true
            fileGate.await()
            return file
        }
    }

    private fun TestScope.launchPlay(
        world: World,
        lookups: ParkedLookups,
    ) = launch {
        world.start.playSharedTrack(
            hash = hash,
            findLocal = lookups::findLocal,
            cachedFile = lookups::cachedFile,
            playNow = { world.log += "play-now ${it.track.title}" },
            playCached = { world.log += "play-cached ${it.name}" },
        )
    }

    @Test
    fun `the Activity stops during the cache lookup - no foreground-service start and no local playback`() =
        runTest(StandardTestDispatcher()) {
            val world = World()
            val lookups = ParkedLookups(local = null, file = cacheFile)
            lookups.localGate.complete(Unit)
            launchPlay(world, lookups)
            runCurrent()
            assertTrue(lookups.fileReached, "premise: parked in the cache lookup")

            world.visible = false
            lookups.fileGate.complete(Unit)
            runCurrent()

            assertEquals(emptyList(), world.log, "a stopped Activity started the service or played")
        }

    @Test
    fun `the Activity stops during the repository lookup - no foreground-service start and no local playback`() =
        runTest(StandardTestDispatcher()) {
            val world = World()
            val lookups = ParkedLookups(local = entry(), file = null)
            launchPlay(world, lookups)
            runCurrent()
            assertTrue(lookups.localReached, "premise: parked in the repository lookup")

            world.visible = false
            lookups.localGate.complete(Unit)
            runCurrent()

            assertEquals(emptyList(), world.log)
        }

    @Test
    fun `synchronised mode takes transport during the lookup - no foreground-service start and no local playback`() =
        runTest(StandardTestDispatcher()) {
            val world = World()
            val lookups = ParkedLookups(local = null, file = cacheFile)
            lookups.localGate.complete(Unit)
            launchPlay(world, lookups)
            runCurrent()

            world.locked = true
            lookups.fileGate.complete(Unit)
            runCurrent()

            assertEquals(emptyList(), world.log)
        }

    @Test
    fun `still visible and local after the lookups - the service starts, then the cached track plays`() =
        runTest(StandardTestDispatcher()) {
            val world = World()
            val lookups = ParkedLookups(local = null, file = cacheFile)
            lookups.localGate.complete(Unit)
            lookups.fileGate.complete(Unit)
            launchPlay(world, lookups)
            runCurrent()

            assertEquals(listOf("start-service", "play-cached cache-only.mp3"), world.log)
        }

    @Test
    fun `an imported copy is preferred and played after the service starts`() =
        runTest(StandardTestDispatcher()) {
            val world = World()
            val lookups = ParkedLookups(local = entry(), file = cacheFile)
            lookups.localGate.complete(Unit)
            launchPlay(world, lookups)
            runCurrent()

            assertEquals(listOf("start-service", "play-now Test Track"), world.log)
            assertFalse(lookups.fileReached, "the cache was consulted although an imported copy exists")
        }

    @Test
    fun `not visible at the press - nothing is looked up, started or played`() =
        runTest(StandardTestDispatcher()) {
            val world = World().apply { visible = false }
            val lookups = ParkedLookups(local = entry(), file = cacheFile)
            launchPlay(world, lookups)
            runCurrent()

            assertFalse(lookups.localReached)
            assertEquals(emptyList(), world.log)
        }

    @Test
    fun `attemptPlayNow's first-play rule - start then play, a refused start records it and plays nothing`() {
        val world = World()
        assertTrue(world.start.startThen { world.log += "play" })
        assertEquals(listOf("start-service", "play"), world.log)

        world.log.clear()
        world.serviceGranted = false
        assertFalse(world.start.startThen { world.log += "play" })
        assertEquals(listOf("start-service", "start-refused"), world.log)

        world.log.clear()
        world.locked = true
        assertFalse(world.start.startThen { world.log += "play" })
        assertEquals(emptyList(), world.log, "a locked local queue still started the service")
    }

    private fun entry(): LibraryEntry =
        LibraryEntry(
            localEntryId = LocalEntryId.parse("00000000-0000-0000-0000-000000000002")!!,
            track =
                Track(
                    contentHash = hash,
                    quickId = QuickId.parse("sha256:" + "aa".repeat(32))!!,
                    title = "Test Track",
                    artist = "Test Artist",
                    album = "Test Album",
                    durationMs = 180_000,
                    filename = "test.mp3",
                    codec = "mp3",
                    bitrateKbps = 320,
                    artworkRef = null,
                    sizeBytes = 4_000_000,
                ),
            location = LocalTrackLocation("file:///tmp/test.mp3"),
            decodeStatus = DecodeStatus.INDEXED,
            indexedAtMonoUs = 0,
            lastSeenAtMonoUs = 0,
        )
}
