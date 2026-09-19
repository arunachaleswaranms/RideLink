package com.ridelink.app.resync

import com.ridelink.app.sync.SyncTestValues
import com.ridelink.core.resync.ResyncMessage
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.control.LinkLossReason
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * See [ResyncTestPair] for the harness. This file covers the single-cycle wiring properties;
 * `ResyncStressTest` covers repeated-cycle and fault-injection scenarios over the same harness.
 */
class ResyncCoordinatorTest {
    @Test
    fun `a fresh first pairing sends no STATE_REQUEST`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            assertTrue(
                pair.follower.resyncSession.sent
                    .isEmpty(),
                "nothing to reconcile on a first connect",
            )
        }

    @Test
    fun `a reconnect makes the follower request state and the leader's answer reconciles it`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()

            pair.dropLink()
            pair.reconnect(generation = 2)

            val requests = pair.follower.resyncSession.sentOfType<ResyncMessage.StateRequest>()
            assertEquals(1, requests.size, "exactly one STATE_REQUEST, deduplicated by generation")
            val snapshots = pair.leader.resyncSession.sentOfType<ResyncMessage.StateSnapshot>()
            assertEquals(1, snapshots.size, "the leader answers exactly once")
            assertEquals(SyncTestValues.leaderPeerId, snapshots.single().leaderPeerId)

            val followerDiag = pair.follower.resync.diagnostics.value
            assertEquals(ResyncOutcome.RECONCILED, followerDiag.lastOutcome)
            assertEquals(false, followerDiag.requestPending)
        }

    @Test
    fun `a desynchronized follower requests state without a reconnect`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            pair.follower.sync.forceDesynchronizedForTest()
            runCurrent()
            assertEquals(
                1,
                pair.follower.resyncSession
                    .sentOfType<ResyncMessage.StateRequest>()
                    .size,
            )
            assertEquals(1, pair.follower.resync.diagnostics.value.desyncRequestCount)
        }

    @Test
    fun `a second desync signal while a request is already pending does not resend`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            // The leader's answer is blocked, so the round trip cannot auto-complete inside a
            // single runCurrent() — the request stays genuinely outstanding, which is the premise
            // this test needs (without this, the desync would self-heal before a second signal
            // could ever race it).
            pair.leader.resyncSession.sendResult = false
            pair.follower.sync.forceDesynchronizedForTest()
            runCurrent()
            assertEquals(
                1,
                pair.follower.resyncSession
                    .sentOfType<ResyncMessage.StateRequest>()
                    .size,
                "the first desync signal sends one request",
            )
            assertTrue(pair.follower.resync.diagnostics.value.requestPending, "premise: still outstanding")

            // A redundant desync report — the same overflow observed twice, or simply the
            // diagnostics flow re-emitting — while the first request is still outstanding.
            pair.follower.sync.forceDesynchronizedForTest()
            runCurrent()

            assertEquals(
                1,
                pair.follower.resyncSession
                    .sentOfType<ResyncMessage.StateRequest>()
                    .size,
                "StateResyncGate.onTrigger refuses a second send while pendingGeneration is unchanged",
            )
        }

    @Test
    fun `a foreign-generation snapshot delivered while desynchronized does not clear the desync flag`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            // Block the leader's genuine answer, for the same reason as above: this test injects
            // its own adversarial snapshot and must not have the real round trip clear desync first.
            pair.leader.resyncSession.sendResult = false
            pair.follower.sync.forceDesynchronizedForTest()
            runCurrent()
            assertTrue(pair.follower.sync.diagnostics.value.ingressDesynchronized, "premise: desynchronized")

            // A snapshot for a generation this device never authenticated at — the relay would
            // already have refused this before it ever reached a sink; delivered directly here to
            // prove the *application* layer is safe even if that gate were somehow bypassed.
            val foreignSnapshot =
                ResyncMessage.StateSnapshot(
                    leaderPeerId = SyncTestValues.leaderPeerId,
                    commandSeq = 1,
                    queueRevision = 1,
                    playback = null,
                    queueItems = emptyList(),
                    queueCurrentIndex = null,
                    manifestRevision = 0,
                    transfersInFlight = emptyList(),
                )
            pair.follower.resyncSession.deliver(foreignSnapshot, generation = 999)
            runCurrent()

            assertTrue(
                pair.follower.sync.diagnostics.value.ingressDesynchronized,
                "a snapshot for a generation this device never authenticated must not clear desync",
            )
        }

    @Test
    fun `a duplicate STATE_SNAPSHOT for the same generation is a safe no-op`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            pair.dropLink()
            pair.reconnect(generation = 2)
            val snapshot =
                pair.leader.resyncSession
                    .sentOfType<ResyncMessage.StateSnapshot>()
                    .single()
            val queueBefore = pair.follower.sync.queueState.value

            // A retransmit of the same snapshot, same generation.
            pair.follower.resyncSession.deliver(snapshot, generation = 2)
            runCurrent()

            assertEquals(queueBefore, pair.follower.sync.queueState.value, "no second, divergent application")
            assertEquals(ResyncOutcome.RECONCILED, pair.follower.resync.diagnostics.value.lastOutcome)
        }

    @Test
    fun `a delayed A snapshot after B authenticates is inert`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.dropLink()
            pair.reconnect(generation = 2)
            val staleSnapshotFromA =
                pair.leader.resyncSession
                    .sentOfType<ResyncMessage.StateSnapshot>()
                    .single()

            // B authenticates for real (generation 3) before A's own reply is ever delivered.
            pair.dropLink()
            pair.reconnect(generation = 3)
            val stateBefore = pair.follower.sync.queueState.value
            val diagBefore = pair.follower.resync.diagnostics.value

            // A's delayed reply finally arrives, still labelled with A's own generation (2) —
            // exactly what a frame read under A and dispatched late would carry.
            pair.follower.resyncSession.deliver(staleSnapshotFromA, generation = 2)
            runCurrent()

            assertEquals(stateBefore, pair.follower.sync.queueState.value, "A's snapshot never touches B's state")
            assertEquals(diagBefore.lastOutcome, pair.follower.resync.diagnostics.value.lastOutcome, "B's own outcome is untouched")
        }

    @Test
    fun `B reconciles normally even after a delayed A boundary is processed afterwards`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            pair.dropLink()
            pair.reconnect(generation = 2)
            // B's own snapshot lands and reconciles.
            assertEquals(ResyncOutcome.RECONCILED, pair.follower.resync.diagnostics.value.lastOutcome)
            val queueAfterB = pair.follower.sync.queueState.value

            // A delayed control-lifetime-A LinkLost boundary, processed after B is already live —
            // must not undo B's reconciliation (rule 13's opposite ordering).
            pair.follower.resyncSession.emit(ControlEvent.LinkLost(LinkLossReason.NETWORK))
            runCurrent()

            assertEquals(queueAfterB, pair.follower.sync.queueState.value, "B's reconciled state survives a stale A boundary")
        }

    @Test
    fun `a follower receiving a stray STATE_REQUEST refuses it and answers nothing`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            pair.follower.resyncSession.deliver(ResyncMessage.StateRequest, generation = 1)
            runCurrent()
            assertTrue(
                pair.follower.resyncSession.sent
                    .none { it is ResyncMessage.StateSnapshot },
            )
            assertEquals(1, pair.follower.resync.diagnostics.value.roleViolationCount)
        }

    @Test
    fun `the leader's answer carries its own catalogue revision, never the follower's`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this, leaderCatalogueRevision = 7L, followerCatalogueRevision = 3L)
            pair.connect(generation = 1)
            pair.dropLink()
            pair.reconnect(generation = 2)
            val snapshot =
                pair.leader.resyncSession
                    .sentOfType<ResyncMessage.StateSnapshot>()
                    .single()
            assertEquals(7L, snapshot.manifestRevision)
        }

    @Test
    fun `a session's first STATE_SNAPSHOT never triggers a manifest refresh of its own`() =
        runTest(StandardTestDispatcher()) {
            // The unconditional Connected -> requestCatalogue() (SharedLibraryCoordinator, brief
            // Fix 2) already covers this; a second, revision-gated request here would be redundant.
            val pair = ResyncTestPair(this, leaderCatalogueRevision = 7L)
            pair.connect(generation = 1)
            pair.dropLink()
            pair.reconnect(generation = 2)
            assertTrue(
                pair.follower.manifestRefreshCalls.isEmpty(),
                "the first snapshot only records the revision, never requests a refresh",
            )
        }

    @Test
    fun `a later STATE_SNAPSHOT whose manifest revision moved triggers exactly one refresh`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this, leaderCatalogueRevision = 7L)
            pair.connect(generation = 1)
            pair.dropLink()
            pair.reconnect(generation = 2)
            assertTrue(pair.follower.manifestRefreshCalls.isEmpty(), "premise: nothing yet")

            // A mid-ride desync resync (no reconnect), with the leader's catalogue having moved.
            pair.leaderCatalogueRevision = 8L
            pair.follower.sync.forceDesynchronizedForTest()
            runCurrent()

            assertEquals(
                1,
                pair.follower.manifestRefreshCalls.size,
                "a later snapshot revealing a revision change is the case Fix 2 exists for — " +
                    "nothing else would notice it before the next full session boundary",
            )
        }

    @Test
    fun `a later STATE_SNAPSHOT whose manifest revision is unchanged triggers no refresh`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this, leaderCatalogueRevision = 7L)
            pair.connect(generation = 1)
            pair.dropLink()
            pair.reconnect(generation = 2)

            pair.follower.sync.forceDesynchronizedForTest()
            runCurrent()

            assertTrue(
                pair.follower.manifestRefreshCalls.isEmpty(),
                "the leader's catalogue has not changed, so a second snapshot is not a manifest event",
            )
        }
}
