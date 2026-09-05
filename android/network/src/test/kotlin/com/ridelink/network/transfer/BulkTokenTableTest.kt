package com.ridelink.network.transfer

import com.ridelink.core.model.TransferId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Closure-audit Findings A/L/M, isolated from [BulkTransportManager]'s socket/TLS plumbing —
 * [BulkTransportManagerTest] already proves the end-to-end real-loopback behaviour; these prove the
 * token table's own rules directly and fast.
 */
class BulkTokenTableTest {
    @Test
    fun `a token issued under generation N is rejected once the live generation moves to N plus 1`() =
        runTest {
            // Finding A's real bug lived in the *production caller* (a captured `val generation`
            // replayed into a closure), not in this table — but the invariant the fix depends on is
            // this table's own: validation must be checked against whatever `currentGeneration` the
            // caller supplies at consumption time, not stored anywhere here at issuance time.
            val table = BulkTokenTable({ 0L })
            val transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5C")
            val token = table.issue(transferId, generation = 5L)

            assertFalse(
                table.validateAndConsume(transferId, token, currentGeneration = 6L),
                "a token minted under generation 5 must fail once the live generation is 6",
            )
        }

    @Test
    fun `a token issued and validated under the same still-current generation succeeds`() =
        runTest {
            val table = BulkTokenTable({ 0L })
            val transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5C")
            val token = table.issue(transferId, generation = 5L)

            assertTrue(table.validateAndConsume(transferId, token, currentGeneration = 5L))
        }

    @Test
    fun `single-use- a second presentation of the same token is rejected`() =
        runTest {
            val table = BulkTokenTable({ 0L })
            val transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5C")
            val token = table.issue(transferId, generation = 1L)

            assertTrue(table.validateAndConsume(transferId, token, currentGeneration = 1L))
            assertFalse(table.validateAndConsume(transferId, token, currentGeneration = 1L), "a consumed token must never validate again")
        }

    @Test
    fun `an expired token is rejected even with the right generation and token bytes`() =
        runTest {
            var now = 0L
            val table = BulkTokenTable({ now })
            val transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5C")
            val token = table.issue(transferId, generation = 1L)

            now = 31_000_000L // past the 30s TTL
            assertFalse(table.validateAndConsume(transferId, token, currentGeneration = 1L))
        }

    @Test
    fun `the wrong token is rejected even with the right transfer id and generation`() =
        runTest {
            val table = BulkTokenTable({ 0L })
            val transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5C")
            table.issue(transferId, generation = 1L)

            assertFalse(table.validateAndConsume(transferId, "0".repeat(64), currentGeneration = 1L))
        }

    // --- Finding M: reissue collision ------------------------------------------------------------

    @Test
    fun `tryIssue refuses to overwrite a still-live unconsumed entry for the same transfer id`() =
        runTest {
            val table = BulkTokenTable({ 0L })
            val transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5C")
            val first = table.issue(transferId, generation = 1L)

            val second = table.tryIssue(transferId, generation = 1L)
            assertNull(second, "a live unconsumed entry must not be silently replaced")
            // The original token must still be the one that validates.
            assertTrue(table.validateAndConsume(transferId, first, currentGeneration = 1L))
        }

    @Test
    fun `tryIssue succeeds once the prior entry for that transfer id has been consumed`() =
        runTest {
            val table = BulkTokenTable({ 0L })
            val transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5C")
            val first = table.issue(transferId, generation = 1L)
            assertTrue(table.validateAndConsume(transferId, first, currentGeneration = 1L))

            val second = table.tryIssue(transferId, generation = 1L)
            assertNotNull(second, "a transfer id whose prior token was already consumed may be reissued")
        }

    @Test
    fun `tryIssue succeeds once the prior entry for that transfer id has expired`() =
        runTest {
            var now = 0L
            val table = BulkTokenTable({ now })
            val transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5C")
            table.issue(transferId, generation = 1L)

            now = 31_000_000L
            val second = table.tryIssue(transferId, generation = 1L)
            assertNotNull(second, "an expired prior entry does not block reissue")
        }

    @Test
    fun `sweepBelow removes only entries from an earlier generation`() =
        runTest {
            val table = BulkTokenTable({ 0L })
            val old = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5C")
            val fresh = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5D")
            val oldToken = table.issue(old, generation = 1L)
            val freshToken = table.issue(fresh, generation = 2L)

            table.sweepBelow(currentGeneration = 2L)

            assertFalse(table.validateAndConsume(old, oldToken, currentGeneration = 2L))
            assertTrue(table.validateAndConsume(fresh, freshToken, currentGeneration = 2L))
        }
}
