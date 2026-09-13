package com.ridelink.app.ui

import androidx.compose.material3.Button
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
) {
    val action = sessionAction(status) ?: return
    Button(onClick = {
        when (action) {
            SessionAction.START -> coordinator.startDiscovery()
            SessionAction.STOP_DISCOVERY -> coordinator.cancelDiscovery()
            SessionAction.END -> coordinator.endSession()
            SessionAction.RETRY -> coordinator.retryDiscovery()
        }
    }) {
        Text(action.label)
    }
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
    START("Start Discovery"),
    STOP_DISCOVERY("Stop Discovery"),
    END("End Session"),
    RETRY("Retry"),
}

private fun sessionAction(status: SessionStatus): SessionAction? =
    when (status) {
        SessionStatus.IDLE -> SessionAction.START
        SessionStatus.DISCOVERING -> SessionAction.STOP_DISCOVERY
        SessionStatus.CONNECTED, SessionStatus.RIDE_ACTIVE, SessionStatus.RECONNECTING -> SessionAction.END
        SessionStatus.DISCONNECTED -> SessionAction.RETRY
        SessionStatus.PAIRING, SessionStatus.CONNECTING, SessionStatus.ENDING, SessionStatus.ERROR -> null
    }
