package com.ridelink.core.playback

import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.PeerId

/**
 * PROTOCOL §5 / ADR-010: which side of the command-ordering split this device is on. Derived from
 * `peer_id` alone (the lexicographically smaller one leads) and **never** from who dialled, who
 * pressed play, which platform this is, or who owns the track.
 */
enum class PlaybackRole { LEADER, FOLLOWER }

/**
 * Phase 5 wire bounds. Every one is checked at parse time on both platforms and pinned by
 * `protocol/vectors/playback-messages/` and `protocol/vectors/queue-messages/`.
 */
object PlaybackBounds {
    /**
     * The largest integer a JSON number survives intact on **both** platforms. Swift decodes JSON
     * numbers through `Double`, so a `uint64` above 2^53-1 would silently round on iOS while
     * staying exact on Android — two peers disagreeing about a `command_seq` is precisely the class
     * of silent cross-platform divergence the shared vectors exist to catch. PROTOCOL §5's
     * `command_seq`, §9's `queue_revision` and every `*_session_us` are bounded here.
     *
     * The bound is generous, not restrictive: 2^53 microseconds is over 285 years of monotonic
     * uptime, and a ride would have to issue a command every microsecond for nine decades to reach
     * it as a `command_seq`.
     */
    const val MAX_WIRE_INT: Long = 9_007_199_254_740_991

    /**
     * PROTOCOL §5: `command_seq` is leader-assigned and strictly increasing, starting at 1. **Zero
     * is reserved** and means "unassigned" — it is how a follower's *intent* is distinguished from
     * the leader's *authoritative command* without adding a message type (ADR-024 §3). A receiver
     * therefore never treats 0 as an ordering value.
     */
    const val UNASSIGNED_COMMAND_SEQ: Long = 0

    const val FIRST_COMMAND_SEQ: Long = 1

    /**
     * PROTOCOL §9's queue cap, **corrected from 2 000 to 1 000** (ADR-024 §6). §9 asserted that
     * 2 000 items "cannot" overflow `MAX_CONTROL_FRAME_BYTES`; the arithmetic says otherwise —
     * ~190 encoded bytes per item x 2 000 = ~378 KB against a 256 KiB cap. 1 000 items is
     * ~190 KB, inside the 192 KiB budget §8.1 already uses for the same reason, and is far beyond
     * any queue two people build on one motorcycle. **The cap moved, not the frame limit.**
     */
    const val MAX_QUEUE_ITEMS: Int = 1_000

    /** PROTOCOL §9: sparse ordering keys, so a `QUEUE_MOVE` rarely has to renumber. */
    const val QUEUE_ORDER_STEP: Long = 1_024

    /** PROTOCOL §5: `POSITION_REPORT` every 5 s, both directions. */
    const val POSITION_REPORT_INTERVAL_MS: Long = 5_000

    /** A defensive ceiling on a reported/commanded track position: 24 hours. */
    const val MAX_POSITION_MS: Long = 86_400_000

    /** The playback-rate range the drift ladder may ever ask for, checked at parse time. */
    const val MIN_PLAYBACK_RATE: Double = 0.5
    const val MAX_PLAYBACK_RATE: Double = 2.0

    /** PROTOCOL §9's `position` vocabulary for `QUEUE_ADD`. Unknown values degrade to `end`. */
    const val QUEUE_POSITION_END = "end"
    const val QUEUE_POSITION_NEXT = "next"
    val VALID_QUEUE_POSITIONS = setOf(QUEUE_POSITION_END, QUEUE_POSITION_NEXT)
}

/**
 * The fields PROTOCOL §5 puts on **every** playback command payload. Held once, as a value, rather
 * than repeated across six message classes.
 *
 * @property commandSeq leader-assigned and strictly increasing — *the* ordering authority (never
 *   `seq`, never `msg_id`, never `sent_at_mono_us`, never `effective_at_session_us`).
 *   [PlaybackBounds.UNASSIGNED_COMMAND_SEQ] marks a follower intent.
 * @property effectiveAtSessionUs the session-clock instant the command takes audible effect.
 * @property issuedBy the `peer_id` of the user who pressed the button — UI attribution only, and
 *   never an ordering or authorisation input.
 * @property queueRevision the queue version this command assumes (PROTOCOL §5 rule 3).
 */
data class PlaybackCommandHeader(
    val commandSeq: Long,
    val effectiveAtSessionUs: Long,
    val issuedBy: PeerId,
    val queueRevision: Long,
) {
    /** True when this frame is a follower's intent rather than the leader's authoritative command. */
    val isIntent: Boolean get() = commandSeq == PlaybackBounds.UNASSIGNED_COMMAND_SEQ
}

/**
 * One decoded, bounds-checked playback message (PROTOCOL §5). `POSITION_REPORT` and
 * `PLAYBACK_STATE` are deliberately in the same family but carry **no** [PlaybackCommandHeader]:
 * neither is a command, and neither may ever outrank one (this phase's brief §31).
 */
sealed class PlaybackMessage {
    /** PROTOCOL §5 `PLAY`. [trackHash] is authoritative identity (ADR-005) — never a filename or `quick_id`. */
    data class Play(
        val header: PlaybackCommandHeader,
        val trackHash: ContentHash,
        val positionMs: Long,
        val queueItemId: String,
    ) : PlaybackMessage()

    data class Pause(
        val header: PlaybackCommandHeader,
        val positionMs: Long,
    ) : PlaybackMessage()

    /**
     * PROTOCOL §5 lists `RESUME` in the catalogue but shows no payload. ADR-024 §4 fills it in with
     * `PAUSE`'s shape — resuming from an explicit position is what lets both phones restart from the
     * same instant rather than from whatever each had drifted to while paused.
     */
    data class Resume(
        val header: PlaybackCommandHeader,
        val positionMs: Long,
    ) : PlaybackMessage()

    data class Seek(
        val header: PlaybackCommandHeader,
        val targetPositionMs: Long,
    ) : PlaybackMessage()

    data class Next(
        val header: PlaybackCommandHeader,
    ) : PlaybackMessage()

    data class Previous(
        val header: PlaybackCommandHeader,
    ) : PlaybackMessage()

    /**
     * PROTOCOL §5's drift input, sent every 5 s in both directions. Diagnostics and corrective
     * *input* only — it is not a command, has no `command_seq`, and can never supersede one.
     */
    data class PositionReport(
        val trackHash: ContentHash,
        val positionMs: Long,
        val atSessionUs: Long,
        val playing: Boolean,
        val playbackRate: Double,
    ) : PlaybackMessage()

    /**
     * PROTOCOL §5's "full authoritative snapshot the leader emits after any correction or reconnect
     * — the reconciliation anchor, not an incremental update." Its payload was likewise unspecified;
     * ADR-024 §4 derives it from §10's `STATE_SNAPSHOT.playback` so the two agree field for field.
     *
     * [trackHash] is nullable because "nothing is loaded" is a representable authoritative state.
     */
    data class PlaybackStateSnapshot(
        val commandSeq: Long,
        val queueRevision: Long,
        val trackHash: ContentHash?,
        val queueItemId: String?,
        val positionMs: Long,
        val playing: Boolean,
        val atSessionUs: Long,
    ) : PlaybackMessage()
}

/**
 * The fields PROTOCOL §9's mutation messages carry. Deliberately **not** [PlaybackCommandHeader]:
 * §9's own `QUEUE_ADD` example carries `command_seq` and `queue_revision` and nothing else, because
 * a queue mutation has no audible instant to schedule (no `effective_at_session_us`) and its
 * attribution already lives per item in `added_by`.
 *
 * [commandSeq] is the same leader-assigned ordering authority §5 defines, with the same
 * [PlaybackBounds.UNASSIGNED_COMMAND_SEQ] intent convention — one serialisation point orders
 * playback commands and queue mutations together, which is what makes "NEXT racing a QUEUE_REMOVE"
 * resolve deterministically rather than by luck.
 */
data class QueueCommandHeader(
    val commandSeq: Long,
    val queueRevision: Long,
) {
    val isIntent: Boolean get() = commandSeq == PlaybackBounds.UNASSIGNED_COMMAND_SEQ
}

/** One decoded, bounds-checked queue message (PROTOCOL §9). */
sealed class QueueMessage {
    data class Add(
        val header: QueueCommandHeader,
        val items: List<QueueAddItem>,
    ) : QueueMessage()

    data class Remove(
        val header: QueueCommandHeader,
        val queueItemIds: List<String>,
    ) : QueueMessage()

    data class Move(
        val header: QueueCommandHeader,
        val queueItemId: String,
        val toIndex: Int,
    ) : QueueMessage()

    /**
     * PROTOCOL §9's reconciliation mechanism, and in this implementation the **only** way the
     * authoritative queue reaches a follower (ADR-024 §5): the leader broadcasts a snapshot after
     * every accepted mutation and the follower adopts it wholesale. "The snapshot always wins —
     * there is no merge algorithm to get subtly wrong" is §9's own rule, taken literally.
     */
    data class Snapshot(
        val queueRevision: Long,
        val items: List<SharedQueueItem>,
        val currentIndex: Int?,
    ) : QueueMessage()
}

/**
 * One item inside a `QUEUE_ADD`. [queueItemId] is a ULID minted by the **issuer**, which is what
 * makes an add idempotent under retry (PROTOCOL §9) — and what lets the same [trackHash] appear in
 * the queue more than once as separately removable entries (this phase's brief §27).
 */
data class QueueAddItem(
    val queueItemId: String,
    val trackHash: ContentHash,
    val addedBy: PeerId,
    /** [PlaybackBounds.QUEUE_POSITION_END] or `..._NEXT`; an unknown value degrades to `end`. */
    val position: String,
)

/**
 * One slot in the replicated shared queue. Deliberately **no** `status` field: PROTOCOL §9 says in
 * the same paragraph that `status` is "derived locally from presence, never trusted from the peer",
 * so carrying it on the wire contradicted its own rule. ADR-024 §6 removes it; local availability
 * comes from `core.transfer.Availability`, as it always did.
 */
data class SharedQueueItem(
    val queueItemId: String,
    val trackHash: ContentHash,
    val addedBy: PeerId,
    val order: Long,
)
