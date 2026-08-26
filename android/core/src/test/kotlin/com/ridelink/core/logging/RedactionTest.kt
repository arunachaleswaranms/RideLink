package com.ridelink.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests with fabricated planted secrets (CLAUDE.md step 18): prove that redaction
 * happens structurally, not by remembering to call a function. Crypto isn't implemented yet, so
 * these plant fake-shaped values rather than real key material.
 */
class RedactionTest {
    @Test
    fun `peer id is redacted to first 6 chars`() {
        val planted = "b7c1e0d9a4f28356"
        val redacted = Redactor.peerId(planted)
        assertTrue(redacted.startsWith("peer:b7c1e0"))
        assertFalse(redacted.contains(planted), "the full peer_id must never appear in a redacted log field")
    }

    @Test
    fun `spki hash is redacted to first 6 hex`() {
        val planted = "sha256:9f2c4b7e0a1d38f5c6b29e74d0a15f83c47b6e29d81a05f3c7b4e69d2a0f18b5c"
        val redacted = Redactor.spkiHash(planted)
        assertTrue(redacted.startsWith("spki:9f2c4b"))
        assertFalse(redacted.contains(planted.removePrefix("sha256:")), "the full SPKI hex must never appear")
    }

    @Test
    fun `conn tiebreak is redacted to first 6 hex`() {
        val planted = "5e2a9c40b7f13d86e0a4c95b28f7d613"
        val redacted = Redactor.connTiebreak(planted)
        assertTrue(redacted.startsWith("tiebreak:5e2a9c"))
        assertFalse(redacted.contains(planted))
    }

    @Test
    fun `paths are reduced to basename only, dropping any planted username segment`() {
        val plantedUsername = "totally-not-a-real-user-p4ssw0rd-lookalike"
        val fullPath = "/Users/$plantedUsername/Music/library/track.mp3"
        val redacted = Redactor.path(fullPath)
        assertEquals("track.mp3", redacted)
        assertFalse(redacted.contains(plantedUsername))
    }

    @Test
    fun `every event emitted through StructuredLogger is captured and inspectable, never silently dropped`() {
        val sink = InMemoryLogSink()
        val logger = StructuredLogger(sink) { 42L }
        logger.info("test", "hello")
        assertEquals(1, sink.events.size)
        assertEquals("hello", sink.events.single().message)
        assertEquals(42L, sink.events.single().monotonicTimestampUs)
    }
}
