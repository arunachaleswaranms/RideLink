package com.ridelink.core.protocol

import com.ridelink.core.model.ConnTiebreak
import com.ridelink.core.model.PeerId

/**
 * PROTOCOL §4.2 / ADR-015. Deliberately uses [ConnTiebreak], never [PeerId] — see
 * ADR-015 "Why conn_tiebreak and not peer_id".
 *
 * ADR-015 Amendment A2 / ADR-010 Amendment A2 (this session's correction): connection ownership
 * and leadership are independent by construction. Nothing in this object may be used to infer,
 * or be inferred from, [Leadership]. **No caller may assume `initiator == leader` or
 * `acceptor == leader`.**
 */
object Dedup {
    /** Comparison is over the 32-character lowercase hex string (PROTOCOL §4.2). */
    sealed class Verdict {
        /** The connection initiated by [survivorPeer] survives; the other peer's outbound is closed. */
        data class Survivor(
            val survivorPeer: Side,
        ) : Verdict()

        /** conn_tiebreak values are byte-identical (probability 2^-128): both sides close and retry. */
        object Tie : Verdict()
    }

    enum class Side { A, B }

    data class PeerTiebreak(
        val peerId: PeerId,
        val connTiebreak: ConnTiebreak,
    )

    /**
     * @return which side's *outbound* connection survives. The rule: the surviving connection is
     *   the one initiated by the peer with the **larger** conn_tiebreak.
     */
    fun resolve(
        a: PeerTiebreak,
        b: PeerTiebreak,
    ): Verdict {
        val cmp = a.connTiebreak.value.compareTo(b.connTiebreak.value)
        return when {
            cmp == 0 -> Verdict.Tie
            cmp > 0 -> Verdict.Survivor(Side.A)
            else -> Verdict.Survivor(Side.B)
        }
    }
}

/** ADR-010. Keyed on [PeerId] alone — never on [ConnTiebreak] (see [Dedup]'s doc comment). */
object Leadership {
    /** The lexicographically smaller peer_id leads. */
    fun elect(
        a: PeerId,
        b: PeerId,
    ): Dedup.Side = if (a.value < b.value) Dedup.Side.A else Dedup.Side.B
}
