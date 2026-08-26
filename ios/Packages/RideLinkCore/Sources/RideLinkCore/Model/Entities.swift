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
    public let quickId: String
    public let title: String
    public let artist: String
    public let album: String
    public let durationMs: Int64
    public let filename: String
    public let codec: String
    public let bitrateKbps: Int
    public let artworkRef: String?
    public let sizeBytes: Int64
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

public enum EndpointClass: Sendable, Equatable {
    case bluetooth
    case wired
    case builtinSpeaker
    case builtinEarpiece
    case other
    case unknown
}

public enum AudioProfile: Sendable, Equatable {
    case mediaStereo
    case duplexNarrowband
    case duplexWideband
    case duplexWideStereo
    case builtin
    case none
    case unknown
}

public enum ProfileCoupling: Sendable, Equatable {
    case independent
    case inputForcesOutput
    case unknown
}

/// REQUIREMENTS §16 AudioRoute, refined by ADR-016 into declared capability + effective runtime state.
public struct AudioRoute: Sendable, Equatable {
    public let endpointClass: EndpointClass
    public let effectiveOutputProfile: AudioProfile
    public let effectiveInputProfile: AudioProfile
    public let effectiveOutputSampleRateHz: Int?
    public let effectiveInputSampleRateHz: Int?
    public let profileCoupling: ProfileCoupling
}
