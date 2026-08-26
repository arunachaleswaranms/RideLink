package com.ridelink.core.sessionfsm

/** The 10 states of ARCHITECTURE §3. */
enum class SessionStatus {
    IDLE,
    DISCOVERING,
    PAIRING,
    CONNECTING,
    CONNECTED,
    RIDE_ACTIVE,
    RECONNECTING,
    DISCONNECTED,
    ENDING,
    ERROR,
}

/**
 * [returnTo] is only meaningful when [status] is [SessionStatus.RECONNECTING]: it records which
 * state the reconnect attempt is trying to return to (ARCHITECTURE §3 rule 1 — "RECONNECTING
 * returns to the state it left, never skips forward"). It is always [SessionStatus.CONNECTED] or
 * [SessionStatus.RIDE_ACTIVE].
 */
data class FsmState(
    val status: SessionStatus,
    val returnTo: SessionStatus? = null,
) {
    init {
        require((status == SessionStatus.RECONNECTING) == (returnTo != null)) {
            "returnTo must be set if and only if status is RECONNECTING"
        }
        if (returnTo != null) {
            require(returnTo == SessionStatus.CONNECTED || returnTo == SessionStatus.RIDE_ACTIVE) {
                "returnTo must be CONNECTED or RIDE_ACTIVE"
            }
        }
    }

    companion object {
        val INITIAL = FsmState(SessionStatus.IDLE)
    }
}

enum class LinkLossReason { NETWORK, BYE }

sealed class SessionEvent {
    object StartDiscovery : SessionEvent()

    object CancelDiscovery : SessionEvent()

    object PeerSelected : SessionEvent()

    object PairingRejectedOrTimeout : SessionEvent()

    object PairingSucceeded : SessionEvent()

    object ConnectionEstablished : SessionEvent()

    object ConnectionFailed : SessionEvent()

    object ReconnectSucceeded : SessionEvent()

    object ReconnectBudgetExhausted : SessionEvent()

    object RetryRequested : SessionEvent()

    object StartRide : SessionEvent()

    object EndRide : SessionEvent()

    data class LinkLost(
        val reason: LinkLossReason,
    ) : SessionEvent()

    object UserEnded : SessionEvent()

    data class FatalError(
        val reason: String,
    ) : SessionEvent()

    object ErrorAcknowledged : SessionEvent()

    object TeardownComplete : SessionEvent()

    /** Closing a duplicate connection (ADR-015) is not a fault and not a transition attempt. */
    object DuplicateConnectionClosed : SessionEvent()
}

sealed class Effect {
    data class LogTransition(
        val from: FsmState,
        val to: FsmState,
        val trigger: SessionEvent,
    ) : Effect()

    /** Only ENDING may release the audio session and stop the foreground service (ARCHITECTURE §3 rule 3). */
    object ReleaseAudioAndStopForegroundService : Effect()
}

sealed class FsmResult {
    data class Transitioned(
        val newState: FsmState,
        val effects: List<Effect>,
    ) : FsmResult()

    /** An event that is not a legal transition from [currentState]. State is unchanged. */
    data class Rejected(
        val currentState: FsmState,
        val event: SessionEvent,
    ) : FsmResult()

    /**
     * An event that is legitimately a no-op: not a transition, and — unlike [Rejected] — not a
     * fault either. The only current case is [SessionEvent.DuplicateConnectionClosed]
     * (ARCHITECTURE §3 rule 6).
     */
    data class Ignored(
        val currentState: FsmState,
        val event: SessionEvent,
        val reason: String,
    ) : FsmResult()
}

/**
 * `(state, event) -> (state, effects)`. Pure: no platform types, no networking, no clock reads
 * (CLAUDE.md rule 9 / ARCHITECTURE §2's "hard rule").
 */
object SessionFsm {
    @Suppress("ReturnCount")
    fun transition(
        state: FsmState,
        event: SessionEvent,
    ): FsmResult {
        if (event is SessionEvent.DuplicateConnectionClosed) {
            return FsmResult.Ignored(state, event, "duplicate_connection_close_is_not_a_fault")
        }

        // Fatal errors are legal from any state except ERROR itself; handled first because they
        // short-circuit the per-state table below.
        if (event is SessionEvent.FatalError &&
            state.status != SessionStatus.ERROR
        ) {
            return transitioned(state, FsmState(SessionStatus.ERROR), event)
        }

        val newState: FsmState? = computeNextState(state, event)
        return if (newState != null) {
            transitioned(state, newState, event)
        } else {
            FsmResult.Rejected(state, event)
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun computeNextState(
        state: FsmState,
        event: SessionEvent,
    ): FsmState? {
        val s = state.status
        return when (event) {
            is SessionEvent.StartDiscovery ->
                if (s == SessionStatus.IDLE) FsmState(SessionStatus.DISCOVERING) else null

            is SessionEvent.CancelDiscovery ->
                if (s == SessionStatus.DISCOVERING) FsmState(SessionStatus.IDLE) else null

            is SessionEvent.PeerSelected ->
                if (s == SessionStatus.DISCOVERING) FsmState(SessionStatus.PAIRING) else null

            is SessionEvent.PairingRejectedOrTimeout ->
                if (s == SessionStatus.PAIRING) FsmState(SessionStatus.DISCOVERING) else null

            is SessionEvent.PairingSucceeded ->
                if (s == SessionStatus.PAIRING) FsmState(SessionStatus.CONNECTING) else null

            is SessionEvent.ConnectionEstablished ->
                if (s == SessionStatus.CONNECTING) FsmState(SessionStatus.CONNECTED) else null

            is SessionEvent.ConnectionFailed ->
                if (s == SessionStatus.CONNECTING) {
                    FsmState(SessionStatus.RECONNECTING, SessionStatus.CONNECTED)
                } else {
                    null
                }

            is SessionEvent.ReconnectSucceeded ->
                if (s == SessionStatus.RECONNECTING) FsmState(requireNotNull(state.returnTo)) else null

            is SessionEvent.ReconnectBudgetExhausted ->
                if (s == SessionStatus.RECONNECTING) FsmState(SessionStatus.DISCONNECTED) else null

            is SessionEvent.RetryRequested ->
                if (s == SessionStatus.DISCONNECTED) FsmState(SessionStatus.DISCOVERING) else null

            is SessionEvent.StartRide ->
                if (s == SessionStatus.CONNECTED) FsmState(SessionStatus.RIDE_ACTIVE) else null

            is SessionEvent.EndRide ->
                if (s == SessionStatus.RIDE_ACTIVE) FsmState(SessionStatus.CONNECTED) else null

            is SessionEvent.LinkLost ->
                when {
                    event.reason == LinkLossReason.BYE &&
                        (s == SessionStatus.CONNECTED || s == SessionStatus.RIDE_ACTIVE || s == SessionStatus.RECONNECTING) ->
                        FsmState(SessionStatus.ENDING)
                    event.reason == LinkLossReason.NETWORK && s == SessionStatus.CONNECTED ->
                        FsmState(SessionStatus.RECONNECTING, SessionStatus.CONNECTED)
                    event.reason == LinkLossReason.NETWORK && s == SessionStatus.RIDE_ACTIVE ->
                        FsmState(SessionStatus.RECONNECTING, SessionStatus.RIDE_ACTIVE)
                    else -> null
                }

            is SessionEvent.UserEnded ->
                if (s == SessionStatus.CONNECTED ||
                    s == SessionStatus.RIDE_ACTIVE ||
                    s == SessionStatus.RECONNECTING ||
                    s == SessionStatus.DISCONNECTED
                ) {
                    FsmState(SessionStatus.ENDING)
                } else {
                    null
                }

            is SessionEvent.ErrorAcknowledged ->
                if (s == SessionStatus.ERROR) FsmState(SessionStatus.ENDING) else null

            is SessionEvent.TeardownComplete ->
                if (s == SessionStatus.ENDING) FsmState(SessionStatus.IDLE) else null

            is SessionEvent.FatalError, is SessionEvent.DuplicateConnectionClosed -> null
        }
    }

    private fun transitioned(
        from: FsmState,
        to: FsmState,
        trigger: SessionEvent,
    ): FsmResult.Transitioned {
        val effects =
            buildList {
                add(Effect.LogTransition(from, to, trigger))
                if (to.status == SessionStatus.ENDING) {
                    add(Effect.ReleaseAudioAndStopForegroundService)
                }
            }
        return FsmResult.Transitioned(to, effects)
    }
}
