package com.ridelink.app.di

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.ridelink.app.BuildConfig
import com.ridelink.app.session.SessionCoordinator
import com.ridelink.core.logging.InMemoryLogSink
import com.ridelink.network.control.ConnTiebreakGenerator
import com.ridelink.network.control.ControlSessionManager
import com.ridelink.network.control.LocalHandshakeIdentity
import com.ridelink.network.discovery.NsdDiscoveryController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private const val APP_VERSION = "0.1.0"
private const val NANOS_PER_MICRO = 1_000L

/**
 * Manual constructor dependency injection (ADR-014 §2 — no DI framework in V1). This is the
 * composition root; nothing outside `app` should construct a [SessionCoordinator] directly.
 *
 * **Release-transport guard (this session's brief §4).** `PlainControlTransportPhase1a` is
 * plaintext and debug/development only — PROTOCOL §1's TLS 1.3 requirement is Phase 1b's job, not
 * implemented yet. [plaintextTransportAllowed] defaults to `BuildConfig.DEBUG` and is the single
 * point that decides whether the plaintext transport may exist at all. This is enforced by
 * construction, not a warning: [NsdDiscoveryController] and [ControlSessionManager] — the two
 * types capable of putting bytes on a real socket — are never *instantiated* when it is `false`,
 * so [sessionCoordinator] is `null` and the UI must show a "secure transport not implemented"
 * state instead of a working screen. `core` and `network` do not depend on `BuildConfig` — the
 * decision is made once, here, at the composition root, exactly as CLAUDE.md's module boundaries
 * require.
 */
class AppContainer(
    context: Context,
    private val plaintextTransportAllowed: Boolean = BuildConfig.DEBUG,
) {
    // A SupervisorJob is required here, not merely tidy: network.control's accept loop, read
    // loop, keepalive and clock-sync bursts are all children of this scope, and one of them
    // throwing must never cancel its siblings (see ControlSessionManager's own tests).
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val monotonicNowUs: () -> Long = { SystemClock.elapsedRealtimeNanos() / NANOS_PER_MICRO }

    // Phase 1a: an in-memory sink is enough to prove the redaction-by-construction contract.
    // A persistent/platform sink (Logcat, ring buffer) arrives when there is a reason to keep one.
    private val logSink = InMemoryLogSink()

    private val localIdentity =
        LocalHandshakeIdentity(
            displayName = "${Build.MANUFACTURER} ${Build.MODEL}",
            platform = "android",
            osVersion = Build.VERSION.RELEASE ?: "unknown",
            appVersion = APP_VERSION,
            connTiebreak = ConnTiebreakGenerator.generate(),
        )

    /** `null` in a release build — see the class doc comment. Never constructed, not just unused. */
    val sessionCoordinator: SessionCoordinator? =
        gatedByPlaintextTransport(plaintextTransportAllowed) {
            SessionCoordinator(
                discovery = NsdDiscoveryController(context),
                controlSessionManager = ControlSessionManager(scope = appScope, monotonicNowUs = monotonicNowUs),
                localIdentity = localIdentity,
                scope = appScope,
                logSink = logSink,
                monotonicNowUs = monotonicNowUs,
            )
        }
}
