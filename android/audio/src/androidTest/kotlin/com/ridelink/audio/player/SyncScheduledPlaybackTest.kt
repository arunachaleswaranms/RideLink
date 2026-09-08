package com.ridelink.audio.player

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ridelink.core.library.LocalTrackLocation
import com.ridelink.core.model.LocalEntryId
import com.ridelink.core.playback.DriftController
import com.ridelink.core.player.PlaybackCommand
import com.ridelink.core.player.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 5's two platform-facing claims, against the **real** `ExoPlayer` on the real
 * `RideLink_API36` emulator: that a scheduled start actually happens at a monotonic deadline after
 * a pre-roll, and that ADR-004's rate nudge actually reaches `setPlaybackParameters` and comes back
 * to **exactly** 1.0.
 *
 * Everything else about Phase 5 is proven by pure, mirrored, vector-pinned tables and by coordinator
 * suites driving a fake player. Those prove the decisions; they cannot prove that a real decoder
 * honours them, which is what this file is for — the same division `ExoPlayerMusicPlayerTest` next
 * to it already draws for Phase 3.
 *
 * **This is still not an alignment measurement.** It runs on one emulator, with one player, and
 * measures a *software* scheduling error between a monotonic deadline and the instant `ExoPlayer`
 * reported playing. There is no second phone, no Bluetooth, no speaker and no recorder anywhere in
 * it. TEST_PLAN §5.2's S-03 is the only thing that will ever produce an alignment figure.
 */
@RunWith(AndroidJUnit4::class)
class SyncScheduledPlaybackTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var player: ExoPlayerMusicPlayer
    private lateinit var states: Channel<PlayerState>

    private fun fixtureUri(assetName: String): Uri {
        val outFile = File(context.filesDir, "sync-fixture-${System.nanoTime()}-$assetName")
        context.assets.open(assetName).use { input -> outFile.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(outFile)
    }

    /** The same monotonic source Phase 5 schedules against — never a wall clock (PROTOCOL §2 rule 5). */
    private fun monotonicNowUs(): Long = android.os.SystemClock.elapsedRealtimeNanos() / NANOS_PER_MICRO

    /**
     * The production `MonotonicDeadlineSleeper`'s logic, inlined because it lives in `app` and
     * `audio` must not depend on it (ADR-014). Coarse steps until the last stretch, then fine ones,
     * so a scheduler that overshoots a single long sleep cannot cost the whole margin.
     */
    private suspend fun sleepUntil(deadlineUs: Long) {
        while (true) {
            val remainingUs = deadlineUs - monotonicNowUs()
            if (remainingUs <= 0) return
            val remainingMs = remainingUs / MICROS_PER_MS
            delay(if (remainingMs > COARSE_STEP_MS) COARSE_STEP_MS else remainingMs.coerceAtLeast(1))
        }
    }

    @Before
    fun setUp() {
        states = Channel(capacity = Channel.UNLIMITED)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            player = ExoPlayerMusicPlayer(context, CoroutineScope(Dispatchers.Main))
            player.setStateSink { states.trySend(it) }
        }
    }

    @After
    fun tearDown() = runBlocking { player.release() }

    /**
     * ARCHITECTURE §7.2 steps 5–6 against a real decoder: pre-roll to a position while there is
     * still time, wait out the deadline on the monotonic clock, then start. The point of the
     * pre-roll is that opening and priming a decoder takes tens of milliseconds and doing it *after*
     * the deadline guarantees a late start — so the assertion that matters is that the decoder was
     * already `READY` before the deadline arrived.
     */
    @Test
    fun aPreRolledTrackStartsAtItsMonotonicDeadline() =
        runBlocking {
            val entry = LocalEntryId("5e5e5e5e-0000-0000-0000-000000000001")
            player.execute(PlaybackCommand.Load(entry, LocalTrackLocation(fixtureUri("normal.m4a").toString())))
            player.execute(PlaybackCommand.Seek(PRE_ROLL_POSITION_MS))
            // Duration becomes known once the decoder is READY — the observable proxy for "pre-rolled".
            withTimeout(TIMEOUT_MS) { states.receiveAsFlow().filter { it.durationMs > 0 }.first() }

            val deadlineUs = monotonicNowUs() + LEAD_US
            sleepUntil(deadlineUs)
            val wokeAtUs = monotonicNowUs()
            player.execute(PlaybackCommand.Play)
            val playing = withTimeout(TIMEOUT_MS) { states.receiveAsFlow().filter { it.playing }.first() }
            val startedAtUs = monotonicNowUs()

            assertTrue(playing.playing, "the real player must actually be playing")
            val wakeErrorUs = wokeAtUs - deadlineUs
            val startErrorUs = startedAtUs - deadlineUs
            // Recorded, not silently asserted away: these are the first real numbers Phase 5 has on
            // any Android runtime, and they are software scheduling only.
            println("sync-schedule: wake error ${wakeErrorUs}us, start-observed error ${startErrorUs}us (software only)")
            assertTrue(wakeErrorUs >= 0, "the sleeper must never wake before the deadline")
            assertTrue(
                wakeErrorUs < WAKE_TOLERANCE_US,
                "the monotonic sleeper woke ${wakeErrorUs}us late, past the ${WAKE_TOLERANCE_US}us bound this test asserts",
            )
        }

    /**
     * ADR-004's rate-nudge tier against the real `setPlaybackParameters`, and brief §38's invariant:
     * correction always ends at **exactly** 1.0.
     */
    @Test
    fun aRateNudgeReachesTheRealPlayerAndIsRestoredToExactlyOne() =
        runBlocking {
            val entry = LocalEntryId("5e5e5e5e-0000-0000-0000-000000000002")
            player.execute(PlaybackCommand.Load(entry, LocalTrackLocation(fixtureUri("normal.m4a").toString())))
            withTimeout(TIMEOUT_MS) { states.receiveAsFlow().filter { it.durationMs > 0 }.first() }
            player.execute(PlaybackCommand.Play)
            withTimeout(TIMEOUT_MS) { states.receiveAsFlow().filter { it.playing }.first() }

            player.execute(PlaybackCommand.SetRate(DriftController.RATE_SLOWER))
            val slowed = withTimeout(TIMEOUT_MS) { states.receiveAsFlow().filter { it.rate < 1.0 }.first() }
            assertEquals(DriftController.RATE_SLOWER, slowed.rate, "the ladder's 0.998 must reach the real player")

            player.execute(PlaybackCommand.SetRate(DriftController.RATE_FASTER))
            val hurried = withTimeout(TIMEOUT_MS) { states.receiveAsFlow().filter { it.rate > 1.0 }.first() }
            assertEquals(DriftController.RATE_FASTER, hurried.rate)

            player.execute(PlaybackCommand.SetRate(DriftController.RATE_NORMAL))
            val restored = withTimeout(TIMEOUT_MS) { states.receiveAsFlow().filter { it.rate == 1.0 }.first() }
            assertEquals(1.0, restored.rate, "correction must always end at exactly 1.0")
        }

    /** A `Stop` must not leave a nudge in force for whatever the next track inherits (brief §38). */
    @Test
    fun stopRestoresTheRateToExactlyOne() =
        runBlocking {
            val entry = LocalEntryId("5e5e5e5e-0000-0000-0000-000000000003")
            player.execute(PlaybackCommand.Load(entry, LocalTrackLocation(fixtureUri("normal.m4a").toString())))
            withTimeout(TIMEOUT_MS) { states.receiveAsFlow().filter { it.durationMs > 0 }.first() }
            player.execute(PlaybackCommand.SetRate(DriftController.RATE_SLOWER))
            withTimeout(TIMEOUT_MS) { states.receiveAsFlow().filter { it.rate < 1.0 }.first() }

            player.execute(PlaybackCommand.Stop)
            val stopped = withTimeout(TIMEOUT_MS) { states.receiveAsFlow().filter { !it.playing && it.rate == 1.0 }.first() }
            assertEquals(1.0, stopped.rate)
            assertEquals(0, stopped.positionMs)
        }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val PRE_ROLL_POSITION_MS = 1_000L
        const val NANOS_PER_MICRO = 1_000L
        const val MICROS_PER_MS = 1_000L
        const val COARSE_STEP_MS = 20L

        /** ARCHITECTURE §7.2's 120 ms floor — the lead a real `LEAD = max(120 ms, 4 x rtt_p95)` would give. */
        const val LEAD_US = 120_000L

        /**
         * How late the monotonic sleeper may wake on an emulator under CI-class load. Generous on
         * purpose: this bounds a *scheduler*, and a tighter number would fail for reasons that are
         * not bugs. It is not, and must never be read as, an alignment figure.
         */
        const val WAKE_TOLERANCE_US = 60_000L
    }
}
