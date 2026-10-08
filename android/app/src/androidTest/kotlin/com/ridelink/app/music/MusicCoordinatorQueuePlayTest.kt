package com.ridelink.app.music

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ridelink.core.library.DecodeStatus
import com.ridelink.core.library.LibraryEntry
import com.ridelink.core.library.LocalTrackLocation
import com.ridelink.core.model.LocalEntryId
import com.ridelink.core.model.QuickId
import com.ridelink.core.model.Track
import com.ridelink.core.player.PlaybackCommand
import com.ridelink.core.player.Player
import com.ridelink.core.player.PlayerState
import com.ridelink.data.database.RideLinkDatabase
import com.ridelink.data.library.ArtworkCache
import com.ridelink.data.library.ImportSource
import com.ridelink.data.library.LibraryIndexer
import com.ridelink.data.library.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals

/**
 * Phase 9A.5 §10/§11 against the real [MusicCoordinator], a real Room repository and a recording
 * fake [Player]: Play with tracks queued and nothing selected starts the first one; duplicates stay
 * independent entries; and Now Playing's track does not depend on the Library screen's search.
 */
@RunWith(AndroidJUnit4::class)
class MusicCoordinatorQueuePlayTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: RideLinkDatabase
    private lateinit var repository: LibraryRepository
    private lateinit var coordinator: MusicCoordinator
    private lateinit var job: Job
    private val clock = AtomicLong(0)
    private val ids = AtomicLong(0)
    private val commands: MutableList<PlaybackCommand> = Collections.synchronizedList(mutableListOf())

    private inner class RecordingPlayer : Player {
        override var state: PlayerState = PlayerState()
            private set

        override suspend fun execute(command: PlaybackCommand): Result<Unit> {
            commands += command
            return Result.success(Unit)
        }

        override fun setStateSink(sink: (PlayerState) -> Unit) = Unit

        override suspend fun release() = Unit
    }

    private fun entry(n: Int) =
        LibraryEntry(
            localEntryId = LocalEntryId("bbbbbbbb-0000-0000-0000-00000000000$n"),
            track =
                Track(
                    contentHash = null,
                    quickId = QuickId("sha256:" + "%064x".format(n)),
                    title = "Track $n",
                    artist = "Artist",
                    album = "Album",
                    durationMs = 1000,
                    filename = "t$n.m4a",
                    codec = "aac",
                    bitrateKbps = 128,
                    artworkRef = null,
                    sizeBytes = 1,
                ),
            location = LocalTrackLocation("content://queue-play/$n"),
            decodeStatus = DecodeStatus.INDEXED,
            indexedAtMonoUs = n.toLong(),
            lastSeenAtMonoUs = n.toLong(),
        )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RideLinkDatabase::class.java).build()
        repository = LibraryRepository(db.trackDao())
        val indexer = LibraryIndexer(context, repository, ArtworkCache(context), monotonicNowUs = { clock.incrementAndGet() })
        job = SupervisorJob()
        coordinator =
            MusicCoordinator(
                repository = repository,
                indexer = indexer,
                player = RecordingPlayer(),
                scope = CoroutineScope(job + Dispatchers.Unconfined),
                monotonicNowUs = { clock.incrementAndGet() },
                nextQueueItemId = { "q${ids.incrementAndGet()}" },
            )
        runBlocking { (1..2).forEach { repository.insertNew(entry(it), ImportSource.File("content://queue-play/$it")) } }
    }

    @After
    fun tearDown() =
        runBlocking {
            job.cancelAndJoin()
            db.close()
        }

    private fun awaitCommands(count: Int) =
        runBlocking { withTimeout(5_000) { while (commands.size < count) kotlinx.coroutines.delay(10) } }

    @Test
    fun playWithAQueueAndNothingSelectedStartsTheFirstQueuedTrack() {
        coordinator.addToQueue(entry(1))
        coordinator.addToQueue(entry(2))
        commands.clear()

        coordinator.play()
        awaitCommands(2)

        assertEquals("q1", coordinator.queueState.value.currentId)
        val load = commands[0] as PlaybackCommand.Load
        assertEquals(entry(1).localEntryId, load.localEntryId)
        assertEquals(PlaybackCommand.Play, commands[1])
    }

    @Test
    fun playWithATrackSelectedOnlyResumesIt() {
        coordinator.addToQueue(entry(1))
        coordinator.addToQueue(entry(2))
        coordinator.selectQueueItem("q2")
        awaitCommands(2)
        commands.clear()

        coordinator.play()
        awaitCommands(1)

        assertEquals("q2", coordinator.queueState.value.currentId)
        assertEquals(listOf<PlaybackCommand>(PlaybackCommand.Play), commands.toList())
    }

    @Test
    fun theSameTrackQueuedTwiceIsTwoEntriesAndRemovalTakesExactlyOne() {
        coordinator.addToQueue(entry(1))
        coordinator.addToQueue(entry(1))
        assertEquals(
            listOf("q1", "q2"),
            coordinator.queueState.value.items
                .map { it.id },
        )

        coordinator.removeFromQueue("q2")

        assertEquals(
            listOf("q1"),
            coordinator.queueState.value.items
                .map { it.id },
        )
    }

    @Test
    fun nowPlayingDoesNotDependOnTheLibrarySearch() =
        runBlocking {
            coordinator.setSearchText("no track matches this")
            coordinator.addToQueue(entry(2))
            coordinator.play()

            val playing = withTimeout(5_000) { coordinator.nowPlayingEntry.first { it != null } }

            assertEquals("Track 2", playing?.track?.title)
        }
}
