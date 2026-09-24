package com.ridelink.app.ui

import com.ridelink.app.sync.SyncState
import com.ridelink.core.audiopolicy.IntercomPolicy
import com.ridelink.core.audiopolicy.VoiceFailure
import com.ridelink.core.sessionfsm.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiLabelsTest {
    @Test
    fun `late debt does not label local controls synchronized`() {
        listOf(SyncState.SCHEDULED, SyncState.SYNCED).forEach {
            assertEquals("Local playback", rideMusicLabel(SessionStatus.CONNECTED, it, false))
        }
        assertEquals("Synchronized", rideMusicLabel(SessionStatus.RIDE_ACTIVE, SyncState.SYNCED, true))
    }

    @Test
    fun `connection loss outranks playback diagnostics and failure stays visible without ownership`() {
        SyncState.entries.forEach {
            assertEquals("Music sync waits for connection", rideMusicLabel(SessionStatus.RECONNECTING, it, true))
            assertEquals("Peer unavailable · Local controls", rideMusicLabel(SessionStatus.DISCONNECTED, it, true))
        }
        assertEquals("Music sync paused", rideMusicLabel(SessionStatus.RIDE_ACTIVE, SyncState.SYNC_FAILED, false))
    }

    @Test
    fun `failures have useful wording and policies keep their actual semantics`() {
        VoiceFailure.entries.forEach { assertFalse(voiceFailureLabel(it).contains(it.name)) }
        assertTrue(voiceFailureLabel(VoiceFailure.MIC_PERMISSION_DENIED).contains("Settings"))
        assertEquals("D · Continuous / pause music", policyLabel(IntercomPolicy.MODE_D))
        assertEquals("E · Music only", policyLabel(IntercomPolicy.MODE_E))
    }
}
