package com.ridelink.core.player

import com.ridelink.core.model.QuickId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val hash = QuickId("sha256:" + "ab".repeat(32))

class PlayerStateTest {
    @Test
    fun `a fresh state has not ended`() {
        assertFalse(PlayerState().ended)
    }

    @Test
    fun `reaching duration while playing has not ended yet`() {
        // "ended" means playback stopped at the end, not merely that position caught up while a
        // final buffer is still draining.
        assertFalse(PlayerState(quickId = hash, positionMs = 1000, durationMs = 1000, playing = true).ended)
    }

    @Test
    fun `stopped exactly at duration has ended`() {
        assertTrue(PlayerState(quickId = hash, positionMs = 1000, durationMs = 1000, playing = false).ended)
    }

    @Test
    fun `a zero-length duration never reports ended`() {
        // Duration not yet known (still loading) must not look like "finished".
        assertFalse(PlayerState(quickId = hash, positionMs = 0, durationMs = 0, playing = false).ended)
    }

    @Test
    fun `no loaded track never reports ended`() {
        assertFalse(PlayerState(quickId = null, positionMs = 0, durationMs = 0, playing = false).ended)
    }
}
