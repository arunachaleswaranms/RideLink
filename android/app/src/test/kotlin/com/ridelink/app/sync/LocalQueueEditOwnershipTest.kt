package com.ridelink.app.sync

import com.ridelink.app.music.LocalQueueEdits
import com.ridelink.core.model.LocalEntryId
import com.ridelink.core.model.SessionId
import com.ridelink.core.player.LocalQueueAction
import com.ridelink.core.player.LocalQueueEffect
import com.ridelink.core.player.LocalQueueItem
import com.ridelink.core.player.LocalQueueState
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 9A.5, PR #18 review: a user's edit of the **local** queue is refused while synchronised
 * transport owns playback, and admitted again once ownership has really returned local.
 *
 * Everything here goes through the real [SyncPlaybackCoordinator] and the real
 * [SyncPlaybackGateAdapter] — the same ownership ADR-024 Amendment A14 established
 * (`syncEnabled && role != null`) — into [LocalQueueEdits], the one admission `MusicCoordinator`
 * applies to select, remove, move, clear, add and play-now. A `null` outcome is a refusal with no
 * state change and no effect; the coordinator-level proof that a refusal reaches no player is
 * `MusicCoordinatorQueuePlayTest` on the emulator.
 */
class LocalQueueEditOwnershipTest {
    private lateinit var session: FakeSyncSession
    private lateinit var coordinator: SyncPlaybackCoordinator
    private lateinit var clock: FakeMonotonicClock
    private var idSeed = 900

    private fun id(n: Int) = LocalEntryId("dddddddd-0000-0000-0000-00000000000$n")

    /** Three entries, the second current — so removing it would advance, and clearing would stop. */
    private val queue =
        LocalQueueState(
            items = listOf(LocalQueueItem("q1", id(1), 0), LocalQueueItem("q2", id(2), 1), LocalQueueItem("q3", id(3), 2)),
            currentId = "q2",
        )

    private val edits =
        listOf(
            LocalQueueAction.Select("q3"),
            LocalQueueAction.Clear,
            LocalQueueAction.Remove("q2"),
            LocalQueueAction.Move("q3", 0),
            LocalQueueAction.Add(LocalQueueItem("q4", id(4), 3)),
        )

    @Test
    fun `local, then synchronised, then local again — through Play on this phone only`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this)
            val gate = SyncPlaybackGateAdapter(backgroundScope, coordinator)

            // E: local ownership — every edit is admitted with exactly LocalQueue's effects.
            assertFalse(coordinator.isSynchronizedModeActive(), "premise: connected but not synchronised")
            assertLocalRulesApply(gate)

            // A-D: synchronised ownership — every edit is refused, with nothing to apply.
            coordinator.playSynchronized(HASH_UNHELD)
            runCurrent()
            assertTrue(coordinator.isSynchronizedModeActive(), "premise: Play synced is an activation")
            assertTrue(coordinator.transportOwnershipForDisplay.value, "the display mirror follows")
            assertEverythingRefused(gate)

            // F: ownership genuinely returns local — the same gate admits again, nothing cached.
            coordinator.leaveSynchronizedMode()
            runCurrent()
            assertFalse(coordinator.isSynchronizedModeActive())
            assertFalse(coordinator.transportOwnershipForDisplay.value, "the display mirror follows back")
            assertLocalRulesApply(gate)
        }

    @Test
    fun `End Ride returns the local queue to the user, and the role surviving it does not lock it`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this)
            val gate = SyncPlaybackGateAdapter(backgroundScope, coordinator)
            coordinator.rideEpochs.next()
            coordinator.playSynchronized(HASH_UNHELD)
            runCurrent()
            assertEverythingRefused(gate)

            coordinator.endRideSegment(coordinator.rideEpochs.next())
            runCurrent()

            assertEquals(com.ridelink.core.playback.PlaybackRole.FOLLOWER, coordinator.diagnostics.value.role, "premise: the role survives")
            assertFalse(coordinator.isSynchronizedModeActive())
            assertLocalRulesApply(gate)
        }

    @Test
    fun `no gate at all — no synchronised session was ever built — is plain local behaviour`() {
        edits.forEach { assertNotNull(LocalQueueEdits.reduce(queue, listOf(it), gate = null), "$it") }
    }

    private fun assertEverythingRefused(gate: SyncPlaybackGateAdapter) {
        assertTrue(gate.localQueueLocked())
        edits.forEach { assertNull(LocalQueueEdits.reduce(queue, listOf(it), gate), "$it was admitted while synchronised") }
        val playNow = listOf(LocalQueueAction.Add(LocalQueueItem("q5", id(5), 4)), LocalQueueAction.Select("q5"))
        assertNull(LocalQueueEdits.reduce(queue, playNow, gate), "play-now was admitted while synchronised")
    }

    private fun assertLocalRulesApply(gate: SyncPlaybackGateAdapter) {
        assertFalse(gate.localQueueLocked())
        val select = assertNotNull(LocalQueueEdits.reduce(queue, listOf(LocalQueueAction.Select("q3")), gate))
        assertEquals("q3", select.state.currentId)
        assertEquals(listOf<LocalQueueEffect>(LocalQueueEffect.LoadAndPlay(id(3))), select.effects)

        val clear = assertNotNull(LocalQueueEdits.reduce(queue, listOf(LocalQueueAction.Clear), gate))
        assertTrue(clear.state.items.isEmpty())
        assertEquals(listOf<LocalQueueEffect>(LocalQueueEffect.StopPlayback), clear.effects)

        val removeCurrent = assertNotNull(LocalQueueEdits.reduce(queue, listOf(LocalQueueAction.Remove("q2")), gate))
        assertEquals("q3", removeCurrent.state.currentId, "removing the current entry advances to its successor")
        assertEquals(listOf<LocalQueueEffect>(LocalQueueEffect.LoadAndPlay(id(3))), removeCurrent.effects)

        val move = assertNotNull(LocalQueueEdits.reduce(queue, listOf(LocalQueueAction.Move("q3", 0)), gate))
        assertEquals(listOf("q3", "q1", "q2"), move.state.items.map { it.id })
        assertTrue(move.effects.isEmpty())

        val playNow = listOf(LocalQueueAction.Add(LocalQueueItem("q5", id(5), 4)), LocalQueueAction.Select("q5"))
        val played = assertNotNull(LocalQueueEdits.reduce(queue, playNow, gate))
        assertEquals("q5", played.state.currentId)
        assertEquals(listOf<LocalQueueEffect>(LocalQueueEffect.LoadAndPlay(id(5))), played.effects)
    }

    private fun build(scope: CoroutineScope) {
        session = FakeSyncSession()
        clock = FakeMonotonicClock(ANCHOR_US)
        coordinator =
            SyncPlaybackCoordinator(
                scope = scope,
                monotonicNowUs = { clock.nowUs() },
                localPeerId = SyncTestValues.followerPeerId,
                session = session,
                player = FakeSyncPlayer(),
                content = FakeSyncContent(),
                sleeper = clock.sleeper,
                routeTransitioning = { false },
                nextQueueItemId = { SyncTestValues.ulid(idSeed++) },
            )
    }

    private suspend fun connect(scope: TestScope) {
        scope.runCurrent()
        session.setClock(SessionClockEstimate(offsetToLeaderUs = 0, rttP95Us = 8_000, ready = true))
        session.emit(ControlEvent.Connected(SyncTestValues.leaderPeerId, SessionId("S"), false, 1L))
        scope.runCurrent()
    }

    private companion object {
        const val ANCHOR_US = 50_000_000L

        /** A track neither phone holds: Play synced on it is an activation that issues nothing. */
        val HASH_UNHELD = SyncTestValues.hash(0xB3)
    }
}
