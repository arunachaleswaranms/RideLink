import Foundation
import RideLinkCore

/// Wires ADR-015 / PROTOCOL §4.2 into real candidate sockets. A candidate socket that has
/// completed `ControlHandshake` (both `conn_tiebreak` values now known) is held here — never
/// handed to a session owner — until resolution completes, per ARCHITECTURE §4.2: "candidate
/// connections ... are held by the transport layer, not by the coordinator, so a losing socket
/// can never touch session state."
///
/// `conn_tiebreak` is stable for the whole discovery session (ADR-015), so as soon as it is known
/// on *either* direction it resolves the outcome for *both* possible connections to this peer —
/// there is only ever at most one outbound and one inbound candidate per remote peer at a time.
///
/// Because a rival connection can complete its handshake a moment after this one does, a
/// candidate that arrives alone is held for `gracePeriodNs` before being declared the sole
/// survivor — short enough not to be felt as latency, long enough to catch the "both peers retry
/// within milliseconds of each other" race PROTOCOL §4.2 describes as the normal case.
public actor DuplicateConnectionArbiter {
    public struct Candidate: Sendable {
        public let socket: ControlConnection
        public let outcome: HandshakeOutcome

        public init(socket: ControlConnection, outcome: HandshakeOutcome) {
            self.socket = socket
            self.outcome = outcome
        }

        fileprivate var success: (remotePeerId: PeerId, remoteConnTiebreak: ConnTiebreak, sessionId: SessionId, leaderPeerId: PeerId) {
            guard case .success(let remotePeerId, let remoteConnTiebreak, let sessionId, let leaderPeerId, _, _) = outcome else {
                preconditionFailure("Candidate must wrap a successful handshake outcome")
            }
            return (remotePeerId, remoteConnTiebreak, sessionId, leaderPeerId)
        }
    }

    public enum Result: Sendable {
        /// No rival yet. Caller should wait `gracePeriodNs` then call `finalizeIfStillLone`.
        case awaitingRival(Candidate)
        /// `loser` is `nil` when there was never a rival to close.
        case survivor(winner: Candidate, loser: Candidate?)
        /// conn_tiebreak values were byte-identical (2^-128): both closed, retry with `newConnTiebreak`.
        case tieRetry(newConnTiebreak: ConnTiebreak)
    }

    public static let gracePeriodNs: UInt64 = 300_000_000 // 300ms

    private let localPeerId: PeerId
    private var myConnTiebreak: ConnTiebreak
    private var outbound: Candidate?
    private var inbound: Candidate?

    public init(localPeerId: PeerId, initialConnTiebreak: ConnTiebreak) {
        self.localPeerId = localPeerId
        self.myConnTiebreak = initialConnTiebreak
    }

    public var connTiebreak: ConnTiebreak { myConnTiebreak }

    public func register(_ candidate: Candidate) -> Result {
        if candidate.socket.isInitiator {
            outbound = candidate
        } else {
            inbound = candidate
        }
        guard let o = outbound, let i = inbound else {
            return .awaitingRival(candidate)
        }

        let verdict = Dedup.resolve(
            Dedup.PeerTiebreak(peerId: localPeerId, connTiebreak: myConnTiebreak),
            Dedup.PeerTiebreak(peerId: o.success.remotePeerId, connTiebreak: o.success.remoteConnTiebreak)
        )
        outbound = nil
        inbound = nil

        switch verdict {
        case .survivor(let side):
            return side == .a ? .survivor(winner: o, loser: i) : .survivor(winner: i, loser: o)
        case .tie:
            let fresh = ConnTiebreakGenerator.generate()
            myConnTiebreak = fresh
            return .tieRetry(newConnTiebreak: fresh)
        }
    }

    /// - Returns: `candidate` if it is still the only registered one (no rival arrived), else `nil`.
    public func finalizeIfStillLone(_ candidate: Candidate) -> Candidate? {
        if outbound?.socket === candidate.socket, inbound == nil {
            outbound = nil
            return candidate
        }
        if inbound?.socket === candidate.socket, outbound == nil {
            inbound = nil
            return candidate
        }
        return nil
    }

    /// Drains and returns every currently-held candidate, clearing arbiter state. Used on
    /// teardown (this session's brief §9/§10): a candidate awaiting its rival or its grace
    /// period is a real open socket the transport layer is holding, and it must be closed like
    /// any other live socket rather than left to resolve on its own after the owner is gone.
    public func drainAll() -> [Candidate] {
        let held = [outbound, inbound].compactMap { $0 }
        outbound = nil
        inbound = nil
        return held
    }
}
