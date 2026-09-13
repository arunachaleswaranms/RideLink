package com.ridelink.app.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [SessionTeardownOwner]'s two guarantees, on their own — the ownership primitive
 * `SessionCoordinator`'s `retireSession` is built on (`docs/STATUS.md` §4 problem 53).
 *
 * Kept separate from `SessionLifecycleRestartTest` because these are the *deterministic* half: they
 * prove "joining the returned job is joining the body" and "teardowns never interleave" without
 * depending on any scheduler race at all. The integration suite then proves the coordinator actually
 * uses them for the right steps, in the right order.
 *
 * `RideLinkPlatform`'s `SessionTeardownOwnershipTests` is the mirror, and on that platform it carries
 * more weight: the iOS app-target coordinator has no test bundle at all, so these three properties
 * plus code inspection are the strongest direct proof iOS has of the primitive.
 */
class SessionTeardownOwnerTest {
    @Test
    fun `joining a retirement joins its body, not merely its start`() =
        withOwner { owner ->
            val gate = CompletableDeferred<Unit>()
            var finished = false

            val job =
                owner.retire {
                    gate.await()
                    finished = true
                }
            assertFalse(finished, "the body has not run yet")
            gate.complete(Unit)
            job.join()
            assertTrue(finished, "joining the job is joining the body")
        }

    /**
     * Two teardowns share one `ControlSessionManager`, so an overlapping pair could shut down the
     * session a successor had just started. They are chained instead.
     */
    @Test
    fun `a second retirement waits for the first`() =
        withOwner { owner ->
            val firstGate = CompletableDeferred<Unit>()
            val order = Collections.synchronizedList(mutableListOf<String>())

            owner.retire {
                firstGate.await()
                order += "first"
            }
            val second = owner.retire { order += "second" }

            assertTrue(order.isEmpty(), "neither teardown may have finished while the first is parked")
            firstGate.complete(Unit)
            second.join()
            assertEquals(listOf("first", "second"), order.toList(), "teardowns run in order, never concurrently")
        }

    /**
     * [SessionTeardownOwner.pending] is what a successor joins, so it must always be the **latest**
     * retirement — the one whose completion implies every earlier one has completed too.
     */
    @Test
    fun `pending is always the latest retirement`() =
        withOwner { owner ->
            assertNull(owner.pending, "nothing has been retired yet")
            val first = owner.retire {}
            assertSame(first, owner.pending)
            val second = owner.retire {}
            assertSame(second, owner.pending)
            second.join()
            assertTrue(first.isCompleted, "and joining the latest implies the earlier one finished")
        }

    private fun withOwner(body: suspend (SessionTeardownOwner) -> Unit) =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob())
            try {
                body(SessionTeardownOwner(scope))
            } finally {
                scope.cancel()
            }
        }
}
