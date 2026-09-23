package com.ridelink.app.sync

import com.ridelink.app.sync.SyncPlaybackTwoPeerTest.Companion.LEADER_START_US
import com.ridelink.app.sync.SyncPlaybackTwoPeerTest.Companion.OFFSET_US
import com.ridelink.core.playback.Phase5GateBounds
import com.ridelink.core.playback.PlaybackCommandHeader
import com.ridelink.core.playback.PlaybackMessage
import com.ridelink.core.playback.PlaybackTimeline
import com.ridelink.core.resync.ResyncMessage
import com.ridelink.core.resync.ResyncPlaybackSnapshot
import com.ridelink.core.sync.SessionClockEstimate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-024 Amendment A13: an authoritative command a follower has **accepted** — `lastReceivedSeq`
 * advanced — while its clock is untrusted is distributed debt, not ride-local candidate work.
 *
 * Two real coordinators over the in-process ordered channels, each on its own fake monotonic clock
 * (the iOS mirror, `SyncPlaybackTwoPeerTests`, runs Regressions A–C over real TLS). Every ordering is
 * produced by a gate or a virtual clock advance; nothing waits on elapsed time.
 */
class SyncPlaybackAcceptedObligationTest {
    // --- Regressions A and B ---------------------------------------------------------------------

    @Test
    fun `clock-held accepted C1 survives follower End Ride and completes on both peers`() =
        clockHeldAcceptedCommand(startAnotherRide = false)

    @Test
    fun `clock-held accepted C1 survives End and nominal Start with original provenance`() =
        clockHeldAcceptedCommand(startAnotherRide = true)

    private fun clockHeldAcceptedCommand(startAnotherRide: Boolean) =
        runTest(StandardTestDispatcher()) {
            val pair = SyncPlaybackTwoPeerTest().Pair(this)
            pair.connect()
            pair.seedContent(1..1)
            val hash = SyncTestValues.hash(1)
            val follower = pair.follower.coordinator
            pair.leader.coordinator.rideEpochs
                .next()
            val followerOrigin = follower.rideEpochs.next()
            pair.follower.session.setClock(UNREADY)

            pair.leader.coordinator.playSynchronized(hash)
            runCurrent()
            assertEquals(1, follower.diagnostics.value.lastReceivedCommandSeq, "F accepted C1")
            assertEquals(listOf(1L), heldAcceptedSeqs(follower), "F retained C1 for its clock")
            assertNull(field(follower, "lastAppliedSeq"), "accepted is not represented")
            assertTrue(
                pair.follower.player.calls
                    .none { it is FakeSyncPlayer.Call.Select || it == FakeSyncPlayer.Call.Start },
            )
            pair.advanceSessionTo(LEADER_START_US + 1_000_000)
            runCurrent()
            assertTrue(
                pair.leader.player.calls
                    .contains(FakeSyncPlayer.Call.Start),
                "L represented and started C1",
            )
            assertEquals(1, pair.leader.coordinator.diagnostics.value.lastAppliedCommandSeq)

            follower.endRideSegment(follower.rideEpochs.next())
            if (startAnotherRide) follower.rideEpochs.next()
            assertEquals(1L, field(follower, "lastReceivedSeq"), "End Ride never rolls back accepted responsibility")
            assertEquals(listOf(1L), heldAcceptedSeqs(follower), "End Ride must not erase an accepted distributed obligation")

            pair.follower.session.setClock(READY)
            pair.advanceSessionTo(LEADER_START_US + 2_000_000)
            runCurrent()

            assertNoLeaderAppliedFollowerAcceptedFollowerDiscarded(pair, seq = 1)
            for (peer in listOf(pair.leader, pair.follower)) {
                assertEquals(1, peer.coordinator.diagnostics.value.lastReceivedCommandSeq)
                assertEquals(1, peer.coordinator.diagnostics.value.lastAppliedCommandSeq)
                assertEquals(1L, field(peer.coordinator, "lastReceivedSeq"))
                assertEquals(1L, field(peer.coordinator, "lastAppliedSeq"))
                assertEquals(hash, peer.coordinator.diagnostics.value.currentTrackHash)
                assertEquals(hash, lastLoad(peer), "actual player effects reflect C1")
                assertTrue(peer.player.calls.contains(FakeSyncPlayer.Call.Start))
                assertEquals(0, peer.coordinator.retainedWorkCount)
            }
            assertEquals(followerOrigin, field(follower, "rideAuthorityEpoch"), "C1 keeps Ride-1 provenance")
            assertTrue(heldAcceptedSeqs(follower).isEmpty())
            assertEquals(0, follower.diagnostics.value.retiredRideDeferredCount, "never retired by local Ride expiry")
            assertMatchingTimelines(pair.leader.coordinator, follower)
            assertEquals(1, pair.follower.session.currentAuthGeneration, "the authenticated generation stays healthy")
        }

    // --- Regression C ----------------------------------------------------------------------------

    /**
     * Nothing authoritative can overtake a *retained* C1 (ADR-024 A2 Finding D), so the reachable
     * successor ordering is C1's own first suspension after it leaves the held stream — its
     * `content.resolve` — with genuine Ride-2 C2 arriving on the inbound consumer meanwhile.
     */
    @Test
    fun `genuine Ride 2 authority established before held C1 represents wins on both peers`() =
        runTest(StandardTestDispatcher()) {
            val pair = SyncPlaybackTwoPeerTest().Pair(this)
            pair.connect()
            pair.seedContent(1..2)
            val old = SyncTestValues.hash(1)
            val new = SyncTestValues.hash(2)
            pair.leader.coordinator.enqueue(old)
            pair.leader.coordinator.enqueue(new)
            runCurrent()
            val follower = pair.follower.coordinator
            pair.leader.coordinator.rideEpochs
                .next()
            val followerOrigin = follower.rideEpochs.next()
            pair.follower.session.setClock(UNREADY)
            pair.leader.coordinator.playSynchronized(old)
            runCurrent()
            assertEquals(listOf(1L), heldAcceptedSeqs(follower))
            pair.advanceSessionTo(LEADER_START_US + 1_000_000)
            runCurrent()
            assertEquals(1, pair.leader.coordinator.diagnostics.value.lastAppliedCommandSeq)
            assertEquals(0, pair.leader.coordinator.retainedWorkCount)
            for (peer in listOf(pair.leader, pair.follower)) {
                peer.coordinator.endRideSegment(peer.coordinator.rideEpochs.next())
                peer.coordinator.rideEpochs.next()
            }
            val followerRide2 = follower.rideEpochs.current

            val resolve = CompletableDeferred<Unit>()
            pair.follower.content.resolveGate = resolve
            pair.follower.content.resolveGateWhen = { true }
            pair.follower.session.setClock(READY)
            pair.advanceSessionTo(LEADER_START_US + 2_000_000)
            runCurrent()
            assertTrue(heldAcceptedSeqs(follower).isEmpty(), "C1 left the held stream")
            assertEquals(1, follower.retainedWorkCount, "C1 holds its reservation, parked in its resolve")
            assertNull(field(follower, "lastAppliedSeq"), "C1 has represented nothing yet")
            // Detach the gate so only C1 stays parked: the fake would otherwise hold every later resolve.
            pair.follower.content.resolveGate = null

            pair.leader.coordinator.playSynchronized(new)
            runCurrent()
            pair.advanceSessionTo(LEADER_START_US + 3_000_000)
            runCurrent()
            assertEquals(2, pair.leader.coordinator.diagnostics.value.lastAppliedCommandSeq)
            assertEquals(2, follower.diagnostics.value.lastAppliedCommandSeq, "genuine C2 established on F")
            assertEquals(new, lastLoad(pair.follower))
            assertEquals(1, follower.retainedWorkCount, "only parked C1 remains")
            assertEquals(followerRide2, field(follower, "rideAuthorityEpoch"), "C2 established Ride 2 ownership")
            val protected =
                listOf(
                    "currentPlaybackIdentity",
                    "timeline",
                    "rideAuthorityEpoch",
                    "synchronizedModeEpoch",
                    "pendingPlay",
                    "currentEpochToken",
                )
            val before = protected.map { field(follower, it) }
            val trackBefore = follower.diagnostics.value.currentTrackHash
            val reconciliationBefore = follower.diagnostics.value.pendingPlaybackReconciliationGeneration
            val callsBefore =
                pair.follower.player.calls
                    .toList()

            resolve.complete(Unit)
            runCurrent()
            assertEquals(before, protected.map { field(follower, it) }, "C1 overwrote Ride-2 state")
            assertEquals(trackBefore, follower.diagnostics.value.currentTrackHash, "C1 altered current-track diagnostics")
            assertEquals(reconciliationBefore, follower.diagnostics.value.pendingPlaybackReconciliationGeneration)
            assertEquals(callsBefore, pair.follower.player.calls, "C1 dispatched stale player steps")
            assertNotEquals(followerOrigin, field(follower, "rideAuthorityEpoch"))
            for (peer in listOf(pair.leader, pair.follower)) {
                assertEquals(2L, field(peer.coordinator, "lastReceivedSeq"), "sequence truth rolled backwards")
                assertEquals(2L, field(peer.coordinator, "lastAppliedSeq"), "sequence truth rolled backwards")
                assertEquals(new, peer.coordinator.diagnostics.value.currentTrackHash)
                assertEquals(0, peer.coordinator.retainedWorkCount)
            }
            assertMatchingTimelines(pair.leader.coordinator, follower)
        }

    // --- Regression D ----------------------------------------------------------------------------

    /**
     * G1's accepted, clock-held C1 dies with G1: the authenticated stream itself retired, and Phase 7
     * resynchronisation owns convergence. It never applies under G2, leaves nothing behind, and does
     * not stop G2 making progress.
     */
    @Test
    fun `a held G1 accepted command dies with G1 and never applies under G2`() =
        runTest(StandardTestDispatcher()) {
            val pair = SyncPlaybackTwoPeerTest().Pair(this)
            pair.connect()
            pair.seedContent(1..2)
            val follower = pair.follower.coordinator
            pair.follower.session.setClock(UNREADY)
            pair.leader.coordinator.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            assertEquals(listOf(1L), heldAcceptedSeqs(follower), "C1 accepted and retained under G1")

            pair.dropLink()
            pair.reconnect(generation = 2)
            assertTrue(heldAcceptedSeqs(follower).isEmpty(), "G1's retained metadata must disappear with G1")
            assertEquals(0, follower.diagnostics.value.deferredCommandCount)
            assertNull(field(follower, "lastReceivedSeq"), "G2's ordering floor is G2's own")
            assertEquals(0, follower.retainedWorkCount, "no G1 reservation survives")
            assertEquals(0, (field(follower, "deliveredEffects") as Map<*, *>).size)

            pair.follower.session.setClock(READY)
            pair.advanceSessionTo(LEADER_START_US + 2_000_000)
            runCurrent()
            assertFalse(
                pair.follower.player.calls
                    .contains(FakeSyncPlayer.Call.Select(SyncTestValues.hash(1))),
                "a G1 command mutated G2",
            )

            pair.leader.coordinator.playSynchronized(SyncTestValues.hash(2))
            runCurrent()
            pair.advanceSessionTo(LEADER_START_US + 3_000_000)
            runCurrent()
            assertEquals(1, follower.diagnostics.value.lastAppliedCommandSeq, "G2 makes progress from its own command_seq 1")
            assertEquals(SyncTestValues.hash(2), follower.diagnostics.value.currentTrackHash)
            assertEquals(0, follower.diagnostics.value.staleCommandCount, "G1's floor refused G2's command_seq 1")
        }

    // --- Regression E ----------------------------------------------------------------------------

    /**
     * A clock-ready accepted command meeting a full `SessionWorkLedger` stays at the head of the held
     * stream — not popped, not lost, applied truth unmoved, the drain parked on its cadence — and
     * later reserves exactly once, applies and releases exactly once. The held command holds no
     * ledger reservation while it waits for its clock: the held stream's own bound owns it.
     */
    @Test
    fun `a clock-ready accepted command waits for capacity without loss or spin and releases once`() =
        runTest(StandardTestDispatcher()) {
            val pair = SyncPlaybackTwoPeerTest().Pair(this, followerSessionWorkCapacity = 1)
            pair.connect()
            pair.seedContent(1..2)
            val follower = pair.follower.coordinator
            // C0's scheduled start parks inside the player, so its one obligation holds the whole
            // ledger while the inbound consumer stays free.
            val startGate = CompletableDeferred<Unit>()
            pair.follower.player.gate = startGate
            pair.follower.player.gateOn = { it == FakeSyncPlayer.Call.Start }
            pair.leader.coordinator.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.advanceSessionTo(LEADER_START_US + 1_000_000)
            runCurrent()
            assertEquals(1, follower.diagnostics.value.lastAppliedCommandSeq)
            assertEquals(1, follower.retainedWorkCount, "C0's scheduled effect holds the ledger")

            pair.follower.session.setClock(UNREADY)
            pair.leader.coordinator.playSynchronized(SyncTestValues.hash(2))
            runCurrent()
            assertEquals(listOf(2L), heldAcceptedSeqs(follower), "C1 accepted")
            assertEquals(1, follower.retainedWorkCount, "a clock-held command takes no local work capacity")

            pair.follower.session.setClock(READY)
            repeat(RETRIES) {
                pair.followerClock.advanceBy(Phase5GateBounds.DEFERRED_RETRY_INTERVAL_US)
                runCurrent()
            }
            assertEquals(listOf(2L), heldAcceptedSeqs(follower), "C1 was popped or lost while capacity was unavailable")
            assertEquals(1L, field(follower, "lastAppliedSeq"), "applied truth moved without representation")
            assertEquals(1, follower.retainedWorkCount, "the ledger bound was exceeded")
            assertFalse(
                pair.follower.player.calls
                    .contains(FakeSyncPlayer.Call.Select(SyncTestValues.hash(2))),
            )
            assertEquals(RETRIES, follower.diagnostics.value.heldCommandCapacityWaitCount, "one wait per retry pass")
            runCurrent()
            assertEquals(RETRIES, follower.diagnostics.value.heldCommandCapacityWaitCount, "retried without time passing: a spin")
            assertTrue(
                pair.followerClock.pendingDeadlines
                    .contains(pair.followerClock.nowUs() + Phase5GateBounds.DEFERRED_RETRY_INTERVAL_US),
                "the drain is not waiting on its retry cadence",
            )
            assertEquals(0, follower.diagnostics.value.workCapacityRefusedCount, "a wait is not a refused admission")

            startGate.complete(Unit)
            runCurrent()
            assertEquals(0, follower.retainedWorkCount, "C0 completed and released its exact reservation")
            pair.followerClock.advanceBy(Phase5GateBounds.DEFERRED_RETRY_INTERVAL_US)
            runCurrent()
            assertTrue(heldAcceptedSeqs(follower).isEmpty())
            assertEquals(2L, field(follower, "lastAppliedSeq"))
            assertEquals(SyncTestValues.hash(2), lastLoad(pair.follower))
            assertEquals(0, follower.retainedWorkCount, "C1 released exactly its own reservation")
            assertEquals(0, (field(follower, "deliveredEffects") as Map<*, *>).size, "delivered metadata outlived its reservation")
        }

    // --- Regression F ----------------------------------------------------------------------------

    /**
     * PROTOCOL §5's existing supersession rule, reached through its real ordering: a `STATE_SNAPSHOT`
     * whose hold check found the stream empty suspends in its own content resolve, and C1 is accepted
     * and held inside that suspension. The snapshot covers C1 by `command_seq`, so C1 leaves by that
     * route — counted as superseded, never retired by ride, never applied twice — and the snapshot's
     * represented state is what `lastAppliedSeq` reports.
     */
    @Test
    fun `an authoritative snapshot covering a held accepted command supersedes it by sequence`() =
        runTest(StandardTestDispatcher()) {
            val pair = SyncPlaybackTwoPeerTest().Pair(this)
            pair.connect()
            pair.seedContent(1..1)
            val hash = SyncTestValues.hash(1)
            val follower = pair.follower.coordinator
            val resolve = CompletableDeferred<Unit>()
            pair.follower.content.resolveGate = resolve
            pair.follower.content.resolveGateWhen = { true }
            val snapshot =
                ResyncMessage.StateSnapshot(
                    leaderPeerId = pair.leader.localPeerId,
                    commandSeq = 1,
                    queueRevision = 0,
                    playback = ResyncPlaybackSnapshot(hash, SyncTestValues.ulid(1), 0, true, pair.leaderClock.nowUs()),
                    queueItems = emptyList(),
                    queueCurrentIndex = null,
                    manifestRevision = 0,
                    transfersInFlight = emptyList(),
                )
            val outcome = async { follower.onStateSnapshot(snapshot, 1, reconciliation = 41) }
            runCurrent()
            assertEquals(0, follower.diagnostics.value.deferredCommandCount, "the snapshot passed its hold check on an empty stream")
            pair.follower.content.resolveGate = null

            pair.follower.session.setClock(UNREADY)
            pair.follower.session.deliver(
                PlaybackMessage.Play(
                    PlaybackCommandHeader(1, pair.leaderClock.nowUs(), pair.leader.localPeerId, 0),
                    hash,
                    0,
                    SyncTestValues.ulid(1),
                ),
            )
            runCurrent()
            assertEquals(listOf(1L), heldAcceptedSeqs(follower), "C1 accepted and held inside the snapshot's suspension")

            resolve.complete(Unit)
            runCurrent()
            outcome.await()
            assertEquals(1, follower.diagnostics.value.supersededHeldCommandCount, "C1 was not superseded by command_seq")
            assertEquals(0, follower.diagnostics.value.retiredRideDeferredCount, "supersession decided by ride")
            assertTrue(heldAcceptedSeqs(follower).isEmpty(), "a superseded command was left blocking the stream")

            pair.follower.session.setClock(READY)
            repeat(4) {
                pair.followerClock.advanceBy(Phase5GateBounds.DEFERRED_RETRY_INTERVAL_US)
                runCurrent()
            }
            assertEquals(hash, follower.diagnostics.value.currentTrackHash, "the snapshot's state is represented")
            assertEquals(0, follower.diagnostics.value.deferredCommandCount)
            assertEquals(
                1,
                pair.follower.player.calls
                    .count { it == FakeSyncPlayer.Call.Select(hash) },
                "C1 applied a second time on top of the snapshot that superseded it",
            )
            assertEquals(1L, field(follower, "lastReceivedSeq"))
            assertEquals(1L, field(follower, "lastAppliedSeq"), "represented authoritative state was not reported as applied")
            assertEquals(1, follower.diagnostics.value.lastAppliedCommandSeq)
        }

    // --- Fresh-fix audit ------------------------------------------------------------------------

    /**
     * Held, in arrival order: accepted C1, then a `STATE_SNAPSHOT`'s queue half and its ride-scoped
     * reconciliation anchor. End Ride keeps C1 and the queue state in order and still cancels the
     * reconciliation — neither "End Ride preserves every deferred event" nor "an old reconciliation
     * survives into Ride 2".
     */
    @Test
    fun `End Ride keeps accepted debt and queue state but cancels the held reconciliation`() =
        runTest(StandardTestDispatcher()) {
            val pair = SyncPlaybackTwoPeerTest().Pair(this)
            pair.connect()
            pair.seedContent(1..1)
            val hash = SyncTestValues.hash(1)
            val follower = pair.follower.coordinator
            val cancelled = mutableListOf<Long>()
            val applied = mutableListOf<Long>()
            follower.onReconciliationCancelled = { obligation, _ -> cancelled.add(obligation) }
            follower.onReconciliationApplied = { obligation, _ -> applied.add(obligation) }
            follower.rideEpochs.next()
            pair.follower.session.setClock(UNREADY)
            pair.leader.coordinator.playSynchronized(hash)
            runCurrent()
            val snapshot =
                ResyncMessage.StateSnapshot(
                    leaderPeerId = pair.leader.localPeerId,
                    commandSeq = 1,
                    queueRevision = pair.leader.coordinator.queueState.value.revision,
                    playback = ResyncPlaybackSnapshot(hash, SyncTestValues.ulid(1), 0, true, pair.leaderClock.nowUs()),
                    queueItems = pair.leader.coordinator.queueState.value.items,
                    queueCurrentIndex = pair.leader.coordinator.queueState.value.currentIndex,
                    manifestRevision = 0,
                    transfersInFlight = emptyList(),
                )
            assertEquals(
                SyncPlaybackCoordinator.StateSnapshotOutcome.DEFERRED_CLOCK,
                follower.onStateSnapshot(snapshot, 1, reconciliation = 77),
                "the snapshot was held behind C1",
            )
            assertEquals(listOf("accepted:1", "queue", "playback:77"), heldKinds(follower))

            follower.endRideSegment(follower.rideEpochs.next())
            assertEquals(
                listOf("accepted:1", "queue"),
                heldKinds(follower),
                "End Ride must keep debt and queue state, in order, and nothing else",
            )
            assertEquals(listOf(77L), cancelled, "the retired reconciliation got its terminal cancellation")
            assertEquals(2, follower.diagnostics.value.deferredCommandCount)

            pair.follower.session.setClock(READY)
            pair.advanceSessionTo(LEADER_START_US + 2_000_000)
            runCurrent()
            assertEquals(1L, field(follower, "lastAppliedSeq"))
            assertTrue(heldKinds(follower).isEmpty())
            assertTrue(applied.isEmpty(), "a reconciliation from the ended ride reported convergence: $applied")
            assertEquals(1L, field(follower, "rideAuthorityEpoch"), "C1 completed as ride-1 work")
        }

    /** Parity pin for the iOS drain-liveness defect this pass fixed: every hold gets the cadence. */
    @Test
    fun `a second clock hold in one session recovers on the retry cadence`() =
        runTest(StandardTestDispatcher()) {
            val pair = SyncPlaybackTwoPeerTest().Pair(this)
            pair.connect()
            pair.seedContent(1..2)
            val follower = pair.follower.coordinator
            for (seq in 1L..2L) {
                pair.follower.session.setClock(UNREADY)
                pair.leader.coordinator.playSynchronized(SyncTestValues.hash(seq.toInt()))
                runCurrent()
                assertEquals(listOf(seq), heldAcceptedSeqs(follower), "C$seq accepted and held")
                pair.follower.session.setClock(READY)
                pair.followerClock.advanceBy(Phase5GateBounds.DEFERRED_RETRY_INTERVAL_US)
                runCurrent()
                assertEquals(seq, field(follower, "lastAppliedSeq"), "C$seq did not recover on the retry cadence")
                assertTrue(heldAcceptedSeqs(follower).isEmpty())
            }
        }

    // --- Helpers ---------------------------------------------------------------------------------

    private fun heldKinds(coordinator: SyncPlaybackCoordinator): List<String> =
        (field(coordinator, "deferredEvents") as Iterable<*>).map { event ->
            requireNotNull(event)

            fun read(name: String) =
                event.javaClass.getDeclaredField(name).let {
                    it.isAccessible = true
                    it.get(event)
                }
            when (event.javaClass.simpleName) {
                "AcceptedCommand" -> "accepted:${read("commandSeq")}"
                "QueueSnapshot" -> "queue"
                "PlaybackState" -> "playback:${read("reconciliation")}"
                else -> event.javaClass.simpleName
            }
        }

    /**
     * The one terminal state ADR-024 Amendment A13 forbids, stated as a predicate: the issuer
     * represented `seq`, the peer took responsibility for it, and the peer holds neither a
     * representation of it nor a retained obligation that could produce one.
     */
    private fun assertNoLeaderAppliedFollowerAcceptedFollowerDiscarded(
        pair: SyncPlaybackTwoPeerTest.Pair,
        seq: Long,
    ) {
        val leaderApplied = (field(pair.leader.coordinator, "lastAppliedSeq") as Long?) ?: 0
        val followerReceived = (field(pair.follower.coordinator, "lastReceivedSeq") as Long?) ?: 0
        val followerApplied = (field(pair.follower.coordinator, "lastAppliedSeq") as Long?) ?: 0
        val stillOwed = seq in heldAcceptedSeqs(pair.follower.coordinator)
        val discarded = leaderApplied >= seq && followerReceived >= seq && followerApplied < seq && !stillOwed
        assertFalse(discarded, "L applied C$seq, F accepted C$seq, and F discarded C$seq")
    }

    /** The `command_seq` of every accepted command still held, read through the retained type itself. */
    private fun heldAcceptedSeqs(coordinator: SyncPlaybackCoordinator): List<Long> =
        (field(coordinator, "deferredEvents") as Iterable<*>).mapNotNull { event ->
            event
                ?.takeIf { it.javaClass.simpleName == "AcceptedCommand" }
                ?.let { accepted ->
                    accepted.javaClass.getDeclaredField("commandSeq").let {
                        it.isAccessible = true
                        it.get(accepted) as Long
                    }
                }
        }

    private fun lastLoad(peer: SyncPlaybackTwoPeerTest.Peer) =
        peer.player.calls
            .filterIsInstance<FakeSyncPlayer.Call.Load>()
            .lastOrNull()
            ?.contentHash

    private fun assertMatchingTimelines(
        leader: SyncPlaybackCoordinator,
        follower: SyncPlaybackCoordinator,
    ) {
        val leaderTimeline = field(leader, "timeline") as PlaybackTimeline
        val followerTimeline = field(follower, "timeline") as PlaybackTimeline
        // The playback generation is a local token; every authoritative field must agree.
        assertEquals(leaderTimeline.copy(generation = 0), followerTimeline.copy(generation = 0))
    }

    /** Read-only invariant inspection without adding production APIs solely for tests. */
    private fun field(
        coordinator: SyncPlaybackCoordinator,
        name: String,
    ): Any? =
        SyncPlaybackCoordinator::class.java.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(coordinator)
        }

    private companion object {
        const val RETRIES = 6
        val READY = SessionClockEstimate(offsetToLeaderUs = OFFSET_US, rttP95Us = 8_000, ready = true)
        val UNREADY = SessionClockEstimate(offsetToLeaderUs = OFFSET_US, rttP95Us = 8_000, ready = false)
    }
}
