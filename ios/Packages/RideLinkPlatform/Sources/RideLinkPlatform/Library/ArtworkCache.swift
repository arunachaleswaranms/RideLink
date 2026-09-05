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
///
/// **Keyed by `LocalEntryId`, not `quickId`** (ADR-005 Amendment A1). `quickId` is only a 128 KiB
/// sample and is not guaranteed unique across rows — keying a cache filename on it would let two
/// different files' artwork collide onto the same cache entry exactly the way the database identity
/// bug did. `LocalEntryId` is generated per row and cannot collide.
public struct ArtworkCache: Sendable {
    private let cachesDirectory: URL

    public init(cachesDirectory: URL = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]) {
        self.cachesDirectory = cachesDirectory
    }

    /// @return the ref to store on `Track.artworkRef`, or `nil` if `data` is `nil` (no artwork) or
    ///   could not be written.
    public func store(localEntryId: LocalEntryId, data: Data?) -> String? {
        guard let data else { return nil }
        let directory = cachesDirectory.appendingPathComponent(Self.artworkSubdirectory, isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let ref = refFor(localEntryId)
            try data.write(to: cachesDirectory.appendingPathComponent(ref))
            return ref
        } catch {
            return nil
        }
    }

    public func fileURL(for ref: String) -> URL { cachesDirectory.appendingPathComponent(ref) }

    private func refFor(_ localEntryId: LocalEntryId) -> String {
        // The file's name is derived from this row's own local identity, never from the track's own
        // filename — CLAUDE.md's privacy rule against unnecessary paths/names in stored artefacts.
        "\(Self.artworkSubdirectory)/\(localEntryId.value).jpg"
    }

    private static let artworkSubdirectory = "artwork"
}
