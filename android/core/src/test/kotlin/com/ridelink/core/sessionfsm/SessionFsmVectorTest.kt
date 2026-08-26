package com.ridelink.core.sessionfsm

import com.ridelink.core.testutil.Vectors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** protocol/vectors/session-fsm/fsm_vectors.json, ARCHITECTURE §3, TEST_PLAN §2. */
class SessionFsmVectorTest {
    private fun parseState(obj: JsonObject): FsmState {
        val status = SessionStatus.valueOf(obj["status"]!!.jsonPrimitive.content)
        val returnTo = obj["returnTo"]?.jsonPrimitive?.content?.let { SessionStatus.valueOf(it) }
        return FsmState(status, returnTo)
    }

    @Suppress("CyclomaticComplexMethod") // flat string->sealed-subtype dispatch table, not real branching logic
    private fun parseEvent(obj: JsonObject): SessionEvent =
        when (val kind = obj["kind"]!!.jsonPrimitive.content) {
            "StartDiscovery" -> SessionEvent.StartDiscovery
            "CancelDiscovery" -> SessionEvent.CancelDiscovery
            "PeerSelected" -> SessionEvent.PeerSelected
            "PairingRejectedOrTimeout" -> SessionEvent.PairingRejectedOrTimeout
            "PairingSucceeded" -> SessionEvent.PairingSucceeded
            "ConnectionEstablished" -> SessionEvent.ConnectionEstablished
            "ConnectionFailed" -> SessionEvent.ConnectionFailed
            "ReconnectSucceeded" -> SessionEvent.ReconnectSucceeded
            "ReconnectBudgetExhausted" -> SessionEvent.ReconnectBudgetExhausted
            "RetryRequested" -> SessionEvent.RetryRequested
            "StartRide" -> SessionEvent.StartRide
            "EndRide" -> SessionEvent.EndRide
            "LinkLost" -> SessionEvent.LinkLost(LinkLossReason.valueOf(obj["reason"]!!.jsonPrimitive.content))
            "UserEnded" -> SessionEvent.UserEnded
            "FatalError" -> SessionEvent.FatalError(obj["reason"]!!.jsonPrimitive.content)
            "ErrorAcknowledged" -> SessionEvent.ErrorAcknowledged
            "TeardownComplete" -> SessionEvent.TeardownComplete
            "DuplicateConnectionClosed" -> SessionEvent.DuplicateConnectionClosed
            else -> error("unknown event kind in vector: $kind")
        }

    @TestFactory
    fun legalTransitions(): List<DynamicTest> {
        val root = Vectors.load("session-fsm/fsm_vectors.json").jsonObject
        return root["legal_transitions"]!!.jsonArray.map { element ->
            val v = element.jsonObject
            DynamicTest.dynamicTest(v["name"]!!.jsonPrimitive.content) {
                val from = parseState(v["from"]!!.jsonObject)
                val event = parseEvent(v["event"]!!.jsonObject)
                val expectedTo = parseState(v["to"]!!.jsonObject)

                val result = SessionFsm.transition(from, event)
                val transitioned = assertIs<FsmResult.Transitioned>(result, "expected a legal transition")
                assertEquals(expectedTo, transitioned.newState)

                val expectedEffects = v["effects"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                if ("RELEASE_AUDIO_AND_STOP_FOREGROUND_SERVICE" in expectedEffects) {
                    assertTrue(
                        transitioned.effects.any { it is Effect.ReleaseAudioAndStopForegroundService },
                        "expected the audio-release effect on entering ${expectedTo.status}",
                    )
                }
                assertTrue(
                    transitioned.effects.any { it is Effect.LogTransition },
                    "every real transition must be logged (ARCHITECTURE §3 rule 5)",
                )
            }
        }
    }

    @TestFactory
    fun illegalTransitions(): List<DynamicTest> {
        val root = Vectors.load("session-fsm/fsm_vectors.json").jsonObject
        return root["illegal_transitions"]!!.jsonArray.map { element ->
            val v = element.jsonObject
            DynamicTest.dynamicTest(v["name"]!!.jsonPrimitive.content) {
                val from = parseState(v["from"]!!.jsonObject)
                val event = parseEvent(v["event"]!!.jsonObject)

                val result = SessionFsm.transition(from, event)
                val rejected = assertIs<FsmResult.Rejected>(result, "expected the transition to be rejected, not applied or crash")
                assertEquals(from, rejected.currentState, "state must be unchanged after an illegal transition")
            }
        }
    }

    @TestFactory
    fun nonFaultNonTransitionEvents(): List<DynamicTest> {
        val root = Vectors.load("session-fsm/fsm_vectors.json").jsonObject
        return root["non_fault_non_transition_events"]!!.jsonArray.map { element ->
            val v = element.jsonObject
            DynamicTest.dynamicTest(v["name"]!!.jsonPrimitive.content) {
                val from = parseState(v["from"]!!.jsonObject)
                val event = parseEvent(v["event"]!!.jsonObject)

                val result = SessionFsm.transition(from, event)
                val failureMessage = "duplicate-connection-closed must be Ignored, not Rejected or Transitioned"
                val ignored = assertIs<FsmResult.Ignored>(result, failureMessage)
                assertEquals(from, ignored.currentState, "state must be unchanged")
            }
        }
    }
}
