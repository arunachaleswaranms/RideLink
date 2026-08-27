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

    // CI-stabilization session's brief §11: the invariant must be impossible to override — a
    // caller requesting `true` must still be blocked in a Release build. All four combinations,
    // asserted directly against the pure function `AppContainer` actually uses.

    @Test
    fun `debug plus requested true is allowed`() {
        assertTrue(effectivePlaintextTransportAllowed(isDebugBuild = true, requested = true))
    }

    @Test
    fun `debug plus requested false is blocked`() {
        assertFalse(effectivePlaintextTransportAllowed(isDebugBuild = true, requested = false))
    }

    @Test
    fun `release plus requested true is blocked -- the non-bypassable case`() {
        assertFalse(effectivePlaintextTransportAllowed(isDebugBuild = false, requested = true))
    }

    @Test
    fun `release plus requested false is blocked`() {
        assertFalse(effectivePlaintextTransportAllowed(isDebugBuild = false, requested = false))
    }
}
