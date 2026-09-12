package com.ridelink.app.sync

import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.SessionId
import com.ridelink.core.playback.IngressAdmission
import com.ridelink.core.playback.Phase5FrameKind
import com.ridelink.core.playback.PlaybackBounds
import com.ridelink.core.playback.PlaybackCommandHeader
import com.ridelink.core.playback.PlaybackMessage
import com.ridelink.core.playback.QueueMessage
import com.ridelink.core.playback.SharedQueueItem
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
import kotlin.test.assertTrue

/**
 * The regressions ADR-024 **Amendment A6** — the sixth Phase 5 closure audit — exists for.
 *
 * A1 made the inbound handoff lossless and bounded, and made a refusal *explicit*: a follower that
 * loses an unsupersedable frame stops trusting incremental state until authoritative full state
 * arrives. A3 and A5 then established that a session boundary retires everything the old session
 * authorised. A6 is the one place those two never met.
 *
 * **The pipe outlives sessions by design; the identity of what it lost must not.**
 * [Phase5FrameQueue] deliberately survives an authentication boundary — a boundary is expressed by
 * the generation each frame carries, not by tearing the pipe down — but its loss accounting was two
 * cumulative counters the consumer diffed against its own baseline. That difference carried no
 * generation at all, so a frame refused under Session A and observed after Session B activated told
 * Session B that *it* had lost a frame. On a follower that sets `playbackDesynchronized`/
 * `queueDesynchronized`, which decide whether incremental authoritative commands are applied at
 * all — so this was a correctness failure, not a diagnostics one: **Session B halted because
 * Session A dropped something.**
 *
 * Pre-fix, against unmodified `2836695e` production sources, the first test below records
 * `ingressDesynchronized` **false → true**, `inboundOverflowCount` **0 → 1** and `syncState`
 * **→ DESYNCHRONIZED** on a Session B whose own ingress never refused anything.
 *
 * The fix binds every loss to the generation of the frame that caused it, and hands the records to
 * the consumer to attribute. Both halves of that have to hold, and both are asserted here:
 *
 * - **cross-generation** — Session A's loss never changes Session B;
 * - **same-generation** — a loss is still observed *before* any later incremental frame of that same
 *   generation is applied. Fixing the first by weakening the second would be no fix at all.
 *
 * The mirror is `RideLinkPlatformTests.SyncPlaybackIngressLifetimeAuditTests`.
 */
class SyncPlaybackIngressLifetimeAuditTest {
    private lateinit var session: FakeSyncSession
    private lateinit var player: FakeSyncPlayer
    private lateinit var content: FakeSyncContent
    private lateinit var clock: FakeMonotonicClock
    private lateinit var coordinator: SyncPlaybackCoordinator
    private var idSeed = 4_100

    private fun build(
        scope: CoroutineScope,
        inboundCapacity: Int = 1,
    ) {
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
                inboundCapacity = inboundCapacity,
                deferredCommandCapacity = 16,
            )
    }

    // --- Finding A: an ingress loss belongs to the generation that caused it --------------------

    /**
     * **The defect, in full.** Session A's one ingress consumer is parked inside a decoder resolve,
     * so the read loop keeps filling a bounded queue behind it and one authoritative frame is
     * genuinely refused. Session A then ends and Session B authenticates as a follower, clean. Only
     * *then* does the old parked work release and the consumer reach the refusal.
     *
     * Session B never lost anything. It must not be halted, must not be counted against, and must go
     * on accepting its own authority normally.
     *
     * The refusal is *proved*, not assumed: `inboundRetiredLossCount` rising by exactly one is a
     * statement that a frame was refused **and** that the refusal was attributed to a generation
     * that is no longer live. Nothing else can move that counter.
     */
    @Test
    fun `a Session A ingress overflow never desynchronizes Session B`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connectAsFollower(generation = 1)
            content.localHashes.add(HASH_A.value)

            val parked = parkConsumer(hash = HASH_A)
            overflowUnder(generation = 1)

            boundaryToFollower(generation = 2)
            val before = coordinator.diagnostics.value
            assertFalse(before.ingressDesynchronized, "Session B starts clean")

            releaseParked(parked)

            val after = coordinator.diagnostics.value
            assertFalse(after.ingressDesynchronized, "Session A's loss is not Session B's loss")
            assertTrue(after.syncState != SyncState.DESYNCHRONIZED, "and Session B is not shown as halted")
            assertEquals(
                before.inboundOverflowCount,
                after.inboundOverflowCount,
                "nor does it increment the live session's loss figure",
            )
            assertEquals(
                before.inboundRetiredLossCount + 1,
                after.inboundRetiredLossCount,
                "the evidence is kept, attributed to the session that is gone",
            )
            assertTrue(
                player.calls.none { it is FakeSyncPlayer.Call.Pause },
                "and no Session A command took effect in Session B",
            )
            assertEquals(0L, coordinator.queueState.value.revision, "nor mutated its queue")

            // And Session B's own authority still applies, from its own sequence floor.
            session.deliver(playCommand(seq = 1, hash = HASH_A), generation = 2)
            runCurrent()
            assertEquals(1L, coordinator.diagnostics.value.lastAppliedCommandSeq, "Session B commands normally")
        }

    /**
     * The property the fix above must not weaken (A1 Finding C). A loss in the **live** session is
     * still observed strictly before any later incremental frame of that session is dispatched — a
     * `PAUSE` applied as coherent state without the `PLAY` that preceded it is the exact incoherence
     * the halt exists to prevent — and authoritative full state is still the only thing that ends it.
     */
    @Test
    fun `a same-generation overflow still halts before later incremental authority applies`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connectAsFollower(generation = 1)
            content.localHashes.add(HASH_A.value)

            val parked = parkConsumer(hash = HASH_A)
            overflowUnder(generation = 1)
            releaseParked(parked)

            val halted = coordinator.diagnostics.value
            assertTrue(halted.ingressDesynchronized, "the live session's own loss still halts it")
            assertEquals(SyncState.DESYNCHRONIZED, halted.syncState)
            assertEquals(1, halted.inboundOverflowCount, "counted against the session that lost it")
            assertEquals(0, halted.inboundRetiredLossCount, "and not as a retired one")
            assertEquals(1L, halted.lastReceivedCommandSeq, "the halt spends no sequence number")
            assertTrue(
                player.calls.none { it is FakeSyncPlayer.Call.Pause },
                "the PAUSE queued behind the refusal is never applied as coherent state",
            )

            // A further incremental command still changes nothing while the halt is in force.
            session.deliver(pauseCommand(seq = 4, positionMs = 9_000), generation = 1)
            runCurrent()
            assertEquals(1L, coordinator.diagnostics.value.lastReceivedCommandSeq, "still halted")

            // And authoritative full state still ends it, exactly as A1 established.
            session.deliver(snapshot(revision = 3), generation = 1)
            runCurrent()
            session.deliver(
                PlaybackMessage.PlaybackStateSnapshot(
                    commandSeq = 7,
                    queueRevision = 3,
                    trackHash = HASH_A,
                    queueItemId = SyncTestValues.ulid(1),
                    positionMs = 12_000,
                    playing = true,
                    atSessionUs = clock.nowUs(),
                ),
                generation = 1,
            )
            runCurrent()
            val reconciled = coordinator.diagnostics.value
            assertFalse(reconciled.ingressDesynchronized, "authoritative full state is what ends the halt")
            assertEquals(7L, reconciled.lastAppliedCommandSeq, "ordering resumes from the authoritative value")
        }

    /**
     * The proof that A6 **scopes** losses rather than merely suppressing old counters: each
     * generation's own loss is accounted for, exactly once, to itself.
     */
    @Test
    fun `generation A and generation B each own their loss exactly once`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connectAsFollower(generation = 1)
            content.localHashes.add(HASH_A.value)
            content.localHashes.add(HASH_B.value)

            val parkedA = parkConsumer(hash = HASH_A)
            overflowUnder(generation = 1)
            boundaryToFollower(generation = 2)
            releaseParked(parkedA)

            val afterA = coordinator.diagnostics.value
            assertFalse(afterA.ingressDesynchronized, "A's loss did not reach B")
            assertEquals(0, afterA.inboundOverflowCount)
            assertEquals(1, afterA.inboundRetiredLossCount)

            // Now Session B loses one of its own, the same way.
            val parkedB = parkConsumer(hash = HASH_B)
            overflowUnder(generation = 2)
            releaseParked(parkedB)

            val afterB = coordinator.diagnostics.value
            assertTrue(afterB.ingressDesynchronized, "B's own loss does affect B")
            assertEquals(1, afterB.inboundOverflowCount, "counted once, for B")
            assertEquals(1, afterB.inboundRetiredLossCount, "and A's stays A's — no double counting")
        }

    /**
     * Coalescing is not a loss of authority and never halts — but it is still an event, and it still
     * belongs to the generation whose frame caused it. Session B must not inherit Session A's.
     */
    @Test
    fun `coalescing accounting is generation-bound too`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connectAsFollower(generation = 1)
            content.localHashes.add(HASH_A.value)
            content.localHashes.add(HASH_B.value)

            val parkedA = parkConsumer(hash = HASH_A)
            repeat(3) { index -> session.deliver(positionReport(index), generation = 1) }
            runCurrent()

            boundaryToFollower(generation = 2)
            val before = coordinator.diagnostics.value
            releaseParked(parkedA)

            val afterA = coordinator.diagnostics.value
            assertEquals(
                before.inboundCoalescedCount,
                afterA.inboundCoalescedCount,
                "Session B does not inherit Session A's coalescing as its own",
            )
            assertEquals(
                before.inboundRetiredLossCount + 2,
                afterA.inboundRetiredLossCount,
                "the two superseded reports are surfaced as the retired events they are",
            )
            assertFalse(afterA.ingressDesynchronized, "coalescing never halts, in either session")

            // Session B's own coalescing is its own.
            val parkedB = parkConsumer(hash = HASH_B)
            repeat(3) { index -> session.deliver(positionReport(index), generation = 2) }
            runCurrent()
            releaseParked(parkedB)

            val afterB = coordinator.diagnostics.value
            assertEquals(2, afterB.inboundCoalescedCount, "and B accounts for its own, exactly once")
            assertEquals(afterA.inboundRetiredLossCount, afterB.inboundRetiredLossCount, "A's stays A's")
            assertFalse(afterB.ingressDesynchronized)
        }

    // --- Finding B's Android half: structurally safe, and asserted rather than assumed ----------

    /**
     * ADR-024 Amendment A6 §I. On iOS `failClosedOutbound` **awaited** the rate restore and then
     * wrote seven diagnostics fields, so a boundary landing inside the rate change had Session A's
     * fail-closed verdict overwrite Session B's live state.
     *
     * Android cannot: every caller of `restoreRate` *launches* it, so the whole verdict is written
     * in one uninterrupted synchronous block and the player call is the only thing that outlives it.
     * That is a structural fact, and this test is what keeps it one — it lands a real boundary
     * strictly inside the rate restore and proves nothing of Session A's reaches Session B.
     */
    @Test
    fun `a fail-closed rate restore parked across a boundary writes nothing into the new session`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope, inboundCapacity = 256)
            // A leader, because only an **authoritative** frame the transport refuses fails closed:
            // a follower's PLAY is an intent, and `OutboundCommitGate` quietly abandons those.
            connectAsLeader(generation = 1)
            content.localHashes.add(HASH_A.value)
            content.localHashes.add(HASH_B.value)

            // Park the rate restore the fail-closed path performs.
            val rateGate = CompletableDeferred<Unit>()
            player.gate = rateGate
            player.gateOn = { it is FakeSyncPlayer.Call.SetRate }

            session.sendResult = false
            coordinator.enqueue(HASH_A)
            runCurrent()
            assertTrue(coordinator.diagnostics.value.outboundAuthorityLost, "Session A failed closed")

            session.sendResult = true
            boundaryToFollower(generation = 2)
            session.deliver(playCommand(seq = 1, hash = HASH_B), generation = 2)
            runCurrent()

            val before = coordinator.diagnostics.value
            assertFalse(before.outboundAuthorityLost, "Session B starts with its authority intact")

            player.gateOn = null
            rateGate.complete(Unit)
            runCurrent()

            val after = coordinator.diagnostics.value
            assertEquals(before.syncState, after.syncState, "no retired fail-closed verdict reached Session B")
            assertEquals(before.outboundAuthorityLost, after.outboundAuthorityLost)
            assertEquals(before.deferredCommandCount, after.deferredCommandCount)
            assertEquals(before.localDriftMs, after.localDriftMs)
            assertEquals(before.peerDriftMs, after.peerDriftMs)
            assertEquals(before.lastAppliedCommandSeq, after.lastAppliedCommandSeq)
            assertEquals(before.playbackRate, after.playbackRate)
        }

    /** The same-session control: fail-closed still does everything A2 requires of it. */
    @Test
    fun `a same-session fail-closed still latches, leaves synchronised mode and restores the rate`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope, inboundCapacity = 256)
            connectAsLeader(generation = 1)
            content.localHashes.add(HASH_A.value)

            session.sendResult = false
            coordinator.enqueue(HASH_A)
            runCurrent()

            val diagnostics = coordinator.diagnostics.value
            assertTrue(diagnostics.outboundAuthorityLost, "the divergence is surfaced, never silent")
            assertEquals(SyncState.TRANSPORT_FAILED, diagnostics.syncState)
            assertEquals(1.0, diagnostics.playbackRate, "and the rate is back at exactly 1.0")
            assertFalse(coordinator.isSynchronizedModeActive(), "synchronised mode is left")
            assertTrue(
                player.calls.contains(FakeSyncPlayer.Call.SetRate(1.0)),
                "the unfenced restore still reaches the player",
            )
            assertTrue(
                player.calls.none { it is FakeSyncPlayer.Call.Stop },
                "ADR-004: local music is not stopped",
            )
        }

    // --- Stress ---------------------------------------------------------------------------------

    /**
     * The permutations A6 §16 asks for, taken on the queue itself so each iteration is a whole
     * producer/consumer interleaving rather than a coordinator fixture: capacity 1 and 2, an
     * overflow and a coalescing, generations that keep increasing, and an observation that lands
     * either while the causing generation is still live or after it has been replaced.
     *
     * What every iteration asserts is the one thing A6 rests on — **a loss carries the generation of
     * the frame that caused it, and draining it does not consult anything else.**
     */
    @Test
    fun `stress - two hundred generation-scoped ingress permutations`() {
        var live = 0
        var retired = 0
        for (iteration in 0 until 200) {
            val capacity = if (iteration % 3 == 0) 1 else 2
            val coalescing = iteration % 2 == 0
            val generation = 1L + iteration
            val subject = lossQueue(capacity)

            // Fill the bound. When the newcomer is a latest-wins frame it needs a sibling to
            // supersede, so the oldest filler is one of its own family.
            repeat(capacity) { index ->
                val name = if (coalescing && index == 0) "report-seed" else "fill$index"
                assertEquals(IngressAdmission.ADMIT, subject.offer(name to generation))
            }

            val newcomer = if (coalescing) "report-new" else "refused"
            val expected = if (coalescing) IngressAdmission.COALESCE else IngressAdmission.OVERFLOW
            assertEquals(expected, subject.offer(newcomer to generation), "iteration $iteration")

            // The boundary lands before the consumer looks on half the iterations, and not at all on
            // the other half.
            val liveWhenObserved = if (iteration % 4 == 1 || iteration % 4 == 2) generation else generation + 1
            val losses = subject.drainLosses()
            assertEquals(1, losses.size, "one generation, one bucket")
            val loss = losses.single()
            assertEquals(generation, loss.generation, "attributed to the causing frame's own generation")
            assertEquals(if (coalescing) 0 else 1, loss.overflowCount)
            assertEquals(if (coalescing) 1 else 0, loss.coalescedCount)
            if (loss.generation == liveWhenObserved) live += 1 else retired += 1
            assertTrue(subject.drainLosses().isEmpty(), "draining clears")
        }
        assertEquals(100, live, "half the iterations observed while the causing generation was live")
        assertEquals(100, retired)
    }

    /**
     * A consumer parked across many boundaries cannot grow the ledger without bound, and the bound
     * is not a silent hole: an evicted bucket's counts are folded into the next oldest, so the total
     * is preserved exactly.
     */
    @Test
    fun `the loss ledger is bounded and folds rather than drops`() {
        val subject = lossQueue(capacity = 1)
        assertEquals(IngressAdmission.ADMIT, subject.offer("fill" to 0L))
        for (generation in 1L..40L) {
            assertEquals(IngressAdmission.OVERFLOW, subject.offer("refused" to generation))
        }
        val losses = subject.drainLosses()
        assertTrue(losses.size <= 8, "bounded: ${losses.size} buckets")
        assertEquals(40, losses.sumOf { it.overflowCount }, "and nothing was silently discarded")
        assertEquals(40L, losses.last().generation, "the newest generation keeps its own identity")
    }

    /** A bare queue whose frames are `name to generation`; "report…" names are the latest-wins family. */
    private fun lossQueue(capacity: Int) =
        Phase5FrameQueue<Pair<String, Long>>(
            capacity = capacity,
            kindOf = { if (it.first.startsWith("report")) Phase5FrameKind.LATEST_WINS else Phase5FrameKind.COMMAND },
            coalesceKeyOf = { if (it.first.startsWith("report")) "REPORT" else null },
            generationOf = { it.second },
        )

    // --- Helpers --------------------------------------------------------------------------------

    private suspend fun TestScope.connectAsFollower(generation: Long) {
        runCurrent()
        session.currentAuthGeneration = generation
        session.setClock(SessionClockEstimate(offsetToLeaderUs = 0, rttP95Us = 8_000, ready = true))
        session.emit(ControlEvent.Connected(SyncTestValues.leaderPeerId, SessionId("S$generation"), false))
        runCurrent()
    }

    private suspend fun TestScope.connectAsLeader(generation: Long) {
        runCurrent()
        session.currentAuthGeneration = generation
        session.setClock(SessionClockEstimate(offsetToLeaderUs = 0, rttP95Us = 8_000, ready = true))
        session.emit(ControlEvent.Connected(SyncTestValues.followerPeerId, SessionId("S$generation"), true))
        runCurrent()
    }

    private suspend fun TestScope.boundaryToFollower(generation: Long) {
        session.emit(ControlEvent.LinkLost(LinkLossReason.NETWORK))
        runCurrent()
        connectAsFollower(generation)
    }

    /** Parks the one ingress consumer inside a decoder resolve, so the read loop runs on without it. */
    private fun TestScope.parkConsumer(hash: ContentHash): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        content.resolveGate = gate
        session.deliver(playCommand(seq = 1, hash = hash), session.currentAuthGeneration)
        runCurrent()
        return gate
    }

    private fun TestScope.releaseParked(gate: CompletableDeferred<Unit>) {
        content.resolveGate = null
        gate.complete(Unit)
        runCurrent()
    }

    /**
     * Fills the bound behind the parked consumer and offers one more authoritative command, which
     * the queue has nowhere to put. The *refusal* is what the assertions above prove happened.
     */
    private fun TestScope.overflowUnder(generation: Long) {
        session.deliver(pauseCommand(seq = 2, positionMs = 5_000), generation)
        session.deliver(resumeCommand(seq = 3, positionMs = 6_000), generation)
        runCurrent()
    }

    private fun header(seq: Long) =
        PlaybackCommandHeader(
            commandSeq = seq,
            effectiveAtSessionUs = clock.nowUs(),
            issuedBy = SyncTestValues.leaderPeerId,
            queueRevision = 0,
        )

    private fun playCommand(
        seq: Long,
        hash: ContentHash,
    ) = PlaybackMessage.Play(header(seq), hash, 0, SyncTestValues.ulid(1))

    private fun pauseCommand(
        seq: Long,
        positionMs: Long,
    ) = PlaybackMessage.Pause(header(seq), positionMs)

    private fun resumeCommand(
        seq: Long,
        positionMs: Long,
    ) = PlaybackMessage.Resume(header(seq), positionMs)

    private fun positionReport(index: Int) =
        PlaybackMessage.PositionReport(
            HASH_A,
            1_000L * index,
            clock.nowUs() + index,
            playing = true,
            playbackRate = 1.0,
        )

    private fun snapshot(revision: Long) =
        QueueMessage.Snapshot(
            queueRevision = revision,
            items =
                listOf(
                    SharedQueueItem(
                        SyncTestValues.ulid(1),
                        HASH_A,
                        SyncTestValues.leaderPeerId,
                        order = PlaybackBounds.QUEUE_ORDER_STEP,
                    ),
                ),
            currentIndex = null,
        )

    private companion object {
        val HASH_A: ContentHash = SyncTestValues.hash(1)
        val HASH_B: ContentHash = SyncTestValues.hash(2)
    }
}
