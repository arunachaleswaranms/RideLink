package com.ridelink.core.voice

import com.ridelink.core.protocol.VoiceSessionId
import com.ridelink.core.protocol.VoiceSignal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sequential ADR-020 A11 proofs, mirrored by VoicePendingStartIntentTests. */
class VoicePendingStartIntentTest {
    @Test
    fun `both orders consume once for both roles`() {
        for (role in VoiceRole.entries) {
            for (connectedFirst in listOf(false, true)) {
                val h = Trace(role)
                if (connectedFirst) h.apply(VoiceInput.ControlAuthenticated(2, id(2)))
                h.apply(VoiceInput.StartRequested(id(1), null))
                if (!connectedFirst) {
                    assertTrue(h.state.pendingStartIntent)
                    assertTrue(h.state.localAudioOpen)
                    assertNull(h.state.negotiationControlGeneration)
                    assertNull(h.state.voiceSessionId)
                    assertEquals(listOf<VoiceAction>(VoiceAction.StartLocalAudio), h.actions)
                    h.apply(VoiceInput.ControlAuthenticated(2, id(2)))
                }
                assertEquals(2L, h.state.negotiationControlGeneration)
                assertFalse(h.state.pendingStartIntent)
                assertEquals(VoiceStatus.NEGOTIATING, h.state.status)
                assertEquals(if (role == VoiceRole.OFFERER) id(if (connectedFirst) 1 else 2) else null, h.state.voiceSessionId)
                assertTrue(h.actions.filterIsInstance<OutboundVoiceAction>().all { it.controlGeneration == 2L })
                val before = h.state
                val effects = h.actions.toList()
                h.apply(VoiceInput.ControlAuthenticated(2, id(3)))
                h.apply(VoiceInput.StartRequested(id(4), 2))
                assertEquals(before, h.state)
                assertEquals(effects, h.actions)
                h.apply(VoiceInput.ControlLinkLost(1))
                assertEquals(before, h.state)
                h.apply(VoiceInput.ControlLinkLost(2))
                assertEquals(VoiceStatus.IDLE, h.state.status)
                assertNull(h.state.negotiationControlGeneration)
                assertNull(h.state.authenticatedControlGeneration)
                assertTrue(h.state.localAudioOpen)
                assertFalse(h.state.pendingStartIntent)
            }
        }
    }

    @Test
    fun `failure never manufactures intent or retries on duplicate availability`() {
        for (role in VoiceRole.entries) {
            val h = Trace(role)
            h.apply(VoiceInput.StartRequested(id(1), null))
            h.apply(VoiceInput.ControlAuthenticated(2, id(2)))
            h.apply(VoiceInput.NegotiationSendFailed(h.state.voiceSessionId))
            assertFalse(h.state.pendingStartIntent)
            assertTrue(h.state.localAudioOpen)
            assertEquals(VoiceStatus.IDLE, h.state.status)
            val effects = h.actions.toList()
            repeat(5) { h.apply(VoiceInput.ControlAuthenticated(2, id(3))) }
            assertEquals(effects, h.actions)
            assertEquals(VoiceStatus.IDLE, h.state.status)
        }
    }

    @Test
    fun `Stop and session Stop clear intent`() {
        for (role in VoiceRole.entries) {
            val h = Trace(role)
            h.apply(VoiceInput.StartRequested(id(1), null))
            h.apply(VoiceInput.StopRequested)
            assertFalse(h.state.pendingStartIntent)
            assertFalse(h.state.localAudioOpen)
            assertEquals(listOf(VoiceAction.StartLocalAudio, VoiceAction.StopMediaTransport, VoiceAction.ReleaseLocalAudio), h.actions)
            h.apply(VoiceInput.ControlAuthenticated(2, id(2)))
            assertEquals(VoiceStatus.IDLE, h.state.status)
            assertEquals(3, h.actions.size)
        }
    }

    @Test
    fun `queued B availability retired before consumption leaves intent for C`() {
        for (role in VoiceRole.entries) {
            val h = Trace(role)
            h.apply(VoiceInput.StartRequested(id(1), null))
            val mailbox = VoiceInputMailbox()
            mailbox.offer(VoiceInput.ControlAuthenticated(2, id(2)))
            mailbox.offer(VoiceInput.ControlLinkLost(2))
            mailbox.offer(VoiceInput.ControlAuthenticated(3, id(3)))
            drain(mailbox, h)
            assertEquals(3L, h.state.negotiationControlGeneration)
            assertEquals(if (role == VoiceRole.OFFERER) id(3) else null, h.state.voiceSessionId)
            assertFalse(h.state.pendingStartIntent)
            assertTrue(h.actions.filterIsInstance<OutboundVoiceAction>().all { it.controlGeneration == 3L })
            assertEquals(1, mailbox.discardedRetiredAvailabilityCount)
            assertEquals(0, mailbox.discardedRetiredSignalCount)
            assertEquals(VoiceMailboxOutcome.RetiredGeneration, mailbox.offer(VoiceInput.ControlAuthenticated(2, id(4))))
            assertEquals(1, mailbox.refusedRetiredAvailabilityCount)
            assertEquals(0, mailbox.refusedRetiredSignalCount)
        }
    }

    @Test
    fun `new availability retires old queued authority without discarding local Start`() {
        val mailbox = VoiceInputMailbox()
        mailbox.offer(VoiceInput.ControlAuthenticated(2, id(2)))
        mailbox.offer(VoiceInput.StartRequested(id(1), null))
        mailbox.offer(VoiceInput.ControlAuthenticated(3, id(3)))
        val h = Trace(VoiceRole.OFFERER)
        drain(mailbox, h)
        assertEquals(3L, h.state.negotiationControlGeneration)
        assertFalse(h.state.pendingStartIntent)
        assertEquals(id(3), h.state.voiceSessionId)
        assertEquals(1, mailbox.discardedRetiredAvailabilityCount)
        assertEquals(0, mailbox.discardedRetiredSignalCount)
    }

    @Test
    fun `recorded B availability survives late A while idle`() {
        val h = Trace(VoiceRole.OFFERER)
        h.apply(VoiceInput.ControlAuthenticated(2, id(2)))
        h.apply(VoiceInput.ControlLinkLost(1))
        assertEquals(2L, h.state.authenticatedControlGeneration)
        h.apply(VoiceInput.StartRequested(id(1), null))
        assertEquals(2L, h.state.negotiationControlGeneration)
    }

    @Test
    fun `held B alone authorises nil consent`() {
        val h = Trace(VoiceRole.ANSWERER)
        h.apply(VoiceInput.SignalReceived(VoiceSignal.Offer(id(2), "v=0\r\n"), 2, id(3)))
        h.apply(VoiceInput.StartRequested(id(1), null))
        assertEquals(2L, h.state.negotiationControlGeneration)
        assertEquals(id(2), h.state.voiceSessionId)
        assertFalse(h.state.pendingStartIntent)
        assertEquals(1, h.actions.filterIsInstance<VoiceAction.CreateAnswer>().size)
    }

    @Test
    fun `explicit B Start before availability also consumes pending intent`() {
        val h = Trace(VoiceRole.OFFERER)
        h.apply(VoiceInput.StartRequested(id(1), null))
        h.apply(VoiceInput.StartRequested(id(2), 2))
        h.apply(VoiceInput.ControlAuthenticated(2, id(3)))
        assertEquals(id(2), h.state.voiceSessionId)
        assertFalse(h.state.pendingStartIntent)
        assertEquals(1, h.actions.filterIsInstance<VoiceAction.CreateOffer>().size)
    }

    @Test
    fun `coalesced loss cannot forget that B ended`() {
        val h = Trace(VoiceRole.OFFERER)
        h.apply(VoiceInput.ControlAuthenticated(2, id(2)))
        val mailbox = VoiceInputMailbox()
        mailbox.offer(VoiceInput.ControlLinkLost(2))
        mailbox.offer(VoiceInput.ControlLinkLost(1))
        drain(mailbox, h)
        h.apply(VoiceInput.StartRequested(id(1), null))
        assertTrue(h.state.pendingStartIntent)
        assertNull(h.state.negotiationControlGeneration)
    }

    @Test
    fun `Connected B before delayed loss A rebuilds without reowning A`() {
        val h = Trace(VoiceRole.OFFERER)
        h.apply(VoiceInput.ControlAuthenticated(1, id(1)))
        h.apply(VoiceInput.StartRequested(id(2), 1))
        h.apply(VoiceInput.ControlAuthenticated(2, id(3)))
        h.apply(VoiceInput.ControlLinkLost(1))
        assertEquals(2L, h.state.negotiationControlGeneration)
        assertEquals(id(3), h.state.voiceSessionId)
        assertEquals(1, h.actions.count { it == VoiceAction.StopMediaTransport })
    }

    private class Trace(
        role: VoiceRole,
    ) {
        var state = VoiceNegotiationState(role)
        val actions = mutableListOf<VoiceAction>()

        fun apply(input: VoiceInput) {
            val outcome = VoiceNegotiation.reduce(state, input)
            state = outcome.state
            actions += outcome.actions
        }
    }

    private fun drain(
        mailbox: VoiceInputMailbox,
        h: Trace,
    ) {
        while (true) h.apply(mailbox.poll() ?: return)
    }

    private fun id(n: Int): VoiceSessionId = VoiceSessionId(n.toString().repeat(32))
}
