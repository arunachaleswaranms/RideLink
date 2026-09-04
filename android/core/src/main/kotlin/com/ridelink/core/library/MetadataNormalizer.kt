package com.ridelink.core.library

import java.text.Normalizer

/**
 * Deterministic display-metadata normalization (this phase's brief §8). Pure text transformation —
 * no I/O, no locale-dependent collation, so the same raw tag input produces the same display value
 * on both platforms.
 *
 * What "deterministic" rules out: no random placeholder text, no wall-clock-dependent formatting,
 * no locale-default fallback strings (a fixed English literal is used instead — this app has
 * exactly two users and no localization infrastructure, so a locale-varying fallback would be
 * nondeterministic for no benefit).
 */
object MetadataNormalizer {
    /**
     * PROTOCOL §8.1 clamps manifest display strings to 512 Unicode scalar values; this phase reuses
     * the same bound for locally-stored metadata rather than inventing a second one; a decode
     * boundary should not depend on a value stored one bound and clamped another.
     */
    const val MAX_FIELD_LENGTH = 512

    const val UNKNOWN_ARTIST = "Unknown Artist"
    const val UNKNOWN_ALBUM = "Unknown Album"

    /**
     * Missing/blank title falls back to the filename with its extension stripped — a deterministic,
     * always-available value, never "Unknown Title" (the file's own name is more useful and this
     * phase's brief §8 requires the fallback be deterministic, not merely present).
     */
    fun title(
        rawTitle: String?,
        filename: String,
    ): String {
        val cleaned = clean(rawTitle)
        return clampAndNormalize(cleaned ?: titleFromFilename(filename))
    }

    fun artist(rawArtist: String?): String = clampAndNormalize(clean(rawArtist) ?: UNKNOWN_ARTIST)

    fun album(rawAlbum: String?): String = clampAndNormalize(clean(rawAlbum) ?: UNKNOWN_ALBUM)

    /** Null and blank (after trimming) are both "missing" — a tag full of spaces is not a title. */
    private fun clean(raw: String?): String? {
        val trimmed = raw?.trim()
        return if (trimmed.isNullOrEmpty()) null else trimmed
    }

    private fun titleFromFilename(filename: String): String {
        val dot = filename.lastIndexOf('.')
        val withoutExtension = if (dot > 0) filename.substring(0, dot) else filename
        return withoutExtension.ifBlank { filename }
    }

    /**
     * NFC only — canonical composition, never transliteration or case-folding. Two byte-different
     * but canonically-equivalent Unicode strings (e.g. precomposed vs combining-mark é) must display
     * and sort identically on both platforms; anything stronger would silently change what the user
     * typed or tagged.
     *
     * Clamped by **Unicode scalar value** count, matching PROTOCOL §8.1's manifest-field bound
     * exactly (deliberately not `String.length`, which counts UTF-16 code units — a supplementary-
     * plane character such as an emoji is one scalar but two code units, so length-based truncation
     * would clamp non-BMP-heavy titles more aggressively than BMP ones for no reason and risks
     * splitting a surrogate pair in half).
     */
    private fun clampAndNormalize(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
        var scalarCount = 0
        var index = 0
        while (index < normalized.length) {
            val codePoint = normalized.codePointAt(index)
            scalarCount++
            if (scalarCount > MAX_FIELD_LENGTH) return normalized.substring(0, index)
            index += Character.charCount(codePoint)
        }
        return normalized
    }
}
