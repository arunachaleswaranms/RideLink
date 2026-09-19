package com.ridelink.app.resync

import com.ridelink.app.sync.FakeMonotonicClock
import com.ridelink.app.sync.FakeSyncContent
import com.ridelink.app.sync.FakeSyncPlayer
import com.ridelink.app.sync.FakeSyncSession
import com.ridelink.app.sync.SyncPlaybackCoordinator
import com.ridelink.app.sync.SyncTestValues
import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.sync.SessionClockEstimate
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.control.LinkLossReason
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/**
 * The shared two-peer harness for every `com.ridelink.app.resync` test: two real
 * [ResyncCoordinator]s, each driving a real [SyncPlaybackCoordinator], joined by fakes — the
 * coordinator-to-coordinator half of PROTOCOL §10 (Phase 7, ADR-028). Mirrors
 * `com.ridelink.app.sync.SyncPlaybackTwoPeerTest`'s harness shape exactly, for the same reason: it
 * proves properties a single-coordinator test cannot, here specifically that a `STATE_SNAPSHOT`
 * authorised by one control lifetime can never reconcile a different one (this phase's brief §13).
 *
 * Extracted from `ResyncCoordinatorTest` (rather than kept as its private inner class) once
 * `ResyncStressTest` needed the identical harness for long-running/repeated-cycle scenarios — one
 * harness, two test files, never two copies to keep in sync.
 *
 * **What it does not prove.** No TLS, no socket, no real clock — the codec's own shared vectors
 * (`resync-messages/`) and the gate's own unit tests cover those. This proves only the wiring
 * between them: when a request is sent, who answers it, and whose state a snapshot may touch.
 *
 * [RouteTransitionFlag], below, is this file's one addition to that shape: a settable box for
 * [SyncPlaybackCoordinator]'s `routeTransitioning` lambda parameter, since a plain `var` cannot be
 * captured by reference across [ResyncTestPair.build]'s construction order.
 */
internal class RouteTransitionFlag {
    var transitioning: Boolean = false
}

internal class ResyncPeer(
    val localPeerId: PeerId,
    val syncSession: FakeSyncSession,
    val resyncSession: FakeResyncSession,
    val sync: SyncPlaybackCoordinator,
    val resync: ResyncCoordinator,
    val manifestRefreshCalls: MutableList<Unit>,
    /** Exposed so a test can register a track as locally/peer-resolvable before playing it. */
    val content: FakeSyncContent,
    /** Exposed so a test can inspect the exact player calls a reconciliation produced. */
    val player: FakeSyncPlayer,
    /** Exposed so a test can simulate `route_state == transitioning` during a reconciliation. */
    val routeTransitioning: RouteTransitionFlag,
)

internal class ResyncTestPair(
    private val scope: TestScope,
    /** `var`: several tests mutate this mid-test to prove the revision-gated refresh (Fix 2). */
    var leaderCatalogueRevision: Long = 0L,
    var followerCatalogueRevision: Long = 0L,
) {
    val leaderClock = FakeMonotonicClock(nowUs = 10_000_000L)
    val followerClock = FakeMonotonicClock(nowUs = 2_500_000L)

    val leader: ResyncPeer
    val follower: ResyncPeer

    init {
        val leaderSyncSession = FakeSyncSession()
        val followerSyncSession = FakeSyncSession()
        val leaderResyncSession = FakeResyncSession()
        val followerResyncSession = FakeResyncSession()

        leader =
            build(
                SyncTestValues.leaderPeerId,
                leaderSyncSession,
                leaderResyncSession,
                leaderClock,
                100,
                { leaderCatalogueRevision },
            )
        follower =
            build(
                SyncTestValues.followerPeerId,
                followerSyncSession,
                followerResyncSession,
                followerClock,
                500,
                { followerCatalogueRevision },
            )

        leaderSyncSession.forwardTo(followerSyncSession)
        followerSyncSession.forwardTo(leaderSyncSession)
        leaderResyncSession.forwardTo(followerResyncSession)
        followerResyncSession.forwardTo(leaderResyncSession)
    }

    @Suppress("LongParameterList")
    private fun build(
        peerId: PeerId,
        syncSession: FakeSyncSession,
        resyncSession: FakeResyncSession,
        clock: FakeMonotonicClock,
        idBase: Int,
        catalogueRevision: () -> Long,
    ): ResyncPeer {
        var seed = idBase
        val content = FakeSyncContent()
        val player = FakeSyncPlayer()
        val routeTransitioning = RouteTransitionFlag()
        val sync =
            SyncPlaybackCoordinator(
                scope = scope.backgroundScope,
                monotonicNowUs = { clock.nowUs() },
                localPeerId = peerId,
                session = syncSession,
                player = player,
                content = content,
                sleeper = clock.sleeper,
                routeTransitioning = { routeTransitioning.transitioning },
                nextQueueItemId = { SyncTestValues.ulid(seed++) },
                // ADR-028: `emitStateSnapshot` admits `STATE_SNAPSHOT` onto this coordinator's own
                // ordered outbound queue and writes it through the same resync channel
                // `ResyncCoordinator` reads from below — never a second, independently-timed send.
                resync = resyncSession.resync,
            )
        val manifestRefreshCalls = mutableListOf<Unit>()
        val resync =
            ResyncCoordinator(
                scope = scope.backgroundScope,
                session = resyncSession,
                syncPlaybackCoordinator = sync,
                currentCatalogueRevision = catalogueRevision,
                requestManifestRefresh = { manifestRefreshCalls.add(Unit) },
                localPeerId = peerId,
            )
        return ResyncPeer(peerId, syncSession, resyncSession, sync, resync, manifestRefreshCalls, content, player, routeTransitioning)
    }

    /** Establishes the **first** session — nothing worth keeping is sent yet, so `sent` is cleared after. */
    suspend fun connect(generation: Long) {
        authenticate(generation)
        leader.resyncSession.sent.clear()
        follower.resyncSession.sent.clear()
    }

    suspend fun dropLink() {
        leader.resyncSession.liveAuthenticatedGeneration = null
        follower.resyncSession.liveAuthenticatedGeneration = null
        leader.resyncSession.emit(ControlEvent.LinkLost(LinkLossReason.NETWORK))
        follower.resyncSession.emit(ControlEvent.LinkLost(LinkLossReason.NETWORK))
        scope.runCurrent()
    }

    /**
     * A reconnect — deliberately **not** clearing `sent` afterward, unlike [connect]: the whole
     * `STATE_REQUEST`/`STATE_SNAPSHOT` round trip this triggers happens inside this call's own
     * [kotlinx.coroutines.test.TestScope.runCurrent], so a test asserting on it needs it to
     * survive the call.
     */
    suspend fun reconnect(generation: Long) = authenticate(generation)

    private suspend fun authenticate(generation: Long) {
        // Drains whatever is only queued so far — in particular the `scope.launch { events.collect {} } }`
        // calls each coordinator's `init` made — so every collector is actively subscribed
        // *before* the emits below, rather than racing to start against them (the same ordering
        // `SyncPlaybackTwoPeerTest.Pair.connect` uses, for the same reason: a `SharedFlow` with
        // replay=0 does not redeliver an emission to a collector that starts after it fired).
        scope.runCurrent()

        leader.syncSession.currentAuthGeneration = generation
        follower.syncSession.currentAuthGeneration = generation
        leader.syncSession.setClock(SessionClockEstimate(offsetToLeaderUs = 0L, rttP95Us = 8_000, ready = true))
        follower.syncSession.setClock(SessionClockEstimate(offsetToLeaderUs = 0L, rttP95Us = 8_000, ready = true))

        leader.resyncSession.currentAuthGeneration = generation
        follower.resyncSession.currentAuthGeneration = generation
        leader.resyncSession.liveAuthenticatedGeneration = generation
        follower.resyncSession.liveAuthenticatedGeneration = generation

        leader.syncSession.emit(ControlEvent.Connected(follower.localPeerId, SESSION_ID, true, generation))
        follower.syncSession.emit(ControlEvent.Connected(leader.localPeerId, SESSION_ID, false, generation))
        leader.resyncSession.emit(ControlEvent.Connected(follower.localPeerId, SESSION_ID, true, generation))
        follower.resyncSession.emit(ControlEvent.Connected(leader.localPeerId, SESSION_ID, false, generation))
        scope.runCurrent()
    }

    companion object {
        val SESSION_ID = SessionId("resync-two-peer")
    }
}
