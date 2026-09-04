package com.ridelink.data.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Bounds embedded artwork before it ever reaches disk or a database row — this phase's brief §18/
 * §20's "bound dimensions, decoded memory, stored size" and "malformed artwork must not crash
 * indexing."
 *
 * Untrusted input: an embedded picture is attacker-shaped the moment it comes from a file the user
 * merely *possesses* (brief §20's "even local files are untrusted input"), and
 * [android.graphics.BitmapFactory] decoding a hostile image is a real, documented OOM vector if the
 * dimensions are not checked before a full decode is attempted.
 */
object ArtworkProcessor {
    /** No music-player artwork needs to be larger than this to fill a phone screen at arm's length. */
    private const val MAX_DIMENSION_PX = 1024

    /** A cap on the *encoded* output, independent of dimensions — a highly compressible image at
     *  the maximum dimensions could otherwise still balloon on a pathological re-encode. */
    private const val MAX_OUTPUT_BYTES = 512 * 1024

    private const val JPEG_QUALITY = 85

    /**
     * @return bounded JPEG bytes, or null if [rawBytes] is empty, not decodable as an image at all,
     *   or a decoded bitmap could not be produced — never a thrown exception.
     */
    fun processToBoundedJpeg(rawBytes: ByteArray?): ByteArray? {
        if (rawBytes == null || rawBytes.isEmpty()) return null
        return decodeBounded(rawBytes)?.let(::compressBounded)
    }

    /** Null if [rawBytes] is not a decodable image at all — checked via bounds-only decoding first
     *  so a hostile, enormous image is never fully decoded just to measure it. */
    private fun decodeBounded(rawBytes: ByteArray): Bitmap? {
        val bounds =
            BitmapFactory
                .Options()
                .apply { inJustDecodeBounds = true }
                .also { BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, it) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION_PX)
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return runCatching { BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions) }.getOrNull()
    }

    /**
     * A pathological source image that still exceeds the byte cap after JPEG compression at
     * [MAX_DIMENSION_PX] is treated as "no usable artwork" rather than truncated — a truncated JPEG
     * is a corrupt image, not a smaller one.
     */
    private fun compressBounded(decoded: Bitmap): ByteArray? {
        val scaled = scaleDownIfNeeded(decoded, MAX_DIMENSION_PX)
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        if (scaled !== decoded) decoded.recycle()
        scaled.recycle()
        val bytes = output.toByteArray()
        return if (bytes.size <= MAX_OUTPUT_BYTES) bytes else null
    }

    /** `inSampleSize` must be a power of two for `BitmapFactory` to honour it exactly. */
    private fun computeSampleSize(
        width: Int,
        height: Int,
        maxDimension: Int,
    ): Int {
        var sample = 1
        while (width / sample > maxDimension || height / sample > maxDimension) {
            sample *= 2
        }
        return sample
    }

    private fun scaleDownIfNeeded(
        bitmap: Bitmap,
        maxDimension: Int,
    ): Bitmap {
        if (bitmap.width <= maxDimension && bitmap.height <= maxDimension) return bitmap
        val scale = maxDimension.toDouble() / maxOf(bitmap.width, bitmap.height)
        val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }
}
