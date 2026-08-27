package com.ridelink.core.security

/**
 * A deliberately tiny DER **encoder**. Not an ASN.1 framework, and not a parser
 * ([ADR-017](../../../../../../../docs/DECISIONS/ADR-017-identity-key-and-certificate.md) §3):
 * it emits exactly the structures RideLink's one identity certificate needs and nothing else.
 *
 * It exists because iOS has no first-party API that *builds* a certificate, and because Android's
 * `KeyGenParameterSpec` can only issue one at key-generation time — which makes ADR-012's
 * "re-issue a certificate around the same key" behaviour unreachable there. Both platforms
 * therefore use this same encoder, mirrored line for line in `RideLinkCore.Security.Der`, and
 * every structure it emits is pinned by `protocol/vectors/identity/`.
 *
 * Rules, all of which the vectors check:
 * - definite lengths only, always in **minimal** form (short form below 128, else the shortest long form);
 * - INTEGERs are non-negative, minimally encoded, with a single 0x00 pad added only when the top bit would otherwise make the value negative;
 * - no indefinite lengths, no BER, no re-ordering, no optional-field cleverness.
 *
 * Cryptographic signing is never done here — it is always the platform's (`Signature` over an
 * Android Keystore key, `SecKeyCreateSignature` over a Keychain key). This file only arranges
 * bytes.
 */
object Der {
    private const val MAX_SHORT_FORM_LENGTH = 0x7F
    private const val LONG_FORM_FLAG = 0x80
    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8
    private const val HIGH_BIT = 0x80

    const val TAG_BOOLEAN: Int = 0x01
    const val TAG_INTEGER: Int = 0x02
    const val TAG_BIT_STRING: Int = 0x03
    const val TAG_OCTET_STRING: Int = 0x04
    const val TAG_OBJECT_IDENTIFIER: Int = 0x06
    const val TAG_UTF8_STRING: Int = 0x0C
    const val TAG_UTC_TIME: Int = 0x17
    const val TAG_SEQUENCE: Int = 0x30
    const val TAG_SET: Int = 0x31
    private const val TAG_CONTEXT_CONSTRUCTED: Int = 0xA0

    /** DER definite length, minimal form. The likeliest place two hand-written encoders diverge. */
    fun encodeLength(length: Int): ByteArray {
        require(length >= 0) { "DER length cannot be negative" }
        if (length <= MAX_SHORT_FORM_LENGTH) return byteArrayOf(length.toByte())
        val body = mutableListOf<Byte>()
        var remaining = length
        while (remaining > 0) {
            body.add(0, (remaining and BYTE_MASK).toByte())
            remaining = remaining ushr BITS_PER_BYTE
        }
        return byteArrayOf((LONG_FORM_FLAG or body.size).toByte()) + body.toByteArray()
    }

    /** tag ‖ length ‖ body. */
    fun tlv(
        tag: Int,
        body: ByteArray,
    ): ByteArray = byteArrayOf(tag.toByte()) + encodeLength(body.size) + body

    fun sequence(vararg items: ByteArray): ByteArray = tlv(TAG_SEQUENCE, concat(items))

    fun set(vararg items: ByteArray): ByteArray = tlv(TAG_SET, concat(items))

    fun objectIdentifier(encodedOid: ByteArray): ByteArray = tlv(TAG_OBJECT_IDENTIFIER, encodedOid)

    /** BIT STRING with no unused trailing bits — the only form this certificate needs. */
    fun bitString(body: ByteArray): ByteArray = tlv(TAG_BIT_STRING, byteArrayOf(0x00) + body)

    fun octetString(body: ByteArray): ByteArray = tlv(TAG_OCTET_STRING, body)

    fun utf8String(value: String): ByteArray = tlv(TAG_UTF8_STRING, value.toByteArray(Charsets.UTF_8))

    /** UTCTime, `YYMMDDHHMMSSZ`. Callers pass an already-formatted [UtcTime]. */
    fun utcTime(value: String): ByteArray {
        require(UTC_TIME_FORMAT.matches(value)) { "UTCTime must be YYMMDDHHMMSSZ" }
        return tlv(TAG_UTC_TIME, value.toByteArray(Charsets.US_ASCII))
    }

    fun boolean(value: Boolean): ByteArray = tlv(TAG_BOOLEAN, byteArrayOf(if (value) 0xFF.toByte() else 0x00))

    /** `[n] EXPLICIT` — a constructed context-specific tag wrapping an already-encoded value. */
    fun explicit(
        tagNumber: Int,
        body: ByteArray,
    ): ByteArray {
        require(tagNumber in 0..0x1E) { "only low-tag-number context tags are supported" }
        return tlv(TAG_CONTEXT_CONSTRUCTED or tagNumber, body)
    }

    /**
     * Non-negative INTEGER in minimal two's-complement form: redundant leading zero bytes are
     * stripped, and exactly one 0x00 is prepended when the leading bit would otherwise be read as
     * a sign bit.
     */
    fun integer(magnitude: ByteArray): ByteArray {
        var start = 0
        while (start + 1 < magnitude.size && magnitude[start] == 0x00.toByte() &&
            (magnitude[start + 1].toInt() and HIGH_BIT) == 0
        ) {
            start++
        }
        val trimmed = if (magnitude.isEmpty()) byteArrayOf(0x00) else magnitude.copyOfRange(start, magnitude.size)
        val body = if ((trimmed[0].toInt() and HIGH_BIT) != 0) byteArrayOf(0x00) + trimmed else trimmed
        return tlv(TAG_INTEGER, body)
    }

    fun integer(value: Int): ByteArray {
        require(value >= 0) { "only non-negative INTEGERs are encoded" }
        if (value == 0) return integer(byteArrayOf(0x00))
        val body = mutableListOf<Byte>()
        var remaining = value
        while (remaining > 0) {
            body.add(0, (remaining and BYTE_MASK).toByte())
            remaining = remaining ushr BITS_PER_BYTE
        }
        return integer(body.toByteArray())
    }

    private fun concat(items: Array<out ByteArray>): ByteArray {
        val out = ByteArray(items.sumOf { it.size })
        var offset = 0
        for (item in items) {
            item.copyInto(out, offset)
            offset += item.size
        }
        return out
    }

    private val UTC_TIME_FORMAT = Regex("^[0-9]{12}Z$")
}

/** The DER object identifiers this certificate uses, and only those. */
object Oid {
    /** 1.2.840.10045.2.1 — id-ecPublicKey. */
    val EC_PUBLIC_KEY: ByteArray = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01)

    /** 1.2.840.10045.3.1.7 — prime256v1 / secp256r1 / NIST P-256. */
    val PRIME256V1: ByteArray = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07)

    /** 1.2.840.10045.4.3.2 — ecdsa-with-SHA256. Parameters MUST be absent (RFC 5758). */
    val ECDSA_WITH_SHA256: ByteArray = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02)

    /** 2.5.4.3 — id-at-commonName. */
    val COMMON_NAME: ByteArray = byteArrayOf(0x55, 0x04, 0x03)

    /** 2.5.29.19 — basicConstraints. */
    val BASIC_CONSTRAINTS: ByteArray = byteArrayOf(0x55, 0x1D, 0x13)

    /** 2.5.29.15 — keyUsage. */
    val KEY_USAGE: ByteArray = byteArrayOf(0x55, 0x1D, 0x0F)
}
