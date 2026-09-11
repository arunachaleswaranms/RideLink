package com.ridelink.app.sync

import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.SessionId
import com.ridelink.core.playback.DriftController
import com.ridelink.core.playback.PlaybackBounds
import com.ridelink.core.playback.PlaybackCommandHeader
import com.ridelink.core.playback.PlaybackMessage
import com.ridelink.core.playback.PlaybackRole
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
 * The six regressions ADR-024 Amendment A1 — the Phase 5 closure audit — exists for. Each test
 * fails on the code as Phase 5 shipped and passes on the code as amended; the comment on each names
 * the exact defect it pins.
 *
 * **Deterministic throughout** (Amendment A1's own test rule). [FakeMonotonicClock] is the only
 * clock, [StandardTestDispatcher] decides ordering, the ingress bound is *injected* rather than
 * raced, and [FakeSyncPlayer.gate]/[FakeSyncContent.resolveGate] land a supersession strictly inside
 * a suspension instead of hoping for an interleaving. Nothing here sleeps and nothing yields a fixed
 * number of times.
 *
 * The mirror is `RideLinkPlatformTests.SyncPlaybackClosureAuditTests`, asserting the same six
 * properties against the same pure tables (`protocol/vectors/phase5-gates/`).
 */
@Suppress("LargeClass")
class SyncPlaybackClosureAuditTest {
    private lateinit var session: FakeSyncSession
    private lateinit var player: FakeSyncPlayer
    private lateinit var content: FakeSyncContent
    private lateinit var clock: FakeMonotonicClock
    private lateinit var coordinator: SyncPlaybackCoordinator
    private var idSeed = 500

    private fun build(
        scope: CoroutineScope,
        inboundCapacity: Int = 256,
        deferredCommandCapacity: Int = 16,
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
                deferredCommandCapacity = deferredCommandCapacity,
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
    }

    // --- Finding A: a follower's first Play must not lose a race with its own queue add ----------

    /**
     * **The defect.** `playSynchronized` used to call `ensureQueued`, which sent a `QUEUE_ADD` intent
     * and returned immediately, and then issued the `PLAY` **carrying the revision it still held** —
     * revision 0. The leader accepted the add (revision → 1) and then refused the `PLAY` for a stale
     * revision. The user's first press did nothing; a second press worked, because by then the
     * snapshot had arrived.
     *
     * Not fixed by weakening the revision rule: the Play *waits* for the revision it needs.
     */
    @Test
    fun `a follower's Play for an unqueued track waits for the authoritative revision instead of being refused`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false)
            content.localHashes.add(HASH_A.value)

            coordinator.playSynchronized(HASH_A)
            runCurrent()

            val add = session.sentOfType<QueueMessage.Add>().single()
            assertEquals(HASH_A, add.items.single().trackHash)
            assertEquals(0, add.header.queueRevision, "the add legitimately carries the revision the follower holds")
            assertTrue(
                session.sentOfType<PlaybackMessage.Play>().isEmpty(),
                "the PLAY must not be sent against a revision the leader has already moved past",
            )
            assertEquals(SyncState.WAITING_FOR_QUEUE, coordinator.diagnostics.value.syncState)

            // The leader accepts the add and broadcasts revision 1.
            val queueItemId = add.items.single().queueItemId
            session.deliver(snapshot(revision = 1, items = listOf(item(queueItemId, HASH_A))))
            runCurrent()

            val play = session.sentOfType<PlaybackMessage.Play>().single()
            assertEquals(PlaybackBounds.UNASSIGNED_COMMAND_SEQ, play.header.commandSeq, "still an intent — a follower allocates nothing")
            assertEquals(1, play.header.queueRevision, "the intent carries the authoritative revision, so the leader accepts it")
            assertEquals(queueItemId, play.queueItemId, "the issuer-minted id survives the wait, so the add stays idempotent")
            assertEquals(1, coordinator.diagnostics.value.resumedPendingPlayCount, "one press produced one Play")
            assertEquals(0, coordinator.diagnostics.value.staleRevisionCount, "nothing in this valid flow is stale")
        }

    /** A snapshot that does not yet name the track keeps the Play waiting rather than racing ahead. */
    @Test
    fun `a snapshot that does not name the track leaves the Play waiting`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false)
            content.localHashes.add(HASH_A.value)
            coordinator.playSynchronized(HASH_A)
            runCurrent()

            // Revision moved, but for somebody else's add.
            session.deliver(snapshot(revision = 1, items = listOf(item(SyncTestValues.ulid(99), HASH_B))))
            runCurrent()
            assertTrue(session.sentOfType<PlaybackMessage.Play>().isEmpty(), "the Play's own item is still not authoritative")
            assertEquals(SyncState.WAITING_FOR_QUEUE, coordinator.diagnostics.value.syncState)

            val queueItemId =
                session
                    .sentOfType<QueueMessage.Add>()
                    .single()
                    .items
                    .single()
                    .queueItemId
            session.deliver(snapshot(revision = 2, items = listOf(item(queueItemId, HASH_A))))
            runCurrent()
            assertEquals(
                2,
                session
                    .sentOfType<PlaybackMessage.Play>()
                    .single()
                    .header.queueRevision,
            )
        }

    /** brief §18: a session boundary cancels the retained Play, and the snapshot that would have settled it is inert. */
    @Test
    fun `a session boundary while a Play waits for the queue leaves it inert forever`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false)
            content.localHashes.add(HASH_A.value)
            coordinator.playSynchronized(HASH_A)
            runCurrent()
            val queueItemId =
                session
                    .sentOfType<QueueMessage.Add>()
                    .single()
                    .items
                    .single()
                    .queueItemId

            session.emit(ControlEvent.LinkLost(LinkLossReason.NETWORK))
            runCurrent()
            assertEquals(1, coordinator.diagnostics.value.cancelledPendingPlayCount)

            // A brand-new session, and the snapshot the *old* request was waiting for.
            session.currentAuthGeneration = 2
            connect(this, asLeader = false)
            session.deliver(snapshot(revision = 1, items = listOf(item(queueItemId, HASH_A))))
            runCurrent()
            assertTrue(session.sentOfType<PlaybackMessage.Play>().isEmpty(), "a dead session's Play may never execute")
            assertEquals(0, coordinator.diagnostics.value.resumedPendingPlayCount)
        }

    // --- Finding B: the leader's semantic order is the wire-visible order ------------------------

    /**
     * **The defect.** The leader locked its `command_seq` allocation and its queue-revision bump, then
     * **released the lock before sending**. Two independent coroutines then raced for the socket, so a
     * `PLAY` stamped for revision *n* could reach the wire ahead of the `QUEUE_SNAPSHOT` that created
     * revision *n* — and the follower would refuse the valid command for a revision it had not been
     * told about yet. The inverse race existed too.
     *
     * The assertion is the strongest available: **replay the leader's own outbound stream through the
     * follower's stale-revision rule.** If the order the leader chose is a valid order, nothing in
     * that replay is ever rejected. This is exactly the check the receiving side performs, so a pass
     * here is a statement about the peer rather than about this class's internals.
     */
    @Test
    fun `a queue mutation and a playback command never cross the wire in an order the peer would reject`() =
        runTest(StandardTestDispatcher()) {
            for (pairing in interleavings()) {
                build(backgroundScope)
                connect(this, asLeader = true)
                content.localHashes.add(HASH_A.value)
                content.peerHashes.add(HASH_A.value)
                content.localHashes.add(HASH_B.value)
                content.peerHashes.add(HASH_B.value)
                // A queue with two items, so a REMOVE and a MOVE both have something to act on.
                coordinator.enqueue(HASH_A)
                coordinator.enqueue(HASH_B)
                runCurrent()
                session.sent.clear()

                // Both actions are started before either can complete — the interleaving the defect
                // needed. `runCurrent` then drains them under the test dispatcher.
                val baseline = coordinator.queueState.value.revision
                pairing.queueAction(coordinator, coordinator.queueState.value.items)
                pairing.playbackAction(coordinator)
                runCurrent()

                assertWireOrderAcceptable(pairing.name, baseline)
            }
        }

    /** The same, with the two actions started in the opposite order. */
    @Test
    fun `the reverse interleaving is also an order the peer accepts`() =
        runTest(StandardTestDispatcher()) {
            for (pairing in interleavings()) {
                build(backgroundScope)
                connect(this, asLeader = true)
                content.localHashes.add(HASH_A.value)
                content.peerHashes.add(HASH_A.value)
                content.localHashes.add(HASH_B.value)
                content.peerHashes.add(HASH_B.value)
                coordinator.enqueue(HASH_A)
                coordinator.enqueue(HASH_B)
                runCurrent()
                session.sent.clear()

                val baseline = coordinator.queueState.value.revision
                pairing.playbackAction(coordinator)
                pairing.queueAction(coordinator, coordinator.queueState.value.items)
                runCurrent()

                assertWireOrderAcceptable("${pairing.name} (reversed)", baseline)
            }
        }

    /**
     * Replays this leader's outbound stream through the follower's own PROTOCOL §5 rule 3 / §9 check:
     * a snapshot sets the revision the peer holds, and an authoritative playback command must name
     * exactly that revision or the peer refuses it.
     */
    private fun assertWireOrderAcceptable(
        label: String,
        baselineRevision: Long,
    ) {
        // The revision the peer already holds from the snapshots this test sent before clearing its
        // record of the wire — clearing the record does not un-tell the peer.
        var revisionKnownToPeer = baselineRevision
        for (frame in session.sent) {
            when (frame) {
                is QueueMessage.Snapshot -> revisionKnownToPeer = frame.queueRevision
                is PlaybackMessage -> {
                    val header = authoritativeHeader(frame) ?: continue
                    assertEquals(
                        revisionKnownToPeer,
                        header.queueRevision,
                        "$label: command_seq ${header.commandSeq} names revision ${header.queueRevision} " +
                            "but the peer has only been told about $revisionKnownToPeer — the peer would refuse a valid command",
                    )
                }
                else -> Unit
            }
        }
        assertTrue(
            session.sent.any { it is PlaybackMessage && authoritativeHeader(it) != null },
            "$label: the test must actually have produced an authoritative command",
        )
    }

    // --- Finding C: a frame accepted from TCP cannot vanish from the local handoff ---------------

    /**
     * **The defect.** The inbound handoff was `Channel(256, onBufferOverflow = DROP_OLDEST)`. A `PLAY`
     * could be evicted while the `PAUSE` behind it survived; `CommandOrderGate` would legitimately
     * accept the `PAUSE`, and the follower would pause a track it never loaded. The eviction was also
     * **invisible** — `trySend` on a `DROP_OLDEST` channel returns success, so the drop counter the
     * phase shipped with could never fire for an eviction at all.
     *
     * This is Amendment A1's option **A**: with room for both, both are applied, in order.
     */
    @Test
    fun `two authoritative commands behind a stalled consumer are both applied, in order`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope, inboundCapacity = 2)
            connect(this, asLeader = false)
            content.localHashes.add(HASH_A.value)

            // Stall the consumer strictly inside content resolution, holding the PLAY in flight.
            val gate = CompletableDeferred<Unit>()
            content.resolveGate = gate
            session.deliver(playCommand(seq = 1, effectiveAt = clock.nowUs()))
            runCurrent()
            assertTrue(player.calls.isEmpty(), "the consumer is parked inside resolve")

            // Two more frames arrive while it is parked. Capacity 2, so both fit.
            session.deliver(pauseCommand(seq = 2, effectiveAt = clock.nowUs(), positionMs = 5_000))
            session.deliver(resumeCommand(seq = 3, effectiveAt = clock.nowUs(), positionMs = 6_000))
            runCurrent()

            content.resolveGate = null
            gate.complete(Unit)
            runCurrent()

            assertEquals(0, coordinator.diagnostics.value.inboundOverflowCount, "nothing was refused")
            assertFalse(coordinator.diagnostics.value.ingressDesynchronized)
            assertEquals(3, coordinator.diagnostics.value.lastAppliedCommandSeq, "all three applied")
            val calls = player.calls.toList()
            assertTrue(calls.contains(FakeSyncPlayer.Call.Load(HASH_A)), "the PLAY was not lost")
            assertTrue(
                calls.indexOf(FakeSyncPlayer.Call.Pause) < calls.indexOfFirst { it == FakeSyncPlayer.Call.Seek(6_000) },
                "arrival order survived: PAUSE(2) took effect before RESUME(3)",
            )
        }

    /**
     * Amendment A1's option **B**: when the bound is genuinely reached by frames that cannot be
     * superseded, the refusal is **explicit** and synchronisation halts. Crucially, the `PAUSE` that
     * followed the refused frame is **not** applied — a `PAUSE` treated as coherent state without the
     * `PLAY` that preceded it is precisely the incoherence this finding is about.
     */
    @Test
    fun `an ingress overflow is explicit, halts synchronisation, and applies nothing incoherent`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope, inboundCapacity = 1)
            connect(this, asLeader = false)
            content.localHashes.add(HASH_A.value)

            val gate = CompletableDeferred<Unit>()
            content.resolveGate = gate
            session.deliver(playCommand(seq = 1, effectiveAt = clock.nowUs()))
            runCurrent()

            // One fits; the second has nowhere to go and is refused rather than evicting the first.
            session.deliver(pauseCommand(seq = 2, effectiveAt = clock.nowUs(), positionMs = 5_000))
            session.deliver(resumeCommand(seq = 3, effectiveAt = clock.nowUs(), positionMs = 6_000))
            runCurrent()

            content.resolveGate = null
            gate.complete(Unit)
            runCurrent()

            val diagnostics = coordinator.diagnostics.value
            assertEquals(1, diagnostics.inboundOverflowCount, "the refusal is counted — it was structurally impossible to count before")
            assertTrue(diagnostics.ingressDesynchronized, "incremental state is explicitly no longer trusted")
            assertEquals(SyncState.DESYNCHRONIZED, diagnostics.syncState)
            assertEquals(1, diagnostics.lastReceivedCommandSeq, "the halt spends no sequence number")
            assertFalse(
                player.calls.contains(FakeSyncPlayer.Call.Pause),
                "a PAUSE behind a refused frame is never applied as coherent state",
            )
        }

    /** And the halt ends only on authoritative full state, which restores what the halt could not apply. */
    @Test
    fun `authoritative full state reconciles a halted follower and restores playback`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope, inboundCapacity = 1)
            connect(this, asLeader = false)
            content.localHashes.add(HASH_A.value)
            val gate = CompletableDeferred<Unit>()
            content.resolveGate = gate
            session.deliver(playCommand(seq = 1, effectiveAt = clock.nowUs()))
            runCurrent()
            session.deliver(pauseCommand(seq = 2, effectiveAt = clock.nowUs(), positionMs = 5_000))
            session.deliver(resumeCommand(seq = 3, effectiveAt = clock.nowUs(), positionMs = 6_000))
            runCurrent()
            content.resolveGate = null
            gate.complete(Unit)
            runCurrent()
            assertTrue(coordinator.diagnostics.value.ingressDesynchronized)

            // A further incremental command changes nothing while the halt is in force.
            session.deliver(pauseCommand(seq = 4, effectiveAt = clock.nowUs(), positionMs = 9_000))
            runCurrent()
            assertEquals(1, coordinator.diagnostics.value.lastReceivedCommandSeq, "still halted, still spending nothing")

            // PROTOCOL §9's snapshot reconciles the queue; §5's PLAYBACK_STATE reconciles playback and,
            // on this path only, restores what is playing (ADR-024 Amendment A1).
            player.calls.clear()
            // One at a time: this coordinator's ingress bound is **1** for the sake of the overflow
            // above, so two reconciliation frames offered back to back would have the second refused
            // for want of room. That is a real property of a bounded queue, asserted on its own
            // below; recovery needs a moment where the queue has space, which at the production bound
            // of 256 (with latest-wins coalescing) any non-pathological link provides.
            session.deliver(snapshot(revision = 3, items = listOf(item(SyncTestValues.ulid(1), HASH_A))))
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
            )
            runCurrent()

            val diagnostics = coordinator.diagnostics.value
            assertFalse(diagnostics.ingressDesynchronized, "authoritative full state is what ends the halt")
            assertEquals(7, diagnostics.lastAppliedCommandSeq, "ordering resumes from the authoritative value")
            assertEquals(3, diagnostics.queueRevision)
            assertTrue(
                player.calls.contains(FakeSyncPlayer.Call.Load(HASH_A)) &&
                    player.calls.contains(FakeSyncPlayer.Call.Seek(12_000)),
                "the snapshot loads what the halt could not — PROTOCOL §5 rule 2 applies its past instant immediately",
            )
            assertTrue(player.calls.contains(FakeSyncPlayer.Call.Start))

            // And incremental commands are trusted again, from the authoritative sequence — at the
            // revision the reconciliation established, which the stale-revision rule still enforces.
            session.deliver(pauseCommand(seq = 8, effectiveAt = clock.nowUs(), positionMs = 15_000, queueRevision = 3))
            runCurrent()
            assertEquals(8, coordinator.diagnostics.value.lastAppliedCommandSeq)
        }

    /**
     * Coalescing is what keeps the halt above from firing under a peer's ordinary cadence: a
     * `POSITION_REPORT` or a `PLAYBACK_STATE` is superseded by its own newer instance rather than
     * pushing an authoritative command out. Lossless by construction — applying only the newest of
     * such a run reaches the same state.
     */
    @Test
    fun `latest-wins frames coalesce instead of refusing an authoritative command`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope, inboundCapacity = 1)
            connect(this, asLeader = false)
            content.localHashes.add(HASH_A.value)
            val gate = CompletableDeferred<Unit>()
            content.resolveGate = gate
            session.deliver(playCommand(seq = 1, effectiveAt = clock.nowUs()))
            runCurrent()

            // Four reports and a queue snapshot arrive while the consumer is parked. The queue holds
            // one frame, so without coalescing this would refuse four of them.
            repeat(4) { index ->
                session.deliver(
                    PlaybackMessage.PositionReport(HASH_A, 1_000L * index, clock.nowUs() + index, playing = true, playbackRate = 1.0),
                )
            }
            runCurrent()

            content.resolveGate = null
            gate.complete(Unit)
            runCurrent()

            assertEquals(0, coordinator.diagnostics.value.inboundOverflowCount, "no authoritative frame was refused")
            assertEquals(3, coordinator.diagnostics.value.inboundCoalescedCount, "three older reports were superseded by the newest")
            assertFalse(coordinator.diagnostics.value.ingressDesynchronized)
        }

    // --- Finding G: the apply path must preserve authoritative order too ------------------------

    /**
     * **The defect, found by stress-running this suite's own Finding C regression** (2 failures in
     * 100 on iOS; the same hazard exists here the moment a real player suspends).
     *
     * Every accepted command's audible effect was armed as its own coroutine/task. That preserves
     * only the order in which they *start*: each action then suspends inside the player, and the next
     * one runs inside that suspension. `PAUSE(n)` and `RESUME(n+1)` — a pair the leader stamps
     * microseconds apart, so both deadlines have passed by the time they arrive — could therefore
     * take effect in **either** order. That is the same defect the inbound handoff already had, on
     * the other end of the pipe, and the exact opposite of what `command_seq` is for.
     *
     * The gate makes it deterministic here: the `PAUSE`'s player call is parked, the `RESUME` is
     * delivered and fully processed while it is parked, and only then is the `PAUSE` released. Under
     * the old shape the `RESUME`'s seek ran during that park and landed first.
     */
    @Test
    fun `two commands whose deadlines have both passed take effect in authoritative order`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false)
            content.localHashes.add(HASH_A.value)
            session.deliver(playCommand(seq = 1, effectiveAt = clock.nowUs()))
            runCurrent()
            player.calls.clear()

            // Park the PAUSE strictly inside the player, then let the RESUME arrive behind it.
            val gate = CompletableDeferred<Unit>()
            player.gate = gate
            player.gateOn = { it == FakeSyncPlayer.Call.Pause }
            session.deliver(pauseCommand(seq = 2, effectiveAt = clock.nowUs(), positionMs = 5_000))
            runCurrent()
            assertTrue(player.calls.contains(FakeSyncPlayer.Call.Pause), "the PAUSE's effect began")

            player.gateOn = null
            session.deliver(resumeCommand(seq = 3, effectiveAt = clock.nowUs(), positionMs = 6_000))
            runCurrent()
            assertTrue(
                player.calls.none { it == FakeSyncPlayer.Call.Seek(6_000) },
                "the RESUME may not overtake a PAUSE whose effect is still in flight",
            )

            gate.complete(Unit)
            runCurrent()

            val calls = player.calls.toList()
            val pauseSeek = calls.indexOfFirst { it == FakeSyncPlayer.Call.Seek(5_000) }
            val resumeSeek = calls.indexOfFirst { it == FakeSyncPlayer.Call.Seek(6_000) }
            assertTrue(pauseSeek >= 0, "the PAUSE completed: $calls")
            assertTrue(resumeSeek >= 0, "and the RESUME was not lost: $calls")
            assertTrue(pauseSeek < resumeSeek, "authoritative order held all the way to the player: $calls")
            assertEquals(3, coordinator.diagnostics.value.lastAppliedCommandSeq)
        }

    /** A superseded link never holds the chain up: its ownership proof fails and it returns at once. */
    @Test
    fun `a superseded scheduled action does not stall the ones behind it`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false)
            content.localHashes.add(HASH_A.value)
            content.localHashes.add(HASH_B.value)

            // A PLAY scheduled well ahead, then a second PLAY that supersedes its epoch outright.
            val firstAt = clock.nowUs() + 400_000
            session.deliver(playCommand(seq = 1, effectiveAt = firstAt, seed = 1))
            runCurrent()
            val secondAt = clock.nowUs() + 500_000
            session.deliver(playCommand(seq = 2, effectiveAt = secondAt, seed = 2))
            runCurrent()
            player.calls.clear()

            clock.advanceTo(secondAt)
            runCurrent()
            assertEquals(FakeSyncPlayer.Call.Start, player.calls.last(), "the surviving epoch started on time")
            assertEquals(HASH_B, coordinator.diagnostics.value.currentTrackHash)
        }

    // --- Finding D: an accepted command is not an applied command --------------------------------

    /**
     * **The defect.** `onInboundCommand` set `lastAppliedSeq = header.commandSeq` and *then* consulted
     * the clock. An estimator that was momentarily untrusted therefore spent the sequence number and
     * applied nothing — and the leader's replay of that command was then correctly dropped as a
     * duplicate. The command was lost **permanently**, on a condition that resolves itself in
     * milliseconds.
     */
    @Test
    fun `a command accepted while the clock is unready is held rather than lost`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false, clockReady = false)
            content.localHashes.add(HASH_A.value)

            session.deliver(playCommand(seq = 10, effectiveAt = clock.nowUs() + 200_000))
            runCurrent()

            var diagnostics = coordinator.diagnostics.value
            assertTrue(player.calls.isEmpty(), "nothing is scheduled against an untrusted clock")
            assertNull(diagnostics.lastAppliedCommandSeq, "the command is not recorded as applied")
            assertEquals(10, diagnostics.lastReceivedCommandSeq, "it *is* recorded as accepted — that is the distinction")
            assertEquals(1, diagnostics.deferredCommandCount)
            assertEquals(SyncState.CLOCK_UNREADY, diagnostics.syncState)

            // A replay while it is held is a duplicate, not a second copy.
            session.deliver(playCommand(seq = 10, effectiveAt = clock.nowUs() + 200_000))
            runCurrent()
            assertEquals(1, coordinator.diagnostics.value.duplicateCommandCount)
            assertEquals(1, coordinator.diagnostics.value.deferredCommandCount)

            // The estimator recovers; the held command is applied exactly once.
            session.setClock(SessionClockEstimate(offsetToLeaderUs = 0, rttP95Us = 8_000, ready = true))
            clock.advanceBy(DEFERRED_RETRY_US)
            runCurrent()

            diagnostics = coordinator.diagnostics.value
            assertEquals(10, diagnostics.lastAppliedCommandSeq, "applied at the point it actually took effect")
            assertEquals(0, diagnostics.deferredCommandCount)
            assertEquals(1, diagnostics.recoveredCommandCount)
            assertEquals(1, player.calls.count { it is FakeSyncPlayer.Call.Load }, "exactly once, never twice")
        }

    /** Authoritative order survives the wait: `PLAY(n)` then `PAUSE(n+1)` may not become `PAUSE` alone. */
    @Test
    fun `commands held for the clock are applied in authoritative order`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false, clockReady = false)
            content.localHashes.add(HASH_A.value)

            session.deliver(playCommand(seq = 10, effectiveAt = clock.nowUs()))
            session.deliver(pauseCommand(seq = 11, effectiveAt = clock.nowUs(), positionMs = 4_000))
            runCurrent()
            assertEquals(2, coordinator.diagnostics.value.deferredCommandCount)
            assertTrue(player.calls.isEmpty())

            session.setClock(SessionClockEstimate(offsetToLeaderUs = 0, rttP95Us = 8_000, ready = true))
            clock.advanceBy(DEFERRED_RETRY_US)
            runCurrent()

            val calls = player.calls.toList()
            val prepared = calls.indexOfFirst { it is FakeSyncPlayer.Call.Load }
            val paused = calls.indexOf(FakeSyncPlayer.Call.Pause)
            assertTrue(prepared >= 0, "the PLAY was not discarded in favour of the PAUSE")
            assertTrue(paused > prepared, "the PAUSE took effect after the PLAY, exactly as the leader ordered them")
            assertEquals(11, coordinator.diagnostics.value.lastAppliedCommandSeq)
            assertEquals(2, coordinator.diagnostics.value.recoveredCommandCount)
        }

    /** brief §15: a session boundary while commands are held leaves them inert forever. */
    @Test
    fun `a session boundary while commands are held leaves them inert forever`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false, clockReady = false)
            content.localHashes.add(HASH_A.value)
            session.deliver(playCommand(seq = 10, effectiveAt = clock.nowUs()))
            session.deliver(pauseCommand(seq = 11, effectiveAt = clock.nowUs(), positionMs = 4_000))
            runCurrent()
            assertEquals(2, coordinator.diagnostics.value.deferredCommandCount)

            session.emit(ControlEvent.LinkLost(LinkLossReason.NETWORK))
            runCurrent()
            player.calls.clear()
            assertEquals(0, coordinator.diagnostics.value.deferredCommandCount)

            // The clock recovers on a *new* session; nothing from the old one may execute.
            session.currentAuthGeneration = 2
            connect(this, asLeader = false)
            clock.advanceBy(DEFERRED_RETRY_US * 4)
            runCurrent()
            assertTrue(player.calls.isEmpty(), "an old session's held commands may never touch the new session's player")
            assertNull(coordinator.diagnostics.value.lastAppliedCommandSeq)
        }

    /** More held commands than may be held is the same explicit halt, never a silent drop. */
    @Test
    fun `overflowing the held-command buffer halts rather than dropping`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope, deferredCommandCapacity = 1)
            connect(this, asLeader = false, clockReady = false)
            content.localHashes.add(HASH_A.value)

            session.deliver(playCommand(seq = 10, effectiveAt = clock.nowUs()))
            session.deliver(pauseCommand(seq = 11, effectiveAt = clock.nowUs(), positionMs = 4_000))
            runCurrent()

            val diagnostics = coordinator.diagnostics.value
            assertEquals(1, diagnostics.deferredCommandCount, "the buffer is bounded and the bound is real")
            assertEquals(1, diagnostics.inboundOverflowCount)
            assertTrue(diagnostics.ingressDesynchronized, "the refusal halts synchronisation instead of losing the command quietly")
            assertEquals(10, diagnostics.lastReceivedCommandSeq, "the refused command spends no sequence number")
        }

    // --- Finding E: one Play request survives a Phase 4 transfer ---------------------------------

    /**
     * **The defect.** `gateContent` requested the transfer and returned false, and the user action
     * then simply ended. When the transfer verified, nothing was retained to reschedule — the user
     * had to press Play again. REQUIREMENTS §9.4's first-play flow says otherwise.
     */
    @Test
    fun `one Play for content only the peer holds becomes a Play by itself once the transfer verifies`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = true)
            content.peerHashes.add(HASH_A.value)

            coordinator.playSynchronized(HASH_A)
            runCurrent()

            assertEquals(listOf(HASH_A), content.transferRequests, "asked once, through the existing Phase 4 queue")
            assertEquals(SyncState.WAITING_FOR_CONTENT, coordinator.diagnostics.value.syncState)
            assertTrue(session.sentOfType<PlaybackMessage.Play>().isEmpty(), "nothing may play before it is verified here")

            // Phase 4 commits the verified cache entry and fires its own notification.
            content.completeTransfer(HASH_A)
            runCurrent()

            val play = session.sentOfType<PlaybackMessage.Play>().single()
            assertEquals(HASH_A, play.trackHash)
            assertEquals(1, play.header.commandSeq, "a new authoritative command, freshly stamped")
            assertTrue(play.header.effectiveAtSessionUs > clock.nowUs(), "and a fresh instant — never a reused, expired one")
            assertEquals(1, coordinator.diagnostics.value.resumedPendingPlayCount)
            assertEquals(listOf(HASH_A), content.transferRequests, "and Phase 4 was still only asked once")
        }

    /** brief §18: requesting X supersedes a pending H, and H must not resurrect when its transfer lands. */
    @Test
    fun `a superseded pending Play never resurrects`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = true)
            content.peerHashes.add(HASH_A.value)
            content.peerHashes.add(HASH_B.value)
            content.localHashes.add(HASH_B.value)

            coordinator.playSynchronized(HASH_A)
            runCurrent()
            coordinator.playSynchronized(HASH_B)
            runCurrent()

            assertEquals(HASH_B, session.sentOfType<PlaybackMessage.Play>().single().trackHash, "the newer request is the one that plays")

            // H's transfer lands afterwards. It is not the current request and must change nothing.
            content.completeTransfer(HASH_A)
            runCurrent()
            assertEquals(
                listOf(HASH_B),
                session.sentOfType<PlaybackMessage.Play>().map { it.trackHash },
                "a superseded request may never resurrect, even for a track that is now available",
            )
        }

    /** brief §18: leaving synchronised mode cancels the retained Play. */
    @Test
    fun `leaving synchronized mode cancels a pending Play`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = true)
            content.peerHashes.add(HASH_A.value)
            coordinator.playSynchronized(HASH_A)
            runCurrent()

            coordinator.leaveSynchronizedMode()
            runCurrent()
            assertEquals(1, coordinator.diagnostics.value.cancelledPendingPlayCount)

            content.completeTransfer(HASH_A)
            runCurrent()
            assertTrue(
                session.sentOfType<PlaybackMessage.Play>().isEmpty(),
                "a transfer completing must not start music the user has stopped asking for",
            )
        }

    /** brief §18: a session boundary cancels it, and the verification that follows is inert. */
    @Test
    fun `a session boundary cancels a pending Play awaiting content`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = true)
            content.peerHashes.add(HASH_A.value)
            coordinator.playSynchronized(HASH_A)
            runCurrent()

            session.emit(ControlEvent.LinkLost(LinkLossReason.NETWORK))
            runCurrent()
            session.currentAuthGeneration = 2
            connect(this, asLeader = true)

            content.completeTransfer(HASH_A)
            runCurrent()
            assertTrue(session.sentOfType<PlaybackMessage.Play>().isEmpty(), "a dead session's Play may never execute")
        }

    /** brief §20 case 5: a failed transfer starts nothing and says so, rather than pretending. */
    @Test
    fun `a failed transfer starts nothing and keeps saying it is waiting`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = true)
            content.peerHashes.add(HASH_A.value)
            coordinator.playSynchronized(HASH_A)
            runCurrent()

            // The notification fires, but availability did not change — the transfer failed.
            content.failTransfer()
            runCurrent()

            assertTrue(session.sentOfType<PlaybackMessage.Play>().isEmpty(), "nothing may start")
            assertEquals(SyncState.WAITING_FOR_CONTENT, coordinator.diagnostics.value.syncState, "and the state stays honest")
            assertEquals(0, coordinator.diagnostics.value.resumedPendingPlayCount)
        }

    /** The peer half: the leader waits for the *follower* to hold it, and the peer's own report ends the wait. */
    @Test
    fun `the leader's Play waits for the peer to hold the track, then issues by itself`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = true)
            content.localHashes.add(HASH_A.value)

            coordinator.playSynchronized(HASH_A)
            runCurrent()
            assertTrue(session.sentOfType<PlaybackMessage.Play>().isEmpty(), "REQUIREMENTS §9.4: a remote-only-absent track cannot start")
            assertEquals(SyncState.WAITING_FOR_CONTENT, coordinator.diagnostics.value.syncState)

            // ADR-024 §7: the peer reports having verified the transfer we served it.
            content.peerVerified(HASH_A)
            runCurrent()
            assertEquals(HASH_A, session.sentOfType<PlaybackMessage.Play>().single().trackHash)
        }

    // --- Finding F: a superseded correction has no side effects at all ---------------------------

    /**
     * **The defect (iOS-shaped, asserted on both platforms).** `runIfCurrent` returned `Void`, and the
     * correction call sites mutated diagnostics, incremented the hard-seek budget, set `syncFailed`
     * and emitted a `PLAYBACK_STATE` **after** it — unconditionally. A correction the guard had
     * refused still had four visible side effects, one of them on the wire.
     *
     * Here the supersession lands strictly *inside* the player call, which is the harder half: the
     * action was authorised when it began and is not when it returns.
     */
    @Test
    fun `a correction superseded inside its own player call has no state or wire effect`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false)
            content.localHashes.add(HASH_A.value)
            content.localHashes.add(HASH_B.value)
            val anchor = clock.nowUs()
            session.deliver(playCommand(seq = 1, effectiveAt = anchor))
            runCurrent()
            player.calls.clear()
            session.sent.clear()

            // Park the drift ladder's hard seek inside the player.
            val gate = CompletableDeferred<Unit>()
            player.gate = gate
            player.gateOn = { it is FakeSyncPlayer.Call.Seek }
            val tickAt = clock.pendingDeadlines.max()
            player.setState(PlayerState(positionMs = (tickAt - anchor) / 1_000 + 400, durationMs = 600_000, playing = true))
            clock.advanceTo(tickAt)
            runCurrent()
            assertTrue(player.calls.any { it is FakeSyncPlayer.Call.Seek }, "the correction began while it was still current")
            val before = coordinator.diagnostics.value

            // A new PLAY supersedes the epoch while the seek is still in flight.
            player.gateOn = null
            session.deliver(playCommand(seq = 2, effectiveAt = clock.nowUs() + 400_000, seed = 2))
            runCurrent()
            gate.complete(Unit)
            runCurrent()

            val after = coordinator.diagnostics.value
            assertEquals(before.hardSeekCount, after.hardSeekCount, "a superseded correction may not spend the seek budget")
            assertEquals(before.lastCorrection, after.lastCorrection, "nor claim to have corrected anything")
            assertTrue(
                session.sentOfType<PlaybackMessage.PlaybackStateSnapshot>().isEmpty(),
                "nor emit authoritative state — and a follower never emits one at all",
            )
        }

    /** The easier half, for completeness: refused *before* the player call, so the player is untouched too. */
    @Test
    fun `a correction refused before its player call touches nothing`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connect(this, asLeader = false)
            content.localHashes.add(HASH_A.value)
            val anchor = clock.nowUs()
            session.deliver(playCommand(seq = 1, effectiveAt = anchor))
            runCurrent()
            player.calls.clear()

            // Leaving synchronised mode supersedes the epoch, so the tick's correction owns nothing.
            coordinator.leaveSynchronizedMode()
            runCurrent()
            player.calls.clear()
            val before = coordinator.diagnostics.value

            val tickAt = clock.pendingDeadlines.max()
            player.setState(PlayerState(positionMs = (tickAt - anchor) / 1_000 + 400, durationMs = 600_000, playing = true))
            clock.advanceTo(tickAt)
            runCurrent()

            val after = coordinator.diagnostics.value
            assertTrue(player.calls.none { it is FakeSyncPlayer.Call.Seek }, "no player action")
            assertEquals(before.hardSeekCount, after.hardSeekCount)
            assertEquals(before.lastCorrection, after.lastCorrection)
        }

    // --- helpers ----------------------------------------------------------------------------------

    /** The three race pairings §7 of the audit brief names, plus the `PLAY`/`QUEUE_ADD` one. */
    private class Interleaving(
        val name: String,
        val queueAction: (SyncPlaybackCoordinator, List<SharedQueueItem>) -> Unit,
        val playbackAction: (SyncPlaybackCoordinator) -> Unit,
    )

    private fun interleavings() =
        listOf(
            Interleaving("NEXT vs QUEUE_REMOVE", { c, items -> c.removeFromQueue(items.first().queueItemId) }, { it.next() }),
            Interleaving("PLAY vs QUEUE_ADD", { c, _ -> c.enqueue(HASH_C) }, { it.playSynchronized(HASH_A) }),
            Interleaving("SEEK vs QUEUE_MOVE", { c, items -> c.moveInQueue(items.first().queueItemId, 1) }, { it.seek(30_000) }),
            Interleaving("PAUSE vs QUEUE_REMOVE", { c, items -> c.removeFromQueue(items.last().queueItemId) }, { it.pause() }),
        )

    private fun authoritativeHeader(message: PlaybackMessage): PlaybackCommandHeader? {
        val header =
            when (message) {
                is PlaybackMessage.Play -> message.header
                is PlaybackMessage.Pause -> message.header
                is PlaybackMessage.Resume -> message.header
                is PlaybackMessage.Seek -> message.header
                is PlaybackMessage.Next -> message.header
                is PlaybackMessage.Previous -> message.header
                else -> null
            } ?: return null
        return header.takeIf { it.commandSeq != PlaybackBounds.UNASSIGNED_COMMAND_SEQ }
    }

    private fun snapshot(
        revision: Long,
        items: List<SharedQueueItem>,
    ) = QueueMessage.Snapshot(revision, items, currentIndex = null)

    private fun item(
        queueItemId: String,
        hash: ContentHash,
    ) = SharedQueueItem(queueItemId, hash, SyncTestValues.leaderPeerId, order = PlaybackBounds.QUEUE_ORDER_STEP)

    private fun playCommand(
        seq: Long,
        effectiveAt: Long,
        seed: Int = 1,
    ) = PlaybackMessage.Play(
        PlaybackCommandHeader(seq, effectiveAt, SyncTestValues.leaderPeerId, queueRevision = 0),
        SyncTestValues.hash(seed),
        positionMs = 0,
        queueItemId = SyncTestValues.ulid(seed),
    )

    private fun pauseCommand(
        seq: Long,
        effectiveAt: Long,
        positionMs: Long,
        queueRevision: Long = 0,
    ) = PlaybackMessage.Pause(
        PlaybackCommandHeader(seq, effectiveAt, SyncTestValues.leaderPeerId, queueRevision),
        positionMs,
    )

    private fun resumeCommand(
        seq: Long,
        effectiveAt: Long,
        positionMs: Long,
    ) = PlaybackMessage.Resume(
        PlaybackCommandHeader(seq, effectiveAt, SyncTestValues.leaderPeerId, queueRevision = 0),
        positionMs,
    )

    private companion object {
        val HASH_A: ContentHash = SyncTestValues.hash(1)
        val HASH_B: ContentHash = SyncTestValues.hash(2)
        val HASH_C: ContentHash = SyncTestValues.hash(3)

        /** [com.ridelink.core.playback.Phase5GateBounds.DEFERRED_RETRY_INTERVAL_US]. */
        const val DEFERRED_RETRY_US = 100_000L

        /** Kept for symmetry with the role a leader plays in the wire-order assertions. */
        val LEADER_ROLE = PlaybackRole.LEADER

        /** The rate correction always ends at, asserted by the drift suite and referenced here. */
        const val RATE_NORMAL = DriftController.RATE_NORMAL
    }
}
