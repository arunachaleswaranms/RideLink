package com.ridelink.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-024 **Amendment A11**'s bound, in isolation.
 *
 * The coordinator suites prove that the *right callers* ask this the right question at the right
 * instant; this proves the answers themselves — bounded, exactly-once, immune to ABA, and unable to
 * let a retired lifetime's cleanup free a successor's capacity.
 *
 * The mirror is `RideLinkCoreTests.SessionWorkLedgerTests`.
 */
class SessionWorkLedgerTest {
    @Test
    fun `capacity is a hard bound and a refusal changes nothing`() {
        val ledger = SessionWorkLedger(capacity = 3)
        val held = (1..3).map { assertNotNull(ledger.reserve(generation = 1)) }
        assertEquals(3, ledger.liveCount)
        assertNull(ledger.reserve(generation = 1), "the fourth is refused")
        assertEquals(3, ledger.liveCount, "and a refusal consumes nothing")
        ledger.leavePhase(held.first())
        assertEquals(2, ledger.liveCount)
        assertNotNull(ledger.reserve(generation = 1), "released capacity is genuinely reusable")
    }

    @Test
    fun `ids are never reused, so a stale release can never free a successor's capacity`() {
        val ledger = SessionWorkLedger(capacity = 1)
        val first = assertNotNull(ledger.reserve(generation = 1))
        ledger.leavePhase(first)
        val second = assertNotNull(ledger.reserve(generation = 2))
        assertTrue(second.id > first.id, "monotonic, so no ABA")
        // The retired holder hands its token back a second time, late.
        ledger.leavePhase(first)
        assertEquals(1, ledger.liveCount, "the successor's obligation is untouched")
        assertTrue(ledger.isLive(second))
        assertNull(ledger.reserve(generation = 2), "and the bound still holds")
    }

    @Test
    fun `a double release frees exactly one obligation`() {
        val ledger = SessionWorkLedger(capacity = 2)
        val a = assertNotNull(ledger.reserve(generation = 1))
        val b = assertNotNull(ledger.reserve(generation = 1))
        ledger.leavePhase(a)
        ledger.leavePhase(a)
        ledger.leavePhase(a)
        assertEquals(1, ledger.liveCount)
        assertTrue(ledger.isLive(b))
    }

    @Test
    fun `an obligation survives until its last phase leaves`() {
        val ledger = SessionWorkLedger(capacity = 1)
        val reservation = assertNotNull(ledger.reserve(generation = 1))
        assertTrue(ledger.enterPhase(reservation), "the scheduled node joins the apply's obligation")
        ledger.leavePhase(reservation) // the apply finished
        assertEquals(1, ledger.liveCount, "the armed effect still owes work")
        assertNull(ledger.reserve(generation = 1))
        ledger.leavePhase(reservation) // the armed effect ran
        assertEquals(0, ledger.liveCount)
    }

    @Test
    fun `a phase cannot join an obligation a boundary has already released`() {
        val ledger = SessionWorkLedger(capacity = 2)
        val reservation = assertNotNull(ledger.reserve(generation = 1))
        ledger.retire(throughGeneration = 1)
        assertFalse(ledger.enterPhase(reservation), "so the caller must not create the work either")
        assertEquals(0, ledger.liveCount)
    }

    @Test
    fun `retiring a lifetime releases its own obligations and never a newer one's`() {
        val ledger = SessionWorkLedger(capacity = 8)
        val old = listOf(ledger.reserve(1), ledger.reserve(1), ledger.reserve(2)).map { assertNotNull(it) }
        val fresh = assertNotNull(ledger.reserve(generation = 3))
        assertEquals(4, ledger.liveCount)

        // Generation 2 ended. Generations strictly increase, so everything at or below it ended too.
        assertEquals(3, ledger.retire(throughGeneration = 2))
        assertEquals(1, ledger.liveCount)
        assertTrue(ledger.isLive(fresh), "the successor keeps the capacity it had already reserved")
        for (reservation in old) assertFalse(ledger.isLive(reservation))

        // And the retired lifetime's late releases still free nothing of the successor's.
        for (reservation in old) ledger.leavePhase(reservation)
        assertEquals(1, ledger.liveCount)
    }

    @Test
    fun `clear is terminal`() {
        val ledger = SessionWorkLedger(capacity = 4)
        repeat(4) { ledger.reserve(generation = 7) }
        ledger.clear()
        assertEquals(0, ledger.liveCount)
        assertNotNull(ledger.reserve(generation = 8))
    }

    @Test
    fun `a long alternating run never exceeds the bound and never leaks`() {
        val ledger = SessionWorkLedger(capacity = 16)
        var generation = 1L
        val live = ArrayDeque<WorkReservation>()
        repeat(10_000) { step ->
            if (step % 500 == 499) {
                ledger.retire(throughGeneration = generation)
                live.clear()
                generation += 1
            }
            val reservation = ledger.reserve(generation)
            if (reservation == null) {
                // At the bound: discharge the oldest exactly as a completed effect does.
                ledger.leavePhase(live.removeFirst())
            } else {
                ledger.enterPhase(reservation)
                ledger.leavePhase(reservation) // the apply phase
                live.addLast(reservation) // the armed phase is still outstanding
            }
            assertTrue(ledger.liveCount <= 16, "step $step exceeded the bound")
        }
        ledger.retire(throughGeneration = generation)
        assertEquals(0, ledger.liveCount, "nothing is left holding capacity")
    }
}
