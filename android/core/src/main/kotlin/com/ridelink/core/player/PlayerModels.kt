package com.ridelink.core.player

import com.ridelink.core.library.LocalTrackLocation
import com.ridelink.core.model.ContentHash

/**
 * What a [Player] can be told to do. Deliberately narrow (this phase's brief §15): loading and
 * transport control only. Next/Previous are a **queue-owner** concept ([com.ridelink.core.player.LocalQueue]
 * turns them into a fresh [Load] + [Play]), not a player command — a player has no idea what
 * "next" means.
 *
 * No `commandSeq`, no `effectiveAtSessionTime` — that is Phase 5 wire behaviour (ADR-004/§9.3) and
 * this phase's brief §15 explicitly excludes it. A local player executes commands as it receives
 * them, immediately.
 */
sealed class PlaybackCommand {
    /**
     * [location] is what a real player binding actually opens; [contentHash] is carried alongside
     * purely for identity so [PlayerState.contentHash] can report *which* track is loaded without
     * the player ever resolving a hash back to a location itself. That resolution
     * (`ContentHash -> LocalTrackLocation`) is `data.library.LibraryRepository`'s job — `audio`
     * (where the real player binding lives) must never depend on `data` (ADR-014), so the one
     * layer allowed to depend on both, the `app` composition root's queue-owner coordinator, does
     * the lookup and builds this command already carrying everything the player needs.
     */
    data class Load(
        val contentHash: ContentHash,
        val location: LocalTrackLocation,
    ) : PlaybackCommand()

    object Play : PlaybackCommand()

    object Pause : PlaybackCommand()

    data class Seek(
        val positionMs: Long,
    ) : PlaybackCommand()

    object Stop : PlaybackCommand()
}

/**
 * Named failures a platform decoder/player can hit, never a raw exception crossing the domain
 * boundary (matches [com.ridelink.core.audiopolicy.VoiceFailure]'s existing pattern: one value per
 * distinct thing the user or the app can do about it).
 */
enum class MusicFailure {
    /** The platform decoder rejected the file's content — [com.ridelink.core.library.DecodeStatus.CORRUPT]. */
    DECODE_FAILED,

    /** [PlaybackCommand.Load]'s location could not be opened — the file moved, was deleted, or a
     *  SAF/security-scoped permission lapsed. */
    FILE_MISSING,

    /** The container/codec is not one the platform decoder supports at all. */
    UNSUPPORTED_FORMAT,

    /** A storage-layer read failure that is not clearly either of the above (a disk I/O error). */
    STORAGE_IO,

    /** A newer [PlaybackCommand.Load] superseded this one before it finished — not a real failure. */
    CANCELLED,
}

/**
 * Observable player state. Enough for Phase 5 to build synchronized playback on top of later
 * (`trackHash`, `positionMs`, `durationMs` are exactly what a future session-time scheduler would
 * need to read) **without** this phase adding any of that behaviour itself (brief §15).
 */
data class PlayerState(
    val contentHash: ContentHash? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    /** Playback rate if the platform exposes one; 1.0 when it doesn't or hasn't been changed. */
    val rate: Double = 1.0,
    val error: MusicFailure? = null,
) {
    /** True once [positionMs] has reached [durationMs] on a loaded, non-zero-length track — the
     *  signal a queue owner turns into [com.ridelink.core.player.LocalQueueAction.Next]. */
    val ended: Boolean get() = contentHash != null && durationMs > 0 && positionMs >= durationMs && !playing
}
