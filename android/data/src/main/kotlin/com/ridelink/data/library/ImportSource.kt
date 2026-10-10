package com.ridelink.data.library

/**
 * Which import operation a library row belongs to — its **provenance** (STATUS §4 problem 115,
 * ADR-005 Amendment A2).
 *
 * Reconciliation used to compare one scan against *every* row, so importing folder B marked every
 * track of folder A, and every individually picked file, `MISSING`. "Not under the folder I just
 * scanned" is not "gone from this phone". A scan may now only decide the fate of rows that belong
 * to the scope it actually walked; [ScopedReconciliation] is that rule.
 *
 * Pure Kotlin on purpose (no `android.net.Uri`), so the rule is testable on the JVM. The one
 * platform-aware step — deriving a [Tree] key from a SAF URI — lives in [ImportSourceKeys].
 *
 * Three kinds, each with defined semantics:
 *
 * - [Tree] — one `ACTION_OPEN_DOCUMENT_TREE` grant. Its key is the normalized tree URI, the identity
 *   SAF itself persists (`takePersistableUriPermission`), never a display path. Rescanning a tree
 *   reconciles only that tree's rows.
 * - [MediaStore] — the device-wide `MediaStore.Audio` collection, one scope for the whole
 *   collection. It never touches a tree's or an individual file's rows. (No production UI reaches
 *   it today; the semantics are defined so that adding one cannot reintroduce problem 115.)
 * - [File] — one `ACTION_OPEN_DOCUMENT` pick. Each file is its own scope, keyed by its own URI, so
 *   an explicit import never reconciles anything but the file it names.
 */
sealed class ImportSource {
    abstract val kind: Kind
    abstract val key: String

    data class Tree(
        override val key: String,
    ) : ImportSource() {
        override val kind: Kind get() = Kind.TREE
    }

    data object MediaStore : ImportSource() {
        override val kind: Kind get() = Kind.MEDIA_STORE
        override val key: String get() = MEDIA_STORE_KEY
    }

    data class File(
        override val key: String,
    ) : ImportSource() {
        override val kind: Kind get() = Kind.FILE
    }

    /** Stored by name, the same manual-mapping discipline [com.ridelink.data.database.TrackEntity]
     *  already uses for `decodeStatus`. */
    enum class Kind { TREE, MEDIA_STORE, FILE }

    companion object {
        const val MEDIA_STORE_KEY = "mediastore"

        /** Rebuilds a stored provenance. An unknown kind name is treated as [File] — the one kind that
         *  never reconciles anything else, so a corrupt value can only ever fail safe. */
        fun of(
            kind: String,
            key: String,
        ): ImportSource =
            when (kind) {
                Kind.TREE.name -> Tree(key)
                Kind.MEDIA_STORE.name -> MediaStore
                else -> File(key)
            }
    }
}
