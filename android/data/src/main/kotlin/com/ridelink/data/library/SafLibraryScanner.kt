package com.ridelink.data.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Recursive [DocumentFile] traversal of a user-granted SAF tree (`ACTION_OPEN_DOCUMENT_TREE`) —
 * ARCHITECTURE §8.4's primary, always-available Android import path, needing no runtime permission
 * at all (this phase's brief §10's "minimize permissions").
 *
 * Files with an unsupported extension are silently excluded from the walk, not classified
 * [com.ridelink.core.library.DecodeStatus.UNSUPPORTED] — a folder full of a rider's other documents
 * should not flood the music library with entries for them. A file the user *explicitly* picks via
 * [LibraryIndexer.importFiles] gets the opposite treatment: it is always classified, precisely
 * because it was a deliberate choice, not incidental folder contents (see that function's KDoc for
 * the `unsupported.xyz` fixture case this distinction exists for).
 */
object SafLibraryScanner {
    /** The production entry point: [treeUri] is a real SAF tree from `ACTION_OPEN_DOCUMENT_TREE`. */
    suspend fun scanTree(
        context: Context,
        treeUri: Uri,
    ): List<DiscoveredLocation> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        return scanTree(root)
    }

    /**
     * The testable entry point: any [DocumentFile] directory, including one built from a plain
     * [java.io.File] via `DocumentFile.fromFile` — which is how `LibraryIndexerTest` exercises this
     * exact recursive-walk/extension-gate logic on the real filesystem without needing the system
     * folder picker (brief §26's "real pipeline, not real picker UI" distinction).
     */
    suspend fun scanTree(root: DocumentFile): List<DiscoveredLocation> {
        val results = mutableListOf<DiscoveredLocation>()
        walk(root, results)
        return results
    }

    private suspend fun walk(
        dir: DocumentFile,
        into: MutableList<DiscoveredLocation>,
    ) {
        currentCoroutineContext().ensureActive() // brief §19: indexing must be cancellable
        // A permission revoked mid-walk (brief §10) surfaces as listFiles() returning empty or
        // throwing a SecurityException on this subtree; either way the walk simply does not descend
        // further into it rather than crashing the whole scan.
        val children = runCatching { dir.listFiles() }.getOrElse { emptyArray() }
        for (child in children) {
            currentCoroutineContext().ensureActive()
            when {
                child.isDirectory -> walk(child, into)
                child.isFile -> {
                    val name = child.name ?: continue
                    if (AudioFormats.isSupportedExtension(name)) {
                        into += DiscoveredLocation(uri = child.uri.toString(), filename = name, sizeBytes = child.length())
                    }
                }
            }
        }
    }
}
