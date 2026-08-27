package com.ridelink.network.control

import com.ridelink.core.sessionfsm.FsmResult
import com.ridelink.core.sessionfsm.FsmState
import com.ridelink.core.sessionfsm.SessionEvent
import com.ridelink.core.sessionfsm.SessionFsm
import com.ridelink.core.sessionfsm.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * One phone, as the app actually wires it: a real [ControlSessionManager] over a real TLS channel,
 * with its [ControlEvent]s driven through the real [SessionGate] into the real
 * [com.ridelink.core.sessionfsm.SessionFsm].
 *
 * The five lines of [onControlEvent] are the entirety of what `SessionCoordinator` does with a
 * control event's *state* half — side effects (persisting `last_seen_at`, raising a security
 * alert) are the coordinator's and are not what these tests are about. Driving the FSM here rather
 * than in the `app` module is what makes it possible at all: `ControlSocket` is `internal` to
 * `network`, so the real transport can only be stood up from inside this module.
 */
class FsmSession(
    val peer: TestPeer,
    val manager: ControlSessionManager,
) {
    private val lock = Any()
    private val recorded = mutableListOf<ControlEvent>()
    private val visited = mutableListOf<SessionStatus>(SessionStatus.IDLE)

    @Volatile
    var state: FsmState = FsmState.INITIAL
        private set

    val status: SessionStatus get() = state.status
    val events: List<ControlEvent> get() = synchronized(lock) { recorded.toList() }

    /** Every status this session has ever been in, in order. Absence proofs are made against this. */
    val visitedStatuses: List<SessionStatus> get() = synchronized(lock) { visited.toList() }

    val trustStore get() = peer.trustedPeers

    fun apply(event: SessionEvent): Boolean =
        synchronized(lock) {
            when (val result = SessionFsm.transition(state, event)) {
                is FsmResult.Transitioned -> {
                    state = result.newState
                    visited.add(result.newState.status)
                    true
                }
                is FsmResult.Rejected, is FsmResult.Ignored -> false
            }
        }

    private fun onControlEvent(event: ControlEvent) {
        synchronized(lock) { recorded.add(event) }
        SessionGate.sessionEventFor(event, status)?.let { apply(it) }
    }

    fun collectInto(scope: CoroutineScope) {
        scope.launch { manager.events.collect(::onControlEvent) }
    }

    fun countOf(predicate: (ControlEvent) -> Boolean): Int = events.count(predicate)

    fun hasReached(target: SessionStatus): Boolean = visitedStatuses.contains(target)

    suspend fun awaitPairingPrompt(): PairingPrompt = withTimeout(TIMEOUT_MS) { manager.pairingPrompt.first { it != null }!! }

    suspend fun awaitStatus(target: SessionStatus) {
        withTimeout(TIMEOUT_MS) {
            while (status != target) delay(POLL_MS)
        }
    }

    suspend fun awaitEvent(predicate: (ControlEvent) -> Boolean) {
        withTimeout(TIMEOUT_MS) {
            while (events.none(predicate)) delay(POLL_MS)
        }
    }

    companion object {
        const val TIMEOUT_MS = 15_000L
        private const val POLL_MS = 10L

        /**
         * Long enough for a `PAIR_CONFIRM` to cross loopback and be acted on, so that "still
         * `PAIRING`" afterwards is a real absence rather than a race the assertion won.
         */
        const val SETTLE_MS = 500L
    }
}

/**
 * Wraps a [ControlChannel] and counts how many sockets it opens.
 *
 * It is how "pairing completing must not open a second TLS connection" is asserted as a fact
 * rather than as a code reading: the six digits are bound to *one* exporter (PROTOCOL §4.5.1), so
 * a second handshake after confirmation would silently mean the users approved a session that is
 * no longer the one in use.
 */
class CountingControlChannel(
    private val delegate: ControlChannel,
) : ControlChannel {
    @Volatile
    var dials: Int = 0
        private set

    override val transportLabel: String get() = delegate.transportLabel
    override val isSecure: Boolean get() = delegate.isSecure

    override suspend fun bind(): ControlListener = delegate.bind()

    override suspend fun connect(
        host: String,
        port: Int,
    ): ControlSocket {
        dials += 1
        return delegate.connect(host, port)
    }
}
