import Foundation

/// The authoritative playback timeline both phones track: "at session instant `anchorSessionUs` this
/// track was at `anchorPositionMs`, and it is (or is not) advancing."
///
/// Every drift measurement in the system is `actual local position - expectedPositionMs(now)`
/// against **this** object (this phase's brief §33). Subtracting one phone's reported position from
/// the other's is explicitly not how it is done: those two numbers are sampled at different session
/// instants and separated by a network delay, so their difference is not a drift.
///
/// `generation` exists because `trackHash` is not enough to identify a playback epoch — the same
/// track can be played again, and a `POSITION_REPORT` or a scheduled timer from the *previous* play
/// of the same hash must be inert (brief §32). It is local bookkeeping, never on the wire; the wire
/// distinguishes epochs by `anchorSessionUs`, which strictly increases with every accepted command.
public struct PlaybackTimeline: Sendable, Equatable {
    public static let microsPerMs: Int64 = 1_000

    public let trackHash: ContentHash
    public let queueItemId: String
    public let anchorPositionMs: Int64
    public let anchorSessionUs: Int64
    public let playing: Bool
    public let generation: Int64

    public init(
        trackHash: ContentHash,
        queueItemId: String,
        anchorPositionMs: Int64,
        anchorSessionUs: Int64,
        playing: Bool,
        generation: Int64
    ) {
        self.trackHash = trackHash
        self.queueItemId = queueItemId
        self.anchorPositionMs = anchorPositionMs
        self.anchorSessionUs = anchorSessionUs
        self.playing = playing
        self.generation = generation
    }

    /// Where the track should be at `atSessionUs`, derived from the anchor alone.
    ///
    /// Before the anchor (a scheduled command whose deadline has not arrived) the expected position
    /// is the anchor position itself — never a negative extrapolation. `durationMs` clamps the top
    /// when it is known; `nil` means the decoder has not reported one yet and no clamp is applied.
    public func expectedPositionMs(atSessionUs: Int64, durationMs: Int64? = nil) -> Int64 {
        if !playing || atSessionUs <= anchorSessionUs { return clamp(anchorPositionMs, durationMs) }
        let elapsedMs = (atSessionUs - anchorSessionUs) / Self.microsPerMs
        return clamp(anchorPositionMs + elapsedMs, durationMs)
    }

    /// `actual - expected`: positive means this device is **ahead** of the authoritative timeline
    /// (it must slow down or seek back), negative means behind.
    public func driftMs(actualPositionMs: Int64, atSessionUs: Int64, durationMs: Int64? = nil) -> Int64 {
        actualPositionMs - expectedPositionMs(atSessionUs: atSessionUs, durationMs: durationMs)
    }

    private func clamp(_ positionMs: Int64, _ durationMs: Int64?) -> Int64 {
        let floored = max(positionMs, 0)
        if let durationMs, durationMs > 0 { return min(floored, durationMs) }
        return floored
    }
}
