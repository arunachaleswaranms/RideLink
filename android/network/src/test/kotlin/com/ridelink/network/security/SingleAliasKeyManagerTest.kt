package com.ridelink.network.security

import com.ridelink.core.security.UtcTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * ADR-017 Amendment A1: after an identity replacement the keystore holds two EC identities — the
 * new one and the TLS-incompatible one, which is deliberately never deleted. TLS must present the
 * resolved one, never whichever entry the platform key manager happens to find first.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SingleAliasKeyManagerTest {
    private val password = CharArray(0)

    private class Entry(
        val privateKey: PrivateKey,
        val certificate: X509Certificate,
    )

    private fun entry(): Entry {
        val generator = KeyPairGenerator.getInstance(DeviceIdentity.KEY_ALGORITHM)
        generator.initialize(ECGenParameterSpec(DeviceIdentity.CURVE))
        val keyPair = generator.generateKeyPair()
        val signer = Signature.getInstance(DeviceIdentity.SIGNATURE_ALGORITHM).apply { initSign(keyPair.private) }
        return Entry(keyPair.private, IdentityIssuer.issue(keyPair.public, signer, UtcTime(TestTlsSupport.NOW_EPOCH_SECONDS)))
    }

    private val legacy = entry()
    private val current = entry()

    /** Both identities in one keystore, legacy inserted first — the order a replacement leaves. */
    private val keyStore =
        KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setKeyEntry(LEGACY, legacy.privateKey, password, arrayOf(legacy.certificate))
            setKeyEntry(CURRENT, current.privateKey, password, arrayOf(current.certificate))
        }

    private fun platformKeyManager(): X509ExtendedKeyManager {
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore, password)
        return factory.keyManagers.filterIsInstance<X509ExtendedKeyManager>().single()
    }

    @Test
    fun `only the pinned alias is ever offered or dereferenced`() {
        val manager = SingleAliasKeyManager(platformKeyManager(), CURRENT)

        assertEquals(CURRENT, manager.chooseServerAlias("EC", null, null))
        assertEquals(CURRENT, manager.chooseClientAlias(arrayOf("RSA", "EC"), null, null))
        assertEquals(CURRENT, manager.chooseEngineServerAlias("EC", null, null))
        assertContentEquals(arrayOf(CURRENT), manager.getServerAliases("EC", null))

        assertNull(manager.getPrivateKey(LEGACY), "the legacy key must not be reachable through the TLS credential")
        assertNull(manager.getCertificateChain(LEGACY))
        assertContentEquals(arrayOf(current.certificate), manager.getCertificateChain(CURRENT))
    }

    @Test
    fun `a key type the identity is not is declined rather than answered with the wrong key`() {
        val manager = SingleAliasKeyManager(platformKeyManager(), CURRENT)

        assertNull(manager.chooseServerAlias("RSA", null, null))
        assertNull(manager.chooseClientAlias(arrayOf("RSA"), null, null))
        assertNull(manager.getServerAliases("RSA", null))
    }

    @Test
    fun `pinning an alias the keystore does not hold fails at construction, not mid-handshake`() {
        assertFailsWith<IllegalArgumentException> { SingleAliasKeyManager(platformKeyManager(), "absent") }
    }

    @Test
    fun `a real TLS 1_3 handshake presents the pinned identity, in either direction, whatever else is stored`() =
        runBlocking {
            val currentSpki = DeviceIdentity(current.certificate, keyStore, password, CURRENT).identitySpkiSha256
            val legacySpki = DeviceIdentity(legacy.certificate, keyStore, password, LEGACY).identitySpkiSha256
            assertNotEquals(currentSpki, legacySpki)

            // Pinned to CURRENT: the peer sees CURRENT as server and as client.
            assertEquals(currentSpki, spkiSeenByPeer(DeviceIdentity(current.certificate, keyStore, password, CURRENT), asServer = true))
            assertEquals(currentSpki, spkiSeenByPeer(DeviceIdentity(current.certificate, keyStore, password, CURRENT), asServer = false))
            // And the pin, not keystore order, is what decides: pinned to LEGACY, the peer sees LEGACY.
            assertEquals(legacySpki, spkiSeenByPeer(DeviceIdentity(legacy.certificate, keyStore, password, LEGACY), asServer = true))
        }

    private suspend fun spkiSeenByPeer(
        identity: DeviceIdentity,
        asServer: Boolean,
    ) = coroutineScope {
        val peer = TestTlsSupport.freshIdentity().identity
        val (serverIdentity, clientIdentity) = if (asServer) identity to peer else peer to identity
        val listener = channel(serverIdentity).bind()
        try {
            val accepted = async(Dispatchers.IO) { listener.accept() }
            val client = withContext(Dispatchers.IO) { channel(clientIdentity).connect("127.0.0.1", listener.localPort) }
            val server = withTimeout(HANDSHAKE_BUDGET_MS) { accepted.await() }
            try {
                val peerSide = if (asServer) client else server
                assertNotNull(peerSide.security).peerIdentitySpkiSha256
            } finally {
                server.close()
                client.close()
            }
        } finally {
            listener.close()
        }
    }

    private fun channel(identity: DeviceIdentity) =
        TlsControlChannel(
            identity = identity,
            ioDispatcher = Dispatchers.IO,
            provider = TestTlsSupport.ConscryptTlsProvider,
            secureRandom = SecureRandom(),
        )

    private companion object {
        const val LEGACY = "legacy"
        const val CURRENT = "current"
        const val HANDSHAKE_BUDGET_MS = 30_000L
    }
}
