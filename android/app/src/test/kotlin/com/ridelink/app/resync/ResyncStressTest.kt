package com.ridelink.app.resync

import com.ridelink.app.sync.FakeSyncPlayer
import com.ridelink.app.sync.SyncCorrection
import com.ridelink.app.sync.SyncTestValues
import com.ridelink.core.playback.PlaybackMessage
import com.ridelink.core.playback.QueueMessage
import com.ridelink.core.resync.ResyncMessage
import com.ridelink.core.sync.SessionClockEstimate
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.control.LinkLossReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 7 stress/fault-injection coverage over [ResyncTestPair] (Phase 7, ADR-028) — this repo's
 * standing lesson (`docs/STATUS.md`) is that almost every real defect surfaced only on a *second*
 * session or under repeated cycling, never on the first pass. No wall-clock sleeps anywhere: every
 * cycle advances through [runCurrent] against the injected [kotlinx.coroutines.test.TestScope]
 * scheduler and, where a real interval matters, the fake monotonic clocks the harness already uses.
 */
@Suppress("LargeClass") // one shared harness across a growing set of fault/race scenarios; splitting would duplicate the harness
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
            // `resolvePendingPlay` (PROTOCOL §5 rule 4) will not even issue the PLAY until the
            // leader believes both sides can play it -- register it as resolvable on both, matching
            // how `SyncPlaybackTwoPeerTest` exercises a real Play.
            pair.leader.content.localHashes
                .add(SyncTestValues.hash(1).value)
            pair.leader.content.peerHashes
                .add(SyncTestValues.hash(1).value)
            pair.follower.content.localHashes
                .add(SyncTestValues.hash(1).value)
            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()
            assertEquals(
                SyncTestValues.hash(1),
                pair.leader.sync.diagnostics.value.currentTrackHash,
                "the leader must actually be playing before this test's reconnect scenario begins",
            )

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

            // Independent-review Blocker 2A/2D: the wire round trip itself does not require the
            // follower's clock (PROTOCOL §9's queue adoption is unconditional, and the snapshot must
            // not crash or corrupt state merely because the clock estimator has not produced a
            // window yet), but genuine reconciliation *does* need it here — this device's own
            // timeline was cleared by the reconnect, so it needs a full restore, and that must not
            // be declared complete before it actually happens. Before the fix this asserted
            // `RECONCILED` immediately, which was itself the bug: a follower whose state never
            // actually converged reported success regardless.
            assertEquals(
                ResyncOutcome.DEFERRED,
                pair.follower.resync.diagnostics.value.lastOutcome,
                "reconciliation is genuinely outstanding, not silently skipped, while the clock is not ready",
            )
            // `lastSnapshotCommandSeq` is written only by `ResyncCoordinator`'s own APPLIED branch —
            // never by DEFERRED_CLOCK, and never by the ordinary Phase 5 broadcast channel this
            // harness's `dropLink()` does not fully isolate — so it is an uncontaminated signal
            // specifically for *this* reconciliation, unlike `currentTrackHash` (which the follower
            // may already know from before the outage regardless of what this test does).
            assertNull(
                pair.follower.resync.diagnostics.value.lastSnapshotCommandSeq,
                "this reconciliation has not recorded a completed snapshot yet",
            )

            // The clock becomes ready — a fresh 11-sample window landing, as PROTOCOL §10 requires
            // after every reconnect. The deferred-event drain retries on `Phase5GateBounds
            // .DEFERRED_RETRY_INTERVAL_US`'s own cadence (`startDeferredDrain`), so the fake clock
            // must advance past it -- the same pattern `SyncPlaybackDeliveryAuditTest` already uses
            // for a held `Command`'s recovery -- not a real wall-clock sleep.
            pair.follower.syncSession.setClock(SessionClockEstimate(offsetToLeaderUs = 0L, rttP95Us = 8_000, ready = true))
            pair.followerClock.advanceBy(DEFERRED_RETRY_US)
            runCurrent()

            assertEquals(
                ResyncOutcome.RECONCILED,
                pair.follower.resync.diagnostics.value.lastOutcome,
                "settled once the clock catches up -- via the deferred-event drain, never a resend",
            )
            // Section 14's own finding, not a bug: `resetForNewSession()` resets `nextSeq`/
            // `lastAppliedSeq` on *both* sides symmetrically at every session boundary (pre-existing,
            // unchanged by this phase), so a snapshot built before any new command is issued in the
            // fresh generation truthfully reports a fresh floor (0) rather than the previous
            // generation's now-meaningless number. Nothing on the wire ever compares a command_seq
            // *across* generations — ReadFrameBinding/ADR-025 already scope every frame to the
            // generation that authorised it — so this is a safe renumbering, not a divergence: this
            // assertion proves *that a floor was recorded at all* by the deferred-completion path,
            // not that a specific stale-generation number survived (it correctly does not).
            assertEquals(
                0L,
                pair.follower.resync.diagnostics.value.lastSnapshotCommandSeq,
                "and this reconciliation genuinely completed and recorded the leader's fresh authoritative floor",
            )
            assertEquals(
                SyncTestValues.hash(1),
                pair.follower.sync.diagnostics.value.currentTrackHash,
                "convergence happened, not merely a label change",
            )

            // Liveness: an ordinary incremental command from the leader is accepted normally
            // afterward -- recovery restored the follower to a live, unwedged state.
            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()
            assertEquals(
                SyncTestValues.hash(1),
                pair.follower.sync.diagnostics.value.currentTrackHash,
                "an ordinary command after recovery still converges normally",
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

    // --- Blocker 1: outbound generation binding (Cases B and C) --------------------------------

    /**
     * Independent-review Blocker 1, Case B. A `STATE_SNAPSHOT` the leader has already admitted onto
     * its ordered outbound queue (`emitStateSnapshot`'s `enqueueOutbound`) can still be sitting
     * there when the authorising generation retires — the outbound consumer only reaches it later.
     * Before the fix, `ResyncRelay.send` resolved the writer live at that later instant, so the
     * item would have been written through whatever generation happened to be current. Proved here
     * by gating the resync channel's actual write (`FakeResyncSession.sendGate`, the same shape
     * `VoiceLifetimeProvenanceTest`/`ResyncLifetimeProvenanceTest` gate a real socket with) so the
     * item is genuinely still queued, not merely admitted, when the generation moves on.
     */
    @Test
    fun `blocker1 case B -- a queued STATE_SNAPSHOT that outlives its generation is refused, never wedging the queue`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)

            val gate = CompletableDeferred<Unit>()
            pair.leader.resyncSession.sendGate = gate
            pair.leader.resyncSession.deliver(ResyncMessage.StateRequest, generation = 1)
            runCurrent()
            assertTrue(
                pair.leader.resyncSession.sent
                    .isEmpty(),
                "the reply is genuinely queued, not yet written",
            )

            // Generation 1 retires and generation 2 authenticates while the item is still gated.
            pair.leader.resyncSession.liveAuthenticatedGeneration = null
            pair.reconnect(generation = 2)

            gate.complete(Unit)
            runCurrent()

            // The reconnect itself also triggers the follower's own legitimate generation-2
            // STATE_REQUEST/STATE_SNAPSHOT round trip, so `sent` may already contain that item by
            // now -- the assertion that matters is that *nothing* reached the wire under the
            // retired generation, not that the list is empty.
            assertTrue(
                pair.leader.resyncSession.sentGenerations
                    .all { it == 2L },
                "no frame authorised by the retired generation 1 was ever written: ${pair.leader.resyncSession.sentGenerations}",
            )

            // Case C: the stale item must not wedge the queue -- a fresh, generation-2-authorised
            // request still gets a fresh, generation-2-authorised answer.
            pair.leader.resyncSession.sent
                .clear()
            pair.leader.resyncSession.sentGenerations
                .clear()
            pair.leader.resyncSession.deliver(ResyncMessage.StateRequest, generation = 2)
            runCurrent()
            assertEquals(
                1,
                pair.leader.resyncSession.sent
                    .filterIsInstance<ResyncMessage.StateSnapshot>()
                    .size,
                "a following generation-2 request is answered normally -- the stale item did not wedge the writer",
            )
        }

    // --- Blocker 2, sections 17-18: paused reconnect and nothing-loaded, proven rather than argued ---

    /**
     * Independent-review section 17. `applyPlay`'s own `if (!playing) { markSynced(); return }`
     * (unchanged by this phase's routing fix) already means a paused snapshot schedules no `Start`
     * — this test proves that holds through the actual reconnect path this phase changed, so it
     * stays proven if either function changes later without someone re-deriving the reasoning.
     */
    @Test
    fun `fault -- a paused reconnect leaves the follower paused, never incorrectly starting playback`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            pair.leader.content.localHashes
                .add(SyncTestValues.hash(1).value)
            pair.leader.content.peerHashes
                .add(SyncTestValues.hash(1).value)
            pair.follower.content.localHashes
                .add(SyncTestValues.hash(1).value)
            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()
            pair.leader.sync.pause()
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()
            assertTrue(
                pair.leader.player.calls
                    .contains(FakeSyncPlayer.Call.Pause),
                "the leader genuinely paused first",
            )

            pair.dropLink()
            // Isolate the reconciliation's own effects from whatever the ordinary broadcast already
            // did before the outage.
            pair.follower.player.calls
                .clear()
            pair.reconnect(generation = 2)

            assertEquals(ResyncOutcome.RECONCILED, pair.follower.resync.diagnostics.value.lastOutcome)
            assertFalse(
                pair.follower.player.calls
                    .contains(FakeSyncPlayer.Call.Start),
                "a paused authoritative snapshot must never start playback: ${pair.follower.player.calls}",
            )
        }

    /**
     * Independent-review section 18. A snapshot whose `track_hash`/`queue_item_id` are genuinely
     * `null` (the leader really has nothing loaded) must be indistinguishable in outcome from any
     * other authoritative state — `StateSnapshotOutcome.APPLIED`, not stuck and not deferred — and
     * must leave the follower with nothing loaded either, never a stale leftover from before.
     */
    @Test
    fun `fault -- a genuinely nothing-loaded snapshot reconciles cleanly, distinct from a merely-reset timeline`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            pair.leader.content.localHashes
                .add(SyncTestValues.hash(1).value)
            pair.leader.content.peerHashes
                .add(SyncTestValues.hash(1).value)
            pair.follower.content.localHashes
                .add(SyncTestValues.hash(1).value)
            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()
            assertEquals(SyncTestValues.hash(1), pair.follower.sync.diagnostics.value.currentTrackHash)

            // The leader genuinely stops -- nothing loaded is now its own authoritative truth, not
            // merely a side effect of a session boundary it has not even had yet.
            pair.leader.sync.leaveSynchronizedMode()
            runCurrent()
            assertNull(pair.leader.sync.diagnostics.value.currentTrackHash, "the leader genuinely has nothing loaded now")

            pair.dropLink()
            pair.reconnect(generation = 2)

            assertEquals(
                ResyncOutcome.RECONCILED,
                pair.follower.resync.diagnostics.value.lastOutcome,
                "a genuinely-nothing-loaded snapshot is APPLIED, never stuck",
            )
            assertNull(
                pair.follower.sync.diagnostics.value.currentTrackHash,
                "the follower must not keep reporting its stale pre-outage track once told there is nothing loaded",
            )
        }

    // --- Blocker 2, section 22: content-unavailable defers, never gets stuck ---------------------

    /**
     * A snapshot naming a track this device does not have locally: the existing Phase 4
     * transfer-request path fires (unchanged), the outcome is `DEFERRED_CONTENT` -- not a false
     * `APPLIED`, and not stuck -- and once the transfer verifies, the *same* deferred-event/drain
     * machinery `content.observeAvailability` already wires into (§2A/2D's clock-readiness path)
     * completes the restoration and clears reconciliation. No second transfer/resync loop of this
     * mechanism's own is created.
     */
    @Test
    fun `fault -- a snapshot naming content the follower lacks defers rather than getting stuck, until the transfer verifies`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            for (hash in listOf(SyncTestValues.hash(1), SyncTestValues.hash(2))) {
                pair.leader.content.localHashes
                    .add(hash.value)
                pair.leader.content.peerHashes
                    .add(hash.value)
            }
            pair.follower.content.localHashes
                .add(SyncTestValues.hash(1).value)
            // Deliberately absent: pair.follower.content.localHashes for hash(2) -- the follower
            // does not have the leader's *next* track yet.

            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()

            pair.dropLink()
            // `dropLink()` only tears down the resync channel's liveness -- the ordinary
            // `FakeSyncSession` forwarding this fake models is a separate wire in this harness and
            // stays connected regardless, so a generation bump alone cannot stop it (both the
            // sender's write-refusal check and the receiver's admission check read the *same* live
            // `currentAuthGeneration` at their own, later times, and so always agree with each
            // other). Sever the ordinary channel directly, the same way a real outage would take
            // both wires down, so the only way the follower can learn of hash(2) is the resync
            // round trip this test means to exercise.
            pair.leader.syncSession.forwardTo(null)
            pair.leader.sync.playSynchronized(SyncTestValues.hash(2))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()
            assertEquals(
                SyncTestValues.hash(2),
                pair.leader.sync.diagnostics.value.currentTrackHash,
                "the leader moved on during the outage",
            )
            assertEquals(
                SyncTestValues.hash(1),
                pair.follower.sync.diagnostics.value.currentTrackHash,
                "the follower never received the leader's mid-outage change over the ordinary channel",
            )
            pair.leader.syncSession.forwardTo(pair.follower.syncSession)

            pair.reconnect(generation = 2)

            assertEquals(
                ResyncOutcome.DEFERRED,
                pair.follower.resync.diagnostics.value.lastOutcome,
                "content isn't available locally yet -- deferred, not falsely APPLIED",
            )
            assertTrue(
                pair.follower.content.transferRequests
                    .contains(SyncTestValues.hash(2)),
                "the existing Phase 4 transfer-request path fired, unchanged",
            )
            assertEquals(
                SyncTestValues.hash(1),
                pair.follower.sync.diagnostics.value.currentTrackHash,
                "nothing has actually converged yet -- still the pre-outage track, not a false convergence",
            )

            // The transfer verifies -- the same Phase 4 seam `SharedLibraryCoordinator` fires after a
            // real `TransferCacheRepository.commit`.
            pair.follower.content.completeTransfer(SyncTestValues.hash(2))
            runCurrent()

            assertEquals(
                ResyncOutcome.RECONCILED,
                pair.follower.resync.diagnostics.value.lastOutcome,
                "restoration completed and reconciliation cleared once content verified",
            )
            assertEquals(
                SyncTestValues.hash(2),
                pair.follower.sync.diagnostics.value.currentTrackHash,
                "genuine convergence, not merely a label change",
            )
            // No infinite loop: exactly one transfer request for this track, not one per drain retry.
            assertEquals(
                1,
                pair.follower.content.transferRequests
                    .count { it == SyncTestValues.hash(2) },
            )
        }

    // --- independent-review races 3-7: retained-snapshot ownership under further reconnects/teardown ---

    /**
     * Race 3. A snapshot retained for generation B's clock (`pendingPlaybackReconciliationGeneration
     * == 2`) must be provably inert once generation C authenticates, *before* B's clock ever became
     * ready — not merely superseded in place, but discarded outright, the same
     * `resetForNewSession`/`deferredEvents.clear()` guarantee rule 23's negotiation-ownership story
     * gives Phase 2's voice tables, applied here to a reconciliation snapshot instead.
     */
    @Test
    fun `race -- a snapshot retained for generation B is inert once generation C authenticates before B's clock is ready`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            pair.leader.content.localHashes
                .add(SyncTestValues.hash(1).value)
            pair.leader.content.peerHashes
                .add(SyncTestValues.hash(1).value)
            pair.follower.content.localHashes
                .add(SyncTestValues.hash(1).value)
            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()

            // Generation B: reconnect with the follower's clock deliberately not yet ready --
            // `pair.reconnect` always supplies a ready one, so this is the same manual sequence the
            // clock-readiness test above uses.
            pair.dropLink()
            reconnectWithFollowerClockNotReady(pair, generation = 2)
            assertEquals(ResyncOutcome.DEFERRED, pair.follower.resync.diagnostics.value.lastOutcome)
            assertEquals(
                2L,
                pair.follower.sync.diagnostics.value.pendingPlaybackReconciliationGeneration,
                "the snapshot is genuinely retained, owned by generation 2",
            )
            assertEquals(1, pair.follower.sync.diagnostics.value.deferredCommandCount)
            pair.follower.player.calls
                .clear()

            // Generation C authenticates before B's clock ever becomes ready. The leader also moves
            // to a different track while disconnected, so a leaked B effect would show up as the
            // wrong track rather than merely "any track at all".
            pair.leader.content.localHashes
                .add(SyncTestValues.hash(2).value)
            pair.leader.content.peerHashes
                .add(SyncTestValues.hash(2).value)
            pair.follower.content.localHashes
                .add(SyncTestValues.hash(2).value)
            pair.leader.syncSession.forwardTo(null)
            pair.leader.sync.playSynchronized(SyncTestValues.hash(2))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()
            pair.leader.syncSession.forwardTo(pair.follower.syncSession)

            pair.dropLink()
            pair.reconnect(generation = 3)

            assertEquals(
                ResyncOutcome.RECONCILED,
                pair.follower.resync.diagnostics.value.lastOutcome,
                "C's own reconciliation succeeds normally",
            )
            assertNull(
                pair.follower.sync.diagnostics.value.pendingPlaybackReconciliationGeneration,
                "B's retained generation never lingers once C has its own, resolved obligation",
            )
            assertEquals(
                0,
                pair.follower.sync.diagnostics.value.deferredCommandCount,
                "B's held snapshot was discarded, not merely superseded in place",
            )
            assertEquals(
                SyncTestValues.hash(2),
                pair.follower.sync.diagnostics.value.currentTrackHash,
                "converged on C's authoritative track, never B's stale one",
            )

            // Even if B's clock were hypothetically to become ready now, there is nothing left to drain.
            pair.followerClock.advanceBy(DEFERRED_RETRY_US)
            runCurrent()
            assertFalse(
                pair.follower.player.calls
                    .contains(FakeSyncPlayer.Call.Start),
                "no belated player effect from B's retained snapshot ever arrives",
            )
        }

    /**
     * Race 4. A retained snapshot applies exactly once when its clock becomes ready -- one genuine
     * restore, never one per retry tick.
     */
    @Test
    fun `race -- a retained snapshot applies exactly once when its clock becomes ready, never once per retry tick`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            pair.leader.content.localHashes
                .add(SyncTestValues.hash(1).value)
            pair.leader.content.peerHashes
                .add(SyncTestValues.hash(1).value)
            pair.follower.content.localHashes
                .add(SyncTestValues.hash(1).value)
            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()

            pair.dropLink()
            reconnectWithFollowerClockNotReady(pair, generation = 2)
            assertEquals(ResyncOutcome.DEFERRED, pair.follower.resync.diagnostics.value.lastOutcome)
            pair.follower.player.calls
                .clear()

            pair.follower.syncSession.setClock(SessionClockEstimate(offsetToLeaderUs = 0L, rttP95Us = 8_000, ready = true))
            pair.followerClock.advanceBy(DEFERRED_RETRY_US)
            runCurrent()
            assertEquals(ResyncOutcome.RECONCILED, pair.follower.resync.diagnostics.value.lastOutcome)
            // `Start` itself is scheduled at the snapshot's `effectiveAtSessionUs` (ADR-024 A4's
            // `scheduleAt`) rather than fired inline, and this harness's leader/follower fake clocks
            // do not share a base instant -- so `Select`, which `applyPlay`'s pre-roll runs
            // synchronously inside the same restore, is the uncontaminated "did a genuine restore
            // happen" signal here, exactly the way `restoreFromPlaybackState`'s comment already
            // treats the scheduled step as a separate concern from resync's own completion.
            assertEquals(
                1,
                pair.follower.player.calls
                    .count { it == FakeSyncPlayer.Call.Select(SyncTestValues.hash(1)) },
                "exactly one genuine restore from the retained snapshot's single application",
            )

            // A further retry tick must find nothing left to drain -- idempotent, not re-applied.
            pair.followerClock.advanceBy(DEFERRED_RETRY_US)
            runCurrent()
            assertEquals(
                1,
                pair.follower.player.calls
                    .count { it == FakeSyncPlayer.Call.Select(SyncTestValues.hash(1)) },
                "a later retry tick does not re-apply an already-settled snapshot",
            )
        }

    /**
     * Race 5. A duplicate delivery of the same retained snapshot (a retried frame, not a new one)
     * is held too, never merged or dropped -- but draining both produces exactly one genuine
     * restore; the duplicate finds `needsFullPlaybackRestore()` already false once the first has
     * run and takes the harmless incremental re-anchor branch instead, which touches no player call.
     */
    @Test
    fun `race -- a duplicate snapshot arriving while one is already retained causes no duplicate player effects`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            pair.leader.content.localHashes
                .add(SyncTestValues.hash(1).value)
            pair.leader.content.peerHashes
                .add(SyncTestValues.hash(1).value)
            pair.follower.content.localHashes
                .add(SyncTestValues.hash(1).value)
            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()

            pair.dropLink()
            reconnectWithFollowerClockNotReady(pair, generation = 2)
            assertEquals(ResyncOutcome.DEFERRED, pair.follower.resync.diagnostics.value.lastOutcome)
            assertEquals(1, pair.follower.sync.diagnostics.value.deferredCommandCount)

            // The identical snapshot arrives a second time -- a retried frame, still under
            // generation 2, while still retained. `onStateSnapshot` processes a message's queue half
            // and playback half independently, and Amendment A1 Finding D's arrival-order
            // preservation means *both* now hold too, purely because something is already held
            // (`AuthoritativeHoldGate.decide` holds whenever `deferredEvents` is non-empty, before
            // either half ever reaches its own clock/content check) -- so one duplicate delivery
            // grows the queue by two, not one: a `QueueSnapshot` and a second `PlaybackState`.
            val heldSnapshot =
                pair.leader.resyncSession
                    .sentOfType<ResyncMessage.StateSnapshot>()
                    .last()
            pair.follower.resyncSession.deliver(heldSnapshot, generation = 2)
            runCurrent()
            assertEquals(
                3,
                pair.follower.sync.diagnostics.value.deferredCommandCount,
                "the duplicate is held too, not silently dropped or merged -- both its queue and playback halves",
            )
            pair.follower.player.calls
                .clear()

            pair.follower.syncSession.setClock(SessionClockEstimate(offsetToLeaderUs = 0L, rttP95Us = 8_000, ready = true))
            pair.followerClock.advanceBy(DEFERRED_RETRY_US)
            runCurrent()

            assertEquals(ResyncOutcome.RECONCILED, pair.follower.resync.diagnostics.value.lastOutcome)
            // See the previous test's comment: `Select` is the synchronous, uncontaminated "a
            // genuine restore happened" signal here, not the separately-scheduled `Start`.
            assertEquals(
                1,
                pair.follower.player.calls
                    .count { it == FakeSyncPlayer.Call.Select(SyncTestValues.hash(1)) },
                "all three held entries drain, but only the first playback entry is a genuine restore -- " +
                    "the duplicate queue snapshot is a no-op re-adoption and the duplicate playback entry " +
                    "becomes a harmless incremental re-anchor, neither calling into the player again",
            )
            assertEquals(0, pair.follower.sync.diagnostics.value.deferredCommandCount)
        }

    /**
     * Race 6. Modelling exactly as much of "End Ride" as this JVM-only two-coordinator harness can
     * reach -- see the disclosed caveat on `5 complete ride cycles leave no trace of an earlier ride
     * in the next one` above for what a real `SessionCoordinator`/`SessionTeardownOwner` teardown
     * adds beyond this (`RideForegroundService`, `ControlSessionManager.shutdown()`). At this layer,
     * `onSessionLost` already runs `resetForNewSession()` synchronously — clearing `deferredEvents`
     * and cancelling (never merely detaching) `deferredDrainJob` — and `applyPeerPlaybackState`'s own
     * `stillCurrent` re-proof inside its lock is what stops a drain continuation that had already
     * resumed past that cancellation from mutating anything: cancellation is defence one, the
     * re-proof is the correctness boundary (Amendment A3's lesson, applied here).
     */
    @Test
    fun `race -- ending the ride while a snapshot is retained leaves no later player mutation`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            pair.leader.content.localHashes
                .add(SyncTestValues.hash(1).value)
            pair.leader.content.peerHashes
                .add(SyncTestValues.hash(1).value)
            pair.follower.content.localHashes
                .add(SyncTestValues.hash(1).value)
            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()

            pair.dropLink()
            reconnectWithFollowerClockNotReady(pair, generation = 2)
            assertEquals(ResyncOutcome.DEFERRED, pair.follower.resync.diagnostics.value.lastOutcome)
            assertEquals(1, pair.follower.sync.diagnostics.value.deferredCommandCount)
            pair.follower.player.calls
                .clear()

            // End Ride: the control lifetime ends with no successor authenticated yet.
            pair.follower.resyncSession.liveAuthenticatedGeneration = null
            pair.follower.resyncSession.emit(ControlEvent.LinkLost(LinkLossReason.BYE))
            pair.follower.syncSession.emit(ControlEvent.LinkLost(LinkLossReason.BYE))
            runCurrent()

            assertEquals(0, pair.follower.sync.diagnostics.value.deferredCommandCount, "teardown clears the retained snapshot outright")
            assertNull(pair.follower.sync.diagnostics.value.pendingPlaybackReconciliationGeneration)

            // Even if the drain job's own cancellation were merely a request rather than already
            // effective, nothing left in the queue and no live role means a retry tick can do nothing.
            pair.follower.syncSession.setClock(SessionClockEstimate(offsetToLeaderUs = 0L, rttP95Us = 8_000, ready = true))
            pair.followerClock.advanceBy(DEFERRED_RETRY_US)
            runCurrent()

            // `resetForNewSession()` itself fires a fire-and-forget `restoreRate()` on every session
            // loss regardless of what was retained (a pre-existing, unrelated rate-normalisation
            // safety action, not a restore) -- so the assertion that matters is that nothing
            // resembling the retained snapshot's own restore (select/load/start) ever runs, not that
            // the call list is empty.
            assertTrue(
                pair.follower.player.calls.none {
                    it is FakeSyncPlayer.Call.Select || it is FakeSyncPlayer.Call.Load || it == FakeSyncPlayer.Call.Start
                },
                "no later restore effect from the retained snapshot after teardown: ${pair.follower.player.calls}",
            )
        }

    /**
     * Race 7. Ride 1 defers on missing content (section 22's own mechanism) and is torn down before
     * that transfer ever verifies; Ride 2 starts, converges on its own track, and *then* Ride 1's
     * transfer verifies late. `content.observeAvailability`'s callback is registered once for the
     * coordinator's whole lifetime (never re-registered per ride), so this is the one retained-state
     * mechanism that can genuinely fire a late callback across a ride boundary — unlike the
     * clock-drain job, which `resetForNewSession` cancels outright. Safety here is structural rather
     * than a generation comparison: `resetForNewSession` already emptied `deferredEvents` and
     * cleared `pendingPlay`, so the late callback's `drainDeferredEvents`/`resolvePendingPlay` calls
     * find nothing left to act on.
     */
    @Test
    fun `race -- a Ride-1 retained snapshot's late content-readiness callback cannot touch Ride 2 after a full teardown and restart`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            for (hash in listOf(SyncTestValues.hash(1), SyncTestValues.hash(2), SyncTestValues.hash(3))) {
                pair.leader.content.localHashes
                    .add(hash.value)
                pair.leader.content.peerHashes
                    .add(hash.value)
            }
            pair.follower.content.localHashes
                .add(SyncTestValues.hash(1).value)
            // Deliberately absent: hash(2) -- Ride 1's content the follower never receives in time.

            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()

            pair.dropLink()
            pair.leader.syncSession.forwardTo(null)
            pair.leader.sync.playSynchronized(SyncTestValues.hash(2))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()
            pair.leader.syncSession.forwardTo(pair.follower.syncSession)

            pair.reconnect(generation = 2)
            assertEquals(ResyncOutcome.DEFERRED, pair.follower.resync.diagnostics.value.lastOutcome, "Ride 1: content isn't here yet")
            assertTrue(
                pair.follower.content.transferRequests
                    .contains(SyncTestValues.hash(2)),
            )

            // "End Ride" genuinely stops the leader's own authoritative playback before the control
            // lifetime tears down (the real UX order) -- so the leader's own `currentPlaybackIdentity`
            // is `null`, not a stale hash(2), by the time Ride 2's own reconnect-triggered resync
            // round trip fires below. Without this, that round trip would re-report the still-missing
            // hash(2) under generation 3 and legitimately hold Ride 2's own `playSynchronized(hash(3))`
            // behind it (Amendment A1 Finding D's arrival-order preservation) -- a real, separate
            // interaction this test does not mean to exercise.
            pair.leader.sync.leaveSynchronizedMode()
            runCurrent()

            // Ride 1 ends -- torn down before hash(2) ever verifies.
            pair.follower.resyncSession.liveAuthenticatedGeneration = null
            pair.follower.resyncSession.emit(ControlEvent.LinkLost(LinkLossReason.BYE))
            pair.follower.syncSession.emit(ControlEvent.LinkLost(LinkLossReason.BYE))
            pair.leader.resyncSession.liveAuthenticatedGeneration = null
            pair.leader.resyncSession.emit(ControlEvent.LinkLost(LinkLossReason.BYE))
            pair.leader.syncSession.emit(ControlEvent.LinkLost(LinkLossReason.BYE))
            runCurrent()

            // Ride 2: a fresh generation, a fresh track, converging normally.
            pair.follower.content.localHashes
                .add(SyncTestValues.hash(3).value)
            pair.connect(generation = 3)
            pair.leader.sync.playSynchronized(SyncTestValues.hash(3))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()
            assertEquals(SyncTestValues.hash(3), pair.follower.sync.diagnostics.value.currentTrackHash, "Ride 2 converged normally")
            pair.follower.player.calls
                .clear()

            // Ride 1's transfer verifies late.
            pair.follower.content.completeTransfer(SyncTestValues.hash(2))
            runCurrent()

            assertEquals(
                SyncTestValues.hash(3),
                pair.follower.sync.diagnostics.value.currentTrackHash,
                "Ride 1's late callback must not touch Ride 2's converged state",
            )
            assertTrue(
                pair.follower.player.calls
                    .isEmpty(),
                "Ride 1's late content-readiness callback produces no Ride 2 player effect: ${pair.follower.player.calls}",
            )
        }

    // --- independent-review section 23: route-transition/coexistence non-regression -------------

    /**
     * Independent-review section 23. A reconnect snapshot's restoration runs through
     * `restoreFromPlaybackState`/`applyPlay` — the ride-segment full-restore path this phase added —
     * never through `DriftController`'s ordinary per-tick correction ladder (`applyCorrection`,
     * reached only from the position-report tick, ARCHITECTURE §7.3 tier four). `route_state ==
     * transitioning` is a `DriftController` input (`DriftInput.routeTransitioning`) that suppresses
     * *that* ladder's own hard-seek tier so a transient Bluetooth reroute never spends the seek
     * budget rules already give it — it has nothing to do with resync's restore, which is not a
     * "correction" at all and must neither consult it nor be gated by it: there is exactly one
     * route-state system (`routeTransitioning`, read only by the tick loop), and this phase adds no
     * second one. This test proves the restoration side of that boundary: a full restore proceeds
     * unconditionally while `route_state == transitioning`, and spends none of `hardSeekCount`'s
     * budget doing it, because it was never drawn from in the first place.
     */
    @Test
    fun `fault -- a reconnect restoration while route_state is transitioning still restores, and spends no hard-seek budget`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            pair.leader.content.localHashes
                .add(SyncTestValues.hash(1).value)
            pair.leader.content.peerHashes
                .add(SyncTestValues.hash(1).value)
            pair.follower.content.localHashes
                .add(SyncTestValues.hash(1).value)
            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()
            assertEquals(0, pair.follower.sync.diagnostics.value.hardSeekCount, "no correction has run yet")

            // The follower's Bluetooth route is mid-transition when the reconnect's restoration
            // needs to run -- exactly the ARCHITECTURE §6's "opening the mic forces most Bluetooth
            // endpoints onto the duplex profile" moment this flag exists for.
            pair.follower.routeTransitioning.transitioning = true
            pair.follower.player.calls
                .clear()

            pair.dropLink()
            pair.reconnect(generation = 2)

            assertEquals(
                ResyncOutcome.RECONCILED,
                pair.follower.resync.diagnostics.value.lastOutcome,
                "a route transition never blocks or defers a reconnect's own restoration -- it is not a second gate",
            )
            assertEquals(
                1,
                pair.follower.player.calls
                    .count { it == FakeSyncPlayer.Call.Select(SyncTestValues.hash(1)) },
                "the restore proceeded unconditionally while transitioning: ${pair.follower.player.calls}",
            )
            assertEquals(
                0,
                pair.follower.sync.diagnostics.value.hardSeekCount,
                "the restore is not a DriftController correction, so it never draws on the hard-seek budget rules reserve for it",
            )
            assertEquals(
                SyncCorrection.NONE,
                pair.follower.sync.diagnostics.value.lastCorrection,
                "no second route-state system exists -- resync's own restore never reports through DriftController's label either",
            )
        }

    /**
     * The manual reconnect sequence the clock-readiness fault test above pioneered, extracted so
     * races 3-6 can reuse it exactly rather than re-typing eight lines of generation plumbing each:
     * `pair.reconnect` always supplies a ready clock, and these tests specifically need one that
     * is not, on the follower side only.
     */
    private suspend fun TestScope.reconnectWithFollowerClockNotReady(
        pair: ResyncTestPair,
        generation: Long,
    ) {
        pair.follower.syncSession.currentAuthGeneration = generation
        pair.follower.syncSession.setClock(null)
        pair.leader.syncSession.currentAuthGeneration = generation
        pair.leader.syncSession.setClock(SessionClockEstimate(offsetToLeaderUs = 0L, rttP95Us = 8_000, ready = true))
        pair.leader.resyncSession.currentAuthGeneration = generation
        pair.follower.resyncSession.currentAuthGeneration = generation
        pair.leader.resyncSession.liveAuthenticatedGeneration = generation
        pair.follower.resyncSession.liveAuthenticatedGeneration = generation
        pair.leader.syncSession.emit(ControlEvent.Connected(pair.follower.localPeerId, ResyncTestPair.SESSION_ID, true, generation))
        pair.follower.syncSession.emit(ControlEvent.Connected(pair.leader.localPeerId, ResyncTestPair.SESSION_ID, false, generation))
        pair.leader.resyncSession.emit(ControlEvent.Connected(pair.follower.localPeerId, ResyncTestPair.SESSION_ID, true, generation))
        pair.follower.resyncSession.emit(ControlEvent.Connected(pair.leader.localPeerId, ResyncTestPair.SESSION_ID, false, generation))
        runCurrent()
    }

    private companion object {
        const val RIDE_CYCLES = 5
        const val BOUNDED_AUDIT_CYCLES = 100
        const val RECONNECT_CYCLES = 75
        const val RECONCILIATION_CYCLES = 75
        const val OWNERSHIP_RACE_ITERATIONS = 50
        const val RANDOM_SEED = 20260919L

        /** [com.ridelink.core.playback.Phase5GateBounds.DEFERRED_RETRY_INTERVAL_US]. */
        const val DEFERRED_RETRY_US = 100_000L

        /** Comfortably past `LEAD = max(120 ms, 4 x rtt_p95)` for these tests' 8 ms p95. */
        const val LEAD_US = 200_000L
    }
}
