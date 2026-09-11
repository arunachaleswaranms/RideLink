package com.ridelink.app.sync

import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.SessionId
import com.ridelink.core.playback.PlaybackBounds
import com.ridelink.core.playback.PlaybackCommandHeader
import com.ridelink.core.playback.PlaybackMessage
import com.ridelink.core.playback.QueueMessage
import com.ridelink.core.playback.SharedQueueItem
import com.ridelink.core.player.PlayerState
import com.ridelink.core.sync.SessionClockEstimate
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.control.LinkLossReason
import kotlinx.coroutines.CompletableDeferred
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
 * The five regressions ADR-024 **Amendment A2** — the second Phase 5 closure audit — exists for.
 *
 * A1 made the leader's semantic order the wire-visible order and made the inbound handoff lossless.
 * A2 is about the join A1 left open: **being on the outbound queue is not being on the wire, and
 * being on the wire is not the same session's wire.** Each test below fails on the code as A1 left
 * it and passes as amended, and the comment on each names the exact defect it pins.
 *
 * **Deterministic throughout.** [FakeMonotonicClock] is the only clock, [StandardTestDispatcher]
 * decides ordering, the outbound bound is *injected* rather than raced, and
 * [FakeSyncSession.sendGate] parks the single outbound consumer strictly inside a write so a session
 * boundary lands between one frame reaching the wire and the next being considered. Nothing here
 * sleeps and nothing yields a fixed number of times.
 *
 * The mirror is `RideLinkPlatformTests.SyncPlaybackDeliveryAuditTests`.
 */
@Suppress("LargeClass")
class SyncPlaybackDeliveryAuditTest {
    private lateinit var session: FakeSyncSession
    private lateinit var player: FakeSyncPlayer
    private lateinit var content: FakeSyncContent
    private lateinit var clock: FakeMonotonicClock
    private lateinit var coordinator: SyncPlaybackCoordinator
    private var idSeed = 700

    private fun build(
        scope: CoroutineScope,
        outboundCapacity: Int = 256,
        deferredCommandCapacity: Int = 16,
    ) {
        idSeed += 10
        session = FakeSyncSession()
        player = FakeSyncPlayer()
        content = FakeSyncContent()
        clock = FakeMonotonicClock()
        coordinator =
            SyncPlaybackCoordinator(
                scope = scope,
                monotonicNowUs = { clock.nowUs() },
                localPeerId = SyncTestValues.followerPeerId,
                session = session,
                player = player,
                content = content,
                sleeper = clock.sleeper,
                routeTransitioning = { false },
                nextQueueItemId = { SyncTestValues.ulid(idSeed++) },
                deferredCommandCapacity = deferredCommandCapacity,
                outboundCapacity = outboundCapacity,
            )
    }

    private suspend fun connect(
        scope: TestScope,
        asLeader: Boolean,
        clockReady: Boolean = true,
    ) {
        scope.runCurrent()
        session.setClock(SessionClockEstimate(offsetToLeaderUs = 0, rttP95Us = 8_000, ready = clockReady))
        session.emit(
            ControlEvent.Connected(
                if (asLeader) SyncTestValues.followerPeerId else SyncTestValues.leaderPeerId,
                SessionId("S"),
                asLeader,
            ),
        )
        scope.runCurrent()
        player.calls.clear()
        session.sent.clear()
        session.sentGenerations.clear()
    }

    /** A leader with one track queued, playing, and every frame so far actually on the wire. */
    private suspend fun leaderPlaying(scope: TestScope) {
        connect(scope, asLeader = true)
        content.localHashes.add(HASH_A.value)
        content.peerHashes.add(HASH_A.value)
        coordinator.playSynchronized(HASH_A)
        scope.runCurrent()
        clock.advanceBy(LEAD_US * 2)
        scope.runCurrent()
        player.calls.clear()
        session.sent.clear()
        session.sentGenerations.clear()
    }

    /**
     * Parks the outbound consumer inside a write and then fills every queue slot behind it, so the
     * **next** authoritative operation meets a genuinely full outbound path. Two slots is
     * [WEDGE_CAPACITY], injected rather than raced: the production bound is 256 and filling it for
     * real would be a timing test rather than a semantic one.
     *
     * @return the gate holding the consumer, for the test to release.
     */
    private suspend fun wedgeOutbound(scope: TestScope): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        session.sendGate = gate
        coordinator.pause()
        scope.runCurrent() // PAUSE is taken by the consumer and parks inside the write.
        repeat(WEDGE_CAPACITY) { index ->
            coordinator.seek(1_000L * (index + 1))
            scope.runCurrent()
        }
        return gate
    }

    /** Puts this device's own drift into ADR-004's hard-seek band: 120 ms < |drift| <= 2 s. */
    private fun driftIntoHardSeekBand() {
        // `leaderPlaying` anchors position 0 at `t0 + LEAD`; one cadence tick later the expected
        // position is exactly (elapsed - LEAD) ms, so reporting 500 ms past it is a hard seek and
        // nothing else on the ladder.
        val expectedMs = (clock.nowUs() + POSITION_REPORT_US - ANCHOR_SESSION_US) / 1_000
        player.setState(
            PlayerState(positionMs = expectedMs + 500, durationMs = 3_600_000, playing = true, rate = 1.0),
        )
    }

    // --- Finding A: admission is not delivery ----------------------------------------------------

    /**
     * **The defect.** `enqueueOutbound` returned `Unit`. A full outbound queue incremented
     * `outboundOverflowCount` and returned, and `issue` carried straight on to consume the
     * `command_seq`, record it as applied and schedule the audible effect. The leader played a
     * command the follower had no way of ever receiving — silent divergence, reported as a counter
     * nobody reads.
     */
    @Test
    fun `an authoritative command refused by the outbound path is never applied locally`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope, outboundCapacity = WEDGE_CAPACITY)
            leaderPlaying(this)
            val seqBeforeWedge = coordinator.diagnostics.value.nextCommandSeq
            val gate = wedgeOutbound(this)
            val seqAfterWedge = coordinator.diagnostics.value.nextCommandSeq
            assertTrue(seqAfterWedge!! > seqBeforeWedge!!, "the wedging commands were admitted and did consume seqs")
            val appliedBefore = coordinator.diagnostics.value.lastAppliedCommandSeq

            coordinator.next()
            runCurrent()

            val diagnostics = coordinator.diagnostics.value
            assertEquals(1, diagnostics.outboundOverflowCount, "the refusal is counted, exactly once")
            assertEquals(seqAfterWedge, diagnostics.nextCommandSeq, "a refused candidate consumes no command_seq")
            assertEquals(appliedBefore, diagnostics.lastAppliedCommandSeq, "nothing was applied for a frame never admitted")
            // The queue holds one item, so the refused NEXT would have stepped past its end and
            // stopped the player. The only player call the fail-closed path itself makes is the
            // ADR-004 restore to exactly 1.0.
            assertTrue(
                player.calls.all { it == FakeSyncPlayer.Call.SetRate(1.0) },
                "nothing became audible for a frame that was never admitted",
            )
            assertTrue(diagnostics.outboundAuthorityLost, "the failure is explicit, not a statistic")
            assertEquals(SyncState.TRANSPORT_FAILED, diagnostics.syncState)
            assertFalse(coordinator.isSynchronizedModeActive(), "transport control returns to Phase 3 rather than dying")
            gate.complete(Unit)
        }

    /**
     * The same defect on the queue half: `_queueState` was published and the revision bumped inside
     * the critical section that enqueued the snapshot, whether or not the snapshot was admitted. The
     * leader would then sit on revision *n* while the follower could never learn it, and every
     * subsequent command stamped for *n* would be refused by the follower's own §5 rule 3 check.
     */
    @Test
    fun `a queue snapshot refused by the outbound path never bumps the authoritative revision`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope, outboundCapacity = WEDGE_CAPACITY)
            leaderPlaying(this)
            val revisionBefore = coordinator.queueState.value.revision
            val sizeBefore = coordinator.queueState.value.items.size
            val gate = wedgeOutbound(this)

            coordinator.enqueue(HASH_B)
            runCurrent()

            assertEquals(revisionBefore, coordinator.queueState.value.revision, "the revision did not move")
            assertEquals(sizeBefore, coordinator.queueState.value.items.size, "and neither did the queue")
            assertEquals(revisionBefore, coordinator.diagnostics.value.queueRevision)
            assertEquals(1, coordinator.diagnostics.value.outboundOverflowCount)
            assertTrue(coordinator.diagnostics.value.outboundAuthorityLost)
            gate.complete(Unit)
        }

    /**
     * What happens when the wedge clears. A frame the transport **did** accept commits and takes
     * effect — the peer has it, so refusing to apply it here would be the mirror image of the
     * divergence this amendment closes. Everything queued behind the failure does not.
     */
    @Test
    fun `after a refusal the delivered frame still applies and nothing behind it does`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope, outboundCapacity = WEDGE_CAPACITY)
            leaderPlaying(this)
            val gate = wedgeOutbound(this)
            val pauseSeq = session.sentOfType<PlaybackMessage.Pause>().firstOrNull()

            coordinator.next()
            runCurrent()
            assertTrue(coordinator.diagnostics.value.outboundAuthorityLost)
            assertNull(pauseSeq, "the PAUSE is still inside the write, so it has not reached the wire yet")

            gate.complete(Unit)
            clock.advanceBy(LEAD_US * 4)
            runCurrent()

            assertEquals(1, session.sentOfType<PlaybackMessage.Pause>().size, "the accepted write completed")
            assertTrue(session.sentOfType<PlaybackMessage.Seek>().isEmpty(), "the frame behind the failure was suppressed")
            assertEquals(1, player.calls.count { it == FakeSyncPlayer.Call.Pause }, "the delivered PAUSE did take effect")
            // A `PAUSE` legitimately seeks to its own `position_ms`; what must not appear is either
            // of the wedge's undelivered SEEK targets.
            assertTrue(
                player.calls.none { it is FakeSyncPlayer.Call.Seek && it.positionMs in setOf(1_000L, 2_000L) },
                "the undelivered SEEKs did not",
            )
            assertEquals(SyncState.TRANSPORT_FAILED, coordinator.diagnostics.value.syncState)

            // And no further authority is issued under this generation, by either user's action.
            val sentBefore = session.sent.size
            coordinator.pause()
            coordinator.enqueue(HASH_C)
            coordinator.playSynchronized(HASH_A)
            runCurrent()
            assertEquals(sentBefore, session.sent.size, "a fail-closed generation issues nothing further")
        }

    /** A new session clears the latch outright: recovery is a fresh generation, never a retry. */
    @Test
    fun `a fresh session clears the fail-closed latch and works normally`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope, outboundCapacity = WEDGE_CAPACITY)
            leaderPlaying(this)
            val gate = wedgeOutbound(this)
            coordinator.next()
            runCurrent()
            assertTrue(coordinator.diagnostics.value.outboundAuthorityLost)
            gate.complete(Unit)
            runCurrent()

            session.sendGate = null
            session.currentAuthGeneration = 2
            connect(this, asLeader = true)

            assertFalse(coordinator.diagnostics.value.outboundAuthorityLost, "the latch is scoped to its generation")
            content.localHashes.add(HASH_A.value)
            content.peerHashes.add(HASH_A.value)
            coordinator.playSynchronized(HASH_A)
            runCurrent()
            assertEquals(1, session.sentOfType<PlaybackMessage.Play>().size, "the new session is authoritative again")
        }

    /**
     * A follower's intent owns no authority, so a refusal is a button press that did not happen —
     * counted, never a fail-closed halt, and never a local rollback of state it never owned.
     */
    @Test
    fun `a refused follower intent is counted but never fails the session closed`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope, outboundCapacity = WEDGE_CAPACITY)
            connect(this, asLeader = false)
            val gate = wedgeOutbound(this)

            coordinator.next()
            runCurrent()

            val diagnostics = coordinator.diagnostics.value
            assertEquals(1, diagnostics.outboundOverflowCount)
            assertFalse(diagnostics.outboundAuthorityLost, "a follower never owned authority to fail closed on")
            assertTrue(diagnostics.syncState != SyncState.TRANSPORT_FAILED)
            gate.complete(Unit)
        }

    // --- Finding B: an outbound frame belongs to the session that authorised it -------------------

    /**
     * **The defect.** The outbound envelope was the bare message. The queue deliberately outlives
     * sessions, and `PlaybackRelay.send` resolves the authenticated writer **and the `session_id`**
     * at send time — so a frame stamped under Session A that was still queued when Session B
     * activated was written under Session B's identity. That is precisely the session-confusion
     * class ADR-023 Amendments A3/A5 hardened Phase 4 against, on the outbound end of the pipe.
     */
    @Test
    fun `a command authorised by a dead session is never written under the session that replaced it`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            leaderPlaying(this)
            val sentBefore = coordinator.diagnostics.value.outboundSentCount
            val gate = CompletableDeferred<Unit>()
            session.sendGate = gate

            coordinator.pause() // enters the write and parks
            runCurrent()
            coordinator.seek(45_000) // queues behind it, authorised by generation 1
            runCurrent()
            assertTrue(session.sent.isEmpty(), "nothing has reached the wire yet")

            // Session A dies and Session B authenticates while the backlog is still queued.
            session.emit(ControlEvent.LinkLost(LinkLossReason.NETWORK))
            runCurrent()
            session.currentAuthGeneration = 2
            connect(this, asLeader = true)

            gate.complete(Unit)
            runCurrent()

            assertTrue(session.sentOfType<PlaybackMessage.Seek>().isEmpty(), "Session A's SEEK never reached Session B")
            assertTrue(session.sentOfType<PlaybackMessage.Pause>().isEmpty(), "nor did Session A's PAUSE")
            assertEquals(sentBefore, coordinator.diagnostics.value.outboundSentCount, "and neither was counted as sent")
            assertEquals(
                1,
                coordinator.diagnostics.value.outboundStaleCount,
                "the frame still queued at the boundary was refused before the write was even attempted",
            )
            assertEquals(
                1,
                coordinator.diagnostics.value.outboundFailedCount,
                "and the one already inside the write was refused by the relay's own generation check",
            )

            // A legitimate Session B command sends normally.
            content.localHashes.add(HASH_B.value)
            content.peerHashes.add(HASH_B.value)
            coordinator.playSynchronized(HASH_B)
            runCurrent()
            val plays = session.sentOfType<PlaybackMessage.Play>()
            assertEquals(1, plays.size, "Session B is authoritative in its own right")
            assertTrue(
                session.sentGenerations.isNotEmpty() && session.sentGenerations.all { it == 2L },
                "and everything written since the boundary was written under generation 2",
            )
        }

    /** The same for authoritative queue state: a Session A snapshot may not reach Session B. */
    @Test
    fun `a queue snapshot authorised by a dead session is never written under the session that replaced it`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            leaderPlaying(this)
            val sentBefore = coordinator.diagnostics.value.outboundSentCount
            val gate = CompletableDeferred<Unit>()
            session.sendGate = gate

            coordinator.pause()
            runCurrent()
            coordinator.enqueue(HASH_B) // a QUEUE_SNAPSHOT authorised by generation 1
            runCurrent()

            session.emit(ControlEvent.LinkLost(LinkLossReason.NETWORK))
            runCurrent()
            session.currentAuthGeneration = 2
            connect(this, asLeader = true)
            gate.complete(Unit)
            runCurrent()

            assertTrue(session.sentOfType<QueueMessage.Snapshot>().isEmpty(), "no Session A snapshot under Session B")
            assertEquals(sentBefore, coordinator.diagnostics.value.outboundSentCount, "and nothing new was counted as sent")
            assertEquals(0, coordinator.queueState.value.revision, "and Session B started from an empty authoritative queue")
        }

    // --- Finding C: the transport's answer is the only definition of "sent" ----------------------

    /**
     * **The defect.** The drain did `session.playback.send(frame); outboundSentCount += 1` — the
     * `Boolean` result discarded. `PlaybackRelay.send` answers false when there is no authenticated
     * writer or the write throws, so `outboundSentCount == outboundEnqueuedCount` could be reported
     * while frames had been thrown away, and the leader had already committed the command locally.
     */
    @Test
    fun `a transport write that returns false is not a send and commits nothing`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            leaderPlaying(this)
            val appliedBefore = coordinator.diagnostics.value.lastAppliedCommandSeq
            val sentBefore = coordinator.diagnostics.value.outboundSentCount
            val failedBefore = coordinator.diagnostics.value.outboundFailedCount
            session.sendResult = false

            coordinator.pause()
            clock.advanceBy(LEAD_US * 4)
            runCurrent()

            val diagnostics = coordinator.diagnostics.value
            assertEquals(sentBefore, diagnostics.outboundSentCount, "a write that returned false is not a send")
            assertEquals(failedBefore + 1, diagnostics.outboundFailedCount, "it is counted as what it was")
            assertEquals(appliedBefore, diagnostics.lastAppliedCommandSeq, "and nothing was committed for it")
            assertTrue(player.calls.none { it == FakeSyncPlayer.Call.Pause }, "the leader did not pause a peer that never heard")
            assertTrue(diagnostics.outboundAuthorityLost)
            assertEquals(SyncState.TRANSPORT_FAILED, diagnostics.syncState)
            assertEquals(
                diagnostics.outboundSentCount + diagnostics.outboundFailedCount + diagnostics.outboundStaleCount,
                diagnostics.outboundAttemptCount,
                "the three outcomes account for every attempt",
            )
        }

    /** The queue half: a snapshot the transport refused may not leave the leader silently ahead. */
    @Test
    fun `a queue snapshot the transport refused fails the session closed rather than diverging`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            leaderPlaying(this)
            val sentBefore = coordinator.diagnostics.value.outboundSentCount
            session.sendResult = false

            coordinator.enqueue(HASH_B)
            runCurrent()

            val diagnostics = coordinator.diagnostics.value
            assertEquals(1, diagnostics.outboundFailedCount)
            assertEquals(sentBefore, diagnostics.outboundSentCount)
            assertTrue(diagnostics.outboundAuthorityLost, "the divergence is surfaced, never silent")
            assertEquals(SyncState.TRANSPORT_FAILED, diagnostics.syncState)
            assertFalse(coordinator.isSynchronizedModeActive())
        }

    // --- Finding D: nothing overtakes a held authoritative command --------------------------------

    /**
     * **The defect.** A1 held a command whose clock was untrusted, but held nothing else. A
     * `QUEUE_SNAPSHOT` arriving behind a held `NEXT` was applied **immediately**, so when the clock
     * recovered the `NEXT` stepped a queue it was never authored against and the two phones selected
     * different tracks. Re-checking the revision at drain time would only have converted the
     * reordering into a lost command, which is A1 Finding A again.
     */
    @Test
    fun `a queue snapshot may not overtake a command held for the clock`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false, clockReady = true)
            content.localHashes.add(HASH_A.value)
            content.localHashes.add(HASH_B.value)
            content.localHashes.add(HASH_C.value)

            // Revision 5: [A, B, C], with A current.
            val items = listOf(item(ID_A, HASH_A), item(ID_B, HASH_B), item(ID_C, HASH_C))
            session.deliver(QueueMessage.Snapshot(5, items, currentIndex = 0))
            runCurrent()
            assertEquals(5, coordinator.queueState.value.revision)

            // The estimator becomes untrustworthy, then an authoritative NEXT authored at revision 5
            // arrives: at revision 5, NEXT selects B.
            session.setClock(SessionClockEstimate(offsetToLeaderUs = 0, rttP95Us = 8_000, ready = false))
            session.deliver(nextCommand(seq = 10, effectiveAt = clock.nowUs(), queueRevision = 5))
            runCurrent()
            assertEquals(1, coordinator.diagnostics.value.deferredCommandCount)
            assertTrue(player.calls.isEmpty())

            // Then the leader removes B, and revision 6 is [A, C].
            session.deliver(QueueMessage.Snapshot(6, listOf(items[0], items[2]), currentIndex = 0))
            runCurrent()
            assertEquals(
                2,
                coordinator.diagnostics.value.deferredCommandCount,
                "the snapshot joined the held stream instead of overtaking the NEXT",
            )
            assertEquals(5, coordinator.queueState.value.revision, "and revision 5 is still what the held NEXT will see")

            // The clock recovers; the stream replays in arrival order.
            session.setClock(SessionClockEstimate(offsetToLeaderUs = 0, rttP95Us = 8_000, ready = true))
            clock.advanceBy(DEFERRED_RETRY_US)
            runCurrent()
            clock.advanceBy(LEAD_US * 4)
            runCurrent()

            val prepared = player.calls.filterIsInstance<FakeSyncPlayer.Call.Load>()
            assertEquals(1, prepared.size, "exactly one track was loaded")
            assertEquals(HASH_B, prepared.first().contentHash, "NEXT resolved against revision 5, exactly as authored")
            assertEquals(6, coordinator.queueState.value.revision, "and the snapshot then applied, in its own turn")
            assertEquals(0, coordinator.diagnostics.value.deferredCommandCount)
        }

    /** §23's second case: `SEEK`, a queue mutation and `PAUSE` all keep their original arrival order. */
    @Test
    fun `a seek, a queue mutation and a pause held together replay in arrival order`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false, clockReady = true)
            content.localHashes.add(HASH_A.value)
            val items = listOf(item(ID_A, HASH_A), item(ID_B, HASH_B))
            session.deliver(QueueMessage.Snapshot(5, items, currentIndex = 0))
            session.deliver(playCommand(seq = 9, effectiveAt = clock.nowUs(), queueRevision = 5))
            runCurrent()
            clock.advanceBy(LEAD_US * 2)
            runCurrent()
            player.calls.clear()

            session.setClock(SessionClockEstimate(offsetToLeaderUs = 0, rttP95Us = 8_000, ready = false))
            session.deliver(seekCommand(seq = 10, effectiveAt = clock.nowUs(), positionMs = 12_000, queueRevision = 5))
            session.deliver(QueueMessage.Snapshot(6, listOf(items[0]), currentIndex = 0))
            session.deliver(pauseCommand(seq = 11, effectiveAt = clock.nowUs(), positionMs = 12_500, queueRevision = 6))
            runCurrent()

            assertEquals(3, coordinator.diagnostics.value.deferredCommandCount, "all three are held, in arrival order")
            assertTrue(player.calls.isEmpty())
            assertEquals(5, coordinator.queueState.value.revision)

            session.setClock(SessionClockEstimate(offsetToLeaderUs = 0, rttP95Us = 8_000, ready = true))
            clock.advanceBy(DEFERRED_RETRY_US)
            runCurrent()
            clock.advanceBy(LEAD_US * 4)
            runCurrent()

            val calls = player.calls.toList()
            val seekIndex = calls.indexOfFirst { it is FakeSyncPlayer.Call.Seek && it.positionMs == 12_000L }
            val pauseIndex = calls.indexOf(FakeSyncPlayer.Call.Pause)
            assertTrue(seekIndex >= 0, "the SEEK was not lost")
            assertTrue(pauseIndex > seekIndex, "the PAUSE followed it, exactly as the leader ordered them")
            assertEquals(6, coordinator.queueState.value.revision, "and the snapshot between them applied too")
            assertEquals(11, coordinator.diagnostics.value.lastAppliedCommandSeq)
        }

    /** A held `PLAYBACK_STATE` waits its turn too, and still supersedes what it accounts for. */
    @Test
    fun `a playback state snapshot may not overtake a command held for the clock`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false, clockReady = true)
            content.localHashes.add(HASH_A.value)
            session.deliver(QueueMessage.Snapshot(1, listOf(item(ID_A, HASH_A)), currentIndex = 0))
            runCurrent()

            session.setClock(SessionClockEstimate(offsetToLeaderUs = 0, rttP95Us = 8_000, ready = false))
            session.deliver(playCommand(seq = 10, effectiveAt = clock.nowUs(), queueRevision = 1))
            runCurrent()
            assertEquals(1, coordinator.diagnostics.value.deferredCommandCount)

            session.deliver(
                PlaybackMessage.PlaybackStateSnapshot(
                    commandSeq = 9,
                    queueRevision = 1,
                    trackHash = HASH_A,
                    queueItemId = ID_A,
                    positionMs = 1_000,
                    playing = true,
                    atSessionUs = clock.nowUs(),
                ),
            )
            runCurrent()
            assertEquals(2, coordinator.diagnostics.value.deferredCommandCount, "an older anchor may not overtake seq 10")

            session.setClock(SessionClockEstimate(offsetToLeaderUs = 0, rttP95Us = 8_000, ready = true))
            clock.advanceBy(DEFERRED_RETRY_US)
            runCurrent()
            clock.advanceBy(LEAD_US * 4)
            runCurrent()
            assertEquals(10, coordinator.diagnostics.value.lastAppliedCommandSeq, "the command applied, not the older anchor")
            assertEquals(0, coordinator.diagnostics.value.deferredCommandCount)
        }

    /** §25: a session boundary while the stream is held leaves every one of its events inert. */
    @Test
    fun `a session boundary while an authoritative stream is held leaves all of it inert`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false, clockReady = true)
            content.localHashes.add(HASH_A.value)
            content.localHashes.add(HASH_B.value)
            val items = listOf(item(ID_A, HASH_A), item(ID_B, HASH_B))
            session.deliver(QueueMessage.Snapshot(5, items, currentIndex = 0))
            runCurrent()

            session.setClock(SessionClockEstimate(offsetToLeaderUs = 0, rttP95Us = 8_000, ready = false))
            session.deliver(nextCommand(seq = 10, effectiveAt = clock.nowUs(), queueRevision = 5))
            session.deliver(QueueMessage.Snapshot(6, listOf(items[0]), currentIndex = 0))
            runCurrent()
            assertEquals(2, coordinator.diagnostics.value.deferredCommandCount)

            session.emit(ControlEvent.LinkLost(LinkLossReason.NETWORK))
            runCurrent()
            player.calls.clear()
            assertEquals(0, coordinator.diagnostics.value.deferredCommandCount)
            assertEquals(0, coordinator.queueState.value.revision, "the old session's queue went with it")

            session.currentAuthGeneration = 2
            connect(this, asLeader = false)
            session.setClock(SessionClockEstimate(offsetToLeaderUs = 0, rttP95Us = 8_000, ready = true))
            clock.advanceBy(DEFERRED_RETRY_US * 4)
            runCurrent()

            assertTrue(player.calls.isEmpty(), "not one held event of the old session touched the new one's player")
            assertEquals(0, coordinator.queueState.value.revision, "and no old snapshot reached the new session's queue")
            assertNull(coordinator.diagnostics.value.lastAppliedCommandSeq)
        }

    /** The hold buffer is bounded, and reaching the bound is the same explicit halt as everywhere else. */
    @Test
    fun `overflowing the held authoritative stream halts rather than reordering`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope, deferredCommandCapacity = 1)
            connect(this, asLeader = false, clockReady = true)
            content.localHashes.add(HASH_A.value)
            session.deliver(QueueMessage.Snapshot(5, listOf(item(ID_A, HASH_A)), currentIndex = 0))
            runCurrent()

            session.setClock(SessionClockEstimate(offsetToLeaderUs = 0, rttP95Us = 8_000, ready = false))
            session.deliver(nextCommand(seq = 10, effectiveAt = clock.nowUs(), queueRevision = 5))
            session.deliver(QueueMessage.Snapshot(6, emptyList(), currentIndex = null))
            runCurrent()

            val diagnostics = coordinator.diagnostics.value
            assertEquals(1, diagnostics.deferredCommandCount, "the bound is real")
            assertEquals(1, diagnostics.inboundOverflowCount)
            assertTrue(diagnostics.ingressDesynchronized, "an overflow halts; it never evicts and never reorders")
            assertEquals(5, coordinator.queueState.value.revision, "and the snapshot that overflowed did not apply")
        }

    // --- Finding E: a correction's snapshot keeps the correction's own identity --------------------

    /**
     * **The defect.** `emitPlaybackState()` read `session.currentAuthGeneration` *inside itself*, so
     * a correction that had proved ownership of generation A handed the enqueue whatever generation
     * happened to be live by then. The proof and the act were about different sessions.
     *
     * On this platform the window is between two statements the test dispatcher cannot interleave,
     * so this asserts the contract rather than reproducing the interleaving: the emit is refused
     * because the *correction's* generation is no longer current, not because a freshly-read one
     * happens to be. `SyncPlaybackDeliveryAuditTests.testACorrectionSupersededBeforeItsSnapshot…` on
     * iOS reproduces the interleaving itself, where every `await` is an actor re-entrancy point.
     */
    @Test
    fun `a correction whose session ends inside its player call emits no snapshot`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            leaderPlaying(this)
            val gate = CompletableDeferred<Unit>()
            player.gate = gate
            player.gateOn = { it is FakeSyncPlayer.Call.Seek }
            // A drift inside ADR-004's hard-seek band: the tier that emits an authoritative
            // PLAYBACK_STATE, and the only tier that does.
            driftIntoHardSeekBand()

            clock.advanceBy(POSITION_REPORT_US)
            runCurrent()
            assertTrue(player.calls.any { it is FakeSyncPlayer.Call.Seek }, "the ladder reached the hard-seek tier")

            session.currentAuthGeneration = 2
            gate.complete(Unit)
            runCurrent()

            assertTrue(
                session.sentOfType<PlaybackMessage.PlaybackStateSnapshot>().isEmpty(),
                "a correction from a dead session emits nothing",
            )
            assertEquals(0, coordinator.diagnostics.value.hardSeekCount, "and spends none of the seek budget either")
        }

    /**
     * The epoch half, which the old `emitPlaybackState()` could not check at all: it took no token,
     * so a snapshot caused by a correction belonging to a superseded playback epoch was
     * indistinguishable from a current one.
     */
    @Test
    fun `a correction whose playback epoch is superseded inside its player call emits no snapshot`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            leaderPlaying(this)
            val gate = CompletableDeferred<Unit>()
            player.gate = gate
            player.gateOn = { it is FakeSyncPlayer.Call.Seek }
            driftIntoHardSeekBand()

            clock.advanceBy(POSITION_REPORT_US)
            runCurrent()
            assertTrue(player.calls.any { it is FakeSyncPlayer.Call.Seek })

            // A new epoch begins while the correction is inside the player.
            content.localHashes.add(HASH_B.value)
            content.peerHashes.add(HASH_B.value)
            coordinator.playSynchronized(HASH_B)
            runCurrent()
            gate.complete(Unit)
            runCurrent()

            assertEquals(0, coordinator.diagnostics.value.hardSeekCount, "the superseded correction had zero effects")
        }

    /** And the control: a correction that is still current does emit exactly one snapshot. */
    @Test
    fun `a current correction still emits exactly one authoritative snapshot`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            leaderPlaying(this)
            driftIntoHardSeekBand()

            clock.advanceBy(POSITION_REPORT_US)
            runCurrent()

            assertEquals(1, coordinator.diagnostics.value.hardSeekCount)
            assertEquals(
                1,
                session.sentOfType<PlaybackMessage.PlaybackStateSnapshot>().size,
                "the ordinary path is untouched by the ownership work",
            )
        }

    private fun item(
        queueItemId: String,
        hash: ContentHash,
    ) = SharedQueueItem(queueItemId, hash, SyncTestValues.leaderPeerId, order = PlaybackBounds.QUEUE_ORDER_STEP)

    private fun playCommand(
        seq: Long,
        effectiveAt: Long,
        queueRevision: Long,
    ) = PlaybackMessage.Play(
        PlaybackCommandHeader(seq, effectiveAt, SyncTestValues.leaderPeerId, queueRevision),
        HASH_A,
        positionMs = 0,
        queueItemId = ID_A,
    )

    private fun nextCommand(
        seq: Long,
        effectiveAt: Long,
        queueRevision: Long,
    ) = PlaybackMessage.Next(PlaybackCommandHeader(seq, effectiveAt, SyncTestValues.leaderPeerId, queueRevision))

    private fun seekCommand(
        seq: Long,
        effectiveAt: Long,
        positionMs: Long,
        queueRevision: Long,
    ) = PlaybackMessage.Seek(
        PlaybackCommandHeader(seq, effectiveAt, SyncTestValues.leaderPeerId, queueRevision),
        positionMs,
    )

    private fun pauseCommand(
        seq: Long,
        effectiveAt: Long,
        positionMs: Long,
        queueRevision: Long,
    ) = PlaybackMessage.Pause(
        PlaybackCommandHeader(seq, effectiveAt, SyncTestValues.leaderPeerId, queueRevision),
        positionMs,
    )

    private companion object {
        val HASH_A: ContentHash = SyncTestValues.hash(1)
        val HASH_B: ContentHash = SyncTestValues.hash(2)
        val HASH_C: ContentHash = SyncTestValues.hash(3)
        val ID_A: String = SyncTestValues.ulid(1)
        val ID_B: String = SyncTestValues.ulid(2)
        val ID_C: String = SyncTestValues.ulid(3)

        /** [com.ridelink.core.playback.Phase5GateBounds.DEFERRED_RETRY_INTERVAL_US]. */
        const val DEFERRED_RETRY_US = 100_000L

        /** Comfortably past `LEAD = max(120 ms, 4 x rtt_p95)` for the fake's 8 ms p95. */
        const val LEAD_US = 200_000L

        /** [com.ridelink.core.playback.PlaybackBounds.POSITION_REPORT_INTERVAL_MS], in microseconds. */
        const val POSITION_REPORT_US = 5_000_000L

        /** The injected outbound bound the wedge tests use, so the edge is forced rather than raced. */
        const val WEDGE_CAPACITY = 2

        /**
         * Where `leaderPlaying` anchors position 0: [FakeMonotonicClock]'s initial instant plus
         * `LEAD = max(120 ms, 4 x rtt_p95)`, which for the fake's 8 ms p95 is 120 ms.
         */
        const val ANCHOR_SESSION_US = 1_000_000L + 120_000L
    }
}
