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
/// copy into the app's own container → `QuickId` → metadata → artwork → upsert. Every step that can
/// fail does so into a `DecodeStatus`, never a thrown error the caller has to catch — "even local
/// files are untrusted input" (brief §20) applies to every file this touches.
///
/// **A real, deliberate divergence from `com.ridelink.data.library.LibraryIndexer`, not an
/// oversight**: Android's SAF import keeps a persisted `content://` permission and re-scans the
/// *original* location every time, so a file removed from that location is detected as
/// `DecodeStatus.missing` (`IndexReconciliation`). iOS never does that — ADR-009 says "no reliance
/// on an external security-scoped URL indefinitely", so every picked file is copied into this app's
/// own `Application Support` directory on import and the security-scoped source URL is never touched
/// again. There is therefore no live external reference whose disappearance this indexer could
/// detect; `IndexReconciliation`'s `missingQuickIds` path (used by Android's `SafLibraryScanner`) has
/// no iOS caller for that reason, not because it was forgotten. Re-importing the same content (by
/// `QuickId`) is a no-op update, the same de-duplication FR-010 requires on both platforms.
public final class LibraryIndexer: Sendable {
    private let repository: LibraryRepository
    private let artworkCache: ArtworkCache
    private let musicDirectory: URL
    private let monotonicNowUs: @Sendable () -> Int64

    public init(
        repository: LibraryRepository,
        artworkCache: ArtworkCache,
        musicDirectory: URL,
        monotonicNowUs: @escaping @Sendable () -> Int64
    ) {
        self.repository = repository
        self.artworkCache = artworkCache
        self.musicDirectory = musicDirectory
        self.monotonicNowUs = monotonicNowUs
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

    /// Fills in the authoritative `ContentHash` for every entry that does not have one yet (ADR-005's
    /// lazy background pass), one file at a time so a cancelled pass leaves a correct, resumable
    /// partial result rather than a half-written one.
    public func completeContentHashing(_ entriesMissingHash: [LibraryEntry]) async throws {
        for entry in entriesMissingHash {
            try Task.checkCancellation()
            guard let hash = try? ContentHashing.computeContentHash(fileURL: resolvedUrl(for: entry.location)) else { continue }
            try? repository.upsert(
                LibraryEntry(
                    track: Track(
                        contentHash: hash,
                        quickId: entry.track.quickId,
                        title: entry.track.title,
                        artist: entry.track.artist,
                        album: entry.track.album,
                        durationMs: entry.track.durationMs,
                        filename: entry.track.filename,
                        codec: entry.track.codec,
                        bitrateKbps: entry.track.bitrateKbps,
                        artworkRef: entry.track.artworkRef,
                        sizeBytes: entry.track.sizeBytes
                    ),
                    location: entry.location,
                    decodeStatus: entry.decodeStatus,
                    indexedAtMonoUs: entry.indexedAtMonoUs,
                    lastSeenAtMonoUs: entry.lastSeenAtMonoUs
                )
            )
        }
    }

    /// Resolves a stored `LocalTrackLocation.uri` (a filename relative to `musicDirectory`, never an
    /// absolute path — the app's own sandbox root can move between launches/reinstalls, so an
    /// absolute path stored today could point nowhere tomorrow) to a real, currently-valid `URL`.
    public func resolvedUrl(for location: LocalTrackLocation) -> URL {
        musicDirectory.appendingPathComponent(location.uri)
    }

    private func importOne(_ location: DiscoveredLocation, enforceExtensionGate: Bool) async throws {
        let now = monotonicNowUs()
        if enforceExtensionGate == false, !AudioFormats.isSupportedExtension(location.filename) {
            try? repository.upsert(placeholderEntry(location, now: now, status: .unsupported))
            return
        }

        let accessing = location.sourceUrl.startAccessingSecurityScopedResource()
        defer { if accessing { location.sourceUrl.stopAccessingSecurityScopedResource() } }

        guard let quickId = try? ContentHashing.computeQuickId(fileURL: location.sourceUrl) else {
            try? repository.upsert(placeholderEntry(location, now: now, status: .corrupt))
            return
        }

        let ext = (location.filename as NSString).pathExtension
        let quickIdHex = quickId.value.dropFirst(Self.sha256Prefix.count)
        let destinationName = ext.isEmpty ? String(quickIdHex) : "\(quickIdHex).\(ext)"
        let destinationUrl = musicDirectory.appendingPathComponent(destinationName)
        if !FileManager.default.fileExists(atPath: destinationUrl.path) {
            guard (try? FileManager.default.copyItem(at: location.sourceUrl, to: destinationUrl)) != nil else {
                try? repository.upsert(placeholderEntry(location, now: now, status: .corrupt))
                return
            }
        }

        let sizeBytes = (try? destinationUrl.resourceValues(forKeys: [.fileSizeKey]).fileSize.map(Int64.init)) ?? nil
        let extraction = await MetadataExtractor.extract(fileURL: destinationUrl)
        try? repository.upsert(
            buildEntry(
                location: location, quickId: quickId, locationUri: destinationName,
                sizeBytes: sizeBytes ?? 0, now: now, extraction: extraction
            )
        )
    }

    private func buildEntry(
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
            artworkCache.store(quickId: quickId, data: $0)
        }
        return LibraryEntry(
            track: Track(
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
    /// app-owned copy failed). Keyed by a hash of the source URL string itself, **not** a content
    /// hash — documented distinctly so nothing downstream mistakes it for real content identity.
    private func placeholderEntry(_ location: DiscoveredLocation, now: Int64, status: DecodeStatus) -> LibraryEntry {
        LibraryEntry(
            track: Track(
                contentHash: nil,
                quickId: syntheticQuickId(for: location.sourceUrl.absoluteString),
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
            location: LocalTrackLocation(uri: location.sourceUrl.absoluteString),
            decodeStatus: status,
            indexedAtMonoUs: now,
            lastSeenAtMonoUs: now
        )
    }

    private func syntheticQuickId(for uriString: String) -> QuickId {
        let digest = SHA256.hash(data: Data(uriString.utf8))
        return QuickId("sha256:" + digest.map { String(format: "%02x", $0) }.joined())
    }

    private func extensionOf(_ filename: String) -> String { (filename as NSString).pathExtension }

    private func ensureMusicDirectoryExists() throws {
        try FileManager.default.createDirectory(at: musicDirectory, withIntermediateDirectories: true)
    }

    private static let sha256Prefix = "sha256:"
}
