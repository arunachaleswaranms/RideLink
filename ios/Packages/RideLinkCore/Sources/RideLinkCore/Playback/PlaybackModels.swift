import Foundation

/// PROTOCOL §5 / ADR-010: which side of the command-ordering split this device is on. Derived from
/// `peer_id` alone (the lexicographically smaller one leads) and **never** from who dialled, who
/// pressed play, which platform this is, or who owns the track.
public enum PlaybackRole: String, Sendable, Equatable {
    case leader = "LEADER"
    case follower = "FOLLOWER"
}

/// Phase 5 wire bounds. Every one is checked at parse time on both platforms and pinned by
/// `protocol/vectors/playback-messages/` and `protocol/vectors/queue-messages/`.
public enum PlaybackBounds {
    /// The largest integer a JSON number survives intact on **both** platforms. Swift decodes JSON
    /// numbers through `Double`, so a `uint64` above 2^53-1 would silently round here while staying
    /// exact on Android — two peers disagreeing about a `command_seq` is precisely the class of
    /// silent cross-platform divergence the shared vectors exist to catch. PROTOCOL §5's
    /// `command_seq`, §9's `queue_revision` and every `*_session_us` are bounded here.
    ///
    /// The bound is generous, not restrictive: 2^53 microseconds is over 285 years of monotonic
    /// uptime, and a ride would have to issue a command every microsecond for nine decades to reach
    /// it as a `command_seq`.
    public static let maxWireInt: Int64 = 9_007_199_254_740_991

    /// PROTOCOL §5: `command_seq` is leader-assigned and strictly increasing, starting at 1. **Zero
    /// is reserved** and means "unassigned" — it is how a follower's *intent* is distinguished from
    /// the leader's *authoritative command* without adding a message type (ADR-024 §3). A receiver
    /// therefore never treats 0 as an ordering value.
    public static let unassignedCommandSeq: Int64 = 0

    public static let firstCommandSeq: Int64 = 1

    /// PROTOCOL §9's queue cap, **corrected from 2 000 to 1 000** (ADR-024 §6). §9 asserted that
    /// 2 000 items "cannot" overflow `MAX_CONTROL_FRAME_BYTES`; the arithmetic says otherwise —
    /// ~190 encoded bytes per item x 2 000 = ~378 KB against a 256 KiB cap. 1 000 items is ~190 KB,
    /// inside the 192 KiB budget §8.1 already uses for the same reason, and is far beyond any queue
    /// two people build on one motorcycle. **The cap moved, not the frame limit.**
    public static let maxQueueItems = 1_000

    /// PROTOCOL §9: sparse ordering keys, so a `QUEUE_MOVE` rarely has to renumber.
    public static let queueOrderStep: Int64 = 1_024

    /// PROTOCOL §5: `POSITION_REPORT` every 5 s, both directions.
    public static let positionReportIntervalMs: Int64 = 5_000

    /// A defensive ceiling on a reported/commanded track position: 24 hours.
    public static let maxPositionMs: Int64 = 86_400_000

    /// The playback-rate range the drift ladder may ever ask for, checked at parse time.
    public static let minPlaybackRate = 0.5
    public static let maxPlaybackRate = 2.0

    /// PROTOCOL §9's `position` vocabulary for `QUEUE_ADD`. Unknown values degrade to `end`.
    public static let queuePositionEnd = "end"
    public static let queuePositionNext = "next"
    public static let validQueuePositions: Set<String> = [queuePositionEnd, queuePositionNext]
}

/// The fields PROTOCOL §5 puts on **every** playback command payload. Held once, as a value, rather
/// than repeated across six message cases.
///
/// - `commandSeq`: leader-assigned and strictly increasing — *the* ordering authority (never `seq`,
///   never `msg_id`, never `sent_at_mono_us`, never `effective_at_session_us`).
///   `PlaybackBounds.unassignedCommandSeq` marks a follower intent.
/// - `effectiveAtSessionUs`: the session-clock instant the command takes audible effect.
/// - `issuedBy`: the `peer_id` of the user who pressed the button — UI attribution only, and never
///   an ordering or authorisation input.
/// - `queueRevision`: the queue version this command assumes (PROTOCOL §5 rule 3).
public struct PlaybackCommandHeader: Sendable, Equatable {
    public let commandSeq: Int64
    public let effectiveAtSessionUs: Int64
    public let issuedBy: PeerId
    public let queueRevision: Int64

    public init(commandSeq: Int64, effectiveAtSessionUs: Int64, issuedBy: PeerId, queueRevision: Int64) {
        self.commandSeq = commandSeq
        self.effectiveAtSessionUs = effectiveAtSessionUs
        self.issuedBy = issuedBy
        self.queueRevision = queueRevision
    }

    /// True when this frame is a follower's intent rather than the leader's authoritative command.
    public var isIntent: Bool { commandSeq == PlaybackBounds.unassignedCommandSeq }
}

/// One decoded, bounds-checked playback message (PROTOCOL §5). `positionReport` and `playbackState`
/// are deliberately in the same family but carry **no** `PlaybackCommandHeader`: neither is a
/// command, and neither may ever outrank one (this phase's brief §31).
public enum PlaybackMessage: Sendable, Equatable {
    /// PROTOCOL §5 `PLAY`. `trackHash` is authoritative identity (ADR-005) — never a filename or `quick_id`.
    case play(header: PlaybackCommandHeader, trackHash: ContentHash, positionMs: Int64, queueItemId: String)
    case pause(header: PlaybackCommandHeader, positionMs: Int64)
    /// PROTOCOL §5 lists `RESUME` in the catalogue but shows no payload. ADR-024 §4 fills it in with
    /// `PAUSE`'s shape — resuming from an explicit position is what lets both phones restart from
    /// the same instant rather than from whatever each had drifted to while paused.
    case resume(header: PlaybackCommandHeader, positionMs: Int64)
    case seek(header: PlaybackCommandHeader, targetPositionMs: Int64)
    case next(header: PlaybackCommandHeader)
    case previous(header: PlaybackCommandHeader)
    /// PROTOCOL §5's drift input, sent every 5 s in both directions. Diagnostics and corrective
    /// *input* only — it is not a command, has no `command_seq`, and can never supersede one.
    case positionReport(trackHash: ContentHash, positionMs: Int64, atSessionUs: Int64, playing: Bool, playbackRate: Double)
    /// PROTOCOL §5's "full authoritative snapshot the leader emits after any correction or reconnect
    /// — the reconciliation anchor, not an incremental update." Its payload was likewise
    /// unspecified; ADR-024 §4 derives it from §10's `STATE_SNAPSHOT.playback` so the two agree
    /// field for field. `trackHash` is nullable because "nothing is loaded" is a representable
    /// authoritative state.
    case playbackState(
        commandSeq: Int64,
        queueRevision: Int64,
        trackHash: ContentHash?,
        queueItemId: String?,
        positionMs: Int64,
        playing: Bool,
        atSessionUs: Int64
    )
}

/// The fields PROTOCOL §9's mutation messages carry. Deliberately **not** `PlaybackCommandHeader`:
/// §9's own `QUEUE_ADD` example carries `command_seq` and `queue_revision` and nothing else, because
/// a queue mutation has no audible instant to schedule (no `effective_at_session_us`) and its
/// attribution already lives per item in `added_by`.
///
/// `commandSeq` is the same leader-assigned ordering authority §5 defines, with the same
/// `unassignedCommandSeq` intent convention — one serialisation point orders playback commands and
/// queue mutations together, which is what makes "NEXT racing a QUEUE_REMOVE" resolve
/// deterministically rather than by luck.
public struct QueueCommandHeader: Sendable, Equatable {
    public let commandSeq: Int64
    public let queueRevision: Int64

    public init(commandSeq: Int64, queueRevision: Int64) {
        self.commandSeq = commandSeq
        self.queueRevision = queueRevision
    }

    public var isIntent: Bool { commandSeq == PlaybackBounds.unassignedCommandSeq }
}

/// One decoded, bounds-checked queue message (PROTOCOL §9).
public enum QueueMessage: Sendable, Equatable {
    case add(header: QueueCommandHeader, items: [QueueAddItem])
    case remove(header: QueueCommandHeader, queueItemIds: [String])
    case move(header: QueueCommandHeader, queueItemId: String, toIndex: Int)
    /// PROTOCOL §9's reconciliation mechanism, and in this implementation the **only** way the
    /// authoritative queue reaches a follower (ADR-024 §5): the leader broadcasts a snapshot after
    /// every accepted mutation and the follower adopts it wholesale. "The snapshot always wins —
    /// there is no merge algorithm to get subtly wrong" is §9's own rule, taken literally.
    case snapshot(queueRevision: Int64, items: [SharedQueueItem], currentIndex: Int?)
}

/// One item inside a `QUEUE_ADD`. `queueItemId` is a ULID minted by the **issuer**, which is what
/// makes an add idempotent under retry (PROTOCOL §9) — and what lets the same `trackHash` appear in
/// the queue more than once as separately removable entries (this phase's brief §27).
public struct QueueAddItem: Sendable, Equatable {
    public let queueItemId: String
    public let trackHash: ContentHash
    public let addedBy: PeerId
    /// `PlaybackBounds.queuePositionEnd` or `...Next`; an unknown value degrades to `end`.
    public let position: String

    public init(queueItemId: String, trackHash: ContentHash, addedBy: PeerId, position: String) {
        self.queueItemId = queueItemId
        self.trackHash = trackHash
        self.addedBy = addedBy
        self.position = position
    }
}

/// One slot in the replicated shared queue. Deliberately **no** `status` field: PROTOCOL §9 says in
/// the same paragraph that `status` is "derived locally from presence, never trusted from the peer",
/// so carrying it on the wire contradicted its own rule. ADR-024 §6 removes it; local availability
/// comes from `RideLinkCore.Availability`, as it always did.
public struct SharedQueueItem: Sendable, Equatable {
    public let queueItemId: String
    public let trackHash: ContentHash
    public let addedBy: PeerId
    public let order: Int64

    public init(queueItemId: String, trackHash: ContentHash, addedBy: PeerId, order: Int64) {
        self.queueItemId = queueItemId
        self.trackHash = trackHash
        self.addedBy = addedBy
        self.order = order
    }
}
