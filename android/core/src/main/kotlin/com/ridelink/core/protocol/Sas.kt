package com.ridelink.core.protocol

/**
 * The six-digit SAS, exact construction per PROTOCOL §4.5.1. Pure integer/byte math only — the
 * TLS exporter call itself is a Phase 1b concern (ADR-007 Amendment A1); this function takes the
 * already-exported bytes.
 */
object Sas {
    /**
     * PROTOCOL §4.5.1: ASCII, 24 bytes, no trailing NUL. Lives here rather than in each platform's
     * transport so the two cannot drift — a single differing character produces two different
     * six-digit codes, which to the users is indistinguishable from a man-in-the-middle.
     */
    const val EXPORTER_LABEL: String = "EXPORTER-RideLink-SAS-v1"

    /**
     * PROTOCOL §4.5.1: a fixed 32 bytes are exported everywhere, even though only the first 4 are
     * used, so the exporter call itself is identical on both platforms. Bytes 4…31 are reserved.
     */
    const val EXPORTER_LENGTH_BYTES: Int = 32

    private const val MODULUS = 1_000_000L
    private const val SAS6_DIGITS = 6
    private const val EXPORTER_PREFIX_BYTES = 4
    private const val BYTE_MASK = 0xFFL
    private const val SHIFT_3RD_BYTE = 24
    private const val SHIFT_2ND_BYTE = 16
    private const val SHIFT_1ST_BYTE = 8

    /**
     * @param exporterOutput at least 4 bytes; only the first 4 (big-endian) are used. Bytes
     *   beyond index 3 are accepted and ignored (protocol/vectors/sas/ "tail-bytes-ignored").
     */
    @Suppress("MagicNumber") // byte indices 0..3 into a fixed 4-byte prefix, not tunable constants
    fun deriveSas6(exporterOutput: ByteArray): String {
        require(exporterOutput.size >= EXPORTER_PREFIX_BYTES) { "exporter output must be at least 4 bytes" }
        val n =
            ((exporterOutput[0].toLong() and BYTE_MASK) shl SHIFT_3RD_BYTE) or
                ((exporterOutput[1].toLong() and BYTE_MASK) shl SHIFT_2ND_BYTE) or
                ((exporterOutput[2].toLong() and BYTE_MASK) shl SHIFT_1ST_BYTE) or
                (exporterOutput[3].toLong() and BYTE_MASK)
        val value = n % MODULUS
        return value.toString().padStart(SAS6_DIGITS, '0')
    }
}
