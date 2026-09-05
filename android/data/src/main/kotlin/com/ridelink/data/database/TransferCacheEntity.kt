package com.ridelink.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * ADR-023 §6 — one row per verified, promoted Phase 4 transfer cache entry.
 *
 * Deliberately its own table, never merged into [TrackEntity] (brief §16/§18): a peer's manifest
 * disappearing after disconnect, or a later cache eviction, must never delete or shadow a track
 * the user actually imported. [contentHash] (the full `"sha256:..."` wire string) is the primary
 * key — never `quickId`, never a filename (ADR-023 §8, ADR-005 Amendment A1).
 *
 * [cacheFileName] is a filesystem-safe basename **derived from [contentHash] itself**, never from
 * a remote-supplied filename (brief §12) — the remote `filename` a manifest entry carries is
 * display metadata only and never reaches a path. [verified] is set exactly once, at atomic
 * promotion (ADR-023 §6); the only way to unset it is to delete the row entirely.
 */
@Entity(tableName = "transfer_cache")
data class TransferCacheEntity(
    @PrimaryKey val contentHash: String,
    val cacheFileName: String,
    val sizeBytes: Long,
    val verified: Boolean,
    val verifiedAtMonoUs: Long,
    val lastAccessAtMonoUs: Long,
)
