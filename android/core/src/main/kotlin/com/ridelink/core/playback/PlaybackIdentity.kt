package com.ridelink.core.playback

import com.ridelink.core.model.ContentHash

/**
 * The ride-segment's current playback identity: which track, and which queue entry of it.
 *
 * Deliberately **not** [PlaybackTimeline] and not a subset of it. [PlaybackTimeline] additionally
 * carries `anchorSessionUs`/`anchorPositionMs`/`generation` — values that are genuinely
 * control-generation-scoped, because they are only meaningful against the session clock that
 * produced them, and must be retired at every authentication boundary along with the rest of that
 * generation's scheduling machinery (ADR-024's own reasoning). Track and queue-item identity are
 * not: the local player keeps playing a real track through an ordinary link loss (ADR-004), so
 * "what is playing" is ride-segment truth, the same principle already applied to the capture
 * device (rule 17) and the shared queue (ADR-024 Amendment A8) — it must survive a session
 * boundary that the timeline itself must not.
 *
 * [com.ridelink.core.player.PlayerState.localEntryId] cannot answer this on its own: it identifies
 * a Phase 3 local library row, not the [ContentHash] PROTOCOL §10's `STATE_SNAPSHOT.playback`
 * needs (rule 6 — `content_hash` is the only authoritative cross-device track identity).
 */
data class PlaybackIdentity(
    val trackHash: ContentHash,
    val queueItemId: String,
)
