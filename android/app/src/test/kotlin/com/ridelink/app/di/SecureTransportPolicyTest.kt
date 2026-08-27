package com.ridelink.app.di

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The composition root's refusal to run the control plane in the clear (NFR-06, PROTOCOL §1).
 *
 * Replaces Phase 1a's `TransportGateTest`, which asserted that a `BuildConfig.DEBUG` check stopped
 * the plaintext transport from being *constructed*. Phase 1b deletes that transport from
 * production sources entirely — `network`'s `PlaintextTransportAbsenceTest` is the mechanical
 * guard that it stays deleted — so what is left to test here is the runtime assertion that
 * survives it.
 */
class SecureTransportPolicyTest {
    @Test
    fun `a secure channel is accepted`() {
        requireSecureControlChannel(isSecure = true, transportLabel = "TLS 1.3 / MUTUAL / SPKI-PINNED")
    }

    @Test
    fun `an insecure channel is refused before any session is assembled`() {
        val failure =
            assertFailsWith<IllegalStateException> {
                requireSecureControlChannel(isSecure = false, transportLabel = "PLAINTEXT TEST FIXTURE / NOT SECURE")
            }
        // The message has to name the transport, because the only way this ever fires is a wiring
        // mistake and the first question will be "which channel did it get?".
        assertEquals(true, failure.message?.contains("PLAINTEXT TEST FIXTURE / NOT SECURE"))
        assertEquals(true, failure.message?.contains("TLS 1.3"))
    }
}
