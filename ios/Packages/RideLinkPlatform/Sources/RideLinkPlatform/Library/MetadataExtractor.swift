import AVFoundation
import CoreMedia
import Foundation

/// What `AVAsset` gave us, before `RideLinkCore.MetadataNormalizer` ever sees it — raw, possibly
/// missing, possibly empty. `artworkData` is the embedded picture exactly as extracted; bounding it
/// is `ArtworkProcessor`'s job, kept separate so this type does one thing (talk to the platform
/// asset) and nothing else. Mirrors `com.ridelink.data.library.ExtractedMetadata`.
public struct ExtractedMetadata: Sendable, Equatable {
    public let title: String?
    public let artist: String?
    public let album: String?
    public let durationMs: Int64
    public let bitrateKbps: Int
    public let codec: String?
    public let artworkData: Data?
}

/// The one place `AVFoundation` is asked what a local audio file actually is. `extract` never
/// throws: a file the asset cannot open or parse at all is exactly this phase's brief §9's "corrupt"
/// case, and the caller (`LibraryIndexer`) is the one that decides what that means for
/// `DecodeStatus` — this function only reports success or failure, mirroring
/// `com.ridelink.data.library.MetadataExtractor`'s `Result`-returning shape.
public enum MetadataExtractor {
    public static func extract(fileURL: URL) async -> Result<ExtractedMetadata, Error> {
        let asset = AVURLAsset(url: fileURL)
        do {
            // `.load` is the modern, structured-concurrency-friendly replacement for the deprecated
            // synchronous `AVAsset` properties — it throws for exactly the "this file is not a
            // parseable media container" case this phase's brief §9 calls CORRUPT, rather than
            // MediaMetadataRetriever's Android behaviour of silently returning empty metadata (a
            // real, measured platform difference — see `LibraryIndexer`'s CORRUPT heuristic, which
            // still checks `durationMs <= 0` as a second signal for the cases where `.load` does not
            // throw but the container turns out to have no usable duration).
            let duration = try await asset.load(.duration)
            let metadataItems = try await asset.load(.commonMetadata)
            let tracks = try await asset.load(.tracks)
            let bitrateKbps = try await averageBitrateKbps(for: tracks)
            let durationMs = duration.isValid && !duration.isIndefinite
                ? Int64((duration.seconds * millisecondsPerSecond).rounded())
                : 0
            return .success(
                ExtractedMetadata(
                    title: try await stringValue(for: .commonKeyTitle, in: metadataItems),
                    artist: try await stringValue(for: .commonKeyArtist, in: metadataItems),
                    album: try await stringValue(for: .commonKeyAlbumName, in: metadataItems),
                    durationMs: durationMs,
                    bitrateKbps: bitrateKbps,
                    codec: try await codecDescription(for: tracks),
                    artworkData: try await dataValue(for: .commonKeyArtwork, in: metadataItems)
                )
            )
        } catch {
            return .failure(error)
        }
    }

    private static func stringValue(for key: AVMetadataKey, in items: [AVMetadataItem]) async throws -> String? {
        guard let item = AVMetadataItem.metadataItems(from: items, filteredByIdentifier: identifier(for: key)).first else {
            return nil
        }
        return try await item.load(.stringValue)
    }

    private static func dataValue(for key: AVMetadataKey, in items: [AVMetadataItem]) async throws -> Data? {
        guard let item = AVMetadataItem.metadataItems(from: items, filteredByIdentifier: identifier(for: key)).first else {
            return nil
        }
        return try await item.load(.dataValue)
    }

    private static func identifier(for key: AVMetadataKey) -> AVMetadataIdentifier {
        AVMetadataItem.identifier(forKey: key, keySpace: .common) ?? .commonIdentifierTitle
    }

    private static func averageBitrateKbps(for tracks: [AVAssetTrack]) async throws -> Int {
        guard let audioTrack = tracks.first(where: { $0.mediaType == .audio }) else { return 0 }
        let bitsPerSecond = try await audioTrack.load(.estimatedDataRate)
        return bitsPerSecond > 0 ? Int(bitsPerSecond / bitsPerKbps) : 0
    }

    /// There is no direct "codec name" API on `AVAssetTrack` — the format description's media
    /// subtype is the closest honest equivalent, rendered as its four-character-code string (e.g.
    /// `"aac "`, `"mp3 "`) rather than a MIME type, since the description tag is what this value is
    /// used for (`Track.codec` is a display/diagnostic field, not a wire content-type).
    private static func codecDescription(for tracks: [AVAssetTrack]) async throws -> String? {
        guard let audioTrack = tracks.first(where: { $0.mediaType == .audio }) else { return nil }
        let formatDescriptions = try await audioTrack.load(.formatDescriptions)
        guard let formatDescription = formatDescriptions.first else { return nil }
        let mediaSubType = CMFormatDescriptionGetMediaSubType(formatDescription)
        return fourCharCodeString(mediaSubType)
    }

    private static func fourCharCodeString(_ code: FourCharCode) -> String {
        let bytes: [UInt8] = [
            UInt8((code >> 24) & 0xFF),
            UInt8((code >> 16) & 0xFF),
            UInt8((code >> 8) & 0xFF),
            UInt8(code & 0xFF),
        ]
        return String(decoding: bytes, as: UTF8.self)
    }

    private static let millisecondsPerSecond: Double = 1000
    private static let bitsPerKbps: Float = 1000
}
