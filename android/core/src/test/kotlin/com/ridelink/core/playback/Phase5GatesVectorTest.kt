package com.ridelink.core.playback

import com.ridelink.core.testutil.Vectors
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs `protocol/vectors/phase5-gates/phase5_gates_vectors.json` — ADR-024 Amendment A1's three
 * decision tables and Amendment A2's two, shared byte-for-byte with `Phase5GatesVectorTests` on iOS.
 *
 * All five are the audits' answer to CLAUDE.md rule 18: each was coordinator control flow before its
 * amendment, so no vector could pin it and the two platforms had already drifted.
 */
class Phase5GatesVectorTest {
    private val doc by lazy { Vectors.load("phase5-gates/phase5_gates_vectors.json").jsonObject }

    @Test
    fun ingressCrossProduct() {
        val rows = doc["ingress"]!!.jsonArray
        for (element in rows) {
            val row = element.jsonObject
            val input = row["input"]!!.jsonObject
            val admission =
                Phase5Ingress.decide(
                    kind = Phase5FrameKind.valueOf(input["kind"]!!.jsonPrimitive.content),
                    queuedTotal = input["queued_total"]!!.jsonPrimitive.int,
                    capacity = input["capacity"]!!.jsonPrimitive.int,
                    hasQueuedSameKind = input["has_queued_same_kind"]!!.jsonPrimitive.boolean,
                )
            assertEquals(
                IngressAdmission.valueOf(row["expected"]!!.jsonObject["admission"]!!.jsonPrimitive.content),
                admission,
                "vector ${row["name"]!!.jsonPrimitive.content}",
            )
        }
        assertEquals(64, rows.size, "2 kinds x 4 capacities x 4 queue depths x 2 sibling states")
    }

    @Test
    fun pendingCommandCrossProduct() {
        val rows = doc["pending_command"]!!.jsonArray
        for (element in rows) {
            val row = element.jsonObject
            val input = row["input"]!!.jsonObject
            val admission =
                PendingCommandGate.decide(
                    clockReady = input["clock_ready"]!!.jsonPrimitive.boolean,
                    deferredCount = input["deferred_count"]!!.jsonPrimitive.int,
                    capacity = input["capacity"]!!.jsonPrimitive.int,
                )
            assertEquals(
                CommandAdmission.valueOf(row["expected"]!!.jsonObject["admission"]!!.jsonPrimitive.content),
                admission,
                "vector ${row["name"]!!.jsonPrimitive.content}",
            )
        }
        assertEquals(36, rows.size, "2 readiness values x 3 capacities x 6 deferred depths")
    }

    @Test
    fun pendingPlayFullCrossProduct() {
        val rows = doc["pending_play"]!!.jsonArray
        for (element in rows) {
            val row = element.jsonObject
            val input = row["input"]!!.jsonObject
            val decision =
                PendingPlayGate.decide(
                    operationCurrent = input["operation_current"]!!.jsonPrimitive.boolean,
                    sessionCurrent = input["session_current"]!!.jsonPrimitive.boolean,
                    syncEnabled = input["sync_enabled"]!!.jsonPrimitive.boolean,
                    queueSettled = input["queue_settled"]!!.jsonPrimitive.boolean,
                    localContentReady = input["local_content_ready"]!!.jsonPrimitive.boolean,
                    peerContentRequired = input["peer_content_required"]!!.jsonPrimitive.boolean,
                    peerHasContent = input["peer_has_content"]!!.jsonPrimitive.boolean,
                )
            assertEquals(
                PendingPlayDecision.valueOf(row["expected"]!!.jsonObject["decision"]!!.jsonPrimitive.content),
                decision,
                "vector ${row["name"]!!.jsonPrimitive.content}",
            )
        }
        assertEquals(128, rows.size, "the complete 2^7 cross product — no precondition is a don't-care")
    }

    /**
     * The invariants the generator asserts about itself, re-asserted against *this* implementation
     * rather than against the JSON. A vector file that agreed with a wrong implementation would
     * still pass the three tests above; these three cannot.
     */
    @Test
    fun theIngressInvariantsHoldForThisImplementation() {
        val inputs =
            Phase5FrameKind.entries.flatMap { kind ->
                listOf(0, 1, 2, 256).flatMap { capacity ->
                    listOf(0, 1, 2, 256).flatMap { queued ->
                        listOf(false, true).map { sibling -> IngressCase(kind, capacity, queued, sibling) }
                    }
                }
            }
        for (case in inputs) {
            val admission = Phase5Ingress.decide(case.kind, case.queued, case.capacity, case.sibling)
            if (case.queued >= case.capacity) {
                assertTrue(admission != IngressAdmission.ADMIT, "a full queue must never admit")
            }
            if (case.kind == Phase5FrameKind.COMMAND) {
                assertTrue(admission != IngressAdmission.COALESCE, "an authoritative command is never superseded")
            }
        }
    }

    /** One point of the ingress cross product, named so the assertions read as claims rather than indices. */
    private data class IngressCase(
        val kind: Phase5FrameKind,
        val capacity: Int,
        val queued: Int,
        val sibling: Boolean,
    )

    @Test
    fun thePendingCommandInvariantsHoldForThisImplementation() {
        for (ready in listOf(false, true)) {
            for (deferred in 0..17) {
                val admission = PendingCommandGate.decide(ready, deferred, capacity = 16)
                if (!ready) assertTrue(admission != CommandAdmission.APPLY, "never schedule against an untrusted clock")
                if (deferred > 0) {
                    assertTrue(admission != CommandAdmission.APPLY, "a deferred command is never overtaken")
                }
            }
        }
    }

    @Test
    fun thePendingPlayInvariantsHoldForThisImplementation() {
        for (mask in 0 until 128) {
            fun bit(index: Int) = (mask shr index) and 1 == 1

            fun decideWith(peerHasContent: Boolean) =
                PendingPlayGate.decide(
                    operationCurrent = bit(6),
                    sessionCurrent = bit(5),
                    syncEnabled = bit(4),
                    queueSettled = bit(3),
                    localContentReady = bit(2),
                    peerContentRequired = bit(1),
                    peerHasContent = peerHasContent,
                )
            val decision = decideWith(bit(0))
            if (!bit(6) || !bit(5) || !bit(4)) {
                assertEquals(PendingPlayDecision.CANCEL, decision, "a superseded or session-stale Play never resurrects")
            }
            if (decision == PendingPlayDecision.ISSUE) {
                assertTrue(bit(6) && bit(5) && bit(4) && bit(3) && bit(2), "ISSUE needs every local precondition")
                assertTrue(!bit(1) || bit(0), "ISSUE needs the peer half whenever it is this device's question")
            }
            if (!bit(1)) {
                assertEquals(
                    decideWith(peerHasContent = false),
                    decideWith(peerHasContent = true),
                    "the peer half is the leader's question, never a follower's (PROTOCOL §5 rule 4)",
                )
            }
        }
    }

    // --- ADR-024 Amendment A2 ------------------------------------------------------------------

    @Test
    fun outboundCommitFullCrossProduct() {
        val rows = doc["outbound_commit"]!!.jsonArray
        for (element in rows) {
            val row = element.jsonObject
            val input = row["input"]!!.jsonObject
            val commit =
                OutboundCommitGate.decide(
                    authority = OutboundAuthority.valueOf(input["authority"]!!.jsonPrimitive.content),
                    outcome = OutboundOutcome.valueOf(input["outcome"]!!.jsonPrimitive.content),
                )
            assertEquals(
                OutboundCommit.valueOf(row["expected"]!!.jsonObject["commit"]!!.jsonPrimitive.content),
                commit,
                "vector ${row["name"]!!.jsonPrimitive.content}",
            )
        }
        assertEquals(12, rows.size, "the complete 3 authorities x 4 outcomes cross product")
    }

    @Test
    fun authoritativeHoldCrossProduct() {
        val rows = doc["authoritative_hold"]!!.jsonArray
        for (element in rows) {
            val row = element.jsonObject
            val input = row["input"]!!.jsonObject
            val admission =
                AuthoritativeHoldGate.decide(
                    heldCount = input["held_count"]!!.jsonPrimitive.int,
                    capacity = input["capacity"]!!.jsonPrimitive.int,
                )
            assertEquals(
                HoldAdmission.valueOf(row["expected"]!!.jsonObject["admission"]!!.jsonPrimitive.content),
                admission,
                "vector ${row["name"]!!.jsonPrimitive.content}",
            )
        }
        assertEquals(24, rows.size, "4 capacities x 6 hold depths")
    }

    /**
     * Amendment A2's central rule, asserted against *this* implementation rather than against the
     * JSON: **only an actual send commits**, and only an authoritative frame fails closed.
     */
    @Test
    fun theOutboundCommitInvariantsHoldForThisImplementation() {
        for (authority in OutboundAuthority.entries) {
            for (outcome in OutboundOutcome.entries) {
                val commit = OutboundCommitGate.decide(authority, outcome)
                assertEquals(
                    outcome == OutboundOutcome.SENT,
                    commit == OutboundCommit.COMMIT,
                    "admission is not delivery, and send == false is not a send",
                )
                if (authority != OutboundAuthority.AUTHORITATIVE) {
                    assertTrue(
                        commit != OutboundCommit.ABORT_FAIL_CLOSED,
                        "an intent or an advisory frame never owned authority to fail closed on",
                    )
                }
                if (authority == OutboundAuthority.AUTHORITATIVE && outcome != OutboundOutcome.SENT) {
                    assertEquals(
                        OutboundCommit.ABORT_FAIL_CLOSED,
                        commit,
                        "a leader may never commit locally what the follower never received",
                    )
                }
            }
        }
    }

    @Test
    fun theAuthoritativeHoldInvariantsHoldForThisImplementation() {
        for (capacity in 0..17) {
            for (held in 0..17) {
                val admission = AuthoritativeHoldGate.decide(held, capacity)
                if (held > 0) {
                    assertTrue(
                        admission != HoldAdmission.PROCESS_NOW,
                        "nothing may overtake held authoritative work and change what it means",
                    )
                }
                if (held >= capacity && held > 0) {
                    assertEquals(HoldAdmission.OVERFLOW, admission, "the hold buffer's bound is real")
                }
            }
        }
    }

    @Test
    fun theBoundsAreSaneRelativeToEachOther() {
        assertFalse(
            Phase5GateBounds.DEFAULT_DEFERRED_COMMAND_CAPACITY > Phase5GateBounds.DEFAULT_INBOUND_CAPACITY,
            "the deferred buffer is a small back-stop, never larger than the ingress bound",
        )
        assertEquals(100_000L, Phase5GateBounds.DEFERRED_RETRY_INTERVAL_US)
    }

    /** The bounds are on the wire's side of nothing, but a silent divergence would still desynchronise a test. */
    @Test
    fun theBoundsMatchTheVectorFile() {
        assertEquals(
            doc["default_inbound_capacity"]!!.jsonPrimitive.int,
            Phase5GateBounds.DEFAULT_INBOUND_CAPACITY,
        )
        assertEquals(
            doc["default_deferred_command_capacity"]!!.jsonPrimitive.int,
            Phase5GateBounds.DEFAULT_DEFERRED_COMMAND_CAPACITY,
        )
        assertEquals(
            doc["deferred_retry_interval_us"]!!.jsonPrimitive.long,
            Phase5GateBounds.DEFERRED_RETRY_INTERVAL_US,
        )
        assertEquals(
            doc["default_outbound_capacity"]!!.jsonPrimitive.int,
            Phase5GateBounds.DEFAULT_OUTBOUND_CAPACITY,
        )
    }
}
