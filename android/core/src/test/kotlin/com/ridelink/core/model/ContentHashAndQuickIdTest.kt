package com.ridelink.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private const val VALID_HASH = "sha256:1f3ac9d9e5cba5cf5f4c5da8a2b1e6c9d0f3a7b2c4e6f8a0b2c4e6f8a0b2c4e6"

class ContentHashAndQuickIdTest {
    @Test
    fun `accepts a well-formed sha256 value`() {
        assertEquals(VALID_HASH, ContentHash(VALID_HASH).value)
        assertEquals(VALID_HASH, QuickId(VALID_HASH).value)
    }

    @Test
    fun `hex strips the prefix`() {
        assertEquals(VALID_HASH.removePrefix("sha256:"), ContentHash(VALID_HASH).hex)
    }

    @Test
    fun `rejects a missing prefix`() {
        assertFailsWith<IllegalArgumentException> { ContentHash(VALID_HASH.removePrefix("sha256:")) }
    }

    @Test
    fun `rejects uppercase hex`() {
        assertFailsWith<IllegalArgumentException> { ContentHash(VALID_HASH.uppercase()) }
    }

    @Test
    fun `rejects the wrong length`() {
        assertFailsWith<IllegalArgumentException> { ContentHash("sha256:1234") }
        assertFailsWith<IllegalArgumentException> { QuickId("sha256:1234") }
    }

    @Test
    fun `parse returns null instead of throwing on malformed input`() {
        assertNull(ContentHash.parse("not-a-hash"))
        assertNull(QuickId.parse("not-a-hash"))
        assertEquals(VALID_HASH, ContentHash.parse(VALID_HASH)?.value)
        assertEquals(VALID_HASH, QuickId.parse(VALID_HASH)?.value)
    }
}
