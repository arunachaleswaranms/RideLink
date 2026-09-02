package com.ridelink.core.audiopolicy

/**
 * ADR-016 / PROTOCOL §4.3.1 — the audio vocabulary, and the **only** place platform profile names
 * are allowed to be translated into.
 *
 * The rule these enums exist to enforce: no `A2DP`, `HFP`, `SCO`, `AVAudioSession` or
 * `AudioManager` string ever reaches the wire. Each platform's route layer maps its own names into
 * these values in exactly one function, which is also the one place Phase 0's measured results
 * land — they flip [AudioConfidence] from [AudioConfidence.ASSUMED] to [AudioConfidence.MEASURED]
 * and correct the mapping for the real hardware, with no protocol change.
 */
enum class EndpointClass {
    BLUETOOTH,
    WIRED,
    BUILTIN_SPEAKER,
    BUILTIN_EARPIECE,
    OTHER,
    UNKNOWN,
    ;

    val wire: String get() = name.lowercase()

    companion object {
        fun parse(value: String): EndpointClass = entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

/**
 * Named for what a route can *carry*, never for the Bluetooth profile that happens to implement it
 * (ADR-016 choice 3), so a wired headset, the built-in speaker and any future transport are
 * describable in the same field.
 */
enum class AudioProfile {
    /** Media-quality stereo output only — no usable input. Bluetooth media streaming. */
    MEDIA_STEREO,

    /** Duplex at roughly 8 kHz both ways. Legacy hands-free. */
    DUPLEX_NARROWBAND,

    /** Duplex at roughly 16 kHz both ways. Modern hands-free — the ordinary helmet-unit case. */
    DUPLEX_WIDEBAND,

    /** Duplex at media quality *with* usable input. Wired headset, built-in, next-generation BT audio. */
    DUPLEX_WIDE_STEREO,

    /** The device's own speaker and microphone. */
    BUILTIN,

    NONE,
    UNKNOWN,
    ;

    val wire: String get() = name.lowercase()

    /** True for every profile that carries input as well as output. */
    val isDuplex: Boolean
        get() = this == DUPLEX_NARROWBAND || this == DUPLEX_WIDEBAND || this == DUPLEX_WIDE_STEREO || this == BUILTIN

    /**
     * True for a duplex profile that carries output at **reduced** bandwidth — the case where
     * opening the microphone has cost the music something.
     *
     * [DUPLEX_WIDE_STEREO] and [BUILTIN] are duplex but not narrowed: a wired headset and the
     * device's own speaker carry usable input without moving the output onto a narrowband codec.
     * See ADR-016 Amendment A1 for why this is a distinct predicate rather than "duplex and not
     * wide stereo" — the shorter phrasing contradicted ADR-016's own representable-states table for
     * `builtin`.
     */
    val isNarrowedDuplex: Boolean
        get() = this == DUPLEX_NARROWBAND || this == DUPLEX_WIDEBAND

    companion object {
        fun parse(value: String): AudioProfile = entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

/**
 * **The field ADR-016 exists for.** [INPUT_FORCES_OUTPUT] states outright that opening the
 * microphone drags the *output* onto the duplex profile too — which is the single highest product
 * risk in RideLink, and the thing the old independent-routes model was wrong about.
 */
enum class ProfileCoupling {
    INDEPENDENT,
    INPUT_FORCES_OUTPUT,
    UNKNOWN,
    ;

    val wire: String get() = name.lowercase()

    companion object {
        fun parse(value: String): ProfileCoupling = entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

/** Derived from the effective output profile, never measured from the audio (ADR-016 cost note). */
enum class MediaQuality {
    FULL,
    REDUCED,
    UNAVAILABLE,
    UNKNOWN,
    ;

    val wire: String get() = name.lowercase()

    companion object {
        fun parse(value: String): MediaQuality = entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

/** A route change is a first-class state, not a moment when every other field is quietly stale. */
enum class RouteState {
    STABLE,
    TRANSITIONING,
    ;

    val wire: String get() = name.lowercase()

    companion object {
        fun parse(value: String): RouteState = entries.firstOrNull { it.wire == value } ?: STABLE
    }
}

/**
 * [MEASURED] only once real hardware behaviour is recorded in `docs/PHASE0_RESULTS.md`. Until then
 * [ASSUMED] is the truth, and saying so is the whole point of the field.
 */
enum class AudioConfidence {
    MEASURED,
    ASSUMED,
    UNKNOWN,
    ;

    val wire: String get() = name.lowercase()

    companion object {
        fun parse(value: String): AudioConfidence = entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

/**
 * The effective runtime audio state — ADR-016's `AUDIO_STATE`, as a pure value.
 *
 * "Effective" is load-bearing: [effectiveOutputProfile] is what is *actually* active after any
 * [ProfileCoupling] has taken effect, so the honest answer in the ordinary Bluetooth intercom case
 * is a duplex profile at 16 kHz with [MediaQuality.REDUCED] — and both users can see it. The old
 * model could only produce a false reassurance.
 *
 * Phase 2a populates this from each platform's route layer and shows it on the diagnostics screen.
 * The `AUDIO_STATE` **message** that carries it to the peer is Phase 2b/6 work; this is the shared
 * type it will be built from, so the two cannot drift.
 */
data class AudioRouteSnapshot(
    val endpointClass: EndpointClass = EndpointClass.UNKNOWN,
    val microphoneOpen: Boolean = false,
    val effectiveOutputProfile: AudioProfile = AudioProfile.UNKNOWN,
    val effectiveInputProfile: AudioProfile = AudioProfile.UNKNOWN,
    val effectiveOutputSampleRateHz: Int? = null,
    val effectiveInputSampleRateHz: Int? = null,
    val routeState: RouteState = RouteState.STABLE,
    val profileCoupling: ProfileCoupling = ProfileCoupling.UNKNOWN,
    val confidence: AudioConfidence = AudioConfidence.ASSUMED,
    /**
     * A platform-level interruption is in progress — an incoming call, Siri, another app taking the
     * session. Deliberately **not** a `VOICE_STATE` value: PROTOCOL §7.4 keeps the WebRTC session
     * and the local audio route as separate reports, and an interruption is a route fact.
     */
    val interrupted: Boolean = false,
    /**
     * A short, platform-neutral reason for the last route change, for the diagnostics screen.
     * Never a device name and never free text from the platform — see [AudioRouteChangeReason].
     */
    val lastChangeReason: AudioRouteChangeReason = AudioRouteChangeReason.UNKNOWN,
) {
    /**
     * ADR-016, as corrected by its Amendment A1: `reduced` whenever the effective output profile is a
     * **narrowed** duplex profile — `duplex_narrowband` or `duplex_wideband`.
     *
     * Derived here, in one place, on both platforms, so the two cannot disagree about what the user
     * is told. ADR-016's original prose said "a duplex profile that is not `duplex_wide_stereo`",
     * which contradicted its own representable-states table: `builtin` is duplex, is not
     * `duplex_wide_stereo`, and is listed there as `full` — correctly, because a phone's own speaker
     * and microphone do not degrade each other. [AudioProfile.isNarrowedDuplex] is the precise rule.
     */
    val mediaQuality: MediaQuality
        get() =
            when {
                effectiveOutputProfile == AudioProfile.NONE -> MediaQuality.UNAVAILABLE
                effectiveOutputProfile == AudioProfile.UNKNOWN -> MediaQuality.UNKNOWN
                effectiveOutputProfile.isNarrowedDuplex -> MediaQuality.REDUCED
                else -> MediaQuality.FULL
            }
}

/**
 * Why the route last changed, in platform-neutral terms.
 *
 * A device *name* is personally identifying (ADR-016 rejected free-text route descriptions for
 * exactly that reason), and the platform's own reason strings are neither parseable by the peer nor
 * pinnable by a test. This enum is what each route layer maps its platform reason into.
 */
enum class AudioRouteChangeReason {
    NEW_DEVICE_AVAILABLE,
    OLD_DEVICE_UNAVAILABLE,
    CATEGORY_CHANGE,
    OVERRIDE,
    WAKE_FROM_SLEEP,
    NO_SUITABLE_ROUTE,
    CONFIGURATION_CHANGE,
    INTERRUPTION_BEGAN,
    INTERRUPTION_ENDED,
    MEDIA_SERVICES_RESET,
    UNKNOWN,
}
