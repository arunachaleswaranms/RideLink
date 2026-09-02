import Foundation

/// One remote ICE candidate, held until the remote description makes it applicable.
public struct RemoteCandidate: Sendable, Equatable {
    public let voiceSessionId: VoiceSessionId
    public let candidate: String
    public let sdpMid: String?
    public let sdpMlineIndex: Int

    public init(voiceSessionId: VoiceSessionId, candidate: String, sdpMid: String?, sdpMlineIndex: Int) {
        self.voiceSessionId = voiceSessionId
        self.candidate = candidate
        self.sdpMid = sdpMid
        self.sdpMlineIndex = sdpMlineIndex
    }
}

/// PROTOCOL §7.4's bounded trickle-ICE queue.
///
/// Trickle ICE means a candidate can arrive before the remote description that makes it applicable,
/// so early candidates must be *held* rather than dropped — dropping them turns a slow SDP round trip
/// into what looks like a connectivity failure. But the peer decides how many arrive, so an unbounded
/// hold is a memory amplifier under a remote peer's control.
///
/// The compromise, and the reason it is a type rather than a bare array:
///
/// - capacity is `VoiceBounds.maxQueuedCandidates`;
/// - at capacity the **oldest** candidate is discarded, because the newest are the ones most likely to
///   still describe a reachable path;
/// - every discard is **counted** (`droppedCount`) and surfaced in the voice diagnostics. Silent
///   truncation would read as "we saw everything the peer sent", which is exactly the wrong thing to
///   believe while debugging a ride.
///
/// Pure: no clock, no I/O, no platform type. `com.ridelink.core.voice.PendingCandidates` is the mirror.
public struct PendingCandidates: Sendable {
    private let capacity: Int
    private var queue: [RemoteCandidate] = []

    public private(set) var droppedCount = 0

    public init(capacity: Int = VoiceBounds.maxQueuedCandidates) {
        self.capacity = capacity
    }

    public var count: Int { queue.count }

    /// - Returns: true if the candidate was held, false if it displaced an older one to fit.
    @discardableResult
    public mutating func offer(_ candidate: RemoteCandidate) -> Bool {
        let fitted = queue.count < capacity
        if !fitted {
            queue.removeFirst()
            droppedCount += 1
        }
        queue.append(candidate)
        return fitted
    }

    /// Removes and returns everything queued for `voiceSessionId`, in arrival order, and discards
    /// anything queued for any other generation.
    ///
    /// Draining is generation-scoped for the same reason PROTOCOL §7.2 exists: a candidate held from a
    /// negotiation that has since been torn down must not be applied to the next one, and the moment
    /// the queue is drained is the last chance to make sure of it.
    public mutating func drain(voiceSessionId: VoiceSessionId) -> [RemoteCandidate] {
        let matching = queue.filter { $0.voiceSessionId == voiceSessionId }
        queue.removeAll()
        return matching
    }

    public mutating func clear() {
        queue.removeAll()
    }

    /// Resets the counter as well — used when a whole voice session ends, not between negotiations.
    public mutating func reset() {
        queue.removeAll()
        droppedCount = 0
    }
}
