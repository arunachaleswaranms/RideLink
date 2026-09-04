package com.ridelink.core.model

/** REQUIREMENTS §16. Durable record of a paired peer. */
data class Peer(
    val peerId: PeerId,
    val displayName: String,
    val identity: SpkiHash,
    val platform: Platform,
    val appVersion: String,
)

enum class Platform { ANDROID, IOS }

/** A discovered-but-not-yet-connected peer, from mDNS TXT { v, dh, plat } only (ARCHITECTURE §4.1). */
data class DiscoveredPeer(
    val discoveryHandle: String,
    val protocolMajorVersion: Int,
    val platform: Platform?,
    val host: String,
    val port: Int,
)

/** REQUIREMENTS §16. A distinct file on one phone; content_hash is the authoritative identity (ADR-005). */
data class Track(
    val contentHash: ContentHash?,
    val quickId: QuickId,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val filename: String,
    val codec: String,
    val bitrateKbps: Int,
    val artworkRef: String?,
    val sizeBytes: Long,
)

enum class TransferState { NONE, PENDING, TRANSFERRING, FAILED }

/** REQUIREMENTS §16. Local knowledge of whether a peer has a given track. */
data class TrackPresence(
    val contentHash: ContentHash,
    val peerId: PeerId,
    val locallyAvailable: Boolean,
    val cached: Boolean,
    val transferState: TransferState,
)

data class QueueItem(
    val queueItemId: String,
    val trackHash: ContentHash,
    val addedBy: PeerId,
    val order: Long,
    val status: QueueItemStatus,
)

enum class QueueItemStatus { READY, REMOTE_ONLY, TRANSFERRING, UNAVAILABLE }

data class PlaybackState(
    val trackHash: ContentHash,
    val positionMs: Long,
    val playing: Boolean,
    val commandSeq: Long,
    val effectiveSessionTimeUs: Long,
)

/** REQUIREMENTS §16 / PROTOCOL §6 METRICS. Diagnostics only, never used for scheduling. */
data class SessionMetrics(
    val rttMs: Double,
    val jitterMs: Double,
    val packetLossPct: Double,
    val clockOffsetUs: Long,
    val musicDriftMs: Long,
    val reconnectCount: Int,
)

// REQUIREMENTS §16's `AudioRoute` entity, and the `EndpointClass` / `AudioProfile` /
// `ProfileCoupling` enums it was made of, used to be declared here as unimplemented Phase 1a shells.
// Phase 2a implements them, and they moved to `core.audiopolicy` — which is where ADR-016 says the
// audio vocabulary lives, and the one place platform profile names are translated into it.
//
// The implemented form is `core.audiopolicy.AudioRouteSnapshot`. It is a superset: ADR-016's runtime
// half needs `route_state`, `confidence`, an interruption flag and the derived `media_quality`, none
// of which the shell had. Keeping both would have been two types for one concept, differing only in
// which one a given call site happened to reach for — exactly the drift the shared vectors exist to
// prevent, in a place no vector could see.
//
// See ADR-016 and `docs/STATUS.md` §2i.
