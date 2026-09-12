package com.ridelink.app.sync

import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.SessionId
import com.ridelink.core.playback.PlaybackCommandHeader
import com.ridelink.core.playback.PlaybackMessage
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
 * The coordinator half of ADR-024 **Amendment A7**.
 *
 * A7's control-layer fix — `ControlSessionManager` binding every inbound frame to the connection
 * that authorised its read, pinned by `network`'s `StaleReadGenerationTest` — has a consequence
 * this layer has to answer for. **Once a frame keeps its own session's generation instead of
 * inheriting the live one, generations no longer arrive at `Phase5FrameQueue` in increasing
 * order.** A read loop whose session has ended still dispatches the one frame it had already read,
 * and it does so after the successor session's read loop has begun offering, so `A, B, A` reaches
 * `offer`.
 *
 * A6's loss ledger assumed the opposite in writing: it opened a new bucket whenever the incoming
 * generation differed from the *newest* one, and once past its eight-bucket bound it evicted the
 * oldest **by arrival** and folded those counts into the next oldest by arrival — justified by
 * "generations strictly increase, so both of the two oldest are retired". Under an alternating
 * run that fold target is the newest generation, which may be **live**.
 *
 * That is not a diagnostics defect. A follower answers a *live*-generation loss by latching
 * `playbackDesynchronized`/`queueDesynchronized`, which decide whether incremental authoritative
 * commands are applied at all. So the A6 defect — **Session B halted because Session A dropped
 * something** — comes straight back through the ledger's own compaction, and this test is what
 * stops it.
 *
 * `Phase5FrameQueueTest` pins the ledger's mechanics directly. This file pins the consequence the
 * user would actually feel, through the real coordinator.
 *
 * The mirror is `RideLinkPlatformTests.SyncPlaybackReadGenerationAuditTests`.
 */
class SyncPlaybackReadGenerationAuditTest {
    private lateinit var session: FakeSyncSession
    private lateinit var player: FakeSyncPlayer
    private lateinit var content: FakeSyncContent
    private lateinit var clock: FakeMonotonicClock
    private lateinit var coordinator: SyncPlaybackCoordinator
    private var idSeed = 4_700

    private fun build(scope: CoroutineScope) {
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
                inboundCapacity = 1,
                deferredCommandCapacity = 16,
            )
    }

    /**
     * **The defect, in full, at the exact point it first bites.** The dead session's read loop keeps
     * delivering refused commands while the live session's own frames are merely being *coalesced*
     * — which never halts anything, because PROTOCOL §5 makes the newest `POSITION_REPORT` subsume
     * its predecessors by definition. Under A6's per-adjacency-run bucketing that alternation opens
     * a bucket per event, so [RETIRED_REFUSALS] refusals and [RETIRED_REFUSALS] - 1 coalesces is the
     * shortest run that passes the eight-bucket bound and forces exactly one compaction.
     *
     * Session B lost nothing at all. It must end this completely un-halted, and every one of
     * Session A's refusals must still be surfaced as the retired events they are.
     *
     * Measured against the A6 fold restored into `Phase5FrameQueue`: `ingressDesynchronized`
     * **true**, `syncState` **DESYNCHRONIZED** and `inboundOverflowCount` **1** — on a session whose
     * own ingress refused nothing — because the one eviction folds generation 1's refusal into the
     * generation 2 bucket that follows it, and generation 2 is live.
     */
    @Test
    fun `the first ledger compaction never hands the live session a refusal it did not have`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connectAsFollower(generation = 1)
            boundaryToFollower(generation = 2)
            content.localHashes.add(HASH_A.value)

            val parked = parkConsumer(hash = HASH_A)
            // One live-generation report occupies the bound, so the alternation below is exactly
            // "a retired command is refused / a live report supersedes its own predecessor".
            session.deliver(positionReport(0), generation = 2)
            runCurrent()

            repeat(RETIRED_REFUSALS) { index ->
                session.deliver(pauseCommand(seq = 2L + index, positionMs = 5_000L + index), generation = 1)
                if (index < RETIRED_REFUSALS - 1) session.deliver(positionReport(index + 1), generation = 2)
                runCurrent()
            }

            assertFalse(coordinator.diagnostics.value.ingressDesynchronized, "Session B starts clean")

            releaseParked(parked)

            val after = coordinator.diagnostics.value
            assertFalse(
                after.ingressDesynchronized,
                "Session B refused nothing of its own — a retired session's refusal must never halt it",
            )
            assertEquals(
                0,
                after.inboundOverflowCount,
                "nor be counted against it: every refusal here belonged to the session that has ended",
            )
            assertTrue(after.syncState != SyncState.DESYNCHRONIZED, "and Session B is not shown as halted")
            assertEquals(
                RETIRED_REFUSALS,
                after.inboundRetiredLossCount,
                "every one of the dead session's refusals is still surfaced, attributed to it",
            )
            assertEquals(
                RETIRED_REFUSALS - 1,
                after.inboundCoalescedCount,
                "and Session B owns exactly its own coalescing, once each",
            )
            assertTrue(
                player.calls.none { it is FakeSyncPlayer.Call.Pause },
                "no retired command took effect in the live session",
            )

            // And Session B's own authority still applies, from its own sequence floor.
            session.deliver(playCommand(seq = 1, hash = HASH_A), generation = 2)
            runCurrent()
            assertEquals(1L, coordinator.diagnostics.value.lastAppliedCommandSeq, "Session B commands normally")
        }

    /**
     * The same alternation run long past the bound, where A6 would compact many times over: the
     * accounting must stay exact in both directions rather than merely avoiding the halt. Under the
     * A6 fold the counts slosh between the two generations on every eviction, and this run ends with
     * `inboundRetiredLossCount` **20** for a session that caused twelve refusals.
     */
    @Test
    fun `a long alternating run keeps every event with the generation that caused it`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connectAsFollower(generation = 1)
            boundaryToFollower(generation = 2)
            content.localHashes.add(HASH_A.value)

            val parked = parkConsumer(hash = HASH_A)
            session.deliver(positionReport(0), generation = 2)
            runCurrent()
            repeat(ALTERNATIONS) { index ->
                session.deliver(pauseCommand(seq = 2L + index, positionMs = 5_000L + index), generation = 1)
                session.deliver(positionReport(index + 1), generation = 2)
                runCurrent()
            }

            releaseParked(parked)

            val after = coordinator.diagnostics.value
            assertFalse(after.ingressDesynchronized, "still not the live session's loss, however long the run")
            assertEquals(0, after.inboundOverflowCount)
            assertEquals(ALTERNATIONS, after.inboundRetiredLossCount, "exactly the retired session's refusals")
            assertEquals(ALTERNATIONS, after.inboundCoalescedCount, "exactly the live session's coalesces")
        }

    /**
     * The half the fix must not weaken (A1 Finding C, restated under A7's arrival order): the live
     * session's **own** refusal still halts it, and still does so before any later incremental
     * command of that session is applied — even when a retired generation's events are interleaved
     * with it.
     */
    @Test
    fun `the live session's own refusal still halts it amid a retired session's noise`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connectAsFollower(generation = 1)
            boundaryToFollower(generation = 2)
            content.localHashes.add(HASH_A.value)

            val parked = parkConsumer(hash = HASH_A)
            // One live report occupies the bound, so every command below is genuinely refused
            // rather than merely buffered.
            session.deliver(positionReport(0), generation = 2)
            runCurrent()
            // Retired noise first, then the live session's own unsupersedable refusal behind it.
            repeat(ALTERNATIONS) { index ->
                session.deliver(pauseCommand(seq = 2L + index, positionMs = 5_000L + index), generation = 1)
                runCurrent()
            }
            session.deliver(resumeCommand(seq = 3, positionMs = 8_000), generation = 2)
            runCurrent()

            releaseParked(parked)

            val after = coordinator.diagnostics.value
            assertTrue(after.ingressDesynchronized, "the live session's own loss still halts it")
            assertEquals(SyncState.DESYNCHRONIZED, after.syncState)
            assertEquals(1, after.inboundOverflowCount, "counted once, against the session that lost it")
            assertEquals(ALTERNATIONS, after.inboundRetiredLossCount, "and the retired session's stay its own")
            assertTrue(
                player.calls.none { it is FakeSyncPlayer.Call.Pause },
                "the command queued behind the refusal is never applied as coherent state",
            )
        }

    // --- Helpers (mirroring SyncPlaybackIngressLifetimeAuditTest's) ------------------------------

    private suspend fun TestScope.connectAsFollower(generation: Long) {
        runCurrent()
        session.currentAuthGeneration = generation
        session.setClock(SessionClockEstimate(offsetToLeaderUs = 0, rttP95Us = 8_000, ready = true))
        session.emit(ControlEvent.Connected(SyncTestValues.leaderPeerId, SessionId("S$generation"), false))
        runCurrent()
    }

    private suspend fun TestScope.boundaryToFollower(generation: Long) {
        session.emit(ControlEvent.LinkLost(LinkLossReason.NETWORK))
        runCurrent()
        connectAsFollower(generation)
    }

    /** Parks the one ingress consumer inside a decoder resolve, so the read loops run on without it. */
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

    private companion object {
        val HASH_A: ContentHash = SyncTestValues.hash(1)

        /**
         * Comfortably past `Phase5FrameQueue.MAX_LOSS_GENERATIONS`, so the A6 bucketing would have
         * compacted several times over. Under A7's one-bucket-per-generation there are two buckets
         * however long this runs, which is the point.
         */
        const val ALTERNATIONS = 12

        /**
         * The shortest alternating run that exceeds `Phase5FrameQueue.MAX_LOSS_GENERATIONS` under
         * A6's per-adjacency-run bucketing: `n` refusals interleaved with `n - 1` coalesces is
         * `2n - 1` buckets, so `n = 5` is the first value that forces a compaction at all.
         */
        const val RETIRED_REFUSALS = 5
    }
}
