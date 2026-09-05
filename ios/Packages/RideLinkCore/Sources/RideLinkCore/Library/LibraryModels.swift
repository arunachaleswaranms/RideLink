import Foundation

/// Where this track's bytes actually live on this phone. Platform-opaque by design (CLAUDE.md
/// rule 9): a security-scoped bookmark or an app-owned relative path are both just strings at this
/// layer — only `RideLinkPlatform.Library` knows how to open one. Never a display path
/// (REQUIREMENTS §11's basename-only rule applies to `Track.filename`, not to this).
public struct LocalTrackLocation: Hashable, Sendable {
    public let uri: String
    public init(uri: String) { self.uri = uri }
}

/// The deterministic outcome of trying to make sense of a file, never a crash (this phase's brief
/// §9/§20). A file the platform decoder rejects is `.unsupported` or `.corrupt` — distinguished so
/// the UI can say "not a supported format" versus "this file looks damaged" — and a file the last
/// scan saw but this one didn't is `.missing`, not deleted from the catalogue outright (brief
/// §10/§11: "file removed" and "file renamed" are both scan outcomes, not exceptions).
public enum DecodeStatus: Sendable, Equatable {
    /// Metadata and, eventually, `Track.contentHash` were extracted successfully.
    case indexed
    /// The platform decoder does not support this container/codec at all.
    case unsupported
    /// The extension/container looked supported but the file could not be parsed.
    case corrupt
    /// Indexed at some point; the most recent scan could not find it at `LibraryEntry.location`.
    case missing
}

/// One row of the local library: a `Track` (the wire-shape identity/metadata record, reused as-is
/// since REQUIREMENTS §16 already defines exactly the fields Phase 3 needs), where it lives on this
/// phone, and this phone's own bookkeeping about it. Never shared, never sent — Phase 3 is
/// local-only (this phase's brief §2/§28).
///
/// `localEntryId` — not `Track.quickId` — is this row's real identity (ADR-005 Amendment A1). It is
/// generated once, when `location` is first indexed, and carried forward unchanged across every
/// rescan that finds the same location again or detects it changed in place; it is never recomputed
/// from content and never shared with any other row, even one whose `Track.quickId` happens to match.
public struct LibraryEntry: Sendable, Equatable {
    public let localEntryId: LocalEntryId
    public let track: Track
    public let location: LocalTrackLocation
    public let decodeStatus: DecodeStatus
    /// When this phone first indexed this content, monotonic (CLAUDE.md rule 5).
    public let indexedAtMonoUs: Int64
    /// When the most recent scan last saw it at `location`. Advances `.missing` back to `.indexed`
    /// the moment a rescan finds it again.
    public let lastSeenAtMonoUs: Int64

    public init(
        localEntryId: LocalEntryId,
        track: Track,
        location: LocalTrackLocation,
        decodeStatus: DecodeStatus,
        indexedAtMonoUs: Int64,
        lastSeenAtMonoUs: Int64
    ) {
        self.localEntryId = localEntryId
        self.track = track
        self.location = location
        self.decodeStatus = decodeStatus
        self.indexedAtMonoUs = indexedAtMonoUs
        self.lastSeenAtMonoUs = lastSeenAtMonoUs
    }
}

/// Ascending only for V1 — the simplest coherent increment; a descending toggle is a UI concern
/// that can be added without touching this type.
public enum LibrarySort: Sendable, Equatable {
    case title
    case artist
    case album
    case recentlyAdded
}

/// What the library screen asks for. Empty `searchText` means "no filter" — the whole library in
/// `sort` order — which is the defined empty-query behaviour this phase's brief §13 requires rather
/// than leaving unspecified.
public struct LibraryQuery: Sendable, Equatable {
    public let searchText: String
    public let sort: LibrarySort

    public init(searchText: String = "", sort: LibrarySort = .title) {
        self.searchText = searchText
        self.sort = sort
    }
}
