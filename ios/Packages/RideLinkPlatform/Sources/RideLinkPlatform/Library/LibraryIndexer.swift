import CryptoKit
import Foundation
import RideLinkCore

/// A file the document picker returned, before hashing or metadata extraction — deliberately not a
/// domain type (`LibraryEntry` requires a `Track`, which needs a hash this stage does not have yet).
/// Mirrors `com.ridelink.data.library.DiscoveredLocation`.
struct DiscoveredLocation {
    let sourceUrl: URL
    let filename: String
}

/// The whole indexing pipeline (this phase's brief §19): discover → validate supported type →
/// look up any existing row for this location → copy into the app's own container → `QuickId` →
/// metadata → artwork → insert/update. Every step that can fail does so into a `DecodeStatus`, never
/// a thrown error the caller has to catch — "even local files are untrusted input" (brief §20)
/// applies to every file this touches.
///
/// **Identity (ADR-005 Amendment A1, this phase's closure-audit CRITICAL finding):** every row's real
/// identity is a freshly-generated `LocalEntryId` — `newLocalEntryId` by default, overridable only for
/// deterministic tests — preserved across a reimport of the same location, never recomputed from
/// content. `location.uri` (`LibraryEntry.location`, stored in the `locationUri` column) is the
/// **source URL** the picker/enumerator returned, not the app-container destination path: it is what
/// `LibraryRepository.findByLocationUri` looks up to decide "have we already imported this exact
/// pick?", exactly the role Android's persisted `content://` URI plays as the schema's `UNIQUE`
/// location key. The app-container destination filename is derived from `LocalEntryId` instead
/// (`resolvedUrl(for:)`) — **never from `QuickId`**, which is not guaranteed unique across rows: two
/// genuinely different files over 128 KiB can share one, and deriving a destination filename from it
/// used to mean a colliding file's *bytes were never even copied* into the sandbox, because import
/// skipped copying to a destination that already existed. A reimport of the same source location now
/// always removes and recopies its destination file in place, never skip-if-exists.
///
/// **A real, deliberate divergence from `com.ridelink.data.library.LibraryIndexer`, not an
/// oversight**: Android's SAF import keeps a persisted `content://` permission and re-scans the
/// *original* location every time, so a file removed from that location is detected as
/// `DecodeStatus.missing` (`IndexReconciliation`). iOS never does that — ADR-009 says "no reliance
/// on an external security-scoped URL indefinitely", so every picked file is copied into this app's
/// own `Application Support` directory on import and the security-scoped source URL is never touched
/// again. There is therefore no live external reference whose disappearance this indexer could
/// detect; `IndexReconciliation`'s `missingLocations` path (used by Android's `SafLibraryScanner`) has
/// no iOS caller for that reason, not because it was forgotten. Re-importing the same *source
/// location* is a no-op update that preserves identity — the same de-duplication-of-the-same-pick
/// FR-010 requires on both platforms — but Phase 3 does **not** collapse two different source
/// locations that happen to share a `QuickId` or even a `ContentHash`; that is Phase 4/5 transfer
/// scope (ADR-005 Amendment A1).
public final class LibraryIndexer: Sendable {
    private let repository: LibraryRepository
    private let artworkCache: ArtworkCache
    private let musicDirectory: URL
    private let monotonicNowUs: @Sendable () -> Int64
    private let newLocalEntryId: @Sendable () -> LocalEntryId

    public init(
        repository: LibraryRepository,
        artworkCache: ArtworkCache,
        musicDirectory: URL,
        monotonicNowUs: @escaping @Sendable () -> Int64,
        newLocalEntryId: @escaping @Sendable () -> LocalEntryId = { LocalEntryId(UUID().uuidString.lowercased()) }
    ) {
        self.repository = repository
        self.artworkCache = artworkCache
        self.musicDirectory = musicDirectory
        self.monotonicNowUs = monotonicNowUs
        self.newLocalEntryId = newLocalEntryId
    }

    /// Explicit multi-select (`UIDocumentPickerViewController`, brief §10's "multiple files import").
    /// A file picked here is indexed **regardless of extension** — mirrors Android's `importFiles`:
    /// `DecodeStatus.unsupported` answers deterministically rather than silently dropping a
    /// deliberate user choice.
    public func importFiles(_ urls: [URL]) async throws {
        try ensureMusicDirectoryExists()
        for url in urls {
            try Task.checkCancellation()
            try await importOne(DiscoveredLocation(sourceUrl: url, filename: url.lastPathComponent), enforceExtensionGate: false)
        }
    }

    /// Folder import (ARCHITECTURE §8.4's iOS path): recursively enumerates `folderUrl` and imports
    /// every file whose extension `AudioFormats` recognizes, silently skipping the rest — the folder
    /// itself was not a deliberate "index this exact file" choice the way a multi-select pick is, so
    /// an incidental non-audio file inside it is dropped rather than recorded as `.unsupported`
    /// (matches Android's `SafLibraryScanner`/`importTree` behaviour for a scanned tree).
    public func importFolder(_ folderUrl: URL) async throws {
        try ensureMusicDirectoryExists()
        let accessing = folderUrl.startAccessingSecurityScopedResource()
        defer { if accessing { folderUrl.stopAccessingSecurityScopedResource() } }

        let enumerator = FileManager.default.enumerator(
            at: folderUrl, includingPropertiesForKeys: [.isRegularFileKey], options: [.skipsHiddenFiles]
        )
        var files: [URL] = []
        while let candidate = enumerator?.nextObject() as? URL {
            try Task.checkCancellation()
            let isRegularFile = try candidate.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile ?? false
            if isRegularFile, AudioFormats.isSupportedExtension(candidate.lastPathComponent) {
                files.append(candidate)
            }
        }
        for url in files {
            try Task.checkCancellation()
            try await importOne(DiscoveredLocation(sourceUrl: url, filename: url.lastPathComponent), enforceExtensionGate: true)
        }
    }

    /// ADR-005's background lazy pass: computes the authoritative `ContentHash` for every row that
    /// does not have one yet, one file at a time so a cancelled pass leaves a correct, resumable
    /// partial result rather than a half-written one. **No-arg** (this phase's closure-audit
    /// hardening pass, Finding B): reads the current set of not-yet-hashed rows directly from the
    /// repository — never a possibly-stale caller-supplied snapshot — so a fresh call always resumes
    /// exactly the rows still missing a hash, whether that is because a previous pass was cancelled or
    /// because new tracks were imported since.
    public func completeContentHashing() async throws {
        for entry in try repository.entriesMissingContentHash() {
            try Task.checkCancellation()
            guard let hash = try? ContentHashing.computeContentHash(fileURL: resolvedUrl(for: entry)) else { continue }
            try? repository.updateContentHash(localEntryId: entry.localEntryId, contentHash: hash)
        }
    }

    /// Resolves this row's app-container destination file — derived from its `LocalEntryId`, never
    /// from `entry.location.uri` (which stores the original *source* URL, not a durably-reopenable
    /// reference; see this type's own doc comment). The extension is taken from `Track.filename`
    /// (the original display name), matching whatever `importOne` used when it copied the bytes in.
    public func resolvedUrl(for entry: LibraryEntry) -> URL {
        musicDirectory.appendingPathComponent(destinationFilename(localEntryId: entry.localEntryId, filename: entry.track.filename))
    }

    private func importOne(_ location: DiscoveredLocation, enforceExtensionGate: Bool) async throws {
        let now = monotonicNowUs()
        let locationUri = location.sourceUrl.absoluteString
        let existing = try? repository.findByLocationUri(locationUri)

        if enforceExtensionGate == false, !AudioFormats.isSupportedExtension(location.filename) {
            try? save(placeholderEntry(location, locationUri: locationUri, existing: existing, now: now, status: .unsupported), isNew: existing == nil)
            return
        }

        let accessing = location.sourceUrl.startAccessingSecurityScopedResource()
        defer { if accessing { location.sourceUrl.stopAccessingSecurityScopedResource() } }

        guard let quickId = try? ContentHashing.computeQuickId(fileURL: location.sourceUrl) else {
            try? save(placeholderEntry(location, locationUri: locationUri, existing: existing, now: now, status: .corrupt), isNew: existing == nil)
            return
        }

        let localEntryId = existing?.localEntryId ?? newLocalEntryId()
        let destinationUrl = musicDirectory.appendingPathComponent(destinationFilename(localEntryId: localEntryId, filename: location.filename))
        // Never skip-if-exists (ADR-005 Amendment A1's iOS CRITICAL finding): a destination file can
        // only already exist here when `existing` preserved this exact location's `LocalEntryId`, in
        // which case a reimport must overwrite it with fresh bytes — leaving stale bytes in place
        // because a file of that name happened to exist was the whole bug.
        try? FileManager.default.removeItem(at: destinationUrl)
        guard (try? FileManager.default.copyItem(at: location.sourceUrl, to: destinationUrl)) != nil else {
            try? save(placeholderEntry(location, locationUri: locationUri, existing: existing, now: now, status: .corrupt), isNew: existing == nil)
            return
        }

        let sizeBytes = (try? destinationUrl.resourceValues(forKeys: [.fileSizeKey]).fileSize.map(Int64.init)) ?? nil
        let extraction = await MetadataExtractor.extract(fileURL: destinationUrl)
        let entry = buildEntry(
            localEntryId: localEntryId, location: location, quickId: quickId, locationUri: locationUri,
            sizeBytes: sizeBytes ?? 0, now: now, extraction: extraction
        )
        try? save(entry, isNew: existing == nil)
    }

    /// Inserts a never-before-seen location, or, if this exact source location is already known,
    /// updates that row in place — preserving its `LocalEntryId`, never creating a second row for the
    /// same location.
    private func save(_ entry: LibraryEntry, isNew: Bool) throws {
        if isNew {
            try repository.insertNew(entry)
        } else {
            try repository.updateReindexed(entry)
        }
    }

    private func buildEntry(
        localEntryId: LocalEntryId,
        location: DiscoveredLocation,
        quickId: QuickId,
        locationUri: String,
        sizeBytes: Int64,
        now: Int64,
        extraction: Result<ExtractedMetadata, Error>
    ) -> LibraryEntry {
        let metadata = try? extraction.get()
        // The same measured heuristic Android's indexer uses: some malformed containers are opened
        // without error yet report no usable duration, so `durationMs <= 0` is the second CORRUPT
        // signal alongside a thrown extraction error.
        let decodeStatus: DecodeStatus = (metadata == nil || (metadata?.durationMs ?? 0) <= 0) ? .corrupt : .indexed
        let artworkRef = metadata?.artworkData.flatMap(ArtworkProcessor.processToBoundedJpeg).flatMap {
            artworkCache.store(localEntryId: localEntryId, data: $0)
        }
        return LibraryEntry(
            localEntryId: localEntryId,
            track: Track(
                // The authoritative hash is never computed on the fast indexing path (ADR-005) —
                // completeContentHashing fills this in later, in the background. A reimport of an
                // in-place edit resets it to unknown too (the old hash no longer describes the
                // current bytes) via `LibraryRepository.updateReindexed`.
                contentHash: nil,
                quickId: quickId,
                title: MetadataNormalizer.title(metadata?.title, filename: location.filename),
                artist: MetadataNormalizer.artist(metadata?.artist),
                album: MetadataNormalizer.album(metadata?.album),
                durationMs: metadata?.durationMs ?? 0,
                filename: location.filename,
                codec: metadata?.codec ?? extensionOf(location.filename),
                bitrateKbps: metadata?.bitrateKbps ?? 0,
                artworkRef: artworkRef,
                sizeBytes: sizeBytes
            ),
            location: LocalTrackLocation(uri: locationUri),
            decodeStatus: decodeStatus,
            indexedAtMonoUs: now,
            lastSeenAtMonoUs: now
        )
    }

    /// A row for a file that was never hashed at all (`DecodeStatus.unsupported`, extension gate,
    /// never opened; or `.corrupt`, couldn't even read enough bytes to compute a `QuickId`, or the
    /// app-owned copy failed). Its `quickId` is a hash of the source URL string itself, **not** a
    /// content hash — documented distinctly so nothing downstream mistakes it for real content
    /// identity; its real identity is still `localEntryId`, exactly like every other row.
    private func placeholderEntry(
        _ location: DiscoveredLocation,
        locationUri: String,
        existing: LibraryEntry?,
        now: Int64,
        status: DecodeStatus
    ) -> LibraryEntry {
        LibraryEntry(
            localEntryId: existing?.localEntryId ?? newLocalEntryId(),
            track: Track(
                contentHash: nil,
                quickId: syntheticQuickId(for: locationUri),
                title: MetadataNormalizer.title(nil, filename: location.filename),
                artist: MetadataNormalizer.artist(nil),
                album: MetadataNormalizer.album(nil),
                durationMs: 0,
                filename: location.filename,
                codec: extensionOf(location.filename),
                bitrateKbps: 0,
                artworkRef: nil,
                sizeBytes: 0
            ),
            location: LocalTrackLocation(uri: locationUri),
            decodeStatus: status,
            indexedAtMonoUs: now,
            lastSeenAtMonoUs: now
        )
    }

    private func destinationFilename(localEntryId: LocalEntryId, filename: String) -> String {
        let ext = (filename as NSString).pathExtension
        return ext.isEmpty ? localEntryId.value : "\(localEntryId.value).\(ext)"
    }

    private func syntheticQuickId(for uriString: String) -> QuickId {
        let digest = SHA256.hash(data: Data(uriString.utf8))
        return QuickId("sha256:" + digest.map { String(format: "%02x", $0) }.joined())
    }

    private func extensionOf(_ filename: String) -> String { (filename as NSString).pathExtension }

    private func ensureMusicDirectoryExists() throws {
        try FileManager.default.createDirectory(at: musicDirectory, withIntermediateDirectories: true)
    }
}
