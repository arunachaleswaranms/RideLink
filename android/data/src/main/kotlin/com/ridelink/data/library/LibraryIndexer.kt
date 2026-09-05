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
import com.ridelink.core.model.LocalEntryId
import com.ridelink.core.model.QuickId
import com.ridelink.core.model.Track
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest
import java.util.UUID

/**
 * The whole indexing pipeline (this phase's brief §19): discover → validate supported type →
 * gather basic file info → [com.ridelink.core.model.QuickId] → metadata → artwork → upsert. Every
 * step that can fail does so into a [DecodeStatus], never an exception the caller has to catch —
 * "even local files are untrusted input" (brief §20) applies to every file this touches.
 *
 * **Identity (ADR-005 Amendment A1, this phase's closure-audit CRITICAL finding):** every row's real
 * identity is a freshly-generated [LocalEntryId] — [newLocalEntryId] by default, overridable only for
 * deterministic tests. Reconciliation and every repository write below are keyed by
 * [LocalTrackLocation]/[LocalEntryId], never by [QuickId]: `QuickId` samples only size plus the
 * first/last 64 KiB, so two genuinely different files over 128 KiB can share one, and using it as
 * cross-row identity used to let that silently collapse two different files into one row.
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
    private val newLocalEntryId: () -> LocalEntryId = { LocalEntryId(UUID.randomUUID().toString()) },
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

    /**
     * ADR-005's background lazy pass: computes the authoritative
     * [com.ridelink.core.model.ContentHash] for every row that does not have one yet, one file at a
     * time so a cancelled pass leaves a correct, resumable partial result rather than a half-written
     * one. Reads the current set of not-yet-hashed rows directly from the repository — never a
     * possibly-stale caller-supplied snapshot (this phase's closure-audit hardening pass) — so a
     * fresh call always resumes exactly the rows still missing a hash, whether that is because a
     * previous pass was cancelled or because new tracks were imported since.
     */
    suspend fun completeContentHashing() {
        for (entry in repository.entriesMissingContentHash()) {
            currentCoroutineContext().ensureActive()
            val hash =
                runCatching {
                    ContentHashing.computeContentHash(context.contentResolver, Uri.parse(entry.location.uri))
                }.getOrNull() ?: continue
            repository.updateContentHash(entry.localEntryId, hash)
        }
    }

    private suspend fun reconcileAndIndex(discovered: List<DiscoveredLocation>) {
        val now = monotonicNowUs()
        val byLocation = LinkedHashMap<LocalTrackLocation, DiscoveredLocation>()
        val quickIdByLocation = LinkedHashMap<LocalTrackLocation, QuickId>()
        for (location in discovered) {
            currentCoroutineContext().ensureActive()
            val quickId =
                runCatching { ContentHashing.computeQuickId(context.contentResolver, Uri.parse(location.uri)) }.getOrNull()
                    ?: continue // unreadable: skip this scan pass rather than fabricate an identity for it
            val loc = LocalTrackLocation(location.uri)
            byLocation[loc] = location
            quickIdByLocation[loc] = quickId
        }

        val plan = IndexReconciliation.reconcile(repository.allLocationsAndQuickIds(), quickIdByLocation)

        // New and changed locations both go through the same full pipeline; the only difference —
        // whether an existing row's LocalEntryId is preserved or a fresh one is generated — is
        // decided inside indexOrReindex by looking the location up, never by which bucket it came
        // from (this keeps a race between this scan and a concurrent one safe: at worst a location
        // is re-read once more than strictly necessary, never double-inserted, since locationUri is
        // UNIQUE at the schema level).
        for (loc in plan.newLocations + plan.changedLocations) {
            currentCoroutineContext().ensureActive()
            indexOrReindex(byLocation.getValue(loc), quickIdByLocation.getValue(loc), now)
        }
        for (loc in plan.unchangedLocations) {
            currentCoroutineContext().ensureActive()
            repository.touchSeen(loc, now)
        }
        for (loc in plan.missingLocations) {
            currentCoroutineContext().ensureActive()
            repository.markMissing(loc, now)
        }
    }

    private suspend fun indexExplicit(
        location: DiscoveredLocation,
        now: Long,
    ) {
        if (!AudioFormats.isSupportedExtension(location.filename)) {
            upsertPlaceholder(location, now, DecodeStatus.UNSUPPORTED)
            return
        }
        val uri = Uri.parse(location.uri)
        val quickId = runCatching { ContentHashing.computeQuickId(context.contentResolver, uri) }.getOrNull()
        if (quickId == null) {
            upsertPlaceholder(location, now, DecodeStatus.CORRUPT)
            return
        }
        indexOrReindex(location, quickId, now)
    }

    /**
     * Inserts a never-before-seen [location] or, if this exact [LocalTrackLocation] is already
     * known (an in-place edit reconciliation already detected, or a re-import of the same explicit
     * pick), updates that row in place — preserving its [LocalEntryId], never creating a second row
     * for the same location and never deciding identity from [quickId].
     */
    private suspend fun indexOrReindex(
        location: DiscoveredLocation,
        quickId: QuickId,
        now: Long,
    ) {
        val existing = repository.findByLocationUri(location.uri)
        val entry =
            buildEntry(
                existing?.localEntryId ?: newLocalEntryId(),
                location,
                quickId,
                now,
                MetadataExtractor.extract(context, Uri.parse(location.uri)),
            )
        if (existing == null) repository.insertNew(entry) else repository.updateReindexed(entry)
    }

    private suspend fun upsertPlaceholder(
        location: DiscoveredLocation,
        now: Long,
        status: DecodeStatus,
    ) {
        val existing = repository.findByLocationUri(location.uri)
        val entry = placeholderEntry(existing?.localEntryId ?: newLocalEntryId(), location, now, status)
        if (existing == null) repository.insertNew(entry) else repository.updateReindexed(entry)
    }

    private fun buildEntry(
        localEntryId: LocalEntryId,
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
                ?.let { artworkCache.store(localEntryId, it) }
        return LibraryEntry(
            localEntryId = localEntryId,
            track =
                Track(
                    // The authoritative hash is never computed on the fast indexing path (ADR-005) —
                    // completeContentHashing fills this in later, in the background. An in-place edit
                    // resets it to unknown too (the old hash no longer describes the current bytes).
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
     * [com.ridelink.core.model.QuickId]). Its `quickId` column is keyed by a hash of the URI itself,
     * **not** a content hash — documented distinctly so nothing downstream mistakes it for real
     * content identity; its real identity is still [localEntryId], exactly like every other row.
     */
    private fun placeholderEntry(
        localEntryId: LocalEntryId,
        location: DiscoveredLocation,
        now: Long,
        status: DecodeStatus,
    ): LibraryEntry =
        LibraryEntry(
            localEntryId = localEntryId,
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
