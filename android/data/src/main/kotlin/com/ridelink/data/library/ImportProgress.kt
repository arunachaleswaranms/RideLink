package com.ridelink.data.library

/**
 * What an import is doing right now, in terms a person can read (Phase 9A.5 §8).
 *
 * A 3,460-track folder took about eight minutes on the OnePlus Nord 5 and the app showed nothing at
 * all while it ran. Every number here is one the indexer actually knows at that moment: a walk does
 * not know how many files it will find, so [Scanning] reports a running count and never a
 * percentage; [Indexing] has a real total because the walk has finished.
 *
 * Content hashing ([PreparingProgress]) is not part of an import's own lifetime — it is ADR-005's background
 * pass, which also runs at launch — so it is reported separately and never blocks [Complete].
 * Nothing here names a `quickId`, a content hash or a URI.
 */
sealed interface ImportProgress {
    data object Idle : ImportProgress

    /** Walking the folder. [found] counts supported audio files so far. */
    data class Scanning(
        val folderName: String,
        val found: Int,
    ) : ImportProgress

    /**
     * The walk finished and nothing has been written yet. The user sees what will be imported — the
     * folder, the track count, subfolders included — and any [recordingFolders], and confirms.
     * [skipRecordingsSuggested] is only a default for the summary's switch; the user decides.
     */
    data class AwaitingConfirmation(
        val folderName: String,
        val trackCount: Int,
        val recordingFolders: List<RecordingFolder>,
    ) : ImportProgress {
        val recordingTrackCount: Int get() = recordingFolders.sumOf { it.trackCount }
        val skipRecordingsSuggested: Boolean get() = recordingFolders.isNotEmpty()
    }

    /** [done] of [total], both exact, within one [stage]. */
    data class Indexing(
        val folderName: String?,
        val stage: Stage,
        val done: Int,
        val total: Int,
    ) : ImportProgress

    /** An import makes two passes, and says which one it is in rather than blending them into one
     *  invented percentage: a cheap check of every file found, then a full read of the new ones. */
    enum class Stage {
        /** Identifying each file found ([com.ridelink.core.model.QuickId]); `total` is every file. */
        CHECKING,

        /** Reading metadata and artwork; `total` is only the files that are new or changed. */
        READING,
    }

    data class Complete(
        val folderName: String?,
        val trackCount: Int,
        val added: Int,
        val missing: Int,
    ) : ImportProgress

    /** [reason] is the failure's type name, for diagnostics; the UI does not show it. */
    data class Failed(
        val folderName: String?,
        val reason: String,
    ) : ImportProgress

    data object Cancelled : ImportProgress
}

/** ADR-005's background content-hash pass, reported apart from any import. */
data class PreparingProgress(
    val done: Int,
    val total: Int,
) {
    val active: Boolean get() = total > 0 && done < total
}
