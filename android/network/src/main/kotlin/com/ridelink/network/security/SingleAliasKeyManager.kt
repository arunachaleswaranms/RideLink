package com.ridelink.network.security

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/**
 * Presents exactly one keystore alias as the TLS credential, whatever else the keystore holds
 * (ADR-017 Amendment A1).
 *
 * A `KeyManagerFactory` initialised from a whole keystore may choose *any* entry of a matching key
 * type. Android Keystore holds every alias the app ever created, and after an identity replacement
 * that includes the previous, TLS-incompatible key — which is deliberately never deleted. Left to
 * choose, JSSE could present that key, and the handshake would fail again, or a peer would be
 * shown an identity this device no longer uses. Pinning the alias makes "which identity does TLS
 * present?" the same answer as "which identity did [AndroidKeystoreIdentityStore] resolve?".
 *
 * Nothing here touches key material: every call delegates to the platform key manager, which
 * returns the same opaque Keystore handle it always did.
 */
internal class SingleAliasKeyManager(
    private val delegate: X509ExtendedKeyManager,
    private val alias: String,
) : X509ExtendedKeyManager() {
    private val keyAlgorithm: String =
        requireNotNull(delegate.getPrivateKey(alias)) { "no private key under the resolved identity alias" }.algorithm

    init {
        require(!delegate.getCertificateChain(alias).isNullOrEmpty()) { "no certificate under the resolved identity alias" }
    }

    /**
     * Key types arrive as a bare algorithm ("EC") from Conscrypt and the JDK, and as
     * `<key>_<signature>` ("EC_EC") from older JSSE paths; null means "any".
     */
    private fun matches(keyType: String?): Boolean = keyType == null || keyType == keyAlgorithm || keyType.startsWith("${keyAlgorithm}_")

    private fun ownAliasIf(matches: Boolean): String? = if (matches) alias else null

    override fun getClientAliases(
        keyType: String?,
        issuers: Array<out Principal>?,
    ): Array<String>? = if (matches(keyType)) arrayOf(alias) else null

    override fun getServerAliases(
        keyType: String?,
        issuers: Array<out Principal>?,
    ): Array<String>? = if (matches(keyType)) arrayOf(alias) else null

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = ownAliasIf(keyType == null || keyType.any(::matches))

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = ownAliasIf(matches(keyType))

    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = ownAliasIf(keyType == null || keyType.any(::matches))

    override fun chooseEngineServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = ownAliasIf(matches(keyType))

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == this.alias) delegate.getCertificateChain(alias) else null

    override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == this.alias) delegate.getPrivateKey(alias) else null
}
