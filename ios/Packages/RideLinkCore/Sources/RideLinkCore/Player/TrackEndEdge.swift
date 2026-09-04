import Foundation

/// Edge-detects "a track just finished" from a stream of `PlayerState` emissions that may repeat
/// the same finished state more than once.
///
/// **Why this exists — a real bug found on the Android emulator, not a hypothetical.** ExoPlayer
/// fires two separate listener callbacks for one natural end of playback —
/// `onPlaybackStateChanged(STATE_ENDED)` and `onIsPlayingChanged(false)` — each of which emits its
/// own `PlayerState`, and both satisfy `PlayerState.ended`. A coordinator that advances the queue on
/// *every* emission where `ended` (or a missing-file error) is true, rather than only on the
/// transition into it, advances twice per actual end: the first advance correctly stops a
/// single-item queue (`currentId` becomes `nil`), but the second lands on `LocalQueue`'s "nothing
/// selected" branch, whose own documented behaviour is to *start the queue over from its first
/// item* — turning one finished track into an infinite play-then-restart loop.
/// `AVAudioPlayerNode`'s completion-handler-vs-observed-state race is the same shape, which is why
/// this lives in `RideLinkCore` rather than in either platform's player binding — mirrors
/// `core.player.TrackEndEdge` on Android exactly.
public enum TrackEndEdge {
    /// `true` only when `current` first became finished (ended, or failed with a missing file)
    /// relative to `previous` — never for two emissions that both describe the same finish.
    public static func advancedNow(previous: PlayerState, current: PlayerState) -> Bool {
        isDone(current) && !isDone(previous)
    }

    private static func isDone(_ state: PlayerState) -> Bool {
        state.ended || state.error == .fileMissing
    }
}
