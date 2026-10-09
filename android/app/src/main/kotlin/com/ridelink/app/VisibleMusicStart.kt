package com.ridelink.app

import com.ridelink.core.library.LibraryEntry
import com.ridelink.core.model.ContentHash
import java.io.File

/**
 * The first-play discipline for a local track started from the visible UI (ARCHITECTURE §6.4,
 * closure-audit Finding E), as one testable rule rather than a check copied into each `MainActivity`
 * entry point.
 *
 * [startThen] reads both preconditions — the Activity is foreground-visible, and the synchronised
 * transport owner does not hold the local queue (ADR-024 Amendment A15) — **immediately** before the
 * foreground-service start, and nothing suspends between that read, the start and the play. A read
 * taken before a suspension authorises nothing after it (PR #18 review round 3): the shared-music
 * "Play" looks a track up in the repository and then in the transfer cache, both suspending, and the
 * Activity can stop, or synchronised mode take transport, while either is in flight.
 *
 * This only avoids starting the service for a play that would not happen. `MusicCoordinator` remains
 * the final admission authority: `playNow`, `selectQueueItem` and `playExternalVerifiedCachedTrack`
 * refuse on their own while synchronised mode owns transport.
 */
internal class VisibleMusicStart(
    private val foregroundVisible: () -> Boolean,
    private val localQueueLocked: () -> Boolean,
    /** `RideForegroundService.startMusicFromVisibleUi`: false when the platform refused the start. */
    private val startForegroundService: () -> Boolean,
    private val onStartRefused: () -> Unit,
) {
    /** Whether a fresh local play may start from the UI right now. */
    fun permitted(): Boolean = foregroundVisible() && !localQueueLocked()

    /** Starts the foreground service and then [play], or neither. Never suspends. */
    fun startThen(play: () -> Unit): Boolean {
        val started = permitted() && startForegroundService().also { granted -> if (!granted) onStartRefused() }
        if (started) play()
        return started
    }

    /**
     * The shared-music "Play": an imported copy if this phone holds one, otherwise the verified cache
     * file. Both lookups suspend, so the preconditions are read again by [startThen] after them.
     */
    suspend fun playSharedTrack(
        hash: ContentHash,
        findLocal: suspend (ContentHash) -> LibraryEntry?,
        cachedFile: suspend (ContentHash) -> File?,
        playNow: (LibraryEntry) -> Unit,
        playCached: (File) -> Unit,
    ) {
        if (!permitted()) return
        val local = findLocal(hash)
        if (local != null) {
            startThen { playNow(local) }
        } else {
            cachedFile(hash)?.let { file -> startThen { playCached(file) } }
        }
    }
}
