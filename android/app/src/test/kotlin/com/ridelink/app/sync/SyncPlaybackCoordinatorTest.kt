package com.ridelink.app.sync

import com.ridelink.core.model.SessionId
import com.ridelink.core.playback.DriftController
import com.ridelink.core.playback.PlaybackBounds
import com.ridelink.core.playback.PlaybackCommandHeader
import com.ridelink.core.playback.PlaybackMessage
import com.ridelink.core.playback.PlaybackRole
import com.ridelink.core.playback.QueueAddItem
import com.ridelink.core.playback.QueueCommandHeader
import com.ridelink.core.playback.QueueMessage
import com.ridelink.core.sync.SessionClock
import com.ridelink.core.sync.SessionClockEstimate
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.control.LinkLossReason
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
 * Coordinator-level Phase 5 behaviour: ordering, the intent hop, scheduling, the availability gate,
 * session binding and playback-epoch binding.
 *
 * **Deterministic throughout.** [FakeMonotonicClock] is the only clock, [StandardTestDispatcher]
 * decides ordering, and nothing sleeps — so a failure here is always a statement about the
 * algorithm, never about how busy the machine was (this phase's brief §47/§50).
 *
 * The pure tables these tests drive (`CommandOrderGate`, `DriftController`, `SharedQueue`,
 * `SessionClock`) are pinned separately and identically on both platforms by
 * `protocol/vectors/`. What is asserted *here* is the wiring: that the coordinator consults them,
 * in the right order, and honours the answer.
 */
class SyncPlaybackCoordinatorTest {
    private lateinit var session: FakeSyncSession
    private lateinit var player: FakeSyncPlayer
    private lateinit var content: FakeSyncContent
    private lateinit var clock: FakeMonotonicClock
    private lateinit var coordinator: SyncPlaybackCoordinator
    private var routeTransitioning = false
    private var idSeed = 100

    /**
     * [TestScope.backgroundScope], not the test scope itself: the coordinator's position-report tick
     * loop is deliberately endless for the life of a session, and `runTest` waits for every child of
     * its own scope. The background scope is what kotlinx-coroutines-test provides for exactly this
     * — it shares the scheduler, so `runCurrent()` still drives it deterministically, and it is
     * cancelled when the test ends.
     */
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
                localPeerId = SyncTestValues.leaderPeerId,
                session = session,
                player = player,
                content = content,
                sleeper = clock.sleeper,
                routeTransitioning = { routeTransitioning },
                nextQueueItemId = { SyncTestValues.ulid(idSeed++) },
            )
    }

    private suspend fun connect(
        scope: TestScope,
        asLeader: Boolean,
        clockReady: Boolean = true,
    ) {
        // The coordinator subscribes to `events` from a coroutine launched in its own `init`. Under
        // StandardTestDispatcher that coroutine has not started yet, and a `MutableSharedFlow` with
        // no subscriber drops what is emitted to it — so the subscription has to be let run first.
        scope.runCurrent()
        session.setClock(SessionClockEstimate(offsetToLeaderUs = 0, rttP95Us = 8_000, ready = clockReady))
        session.emit(ControlEvent.Connected(SyncTestValues.followerPeerId, SessionId("S"), asLeader))
        scope.runCurrent()
        // Establishing a session legitimately restores the rate to exactly 1.0 (brief §38) — real
        // behaviour, asserted on its own in the link-loss test below. Cleared here so the
        // scheduling tests can assert the *exact* call sequence a command produces.
        player.calls.clear()
    }

    private fun readyEstimateLead(): Long = SessionClock.leadUs(8_000)

    // --- role and clock readiness -------------------------------------------------------------

    @Test
    fun `the role comes from ADR-010's election, not from who dialled`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false)
            assertEquals(PlaybackRole.FOLLOWER, coordinator.diagnostics.value.role)
            connect(this, asLeader = true)
            assertEquals(PlaybackRole.LEADER, coordinator.diagnostics.value.role)
        }

    @Test
    fun `a leader with an unready clock issues nothing and says so`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = true, clockReady = false)
            content.localHashes.add(SyncTestValues.hash(1).value)
            content.peerHashes.add(SyncTestValues.hash(1).value)
            coordinator.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            assertTrue(session.sent.none { it is PlaybackMessage }, "no command may be scheduled against a dubious clock")
            assertEquals(SyncState.CLOCK_UNREADY, coordinator.diagnostics.value.syncState)
        }

    // --- the availability gate (brief §19, REQUIREMENTS §9.4) ----------------------------------

    @Test
    fun `a track the peer lacks cannot begin synchronized playback`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = true)
            content.localHashes.add(SyncTestValues.hash(1).value)
            coordinator.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            assertTrue(session.sent.none { it is PlaybackMessage }, "a remote-only track must not start")
            assertEquals(SyncState.WAITING_FOR_CONTENT, coordinator.diagnostics.value.syncState)
        }

    @Test
    fun `a track this device lacks requests the transfer through the existing Phase 4 machinery`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = true)
            content.peerHashes.add(SyncTestValues.hash(1).value)
            coordinator.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            assertEquals(listOf(SyncTestValues.hash(1)), content.transferRequests)
            assertTrue(player.calls.isEmpty(), "nothing may be prepared for content that is not here")
        }

    @Test
    fun `a PLAY for content this device lacks does not start and requests the transfer`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false)
            session.deliver(playCommand(seq = 1, effectiveAt = clock.nowUs() + 200_000))
            runCurrent()
            assertTrue(player.calls.isEmpty(), "PROTOCOL §5 rule 4: never start a track that is not present")
            assertEquals(listOf(SyncTestValues.hash(1)), content.transferRequests)
        }

    // --- scheduling (PROTOCOL §5 rule 2, ARCHITECTURE §7.2) -------------------------------------

    @Test
    fun `a future deadline pre-rolls now and starts exactly at the deadline`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false)
            content.localHashes.add(SyncTestValues.hash(1).value)
            val effectiveAt = clock.nowUs() + 500_000
            session.deliver(playCommand(seq = 1, effectiveAt = effectiveAt))
            runCurrent()
            assertEquals(FakeSyncPlayer.preRoll(SyncTestValues.hash(1), 0), player.calls.toList())
            // The 5 s position-report tick is also waiting; what matters is that the command's own
            // deadline is among them and is the future instant the leader chose.
            assertTrue(clock.pendingDeadlines.contains(effectiveAt), "the command waits for its own deadline")

            clock.advanceTo(effectiveAt - 1)
            runCurrent()
            assertEquals(
                FakeSyncPlayer.preRoll(SyncTestValues.hash(1), 0),
                player.calls.toList(),
                "nothing may start before the deadline",
            )

            clock.advanceTo(effectiveAt)
            runCurrent()
            assertEquals(FakeSyncPlayer.Call.Start, player.calls.last())
            assertEquals(SyncState.SYNCED, coordinator.diagnostics.value.syncState)
        }

    @Test
    fun `a deadline already past applies immediately and counts the lateness`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false)
            content.localHashes.add(SyncTestValues.hash(1).value)
            session.deliver(playCommand(seq = 1, effectiveAt = clock.nowUs() - 250_000))
            runCurrent()
            assertTrue(player.calls.contains(FakeSyncPlayer.Call.Start), "a late command applies, it is never skipped")
            assertEquals(1, coordinator.diagnostics.value.lateCommandCount)
            assertEquals(250_000, coordinator.diagnostics.value.lastScheduleErrorUs)
            assertTrue(
                clock.pendingDeadlines.none { it <= clock.nowUs() },
                "PROTOCOL §5 rule 2: never schedule into the past — the only wait outstanding is the 5 s report tick",
            )
        }

    // --- ordering (PROTOCOL §2.1/§5) -----------------------------------------------------------

    @Test
    fun `duplicate and stale command_seq are dropped and counted`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false)
            content.localHashes.add(SyncTestValues.hash(1).value)
            val at = clock.nowUs() + 100_000
            session.deliver(playCommand(seq = 5, effectiveAt = at))
            runCurrent()
            val afterFirst = player.calls.size

            session.deliver(playCommand(seq = 5, effectiveAt = at))
            session.deliver(playCommand(seq = 4, effectiveAt = at))
            runCurrent()
            assertEquals(afterFirst, player.calls.size, "neither a duplicate nor a stale command may touch the player")
            assertEquals(1, coordinator.diagnostics.value.duplicateCommandCount)
            assertEquals(1, coordinator.diagnostics.value.staleCommandCount)
            assertEquals(5, coordinator.diagnostics.value.lastAppliedCommandSeq)
        }

    @Test
    fun `a follower refuses an intent, and a leader refuses an authoritative command`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false)
            content.localHashes.add(SyncTestValues.hash(1).value)
            session.deliver(playCommand(seq = PlaybackBounds.UNASSIGNED_COMMAND_SEQ, effectiveAt = clock.nowUs()))
            runCurrent()
            assertEquals(1, coordinator.diagnostics.value.roleViolationCount)
            assertTrue(player.calls.isEmpty())

            build(backgroundScope)
            connect(this, asLeader = true)
            content.localHashes.add(SyncTestValues.hash(1).value)
            content.peerHashes.add(SyncTestValues.hash(1).value)
            session.deliver(playCommand(seq = 9, effectiveAt = clock.nowUs()))
            runCurrent()
            assertEquals(1, coordinator.diagnostics.value.roleViolationCount, "a follower cannot fabricate an authoritative command_seq")
            assertTrue(player.calls.isEmpty())
        }

    // --- the intent hop (ADR-010, ADR-024 §3) ---------------------------------------------------

    @Test
    fun `a follower sends an intent with command_seq zero and never allocates one`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false)
            coordinator.pause()
            runCurrent()
            val sent = session.sentOfType<PlaybackMessage.Pause>().single()
            assertEquals(PlaybackBounds.UNASSIGNED_COMMAND_SEQ, sent.header.commandSeq)
            assertEquals(0, sent.header.effectiveAtSessionUs, "a follower has no authority to choose an audible instant")
            assertTrue(player.calls.isEmpty(), "a follower changes no audio until the leader's broadcast returns")
        }

    @Test
    fun `the leader stamps a follower intent and broadcasts it authoritatively`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = true)
            content.localHashes.add(SyncTestValues.hash(1).value)
            content.peerHashes.add(SyncTestValues.hash(1).value)
            session.deliver(intentPause())
            runCurrent()
            val stamped = session.sentOfType<PlaybackMessage.Pause>().single()
            assertEquals(PlaybackBounds.FIRST_COMMAND_SEQ, stamped.header.commandSeq)
            assertEquals(clock.nowUs() + readyEstimateLead(), stamped.header.effectiveAtSessionUs)
            assertEquals(SyncTestValues.leaderPeerId, stamped.header.issuedBy)
        }

    @Test
    fun `two simultaneous follower intents receive consecutive sequence numbers`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = true)
            session.deliver(intentPause())
            session.deliver(intentPause())
            runCurrent()
            val seqs = session.sentOfType<PlaybackMessage.Pause>().map { it.header.commandSeq }
            assertEquals(listOf(1L, 2L), seqs, "the leader's arrival order is the only thing that decides")
        }

    // --- the shared queue ------------------------------------------------------------------------

    @Test
    fun `the leader broadcasts a snapshot after every accepted mutation and never an empty one`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = true)
            coordinator.enqueue(SyncTestValues.hash(1))
            runCurrent()
            val snapshot = session.sentOfType<QueueMessage.Snapshot>().last()
            assertEquals(1, snapshot.queueRevision)
            assertEquals(1, snapshot.items.size)

            // A duplicate add of the *same* queue_item_id would be idempotent; a fresh id is a new
            // entry, so the revision advances again.
            coordinator.enqueue(SyncTestValues.hash(1))
            runCurrent()
            assertEquals(2, session.sentOfType<QueueMessage.Snapshot>().last().queueRevision)
            assertEquals(2, coordinator.queueState.value.items.size, "the same track twice is two independent entries")
        }

    @Test
    fun `a follower adopts the snapshot wholesale and never increments a revision itself`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false)
            coordinator.enqueue(SyncTestValues.hash(1))
            runCurrent()
            assertEquals(0, coordinator.queueState.value.revision, "a follower's own add is only an intent")
            assertEquals(1, session.sentOfType<QueueMessage.Add>().size)

            session.deliver(
                QueueMessage.Snapshot(
                    queueRevision = 42,
                    items =
                        listOf(
                            com.ridelink.core.playback.SharedQueueItem(
                                SyncTestValues.ulid(1),
                                SyncTestValues.hash(1),
                                SyncTestValues.leaderPeerId,
                                1024,
                            ),
                        ),
                    currentIndex = 0,
                ),
            )
            runCurrent()
            assertEquals(42, coordinator.queueState.value.revision)
            assertEquals(SyncTestValues.ulid(1), coordinator.queueState.value.currentItemId)
        }

    @Test
    fun `a stale-revision intent is refused and answered with a fresh snapshot`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = true)
            coordinator.enqueue(SyncTestValues.hash(1))
            runCurrent()
            val before = session.sentOfType<QueueMessage.Snapshot>().size

            session.deliver(
                QueueMessage.Add(
                    QueueCommandHeader(PlaybackBounds.UNASSIGNED_COMMAND_SEQ, queueRevision = 0),
                    listOf(
                        QueueAddItem(
                            SyncTestValues.ulid(7),
                            SyncTestValues.hash(2),
                            SyncTestValues.followerPeerId,
                            PlaybackBounds.QUEUE_POSITION_END,
                        ),
                    ),
                ),
            )
            runCurrent()
            assertEquals(1, coordinator.diagnostics.value.staleRevisionCount)
            assertEquals(
                before + 1,
                session.sentOfType<QueueMessage.Snapshot>().size,
                "the leader re-broadcasts rather than waiting to be asked",
            )
            assertEquals(1, coordinator.queueState.value.items.size, "the stale mutation was not applied")
        }

    // --- session binding (ADR-023 §3's lesson) --------------------------------------------------

    @Test
    fun `a command dispatched under a session that has since ended is inert`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false)
            content.localHashes.add(SyncTestValues.hash(1).value)
            // Dispatched under generation 1 …
            session.deliver(playCommand(seq = 1, effectiveAt = clock.nowUs() + 100_000))
            // … but the session ends before the launched handler ever runs.
            session.currentAuthGeneration = 2
            runCurrent()
            assertTrue(player.calls.isEmpty(), "an old session's command may never touch the new session's player")
        }

    @Test
    fun `a session boundary landing inside content resolution stops the command`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false)
            content.localHashes.add(SyncTestValues.hash(1).value)
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            content.resolveGate = gate
            session.deliver(playCommand(seq = 1, effectiveAt = clock.nowUs() + 100_000))
            runCurrent()
            assertTrue(player.calls.isEmpty(), "still suspended inside resolve")

            // The boundary happens *inside* the suspension — the exact shape ADR-023 Amendment A3
            // found in Phase 4, where a check at handler entry proved nothing about what came after.
            session.currentAuthGeneration = 2
            gate.complete(Unit)
            runCurrent()
            assertTrue(player.calls.isEmpty(), "the re-check after the suspension is what stops it")
        }

    @Test
    fun `a link loss restores the rate to exactly one and stops correcting`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false)
            content.localHashes.add(SyncTestValues.hash(1).value)
            session.deliver(playCommand(seq = 1, effectiveAt = clock.nowUs()))
            runCurrent()
            player.calls.clear()

            session.emit(ControlEvent.LinkLost(LinkLossReason.NETWORK))
            runCurrent()
            assertEquals(FakeSyncPlayer.Call.SetRate(DriftController.RATE_NORMAL), player.calls.last())
            assertEquals(SyncState.INACTIVE, coordinator.diagnostics.value.syncState)
            assertNull(coordinator.diagnostics.value.role)
            assertFalse(coordinator.isSynchronizedModeActive())
        }

    @Test
    fun `a scheduled start belonging to a superseded epoch never fires`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false)
            content.localHashes.add(SyncTestValues.hash(1).value)
            content.localHashes.add(SyncTestValues.hash(2).value)
            val firstAt = clock.nowUs() + 400_000
            session.deliver(playCommand(seq = 1, effectiveAt = firstAt, seed = 1))
            runCurrent()

            // A second PLAY supersedes the first before its deadline arrives.
            val secondAt = clock.nowUs() + 600_000
            session.deliver(playCommand(seq = 2, effectiveAt = secondAt, seed = 2))
            runCurrent()

            clock.advanceTo(firstAt)
            runCurrent()
            assertFalse(player.calls.contains(FakeSyncPlayer.Call.Start), "track A's timer must not start track B")

            clock.advanceTo(secondAt)
            runCurrent()
            assertEquals(FakeSyncPlayer.Call.Start, player.calls.last())
            assertEquals(SyncTestValues.hash(2), coordinator.diagnostics.value.currentTrackHash)
        }

    // --- helpers ----------------------------------------------------------------------------------

    private fun playCommand(
        seq: Long,
        effectiveAt: Long,
        seed: Int = 1,
    ) = PlaybackMessage.Play(
        PlaybackCommandHeader(seq, effectiveAt, SyncTestValues.leaderPeerId, queueRevision = 0),
        SyncTestValues.hash(seed),
        positionMs = 0,
        queueItemId = SyncTestValues.ulid(seed),
    )

    private fun intentPause() =
        PlaybackMessage.Pause(
            PlaybackCommandHeader(PlaybackBounds.UNASSIGNED_COMMAND_SEQ, 0, SyncTestValues.followerPeerId, queueRevision = 0),
            positionMs = 1_000,
        )
}
