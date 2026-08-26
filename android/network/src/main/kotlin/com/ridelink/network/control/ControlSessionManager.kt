package com.ridelink.network.control

import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.sync.ClockSync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

enum class ControlState { IDLE, CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, ENDED }

/** FR-023 diagnostics surface (this session's brief §17). Never claims security it doesn't have. */
data class ControlDiagnostics(
    val transportLabel: String = "PLAIN / PHASE 1A / NOT SECURE",
    val controlState: ControlState = ControlState.IDLE,
    val remotePeerId: String? = null,
    val isLocalLeader: Boolean? = null,
    val rttMs: Double? = null,
    val clockOffsetUs: Long? = null,
    val clockJitterUs: Long? = null,
    val reconnectCount: Int = 0,
)

sealed class ControlEvent {
    data class Connected(
        val remotePeerId: PeerId,
        val sessionId: SessionId,
        val isLocalLeader: Boolean,
    ) : ControlEvent()

    data class LinkLost(
        val reason: LinkLossReason,
    ) : ControlEvent()

    object DuplicateConnectionClosed : ControlEvent()

    object ReconnectBudgetExhausted : ControlEvent()
}

/**
 * Top-level Phase 1a control-plane orchestrator: binds the listener, accepts inbound and dials
 * outbound candidates, resolves duplicates ([DuplicateConnectionArbiter]), runs the surviving
 * connection's read loop, keepalive and clock-sync bursts ([ClockSync]), and reconnect
 * ([ReconnectController]). One instance per ride session attempt.
 *
 * **[PlainControlTransportPhase1a] — plaintext, debug/development builds only.**
 */
class ControlSessionManager(
    private val scope: CoroutineScope,
    private val monotonicNowUs: () -> Long,
    private val localPeerId: PeerId = ProvisionalIdentity.peerId,
    random: Random = Random,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    private val seqCounter = SeqCounter()
    private val arbiter = DuplicateConnectionArbiter(localPeerId, ConnTiebreakGenerator.generate())
    private val reconnectController =
        ReconnectController(scope, random) { ms -> delay(ms) }

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
    private val stateLock = Mutex()
    private val pendingPings = ConcurrentHashMap<Long, CompletableDeferred<ClockSync.Sample>>()

    private val _diagnostics = MutableStateFlow(ControlDiagnostics())
    val diagnostics: StateFlow<ControlDiagnostics> = _diagnostics.asStateFlow()

    private val _events = MutableSharedFlow<ControlEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ControlEvent> = _events.asSharedFlow()

    val reconnectCount: Int get() = reconnectController.reconnectCount

    /** Binds the OS-selected dynamic port and starts accepting inbound candidates. */
    suspend fun startListening(local: LocalHandshakeIdentity): Int {
        val bound = ControlListener.bind(ioDispatcher)
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

    /** Dials a discovered peer. Runs concurrently with [startListening]'s accept loop. */
    fun connectTo(
        host: String,
        port: Int,
        local: LocalHandshakeIdentity,
    ) {
        scope.launch {
            val socket = connectOrNull(host, port)
            if (socket == null) {
                _events.tryEmit(ControlEvent.LinkLost(LinkLossReason.NETWORK))
                return@launch
            }
            handleCandidate(socket, local)
        }
    }

    @Suppress("SwallowedException") // failure is reported as LinkLost(NETWORK) by the caller, not lost
    private suspend fun connectOrNull(
        host: String,
        port: Int,
    ): ControlSocket? =
        try {
            ControlSocket.connect(host, port, ioDispatcher)
        } catch (e: IOException) {
            null
        }

    @Suppress("ReturnCount")
    private suspend fun handleCandidate(
        socket: ControlSocket,
        local: LocalHandshakeIdentity,
    ) {
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
                )
            } else {
                ControlHandshake.performAsAcceptor(
                    socket,
                    localPeerId,
                    seqCounter,
                    monotonicNowUs,
                    local.copy(connTiebreak = arbiter.connTiebreak),
                )
            }

        when (outcome) {
            is HandshakeOutcome.Success -> resolveCandidate(DuplicateConnectionArbiter.Candidate(socket, outcome))
            is HandshakeOutcome.Rejected -> {
                socket.close()
                // Only an outbound attempt failing is *our* link failing; a stray/garbage inbound
                // connection failing its handshake must never trigger our own reconnect logic.
                if (socket.isInitiator) _events.tryEmit(ControlEvent.LinkLost(LinkLossReason.NETWORK))
            }
            HandshakeOutcome.ConnectionClosed -> {
                socket.close()
                if (socket.isInitiator) _events.tryEmit(ControlEvent.LinkLost(LinkLossReason.NETWORK))
            }
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

    private suspend fun promote(candidate: DuplicateConnectionArbiter.Candidate) {
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
        lastPongAtMonoUs = monotonicNowUs()
        reconnectController.reset()

        val isLeader = candidate.outcome.leaderPeerId == localPeerId
        _diagnostics.update {
            it.copy(
                controlState = ControlState.CONNECTED,
                remotePeerId = candidate.outcome.remotePeerId.toString(),
                isLocalLeader = isLeader,
            )
        }
        _events.tryEmit(ControlEvent.Connected(candidate.outcome.remotePeerId, candidate.outcome.sessionId, isLeader))

        readLoopJob = scope.launch { readLoop(candidate.socket, candidate.outcome.sessionId) }
        keepaliveJob = scope.launch { keepaliveLoop(candidate.socket) }
        clockSyncJob = scope.launch { clockSyncLoop(candidate.socket) }
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

    private suspend fun handleFrame(
        socket: ControlSocket,
        sessionId: SessionId,
        frame: FrameReadResult.Frame,
    ) {
        val payload = frame.envelope.payload
        when (frame.envelope.type) {
            "PING" -> {
                val t1 = longField(payload, "t1_mono_us")
                val t2 = monotonicNowUs()
                val t3 = monotonicNowUs()
                socket.writeFrame(ControlMessages.pong(localPeerId, sessionId, seqCounter.nextSeq(), monotonicNowUs(), t1, t2, t3))
            }
            "PONG" -> {
                val t1 = longField(payload, "t1_mono_us")
                val t2 = longField(payload, "t2_mono_us")
                val t3 = longField(payload, "t3_mono_us")
                val t4 = monotonicNowUs()
                lastPongAtMonoUs = t4
                pendingPings.remove(t1)?.complete(ClockSync.Sample(t1, t2, t3, t4))
                _diagnostics.update { it.copy(rttMs = ((t4 - t1) - (t3 - t2)) / MICROS_PER_MS) }
            }
            "BYE" -> endConnection(socket, LinkLossReason.BYE)
            "ERROR" -> {
                val fatal = payload["fatal"]?.jsonPrimitive?.boolean == true
                if (fatal) endConnection(socket, LinkLossReason.BYE)
            }
            else -> Unit // PROTOCOL §2 rule 2: unknown types ignored, logged, not fatal
        }
    }

    private fun longField(
        payload: JsonObject,
        key: String,
    ): Long = payload[key]!!.jsonPrimitive.long

    private suspend fun endConnection(
        socket: ControlSocket,
        reason: LinkLossReason,
    ) {
        stateLock.withLock {
            if (activeSocket !== socket) return
            activeSocket = null
            endedDeliberately = reason != LinkLossReason.NETWORK
        }
        keepaliveJob?.cancel()
        clockSyncJob?.cancel()
        socket.close()

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
        } catch (timeout: TimeoutCancellationException) {
            pendingPings.remove(t1)
            throw timeout
        }
    }

    /** Starts [ReconnectController]'s ladder after a genuine network-caused link loss. */
    fun beginReconnect(
        local: LocalHandshakeIdentity,
        host: String,
        port: Int,
    ) {
        _diagnostics.update { it.copy(controlState = ControlState.RECONNECTING) }
        reconnectController.start(
            onAttempt = {
                connectTo(host, port, local)
                delay(RECONNECT_ATTEMPT_SETTLE_MS)
                activeSocket != null
            },
            onExhausted = {
                _diagnostics.update { it.copy(controlState = ControlState.DISCONNECTED) }
                _events.tryEmit(ControlEvent.ReconnectBudgetExhausted)
            },
        )
        _diagnostics.update { it.copy(reconnectCount = reconnectController.reconnectCount) }
    }

    suspend fun shutdown(reason: String = BYE_REASON_SHUTDOWN) {
        endedDeliberately = true
        reconnectController.cancel()
        acceptJob?.cancel()
        keepaliveJob?.cancel()
        clockSyncJob?.cancel()
        readLoopJob?.cancel()
        activeSocket?.let { socket ->
            runCatching {
                socket.writeFrame(ControlMessages.bye(localPeerId, activeSessionId, seqCounter.nextSeq(), monotonicNowUs(), reason))
            }
            socket.close()
        }
        activeSocket = null
        listener?.close()
        _diagnostics.update { ControlDiagnostics(controlState = ControlState.ENDED) }
    }

    companion object {
        const val KEEPALIVE_INTERVAL_MS = 2_000L // PROTOCOL §1
        const val KEEPALIVE_LOST_THRESHOLD_US = 6_000_000L // PROTOCOL §1
        const val PING_TIMEOUT_MS = 3_000L
        const val CLOCK_BURST_SAMPLE_COUNT = 11 // ARCHITECTURE §7.1
        const val CLOCK_BURST_SPACING_MS = 50L // ARCHITECTURE §7.1 "~50ms apart"
        const val CLOCK_RESYNC_INTERVAL_MS = 10_000L // ARCHITECTURE §7.1 "every 10s thereafter"
        const val RECONNECT_ATTEMPT_SETTLE_MS = 1_000L
        private const val MICROS_PER_MS = 1_000.0
    }
}
