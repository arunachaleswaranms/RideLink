package com.ridelink.app.di

import android.content.Context
import android.os.SystemClock
import com.ridelink.app.session.SessionCoordinator
import com.ridelink.core.logging.InMemoryLogSink
import com.ridelink.network.discovery.NsdDiscoveryController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual constructor dependency injection (ADR-014 §2 — no DI framework in V1). This is the
 * composition root; nothing outside `app` should construct a [SessionCoordinator] directly.
 */
class AppContainer(
    context: Context,
) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Phase 1a: an in-memory sink is enough to prove the redaction-by-construction contract.
    // A persistent/platform sink (Logcat, ring buffer) arrives when there is a reason to keep one.
    private val logSink = InMemoryLogSink()

    private val discoveryController = NsdDiscoveryController(context)

    val sessionCoordinator: SessionCoordinator =
        SessionCoordinator(
            discovery = discoveryController,
            scope = appScope,
            logSink = logSink,
            monotonicNowUs = { SystemClock.elapsedRealtimeNanos() / 1_000 },
        )
}
