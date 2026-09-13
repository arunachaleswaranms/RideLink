package com.ridelink.core.voice

import com.ridelink.core.protocol.VoiceMode
import com.ridelink.core.protocol.VoiceSessionId
import com.ridelink.core.protocol.VoiceSignal
import com.ridelink.core.protocol.VoiceWireState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **STATUS §4 problem 60 — semantic voice work is scoped by the control lifetime that admitted it,
 * never by the order it happened to arrive in.**
 *
 * Problem 50's fix gave `ControlLinkLost` ownership of the remote signals queued below it, and took
 * *offer time* as the instant at which that ownership was least wrong. It is least wrong; it is not
 * right. A discard with no lifetime identity is wrong in both directions, and this file is the pure
 * half of the proof that neither direction remains:
 *
 * - **retired work admitted late** — `VoiceSignalRelay.deliver` reads the live generation and then
 *   calls `sink.submit`, with nothing spanning the two, so a frame can pass the liveness check and
 *   be overtaken by the whole teardown before it is queued;
 * - **live work discarded early** — `ControlEvent.LinkLost` reaches `VoiceController` through
 *   `SessionCoordinator`'s event consumer (and on iOS through one further `Task`), while an inbound
 *   promotion authenticates a **successor** through `ControlSessionManager.promote`, which waits on
 *   nothing that consumer does.
 *
 * Every case below is a straight-line sequence of `offer` calls with no scheduling in it at all.
 * That is the point: if the answer depended on *when* anything ran, this file could not exist.
 *
 * The mirror is `RideLinkCoreTests.VoiceMailboxLifetimeIdentityTests`.
 */
class VoiceMailboxLifetimeIdentityTest {
    /**
     * **P60-3 — queued A and queued B, then A retires.**
     *
     * The case the old blanket discard got exactly backwards: it removed both. A's is the retired
     * lifetime's and must go; B's was admitted by a lifetime that is still live and is the only copy
     * of a peer offer that will never be sent again, so deleting it wedges voice for the ride
     * segment exactly as problem 56 did.
     */
    @Test
    fun `retiring A discards A's queued signal and keeps B's`() {
        val mailbox = VoiceInputMailbox()
        mailbox.offer(signal(offerA, CONTROL_A))
        mailbox.offer(signal(offerB, CONTROL_B))

        mailbox.offer(VoiceInput.ControlLinkLost(CONTROL_A))

        assertEquals(1, mailbox.discardedRetiredSignalCount, "exactly one signal belonged to the retired lifetime")
        assertEquals(CONTROL_A, mailbox.retiredControlGenerationFloor)
        assertIs<VoiceInput.ControlLinkLost>(mailbox.poll(), "the teardown still applies first, and is never suppressed")
        val survivor = assertIs<VoiceInput.SignalReceived>(mailbox.poll(), "B's offer must still be queued")
        assertEquals(offerB, survivor.signal)
        assertEquals(CONTROL_B, survivor.controlGeneration)
        assertNull(mailbox.poll(), "and nothing else")
    }

    /**
     * **P60-4 — the same two signals, offered in the *opposite* order relative to the retirement.**
     *
     * A is retired first, and only then do both signals arrive. Nothing about the verdict changes,
     * which is the whole claim: `A` is refused because its lifetime is retired, not because it was
     * sitting in a queue when something ran.
     */
    @Test
    fun `after A is retired a late A signal is refused and a late B signal is accepted`() {
        val mailbox = VoiceInputMailbox()
        mailbox.offer(VoiceInput.ControlLinkLost(CONTROL_A))
        mailbox.poll() // the consumer applies the teardown; the mailbox is now empty

        assertEquals(VoiceMailboxOutcome.RetiredGeneration, mailbox.offer(signal(offerA, CONTROL_A)))
        assertIs<VoiceMailboxOutcome.Accepted>(mailbox.offer(signal(offerB, CONTROL_B)))

        assertEquals(1, mailbox.refusedRetiredSignalCount, "the retired lifetime's late signal is counted, not silent")
        assertEquals(0, mailbox.discardedRetiredSignalCount, "nothing was queued to discard")
        val survivor = assertIs<VoiceInput.SignalReceived>(mailbox.poll())
        assertEquals(CONTROL_B, survivor.controlGeneration)
        assertNull(mailbox.poll())
    }

    /** A refusal must not look like an overflow: nothing was lost that still mattered. */
    @Test
    fun `a refused retired signal is not counted as an overflow`() {
        val mailbox = VoiceInputMailbox()
        mailbox.offer(VoiceInput.ControlLinkLost(CONTROL_A))
        repeat(100) { mailbox.offer(signal(offerA, CONTROL_A)) }

        assertEquals(0, mailbox.overflowCount, "a retired signal occupies no lane, so it can overflow none")
        assertEquals(100, mailbox.refusedRetiredSignalCount)
    }

    /** Every lane a peer signal can land in, not just the critical one. */
    @Test
    fun `retirement reaches all four lanes a peer signal can occupy, and no local input`() {
        val mailbox = VoiceInputMailbox()
        val peerSignals =
            listOf(
                VoiceSignal.Offer(VSID_A, SDP), // critical
                VoiceSignal.IceCandidate(VSID_A, CANDIDATE, null, 0), // ice
                VoiceSignal.State(VSID_A, VoiceWireState.CLOSED, false, VoiceMode.CONTINUOUS), // terminal
                VoiceSignal.State(VSID_A, VoiceWireState.ACTIVE, false, VoiceMode.CONTINUOUS), // coalesced
            )
        peerSignals.forEach { mailbox.offer(signal(it, CONTROL_A)) }
        val localInputs =
            listOf(
                VoiceInput.StartRequested(VSID_FRESH),
                VoiceInput.LocalOfferCreated(VSID_A, SDP),
                VoiceInput.LocalCandidateGathered(VSID_A, CANDIDATE, null, 0),
                VoiceInput.MuteRequested(true),
                VoiceInput.ModeSelected(VoiceMode.PTT),
                VoiceInput.RemoteTrackChanged(VSID_A, true),
            )
        localInputs.forEach { mailbox.offer(it) }

        mailbox.offer(VoiceInput.ControlLinkLost(CONTROL_A))

        assertEquals(4, mailbox.discardedRetiredSignalCount, "one per lane the retired lifetime occupied")
        // ...and the successor's own four are accepted afterwards, into the same four lanes.
        peerSignals.forEach { assertIs<VoiceMailboxOutcome.Accepted>(mailbox.offer(signal(it, CONTROL_B))) }

        val drained = generateSequence { mailbox.poll() }.toList()
        val survivingSignals = drained.filterIsInstance<VoiceInput.SignalReceived>()
        assertEquals(4, survivingSignals.size, "every one of B's four signals survives")
        assertTrue(
            survivingSignals.all { it.controlGeneration == CONTROL_B },
            "and every survivor is B's; drained=$survivingSignals",
        )
        localInputs.forEach { local ->
            assertTrue(drained.contains(local), "a local input is never a retired peer's to withdraw: $local")
        }
    }

    /**
     * **The window a boundary alone cannot close.** An A-generation signal that passed
     * `VoiceSignalRelay`'s liveness check an instant before the teardown can be offered while B's
     * work is already queued and **before** `ControlLinkLost(A)` has been delivered at all — that
     * gap is Window 1, and neither this type nor its callers serialise it.
     *
     * The existence of B's admitted work is itself the proof that A ended:
     * `ControlSessionManager` holds one authenticated connection at a time and allocates a strictly
     * greater generation for each. So A's late signal is refused here with no boundary in sight.
     *
     * The [VoiceMailboxLane.COALESCED] lane is why this matters rather than merely being tidy: one
     * slot per kind, latest wins, and PROTOCOL §7.3's `negotiating` intent-to-talk lives in it. A
     * retired lifetime's peer state overwriting the successor's intent loses the one message that
     * starts the successor's negotiation, and voice is wedged for the ride segment.
     */
    @Test
    fun `a retired generation's late signal cannot overwrite a successor's, with no link loss delivered`() {
        val mailbox = VoiceInputMailbox()
        val successorIntent = VoiceSignal.State(null, VoiceWireState.NEGOTIATING, false, VoiceMode.CONTINUOUS)
        val retiredState = VoiceSignal.State(VSID_A, VoiceWireState.IDLE, false, VoiceMode.CONTINUOUS)

        mailbox.offer(signal(successorIntent, CONTROL_B))
        val outcome = mailbox.offer(signal(retiredState, CONTROL_A))

        assertEquals(VoiceMailboxOutcome.RetiredGeneration, outcome, "B's admitted work proves A has ended")
        assertNull(mailbox.retiredControlGenerationFloor, "and no ControlLinkLost has been delivered at all")
        assertEquals(CONTROL_B, mailbox.newestAdmittedControlGeneration)
        val held = assertIs<VoiceInput.SignalReceived>(mailbox.poll())
        assertEquals(successorIntent, held.signal, "the successor's intent-to-talk is still the slot's value")
    }

    /**
     * The same implication applied to work already queued: admitting a newer generation's signal
     * retires the older one's on the spot, without waiting for its boundary.
     */
    @Test
    fun `admitting a newer generation discards the older one's queued work immediately`() {
        val mailbox = VoiceInputMailbox()
        mailbox.offer(signal(offerA, CONTROL_A))

        mailbox.offer(signal(offerB, CONTROL_B))

        assertEquals(1, mailbox.discardedRetiredSignalCount)
        val survivor = assertIs<VoiceInput.SignalReceived>(mailbox.poll())
        assertEquals(CONTROL_B, survivor.controlGeneration)
        assertNull(mailbox.poll())
    }

    /** The other half: coalescing *within* one live lifetime is untouched — latest still wins. */
    @Test
    fun `two peer states from the same generation still coalesce to the newest`() {
        val mailbox = VoiceInputMailbox()
        val older = VoiceSignal.State(VSID_A, VoiceWireState.CONNECTING, false, VoiceMode.CONTINUOUS)
        val newer = VoiceSignal.State(VSID_A, VoiceWireState.ACTIVE, false, VoiceMode.CONTINUOUS)

        assertIs<VoiceMailboxOutcome.Accepted>(mailbox.offer(signal(older, CONTROL_B)))
        assertEquals(VoiceMailboxOutcome.Coalesced, mailbox.offer(signal(newer, CONTROL_B)))

        val held = assertIs<VoiceInput.SignalReceived>(mailbox.poll())
        assertEquals(newer, held.signal)
        assertEquals(0, mailbox.refusedRetiredSignalCount, "same lifetime, so nothing is stale")
    }

    /**
     * **The teardown itself is never suppressed**, and this is the regression that keeps it that way.
     *
     * Suppressing a boundary that a newer lifetime appeared to have superseded was tried while
     * building these regressions and **rejected** — see STATUS §4 problem 61. Admission is not
     * application: a successor's admitted offer can be dropped by `offerReceived`'s
     * `GENERATION_MISMATCH` against a still-live predecessor negotiation, so "a newer generation
     * admitted something" does not imply its negotiation is live, and suppressing on that premise
     * leaves a dead lifetime's negotiation standing with nothing able to replace it. The residue —
     * a boundary applied after a successor's work has already been *reduced* — needs the pure table
     * to know which control lifetime owns a negotiation, and is recorded rather than half-fixed.
     */
    @Test
    fun `a link loss is delivered to the reducer whatever else the mailbox has admitted`() {
        val mailbox = VoiceInputMailbox()
        mailbox.offer(signal(offerB, CONTROL_B))
        mailbox.poll() // the consumer applies B's offer

        assertIs<VoiceMailboxOutcome.Accepted>(mailbox.offer(VoiceInput.ControlLinkLost(CONTROL_A)))

        assertIs<VoiceInput.ControlLinkLost>(mailbox.poll(), "the reducer must still be told to stop the media")
        assertEquals(CONTROL_A, mailbox.retiredControlGenerationFloor, "and A is retired by it")
        assertEquals(VoiceMailboxOutcome.RetiredGeneration, mailbox.offer(signal(offerA, CONTROL_A)))
    }

    /**
     * **P60-5 — A -> B -> C.** Retiring A and then B must leave C untouched, and a late signal from
     * either retired lifetime must stay inert however long afterwards it arrives.
     */
    @Test
    fun `repeated reconnects retire only what has actually ended`() {
        val mailbox = VoiceInputMailbox()

        mailbox.offer(VoiceInput.ControlLinkLost(CONTROL_A))
        mailbox.poll()
        mailbox.offer(VoiceInput.ControlLinkLost(CONTROL_B))
        mailbox.poll()

        assertEquals(VoiceMailboxOutcome.RetiredGeneration, mailbox.offer(signal(offerA, CONTROL_A)), "A after B")
        assertEquals(VoiceMailboxOutcome.RetiredGeneration, mailbox.offer(signal(offerB, CONTROL_B)), "B after B")
        assertIs<VoiceMailboxOutcome.Accepted>(mailbox.offer(signal(offerC, CONTROL_C)))

        // C is live; A and C both arrive again, in that order, and the verdicts are unchanged.
        assertEquals(VoiceMailboxOutcome.RetiredGeneration, mailbox.offer(signal(offerA, CONTROL_A)), "A after C")
        assertIs<VoiceMailboxOutcome.Accepted>(mailbox.offer(signal(offerC, CONTROL_C)))

        val survivors = generateSequence { mailbox.poll() }.filterIsInstance<VoiceInput.SignalReceived>().toList()
        assertEquals(2, survivors.size)
        assertTrue(survivors.all { it.controlGeneration == CONTROL_C }, "only the live lifetime's work survives")
        assertEquals(3, mailbox.refusedRetiredSignalCount)
    }

    /**
     * The floor rises and never falls. A link loss for an **older** lifetime arriving after a newer
     * one has already been retired is stale news, and must not un-retire the newer one.
     *
     * This is the case ADR-024 Amendment A7 makes non-hypothetical: generation *arrival* is
     * deliberately non-monotonic, so `retire(B)` then `retire(A)` is a real ordering.
     */
    @Test
    fun `a late link loss for an older generation cannot lower the retired floor`() {
        val mailbox = VoiceInputMailbox()
        mailbox.offer(VoiceInput.ControlLinkLost(CONTROL_B))
        mailbox.poll()

        mailbox.offer(VoiceInput.ControlLinkLost(CONTROL_A))

        assertEquals(CONTROL_B, mailbox.retiredControlGenerationFloor, "B stays retired; A's late notice adds nothing")
        assertEquals(VoiceMailboxOutcome.RetiredGeneration, mailbox.offer(signal(offerB, CONTROL_B)))
        assertIs<VoiceMailboxOutcome.Accepted>(mailbox.offer(signal(offerC, CONTROL_C)))
    }

    /**
     * **P60-6 — a genuinely new ride session.** A fresh mailbox has no floor, so nothing a previous
     * session retired can poison it.
     *
     * That this is the *right* reset point rather than an arbitrary one is a fact about the
     * producers, and `ControlSessionGenerationMonotonicityTest` (Android `network`) proves the half
     * this file cannot see: `authenticationGeneration` strictly increases across a
     * `shutdown()`/`startListening()` cycle and is never reset, so even a mailbox that *did* survive
     * a session could only ever hold a floor below every generation the next session will use.
     */
    @Test
    fun `a new session's mailbox starts with nothing retired`() {
        val first = VoiceInputMailbox()
        first.offer(VoiceInput.ControlLinkLost(CONTROL_C))
        assertEquals(CONTROL_C, first.retiredControlGenerationFloor)

        val second = VoiceInputMailbox()
        assertNull(second.retiredControlGenerationFloor, "a new VoiceController's mailbox retires nothing")
        assertIs<VoiceMailboxOutcome.Accepted>(second.offer(signal(offerA, CONTROL_A)))
    }

    /**
     * **P60-7 — a `StopRequested` is not a lifetime boundary.** The user pressed End Voice; the link
     * is still up. It retires nothing and discards nothing, and — problem 57 — it is never displaced
     * by a link loss, because it is the only input `shutdown()` can complete on (ADR-026 / rule 21).
     */
    @Test
    fun `a stop retires no generation and is never erased by a link loss`() {
        val mailbox = VoiceInputMailbox()
        mailbox.offer(signal(offerA, CONTROL_A))
        mailbox.offer(VoiceInput.StopRequested)

        assertNull(mailbox.retiredControlGenerationFloor, "End Voice ends no control lifetime")
        assertEquals(0, mailbox.discardedRetiredSignalCount)

        mailbox.offer(VoiceInput.ControlLinkLost(CONTROL_A))

        assertEquals(VoiceInput.StopRequested, mailbox.poll(), "the stop survives; nothing may replace it")
        assertEquals(1, mailbox.discardedRetiredSignalCount, "the link loss still owned A's queued offer")
        assertEquals(CONTROL_A, mailbox.retiredControlGenerationFloor)
    }

    /**
     * A link loss that names **no** generation retires nothing. There are exactly two producers: a
     * connection that died before it ever authenticated, and the mailbox-overflow degrade. Neither
     * is a control-lifetime boundary, so neither owns anybody's queued work — which is a deliberate
     * narrowing of what CLAUDE.md rule 22's parenthetical used to license, made for the reason
     * problem 60 exists: deleting live work because something else went wrong is the defect.
     */
    @Test
    fun `a link loss naming no generation tears down without discarding anything`() {
        val mailbox = VoiceInputMailbox()
        mailbox.offer(signal(offerA, CONTROL_A))

        mailbox.offer(VoiceInput.ControlLinkLost(null))

        assertNull(mailbox.retiredControlGenerationFloor)
        assertEquals(0, mailbox.discardedRetiredSignalCount)
        assertIs<VoiceInput.ControlLinkLost>(mailbox.poll(), "the degrade is unchanged: the reducer still resets")
        val survivor = assertIs<VoiceInput.SignalReceived>(mailbox.poll(), "the live lifetime's work is not its to take")
        assertEquals(CONTROL_A, survivor.controlGeneration)
    }

    /**
     * **P60-8 — a send failure is negotiation-scoped and stays that way** (problem 57). It names a
     * `voice_session_id`, never a control generation, and it must retire nothing: doing otherwise
     * would let a `Boolean` that came back late speak for a lifetime, which is the defect problem 57
     * closed and problem 60 must not reopen.
     */
    @Test
    fun `a send failure retires no control generation and discards no successor work`() {
        val mailbox = VoiceInputMailbox()
        mailbox.offer(signal(offerB, CONTROL_B))

        mailbox.offer(VoiceInput.NegotiationSendFailed(VSID_A))

        assertNull(mailbox.retiredControlGenerationFloor, "a send failure is not a control-lifetime boundary")
        assertEquals(0, mailbox.discardedRetiredSignalCount)
        assertEquals(VoiceInput.NegotiationSendFailed(VSID_A), mailbox.poll(), "it still outranks the critical lane")
        val survivor = assertIs<VoiceInput.SignalReceived>(mailbox.poll())
        assertEquals(CONTROL_B, survivor.controlGeneration, "the successor's offer is untouched")
    }

    private fun signal(
        signal: VoiceSignal,
        controlGeneration: Long,
    ) = VoiceInput.SignalReceived(signal, controlGeneration, VSID_FRESH)

    private companion object {
        /**
         * Three **control authentication** generations, as `activateAuthenticatedSession` allocates
         * them: strictly increasing, one per trust-gate pass, never reused. A different identity
         * from the `voice_session_id`s below, which own a WebRTC negotiation rather than a control
         * lifetime.
         */
        const val CONTROL_A = 1L
        const val CONTROL_B = 2L
        const val CONTROL_C = 3L

        val VSID_A = VoiceSessionId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        val VSID_FRESH = VoiceSessionId("ffffffffffffffffffffffffffffffff")
        const val SDP = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\n"
        const val CANDIDATE = "candidate:1 1 udp 1 192.0.2.11 51234 typ host"

        val offerA = VoiceSignal.Offer(VoiceSessionId("11111111111111111111111111111111"), SDP)
        val offerB = VoiceSignal.Offer(VoiceSessionId("22222222222222222222222222222222"), SDP)
        val offerC = VoiceSignal.Offer(VoiceSessionId("33333333333333333333333333333333"), SDP)
    }
}
