package com.ridelink.audio.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import com.ridelink.core.model.LocalEntryId
import com.ridelink.core.player.MusicFailure
import com.ridelink.core.player.PlaybackCommand
import com.ridelink.core.player.Player
import com.ridelink.core.player.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.media3.common.Player as Media3Player

/**
 * The real local-playback binding: `androidx.media3.exoplayer.ExoPlayer` behind
 * [com.ridelink.core.player.Player]'s platform-free seam — the same isolation
 * [com.ridelink.core.voice.VoiceEngine] gives WebRTC (ADR-003), applied to the music plane.
 *
 * **Every ExoPlayer call is confined to [Dispatchers.Main]**, which is what ExoPlayer's own
 * threading contract requires (it is built on, and must be driven from, one "application thread" —
 * by default the thread that constructed it). [execute] hops there explicitly rather than assuming
 * the caller already is on it. [state] never touches ExoPlayer directly for the same reason: it
 * reads a `@Volatile` snapshot kept current by the listener and the position-tick loop, matching
 * [com.ridelink.core.voice.VoiceEngineDiagnostics]'s existing "never live-query the platform object
 * from an arbitrary thread" convention.
 */
class ExoPlayerMusicPlayer(
    context: Context,
    private val mainScope: CoroutineScope,
) : Player {
    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()

    /**
     * The real `androidx.media3.common.Player` this instance wraps, exposed only so the composition
     * root (`app`) can attach a real `androidx.media3.session.MediaSession` around the *same* player
     * `MusicCoordinator` already drives (ADR-022) — never through [com.ridelink.core.player.Player],
     * which must stay platform-free (ADR-014). Nothing in `audio` or `core` reads this; the one
     * caller is `RideForegroundService`'s composition-root wiring in `app`.
     */
    val media3Player: Media3Player get() = exoPlayer

    @Volatile
    private var cachedState: PlayerState = PlayerState()

    @Volatile
    private var stateSink: ((PlayerState) -> Unit)? = null
    private var positionTickJob: Job? = null

    init {
        exoPlayer.addListener(
            object : Media3Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateState { it.copy(playing = isPlaying, durationMs = currentDurationMs()) }
                    if (isPlaying) startPositionTicking() else stopPositionTicking()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        // Duration becomes known here, independent of whether anything ever plays —
                        // a real bug found only by running this on a device/emulator: durationMs was
                        // previously updated only from onIsPlayingChanged, which never fires for a
                        // Load without a following Play, silently leaving PlayerState.durationMs at
                        // 0 forever for a load-only caller.
                        Media3Player.STATE_READY -> updateState { it.copy(durationMs = currentDurationMs()) }
                        Media3Player.STATE_ENDED -> updateState { it.copy(playing = false, positionMs = it.durationMs) }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    updateState { it.copy(playing = false, error = classify(error)) }
                }
            },
        )
    }

    override suspend fun execute(command: PlaybackCommand): Result<Unit> =
        withContext(Dispatchers.Main.immediate) {
            when (command) {
                is PlaybackCommand.Load -> load(command.localEntryId, command.location.uri, command.title, command.artist)
                PlaybackCommand.Play -> exoPlayer.play()
                PlaybackCommand.Pause -> exoPlayer.pause()
                is PlaybackCommand.Seek -> {
                    exoPlayer.seekTo(command.positionMs)
                    // A seek while paused has no position-tick loop running to observe it (that
                    // only runs while playing) and ExoPlayer raises no callback this class listens
                    // for on a programmatic seek — a real bug found on the emulator: PlayerState
                    // silently kept reporting the pre-seek position forever when paused.
                    updateState { it.copy(positionMs = exoPlayer.currentPosition) }
                }
                PlaybackCommand.Stop -> {
                    exoPlayer.stop()
                    stopPositionTicking()
                    updateState { it.copy(playing = false, positionMs = 0) }
                }
            }
            Result.success(Unit)
        }

    private fun load(
        localEntryId: LocalEntryId,
        uri: String,
        title: String?,
        artist: String?,
    ) {
        stopPositionTicking()
        cachedState = PlayerState(localEntryId = localEntryId)
        emit(cachedState)
        runCatching {
            val mediaItem =
                MediaItem
                    .Builder()
                    .setUri(Uri.parse(uri))
                    // Real title/artist here (ADR-022), not left to whatever embedded tags the file
                    // happens to carry — the one place a lock-screen/MediaSession metadata query
                    // (`Player.getMediaMetadata()`) gets a real answer rather than an empty one.
                    .setMediaMetadata(
                        MediaMetadata
                            .Builder()
                            .setTitle(title)
                            .setArtist(artist)
                            .build(),
                    ).build()
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
        }.onFailure { error ->
            updateState { it.copy(error = classify(error)) }
        }
    }

    override val state: PlayerState get() = cachedState

    override fun setStateSink(sink: (PlayerState) -> Unit) {
        stateSink = sink
    }

    override suspend fun release() =
        withContext(Dispatchers.Main.immediate) {
            stopPositionTicking()
            exoPlayer.release()
        }

    /** While playing, [PlayerState.positionMs] needs to advance for the UI's seek bar and for
     *  [PlayerState.ended] to ever become observable — ExoPlayer has no "position changed" callback
     *  of its own, only a pollable [ExoPlayer.getCurrentPosition]. */
    private fun startPositionTicking() {
        if (positionTickJob?.isActive == true) return
        positionTickJob =
            mainScope.launch(Dispatchers.Main.immediate) {
                while (isActive) {
                    updateState { it.copy(positionMs = exoPlayer.currentPosition, durationMs = currentDurationMs()) }
                    delay(POSITION_TICK_MS)
                }
            }
    }

    private fun stopPositionTicking() {
        positionTickJob?.cancel()
        positionTickJob = null
    }

    private fun currentDurationMs(): Long = exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: 0L

    private fun updateState(transform: (PlayerState) -> PlayerState) {
        val next = transform(cachedState)
        cachedState = next
        emit(next)
    }

    private fun emit(state: PlayerState) {
        stateSink?.invoke(state)
    }

    /**
     * ExoPlayer classifies its own failures via [PlaybackException.errorCode] — Media3's documented
     * way to distinguish "missing file" from "unsupported format" from "genuine I/O error", and far
     * more reliable than pattern-matching the exception's cause chain (tried first; a real bug found
     * on the emulator: a missing-file `FileNotFoundException` arrives wrapped deep enough that a
     * one-level `cause` check never found it and every failure fell through to a generic bucket).
     * This is the one place that error-code space is read, so nothing downstream needs to know it.
     */
    private fun classify(error: Throwable): MusicFailure {
        val errorCode = (error as? PlaybackException)?.errorCode
        return when (errorCode) {
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            -> MusicFailure.FILE_MISSING
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            -> MusicFailure.UNSUPPORTED_FORMAT
            else ->
                when {
                    errorCode != null &&
                        errorCode >= PlaybackException.ERROR_CODE_IO_UNSPECIFIED &&
                        errorCode < PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                    -> MusicFailure.STORAGE_IO
                    else -> MusicFailure.DECODE_FAILED
                }
        }
    }

    private companion object {
        const val POSITION_TICK_MS = 250L
    }
}
