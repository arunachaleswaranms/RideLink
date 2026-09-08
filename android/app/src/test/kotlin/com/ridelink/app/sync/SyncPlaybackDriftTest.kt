package com.ridelink.app.sync

import com.ridelink.core.model.SessionId
import com.ridelink.core.playback.DriftController
import com.ridelink.core.playback.PlaybackCommandHeader
import com.ridelink.core.playback.PlaybackMessage
import com.ridelink.core.player.PlayerState
import com.ridelink.core.sync.SessionClockEstimate
import com.ridelink.network.control.ControlEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The drift half of the coordinator's wiring: that a tick reads the *one* player, measures against
 * the **authoritative timeline** (never one phone's position minus the other's — brief §33), asks
 * `DriftController`, and does what it says.
 *
 * The ladder's own boundaries, hysteresis and seek budget are pinned by `protocol/vectors/drift/` on
 * both platforms and are deliberately not re-asserted here; what is asserted here is that this class
 * consults that table and honours its answer, including the two guards that only exist at this
 * layer — route-transition suspension and epoch/session binding.
 */
class SyncPlaybackDriftTest {
    private lateinit var session: FakeSyncSession
    private lateinit var player: FakeSyncPlayer
    private lateinit var content: FakeSyncContent
    private lateinit var clock: FakeMonotonicClock
    private lateinit var coordinator: SyncPlaybackCoordinator
    private var routeTransitioning = false
    private var idSeed = 200

    private fun build(scope: CoroutineScope) {
        session = FakeSyncSession()
        player = FakeSyncPlayer()
        content = FakeSyncContent()
        clock = FakeMonotonicClock()
        routeTransitioning = false
        coordinator =
            SyncPlaybackCoordinator(
                scope = scope,
                monotonicNowUs = { clock.nowUs() },
                localPeerId = SyncTestValues.followerPeerId,
                session = session,
                player = player,
                content = content,
                sleeper = clock.sleeper,
                routeTransitioning = { routeTransitioning },
                nextQueueItemId = { SyncTestValues.ulid(idSeed++) },
            )
    }

    /** A follower playing track 1, started at the current instant, with the tick loop armed. */
    private suspend fun startPlaying(scope: TestScope) {
        scope.runCurrent()
        session.setClock(SessionClockEstimate(offsetToLeaderUs = 0, rttP95Us = 8_000, ready = true))
        session.emit(ControlEvent.Connected(SyncTestValues.leaderPeerId, SessionId("S"), false))
        scope.runCurrent()
        content.localHashes.add(SyncTestValues.hash(1).value)
        session.deliver(
            PlaybackMessage.Play(
                PlaybackCommandHeader(1, clock.nowUs(), SyncTestValues.leaderPeerId, 0),
                SyncTestValues.hash(1),
                positionMs = 0,
                queueItemId = SyncTestValues.ulid(1),
            ),
        )
        scope.runCurrent()
        player.calls.clear()
        session.sent.clear()
    }

    /** Advances to the next 5 s report tick with the player reporting `expected + driftMs`. */
    private fun tick(
        scope: TestScope,
        driftMs: Long,
        playing: Boolean = true,
    ) {
        val nextTickUs = clock.pendingDeadlines.max()
        val elapsedMs = (nextTickUs - ANCHOR_US) / 1_000
        player.setState(PlayerState(positionMs = elapsedMs + driftMs, durationMs = 600_000, playing = playing))
        clock.advanceTo(nextTickUs)
        scope.runCurrent()
    }

    @Test
    fun `a tick reports our own position against the authoritative timeline`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            startPlaying(this)
            tick(this, driftMs = 0)
            val report = session.sentOfType<PlaybackMessage.PositionReport>().single()
            assertEquals(SyncTestValues.hash(1), report.trackHash)
            assertEquals(clock.nowUs(), report.atSessionUs, "the report is stamped in session time, never wall-clock")
            assertTrue(report.playing)
        }

    @Test
    fun `drift inside the nudge band sets the rate on the one player`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            startPlaying(this)
            // Ahead of the timeline by 40 ms: slow down.
            tick(this, driftMs = 40)
            assertEquals(FakeSyncPlayer.Call.SetRate(DriftController.RATE_SLOWER), player.calls.last())
            assertEquals(SyncCorrection.NUDGE, coordinator.diagnostics.value.lastCorrection)
            assertEquals(40, coordinator.diagnostics.value.localDriftMs)
        }

    @Test
    fun `a converged nudge is restored to exactly one point zero`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            startPlaying(this)
            tick(this, driftMs = 40)
            player.calls.clear()
            tick(this, driftMs = 5)
            assertEquals(FakeSyncPlayer.Call.SetRate(1.0), player.calls.last())
            assertEquals(SyncCorrection.RESTORE_RATE, coordinator.diagnostics.value.lastCorrection)
        }

    @Test
    fun `drift past the nudge band hard-seeks to the expected position and counts it`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            startPlaying(this)
            tick(this, driftMs = 400)
            val seek = player.calls.filterIsInstance<FakeSyncPlayer.Call.Seek>().single()
            val expectedMs = (clock.nowUs() - ANCHOR_US) / 1_000
            assertEquals(expectedMs, seek.positionMs, "the seek target is the authoritative timeline's position")
            assertEquals(1, coordinator.diagnostics.value.hardSeekCount)
        }

    @Test
    fun `a route transition suspends correction entirely and does not spend the seek budget`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            startPlaying(this)
            routeTransitioning = true
            tick(this, driftMs = 400)
            tick(this, driftMs = 400)
            tick(this, driftMs = 400)
            assertTrue(player.calls.none { it is FakeSyncPlayer.Call.Seek }, "no seek while a route is transitioning")
            assertTrue(player.calls.none { it is FakeSyncPlayer.Call.SetRate }, "no nudge while a route is transitioning")
            assertEquals(0, coordinator.diagnostics.value.hardSeekCount)
            assertTrue(coordinator.diagnostics.value.routeTransitioning)

            // Three seeks would have declared failure by now had the transition counted; it must not.
            routeTransitioning = false
            tick(this, driftMs = 400)
            assertEquals(1, coordinator.diagnostics.value.hardSeekCount)
            assertEquals(SyncCorrection.HARD_SEEK, coordinator.diagnostics.value.lastCorrection)
        }

    @Test
    fun `catastrophic drift declares sync failure, restores the rate and leaves music playing`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            startPlaying(this)
            tick(this, driftMs = 5_000)
            assertEquals(SyncState.SYNC_FAILED, coordinator.diagnostics.value.syncState)
            assertEquals(FakeSyncPlayer.Call.SetRate(1.0), player.calls.filterIsInstance<FakeSyncPlayer.Call.SetRate>().last())
            assertTrue(player.calls.none { it == FakeSyncPlayer.Call.Stop }, "FR-025: local music keeps playing")

            player.calls.clear()
            tick(this, driftMs = 5_000)
            assertTrue(player.calls.none { it is FakeSyncPlayer.Call.Seek }, "correction is over once sync has failed")
        }

    @Test
    fun `leaving synchronized mode restores exactly one point zero and stops correcting`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            startPlaying(this)
            tick(this, driftMs = 40)
            player.calls.clear()
            coordinator.leaveSynchronizedMode()
            runCurrent()
            assertEquals(FakeSyncPlayer.Call.SetRate(1.0), player.calls.last())
            assertFalse(coordinator.isSynchronizedModeActive())
            assertNull(coordinator.diagnostics.value.localDriftMs)
        }

    @Test
    fun `a peer position report for a different track is ignored`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            startPlaying(this)
            session.deliver(
                PlaybackMessage.PositionReport(SyncTestValues.hash(9), 1_000, clock.nowUs(), true, 1.0),
            )
            runCurrent()
            assertNull(coordinator.diagnostics.value.peerDriftMs, "a report for another track says nothing about this one")
        }

    @Test
    fun `a peer position report from before this epoch's anchor is ignored`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            startPlaying(this)
            // The same track_hash, but stamped before this play of it began — brief §32's exact case,
            // and why content_hash alone is not enough to identify a playback epoch.
            session.deliver(
                PlaybackMessage.PositionReport(SyncTestValues.hash(1), 55_000, ANCHOR_US - 1, true, 1.0),
            )
            runCurrent()
            assertNull(coordinator.diagnostics.value.peerDriftMs)

            session.deliver(
                PlaybackMessage.PositionReport(SyncTestValues.hash(1), 40, ANCHOR_US + 1_000_000, true, 1.0),
            )
            runCurrent()
            assertEquals(-960, coordinator.diagnostics.value.peerDriftMs, "the peer is 960 ms behind the timeline")
        }

    private companion object {
        /** [FakeMonotonicClock]'s starting instant, which is also the PLAY's `effective_at`. */
        const val ANCHOR_US = 1_000_000L
    }
}
