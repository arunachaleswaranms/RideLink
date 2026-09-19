package com.ridelink.app.resync

import com.ridelink.app.sync.SyncTestValues
import com.ridelink.core.playback.PlaybackMessage
import com.ridelink.core.playback.QueueMessage
import com.ridelink.core.resync.ResyncMessage
import com.ridelink.core.sync.SessionClockEstimate
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.control.LinkLossReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 7 stress/fault-injection coverage over [ResyncTestPair] (Phase 7, ADR-028) — this repo's
 * standing lesson (`docs/STATUS.md`) is that almost every real defect surfaced only on a *second*
 * session or under repeated cycling, never on the first pass. No wall-clock sleeps anywhere: every
 * cycle advances through [runCurrent] against the injected [kotlinx.coroutines.test.TestScope]
 * scheduler and, where a real interval matters, the fake monotonic clocks the harness already uses.
 */
class ResyncStressTest {
    @Test
    fun `75 reconnect cycles keep counters consistent and never wedge pending state`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            var generation = 1L
            repeat(RECONNECT_CYCLES) { cycle ->
                pair.dropLink()
                generation += 1
                pair.reconnect(generation)

                val diag = pair.follower.resync.diagnostics.value
                assertEquals(ResyncOutcome.RECONCILED, diag.lastOutcome, "cycle $cycle: leader answered and follower reconciled")
                assertFalse(diag.requestPending, "cycle $cycle: StateResyncGate must not wedge")
                assertEquals(cycle + 1, diag.reconnectRequestCount, "cycle $cycle: exactly one reconnect-triggered request per cycle")
                assertEquals(0, diag.roleViolationCount, "cycle $cycle: no stray STATE_REQUEST anywhere in this harness")

                val snapshots = pair.leader.resyncSession.sentOfType<ResyncMessage.StateSnapshot>()
                assertEquals(cycle + 1, snapshots.size, "cycle $cycle: the leader answers exactly once per cycle, never zero, never twice")
            }
        }

    @Test
    fun `75 desync-triggered reconciliation cycles remain idempotent, no queue-revision inflation`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            val stableRevision = pair.follower.sync.queueState.value.revision

            repeat(RECONCILIATION_CYCLES) { cycle ->
                pair.follower.sync.forceDesynchronizedForTest()
                runCurrent()

                assertEquals(ResyncOutcome.RECONCILED, pair.follower.resync.diagnostics.value.lastOutcome, "cycle $cycle")
                assertFalse(pair.follower.sync.diagnostics.value.ingressDesynchronized, "cycle $cycle: desync cleared")
                // The leader's own state never changed across these cycles, so a follower that
                // adopted the leader's queue_revision wholesale (PROTOCOL §9, never incrementing
                // its own) must see the identical revision every time — inflation here would mean
                // the follower is treating its own reconciliation as a mutation.
                assertEquals(stableRevision, pair.follower.sync.queueState.value.revision, "cycle $cycle: no revision inflation")
            }

            // Exactly one STATE_SNAPSHOT per cycle reached the follower — no duplicate answers and
            // no answers silently dropped.
            assertEquals(
                RECONCILIATION_CYCLES,
                pair.leader.resyncSession
                    .sentOfType<ResyncMessage.StateSnapshot>()
                    .size,
            )
        }

    @Test
    fun `a delayed A snapshot stays inert under 50 randomized reconnect-and-desync interleavings`() =
        runTest(StandardTestDispatcher()) {
            val random = Random(RANDOM_SEED)
            repeat(OWNERSHIP_RACE_ITERATIONS) { iteration ->
                val pair = ResyncTestPair(this)
                pair.connect(generation = 1)
                pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
                runCurrent()
                pair.dropLink()
                pair.reconnect(generation = 2)
                val staleSnapshotFromA =
                    pair.leader.resyncSession
                        .sentOfType<ResyncMessage.StateSnapshot>()
                        .last()

                // A random number of further reconnects — B's real authenticated generation could
                // be 3, or considerably later if several links dropped before A's own delayed reply
                // ever surfaces.
                var generation = 2L
                repeat(random.nextInt(1, 4)) {
                    pair.dropLink()
                    generation += 1
                    pair.reconnect(generation)
                }
                // Randomly also interleave a desync-triggered resync — a second, independent
                // reconciliation the stale A snapshot must not disturb either.
                if (random.nextBoolean()) {
                    pair.follower.sync.forceDesynchronizedForTest()
                    runCurrent()
                }

                val stateBefore = pair.follower.sync.queueState.value
                val diagBefore = pair.follower.resync.diagnostics.value

                // A's delayed reply, still labelled with A's own generation (2).
                pair.follower.resyncSession.deliver(staleSnapshotFromA, generation = 2)
                runCurrent()

                assertEquals(stateBefore, pair.follower.sync.queueState.value, "iteration $iteration: A's snapshot must stay inert")
                assertEquals(diagBefore.lastOutcome, pair.follower.resync.diagnostics.value.lastOutcome, "iteration $iteration")
            }
        }

    @Test
    fun `B reconciles normally under 50 randomized delayed-A-boundary orderings`() =
        runTest(StandardTestDispatcher()) {
            val random = Random(RANDOM_SEED)
            repeat(OWNERSHIP_RACE_ITERATIONS) { iteration ->
                val pair = ResyncTestPair(this)
                pair.connect(generation = 1)
                pair.dropLink()
                pair.reconnect(generation = 2)
                assertEquals(
                    ResyncOutcome.RECONCILED,
                    pair.follower.resync.diagnostics.value.lastOutcome,
                    "iteration $iteration: B's own snapshot lands and reconciles",
                )
                val queueAfterB = pair.follower.sync.queueState.value

                // A random number of stale LinkLost boundaries from the dead A lifetime, arriving
                // in an arbitrary order relative to further local activity.
                repeat(random.nextInt(1, 4)) {
                    if (random.nextBoolean()) {
                        pair.follower.sync.forceDesynchronizedForTest()
                    }
                    pair.follower.resyncSession.emit(ControlEvent.LinkLost(LinkLossReason.NETWORK))
                    runCurrent()
                }

                assertEquals(
                    queueAfterB,
                    pair.follower.sync.queueState.value,
                    "iteration $iteration: B's reconciled state survives any number of stale A boundaries",
                )
            }
        }

    // --- fault injection ---------------------------------------------------------------------------

    @Test
    fun `fault -- a link loss racing the STATE_REQUEST send still resolves under the new generation`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            pair.dropLink()
            // Reconnect to generation 2, which triggers the follower's request — but before that
            // request's own launched send actually runs, the link drops *again* and generation 3
            // authenticates. `ResyncRelay.send` (mirrored here by `FakeResyncSession.send`) resolves
            // the live writer at send time, exactly like `ManifestRelay`/`AudioStateRelay` — so the
            // request is not lost, it is simply sent under whichever generation is live by then.
            pair.follower.resyncSession.currentAuthGeneration = 2
            pair.follower.resyncSession.liveAuthenticatedGeneration = 2
            pair.follower.resyncSession.emit(ControlEvent.Connected(SyncTestValues.leaderPeerId, ResyncTestPair.SESSION_ID, false, 2))
            pair.dropLink()
            pair.reconnect(generation = 3)

            assertEquals(ResyncOutcome.RECONCILED, pair.follower.resync.diagnostics.value.lastOutcome)
            assertFalse(pair.follower.resync.diagnostics.value.requestPending)
        }

    @Test
    fun `fault -- a pending request for a generation that died does not block the next reconnect's own request`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            // The leader never answers generation 2's request at all (link truly lost before any
            // reply), so the follower's pending flag is still set to generation 2 when generation 3
            // authenticates.
            pair.leader.resyncSession.sendResult = false
            pair.dropLink()
            pair.reconnect(generation = 2)
            assertTrue(pair.follower.resync.diagnostics.value.requestPending, "premise: generation 2's request never got an answer")

            pair.leader.resyncSession.sendResult = true
            pair.dropLink()
            pair.reconnect(generation = 3)

            assertEquals(
                ResyncOutcome.RECONCILED,
                pair.follower.resync.diagnostics.value.lastOutcome,
                "generation 3's own request must not be refused as \"already pending\" because of generation 2's dead one",
            )
            assertFalse(pair.follower.resync.diagnostics.value.requestPending)
        }

    @Test
    fun `fault -- reconciliation still converges when the clock is not ready at snapshot delivery`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()

            pair.dropLink()
            // Reconnect, but the follower's own clock is not ready yet — no fresh window has landed
            // (PROTOCOL §10: "no newly scheduled synchronized command may use an assumed/zero
            // offset until the new estimator is trustworthy").
            pair.follower.syncSession.currentAuthGeneration = 2
            pair.follower.syncSession.setClock(null)
            pair.leader.syncSession.currentAuthGeneration = 2
            pair.leader.syncSession.setClock(SessionClockEstimate(offsetToLeaderUs = 0L, rttP95Us = 8_000, ready = true))
            pair.leader.resyncSession.currentAuthGeneration = 2
            pair.follower.resyncSession.currentAuthGeneration = 2
            pair.leader.resyncSession.liveAuthenticatedGeneration = 2
            pair.follower.resyncSession.liveAuthenticatedGeneration = 2
            pair.leader.syncSession.emit(ControlEvent.Connected(pair.follower.localPeerId, ResyncTestPair.SESSION_ID, true, 2))
            pair.follower.syncSession.emit(ControlEvent.Connected(pair.leader.localPeerId, ResyncTestPair.SESSION_ID, false, 2))
            pair.leader.resyncSession.emit(ControlEvent.Connected(pair.follower.localPeerId, ResyncTestPair.SESSION_ID, true, 2))
            pair.follower.resyncSession.emit(ControlEvent.Connected(pair.leader.localPeerId, ResyncTestPair.SESSION_ID, false, 2))
            runCurrent()

            // The round trip itself does not require the follower's clock: PROTOCOL §9's queue
            // adoption is unconditional, and the reconciliation must not crash or corrupt state
            // merely because the clock estimator has not produced a window yet.
            assertEquals(ResyncOutcome.RECONCILED, pair.follower.resync.diagnostics.value.lastOutcome)

            // The clock becomes ready — a fresh 11-sample window landing, as PROTOCOL §10 requires
            // after every reconnect — and normal ticking resumes without any wall-clock sleep.
            pair.follower.syncSession.setClock(SessionClockEstimate(offsetToLeaderUs = 0L, rttP95Us = 8_000, ready = true))
            runCurrent()

            assertEquals(
                ResyncOutcome.RECONCILED,
                pair.follower.resync.diagnostics.value.lastOutcome,
                "still settled once the clock catches up",
            )
        }

    @Test
    fun `fault -- an ordinary QUEUE_SNAPSHOT arriving right after a STATE_SNAPSHOT does not corrupt reconciliation`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            pair.dropLink()
            pair.reconnect(generation = 2)
            assertEquals(ResyncOutcome.RECONCILED, pair.follower.resync.diagnostics.value.lastOutcome)
            val afterStateSnapshot = pair.follower.sync.queueState.value

            // The leader's ordinary Phase 5 QUEUE_SNAPSHOT channel, carrying the *same* authoritative
            // state the STATE_SNAPSHOT just delivered — exactly what a leader's post-reconnect
            // `rebroadcastAuthoritativeState()` produces independently of Phase 7's own resync.
            pair.follower.syncSession.deliver(
                QueueMessage.Snapshot(
                    queueRevision = afterStateSnapshot.revision,
                    items = afterStateSnapshot.items,
                    currentIndex =
                        afterStateSnapshot.currentItemId?.let { id ->
                            afterStateSnapshot.items.indexOfFirst { it.queueItemId == id }
                        },
                ),
                generation = 2,
            )
            runCurrent()

            assertEquals(afterStateSnapshot, pair.follower.sync.queueState.value, "the same authoritative queue applied twice is a no-op")
        }

    @Test
    fun `fault -- an ordinary PLAYBACK_STATE arriving right after a STATE_SNAPSHOT does not corrupt reconciliation`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.dropLink()
            pair.reconnect(generation = 2)
            assertEquals(ResyncOutcome.RECONCILED, pair.follower.resync.diagnostics.value.lastOutcome)
            val diagAfterStateSnapshot = pair.follower.sync.diagnostics.value

            // The leader's ordinary Phase 5 PLAYBACK_STATE channel, carrying the *same* authoritative
            // command_seq the STATE_SNAPSHOT just delivered.
            pair.follower.syncSession.deliver(
                PlaybackMessage.PlaybackStateSnapshot(
                    commandSeq = diagAfterStateSnapshot.lastAppliedCommandSeq ?: 0,
                    queueRevision = pair.follower.sync.queueState.value.revision,
                    trackHash = null,
                    queueItemId = null,
                    positionMs = 0,
                    playing = false,
                    atSessionUs = 0,
                ),
                generation = 2,
            )
            runCurrent()

            assertEquals(
                diagAfterStateSnapshot.lastAppliedCommandSeq,
                pair.follower.sync.diagnostics.value.lastAppliedCommandSeq,
                "the same authoritative command_seq applied twice is a no-op",
            )
        }

    @Test
    fun `fault -- the clock invalidating mid-reconciliation does not corrupt an already-reconciled snapshot`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.dropLink()
            pair.reconnect(generation = 2)
            assertEquals(ResyncOutcome.RECONCILED, pair.follower.resync.diagnostics.value.lastOutcome)
            pair.leader.sync.playSynchronized(SyncTestValues.hash(2))
            runCurrent()
            val reconciledQueue = pair.follower.sync.queueState.value

            // The clock invalidates *after* this session's own reconciliation already completed — a
            // route transition unrelated to Phase 7, per PROTOCOL §10's "no newly scheduled command
            // may use the offset until the estimator is trustworthy again". It must not roll back
            // what a completed reconciliation already applied.
            pair.follower.syncSession.setClock(null)
            runCurrent()
            assertEquals(
                reconciledQueue,
                pair.follower.sync.queueState.value,
                "an invalidated clock does not roll back an already-applied snapshot",
            )

            // The clock recovers, and a genuine reconnect — a fresh generation, not a repeat of the
            // same already-latched desync signal — drives a fresh round trip exactly as it would
            // have without the intervening invalidation.
            pair.follower.syncSession.setClock(SessionClockEstimate(offsetToLeaderUs = 0L, rttP95Us = 8_000, ready = true))
            pair.dropLink()
            pair.reconnect(generation = 3)

            assertEquals(
                ResyncOutcome.RECONCILED,
                pair.follower.resync.diagnostics.value.lastOutcome,
                "reconciliation still completes once the clock recovers",
            )
            assertFalse(pair.follower.resync.diagnostics.value.requestPending, "no pending request left wedged across the invalidation")
        }

    /**
     * ADR-028's own ordering finding: `emitStateSnapshot` must admit `STATE_SNAPSHOT` onto the same
     * single ordered writer `QUEUE_SNAPSHOT`/`PLAYBACK_STATE` already use, never a second,
     * independently-timed send — otherwise a `STATE_SNAPSHOT` read at T could reach the wire after a
     * concurrently-produced `QUEUE_*`/`PLAYBACK_STATE` reflecting a later revision. Proved here by
     * gating the leader's one Phase 5 writer mid-flight (`FakeSyncSession.sendGate`, the same
     * interleaving `SyncPlaybackTwoPeerTest` already uses) while a `STATE_REQUEST` arrives: if the
     * two channels still shared independent writers, the `STATE_SNAPSHOT` would sail past the gate
     * and reach the wire first. It cannot, because both fakes now write into the same
     * [combinedWireLog][com.ridelink.app.sync.FakeSyncSession.combinedWireLog] in the order the
     * *coordinator's single consumer* actually attempted them, not the order each channel's own
     * `send()` happened to be called.
     */
    @Test
    fun `fault -- STATE_SNAPSHOT shares the leader's one ordered writer, never overtaking a held QUEUE frame`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            val wireLog = mutableListOf<Any>()
            pair.leader.syncSession.combinedWireLog = wireLog
            pair.leader.resyncSession.combinedWireLog = wireLog

            // The leader's Phase 5 writer is gated mid-flight — a queue mutation is enqueued but its
            // frame cannot yet reach the wire.
            val gate = CompletableDeferred<Unit>()
            pair.leader.syncSession.sendGate = gate
            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()

            // A STATE_REQUEST arrives while that frame is still held. The old, independently-timed
            // `session.resync.send(...)` would have answered immediately, past the gate. Injected
            // directly at the leader's sink — the exact hop `ResyncRelay.deliver` performs — rather
            // than via a real follower round trip, since only the leader's own writer matters here.
            pair.leader.resyncSession.deliver(ResyncMessage.StateRequest, generation = 1)
            runCurrent()

            assertTrue(wireLog.isEmpty(), "neither frame reached the wire while the shared writer is gated")

            gate.complete(Unit)
            runCurrent()

            assertEquals(2, wireLog.size, "both frames drained through the one writer")
            // The leader's own mutation broadcasts a QUEUE_SNAPSHOT, not an intent (`applyLeaderMutation`).
            assertTrue(wireLog[0] is QueueMessage.Snapshot, "the queue mutation, enqueued first, was also sent first")
            val snapshot = wireLog[1] as ResyncMessage.StateSnapshot
            assertTrue(
                snapshot.queueItems.any { it.trackHash == SyncTestValues.hash(1) },
                "the STATE_SNAPSHOT enqueued behind the mutation reflects it, never a stale pre-mutation read",
            )
        }

    // --- second-ride restart -------------------------------------------------------------------------

    @Test
    fun `5 complete ride cycles leave no trace of an earlier ride in the next one`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            var generation = 0L

            repeat(RIDE_CYCLES) { ride ->
                generation += 1
                pair.connect(generation)
                pair.leader.sync.playSynchronized(SyncTestValues.hash(ride + 1))
                runCurrent()

                // Operate: a reconnect mid-ride, which resyncs, then a desync-triggered resync too.
                pair.dropLink()
                generation += 1
                pair.reconnect(generation)
                assertEquals(ResyncOutcome.RECONCILED, pair.follower.resync.diagnostics.value.lastOutcome, "ride $ride: reconnect resync")

                pair.follower.sync.forceDesynchronizedForTest()
                runCurrent()
                assertEquals(ResyncOutcome.RECONCILED, pair.follower.resync.diagnostics.value.lastOutcome, "ride $ride: desync resync")
                assertFalse(pair.follower.resync.diagnostics.value.requestPending, "ride $ride: settled before ending")

                // "endRide()"/"endSession()" as far as this harness's two coordinators can see it:
                // the control lifetime ends with no successor authenticated yet — modelling
                // ADR-026's ENDING, before SessionTeardownOwner's real, Android-only teardown
                // (`RideForegroundService`, `ControlSessionManager.shutdown()`) runs. That full
                // sequence needs a real `SessionCoordinator`/`SessionTeardownOwner`, which this
                // JVM-only two-coordinator harness cannot instantiate — see the written report for
                // exactly what that leaves unverified at this layer.
                pair.leader.resyncSession.liveAuthenticatedGeneration = null
                pair.follower.resyncSession.liveAuthenticatedGeneration = null
                pair.leader.resyncSession.emit(ControlEvent.LinkLost(LinkLossReason.BYE))
                pair.follower.resyncSession.emit(ControlEvent.LinkLost(LinkLossReason.BYE))
                pair.leader.syncSession.emit(ControlEvent.LinkLost(LinkLossReason.BYE))
                pair.follower.syncSession.emit(ControlEvent.LinkLost(LinkLossReason.BYE))
                runCurrent()

                // A snapshot from the ride that just ended, delivered late — the next ride must be
                // immune to it regardless of when it turns up.
                val staleSnapshot =
                    pair.leader.resyncSession
                        .sentOfType<ResyncMessage.StateSnapshot>()
                        .last()
                val staleGeneration = generation

                // Ride N+1: a fresh generation, a fresh track, fresh queue state.
                generation += 1
                pair.connect(generation)
                pair.leader.sync.playSynchronized(SyncTestValues.hash(ride + RIDE_CYCLES + 1))
                runCurrent()
                val ride2State = pair.follower.sync.queueState.value
                val ride2Diagnostics = pair.follower.resync.diagnostics.value

                pair.follower.resyncSession.deliver(staleSnapshot, generation = staleGeneration)
                runCurrent()

                assertEquals(
                    ride2State,
                    pair.follower.sync.queueState.value,
                    "ride $ride: the previous ride's late snapshot cannot mutate the next one",
                )
                assertEquals(
                    ride2Diagnostics.lastOutcome,
                    pair.follower.resync.diagnostics.value.lastOutcome,
                    "ride $ride: the previous ride's late snapshot leaves no trace in the next ride's own diagnostics",
                )
                assertFalse(
                    pair.follower.resync.diagnostics.value.requestPending,
                    "ride $ride: no leaked pending state crossing into the next ride",
                )
            }
        }

    // --- bounded-resource audit -----------------------------------------------------------------

    @Test
    fun `100 cycles of reconnect and desync never grow ResyncDiagnostics beyond its fixed scalar shape`() =
        runTest(StandardTestDispatcher()) {
            // ResyncCoordinator.kt holds no list, map or history of any kind — isLocalLeader,
            // hasEverConnected, pendingRequestGeneration and lastKnownManifestRevision are all
            // single scalars, and ResyncDiagnostics is a data class of scalars/counters, never a
            // growing collection. This test is the executable form of that audit: whatever the
            // counters reach, the *shape* of the diagnostics value never changes, and nothing here
            // needs a cap-and-evict policy because there is nothing unbounded to cap.
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            var generation = 1L
            repeat(BOUNDED_AUDIT_CYCLES) {
                pair.dropLink()
                generation += 1
                pair.reconnect(generation)
                pair.follower.sync.forceDesynchronizedForTest()
                runCurrent()
            }
            val diag = pair.follower.resync.diagnostics.value
            assertEquals(BOUNDED_AUDIT_CYCLES, diag.reconnectRequestCount)
            assertEquals(BOUNDED_AUDIT_CYCLES, diag.desyncRequestCount)
            assertEquals(0, diag.roleViolationCount)
            assertFalse(diag.requestPending)
        }

    private companion object {
        const val RIDE_CYCLES = 5
        const val BOUNDED_AUDIT_CYCLES = 100
        const val RECONNECT_CYCLES = 75
        const val RECONCILIATION_CYCLES = 75
        const val OWNERSHIP_RACE_ITERATIONS = 50
        const val RANDOM_SEED = 20260919L
    }
}
