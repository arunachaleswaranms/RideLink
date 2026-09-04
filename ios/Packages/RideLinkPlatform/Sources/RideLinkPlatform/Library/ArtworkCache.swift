import Foundation
import RideLinkCore

/// Where bounded artwork actually lives — a cache file, never the database row itself (this phase's
/// brief §18: "do not store giant images directly in database rows... prefer cached artwork file").
/// `Track.artworkRef` is the relative path this returns, resolved back to a real file `URL` by
/// `fileURL(for:)` whenever the UI needs to load one. Mirrors `com.ridelink.data.library.ArtworkCache`.
///
/// The platform's Caches directory, not Application Support: artwork is a derived, regenerable
/// cache — losing it under storage pressure (the OS may clear this directory at any time) means the
/// next reindex regenerates it, never a user-visible data loss.
public struct ArtworkCache: Sendable {
    private let cachesDirectory: URL

    public init(cachesDirectory: URL = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]) {
        self.cachesDirectory = cachesDirectory
    }

    /// @return the ref to store on `Track.artworkRef`, or `nil` if `data` is `nil` (no artwork) or
    ///   could not be written.
    public func store(quickId: QuickId, data: Data?) -> String? {
        guard let data else { return nil }
        let directory = cachesDirectory.appendingPathComponent(Self.artworkSubdirectory, isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let ref = refFor(quickId)
            try data.write(to: cachesDirectory.appendingPathComponent(ref))
            return ref
        } catch {
            return nil
        }
    }

    public func fileURL(for ref: String) -> URL { cachesDirectory.appendingPathComponent(ref) }

    private func refFor(_ quickId: QuickId) -> String {
        // The file's name is derived from the content-addressed quickId, never from the track's own
        // filename — CLAUDE.md's privacy rule against unnecessary paths/names in stored artefacts.
        "\(Self.artworkSubdirectory)/\(quickId.value.dropFirst(Self.sha256Prefix.count)).jpg"
    }

    private static let sha256Prefix = "sha256:"

    private static let artworkSubdirectory = "artwork"
}
