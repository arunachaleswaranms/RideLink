package com.ridelink.app.session

import com.ridelink.core.logging.LogSink
import com.ridelink.core.logging.StructuredLogger
import com.ridelink.core.model.DiscoveredPeer
import com.ridelink.core.security.TrustedPeer
import com.ridelink.core.security.TrustedPeerStore
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
import com.ridelink.network.control.PairingPrompt
import com.ridelink.network.control.SessionGate
import com.ridelink.network.discovery.DiscoveryEvent
import com.ridelink.network.discovery.NsdDiscoveryController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.ridelink.network.control.LinkLossReason as ControlLinkLossReason

/**
 * The single owner of session state (CLAUDE.md rule 8 / ARCHITECTURE §3 rule 4). No view model
 * holds connection state of its own; every screen observes [state], [discoveredPeers] and
 * [controlDiagnostics] here.
 *
 * Phase 1b scope: discovery -> TLS 1.3 handshake -> SPKI pin check -> HELLO/dedup -> either a
 * silent trusted connect or PROTOCOL §4.5 pairing with a six-digit SAS -> `CONNECTED`.
 *
 * **Which FSM event a control event implies is not decided here.** That table is
 * [SessionGate] — pure, shared with iOS case for case, and exhausted by unit tests on both
 * platforms — because it is where the Phase 1b security property lives: `Connected` is never read
 * as implicit pairing success, so an unknown peer cannot reach `CONNECTED` before both users have
 * confirmed the six digits and the pin has been written. What stays here is ownership of the
 * state itself (CLAUDE.md rule 8) and the side effects a control event carries: persisting trust,
 * raising a security alert, starting a reconnect.
 */
class SessionCoordinator(
    private val discovery: NsdDiscoveryController,
    private val controlSessionManager: ControlSessionManager,
    private val localIdentity: LocalHandshakeIdentity,
    private val scope: CoroutineScope,
    logSink: LogSink,
    monotonicNowUs: () -> Long,
    private val trustedPeers: TrustedPeerStore,
    private val nowEpochSeconds: () -> Long,
) {
    private val logger = StructuredLogger(logSink, monotonicNowUs)

    private val _state = MutableStateFlow(FsmState.INITIAL)
    val state: StateFlow<FsmState> = _state.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    private val _discoveryCount = MutableStateFlow(0)
    val discoveryCount: StateFlow<Int> = _discoveryCount.asStateFlow()

    val controlDiagnostics: StateFlow<ControlDiagnostics> = controlSessionManager.diagnostics

    /** Non-null only while two users are being asked to compare six digits (PROTOCOL §4.5). */
    val pairingPrompt: StateFlow<PairingPrompt?> = controlSessionManager.pairingPrompt

    /** This device's own `identity_spki_sha256`, redacted to 6 hex for display (ARCHITECTURE §11). */
    val localIdentityPrefix: String = localIdentity.identitySpkiSha256.toString()

    private val _securityAlert = MutableStateFlow<String?>(null)

    /**
     * A refused handshake the user needs to see rather than a transient failure to retry.
     * `pin_mismatch` above all: ADR-012 requires it to surface as a security warning and never to
     * be auto-resolved by re-pairing.
     */
    val securityAlert: StateFlow<String?> = _securityAlert.asStateFlow()

    /** The user's answer on the pairing screen. Both peers must answer before any pin is written. */
    fun confirmPairing(accepted: Boolean) {
        controlSessionManager.confirmPairing(accepted)
    }

    fun forgetPeer(peer: TrustedPeer) {
        trustedPeers.forget(peer.peerId)
        _securityAlert.value = null
    }

    fun dismissSecurityAlert() {
        _securityAlert.value = null
    }

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
                    // The Bonjour/mDNS instance name is derived from the rotating discovery
                    // handle inside NsdDiscoveryController.advertise() itself — never the device
                    // model/name (this session's brief §6) — so no name is passed in here.
                    discovery.advertise(port).collect { advertiseState ->
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
     * ARCHITECTURE §4.1: a discovered peer cannot be labelled "known" before a connection exists,
     * because the mDNS TXT record deliberately carries nothing durable (ADR-002 Amendment A1). So
     * the first discovered peer is dialled, and whether that ends in a silent trusted connect or a
     * pairing prompt is decided *after* the TLS handshake, by the SPKI pin.
     *
     * `PairingSucceeded` is no longer applied here. The session now sits in `PAIRING` until the
     * pin check or the two users resolve it.
     */
    private fun maybeConnect(peer: DiscoveredPeer) {
        if (connectAttempted) return
        if (_state.value.status != SessionStatus.DISCOVERING) return
        connectAttempted = true
        lastPeerHost = peer.host
        lastPeerPort = peer.port
        applyEvent(SessionEvent.PeerSelected)
        controlSessionManager.connectTo(peer.host, peer.port, localIdentity)
    }

    /**
     * Side effects first, then the one transition [SessionGate] says this event implies. The order
     * matters for pairing: the trusted-peer record is written before the session is allowed to
     * leave `PAIRING`, so there is no instant in which the app is past the trust gate with nothing
     * persisted behind it.
     */
    private fun handleControlEvent(event: ControlEvent) {
        applySideEffects(event)
        SessionGate.sessionEventFor(event, _state.value.status)?.let { applyEvent(it) }
        // Only after the FSM has been moved: `beginReconnect` requires RECONNECTING, which is
        // exactly what the transition above establishes.
        if (event is ControlEvent.LinkLost && event.reason == ControlLinkLossReason.NETWORK) {
            beginReconnectIfPossible()
        }
    }

    private fun applySideEffects(event: ControlEvent) {
        when (event) {
            is ControlEvent.PeerTrusted ->
                // ARCHITECTURE §4.3's silent connect: the stored pin matched, so no code and no
                // prompt — but this, not `Connected`, is what passes the trust gate.
                logger.info("SessionCoordinator", "known peer ${event.remotePeerId}, silent connect")
            is ControlEvent.PairingRequired ->
                logger.info("SessionCoordinator", "pairing required with ${event.remotePeerId}")
            is ControlEvent.PairingSucceeded -> {
                // PairingExchange already wrote the pin, exactly once and only after both users
                // confirmed; this refreshes `last_seen_at` on that same record (TrustedPeerStore
                // refuses to replace a pin, so it can never become a second, different one).
                trustedPeers.remember(event.peer.copy(lastSeenAtEpochSeconds = nowEpochSeconds()))
                logger.info("SessionCoordinator", "paired with ${event.peer.peerId} (${event.peer.identitySpkiSha256})")
            }
            is ControlEvent.PairingFailed -> _securityAlert.value = event.code
            is ControlEvent.HandshakeRefused -> {
                // pin_mismatch is the one that must never be quietly retried (ADR-012): it means
                // the key behind a familiar peer_id changed, which is either a reinstall or an
                // attack, and only the user can tell those apart.
                _securityAlert.value = event.code
                logger.warn("SessionCoordinator", "handshake refused: ${event.code}")
            }
            is ControlEvent.Connected,
            is ControlEvent.LinkLost,
            ControlEvent.DuplicateConnectionClosed,
            ControlEvent.ReconnectBudgetExhausted,
            -> Unit
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
}
