package com.ridelink.network.transfer

import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.protocol.TransferCodec
import com.ridelink.core.protocol.TransferMessage
import com.ridelink.core.protocol.TransferMessageRejection
import com.ridelink.network.control.ControlMessages
import com.ridelink.network.voice.AuthenticatedFrameWriter
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The `TRANSFER_*` half of the control plane (PROTOCOL §8.2): decode inbound frames, encode
 * outbound ones, and count what was refused. Mirrors
 * [com.ridelink.network.voice.VoiceSignalRelay] / [com.ridelink.network.manifest.ManifestRelay]
 * exactly.
 *
 * Carries only the small `TRANSFER_*` **control** messages (request/offer/progress/result/cancel)
 * — the bulk byte stream itself is [BulkTransportClient]/[BulkTransportServer]'s job, over a
 * separate TLS connection (ADR-023).
 */
class TransferRelay internal constructor(
    private val localPeerId: PeerId,
    private val monotonicNowUs: () -> Long,
    private val nextSeq: () -> Long,
    private val activeSessionId: () -> SessionId,
    private val authenticatedWriter: () -> AuthenticatedFrameWriter?,
) {
    @Volatile
    var sink: TransferSink? = null

    private val rejections = ConcurrentHashMap<TransferMessageRejection, Int>()

    @Volatile
    var droppedPreAuthentication: Int = 0
        private set

    val rejectionCounts: Map<TransferMessageRejection, Int> get() = rejections.toMap()

    suspend fun send(message: TransferMessage): Boolean {
        val write = authenticatedWriter() ?: return false
        return runCatching {
            write.write(
                ControlMessages.raw(
                    localPeerId = localPeerId,
                    type = TransferCodec.wireType(message),
                    sessionId = activeSessionId(),
                    seq = nextSeq(),
                    sentAtMonoUs = monotonicNowUs(),
                    payload = TransferCodec.encode(message),
                ),
            )
        }.isSuccess
    }

    /** Called only from the read loop's authenticated dispatch. A malformed frame is dropped, never fatal. */
    fun deliver(
        type: String,
        payload: JsonObject,
    ) {
        when (val result = TransferCodec.parse(type, payload)) {
            is TransferCodec.Result.Parsed -> sink?.submit(result.message)
            is TransferCodec.Result.Rejected -> rejections.merge(result.reason, 1) { a, b -> a + b }
        }
    }

    /** A `TRANSFER_*` frame arrived before the trust gate passed. Counted, per the same reasoning as [ManifestRelay]. */
    fun countPreAuthenticationDrop() {
        droppedPreAuthentication += 1
    }

    fun reset() {
        sink = null
        rejections.clear()
        droppedPreAuthentication = 0
    }
}

/** Receives parsed, bounds-checked `TRANSFER_*` messages. Implemented by whatever owns transfer state (`TransferCoordinator`). */
fun interface TransferSink {
    fun submit(message: TransferMessage)
}
