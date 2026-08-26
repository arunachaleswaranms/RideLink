import Foundation

/// PROTOCOL §4.2 / ADR-015. Deliberately uses `ConnTiebreak`, never `PeerId` — see
/// ADR-015 "Why conn_tiebreak and not peer_id".
///
/// ADR-015 Amendment A2 / ADR-010 Amendment A2 (this session's correction): connection ownership
/// and leadership are independent by construction. Nothing in this type may be used to infer, or
/// be inferred from, `Leadership`. **No caller may assume `initiator == leader` or
/// `acceptor == leader`.**
public enum Dedup {
    public enum Side: Sendable, Equatable {
        case a
        case b
    }

    /// Comparison is over the 32-character lowercase hex string (PROTOCOL §4.2).
    public enum Verdict: Sendable, Equatable {
        /// The connection initiated by the associated side survives; the other peer's outbound is closed.
        case survivor(Side)
        /// conn_tiebreak values are byte-identical (probability 2^-128): both sides close and retry.
        case tie
    }

    public struct PeerTiebreak: Sendable {
        public let peerId: PeerId
        public let connTiebreak: ConnTiebreak

        public init(peerId: PeerId, connTiebreak: ConnTiebreak) {
            self.peerId = peerId
            self.connTiebreak = connTiebreak
        }
    }

    /// - Returns: which side's *outbound* connection survives. The rule: the surviving connection
    ///   is the one initiated by the peer with the **larger** conn_tiebreak.
    public static func resolve(_ a: PeerTiebreak, _ b: PeerTiebreak) -> Verdict {
        if a.connTiebreak.value == b.connTiebreak.value {
            return .tie
        }
        return a.connTiebreak.value > b.connTiebreak.value ? .survivor(.a) : .survivor(.b)
    }
}

/// ADR-010. Keyed on `PeerId` alone — never on `ConnTiebreak` (see `Dedup`'s doc comment).
public enum Leadership {
    /// The lexicographically smaller peer_id leads.
    public static func elect(_ a: PeerId, _ b: PeerId) -> Dedup.Side {
        a.value < b.value ? .a : .b
    }
}
