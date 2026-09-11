package com.ridelink.app.sync

import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.SessionId
import com.ridelink.core.playback.DriftController
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The regressions ADR-024 **Amendment A4** — the fourth Phase 5 closure audit — exists for, mirrored
 * from `RideLinkPlatformTests.SyncPlaybackOperationLifetimeAuditTests`.
 *
 * A3 fenced *operations*: an apply-chain or scheduled-chain node created under Session A is retired
 * at a boundary, and every apply path proves its authorising generation before it reads live state.
 * A4 is the narrower hole underneath that fence: **an operation that passed its ownership proof
 * while Session A was valid, entered a compound effect, suspended inside its first sub-effect, and
 * then performed a *second* sub-effect after Session B was live.**
 *
 * ## Was this platform actually defective?
 *
 * **Not observably, and this file does not claim otherwise.** Every one of the compounds reaches
 * `ExoPlayerMusicPlayer.execute`, which wraps its body in `withContext(Dispatchers.Main.immediate)`.
 * Every Phase 5 caller runs on `AppContainer`'s `Dispatchers.Main` scope and is therefore already on
 * the main thread, so that `withContext` starts undispatched and returns without ever suspending —
 * and where nothing suspends, nothing can interleave.
 * `SyncScheduledPlaybackTest.aPlayerCommandFromTheMainDispatcherDoesNotSuspend` measures exactly
 * that, against the real `ExoPlayer` on the emulator, so it is a fact about this app rather than a
 * reading of the coroutines source.
 *
 * That safety is an accident of which `CoroutineScope` the composition root happens to build. It
 * was undocumented, untested, and one dispatcher change — or one `Player` implementation that
 * genuinely awaits `STATE_READY` — away from being false, while the iOS mirror (an `@MainActor`
 * coordinator over an `actor` player) suspends for real and *was* defective. The shape is therefore
 * mirrored rather than left to the accident, and these tests are what keep the invariant true
 * independently of the dispatcher: [FakeSyncPlayer]'s gate suspends for real, so each case below
 * builds the interleaving the production dispatcher currently precludes and asserts the fence
 * handles it. Five of the seven fail when `runOwnedSteps` is reduced to a single proof.
 *
 * One difference between the compounds is worth keeping straight. `applyTransport`'s
 * `pause`-then-`seek` and `seek`-then-`start` were **visible to this seam** even before A4 — two
 * separate port calls from the coordinator — so a test of them could have been written against
 * unmodified source. `syncPrepare`'s load-then-seek and `syncStop`'s stop-then-clear could not:
 * they lived below the port, inside `MusicCoordinator`, which no coordinator-level fake can reach
 * into. Making them expressible is part of what the A4 port split *is*.
 *
 * **Deterministic throughout.** [FakeMonotonicClock] is the only clock, [StandardTestDispatcher]
 * decides ordering, and the blocking seam is [FakeSyncPlayer.gate] — deliberately non-cancellable,
 * exactly as `ExoPlayer.prepare` is, so what these tests prove is the generation fence rather than
 * merely that cancellation happened.
 */
@Suppress("LargeClass")
class SyncPlaybackOperationLifetimeAuditTest {
    private lateinit var session: FakeSyncSession
    private lateinit var player: FakeSyncPlayer
    private lateinit var content: FakeSyncContent
    private lateinit var clock: FakeMonotonicClock
    private lateinit var coordinator: SyncPlaybackCoordinator
    private var idSeed = 2_400

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
                localPeerId = SyncTestValues.leaderPeerId,
                session = session,
                player = player,
                content = content,
                sleeper = clock.sleeper,
                routeTransitioning = { false },
                nextQueueItemId = { SyncTestValues.ulid(idSeed++) },
            )
    }

    // --- A4-AND-1: the pre-roll's own sub-effects -----------------------------------------------

    /**
     * Session A's pre-roll parks inside the decoder `load`. Session B then authenticates, pre-rolls,
     * starts and owns the player. When Session A's load finally returns it must not perform the
     * `seek` that used to follow it unconditionally — that seek would move **Session B's** playback
     * to Session A's position, with Session B's own timeline still saying otherwise.
     */
    @Test
    fun `an old session's parked decoder load never seeks the session that replaced it`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connectAsLeader(this)
            val gateA = parkSessionAInsideItsDecoderLoad(this)

            boundary(this, generation = 2)
            establishSessionB(this)

            val callsBefore = player.calls.toList()
            val queueBefore = coordinator.queueState.value
            val diagnosticsBefore = coordinator.diagnostics.value
            val deadlinesBefore = clock.pendingDeadlines
            assertTrue(callsBefore.contains(FakeSyncPlayer.Call.Load(HASH_Y)), "Session B loaded its own track")
            assertTrue(callsBefore.contains(FakeSyncPlayer.Call.Start), "and started it")

            gateA.complete(Unit)
            runCurrent()

            assertEquals(
                callsBefore,
                player.calls.toList(),
                "Session A's pre-roll may not seek, select, load or start anything after Session B is live",
            )
            assertEquals(queueBefore, coordinator.queueState.value, "nor step Session B's shared queue")
            assertEquals(diagnosticsBefore, coordinator.diagnostics.value, "nor move one Session-B diagnostic")
            assertEquals(deadlinesBefore, clock.pendingDeadlines, "and it may not arm a start into Session B")

            assertSessionBStillWorks(this)
        }

    /**
     * A4-AND-6: Session B must be able to select, load, seek and start while Session A's decoder
     * load is still parked, without joining or waiting for it — and releasing Session A afterwards
     * must change nothing Session B owns.
     */
    @Test
    fun `a new session prepares and plays while the old session's load is still parked`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connectAsLeader(this)
            val gateA = parkSessionAInsideItsDecoderLoad(this)

            boundary(this, generation = 2)

            coordinator.playSynchronized(HASH_X)
            runCurrent()
            assertTrue(
                player.calls.contains(FakeSyncPlayer.Call.Load(HASH_X)),
                "Session B pre-rolled while Session A's load was still parked",
            )
            val deadline =
                session
                    .sentOfType<PlaybackMessage.Play>()
                    .last()
                    .header.effectiveAtSessionUs
            clock.advanceTo(deadline)
            runCurrent()
            assertEquals(FakeSyncPlayer.Call.Start, player.calls.last(), "and reached its own scheduled start")
            assertFalse(gateA.isCompleted, "Session A was parked for all of that")
            assertEquals(HASH_X, coordinator.diagnostics.value.currentTrackHash)

            val callsBefore = player.calls.toList()
            val queueBefore = coordinator.queueState.value
            val diagnosticsBefore = coordinator.diagnostics.value

            gateA.complete(Unit)
            runCurrent()

            assertEquals(callsBefore, player.calls.toList(), "releasing Session A changes nothing it owns")
            assertEquals(queueBefore, coordinator.queueState.value)
            assertEquals(diagnosticsBefore, coordinator.diagnostics.value)
        }

    // --- A4-AND-2 / A4-AND-3: the scheduled transport action's two effects ----------------------

    /**
     * A Session-A `RESUME` is `seek` **then** `start`. It parks inside its seek, the boundary lands,
     * Session B becomes live — and the resumed Session-A action must not start Session B's player.
     *
     * This one fails against unmodified pre-A4 code: that pair sat in one lambda in
     * `applyTransport`, behind a single `runIfCurrent`.
     */
    @Test
    fun `an old session's parked resume seek never starts the session that replaced it`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connectAsLeader(this)
            playAndStart(this, HASH_A)

            player.setState(PlayerState(positionMs = RESUME_POSITION_MS, durationMs = TRACK_DURATION_MS, playing = true))
            val gateA = CompletableDeferred<Unit>()
            player.gate = gateA
            player.gateOn = { it == FakeSyncPlayer.Call.Seek(RESUME_POSITION_MS) }
            coordinator.resume()
            runCurrent()
            clock.advanceTo(lastDeadlineOf<PlaybackMessage.Resume>())
            runCurrent()
            assertEquals(
                FakeSyncPlayer.Call.Seek(RESUME_POSITION_MS),
                player.calls.last(),
                "the seek happened; the start has not",
            )

            boundary(this, generation = 2)
            establishSessionB(this)

            val callsBefore = player.calls.toList()
            val diagnosticsBefore = coordinator.diagnostics.value
            gateA.complete(Unit)
            runCurrent()

            assertEquals(callsBefore, player.calls.toList(), "a retired RESUME may not start Session B's player")
            assertEquals(diagnosticsBefore, coordinator.diagnostics.value, "nor move one Session-B diagnostic")

            assertSessionBStillWorks(this)
        }

    /**
     * A Session-A `PAUSE` is `pause` **then** `seek`. It parks inside its pause, the boundary lands,
     * Session B establishes its own timeline and playback — and the resumed Session-A action must
     * not seek Session B.
     *
     * The seek is the dangerous half: it moves audio the rider is listening to without touching any
     * state Session B's drift ladder would notice, so the next cadence tick would measure the
     * resulting error as *Session B's* drift and correct against it.
     */
    @Test
    fun `an old session's parked pause never seeks the session that replaced it`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connectAsLeader(this)
            playAndStart(this, HASH_A)

            player.setState(PlayerState(positionMs = PAUSE_POSITION_MS, durationMs = TRACK_DURATION_MS, playing = true))
            val gateA = CompletableDeferred<Unit>()
            player.gate = gateA
            player.gateOn = { it == FakeSyncPlayer.Call.Pause }
            coordinator.pause()
            runCurrent()
            clock.advanceTo(lastDeadlineOf<PlaybackMessage.Pause>())
            runCurrent()
            assertEquals(FakeSyncPlayer.Call.Pause, player.calls.last(), "the pause happened; the seek has not")

            boundary(this, generation = 2)
            val anchorB = establishSessionB(this)

            val callsBefore = player.calls.toList()
            val diagnosticsBefore = coordinator.diagnostics.value
            gateA.complete(Unit)
            runCurrent()

            assertEquals(callsBefore, player.calls.toList(), "a retired PAUSE may not seek Session B's player")
            assertFalse(
                player.calls.contains(FakeSyncPlayer.Call.Seek(PAUSE_POSITION_MS)),
                "Session A's position never reached it",
            )
            assertEquals(diagnosticsBefore, coordinator.diagnostics.value)

            // And Session B's own drift measurement is still taken against Session B's own anchor.
            val tickAtUs = clock.nowUs() + POSITION_REPORT_US
            player.setState(
                PlayerState(positionMs = (tickAtUs - anchorB) / 1_000, durationMs = TRACK_DURATION_MS, playing = true),
            )
            clock.advanceTo(tickAtUs)
            runCurrent()
            assertEquals(0L, coordinator.diagnostics.value.localDriftMs, "Session B is measured against Session B")
            assertEquals(0, coordinator.diagnostics.value.hardSeekCount, "so nothing on the ladder fired")
        }

    // --- A4-AND-4: stop, then clear the local queue ---------------------------------------------

    /**
     * A `NEXT` that runs off the end of the queue is `stop` **then** clear the local selection.
     * Session A parks inside the player's stop; Session B then materialises a track of its own. The
     * resumed Session-A action must not clear Session B's local selection — which on a phone is the
     * `MediaSession` metadata, the lock-screen entry and what the Phase 3 UI shows.
     */
    @Test
    fun `an old session's parked stop never clears the selection of the session that replaced it`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connectAsLeader(this)
            playAndStart(this, HASH_A)

            val gateA = CompletableDeferred<Unit>()
            player.gate = gateA
            player.gateOn = { it == FakeSyncPlayer.Call.Stop }
            coordinator.next()
            runCurrent()
            clock.advanceTo(lastDeadlineOf<PlaybackMessage.Next>())
            runCurrent()
            assertEquals(
                FakeSyncPlayer.Call.Stop,
                player.calls.last(),
                "the stop happened; the local-queue clear has not",
            )

            boundary(this, generation = 2)
            establishSessionB(this)

            val callsBefore = player.calls.toList()
            val queueBefore = coordinator.queueState.value
            val diagnosticsBefore = coordinator.diagnostics.value
            assertEquals(
                HASH_Y,
                queueBefore.currentItem?.trackHash,
                "Session B has a materialised track",
            )

            gateA.complete(Unit)
            runCurrent()

            assertFalse(
                player.calls.drop(callsBefore.size).contains(FakeSyncPlayer.Call.ClearSelection),
                "a retired stop may not clear Session B's local selection",
            )
            assertEquals(callsBefore, player.calls.toList(), "and may perform no other effect either")
            assertEquals(queueBefore, coordinator.queueState.value)
            assertEquals(diagnosticsBefore, coordinator.diagnostics.value)

            assertSessionBStillWorks(this)
        }

    // --- A4-AND-5: the correction ladder --------------------------------------------------------

    /**
     * The *player* half of ADR-004's ladder was never vulnerable: every `DriftAction` is exactly one
     * effect — `setRate`, `setRate`, `seek`, `setRate` — and A1 Finding F already put the
     * diagnostics, the hard-seek budget and the outbound `PLAYBACK_STATE` behind a second proof
     * taken after that effect returns.
     *
     * **The tick around it was** (Amendment A4 Finding F). `tickOnce` awaited `applyCorrection` and
     * then incremented `correctionTickCount` unconditionally, so a tick belonging to a retired
     * session — parked inside a rate nudge across the whole boundary — woke up and moved the live
     * session's counter. Exactly A3 Finding C's shape, one function further along.
     */
    @Test
    fun `a correction parked inside its only player call has no effect on the session that replaced it`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connectAsLeader(this)
            val deadlineA = playAndStart(this, HASH_A)

            val gateA = CompletableDeferred<Unit>()
            player.gate = gateA
            player.gateOn = { it is FakeSyncPlayer.Call.SetRate }
            val tickAtUs = clock.nowUs() + POSITION_REPORT_US
            val expectedMs = (tickAtUs - deadlineA) / 1_000
            player.setState(
                PlayerState(positionMs = expectedMs + NUDGE_DRIFT_MS, durationMs = TRACK_DURATION_MS, playing = true),
            )
            clock.advanceTo(tickAtUs)
            runCurrent()
            assertEquals(
                FakeSyncPlayer.Call.SetRate(DriftController.RATE_SLOWER),
                player.calls.last(),
                "the correction parked inside its rate nudge",
            )

            boundary(this, generation = 2)
            establishSessionB(this)

            val callsBefore = player.calls.toList()
            val diagnosticsBefore = coordinator.diagnostics.value
            val sentBefore = session.sent.size
            gateA.complete(Unit)
            runCurrent()

            assertEquals(callsBefore, player.calls.toList(), "a retired correction drives Session B's player not at all")
            assertEquals(diagnosticsBefore, coordinator.diagnostics.value, "and writes none of Session B's diagnostics")
            assertEquals(
                DriftController.RATE_NORMAL,
                coordinator.diagnostics.value.playbackRate,
                "Session B's rate is exactly 1.0",
            )
            assertEquals(sentBefore, session.sent.size, "and puts nothing on Session B's wire")
        }

    // --- same-session controls ------------------------------------------------------------------

    /**
     * The control every case above needs: **the same seam, with no boundary, still completes.** A4
     * adds proofs between sub-effects; it must not stop a legitimate pre-roll from seeking, and it
     * must not stop a legitimate `PAUSE` from seeking after it pauses.
     */
    @Test
    fun `within one session every step of a compound still runs`() =
        runTest(StandardTestDispatcher()) {
            build(backgroundScope)
            connectAsLeader(this)

            val preRollGate = CompletableDeferred<Unit>()
            player.gate = preRollGate
            player.gateOn = { it == FakeSyncPlayer.Call.Load(HASH_A) }
            coordinator.playSynchronized(HASH_A)
            runCurrent()
            preRollGate.complete(Unit)
            runCurrent()
            assertEquals(FakeSyncPlayer.preRoll(HASH_A, 0L), player.calls.toList(), "the seek still followed the load")
            clock.advanceTo(lastDeadlineOf<PlaybackMessage.Play>())
            runCurrent()
            assertEquals(FakeSyncPlayer.Call.Start, player.calls.last(), "and the scheduled start still fired")

            player.setState(PlayerState(positionMs = PAUSE_POSITION_MS, durationMs = TRACK_DURATION_MS, playing = true))
            val pauseGate = CompletableDeferred<Unit>()
            player.gate = pauseGate
            player.gateOn = { it == FakeSyncPlayer.Call.Pause }
            coordinator.pause()
            runCurrent()
            clock.advanceTo(lastDeadlineOf<PlaybackMessage.Pause>())
            runCurrent()
            assertEquals(FakeSyncPlayer.Call.Pause, player.calls.last(), "parked between its two effects")
            pauseGate.complete(Unit)
            runCurrent()
            assertEquals(
                FakeSyncPlayer.Call.Seek(PAUSE_POSITION_MS),
                player.calls.last(),
                "and its seek still followed",
            )
            assertEquals(SyncState.SYNCED, coordinator.diagnostics.value.syncState)
        }

    // --- fixtures ---------------------------------------------------------------------------------

    /**
     * Session A: leader, clock ready, `LEAD` clamped to its 2 s maximum so its deadlines outlive the
     * boundary, and every track this file needs playable on both devices.
     */
    private suspend fun connectAsLeader(
        scope: TestScope,
        generation: Long = 1,
    ) {
        scope.runCurrent()
        session.setClock(SessionClockEstimate(offsetToLeaderUs = 0, rttP95Us = 8_000, ready = true))
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
     * Session A issues a `PLAY`, the transport confirms it, and the local apply parks inside the
     * **decoder load** — strictly between the materialisation and the seek that used to follow it
     * unconditionally. Returns the gate holding it.
     */
    private suspend fun parkSessionAInsideItsDecoderLoad(scope: TestScope): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        player.gate = gate
        player.gateOn = { it == FakeSyncPlayer.Call.Load(HASH_A) }
        coordinator.playSynchronized(HASH_A)
        scope.runCurrent()
        assertEquals(
            1,
            session.sentOfType<PlaybackMessage.Play>().size,
            "the premise: PLAY A was actually written to the wire, so A2 committed its command_seq",
        )
        assertEquals(
            listOf<FakeSyncPlayer.Call>(FakeSyncPlayer.Call.Select(HASH_A), FakeSyncPlayer.Call.Load(HASH_A)),
            player.calls,
            "the premise: the seek has not happened yet",
        )
        return gate
    }

    /** A full `PLAY` that reaches its scheduled start, leaving a live timeline and a live epoch. */
    private suspend fun playAndStart(
        scope: TestScope,
        hash: ContentHash,
    ): Long {
        coordinator.playSynchronized(hash)
        scope.runCurrent()
        val deadline = lastDeadlineOf<PlaybackMessage.Play>()
        clock.advanceTo(deadline)
        scope.runCurrent()
        assertEquals(FakeSyncPlayer.Call.Start, player.calls.last(), "the premise: Session A is playing")
        return deadline
    }

    /** A full authentication boundary: the link drops, the generation moves, a new session opens. */
    private suspend fun boundary(
        scope: TestScope,
        generation: Long,
    ) {
        session.emit(ControlEvent.LinkLost(LinkLossReason.NETWORK))
        scope.runCurrent()
        session.currentAuthGeneration = generation
        session.rttP95Us = 8_000
        session.emit(ControlEvent.Connected(SyncTestValues.leaderPeerId, SessionId("S$generation"), true))
        scope.runCurrent()
    }

    /**
     * Session B, distinguishable from Session A at every point A could touch: a three-item queue
     * with the middle item current, its own timeline, its own live playback epoch, and playing.
     *
     * @return the session instant Session B's `PLAY` was stamped for.
     */
    private suspend fun establishSessionB(scope: TestScope): Long {
        coordinator.enqueue(HASH_X)
        scope.runCurrent()
        coordinator.playSynchronized(HASH_Y)
        scope.runCurrent()
        coordinator.enqueue(HASH_Z)
        scope.runCurrent()
        val deadline = lastDeadlineOf<PlaybackMessage.Play>()
        clock.advanceTo(deadline)
        scope.runCurrent()
        // The player state Session B's own timeline implies, so a later tick measures zero drift
        // unless something moved the player behind Session B's back.
        player.setState(PlayerState(positionMs = 0, durationMs = TRACK_DURATION_MS, playing = true))
        return deadline
    }

    /** Session B's own next command still works end to end — the fence retired A, not B. */
    private suspend fun assertSessionBStillWorks(scope: TestScope) {
        coordinator.seek(SESSION_B_SEEK_MS)
        scope.runCurrent()
        clock.advanceTo(lastDeadlineOf<PlaybackMessage.Seek>())
        scope.runCurrent()
        assertEquals(
            FakeSyncPlayer.Call.Seek(SESSION_B_SEEK_MS),
            player.calls.last(),
            "Session B's own SEEK still reaches the player",
        )
    }

    /**
     * The deadline of the most recent command of that shape. The leader's offset is zero, so a
     * session instant is a local monotonic instant.
     */
    private inline fun <reified T : PlaybackMessage> lastDeadlineOf(): Long {
        val message = session.sentOfType<T>().last()
        return checkNotNull(SyncPlaybackCoordinatorHeaders.of(message)) { "that message carries no header" }
            .effectiveAtSessionUs
    }

    private companion object {
        val HASH_A: ContentHash = SyncTestValues.hash(21)
        val HASH_X: ContentHash = SyncTestValues.hash(22)
        val HASH_Y: ContentHash = SyncTestValues.hash(23)
        val HASH_Z: ContentHash = SyncTestValues.hash(24)

        /** Deliberately far from anything Session B ever anchors at, so a stale effect is unmistakable. */
        const val RESUME_POSITION_MS = 720_000L
        const val PAUSE_POSITION_MS = 540_000L
        const val SESSION_B_SEEK_MS = 90_000L
        const val TRACK_DURATION_MS = 3_600_000L

        /** [com.ridelink.core.playback.PlaybackBounds.POSITION_REPORT_INTERVAL_MS], in microseconds. */
        const val POSITION_REPORT_US = 5_000_000L

        /** Past ADR-004's nudge threshold and well inside its hard-seek one, so tier one fires. */
        const val NUDGE_DRIFT_MS = 60L

        /**
         * `4 x rtt_p95` past `SessionClock.MAX_LEAD_US`, so Session A's `LEAD` is the 2 s clamp —
         * see [connectAsLeader] for why the audit needs it.
         */
        const val LONG_LEAD_RTT_US = 500_000L
    }
}

/** Reads a `PlaybackMessage`'s command header without duplicating the coordinator's `when`. */
internal object SyncPlaybackCoordinatorHeaders {
    fun of(message: PlaybackMessage): com.ridelink.core.playback.PlaybackCommandHeader? =
        when (message) {
            is PlaybackMessage.Play -> message.header
            is PlaybackMessage.Pause -> message.header
            is PlaybackMessage.Resume -> message.header
            is PlaybackMessage.Seek -> message.header
            is PlaybackMessage.Next -> message.header
            is PlaybackMessage.Previous -> message.header
            else -> null
        }
}
