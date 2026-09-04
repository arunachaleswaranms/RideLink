package com.ridelink.core.audiopolicy

import kotlin.test.Test
import kotlin.test.assertEquals

class ForegroundServiceTypePolicyTest {
    @Test
    fun `neither active needs no type`() {
        assertEquals(emptySet(), ForegroundServiceTypePolicy.requiredTypes(intercomActive = false, musicPlaying = false))
    }

    @Test
    fun `intercom only needs microphone only`() {
        assertEquals(
            setOf(ForegroundServiceTypeNeed.MICROPHONE),
            ForegroundServiceTypePolicy.requiredTypes(intercomActive = true, musicPlaying = false),
        )
    }

    @Test
    fun `music only needs media playback only, independent of the intercom`() {
        assertEquals(
            setOf(ForegroundServiceTypeNeed.MEDIA_PLAYBACK),
            ForegroundServiceTypePolicy.requiredTypes(intercomActive = false, musicPlaying = true),
        )
    }

    @Test
    fun `both active needs both types`() {
        assertEquals(
            setOf(ForegroundServiceTypeNeed.MICROPHONE, ForegroundServiceTypeNeed.MEDIA_PLAYBACK),
            ForegroundServiceTypePolicy.requiredTypes(intercomActive = true, musicPlaying = true),
        )
    }
}
