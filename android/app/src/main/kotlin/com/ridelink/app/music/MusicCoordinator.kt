package com.ridelink.app.music

import com.ridelink.core.library.LibraryEntry
import com.ridelink.core.library.LibraryQuery
import com.ridelink.core.library.LibrarySort
import com.ridelink.core.library.LocalTrackLocation
import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.LocalEntryId
import com.ridelink.core.player.LocalQueue
import com.ridelink.core.player.LocalQueueAction
import com.ridelink.core.player.LocalQueueEffect
import com.ridelink.core.player.LocalQueueItem
import com.ridelink.core.player.LocalQueueState
import com.ridelink.core.player.MusicFailure
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * The single owner of local-music state (CLAUDE.md rule 8, applied to the music plane the way
 * [com.ridelink.app.session.SessionCoordinator] applies it to the control plane). No screen holds
 * library, queue or playback state of its own.
 *
 * The one thing this class does that neither [LocalQueue] nor [Player] can do alone: turn a
 * [LocalQueueEffect.LoadAndPlay]'s [LocalEntryId] into an actual [PlaybackCommand.Load] by resolving
 * a location through [LibraryRepository] — the lookup ADR-014's module boundary requires happen here,
 * in `app`, since `audio` (where [Player]'s real binding lives) must never depend on `data` (where
 * [LibraryRepository] lives).
 *
 * Player failure never reaches [com.ridelink.app.session.SessionCoordinator] and never touches the
 * control session — this phase's brief §30's "player error must not affect control/TLS session" is
 * structural here: this class has no reference to it at all.
 *
 * One queue and one player deliberately meet here; the Phase 6 port adds adapters, not ownership.
 */
@Suppress("TooManyFunctions")
class MusicCoordinator(
    private val repository: LibraryRepository,
    private val indexer: LibraryIndexer,
    private val player: Player,
    private val scope: CoroutineScope,
    private val monotonicNowUs: () -> Long,
    private val nextQueueItemId: () -> String,
) : MusicCoexistencePort {
    /** The one Phase 6 owner receives player state and playback intent through this narrow seam. */
    override var coexistenceEvents: CoexistenceEventSink? = null

    private val _query = MutableStateFlow(LibraryQuery())
    val query: StateFlow<LibraryQuery> = _query.asStateFlow()

    /**
     * The library as the Library screen shows it — filtered by [query] and sorted. Only that screen
     * should collect it: with 3,460 tracks, every collector keeps a full re-read and re-sort alive
     * on each library change (Phase 9A.5 §5/§6). Screens that need a count, the hashes on this
     * phone or the current track use [libraryCount], [localContentHashes] and [nowPlayingEntry],
     * none of which depends on what the user typed into search.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val libraryEntries: StateFlow<List<LibraryEntry>> =
        _query
            .flatMapLatest { repository.observe(it) }
            .stateIn(scope, SharingStarted.WhileSubscribed(), emptyList())

    /** How many tracks the library holds, whatever the search says. */
    val libraryCount: StateFlow<Int> = repository.observeCount().stateIn(scope, SharingStarted.WhileSubscribed(), 0)

    /** Every content hash a usable local row holds — "on this phone" for the shared-music surfaces. */
    val localContentHashes: StateFlow<Set<String>> =
        repository.observeIndexedContentHashes().stateIn(scope, SharingStarted.WhileSubscribed(), emptySet())

    /** The one owner of imports and their progress. */
    val imports = LibraryImports(indexer, scope)

    private val _queueState = MutableStateFlow(LocalQueueState())
    val queueState: StateFlow<LocalQueueState> = _queueState.asStateFlow()

    /**
     * The library row for the current queue item, looked up by [LocalEntryId] in the repository.
     * It used to be found by scanning [libraryEntries], which is **search-filtered**: typing in the
     * search box made Now Playing say "Shared track" for whatever was playing, and every screen that
     * wanted the title kept the whole library subscribed. Entries in the queue are few; the lookup is
     * one indexed query per change of current item.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val nowPlayingEntry: StateFlow<LibraryEntry?> =
        _queueState
            .map { it.currentItem?.localEntryId }
            .distinctUntilChanged()
            .mapLatest { id -> id?.let { repository.findByLocalEntryId(it) } }
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)

    /** Library rows for the given queue entries, for Up Next — looked up, never scanned for. */
    suspend fun entriesFor(ids: Collection<LocalEntryId>): Map<LocalEntryId, LibraryEntry> =
        ids.toSet().mapNotNull { id -> repository.findByLocalEntryId(id)?.let { id to it } }.toMap()

    /** A usable local row holding [hash], if any — the shared library's "play it here" lookup. */
    suspend fun findLocalByContentHash(hash: ContentHash): LibraryEntry? = repository.findByContentHash(hash)

    /** Title and artist for a queue entry that is a verified Phase 4 cache file, not a library row. */
    fun externalTitleFor(id: LocalEntryId): Pair<String?, String?>? = externalCacheSources[id]?.let { it.title to it.artist }

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()
    override val coexistencePlayerState: StateFlow<PlayerState> get() = playerState

    private val _baseVolumePermille = MutableStateFlow(FULL_VOLUME_PERMILLE)
    val baseVolumePermille: StateFlow<Int> = _baseVolumePermille.asStateFlow()
    override val coexistenceBaseVolumePermille: StateFlow<Int> get() = baseVolumePermille

    /**
     * Set when the platform refused to start the ride foreground service for a music-play attempt
     * (this phase's closure-audit hardening pass, Finding E — mirrors
     * [com.ridelink.app.session.SessionCoordinator]'s own `lastIntercomRefusal` for the intercom's
     * equivalent start gate). `null` means no refusal is currently outstanding; a successful
     * [play]/[playNow] always clears it, since either only ever runs after the caller confirmed the
     * foreground service actually started.
     */
    private val _lastMusicStartRefusal = MutableStateFlow<MusicFailure?>(null)
    val lastMusicStartRefusal: StateFlow<MusicFailure?> = _lastMusicStartRefusal.asStateFlow()

    /** Called by the composition root when [com.ridelink.app.service.RideForegroundService.startMusicFromVisibleUi]
     *  returns false — playback must not proceed as though background ownership were established.
     *  Never retried silently from here or anywhere else; the user must bring the app to the front. */
    fun onForegroundServiceStartFailed() {
        _lastMusicStartRefusal.value = MusicFailure.FOREGROUND_SERVICE_START_FAILED
    }

    /** True whenever the ride foreground service needs the `mediaPlayback` type (this phase's
     *  brief §16) — playing, or paused mid-track, but not once stopped/idle. */
    val isMusicActive: StateFlow<Boolean> =
        combine(_playerState, _queueState) { player, queue -> player.playing || (queue.currentItem != null && player.localEntryId != null) }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    /**
     * Non-null once a Phase 5 synchronised session exists. Set once by the composition root
     * (`AppContainer`), never by a screen. While it reports ownership of an action, this coordinator
     * does not touch the player — the leader-ordered command that comes back over the control plane
     * does, through the `sync*` methods below.
     *
     * The cycle between the two coordinators is deliberate and one-directional per call:
     * `MusicCoordinator` asks the gate, the gate never calls back into these gated methods (it uses
     * `syncSelect`/`syncLoad`/`syncStart`/... which bypass it), so there is no re-entrancy.
     */
    @Volatile
    var syncGate: SyncPlaybackGate? = null

    /** Closure-audit Finding G: verified Phase-4 cache-only tracks playable through the *existing*
     *  queue/player, held in [ExternalCacheSources] rather than inline — see that class for why
     *  Amendment A4 (Finding U) made this a synchronous read rather than a shared flow. */
    private val externalCacheSources = ExternalCacheSources()

    /**
     * Closure-audit Finding I: the [ContentHash] the player currently has loaded from
     * [externalCacheSources], if any — so a caller committing a *new* Phase-4 cache entry
     * (`TransferCacheRepository.commit`) can include it in that call's `locked` set and never evict
     * the file this coordinator's own player has open. `null` whenever nothing playing right now is
     * a cache-only track (including "nothing is playing" and "a Phase 3 imported track is playing").
     *
     * Amendment A4 Finding U: deliberately a function over live state, **not** a `StateFlow`. As a
     * `stateIn(…, WhileSubscribed(), null)` flow it had no collector anywhere in the app — its only
     * consumer reads it directly — so it always reported its initial `null` and left this whole
     * protection inert. [ExternalCacheSources]' KDoc records that in full.
     */
    fun activeExternalCacheHash(): ContentHash? = externalCacheSources.activeHash(_queueState.value.currentItem?.localEntryId)

    init {
        scope.launch {
            player.setStateSink { state ->
                val previous = _playerState.value
                _playerState.value = state
                coexistenceEvents?.onMusicChanged(state)
                // A track ending or its file going missing both mean "move on" — the queue owner's
                // job (LocalQueue.kt's own KDoc: this is deliberately not a queue-internal concept).
                // Edge-triggered via TrackEndEdge, not level-triggered on `state` alone: a real bug
                // found on the emulator, ExoPlayer emits *two* states for one natural end
                // (`STATE_ENDED` and `onIsPlayingChanged(false)`), and dispatching Next on both
                // landed the second Next on the already-advanced (now empty-selection) queue, whose
                // own "nothing selected" semantics restart the first item — an infinite play/restart
                // loop. See TrackEndEdge's KDoc for the full account.
                if (TrackEndEdge.advancedNow(previous, state)) {
                    // In a synchronised session only the ADR-010 leader decides what plays next, and
                    // it does so with an authoritative NEXT both phones schedule. Advancing the local
                    // queue here as well would put this phone a track ahead of the other.
                    if (syncGate?.interceptTrackEnded() != true) dispatch(LocalQueueAction.Next)
                }
            }
        }
        // ADR-005's background pass, actually wired to run (this phase's closure-audit hardening
        // pass — previously this method existed but nothing ever called it). Kicked off once at
        // composition time so rows left unhashed by a previous session's interrupted pass resume;
        // [LibraryImports] runs it again after every import so new rows do not wait for a relaunch.
        imports.completeContentHashingInBackground()
    }

    fun setSearchText(text: String) {
        _query.value = _query.value.copy(searchText = text)
    }

    fun setSort(sort: LibrarySort) {
        _query.value = _query.value.copy(sort = sort)
    }

    /**
     * Phase 9A.5, PR #18 review: every user edit of the local queue — [addToQueue], [playNow],
     * [playExternalVerifiedCachedTrack], [removeFromQueue], [moveInQueue], [clearQueue],
     * [selectQueueItem] — is admitted through [LocalQueueEdits], which refuses it while synchronised
     * transport owns playback ([SyncPlaybackGate.localQueueLocked], ADR-024 Amendment A14). Each
     * returns whether it was admitted. A refusal changes nothing: no queue mutation, no `Load`, no
     * `Play`, no `Stop`. Additions are refused too, because the synchronised path replaces the local
     * queue on every synchronised selection ([syncSelect]) and clears it on stop
     * ([syncClearSelection]) — an entry "staged" during a synchronised ride would silently vanish.
     */
    fun addToQueue(entry: LibraryEntry): Boolean = edit(LocalQueueAction.Add(newItem(entry)))

    /** Whether local queue edits are refused right now — for **rendering** only (which controls to
     *  offer). Every edit re-asks at the moment it is admitted; this answer authorises nothing. */
    fun isLocalQueueLocked(): Boolean = syncGate?.localQueueLocked() == true

    /** ADR-024 Amendment A15: every local player effect runs under the admission that caused it. */
    private val localEffects =
        LocalPlaybackEffects(
            scope = scope,
            player = player,
            resolve = ::resolveLoad,
            stillValid = { admission -> LocalQueueEdits.stillValid(syncGate, admission) },
        )

    /** Adds [entry] to the queue and starts playing it immediately — the library screen's "tap a
     *  track" affordance, as one atomic queue operation rather than an add followed by a
     *  UI-observed "select the item I just added" that would race a second rapid tap. */
    fun playNow(entry: LibraryEntry): Boolean {
        val item = newItem(entry)
        val outcome =
            LocalQueueEdits.reduce(
                _queueState.value,
                listOf(LocalQueueAction.Add(item), LocalQueueAction.Select(item.id)),
                syncGate,
            )
        if (outcome == null) return false
        _lastMusicStartRefusal.value = null
        coexistenceEvents?.onPlaybackIntent(playing = true)
        apply(outcome)
        return true
    }

    private fun newItem(entry: LibraryEntry): LocalQueueItem =
        LocalQueueItem(id = nextQueueItemId(), localEntryId = entry.localEntryId, insertedAtMonoUs = monotonicNowUs())

    /**
     * Closure-audit Finding G: plays a verified Phase-4 cache-only file — one that exists only as
     * [com.ridelink.data.transfer.TransferCacheRepository]'s committed, whole-file-SHA-256-verified
     * bytes, never imported into the Phase 3 library — through the *existing* one player/one queue,
     * exactly like [playNow] does for an imported [LibraryEntry]. brief §24: this is local-only
     * playback on *this* device; no peer command, no synchronized playback, no second player.
     */
    fun playExternalVerifiedCachedTrack(
        contentHash: ContentHash,
        file: File,
        title: String?,
        artist: String?,
    ): Boolean {
        val entryId = LocalEntryId(UUID.randomUUID().toString())
        val item = LocalQueueItem(id = nextQueueItemId(), localEntryId = entryId, insertedAtMonoUs = monotonicNowUs())
        val outcome =
            LocalQueueEdits.reduce(
                _queueState.value,
                listOf(LocalQueueAction.Add(item), LocalQueueAction.Select(item.id)),
                syncGate,
            )
        if (outcome == null) return false
        _lastMusicStartRefusal.value = null
        coexistenceEvents?.onPlaybackIntent(playing = true)
        // Registered before the effects run: `loadAndPlay` resolves the source when it executes.
        externalCacheSources.register(entryId, ExternalCacheSource(contentHash, LocalTrackLocation(file.toURI().toString()), title, artist))
        apply(outcome)
        return true
    }

    fun removeFromQueue(id: String): Boolean = edit(LocalQueueAction.Remove(id))

    fun moveInQueue(
        id: String,
        toIndex: Int,
    ): Boolean = edit(LocalQueueAction.Move(id, toIndex))

    fun clearQueue(): Boolean = edit(LocalQueueAction.Clear)

    fun next() {
        if (syncGate?.interceptNext() == true) return
        dispatch(LocalQueueAction.Next)
    }

    fun previous() {
        if (syncGate?.interceptPrevious() == true) return
        dispatch(LocalQueueAction.Previous)
    }

    fun selectQueueItem(id: String): Boolean = edit(LocalQueueAction.Select(id))

    /**
     * Play, from the app or the lock screen. A synchronised session owns it first (ADR-024 A14, via
     * [syncGate]); otherwise the local queue decides: with tracks queued and nothing selected it
     * starts the first one, and otherwise resumes the player (Phase 9A.5 §11, [LocalQueueAction.Play]).
     */
    fun play() {
        _lastMusicStartRefusal.value = null
        coexistenceEvents?.onPlaybackIntent(playing = true)
        if (syncGate?.interceptPlay() == true) return
        dispatch(LocalQueueAction.Play)
    }

    fun pause() {
        coexistenceEvents?.onPlaybackIntent(playing = false)
        if (syncGate?.interceptPause() == true) return
        val admission = LocalQueueEdits.admit(syncGate) ?: return
        localEffects.pause(admission)
    }

    fun seek(positionMs: Long) {
        if (syncGate?.interceptSeek(positionMs) == true) return
        val admission = LocalQueueEdits.admit(syncGate) ?: return
        localEffects.seek(positionMs, admission)
    }

    // --- Phase 5's own entry points ---------------------------------------------------------
    //
    // These bypass [syncGate] by construction: they are what the gate's owner calls once the ADR-010
    // leader's authoritative command is due, so routing them back through the gate would be an
    // immediate loop. They drive the same one player and the same one queue as everything above —
    // there is no second player, no second queue and no second MediaSession in Phase 5 (brief §21).

    // **ADR-024 Amendment A4: one externally visible effect per entry point.** These were two
    // functions — a `syncPrepare` that materialised, loaded and then seeked, and a `syncStop` that
    // stopped the player and then cleared the local queue. Each composed several effects *below*
    // [SyncPlayerPort], out of reach of any ownership proof `SyncPlaybackCoordinator` could take.
    // Sequencing moved to `SyncPlaybackCoordinator.runOwnedSteps`, which re-proves ownership before
    // every step. See `SyncPlayerPort`'s own note on why this platform was not observably defective
    // and is mirrored anyway.

    /**
     * Brief §26's materialisation point: the track that is actually current becomes the local
     * queue's one selected entry, so `NowPlaying`/`MediaSession` metadata and the Phase 3 UI
     * describe what is loaded. The shared queue itself is displayed from
     * `SyncPlaybackCoordinator.queueState`; it is deliberately not copied wholesale into
     * `LocalQueue`, because two queues that could disagree about an index is exactly the bug that
     * would produce.
     */
    fun syncSelect(
        contentHash: ContentHash,
        localEntryId: LocalEntryId,
        location: LocalTrackLocation,
        title: String?,
        artist: String?,
    ) {
        _lastMusicStartRefusal.value = null
        externalCacheSources.register(localEntryId, ExternalCacheSource(contentHash, location, title, artist))
        val item = LocalQueueItem(id = nextQueueItemId(), localEntryId = localEntryId, insertedAtMonoUs = monotonicNowUs())
        _queueState.value = LocalQueueState(items = listOf(item), currentId = item.id)
    }

    /** ARCHITECTURE §7.2's pre-roll, first half: hand the decoder the file. Never starts. */
    suspend fun syncLoad(
        localEntryId: LocalEntryId,
        location: LocalTrackLocation,
        title: String?,
        artist: String?,
    ) {
        player.execute(PlaybackCommand.Load(localEntryId, location, title, artist))
    }

    /** The tail of what used to be inside [syncStop]. */
    fun syncClearSelection() {
        _queueState.value = LocalQueueState()
    }

    suspend fun syncStart() {
        coexistenceEvents?.onPlaybackIntent(playing = true)
        player.execute(PlaybackCommand.Play)
    }

    suspend fun syncPause() {
        coexistenceEvents?.onPlaybackIntent(playing = false)
        player.execute(PlaybackCommand.Pause)
    }

    suspend fun syncSeek(positionMs: Long) {
        player.execute(PlaybackCommand.Seek(positionMs))
    }

    /** ADR-004's rate-nudge tier. Always exactly 1.0 when correction ends (brief §38). */
    suspend fun syncSetRate(rate: Double) {
        player.execute(PlaybackCommand.SetRate(rate))
    }

    suspend fun syncStop() {
        coexistenceEvents?.onPlaybackIntent(playing = false)
        player.execute(PlaybackCommand.Stop)
    }

    fun setBaseVolumePermille(volumePermille: Int) {
        require(volumePermille in 0..FULL_VOLUME_PERMILLE) { "base volume must be 0...1000" }
        _baseVolumePermille.value = volumePermille
        coexistenceEvents?.onBaseVolumeChanged(volumePermille)
    }

    override suspend fun beginCoexistenceLifetime(generation: Long) {
        player.beginCoexistenceLifetime(generation)
    }

    override suspend fun applyCoexistenceGain(
        generation: Long,
        volumePermille: Int,
    ): Boolean = player.setCoexistenceGain(generation, volumePermille.toDouble() / FULL_VOLUME_PERMILLE)

    override suspend fun pauseForVoice(
        generation: Long,
        trackToken: String,
    ): Boolean = player.pauseForVoice(generation, trackToken)

    override suspend fun resumeAfterVoice(
        generation: Long,
        trackToken: String,
    ): Boolean = player.resumeAfterVoice(generation, trackToken)

    /** A user's edit of the local queue, admitted or refused as one step — see [LocalQueueEdits]. */
    private fun edit(action: LocalQueueAction): Boolean {
        val outcome = LocalQueueEdits.reduce(_queueState.value, listOf(action), syncGate) ?: return false
        apply(outcome)
        return true
    }

    /**
     * Local transport and track-end actions, after [syncGate]'s intercept said "local". They are fresh
     * local presses, so they are admitted too, and their player effects carry that admission exactly
     * as an edit's do (ADR-024 Amendment A15): a press the intercept saw as local, whose effect runs
     * after synchronised mode took transport, must not reach the player either.
     */
    private fun dispatch(action: LocalQueueAction) {
        val admission = LocalQueueEdits.admit(syncGate) ?: return
        apply(AdmittedEdit(LocalQueue.reduce(_queueState.value, action), admission))
    }

    /**
     * Queue state first, synchronously, under a valid admission; player effects after, each re-proving
     * the same admission. If an effect is later dropped the local queue keeps the admitted state — that
     * is what the user asked for under local ownership, and the synchronised path replaces the local
     * queue wholesale at its next selection (`syncSelect`) anyway. No rollback crosses an ownership
     * boundary (ADR-024 Amendment A15).
     */
    private fun apply(edit: AdmittedEdit) {
        _queueState.value = edit.state
        localEffects.run(edit.effects, edit.admission)
    }

    private suspend fun resolveLoad(localEntryId: LocalEntryId): PlaybackCommand.Load? {
        val external = externalCacheSources[localEntryId]
        if (external != null) return PlaybackCommand.Load(localEntryId, external.location, external.title, external.artist)
        return repository.findByLocalEntryId(localEntryId)?.let { entry ->
            PlaybackCommand.Load(localEntryId, entry.location, entry.track.title, entry.track.artist)
        }
    }

    private companion object {
        const val FULL_VOLUME_PERMILLE = 1_000
    }
}
