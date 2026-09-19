import Foundation

/// PROTOCOL §3's Resync group and §10: `STATE_REQUEST` / `STATE_SNAPSHOT`. A follower asks for
/// authoritative state after a reconnect or a detected desynchronisation; the leader alone answers,
/// because only the leader holds authoritative `command_seq`/`queue_revision` (ADR-010) — the same
/// asymmetry PROTOCOL §5/§9 already enforce for every other authoritative frame.
///
/// Bounds live here rather than duplicated in the codec, matching `PlaybackBounds`. Mirrors
/// Android's `com.ridelink.core.resync.ResyncBounds` exactly.
public enum ResyncBounds {
    /// A defensive ceiling on `transfers_in_flight`, in the same spirit as
    /// `PlaybackBounds.maxQueueItems`: V1 runs at most one active transfer at a time per role
    /// (ADR-023 §1's "at most one live bulk listener"), so this is generous headroom rather than a
    /// realistic count.
    public static let maxTransfersInFlight = 64
}

/// The playback half of a `STATE_SNAPSHOT` (PROTOCOL §10). Deliberately **not**
/// `PlaybackMessage.playbackState`: that case also carries `command_seq`/`queue_revision`, which
/// `STATE_SNAPSHOT` states once at the envelope level instead (PROTOCOL §5's own cross-reference —
/// "[`PLAYBACK_STATE`'s] shape is §10's `STATE_SNAPSHOT.playback` plus the two ordering values an
/// anchor needs" — is what fixes the shape here; see ADR-028).
///
/// `trackHash` and `queueItemId` are nullable together: "nothing is loaded" is a representable
/// authoritative state, exactly as it is for `PLAYBACK_STATE`.
public struct ResyncPlaybackSnapshot: Sendable, Equatable {
    public let trackHash: ContentHash?
    public let queueItemId: String?
    public let positionMs: Int64
    public let playing: Bool
    public let atSessionUs: Int64

    public init(trackHash: ContentHash?, queueItemId: String?, positionMs: Int64, playing: Bool, atSessionUs: Int64) {
        self.trackHash = trackHash
        self.queueItemId = queueItemId
        self.positionMs = positionMs
        self.playing = playing
        self.atSessionUs = atSessionUs
    }
}

/// One entry of `STATE_SNAPSHOT.transfers_in_flight`. Informational only — V1 never resumes one.
public struct ResyncTransferInFlight: Sendable, Equatable {
    public let transferId: TransferId
    public let contentHash: ContentHash
    public let bytesDone: Int64

    public init(transferId: TransferId, contentHash: ContentHash, bytesDone: Int64) {
        self.transferId = transferId
        self.contentHash = contentHash
        self.bytesDone = bytesDone
    }
}

/// One decoded, bounds-checked resync message (PROTOCOL §10). Mirrors Android's
/// `com.ridelink.core.resync.ResyncMessage` exactly.
public enum ResyncMessage: Sendable, Equatable {
    /// PROTOCOL §10: a follower's request for authoritative state. Carries no payload.
    case stateRequest

    /// PROTOCOL §10: "the authoritative reconciliation payload." The leader's answer, and the only
    /// authority — "no merge algorithm."
    ///
    /// `playback` is `nil` only when the leader has never had a synchronised timeline this session;
    /// a leader that *has* one always reports it, `trackHash: nil` included, so a follower can tell
    /// "the leader has nothing loaded" from "the leader said nothing yet."
    case stateSnapshot(
        leaderPeerId: PeerId,
        commandSeq: Int64,
        queueRevision: Int64,
        playback: ResyncPlaybackSnapshot?,
        queueItems: [SharedQueueItem],
        queueCurrentIndex: Int?,
        manifestRevision: Int64,
        transfersInFlight: [ResyncTransferInFlight]
    )
}
