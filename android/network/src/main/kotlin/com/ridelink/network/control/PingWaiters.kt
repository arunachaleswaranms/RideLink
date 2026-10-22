package com.ridelink.network.control

import com.ridelink.core.sync.ClockSync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The `PING` round trips one control connection has outstanding, keyed by `t1_mono_us` — PROTOCOL
 * §6's echo field — and nothing else: no socket, no session, no clock estimate.
 *
 * Extracted from [ControlSessionManager] without a change in behaviour (PR #27 review: the class had
 * crossed detekt's `LargeClass` bound, and the repository's rule is to extract a cohesive piece rather
 * than raise the bound). It is the Android counterpart of iOS's `PingRequestRegistry`. Who may answer
 * a waiter is still decided where the `PONG` is admitted — the read loop's connection binding and
 * [ControlSessionManager]'s plausibility guard — before [complete] is ever called.
 */
internal class PingWaiters {
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<ClockSync.Sample>>()

    /** Waiters registered and not yet completed, failed or timed out. */
    val size: Int get() = pending.size

    /**
     * Registers the waiter under [t1] **before** [write] runs, then awaits the matching `PONG` for at
     * most [timeoutMs]. The waiter is registered before the frame is written, and nothing suspends in
     * between that a `PONG` could race through — see this session's brief §2, which asked for this
     * ordering to be reviewed against the iOS race rather than assumed. What *was* missing: a write
     * failure (not just a timeout) left the entry here forever. `catch (Throwable)` covers both,
     * always removes the entry, and always rethrows — never resumes-then-throws twice.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun awaitRoundTrip(
        t1: Long,
        timeoutMs: Long,
        write: suspend () -> Unit,
    ): ClockSync.Sample {
        val deferred = CompletableDeferred<ClockSync.Sample>()
        pending[t1] = deferred
        try {
            write()
            return withTimeout(timeoutMs) { deferred.await() }
        } catch (t: Throwable) {
            pending.remove(t1)
            throw t
        }
    }

    /** Resolves the waiter for [sample]'s `t1`, if one is still outstanding. A late `PONG` finds none. */
    fun complete(sample: ClockSync.Sample) {
        pending.remove(sample.t1MonoUs)?.complete(sample)
    }

    /**
     * Teardown must fail every outstanding waiter rather than leave it to time out on a socket
     * that is already dead (this session's brief §10). `pending.keys.toList()` (the first version
     * of this) could throw `NoSuchElementException` from `ConcurrentHashMap`'s iterator: a
     * keepalive/clock-sync coroutine can still be mid-[awaitRoundTrip], inserting a new entry, in
     * the window between `keepaliveJob?.cancel()`/`clockSyncJob?.cancel()` being requested and
     * those coroutines actually reaching a suspension point that observes the cancellation.
     * `ConcurrentHashMap`'s own bulk [ConcurrentHashMap.forEach] is the JDK-documented
     * concurrent-safe way to traverse under exactly this kind of racing structural modification.
     */
    fun failAll() {
        val toFail = mutableListOf<Pair<Long, CompletableDeferred<ClockSync.Sample>>>()
        pending.forEach { key, value -> toFail.add(key to value) }
        for ((key, deferred) in toFail) {
            pending.remove(key)
            deferred.completeExceptionally(IOException("connection ended"))
        }
    }
}
