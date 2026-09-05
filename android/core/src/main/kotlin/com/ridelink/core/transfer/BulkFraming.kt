package com.ridelink.core.transfer

/**
 * PROTOCOL §8.2 — the RLB1 bulk-frame header: `magic(4) | chunk_index(uint32 BE) |
 * byte_length(uint32 BE) | payload(<= 64 KiB)`. Pure byte-array parsing, no socket I/O — the
 * network module streams bytes in and this decides what a complete buffer contains, per
 * `protocol/vectors/bulk-framing/`.
 *
 * Deliberately in `core`, not `network`: nothing here is Android/iOS-specific, and a pure parser
 * is what the vectors can pin without any platform harness. The one thing this type does not
 * attempt is deciding what bytes arrive when — arbitrary read-boundary splitting is a transport
 * concern the caller handles by feeding [parseAll] a growing buffer and keeping [ParseResult]'s
 * leftover for the next read.
 */
object BulkFraming {
    private val MAGIC = byteArrayOf('R'.code.toByte(), 'L'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
    const val HEADER_LEN = 12
    const val MAX_CHUNK_PAYLOAD_BYTES = 65_536

    private const val BYTES_PER_U32 = 4
    private const val CHUNK_INDEX_OFFSET = BYTES_PER_U32
    private const val BYTE_LENGTH_OFFSET = 2 * BYTES_PER_U32
    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8

    data class Frame(
        /** Read as unsigned — up to 0xFFFFFFFF, hence `Long` rather than `Int`. */
        val chunkIndex: Long,
        val payload: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean = other is Frame && chunkIndex == other.chunkIndex && payload.contentEquals(other.payload)

        override fun hashCode(): Int = 31 * chunkIndex.hashCode() + payload.contentHashCode()
    }

    sealed class ParseResult {
        /** At least zero complete frames, plus any bytes not yet consumed (the next frame's partial header/payload). */
        data class Parsed(
            val frames: List<Frame>,
            val leftover: ByteArray,
        ) : ParseResult() {
            override fun equals(other: Any?): Boolean = other is Parsed && frames == other.frames && leftover.contentEquals(other.leftover)

            override fun hashCode(): Int = 31 * frames.hashCode() + leftover.contentHashCode()
        }

        /** Not enough bytes yet to extract even one frame. Wait for more; never a parse error. */
        object Incomplete : ParseResult()

        /** A fatal header violation — the connection must be closed, not merely this frame dropped. */
        data class Invalid(
            val reason: String,
        ) : ParseResult()
    }

    /**
     * Parses as many complete frames as [buffer] contains. Returns [ParseResult.Incomplete] only
     * when **zero** frames could be extracted and more bytes are needed; a buffer that yields one
     * or more complete frames is always [ParseResult.Parsed], even if what remains after them is
     * itself an incomplete header or payload — that remainder becomes `leftover` for the next call.
     *
     * The `byte_length` bound is checked **before** any allocation sized by it — brief §10/§30's
     * "no unchecked allocation based directly on remote length fields." Both an implausibly large
     * unsigned value (0xFFFFFFFF) and one whose top bit would look negative under a signed 32-bit
     * read (0x80000000) are simply "too large": [readU32] never produces a negative `Long`, so
     * there is no separate "negative length" case to handle.
     */
    @Suppress("ReturnCount")
    fun parseAll(buffer: ByteArray): ParseResult {
        if (buffer.isEmpty()) return ParseResult.Incomplete
        val frames = mutableListOf<Frame>()
        var offset = 0
        while (offset < buffer.size) {
            val remaining = buffer.size - offset
            if (remaining < HEADER_LEN) {
                return if (frames.isEmpty()) ParseResult.Incomplete else ParseResult.Parsed(frames, buffer.copyOfRange(offset, buffer.size))
            }
            if (!magicMatches(buffer, offset)) return ParseResult.Invalid("bad_magic")
            val chunkIndex = readU32(buffer, offset + CHUNK_INDEX_OFFSET)
            val byteLength = readU32(buffer, offset + BYTE_LENGTH_OFFSET)
            if (byteLength > MAX_CHUNK_PAYLOAD_BYTES) return ParseResult.Invalid("chunk_too_large")
            val frameTotal = HEADER_LEN + byteLength
            if ((buffer.size - offset).toLong() < frameTotal) {
                return if (frames.isEmpty()) ParseResult.Incomplete else ParseResult.Parsed(frames, buffer.copyOfRange(offset, buffer.size))
            }
            val payloadStart = offset + HEADER_LEN
            val payloadEnd = payloadStart + byteLength.toInt()
            frames.add(Frame(chunkIndex, buffer.copyOfRange(payloadStart, payloadEnd)))
            offset = payloadEnd
        }
        return ParseResult.Parsed(frames, ByteArray(0))
    }

    private fun magicMatches(
        buffer: ByteArray,
        offset: Int,
    ): Boolean {
        for (i in MAGIC.indices) if (buffer[offset + i] != MAGIC[i]) return false
        return true
    }

    /** Big-endian uint32 as a non-negative `Long` — never a signed 32-bit misread. */
    private fun readU32(
        buffer: ByteArray,
        offset: Int,
    ): Long {
        var result = 0L
        for (i in 0 until BYTES_PER_U32) {
            result = (result shl BITS_PER_BYTE) or (buffer[offset + i].toLong() and BYTE_MASK.toLong())
        }
        return result
    }

    /** Encodes one outgoing frame. `payload.size` must already be within [MAX_CHUNK_PAYLOAD_BYTES]. */
    fun encodeFrame(
        chunkIndex: Long,
        payload: ByteArray,
    ): ByteArray {
        require(payload.size <= MAX_CHUNK_PAYLOAD_BYTES) { "payload exceeds MAX_CHUNK_PAYLOAD_BYTES" }
        val out = ByteArray(HEADER_LEN + payload.size)
        MAGIC.copyInto(out, 0)
        writeU32(out, CHUNK_INDEX_OFFSET, chunkIndex)
        writeU32(out, BYTE_LENGTH_OFFSET, payload.size.toLong())
        payload.copyInto(out, HEADER_LEN)
        return out
    }

    private fun writeU32(
        out: ByteArray,
        offset: Int,
        value: Long,
    ) {
        val mask = BYTE_MASK.toLong()
        for (i in 0 until BYTES_PER_U32) {
            val shift = (BYTES_PER_U32 - 1 - i) * BITS_PER_BYTE
            out[offset + i] = ((value ushr shift) and mask).toByte()
        }
    }
}
