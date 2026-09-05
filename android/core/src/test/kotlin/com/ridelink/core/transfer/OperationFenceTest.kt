package com.ridelink.core.transfer

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Closure-audit findings C/D/N/P/S: a superseded operation's own late completion must never
 * mutate state again. These are the pure-logic proofs behind that guarantee — the coordinator
 * integration itself is exercised by [com.ridelink.app.library.SharedLibraryCoordinator]'s own
 * usage, but the fencing rule itself is proven here, once, independent of any coroutine/socket.
 */
class OperationFenceTest {
    @Test
    fun `a freshly begun token is current`() {
        val fence = OperationFence()
        val token = fence.begin()
        assertTrue(fence.isCurrent(token))
    }

    @Test
    fun `beginning a second operation invalidates the first token`() {
        val fence = OperationFence()
        val first = fence.begin()
        val second = fence.begin()
        assertNotEquals(first, second)
        assertFalse(fence.isCurrent(first), "a superseded operation's token must stop being current")
        assertTrue(fence.isCurrent(second))
    }

    @Test
    fun `supersede invalidates the active token without starting a replacement`() {
        val fence = OperationFence()
        val token = fence.begin()
        fence.supersede()
        assertFalse(fence.isCurrent(token), "cancellation/session-loss must invalidate the in-flight operation")
        // Nothing is current until a fresh begin() — supersede() alone never re-validates anything.
        assertFalse(fence.isCurrent(0L))
    }

    @Test
    fun `a token from before any begin is never current`() {
        val fence = OperationFence()
        assertFalse(fence.isCurrent(0L))
        assertFalse(fence.isCurrent(1L))
    }

    @Test
    fun `terminal-state discipline- a cancelled operation's late completion cannot resurrect it`() {
        // Models brief §17 directly: CANCELLED must stay CANCELLED even if the original operation's
        // coroutine keeps running past the point of cancellation and eventually tries to report
        // COMPLETE/FAILED.
        val fence = OperationFence()
        val token = fence.begin() // operation N starts
        fence.supersede() // user cancels operation N
        val staleWriteAllowed = fence.isCurrent(token)
        assertFalse(staleWriteAllowed, "a cancelled operation's own late write must be dropped, not applied")
    }

    @Test
    fun `many sequential operations only ever validate their own token`() {
        val fence = OperationFence()
        val tokens = (1..50).map { fence.begin() }
        tokens.dropLast(1).forEach { assertFalse(fence.isCurrent(it)) }
        assertTrue(fence.isCurrent(tokens.last()))
    }
}
