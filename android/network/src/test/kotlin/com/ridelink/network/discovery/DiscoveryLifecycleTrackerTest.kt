package com.ridelink.network.discovery

import com.ridelink.core.model.DiscoveredPeer
import com.ridelink.core.model.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** This session's brief §21: Found -> present, Updated -> replaced, Lost -> removed, repeated Found -> no duplicate, own dh -> ignored. */
class DiscoveryLifecycleTrackerTest {
    private val selfDh = "11111111111111111111111111111111"

    private fun peer(
        dh: String,
        port: Int = 5000,
    ) = DiscoveredPeer(discoveryHandle = dh, protocolMajorVersion = 1, platform = Platform.IOS, host = "192.168.1.2", port = port)

    private fun tracker() = DiscoveryLifecycleTracker(isSelf = { it.discoveryHandle == selfDh })

    @Test
    fun `first resolution of a service name is Found`() {
        val t = tracker()
        val event = t.onResolved("svc1", peer("aaaa"))
        assertIs<DiscoveryEvent.Found>(event)
        assertEquals("aaaa", event.peer.discoveryHandle)
    }

    @Test
    fun `second resolution of the same service name is Updated, not a duplicate Found`() {
        val t = tracker()
        t.onResolved("svc1", peer("aaaa", port = 5000))
        val second = t.onResolved("svc1", peer("aaaa", port = 5001))
        assertIs<DiscoveryEvent.Updated>(second)
        assertEquals(5001, second.peer.port)
    }

    @Test
    fun `repeated identical Found never re-emits Found`() {
        val t = tracker()
        t.onResolved("svc1", peer("aaaa"))
        val third = t.onResolved("svc1", peer("aaaa"))
        assertIs<DiscoveryEvent.Updated>(third) // never Found again once seen
    }

    @Test
    fun `service lost removes the peer and reports its discovery handle`() {
        val t = tracker()
        t.onResolved("svc1", peer("aaaa"))
        val lost = t.onServiceLost("svc1")
        assertIs<DiscoveryEvent.Lost>(lost)
        assertEquals("aaaa", lost.discoveryHandle)
    }

    @Test
    fun `losing a service never resolved yields no event`() {
        val t = tracker()
        assertNull(t.onServiceLost("never-seen"))
    }

    @Test
    fun `after lost, the next resolution is Found again, not Updated`() {
        val t = tracker()
        t.onResolved("svc1", peer("aaaa"))
        t.onServiceLost("svc1")
        val rediscovered = t.onResolved("svc1", peer("aaaa"))
        assertIs<DiscoveryEvent.Found>(rediscovered)
    }

    @Test
    fun `own discovery handle is ignored entirely`() {
        val t = tracker()
        val event = t.onResolved("self-svc", peer(selfDh))
        assertNull(event)
    }

    @Test
    fun `two different peers are tracked independently`() {
        val t = tracker()
        val a = t.onResolved("svc-a", peer("aaaa"))
        val b = t.onResolved("svc-b", peer("bbbb"))
        assertIs<DiscoveryEvent.Found>(a)
        assertIs<DiscoveryEvent.Found>(b)
        val lostA = t.onServiceLost("svc-a")
        assertEquals("aaaa", (lostA as DiscoveryEvent.Lost).discoveryHandle)
        // peer b must still be present / resolvable as Updated, not Found (it was never lost)
        assertIs<DiscoveryEvent.Updated>(t.onResolved("svc-b", peer("bbbb")))
    }
}
