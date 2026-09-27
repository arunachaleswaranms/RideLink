package com.ridelink.app.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.ridelink.core.logging.DiagnosticsExport
import java.io.File

/**
 * NFR-08's Android half (ADR-029 Amendment A2): hands the rendered, redacted export to the system
 * share sheet. RideLink sends nothing itself — the user picks a target or cancels — and there is no
 * network path here.
 *
 * The text goes to **one** file in the app's private cache, overwritten by every export, exposed
 * only through the non-exported FileProvider declared in the manifest, with a read grant scoped to
 * the one share intent. A file rather than `EXTRA_TEXT`, because a full 1,024-event log can exceed
 * what a Binder transaction carries.
 */
internal object DiagnosticsShare {
    const val DIRECTORY = "diagnostics"
    private const val AUTHORITY_SUFFIX = ".diagnostics"
    private const val TITLE = "RideLink diagnostics"

    fun write(
        cacheDir: File,
        text: String,
    ): File {
        val directory = File(cacheDir, DIRECTORY).apply { mkdirs() }
        return File(directory, DiagnosticsExport.FILE_NAME).apply { writeText(text) }
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
