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
    val quickId: String,
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

enum class EndpointClass { BLUETOOTH, WIRED, BUILTIN_SPEAKER, BUILTIN_EARPIECE, OTHER, UNKNOWN }

enum class AudioProfile { MEDIA_STEREO, DUPLEX_NARROWBAND, DUPLEX_WIDEBAND, DUPLEX_WIDE_STEREO, BUILTIN, NONE, UNKNOWN }

enum class ProfileCoupling { INDEPENDENT, INPUT_FORCES_OUTPUT, UNKNOWN }

/** REQUIREMENTS §16 AudioRoute, refined by ADR-016 into declared capability + effective runtime state. */
data class AudioRoute(
    val endpointClass: EndpointClass,
    val effectiveOutputProfile: AudioProfile,
    val effectiveInputProfile: AudioProfile,
    val effectiveOutputSampleRateHz: Int?,
    val effectiveInputSampleRateHz: Int?,
    val profileCoupling: ProfileCoupling,
)
