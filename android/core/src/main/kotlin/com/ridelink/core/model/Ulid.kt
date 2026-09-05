package com.ridelink.core.model

import java.security.SecureRandom

/**
 * Generates a fresh identifier in the 26-character Crockford-base32 shape [ManifestId]/
 * [TransferId] (and PROTOCOL's `session_id`/`msg_id`) already require.
 *
 * **Not** a spec-faithful monotonic ULID (real ULIDs encode a sortable 48-bit millisecond
 * timestamp; this is 130 bits of uniform CSPRNG output). Nothing in this codebase depends on
 * timestamp-sortability for a [ManifestId] or [TransferId] — only on the shape and on
 * unpredictability (brief §24: "unpredictable if used for authorization… bounded canonical
 * encoding"), both of which this satisfies. `network.control.ControlHandshake.freshSessionId`
 * already documents the same "shape now, real ULID semantics later if ever needed" stance for
 * `session_id`; this is that same low-risk follow-up, scoped to exactly the two new identifier
 * types that added a `require`d format.
 */
object Ulid {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val LENGTH = 26
    private val random = SecureRandom()

    fun generate(): String = CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }.concatToString()
}
