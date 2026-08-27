package com.ridelink.network.control

import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.model.SpkiHash
import com.ridelink.core.security.TrustedPeer
import com.ridelink.core.sessionfsm.SessionEvent
import com.ridelink.core.sessionfsm.SessionStatus
import com.ridelink.network.testutil.Vectors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.ridelink.core.sessionfsm.LinkLossReason as FsmLinkLossReason

/**
 * Runs `protocol/vectors/session-gate/gate_vectors.json` — the complete 120-row trust-gate table —
 * against [SessionGate].
 *
 * The mirror is `RideLinkPlatformTests.SessionGateVectorTests`, running the **same file**. A gate
 * that read `Connected` as implicit pairing success on one platform and not the other would
 * otherwise be invisible until two phones met on a ride (ADR-019, CLAUDE.md "Shared protocol
 * vectors — not optional").
 */
class SessionGateVectorTest {
    private val rows = Vectors.load("session-gate/gate_vectors.json").jsonObject["rows"]!!

    @Test
    fun `every row of the shared trust-gate table holds`() {
        var checked = 0
        for (element in rows.jsonArray) {
            val row = element.jsonObject
            val name = row.string("name")
            val actual =
                SessionGate.sessionEventFor(
                    controlEvent(row["control_event"]!!.jsonObject),
                    SessionStatus.valueOf(row.string("status")),
                )
            assertEquals(expected(row), actual, "vector $name")
            checked += 1
        }
        assertEquals(120, checked, "the table is the complete cross-product; a shrunken file is a bug")
    }

    @Test
    fun `no row lets Connected mean pairing succeeded`() {
        // The Phase 1b security bug, stated as a property of the shared table rather than of one
        // platform's code.
        val offending =
            rows.jsonArray.map { it.jsonObject }.filter { row ->
                row["control_event"]!!.jsonObject.string("kind") == "Connected" &&
                    (row["session_event"] as? JsonObject)?.string("kind") == "PairingSucceeded"
            }
        assertTrue(offending.isEmpty(), "the vectors themselves must not permit it: $offending")
    }

    private fun expected(row: JsonObject): SessionEvent? = (row["session_event"] as? JsonObject)?.let { sessionEvent(it) }

    private fun controlEvent(spec: JsonObject): ControlEvent =
        when (val kind = spec.string("kind")) {
            "Connected" -> ControlEvent.Connected(REMOTE, SessionId("s"), isLocalLeader = true)
            "PeerTrusted" -> ControlEvent.PeerTrusted(REMOTE)
            "PairingRequired" -> ControlEvent.PairingRequired(REMOTE)
            "PairingSucceeded" -> ControlEvent.PairingSucceeded(PEER)
            "PairingFailed" -> ControlEvent.PairingFailed(ERROR_CODE_PAIRING_REJECTED)
            "HandshakeRefused" -> ControlEvent.HandshakeRefused(ERROR_CODE_PIN_MISMATCH)
            "LinkLost" -> ControlEvent.LinkLost(LinkLossReason.valueOf(spec.string("reason")))
            "DuplicateConnectionClosed" -> ControlEvent.DuplicateConnectionClosed
            "ReconnectBudgetExhausted" -> ControlEvent.ReconnectBudgetExhausted
            else -> error("unknown control event in vectors: $kind")
        }

    private fun sessionEvent(spec: JsonObject): SessionEvent =
        when (val kind = spec.string("kind")) {
            "ConnectionEstablished" -> SessionEvent.ConnectionEstablished
            "ConnectionFailed" -> SessionEvent.ConnectionFailed
            "ReconnectSucceeded" -> SessionEvent.ReconnectSucceeded
            "ReconnectBudgetExhausted" -> SessionEvent.ReconnectBudgetExhausted
            "PairingSucceeded" -> SessionEvent.PairingSucceeded
            "PairingRejectedOrTimeout" -> SessionEvent.PairingRejectedOrTimeout
            "DuplicateConnectionClosed" -> SessionEvent.DuplicateConnectionClosed
            "LinkLost" -> SessionEvent.LinkLost(FsmLinkLossReason.valueOf(spec.string("reason")))
            else -> error("unknown session event in vectors: $kind")
        }

    private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content

    private companion object {
        val REMOTE = PeerId("bbbbbbbbbbbbbbbb")
        val PEER =
            TrustedPeer(
                peerId = REMOTE,
                identitySpkiSha256 = SpkiHash("sha256:" + "ab".repeat(32)),
                displayName = "B",
                pairedAtEpochSeconds = 1,
                lastSeenAtEpochSeconds = 1,
            )
    }
}
