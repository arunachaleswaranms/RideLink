package com.ridelink.app.music

import com.ridelink.core.library.LocalTrackLocation
import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.LocalEntryId
import java.util.concurrent.ConcurrentHashMap

/**
 * Closure-audit Finding G: a verified Phase-4 cache-only track — one that exists only as
 * [com.ridelink.data.transfer.TransferCacheRepository]'s committed, whole-file-SHA-256-verified
 * bytes and was never imported into the Phase 3 library — played through the *existing* one
 * queue/player. Never written to [com.ridelink.data.library.LibraryRepository]: provenance stays
 * distinct (ADR-023 §6 — LOCAL IMPORTED and VERIFIED PEER CACHE are different storage origins),
 * and this registry is the only place the association exists.
 *
 * [LocalEntryId] here is a fresh, opaque token minted at play time purely so the *existing*
 * [com.ridelink.core.player.LocalQueue]/[com.ridelink.core.player.Player] can carry an identity for
 * the track — never looked up against the library repository, and never persisted past this
 * process's lifetime.
 */
internal data class ExternalCacheSource(
    val contentHash: ContentHash,
    val location: LocalTrackLocation,
    val title: String?,
    val artist: String?,
)

/**
 * The registry behind [MusicCoordinator.playExternalVerifiedCachedTrack], extracted so the one
 * property that matters for cache lifetime — "which verified cache entry does the player hold open
 * right now" — is a plain synchronous read with no sharing policy of its own, and is unit-testable
 * without constructing a [MusicCoordinator] (which needs Android-only collaborators).
 *
 * **Closure-audit Amendment A4 Finding U — why this is not a `StateFlow`.** It was one:
 * `_queueState.map { … }.stateIn(scope, SharingStarted.WhileSubscribed(), null)`, read by
 * `AppContainer` as `.value` to build every `TransferCacheRepository.commit`'s `locked` set. Under
 * `WhileSubscribed` the upstream is only collected while a collector exists, and **nothing in the
 * app ever collected this one** — no screen displays it; its only consumer reads `.value` — so it
 * returned its initial `null` forever. That silently emptied the `locked` set and left Finding I's
 * whole "never evict the file the player has open" protection inert. A synchronous read of live
 * state cannot have that failure mode, which is why the fix is to remove the flow rather than to
 * change its sharing policy. iOS's equivalent was already a plain computed property and needed no
 * change.
 *
 * Thread-safe by construction: writes come from [MusicCoordinator] (its own scope), reads come from
 * [com.ridelink.app.library.SharedLibraryCoordinator]'s transfer coroutine on a different
 * dispatcher, so a plain `mutableMapOf` here would be a data race as well as a stale read.
 */
internal class ExternalCacheSources {
    private val sources = ConcurrentHashMap<LocalEntryId, ExternalCacheSource>()

    fun register(
        entryId: LocalEntryId,
        source: ExternalCacheSource,
    ) {
        sources[entryId] = source
    }

    operator fun get(entryId: LocalEntryId): ExternalCacheSource? = sources[entryId]

    /**
     * The [ContentHash] of the verified cache entry the player currently has loaded, or `null`
     * whenever nothing playing right now is a cache-only track — including "nothing is playing" and
     * "a Phase 3 imported track is playing". Every `TransferCacheRepository.commit` includes this in
     * its `locked` set, which is that repository's documented requirement: the caller must pass the
     * *complete* current in-use set on every call.
     */
    fun activeHash(currentItemEntryId: LocalEntryId?): ContentHash? = currentItemEntryId?.let { sources[it]?.contentHash }
}
