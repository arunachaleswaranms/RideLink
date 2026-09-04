import Foundation

/// Deterministic display-metadata normalization (this phase's brief §8). Pure text transformation —
/// no I/O, no locale-dependent collation, so the same raw tag input produces the same display value
/// on both platforms.
///
/// What "deterministic" rules out: no random placeholder text, no wall-clock-dependent formatting,
/// no locale-default fallback strings (a fixed English literal is used instead — this app has
/// exactly two users and no localization infrastructure, so a locale-varying fallback would be
/// nondeterministic for no benefit).
public enum MetadataNormalizer {
    /// PROTOCOL §8.1 clamps manifest display strings to 512 Unicode scalar values; this phase
    /// reuses the same bound for locally-stored metadata rather than inventing a second one; a
    /// decode boundary should not depend on a value stored one bound and clamped another.
    public static let maxFieldLength = 512

    public static let unknownArtist = "Unknown Artist"
    public static let unknownAlbum = "Unknown Album"

    /// Missing/blank title falls back to the filename with its extension stripped — a deterministic,
    /// always-available value, never "Unknown Title" (the file's own name is more useful and this
    /// phase's brief §8 requires the fallback be deterministic, not merely present).
    public static func title(_ rawTitle: String?, filename: String) -> String {
        clampAndNormalize(clean(rawTitle) ?? titleFromFilename(filename))
    }

    public static func artist(_ rawArtist: String?) -> String {
        clampAndNormalize(clean(rawArtist) ?? unknownArtist)
    }

    public static func album(_ rawAlbum: String?) -> String {
        clampAndNormalize(clean(rawAlbum) ?? unknownAlbum)
    }

    /// Nil and blank (after trimming) are both "missing" — a tag full of spaces is not a title.
    private static func clean(_ raw: String?) -> String? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else { return nil }
        return trimmed
    }

    private static func titleFromFilename(_ filename: String) -> String {
        guard let dotIndex = filename.lastIndex(of: "."), dotIndex != filename.startIndex else {
            return filename
        }
        let withoutExtension = String(filename[filename.startIndex..<dotIndex])
        return withoutExtension.isEmpty ? filename : withoutExtension
    }

    /// NFC only — canonical composition, never transliteration or case-folding. Two byte-different
    /// but canonically-equivalent Unicode strings (e.g. precomposed vs combining-mark e-acute) must
    /// display and sort identically on both platforms; anything stronger would silently change what
    /// the user typed or tagged.
    ///
    /// Clamped by **Unicode scalar value** count, matching PROTOCOL §8.1's manifest-field bound and
    /// Android's `core.library.MetadataNormalizer` exactly (Swift's `String.count` counts extended
    /// grapheme clusters, not scalars, and would under-count a string containing combining marks —
    /// `unicodeScalars.count` is the one that matches "Unicode scalar value").
    private static func clampAndNormalize(_ value: String) -> String {
        let normalized = value.precomposedStringWithCanonicalMapping
        let scalars = normalized.unicodeScalars
        guard scalars.count > maxFieldLength else { return normalized }
        let clampedEnd = scalars.index(scalars.startIndex, offsetBy: maxFieldLength)
        return String(String.UnicodeScalarView(scalars[scalars.startIndex..<clampedEnd]))
    }
}
