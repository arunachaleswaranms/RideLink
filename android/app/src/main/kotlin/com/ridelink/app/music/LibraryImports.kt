package com.ridelink.app.music

import com.ridelink.data.library.ImportProgress
import com.ridelink.data.library.LibraryIndexer
import com.ridelink.data.library.PreparingProgress
import com.ridelink.data.library.TreeScan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.net.Uri as PlatformUri

/**
 * The one owner of library imports and their progress (Phase 9A.5 §8/§9).
 *
 * A folder import is two steps. [importTree] walks the folder and writes nothing; the UI shows the
 * summary ([ImportProgress.AwaitingConfirmation]: folder, track count, subfolders included, any
 * recording-like folders) and the user either [confirm]s or [cancel]s. Only a confirmed scan is
 * indexed. An explicit file pick is indexed straight away — the user already chose each file.
 *
 * Every step runs on [LibraryIndexer]'s IO dispatcher; this class only holds the job and publishes
 * [progress]. One import at a time: a request while another is walking or indexing is ignored, and
 * the screens disable their import buttons while [busy] says so. [cancel] cancels the running job,
 * and the indexer's own `ensureActive` checks make that prompt; whatever was written before the
 * cancellation is a valid partial library, and the next import of the same folder finishes it.
 *
 * ADR-005's content-hash pass is reported separately in [preparing]: it is not part of any one
 * import, it also resumes at launch, and it never holds an import's result back.
 */
class LibraryImports(
    private val indexer: LibraryIndexer,
    private val scope: CoroutineScope,
) {
    private val _progress = MutableStateFlow<ImportProgress>(ImportProgress.Idle)
    val progress: StateFlow<ImportProgress> = _progress.asStateFlow()

    private val _preparing = MutableStateFlow(PreparingProgress(0, 0))
    val preparing: StateFlow<PreparingProgress> = _preparing.asStateFlow()

    private var job: Job? = null
    private var pendingScan: TreeScan? = null
    private var hashingJob: Job? = null

    /** True while a walk or an index pass is running — not while a summary waits for the user. */
    val busy: Boolean get() = job?.isActive == true

    fun importTree(treeUri: PlatformUri) {
        if (busy) return
        pendingScan = null
        _progress.value = ImportProgress.Scanning(folderName = "", found = 0)
        job =
            scope.launch {
                track(folderName = null) {
                    val scan = indexer.scanTree(treeUri) { _progress.value = it }
                    pendingScan = scan
                    _progress.value = ImportProgress.AwaitingConfirmation(scan.folderName, scan.discovered.size, scan.recordingFolders)
                }
            }
    }

    /** Indexes the scan the user just confirmed. [skipRecordings] leaves recording-like folders out. */
    fun confirm(skipRecordings: Boolean) {
        val scan = pendingScan ?: return
        if (busy) return
        pendingScan = null
        job =
            scope.launch {
                track(scan.folderName) {
                    indexer.importScan(scan, skipRecordings) { _progress.value = it }
                    completeContentHashingInBackground()
                }
            }
    }

    fun importFiles(uris: List<PlatformUri>) {
        if (busy || uris.isEmpty()) return
        pendingScan = null
        job =
            scope.launch {
                track(folderName = null) {
                    indexer.importFiles(uris) { _progress.value = it }
                    completeContentHashingInBackground()
                }
            }
    }

    /** Stops a running walk or index pass, or discards a summary nobody confirmed. */
    fun cancel() {
        pendingScan = null
        val running = job
        if (running?.isActive == true) {
            running.cancel()
        } else {
            _progress.value = ImportProgress.Cancelled
        }
    }

    /** Clears a finished, failed or cancelled status once the user has seen it. */
    fun dismiss() {
        if (busy || _progress.value is ImportProgress.AwaitingConfirmation) return
        _progress.value = ImportProgress.Idle
    }

    /**
     * Fills in the authoritative hash for every row still missing one — the ADR-005 background
     * pass. Safe to call repeatedly: [LibraryIndexer.completeContentHashing] re-queries the
     * repository on every call, so it always resumes exactly the rows a previous, possibly-cancelled
     * pass had not reached. [hashingJob] only prevents a redundant *concurrent* pass.
     */
    fun completeContentHashingInBackground() {
        if (hashingJob?.isActive == true) return
        hashingJob = scope.launch { indexer.completeContentHashing { _preparing.value = it } }
    }

    private suspend fun track(
        folderName: String?,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            _progress.value = ImportProgress.Cancelled
            throw cancelled
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Exception,
        ) {
            // The indexer turns every per-file failure into a DecodeStatus; what reaches here is a
            // whole-pass failure (a revoked grant, a full disk). Shown, never retried silently.
            _progress.value = ImportProgress.Failed(folderName, failure.javaClass.simpleName)
        }
    }
}
