package com.ridelink.core.sessionfsm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Repeated complete FSM journeys; transport/authentication effects have separate integration tests. */
class ReleaseLifecyclePropertyTest {
    @Test
    fun `1000 sessions each run three rides with recovery ending and restart`() {
        var state = FsmState(SessionStatus.IDLE)
        var releases = 0

        fun step(event: SessionEvent) {
            val result = assertIs<FsmResult.Transitioned>(SessionFsm.transition(state, event))
            releases += result.effects.count { it is Effect.ReleaseAudioAndStopForegroundService }
            state = result.newState
        }
        repeat(1_000) { cycle ->
            step(SessionEvent.StartDiscovery)
            step(SessionEvent.PeerSelected)
            assertIs<FsmResult.Rejected>(SessionFsm.transition(state, SessionEvent.StartRide))
            step(SessionEvent.PairingSucceeded)
            step(SessionEvent.ConnectionEstablished)
            repeat(3) { ride ->
                step(SessionEvent.StartRide)
                if ((cycle + ride) % 2 == 0) {
                    step(SessionEvent.LinkLost(LinkLossReason.NETWORK))
                    step(SessionEvent.EndRide)
                    assertEquals(FsmState(SessionStatus.RECONNECTING, SessionStatus.CONNECTED), state)
                    assertIs<FsmResult.Rejected>(SessionFsm.transition(state, SessionEvent.EndRide))
                    step(SessionEvent.ReconnectSucceeded)
                } else {
                    step(SessionEvent.LinkLost(LinkLossReason.NETWORK))
                    step(SessionEvent.ReconnectSucceeded)
                    assertEquals(SessionStatus.RIDE_ACTIVE, state.status)
                    step(SessionEvent.EndRide)
                }
                assertEquals(SessionStatus.CONNECTED, state.status)
                assertEquals(cycle, releases, "a recovery must not release the control session")
            }
            step(SessionEvent.StartRide)
            step(SessionEvent.LinkLost(LinkLossReason.NETWORK))
            step(SessionEvent.ReconnectBudgetExhausted)
            step(SessionEvent.EndRide)
            assertEquals(SessionStatus.ENDING, state.status)
            assertIs<FsmResult.Rejected>(SessionFsm.transition(state, SessionEvent.StartDiscovery))
            step(SessionEvent.TeardownComplete)
            assertEquals(SessionStatus.IDLE, state.status)
            assertEquals(cycle + 1, releases)
        }
    }
}
