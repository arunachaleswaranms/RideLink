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
    private var routeChanges = 0L
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
                localRouteChangeCount = { routeChanges },
            )
    }

    /** A follower playing track 1, started at the current instant, with the tick loop armed. */
    private suspend fun startPlaying(scope: TestScope) {
        scope.runCurrent()
        session.setClock(SessionClockEstimate(offsetToLeaderUs = 0, rttP95Us = 8_000, ready = true))
        session.emit(ControlEvent.Connected(SyncTestValues.leaderPeerId, SessionId("S"), false, 1L))
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

    /**
     * Advances to the next 5 s report tick with the player reporting `expected + driftMs`, after the
     * leader has reported itself exactly on the timeline one second earlier — the settled reference
     * ADR-004 Amendment A1 requires before a follower's ladder acts, so `driftMs` is the inter-phone
     * error too.
     */
    private fun tick(
        scope: TestScope,
        driftMs: Long,
        playing: Boolean = true,
        referenceDriftMs: Long = 0,
    ) {
        val nextTickUs = clock.pendingDeadlines.max()
        session.deliverLeaderReport(SyncTestValues.hash(1), ANCHOR_US, nextTickUs - 1_000_000, driftMs = referenceDriftMs)
        scope.runCurrent()
        val elapsedMs = (nextTickUs - ANCHOR_US) / 1_000
        player.setState(PlayerState(positionMs = elapsedMs + driftMs, durationMs = 600_000, playing = playing))
        clock.advanceTo(nextTickUs)
        scope.runCurrent()
    }

    @Test
    fun `two and a half virtual hours retain one correction deadline and drain every report`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            startPlaying(this)
            repeat(1_800) { cycle ->
                val nextTick = clock.pendingDeadlines.single()
                val drift = if (cycle % 2 == 0) 50L else 0L
                routeTransitioning = cycle % 17 == 0
                player.setState(
                    PlayerState(positionMs = (nextTick - ANCHOR_US) / 1_000 + drift, durationMs = 10_000_000, playing = true),
                )
                clock.advanceTo(nextTick)
                runCurrent()
                val diagnostics = coordinator.diagnostics.value
                assertEquals(cycle + 1, diagnostics.correctionTickCount)
                assertEquals(0, diagnostics.hardSeekCount)
                assertEquals(0, diagnostics.deferredCommandCount)
                assertEquals(diagnostics.outboundEnqueuedCount, diagnostics.outboundAttemptCount)
                assertEquals(1, clock.pendingDeadlines.size)
                // These are test recordings, not application retention.
                player.calls.clear()
                session.sent.clear()
                session.sentGenerations.clear()
            }
            assertEquals(9_000_000_000L, clock.nowUs() - ANCHOR_US)
            coordinator.leaveSynchronizedMode()
            runCurrent()
            assertEquals(SyncState.INACTIVE, coordinator.diagnostics.value.syncState)
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
    fun `drift past the nudge band hard-seeks to the reference position and counts it`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            startPlaying(this)
            tick(this, driftMs = 400)
            val seek = player.calls.filterIsInstance<FakeSyncPlayer.Call.Seek>().single()
            val expectedMs = (clock.nowUs() - ANCHOR_US) / 1_000
            assertEquals(
                expectedMs,
                seek.positionMs,
                "the seek target is the reference's position on the authoritative timeline, plus nothing learned yet",
            )
            assertEquals(1, coordinator.diagnostics.value.hardSeekCount)
        }

    /**
     * STATUS problem 131 (b), at this layer: the published state is up to 250 ms stale, and the tick
     * must measure the engine's live position at the instant it read it. Here the published value
     * says 200 ms behind — a hard seek, had it been used — and the engine says exactly on time.
     */
    @Test
    fun `a tick measures the live position, never the published one`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            startPlaying(this)
            val nextTickUs = clock.pendingDeadlines.max()
            player.livePositionMs = (nextTickUs - ANCHOR_US) / 1_000
            tick(this, driftMs = -200)
            assertEquals(0, coordinator.diagnostics.value.localDriftMs, "the drift is the live read's")
            assertEquals(0, coordinator.diagnostics.value.hardSeekCount, "and nothing was corrected for the stale one")
            assertEquals(
                (nextTickUs - ANCHOR_US) / 1_000,
                session.sentOfType<PlaybackMessage.PositionReport>().last().positionMs,
                "the report carries the live read too",
            )
        }

    /** ADR-004 Amendment A1 decision 3: no settled leader report, no decision — at any drift. */
    @Test
    fun `a follower with no reference from the leader corrects nothing`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            startPlaying(this)
            val nextTickUs = clock.pendingDeadlines.max()
            player.setState(PlayerState(positionMs = (nextTickUs - ANCHOR_US) / 1_000 + 400, durationMs = 600_000, playing = true))
            clock.advanceTo(nextTickUs)
            runCurrent()
            assertTrue(player.calls.none { it is FakeSyncPlayer.Call.Seek || it is FakeSyncPlayer.Call.SetRate })
            assertEquals(400, coordinator.diagnostics.value.localDriftMs, "its own drift is still measured and shown")
            assertNull(coordinator.diagnostics.value.syncErrorMs, "but there is no inter-phone error to act on")
        }

    /**
     * ADR-004 Amendment A1 decision 2, the case STATUS problem 131 found on two phones: both
     * outputs ~375 ms behind the timeline and aligned with each other. The follower reads its drift
     * honestly and corrects nothing, because the error between the phones is inside the dead band.
     */
    @Test
    fun `a lag the leader shares is measured and never chased`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            startPlaying(this)
            repeat(3) {
                val nextTickUs = clock.pendingDeadlines.max()
                session.deliverLeaderReport(SyncTestValues.hash(1), ANCHOR_US, nextTickUs - 1_000_000, driftMs = -375)
                runCurrent()
                player.setState(PlayerState(positionMs = (nextTickUs - ANCHOR_US) / 1_000 - 380, durationMs = 600_000, playing = true))
                clock.advanceTo(nextTickUs)
                runCurrent()
            }
            val diagnostics = coordinator.diagnostics.value
            assertEquals(-380, diagnostics.localDriftMs)
            assertEquals(-375, diagnostics.peerDriftMs)
            assertEquals(-5, diagnostics.syncErrorMs, "the error the ladder acts on is the difference")
            assertEquals(0, diagnostics.hardSeekCount)
            assertTrue(player.calls.none { it is FakeSyncPlayer.Call.Seek || it is FakeSyncPlayer.Call.SetRate })
        }

    /**
     * PR #32 review, at this layer: the learned restart compensation is forgotten when this device's
     * route-transition count moves between two ticks, with no tick ever seeing `transitioning`. The
     * table decides that (`protocol/vectors/drift/`); this proves the coordinator feeds it the count.
     */
    @Test
    fun `a route change between two ticks forgets the learned restart compensation`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            startPlaying(this)
            tick(this, driftMs = -575, referenceDriftMs = -375) // a 200 ms differential: seek, uncompensated
            tick(this, driftMs = -925, referenceDriftMs = -375) // its residual teaches 550 ms; seek again
            tick(this, driftMs = -375, referenceDriftMs = -375) // converged
            assertEquals(550, coordinator.diagnostics.value.restartCompensationMs, "the premise: 550 ms was learned")

            routeChanges += 1 // a transition began and settled while no tick was looking
            tick(this, driftMs = -375, referenceDriftMs = -375)
            assertEquals(0, coordinator.diagnostics.value.restartCompensationMs, "a new route has a new restart latency")
            assertEquals(2, coordinator.diagnostics.value.hardSeekCount, "and forgetting it is not itself a correction")
        }

    /**
     * PR #32 review: a leader report stamped after this device's own sample — here an hour after —
     * cannot be compared with it, so the ladder makes no decision on it, however large the error.
     */
    @Test
    fun `a leader report stamped after this device's sample is not acted on`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            startPlaying(this)
            val nextTickUs = clock.pendingDeadlines.max()
            session.deliverLeaderReport(SyncTestValues.hash(1), ANCHOR_US, nextTickUs + 3_600_000_000)
            runCurrent()
            player.setState(PlayerState(positionMs = (nextTickUs - ANCHOR_US) / 1_000 + 400, durationMs = 600_000, playing = true))
            clock.advanceTo(nextTickUs)
            runCurrent()
            assertTrue(player.calls.none { it is FakeSyncPlayer.Call.Seek || it is FakeSyncPlayer.Call.SetRate })
            assertNull(coordinator.diagnostics.value.syncErrorMs, "a future-dated reference is no reference")
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
