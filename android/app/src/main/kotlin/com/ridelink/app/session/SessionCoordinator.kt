package com.ridelink.app.session

import com.ridelink.core.logging.LogSink
import com.ridelink.core.logging.StructuredLogger
import com.ridelink.core.model.DiscoveredPeer
import com.ridelink.core.sessionfsm.Effect
import com.ridelink.core.sessionfsm.FsmResult
import com.ridelink.core.sessionfsm.FsmState
import com.ridelink.core.sessionfsm.SessionEvent
import com.ridelink.core.sessionfsm.SessionFsm
import com.ridelink.core.sessionfsm.SessionStatus
import com.ridelink.network.control.ControlDiagnostics
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.control.ControlSessionManager
import com.ridelink.network.control.LocalHandshakeIdentity
import com.ridelink.network.discovery.DiscoveryEvent
import com.ridelink.network.discovery.NsdDiscoveryController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.ridelink.core.sessionfsm.LinkLossReason as FsmLinkLossReason
import com.ridelink.network.control.LinkLossReason as ControlLinkLossReason

/**
 * The single owner of session state (CLAUDE.md rule 8 / ARCHITECTURE §3 rule 4). No view model
 * holds connection state of its own; every screen observes [state], [discoveredPeers] and
 * [controlDiagnostics] here.
 *
 * Phase 1a scope: discovery -> [PlainControlTransportPhase1a][com.ridelink.network.control.PlainControlTransportPhase1a]
 * HELLO/dedup/PING-PONG -> `CONNECTED`. There is no pairing UI yet (CLAUDE.md rule 28 / this
 * session's brief §9): the first discovered peer is dialled automatically and `PairingSucceeded`
 * is applied immediately after `PeerSelected`, since Phase 1a's plaintext transport has no trust
 * to establish yet. That is a Phase 1a *simplification*, not a change to the FSM's legal
 * transitions — real pairing (SAS confirmation) arrives with Phase 1b.
 */
class SessionCoordinator(
    private val discovery: NsdDiscoveryController,
    private val controlSessionManager: ControlSessionManager,
    private val localIdentity: LocalHandshakeIdentity,
    private val scope: CoroutineScope,
    logSink: LogSink,
    monotonicNowUs: () -> Long,
) {
    private val logger = StructuredLogger(logSink, monotonicNowUs)

    private val _state = MutableStateFlow(FsmState.INITIAL)
    val state: StateFlow<FsmState> = _state.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    private val _discoveryCount = MutableStateFlow(0)
    val discoveryCount: StateFlow<Int> = _discoveryCount.asStateFlow()

    val controlDiagnostics: StateFlow<ControlDiagnostics> = controlSessionManager.diagnostics

    private var sessionJob: Job? = null
    private var connectAttempted = false
    private var lastPeerHost: String? = null
    private var lastPeerPort: Int? = null

    fun startDiscovery() {
        if (!applyEvent(SessionEvent.StartDiscovery)) return
        connectAttempted = false
        _discoveredPeers.value = emptyList()
        _discoveryCount.value = 0

        sessionJob =
            scope.launch {
                launch {
                    controlSessionManager.events.collect { event -> handleControlEvent(event) }
                }
                val port = controlSessionManager.startListening(localIdentity)
                launch {
                    discovery.advertise(deviceServiceName(), port).collect { advertiseState ->
                        logger.debug("SessionCoordinator", "advertise: $advertiseState")
                    }
                }
                launch {
                    discovery.browse().collect { event -> handleDiscoveryEvent(event) }
                }
            }
    }

    fun cancelDiscovery() {
        applyEvent(SessionEvent.CancelDiscovery)
        teardownSession()
        _discoveredPeers.value = emptyList()
    }

    private fun teardownSession() {
        sessionJob?.cancel()
        sessionJob = null
        scope.launch { controlSessionManager.shutdown() }
    }

    private fun handleDiscoveryEvent(event: DiscoveryEvent) {
        when (event) {
            is DiscoveryEvent.Found -> {
                _discoveredPeers.value = _discoveredPeers.value.filterNot { it.discoveryHandle == event.peer.discoveryHandle } + event.peer
                _discoveryCount.value += 1
                maybeConnect(event.peer)
            }
            is DiscoveryEvent.Updated -> {
                _discoveredPeers.value =
                    _discoveredPeers.value.map { if (it.discoveryHandle == event.peer.discoveryHandle) event.peer else it }
            }
            is DiscoveryEvent.Lost -> {
                _discoveredPeers.value = _discoveredPeers.value.filterNot { it.discoveryHandle == event.discoveryHandle }
            }
        }
    }

    /**
     * Phase 1a auto-connects to the first peer it discovers rather than waiting for a tap — there
     * is no pairing UI yet to tap *into* (CLAUDE.md rule 28). ARCHITECTURE §4.1's "exactly one
     * trusted peer -> silent auto-connect" UX is the eventual behaviour; Phase 1a has no trust
     * store yet, so this is that same idea applied to "exactly one peer discovered" instead.
     */
    private fun maybeConnect(peer: DiscoveredPeer) {
        if (connectAttempted) return
        if (_state.value.status != SessionStatus.DISCOVERING) return
        connectAttempted = true
        lastPeerHost = peer.host
        lastPeerPort = peer.port
        applyEvent(SessionEvent.PeerSelected)
        applyEvent(SessionEvent.PairingSucceeded)
        controlSessionManager.connectTo(peer.host, peer.port, localIdentity)
    }

    private fun handleControlEvent(event: ControlEvent) {
        when (event) {
            is ControlEvent.Connected -> {
                when (_state.value.status) {
                    SessionStatus.CONNECTING -> applyEvent(SessionEvent.ConnectionEstablished)
                    SessionStatus.RECONNECTING -> applyEvent(SessionEvent.ReconnectSucceeded)
                    else -> Unit
                }
            }
            is ControlEvent.LinkLost -> {
                when (event.reason) {
                    ControlLinkLossReason.NETWORK -> {
                        if (_state.value.status == SessionStatus.CONNECTING) {
                            applyEvent(SessionEvent.ConnectionFailed)
                        } else {
                            applyEvent(SessionEvent.LinkLost(FsmLinkLossReason.NETWORK))
                        }
                        beginReconnectIfPossible()
                    }
                    ControlLinkLossReason.BYE -> applyEvent(SessionEvent.LinkLost(FsmLinkLossReason.BYE))
                    ControlLinkLossReason.DUPLICATE_CONNECTION, ControlLinkLossReason.USER_ENDED -> Unit
                }
            }
            ControlEvent.DuplicateConnectionClosed -> applyEvent(SessionEvent.DuplicateConnectionClosed)
            ControlEvent.ReconnectBudgetExhausted -> applyEvent(SessionEvent.ReconnectBudgetExhausted)
        }
    }

    private fun beginReconnectIfPossible() {
        val host = lastPeerHost
        val port = lastPeerPort
        if (host == null || port == null || _state.value.status != SessionStatus.RECONNECTING) return
        controlSessionManager.beginReconnect(localIdentity, host, port)
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
            is Effect.ReleaseAudioAndStopForegroundService -> {
                logger.info("SessionCoordinator", "release audio + stop foreground service (not yet implemented, Phase 1a)")
                teardownSession()
            }
        }
    }

    private fun deviceServiceName(): String = "RideLink-${localIdentity.displayName}"
}
