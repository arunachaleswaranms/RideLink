package com.ridelink.network.security

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The mechanical guard behind "production networking must not fall back to plaintext"
 * (NFR-06, PROTOCOL §1, and this phase's brief).
 *
 * Phase 1a enforced that with a `BuildConfig.DEBUG` check around *constructing* the plaintext
 * transport. Phase 1b deletes the plaintext transport from production sources entirely, which is a
 * stronger guarantee — but only for as long as it stays true. A future session adding "just a
 * quick raw socket" to `network/src/main` would silently undo it, and no existing test would
 * notice, because every behavioural test would still pass.
 *
 * So this test reads the production source tree and fails on the reappearance. It is the same
 * technique as `DiscoveryPrivacyTest`'s TXT-record assertion: check the property that actually
 * matters, against the thing that actually ships, rather than against a comment saying it is fine.
 */
class PlaintextTransportAbsenceTest {
    private val mainSources: List<File> =
        File("src/main/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    @Test
    fun `production sources contain at least one file to scan`() {
        // Guards the guard: a wrong working directory would make every assertion below vacuous.
        assertTrue(mainSources.size > 5, "expected to find network's production sources, found ${mainSources.size}")
    }

    @Test
    fun `no production source constructs a raw socket outside the TLS channel`() {
        // TlsControlChannel legitimately creates a plain Socket and connects it *before* starting
        // TLS on top of it, because SSLSocketFactory.createSocket(host, port) offers no connect
        // timeout. That is the one permitted occurrence, and it is permitted by name.
        val allowed = setOf("TlsControlChannel.kt")
        val offenders =
            mainSources.filter { file ->
                file.name !in allowed &&
                    RAW_SOCKET_CONSTRUCTION.containsMatchIn(file.readText())
            }
        assertEquals(
            emptyList(),
            offenders.map { it.name },
            "a production source constructs a raw socket. Control-plane bytes are TLS 1.3 only " +
                "(PROTOCOL §1, NFR-06); plaintext sockets belong in src/test. If this is a genuinely " +
                "new secure transport, add it to the allowlist above and say why in an ADR.",
        )
    }

    @Test
    fun `no production source references the plaintext test fixture`() {
        val offenders = mainSources.filter { "PlaintextControlChannelFixture" in it.readText() }
        assertEquals(
            emptyList(),
            offenders.map { it.name },
            "the plaintext fixture is a test-only type; a production reference would not even compile, " +
                "but naming it in production sources is a sign someone is trying to.",
        )
    }

    @Test
    fun `the only production ControlChannel reports itself secure`() {
        val implementations =
            mainSources.filter { file ->
                val text = file.readText()
                // An implementation, not a mention: `class X : ControlChannel {`. A constructor
                // parameter typed `channel: ControlChannel,` must not count, or this test would
                // flag ControlSessionManager for merely being given one.
                IMPLEMENTS_CONTROL_CHANNEL.containsMatchIn(text) && "interface ControlChannel" !in text
            }
        assertEquals(
            listOf("TlsControlChannel.kt"),
            implementations.map { it.name }.sorted(),
            "exactly one production ControlChannel is expected, and it is the TLS one",
        )
        val tls = implementations.single().readText()
        if (!OVERRIDES_IS_SECURE_TRUE.containsMatchIn(tls)) {
            fail("TlsControlChannel must declare `override val isSecure: Boolean = true`")
        }
    }

    private companion object {
        /** `Socket(` / `ServerSocket(` construction, excluding `SSLSocket`/`SSLServerSocket` types. */
        val RAW_SOCKET_CONSTRUCTION = Regex("""(?<![A-Za-z])(?<!SSL)(Server)?Socket\(""")
        val OVERRIDES_IS_SECURE_TRUE = Regex("""override\s+val\s+isSecure\s*:\s*Boolean\s*=\s*true""")
        val IMPLEMENTS_CONTROL_CHANNEL = Regex(""":\s*ControlChannel\s*\{""")
    }
}
