package com.ridelink.network.discovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure bookkeeping tests for this session's brief §5 — `NsdManager.ServiceInfoCallback`
 * registrations must be tracked and unregistered on service loss, browse stop and teardown, and
 * a registration failure must not leave a phantom entry behind. `Callback` is a plain `String`
 * stand-in here; the registry has no Android dependency to fake.
 */
class ServiceInfoCallbackRegistryTest {
    @Test
    fun `record then remove returns the tracked callback`() {
        val registry = ServiceInfoCallbackRegistry<String>()
        assertNull(registry.record("svc-1", "callback-A"))
        assertTrue(registry.isTracked("svc-1"))
        assertEquals("callback-A", registry.remove("svc-1"))
        assertNull(registry.remove("svc-1"), "removing twice must not resurrect a stale entry")
    }

    @Test
    fun `service lost unregisters exactly the tracked callback for that service, not another`() {
        val registry = ServiceInfoCallbackRegistry<String>()
        registry.record("svc-1", "callback-A")
        registry.record("svc-2", "callback-B")

        val removed = registry.remove("svc-1")

        assertEquals("callback-A", removed)
        assertTrue(registry.isTracked("svc-2"), "an unrelated service's registration must survive")
        assertEquals(1, registry.size)
    }

    @Test
    fun `re-recording the same service name returns the previous callback for the caller to unregister`() {
        val registry = ServiceInfoCallbackRegistry<String>()
        registry.record("svc-1", "callback-A")

        val previous = registry.record("svc-1", "callback-A-replacement")

        assertEquals("callback-A", previous, "a duplicate onServiceFound must surface the stale registration, not silently drop it")
        assertEquals("callback-A-replacement", registry.remove("svc-1"))
    }

    @Test
    fun `browse stop or teardown removes and returns every tracked callback`() {
        val registry = ServiceInfoCallbackRegistry<String>()
        registry.record("svc-1", "callback-A")
        registry.record("svc-2", "callback-B")
        registry.record("svc-3", "callback-C")

        val all = registry.removeAll()

        assertEquals(setOf("callback-A", "callback-B", "callback-C"), all.toSet())
        assertEquals(0, registry.size, "the registry must be empty after removeAll")
        assertTrue(registry.removeAll().isEmpty(), "calling removeAll again must be a safe no-op")
    }

    @Test
    fun `registration failure clears local tracking without anything to unregister`() {
        val registry = ServiceInfoCallbackRegistry<String>()
        registry.record("svc-1", "callback-A")

        registry.recordFailed("svc-1")

        assertNull(registry.remove("svc-1"), "a failed registration must not remain tracked")
        assertEquals(0, registry.size)
    }

    @Test
    fun `recordFailed on an untracked service is a safe no-op`() {
        val registry = ServiceInfoCallbackRegistry<String>()
        registry.recordFailed("never-registered")
        assertEquals(0, registry.size)
    }

    @Test
    fun `repeated discovery cycles and dh rotation never accumulate stale entries`() {
        val registry = ServiceInfoCallbackRegistry<String>()
        // Simulate several found/lost cycles for the same service name across rotation.
        repeat(5) { iteration ->
            registry.record("svc-1", "callback-$iteration")
            assertEquals(1, registry.size, "a service already tracked must not create a second entry")
            registry.remove("svc-1")
        }
        assertEquals(0, registry.size)
    }
}
