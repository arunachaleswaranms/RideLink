package com.ridelink.core.voice

import com.ridelink.core.protocol.VoiceSessionId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [VoiceEngineGeneration] exhausted directly, because neither real [VoiceEngine] can be
 * constructed in a host unit test to exercise this rule end to end (`WebRtcVoiceEngine` needs an
 * Android `Context`; its Apple mirror needs the Apple audio stack) — see both classes' own
 * "REAL-DEVICE AUDIO GATE PENDING" notes. This is the pure logic those two `emit()` methods now
 * delegate to, so the rule itself is proven here even though the wiring around it is not.
 */
class VoiceEngineGenerationTest {
    @Test
    fun `a callback naming the active generation is accepted`() {
        assertTrue(VoiceEngineGeneration.accepts(active = GEN_A, expected = GEN_A))
    }

    @Test
    fun `a callback naming a different generation than the active one is rejected`() {
        assertFalse(VoiceEngineGeneration.accepts(active = GEN_A, expected = GEN_B))
    }

    /**
     * The exact bug this replaces: `active != null && active != expected` short-circuits to `false`
     * (accept) the moment `active` is `null` — which is precisely the torn-down state. A stopped
     * engine's `active` is `null`, and every callback from its closed peer connection must be
     * inert then, not just the ones naming a still-remembered id.
     */
    @Test
    fun `a callback arriving after the engine has been stopped is rejected, not accepted by default`() {
        assertFalse(VoiceEngineGeneration.accepts(active = null, expected = GEN_A))
    }

    @Test
    fun `a late callback from generation N cannot affect generation N plus 1`() {
        // The engine has moved on to GEN_B; a delegate call from the GEN_A peer connection,
        // queued or delayed by the platform, arrives afterward.
        assertFalse(VoiceEngineGeneration.accepts(active = GEN_B, expected = GEN_A))
    }

    private companion object {
        val GEN_A = VoiceSessionId("11111111111111111111111111111111")
        val GEN_B = VoiceSessionId("22222222222222222222222222222222")
    }
}
