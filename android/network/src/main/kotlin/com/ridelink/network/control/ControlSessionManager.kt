package com.ridelink.network.control

import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
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

    val reconnectCount: Int get() = reconnectController.reconnectCount

    /**
     * Binds the OS-selected dynamic port and starts accepting inbound candidates. This instance
     * is reused across sessions (`SessionCoordinator` constructs it once), so a fresh
     * [startListening] after a prior [shutdown] must un-latch [isShutDown] — otherwise [promote]
     * would keep refusing every connection this new session ever completes.
     */
    suspend fun startListening(local: LocalHandshakeIdentity): Int {
        isShutDown = false
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

        // Neither branch below emits LinkLost directly: the caller (connectTo/attemptConnection)
        // determines success or failure from `activeSocket` once this function returns, which is
        // the single source of truth for both the top-level and the reconnect-driven path.
        when (outcome) {
            is HandshakeOutcome.Success -> resolveCandidate(DuplicateConnectionArbiter.Candidate(socket, outcome))
            is HandshakeOutcome.Rejected -> socket.close()
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

    @Suppress("ReturnCount") // one early-out per malformed-field guard (this session's brief §7) reads clearer than nesting
    private suspend fun handleFrame(
        socket: ControlSocket,
        sessionId: SessionId,
        frame: FrameReadResult.Frame,
    ) {
        val payload = frame.envelope.payload
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
            "BYE" -> endConnection(socket, LinkLossReason.BYE)
            "ERROR" -> {
                if (requiredBooleanField(payload, "fatal") == true) endConnection(socket, LinkLossReason.BYE)
            }
            else -> Unit // PROTOCOL §2 rule 2: unknown types ignored, logged, not fatal
        }
    }

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
        const val KEEPALIVE_INTERVAL_MS = 2_000L // PROTOCOL §1
        const val KEEPALIVE_LOST_THRESHOLD_US = 6_000_000L // PROTOCOL §1
        const val PING_TIMEOUT_MS = 3_000L
        const val CLOCK_BURST_SAMPLE_COUNT = 11 // ARCHITECTURE §7.1
        const val CLOCK_BURST_SPACING_MS = 50L // ARCHITECTURE §7.1 "~50ms apart"
        const val CLOCK_RESYNC_INTERVAL_MS = 10_000L // ARCHITECTURE §7.1 "every 10s thereafter"
        private const val MICROS_PER_MS = 1_000.0
    }
}
