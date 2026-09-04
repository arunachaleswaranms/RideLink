import Foundation

/// What a `Player` can be told to do. Deliberately narrow (this phase's brief §15): loading and
/// transport control only. Next/Previous are a **queue-owner** concept (`LocalQueue` turns them into
/// a fresh `.load` + `.play`), not a player command — a player has no idea what "next" means.
///
/// No `commandSeq`, no `effectiveAtSessionTime` — that is Phase 5 wire behaviour (ADR-004/§9.3) and
/// this phase's brief §15 explicitly excludes it. A local player executes commands as it receives
/// them, immediately.
public enum PlaybackCommand: Sendable, Equatable {
    /// `location` is what a real player binding actually opens; `quickId` is carried alongside
    /// purely for identity so `PlayerState.quickId` can report *which* track is loaded without the
    /// player ever resolving an id back to a location itself. That resolution
    /// (`QuickId -> LocalTrackLocation`) is the data-layer repository's job — the real player
    /// binding must not depend on it directly (ADR-014's mirrored boundary); the app composition
    /// root's queue-owner coordinator does the lookup and builds this command already carrying
    /// everything the player needs.
    ///
    /// `QuickId`, not `ContentHash`: the authoritative hash is computed lazily in the background
    /// (ADR-005) and is absent on a freshly-indexed track, but nothing about *playing a local file*
    /// needs it — only Phase 4/5 transfer/sync eligibility does. Making local queue/player identity
    /// wait on a background hash would make a track the user just imported briefly unplayable for
    /// no reason a local player has.
    case load(quickId: QuickId, location: LocalTrackLocation)
    case play
    case pause
    case seek(positionMs: Int64)
    case stop
}

/// Named failures a platform decoder/player can hit, never a raw platform error crossing the domain
/// boundary (matches `RideLinkCore.AudioPolicy`'s `VoiceFailure` pattern: one value per distinct
/// thing the user or the app can do about it).
public enum MusicFailure: Sendable, Equatable {
    /// The platform decoder rejected the file's content — `DecodeStatus.corrupt`.
    case decodeFailed
    /// `PlaybackCommand.load`'s location could not be opened — the file moved, was deleted, or a
    /// security-scoped bookmark lapsed.
    case fileMissing
    /// The container/codec is not one the platform decoder supports at all.
    case unsupportedFormat
    /// A storage-layer read failure that is not clearly either of the above (a disk I/O error).
    case storageIo
    /// A newer `PlaybackCommand.load` superseded this one before it finished — not a real failure.
    case cancelled
}

/// Observable player state. Enough for Phase 5 to build synchronized playback on top of later
/// (`quickId`, `positionMs`, `durationMs` are exactly what a future session-time scheduler would
/// need to read) **without** this phase adding any of that behaviour itself (brief §15).
public struct PlayerState: Sendable, Equatable {
    public let quickId: QuickId?
    public let positionMs: Int64
    public let durationMs: Int64
    public let playing: Bool
    /// Playback rate if the platform exposes one; 1.0 when it doesn't or hasn't been changed.
    public let rate: Double
    public let error: MusicFailure?

    public init(
        quickId: QuickId? = nil,
        positionMs: Int64 = 0,
        durationMs: Int64 = 0,
        playing: Bool = false,
        rate: Double = 1.0,
        error: MusicFailure? = nil
    ) {
        self.quickId = quickId
        self.positionMs = positionMs
        self.durationMs = durationMs
        self.playing = playing
        self.rate = rate
        self.error = error
    }

    /// True once `positionMs` has reached `durationMs` on a loaded, non-zero-length track — the
    /// signal a queue owner turns into `LocalQueueAction.next`.
    public var ended: Bool {
        quickId != nil && durationMs > 0 && positionMs >= durationMs && !playing
    }
}
