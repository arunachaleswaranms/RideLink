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

    // --- ADR-024 Amendment A1 (the closure audit), end to end -----------------------------------

    /**
     * Amendment A1 Finding A, as two real coordinators. **One press of Play by the pillion, on a
     * track that is not yet in the shared queue, becomes exactly one authoritative `PLAY`** — and
     * neither peer ever refuses anything for a stale revision.
     *
     * Before the amendment the follower sent `QUEUE_ADD` (revision 0) and `PLAY` (revision 0) back to
     * back; the leader accepted the add, moved to revision 1, and then refused the `PLAY`. The press
     * did nothing and the rider had to press again.
     */
    @Test
    fun `a follower's first Play on an unqueued track converges and becomes one authoritative PLAY`() =
        runTest(StandardTestDispatcher()) {
            val pair = Pair(this)
            pair.connect()
            // Both phones hold the track; only the queue is behind.
            pair.leader.content.localHashes
                .add(SyncTestValues.hash(1).value)
            pair.leader.content.peerHashes
                .add(SyncTestValues.hash(1).value)
            pair.follower.content.localHashes
                .add(SyncTestValues.hash(1).value)
            pair.follower.content.peerHashes
                .add(SyncTestValues.hash(1).value)

            // One press. Nothing else.
            pair.follower.coordinator.playSynchronized(SyncTestValues.hash(1))
            runCurrent()

            val play =
                pair.leader.session
                    .sentOfType<PlaybackMessage.Play>()
                    .single()
            assertEquals(1, play.header.commandSeq, "exactly one authoritative PLAY, from the leader")
            assertEquals(SyncTestValues.hash(1), play.trackHash)
            assertEquals(
                pair.leader.coordinator.queueState.value.revision,
                play.header.queueRevision,
                "stamped against the revision both peers hold",
            )
            assertEquals(
                0,
                pair.leader.coordinator.diagnostics.value.staleRevisionCount,
                "the leader refused nothing — no second press was needed",
            )
            assertEquals(0, pair.follower.coordinator.diagnostics.value.staleRevisionCount)
            assertEquals(
                pair.leader.coordinator.queueState.value.items
                    .map { it.queueItemId },
                pair.follower.coordinator.queueState.value.items
                    .map { it.queueItemId },
                "and the queue converged to one identical state",
            )

            // Both phones then schedule the same session instant, and start at it.
            val effectiveAt = play.header.effectiveAtSessionUs
            pair.advanceSessionTo(effectiveAt)
            runCurrent()
            assertEquals(effectiveAt, pair.leaderStartSessionUs, "the leader started at the instant it chose")
            assertEquals(effectiveAt, pair.followerStartSessionUs, "and so did the follower, through its own offset")
        }

    /**
     * Amendment A1 Finding B, as two real coordinators: a queue mutation and a playback command
     * decided in one leader order **cannot** be observed by the peer in an order that makes the
     * command invalid. The peer's own stale-revision counter is the assertion — it is the exact
     * counter the defect incremented.
     */
    @Test
    fun `a queue mutation racing a playback command is never observed in an invalid cross-order`() =
        runTest(StandardTestDispatcher()) {
            for (repetition in 0 until 8) {
                val pair = Pair(this)
                pair.connect()
                for (seed in 1..3) {
                    pair.leader.content.localHashes
                        .add(SyncTestValues.hash(seed).value)
                    pair.leader.content.peerHashes
                        .add(SyncTestValues.hash(seed).value)
                    pair.follower.content.localHashes
                        .add(SyncTestValues.hash(seed).value)
                    pair.follower.content.peerHashes
                        .add(SyncTestValues.hash(seed).value)
                }
                pair.leader.coordinator.enqueue(SyncTestValues.hash(1))
                pair.leader.coordinator.enqueue(SyncTestValues.hash(2))
                runCurrent()

                // The two users act at once, from both ends, in both directions.
                val doomed =
                    pair.leader.coordinator.queueState.value.items
                        .first()
                        .queueItemId
                if (repetition % 2 == 0) {
                    pair.leader.coordinator.removeFromQueue(doomed)
                    pair.follower.coordinator.next()
                    pair.leader.coordinator.enqueue(SyncTestValues.hash(3))
                    pair.leader.coordinator.seek(12_000)
                } else {
                    pair.follower.coordinator.next()
                    pair.leader.coordinator.removeFromQueue(doomed)
                    pair.leader.coordinator.seek(12_000)
                    pair.leader.coordinator.enqueue(SyncTestValues.hash(3))
                }
                runCurrent()

                assertEquals(
                    0,
                    pair.follower.coordinator.diagnostics.value.staleRevisionCount,
                    "repetition $repetition: the follower refused an authoritative command for a revision " +
                        "the leader had already moved past — the exact Finding B defect",
                )
                assertEquals(
                    pair.leader.coordinator.queueState.value.revision,
                    pair.follower.coordinator.queueState.value.revision,
                    "repetition $repetition: both peers converged to one revision",
                )
                assertEquals(
                    pair.leader.coordinator.queueState.value.items
                        .map { it.queueItemId },
                    pair.follower.coordinator.queueState.value.items
                        .map { it.queueItemId },
                    "repetition $repetition: and to one identical queue",
                )
                assertEquals(
                    pair.leader.coordinator.diagnostics.value.lastAppliedCommandSeq,
                    pair.follower.coordinator.diagnostics.value.lastAppliedCommandSeq,
                    "repetition $repetition: and applied the same commands",
                )
            }
        }

    /**
     * Amendment A1 Finding E across the pair: the pillion presses Play on a track only the rider
     * holds, the existing Phase 4 machinery is asked once, and when the verified cache reports it the
     * synchronised `PLAY` happens **by itself**.
     */
    @Test
    fun `a Play for content only the peer holds becomes a synchronized PLAY once the transfer verifies`() =
        runTest(StandardTestDispatcher()) {
            val pair = Pair(this)
            pair.connect()
            // The rider has it; the pillion does not, and the rider knows the pillion will once served.
            pair.leader.content.localHashes
                .add(SyncTestValues.hash(1).value)
            pair.follower.content.peerHashes
                .add(SyncTestValues.hash(1).value)

            pair.follower.coordinator.playSynchronized(SyncTestValues.hash(1))
            runCurrent()

            assertEquals(
                listOf(SyncTestValues.hash(1)),
                pair.follower.content.transferRequests,
                "PROTOCOL §5 rule 4, through the existing Phase 4 queue, asked once",
            )
            assertTrue(
                pair.leader.session
                    .sentOfType<PlaybackMessage.Play>()
                    .isEmpty(),
                "nothing may play while one phone cannot",
            )
            assertEquals(SyncState.WAITING_FOR_CONTENT, pair.follower.coordinator.diagnostics.value.syncState)

            // Phase 4 commits, and the rider learns of it the way ADR-024 §7 says.
            pair.follower.content.completeTransfer(SyncTestValues.hash(1))
            pair.leader.content.peerVerified(SyncTestValues.hash(1))
            runCurrent()

            val play =
                pair.leader.session
                    .sentOfType<PlaybackMessage.Play>()
                    .single()
            assertEquals(SyncTestValues.hash(1), play.trackHash)
            assertTrue(play.header.commandSeq >= 1, "one authoritative PLAY, with no second press")
            pair.advanceSessionTo(play.header.effectiveAtSessionUs)
            runCurrent()
            assertEquals(play.header.effectiveAtSessionUs, pair.leaderStartSessionUs)
            assertEquals(play.header.effectiveAtSessionUs, pair.followerStartSessionUs)
        }

    // --- ADR-024 Amendment A2: the peer never sees what the leader has not delivered ---------------

    /**
     * The two-peer statement of Amendment A2's whole invariant: **the leader may not hold local
     * authoritative state the peer was never told about.**
     *
     * The leader's outbound writer is stalled mid-write and its one-slot backlog filled, so the next
     * authoritative operation meets a genuinely full outbound path. What must then be true of the
     * *follower* — a real second coordinator, not an assertion about the leader's internals — is
     * that its queue revision, its `command_seq` and its player agree with the leader's throughout.
     */
    @Test
    fun `an operation the leader could not deliver leaves both peers agreeing`() =
        runTest(StandardTestDispatcher()) {
            val pair = Pair(this, leaderOutboundCapacity = 2)
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
            pair.advanceSessionTo(play.header.effectiveAtSessionUs)
            runCurrent()
            pair.clearPlayers()

            // Stall the leader's writer mid-frame, then fill its outbound path behind that frame.
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            pair.leader.session.sendGate = gate
            pair.leader.coordinator.pause()
            runCurrent()
            pair.leader.coordinator.seek(1_000)
            runCurrent()
            pair.leader.coordinator.seek(2_000)
            runCurrent()

            // The next authoritative operation cannot be admitted at all.
            pair.leader.coordinator.next()
            runCurrent()
            gate.complete(Unit)
            pair.advanceSessionTo(play.header.effectiveAtSessionUs + 4_000_000)
            runCurrent()

            val leaderDiagnostics = pair.leader.coordinator.diagnostics.value
            val followerDiagnostics = pair.follower.coordinator.diagnostics.value
            assertTrue(leaderDiagnostics.outboundAuthorityLost, "the leader knows it failed to deliver")
            assertEquals(
                leaderDiagnostics.lastAppliedCommandSeq,
                followerDiagnostics.lastAppliedCommandSeq,
                "the two peers applied exactly the same authoritative commands",
            )
            assertEquals(
                pair.leader.coordinator.queueState.value.revision,
                pair.follower.coordinator.queueState.value.revision,
                "and hold the same queue revision",
            )
            assertEquals(
                pair.leader.player.calls
                    .filter { it !is FakeSyncPlayer.Call.SetRate },
                pair.follower.player.calls
                    .filter { it !is FakeSyncPlayer.Call.SetRate },
                "and drove their players identically — the undelivered SEEKs and NEXT reached neither",
            )
        }

    /**
     * The same invariant across a **session boundary** rather than a capacity limit: the leader's
     * backlog is authorised by Session A and the boundary lands while it is still queued.
     */
    @Test
    fun `an operation stranded by a session boundary reaches neither peer`() =
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
            pair.advanceSessionTo(play.header.effectiveAtSessionUs)
            runCurrent()
            pair.clearPlayers()
            pair.follower.session.sent
                .clear()

            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            pair.leader.session.sendGate = gate
            pair.leader.coordinator.pause()
            runCurrent()
            pair.leader.coordinator.seek(9_000)
            runCurrent()

            // Session A ends on both sides; the leader's backlog is still queued.
            pair.leader.session.emit(
                com.ridelink.network.control.ControlEvent
                    .LinkLost(LINK_LOST),
            )
            pair.follower.session.emit(
                com.ridelink.network.control.ControlEvent
                    .LinkLost(LINK_LOST),
            )
            runCurrent()
            pair.reconnect(generation = 2)
            gate.complete(Unit)
            runCurrent()

            assertTrue(
                pair.follower.player.calls
                    .none { it is FakeSyncPlayer.Call.Seek && it.positionMs == 9_000L },
                "Session A's SEEK never reached the follower under Session B",
            )
            assertEquals(
                0L,
                pair.follower.coordinator.queueState.value.revision,
                "and Session B began from an empty authoritative queue on both sides",
            )
            assertEquals(0L, pair.leader.coordinator.queueState.value.revision)
        }

    /**
     * ADR-024 **Amendment A3**, across two coordinators: the leader's own local apply is blocked
     * inside its player when the session dies, and the command behind it in the apply chain has
     * already been delivered to the follower and committed by A2's outbound consumer.
     *
     * The single-coordinator mirror of this is `SyncPlaybackLifecycleAuditTest`, which is where the
     * fence itself is pinned. What this adds is the *pair* property: releasing Session A's blocked
     * apply after Session B is live corrupts neither peer's authoritative state and puts no frame on
     * Session B's wire. **No TLS and no socket** — the same limitation as every test in this class.
     */
    @Test
    fun `an old session's blocked leader apply corrupts neither peer under the session that replaced it`() =
        runTest(StandardTestDispatcher()) {
            val pair = Pair(this)
            pair.connect()
            pair.seedContent(1..2)

            // The leader's own apply parks inside its pre-roll, *after* the PLAY reached the peer.
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            pair.leader.player.gate = gate
            pair.leader.player.gateOn = {
                it is FakeSyncPlayer.Call.Prepare && it.contentHash == SyncTestValues.hash(1)
            }
            pair.leader.coordinator.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            assertTrue(
                pair.follower.player.calls
                    .any { it is FakeSyncPlayer.Call.Prepare },
                "the premise: the follower received and applied Session A's PLAY",
            )
            assertEquals(
                listOf<FakeSyncPlayer.Call>(FakeSyncPlayer.Call.Prepare(SyncTestValues.hash(1), 0L)),
                pair.leader.player.calls,
                "the premise: the leader's own apply is parked inside its pre-roll",
            )

            // A second authoritative command, delivered and committed, queued behind the blocked one.
            pair.leader.coordinator.next()
            runCurrent()
            assertEquals(
                1,
                pair.leader.session
                    .sentOfType<PlaybackMessage.Next>()
                    .size,
                "the premise: NEXT reached the follower too",
            )

            pair.dropLink()
            pair.reconnect(generation = 2)

            // Session B, on a different track, while Session A's apply is *still* parked.
            pair.leader.coordinator.playSynchronized(SyncTestValues.hash(2))
            runCurrent()
            val playB =
                pair.leader.session
                    .sentOfType<PlaybackMessage.Play>()
                    .last()
            pair.advanceSessionTo(playB.header.effectiveAtSessionUs)
            runCurrent()
            assertTrue(
                pair.follower.player.calls
                    .contains(FakeSyncPlayer.Call.Start),
                "Session B works normally without waiting for Session A's blocked apply",
            )

            val leaderQueue = pair.leader.coordinator.queueState.value
            val followerQueue = pair.follower.coordinator.queueState.value
            val leaderCalls =
                pair.leader.player.calls
                    .toList()
            val followerCalls =
                pair.follower.player.calls
                    .toList()
            val leaderSent =
                pair.leader.session.sent
                    .toList()
            val leaderTrack = pair.leader.coordinator.diagnostics.value.currentTrackHash
            val followerTrack = pair.follower.coordinator.diagnostics.value.currentTrackHash

            gate.complete(Unit)
            runCurrent()

            assertEquals(leaderQueue, pair.leader.coordinator.queueState.value, "the leader's own Session-B queue is untouched")
            assertEquals(followerQueue, pair.follower.coordinator.queueState.value, "and so is the follower's")
            assertEquals(leaderCalls, pair.leader.player.calls, "no old apply reached the leader's player")
            assertEquals(followerCalls, pair.follower.player.calls, "and none reached the follower's")
            assertEquals(
                leaderSent,
                pair.leader.session.sent,
                "and Session A's continuation put no frame on Session B's wire",
            )
            assertEquals(SyncTestValues.hash(2), leaderTrack)
            assertEquals(leaderTrack, pair.leader.coordinator.diagnostics.value.currentTrackHash)
            assertEquals(followerTrack, pair.follower.coordinator.diagnostics.value.currentTrackHash)
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
        /** Injected only by the Amendment A2 scenarios, which need the outbound edge forced. */
        private val leaderOutboundCapacity: Int = 256,
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
            leader = build(SyncTestValues.leaderPeerId, leaderSession, leaderClock, 100, leaderOutboundCapacity)
            follower = build(SyncTestValues.followerPeerId, followerSession, followerClock, 500, 256)
            leaderSession.forwardTo(followerSession)
            followerSession.forwardTo(leaderSession)
        }

        @Suppress("LongParameterList") // one per collaborator the peer owns, plus the injected bound
        private fun build(
            peerId: PeerId,
            session: FakeSyncSession,
            clock: FakeMonotonicClock,
            idBase: Int,
            outboundCapacity: Int,
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
                    outboundCapacity = outboundCapacity,
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

        /** Makes tracks [seeds] playable on both peers and known to be held by both. */
        fun seedContent(seeds: IntRange) {
            for (seed in seeds) {
                val hash = SyncTestValues.hash(seed).value
                leader.content.localHashes.add(hash)
                leader.content.peerHashes.add(hash)
                follower.content.localHashes.add(hash)
                follower.content.peerHashes.add(hash)
            }
        }

        /** Session A ends on both sides, as a Wi-Fi drop produces. */
        suspend fun dropLink() {
            leader.session.emit(
                com.ridelink.network.control.ControlEvent
                    .LinkLost(LINK_LOST),
            )
            follower.session.emit(
                com.ridelink.network.control.ControlEvent
                    .LinkLost(LINK_LOST),
            )
            scope.runCurrent()
        }

        /** A fresh authentication generation on both peers (ADR-023 §3), as a reconnect produces. */
        suspend fun reconnect(generation: Long) {
            leader.session.currentAuthGeneration = generation
            follower.session.currentAuthGeneration = generation
            leader.session.emit(
                com.ridelink.network.control.ControlEvent
                    .Connected(follower.localPeerId, SESSION_ID, true),
            )
            follower.session.emit(
                com.ridelink.network.control.ControlEvent
                    .Connected(leader.localPeerId, SESSION_ID, false),
            )
            scope.runCurrent()
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

        val LINK_LOST =
            com.ridelink.network.control.LinkLossReason
                .NETWORK
    }
}
