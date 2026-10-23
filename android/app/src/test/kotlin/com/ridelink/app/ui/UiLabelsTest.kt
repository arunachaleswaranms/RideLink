package com.ridelink.app.ui

import com.ridelink.app.session.ForgetPeerResult
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
            assertEquals("Playing on this phone", rideMusicLabel(SessionStatus.CONNECTED, it, false))
        }
        assertEquals("Playing on both phones", rideMusicLabel(SessionStatus.RIDE_ACTIVE, SyncState.SYNCED, true))
    }

    @Test
    fun `connection loss outranks playback diagnostics and failure stays visible without ownership`() {
        SyncState.entries.forEach {
            assertEquals("Music sync resumes when reconnected", rideMusicLabel(SessionStatus.RECONNECTING, it, true))
            assertEquals("Other phone unavailable · Playing on this phone", rideMusicLabel(SessionStatus.DISCONNECTED, it, true))
        }
        assertEquals("Music sync paused", rideMusicLabel(SessionStatus.RIDE_ACTIVE, SyncState.SYNC_FAILED, false))
    }

    @Test
    fun `failures have useful wording and policies keep their actual semantics`() {
        VoiceFailure.entries.forEach { assertFalse(voiceFailureLabel(it).contains(it.name)) }
        assertTrue(voiceFailureLabel(VoiceFailure.MIC_PERMISSION_DENIED).contains("Settings"))
        assertEquals("Always on · music paused", policyLabel(IntercomPolicy.MODE_D))
        assertEquals("Music only · intercom off", policyLabel(IntercomPolicy.MODE_E))
    }

    /** Phase 9A.5 §4: primary UI says "other phone"; "peer" stays in diagnostics only. */
    @Test
    fun `primary connection and intercom copy never says peer`() {
        SessionStatus.entries.forEach { status ->
            assertFalse(connectionTitle(status).contains("peer", ignoreCase = true), "$status title")
            assertFalse(connectionHint(status).contains("peer", ignoreCase = true), "$status hint")
        }
        VoiceFailure.entries.forEach { assertFalse(voiceFailureLabel(it).contains("peer", ignoreCase = true), it.name) }
        SyncState.entries.forEach { state ->
            SessionStatus.entries.forEach { status ->
                assertFalse(rideMusicLabel(status, state, true).contains("peer", ignoreCase = true), "$status $state")
            }
        }
        IntercomPolicy.ALL.forEach { assertFalse(policyLabel(it).contains("peer", ignoreCase = true)) }
        listOf(true, false).forEach { assertFalse(foundLabel(it).contains("peer", ignoreCase = true)) }
        listOf("pin_mismatch", "certificate_invalid", "identity_mismatch", "other").forEach {
            assertFalse(securityAlertExplanation(it).contains("peer", ignoreCase = true), it)
        }
    }

    /** STATUS §4 problem 126: the button and the coordinator read one policy, and the copy says what forgetting costs. */
    @Test
    fun `forget is offered only in IDLE and its confirmation names the six-digit code`() {
        SessionStatus.entries.forEach { status ->
            val hint = forgetPeerUnavailableHint(status)
            assertEquals(status == SessionStatus.IDLE, hint == null, status.name)
            hint?.let { assertFalse(it.contains("peer", ignoreCase = true), status.name) }
        }
        val confirmation = forgetPeerConfirmation("Rider")
        assertTrue(confirmation.contains("six-digit code"))
        assertTrue(confirmation.contains("identity"), "it says the identity is kept")
        assertFalse(confirmation.contains("peer", ignoreCase = true))
        assertEquals(null, forgetPeerResultLabel(ForgetPeerResult.FORGOTTEN))
        assertTrue(forgetPeerResultLabel(ForgetPeerResult.FAILED)!!.contains("still trusted"))
        assertTrue(securityAlertExplanation("pin_mismatch").contains("Paired phones"))
    }
}
