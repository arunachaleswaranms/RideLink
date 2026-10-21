package com.ridelink.app.sync

import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.playback.PlaybackMessage
import com.ridelink.core.player.PlayerPositionSample
import com.ridelink.core.player.PlayerState
import com.ridelink.core.sync.SessionClock
import com.ridelink.core.sync.SessionClockEstimate
import com.ridelink.network.control.ControlEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.fail

/**
 * STATUS §4 problem 131 — the reproduction from PR #29, now the regression for ADR-004 Amendment A1.
 * The first two cases failed against `main` at `3715551` (unmodified production) and their
 * assertions are unchanged; the third was added with the fix.
 *
 * Physical evidence (two Android phones, built-in speakers, build 47d7cde, 19–20 Oct 2026): both
 * phones reported `local drift` of about −350 to −650 ms (±~120 ms jitter, no trend), made two or
 * three hard seeks and then declared `SYNC_FAILED` on every new epoch — while a single microphone
 * recording both loudspeakers measured the two outputs within ≤ 2 ms of **each other** (n = 78). The
 * corrections were themselves audible skips, on both phones together.
 *
 * [FakeSyncPlayer] cannot show this: its position is whatever a test writes, so a start lands
 * exactly on the timeline by construction. [OutputLatencyModelPlayer] instead models the two
 * properties of the production `ExoPlayerMusicPlayer` that the analysis in STATUS problem 131
 * identifies:
 *
 * 1. **Restart latency.** Media3 1.11.0's audio position (`AudioTrackPositionTracker
 *    .getCurrentPositionUs`) comes from `AudioTrack` presentation timestamps / the playback head,
 *    so after `play()` — and after every seek while playing, which flushes the `AudioTrack` — the
 *    reported position stays at its start value until output actually begins, then advances. The
 *    model freezes the position for [OutputLatencyModelPlayer.outputLatencyUs] after every start or
 *    seek, and treats what it reports as what is audible (Media3's design intent; **inferred**, not
 *    measured on these phones).
 * 2. **Stale publication.** `ExoPlayerMusicPlayer` publishes `positionMs` only from a 250 ms
 *    `delay` loop (`POSITION_TICK_MS`), and `SyncPlaybackCoordinator.tickOnce` reads that cached
 *    value and pairs it with a *fresh* session instant. The model publishes on a 253 ms period
 *    (250 ms plus main-thread slack, so the staleness sweeps 0..253 ms as it does on a device).
 *
 * Neither of the first two tests asserts a particular remedy. Each asserts a property any correct
 * fix must have. The third asserts the constraint the fix was held to: a **differential** lag (one
 * phone's Bluetooth path) must still be corrected, never concealed.
 *
 * The common-mode case also shows a third, code-established mechanism. The leader's `PLAYBACK_STATE`
 * after a hard seek or `SYNC_FAILED` carries its *cached* position. The follower re-anchors to it
 * and resets its seek budget. In this model, that turns the common-mode lag into a real
 * inter-phone offset. The acoustic measurement did **not** observe that, so that part of the model
 * is unconfirmed (docs/test-results/phase9ba-problem131-sync-drift-20261020.md §2 E4).
 */
class SyncPlaybackOutputLatencyTest {
    /**
     * The common-mode case, which is what the phones actually did: both have the same restart
     * latency, so they are audibly aligned with each other throughout (asserted first, as a check on
     * the model), and every correction the ladder makes is pure harm — an audible skip on both phones
     * that cannot reduce the measured drift because the seek re-incurs the very latency it measured.
     */
    @Test
    fun `a restart latency common to both phones is never chased by the drift ladder`() =
        runTest(StandardTestDispatcher()) {
            val sim = TwoPhoneSimulation(this, outputLatencyUs = 350_000, positionTickUs = 253_000)
            sim.connectAndPlay()
            sim.runFor(SIXTY_SECONDS_US)

            val trace = sim.trace()
            val leader = sim.leader.coordinator.diagnostics.value
            val follower = sim.follower.coordinator.diagnostics.value
            val corrected = leader.hardSeekCount + follower.hardSeekCount > 0
            val failed = SyncState.SYNC_FAILED in listOf(leader.syncState, follower.syncState)
            if (corrected || failed || sim.maxInterPhoneOffsetUs > INTER_PHONE_TOLERANCE_US) {
                fail(
                    "problem 131: both phones share one restart latency, so their outputs start aligned " +
                        "(max inter-phone offset while neither ladder had acted: ${sim.maxInterPhoneOffsetBeforeCorrectionUs} us), " +
                        "yet the ladder chased the common-mode lag — leader hardSeeks=${leader.hardSeekCount} " +
                        "state=${leader.syncState}, follower hardSeeks=${follower.hardSeekCount} " +
                        "state=${follower.syncState}, max inter-phone offset over the run ${sim.maxInterPhoneOffsetUs} us\n$trace",
                )
            }
        }

    /**
     * Hypothesis (b) on its own: zero output latency, so the phone is **exactly** on the timeline at
     * every instant, and the only error left is that `tickOnce` pairs a position published up to one
     * tick ago with the session time of *now*. A phone that is exactly on time must not measure
     * itself as up to 253 ms behind, and must never hard-seek because of it.
     */
    @Test
    fun `a phone exactly on the timeline measures no drift from a stale position sample`() =
        runTest(StandardTestDispatcher()) {
            val sim = TwoPhoneSimulation(this, outputLatencyUs = 0, positionTickUs = 253_000)
            sim.connectAndPlay()
            sim.runFor(SIXTY_SECONDS_US)

            val trace = sim.trace()
            val worst = sim.samples.maxOf { kotlin.math.abs(it.leaderDriftMs ?: 0) }
            val leader = sim.leader.coordinator.diagnostics.value
            if (worst >= STALE_SAMPLE_TOLERANCE_MS || leader.hardSeekCount > 0) {
                fail(
                    "problem 131 (b): the leader is exactly on the timeline yet measured |drift| up to $worst ms " +
                        "and hard-seeked ${leader.hardSeekCount} time(s) — the cached 250 ms position was paired " +
                        "with a fresh session instant\n$trace",
                )
            }
            // Since ADR-004 Amendment A1 the leader never corrects, so its seek count above cannot
            // fail on its own. The follower is the one that acts, and it is exactly on time too.
            val follower = sim.follower.coordinator.diagnostics.value
            val worstFollower = sim.samples.maxOf { kotlin.math.abs(it.followerDriftMs ?: 0) }
            if (worstFollower >= STALE_SAMPLE_TOLERANCE_MS || follower.hardSeekCount > 0) {
                fail(
                    "problem 131 (b): the follower is exactly on the timeline yet measured |drift| up to " +
                        "$worstFollower ms and hard-seeked ${follower.hardSeekCount} time(s)\n$trace",
                )
            }
        }

    /**
     * ADR-004 Amendment A1's constraint: a lag only **one** phone has — the follower's Bluetooth
     * path adding 200 ms, and its output restarting 550 ms after a seek where the leader's takes
     * 350 ms — is real inter-phone error and must be corrected, not hidden as common-mode. The
     * follower makes at most two seeks (the first teaches its restart latency), never fails, and
     * ends audibly aligned with the leader. The leader, which is the reference, never seeks.
     */
    @Test
    fun `a lag only one phone has is still corrected, within the seek budget`() =
        runTest(StandardTestDispatcher()) {
            val sim =
                TwoPhoneSimulation(this, outputLatencyUs = 350_000, positionTickUs = 253_000, followerOutputLatencyUs = 550_000)
            sim.connectAndPlay()
            sim.runFor(SIXTY_SECONDS_US)

            val trace = sim.trace()
            val leader = sim.leader.coordinator.diagnostics.value
            val follower = sim.follower.coordinator.diagnostics.value
            val finalOffsetUs = kotlin.math.abs(sim.lastInterPhoneOffsetUs)
            if (follower.hardSeekCount !in 1..2 ||
                leader.hardSeekCount != 0 ||
                SyncState.SYNC_FAILED in listOf(leader.syncState, follower.syncState) ||
                finalOffsetUs > CONVERGED_TOLERANCE_US
            ) {
                fail(
                    "ADR-004 A1: a 200 ms differential lag must be corrected, not concealed — " +
                        "follower hardSeeks=${follower.hardSeekCount} state=${follower.syncState} " +
                        "learned=${follower.restartCompensationMs} ms, leader hardSeeks=${leader.hardSeekCount} " +
                        "state=${leader.syncState}, final inter-phone offset $finalOffsetUs us\n$trace",
                )
            }
        }

    // --- the harness ------------------------------------------------------------------------------

    private class Phone(
        val clock: FakeMonotonicClock,
        val player: OutputLatencyModelPlayer,
        val session: FakeSyncSession,
        val content: FakeSyncContent,
        val coordinator: SyncPlaybackCoordinator,
        /** This phone's monotonic clock minus session time. */
        val localMinusSessionUs: Long,
    ) {
        fun localUs(sessionUs: Long) = sessionUs + localMinusSessionUs

        fun sessionUs(localUs: Long) = localUs - localMinusSessionUs
    }

    private class Sample(
        val sessionUs: Long,
        val leaderDriftMs: Long?,
        val followerDriftMs: Long?,
        val leaderHardSeeks: Int,
        val followerHardSeeks: Int,
        val leaderState: SyncState,
        val followerState: SyncState,
        /** The leader's audible position minus the follower's, at the same session instant. */
        val interPhoneMs: Long,
    )

    /**
     * Two real coordinators on two unrelated monotonic clocks, joined directly — the same shape as
     * [SyncPlaybackTwoPeerTest]'s `Pair`, with [OutputLatencyModelPlayer] in place of the recorder.
     * Time is advanced to every pending deadline exactly, and in 50 ms steps between them, with each
     * player publishing its 250 ms ticks before the coordinators run.
     */
    private class TwoPhoneSimulation(
        private val scope: TestScope,
        outputLatencyUs: Long,
        positionTickUs: Long,
        followerOutputLatencyUs: Long = outputLatencyUs,
    ) {
        val leader: Phone = build(SyncTestValues.leaderPeerId, LEADER_START_US, 0L, 100, outputLatencyUs, positionTickUs)
        val follower: Phone =
            build(SyncTestValues.followerPeerId, LEADER_START_US - OFFSET_US, -OFFSET_US, 500, followerOutputLatencyUs, positionTickUs)
        private var sessionNowUs = LEADER_START_US
        val samples = mutableListOf<Sample>()
        var maxInterPhoneOffsetUs = 0L
            private set

        /** The same, but only while neither ladder has hard-seeked yet (observation 2's regime). */
        var maxInterPhoneOffsetBeforeCorrectionUs = 0L
            private set
        private var interPhoneOffsetUs = 0L

        /** The leader's audible position minus the follower's at the last instant both were playing. */
        val lastInterPhoneOffsetUs: Long get() = interPhoneOffsetUs

        init {
            leader.session.forwardTo(follower.session)
            follower.session.forwardTo(leader.session)
        }

        @Suppress("LongParameterList") // one per collaborator the phone owns
        private fun build(
            peerId: PeerId,
            startUs: Long,
            localMinusSessionUs: Long,
            idBase: Int,
            outputLatencyUs: Long,
            positionTickUs: Long,
        ): Phone {
            val clock = FakeMonotonicClock(nowUs = startUs)
            val player = OutputLatencyModelPlayer({ clock.nowUs() }, outputLatencyUs, positionTickUs)
            val session = FakeSyncSession()
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
            return Phone(clock, player, session, content, coordinator, localMinusSessionUs)
        }

        suspend fun connectAndPlay() {
            scope.runCurrent()
            // As SyncPlaybackTwoPeerTest: the follower's offset to the leader is +OFFSET_US; the
            // leader's coordinator substitutes zero for itself.
            leader.session.setClock(SessionClockEstimate(offsetToLeaderUs = OFFSET_US, rttP95Us = 8_000, ready = true))
            follower.session.setClock(SessionClockEstimate(offsetToLeaderUs = OFFSET_US, rttP95Us = 8_000, ready = true))
            leader.session.emit(ControlEvent.Connected(SyncTestValues.followerPeerId, SESSION_ID, true, 1L))
            follower.session.emit(ControlEvent.Connected(SyncTestValues.leaderPeerId, SESSION_ID, false, 1L))
            scope.runCurrent()
            val hash = SyncTestValues.hash(1).value
            leader.content.localHashes.add(hash)
            leader.content.peerHashes.add(hash)
            follower.content.localHashes.add(hash)
            follower.content.peerHashes.add(hash)
            leader.coordinator.playSynchronized(SyncTestValues.hash(1))
            scope.runCurrent()
            check(leader.session.sentOfType<PlaybackMessage.Play>().size == 1) { "the leader must have issued PLAY" }
        }

        fun runFor(durationUs: Long) {
            val endUs = sessionNowUs + durationUs
            while (sessionNowUs < endUs) {
                val leaderDeadlines = leader.clock.pendingDeadlines.map { leader.sessionUs(it) }
                val followerDeadlines = follower.clock.pendingDeadlines.map { follower.sessionUs(it) }
                val nextDeadline = (leaderDeadlines + followerDeadlines).filter { it > sessionNowUs }.minOrNull()
                val next = minOf(endUs, sessionNowUs + STEP_US, nextDeadline ?: Long.MAX_VALUE)
                advanceTo(next)
            }
        }

        private fun advanceTo(sessionUs: Long) {
            sessionNowUs = sessionUs
            for (phone in listOf(leader, follower)) {
                phone.clock.advanceTo(phone.localUs(sessionUs))
                phone.player.publishTicksUpTo(phone.clock.nowUs())
            }
            val leaderDiagBefore = leader.coordinator.diagnostics.value.correctionTickCount
            val followerDiagBefore = follower.coordinator.diagnostics.value.correctionTickCount
            scope.runCurrent()
            if (leader.player.isPlaying && follower.player.isPlaying) {
                val offset =
                    leader.player.presentedPositionUs(leader.localUs(sessionUs)) -
                        follower.player.presentedPositionUs(follower.localUs(sessionUs))
                interPhoneOffsetUs = offset
                maxInterPhoneOffsetUs = maxOf(maxInterPhoneOffsetUs, kotlin.math.abs(offset))
                if (leader.coordinator.diagnostics.value.hardSeekCount == 0 &&
                    follower.coordinator.diagnostics.value.hardSeekCount == 0
                ) {
                    maxInterPhoneOffsetBeforeCorrectionUs =
                        maxOf(maxInterPhoneOffsetBeforeCorrectionUs, kotlin.math.abs(offset))
                }
            }
            val l = leader.coordinator.diagnostics.value
            val f = follower.coordinator.diagnostics.value
            if (l.correctionTickCount != leaderDiagBefore || f.correctionTickCount != followerDiagBefore) {
                samples +=
                    Sample(
                        sessionUs - LEADER_START_US,
                        l.localDriftMs,
                        f.localDriftMs,
                        l.hardSeekCount,
                        f.hardSeekCount,
                        l.syncState,
                        f.syncState,
                        interPhoneOffsetUs / MICROS_PER_MS,
                    )
            }
        }

        fun trace(): String =
            samples.joinToString("\n", prefix = "t(s)  leaderDrift  followerDrift  seeks(L/F)  interPhoneMs  state(L/F)\n") {
                "%5.1f  %11s  %13s  %5d/%-4d  %12d  %s/%s".format(
                    it.sessionUs / 1_000_000.0,
                    it.leaderDriftMs,
                    it.followerDriftMs,
                    it.leaderHardSeeks,
                    it.followerHardSeeks,
                    it.interPhoneMs,
                    it.leaderState,
                    it.followerState,
                )
            }
    }

    /**
     * A physical model of `ExoPlayerMusicPlayer` over Media3 on a phone (see the class comment).
     * Internally in microseconds; publishes milliseconds, as the real one does.
     */
    private class OutputLatencyModelPlayer(
        private val nowUs: () -> Long,
        val outputLatencyUs: Long,
        private val positionTickUs: Long,
    ) : SyncPlayerPort {
        private var basePositionUs = 0L

        /** Local instant output (and the reported position) starts advancing; null while paused. */
        private var advancingFromUs: Long? = null
        private var rate = 1.0
        private var tickOriginUs = 0L
        private var lastPublishedTickUs = Long.MIN_VALUE
        var isPlaying = false
            private set
        private val stateFlow = MutableStateFlow(PlayerState())
        override val playerState: StateFlow<PlayerState> = stateFlow.asStateFlow()

        /** What is audible — and, per Media3's presentation-timestamp design, what `currentPosition` is. */
        fun presentedPositionUs(atUs: Long): Long {
            val from = advancingFromUs
            return if (from == null || atUs <= from) basePositionUs else basePositionUs + ((atUs - from) * rate).toLong()
        }

        /** The 250 ms `delay` loop: publishes the position as it was at the most recent tick instant. */
        fun publishTicksUpTo(atUs: Long) {
            if (!isPlaying || atUs < tickOriginUs) return
            val latestTickUs = tickOriginUs + ((atUs - tickOriginUs) / positionTickUs) * positionTickUs
            if (latestTickUs <= lastPublishedTickUs) return
            publish(latestTickUs)
        }

        private fun publish(atUs: Long) {
            lastPublishedTickUs = atUs
            stateFlow.value =
                PlayerState(
                    positionMs = presentedPositionUs(atUs) / MICROS_PER_MS,
                    durationMs = TRACK_DURATION_MS,
                    playing = isPlaying,
                    rate = rate,
                )
        }

        /** `ExoPlayer.currentPosition` read live: what is presented right now (ADR-004 Amendment A1). */
        override suspend fun samplePosition(monotonicNowUs: () -> Long): PlayerPositionSample {
            val atUs = monotonicNowUs()
            return PlayerPositionSample(presentedPositionUs(atUs) / MICROS_PER_MS, TRACK_DURATION_MS, isPlaying, rate, atUs)
        }

        /** `onIsPlayingChanged(true)` restarts the tick loop, which samples immediately. */
        private fun restartTicking() {
            tickOriginUs = nowUs()
            publish(tickOriginUs)
        }

        override suspend fun select(content: SyncPlayableContent) = Unit

        override suspend fun load(content: SyncPlayableContent) {
            basePositionUs = 0
            advancingFromUs = null
            isPlaying = false
            stateFlow.value = PlayerState(durationMs = TRACK_DURATION_MS)
        }

        override suspend fun clearSelection() = Unit

        override suspend fun start() {
            if (isPlaying) return
            basePositionUs = presentedPositionUs(nowUs())
            advancingFromUs = nowUs() + outputLatencyUs
            isPlaying = true
            restartTicking()
        }

        override suspend fun pause() {
            basePositionUs = presentedPositionUs(nowUs())
            advancingFromUs = null
            isPlaying = false
            publish(nowUs())
        }

        override suspend fun seek(positionMs: Long) {
            basePositionUs = positionMs * MICROS_PER_MS
            // A seek while playing flushes the AudioTrack: output restarts, and so does the latency.
            advancingFromUs = if (isPlaying) nowUs() + outputLatencyUs else null
            // `ExoPlayerMusicPlayer` publishes `exoPlayer.currentPosition` (the target) immediately,
            // and the brief BUFFERING toggles `isPlaying`, restarting the tick loop.
            if (isPlaying) restartTicking() else publish(nowUs())
        }

        override suspend fun setRate(rate: Double) {
            val now = nowUs()
            val from = advancingFromUs
            if (from != null && now > from) {
                basePositionUs = presentedPositionUs(now)
                advancingFromUs = now
            }
            this.rate = rate
        }

        override suspend fun stop() {
            basePositionUs = 0
            advancingFromUs = null
            isPlaying = false
            stateFlow.value = PlayerState()
        }
    }

    private companion object {
        val SESSION_ID = SessionId("problem-131")
        const val LEADER_START_US = 10_000_000L
        const val OFFSET_US = 7_500_000L
        const val STEP_US = 50_000L
        const val SIXTY_SECONDS_US = 60_000_000L
        const val MICROS_PER_MS = 1_000L
        const val TRACK_DURATION_MS = 300_000L

        /** The ladder's own dead band: a phone exactly on time must measure inside it. */
        const val STALE_SAMPLE_TOLERANCE_MS = 25L

        /** Observation 2's measured bound on the real phones. */
        const val INTER_PHONE_TOLERANCE_US = 2_000L

        /** The ladder's own dead band: what a converged correction leaves at most. */
        const val CONVERGED_TOLERANCE_US = 25_000L

        init {
            // Session time is the leader's clock (ADR-010); the follower's local = session - OFFSET.
            check(SessionClock.localMonoUs(LEADER_START_US, OFFSET_US) == LEADER_START_US - OFFSET_US)
        }
    }
}
