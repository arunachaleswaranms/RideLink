package com.ridelink.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The fixed control-frame envelope (PROTOCOL §2). Field names mirror the wire exactly via
 * [SerialName] so the Kotlin property names can stay idiomatic camelCase.
 */
@Serializable
data class Envelope(
    val v: Int,
    val type: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("sender_id") val senderId: String,
    @SerialName("msg_id") val msgId: String,
    val seq: Long,
    @SerialName("sent_at_mono_us") val sentAtMonoUs: Long,
    @SerialName("requires_ack") val requiresAck: Boolean = false,
    val payload: JsonObject,
)
