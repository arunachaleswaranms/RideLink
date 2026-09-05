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
import com.ridelink.core.player.MusicFailure
import com.ridelink.core.player.PlaybackCommand
import com.ridelink.core.player.Player
import com.ridelink.core.player.PlayerState
import com.ridelink.data.database.RideLinkDatabase
import com.ridelink.data.library.ArtworkCache
import com.ridelink.data.library.LibraryIndexer
import com.ridelink.data.library.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * This phase's closure-audit hardening pass, Finding E: [MainActivity][com.ridelink.app.MainActivity]'s
 * `attemptMusicPlay`/`attemptPlayNow` used to discard
 * [com.ridelink.app.service.RideForegroundService.startMusicFromVisibleUi]'s return value and call
 * [MusicCoordinator.play]/[MusicCoordinator.playNow] unconditionally, so playback proceeded as though
 * background ownership were established even when Android refused the foreground-service start.
 * `MainActivity` itself has no test harness (real device/Espresso territory, out of reach here), so
 * this proves the deterministic, injectable seam the fix actually lives behind:
 * [MusicCoordinator.onForegroundServiceStartFailed] records a named refusal instead of a real
 * `ForegroundServiceStartNotAllowedException`, and a subsequent successful [MusicCoordinator.play]/
 * [MusicCoordinator.playNow] always clears it — exactly the contract `MainActivity`'s two call sites
 * rely on.
 *
 * Uses a real in-memory Room database/[LibraryRepository] and a real [LibraryIndexer] (this class
 * needs a real `Context`, which only an instrumented test can supply — never actually asked to import
 * anything here) with a hand-written fake [Player], matching [Player]'s own documented "a fake
 * implementation can drive queue/coordinator logic" contract rather than a mocking framework (none is
 * a project dependency).
 */
@RunWith(AndroidJUnit4::class)
class MusicCoordinatorForegroundServiceFailureTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: RideLinkDatabase
    private lateinit var coordinator: MusicCoordinator
    private val clock = AtomicLong(0)
    private val queueItemIds = AtomicLong(0)

    /**
     * Owned by this test, not `MusicCoordinator` — [MusicCoordinator.completeContentHashingInBackground]
     * is deliberately kicked off unstructured, from the constructor, against exactly this scope (Finding
     * B's fix). A real crash found while verifying Finding C: without cancelling and awaiting this job
     * before [db] closes in [tearDown], that background query can still be mid-flight against the
     * in-memory Room database, and closing it out from under an in-flight query throws
     * `IllegalStateException: connection pool has been closed` on whatever thread the query resumes on —
     * fatal to the instrumentation process, not just this test. Cancel-and-join makes the ordering
     * explicit rather than accidental.
     */
    private lateinit var coordinatorJob: Job
    private lateinit var coordinatorScope: CoroutineScope

    private class FakePlayer : Player {
        override var state: PlayerState = PlayerState()
            private set

        override suspend fun execute(command: PlaybackCommand): Result<Unit> = Result.success(Unit)

        override fun setStateSink(sink: (PlayerState) -> Unit) = Unit

        override suspend fun release() = Unit
    }

    private fun fakeEntry(suffix: String): LibraryEntry =
        LibraryEntry(
            localEntryId = LocalEntryId("aaaaaaaa-0000-0000-0000-00000000000$suffix"),
            track =
                Track(
                    contentHash = null,
                    quickId = QuickId("sha256:" + "aa".repeat(32)),
                    title = "Fake Track $suffix",
                    artist = "Artist",
                    album = "Album",
                    durationMs = 1000,
                    filename = "fake$suffix.m4a",
                    codec = "aac",
                    bitrateKbps = 128,
                    artworkRef = null,
                    sizeBytes = 4096,
                ),
            location = LocalTrackLocation("content://fake$suffix"),
            decodeStatus = DecodeStatus.INDEXED,
            indexedAtMonoUs = 0,
            lastSeenAtMonoUs = 0,
        )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RideLinkDatabase::class.java).build()
        val repository = LibraryRepository(db.trackDao())
        val indexer = LibraryIndexer(context, repository, ArtworkCache(context), monotonicNowUs = { clock.incrementAndGet() })
        coordinatorJob = SupervisorJob()
        coordinatorScope = CoroutineScope(coordinatorJob + Dispatchers.Unconfined)
        coordinator =
            MusicCoordinator(
                repository = repository,
                indexer = indexer,
                player = FakePlayer(),
                scope = coordinatorScope,
                monotonicNowUs = { clock.incrementAndGet() },
                nextQueueItemId = { "q${queueItemIds.incrementAndGet()}" },
            )
    }

    @After
    fun tearDown() =
        runBlocking {
            // Must complete before db.close() below — see coordinatorJob's own doc comment.
            coordinatorJob.cancelAndJoin()
            db.close()
        }

    @Test
    fun noRefusalOutstandingByDefault() {
        assertNull(coordinator.lastMusicStartRefusal.value)
    }

    @Test
    fun onForegroundServiceStartFailedRecordsANamedRefusal() {
        coordinator.onForegroundServiceStartFailed()
        assertEquals(MusicFailure.FOREGROUND_SERVICE_START_FAILED, coordinator.lastMusicStartRefusal.value)
    }

    @Test
    fun aSuccessfulPlayClearsAnOutstandingRefusal() =
        runBlocking {
            coordinator.onForegroundServiceStartFailed()
            assertEquals(MusicFailure.FOREGROUND_SERVICE_START_FAILED, coordinator.lastMusicStartRefusal.value)
            coordinator.play()
            assertNull(
                coordinator.lastMusicStartRefusal.value,
                "a caller only ever calls play() after confirming the foreground service started",
            )
        }

    @Test
    fun aSuccessfulPlayNowClearsAnOutstandingRefusal() =
        runBlocking {
            coordinator.onForegroundServiceStartFailed()
            assertEquals(MusicFailure.FOREGROUND_SERVICE_START_FAILED, coordinator.lastMusicStartRefusal.value)
            coordinator.playNow(fakeEntry("1"))
            assertNull(coordinator.lastMusicStartRefusal.value)
        }
}
