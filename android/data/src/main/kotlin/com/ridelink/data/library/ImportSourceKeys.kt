package com.ridelink.data.library

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore

/**
 * The one place an [ImportSource] is derived from a platform [Uri].
 *
 * Tree keys come from SAF's own structure (`DocumentsContract.getTreeDocumentId` and
 * `buildTreeDocumentUri`), never from a display path, so the key recorded when a tree is picked and
 * the key recovered from one of its children's document URIs are the same string.
 */
object ImportSourceKeys {
    /** The scope of an `ACTION_OPEN_DOCUMENT_TREE` result. A non-tree URI (a plain directory in a
     *  test) is its own key. */
    fun forTree(treeUri: Uri): ImportSource.Tree = ImportSource.Tree(normalizedTreeKey(treeUri) ?: treeUri.toString())

    /**
     * Best-effort provenance for a row that predates provenance (schema version 2 → 3): a child of a
     * SAF tree belongs to that tree, a `content://media/...` row to [ImportSource.MediaStore], and
     * anything else to itself as an individual [ImportSource.File] — the one kind that never
     * reconciles another row, so a URI this cannot classify fails safe.
     */
    fun forExistingLocation(locationUri: String): ImportSource {
        val uri = runCatching { Uri.parse(locationUri) }.getOrNull()
        val treeKey = uri?.let(::normalizedTreeKey)
        return when {
            treeKey != null -> ImportSource.Tree(treeKey)
            uri?.authority == MediaStore.AUTHORITY -> ImportSource.MediaStore
            else -> ImportSource.File(locationUri)
        }
    }

    private fun normalizedTreeKey(uri: Uri): String? =
        runCatching {
            if (uri.scheme != "content" || uri.authority == null || !DocumentsContract.isTreeUri(uri)) return null
            DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri)).toString()
        }.getOrNull()
}
