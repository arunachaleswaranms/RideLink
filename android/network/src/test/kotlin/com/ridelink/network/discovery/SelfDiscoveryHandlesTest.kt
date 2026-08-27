package com.ridelink.network.discovery

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * This session's brief §8: during a `dh` rotation transition, the old local dh must still be
 * treated as self until its unregistration is confirmed, the new local dh must be treated as
 * self immediately, and a remote peer's dh must never be treated as self.
 */
class SelfDiscoveryHandlesTest {
    @Test
    fun `before any rotation, nothing is self`() {
        val handles = SelfDiscoveryHandles()
        assertFalse(handles.isSelf("anything"))
        assertNull(handles.currentHandle)
    }

    @Test
    fun `after the first rotate, the new handle is self`() {
        val handles = SelfDiscoveryHandles()
        handles.rotate("aaaa")
        assertTrue(handles.isSelf("aaaa"))
        assertEqualsHandle("aaaa", handles.currentHandle)
    }

    @Test
    fun `during the transition, both the new and the just-superseded old handle are self`() {
        val handles = SelfDiscoveryHandles()
        handles.rotate("aaaa")
        handles.rotate("bbbb")

        assertTrue(handles.isSelf("bbbb"), "new local dh must be treated as self")
        assertTrue(handles.isSelf("aaaa"), "old local dh must still be treated as self until unregistration confirms")
        assertEqualsHandle("bbbb", handles.currentHandle)
    }

    @Test
    fun `a remote peer's dh is never treated as self, even mid-transition`() {
        val handles = SelfDiscoveryHandles()
        handles.rotate("aaaa")
        handles.rotate("bbbb")

        assertFalse(handles.isSelf("cccccccc-remote-peer"), "remote dh must not be treated as self")
    }

    @Test
    fun `clearPrevious ends the transition -- only the current handle remains self`() {
        val handles = SelfDiscoveryHandles()
        handles.rotate("aaaa")
        handles.rotate("bbbb")

        handles.clearPrevious()

        assertTrue(handles.isSelf("bbbb"))
        assertFalse(handles.isSelf("aaaa"), "once unregistration is confirmed, the old dh is no longer self")
    }

    @Test
    fun `a third rotation without an intervening clearPrevious drops the oldest handle`() {
        val handles = SelfDiscoveryHandles()
        handles.rotate("aaaa")
        handles.rotate("bbbb")
        handles.rotate("cccc")

        assertTrue(handles.isSelf("cccc"))
        assertTrue(handles.isSelf("bbbb"), "the immediately-previous handle is still self")
        assertFalse(handles.isSelf("aaaa"), "only one generation back is tracked, matching the real single unregister-in-flight case")
    }

    @Test
    fun `reset clears both handles -- nothing is self once advertising stops`() {
        val handles = SelfDiscoveryHandles()
        handles.rotate("aaaa")
        handles.rotate("bbbb")

        handles.reset()

        assertFalse(handles.isSelf("aaaa"))
        assertFalse(handles.isSelf("bbbb"))
        assertNull(handles.currentHandle)
    }

    private fun assertEqualsHandle(
        expected: String,
        actual: String?,
    ) {
        assertTrue(expected == actual, "expected currentHandle '$expected' but was '$actual'")
    }
}
