package com.ridelink.core.voice

/**
 * The stages of bringing voice up, timed with a **monotonic** clock the caller supplies
 * (CLAUDE.md rule 5 — never a wall clock, and never for an interval).
 *
 * ### What this can and cannot measure
 *
 * It measures **software setup time**: how long it took between the user asking for voice and the
 * media transport carrying a remote track. That is a genuinely useful number — it is what a
 * "why is it slow to connect?" complaint is about, and TEST_PLAN V-01/V-02 will read it.
 *
 * It is **not** latency. Mouth-to-ear latency (REQUIREMENTS' <200 ms target, TEST_PLAN A-09/V-11)
 * includes two Bluetooth hops, an encoder, a jitter buffer and a decoder, and can only be obtained
 * by playing a click into a real microphone and cross-correlating a recording of the real earbud
 * output. **Network RTT is not mouth-to-ear latency and neither is anything in this file**, so no
 * value here may be presented as approaching, meeting or bearing on that target.
 */
data class VoiceSetupTimeline(
    /** Start Intercom pressed, or a reconnect rebuild beginning (PROTOCOL §7.8). */
    val startRequestedAtMonoUs: Long? = null,
    /** The platform audio session and capture path came up. */
    val captureOpenAtMonoUs: Long? = null,
    /** The local description this side authored was created — the first signalling milestone. */
    val localDescriptionAtMonoUs: Long? = null,
    /** The peer's description was applied: both sides now agree on the media parameters. */
    val remoteDescriptionAtMonoUs: Long? = null,
    /** `PeerConnection` reached `connected`: DTLS-SRTP is up. */
    val mediaConnectedAtMonoUs: Long? = null,
    /** The remote audio track appeared. The last software milestone before audio could flow. */
    val remoteTrackAtMonoUs: Long? = null,
) {
    /** Start Intercom -> capture path open. The route/profile switch dominates this on Bluetooth. */
    val captureOpenMs: Double? get() = span(startRequestedAtMonoUs, captureOpenAtMonoUs)

    /** Start Intercom -> our own SDP created. */
    val localDescriptionMs: Double? get() = span(startRequestedAtMonoUs, localDescriptionAtMonoUs)

    /** Start Intercom -> the peer's SDP applied. One control round trip lives in here. */
    val signallingMs: Double? get() = span(startRequestedAtMonoUs, remoteDescriptionAtMonoUs)

    /** Start Intercom -> DTLS-SRTP connected. */
    val mediaConnectedMs: Double? get() = span(startRequestedAtMonoUs, mediaConnectedAtMonoUs)

    /**
     * Start Intercom -> remote track present. **Voice setup time**, and the only end-to-end figure
     * this type produces. Explicitly not a latency figure — see the class doc.
     */
    val setupMs: Double? get() = span(startRequestedAtMonoUs, remoteTrackAtMonoUs)

    private fun span(
        from: Long?,
        to: Long?,
    ): Double? {
        if (from == null || to == null) return null
        val delta = to - from
        // A negative span would mean the marks were recorded out of order, which is a bug in the
        // caller rather than a fast connection. Reported as absent rather than as a nonsense number.
        return if (delta < 0) null else delta / MICROS_PER_MS
    }

    private companion object {
        const val MICROS_PER_MS = 1_000.0
    }
}

/** One milestone in [VoiceSetupTimeline]. */
enum class VoiceSetupMark {
    START_REQUESTED,
    CAPTURE_OPEN,
    LOCAL_DESCRIPTION,
    REMOTE_DESCRIPTION,
    MEDIA_CONNECTED,
    REMOTE_TRACK,
}

/**
 * Records [VoiceSetupMark]s, first-write-wins within one negotiation.
 *
 * First-write-wins matters: WebRTC reports `connected` more than once across a session's
 * disconnect/reconnect cycles, and the setup figure is about the *first* time each milestone was
 * reached. A new negotiation calls [restart], which is what makes the number per-generation rather
 * than a lifetime average.
 */
object VoiceSetupTimer {
    fun restart(atMonoUs: Long): VoiceSetupTimeline = VoiceSetupTimeline(startRequestedAtMonoUs = atMonoUs)

    fun mark(
        timeline: VoiceSetupTimeline,
        mark: VoiceSetupMark,
        atMonoUs: Long,
    ): VoiceSetupTimeline =
        when (mark) {
            VoiceSetupMark.START_REQUESTED ->
                if (timeline.startRequestedAtMonoUs == null) timeline.copy(startRequestedAtMonoUs = atMonoUs) else timeline
            VoiceSetupMark.CAPTURE_OPEN ->
                if (timeline.captureOpenAtMonoUs == null) timeline.copy(captureOpenAtMonoUs = atMonoUs) else timeline
            VoiceSetupMark.LOCAL_DESCRIPTION ->
                if (timeline.localDescriptionAtMonoUs == null) timeline.copy(localDescriptionAtMonoUs = atMonoUs) else timeline
            VoiceSetupMark.REMOTE_DESCRIPTION ->
                if (timeline.remoteDescriptionAtMonoUs == null) timeline.copy(remoteDescriptionAtMonoUs = atMonoUs) else timeline
            VoiceSetupMark.MEDIA_CONNECTED ->
                if (timeline.mediaConnectedAtMonoUs == null) timeline.copy(mediaConnectedAtMonoUs = atMonoUs) else timeline
            VoiceSetupMark.REMOTE_TRACK ->
                if (timeline.remoteTrackAtMonoUs == null) timeline.copy(remoteTrackAtMonoUs = atMonoUs) else timeline
        }
}
