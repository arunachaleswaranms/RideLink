package com.ridelink.core.voice

import com.ridelink.core.protocol.VoiceBounds
import com.ridelink.core.protocol.VoiceMode
import com.ridelink.core.protocol.VoiceSessionId
import com.ridelink.core.protocol.VoiceSignal
import com.ridelink.core.protocol.VoiceWireState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [VoiceInputMailbox] exhausted with no coroutines, no controller, no WebRTC — the mailbox policy
 * itself is what is under test here. Behavioural proof that flooding it cannot grow
 * `VoiceController`'s memory without bound lives in [com.ridelink.network.voice.VoiceControllerMailboxTest];
 * this file is the pure classification-and-capacity logic those tests rely on.
 */
class VoiceInputMailboxTest {
    // --- lanes are classified correctly ----------------------------------------------------------

    @Test
    fun `stop and control-link-lost land in the always-accepting teardown lane`() {
        assertEquals(VoiceMailboxLane.TEARDOWN, VoiceInputMailbox.laneFor(VoiceInput.StopRequested))
        assertEquals(VoiceMailboxLane.TEARDOWN, VoiceInputMailbox.laneFor(VoiceInput.ControlLinkLost))
    }

    @Test
    fun `start, engine offer-answer callbacks, connectivity and peer offer-answer are critical`() {
        assertEquals(VoiceMailboxLane.CRITICAL, VoiceInputMailbox.laneFor(VoiceInput.StartRequested(GEN_1)))
        assertEquals(VoiceMailboxLane.CRITICAL, VoiceInputMailbox.laneFor(VoiceInput.LocalOfferCreated(GEN_1, SDP)))
        assertEquals(VoiceMailboxLane.CRITICAL, VoiceInputMailbox.laneFor(VoiceInput.LocalAnswerCreated(GEN_1, SDP)))
        assertEquals(
            VoiceMailboxLane.CRITICAL,
            VoiceInputMailbox.laneFor(VoiceInput.MediaConnectivityChanged(GEN_1, connected = true, failed = false)),
        )
        assertEquals(
            VoiceMailboxLane.CRITICAL,
            VoiceInputMailbox.laneFor(VoiceInput.SignalReceived(VoiceSignal.Offer(GEN_1, SDP), GEN_1)),
        )
        assertEquals(
            VoiceMailboxLane.CRITICAL,
            VoiceInputMailbox.laneFor(VoiceInput.SignalReceived(VoiceSignal.Answer(GEN_1, SDP), GEN_1)),
        )
    }

    @Test
    fun `candidate-shaped inputs are the bounded ICE lane`() {
        assertEquals(
            VoiceMailboxLane.ICE,
            VoiceInputMailbox.laneFor(VoiceInput.LocalCandidateGathered(GEN_1, CANDIDATE, null, 0)),
        )
        assertEquals(
            VoiceMailboxLane.ICE,
            VoiceInputMailbox.laneFor(
                VoiceInput.SignalReceived(VoiceSignal.IceCandidate(GEN_1, CANDIDATE, null, 0), GEN_1),
            ),
        )
    }

    @Test
    fun `mute, remote-track and non-terminal peer-state updates are coalesced`() {
        assertEquals(VoiceMailboxLane.COALESCED, VoiceInputMailbox.laneFor(VoiceInput.MuteRequested(true)))
        assertEquals(
            VoiceMailboxLane.COALESCED,
            VoiceInputMailbox.laneFor(VoiceInput.RemoteTrackChanged(GEN_1, present = true)),
        )
        val ordinaryWireStates =
            listOf(
                VoiceWireState.NEGOTIATING,
                VoiceWireState.CONNECTING,
                VoiceWireState.ACTIVE,
                VoiceWireState.IDLE,
                VoiceWireState.UNKNOWN,
            )
        for (wire in ordinaryWireStates) {
            assertEquals(
                VoiceMailboxLane.COALESCED,
                VoiceInputMailbox.laneFor(VoiceInput.SignalReceived(VoiceSignal.State(GEN_1, wire, false, VoiceMode.CONTINUOUS), GEN_1)),
                "wire state $wire must coalesce, not be treated as terminal",
            )
        }
    }

    @Test
    fun `a peer's closed or failed state is the terminal-peer-state lane, not coalesced`() {
        assertEquals(
            VoiceMailboxLane.TERMINAL_PEER_STATE,
            VoiceInputMailbox.laneFor(
                VoiceInput.SignalReceived(VoiceSignal.State(GEN_1, VoiceWireState.CLOSED, false, VoiceMode.CONTINUOUS), GEN_1),
            ),
        )
        assertEquals(
            VoiceMailboxLane.TERMINAL_PEER_STATE,
            VoiceInputMailbox.laneFor(
                VoiceInput.SignalReceived(VoiceSignal.State(GEN_1, VoiceWireState.FAILED, false, VoiceMode.CONTINUOUS), GEN_1),
            ),
        )
        // A null voice_session_id is legal for `closed` (PROTOCOL §7.4) and must classify the same way.
        assertEquals(
            VoiceMailboxLane.TERMINAL_PEER_STATE,
            VoiceInputMailbox.laneFor(
                VoiceInput.SignalReceived(VoiceSignal.State(null, VoiceWireState.CLOSED, false, VoiceMode.CONTINUOUS), GEN_1),
            ),
        )
    }

    // --- priority: teardown > terminal-peer-state > critical > ice > coalesced ---------------------

    @Test
    fun `poll drains teardown before anything else, regardless of arrival order`() {
        val mailbox = VoiceInputMailbox()
        mailbox.offer(VoiceInput.StartRequested(GEN_1))
        mailbox.offer(VoiceInput.LocalCandidateGathered(GEN_1, CANDIDATE, null, 0))
        mailbox.offer(VoiceInput.MuteRequested(true))
        val closed = VoiceInput.SignalReceived(VoiceSignal.State(GEN_1, VoiceWireState.CLOSED, false, VoiceMode.CONTINUOUS), GEN_1)
        mailbox.offer(closed)
        mailbox.offer(VoiceInput.ControlLinkLost)

        assertEquals(VoiceInput.ControlLinkLost, mailbox.poll(), "local teardown outranks even a terminal peer state")
        assertEquals(closed, mailbox.poll())
        assertEquals(VoiceInput.StartRequested(GEN_1), mailbox.poll())
        assertEquals(VoiceInput.LocalCandidateGathered(GEN_1, CANDIDATE, null, 0), mailbox.poll())
        assertEquals(VoiceInput.MuteRequested(true), mailbox.poll())
        assertEquals(null, mailbox.poll())
    }

    @Test
    fun `only the latest teardown request survives, but it is never lost`() {
        val mailbox = VoiceInputMailbox()
        mailbox.offer(VoiceInput.ControlLinkLost)
        mailbox.offer(VoiceInput.StopRequested)

        assertEquals(VoiceInput.StopRequested, mailbox.poll())
        assertEquals(null, mailbox.poll())
    }

    @Test
    fun `critical inputs are FIFO within their own lane`() {
        val mailbox = VoiceInputMailbox()
        mailbox.offer(VoiceInput.LocalOfferCreated(GEN_1, "first"))
        mailbox.offer(VoiceInput.LocalAnswerCreated(GEN_1, "second"))

        assertEquals(VoiceInput.LocalOfferCreated(GEN_1, "first"), mailbox.poll())
        assertEquals(VoiceInput.LocalAnswerCreated(GEN_1, "second"), mailbox.poll())
    }

    // --- terminal peer state: never coalesced, never overtaken -------------------------------------

    @Test
    fun `a queued CLOSED is not overwritten by a later ACTIVE, and both survive as distinct entries`() {
        val mailbox = VoiceInputMailbox()
        val closed = VoiceInput.SignalReceived(VoiceSignal.State(GEN_1, VoiceWireState.CLOSED, false, VoiceMode.CONTINUOUS), GEN_1)
        val active = VoiceInput.SignalReceived(VoiceSignal.State(GEN_1, VoiceWireState.ACTIVE, false, VoiceMode.CONTINUOUS), GEN_1)

        mailbox.offer(closed)
        mailbox.offer(active)

        // CLOSED is drained first (higher-priority lane) and unchanged -- ACTIVE landed in the
        // separate coalesced lane and never had the chance to replace it.
        assertEquals(closed, mailbox.poll(), "the terminal CLOSED must survive intact")
        assertEquals(active, mailbox.poll(), "the ordinary ACTIVE update is still delivered, just after")
        assertEquals(null, mailbox.poll())
    }

    @Test
    fun `a queued FAILED is not overwritten by a later CONNECTING`() {
        val mailbox = VoiceInputMailbox()
        val failed = VoiceInput.SignalReceived(VoiceSignal.State(GEN_1, VoiceWireState.FAILED, false, VoiceMode.CONTINUOUS), GEN_1)
        val connecting =
            VoiceInput.SignalReceived(VoiceSignal.State(GEN_1, VoiceWireState.CONNECTING, false, VoiceMode.CONTINUOUS), GEN_1)

        mailbox.offer(failed)
        mailbox.offer(connecting)

        assertEquals(failed, mailbox.poll(), "the terminal FAILED must survive intact")
        assertEquals(connecting, mailbox.poll())
        assertEquals(null, mailbox.poll())
    }

    @Test
    fun `terminal peer state is drained ahead of a large ICE backlog`() {
        val mailbox = VoiceInputMailbox(iceCapacity = 64)
        repeat(1_000) { i -> mailbox.offer(VoiceInput.LocalCandidateGathered(GEN_1, "c$i", null, 0)) }
        val closed = VoiceInput.SignalReceived(VoiceSignal.State(GEN_1, VoiceWireState.CLOSED, false, VoiceMode.CONTINUOUS), GEN_1)
        mailbox.offer(closed)

        assertEquals(closed, mailbox.poll(), "a terminal peer state must never queue behind an ICE flood")
    }

    @Test
    fun `terminal peer state is drained ahead of ordinary coalesced updates, and after critical work`() {
        val mailbox = VoiceInputMailbox()
        mailbox.offer(VoiceInput.MuteRequested(true))
        mailbox.offer(VoiceInput.StartRequested(GEN_1))
        val closed = VoiceInput.SignalReceived(VoiceSignal.State(GEN_1, VoiceWireState.CLOSED, false, VoiceMode.CONTINUOUS), GEN_1)
        mailbox.offer(closed)

        assertEquals(closed, mailbox.poll(), "terminal peer state outranks both critical and coalesced work")
        assertEquals(VoiceInput.StartRequested(GEN_1), mailbox.poll())
        assertEquals(VoiceInput.MuteRequested(true), mailbox.poll())
    }

    @Test
    fun `a terminal signal is drained unchanged -- never rewritten into a local teardown input`() {
        val mailbox = VoiceInputMailbox()
        val closed = VoiceInput.SignalReceived(VoiceSignal.State(null, VoiceWireState.CLOSED, false, VoiceMode.CONTINUOUS), GEN_1)
        mailbox.offer(closed)

        val drained = mailbox.poll()
        // Remote CLOSED must remain a SignalReceived carrying the original VoiceSignal.State all the
        // way to VoiceNegotiation, which has its own, distinct remote-teardown handling
        // (`teardownFromPeer`) -- the mailbox must not fold it into VoiceInput.StopRequested, whose
        // reducer path has different local-lifecycle semantics (it may release local capture; a
        // remote CLOSED must not).
        assertIs<VoiceInput.SignalReceived>(drained)
        assertEquals(closed, drained)
        assertTrue(drained !is VoiceInput.StopRequested)
    }

    @Test
    fun `flooding the terminal-peer-state lane past capacity refuses new entries, forces a safe degrade, and stays bounded`() {
        val mailbox = VoiceInputMailbox(terminalPeerStateCapacity = 4)
        repeat(4) { i ->
            val outcome =
                mailbox.offer(
                    VoiceInput.SignalReceived(VoiceSignal.State(GEN_1, VoiceWireState.CLOSED, false, VoiceMode.CONTINUOUS), GEN_1),
                )
            assertIs<VoiceMailboxOutcome.Accepted>(outcome, "entry $i should still fit under capacity")
        }

        val overflow =
            mailbox.offer(
                VoiceInput.SignalReceived(VoiceSignal.State(GEN_1, VoiceWireState.FAILED, false, VoiceMode.CONTINUOUS), GEN_1),
            )
        assertIs<VoiceMailboxOutcome.TerminalPeerStateOverflow>(overflow)
        assertEquals(1, mailbox.overflowCount)

        var drained = 0
        while (mailbox.poll() != null) drained++
        assertEquals(4, drained, "the overflowing terminal signal must not have been silently accepted anyway")
    }

    @Test
    fun `an authenticated flood of terminal peer states cannot grow the mailbox past the terminal bound`() {
        val mailbox = VoiceInputMailbox(terminalPeerStateCapacity = 8)
        var overflowed = 0
        repeat(10_000) { i ->
            val wire = if (i % 2 == 0) VoiceWireState.CLOSED else VoiceWireState.FAILED
            val outcome =
                mailbox.offer(VoiceInput.SignalReceived(VoiceSignal.State(GEN_1, wire, false, VoiceMode.CONTINUOUS), GEN_1))
            if (outcome is VoiceMailboxOutcome.TerminalPeerStateOverflow) overflowed++
        }
        assertTrue(overflowed > 0, "10,000 terminal signals must eventually overflow an 8-deep lane")
        assertEquals(8, mailbox.size, "size must never exceed the configured terminal-peer-state capacity")
        assertEquals(overflowed, mailbox.overflowCount)
    }

    // --- bounded critical lane, and the forced degrade it implies ---------------------------------

    @Test
    fun `flooding the critical lane past capacity refuses new entries and counts an overflow`() {
        val mailbox = VoiceInputMailbox(criticalCapacity = 4)
        repeat(4) { assertIs<VoiceMailboxOutcome.Accepted>(mailbox.offer(VoiceInput.StartRequested(GEN_1))) }

        val outcome = mailbox.offer(VoiceInput.StartRequested(GEN_1))
        assertIs<VoiceMailboxOutcome.CriticalOverflow>(outcome)
        assertEquals(1, mailbox.overflowCount)
        // The refused input never entered the lane — the lane still holds exactly its capacity.
        var drained = 0
        while (mailbox.poll() != null) drained++
        assertEquals(4, drained, "the overflowing input must not have been silently accepted anyway")
    }

    @Test
    fun `an authenticated flood of offers cannot grow the mailbox past the critical bound`() {
        val mailbox = VoiceInputMailbox(criticalCapacity = 32)
        var overflowed = 0
        repeat(10_000) {
            val outcome = mailbox.offer(VoiceInput.SignalReceived(VoiceSignal.Offer(GEN_1, SDP), GEN_1))
            if (outcome is VoiceMailboxOutcome.CriticalOverflow) overflowed++
        }
        assertTrue(overflowed > 0, "10,000 offers must eventually overflow a 32-deep lane")
        assertEquals(32, mailbox.size, "size must never exceed the configured capacity")
        assertEquals(overflowed, mailbox.overflowCount)
    }

    // --- bounded ICE lane, oldest evicted, matching PendingCandidates' own policy ------------------

    @Test
    fun `flooding the ICE lane evicts the oldest candidate and counts it, never growing past capacity`() {
        val mailbox = VoiceInputMailbox(iceCapacity = 3)
        repeat(3) { i -> mailbox.offer(VoiceInput.LocalCandidateGathered(GEN_1, "c$i", null, 0)) }
        val outcome = mailbox.offer(VoiceInput.LocalCandidateGathered(GEN_1, "c3", null, 0))

        assertIs<VoiceMailboxOutcome.IceEvicted>(outcome)
        assertEquals(1, mailbox.overflowCount)
        val remaining = generateSequence { mailbox.poll() }.toList()
        assertEquals(
            listOf("c1", "c2", "c3"),
            remaining.map { (it as VoiceInput.LocalCandidateGathered).candidate },
            "the oldest candidate is discarded so the newest, most likely still reachable ones survive",
        )
    }

    @Test
    fun `the ICE lane's default capacity is the same protocol bound PendingCandidates enforces`() {
        val mailbox = VoiceInputMailbox()
        repeat(VoiceBounds.MAX_QUEUED_CANDIDATES + 10) { i ->
            mailbox.offer(VoiceInput.LocalCandidateGathered(GEN_1, "c$i", null, 0))
        }
        var drained = 0
        while (mailbox.poll() != null) drained++
        assertEquals(VoiceBounds.MAX_QUEUED_CANDIDATES, drained)
    }

    @Test
    fun `flooding ICE cannot starve or evict a critical offer`() {
        val mailbox = VoiceInputMailbox(iceCapacity = 4)
        mailbox.offer(VoiceInput.SignalReceived(VoiceSignal.Offer(GEN_1, SDP), GEN_1))
        repeat(1_000) { i -> mailbox.offer(VoiceInput.LocalCandidateGathered(GEN_1, "c$i", null, 0)) }

        val first = mailbox.poll()
        assertIs<VoiceInput.SignalReceived>(first)
        assertIs<VoiceSignal.Offer>(first.signal)
    }

    // --- coalescing: latest wins, nothing unbounded accumulates -------------------------------------

    @Test
    fun `repeated peer-state updates converge to only the newest value`() {
        val mailbox = VoiceInputMailbox()
        repeat(500) { i ->
            val state = if (i % 2 == 0) VoiceWireState.ACTIVE else VoiceWireState.CONNECTING
            mailbox.offer(VoiceInput.SignalReceived(VoiceSignal.State(GEN_1, state, false, VoiceMode.CONTINUOUS), GEN_1))
        }
        val next = mailbox.poll()
        assertIs<VoiceInput.SignalReceived>(next)
        val signal = next.signal
        assertIs<VoiceSignal.State>(signal)
        assertEquals(VoiceWireState.CONNECTING, signal.state, "the 500th (odd, i=499) update is the newest")
        assertEquals(null, mailbox.poll(), "only one coalesced entry was ever held")
    }

    @Test
    fun `mute, remote-track and peer-state each coalesce independently`() {
        val mailbox = VoiceInputMailbox()
        mailbox.offer(VoiceInput.MuteRequested(true))
        mailbox.offer(VoiceInput.RemoteTrackChanged(GEN_1, present = true))
        mailbox.offer(VoiceInput.SignalReceived(VoiceSignal.State(GEN_1, VoiceWireState.ACTIVE, false, VoiceMode.CONTINUOUS), GEN_1))
        mailbox.offer(VoiceInput.MuteRequested(false))

        val drained = generateSequence { mailbox.poll() }.toList()
        assertEquals(3, drained.size, "three distinct coalesced kinds, not one shared slot")
        assertTrue(drained.any { it == VoiceInput.MuteRequested(false) }, "the newer mute value, not the first")
        assertTrue(drained.any { it is VoiceInput.RemoteTrackChanged })
        assertTrue(drained.any { it is VoiceInput.SignalReceived })
    }

    @Test
    fun `coalescing never overflows regardless of volume`() {
        val mailbox = VoiceInputMailbox()
        repeat(50_000) { mailbox.offer(VoiceInput.MuteRequested(it % 2 == 0)) }
        assertEquals(1, mailbox.size)
        assertEquals(0, mailbox.overflowCount)
    }

    // --- teardown, restart, clear ------------------------------------------------------------------

    @Test
    fun `stop remains offerable and drainable even while every other lane is completely full`() {
        val mailbox = VoiceInputMailbox(criticalCapacity = 2, iceCapacity = 2, terminalPeerStateCapacity = 2)
        repeat(2) { mailbox.offer(VoiceInput.StartRequested(GEN_1)) }
        repeat(2) { i -> mailbox.offer(VoiceInput.LocalCandidateGathered(GEN_1, "c$i", null, 0)) }
        mailbox.offer(VoiceInput.MuteRequested(true))
        repeat(2) {
            mailbox.offer(VoiceInput.SignalReceived(VoiceSignal.State(GEN_1, VoiceWireState.CLOSED, false, VoiceMode.CONTINUOUS), GEN_1))
        }

        val outcome = mailbox.offer(VoiceInput.StopRequested)
        assertIs<VoiceMailboxOutcome.Accepted>(outcome)
        assertEquals(VoiceInput.StopRequested, mailbox.poll(), "stop is drained first no matter how full the rest is")
    }

    @Test
    fun `clear empties every lane and resets nothing that should survive a fresh session`() {
        val mailbox = VoiceInputMailbox()
        mailbox.offer(VoiceInput.StartRequested(GEN_1))
        mailbox.offer(VoiceInput.LocalCandidateGathered(GEN_1, CANDIDATE, null, 0))
        mailbox.offer(VoiceInput.MuteRequested(true))
        mailbox.offer(VoiceInput.SignalReceived(VoiceSignal.State(GEN_1, VoiceWireState.CLOSED, false, VoiceMode.CONTINUOUS), GEN_1))
        mailbox.offer(VoiceInput.ControlLinkLost)

        mailbox.clear()

        assertTrue(mailbox.isEmpty())
        assertEquals(null, mailbox.poll())
        // A fresh session offers cleanly afterward — clearing does not wedge the mailbox.
        assertIs<VoiceMailboxOutcome.Accepted>(mailbox.offer(VoiceInput.StartRequested(GEN_2)))
    }

    @Test
    fun `isEmpty and size agree with what has actually been drained`() {
        val mailbox = VoiceInputMailbox()
        assertTrue(mailbox.isEmpty())
        assertEquals(0, mailbox.size)

        mailbox.offer(VoiceInput.StartRequested(GEN_1))
        assertFalse(mailbox.isEmpty())
        assertEquals(1, mailbox.size)

        mailbox.poll()
        assertTrue(mailbox.isEmpty())
        assertEquals(0, mailbox.size)
    }

    private companion object {
        val GEN_1 = VoiceSessionId("11111111111111111111111111111111")
        val GEN_2 = VoiceSessionId("22222222222222222222222222222222")
        const val SDP = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\n"
        const val CANDIDATE = "candidate:1 1 udp 1 192.0.2.11 51234 typ host"
    }
}
