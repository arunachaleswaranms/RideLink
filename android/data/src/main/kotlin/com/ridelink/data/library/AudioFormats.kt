package com.ridelink.data.library

/**
 * REQUIREMENTS §9.1: MP3/AAC/M4A required (P0); FLAC allowed where the platform decodes it
 * natively, which `MediaExtractor`/`MediaCodec` has since API 26 (well under this project's
 * `minSdk 31`) — so FLAC needs no extra code here, only inclusion in this allowlist.
 *
 * This is an **extension gate**, checked before any attempt to open a file — the cheap, first-line
 * classification this phase's brief §9 requires ("indexing and playback capability are distinct: a
 * file that cannot be decoded must not crash the indexer"). A file passing this gate can still turn
 * out to be [com.ridelink.core.library.DecodeStatus.CORRUPT]; a file failing it is
 * [com.ridelink.core.library.DecodeStatus.UNSUPPORTED] without ever being opened.
 */
object AudioFormats {
    private val SUPPORTED_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac")

    fun isSupportedExtension(filename: String): Boolean {
        val dot = filename.lastIndexOf('.')
        if (dot < 0 || dot == filename.length - 1) return false
        return filename.substring(dot + 1).lowercase() in SUPPORTED_EXTENSIONS
    }

    /** For the MediaStore scan path, which filters by MIME type rather than filename. */
    fun isSupportedMimeType(mimeType: String?): Boolean =
        when (mimeType?.lowercase()) {
            "audio/mpeg", "audio/mp4", "audio/aac", "audio/x-m4a", "audio/flac", "audio/x-flac" -> true
            else -> false
        }
}
