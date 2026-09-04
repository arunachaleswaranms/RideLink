package com.ridelink.core.player

/**
 * The local playback engine, as the app sees it — same seam pattern as
 * [com.ridelink.core.voice.VoiceEngine]: every parameter and event payload is a plain value, so
 * `core` stays free of platform types (CLAUDE.md rule 9) and a fake implementation can drive
 * queue/coordinator logic with no `ExoPlayer`/`AVAudioEngine`, no file I/O and no decoder at all.
 *
 * **A fake implementation proves the coordinator, not the codec** — the same caveat
 * [com.ridelink.core.voice.VoiceEngine] carries. Real decode/output behaviour is only ever proven
 * by the real bindings (`audio.player.ExoPlayerMusicPlayer` / `RideLinkPlatform.Player`) and,
 * beyond that, by the real-device gate.
 *
 * [execute] never throws: a command against a missing or corrupt file resolves to
 * [PlayerState.error] on the next emitted state, exactly the way [PlaybackCommand.Load] loading a
 * deleted file must surface [MusicFailure.FILE_MISSING] rather than crash the caller.
 */
interface Player {
    suspend fun execute(command: PlaybackCommand): Result<Unit>

    val state: PlayerState

    /** Pushed on every state change, including position ticks while playing. */
    fun setStateSink(sink: (PlayerState) -> Unit)
}
