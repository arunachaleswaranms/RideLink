import CryptoKit
import Foundation

/// PROTOCOL §8.1 / ADR-013 — the sender's page-assembly rule, the display-metadata clamp, and the
/// `MANIFEST_END` digest. Pure functions over arrays, driven entirely by shared vectors
/// (`protocol/vectors/manifest-paging/`).
///
/// The Kotlin mirror is `com.ridelink.core.manifest.ManifestPaging`.
public enum ManifestPaging {
    public static let manifestPageSoftLimitBytes = 196_608 // 192 KiB, PROTOCOL §1
    public static let maxEntriesPerPage = 256
    public static let displayClampScalars = 512

    /// ADR-013 rule 3: title/artist/album/filename truncated to 512 Unicode **scalar values** —
    /// not UTF-16 code units and not extended grapheme clusters. Swift's `String.count` counts the
    /// latter, so this walks `unicodeScalars` explicitly, which is what makes this the right API
    /// and `.count`/`.prefix` the wrong one (a musical-note-emoji title is the vector that catches
    /// getting this backwards: one scalar value, a surrogate pair in UTF-16, one grapheme cluster
    /// only by coincidence).
    public static func clampScalars(_ s: String, limit: Int = displayClampScalars) -> String {
        let scalars = s.unicodeScalars
        guard scalars.count > limit else { return s }
        let end = scalars.index(scalars.startIndex, offsetBy: limit)
        return String(String.UnicodeScalarView(scalars[scalars.startIndex..<end]))
    }

    public static func clampEntry(_ entry: ManifestEntry) -> ManifestEntry {
        var clamped = entry
        clamped.title = clampScalars(entry.title)
        clamped.artist = clampScalars(entry.artist)
        clamped.album = clampScalars(entry.album)
        clamped.filename = clampScalars(entry.filename)
        return clamped
    }

    /// ADR-013's page-sizing rule: close a page when the next entry would exceed the byte budget,
    /// or when it reaches `maxEntriesPerPage` — whichever binds first. Every entry is clamped
    /// before it is measured or placed, so a single entry always fits (ADR-013's ~48 KiB
    /// worst-case arithmetic) and a page is never empty.
    public static func paginate(
        _ entries: [ManifestEntry],
        budgetBytes: Int = manifestPageSoftLimitBytes
    ) -> [[ManifestEntry]] {
        var pages: [[ManifestEntry]] = []
        var current: [ManifestEntry] = []
        var currentBytes = 0
        for raw in entries {
            let clamped = clampEntry(raw)
            let size = clamped.encodedByteLength()
            let wouldBe = currentBytes + size + (current.isEmpty ? 0 : 1)
            if !current.isEmpty, wouldBe > budgetBytes || current.count >= maxEntriesPerPage {
                pages.append(current)
                current = [clamped]
                currentBytes = size
            } else {
                current.append(clamped)
                currentBytes += size + (current.count > 1 ? 1 : 0)
            }
        }
        if !current.isEmpty { pages.append(current) }
        return pages
    }

    private static let unitSeparator: UInt8 = 0x1F
    private static let recordSeparator: UInt8 = 0x1E

    /// PROTOCOL §8.1's exact `MANIFEST_END` digest: identity fields only, in transmission order.
    /// Entries passed here must already be clamped — the digest is computed over what was actually
    /// sent, and clamping never touches identity fields anyway.
    public static func digest(entries: [ManifestEntry], removed: [ContentHash]) -> String {
        var hasher = SHA256()
        for entry in entries {
            hasher.update(data: Data((entry.contentHash?.value ?? "").utf8))
            hasher.update(data: Data([unitSeparator]))
            hasher.update(data: Data(entry.quickId.value.utf8))
            hasher.update(data: Data([recordSeparator]))
        }
        for removedHash in removed {
            hasher.update(data: Data("-".utf8))
            hasher.update(data: Data(removedHash.value.utf8))
            hasher.update(data: Data([recordSeparator]))
        }
        let hex = hasher.finalize().map { String(format: "%02x", $0) }.joined()
        return "sha256:\(hex)"
    }
}
