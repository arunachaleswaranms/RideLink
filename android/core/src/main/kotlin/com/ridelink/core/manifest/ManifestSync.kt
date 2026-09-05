package com.ridelink.core.manifest

import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.ManifestId

/** PROTOCOL §8.1 `MANIFEST_BEGIN.kind`. */
enum class ManifestKind {
    FULL,
    DELTA,
    ;

    val wire: String get() = name.lowercase()

    companion object {
        fun parse(value: String): ManifestKind? = entries.firstOrNull { it.wire == value }
    }
}

/** PROTOCOL §8.1's receiver-side error codes — the only two ways an open synchronisation fails. */
enum class ManifestSyncError {
    SEQUENCE_ERROR,
    DIGEST_MISMATCH,
    INCOMPLETE,
}

/** One event delivered to [ManifestSyncStateMachine], one per PROTOCOL §8.1 wire message plus two transport events. */
sealed class ManifestSyncEvent {
    data class Begin(
        val manifestId: ManifestId,
        val kind: ManifestKind,
        val manifestRevision: Long,
        val baseRevision: Long?,
        val totalEntries: Int,
        val totalRemoved: Int,
    ) : ManifestSyncEvent()

    data class Page(
        val manifestId: ManifestId,
        val manifestRevision: Long,
        val pageIndex: Int,
        val entries: List<ManifestEntry>,
        val removed: List<ContentHash>,
    ) : ManifestSyncEvent()

    data class End(
        val manifestId: ManifestId,
        val manifestRevision: Long,
        val pageCount: Int,
        val totalEntries: Int,
        val totalRemoved: Int,
        val digest: String,
    ) : ManifestSyncEvent()

    data class Abort(
        val manifestId: ManifestId,
        val reason: String,
    ) : ManifestSyncEvent()

    /** PROTOCOL §8.1 rule 6: 10 s between consecutive frames of an open synchronisation. */
    object Timeout : ManifestSyncEvent()

    /** PROTOCOL §8.1 rule 7: staging is in-memory/session-scoped, never persisted. */
    object ControlLinkLost : ManifestSyncEvent()
}

/** The result of applying one [ManifestSyncEvent]. */
sealed class ManifestSyncStepResult {
    /** Staging advanced or an event was a harmless no-op (e.g. an abort for an id that isn't open). */
    object Continue : ManifestSyncStepResult()

    data class Aborted(
        val reason: ManifestSyncError,
    ) : ManifestSyncStepResult()

    data class Committed(
        val revision: Long,
        val entries: List<ManifestEntry>,
        val removed: List<ContentHash>,
    ) : ManifestSyncStepResult()
}

/**
 * PROTOCOL §8.1 "Receiver rules" — the manifest-sync state machine: idle -> staging -> validating
 * -> committed. Pure and mirrored, pinned by `protocol/vectors/manifest-paging-errors/`
 * (ADR-013's rule 5: nothing partial is ever promoted).
 *
 * One instance per manifest-sync **direction** per session — PROTOCOL §8.1 rule 8: at most one
 * synchronisation is open per direction, and a concurrent `MANIFEST_BEGIN` implicitly aborts
 * whichever one was open.
 */
class ManifestSyncStateMachine(
    initialRevision: Long,
) {
    var liveRevision: Long = initialRevision
        private set

    private data class OpenSync(
        val manifestId: ManifestId,
        val kind: ManifestKind,
        val revision: Long,
        var expectedPage: Int = 0,
        val stagedEntries: MutableList<ManifestEntry> = mutableListOf(),
        val stagedRemoved: MutableList<ContentHash> = mutableListOf(),
        val totalEntries: Int,
        val totalRemoved: Int,
    )

    private var open: OpenSync? = null

    val isSyncOpen: Boolean get() = open != null

    fun apply(event: ManifestSyncEvent): ManifestSyncStepResult =
        when (event) {
            is ManifestSyncEvent.Begin -> applyBegin(event)
            is ManifestSyncEvent.Page -> applyPage(event)
            is ManifestSyncEvent.End -> applyEnd(event)
            is ManifestSyncEvent.Abort -> applyAbort(event)
            ManifestSyncEvent.Timeout -> applyTimeout()
            ManifestSyncEvent.ControlLinkLost -> {
                open = null
                ManifestSyncStepResult.Continue
            }
        }

    private fun applyBegin(event: ManifestSyncEvent.Begin): ManifestSyncStepResult {
        // Rule 8: a new Begin implicitly aborts any open sync — not itself an error for the new one.
        open = null
        if (event.kind == ManifestKind.DELTA && event.baseRevision != liveRevision) {
            return ManifestSyncStepResult.Aborted(ManifestSyncError.SEQUENCE_ERROR)
        }
        open =
            OpenSync(
                manifestId = event.manifestId,
                kind = event.kind,
                revision = event.manifestRevision,
                totalEntries = event.totalEntries,
                totalRemoved = event.totalRemoved,
            )
        return ManifestSyncStepResult.Continue
    }

    @Suppress("ReturnCount")
    private fun applyPage(event: ManifestSyncEvent.Page): ManifestSyncStepResult {
        val sync = open ?: return abortAndReturn(ManifestSyncError.SEQUENCE_ERROR)
        if (event.manifestId != sync.manifestId || event.manifestRevision != sync.revision) {
            return abortAndReturn(ManifestSyncError.SEQUENCE_ERROR)
        }
        if (event.pageIndex != sync.expectedPage) {
            return abortAndReturn(ManifestSyncError.SEQUENCE_ERROR)
        }
        if (sync.kind == ManifestKind.FULL && event.removed.isNotEmpty()) {
            return abortAndReturn(ManifestSyncError.SEQUENCE_ERROR)
        }
        sync.stagedEntries.addAll(event.entries)
        sync.stagedRemoved.addAll(event.removed)
        sync.expectedPage += 1
        return ManifestSyncStepResult.Continue
    }

    @Suppress("ReturnCount")
    private fun applyEnd(event: ManifestSyncEvent.End): ManifestSyncStepResult {
        val sync = open ?: return abortAndReturn(ManifestSyncError.SEQUENCE_ERROR)
        if (event.manifestId != sync.manifestId || event.manifestRevision != sync.revision) {
            return abortAndReturn(ManifestSyncError.SEQUENCE_ERROR)
        }
        if (event.pageCount != sync.expectedPage) {
            return abortAndReturn(ManifestSyncError.SEQUENCE_ERROR)
        }
        if (event.totalEntries != sync.totalEntries ||
            event.totalRemoved != sync.totalRemoved ||
            sync.stagedEntries.size != sync.totalEntries ||
            sync.stagedRemoved.size != sync.totalRemoved
        ) {
            return abortAndReturn(ManifestSyncError.SEQUENCE_ERROR)
        }
        val expectedDigest = ManifestPaging.digest(sync.stagedEntries, sync.stagedRemoved)
        if (event.digest != expectedDigest) {
            return abortAndReturn(ManifestSyncError.DIGEST_MISMATCH)
        }
        liveRevision = sync.revision
        val result = ManifestSyncStepResult.Committed(sync.revision, sync.stagedEntries.toList(), sync.stagedRemoved.toList())
        open = null
        return result
    }

    private fun applyAbort(event: ManifestSyncEvent.Abort): ManifestSyncStepResult {
        if (open?.manifestId == event.manifestId) open = null
        // An abort for an id that isn't the open one is simply ignored (stale).
        return ManifestSyncStepResult.Continue
    }

    private fun applyTimeout(): ManifestSyncStepResult {
        if (open == null) return ManifestSyncStepResult.Continue
        return abortAndReturn(ManifestSyncError.INCOMPLETE)
    }

    private fun abortAndReturn(reason: ManifestSyncError): ManifestSyncStepResult {
        open = null
        return ManifestSyncStepResult.Aborted(reason)
    }
}
