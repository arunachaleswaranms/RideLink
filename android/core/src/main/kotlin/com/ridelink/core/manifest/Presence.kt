package com.ridelink.core.manifest

import com.ridelink.core.model.ContentHash

/**
 * ARCHITECTURE §8.2's static presence classification: does *this* phone have a `content_hash`
 * locally, does the connected peer's manifest advertise it, or both. The transfer-in-progress
 * states (`TRANSFER_PENDING`/`TRANSFERRING`/`TRANSFER_FAILED`) come from
 * [com.ridelink.core.transfer.TransferReducer] instead — a set-membership question and a
 * state-machine question are different pure functions, not one type trying to be both.
 *
 * Classification and delta below are keyed on [ContentHash] equality **only**. `QuickId` never
 * participates (ADR-005 Amendment A1) — two entries sharing a `quick_id` but differing in
 * `content_hash` are always classified or diffed independently.
 */
object Presence {
    enum class Classification { LOCAL_ONLY, PEER_ONLY, BOTH }

    fun classify(
        localHashes: Set<ContentHash>,
        peerHashes: Set<ContentHash>,
    ): Map<ContentHash, Classification> {
        val all = localHashes + peerHashes
        return all.associateWith { h ->
            when {
                h in localHashes && h in peerHashes -> Classification.BOTH
                h in localHashes -> Classification.LOCAL_ONLY
                else -> Classification.PEER_ONLY
            }
        }
    }
}

/**
 * PROTOCOL §8.1's delta computation: given an old and a new full manifest snapshot, the `added`
 * entries and `removed` content_hash values a sender would place in a `kind: "delta"` sequence.
 * A metadata-only change on the same `content_hash` is neither an add nor a remove.
 */
object ManifestDelta {
    data class Delta(
        val added: List<ManifestEntry>,
        val removed: List<ContentHash>,
    )

    fun compute(
        old: List<ManifestEntry>,
        new: List<ManifestEntry>,
    ): Delta {
        val oldByHash = old.filter { it.contentHash != null }.associateBy { it.contentHash!! }
        val newByHash = new.filter { it.contentHash != null }.associateBy { it.contentHash!! }
        val added = new.filter { it.contentHash != null && it.contentHash !in oldByHash }
        // Sorted (by the wire string, not insertion order) — matches
        // tools/generate_manifest_vectors.py's `sorted(...)`, since removal order carries no
        // meaning of its own and a stable, comparable order keeps the vector deterministic.
        val removed = oldByHash.keys.filter { it !in newByHash }.sortedBy { it.value }
        return Delta(added, removed)
    }
}
