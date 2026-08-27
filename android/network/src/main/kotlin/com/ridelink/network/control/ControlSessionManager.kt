package com.ridelink.network.control

import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.model.SpkiHash
import com.ridelink.core.security.PinDecision
import com.ridelink.core.security.TrustedPeer
import com.ridelink.core.security.TrustedPeerStore
import com.ridelink.core.sync.ClockSync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

enum class ControlState { IDLE, CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, ENDED }

/** FR-023 diagnostics surface. Never claims security it doesn't have. */
data class ControlDiagnostics(
    val transportLabel: String = "NOT CONNECTED",
    val controlState: ControlState = ControlState.IDLE,
    val remotePeerId: String? = null,
    val isLocalLeader: Boolean? = null,
    val rttMs: Double? = null,
    val clockOffsetUs: Long? = null,
    val clockJitterUs: Long? = null,
    val reconnectCount: Int = 0,
    /** First 6 hex of the peer's `identity_spki_sha256`, per the ARCHITECTURE §11 redaction rule. */
    val peerIdentityPrefix: String? = null,
    /** Negotiated TLS cipher suite, so the UI can show what actually protects the link. */
    val cipherSuite: String? = null,
)

sealed class ControlEvent {
    /**
     * **The surviving secure control connection has passed the RideLink trust gate and may be
     * treated as authenticated by the session FSM.**
     *
     * It does *not* mean "TLS and HELLO succeeded". A TLS socket to an unknown peer is a
     * transport, not an authenticated RideLink session: for such a peer this event is emitted only
     * once both users have confirmed the six digits and the pin has been written (PROTOCOL §4.5),
     * and for a peer whose stored pin matched, only after [PeerTrusted]. It is emitted from
     * exactly one place — [ControlSessionManager.activateAuthenticatedSession] — and never from
     * the handshake or from candidate promotion.
     */
    data class Connected(
        val remotePeerId: PeerId,
        val sessionId: SessionId,
        val isLocalLeader: Boolean,
    ) : ControlEvent()

    /**
     * The peer's presented SPKI matched the stored pin, so the trust gate passed with no user
     * action at all (PROTOCOL §4.1 "silent connect"). Raised only on the **surviving** connection
     * and always immediately before [Connected] — it is what carries `PAIRING -> CONNECTING` for a
     * known peer, now that [Connected] no longer doubles as implicit pairing success.
     */
    data class PeerTrusted(
        val remotePeerId: PeerId,
    ) : ControlEvent()

    data class LinkLost(
        val reason: LinkLossReason,
    ) : ControlEvent()

    object DuplicateConnectionClosed : ControlEvent()

    object ReconnectBudgetExhausted : ControlEvent()

    /**
     * The peer is unknown and PROTOCOL §4.5 pairing is required. Raised only on the **surviving**
     * connection (§4.2), so exactly one six-digit code is ever shown.
     */
    data class PairingRequired(
        val remotePeerId: PeerId,
    ) : ControlEvent()

    /** The handshake was refused. [code] is a PROTOCOL §4.6 code — `pin_mismatch` is the serious one. */
    data class HandshakeRefused(
        val code: String,
    ) : ControlEvent()

    /** Both users confirmed the six digits and the trusted-peer record is written (PROTOCOL §4.5). */
    data class PairingSucceeded(
        val peer: TrustedPeer,
    ) : ControlEvent()

    /** Pairing ended without a pin being written. [code] is a PROTOCOL §4.6 code. */
    data class PairingFailed(
        val code: String,
    ) : ControlEvent()
}

/**
 * What the pairing screen shows. [sas6] is the six digits the user compares with the other phone
 * — displayed and then discarded. It is never logged, never persisted and never sent
 * (PROTOCOL §4.5.1, ARCHITECTURE §11), so this object must not outlive the prompt.
 */
data class PairingPrompt(
    val sas6: String,
    val remotePeerId: PeerId,
    val peerDisplayName: String,
)

/**
 * Top-level control-plane orchestrator: binds the listener, accepts inbound and dials outbound
 * candidates, resolves duplicates ([DuplicateConnectionArbiter]), applies the SPKI pin decision,
 * runs the surviving connection's read loop, keepalive and clock-sync bursts ([ClockSync]), and
 * reconnect ([ReconnectController]). One instance per ride session attempt.
 *
 * It knows nothing about TLS. The [channel] it is given decides how bytes are protected, and the
 * only channel a shipped build can construct is
 * [com.ridelink.network.security.TlsControlChannel].
 */
class ControlSessionManager(
    private val scope: CoroutineScope,
    private val monotonicNowUs: () -> Long,
    private val localPeerId: PeerId,
    private val channel: ControlChannel,
    private val trustedPeers: TrustedPeerStore,
    /**
     * Wall clock, for the `paired_at`/`last_seen_at` fields of a trusted-peer record only. Those
     * are human-facing timestamps in a stored record, never used for scheduling, so CLAUDE.md's
     * monotonic-clocks rule does not apply — and a monotonic value would be meaningless across
     * reboots, which is exactly what a persisted record has to survive.
     */
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / MILLIS_PER_SECOND },
    random: Random = Random,
) {
    private val seqCounter = SeqCounter()
    private val arbiter = DuplicateConnectionArbiter(localPeerId, ConnTiebreakGenerator.generate())
    private val reconnectController =
        ReconnectController(scope, random) { ms -> delay(ms) }

    /**
     * Where the fire-and-forget socket writes below run (a courtesy `BYE`/`ERROR` before closing).
     * Framed reads and writes on a live socket already confine their own blocking I/O
     * ([ControlSocket]), so this is only for the paths that are not awaited.
     */
    private val ioDispatcher = Dispatchers.IO

    private var listener: ControlListener? = null
    private var acceptJob: Job? = null

    @Volatile
    private var activeSocket: ControlSocket? = null

    @Volatile
    private var activeSessionId: SessionId = SessionId("n/a")
    private var readLoopJob: Job? = null
    private var keepaliveJob: Job? = null
    private var clockSyncJob: Job? = null

    @Volatile
    private var lastPongAtMonoUs: Long = Long.MIN_VALUE

    @Volatile
    private var clockState: ClockSync.EstimatorState? = null

    @Volatile
    private var endedDeliberately = false

    /**
     * Distinct from [endedDeliberately] (which also becomes true after an ordinary BYE, where
     * this manager stays alive and should accept a future reconnect). [isShutDown] is only ever
     * true between [shutdown] and the next [startListening], and it is what guards [promote]
     * against a handshake/dedup resolution that was already in flight when [shutdown] was called
     * from completing *after* teardown (this session's brief §9/§10) — no per-candidate coroutine
     * is individually cancelled, so this is the backstop.
     */
    @Volatile
    private var isShutDown = false
    private val stateLock = Mutex()
    private val pendingPings = ConcurrentHashMap<Long, CompletableDeferred<ClockSync.Sample>>()

    private val _diagnostics = MutableStateFlow(ControlDiagnostics())
    val diagnostics: StateFlow<ControlDiagnostics> = _diagnostics.asStateFlow()

    private val _events = MutableSharedFlow<ControlEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ControlEvent> = _events.asSharedFlow()

    /**
     * Non-null exactly while a first-meeting pairing is awaiting the two users. Cleared as soon as
     * the exchange settles either way, so the six digits do not linger in memory or on screen
     * after they stop meaning anything.
     */
    private val _pairingPrompt = MutableStateFlow<PairingPrompt?>(null)
    val pairingPrompt: StateFlow<PairingPrompt?> = _pairingPrompt.asStateFlow()

    @Volatile
    private var pairing: PairingExchange? = null

    /**
     * The surviving connection's facts, held between promotion and the moment the trust gate lets
     * it become an authenticated session. Non-null exactly while a promoted connection is still
     * *unauthenticated* — which, for an unknown peer, is the whole of PROTOCOL §4.5 pairing.
     *
     * It exists so that pairing completing can activate **the socket that is already open**
     * ([activateAuthenticatedSession]) rather than dialling again: PROTOCOL §4.5 runs over the
     * same control connection, and a second TLS handshake would produce a second exporter and
     * therefore a code that was never the one the two users compared.
     */
    @Volatile
    private var pendingActivation: PendingActivation? = null

    /**
     * Whether the trust gate has passed on [activeSocket]. Read by [handleFrame] so that a peer
     * which has completed TLS but not RideLink authentication cannot invoke anything reserved for
     * an authenticated session. Transport alive != session authenticated.
     */
    @Volatile
    private var authenticated = false

    private data class PendingActivation(
        val socket: ControlSocket,
        val remotePeerId: PeerId,
        val sessionId: SessionId,
        val isLocalLeader: Boolean,
    )

    /**
     * Captured from whichever call started this session's activity. Pairing needs the local
     * display name and `identity_spki_sha256` after the handshake has already finished, so they
     * cannot stay parameters of the handshake alone.
     */
    @Volatile
    private var localIdentity: LocalHandshakeIdentity? = null

    val reconnectCount: Int get() = reconnectController.reconnectCount

    /**
     * Binds the OS-selected dynamic port and starts accepting inbound candidates. This instance
     * is reused across sessions (`SessionCoordinator` constructs it once), so a fresh
     * [startListening] after a prior [shutdown] must un-latch [isShutDown] — otherwise [promote]
     * would keep refusing every connection this new session ever completes.
     */
    suspend fun startListening(local: LocalHandshakeIdentity): Int {
        isShutDown = false
        _diagnostics.update { it.copy(transportLabel = channel.transportLabel) }
        val bound = channel.bind()
        listener = bound
        acceptJob =
            scope.launch {
                while (true) {
                    val socket = acceptOrNull(bound) ?: return@launch
                    scope.launch { handleCandidate(socket, local) }
                }
            }
        return bound.localPort
    }

    @Suppress("SwallowedException") // the listener being closed is the expected way this loop ends
    private suspend fun acceptOrNull(listener: ControlListener): ControlSocket? =
        try {
            listener.accept()
        } catch (closed: IOException) {
            null
        }

    /**
     * Dials a discovered peer. Runs concurrently with [startListening]'s accept loop. May emit
     * [ControlEvent.LinkLost] on failure — used for the *top-level* (first) connection attempt
     * only. [ReconnectController] must never call this directly: it already owns its own retry
     * decision, and a failed attempt re-emitting `LinkLost` would let `SessionCoordinator` start
     * a second, nested reconnect loop on top of the one already running (this session's brief
     * §3). It calls [attemptConnection] instead.
     */
    fun connectTo(
        host: String,
        port: Int,
        local: LocalHandshakeIdentity,
    ) {
        scope.launch {
            if (!attemptConnection(host, port, local)) {
                _events.tryEmit(ControlEvent.LinkLost(LinkLossReason.NETWORK))
            }
        }
    }

    /**
     * Internal connection primitive: dials, runs the handshake and duplicate-connection
     * resolution to completion, and reports success/failure directly as a return value — **no
     * [ControlEvent] is emitted here**. This is what [ReconnectController]'s ladder calls
     * ([beginReconnect] below), so a failed attempt simply advances the same ladder rather than
     * triggering another `LinkLost` -> `beginReconnect` cycle.
     */
    private suspend fun attemptConnection(
        host: String,
        port: Int,
        local: LocalHandshakeIdentity,
    ): Boolean {
        val socket = connectOrNull(host, port) ?: return false
        handleCandidate(socket, local)
        return activeSocket != null
    }

    @Suppress("SwallowedException") // failure is reported as LinkLost(NETWORK) by the caller, not lost
    private suspend fun connectOrNull(
        host: String,
        port: Int,
    ): ControlSocket? =
        try {
            channel.connect(host, port)
        } catch (e: IOException) {
            null
        }

    @Suppress("ReturnCount")
    private suspend fun handleCandidate(
        socket: ControlSocket,
        local: LocalHandshakeIdentity,
    ) {
        localIdentity = local
        if (activeSocket != null) {
            rejectAsAlreadyActive(socket)
            return
        }

        val outcome =
            if (socket.isInitiator) {
                ControlHandshake.performAsInitiator(
                    socket,
                    localPeerId,
                    seqCounter,
                    monotonicNowUs,
                    local.copy(connTiebreak = arbiter.connTiebreak),
                    trustedPeers,
                )
            } else {
                ControlHandshake.performAsAcceptor(
                    socket,
                    localPeerId,
                    seqCounter,
                    monotonicNowUs,
                    local.copy(connTiebreak = arbiter.connTiebreak),
                    trustedPeers,
                )
            }

        // Neither branch below emits LinkLost directly: the caller (connectTo/attemptConnection)
        // determines success or failure from `activeSocket` once this function returns, which is
        // the single source of truth for both the top-level and the reconnect-driven path.
        when (outcome) {
            is HandshakeOutcome.Success -> resolveCandidate(DuplicateConnectionArbiter.Candidate(socket, outcome))
            is HandshakeOutcome.Rejected -> refuse(socket, outcome.errorCode)
            HandshakeOutcome.ConnectionClosed -> socket.close()
        }
    }

    private suspend fun resolveCandidate(candidate: DuplicateConnectionArbiter.Candidate) {
        when (val result = arbiter.register(candidate)) {
            is DuplicateConnectionArbiter.Result.AwaitingRival -> {
                delay(DuplicateConnectionArbiter.GRACE_PERIOD_MS)
                arbiter.finalizeIfStillLone(result.candidate)?.let { promote(it) }
            }
            is DuplicateConnectionArbiter.Result.Survivor -> {
                result.loser?.let { closeLoser(it) }
                promote(result.winner)
            }
            is DuplicateConnectionArbiter.Result.TieRetry -> {
                candidate.socket.close()
            }
        }
    }

    /**
     * Tells the peer why before closing, then surfaces it. `pin_mismatch` in particular must
     * reach the user as a security warning rather than vanishing into a reconnect loop (ADR-012),
     * which is why this emits an event instead of only closing the socket.
     */
    private suspend fun refuse(
        socket: ControlSocket,
        code: String,
    ) {
        runCatching {
            socket.writeFrame(
                ControlMessages.error(
                    localPeerId = localPeerId,
                    sessionId = SessionId("n/a"),
                    seq = seqCounter.nextSeq(),
                    sentAtMonoUs = monotonicNowUs(),
                    code = code,
                    message = "handshake refused",
                    fatal = true,
                ),
            )
        }
        socket.close()
        _events.tryEmit(ControlEvent.HandshakeRefused(code))
    }

    private fun rejectAsAlreadyActive(socket: ControlSocket) {
        scope.launch(ioDispatcher) {
            runCatching {
                socket.writeFrame(
                    ControlMessages.error(
                        localPeerId = localPeerId,
                        sessionId = SessionId("n/a"),
                        seq = 1,
                        sentAtMonoUs = monotonicNowUs(),
                        code = ERROR_CODE_SESSION_ALREADY_ACTIVE,
                        message = "a control session is already active",
                        fatal = true,
                    ),
                )
            }
            socket.close()
        }
    }

    private fun closeLoser(candidate: DuplicateConnectionArbiter.Candidate) {
        scope.launch(ioDispatcher) {
            runCatching {
                candidate.socket.writeFrame(
                    ControlMessages.bye(
                        localPeerId,
                        candidate.outcome.sessionId,
                        seqCounter.nextSeq(),
                        monotonicNowUs(),
                        BYE_REASON_DUPLICATE_CONNECTION,
                    ),
                )
            }
            candidate.socket.close()
        }
        // ARCHITECTURE §3 rule 6: not a fault, not a state transition, must not touch reconnect_count.
        _events.tryEmit(ControlEvent.DuplicateConnectionClosed)
    }

    @Suppress("ReturnCount") // one early-out per reason this candidate must not become the session
    private suspend fun promote(candidate: DuplicateConnectionArbiter.Candidate) {
        if (isShutDown) {
            candidate.socket.close()
            return
        }
        stateLock.withLock {
            if (activeSocket != null) {
                closeLoser(candidate)
                return
            }
            activeSocket = candidate.socket
            activeSessionId = candidate.outcome.sessionId
            endedDeliberately = false
        }
        clockState = null
        authenticated = false
        lastPongAtMonoUs = monotonicNowUs()
        reconnectController.reset()

        val isLeader = candidate.outcome.leaderPeerId == localPeerId
        pendingActivation =
            PendingActivation(candidate.socket, candidate.outcome.remotePeerId, candidate.outcome.sessionId, isLeader)
        _diagnostics.update {
            it.copy(
                transportLabel = channel.transportLabel,
                // Deliberately CONNECTING, not CONNECTED: the socket is up and the peer is
                // identified, but nothing has authenticated it yet. Only
                // [activateAuthenticatedSession] may claim CONNECTED.
                controlState = ControlState.CONNECTING,
                remotePeerId = candidate.outcome.remotePeerId.toString(),
                isLocalLeader = isLeader,
                peerIdentityPrefix = candidate.outcome.peerIdentitySpkiSha256.toString(),
                cipherSuite = candidate.socket.security?.negotiatedCipherSuite,
            )
        }

        // PROTOCOL §4.5: pairing runs only on the surviving connection, so this is decided here —
        // after duplicate resolution — and never in the handshake itself. That is what guarantees
        // exactly one six-digit code is ever displayed, even on a simultaneous first meeting.
        //
        // The exchange is armed *before* the read loop starts, so a PAIR_REQUEST that arrives
        // immediately cannot be dropped for want of an exchange to hand it to.
        val pairingRequired = candidate.outcome.pinDecision is PinDecision.PairingRequired
        if (pairingRequired) {
            beginPairing(candidate)
            // beginPairing fails closed when the exporter is unavailable, and that closes the
            // socket. There is nothing left to run loops over.
            if (activeSocket !== candidate.socket) return
        }

        // The read loop and keepalive belong to the **transport**: PROTOCOL §4.5's pairing frames
        // arrive on this same connection, and a link that dies mid-pairing still has to be
        // noticed. Everything that presumes an authenticated peer — the ARCHITECTURE §7.1 clock
        // burst — waits for the trust gate instead.
        readLoopJob = scope.launch { readLoop(candidate.socket, candidate.outcome.sessionId) }
        keepaliveJob = scope.launch { keepaliveLoop(candidate.socket) }

        if (!pairingRequired) {
            _events.tryEmit(ControlEvent.PeerTrusted(candidate.outcome.remotePeerId))
            activateAuthenticatedSession()
        }
    }

    /**
     * The one place a connection becomes an authenticated RideLink session, and therefore the one
     * place [ControlEvent.Connected] is emitted.
     *
     * Reached by exactly two routes: the stored SPKI pin matched ([ControlEvent.PeerTrusted]), or
     * both users confirmed the six digits and the pin was written ([ControlEvent.PairingSucceeded]).
     * There is no third route, and in particular a completed TLS handshake is not one.
     *
     * It activates the socket that is **already open** — no second dial, no second handshake, no
     * second exporter.
     */
    private fun activateAuthenticatedSession() {
        val pending = pendingActivation ?: return
        if (activeSocket !== pending.socket) return
        pendingActivation = null
        authenticated = true
        _diagnostics.update { it.copy(controlState = ControlState.CONNECTED) }
        _events.tryEmit(ControlEvent.Connected(pending.remotePeerId, pending.sessionId, pending.isLocalLeader))
        clockSyncJob = scope.launch { clockSyncLoop(pending.socket) }
    }

    /**
     * Starts PROTOCOL §4.5 pairing on the surviving connection. The six digits come from the TLS
     * exporter for **this** handshake (§4.5.1), which is what makes the comparison a real
     * channel-binding check rather than decoration.
     */
    private suspend fun beginPairing(candidate: DuplicateConnectionArbiter.Candidate) {
        val socket = candidate.socket
        val exchange =
            PairingExchange(
                remotePeerId = candidate.outcome.remotePeerId,
                peerIdentitySpkiSha256 = candidate.outcome.peerIdentitySpkiSha256,
                isInitiator = socket.isInitiator,
                trustedPeers = trustedPeers,
                nowEpochSeconds = nowEpochSeconds,
            )
        pairing = exchange
        when (val step = exchange.begin(socket.security?.deriveSas6())) {
            is PairingExchange.Step.Failed -> failPairing(socket, step.code)
            else -> {
                _pairingPrompt.value =
                    PairingPrompt(
                        sas6 = requireNotNull(exchange.sas6),
                        remotePeerId = candidate.outcome.remotePeerId,
                        peerDisplayName = exchange.peerDisplayName,
                    )
                _events.tryEmit(ControlEvent.PairingRequired(candidate.outcome.remotePeerId))
                if (socket.isInitiator) sendPairRequest(socket)
            }
        }
    }

    private suspend fun sendPairRequest(socket: ControlSocket) {
        runCatching {
            socket.writeFrame(
                ControlMessages.pairRequest(
                    localPeerId = localPeerId,
                    sessionId = activeSessionId,
                    seq = seqCounter.nextSeq(),
                    sentAtMonoUs = monotonicNowUs(),
                    displayName = localIdentity?.displayName.orEmpty(),
                    platform = localIdentity?.platform.orEmpty(),
                    identitySpkiSha256 = requireNotNull(localIdentity).identitySpkiSha256,
                ),
            )
        }
    }

    /**
     * The user's answer on **this** device. Both users must confirm before any pin is written —
     * one screen's "yes" is only half of the check PROTOCOL §4.5 describes.
     */
    fun confirmPairing(accepted: Boolean) {
        val exchange = pairing ?: return
        val socket = activeSocket ?: return
        scope.launch { applyPairingStep(socket, exchange, exchange.onLocalDecision(accepted)) }
    }

    private suspend fun applyPairingStep(
        socket: ControlSocket,
        exchange: PairingExchange,
        step: PairingExchange.Step,
    ) {
        when (step) {
            PairingExchange.Step.Wait -> Unit
            PairingExchange.Step.SendPairConfirm ->
                runCatching {
                    socket.writeFrame(
                        ControlMessages.pairConfirm(localPeerId, activeSessionId, seqCounter.nextSeq(), monotonicNowUs(), true),
                    )
                }
            PairingExchange.Step.SendPairResultAccepted -> {
                runCatching {
                    socket.writeFrame(
                        ControlMessages.pairResult(
                            localPeerId,
                            activeSessionId,
                            seqCounter.nextSeq(),
                            monotonicNowUs(),
                            accepted = true,
                            identitySpkiSha256 = requireNotNull(localIdentity).identitySpkiSha256,
                        ),
                    )
                }
                exchange.completedPeer()?.let { succeedPairing(it) }
            }
            is PairingExchange.Step.Succeeded -> succeedPairing(step.peer)
            is PairingExchange.Step.Failed -> failPairing(socket, step.code)
        }
    }

    /**
     * Both users confirmed and [PairingExchange] has written the pin exactly once. Only now does
     * the surviving connection become authenticated — `PairingSucceeded` first (which carries
     * `PAIRING -> CONNECTING`), then `Connected` (`CONNECTING -> CONNECTED`), over the same socket.
     */
    private fun succeedPairing(peer: TrustedPeer) {
        pairing = null
        _pairingPrompt.value = null // the six digits stop meaning anything the moment this settles
        _events.tryEmit(ControlEvent.PairingSucceeded(peer))
        activateAuthenticatedSession()
    }

    /**
     * PROTOCOL §4.5: "on failure, close the connection." A half-paired session is not a state worth
     * having — the peer is still untrusted, so nothing may be done over it, and no pin was written.
     *
     * The peer is told why, and then the connection is ended as a **deliberate** close
     * ([LinkLossReason.USER_ENDED]): a pairing someone refused must never come back as an automatic
     * reconnect that silently re-asks. The socket the exchange was running on is closed here, so
     * nothing is left half-open for the read loop to inherit.
     */
    private suspend fun failPairing(
        socket: ControlSocket,
        code: String,
    ) {
        pairing = null
        _pairingPrompt.value = null
        pendingActivation = null
        _events.tryEmit(ControlEvent.PairingFailed(code))
        runCatching {
            socket.writeFrame(
                ControlMessages.error(
                    localPeerId = localPeerId,
                    sessionId = activeSessionId,
                    seq = seqCounter.nextSeq(),
                    sentAtMonoUs = monotonicNowUs(),
                    code = code,
                    message = "pairing failed",
                    fatal = true,
                ),
            )
        }
        endConnection(socket, LinkLossReason.USER_ENDED)
    }

    private suspend fun readLoop(
        socket: ControlSocket,
        sessionId: SessionId,
    ) {
        while (true) {
            when (val result = socket.readFrame()) {
                is FrameReadResult.Frame -> handleFrame(socket, sessionId, result)
                is FrameReadResult.Malformed -> Unit // PROTOCOL §2: log and continue, framing itself is intact
                is FrameReadResult.FrameTooLarge -> {
                    runCatching {
                        socket.writeFrame(
                            ControlMessages.error(
                                localPeerId,
                                sessionId,
                                seqCounter.nextSeq(),
                                monotonicNowUs(),
                                ERROR_CODE_FRAME_TOO_LARGE,
                                "frame exceeds cap",
                                true,
                            ),
                        )
                    }
                    endConnection(socket, LinkLossReason.NETWORK)
                    return
                }
                FrameReadResult.ConnectionClosed -> {
                    if (!endedDeliberately) endConnection(socket, LinkLossReason.NETWORK)
                    return
                }
            }
        }
    }

    @Suppress("ReturnCount") // one early-out per malformed-field guard (this session's brief §7) reads clearer than nesting
    private suspend fun handleFrame(
        socket: ControlSocket,
        sessionId: SessionId,
        frame: FrameReadResult.Frame,
    ) {
        val payload = frame.envelope.payload
        // Until the trust gate has passed, the only frames acted on are the ones an
        // *unauthenticated* connection is defined to carry: PROTOCOL §4.5's pairing exchange,
        // §1's keepalive, and §4.6's two ways of ending. Anything reserved for an authenticated
        // peer is dropped the same way an unknown type is (PROTOCOL §2 rule 2) — a peer that has
        // completed TLS but not RideLink authentication must not be able to reach it, and
        // PING/PONG in particular can never mark authentication complete.
        if (!authenticated && frame.envelope.type !in PRE_AUTHENTICATION_FRAME_TYPES) return
        when (frame.envelope.type) {
            "PING" -> {
                // A malformed/missing field is dropped, not fatal: the framing was intact (this
                // frame decoded fine), only this message's PROTOCOL §6-specific shape is invalid.
                // Silently ignoring one bad PING costs nothing — the sender's own keepalive
                // timeout (PROTOCOL §1) is what actually detects a truly broken peer.
                val t1 = requiredLongField(payload, "t1_mono_us") ?: return
                val t2 = monotonicNowUs()
                val t3 = monotonicNowUs()
                socket.writeFrame(ControlMessages.pong(localPeerId, sessionId, seqCounter.nextSeq(), monotonicNowUs(), t1, t2, t3))
            }
            "PONG" -> {
                val t1 = requiredLongField(payload, "t1_mono_us") ?: return
                val t2 = requiredLongField(payload, "t2_mono_us") ?: return
                val t3 = requiredLongField(payload, "t3_mono_us") ?: return
                val t4 = monotonicNowUs()
                // t2/t3 are peer-controlled; an adversarial or badly broken peer could pick
                // values that overflow the rtt/offset arithmetic. Kotlin Long subtraction wraps
                // silently on overflow rather than throwing, so a naive computation could turn a
                // garbage sample into a plausible-looking (wrong) clock offset that then corrupts
                // synchronised playback. Reject it here, before ClockSync ever sees it, the same
                // way a malformed field is dropped above (this session's brief §11) — the shared
                // estimator's own algorithm and vectors are untouched.
                if (!isPlausibleClockSample(t1, t2, t3, t4)) return
                lastPongAtMonoUs = t4
                pendingPings.remove(t1)?.complete(ClockSync.Sample(t1, t2, t3, t4))
                _diagnostics.update { it.copy(rttMs = ((t4 - t1) - (t3 - t2)) / MICROS_PER_MS) }
            }
            "PAIR_REQUEST", "PAIR_CONFIRM", "PAIR_RESULT" -> handlePairingFrame(socket, frame.envelope.type, payload)
            "BYE" -> endConnection(socket, LinkLossReason.BYE)
            "ERROR" -> {
                if (requiredBooleanField(payload, "fatal") != true) return
                // A fatal ERROR during PROTOCOL §4.5 is the other user saying no — the peer's own
                // reject path sends exactly this. Surfacing it as a pairing failure (rather than a
                // bare link loss) is what lets the local user be told *why* their code vanished.
                if (pairing != null) {
                    failPairing(socket, knownErrorCode(payload))
                } else {
                    endConnection(socket, LinkLossReason.BYE)
                }
            }
            else -> Unit // PROTOCOL §2 rule 2: unknown types ignored, logged, not fatal
        }
    }

    /**
     * Pairing frames arrive on the same read loop as everything else. Every field is peer-chosen,
     * so each is read through a non-throwing accessor and a malformed one ends the exchange rather
     * than the coroutine — the same rule the handshake follows.
     *
     * A pairing frame with no exchange in progress is dropped, not treated as an error: it is what
     * a duplicated or late frame looks like after the exchange has already settled.
     */
    @Suppress("ReturnCount")
    private suspend fun handlePairingFrame(
        socket: ControlSocket,
        type: String,
        payload: JsonObject,
    ) {
        val exchange = pairing ?: return
        val step =
            when (type) {
                "PAIR_REQUEST" -> {
                    val spki = requiredSpkiField(payload) ?: return failPairing(socket, ERROR_CODE_MALFORMED_FRAME)
                    val displayName = (payload["display_name"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                    exchange.onPairRequest(displayName, spki).also {
                        // The peer's name only becomes known here, and the prompt is already on
                        // screen by then — refresh it rather than showing a blank name.
                        _pairingPrompt.update { prompt -> prompt?.copy(peerDisplayName = exchange.peerDisplayName) }
                    }
                }
                "PAIR_CONFIRM" -> {
                    val accepted = requiredBooleanField(payload, "sas6_accepted") ?: return failPairing(socket, ERROR_CODE_MALFORMED_FRAME)
                    exchange.onPairConfirm(accepted)
                }
                else -> {
                    val accepted = requiredBooleanField(payload, "accepted") ?: return failPairing(socket, ERROR_CODE_MALFORMED_FRAME)
                    val spki = requiredSpkiField(payload) ?: return failPairing(socket, ERROR_CODE_MALFORMED_FRAME)
                    exchange.onPairResult(accepted, spki)
                }
            }
        applyPairingStep(socket, exchange, step)
    }

    private suspend fun endConnection(
        socket: ControlSocket,
        reason: LinkLossReason,
    ) {
        stateLock.withLock {
            if (activeSocket !== socket) return
            activeSocket = null
            endedDeliberately = reason != LinkLossReason.NETWORK
        }
        authenticated = false
        pendingActivation = null
        // A six-digit code belongs to one live TLS session (PROTOCOL §4.5.1). The moment that
        // session ends the code means nothing, so it is dropped here too rather than left on a
        // screen for a connection that no longer exists.
        pairing = null
        _pairingPrompt.value = null
        keepaliveJob?.cancel()
        clockSyncJob?.cancel()
        socket.close()
        failAllPendingPings()

        when (reason) {
            LinkLossReason.NETWORK -> {
                _diagnostics.update { it.copy(controlState = ControlState.RECONNECTING) }
                _events.tryEmit(ControlEvent.LinkLost(reason))
            }
            LinkLossReason.BYE -> {
                _diagnostics.update { it.copy(controlState = ControlState.ENDED) }
                _events.tryEmit(ControlEvent.LinkLost(reason))
            }
            LinkLossReason.DUPLICATE_CONNECTION, LinkLossReason.USER_ENDED -> Unit
        }
    }

    private suspend fun keepaliveLoop(socket: ControlSocket) {
        while (true) {
            delay(KEEPALIVE_INTERVAL_MS)
            if (monotonicNowUs() - lastPongAtMonoUs > KEEPALIVE_LOST_THRESHOLD_US) {
                endConnection(socket, LinkLossReason.NETWORK)
                return
            }
            runCatching { sendPingAndAwait(socket, timeoutMs = KEEPALIVE_INTERVAL_MS) }
        }
    }

    private suspend fun clockSyncLoop(socket: ControlSocket) {
        runClockBurst(socket) // ARCHITECTURE §7.1: 11 samples at CONNECTING
        while (true) {
            delay(CLOCK_RESYNC_INTERVAL_MS)
            runClockBurst(socket) // ... and every 10s thereafter
        }
    }

    private suspend fun runClockBurst(socket: ControlSocket) {
        val samples =
            (1..CLOCK_BURST_SAMPLE_COUNT).mapNotNull {
                val sample = runCatching { sendPingAndAwait(socket, timeoutMs = PING_TIMEOUT_MS) }.getOrNull()
                delay(CLOCK_BURST_SPACING_MS)
                sample
            }
        if (samples.isEmpty()) return
        val result = ClockSync.applyWindow(clockState, samples)
        clockState = result.newState
        _diagnostics.update {
            it.copy(
                clockOffsetUs = result.offsetUs ?: it.clockOffsetUs,
                clockJitterUs = result.jitterUs ?: it.clockJitterUs,
                rttMs = result.rttUs?.let { rtt -> rtt / MICROS_PER_MS } ?: it.rttMs,
            )
        }
    }

    /**
     * The waiter is registered before the frame is written, which is already correct here (the
     * write and the registration happen without an intervening suspension a `PONG` could race
     * through) — see this session's brief §2, which asked for this ordering to be reviewed
     * against the iOS race rather than assumed. What *was* missing: a write failure (not just a
     * timeout) left the entry in [pendingPings] forever. `catch (Throwable)` covers both, always
     * removes the entry, and always rethrows — never resumes-then-throws twice.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun sendPingAndAwait(
        socket: ControlSocket,
        timeoutMs: Long,
    ): ClockSync.Sample {
        val t1 = monotonicNowUs()
        val deferred = CompletableDeferred<ClockSync.Sample>()
        pendingPings[t1] = deferred
        try {
            socket.writeFrame(ControlMessages.ping(localPeerId, activeSessionId, seqCounter.nextSeq(), t1, t1))
            return withTimeout(timeoutMs) { deferred.await() }
        } catch (t: Throwable) {
            pendingPings.remove(t1)
            throw t
        }
    }

    /**
     * Teardown must fail every outstanding waiter rather than leave it to time out on a socket
     * that is already dead (this session's brief §10). `pendingPings.keys.toList()` (the first
     * version of this) could throw `NoSuchElementException` from `ConcurrentHashMap`'s iterator:
     * a keepalive/clock-sync coroutine can still be mid-`sendPingAndAwait`, inserting a new
     * entry, in the window between `keepaliveJob?.cancel()`/`clockSyncJob?.cancel()` being
     * requested and those coroutines actually reaching a suspension point that observes the
     * cancellation. `ConcurrentHashMap`'s own bulk
     * [java.util.concurrent.ConcurrentHashMap.forEach] is the JDK-documented concurrent-safe way
     * to traverse under exactly this kind of racing structural modification.
     */
    private fun failAllPendingPings() {
        val toFail = mutableListOf<Pair<Long, CompletableDeferred<ClockSync.Sample>>>()
        pendingPings.forEach { key, value -> toFail.add(key to value) }
        for ((key, deferred) in toFail) {
            pendingPings.remove(key)
            deferred.completeExceptionally(IOException("connection ended"))
        }
    }

    /**
     * Starts [ReconnectController]'s ladder after a genuine network-caused link loss. Each
     * attempt calls [attemptConnection] (not [connectTo]) so a failed attempt reports directly
     * back to this same, already-running ladder instead of emitting an event that could start a
     * second one (this session's brief §3).
     */
    fun beginReconnect(
        local: LocalHandshakeIdentity,
        host: String,
        port: Int,
    ) {
        _diagnostics.update { it.copy(controlState = ControlState.RECONNECTING) }
        reconnectController.start(
            onAttempt = { attemptConnection(host, port, local) },
            onExhausted = {
                _diagnostics.update { it.copy(controlState = ControlState.DISCONNECTED) }
                _events.tryEmit(ControlEvent.ReconnectBudgetExhausted)
            },
        )
        _diagnostics.update { it.copy(reconnectCount = reconnectController.reconnectCount) }
    }

    suspend fun shutdown(reason: String = BYE_REASON_SHUTDOWN) {
        isShutDown = true
        pairing = null
        authenticated = false
        pendingActivation = null
        _pairingPrompt.value = null
        endedDeliberately = true
        reconnectController.cancel()
        acceptJob?.cancel()
        keepaliveJob?.cancel()
        clockSyncJob?.cancel()
        readLoopJob?.cancel()
        // Candidate sockets the arbiter is still holding (awaiting a rival, or mid grace-period)
        // are real open sockets — close them rather than leaving them to resolve on their own
        // after this manager is gone (this session's brief §9/§10).
        arbiter.drainAll().forEach { candidate -> candidate.socket.close() }
        activeSocket?.let { socket ->
            runCatching {
                socket.writeFrame(ControlMessages.bye(localPeerId, activeSessionId, seqCounter.nextSeq(), monotonicNowUs(), reason))
            }
            socket.close()
        }
        activeSocket = null
        listener?.close()
        failAllPendingPings()
        _diagnostics.update { ControlDiagnostics(controlState = ControlState.ENDED) }
    }

    companion object {
        /**
         * PROTOCOL §1/§4.5/§4.6: what a connection that has not yet passed the RideLink trust gate
         * is allowed to say. Deliberately a closed list, so a Phase 2 message type is inert before
         * authentication unless it is added here on purpose.
         */
        private val PRE_AUTHENTICATION_FRAME_TYPES =
            setOf("PING", "PONG", "PAIR_REQUEST", "PAIR_CONFIRM", "PAIR_RESULT", "BYE", "ERROR")

        private const val MILLIS_PER_SECOND = 1_000L

        const val KEEPALIVE_INTERVAL_MS = 2_000L // PROTOCOL §1
        const val KEEPALIVE_LOST_THRESHOLD_US = 6_000_000L // PROTOCOL §1
        const val PING_TIMEOUT_MS = 3_000L
        const val CLOCK_BURST_SAMPLE_COUNT = 11 // ARCHITECTURE §7.1
        const val CLOCK_BURST_SPACING_MS = 50L // ARCHITECTURE §7.1 "~50ms apart"
        const val CLOCK_RESYNC_INTERVAL_MS = 10_000L // ARCHITECTURE §7.1 "every 10s thereafter"
        private const val MICROS_PER_MS = 1_000.0
    }
}

// Wire-field readers and the peer-controlled-value guards below are deliberately *not* members of
// [ControlSessionManager]: none of them touch a session, they are pure functions of one JSON
// payload, and that class is already the single owner of quite enough (config/detekt/detekt.yml).

/** PROTOCOL §4.6's complete code list. Nothing outside it is ever shown to a user. */
private val PROTOCOL_ERROR_CODES =
    setOf(
        ERROR_CODE_VERSION_MISMATCH,
        ERROR_CODE_LEADER_MISMATCH,
        ERROR_CODE_UNTRUSTED_PEER,
        ERROR_CODE_PIN_MISMATCH,
        ERROR_CODE_IDENTITY_MISMATCH,
        ERROR_CODE_CERTIFICATE_INVALID,
        ERROR_CODE_SESSION_ALREADY_ACTIVE,
        ERROR_CODE_PAIRING_REJECTED,
        ERROR_CODE_PAIRING_RATE_LIMITED,
        ERROR_CODE_FRAME_TOO_LARGE,
        ERROR_CODE_MALFORMED_FRAME,
        ERROR_CODE_INTERNAL,
    )

/**
 * A peer-chosen `code` is only accepted if it is one of PROTOCOL §4.6's defined codes.
 * Anything else — a missing field, a wrong type, or free text — becomes `pairing_rejected`,
 * because this value reaches the user as a security message and a remote peer must not be able
 * to choose what that message says.
 */
private fun knownErrorCode(payload: JsonObject): String {
    val code = (payload["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return if (code != null && code in PROTOCOL_ERROR_CODES) code else ERROR_CODE_PAIRING_REJECTED
}

private fun requiredSpkiField(payload: JsonObject): SpkiHash? =
    (payload["identity_spki_sha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(SpkiHash::parse)

/**
 * Safe, non-throwing extraction for a required numeric wire field (this session's brief §7):
 * a valid PING/PONG requires the field to be **present**, a **JSON number** (never a quoted
 * string — PROTOCOL fields are typed, not stringly-typed) and **representable as a `Long`**.
 * `longOrNull` already returns `null` rather than throwing for a non-numeric or
 * out-of-range/overflowing literal, so this never lets a malformed frame kill the read loop.
 */
private fun requiredLongField(
    payload: JsonObject,
    key: String,
): Long? = (payload[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

private fun requiredBooleanField(
    payload: JsonObject,
    key: String,
): Boolean? = (payload[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

/**
 * Live-wire acceptance check for a raw `(t1,t2,t3,t4)` PONG sample, run **before** it is ever
 * turned into a [ClockSync.Sample] (this session's brief §11). `ClockSync`'s own algorithm
 * and shared vectors are untouched — this only rejects a sample whose arithmetic would
 * overflow, or whose resulting rtt is non-positive, using overflow-checked subtraction so a
 * peer-controlled `t2`/`t3` can never silently wrap into a plausible-looking wrong number.
 * `rtt <= 0` mirrors `ClockSync`'s own `rttUs > 0` outlier filter — this check exists so an
 * overflowing sample never reaches even that filter with a corrupted value.
 */
@Suppress("ReturnCount") // one early-out per overflow-checked step reads clearer than nesting
private fun isPlausibleClockSample(
    t1: Long,
    t2: Long,
    t3: Long,
    t4: Long,
): Boolean {
    val aDiff = runCatching { Math.subtractExact(t4, t1) }.getOrNull() ?: return false
    val bDiff = runCatching { Math.subtractExact(t3, t2) }.getOrNull() ?: return false
    val rtt = runCatching { Math.subtractExact(aDiff, bDiff) }.getOrNull() ?: return false
    return rtt > 0
}
