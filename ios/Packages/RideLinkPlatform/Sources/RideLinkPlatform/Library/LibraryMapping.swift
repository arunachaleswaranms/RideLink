import Foundation
import RideLinkCore

/// The one place `TrackRecord` (storage shape) and `LibraryEntry` (domain shape) convert between
/// each other — kept out of both `LibraryRepository` (storage should not know the domain model) and
/// `LibraryIndexer` (indexing should not know column names). Mirrors
/// `com.ridelink.data.library.LibraryMapping`.
enum LibraryMapping {
    static func toDomain(_ record: TrackRecord) -> LibraryEntry? {
        guard let quickId = QuickId.parse(record.quickId), let decodeStatus = decodeStatus(fromStored: record.decodeStatus)
        else { return nil }
        return LibraryEntry(
            track: Track(
                contentHash: record.contentHash.flatMap(ContentHash.parse),
                quickId: quickId,
                title: record.title,
                artist: record.artist,
                album: record.album,
                durationMs: record.durationMs,
                filename: record.filename,
                codec: record.codec,
                bitrateKbps: record.bitrateKbps,
                artworkRef: record.artworkRef,
                sizeBytes: record.sizeBytes
            ),
            location: LocalTrackLocation(uri: record.locationUri),
            decodeStatus: decodeStatus,
            indexedAtMonoUs: record.indexedAtMonoUs,
            lastSeenAtMonoUs: record.lastSeenAtMonoUs
        )
    }

    static func toRecord(_ entry: LibraryEntry, id: Int64? = nil) -> TrackRecord {
        TrackRecord(
            id: id,
            quickId: entry.track.quickId.value,
            contentHash: entry.track.contentHash?.value,
            title: entry.track.title,
            artist: entry.track.artist,
            album: entry.track.album,
            durationMs: entry.track.durationMs,
            filename: entry.track.filename,
            codec: entry.track.codec,
            bitrateKbps: entry.track.bitrateKbps,
            artworkRef: entry.track.artworkRef,
            sizeBytes: entry.track.sizeBytes,
            locationUri: entry.location.uri,
            decodeStatus: storedValue(for: entry.decodeStatus),
            indexedAtMonoUs: entry.indexedAtMonoUs,
            lastSeenAtMonoUs: entry.lastSeenAtMonoUs
        )
    }

    /// Plain functions rather than a `DecodeStatus: RawRepresentable` extension: `DecodeStatus` is
    /// defined in `RideLinkCore`, and the compiler itself warns that a retroactive conformance from
    /// another module "will not behave correctly if the owners of RideLinkCore introduce this
    /// conformance in the future" — exactly the kind of silent-conflict risk this project avoids by
    /// keeping cross-module conversions as ordinary functions instead.
    ///
    /// The four stored strings round-trip through the same names Android's `DecodeStatus.name`
    /// stores — not that the two databases are ever compared, but a name that only one platform's
    /// code happens to spell consistently is exactly the kind of drift CLAUDE.md rule 6 warns about.
    static func decodeStatus(fromStored value: String) -> DecodeStatus? {
        switch value {
        case "INDEXED": .indexed
        case "UNSUPPORTED": .unsupported
        case "CORRUPT": .corrupt
        case "MISSING": .missing
        default: nil
        }
    }

    static func storedValue(for status: DecodeStatus) -> String {
        switch status {
        case .indexed: "INDEXED"
        case .unsupported: "UNSUPPORTED"
        case .corrupt: "CORRUPT"
        case .missing: "MISSING"
        }
    }
}
