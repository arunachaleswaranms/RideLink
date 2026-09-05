package com.ridelink.app.service

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.ridelink.app.music.MusicCoordinator

/**
 * ADR-022's "the `MediaSession` callback routes to [MusicCoordinator], never a second path to
 * playback state" decision, realised against the actual `androidx.media3.session` 1.11.0 API
 * surface rather than the pre-Media3 `MediaSessionCompat.Callback` shape the ADR's prose sketches.
 *
 * At this pinned version, `androidx.media3.session.MediaSession.Callback` has **no**
 * `onPlay`/`onPause`/`onSeekTo`/`onSkipToNext`/`onSkipToPrevious` methods to override — a
 * `MediaSession` instead forwards standard transport commands straight to whichever
 * [androidx.media3.common.Player] it was built with (`MediaSession.Builder(context, player)`).
 * This [ForwardingPlayer] **is** the real routing point: it wraps the one real `ExoPlayer` instance
 * ([com.ridelink.audio.player.ExoPlayerMusicPlayer.media3Player]) and hands every transport request
 * straight to the one [MusicCoordinator] instance already accepting exactly these calls from the
 * in-app UI — never to the wrapped player directly, so a lock-screen tap and an in-app tap are the
 * same one call, never two paths to playback state.
 *
 * Play/pause/seek could, in this app's current shape, be left to fall straight through to the
 * wrapped player with an equivalent real-world effect ([MusicCoordinator.play]/[pause]/[seek] are
 * thin wrappers over the exact same [com.ridelink.core.player.Player] this class also wraps) — they
 * are routed through [coordinator] anyway, so there is exactly one code path for "what does a
 * play/pause/seek request do," never a wrapped-player path that happens to agree with a
 * coordinator path by coincidence.
 *
 * Skip-to-next/previous cannot be left to the wrapped player at all: the real `ExoPlayer` here only
 * ever holds the one currently-selected `MediaItem` — [com.ridelink.core.player.PlaybackCommand.Load]
 * loads a fresh one, it never builds a native ExoPlayer playlist (see that type's own KDoc: "Next/
 * Previous are a queue-owner concept"). Only [MusicCoordinator]'s `LocalQueue` knows what "next"
 * means, so [getAvailableCommands]/[isCommandAvailable] additionally force those two commands into
 * the available set — the wrapped single-item player would otherwise report no next/previous item
 * and hide the lock-screen buttons entirely.
 *
 * Media3 marks `ForwardingPlayer` itself, several of the `Player` methods this class overrides, and
 * `Player.Commands.Builder.addAll` as `@UnstableApi` — this class's whole reason to exist is
 * overriding exactly those methods, so the annotation belongs at the class, not scattered per-method.
 */
@UnstableApi
internal class MusicSessionPlayer(
    player: Player,
    private val coordinator: MusicCoordinator,
) : ForwardingPlayer(player) {
    // Block bodies throughout, deliberately: several MusicCoordinator methods (`pause`, `seek`) have
    // an expression-body `= scope.launch { ... }` and so are themselves typed `Job`, not `Unit` — an
    // expression-body override here would inherit that `Job` return type and fail to override
    // `Player`'s `Unit`-returning methods.
    override fun play() {
        coordinator.play()
    }

    override fun pause() {
        coordinator.pause()
    }

    override fun seekTo(positionMs: Long) {
        coordinator.seek(positionMs)
    }

    override fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
    ) {
        coordinator.seek(positionMs)
    }

    override fun seekToNext() {
        coordinator.next()
    }

    override fun seekToNextMediaItem() {
        coordinator.next()
    }

    override fun seekToPrevious() {
        coordinator.previous()
    }

    override fun seekToPreviousMediaItem() {
        coordinator.previous()
    }

    override fun isCommandAvailable(command: Int): Boolean =
        when (command) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            -> true
            else -> super.isCommandAvailable(command)
        }

    override fun getAvailableCommands(): Player.Commands =
        super
            .getAvailableCommands()
            .buildUpon()
            .addAll(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            ).build()
}
