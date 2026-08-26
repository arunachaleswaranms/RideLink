package com.ridelink.network.discovery

import com.ridelink.core.model.DiscoveredPeer

/**
 * Platform-neutral peer lifecycle bookkeeping (this session's brief §4D/§4E), extracted from the
 * `NsdManager` callback wiring so it is unit-testable with no Android APIs on the classpath.
 *
 * mDNS service *names*, not discovery handles, are what a browse-level "lost" notification gives
 * back unresolved — [onServiceLost] uses the name to recover the [DiscoveryEvent.Lost] handle
 * this class remembered from the matching [onResolved] call. Not internally synchronized: the
 * caller (real `NsdManager` callbacks arrive on arbitrary threads) is responsible for
 * serialising access — see [NsdDiscoveryController.browse].
 */
class DiscoveryLifecycleTracker(
    private val isSelf: (DiscoveredPeer) -> Boolean,
) {
    private val handleByServiceName = mutableMapOf<String, String>()
    private val seenServiceNames = mutableSetOf<String>()

    /**
     * @return [DiscoveryEvent.Found] the first time [serviceName] resolves,
     *   [DiscoveryEvent.Updated] on every subsequent resolution of the same service name, or
     *   `null` when [peer] is this device's own advertisement (self-discovery, silently ignored).
     */
    fun onResolved(
        serviceName: String,
        peer: DiscoveredPeer,
    ): DiscoveryEvent? {
        if (isSelf(peer)) return null
        handleByServiceName[serviceName] = peer.discoveryHandle
        return if (seenServiceNames.add(serviceName)) {
            DiscoveryEvent.Found(peer)
        } else {
            DiscoveryEvent.Updated(peer)
        }
    }

    /** @return [DiscoveryEvent.Lost] with the peer's discovery handle, or `null` if it was never (successfully) resolved. */
    fun onServiceLost(serviceName: String): DiscoveryEvent.Lost? {
        seenServiceNames.remove(serviceName)
        return handleByServiceName.remove(serviceName)?.let(DiscoveryEvent::Lost)
    }
}
