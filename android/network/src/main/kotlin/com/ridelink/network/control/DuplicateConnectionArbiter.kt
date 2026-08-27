package com.ridelink.network.control

import com.ridelink.core.model.ConnTiebreak
import com.ridelink.core.model.PeerId
import com.ridelink.core.protocol.Dedup

/**
 * Wires ADR-015 / PROTOCOL §4.2 into real candidate sockets. A candidate socket that has
 * completed [ControlHandshake] (both `conn_tiebreak` values now known) is held here — never
 * handed to a session owner — until resolution completes, per ARCHITECTURE §4.2: "candidate
 * connections ... are held by the transport layer, not by the coordinator, so a losing socket can
 * never touch session state."
 *
 * `conn_tiebreak` is stable for the whole discovery session (ADR-015), so as soon as it is known
 * on *either* direction it resolves the outcome for *both* possible connections to this peer —
 * there is only ever at most one outbound and one inbound candidate per remote peer at a time.
 *
 * Because a rival connection can complete its handshake a moment after this one does, a candidate
 * that arrives alone is held for [GRACE_PERIOD_MS] before being declared the sole survivor —
 * short enough not to be felt as latency, long enough to catch the "both peers retry within
 * milliseconds of each other" race PROTOCOL §4.2 describes as the normal case, not the exotic one.
 */
class DuplicateConnectionArbiter(
    private val localPeerId: PeerId,
    initialConnTiebreak: ConnTiebreak,
) {
    data class Candidate(
        val socket: ControlSocket,
        val outcome: HandshakeOutcome.Success,
    )

    sealed class Result {
        /** No rival yet. Caller should wait [GRACE_PERIOD_MS] then call [finalizeIfStillLone]. */
        data class AwaitingRival(
            val candidate: Candidate,
        ) : Result()

        /** [loser] is `null` when there was never a rival to close. */
        data class Survivor(
            val winner: Candidate,
            val loser: Candidate?,
        ) : Result()

        /** conn_tiebreak values were byte-identical (2^-128): both closed, retry with [newConnTiebreak]. */
        data class TieRetry(
            val newConnTiebreak: ConnTiebreak,
        ) : Result()
    }

    @Volatile
    private var myConnTiebreak: ConnTiebreak = initialConnTiebreak
    private var outbound: Candidate? = null
    private var inbound: Candidate? = null

    val connTiebreak: ConnTiebreak get() = myConnTiebreak

    @Synchronized
    fun register(candidate: Candidate): Result {
        if (candidate.socket.isInitiator) outbound = candidate else inbound = candidate
        val o = outbound
        val i = inbound
        if (o == null || i == null) return Result.AwaitingRival(candidate)

        val verdict =
            Dedup.resolve(
                Dedup.PeerTiebreak(localPeerId, myConnTiebreak),
                Dedup.PeerTiebreak(o.outcome.remotePeerId, o.outcome.remoteConnTiebreak),
            )
        outbound = null
        inbound = null
        return when (verdict) {
            is Dedup.Verdict.Survivor ->
                if (verdict.survivorPeer == Dedup.Side.A) {
                    Result.Survivor(winner = o, loser = i)
                } else {
                    Result.Survivor(winner = i, loser = o)
                }
            Dedup.Verdict.Tie -> {
                val fresh = ConnTiebreakGenerator.generate()
                myConnTiebreak = fresh
                Result.TieRetry(fresh)
            }
        }
    }

    /** @return [candidate] if it is still the only registered one (no rival arrived), else `null`. */
    @Synchronized
    fun finalizeIfStillLone(candidate: Candidate): Candidate? {
        val isLoneOutbound = outbound === candidate && inbound == null
        val isLoneInbound = inbound === candidate && outbound == null
        return when {
            isLoneOutbound -> {
                outbound = null
                candidate
            }
            isLoneInbound -> {
                inbound = null
                candidate
            }
            else -> null
        }
    }

    /**
     * Drains and returns every currently-held candidate, clearing arbiter state. Used on teardown
     * (this session's brief §9/§10): a candidate awaiting its rival or its grace period is a real
     * open socket the transport layer is holding, and it must be closed like any other live
     * socket rather than left to resolve on its own after the owner is gone.
     */
    @Synchronized
    fun drainAll(): List<Candidate> {
        val held = listOfNotNull(outbound, inbound)
        outbound = null
        inbound = null
        return held
    }

    companion object {
        const val GRACE_PERIOD_MS = 300L
    }
}
