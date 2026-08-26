package com.ridelink.app.session

import com.ridelink.core.logging.LogSink
import com.ridelink.core.logging.StructuredLogger
import com.ridelink.core.model.DiscoveredPeer
import com.ridelink.core.sessionfsm.Effect
import com.ridelink.core.sessionfsm.FsmResult
import com.ridelink.core.sessionfsm.FsmState
import com.ridelink.core.sessionfsm.SessionEvent
import com.ridelink.core.sessionfsm.SessionFsm
import com.ridelink.network.discovery.NsdDiscoveryController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The single owner of session state (CLAUDE.md rule 8 / ARCHITECTURE §3 rule 4). No view model
 * holds connection state of its own; every screen observes [state] and [discoveredPeers] here.
 *
 * Phase 1a scope only: discovery in, nothing else wired yet (no pairing, no control channel).
 */
class SessionCoordinator(
    private val discovery: NsdDiscoveryController,
    private val scope: CoroutineScope,
    logSink: LogSink,
    monotonicNowUs: () -> Long,
) {
    private val logger = StructuredLogger(logSink, monotonicNowUs)

    private val _state = MutableStateFlow(FsmState.INITIAL)
    val state: StateFlow<FsmState> = _state.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    fun startDiscovery() {
        if (!applyEvent(SessionEvent.StartDiscovery)) return

        scope.launch {
            discovery.browse().collect { peer ->
                _discoveredPeers.value = (_discoveredPeers.value.filterNot { it.discoveryHandle == peer.discoveryHandle }) + peer
            }
        }
    }

    fun cancelDiscovery() {
        applyEvent(SessionEvent.CancelDiscovery)
        _discoveredPeers.value = emptyList()
    }

    /** @return true if the event produced a real transition, false if it was rejected or ignored. */
    private fun applyEvent(event: SessionEvent): Boolean {
        val current = _state.value
        return when (val result = SessionFsm.transition(current, event)) {
            is FsmResult.Transitioned -> {
                _state.value = result.newState
                result.effects.forEach(::runEffect)
                true
            }
            is FsmResult.Rejected -> {
                logger.warn("SessionCoordinator", "rejected $event from ${current.status}")
                false
            }
            is FsmResult.Ignored -> {
                logger.debug("SessionCoordinator", "ignored $event from ${current.status}: ${result.reason}")
                false
            }
        }
    }

    private fun runEffect(effect: Effect) {
        when (effect) {
            is Effect.LogTransition ->
                logger.info("SessionCoordinator", "${effect.from.status} -> ${effect.to.status} (${effect.trigger})")
            is Effect.ReleaseAudioAndStopForegroundService ->
                logger.info("SessionCoordinator", "release audio + stop foreground service (not yet implemented, Phase 1a)")
        }
    }
}
