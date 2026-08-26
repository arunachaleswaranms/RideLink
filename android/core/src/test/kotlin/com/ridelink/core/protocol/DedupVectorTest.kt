package com.ridelink.core.protocol

import com.ridelink.core.model.ConnTiebreak
import com.ridelink.core.model.PeerId
import com.ridelink.core.testutil.Vectors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals

/**
 * protocol/vectors/dedup/dedup_vectors.json, PROTOCOL §4.2, ADR-015 (+ this session's Amendment
 * A2), ADR-010 (+ Amendment A2). Deliberately proves [Dedup] and [Leadership] are independent.
 */
class DedupVectorTest {
    private fun peerTiebreak(obj: JsonObject): Dedup.PeerTiebreak =
        Dedup.PeerTiebreak(
            PeerId(obj["peer_id"]!!.jsonPrimitive.content),
            ConnTiebreak(obj["conn_tiebreak"]!!.jsonPrimitive.content),
        )

    private fun sideOf(s: String): Dedup.Side = if (s == "a") Dedup.Side.A else Dedup.Side.B

    @TestFactory
    fun dedupVectors(): List<DynamicTest> {
        val root = Vectors.load("dedup/dedup_vectors.json").jsonObject
        return root["vectors"]!!.jsonArray.map { element ->
            val vector = element.jsonObject
            val name = vector["name"]!!.jsonPrimitive.content
            DynamicTest.dynamicTest(name) { runVector(name, vector) }
        }
    }

    private fun runVector(
        name: String,
        vector: JsonObject,
    ) {
        val input = vector["input"]!!.jsonObject
        val expected = vector["expected"]!!.jsonObject

        when (name) {
            "larger-tiebreak-outbound-survives", "smaller-tiebreak-outbound-loses" -> {
                val a = peerTiebreak(input["peer_a"]!!.jsonObject)
                val b = peerTiebreak(input["peer_b"]!!.jsonObject)
                val verdict = Dedup.resolve(a, b)
                check(verdict is Dedup.Verdict.Survivor)
                expected["survivor"]?.let { assertEquals(sideOf(it.jsonPrimitive.content), verdict.survivorPeer) }
                expected["surviving_connection_initiator"]?.let {
                    assertEquals(sideOf(it.jsonPrimitive.content), verdict.survivorPeer)
                }
            }

            "both-peers-derive-same-verdict" -> {
                val fromA = input["from_peer_a_perspective"]!!.jsonObject
                val fromB = input["from_peer_b_perspective"]!!.jsonObject
                val aVerdict =
                    Dedup.resolve(
                        Dedup.PeerTiebreak(PeerId("0000000000000000"), ConnTiebreak(fromA["self_conn_tiebreak"]!!.jsonPrimitive.content)),
                        Dedup.PeerTiebreak(PeerId("ffffffffffffffff"), ConnTiebreak(fromA["remote_conn_tiebreak"]!!.jsonPrimitive.content)),
                    )
                val bVerdict =
                    Dedup.resolve(
                        Dedup.PeerTiebreak(PeerId("ffffffffffffffff"), ConnTiebreak(fromB["self_conn_tiebreak"]!!.jsonPrimitive.content)),
                        Dedup.PeerTiebreak(PeerId("0000000000000000"), ConnTiebreak(fromB["remote_conn_tiebreak"]!!.jsonPrimitive.content)),
                    )
                check(aVerdict is Dedup.Verdict.Survivor && bVerdict is Dedup.Verdict.Survivor)
                // A computed (self=A, remote=B): self surviving means Side.A. B computed
                // (self=B, remote=A): remote surviving means Side.B. Both must agree on whether
                // peer A's outbound connection is the one that survives.
                val doesASurviveAccordingToA = aVerdict.survivorPeer == Dedup.Side.A
                val doesASurviveAccordingToB = bVerdict.survivorPeer == Dedup.Side.B
                assertEquals(doesASurviveAccordingToA, doesASurviveAccordingToB, "both peers must derive the same physical survivor")
            }

            "equal-tiebreak-both-close-and-regenerate" -> {
                val a = peerTiebreak(input["peer_a"]!!.jsonObject)
                val b = peerTiebreak(input["peer_b"]!!.jsonObject)
                val verdict = Dedup.resolve(a, b)
                assertEquals(Dedup.Verdict.Tie, verdict)
            }

            "initiator-not-assumed-leader" -> {
                val a = peerTiebreak(input["peer_a"]!!.jsonObject)
                val b = peerTiebreak(input["peer_b"]!!.jsonObject)
                val verdict = Dedup.resolve(a, b)
                check(verdict is Dedup.Verdict.Survivor)
                val leader = Leadership.elect(a.peerId, b.peerId)
                expected["surviving_connection_initiator"]?.let { assertEquals(sideOf(it.jsonPrimitive.content), verdict.survivorPeer) }
                expected["leader"]?.let { assertEquals(sideOf(it.jsonPrimitive.content), leader) }
                assertEquals(false, verdict.survivorPeer == leader, "initiator must NOT equal leader in this vector")
            }

            "acceptor-not-assumed-leader" -> {
                val a = peerTiebreak(input["peer_a"]!!.jsonObject)
                val b = peerTiebreak(input["peer_b"]!!.jsonObject)
                val verdict = Dedup.resolve(a, b)
                check(verdict is Dedup.Verdict.Survivor)
                val acceptor = if (verdict.survivorPeer == Dedup.Side.A) Dedup.Side.B else Dedup.Side.A
                val leader = Leadership.elect(a.peerId, b.peerId)
                expected["surviving_connection_acceptor"]?.let { assertEquals(sideOf(it.jsonPrimitive.content), acceptor) }
                expected["leader"]?.let { assertEquals(sideOf(it.jsonPrimitive.content), leader) }
                assertEquals(false, acceptor == leader, "acceptor must NOT equal leader in this vector")
            }

            else -> error("unhandled dedup vector: $name")
        }
    }
}
