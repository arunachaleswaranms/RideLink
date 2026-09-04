package com.ridelink.core.library

import kotlin.test.Test
import kotlin.test.assertEquals

class MetadataNormalizerTest {
    @Test
    fun `missing title falls back to the filename without its extension`() {
        assertEquals("song", MetadataNormalizer.title(null, "song.mp3"))
        assertEquals("song", MetadataNormalizer.title("", "song.mp3"))
        assertEquals("song", MetadataNormalizer.title("   ", "song.mp3"))
    }

    @Test
    fun `a filename with no extension is used as-is`() {
        assertEquals("song", MetadataNormalizer.title(null, "song"))
    }

    @Test
    fun `a dotfile with no real extension keeps its leading dot`() {
        // lastIndexOf('.') == 0 must not be treated as an extension separator.
        assertEquals(".hidden", MetadataNormalizer.title(null, ".hidden"))
    }

    @Test
    fun `a present title is used untouched`() {
        assertEquals("Real Title", MetadataNormalizer.title("Real Title", "song.mp3"))
    }

    @Test
    fun `missing artist and album use fixed deterministic literals`() {
        assertEquals(MetadataNormalizer.UNKNOWN_ARTIST, MetadataNormalizer.artist(null))
        assertEquals(MetadataNormalizer.UNKNOWN_ARTIST, MetadataNormalizer.artist("  "))
        assertEquals(MetadataNormalizer.UNKNOWN_ALBUM, MetadataNormalizer.album(null))
    }

    @Test
    fun `unicode passes through untouched apart from NFC composition`() {
        // Built entirely from explicit \u escapes, never a literal accented character in the source,
        // so the two inputs are provably distinct code-point sequences rather than an accident of
        // how a source file happened to be encoded.
        //   nfd: 'c','a','f','e', U+0301 COMBINING ACUTE ACCENT — 5 code points
        //   nfc: 'c','a','f', U+00E9 LATIN SMALL LETTER E WITH ACUTE — 4 code points
        val nfd = "caf" + "e" + "́"
        val nfc = "caf" + "é"
        check(nfd != nfc) { "test setup bug: nfd and nfc must be genuinely distinct code-point sequences" }
        assertEquals(nfc, MetadataNormalizer.title(nfd, "f"))
        assertEquals(MetadataNormalizer.title(nfc, "f"), MetadataNormalizer.title(nfd, "f"))
    }

    @Test
    fun `very long metadata is clamped to the shared 512 scalar bound`() {
        val long = "a".repeat(MetadataNormalizer.MAX_FIELD_LENGTH + 100)
        val result = MetadataNormalizer.artist(long)
        assertEquals(MetadataNormalizer.MAX_FIELD_LENGTH, result.length)
    }

    @Test
    fun `clamping counts unicode scalar values, not UTF-16 code units`() {
        // U+1F3B5 MUSICAL NOTE is one scalar value but a surrogate pair (two UTF-16 code units).
        // Clamping by String.length would cut this string roughly in half; clamping by scalar count
        // must not, and must never split a surrogate pair in half.
        val emoji = "🎵"
        val long = emoji.repeat(MetadataNormalizer.MAX_FIELD_LENGTH + 10)
        val result = MetadataNormalizer.artist(long)
        val scalarCount = result.codePointCount(0, result.length)
        assertEquals(MetadataNormalizer.MAX_FIELD_LENGTH, scalarCount)
        assertEquals(0, result.length % 2)
    }

    @Test
    fun `duplicate filenames with distinct titles are not conflated`() {
        // The normalizer is a pure function of its inputs — two calls with the same filename but
        // different raw titles must not collide just because the fallback path would have.
        assertEquals("First", MetadataNormalizer.title("First", "same.mp3"))
        assertEquals("Second", MetadataNormalizer.title("Second", "same.mp3"))
    }
}
