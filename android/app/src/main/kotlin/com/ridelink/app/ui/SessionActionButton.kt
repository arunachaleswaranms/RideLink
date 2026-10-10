package com.ridelink.app.ui

import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.ridelink.app.session.SessionCoordinator
import com.ridelink.core.sessionfsm.SessionStatus

/**
 * **The one session button** — Start Discovery / Stop Discovery / End Session / Retry — and the
 * mapping that decides which of them a given state offers.
 *
 * Its own file rather than a block inside `MainScreen` for the reason `config/detekt/detekt.yml`
 * prescribes: extract rather than raise a threshold. `MainScreen` had reached both its `LongMethod`
 * and its `TooManyFunctions` ceiling, and a screen's one lifecycle affordance is a coherent thing to
 * lift out.
 */
@Composable
fun SessionActionButton(
    status: SessionStatus,
    coordinator: SessionCoordinator,
    /** Drawn outlined when another action (Start ride) is the one the screen is leading with. */
    secondary: Boolean = false,
) {
    val action = sessionAction(status) ?: return
    val onClick = {
        when (action) {
            SessionAction.START -> coordinator.startDiscovery()
            SessionAction.STOP_DISCOVERY -> coordinator.cancelDiscovery()
            SessionAction.END -> coordinator.endSession()
            SessionAction.RETRY -> coordinator.retryDiscovery()
        }
    }
    if (secondary || action == SessionAction.STOP_DISCOVERY) {
        OutlinedButton(onClick = onClick) { Text(action.label) }
    } else {
        Button(onClick = onClick) { Text(action.label) }
    }
}

/**
 * Phase 7 (ADR-028): the only production entry point into Ride Mode — `RideLinkRoot` switches
 * screens the instant `SessionFsm` actually reaches `RIDE_ACTIVE`, so this button never mutates
 * presentation state itself, only the FSM. Lives beside [SessionActionButton] for the same reason
 * that one lives in its own file: one lifecycle affordance per composable.
 */
@Composable
fun StartRideButton(
    status: SessionStatus,
    onStartRide: () -> Unit,
) {
    if (status != SessionStatus.CONNECTED) return
    Button(onClick = onStartRide) { Text("Start ride") }
}

/**
 * What the one session button does from a given state — the UI half of `docs/STATUS.md` §4 problem
 * 53. Every entry maps to an event [com.ridelink.core.sessionfsm.SessionFsm] accepts from that
 * state, so the button is never offered for a transition the FSM would reject.
 *
 * `null` for `PAIRING`, `CONNECTING` and `ENDING`: the first two are waiting on two humans or a
 * handshake and have no FSM event to abandon them, and `ENDING` is a teardown in progress — offering
 * a button there would invite exactly the successor race this pass closes.
 *
 * `null` for `ERROR` too, and for a different reason: its only exit is `ErrorAcknowledged`, and
 * nothing in the app emits `FatalError`, so the state cannot be entered. Wiring a button to a state
 * no production path reaches would be dead code; the remaining gap is recorded in `docs/STATUS.md`
 * §4 rather than papered over here.
 */
private enum class SessionAction(
    val label: String,
) {
    START("Find other phone"),
    STOP_DISCOVERY("Stop searching"),
    END("End session"),
    RETRY("Search again"),
}

private fun sessionAction(status: SessionStatus): SessionAction? =
    when (status) {
        SessionStatus.IDLE -> SessionAction.START
        SessionStatus.DISCOVERING -> SessionAction.STOP_DISCOVERY
        SessionStatus.CONNECTED, SessionStatus.RIDE_ACTIVE, SessionStatus.RECONNECTING -> SessionAction.END
        SessionStatus.DISCONNECTED -> SessionAction.RETRY
        SessionStatus.PAIRING, SessionStatus.CONNECTING, SessionStatus.ENDING, SessionStatus.ERROR -> null
    }
