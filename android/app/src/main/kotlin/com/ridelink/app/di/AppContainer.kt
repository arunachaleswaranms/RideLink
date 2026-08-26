package com.ridelink.app.di

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.ridelink.app.session.SessionCoordinator
import com.ridelink.core.logging.InMemoryLogSink
import com.ridelink.network.control.ConnTiebreakGenerator
import com.ridelink.network.control.ControlSessionManager
import com.ridelink.network.control.LocalHandshakeIdentity
import com.ridelink.network.discovery.NsdDiscoveryController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Phase 1a has no BuildConfig wiring yet (no reason to enable it for one literal). */
private const val APP_VERSION = "0.1.0"
private const val NANOS_PER_MICRO = 1_000L

/**
 * Manual constructor dependency injection (ADR-014 §2 — no DI framework in V1). This is the
 * composition root; nothing outside `app` should construct a [SessionCoordinator] directly.
 */
class AppContainer(
    context: Context,
) {
    // A SupervisorJob is required here, not merely tidy: network.control's accept loop, read
    // loop, keepalive and clock-sync bursts are all children of this scope, and one of them
    // throwing must never cancel its siblings (see ControlSessionManager's own tests).
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val monotonicNowUs: () -> Long = { SystemClock.elapsedRealtimeNanos() / NANOS_PER_MICRO }

    // Phase 1a: an in-memory sink is enough to prove the redaction-by-construction contract.
    // A persistent/platform sink (Logcat, ring buffer) arrives when there is a reason to keep one.
    private val logSink = InMemoryLogSink()

    private val discoveryController = NsdDiscoveryController(context)

    private val controlSessionManager = ControlSessionManager(scope = appScope, monotonicNowUs = monotonicNowUs)

    private val localIdentity =
        LocalHandshakeIdentity(
            displayName = "${Build.MANUFACTURER} ${Build.MODEL}",
            platform = "android",
            osVersion = Build.VERSION.RELEASE ?: "unknown",
            appVersion = APP_VERSION,
            connTiebreak = ConnTiebreakGenerator.generate(),
        )

    val sessionCoordinator: SessionCoordinator =
        SessionCoordinator(
            discovery = discoveryController,
            controlSessionManager = controlSessionManager,
            localIdentity = localIdentity,
            scope = appScope,
            logSink = logSink,
            monotonicNowUs = monotonicNowUs,
        )
}
