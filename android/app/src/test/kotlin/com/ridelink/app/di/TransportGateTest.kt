package com.ridelink.app.di

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * This session's brief §4: `PlainControlTransportPhase1a` must never be *instantiated* in a
 * release build, not merely unused. These assert the "never called" half directly — a factory
 * that runs when it should not have is the exact bug a release build must not ship with.
 */
class TransportGateTest {
    @Test
    fun `factory is never invoked when transport is not allowed`() {
        var invoked = false
        val result =
            gatedByPlaintextTransport(allowed = false) {
                invoked = true
                "session"
            }
        assertFalse(invoked, "the plaintext-transport factory must not be instantiated at all when disallowed")
        assertNull(result)
    }

    @Test
    fun `factory is invoked exactly once when transport is allowed`() {
        var invocationCount = 0
        val result =
            gatedByPlaintextTransport(allowed = true) {
                invocationCount += 1
                "session"
            }
        assertEquals(1, invocationCount)
        assertEquals("session", result)
    }

    @Test
    fun `debug allows, release refuses -- the BuildConfig DEBUG contract`() {
        // AppContainer defaults plaintextTransportAllowed to BuildConfig.DEBUG; this pins the
        // gate's own semantics against the two literal values that flag can take.
        assertTrue(gatedByPlaintextTransport(allowed = true) { Unit } != null)
        assertNull(gatedByPlaintextTransport(allowed = false) { Unit })
    }
}
