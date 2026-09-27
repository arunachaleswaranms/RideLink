package com.ridelink.app.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * NFR-08's Android half (ADR-029 Amendment A2): hands the rendered, redacted export to the system
 * share sheet. RideLink sends nothing itself — the user picks a target or cancels — and there is no
 * network path here.
 *
 * Each share gets a new private-cache file and therefore a new FileProvider URI. At most four
 * snapshots are retained; deleting an old file never reuses its URI for newer bytes. A file rather
 * than `EXTRA_TEXT`, because a full 1,024-event log can exceed a Binder transaction.
 */
internal object DiagnosticsShare {
    const val DIRECTORY = "diagnostics"
    private const val AUTHORITY_SUFFIX = ".diagnostics"
    private const val TITLE = "RideLink diagnostics"
    private const val MAX_EXPORTS = 4
    private const val LEGACY_FILE = "ridelink-diagnostics.txt"
    private val idPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val exportPattern = Regex("ridelink-diagnostics-${idPattern.pattern}\\.txt")

    fun write(
        cacheDir: File,
        text: String,
    ): File = write(cacheDir, text, UUID.randomUUID().toString())

    /** The ID seam makes the snapshot and path guarantees deterministic in JVM tests. */
    @Synchronized
    internal fun write(
        cacheDir: File,
        text: String,
        uniqueId: String,
    ): File {
        require(idPattern.matches(uniqueId)) { "Invalid diagnostics export ID" }
        val directory = exportDirectory(cacheDir)
        val file = File(directory, "ridelink-diagnostics-$uniqueId.txt")
        if (file.exists()) throw IOException("Diagnostics export ID already exists")

        // Keep three older snapshots so an active share sheet can still read its file. If cleanup
        // fails, do not add another file and silently exceed the bound.
        pruneOldExports(directory)

        if (!file.createNewFile()) throw IOException("Diagnostics export ID already exists")
        var written = false
        try {
            file.writeText(text)
            written = true
        } finally {
            if (!written) file.delete()
        }
        return file
    }

    private fun exportDirectory(cacheDir: File): File =
        File(cacheDir, DIRECTORY).also { directory ->
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create diagnostics cache")
        }

    private fun pruneOldExports(directory: File) {
        val files = directory.listFiles() ?: throw IOException("Cannot list diagnostics cache")
        files.filter { it.name == LEGACY_FILE }.forEach { it.deleteOrFail() }
        val exports =
            files
                .filter { it.isFile && exportPattern.matches(it.name) }
                .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
        exports.take((exports.size - MAX_EXPORTS + 1).coerceAtLeast(0)).forEach { it.deleteOrFail() }
    }

    private fun File.deleteOrFail() {
        if (!delete()) throw IOException("Cannot prune diagnostics export")
    }

    fun chooser(
        context: Context,
        file: File,
    ): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, TITLE)
                clipData = ClipData.newRawUri(TITLE, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        return Intent.createChooser(send, "Export diagnostics")
    }
}
