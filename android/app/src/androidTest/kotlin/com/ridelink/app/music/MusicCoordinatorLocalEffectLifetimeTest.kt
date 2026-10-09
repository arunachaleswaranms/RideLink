package com.ridelink.app.music

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ridelink.core.model.ContentHash
import com.ridelink.core.player.PlaybackCommand
import com.ridelink.core.player.Player
import com.ridelink.core.player.PlayerState
import com.ridelink.data.database.RideLinkDatabase
import com.ridelink.data.library.ArtworkCache
import com.ridelink.data.library.LibraryIndexer
import com.ridelink.data.library.LibraryRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-024 Amendment A15 round 2 through the **real** [MusicCoordinator]: it carries the admission it
 * minted for an edit into the launched player effect and re-proves that same admission there.
 *
 * The coordinator's scope runs on a [StandardTestDispatcher], so a launched effect does not run until
 * the test advances the scheduler — the effect is parked before its first proof, deterministically.
 * Queue entries are verified-cache tracks, whose `Load` resolves without a database read, so nothing
 * else suspends. `LocalPlaybackEffectsLifetimeTest` (JVM) covers every race in isolation; this proves
 * the coordinator is wired to it.
 */
@RunWith(AndroidJUnit4::class)
class MusicCoordinatorLocalEffectLifetimeTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: RideLinkDatabase
    private lateinit var coordinator: MusicCoordinator
    private lateinit var job: Job
    private val scheduler = TestCoroutineScheduler()
    private val gate = MusicCoordinatorQueuePlayTest.OwnershipGate()
    private val player = ParkingPlayer()
    private val clock = AtomicLong(0)
    private val ids = AtomicLong(0)

    private class ParkingPlayer : Player {
        val calls: MutableList<PlaybackCommand> = Collections.synchronizedList(mutableListOf())
        var parkOnLoad = false
        val parked = CompletableDeferred<Unit>()
        val reachedLoad = CompletableDeferred<Unit>()

        override suspend fun execute(command: PlaybackCommand): Result<Unit> {
            calls += command
            if (parkOnLoad && command is PlaybackCommand.Load) {
                reachedLoad.complete(Unit)
                parked.await()
            }
            return Result.success(Unit)
        }

        override val state = PlayerState()

        override fun setStateSink(sink: (PlayerState) -> Unit) = Unit

        override suspend fun release() = Unit
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RideLinkDatabase::class.java).build()
        val repository = LibraryRepository(db.trackDao())
        val indexer = LibraryIndexer(context, repository, ArtworkCache(context), monotonicNowUs = { clock.incrementAndGet() })
        job = SupervisorJob()
        coordinator =
            MusicCoordinator(
                repository = repository,
                indexer = indexer,
                player = player,
                scope = CoroutineScope(job + StandardTestDispatcher(scheduler)),
                monotonicNowUs = { clock.incrementAndGet() },
                nextQueueItemId = { "q${ids.incrementAndGet()}" },
            )
        coordinator.syncGate = gate
    }

    @After
    fun tearDown() {
        // The scope's dispatcher only runs when the test scheduler is advanced, so cancellation is
        // processed by advancing it — `cancelAndJoin` would wait on work nothing is running.
        player.parked.complete(Unit)
        job.cancel()
        scheduler.runCurrent()
        db.close()
    }

    private fun cacheTrack(n: Int) {
        assertTrue(
            coordinator.playExternalVerifiedCachedTrack(
                ContentHash("sha256:" + "%064x".format(n)),
                File(context.cacheDir, "t$n"),
                "T$n",
                "A",
            ),
        )
        scheduler.runCurrent()
    }

    @Test
    fun aSelectAdmittedLocallyDoesNotLoadOrPlayAfterSynchronisedActivation() {
        (1..3).forEach(::cacheTrack)
        player.calls.clear()

        assertTrue(coordinator.selectQueueItem("q2"), "admitted under local ownership")
        assertEquals("q2", coordinator.queueState.value.currentId)
        gate.synchronized = true // its effect has not run: parked before the first proof
        scheduler.runCurrent()

        assertEquals(emptyList(), player.calls.toList(), "stale local work reached the player")
    }

    @Test
    fun aClearAdmittedLocallyDoesNotStopSynchronisedPlayback() {
        (1..2).forEach(::cacheTrack)
        player.calls.clear()

        assertTrue(coordinator.clearQueue())
        gate.synchronized = true
        scheduler.runCurrent()

        assertEquals(emptyList(), player.calls.toList())
    }

    @Test
    fun abaLocalAgainDoesNotReviveTheOldEffectButAFreshEditWorks() {
        (1..3).forEach(::cacheTrack)
        player.calls.clear()

        assertTrue(coordinator.selectQueueItem("q1"))
        gate.synchronized = true
        gate.synchronized = false
        scheduler.runCurrent()
        assertEquals(emptyList(), player.calls.toList(), "local again revived the old admission")

        assertTrue(coordinator.selectQueueItem("q2"))
        scheduler.runCurrent()
        assertEquals(2, player.calls.size, "a fresh edit in the new local lifetime works: ${player.calls}")
        assertEquals(PlaybackCommand.Play, player.calls.last())
    }

    @Test
    fun aPlayNowParkedInsideLoadDoesNotPlayAfterSynchronisedActivation() {
        player.parkOnLoad = true
        assertTrue(
            coordinator.playExternalVerifiedCachedTrack(
                ContentHash("sha256:" + "%064x".format(9)),
                File(context.cacheDir, "t9"),
                "T9",
                "A",
            ),
        )
        scheduler.runCurrent()
        assertTrue(player.reachedLoad.isCompleted, "premise: parked inside Load")

        gate.synchronized = true
        player.parked.complete(Unit)
        scheduler.runCurrent()

        assertEquals(1, player.calls.size, "Play ran on the proof taken before Load: ${player.calls}")
        assertTrue(player.calls.single() is PlaybackCommand.Load)
    }
}
