package com.ridelink.app.sync

import com.ridelink.core.library.LocalTrackLocation
import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.LocalEntryId
import com.ridelink.core.playback.PlaybackMessage
import com.ridelink.core.playback.QueueMessage
import com.ridelink.core.player.PlayerState
import com.ridelink.core.sync.SessionClockEstimate
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.control.ControlSessionManager
import com.ridelink.network.playback.PlaybackRelay
import com.ridelink.network.playback.PlaybackSink
import com.ridelink.network.playback.QueueSink
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

// The Phase 5 coordinator-level test seam, mirroring `library.TransferPorts`' shape exactly and for
// the same reason (ADR-023 Amendment A3): [SyncPlaybackCoordinator] depends on concrete production
// classes in modules below `app` that have no reason to know a test double exists, so its **exact**
// call surface on each is declared here as a narrow interface with a zero-behaviour-change adapter.
//
// A deterministic test builds fakes implementing these directly — a controllable
// [SyncDeadlineSleeper], a scripted [SyncContentPort], mutable session state behind
// [SyncSessionPort] — with no TLS, no real player and no wall-clock timing anywhere.

/** One thing this device can actually play, already resolved to an openable location. */
data class SyncPlayableContent(
    /** ADR-005: the authoritative identity. Never a filename, never a `quick_id`. */
    val contentHash: ContentHash,
    /**
     * The local row identity the one existing player/queue keys on. For a Phase 3 library track this
     * is the library row's id; for a Phase 4 verified-cache-only track it is the opaque token
     * `MusicCoordinator` mints for exactly this purpose (see `ExternalCacheSources`). Never on the
     * wire (ADR-005 Amendment A1).
     */
    val localEntryId: LocalEntryId,
    val location: LocalTrackLocation,
    val title: String?,
    val artist: String?,
)

/**
 * Resolving a `content_hash` to something playable **on this device**, and what is known about the
 * peer's copy.
 *
 * [resolve] returning null is the whole of the local half of this phase's brief §19 gate: it is
 * null unless the hash names a Phase 3 library row or a Phase 4 **verified, committed** cache entry
 * — never merely a download that reported `COMPLETE`, because Phase 4's own truth for that is
 * `TransferCacheRepository.isVerifiedCached`.
 */
interface SyncContentPort {
    suspend fun resolve(contentHash: ContentHash): SyncPlayableContent?

    /**
     * Whether the connected peer is known to hold this content — the peer half of the brief §19
     * gate. Session-scoped and cleared on every session boundary, exactly like
     * `SharedLibraryCoordinator.remoteEntries`.
     */
    fun peerHasContent(contentHash: ContentHash): Boolean

    /**
     * PROTOCOL §5 rule 4: a `PLAY` for a track this device lacks must not start, and must request
     * the transfer. This forwards to the **existing** Phase 4 machinery
     * (`SharedLibraryCoordinator.requestDownload`) — Phase 5 owns no transfer logic and no third
     * cache (brief §20).
     */
    fun requestTransfer(contentHash: ContentHash)

    /**
     * Installs the one observer notified whenever **verified** availability changes — locally (a
     * Phase 4 transfer committed) or on the peer (it reported verifying a transfer we served,
     * ADR-024 §7). ADR-024 Amendment A1 Finding E: this is the seam that lets one press of Play
     * survive a transfer, and it is deliberately a notification rather than a poll.
     *
     * It carries **no** payload: the coordinator holds at most one pending Play and re-asks
     * [resolve]/[peerHasContent] for exactly that hash, so a hash argument would be a second source
     * of truth about availability with nothing to gain. A later call replaces the observer.
     */
    fun observeAvailability(onAvailabilityChanged: () -> Unit)
}

/**
 * The one player/queue Phase 5 drives. Every method lands on the **existing**
 * `com.ridelink.app.music.MusicCoordinator`, its existing `LocalQueue` and its existing
 * `ExoPlayerMusicPlayer` (brief §21) — there is no second player, no second queue and no second
 * `MediaSession` anywhere in this phase.
 */
interface SyncPlayerPort {
    val playerState: StateFlow<PlayerState>

    /**
     * ARCHITECTURE §7.2's pre-roll: load and seek to [positionMs], but do **not** start.
     *
     * This is also brief §26's materialisation point. The authoritative shared queue is *not* copied
     * into `LocalQueue` wholesale — only the item that is actually current becomes the local queue's
     * one selected entry, which is what keeps `NowPlaying`/`MediaSession` metadata and the Phase 3
     * UI correct without two queues that could disagree about an index. `NEXT`/`PREVIOUS` are
     * resolved from the *shared* queue (brief §25), never from the local one, so there is nothing
     * the local copy would be consulted for.
     */
    suspend fun prepare(
        content: SyncPlayableContent,
        positionMs: Long,
    )

    suspend fun start()

    suspend fun pause()

    suspend fun seek(positionMs: Long)

    /** ADR-004's rate-nudge tier. Always exactly 1.0 when correction ends (brief §38). */
    suspend fun setRate(rate: Double)

    suspend fun stop()
}

/** [SyncPlaybackCoordinator]'s exact call surface on [PlaybackRelay]. */
interface PlaybackChannelPort {
    var playbackSink: PlaybackSink?

    var queueSink: QueueSink?

    /**
     * @param authorizingGeneration the authentication generation that authorised this frame
     *   (ADR-024 Amendment A2 Finding B). The relay refuses outright unless it is still the live
     *   one, so a frame that waited on the ordered outbound queue across a session boundary can
     *   never be written using the replacement session's writer or `session_id`.
     */
    suspend fun send(
        message: PlaybackMessage,
        authorizingGeneration: Long,
    ): Boolean

    suspend fun send(
        message: QueueMessage,
        authorizingGeneration: Long,
    ): Boolean
}

/**
 * [SyncPlaybackCoordinator]'s exact call surface on [ControlSessionManager]: the Phase 5 channel,
 * the session-lifecycle event stream, the read-only live-session view, and the one session clock.
 * Never the connection-management surface, which stays [ControlSessionManager]'s alone.
 */
interface SyncSessionPort {
    val playback: PlaybackChannelPort
    val events: SharedFlow<ControlEvent>
    val currentAuthGeneration: Long
    val clockEstimate: StateFlow<SessionClockEstimate?>

    /** The bounded RTT window's p95, available before the first offset estimate exists. */
    val rttP95Us: Long?
}

/**
 * Waiting until a local **monotonic** deadline. The one place Phase 5 touches real time, and the one
 * seam a test replaces to make every scheduling assertion deterministic (brief §47/§50: no sleeps).
 */
fun interface SyncDeadlineSleeper {
    suspend fun sleepUntil(localMonoUs: Long)
}

/** Zero-behaviour-change wrapper around an already-constructed [PlaybackRelay] (its constructor is `internal` to `network`). */
internal class PlaybackRelayAdapter(
    private val delegate: PlaybackRelay,
) : PlaybackChannelPort {
    override var playbackSink: PlaybackSink?
        get() = delegate.playbackSink
        set(value) {
            delegate.playbackSink = value
        }

    override var queueSink: QueueSink?
        get() = delegate.queueSink
        set(value) {
            delegate.queueSink = value
        }

    override suspend fun send(
        message: PlaybackMessage,
        authorizingGeneration: Long,
    ): Boolean = delegate.send(message, authorizingGeneration)

    override suspend fun send(
        message: QueueMessage,
        authorizingGeneration: Long,
    ): Boolean = delegate.send(message, authorizingGeneration)
}

/** Zero-behaviour-change wrapper — `AppContainer`'s production call site. */
internal class SyncSessionManagerAdapter(
    private val delegate: ControlSessionManager,
) : SyncSessionPort {
    override val playback: PlaybackChannelPort = PlaybackRelayAdapter(delegate.playback)
    override val events: SharedFlow<ControlEvent> get() = delegate.events
    override val currentAuthGeneration: Long get() = delegate.currentAuthGeneration
    override val clockEstimate: StateFlow<SessionClockEstimate?> get() = delegate.clock.estimate
    override val rttP95Us: Long? get() = delegate.clock.rttP95Us
}
