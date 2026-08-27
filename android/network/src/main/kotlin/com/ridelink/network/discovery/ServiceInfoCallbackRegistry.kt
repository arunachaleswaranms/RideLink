package com.ridelink.network.discovery

/**
 * Pure bookkeeping for `NsdManager.ServiceInfoCallback` registrations (API 34+), keyed by mDNS
 * service name. `NsdManager` gives no way to ask "what did I register for this service", so this
 * tracks it — the fix for this session's brief §5: a callback registered on every
 * `onServiceFound` with nothing ever unregistering it leaks across peer loss, repeated discovery,
 * `dh` rotation and browsing restarts.
 *
 * Extracted as a plain, `android.*`-free class so the bookkeeping — the part that is easy to get
 * wrong (unregistering the wrong callback, double-unregistering, leaking on failure) — is a fast
 * JVM unit test, not something only observable on-device.
 */
class ServiceInfoCallbackRegistry<Callback> {
    private val active = mutableMapOf<String, Callback>()

    /**
     * Records [callback] as the active registration for [serviceName]. Returns the *previous*
     * callback tracked for that name, if any — a caller that finds a non-null result has a stale
     * registration on its hands (e.g. a duplicate `onServiceFound` with no intervening `Lost`)
     * and must unregister exactly that one, never a different service's.
     */
    fun record(
        serviceName: String,
        callback: Callback,
    ): Callback? = active.put(serviceName, callback)

    /**
     * Removes and returns the tracked callback for [serviceName], or `null` if none is tracked
     * (already unregistered, or registration never succeeded — see [recordFailed]).
     */
    fun remove(serviceName: String): Callback? = active.remove(serviceName)

    /** Registration failed: there is nothing to unregister, only local bookkeeping to clear. */
    fun recordFailed(serviceName: String) {
        active.remove(serviceName)
    }

    /**
     * Removes and returns every tracked callback, clearing the registry — used when browsing
     * stops or the controller tears down, where every outstanding registration must go.
     */
    fun removeAll(): List<Callback> {
        val all = active.values.toList()
        active.clear()
        return all
    }

    val size: Int get() = active.size

    fun isTracked(serviceName: String): Boolean = active.containsKey(serviceName)
}
