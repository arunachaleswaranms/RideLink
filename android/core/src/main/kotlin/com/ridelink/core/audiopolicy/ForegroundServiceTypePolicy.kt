package com.ridelink.core.audiopolicy

/**
 * The two foreground-service types `RideForegroundService` (ARCHITECTURE §6.4) can ever hold, kept
 * as a platform-free enum here rather than `android.content.pm.ServiceInfo`'s raw int flags — the
 * mapping to those flags is the one line of platform code that reads this.
 */
enum class ForegroundServiceTypeNeed {
    MICROPHONE,
    MEDIA_PLAYBACK,
}

/**
 * Phase 3's addition to the one ride foreground service: **music-only playback must work without
 * the microphone/intercom being active** (this phase's brief §16), and the reverse already held
 * (Phase 2b runs with no player at all). Both facts are true or false independently, so the type set
 * is a pure function of both, never of one assumed from the other.
 *
 * A service holding neither type is never a real state this policy produces — the caller stops the
 * service entirely once both are false, which is a lifecycle decision this pure function does not
 * make (it has no notion of "should the service still be running", only "if it is, what types does
 * it need").
 */
object ForegroundServiceTypePolicy {
    fun requiredTypes(
        intercomActive: Boolean,
        musicPlaying: Boolean,
    ): Set<ForegroundServiceTypeNeed> =
        buildSet {
            if (intercomActive) add(ForegroundServiceTypeNeed.MICROPHONE)
            if (musicPlaying) add(ForegroundServiceTypeNeed.MEDIA_PLAYBACK)
        }
}
