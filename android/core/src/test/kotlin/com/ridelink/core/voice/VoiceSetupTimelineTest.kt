package com.ridelink.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [VoiceSetupTimeline] / [VoiceSetupTimer] — the software timing instrumentation this phase adds.
 *
 * **Read the type's own doc before reading anything into these numbers.** They are *setup* times:
 * how long the app took to bring voice up. Mouth-to-ear latency (TEST_PLAN A-09/V-11) is a
 * different quantity that requires a real microphone, a real earbud and a recorder, and nothing here
 * bears on it. The mirror is `RideLinkCoreTests.VoiceSetupTimelineTests`.
 */
class VoiceSetupTimelineTest {
    @Test
    fun `an empty timeline reports no measurement rather than zero`() {
        val timeline = VoiceSetupTimeline()
        assertNull(timeline.captureOpenMs)
        assertNull(timeline.localDescriptionMs)
        assertNull(timeline.signallingMs)
        assertNull(timeline.mediaConnectedMs)
        assertNull(timeline.setupMs, "no remote track yet means no setup figure, not a zero")
    }

    @Test
    fun `a full sequence produces one figure per stage, in monotonic microseconds`() {
        var timeline = VoiceSetupTimer.restart(atMonoUs = 1_000_000)
        timeline = VoiceSetupTimer.mark(timeline, VoiceSetupMark.CAPTURE_OPEN, atMonoUs = 2_500_000)
        timeline = VoiceSetupTimer.mark(timeline, VoiceSetupMark.LOCAL_DESCRIPTION, atMonoUs = 2_600_000)
        timeline = VoiceSetupTimer.mark(timeline, VoiceSetupMark.REMOTE_DESCRIPTION, atMonoUs = 2_800_000)
        timeline = VoiceSetupTimer.mark(timeline, VoiceSetupMark.MEDIA_CONNECTED, atMonoUs = 3_100_000)
        timeline = VoiceSetupTimer.mark(timeline, VoiceSetupMark.REMOTE_TRACK, atMonoUs = 3_200_000)

        assertEquals(1_500.0, timeline.captureOpenMs, "the route/profile switch dominates this on Bluetooth")
        assertEquals(1_600.0, timeline.localDescriptionMs)
        assertEquals(1_800.0, timeline.signallingMs)
        assertEquals(2_100.0, timeline.mediaConnectedMs)
        assertEquals(2_200.0, timeline.setupMs)
    }

    /**
     * First-write-wins per milestone. WebRTC reports `connected` more than once across a session's
     * disconnect/reconnect cycles, and the setup figure is about the *first* time each milestone was
     * reached — otherwise a long ride's numbers would drift upward for no reason.
     */
    @Test
    fun `a repeated mark does not move the figure`() {
        var timeline = VoiceSetupTimer.restart(atMonoUs = 0)
        timeline = VoiceSetupTimer.mark(timeline, VoiceSetupMark.MEDIA_CONNECTED, atMonoUs = 500_000)
        timeline = VoiceSetupTimer.mark(timeline, VoiceSetupMark.MEDIA_CONNECTED, atMonoUs = 9_000_000)
        assertEquals(500.0, timeline.mediaConnectedMs, "the first connect is the one that counts")
    }

    /** A restart is per negotiation, which is what makes the figure per-generation (PROTOCOL §7.8). */
    @Test
    fun `restarting clears every earlier mark`() {
        var timeline = VoiceSetupTimer.restart(atMonoUs = 0)
        timeline = VoiceSetupTimer.mark(timeline, VoiceSetupMark.REMOTE_TRACK, atMonoUs = 1_000_000)
        assertEquals(1_000.0, timeline.setupMs)

        timeline = VoiceSetupTimer.restart(atMonoUs = 5_000_000)
        assertNull(timeline.setupMs, "a rebuild starts a fresh measurement")
        assertEquals(5_000_000L, timeline.startRequestedAtMonoUs)
    }

    /**
     * Marks recorded out of order would mean a bug in the caller, not a fast connection. Reported as
     * absent rather than as a negative or wrapped number — the same discipline `ClockSync` applies to
     * an implausible sample.
     */
    @Test
    fun `an out-of-order mark reports no measurement rather than a negative one`() {
        var timeline = VoiceSetupTimer.restart(atMonoUs = 5_000_000)
        timeline = VoiceSetupTimer.mark(timeline, VoiceSetupMark.REMOTE_TRACK, atMonoUs = 1_000_000)
        assertNull(timeline.setupMs, "a negative span is a caller bug, not a measurement")
    }

    @Test
    fun `marking start twice keeps the first instant`() {
        var timeline = VoiceSetupTimer.restart(atMonoUs = 1_000)
        timeline = VoiceSetupTimer.mark(timeline, VoiceSetupMark.START_REQUESTED, atMonoUs = 9_000)
        assertEquals(1_000L, timeline.startRequestedAtMonoUs)
    }

    /** Every mark is reachable, so none can be silently dropped by a `when` that forgot one. */
    @Test
    fun `every mark can be recorded`() {
        for (mark in VoiceSetupMark.entries) {
            val timeline = VoiceSetupTimer.mark(VoiceSetupTimeline(), mark, atMonoUs = 42)
            assertEquals(
                42L,
                when (mark) {
                    VoiceSetupMark.START_REQUESTED -> timeline.startRequestedAtMonoUs
                    VoiceSetupMark.CAPTURE_OPEN -> timeline.captureOpenAtMonoUs
                    VoiceSetupMark.LOCAL_DESCRIPTION -> timeline.localDescriptionAtMonoUs
                    VoiceSetupMark.REMOTE_DESCRIPTION -> timeline.remoteDescriptionAtMonoUs
                    VoiceSetupMark.MEDIA_CONNECTED -> timeline.mediaConnectedAtMonoUs
                    VoiceSetupMark.REMOTE_TRACK -> timeline.remoteTrackAtMonoUs
                },
                "$mark was not recorded",
            )
        }
    }
}
