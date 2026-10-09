package com.ridelink.app.music

import com.ridelink.core.model.LocalEntryId
import com.ridelink.core.player.LocalQueueEffect
import com.ridelink.core.player.PlaybackCommand
import com.ridelink.core.player.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs the player effects of **local** queue edits and transport presses (ADR-024 Amendment A15).
 * Mirrors `RideLinkPlatform.LocalPlaybackEffects`. Owned by the one `MusicCoordinator`; there is no
 * other local effect path.
 *
 * Every effect is issued synchronously, in the same step as the queue mutation or press that caused
 * it, and runs later in a launched coroutine. Two independent authorities are carried into it and
 * re-proved immediately before **every** player call, and after every suspension that precedes one:
 *
 * 1. **The ownership admission** (round 2): the local transport-ownership lifetime that admitted the
 *    edit. Synchronised mode taking transport — or a later return to local, which is a new lifetime —
 *    ends it.
 * 2. **The playback ticket** (round 3): this effect's place in the local operation order, so an older
 *    local operation cannot overtake a newer one inside the same lifetime. Two sequences, because they
 *    answer two questions:
 *    - **selection** — *which track should be loaded?* Advanced by a load-and-play and by a stop. A
 *      newer selection or stop ends an older `Load` and its `Play` (Select A, then Clear or Select B:
 *      A never loads after either).
 *    - **transport intent** — *should the player be running?* Advanced by a load-and-play, a stop, a
 *      resume and a pause. A newer intent ends an older `Play` (Select A, then Pause: A may still load,
 *      so the player holds the track the queue names, but A's `Play` cannot defeat the pause).
 *
 *    Add, move and removing a non-current entry have no player effect and advance neither, so they
 *    never invalidate work in flight. A seek is a position on the current selection: it advances
 *    nothing and is dropped only if the selection moved on.
 *
 * A failed proof drops the rest of that effect. Nothing is reconstructed from current queue state,
 * and no replacement ticket or admission is ever minted after a suspension.
 *
 * Nothing synchronised passes through here — `MusicCoordinator.sync*` drive the player under the
 * synchronised session's own generation, ride and work authority, and neither sequence moves for them.
 */
internal class LocalPlaybackEffects(
    private val scope: CoroutineScope,
    private val player: Player,
    /** The `Load` for an entry, or `null` if it no longer resolves; may suspend (a repository read). */
    private val resolve: suspend (LocalEntryId) -> PlaybackCommand.Load?,
    private val stillValid: (LocalQueueEditAdmission) -> Boolean,
) {
    private val lock = Any()
    private var selection = 0L
    private var transport = 0L

    /** A local effect's place in the operation order, captured when it is issued. */
    private class Ticket(
        val selection: Long,
        val transport: Long,
    )

    fun run(
        effects: List<LocalQueueEffect>,
        admission: LocalQueueEditAdmission,
    ): List<Job> =
        effects.map { effect ->
            when (effect) {
                is LocalQueueEffect.LoadAndPlay -> {
                    val ticket = issue(newSelection = true, newTransport = true)
                    scope.launch { loadAndPlay(effect.localEntryId, admission, ticket) }
                }
                LocalQueueEffect.StopPlayback -> {
                    val ticket = issue(newSelection = true, newTransport = true)
                    launchIf(PlaybackCommand.Stop) { stillValid(admission) && selectionCurrent(ticket) && transportCurrent(ticket) }
                }
                LocalQueueEffect.ResumePlayback -> {
                    val ticket = issue(newSelection = false, newTransport = true)
                    launchIf(PlaybackCommand.Play) { stillValid(admission) && transportCurrent(ticket) }
                }
            }
        }

    /** A local Pause press: a newer transport intent than anything issued before it. */
    fun pause(admission: LocalQueueEditAdmission): Job {
        val ticket = issue(newSelection = false, newTransport = true)
        return launchIf(PlaybackCommand.Pause) { stillValid(admission) && transportCurrent(ticket) }
    }

    /** A local seek: a position on the current selection, dropped if the selection has moved on. */
    fun seek(
        positionMs: Long,
        admission: LocalQueueEditAdmission,
    ): Job {
        val ticket = issue(newSelection = false, newTransport = false)
        return launchIf(PlaybackCommand.Seek(positionMs)) { stillValid(admission) && selectionCurrent(ticket) }
    }

    private fun issue(
        newSelection: Boolean,
        newTransport: Boolean,
    ): Ticket =
        synchronized(lock) {
            if (newSelection) selection++
            if (newTransport) transport++
            Ticket(selection, transport)
        }

    private fun selectionCurrent(ticket: Ticket) = synchronized(lock) { selection == ticket.selection }

    private fun transportCurrent(ticket: Ticket) = synchronized(lock) { transport == ticket.transport }

    private fun launchIf(
        command: PlaybackCommand,
        authorised: () -> Boolean,
    ): Job =
        scope.launch {
            if (authorised()) player.execute(command)
        }

    private suspend fun loadAndPlay(
        localEntryId: LocalEntryId,
        admission: LocalQueueEditAdmission,
        ticket: Ticket,
    ) {
        val load = resolve(localEntryId)
        if (load == null || !stillValid(admission) || !selectionCurrent(ticket)) return
        player.execute(load)
        // Neither proof taken before `Load` authorises `Play`: `Load` suspends, and a newer pause,
        // resume, stop or selection may have been issued meanwhile.
        if (stillValid(admission) && selectionCurrent(ticket) && transportCurrent(ticket)) {
            player.execute(PlaybackCommand.Play)
        }
    }
}
