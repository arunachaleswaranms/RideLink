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

    /**
     * Installs the Phase 6 coexistence lifetime. A later generation invalidates every delayed gain,
     * pause and resume from its predecessor before that effect can touch the renderer.
     */
    suspend fun beginCoexistenceLifetime(generation: Long) = Unit

    /** Applies one already-interpolated temporary gain step if [generation] still owns the player. */
    suspend fun setCoexistenceGain(
        generation: Long,
        gain: Double,
    ): Boolean = false

    /** Temporarily pauses only if the exact expected track is still loaded. */
    suspend fun pauseForVoice(
        generation: Long,
        trackToken: String,
    ): Boolean = false

    /** Resumes only the exact track that coexistence previously suppressed. */
    suspend fun resumeAfterVoice(
        generation: Long,
        trackToken: String,
    ): Boolean = false

    /**
     * Releases the underlying decoder/renderer resources. Unlike
     * [com.ridelink.core.voice.VoiceEngine]'s `stop`/`release` split, there is no hardware reason
     * to keep two lifecycles here — a local player has no Bluetooth profile to avoid disturbing —
     * so one method covers what a control-link blip and a deliberate app teardown both need.
     * Idempotent.
     */
    suspend fun release()
}
