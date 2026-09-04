import Foundation

public enum Platform: Sendable, Equatable {
    case android
    case ios
}

/// REQUIREMENTS §16. Durable record of a paired peer.
public struct Peer: Sendable, Equatable {
    public let peerId: PeerId
    public let displayName: String
    public let identity: SpkiHash
    public let platform: Platform
    public let appVersion: String

    public init(peerId: PeerId, displayName: String, identity: SpkiHash, platform: Platform, appVersion: String) {
        self.peerId = peerId
        self.displayName = displayName
        self.identity = identity
        self.platform = platform
        self.appVersion = appVersion
    }
}

/// A discovered-but-not-yet-connected peer, from mDNS TXT { v, dh, plat } only (ARCHITECTURE §4.1).
public struct DiscoveredPeer: Sendable, Equatable {
    public let discoveryHandle: String
    public let protocolMajorVersion: Int
    public let platform: Platform?
    public let host: String
    public let port: Int

    public init(discoveryHandle: String, protocolMajorVersion: Int, platform: Platform?, host: String, port: Int) {
        self.discoveryHandle = discoveryHandle
        self.protocolMajorVersion = protocolMajorVersion
        self.platform = platform
        self.host = host
        self.port = port
    }
}

/// REQUIREMENTS §16. A distinct file on one phone; content_hash is the authoritative identity (ADR-005).
public struct Track: Sendable, Equatable {
    public let contentHash: ContentHash?
    public let quickId: QuickId
    public let title: String
    public let artist: String
    public let album: String
    public let durationMs: Int64
    public let filename: String
    public let codec: String
    public let bitrateKbps: Int
    public let artworkRef: String?
    public let sizeBytes: Int64

    public init(
        contentHash: ContentHash?,
        quickId: QuickId,
        title: String,
        artist: String,
        album: String,
        durationMs: Int64,
        filename: String,
        codec: String,
        bitrateKbps: Int,
        artworkRef: String?,
        sizeBytes: Int64
    ) {
        self.contentHash = contentHash
        self.quickId = quickId
        self.title = title
        self.artist = artist
        self.album = album
        self.durationMs = durationMs
        self.filename = filename
        self.codec = codec
        self.bitrateKbps = bitrateKbps
        self.artworkRef = artworkRef
        self.sizeBytes = sizeBytes
    }
}

public enum TransferState: Sendable, Equatable {
    case none
    case pending
    case transferring
    case failed
}

/// REQUIREMENTS §16. Local knowledge of whether a peer has a given track.
public struct TrackPresence: Sendable, Equatable {
    public let contentHash: ContentHash
    public let peerId: PeerId
    public let locallyAvailable: Bool
    public let cached: Bool
    public let transferState: TransferState
}

public enum QueueItemStatus: Sendable, Equatable {
    case ready
    case remoteOnly
    case transferring
    case unavailable
}

public struct QueueItem: Sendable, Equatable {
    public let queueItemId: String
    public let trackHash: ContentHash
    public let addedBy: PeerId
    public let order: Int64
    public let status: QueueItemStatus
}

public struct PlaybackState: Sendable, Equatable {
    public let trackHash: ContentHash
    public let positionMs: Int64
    public let playing: Bool
    public let commandSeq: Int64
    public let effectiveSessionTimeUs: Int64
}

/// REQUIREMENTS §16 / PROTOCOL §6 METRICS. Diagnostics only, never used for scheduling.
public struct SessionMetrics: Sendable, Equatable {
    public let rttMs: Double
    public let jitterMs: Double
    public let packetLossPct: Double
    public let clockOffsetUs: Int64
    public let musicDriftMs: Int64
    public let reconnectCount: Int
}

// REQUIREMENTS §16's `AudioRoute` entity, and the `EndpointClass` / `AudioProfile` /
// `ProfileCoupling` enums it was made of, used to be declared here as unimplemented Phase 1a shells.
// Phase 2a implements them, and they moved to `RideLinkCore.AudioPolicy` — which is where ADR-016
// says the audio vocabulary lives, and the one place platform profile names are translated into it.
//
// The implemented form is `AudioRouteSnapshot`. It is a superset: ADR-016's runtime half needs
// `route_state`, `confidence`, an interruption flag and the derived `media_quality`, none of which
// the shell had. Keeping both would have been two types for one concept, differing only in which one
// a given call site happened to reach for — exactly the drift the shared vectors exist to prevent,
// in a place no vector could see.
//
// See ADR-016 and `docs/STATUS.md` §2i.
