package com.ridelink.app.sync

import com.ridelink.app.sync.SyncPlaybackTwoPeerTest.Companion.LEADER_START_US
import com.ridelink.core.playback.PlaybackTimeline
import com.ridelink.core.resync.ResyncMessage
import com.ridelink.core.resync.ResyncPlaybackSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Delivered authority across a local ride boundary, using the existing real two-coordinator fixture. */
class SyncPlaybackDeliveredAuthorityTest {
    @Test
    fun `no Outcome D - delivered Ride 1 PLAY completes on both peers after End and Start`() =
        deliveredRideBoundary(restart = true, successor = false)

    @Test
    fun `no Outcome D - End Ride without Start still honours delivered PLAY`() = deliveredRideBoundary(restart = false, successor = false)

    @Test
    fun `Ride 2 command admitted while C1 is parked wins on both peers`() = deliveredRideBoundary(restart = true, successor = true)

    private fun deliveredRideBoundary(
        restart: Boolean,
        successor: Boolean,
    ) = runTest(StandardTestDispatcher()) {
        val pair = SyncPlaybackTwoPeerTest().Pair(this)
        pair.connect()
        pair.seedContent(1..2)
        pair.leader.coordinator.enqueue(SyncTestValues.hash(1))
        pair.leader.coordinator.enqueue(SyncTestValues.hash(2))
        runCurrent()
        val origin =
            pair.leader.coordinator.rideEpochs
                .next()
        pair.follower.coordinator.rideEpochs
            .next()
        val gate = CompletableDeferred<Unit>()
        pair.leader.player.gate = gate
        pair.leader.player.gateOn = { it is FakeSyncPlayer.Call.Load }
        pair.leader.coordinator.playSynchronized(SyncTestValues.hash(1))
        runCurrent()
        assertTrue(
            pair.leader.player.calls
                .any { it is FakeSyncPlayer.Call.Load },
        )
        assertEquals(1, pair.follower.coordinator.diagnostics.value.lastReceivedCommandSeq)
        pair.advanceSessionTo(LEADER_START_US + 1_000_000)
        runCurrent()
        assertTrue(
            pair.follower.player.calls
                .contains(FakeSyncPlayer.Call.Start),
        )
        pair.leader.coordinator.endRideSegment(
            pair.leader.coordinator.rideEpochs
                .next(),
        )
        if (restart) {
            pair.leader.coordinator.rideEpochs
                .next()
        }
        if (successor) {
            val follower = pair.follower.coordinator
            follower.endRideSegment(follower.rideEpochs.next())
            follower.rideEpochs.next()
            pair.leader.coordinator.playSynchronized(SyncTestValues.hash(2))
            runCurrent()
            assertEquals(2, pair.follower.coordinator.diagnostics.value.lastReceivedCommandSeq)
            assertEquals(1, pair.leader.coordinator.diagnostics.value.lastAppliedCommandSeq)
        }
        gate.complete(Unit)
        runCurrent()
        pair.advanceSessionTo(LEADER_START_US + 2_000_000)
        runCurrent()
        assertTrue(
            pair.leader.player.calls
                .contains(FakeSyncPlayer.Call.Start),
            "No Outcome D: follower applied C1; issuer must not refuse solely because its ride ended",
        )
        for (peer in listOf(pair.leader, pair.follower)) {
            val expectedSeq = if (successor) 2L else 1L
            assertEquals(expectedSeq, peer.coordinator.diagnostics.value.lastReceivedCommandSeq)
            assertEquals(expectedSeq, peer.coordinator.diagnostics.value.lastAppliedCommandSeq)
            assertEquals(expectedSeq, authorityField(peer.coordinator, "lastReceivedSeq"))
            assertEquals(expectedSeq, authorityField(peer.coordinator, "lastAppliedSeq"))
            assertEquals(if (successor) origin + 2 else origin, authorityField(peer.coordinator, "rideAuthorityEpoch"))
            val expectedHash = SyncTestValues.hash(if (successor) 2 else 1)
            assertEquals(expectedHash, peer.coordinator.diagnostics.value.currentTrackHash)
            assertEquals(
                expectedHash,
                peer.player.calls
                    .filterIsInstance<FakeSyncPlayer.Call.Load>()
                    .last()
                    .contentHash,
            )
            assertEquals(0, peer.coordinator.retainedWorkCount)
        }
        assertTrue(origin < pair.leader.coordinator.rideEpochs.current)
        assertEquals(pair.leader.coordinator.queueState.value, pair.follower.coordinator.queueState.value)
        assertMatchingTimelines(pair.leader.coordinator, pair.follower.coordinator)
        assertEquals(1, pair.leader.session.currentAuthGeneration)
        assertEquals(1, pair.follower.session.currentAuthGeneration)
    }

    @Test
    fun `transport SENT after End Ride keeps original obligation on both peers`() =
        runTest(StandardTestDispatcher()) {
            val pair = SyncPlaybackTwoPeerTest().Pair(this)
            pair.connect()
            pair.seedContent(1..1)
            pair.leader.coordinator.rideEpochs
                .next()
            val outcome = CompletableDeferred<Unit>()
            pair.leader.session.sentOutcomeGate = outcome
            pair.leader.coordinator.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            assertTrue(pair.leader.session.isSentOutcomeParked)
            assertEquals(1, pair.follower.coordinator.diagnostics.value.lastReceivedCommandSeq)
            assertEquals(null, pair.leader.coordinator.diagnostics.value.lastReceivedCommandSeq)
            assertEquals(null, pair.leader.coordinator.diagnostics.value.lastAppliedCommandSeq)
            pair.leader.coordinator.endRideSegment(
                pair.leader.coordinator.rideEpochs
                    .next(),
            )
            pair.leader.coordinator.rideEpochs
                .next()
            outcome.complete(Unit)
            runCurrent()
            pair.advanceSessionTo(LEADER_START_US + 1_000_000)
            runCurrent()
            for (peer in listOf(pair.leader, pair.follower)) {
                assertEquals(1, peer.coordinator.diagnostics.value.lastReceivedCommandSeq)
                assertEquals(1, peer.coordinator.diagnostics.value.lastAppliedCommandSeq)
                assertTrue(peer.player.calls.contains(FakeSyncPlayer.Call.Start))
                assertEquals(0, peer.coordinator.retainedWorkCount)
            }
            assertEquals(1L, authorityField(pair.leader.coordinator, "rideAuthorityEpoch"))
        }

    @Test
    fun `Ride 2 snapshot establishes authority before C1 returns and old completion changes nothing`() =
        runTest(StandardTestDispatcher()) {
            val pair = SyncPlaybackTwoPeerTest().Pair(this)
            pair.connect()
            pair.seedContent(1..2)
            for (seed in 1..2) pair.leader.coordinator.enqueue(SyncTestValues.hash(seed))
            runCurrent()
            pair.leader.coordinator.rideEpochs
                .next()
            pair.follower.coordinator.rideEpochs
                .next()
            val gate = CompletableDeferred<Unit>()
            pair.follower.player.gate = gate
            pair.follower.player.gateOn = { it is FakeSyncPlayer.Call.Load && it.contentHash == SyncTestValues.hash(1) }
            pair.leader.coordinator.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            assertTrue(
                pair.follower.player.calls
                    .any { it is FakeSyncPlayer.Call.Load },
            )
            pair.advanceSessionTo(LEADER_START_US + 1_000_000)
            runCurrent()
            for (peer in listOf(pair.leader, pair.follower)) {
                peer.coordinator.endRideSegment(peer.coordinator.rideEpochs.next())
                peer.coordinator.rideEpochs.next()
            }
            pair.leader.coordinator.playSynchronized(SyncTestValues.hash(2))
            runCurrent()
            pair.advanceSessionTo(LEADER_START_US + 2_000_000)
            runCurrent()
            assertEquals(2, pair.leader.coordinator.diagnostics.value.lastAppliedCommandSeq)
            // Resync is a separate production consumer. An ingress loss requests a full restore;
            // the snapshot below is C2's actual leader queue/sequence/track, not fresh authority.
            pair.follower.coordinator.forceDesynchronizedForTest()
            val queue = pair.leader.coordinator.queueState.value
            val selected = requireNotNull(queue.items.getOrNull(requireNotNull(queue.currentIndex)))
            val snapshot =
                ResyncMessage.StateSnapshot(
                    leaderPeerId = pair.leader.localPeerId,
                    commandSeq = requireNotNull(pair.leader.coordinator.diagnostics.value.lastAppliedCommandSeq),
                    queueRevision = queue.revision,
                    playback = ResyncPlaybackSnapshot(selected.trackHash, selected.queueItemId, 0, true, pair.leaderClock.nowUs()),
                    queueItems = queue.items,
                    queueCurrentIndex = queue.currentIndex,
                    manifestRevision = 0,
                    transfersInFlight = emptyList(),
                )
            assertEquals(
                SyncPlaybackCoordinator.StateSnapshotOutcome.APPLIED,
                pair.follower.coordinator.onStateSnapshot(snapshot, 1, reconciliation = 77),
            )
            runCurrent()
            assertEquals(1, pair.follower.coordinator.retainedWorkCount, "only old C1 remains parked")
            assertTrue(
                pair.follower.player.calls
                    .contains(FakeSyncPlayer.Call.Start),
                "C2 became audible first",
            )
            pair.follower.coordinator.playSynchronized(SyncTestValues.hash(3))
            runCurrent()
            val protected = listOf("currentPlaybackIdentity", "timeline", "rideAuthorityEpoch", "synchronizedModeEpoch", "pendingPlay")
            val before = protected.map { authorityField(pair.follower.coordinator, it) }
            assertEquals(3L, authorityField(pair.follower.coordinator, "rideAuthorityEpoch"))
            assertTrue(authorityField(pair.follower.coordinator, "pendingPlay") != null)
            val reconciliation = pair.follower.coordinator.diagnostics.value.pendingPlaybackReconciliationGeneration
            val calls =
                pair.follower.player.calls
                    .toList()
            gate.complete(Unit)
            runCurrent()
            assertEquals(before, protected.map { authorityField(pair.follower.coordinator, it) })
            assertEquals(calls, pair.follower.player.calls, "old C1 dispatched no seek or start after successor authority")
            assertEquals(reconciliation, pair.follower.coordinator.diagnostics.value.pendingPlaybackReconciliationGeneration)
            for (peer in listOf(pair.leader, pair.follower)) {
                assertEquals(2, peer.coordinator.diagnostics.value.lastReceivedCommandSeq)
                assertEquals(2, peer.coordinator.diagnostics.value.lastAppliedCommandSeq)
                assertEquals(SyncTestValues.hash(2), peer.coordinator.diagnostics.value.currentTrackHash)
                assertEquals(0, peer.coordinator.retainedWorkCount)
            }
        }

    private fun assertMatchingTimelines(
        leader: SyncPlaybackCoordinator,
        follower: SyncPlaybackCoordinator,
    ) {
        val leaderTimeline = authorityField(leader, "timeline") as PlaybackTimeline
        val followerTimeline = authorityField(follower, "timeline") as PlaybackTimeline
        // Playback generation is a local token; all authoritative timeline fields must agree.
        assertEquals(leaderTimeline.copy(generation = 0), followerTimeline.copy(generation = 0))
    }

    /** Read-only invariant inspection without adding production APIs solely for tests. */
    private fun authorityField(
        coordinator: SyncPlaybackCoordinator,
        name: String,
    ): Any? =
        SyncPlaybackCoordinator::class.java.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(coordinator)
        }
}
