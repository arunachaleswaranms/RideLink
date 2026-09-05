package com.ridelink.audio.player

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ridelink.core.library.LocalTrackLocation
import com.ridelink.core.model.LocalEntryId
import com.ridelink.core.player.MusicFailure
import com.ridelink.core.player.PlaybackCommand
import com.ridelink.core.player.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
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
 * Real `ExoPlayer`, real AAC decode, on the real `RideLink_API36` emulator — brief §25/§26's "real
 * decode/output behaviour is only ever proven by the real bindings." A fake `Player` (used
 * elsewhere for pure queue/coordinator tests) proves none of what this proves.
 *
 * `ExoPlayer` must be constructed and driven from a thread with a prepared `Looper` — the
 * instrumentation test thread has none — so both construction and every [PlaybackCommand] go
 * through [androidx.test.platform.app.InstrumentationRegistry]'s main-thread hop.
 */
@RunWith(AndroidJUnit4::class)
class ExoPlayerMusicPlayerTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var player: ExoPlayerMusicPlayer
    private lateinit var states: Channel<PlayerState>

    private fun fixtureUri(assetName: String): Uri {
        val outFile = File(context.filesDir, "player-fixture-${System.nanoTime()}-$assetName")
        context.assets.open(assetName).use { input -> outFile.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(outFile)
    }

    private fun runOnMain(block: suspend () -> Unit) = runBlocking { block() }

    @Before
    fun setUp() {
        states = Channel(capacity = Channel.UNLIMITED)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            player = ExoPlayerMusicPlayer(context, CoroutineScope(Dispatchers.Main))
            player.setStateSink { states.trySend(it) }
        }
    }

    @After
    fun tearDown() {
        runOnMain { player.release() }
    }

    @Test
    fun loadingARealTrackReportsARealDuration() =
        runOnMain {
            val hash = LocalEntryId("a1a1a1a1-0000-0000-0000-000000000001")
            player.execute(PlaybackCommand.Load(hash, LocalTrackLocation(fixtureUri("normal.m4a").toString())))
            val ready = withTimeout(TIMEOUT_MS) { states.receiveAsFlow().filter { it.durationMs > 0 }.first() }
            assertEquals(hash, ready.localEntryId)
            assertTrue(ready.durationMs > 0, "a real AAC file must report a real, positive duration")
        }

    @Test
    fun playPauseAndPositionAdvancement() =
        runOnMain {
            val hash = LocalEntryId("b2b2b2b2-0000-0000-0000-000000000002")
            player.execute(PlaybackCommand.Load(hash, LocalTrackLocation(fixtureUri("normal.m4a").toString())))
            withTimeout(TIMEOUT_MS) { states.receiveAsFlow().filter { it.durationMs > 0 }.first() }
            player.execute(PlaybackCommand.Play)
            val playing = withTimeout(TIMEOUT_MS) { states.receiveAsFlow().filter { it.playing }.first() }
            assertTrue(playing.playing)
            val advanced = withTimeout(TIMEOUT_MS) { states.receiveAsFlow().filter { it.positionMs > 0 }.first() }
            assertTrue(advanced.positionMs > 0, "position must actually advance while playing")
            player.execute(PlaybackCommand.Pause)
            val paused = withTimeout(TIMEOUT_MS) { states.receiveAsFlow().filter { !it.playing }.first() }
            assertTrue(!paused.playing)
        }

    @Test
    fun seekMovesPosition() =
        runOnMain {
            player.execute(
                PlaybackCommand.Load(
                    LocalEntryId("c3c3c3c3-0000-0000-0000-000000000003"),
                    LocalTrackLocation(fixtureUri("normal.m4a").toString()),
                ),
            )
            withTimeout(TIMEOUT_MS) { states.receiveAsFlow().filter { it.durationMs > 0 }.first() }
            player.execute(PlaybackCommand.Seek(SEEK_TARGET_MS))
            val sought = withTimeout(TIMEOUT_MS) { states.receiveAsFlow().filter { it.positionMs >= SEEK_TARGET_MS }.first() }
            assertTrue(sought.positionMs >= SEEK_TARGET_MS)
        }

    @Test
    fun stopResetsPositionAndPlayingState() =
        runOnMain {
            player.execute(
                PlaybackCommand.Load(
                    LocalEntryId("d4d4d4d4-0000-0000-0000-000000000004"),
                    LocalTrackLocation(fixtureUri("normal.m4a").toString()),
                ),
            )
            withTimeout(TIMEOUT_MS) { states.receiveAsFlow().filter { it.durationMs > 0 }.first() }
            player.execute(PlaybackCommand.Play)
            withTimeout(TIMEOUT_MS) { states.receiveAsFlow().filter { it.playing }.first() }
            player.execute(PlaybackCommand.Stop)
            val stopped = withTimeout(TIMEOUT_MS) { states.receiveAsFlow().filter { !it.playing && it.positionMs == 0L }.first() }
            assertTrue(!stopped.playing)
            assertEquals(0L, stopped.positionMs)
        }

    @Test
    fun loadingAMissingFileReportsFileMissing() =
        runOnMain {
            val missingUri = Uri.fromFile(File(context.filesDir, "does-not-exist-${System.nanoTime()}.m4a"))
            player.execute(
                PlaybackCommand.Load(LocalEntryId("e5e5e5e5-0000-0000-0000-000000000005"), LocalTrackLocation(missingUri.toString())),
            )
            val failed = withTimeout(TIMEOUT_MS) { states.receiveAsFlow().filter { it.error != null }.first() }
            assertEquals(MusicFailure.FILE_MISSING, failed.error)
        }

    @Test
    fun loadingUnparseableContentReportsAFailureRatherThanCrashing() =
        runOnMain {
            // Deliberately not test-media/synthetic/unsupported.xyz: that fixture's *bytes* are a
            // real, valid M4A (only its extension is wrong), and ExoPlayer's extractors sniff
            // content rather than trusting a file extension — a real finding from running this on
            // the emulator, where this fixture played back completely normally through the player,
            // even though data.library's extension gate (a separate, deliberate policy) never lets
            // it reach the player in the real app. Genuinely non-media bytes are what exercises the
            // player's own failure path.
            val garbage = File(context.filesDir, "garbage-${System.nanoTime()}.m4a").apply { writeBytes(ByteArray(256) { 0x2A }) }
            player.execute(
                PlaybackCommand.Load(
                    LocalEntryId("f6f6f6f6-0000-0000-0000-000000000006"),
                    LocalTrackLocation(Uri.fromFile(garbage).toString()),
                ),
            )
            val failed = withTimeout(TIMEOUT_MS) { states.receiveAsFlow().filter { it.error != null }.first() }
            assertTrue(
                failed.error == MusicFailure.UNSUPPORTED_FORMAT || failed.error == MusicFailure.DECODE_FAILED,
                "expected an unsupported/decode failure, got ${failed.error}",
            )
        }

    @Test
    fun endOfTrackIsReportedAsEnded() =
        runOnMain {
            // normal.m4a is ~0.5s — short enough to reach the real end of track within the test's
            // own timeout rather than needing a fake clock, proving the real ExoPlayer STATE_ENDED
            // callback path end to end.
            player.execute(
                PlaybackCommand.Load(
                    LocalEntryId("07070707-0000-0000-0000-000000000007"),
                    LocalTrackLocation(fixtureUri("normal.m4a").toString()),
                ),
            )
            withTimeout(TIMEOUT_MS) { states.receiveAsFlow().filter { it.durationMs > 0 }.first() }
            player.execute(PlaybackCommand.Play)
            val ended = withTimeout(END_OF_TRACK_TIMEOUT_MS) { states.receiveAsFlow().filter { it.ended }.first() }
            assertTrue(ended.ended)
        }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val END_OF_TRACK_TIMEOUT_MS = 15_000L
        const val SEEK_TARGET_MS = 200L
    }
}
