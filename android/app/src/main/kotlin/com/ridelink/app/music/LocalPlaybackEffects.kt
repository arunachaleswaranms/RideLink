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
 * 2. **The playback ticket** (rounds 3 and 4): this effect's place in the local operation order, so
 *    an older local operation cannot overtake a newer one inside the same lifetime. Two sequences,
 *    because they answer two questions:
 *    - **selection** — *which track should be loaded?* Advanced by a load-and-play and by a stop. A
 *      newer selection or stop ends an older `Load` and its `Play` (Select A, then Clear or Select B:
 *      A never loads after either). A stop is ended **only** by a newer selection: a Clear's `Stop`
 *      is what removes the cleared track from the player, and a later Pause or Play press must not
 *      discard it (round 4).
 *    - **transport intent** — *should the player be running?* Advanced by a load-and-play, a stop, a
 *      resume and a pause, each recording whether it wants playback. A Pause or resume press acts only
 *      while it is still the newest intent. A load's trailing `Play` runs only if the newest intent
 *      **wants playback** — so Select A then Pause leaves A loaded but paused, and Select A then Play
 *      starts A once it has loaded rather than losing the Play (round 4). A resume pressed while its
 *      own selection's load is still in flight leaves the `Play` to that load, so the previous track
 *      never plays in between.
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
    private var intent = 0L
    private var intentWantsPlay = false

    /** The selection whose load-and-play is still in flight, if any. */
    private var loadPending: Long? = null

    /** A local effect's place in the operation order, captured when it is issued. */
    private class Ticket(
        val selection: Long,
        val intent: Long,
    )

    fun run(
        effects: List<LocalQueueEffect>,
        admission: LocalQueueEditAdmission,
    ): List<Job> =
        effects.map { effect ->
            when (effect) {
                is LocalQueueEffect.LoadAndPlay -> {
                    val ticket = issue(newSelection = true, wantsPlay = true, loads = true)
                    scope.launch { loadAndPlay(effect.localEntryId, admission, ticket) }
                }
                LocalQueueEffect.StopPlayback -> {
                    val ticket = issue(newSelection = true, wantsPlay = false)
                    launchIf(PlaybackCommand.Stop) { stillValid(admission) && selectionCurrent(ticket) }
                }
                LocalQueueEffect.ResumePlayback -> {
                    val ticket = issue(newSelection = false, wantsPlay = true)
                    launchIf(PlaybackCommand.Play) {
                        stillValid(admission) && intentCurrent(ticket) && selectionCurrent(ticket) && !loadInFlight(ticket)
                    }
                }
            }
        }

    /** A local Pause press: a newer transport intent than anything issued before it. */
    fun pause(admission: LocalQueueEditAdmission): Job {
        val ticket = issue(newSelection = false, wantsPlay = false)
        return launchIf(PlaybackCommand.Pause) { stillValid(admission) && intentCurrent(ticket) }
    }

    /** A local seek: a position on the current selection, dropped if the selection has moved on. */
    fun seek(
        positionMs: Long,
        admission: LocalQueueEditAdmission,
    ): Job {
        val ticket = synchronized(lock) { Ticket(selection, intent) }
        return launchIf(PlaybackCommand.Seek(positionMs)) { stillValid(admission) && selectionCurrent(ticket) }
    }

    private fun issue(
        newSelection: Boolean,
        wantsPlay: Boolean,
        loads: Boolean = false,
    ): Ticket =
        synchronized(lock) {
            if (newSelection) selection++
            intent++
            intentWantsPlay = wantsPlay
            loadPending = if (loads) selection else loadPending.takeUnless { newSelection }
            Ticket(selection, intent)
        }

    private fun selectionCurrent(ticket: Ticket) = synchronized(lock) { selection == ticket.selection }

    private fun intentCurrent(ticket: Ticket) = synchronized(lock) { intent == ticket.intent }

    /** The newest transport intent — whichever press issued it — wants playback. */
    private fun newestIntentWantsPlay() = synchronized(lock) { intentWantsPlay }

    private fun loadInFlight(ticket: Ticket) = synchronized(lock) { loadPending == ticket.selection }

    private fun loadSettled(ticket: Ticket) = synchronized(lock) { if (loadPending == ticket.selection) loadPending = null }

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
        try {
            val load = resolve(localEntryId)
            if (load == null || !stillValid(admission) || !selectionCurrent(ticket)) return
            player.execute(load)
            // No proof taken before `Load` authorises `Play`: `Load` suspends, and a newer selection,
            // stop or pause may have been issued meanwhile — or a newer Play, which this load carries.
            if (stillValid(admission) && selectionCurrent(ticket) && newestIntentWantsPlay()) {
                player.execute(PlaybackCommand.Play)
            }
        } finally {
            loadSettled(ticket)
        }
    }
}
