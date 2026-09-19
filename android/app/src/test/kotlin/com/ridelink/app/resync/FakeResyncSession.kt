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

    /**
     * Independent-review Blocker 1's regression seam: parks the send **inside** the write, so a
     * test can land a session boundary strictly between the upstream `stillCurrent` proof and the
     * actual generation-bound write — the same shape `FakeSyncSession.sendGate` already models for
     * Phase 5's own frames.
     */
    var sendGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** The authentication generation live at the instant each frame was actually written. */
    val sentGenerations = mutableListOf<Long>()

    /** See `FakeSyncSession.combinedWireLog` — same purpose, the resync-channel half of it. */
    var combinedWireLog: MutableList<Any>? = null

    private var forward: FakeResyncSession? = null

    override val resync: ResyncChannelPort =
        object : ResyncChannelPort {
            override var sink: ResyncSink? = null

            /**
             * `ResyncRelay.send`, modelled exactly: park where the socket would, then refuse the
             * frame unless the generation that authorised it is still the live one
             * (independent-review Blocker 1). A real relay resolves the writer from the one
             * immutable `AuthenticatedConnection` record for that generation; refusing here is the
             * same guarantee expressed the way a fake can.
             */
            @Suppress("ReturnCount") // the gate, the scripted result and the generation check
            override suspend fun send(
                message: ResyncMessage,
                generation: Long,
            ): Boolean {
                sendGate?.await()
                if (!sendResult) return false
                if (generation != currentAuthGeneration) return false
                sent.add(message)
                sentGenerations.add(currentAuthGeneration)
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
