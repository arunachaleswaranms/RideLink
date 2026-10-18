package com.ridelink.network.security

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-017 Amendment A1 (STATUS §4 problem 122): an identity key is usable only when Keystore has
 * authorised it for both digests. The Keystore half — that these strings are what `KeyInfo`
 * reports, and that the authorizations are enforced — is `KeystoreTlsIdentityTest` on a device.
 */
class IdentityKeyAuthorizationTest {
    @Test
    fun `the key generated through 8f47a72 — SHA-256 only — cannot sign a TLS handshake`() {
        assertFalse(IdentityKeyAuthorization.isTlsCapable(setOf("SHA-256")))
    }

    @Test
    fun `SHA-256 plus NONE is TLS-capable, whatever else is authorised`() {
        assertTrue(IdentityKeyAuthorization.isTlsCapable(setOf("SHA-256", "NONE")))
        assertTrue(IdentityKeyAuthorization.isTlsCapable(listOf("NONE", "SHA-512", "SHA-256")))
    }

    @Test
    fun `NONE alone is not enough, because the certificate must stay re-issuable around the same key`() {
        assertFalse(IdentityKeyAuthorization.isTlsCapable(setOf("NONE")))
    }

    @Test
    fun `no recorded digests is incompatible`() {
        assertFalse(IdentityKeyAuthorization.isTlsCapable(emptySet()))
    }

    @Test
    fun `the required set is exactly the two digests the amendment names`() {
        assertEquals(setOf("SHA-256", "NONE"), IdentityKeyAuthorization.REQUIRED_DIGESTS)
    }
}
