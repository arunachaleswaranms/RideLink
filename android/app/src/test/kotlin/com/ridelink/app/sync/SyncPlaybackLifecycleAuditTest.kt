package com.ridelink.app.sync

import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.SessionId
import com.ridelink.core.playback.PlaybackMessage
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The regressions ADR-024 **Amendment A3** — the third Phase 5 closure audit — exists for.
 *
 * A2 established that an authoritative frame commits nothing until the transport says it went out.
 * A3 is about what happens to the *local* half of that commit when the session dies between the
 * send succeeding and the apply running: **an apply-chain or scheduled-chain node created under
 * Session A could still be waiting when Session B authenticated, and then ran against Session B's
 * queue, timeline, playback epoch, player and diagnostics.**
 *
 * Every test below builds that interleaving deterministically and asserts **zero** Session-B
 * effects. Each one fails on the code as A2 left it:
 *
 * - `applyChain`/`scheduledChain` were *detached* at a session boundary (`= null`) and never
 *   cancelled, so the nodes already created went on existing;
 * - `applyStep`, `applyTransport` and `applySeek` mutated the live queue, the live timeline and the
 *   live playback epoch **before** proving anything about the session that authorised them;
 * - a retired scheduled action wrote `lastScheduleErrorUs` *before* its ownership proof.
 *
 * **Deterministic throughout.** [FakeMonotonicClock] is the only clock, [StandardTestDispatcher]
 * decides ordering, and the blocking seam is [FakeSyncPlayer.gate] — which is deliberately
 * **non-cancellable**, exactly as `ExoPlayer.prepare` is, so what these tests prove is the
 * generation fence rather than merely that cancellation happened.
 *
 * The mirror is `RideLinkPlatformTests.SyncPlaybackLifecycleAuditTests`.
 */
@Suppress("LargeClass")
class SyncPlaybackLifecycleAuditTest {
    private lateinit var session: FakeSyncSession
    private lateinit var player: FakeSyncPlayer
    private lateinit var content: FakeSyncContent
    private lateinit var clock: FakeMonotonicClock
    private lateinit var coordinator: SyncPlaybackCoordinator
    private var idSeed = 900

    private fun build(scope: CoroutineScope) {
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
            )
    }

    /** Session A: leader, clock ready, every track this test needs playable on both devices. */
    private suspend fun connectAsLeader(
        scope: TestScope,
        generation: Long = 1,
    ) {
        scope.runCurrent()
        session.setClock(SessionClockEstimate(offsetToLeaderUs = 0, rttP95Us = 8_000, ready = true))
        // Session A is given the **maximum** `LEAD = max(120 ms, 4 x rtt_p95)` (clamped at 2 s by
        // `SessionClock.MAX_LEAD_US`), so every deadline it stamps is still in the future after
        // Session B has authenticated and started its own playback. Without that, Session A's
        // scheduled nodes fire while Session B is being built and the race these tests exist for
        // would be over before the assertions began — which is exactly how the first draft of the
        // scheduled-chain test passed vacuously.
        session.rttP95Us = LONG_LEAD_RTT_US
        session.currentAuthGeneration = generation
        session.emit(ControlEvent.Connected(SyncTestValues.leaderPeerId, SessionId("S$generation"), true))
        scope.runCurrent()
        for (hash in listOf(HASH_A, HASH_X, HASH_Y, HASH_Z)) {
            content.localHashes.add(hash.value)
            content.peerHashes.add(hash.value)
        }
        player.calls.clear()
    }

    /**
     * Session A's leader issues an authoritative `PLAY`, the transport confirms it went out, and its
     * **local** apply then parks inside `player.prepare` — the exact position ADR-024 Amendment A2's
     * commit point creates and A3 is about. Returns the gate holding it.
     */
    private suspend fun sentPlayBlockedInPrepare(scope: TestScope): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        player.gate = gate
        player.gateOn = { it is FakeSyncPlayer.Call.Prepare && it.contentHash == HASH_A }
        coordinator.playSynchronized(HASH_A)
        scope.runCurrent()
        assertEquals(
            1,
            session.sentOfType<PlaybackMessage.Play>().size,
            "the premise: PLAY A was actually written to the wire, so A2 committed its command_seq",
        )
        assertEquals(
            listOf<FakeSyncPlayer.Call>(FakeSyncPlayer.Call.Prepare(HASH_A, 0L)),
            player.calls,
            "the premise: its local apply is now parked inside prepare",
        )
        return gate
    }

    /** A full authentication boundary: the link drops, the generation moves, a new session opens. */
    private suspend fun boundary(
        scope: TestScope,
        generation: Long,
    ) {
        session.emit(ControlEvent.LinkLost(LinkLossReason.NETWORK))
        scope.runCurrent()
        session.currentAuthGeneration = generation
        // Session B stamps ordinary 120 ms deadlines, so its own work lands well before any
        // Session-A deadline arrives.
        session.rttP95Us = 8_000
        session.emit(ControlEvent.Connected(SyncTestValues.leaderPeerId, SessionId("S$generation"), true))
        scope.runCurrent()
    }

    /**
     * Session B, built to be distinguishable from Session A at every point A could touch: a
     * three-item queue whose **current item is the middle one**, a live timeline for it, and a live
     * playback epoch.
     *
     * @param startPlayback whether to let B's scheduled start actually fire. Left false by the epoch
     *   test, which needs B's scheduled action still pending when Session A's continuation runs.
     * @return the session instant B's `PLAY` was stamped for.
     */
    private suspend fun establishSessionB(
        scope: TestScope,
        startPlayback: Boolean = true,
        currentIsLast: Boolean = false,
    ): Long {
        coordinator.enqueue(HASH_X)
        scope.runCurrent()
        coordinator.playSynchronized(HASH_Y)
        scope.runCurrent()
        if (!currentIsLast) {
            coordinator.enqueue(HASH_Z)
            scope.runCurrent()
        }
        val deadline =
            session
                .sentOfType<PlaybackMessage.Play>()
                .last()
                .header.effectiveAtSessionUs
        if (startPlayback) {
            clock.advanceTo(deadline)
            scope.runCurrent()
        }
        return deadline
    }

    // --- Regression A: an apply-chain node created under Session A ------------------------------

    /**
     * **The defect.** `resetForNewSession` did `applyChain = null`. That detaches the *tail
     * reference*; it cancels nothing and fences nothing. `PLAY(seq n)` parked inside
     * `player.prepare`, `NEXT(seq n+1)` — already written to the wire, already committed by A2's
     * outbound consumer — waited behind it in the chain, and when the blocked `PLAY` finally
     * returned the `NEXT` woke up in **Session B** and ran `applyStep` against Session B's queue.
     *
     * `PLAY`'s own continuation was already safe (`owns` after the pre-roll). The command *behind*
     * it was not, because nothing between "the previous node finished" and "mutate the queue" ever
     * asked which session had authorised it.
     */
    @Test
    fun `an old session's queued NEXT has zero effect on the session that replaced it`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connectAsLeader(this)
            val gateA = sentPlayBlockedInPrepare(this)

            coordinator.next()
            runCurrent()
            assertEquals(
                1,
                session.sentOfType<PlaybackMessage.Next>().size,
                "the premise: NEXT A reached the wire too, so its command_seq is committed",
            )
            assertEquals(
                listOf<FakeSyncPlayer.Call>(FakeSyncPlayer.Call.Prepare(HASH_A, 0L)),
                player.calls,
                "the premise: NEXT A's apply is queued behind the blocked PLAY A",
            )

            boundary(this, generation = 2)
            establishSessionB(this)

            val queueBefore = coordinator.queueState.value
            val diagnosticsBefore = coordinator.diagnostics.value
            val callsBefore = player.calls.toList()
            val sentBefore = session.sent.toList()
            assertEquals(HASH_Y, queueBefore.currentItem?.trackHash, "Session B is playing the middle item")

            gateA.complete(Unit)
            runCurrent()

            assertEquals(queueBefore, coordinator.queueState.value, "Session A's NEXT may not step Session B's queue")
            assertEquals(callsBefore, player.calls, "and may not reach Session B's player")
            assertEquals(sentBefore, session.sent, "and may not put a frame on Session B's wire")
            assertEquals(diagnosticsBefore, coordinator.diagnostics.value, "and may not alter one Session-B diagnostic")
        }

    /**
     * Regression B (brief §15): Session B's first apply must not join, or wait behind, Session A's
     * blocked chain. The chain is *session-owned*; a boundary starts a fresh one.
     *
     * Note the ordering of this test: everything Session B does happens while Session A's apply is
     * still parked, and A is released only at the very end.
     */
    @Test
    fun `a new session's first apply does not wait for the old session's blocked apply chain`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connectAsLeader(this)
            val gateA = sentPlayBlockedInPrepare(this)
            coordinator.next()
            runCurrent()

            boundary(this, generation = 2)

            // Session A's apply is still parked, and stays parked for the whole of Session B's work.
            coordinator.playSynchronized(HASH_X)
            runCurrent()
            val deadline =
                session
                    .sentOfType<PlaybackMessage.Play>()
                    .last()
                    .header.effectiveAtSessionUs
            clock.advanceTo(deadline)
            runCurrent()

            assertTrue(
                player.calls.contains(FakeSyncPlayer.Call.Prepare(HASH_X, 0L)),
                "Session B's own apply ran while Session A's was still blocked",
            )
            assertTrue(player.calls.contains(FakeSyncPlayer.Call.Start), "and reached its scheduled start")
            assertEquals(HASH_X, coordinator.diagnostics.value.currentTrackHash)

            val callsBefore = player.calls.toList()
            val queueBefore = coordinator.queueState.value
            val diagnosticsBefore = coordinator.diagnostics.value
            gateA.complete(Unit)
            runCurrent()
            assertEquals(callsBefore, player.calls, "and releasing Session A afterwards changes nothing")
            assertEquals(queueBefore, coordinator.queueState.value)
            assertEquals(diagnosticsBefore, coordinator.diagnostics.value)
        }

    /**
     * Regression C (brief §16): `applySeek` read `currentEpochToken` and re-anchored `timeline`
     * with **no** ownership proof at all. A Session-A `SEEK` waking in Session B therefore
     * re-anchored Session B's timeline to Session A's target instant, and every drift measurement
     * afterwards was taken against a timeline no leader had ever authorised.
     *
     * The re-anchor is proved through the one number it changes: `localDriftMs`. Session B's player
     * is placed exactly on Session B's own timeline, so a correct fence leaves the drift at zero and
     * a re-anchored timeline cannot.
     */
    @Test
    fun `an old session's queued SEEK never reanchors the new session's timeline`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connectAsLeader(this)
            val gateA = sentPlayBlockedInPrepare(this)

            coordinator.seek(SEEK_TARGET_MS)
            runCurrent()
            assertEquals(
                1,
                session.sentOfType<PlaybackMessage.Seek>().size,
                "the premise: SEEK A reached the wire and is queued behind the blocked PLAY A",
            )

            boundary(this, generation = 2)
            val anchorB = establishSessionB(this)

            val diagnosticsBefore = coordinator.diagnostics.value
            val deadlinesBefore = clock.pendingDeadlines.toList()
            gateA.complete(Unit)
            runCurrent()

            assertEquals(diagnosticsBefore, coordinator.diagnostics.value, "no Session-B diagnostic moved")
            assertEquals(deadlinesBefore, clock.pendingDeadlines, "and Session A scheduled nothing into Session B")
            assertTrue(
                player.calls.none { it == FakeSyncPlayer.Call.Seek(SEEK_TARGET_MS) },
                "Session A's seek target never reached Session B's player",
            )

            // The timeline itself: place B's player exactly where B's own anchor says it should be
            // one cadence tick from now, and assert the measured drift is zero.
            val tickAtUs = clock.nowUs() + POSITION_REPORT_US
            player.setState(
                PlayerState(
                    positionMs = (tickAtUs - anchorB) / 1_000,
                    durationMs = 3_600_000,
                    playing = true,
                    rate = 1.0,
                ),
            )
            clock.advanceTo(tickAtUs)
            runCurrent()
            assertEquals(
                0L,
                coordinator.diagnostics.value.localDriftMs,
                "Session B is still measured against Session B's anchor",
            )
            assertEquals(0, coordinator.diagnostics.value.hardSeekCount, "so nothing on the ladder fired")
        }

    /**
     * The `applyTransport` half of the same defect: `PAUSE`/`RESUME` read `currentEpochToken` and
     * re-anchored `timeline` before proving anything. Asserted the same way, plus the two
     * diagnostics `scheduleAt` writes at arm time (`lateCommandCount`, `syncState`) which a retired
     * command must not touch either.
     */
    @Test
    fun `an old session's queued PAUSE never reanchors the new session's timeline`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connectAsLeader(this)
            val gateA = sentPlayBlockedInPrepare(this)

            player.setState(PlayerState(positionMs = PAUSE_POSITION_MS, durationMs = 3_600_000, playing = true, rate = 1.0))
            coordinator.pause()
            runCurrent()
            assertEquals(
                1,
                session.sentOfType<PlaybackMessage.Pause>().size,
                "the premise: PAUSE A reached the wire and is queued behind the blocked PLAY A",
            )

            boundary(this, generation = 2)
            val anchorB = establishSessionB(this)

            val diagnosticsBefore = coordinator.diagnostics.value
            val deadlinesBefore = clock.pendingDeadlines.toList()
            gateA.complete(Unit)
            runCurrent()

            assertEquals(diagnosticsBefore, coordinator.diagnostics.value, "no Session-B diagnostic moved")
            assertEquals(deadlinesBefore, clock.pendingDeadlines, "and Session A scheduled nothing into Session B")
            assertTrue(player.calls.none { it == FakeSyncPlayer.Call.Pause }, "Session B's player was never paused")

            val tickAtUs = clock.nowUs() + POSITION_REPORT_US
            player.setState(
                PlayerState(
                    positionMs = (tickAtUs - anchorB) / 1_000,
                    durationMs = 3_600_000,
                    playing = true,
                    rate = 1.0,
                ),
            )
            clock.advanceTo(tickAtUs)
            runCurrent()
            assertEquals(0L, coordinator.diagnostics.value.localDriftMs, "and B's timeline still says it is playing")
        }

    /**
     * The sharpest form of Regression D (brief §17/§6): a Session-A `NEXT` that runs off the end of
     * **Session B's** queue took `applyStep`'s `selected == null` branch, and that branch called
     * `playbackFence.begin()` — which *supersedes the live playback epoch*. Session B's own
     * scheduled start, armed against the token that `begin()` had just retired, then failed its
     * ownership proof and never fired.
     *
     * So the old session did not merely write state it did not own: it silently disabled the new
     * session's audio. This test keeps B's start pending across the release for exactly that reason.
     */
    @Test
    fun `an old session's queued NEXT cannot retire the new session's playback epoch`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connectAsLeader(this)
            val gateA = sentPlayBlockedInPrepare(this)
            coordinator.next()
            runCurrent()

            boundary(this, generation = 2)
            // B's queue is [X, Y] with Y — the last item — current, so an old NEXT runs off the end.
            val deadlineB = establishSessionB(this, startPlayback = false, currentIsLast = true)
            assertEquals(
                HASH_Y,
                coordinator.queueState.value.currentItem
                    ?.trackHash,
            )
            assertTrue(
                clock.pendingDeadlines.contains(deadlineB),
                "the premise: Session B's start is armed and still waiting for its deadline",
            )

            gateA.complete(Unit)
            runCurrent()

            assertEquals(
                HASH_Y,
                coordinator.queueState.value.currentItem
                    ?.trackHash,
                "B's selection is untouched",
            )
            assertTrue(player.calls.none { it == FakeSyncPlayer.Call.Stop }, "and B was never stopped")

            clock.advanceTo(deadlineB)
            runCurrent()
            assertTrue(
                player.calls.contains(FakeSyncPlayer.Call.Start),
                "Session B's own scheduled start still fires — its playback epoch was never retired",
            )
            assertEquals(SyncState.SYNCED, coordinator.diagnostics.value.syncState)
        }

    // --- Regression E: a scheduled-chain node created under Session A ---------------------------

    /**
     * **The defect.** `scheduleAt`'s node wrote `lastScheduleErrorUs` immediately after its sleep
     * and *before* `runIfCurrent`. The player action was correctly refused, but a Session-A deadline
     * firing after Session B was live still overwrote Session B's scheduling-error figure — the
     * FR-023 number a rider would read as "this is how well the last synchronised command landed".
     *
     * `resetForNewSession` also only detached `scheduledChain`, so the node was still there to fire.
     */
    @Test
    fun `an old session's scheduled deadline firing after the boundary changes nothing`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connectAsLeader(this)
            // A PLAY whose deadline is far enough ahead to survive the boundary and B's own work.
            coordinator.playSynchronized(HASH_A)
            runCurrent()
            val deadlineA =
                session
                    .sentOfType<PlaybackMessage.Play>()
                    .last()
                    .header.effectiveAtSessionUs
            assertTrue(clock.pendingDeadlines.contains(deadlineA), "the premise: Session A's start is armed")
            assertNotNull(coordinator.diagnostics.value.currentTrackHash)

            boundary(this, generation = 2)
            establishSessionB(this)

            val diagnosticsBefore = coordinator.diagnostics.value
            val queueBefore = coordinator.queueState.value
            val callsBefore = player.calls.toList()
            val sentBefore = session.sent.toList()

            assertEquals(0L, diagnosticsBefore.lastScheduleErrorUs, "Session B's own start landed exactly on time")

            // Session A's old deadline arrives while Session B is live — measurably late, so what it
            // would have written is a different number from what Session B legitimately wrote.
            clock.advanceTo(deadlineA + LATE_BY_US)
            runCurrent()

            assertEquals(
                diagnosticsBefore.lastScheduleErrorUs,
                coordinator.diagnostics.value.lastScheduleErrorUs,
                "a retired session's deadline may not write the live session's schedule error",
            )
            assertEquals(
                diagnosticsBefore.lateCommandCount,
                coordinator.diagnostics.value.lateCommandCount,
                "nor its lateness count",
            )
            assertEquals(diagnosticsBefore, coordinator.diagnostics.value, "nor any other diagnostic")
            assertEquals(callsBefore, player.calls, "and it may not drive Session B's player")
            assertEquals(queueBefore, coordinator.queueState.value)
            assertEquals(sentBefore, session.sent)

            // And Session B's own next command still works, so the fence retired A rather than B.
            coordinator.seek(SEEK_TARGET_MS)
            runCurrent()
            val deadlineB =
                session
                    .sentOfType<PlaybackMessage.Seek>()
                    .last()
                    .header.effectiveAtSessionUs
            clock.advanceTo(deadlineB)
            runCurrent()
            assertEquals(
                FakeSyncPlayer.Call.Seek(SEEK_TARGET_MS),
                player.calls.last(),
                "Session B's own scheduled seek still reaches the player",
            )
        }

    /**
     * Brief §18's second half, with **several** nodes on the scheduled chain at the boundary rather
     * than one: three armed deadlines, all retired together, none of them able to write anything.
     */
    @Test
    fun `a boundary with several scheduled nodes retires all of them`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connectAsLeader(this)
            coordinator.playSynchronized(HASH_A)
            runCurrent()
            coordinator.seek(SEEK_TARGET_MS)
            runCurrent()
            player.setState(PlayerState(positionMs = PAUSE_POSITION_MS, durationMs = 3_600_000, playing = true, rate = 1.0))
            coordinator.pause()
            runCurrent()
            // The premise is counted in *frames sent*, not in sleeping waiters: the chain parks only
            // its head in the sleeper, and every node behind that one is waiting on the node ahead
            // of it — which is A1 Finding G's ordering property, still intact.
            val deadlinesA =
                session.sent.filterIsInstance<PlaybackMessage>().mapNotNull { message ->
                    when (message) {
                        is PlaybackMessage.Play -> message.header.effectiveAtSessionUs
                        is PlaybackMessage.Seek -> message.header.effectiveAtSessionUs
                        is PlaybackMessage.Pause -> message.header.effectiveAtSessionUs
                        else -> null
                    }
                }
            assertEquals(3, deadlinesA.size, "the premise: three Session-A actions were sent and armed")

            boundary(this, generation = 2)
            establishSessionB(this)
            player.setState(PlayerState())

            val diagnosticsBefore = coordinator.diagnostics.value
            val callsBefore = player.calls.toList()
            clock.advanceTo(deadlinesA.max() + LATE_BY_US)
            runCurrent()

            assertEquals(diagnosticsBefore, coordinator.diagnostics.value, "all three retired without writing anything")
            assertEquals(callsBefore, player.calls)
        }

    // --- same-session controls (brief §19/§20) -------------------------------------------------

    /**
     * The control for every test above: **the same interleaving with no boundary still applies both
     * commands, in `command_seq` order.** A3 adds lifetime fences; it must not disable legitimate
     * application, and it must not regress A1 Finding G's / A2's apply ordering.
     */
    @Test
    fun `within one session a command queued behind a blocked apply still applies, and in order`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connectAsLeader(this)
            val gate = sentPlayBlockedInPrepare(this)
            // Queued *after* A, so A is the current item and NEXT has somewhere to step to.
            coordinator.enqueue(HASH_X)
            runCurrent()

            coordinator.next()
            runCurrent()
            assertEquals(
                listOf<FakeSyncPlayer.Call>(FakeSyncPlayer.Call.Prepare(HASH_A, 0L)),
                player.calls,
                "NEXT's apply is queued behind PLAY's",
            )

            gate.complete(Unit)
            runCurrent()

            // PLAY A selected A; the NEXT behind it steps to X and prepares it. Both applied, in
            // order, exactly once.
            assertEquals(
                listOf<FakeSyncPlayer.Call>(FakeSyncPlayer.Call.Prepare(HASH_A, 0L), FakeSyncPlayer.Call.Prepare(HASH_X, 0L)),
                player.calls,
                "N's local effect precedes N+1's, and neither is dropped",
            )
            assertEquals(
                HASH_X,
                coordinator.queueState.value.currentItem
                    ?.trackHash,
            )
            assertEquals(HASH_X, coordinator.diagnostics.value.currentTrackHash)
        }

    /** The same control for the scheduled chain: a legitimate deadline still fires and still counts. */
    @Test
    fun `within one session a scheduled deadline still fires and still records its error`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connectAsLeader(this)
            coordinator.playSynchronized(HASH_A)
            runCurrent()
            val deadline =
                session
                    .sentOfType<PlaybackMessage.Play>()
                    .last()
                    .header.effectiveAtSessionUs

            clock.advanceTo(deadline + LATE_BY_US)
            runCurrent()

            assertEquals(FakeSyncPlayer.Call.Start, player.calls.last(), "the start still happens")
            assertEquals(
                LATE_BY_US,
                coordinator.diagnostics.value.lastScheduleErrorUs,
                "and the measurement it exists to produce is still taken",
            )
            assertEquals(SyncState.SYNCED, coordinator.diagnostics.value.syncState)
        }

    private companion object {
        val HASH_A: ContentHash = SyncTestValues.hash(11)
        val HASH_X: ContentHash = SyncTestValues.hash(12)
        val HASH_Y: ContentHash = SyncTestValues.hash(13)
        val HASH_Z: ContentHash = SyncTestValues.hash(14)

        /** Deliberately far from anything Session B ever anchors at. */
        const val SEEK_TARGET_MS = 600_000L
        const val PAUSE_POSITION_MS = 450_000L

        /** [com.ridelink.core.playback.PlaybackBounds.POSITION_REPORT_INTERVAL_MS], in microseconds. */
        const val POSITION_REPORT_US = 5_000_000L

        /** How late a deadline is let arrive, so the measured error is an exact, distinguishable number. */
        const val LATE_BY_US = 2_000L

        /**
         * `4 x rtt_p95` past `SessionClock.MAX_LEAD_US`, so Session A's `LEAD` is the 2 s clamp —
         * see `connectAsLeader` for why the audit needs it.
         */
        const val LONG_LEAD_RTT_US = 500_000L
    }
}
