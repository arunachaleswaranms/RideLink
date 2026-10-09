package com.ridelink.app.music

import com.ridelink.core.model.LocalEntryId
import com.ridelink.core.player.LocalQueueEffect
import com.ridelink.core.player.PlaybackCommand
import com.ridelink.core.player.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs the player effects of **local** queue edits and transport presses, each under the
 * [LocalQueueEditAdmission] that admitted it (ADR-024 Amendment A15, PR #18 review round 2). Mirrors
 * `RideLinkPlatform.LocalPlaybackEffects`.
 *
 * The queue mutation happens synchronously at admission; the player effects run here in launched
 * coroutines, after a suspension. So the admission is re-proved immediately before **every** player
 * call — before `Load`, again before `Play` (a `Load` that was valid does not authorise the `Play`
 * after it), and before `Stop`, `Play`, `Pause` and `Seek`. A proof that fails drops the rest of that
 * effect: stale local work never touches the one player after synchronised mode took transport, and a
 * later return to local is a different lifetime, so it cannot revive it.
 *
 * Nothing synchronised passes through here — `MusicCoordinator.sync*` drive the player under the
 * synchronised session's own generation, ride and work authority.
 */
internal class LocalPlaybackEffects(
    private val scope: CoroutineScope,
    private val player: Player,
    /** The `Load` for an entry, or `null` if it no longer resolves; may suspend (a repository read). */
    private val resolve: suspend (LocalEntryId) -> PlaybackCommand.Load?,
    private val stillValid: (LocalQueueEditAdmission) -> Boolean,
) {
    fun run(
        effects: List<LocalQueueEffect>,
        admission: LocalQueueEditAdmission,
    ): List<Job> =
        effects.map { effect ->
            when (effect) {
                is LocalQueueEffect.LoadAndPlay -> scope.launch { loadAndPlay(effect.localEntryId, admission) }
                LocalQueueEffect.StopPlayback -> command(PlaybackCommand.Stop, admission)
                LocalQueueEffect.ResumePlayback -> command(PlaybackCommand.Play, admission)
            }
        }

    /** One local player command — Stop, Play, Pause, Seek — proved immediately before it runs. */
    fun command(
        command: PlaybackCommand,
        admission: LocalQueueEditAdmission,
    ): Job =
        scope.launch {
            if (!stillValid(admission)) return@launch
            player.execute(command)
        }

    private suspend fun loadAndPlay(
        localEntryId: LocalEntryId,
        admission: LocalQueueEditAdmission,
    ) {
        val load = resolve(localEntryId)
        if (load == null || !stillValid(admission)) return
        player.execute(load)
        // A proof taken before `Load` does not authorise `Play`: `Load` suspends.
        if (stillValid(admission)) player.execute(PlaybackCommand.Play)
    }
}
