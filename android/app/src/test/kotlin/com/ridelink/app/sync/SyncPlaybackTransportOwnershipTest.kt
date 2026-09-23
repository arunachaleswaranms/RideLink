package com.ridelink.app.sync

import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.playback.Phase5GateBounds
import com.ridelink.core.playback.PlaybackBounds
import com.ridelink.core.playback.PlaybackCommandHeader
import com.ridelink.core.playback.PlaybackMessage
import com.ridelink.core.playback.PlaybackRole
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
import kotlin.test.assertTrue

/**
 * ADR-024 Amendment A14, Android parity: **completing previously distributed authority after End
 * Ride may satisfy that old obligation, but it never reopens admission of fresh synchronised
 * transport authority.**
 *
 * Android's gate already read the authoritative [SyncPlaybackCoordinator.isSynchronizedModeActive]
 * (iOS reconstructed it from diagnostics, which is what A14 fixed there), so ownership itself was
 * already right here. What was not: the coordinator's own transport entry points admitted a fresh
 * press whether or not synchronised mode owned the controls — reachable from the synchronised
 * playback card, which calls them directly, and from the gate's own intercept-then-launch window.
 * Reproduced against the unmodified sources first (a follower's direct `pause()` after End Ride and
 * old-debt completion put a PAUSE intent on the wire).
 *
 * The real [SyncPlaybackGateAdapter] is used wherever a gate answer is asserted. [FakeMonotonicClock]
 * is the only clock and [StandardTestDispatcher] decides ordering; nothing sleeps.
 */
class SyncPlaybackTransportOwnershipTest {
    private lateinit var session: FakeSyncSession
    private lateinit var player: FakeSyncPlayer
    private lateinit var content: FakeSyncContent
    private lateinit var clock: FakeMonotonicClock
    private lateinit var coordinator: SyncPlaybackCoordinator
    private var idSeed = 700

    // --- Regressions A–E ---------------------------------------------------------------------------

    @Test
    fun `accepted debt finishing after End Ride leaves the gate local until a legitimate activation`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false)
            val gate = SyncPlaybackGateAdapter(backgroundScope, coordinator)
            followerFinishesAcceptedDebtAfterEndRide()

            // A: C1 completed correctly, as ride-1 work.
            assertEquals(1, coordinator.diagnostics.value.lastAppliedCommandSeq, "C1 was not represented")
            assertTrue(heldAcceptedSeqs().isEmpty(), "C1 did not leave the held stream")
            assertTrue(player.calls.contains(FakeSyncPlayer.Call.Start), "C1's effect did not complete")
            assertEquals(HASH_A, coordinator.diagnostics.value.currentTrackHash)
            // E: the role survives without ownership, with SYNCED on display.
            assertEquals(PlaybackRole.FOLLOWER, coordinator.diagnostics.value.role)
            assertEquals(SyncState.SYNCED, coordinator.diagnostics.value.syncState)
            assertFalse(coordinator.isSynchronizedModeActive(), "role != null and SYNCED are not ownership")

            // B: the real gate.
            val quietSent = session.sent.size
            val quietCalls = player.calls.toList()
            assertFalse(gate.interceptPlay(), "Play")
            assertFalse(gate.interceptPause(), "Pause")
            assertFalse(gate.interceptSeek(12_000), "Seek")
            assertFalse(gate.interceptNext(), "Next")
            assertFalse(gate.interceptPrevious(), "Previous")
            assertFalse(gate.interceptTrackEnded(), "TrackEnded: MusicCoordinator advances its own queue")
            runCurrent()
            // C: the entry points the synchronised-playback card calls directly refuse fresh authority.
            coordinator.pause()
            coordinator.resume()
            coordinator.seek(3_000)
            coordinator.next()
            coordinator.previous()
            runCurrent()
            assertEquals(quietSent, session.sent.size, "a synchronised frame reached the wire: ${session.sent}")
            assertEquals(quietCalls, player.calls, "the synchronised session touched the player")

            // D: a nominal Start Ride is not an activation; the follower's Play synced is.
            coordinator.rideEpochs.next()
            assertFalse(coordinator.isSynchronizedModeActive(), "a nominal Start Ride establishes nothing")
            assertFalse(gate.interceptPause())
            coordinator.playSynchronized(HASH_UNHELD)
            runCurrent()
            assertTrue(coordinator.isSynchronizedModeActive(), "Play synced reopened ownership")
            assertTrue(gate.interceptPause(), "Pause is intercepted again")
            runCurrent()
            assertTrue(gate.interceptSeek(7_000), "Seek is intercepted again")
            runCurrent()
            assertTrue(gate.interceptNext(), "Next is intercepted again")
            runCurrent()
            val intents =
                session.sent.filterIsInstance<PlaybackMessage>().filter {
                    it is PlaybackMessage.Pause || it is PlaybackMessage.Seek || it is PlaybackMessage.Next
                }
            assertEquals(3, intents.size, "each intercepted press became exactly one intent: $intents")
            assertTrue(intents.all { headerOf(it).commandSeq == PlaybackBounds.UNASSIGNED_COMMAND_SEQ }, "a follower only ever asks")
            assertTrue(gate.interceptTrackEnded(), "a synchronised follower waits for the leader's NEXT")
        }

    // --- Fresh-fix audit -------------------------------------------------------------------------

    /**
     * The gate answers on the caller's thread and forwards in a launched coroutine. A press it
     * intercepted while synchronised whose coroutine runs after End Ride must be refused where the
     * authority would be created, not admitted as a brand-new ride.
     */
    @Test
    fun `a press intercepted just before End Ride is refused where the authority would be created`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false)
            coordinator.rideEpochs.next()
            session.setClock(UNREADY)
            session.deliver(play(HASH_A, seq = 1, effectiveAt = clock.nowUs() + C1_LEAD_US))
            runCurrent()
            assertTrue(coordinator.isSynchronizedModeActive(), "premise: accepting C1 is an activation")
            val gate = SyncPlaybackGateAdapter(backgroundScope, coordinator)
            val before = session.sent.size

            assertTrue(gate.interceptPause(), "premise: intercepted while synchronised")
            // The forwarded press has only been launched; End Ride runs before it does.
            coordinator.endRideSegment(coordinator.rideEpochs.next())
            runCurrent()

            assertEquals(before, session.sent.size, "the raced press became fresh authority: ${session.sent}")
        }

    /**
     * The guard is for fresh **local** authority only. The leader's fresh command after this phone's
     * End Ride is the peer's authority: accepted, applied and — exactly as before A14 — an activation.
     */
    @Test
    fun `a fresh authoritative command from the leader after End Ride is still accepted and activates`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false)
            followerFinishesAcceptedDebtAfterEndRide()

            session.deliver(
                PlaybackMessage.Pause(
                    PlaybackCommandHeader(2, clock.nowUs(), SyncTestValues.leaderPeerId, queueRevision = 0),
                    positionMs = 500,
                ),
            )
            runCurrent()
            val diagnostics = coordinator.diagnostics.value
            assertEquals(2, diagnostics.lastAppliedCommandSeq, "the leader's fresh PAUSE was applied")
            assertEquals(0, diagnostics.staleCommandCount)
            assertEquals(0, diagnostics.duplicateCommandCount)
            assertEquals(0, diagnostics.staleRevisionCount)
            assertTrue(coordinator.isSynchronizedModeActive(), "the peer's fresh authority is an activation")
        }

    // --- The scenario ------------------------------------------------------------------------------

    /**
     * Ride 1: this follower accepts C1 while its clock is untrusted, so C1 is held as accepted debt
     * (ADR-024 Amendment A13) and synchronised mode is active. End Ride. Then the clock recovers and
     * C1 finishes after the ride — SCHEDULED, then started and SYNCED — with ownership asserted off at
     * each of those points.
     */
    private fun TestScope.followerFinishesAcceptedDebtAfterEndRide() {
        coordinator.rideEpochs.next()
        session.setClock(UNREADY)
        val deadline = clock.nowUs() + C1_LEAD_US
        session.deliver(play(HASH_A, seq = 1, effectiveAt = deadline))
        runCurrent()
        assertEquals(listOf(1L), heldAcceptedSeqs(), "premise: C1 accepted and held as distributed debt")
        assertTrue(coordinator.isSynchronizedModeActive(), "premise: accepting C1 is an activation")

        coordinator.endRideSegment(coordinator.rideEpochs.next())
        assertFalse(coordinator.isSynchronizedModeActive(), "End Ride ended transport ownership")
        assertEquals(PlaybackRole.FOLLOWER, coordinator.diagnostics.value.role, "the role survives End Ride")
        assertEquals(listOf(1L), heldAcceptedSeqs(), "End Ride kept C1")
        runCurrent()
        player.calls.clear()

        session.setClock(READY)
        clock.advanceBy(Phase5GateBounds.DEFERRED_RETRY_INTERVAL_US)
        runCurrent()
        assertEquals(SyncState.SCHEDULED, coordinator.diagnostics.value.syncState, "C1 scheduled after End Ride")
        assertFalse(coordinator.isSynchronizedModeActive(), "SCHEDULED is not an activation")

        clock.advanceTo(deadline)
        runCurrent()
        assertEquals(SyncState.SYNCED, coordinator.diagnostics.value.syncState, "C1 synced after End Ride")
        assertFalse(coordinator.isSynchronizedModeActive(), "SYNCED is not an activation")
    }

    // --- Fixtures ----------------------------------------------------------------------------------

    private fun build(
        scope: CoroutineScope,
        localPeerId: PeerId = SyncTestValues.followerPeerId,
    ) {
        session = FakeSyncSession()
        player = FakeSyncPlayer()
        content = FakeSyncContent()
        clock = FakeMonotonicClock(ANCHOR_US)
        coordinator =
            SyncPlaybackCoordinator(
                scope = scope,
                monotonicNowUs = { clock.nowUs() },
                localPeerId = localPeerId,
                session = session,
                player = player,
                content = content,
                sleeper = clock.sleeper,
                routeTransitioning = { false },
                nextQueueItemId = { SyncTestValues.ulid(idSeed++) },
            )
        content.localHashes.add(HASH_A.value)
        content.peerHashes.add(HASH_A.value)
    }

    private suspend fun connect(
        scope: TestScope,
        asLeader: Boolean,
    ) {
        scope.runCurrent()
        session.setClock(READY)
        session.emit(ControlEvent.Connected(SyncTestValues.leaderPeerId, SessionId("S"), asLeader, 1L))
        scope.runCurrent()
        player.calls.clear()
    }

    private fun play(
        hash: com.ridelink.core.model.ContentHash,
        seq: Long,
        effectiveAt: Long,
    ) = PlaybackMessage.Play(
        PlaybackCommandHeader(seq, effectiveAt, SyncTestValues.leaderPeerId, queueRevision = 0),
        hash,
        positionMs = 0,
        queueItemId = SyncTestValues.ulid(seq.toInt()),
    )

    private fun headerOf(message: PlaybackMessage): PlaybackCommandHeader =
        when (message) {
            is PlaybackMessage.Play -> message.header
            is PlaybackMessage.Pause -> message.header
            is PlaybackMessage.Resume -> message.header
            is PlaybackMessage.Seek -> message.header
            is PlaybackMessage.Next -> message.header
            is PlaybackMessage.Previous -> message.header
            else -> error("not a command: $message")
        }

    private fun heldAcceptedSeqs(): List<Long> =
        (field("deferredEvents") as Iterable<*>).mapNotNull { event ->
            event
                ?.takeIf { it.javaClass.simpleName == "AcceptedCommand" }
                ?.let { accepted ->
                    accepted.javaClass.getDeclaredField("commandSeq").let {
                        it.isAccessible = true
                        it.get(accepted) as Long
                    }
                }
        }

    private fun field(name: String): Any? =
        SyncPlaybackCoordinator::class.java.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(coordinator)
        }

    private companion object {
        const val ANCHOR_US = 50_000_000L

        /** Far enough ahead that the drain's first retry schedules C1 rather than applying it late. */
        const val C1_LEAD_US = 10 * Phase5GateBounds.DEFERRED_RETRY_INTERVAL_US
        val HASH_A = SyncTestValues.hash(0xA1)

        /** A track neither phone holds: pressing Play synced on it is an activation that issues nothing. */
        val HASH_UNHELD = SyncTestValues.hash(0xB2)
        val READY = SessionClockEstimate(offsetToLeaderUs = 0, rttP95Us = 8_000, ready = true)
        val UNREADY = SessionClockEstimate(offsetToLeaderUs = 0, rttP95Us = 8_000, ready = false)
    }
}
