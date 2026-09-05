package com.ridelink.core.manifest

import com.ridelink.core.model.ContentHash
import java.security.MessageDigest

/**
 * PROTOCOL §8.1 / ADR-013 — the sender's page-assembly rule, the display-metadata clamp, and the
 * `MANIFEST_END` digest. Pure functions over lists, per ARCHITECTURE §8.2: "manifest paging lives
 * in `core`'s `manifest` package... driven entirely by shared vectors"
 * (`protocol/vectors/manifest-paging/`).
 */
object ManifestPaging {
    const val MANIFEST_PAGE_SOFT_LIMIT_BYTES = 196_608 // 192 KiB, PROTOCOL §1
    const val MAX_ENTRIES_PER_PAGE = 256
    const val DISPLAY_CLAMP_SCALARS = 512

    /**
     * ADR-013 rule 3: title/artist/album/filename truncated to 512 Unicode **scalar values** —
     * not UTF-16 code units (`String.length`) and not grapheme clusters. `codePointCount`/
     * `offsetByCodePoints` count actual Unicode scalar values, correctly treating a surrogate pair
     * as one unit, which is what makes this the right API and `.length`/`.take(512)` the wrong one.
     */
    fun clampScalars(
        s: String,
        limit: Int = DISPLAY_CLAMP_SCALARS,
    ): String {
        val scalarCount = s.codePointCount(0, s.length)
        if (scalarCount <= limit) return s
        val end = s.offsetByCodePoints(0, limit)
        return s.substring(0, end)
    }

    fun clampEntry(e: ManifestEntry): ManifestEntry =
        e.copy(
            title = clampScalars(e.title),
            artist = clampScalars(e.artist),
            album = clampScalars(e.album),
            filename = clampScalars(e.filename),
        )

    /**
     * ADR-013's page-sizing rule: close a page when the next entry would exceed the byte budget,
     * or when it reaches [MAX_ENTRIES_PER_PAGE] — whichever binds first. Every entry is clamped
     * before it is measured or placed, so a single entry always fits (ADR-013's ~48 KiB worst-case
     * arithmetic) and a page is never empty.
     */
    fun paginate(
        entries: List<ManifestEntry>,
        budgetBytes: Int = MANIFEST_PAGE_SOFT_LIMIT_BYTES,
    ): List<List<ManifestEntry>> {
        val pages = mutableListOf<List<ManifestEntry>>()
        var current = mutableListOf<ManifestEntry>()
        var currentBytes = 0
        for (raw in entries) {
            val clamped = clampEntry(raw)
            val size = clamped.encodedByteLength()
            val wouldBe = currentBytes + size + (if (current.isNotEmpty()) 1 else 0)
            if (current.isNotEmpty() && (wouldBe > budgetBytes || current.size >= MAX_ENTRIES_PER_PAGE)) {
                pages.add(current)
                current = mutableListOf(clamped)
                currentBytes = size
            } else {
                current.add(clamped)
                currentBytes += size + (if (current.size > 1) 1 else 0)
            }
        }
        if (current.isNotEmpty()) pages.add(current)
        return pages
    }

    private const val UNIT_SEPARATOR: Byte = 0x1f
    private const val RECORD_SEPARATOR: Byte = 0x1e

    /**
     * PROTOCOL §8.1's exact `MANIFEST_END` digest: identity fields only, in transmission order.
     * Entries passed here must already be clamped — the digest is computed over what was actually
     * sent, and clamping never touches identity fields anyway.
     */
    fun digest(
        entries: List<ManifestEntry>,
        removed: List<ContentHash>,
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (e in entries) {
            md.update((e.contentHash?.value ?: "").toByteArray(Charsets.UTF_8))
            md.update(UNIT_SEPARATOR)
            md.update(e.quickId.value.toByteArray(Charsets.UTF_8))
            md.update(RECORD_SEPARATOR)
        }
        for (r in removed) {
            md.update('-'.code.toByte())
            md.update(r.value.toByteArray(Charsets.UTF_8))
            md.update(RECORD_SEPARATOR)
        }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        return "sha256:$hex"
    }
}
