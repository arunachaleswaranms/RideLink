package com.ridelink.core.voice

import com.ridelink.core.protocol.VoiceBounds
import com.ridelink.core.protocol.VoiceSessionId

/** One remote ICE candidate, held until the remote description makes it applicable. */
data class RemoteCandidate(
    val voiceSessionId: VoiceSessionId,
    val candidate: String,
    val sdpMid: String?,
    val sdpMlineIndex: Int,
)

/**
 * PROTOCOL §7.4's bounded trickle-ICE queue.
 *
 * Trickle ICE means a candidate can arrive before the remote description that makes it applicable,
 * so early candidates must be *held* rather than dropped — dropping them turns a slow SDP round
 * trip into what looks like a connectivity failure. But the peer decides how many arrive, so an
 * unbounded hold is a memory amplifier under a remote peer's control.
 *
 * The compromise, and the reason it is a type rather than a bare list:
 *
 * - capacity is [VoiceBounds.MAX_QUEUED_CANDIDATES];
 * - at capacity the **oldest** candidate is discarded, because the newest are the ones most likely
 *   to still describe a reachable path;
 * - every discard is **counted** ([droppedCount]) and surfaced in the voice diagnostics. Silent
 *   truncation would read as "we saw everything the peer sent", which is exactly the wrong thing to
 *   believe while debugging a ride.
 *
 * Pure and mutable-but-local: no clock, no I/O, no platform type. `RideLinkCore.PendingCandidates`
 * is the mirror.
 */
class PendingCandidates(
    private val capacity: Int = VoiceBounds.MAX_QUEUED_CANDIDATES,
) {
    private val queue = ArrayDeque<RemoteCandidate>()

    var droppedCount: Int = 0
        private set

    val size: Int get() = queue.size

    /** @return true if the candidate was held, false if it displaced an older one to fit. */
    fun offer(candidate: RemoteCandidate): Boolean {
        val fitted = queue.size < capacity
        if (!fitted) {
            queue.removeFirst()
            droppedCount += 1
        }
        queue.addLast(candidate)
        return fitted
    }

    /**
     * Removes and returns everything queued for [voiceSessionId], in arrival order, and discards
     * anything queued for any other generation.
     *
     * Draining is generation-scoped for the same reason PROTOCOL §7.2 exists: a candidate held from
     * a negotiation that has since been torn down must not be applied to the next one, and the
     * moment the queue is drained is the last chance to make sure of it.
     */
    fun drain(voiceSessionId: VoiceSessionId): List<RemoteCandidate> {
        val matching = queue.filter { it.voiceSessionId == voiceSessionId }
        queue.clear()
        return matching
    }

    fun clear() {
        queue.clear()
    }

    /** Resets the counter as well — used when a whole voice session ends, not between negotiations. */
    fun reset() {
        queue.clear()
        droppedCount = 0
    }
}
