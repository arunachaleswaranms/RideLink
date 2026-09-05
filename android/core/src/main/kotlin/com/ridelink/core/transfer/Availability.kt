package com.ridelink.core.transfer

/**
 * Local availability for one `content_hash`, combining three independent facts: is it in the
 * Phase 3 library (imported by this user), is it in the verified transfer cache (ADR-023 §6), and
 * does the connected peer's manifest currently advertise it. Deliberately three booleans rather
 * than one pre-named enum for every combination (brief §7: "you do not need those exact enum
 * names, choose a clean domain model") — a combination is never invalid, so there is nothing an
 * enum's exhaustiveness would protect against that these three fields don't already guarantee.
 *
 * [hasCached] must only ever be set once bytes have been fully received **and** whole-file
 * SHA-256 verified **and** the cache entry committed (ADR-023 §6) — never merely because a
 * transfer reached `TRANSFERRING`. [hasRemote] is session/peer-scoped: it must be cleared, not
 * merely left stale, the moment the peer disconnects or a different peer connects (brief §6/§22).
 */
data class Availability(
    val hasLocal: Boolean,
    val hasCached: Boolean,
    val hasRemote: Boolean,
) {
    /** A UI-facing label. Not a separate source of truth — always derived from the three fields. */
    enum class Label { NONE, LOCAL, CACHED, LOCAL_AND_CACHED, REMOTE_ONLY, LOCAL_AND_REMOTE, CACHED_AND_REMOTE, ALL }

    val label: Label
        get() =
            when {
                hasLocal && hasCached && hasRemote -> Label.ALL
                hasLocal && hasRemote -> Label.LOCAL_AND_REMOTE
                hasCached && hasRemote -> Label.CACHED_AND_REMOTE
                hasLocal && hasCached -> Label.LOCAL_AND_CACHED
                hasLocal -> Label.LOCAL
                hasCached -> Label.CACHED
                hasRemote -> Label.REMOTE_ONLY
                else -> Label.NONE
            }

    /** Playable right now, without any transfer — true for either provenance (brief §19). */
    val playableLocally: Boolean get() = hasLocal || hasCached

    /** A transfer would be pointless — brief §17: never re-transfer content already held. */
    val transferWouldBeRedundant: Boolean get() = hasLocal || hasCached
}
