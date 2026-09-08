package com.ridelink.app.sync

import com.ridelink.core.model.PeerId
import com.ridelink.core.playback.PlaybackMessage
import com.ridelink.core.player.PlayerState
import com.ridelink.core.sync.SessionClock
import com.ridelink.core.sync.SessionClockEstimate
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two real [SyncPlaybackCoordinator]s, wired to each other, on **two different local clocks**.
 *
 * This is the coordinator-to-coordinator half of this phase's brief §64. It proves the properties a
 * single-coordinator test cannot: that the leader's `effective_at_session_us` means the same instant
 * on both devices once each has mapped it through its own offset, that a follower's intent comes back
 * as an authoritative command, and that a queue mutation on one side reaches the other as the
 * snapshot §9 says it must.
 *
 * **What it does not prove.** There is no TLS here and no socket — the two coordinators are joined by
 * a direct in-process pair, so this says nothing about the wire (the codecs' shared vectors and
 * `PlaybackAuthenticationGateTest`'s real-TLS run cover that) and **nothing whatsoever about audible
 * alignment**. The iOS mirror,
 * `RideLinkPlatformTests.SyncPlaybackTwoPeerTests`, runs the equivalent scenario over a real
 * authenticated TLS control connection; the asymmetry is because Android's coordinator lives in
 * `app` while the TLS test harness lives in `network`'s own test source set.
 *
 * The two peers deliberately run on offset clocks. If the implementation ever scheduled against a
 * raw local monotonic instant instead of mapping through `SessionClock`, the follower would start
 * [OFFSET_US] microseconds early or late and this test would say so.
 */
class SyncPlaybackTwoPeerTest {
    @Test
    fun `a leader's PLAY starts both devices at the same session instant`() =
        runTest(StandardTestDispatcher()) {
            val pair = Pair(this)
            pair.connect()

            pair.leader.content.localHashes
                .add(SyncTestValues.hash(1).value)
            pair.leader.content.peerHashes
                .add(SyncTestValues.hash(1).value)
            pair.follower.content.localHashes
                .add(SyncTestValues.hash(1).value)

            pair.leader.coordinator.playSynchronized(SyncTestValues.hash(1))
            runCurrent()

            val play =
                pair.leader.session
                    .sentOfType<PlaybackMessage.Play>()
                    .single()
            val effectiveAt = play.header.effectiveAtSessionUs
            assertEquals(1, play.header.commandSeq)
            assertTrue(effectiveAt > pair.leaderClock.nowUs(), "the command must be scheduled ahead, never into the past")

            // Both pre-rolled before the deadline — ARCHITECTURE §7.2's whole point.
            assertTrue(
                pair.leader.player.calls
                    .any { it is FakeSyncPlayer.Call.Prepare },
                "the leader pre-rolls",
            )
            assertTrue(
                pair.follower.player.calls
                    .any { it is FakeSyncPlayer.Call.Prepare },
                "the follower pre-rolls",
            )
            assertTrue(
                pair.leader.player.calls
                    .none { it == FakeSyncPlayer.Call.Start },
            )
            assertTrue(
                pair.follower.player.calls
                    .none { it == FakeSyncPlayer.Call.Start },
            )

            // Advance both clocks together, in session time, past the deadline.
            pair.advanceSessionTo(effectiveAt)
            runCurrent()

            val leaderStart = pair.leaderStartSessionUs ?: error("the leader never started")
            val followerStart = pair.followerStartSessionUs ?: error("the follower never started")
            assertEquals(effectiveAt, leaderStart, "the leader started at the instant it chose")
            assertEquals(
                effectiveAt,
                followerStart,
                "the follower mapped the same session instant through its own offset and started there",
            )
            assertEquals(
                0L,
                leaderStart - followerStart,
                "mapped session start error, in microseconds — a *software* figure that says nothing about audio",
            )
        }

    @Test
    fun `a follower's intent comes back as the leader's authoritative command and both apply it`() =
        runTest(StandardTestDispatcher()) {
            val pair = Pair(this)
            pair.connect()
            pair.leader.content.localHashes
                .add(SyncTestValues.hash(1).value)
            pair.leader.content.peerHashes
                .add(SyncTestValues.hash(1).value)
            pair.follower.content.localHashes
                .add(SyncTestValues.hash(1).value)
            pair.leader.coordinator.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            val effectiveAt =
                pair.leader.session
                    .sentOfType<PlaybackMessage.Play>()
                    .single()
                    .header.effectiveAtSessionUs
            pair.advanceSessionTo(effectiveAt)
            runCurrent()
            pair.clearPlayers()

            // The *follower* presses pause. It must change no audio of its own until the leader's
            // broadcast returns (ARCHITECTURE §5's optimistic-feedback rule).
            pair.follower.player.setState(PlayerState(positionMs = 4_000, durationMs = 200_000, playing = true))
            pair.follower.coordinator.pause()
            runCurrent()

            val intent =
                pair.follower.session
                    .sentOfType<PlaybackMessage.Pause>()
                    .single()
            assertEquals(0, intent.header.commandSeq, "a follower never allocates a sequence number")

            val authoritative =
                pair.leader.session
                    .sentOfType<PlaybackMessage.Pause>()
                    .single()
            assertEquals(2, authoritative.header.commandSeq, "the leader stamped the next sequence number")
            assertEquals(pair.leader.localPeerId, authoritative.header.issuedBy)

            pair.advanceSessionTo(authoritative.header.effectiveAtSessionUs)
            runCurrent()
            assertTrue(
                pair.leader.player.calls
                    .contains(FakeSyncPlayer.Call.Pause),
                "the leader paused",
            )
            assertTrue(
                pair.follower.player.calls
                    .contains(FakeSyncPlayer.Call.Pause),
                "the follower paused",
            )
        }

    @Test
    fun `a queue mutation on either side converges both peers to the same revision`() =
        runTest(StandardTestDispatcher()) {
            val pair = Pair(this)
            pair.connect()

            pair.leader.coordinator.enqueue(SyncTestValues.hash(1))
            runCurrent()
            assertEquals(1, pair.leader.coordinator.queueState.value.revision)
            assertEquals(1, pair.follower.coordinator.queueState.value.revision, "the snapshot reached the follower")

            pair.follower.coordinator.enqueue(SyncTestValues.hash(2))
            runCurrent()
            assertEquals(2, pair.leader.coordinator.queueState.value.revision, "the leader serialised the follower's intent")
            assertEquals(2, pair.follower.coordinator.queueState.value.revision)
            assertEquals(
                pair.leader.coordinator.queueState.value.items
                    .map { it.queueItemId },
                pair.follower.coordinator.queueState.value.items
                    .map { it.queueItemId },
                "both peers hold the identical queue, in the identical order",
            )
        }

    @Test
    fun `two simultaneous conflicting presses resolve identically on both phones`() =
        runTest(StandardTestDispatcher()) {
            val pair = Pair(this)
            pair.connect()
            pair.leader.content.localHashes
                .add(SyncTestValues.hash(1).value)
            pair.leader.content.peerHashes
                .add(SyncTestValues.hash(1).value)
            pair.follower.content.localHashes
                .add(SyncTestValues.hash(1).value)
            pair.leader.coordinator.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.advanceSessionTo(
                pair.leader.session
                    .sentOfType<PlaybackMessage.Play>()
                    .single()
                    .header.effectiveAtSessionUs,
            )
            runCurrent()

            // The rider seeks while the pillion pauses, "at the same instant". Determinism comes from
            // the leader having exactly one arrival order, not from comparing timestamps.
            pair.follower.coordinator.pause()
            pair.leader.coordinator.seek(30_000)
            runCurrent()

            val broadcast =
                pair.leader.session.sent
                    .filterIsInstance<PlaybackMessage>()
                    .mapNotNull { message ->
                        when (message) {
                            is PlaybackMessage.Pause -> "PAUSE" to message.header.commandSeq
                            is PlaybackMessage.Seek -> "SEEK" to message.header.commandSeq
                            else -> null
                        }
                    }

            // **Which of the two the leader saw first is deliberately not asserted.** ADR-010's
            // guarantee is that there is exactly one serialisation point, not that a particular
            // press wins — pinning an order here would be pinning the scheduler, and a test that
            // did so would fail for a reason that is not a bug. What must hold is that the two
            // presses became *one* total order, with consecutive sequence numbers, and that both
            // phones ended on the same one.
            assertEquals(2, broadcast.size, "each press produced exactly one authoritative command")
            assertEquals(setOf("PAUSE", "SEEK"), broadcast.map { it.first }.toSet())
            assertEquals(listOf(2L, 3L), broadcast.map { it.second }, "consecutive, never duplicated, never skipped")
            assertEquals(3, pair.leader.coordinator.diagnostics.value.lastAppliedCommandSeq)
            assertEquals(
                3,
                pair.follower.coordinator.diagnostics.value.lastAppliedCommandSeq,
                "both phones applied both commands, in the leader's order",
            )
        }

    // --- the harness ------------------------------------------------------------------------------

    /**
     * One peer: its own coordinator, its own fakes and — crucially — **its own clock**, offset from
     * the other's.
     */
    private class Peer(
        val localPeerId: PeerId,
        val session: FakeSyncSession,
        val player: FakeSyncPlayer,
        val content: FakeSyncContent,
        val coordinator: SyncPlaybackCoordinator,
    )

    /** Two coordinators joined so each one's `send` becomes the other's `deliver`. */
    private inner class Pair(
        private val scope: TestScope,
    ) {
        val leaderClock = FakeMonotonicClock(nowUs = LEADER_START_US)
        val followerClock = FakeMonotonicClock(nowUs = LEADER_START_US - OFFSET_US)

        var leaderStartSessionUs: Long? = null
        var followerStartSessionUs: Long? = null

        val leader: Peer
        val follower: Peer

        init {
            val leaderSession = FakeSyncSession()
            val followerSession = FakeSyncSession()
            leader = build(SyncTestValues.leaderPeerId, leaderSession, leaderClock, 100)
            follower = build(SyncTestValues.followerPeerId, followerSession, followerClock, 500)
            leaderSession.forwardTo(followerSession)
            followerSession.forwardTo(leaderSession)
        }

        private fun build(
            peerId: PeerId,
            session: FakeSyncSession,
            clock: FakeMonotonicClock,
            idBase: Int,
        ): Peer {
            val player = FakeSyncPlayer()
            val content = FakeSyncContent()
            var seed = idBase
            val coordinator =
                SyncPlaybackCoordinator(
                    scope = scope.backgroundScope,
                    monotonicNowUs = { clock.nowUs() },
                    localPeerId = peerId,
                    session = session,
                    player = player,
                    content = content,
                    sleeper = clock.sleeper,
                    routeTransitioning = { false },
                    nextQueueItemId = { SyncTestValues.ulid(seed++) },
                )
            return Peer(peerId, session, player, content, coordinator)
        }

        suspend fun connect() {
            scope.runCurrent()
            // The leader's offset to the peer is +OFFSET_US; the follower's to the leader is the
            // negation. Both are "ready", which is what the real estimator produces after its first
            // accepted 11-sample window.
            leader.session.setClock(SessionClockEstimate(offsetToLeaderUs = OFFSET_US, rttP95Us = 8_000, ready = true))
            follower.session.setClock(SessionClockEstimate(offsetToLeaderUs = OFFSET_US, rttP95Us = 8_000, ready = true))
            leader.session.emit(
                com.ridelink.network.control.ControlEvent
                    .Connected(follower.localPeerId, SESSION_ID, true),
            )
            follower.session.emit(
                com.ridelink.network.control.ControlEvent
                    .Connected(leader.localPeerId, SESSION_ID, false),
            )
            scope.runCurrent()
            clearPlayers()
            leader.session.sent.clear()
            follower.session.sent.clear()
            observeStarts()
        }

        /** Records the *session* instant at which each device's player was actually told to start. */
        private fun observeStarts() {
            leader.player.onCall = { call ->
                if (call == FakeSyncPlayer.Call.Start && leaderStartSessionUs == null) {
                    leaderStartSessionUs = leaderClock.nowUs()
                }
            }
            follower.player.onCall = { call ->
                if (call == FakeSyncPlayer.Call.Start && followerStartSessionUs == null) {
                    // The follower's local instant mapped into session time, which is the only
                    // comparison that means anything across two unrelated monotonic epochs.
                    followerStartSessionUs = SessionClock.sessionUs(followerClock.nowUs(), OFFSET_US)
                }
            }
        }

        fun clearPlayers() {
            leader.player.calls.clear()
            follower.player.calls.clear()
        }

        /** Moves both clocks to the same **session** instant, each through its own offset. */
        fun advanceSessionTo(sessionUs: Long) {
            leaderClock.advanceTo(SessionClock.localMonoUs(sessionUs, LEADER_OFFSET_TO_SESSION))
            followerClock.advanceTo(SessionClock.localMonoUs(sessionUs, OFFSET_US))
        }
    }

    private companion object {
        val SESSION_ID =
            com.ridelink.core.model
                .SessionId("two-peer")
        const val LEADER_START_US = 10_000_000L

        /**
         * The follower's monotonic clock runs 7.5 s behind the leader's — an arbitrary, unrelated
         * epoch, which is exactly what ARCHITECTURE §7.1 says two devices' monotonic clocks have.
         */
        const val OFFSET_US = 7_500_000L

        /**
         * The leader *is* the session clock (ADR-010/ARCHITECTURE §7.1), so its offset is zero. Named
         * rather than written as a bare `0` so the asymmetry with the follower's is deliberate and
         * visible.
         */
        const val LEADER_OFFSET_TO_SESSION = 0L
    }
}
