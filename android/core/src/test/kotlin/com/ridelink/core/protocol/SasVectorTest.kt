package com.ridelink.core.protocol

import com.ridelink.core.testutil.Vectors
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** protocol/vectors/sas/sas_vectors.json, PROTOCOL §4.5.1-4.5.2, TEST_PLAN §2/§11. */
class SasVectorTest {
    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte() }

    @TestFactory
    fun valueVectors(): List<DynamicTest> {
        val root = Vectors.load("sas/sas_vectors.json").jsonObject
        return root["value_vectors"]!!.jsonArray.map { element ->
            val vector = element.jsonObject
            val name = vector["name"]!!.jsonPrimitive.content
            DynamicTest.dynamicTest(name) {
                val hex = vector["input"]!!.jsonObject["exporter_output_hex"]!!.jsonPrimitive.content
                val expectedSas6 = vector["expected"]!!.jsonObject["sas6"]!!.jsonPrimitive.content
                val actual = Sas.deriveSas6(hexToBytes(hex))
                assertEquals(expectedSas6, actual)
                assertEquals(6, actual.length, "sas6 must always be exactly 6 characters")
                assertTrue(actual.all { it.isDigit() }, "sas6 must be all decimal digits")
            }
        }
    }

    @TestFactory
    fun propertyVectors(): List<DynamicTest> {
        val root = Vectors.load("sas/sas_vectors.json").jsonObject
        val propertyVectors = root["property_vectors"]!!.jsonArray

        val tailBytesIgnored =
            DynamicTest.dynamicTest("tail-bytes-ignored") {
                val vector = propertyVectors.first { it.jsonObject["name"]!!.jsonPrimitive.content == "tail-bytes-ignored" }.jsonObject
                val input = vector["input"]!!.jsonObject
                val a = Sas.deriveSas6(hexToBytes(input["exporter_output_hex_a"]!!.jsonPrimitive.content))
                val b = Sas.deriveSas6(hexToBytes(input["exporter_output_hex_b"]!!.jsonPrimitive.content))
                assertEquals(a, b, "differing bytes 4..31 must not change sas6")
            }

        val outputIsSixCharacters =
            DynamicTest.dynamicTest("output-is-six-characters") {
                val valueVectors = root["value_vectors"]!!.jsonArray
                for (v in valueVectors) {
                    val hex =
                        v.jsonObject["input"]!!
                            .jsonObject["exporter_output_hex"]!!
                            .jsonPrimitive.content
                    val sas6 = Sas.deriveSas6(hexToBytes(hex))
                    assertEquals(6, sas6.length, "vector ${v.jsonObject["name"]} produced a non-6-digit sas6")
                    assertTrue(sas6.all { it in '0'..'9' })
                }
            }

        return listOf(tailBytesIgnored, outputIsSixCharacters)
    }
}
