package com.ridelink.core.resync

import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.PeerId
import com.ridelink.core.model.TransferId
import com.ridelink.core.playback.SharedQueueItem

/**
 * PROTOCOL §3's Resync group and §10: `STATE_REQUEST` / `STATE_SNAPSHOT`. A follower asks for
 * authoritative state after a reconnect or a detected desynchronisation; the leader alone answers,
 * because only the leader holds authoritative `command_seq`/`queue_revision` (ADR-010) — the same
 * asymmetry PROTOCOL §5/§9 already enforce for every other authoritative frame.
 *
 * Bounds live here rather than duplicated in the codec, matching [com.ridelink.core.playback.PlaybackBounds].
 */
object ResyncBounds {
    /**
     * A defensive ceiling on `transfers_in_flight`, in the same spirit as
     * [com.ridelink.core.playback.PlaybackBounds.MAX_QUEUE_ITEMS]: V1 runs at most one active
     * transfer at a time per role (ADR-023 §1's "at most one live bulk listener"), so this is
     * generous headroom rather than a realistic count.
     */
    const val MAX_TRANSFERS_IN_FLIGHT: Int = 64
}

/**
 * The playback half of a `STATE_SNAPSHOT` (PROTOCOL §10). Deliberately **not**
 * [com.ridelink.core.playback.PlaybackMessage.PlaybackStateSnapshot]: that type also carries
 * `command_seq`/`queue_revision`, which `STATE_SNAPSHOT` states once at the envelope level instead
 * (PROTOCOL §5's own cross-reference — "[`PLAYBACK_STATE`'s] shape is §10's `STATE_SNAPSHOT.playback`
 * plus the two ordering values an anchor needs" — is what fixes the shape here; see ADR-028).
 *
 * [trackHash] and [queueItemId] are nullable together: "nothing is loaded" is a representable
 * authoritative state, exactly as it is for `PLAYBACK_STATE`.
 */
data class ResyncPlaybackSnapshot(
    val trackHash: ContentHash?,
    val queueItemId: String?,
    val positionMs: Long,
    val playing: Boolean,
    val atSessionUs: Long,
)

/** One entry of `STATE_SNAPSHOT.transfers_in_flight`. Informational only — V1 never resumes one. */
data class ResyncTransferInFlight(
    val transferId: TransferId,
    val contentHash: ContentHash,
    val bytesDone: Long,
)

/** One decoded, bounds-checked resync message (PROTOCOL §10). */
sealed class ResyncMessage {
    /** PROTOCOL §10: a follower's request for authoritative state. Carries no payload. */
    object StateRequest : ResyncMessage()

    /**
     * PROTOCOL §10: "the authoritative reconciliation payload." The leader's answer, and the only
     * authority — "no merge algorithm."
     *
     * @property playback `null` only when the leader has never had a synchronised timeline this
     *   session; a leader that *has* one always reports it, `track_hash: null` included, so a
     *   follower can tell "the leader has nothing loaded" from "the leader said nothing yet."
     */
    data class StateSnapshot(
        val leaderPeerId: PeerId,
        val commandSeq: Long,
        val queueRevision: Long,
        val playback: ResyncPlaybackSnapshot?,
        val queueItems: List<SharedQueueItem>,
        val queueCurrentIndex: Int?,
        val manifestRevision: Long,
        val transfersInFlight: List<ResyncTransferInFlight>,
    ) : ResyncMessage()
}
