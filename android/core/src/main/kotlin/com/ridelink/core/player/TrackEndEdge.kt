package com.ridelink.core.player

/**
 * Edge-detects "a track just finished" from a stream of [PlayerState] emissions that may repeat the
 * same finished state more than once.
 *
 * **Why this exists — a real bug found on the emulator, not a hypothetical.** ExoPlayer fires two
 * separate listener callbacks for one natural end of playback:
 * `onPlaybackStateChanged(STATE_ENDED)` and `onIsPlayingChanged(false)`, each of which calls the
 * [Player] binding's own `updateState` and therefore emits its own [PlayerState] to
 * [Player.setStateSink]'s sink. Both emissions satisfy [PlayerState.ended]. A coordinator that
 * dispatches [LocalQueueAction.Next] on *every* emission where `ended` (or a missing-file error) is
 * true — rather than only on the transition into it — advances twice per actual end: the first
 * [LocalQueueAction.Next] correctly stops a single-item queue (`currentId` becomes `null`), but the
 * second lands on [LocalQueue.reduce]'s "nothing selected" branch, whose own documented behaviour is
 * to *start the queue over from its first item* — turning one finished track into an infinite
 * play-then-restart loop. AVFoundation's completion-handler-vs-observed-state race on iOS is the
 * same shape, which is why this lives in `core`/[RideLinkCore] rather than in either platform's
 * player binding.
 *
 * [advancedNow] is level-independent: it only reports `true` on the transition from "not done" to
 * "done", never for a repeated emission that is still describing the same finished track.
 */
object TrackEndEdge {
    /**
     * @return `true` only when [current] first became finished (ended, or failed with a missing
     *   file) relative to [previous] — never for two emissions that both describe the same finish.
     */
    fun advancedNow(
        previous: PlayerState,
        current: PlayerState,
    ): Boolean = isDone(current) && !isDone(previous)

    private fun isDone(state: PlayerState): Boolean = state.ended || state.error == MusicFailure.FILE_MISSING
}
