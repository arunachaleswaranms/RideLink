package com.ridelink.core.security

import com.ridelink.core.model.SpkiHash
import com.ridelink.core.testutil.Vectors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `protocol/vectors/identity/identity_vectors.json` — ADR-012 and ADR-017, TEST_PLAN §3.
 *
 * These run on both platforms against the same file. A DER length encoded one byte differently,
 * an INTEGER padded when it should not be, or a hex digest formatted in uppercase would all
 * produce a different `identity_spki_sha256` on one phone than the other — which presents to the
 * user as an unexplained `pin_mismatch` mid-ride, i.e. exactly what a real attack looks like.
 * That failure belongs here, on a laptop.
 */
class IdentityVectorTest {
    private val root: JsonObject get() = Vectors.load("identity/identity_vectors.json").jsonObject

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    private fun JsonObject.int(key: String): Int = this[key]!!.jsonPrimitive.content.toInt()

    private fun cases(
        section: String,
        key: String = "cases",
    ) = root[section]!!.jsonObject[key]!!.jsonArray.map { it.jsonObject }

    @TestFactory
    fun derLengths(): List<DynamicTest> =
        cases("der_length_vectors").map { vector ->
            DynamicTest.dynamicTest("der-length/${vector.str("name")}") {
                assertEquals(vector.str("expected_hex"), Der.encodeLength(vector.int("length")).toHex())
            }
        }

    @TestFactory
    fun derIntegers(): List<DynamicTest> =
        cases("der_integer_vectors").map { vector ->
            DynamicTest.dynamicTest("der-integer/${vector.str("name")}") {
                assertEquals(vector.str("expected_hex"), Der.integer(hexToBytes(vector.str("magnitude_hex"))).toHex())
            }
        }

    @TestFactory
    fun subjectPublicKeyInfo(): List<DynamicTest> =
        cases("spki_vectors").map { vector ->
            DynamicTest.dynamicTest("spki/${vector.str("name")}") {
                val point = hexToBytes(vector.str("point_hex"))
                val expected = vector["expected"]!!.jsonObject
                val spki = IdentityCertificate.subjectPublicKeyInfo(point)
                assertEquals(expected.str("spki_der_hex"), spki.toHex())
                assertEquals(expected.int("spki_der_bytes"), spki.size)
                assertEquals(IdentityCertificate.P256_SPKI_BYTES, spki.size)
                assertEquals(expected.str("identity_spki_sha256"), IdentityCertificate.identitySpkiSha256(point).value)
                // The Android production path hashes PublicKey.getEncoded() directly rather than
                // rebuilding from the point; both routes must land on the same pin.
                assertEquals(
                    IdentityCertificate.identitySpkiSha256(point),
                    IdentityCertificate.spkiHashOfEncoded(spki),
                )
            }
        }

    @TestFactory
    fun rejectedPublicKeyPoints(): List<DynamicTest> =
        cases("spki_vectors", "rejected_points").map { vector ->
            DynamicTest.dynamicTest("spki-rejected/${vector.str("name")}") {
                val point = hexToBytes(vector.str("point_hex"))
                val threw =
                    try {
                        IdentityCertificate.subjectPublicKeyInfo(point)
                        false
                    } catch (expected: IllegalArgumentException) {
                        true
                    }
                assertTrue(threw, "${vector.str("name")} must be rejected: ${vector.str("reason")}")
            }
        }

    @TestFactory
    fun spkiHashFormat(): List<DynamicTest> {
        val section = root["identity_spki_sha256_format_vectors"]!!.jsonObject
        val accepted =
            section["accepted"]!!.jsonArray.map { it.jsonObject }.map { vector ->
                DynamicTest.dynamicTest("spki-format-accepted/${vector.str("name")}") {
                    assertTrue(PeerTrust.isWellFormedSpkiHash(vector.str("value")))
                    // SpkiHash's own constructor must agree with PeerTrust, or one of the two is a
                    // second, weaker gate on the same value.
                    SpkiHash(vector.str("value"))
                }
            }
        val rejected =
            section["rejected"]!!.jsonArray.map { it.jsonObject }.map { vector ->
                DynamicTest.dynamicTest("spki-format-rejected/${vector.str("name")}") {
                    assertFalse(
                        PeerTrust.isWellFormedSpkiHash(vector.str("value")),
                        "${vector.str("value")} must be rejected: ${vector.str("reason")}",
                    )
                    try {
                        SpkiHash(vector.str("value"))
                        fail("SpkiHash accepted a malformed value: ${vector.str("value")}")
                    } catch (expected: IllegalArgumentException) {
                        // expected
                    }
                }
            }
        return accepted + rejected
    }

    @TestFactory
    fun tbsCertificates(): List<DynamicTest> =
        cases("tbs_certificate_vectors").map { vector ->
            DynamicTest.dynamicTest("tbs/${vector.str("name")}") {
                val input = vector["input"]!!.jsonObject
                val expected = vector["expected"]!!.jsonObject
                assertEquals(
                    IdentityCertificate.SUBJECT_COMMON_NAME,
                    input.str("subject_common_name"),
                    "the vector and the implementation must agree on the subject",
                )
                val tbs =
                    IdentityCertificate.tbsCertificate(
                        uncompressedPoint = hexToBytes(input.str("point_hex")),
                        serial = hexToBytes(input.str("serial_hex")),
                        notBefore = requireNotNull(UtcTime.parse(input.str("not_before_utc"))),
                        notAfter = requireNotNull(UtcTime.parse(input.str("not_after_utc"))),
                    )
                assertEquals(expected.str("tbs_der_hex"), tbs.toHex())
                assertEquals(expected.int("tbs_der_bytes"), tbs.size)

                expected["certificate_der_hex_with_fabricated_signature"]?.let {
                    val signature = hexToBytes(expected.str("fabricated_signature_der_hex"))
                    assertEquals(it.jsonPrimitive.content, IdentityCertificate.certificate(tbs, signature).toHex())
                }
            }
        }

    @TestFactory
    fun pinDecisions(): List<DynamicTest> =
        cases("pin_decision_vectors").map { vector ->
            DynamicTest.dynamicTest("pin/${vector.str("name")}") {
                val input = vector["input"]!!.jsonObject
                val expected = vector["expected"]!!.jsonObject
                val decision =
                    PeerTrust.decide(
                        storedPin = input["stored_pin"]?.jsonPrimitive?.contentOrNullIfJsonNull()?.let(::SpkiHash),
                        presentedSpki = SpkiHash(input.str("presented_spki")),
                        helloAdvertisedSpki = input["hello_advisory"]?.jsonPrimitive?.contentOrNullIfJsonNull()?.let(::SpkiHash),
                        certificateStructurallyValid = input["certificate_valid"]!!.jsonPrimitive.content.toBoolean(),
                    )
                val actual =
                    when (decision) {
                        is PinDecision.Trusted -> "trusted"
                        is PinDecision.PairingRequired -> "pairing_required"
                        is PinDecision.Refused -> decision.code
                    }
                assertEquals(expected.str("decision"), actual)
                expected["error_code"]?.let {
                    assertEquals(it.jsonPrimitive.content, (decision as PinDecision.Refused).code)
                }
            }
        }

    @TestFactory
    fun certificateValidity(): List<DynamicTest> =
        cases("certificate_validity_vectors").map { vector ->
            DynamicTest.dynamicTest("validity/${vector.str("name")}") {
                val notBefore = requireNotNull(UtcTime.parse(vector.str("not_before_utc")))
                val notAfter = requireNotNull(UtcTime.parse(vector.str("not_after_utc")))
                val now = requireNotNull(UtcTime.parse(vector.str("now_utc")))
                assertEquals(
                    vector["expected"]!!.jsonObject["valid"]!!.jsonPrimitive.content.toBoolean(),
                    now.isWithin(notBefore, notAfter),
                )
            }
        }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullIfJsonNull(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content
}
