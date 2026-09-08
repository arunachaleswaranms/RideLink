package com.ridelink.app.sync

import com.ridelink.app.library.SharedLibraryCoordinator
import com.ridelink.app.music.MusicCoordinator
import com.ridelink.core.library.LocalTrackLocation
import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.LocalEntryId
import com.ridelink.core.player.PlayerState
import com.ridelink.data.library.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

// The production implementations of Phase 5's ports. Every one is a thin translation onto something
// that already exists — there is no new player, no new queue, no new cache and no new transfer
// machinery in this file, which is the point (brief §20/§21).

/**
 * [SyncPlayerPort] over the **one** [MusicCoordinator]: its `LocalQueue`, its one
 * `ExoPlayerMusicPlayer`, its one ADR-022 `MediaSession`.
 */
internal class MusicCoordinatorPlayerPort(
    private val music: MusicCoordinator,
) : SyncPlayerPort {
    override val playerState: StateFlow<PlayerState> get() = music.playerState

    override suspend fun prepare(
        content: SyncPlayableContent,
        positionMs: Long,
    ) = music.syncPrepare(content.contentHash, content.localEntryId, content.location, content.title, content.artist, positionMs)

    override suspend fun start() = music.syncStart()

    override suspend fun pause() = music.syncPause()

    override suspend fun seek(positionMs: Long) = music.syncSeek(positionMs)

    override suspend fun setRate(rate: Double) = music.syncSetRate(rate)

    override suspend fun stop() = music.syncStop()
}

/**
 * [SyncContentPort] over Phase 3's library and Phase 4's verified cache — the two, and only two,
 * provenances brief §19 admits.
 *
 * The verified cache is asked **first** and through [SharedLibraryCoordinator.cachedFile], which is
 * `TransferCacheRepository.open`: a row claiming verified with no file behind it is dropped rather
 * than trusted (Amendment A4). A `DownloadState` of `COMPLETE` is never consulted — brief §19 is
 * explicit that download state is not availability, and Phase 4's own truth is the committed cache
 * entry.
 */
internal class SharedLibraryContentPort(
    private val scope: CoroutineScope,
    private val library: LibraryRepository,
    private val sharedLibrary: SharedLibraryCoordinator,
    private val cacheEntryIds: MutableMap<String, LocalEntryId> = HashMap(),
) : SyncContentPort {
    @Suppress("ReturnCount") // one early-out per provenance brief §19 admits: verified cache, then library
    override suspend fun resolve(contentHash: ContentHash): SyncPlayableContent? {
        sharedLibrary.cachedFile(contentHash)?.let { file ->
            // One stable LocalEntryId per cached hash for this process: the player and the local
            // queue key on it, and minting a fresh one per resolve would make the same file look
            // like a different row on every drift tick.
            val entryId = cacheEntryIds.getOrPut(contentHash.value) { LocalEntryId(UUID.randomUUID().toString()) }
            return SyncPlayableContent(contentHash, entryId, LocalTrackLocation(file.toURI().toString()), null, null)
        }
        val entry = library.findByContentHash(contentHash) ?: return null
        return SyncPlayableContent(contentHash, entry.localEntryId, entry.location, entry.track.title, entry.track.artist)
    }

    override fun peerHasContent(contentHash: ContentHash): Boolean = sharedLibrary.peerHasContent(contentHash)

    override fun requestTransfer(contentHash: ContentHash) {
        // PROTOCOL §5 rule 4, through the **existing** Phase 4 queue. `requestDownload` already
        // refuses a hash already held and already de-duplicates against its own in-flight queue, so
        // calling it again on a later PLAY for the same track is harmless.
        val entry = sharedLibrary.remoteEntries.value.firstOrNull { it.contentHash == contentHash } ?: return
        sharedLibrary.requestDownload(entry)
    }

    /**
     * ADR-024 Amendment A1 Finding E: forwards Phase 4's own verified-availability notification.
     *
     * `SharedLibraryCoordinator` already owned the two facts that matter — `cachedHashes`, refreshed
     * only after `TransferCacheRepository.commit` succeeds, and `peerVerifiedHashes`, written only
     * on a `TRANSFER_RESULT { ok: true }` for a hash we ourselves served — so this adds a
     * notification, not a third source of truth, and certainly not a poll.
     */
    override fun observeAvailability(onAvailabilityChanged: () -> Unit) {
        sharedLibrary.onAvailabilityChanged = { scope.launch { onAvailabilityChanged() } }
    }
}

/**
 * The one place Phase 5 touches real time: waiting on the **monotonic** clock until a deadline.
 *
 * `delay` rather than a spin: a busy-wait would burn a core for up to two seconds of scheduling lead
 * on a phone in someone's pocket, and the scheduling error it would buy back is far below what the
 * decoder and two Bluetooth hops contribute anyway. The residual error is measured rather than
 * assumed — `SyncPlaybackDiagnostics.lastScheduleErrorUs` records `actual - deadline` for every
 * scheduled start, and it is a *software* figure that says nothing about audible alignment
 * (brief §23).
 */
internal class MonotonicDeadlineSleeper(
    private val monotonicNowUs: () -> Long,
) : SyncDeadlineSleeper {
    override suspend fun sleepUntil(localMonoUs: Long) {
        while (true) {
            val remainingUs = localMonoUs - monotonicNowUs()
            if (remainingUs <= 0) return
            val remainingMs = remainingUs / MICROS_PER_MS
            // Long waits sleep in coarse steps; the last stretch is stepped finely so a scheduler
            // that overshoots a single long delay cannot cost the whole margin.
            delay(if (remainingMs > COARSE_STEP_MS) COARSE_STEP_MS else remainingMs.coerceAtLeast(1))
        }
    }

    private companion object {
        const val MICROS_PER_MS = 1_000L
        const val COARSE_STEP_MS = 20L
    }
}

/** Bridges [MusicCoordinator]'s gate to the coordinator that owns synchronisation. */
internal class SyncPlaybackGateAdapter(
    private val scope: CoroutineScope,
    private val sync: SyncPlaybackCoordinator,
) : com.ridelink.app.music.SyncPlaybackGate {
    override fun interceptPlay(): Boolean =
        intercept {
            // A local `play` during a synchronised ride resumes *both* phones from the position the
            // authoritative timeline is at — never just this one.
            sync.resume()
        }

    override fun interceptPause(): Boolean = intercept { sync.pause() }

    override fun interceptSeek(positionMs: Long): Boolean = intercept { sync.seek(positionMs) }

    override fun interceptNext(): Boolean = intercept { sync.next() }

    override fun interceptPrevious(): Boolean = intercept { sync.previous() }

    override fun interceptTrackEnded(): Boolean =
        intercept {
            // Only the ADR-010 leader may decide what plays next. A follower deliberately does
            // nothing and waits for the leader's authoritative NEXT — that is not a stall, it is the
            // single serialisation point doing its job.
            if (sync.diagnostics.value.role == com.ridelink.core.playback.PlaybackRole.LEADER) sync.next()
        }

    private inline fun intercept(crossinline action: () -> Unit): Boolean {
        if (!sync.isSynchronizedModeActive()) return false
        scope.launch { action() }
        return true
    }
}
