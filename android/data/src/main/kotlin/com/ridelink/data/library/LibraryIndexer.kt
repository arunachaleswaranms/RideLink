package com.ridelink.data.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.ridelink.core.library.DecodeStatus
import com.ridelink.core.library.IndexReconciliation
import com.ridelink.core.library.LibraryEntry
import com.ridelink.core.library.LocalTrackLocation
import com.ridelink.core.library.MetadataNormalizer
import com.ridelink.core.model.QuickId
import com.ridelink.core.model.Track
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest

/**
 * The whole indexing pipeline (this phase's brief §19): discover → validate supported type →
 * gather basic file info → [com.ridelink.core.model.QuickId] → metadata → artwork → upsert. Every
 * step that can fail does so into a [DecodeStatus], never an exception the caller has to catch —
 * "even local files are untrusted input" (brief §20) applies to every file this touches.
 *
 * Runs on whatever dispatcher the caller supplies (the `app`-layer composition root chooses
 * `Dispatchers.IO`, matching [com.ridelink.network.control.ControlSessionManager]'s convention of
 * deciding dispatchers at the composition root rather than inside a reusable class); every loop
 * checks [ensureActive] so a cancelled scan actually stops rather than merely being ignored (brief
 * §19's "cancellable").
 */
class LibraryIndexer(
    private val context: Context,
    private val repository: LibraryRepository,
    private val artworkCache: ArtworkCache,
    private val monotonicNowUs: () -> Long,
) {
    /** Primary Android import path (ARCHITECTURE §8.4): a SAF folder tree, persisted so future
     *  rescans do not need the picker again. */
    suspend fun importTree(treeUri: Uri) {
        persistPermission(treeUri)
        reconcileAndIndex(SafLibraryScanner.scanTree(context, treeUri))
    }

    /**
     * Explicit multi-select (`ACTION_OPEN_DOCUMENT`, brief §10's "multiple files import"). Unlike
     * [importTree]'s folder walk, a file picked here is indexed **regardless of extension** —
     * `test-media/synthetic/unsupported.xyz` exists exactly to prove this path answers
     * deterministically ([DecodeStatus.UNSUPPORTED]) rather than silently dropping a deliberate
     * user choice the way an incidental non-audio file in a scanned folder is dropped.
     */
    suspend fun importFiles(uris: List<Uri>) {
        val now = monotonicNowUs()
        for (uri in uris) {
            currentCoroutineContext().ensureActive()
            persistPermission(uri)
            val filename = displayNameOf(uri) ?: uri.lastPathSegment ?: uri.toString()
            val sizeBytes = sizeOf(uri) ?: 0L
            indexExplicit(DiscoveredLocation(uri.toString(), filename, sizeBytes), now)
        }
    }

    /** Secondary "whole device library" convenience (ARCHITECTURE §8.4). */
    suspend fun rescanMediaStore() {
        reconcileAndIndex(MediaStoreLibraryScanner.scan(context))
    }

    /** Background lazy pass (ADR-005): computes the authoritative [com.ridelink.core.model.ContentHash]
     *  for every row that does not have one yet, one file at a time so a cancelled pass leaves a
     *  correct, resumable partial result rather than a half-written one. */
    suspend fun completeContentHashing(entriesMissingHash: List<LibraryEntry>) {
        for (entry in entriesMissingHash) {
            currentCoroutineContext().ensureActive()
            val hash =
                runCatching {
                    ContentHashing.computeContentHash(context.contentResolver, Uri.parse(entry.location.uri))
                }.getOrNull() ?: continue
            repository.upsert(
                entry.copy(track = entry.track.copy(contentHash = hash)),
            )
        }
    }

    private suspend fun reconcileAndIndex(discovered: List<DiscoveredLocation>) {
        val now = monotonicNowUs()
        val byQuickId = LinkedHashMap<QuickId, DiscoveredLocation>()
        for (location in discovered) {
            currentCoroutineContext().ensureActive()
            val quickId =
                runCatching { ContentHashing.computeQuickId(context.contentResolver, Uri.parse(location.uri)) }.getOrNull()
                    ?: continue // unreadable: skip this scan pass rather than fabricate an identity for it
            byQuickId[quickId] = location // last-scanned location wins for a content-identical duplicate (FR-010)
        }

        val plan = IndexReconciliation.reconcile(repository.allQuickIds(), byQuickId.keys)

        for (quickId in plan.newQuickIds) {
            currentCoroutineContext().ensureActive()
            indexNew(byQuickId.getValue(quickId), quickId, now)
        }
        for (quickId in plan.stillPresentQuickIds) {
            currentCoroutineContext().ensureActive()
            repository.touchSeen(quickId, byQuickId.getValue(quickId).uri, now)
        }
        for (quickId in plan.missingQuickIds) {
            currentCoroutineContext().ensureActive()
            repository.markMissing(quickId, now)
        }
    }

    private suspend fun indexNew(
        location: DiscoveredLocation,
        quickId: QuickId,
        now: Long,
    ) {
        repository.upsert(buildEntry(location, quickId, now, MetadataExtractor.extract(context, Uri.parse(location.uri))))
    }

    private suspend fun indexExplicit(
        location: DiscoveredLocation,
        now: Long,
    ) {
        if (!AudioFormats.isSupportedExtension(location.filename)) {
            repository.upsert(placeholderEntry(location, now, DecodeStatus.UNSUPPORTED))
            return
        }
        val uri = Uri.parse(location.uri)
        val quickId = runCatching { ContentHashing.computeQuickId(context.contentResolver, uri) }.getOrNull()
        if (quickId == null) {
            repository.upsert(placeholderEntry(location, now, DecodeStatus.CORRUPT))
            return
        }
        repository.upsert(buildEntry(location, quickId, now, MetadataExtractor.extract(context, uri)))
    }

    private fun buildEntry(
        location: DiscoveredLocation,
        quickId: QuickId,
        now: Long,
        extraction: Result<ExtractedMetadata>,
    ): LibraryEntry {
        val metadata = extraction.getOrNull()
        // Measured directly on a real emulator (docs/STATUS.md's own discipline of recording what
        // was actually observed): MediaMetadataRetriever does not reliably throw for a severely
        // truncated/malformed container — `setDataSource` can succeed and every `extractMetadata`
        // call simply return null. A zero/absent duration is the signal that actually distinguishes
        // "genuinely unparseable" from "no_metadata.m4a"'s merely-untagged-but-playable case, since
        // a real container MediaMetadataRetriever could open at all reports a real duration even
        // with no title/artist/album tags present.
        val decodeStatus = if (metadata == null || metadata.durationMs <= 0L) DecodeStatus.CORRUPT else DecodeStatus.INDEXED
        val artworkRef =
            metadata
                ?.artworkBytes
                ?.let(ArtworkProcessor::processToBoundedJpeg)
                ?.let { artworkCache.store(quickId, it) }
        return LibraryEntry(
            track =
                Track(
                    // The authoritative hash is never computed on the fast indexing path (ADR-005) —
                    // completeContentHashing fills this in later, in the background.
                    contentHash = null,
                    quickId = quickId,
                    title = MetadataNormalizer.title(metadata?.title, location.filename),
                    artist = MetadataNormalizer.artist(metadata?.artist),
                    album = MetadataNormalizer.album(metadata?.album),
                    durationMs = metadata?.durationMs ?: 0L,
                    filename = location.filename,
                    codec = metadata?.codec ?: extensionOf(location.filename),
                    bitrateKbps = metadata?.bitrateKbps ?: 0,
                    artworkRef = artworkRef,
                    sizeBytes = location.sizeBytes,
                ),
            location = LocalTrackLocation(location.uri),
            decodeStatus = decodeStatus,
            indexedAtMonoUs = now,
            lastSeenAtMonoUs = now,
        )
    }

    /**
     * A row for a file that was never hashed at all — [DecodeStatus.UNSUPPORTED] (extension gate,
     * never opened) or [DecodeStatus.CORRUPT] (couldn't even read enough bytes to compute
     * [com.ridelink.core.model.QuickId]). Keyed by a hash of the URI itself, **not** a content
     * hash — documented distinctly so nothing downstream mistakes it for real content identity.
     */
    private fun placeholderEntry(
        location: DiscoveredLocation,
        now: Long,
        status: DecodeStatus,
    ): LibraryEntry =
        LibraryEntry(
            track =
                Track(
                    contentHash = null,
                    quickId = syntheticQuickIdForUri(location.uri),
                    title = MetadataNormalizer.title(null, location.filename),
                    artist = MetadataNormalizer.artist(null),
                    album = MetadataNormalizer.album(null),
                    durationMs = 0,
                    filename = location.filename,
                    codec = extensionOf(location.filename),
                    bitrateKbps = 0,
                    artworkRef = null,
                    sizeBytes = location.sizeBytes,
                ),
            location = LocalTrackLocation(location.uri),
            decodeStatus = status,
            indexedAtMonoUs = now,
            lastSeenAtMonoUs = now,
        )

    private fun syntheticQuickIdForUri(uri: String): QuickId {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(Charsets.UTF_8))
        return QuickId("sha256:" + digest.joinToString("") { "%02x".format(it) })
    }

    private fun extensionOf(filename: String): String = filename.substringAfterLast('.', missingDelimiterValue = "")

    private fun persistPermission(uri: Uri) {
        // Best-effort: a provider that does not support persistable permissions (some MediaStore
        // rows) throws here, and the file is still usable for this session either way.
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun displayNameOf(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun sizeOf(uri: Uri): Long? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
}
