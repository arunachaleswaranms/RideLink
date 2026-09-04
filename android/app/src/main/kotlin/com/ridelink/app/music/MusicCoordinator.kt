package com.ridelink.app.music

import com.ridelink.core.library.LibraryEntry
import com.ridelink.core.library.LibraryQuery
import com.ridelink.core.library.LibrarySort
import com.ridelink.core.model.QuickId
import com.ridelink.core.player.LocalQueue
import com.ridelink.core.player.LocalQueueAction
import com.ridelink.core.player.LocalQueueEffect
import com.ridelink.core.player.LocalQueueItem
import com.ridelink.core.player.LocalQueueState
import com.ridelink.core.player.PlaybackCommand
import com.ridelink.core.player.Player
import com.ridelink.core.player.PlayerState
import com.ridelink.core.player.TrackEndEdge
import com.ridelink.data.library.LibraryIndexer
import com.ridelink.data.library.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import android.net.Uri as PlatformUri

/**
 * The single owner of local-music state (CLAUDE.md rule 8, applied to the music plane the way
 * [com.ridelink.app.session.SessionCoordinator] applies it to the control plane). No screen holds
 * library, queue or playback state of its own.
 *
 * The one thing this class does that neither [LocalQueue] nor [Player] can do alone: turn a
 * [LocalQueueEffect.LoadAndPlay]'s [QuickId] into an actual [PlaybackCommand.Load] by resolving a
 * location through [LibraryRepository] — the lookup ADR-014's module boundary requires happen here,
 * in `app`, since `audio` (where [Player]'s real binding lives) must never depend on `data` (where
 * [LibraryRepository] lives).
 *
 * Player failure never reaches [com.ridelink.app.session.SessionCoordinator] and never touches the
 * control session — this phase's brief §30's "player error must not affect control/TLS session" is
 * structural here: this class has no reference to it at all.
 */
class MusicCoordinator(
    private val repository: LibraryRepository,
    private val indexer: LibraryIndexer,
    private val player: Player,
    private val scope: CoroutineScope,
    private val monotonicNowUs: () -> Long,
    private val nextQueueItemId: () -> String,
) {
    private val _query = MutableStateFlow(LibraryQuery())
    val query: StateFlow<LibraryQuery> = _query.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val libraryEntries: StateFlow<List<LibraryEntry>> =
        _query
            .flatMapLatest { repository.observe(it) }
            .stateIn(scope, SharingStarted.WhileSubscribed(), emptyList())

    private val _queueState = MutableStateFlow(LocalQueueState())
    val queueState: StateFlow<LocalQueueState> = _queueState.asStateFlow()

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    /** True whenever the ride foreground service needs the `mediaPlayback` type (this phase's
     *  brief §16) — playing, or paused mid-track, but not once stopped/idle. */
    val isMusicActive: StateFlow<Boolean> =
        combine(_playerState, _queueState) { player, queue -> player.playing || (queue.currentItem != null && player.quickId != null) }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    init {
        scope.launch {
            player.setStateSink { state ->
                val previous = _playerState.value
                _playerState.value = state
                // A track ending or its file going missing both mean "move on" — the queue owner's
                // job (LocalQueue.kt's own KDoc: this is deliberately not a queue-internal concept).
                // Edge-triggered via TrackEndEdge, not level-triggered on `state` alone: a real bug
                // found on the emulator, ExoPlayer emits *two* states for one natural end
                // (`STATE_ENDED` and `onIsPlayingChanged(false)`), and dispatching Next on both
                // landed the second Next on the already-advanced (now empty-selection) queue, whose
                // own "nothing selected" semantics restart the first item — an infinite play/restart
                // loop. See TrackEndEdge's KDoc for the full account.
                if (TrackEndEdge.advancedNow(previous, state)) {
                    dispatch(LocalQueueAction.Next)
                }
            }
        }
    }

    fun setSearchText(text: String) {
        _query.value = _query.value.copy(searchText = text)
    }

    fun setSort(sort: LibrarySort) {
        _query.value = _query.value.copy(sort = sort)
    }

    fun addToQueue(entry: LibraryEntry) {
        dispatch(LocalQueueAction.Add(newItem(entry)))
    }

    /** Adds [entry] to the queue and starts playing it immediately — the library screen's "tap a
     *  track" affordance, as one atomic queue operation rather than an add followed by a
     *  UI-observed "select the item I just added" that would race a second rapid tap. */
    fun playNow(entry: LibraryEntry) {
        val item = newItem(entry)
        dispatch(LocalQueueAction.Add(item))
        dispatch(LocalQueueAction.Select(item.id))
    }

    private fun newItem(entry: LibraryEntry): LocalQueueItem =
        LocalQueueItem(id = nextQueueItemId(), quickId = entry.track.quickId, insertedAtMonoUs = monotonicNowUs())

    fun removeFromQueue(id: String) = dispatch(LocalQueueAction.Remove(id))

    fun moveInQueue(
        id: String,
        toIndex: Int,
    ) = dispatch(LocalQueueAction.Move(id, toIndex))

    fun clearQueue() = dispatch(LocalQueueAction.Clear)

    fun next() = dispatch(LocalQueueAction.Next)

    fun previous() = dispatch(LocalQueueAction.Previous)

    fun selectQueueItem(id: String) = dispatch(LocalQueueAction.Select(id))

    fun play() = scope.launch { player.execute(PlaybackCommand.Play) }

    fun pause() = scope.launch { player.execute(PlaybackCommand.Pause) }

    fun seek(positionMs: Long) = scope.launch { player.execute(PlaybackCommand.Seek(positionMs)) }

    fun importTree(treeUri: PlatformUri) = scope.launch { indexer.importTree(treeUri) }

    fun importFiles(uris: List<PlatformUri>) = scope.launch { indexer.importFiles(uris) }

    fun rescanMediaStore() = scope.launch { indexer.rescanMediaStore() }

    /** Fills in the authoritative hash for every row still missing one — the ADR-005 background
     *  pass, kicked off once at composition time and safe to call again (it only ever touches rows
     *  still missing a hash). */
    fun completeContentHashingInBackground() {
        scope.launch {
            val missing = libraryEntries.value.filter { it.track.contentHash == null }
            if (missing.isNotEmpty()) indexer.completeContentHashing(missing)
        }
    }

    private fun dispatch(action: LocalQueueAction) {
        val outcome = LocalQueue.reduce(_queueState.value, action)
        _queueState.value = outcome.state
        outcome.effects.forEach { effect ->
            when (effect) {
                is LocalQueueEffect.LoadAndPlay -> scope.launch { loadAndPlay(effect.quickId) }
                LocalQueueEffect.StopPlayback -> scope.launch { player.execute(PlaybackCommand.Stop) }
            }
        }
    }

    private suspend fun loadAndPlay(quickId: QuickId) {
        val entry = repository.findByQuickId(quickId) ?: return
        player.execute(PlaybackCommand.Load(quickId, entry.location))
        player.execute(PlaybackCommand.Play)
    }
}
