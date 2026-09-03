package com.ridelink.core.audiopolicy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exhausts [IntercomCommandMailbox] in isolation — no controller, no coroutine.
 *
 * This phase's brief §38 requires every new input stream to have an explicit finite buffering
 * policy. This mailbox's is "one slot per kind", so the tests below are about proving the bound is
 * structural rather than a capacity check that could be raised later. The mirror is
 * `RideLinkCoreTests.IntercomCommandMailboxTests`.
 */
class IntercomCommandMailboxTest {
    @Test
    fun `an empty mailbox polls to null`() {
        val mailbox = IntercomCommandMailbox()
        assertTrue(mailbox.isEmpty())
        assertNull(mailbox.poll())
        assertEquals(0, mailbox.size)
    }

    /**
     * The bound, demonstrated rather than asserted from the constant: ten thousand PTT edges cannot
     * make the mailbox hold more than one of them.
     */
    @Test
    fun `ten thousand ptt edges hold exactly one slot`() {
        val mailbox = IntercomCommandMailbox()
        repeat(FLOOD) { index -> mailbox.offer(IntercomInput.PttHeld(index % 2 == 0)) }
        assertEquals(1, mailbox.size, "one slot per kind, whatever the arrival rate")
        assertEquals(IntercomInput.PttHeld(FLOOD % 2 != 0), mailbox.poll(), "the newest value survives")
        assertTrue(mailbox.isEmpty())
    }

    /** Every kind at once is the whole capacity, and there is no eleventh slot to overflow into. */
    @Test
    fun `one of every kind fills the mailbox to its structural capacity`() {
        val mailbox = IntercomCommandMailbox()
        val everyKind =
            listOf(
                IntercomInput.PolicySelected(IntercomPolicy.MODE_A),
                IntercomInput.CaptureOpen(true),
                IntercomInput.Interrupted(false),
                IntercomInput.UserMuted(false),
                IntercomInput.PttHeld(true),
                IntercomInput.SpeechLevel(-10.0, 1_000),
            )
        for (input in everyKind) assertEquals(IntercomMailboxOutcome.ACCEPTED, mailbox.offer(input))
        assertEquals(IntercomCommandMailbox.CAPACITY, mailbox.size, "one slot per kind, all six")
        assertEquals(IntercomCommandKind.entries.size, IntercomCommandMailbox.CAPACITY, "the constant must match reality")

        // Flooding every kind again cannot grow it.
        repeat(FLOOD) { for (input in everyKind) mailbox.offer(input) }
        assertEquals(IntercomCommandMailbox.CAPACITY, mailbox.size, "still bounded after a flood of every kind")
    }

    /**
     * The drain order is the safety property. Policy and capture come first because they reset the
     * gate's transient state; the two overrides that can only ever *stop* transmission come next;
     * the gate inputs come last. A batch that contains a reason not to transmit therefore always
     * lands with transmission off.
     */
    @Test
    fun `poll returns kinds in the declared safety order`() {
        val mailbox = IntercomCommandMailbox()
        // Offered in the reverse of the expected drain order, so the order cannot be an accident of
        // insertion.
        mailbox.offer(IntercomInput.SpeechLevel(-10.0, 1))
        mailbox.offer(IntercomInput.PttHeld(true))
        mailbox.offer(IntercomInput.UserMuted(true))
        mailbox.offer(IntercomInput.Interrupted(true))
        mailbox.offer(IntercomInput.CaptureOpen(true))
        mailbox.offer(IntercomInput.PolicySelected(IntercomPolicy.MODE_C))

        val drained = generateSequence { mailbox.poll() }.map { IntercomCommandMailbox.kindFor(it) }.toList()
        assertEquals(IntercomCommandKind.entries.toList(), drained, "drain order must be the declared kind order")
    }

    /**
     * **The guarantee the fixed drain order buys.** [IntercomTransmission] is deliberately not
     * commutative across kinds — a policy switch and a capture close reset the gate's transient
     * state — so what callers need is not an order-blind reducer but an order-blind *pipeline*. Every
     * arrival permutation of one batch drains to the same state, because the mailbox imposes the
     * order rather than inheriting it from whichever thread got there first.
     */
    @Test
    fun `every arrival order of one batch drains to the same state`() {
        val batch =
            listOf<IntercomInput>(
                IntercomInput.PolicySelected(IntercomPolicy.MODE_A),
                IntercomInput.CaptureOpen(true),
                IntercomInput.Interrupted(false),
                IntercomInput.UserMuted(false),
                IntercomInput.PttHeld(true),
            )
        val results =
            permutations(batch).map { order ->
                val mailbox = IntercomCommandMailbox()
                for (input in order) mailbox.offer(input)
                var state = TransmissionState(policy = IntercomPolicy.MODE_C)
                while (true) {
                    val next = mailbox.poll() ?: break
                    state = IntercomTransmission.reduce(state, next).state
                }
                state
            }
        assertEquals(FACTORIAL_5, results.size, "every permutation must be exercised")
        assertEquals(1, results.distinct().size, "arrival order must not change the drained state")
        assertTrue(results.first().transmitting, "and this particular batch ends up transmitting")
    }

    /**
     * The safety half of the same property: a batch that pairs a PTT press with **any one** reason not
     * to transmit drains with transmission off, whatever order the two arrived in.
     *
     * Each stopper is a different [IntercomCommandKind] from the press, which is the case that
     * matters — two values of the *same* kind coalesce and the newest legitimately wins, so a batch
     * holding both `CaptureOpen(true)` and `CaptureOpen(false)` is a contradiction the mailbox is
     * right to resolve by recency rather than by pessimism.
     */
    @Test
    fun `a ptt press batched with any single reason not to transmit never drains to transmitting`() {
        val stoppers =
            listOf<IntercomInput>(
                IntercomInput.UserMuted(true),
                IntercomInput.Interrupted(true),
                IntercomInput.CaptureOpen(false),
                IntercomInput.PolicySelected(IntercomPolicy.MODE_E),
            )
        for (stopper in stoppers) {
            for (order in permutations(listOf(IntercomInput.PttHeld(true), stopper))) {
                val mailbox = IntercomCommandMailbox()
                for (input in order) mailbox.offer(input)
                var state = TransmissionState(policy = IntercomPolicy.MODE_C, captureOpen = true)
                while (true) {
                    val next = mailbox.poll() ?: break
                    state = IntercomTransmission.reduce(state, next).state
                }
                assertFalse(state.transmitting, "$stopper arriving in order $order left transmission on")
            }
        }
    }

    /**
     * And the converse, so the test above is not passing vacuously: the same press with no stopper in
     * the batch **does** transmit.
     */
    @Test
    fun `a ptt press with no stopper in the batch drains to transmitting`() {
        val mailbox = IntercomCommandMailbox()
        mailbox.offer(IntercomInput.PttHeld(true))
        var state = TransmissionState(policy = IntercomPolicy.MODE_C, captureOpen = true)
        while (true) {
            val next = mailbox.poll() ?: break
            state = IntercomTransmission.reduce(state, next).state
        }
        assertTrue(state.transmitting)
    }

    /**
     * A press *and* its release inside one drain window coalesce to "not held". That is lossy and it
     * is the safe direction of the loss: the alternative rounding would leave transmission stuck on
     * after a release, which this phase's brief §25 forbids outright.
     */
    @Test
    fun `a press and its release inside one window coalesce to not held`() {
        val mailbox = IntercomCommandMailbox()
        assertEquals(IntercomMailboxOutcome.ACCEPTED, mailbox.offer(IntercomInput.PttHeld(true)))
        assertEquals(IntercomMailboxOutcome.COALESCED, mailbox.offer(IntercomInput.PttHeld(false)))

        var state = TransmissionState(policy = IntercomPolicy.MODE_C, captureOpen = true)
        while (true) {
            val next = mailbox.poll() ?: break
            state = IntercomTransmission.reduce(state, next).state
        }
        assertFalse(state.transmitting, "a coalesced press/release must never leave transmission on")
    }

    /** A level and a tick share a slot: both answer "where is the VOX gate now". */
    @Test
    fun `a vox tick and a speech level share one slot`() {
        val mailbox = IntercomCommandMailbox()
        assertEquals(IntercomMailboxOutcome.ACCEPTED, mailbox.offer(IntercomInput.SpeechLevel(-10.0, 1_000)))
        assertEquals(IntercomMailboxOutcome.COALESCED, mailbox.offer(IntercomInput.VoxTick(2_000)))
        assertEquals(1, mailbox.size)
        assertEquals(IntercomInput.VoxTick(2_000), mailbox.poll())
    }

    @Test
    fun `coalescing is counted so the diagnostics can say it happened`() {
        val mailbox = IntercomCommandMailbox()
        mailbox.offer(IntercomInput.PttHeld(true))
        mailbox.offer(IntercomInput.PttHeld(false))
        mailbox.offer(IntercomInput.PttHeld(true))
        assertEquals(2, mailbox.coalescedCount, "two replacements of an undelivered value")
    }

    @Test
    fun `clear empties every slot`() {
        val mailbox = IntercomCommandMailbox()
        mailbox.offer(IntercomInput.PttHeld(true))
        mailbox.offer(IntercomInput.UserMuted(true))
        mailbox.clear()
        assertTrue(mailbox.isEmpty())
        assertNull(mailbox.poll())
    }

    /** Every input has exactly one kind, so no input can escape the bound by being unclassified. */
    @Test
    fun `every input kind is classified`() {
        val inputs =
            listOf(
                IntercomInput.PolicySelected(IntercomPolicy.MODE_A) to IntercomCommandKind.POLICY,
                IntercomInput.CaptureOpen(true) to IntercomCommandKind.CAPTURE,
                IntercomInput.Interrupted(true) to IntercomCommandKind.INTERRUPTED,
                IntercomInput.UserMuted(true) to IntercomCommandKind.USER_MUTED,
                IntercomInput.PttHeld(true) to IntercomCommandKind.PTT_HELD,
                IntercomInput.SpeechLevel(0.0, 0) to IntercomCommandKind.VOX_LEVEL,
                IntercomInput.VoxTick(0) to IntercomCommandKind.VOX_LEVEL,
            )
        for ((input, kind) in inputs) {
            assertEquals(kind, IntercomCommandMailbox.kindFor(input), "$input classification")
        }
        assertEquals(
            IntercomCommandKind.entries.toSet(),
            inputs.map { it.second }.toSet(),
            "every kind must be reachable from some input",
        )
    }

    private fun <T> permutations(items: List<T>): List<List<T>> {
        if (items.size <= 1) return listOf(items)
        return items.indices.flatMap { index ->
            val rest = items.toMutableList().also { it.removeAt(index) }
            permutations(rest).map { listOf(items[index]) + it }
        }
    }

    private companion object {
        const val FLOOD = 10_000
        const val FACTORIAL_5 = 120
    }
}
