package com.ridelink.app.resync

import com.ridelink.core.resync.ResyncMessage
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.resync.ResyncSink
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The [ResyncSessionPort] twin of `com.ridelink.app.sync.FakeSyncSession` — same "forward to the
 * other peer, refuse unless the authorising generation is still live" shape, applied to
 * `STATE_REQUEST`/`STATE_SNAPSHOT` instead of Phase 5's frames.
 */
class FakeResyncSession : ResyncSessionPort {
    val sent = mutableListOf<ResyncMessage>()

    var sendResult: Boolean = true

    /** See `FakeSyncSession.combinedWireLog` — same purpose, the resync-channel half of it. */
    var combinedWireLog: MutableList<Any>? = null

    private var forward: FakeResyncSession? = null

    override val resync: ResyncChannelPort =
        object : ResyncChannelPort {
            override var sink: ResyncSink? = null

            override suspend fun send(message: ResyncMessage): Boolean {
                if (!sendResult) return false
                sent.add(message)
                combinedWireLog?.add(message)
                forward?.deliver(message, currentAuthGeneration)
                return true
            }
        }

    private val eventFlow = MutableSharedFlow<ControlEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<ControlEvent> = eventFlow.asSharedFlow()

    override var currentAuthGeneration: Long = 1

    /** `null` models "no live authenticated session right now" (ADR-025 §1). */
    override var liveAuthenticatedGeneration: Long? = 1

    suspend fun emit(event: ControlEvent) {
        eventFlow.emit(event)
    }

    fun forwardTo(other: FakeResyncSession) {
        forward = other
    }

    /** What the peer would have received, in order. */
    inline fun <reified T> sentOfType(): List<T> = sent.filterIsInstance<T>()

    fun deliver(
        message: ResyncMessage,
        generation: Long,
    ) = resync.sink?.submit(message, generation)
}
