package com.ridelink.core.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Defense in depth: even code that bypasses `core.logging.Redactor` entirely and does
 * `"$peerId"` / string-templates a value type directly must still not leak the full value,
 * because these types redact their own `toString()`.
 */
class IdentifiersRedactionTest {
    @Test
    fun `PeerId toString never contains the full value`() {
        val id = PeerId("b7c1e0d9a4f28356")
        assertFalse(id.toString().contains(id.value))
        assertTrue(id.toString().startsWith("peer:b7c1e0"))
    }

    @Test
    fun `SpkiHash toString never contains the full hash`() {
        val hash = SpkiHash("sha256:2488a4e8a6347f0ca5e9befd679f5fe0d293de2f2cc28caf98392dfdc98aea1a")
        assertFalse(hash.toString().contains(hash.hex))
        assertTrue(hash.toString().startsWith("spki:2488a4"))
    }

    @Test
    fun `ConnTiebreak toString never contains the full value`() {
        val tiebreak = ConnTiebreak("5e2a9c40b7f13d86e0a4c95b28f7d613")
        assertFalse(tiebreak.toString().contains(tiebreak.value))
        assertTrue(tiebreak.toString().startsWith("tiebreak:5e2a9c"))
    }
}
