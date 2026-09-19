package com.ridelink.core.resync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Exhausts [StateResyncGate], the one new pure decision Phase 7 adds. `RideLinkPlatformTests`
 * mirrors these exact cases.
 */
class StateResyncGateTest {
    @Test
    fun `no pending request sends one`() {
        assertEquals(StateResyncGate.RequestDecision.SEND_REQUEST, StateResyncGate.onTrigger(null, 1L))
    }

    @Test
    fun `a request already pending for the live generation is not duplicated`() {
        assertEquals(StateResyncGate.RequestDecision.ALREADY_PENDING, StateResyncGate.onTrigger(5L, 5L))
    }

    @Test
    fun `a request pending for a retired generation does not block a fresh one`() {
        // The exact reconnect case: generation 5's request never resolved before generation 6
        // authenticated. A stale pending flag must not wedge the new session's own trigger shut.
        assertEquals(StateResyncGate.RequestDecision.SEND_REQUEST, StateResyncGate.onTrigger(5L, 6L))
    }

    @Test
    fun `a snapshot for the pending generation clears it`() {
        assertNull(StateResyncGate.onSnapshotObserved(5L, 5L))
    }

    @Test
    fun `a snapshot for a foreign generation leaves the pending request untouched`() {
        // A delayed reply from a dead session (or, by construction, an impossible future one)
        // must not clear the live generation's own outstanding request.
        assertEquals(5L, StateResyncGate.onSnapshotObserved(5L, 4L))
        assertEquals(5L, StateResyncGate.onSnapshotObserved(5L, 6L))
    }

    @Test
    fun `a snapshot observed with nothing pending stays a no-op`() {
        assertNull(StateResyncGate.onSnapshotObserved(null, 5L))
    }

    @Test
    fun `trigger then observe round-trips back to the ability to send again`() {
        var pending: Long? = null
        assertEquals(StateResyncGate.RequestDecision.SEND_REQUEST, StateResyncGate.onTrigger(pending, 1L))
        pending = 1L
        assertEquals(StateResyncGate.RequestDecision.ALREADY_PENDING, StateResyncGate.onTrigger(pending, 1L))
        pending = StateResyncGate.onSnapshotObserved(pending, 1L)
        assertNull(pending)
        assertEquals(StateResyncGate.RequestDecision.SEND_REQUEST, StateResyncGate.onTrigger(pending, 1L))
    }
}
