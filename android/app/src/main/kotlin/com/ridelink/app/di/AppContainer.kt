package com.ridelink.app.di

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.ridelink.app.service.RideCommand
import com.ridelink.app.service.RideCommandBus
import com.ridelink.app.service.RideForegroundService
import com.ridelink.app.session.ForegroundServiceController
import com.ridelink.app.session.SessionCoordinator
import com.ridelink.app.session.SessionEnvironment
import com.ridelink.audio.route.AndroidVoiceAudioSession
import com.ridelink.audio.route.AudioEndpointPreference
import com.ridelink.core.logging.InMemoryLogSink
import com.ridelink.core.security.TrustedPeerStore
import com.ridelink.core.security.UtcTime
import com.ridelink.data.trustedpeers.FileTrustedPeerStore
import com.ridelink.data.trustedpeers.LocalPeerIdStore
import com.ridelink.network.control.ConnTiebreakGenerator
import com.ridelink.network.control.ControlSessionManager
import com.ridelink.network.control.LocalHandshakeIdentity
import com.ridelink.network.discovery.NsdDiscoveryController
import com.ridelink.network.security.AndroidKeystoreIdentityStore
import com.ridelink.network.security.DeviceIdentity
import com.ridelink.network.security.TlsControlChannel
import com.ridelink.network.voice.StopReleaseResult
import com.ridelink.network.voice.VoiceController
import com.ridelink.network.voice.WebRtcVoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

private const val APP_VERSION = "0.1.0"
private const val VOICE_TRACK_ID = "ridelink-voice"
private const val NANOS_PER_MICRO = 1_000L
private const val MILLIS_PER_SECOND = 1_000L

/**
 * Manual constructor dependency injection (ADR-014 §2 — no DI framework in V1). This is the
 * composition root; nothing outside `app` should construct a [SessionCoordinator] directly.
 *
 * **Security wiring (Phase 1b).** Three things are assembled here and nowhere else:
 *
 * 1. the device's identity keypair and certificate, from Android Keystore ([AndroidKeystoreIdentityStore], ADR-017);
 * 2. the one production [com.ridelink.network.control.ControlChannel], which is TLS 1.3 ([TlsControlChannel], ADR-007);
 * 3. the trusted-peer store the SPKI pin is checked against ([FileTrustedPeerStore], ADR-012).
 *
 * The Phase 1a `BuildConfig.DEBUG` plaintext gate is **gone**, replaced by something stronger:
 * the plaintext transport no longer exists in any production source set, so there is nothing left
 * to gate. [requireSecureControlChannel] is the residual runtime assertion, and
 * `network`'s `PlaintextTransportAbsenceTest` is the mechanical guard that keeps it that way.
 */
class AppContainer(
    private val context: Context,
) {
    // A SupervisorJob is required here, not merely tidy: network.control's accept loop, read
    // loop, keepalive and clock-sync bursts are all children of this scope, and one of them
    // throwing must never cancel its siblings (see ControlSessionManager's own tests).
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val monotonicNowUs: () -> Long = { SystemClock.elapsedRealtimeNanos() / NANOS_PER_MICRO }

    /**
     * The one wall-clock read in the app, and only X.509 uses it. Certificate validity is defined
     * in wall-clock terms by RFC 5280 and has no monotonic alternative — see
     * [com.ridelink.core.security.UtcTime] for why this is the single permitted exception to
     * CLAUDE.md's monotonic-clocks rule.
     */
    private val wallClockNow: () -> UtcTime = { UtcTime(System.currentTimeMillis() / MILLIS_PER_SECOND) }

    // Phase 1b: an in-memory sink is enough to prove the redaction-by-construction contract.
    // A persistent/platform sink (Logcat, ring buffer) arrives when there is a reason to keep one.
    private val logSink = InMemoryLogSink()

    private val securityDirectory: File = File(context.filesDir, "security").apply { mkdirs() }

    private val deviceIdentity: DeviceIdentity =
        AndroidKeystoreIdentityStore().loadOrCreate(wallClockNow())

    val trustedPeers: TrustedPeerStore = FileTrustedPeerStore(File(securityDirectory, "trusted_peers.json"))

    private val localPeerId = LocalPeerIdStore(File(securityDirectory, "peer_id")).loadOrCreate()

    private val controlChannel = TlsControlChannel(deviceIdentity)

    private val localIdentity =
        LocalHandshakeIdentity(
            displayName = "${Build.MANUFACTURER} ${Build.MODEL}",
            platform = "android",
            osVersion = Build.VERSION.RELEASE ?: "unknown",
            appVersion = APP_VERSION,
            connTiebreak = ConnTiebreakGenerator.generate(),
            identitySpkiSha256 = deviceIdentity.identitySpkiSha256,
        )

    /**
     * Hoisted out of the coordinator's construction because the voice subsystem needs *this* instance
     * — `voice.send` writes to the surviving authenticated connection, and a second manager
     * would have no connection at all (PROTOCOL §7.1).
     */
    private val controlSessionManager =
        ControlSessionManager(
            scope = appScope,
            monotonicNowUs = monotonicNowUs,
            localPeerId = localPeerId,
            channel = controlChannel,
            trustedPeers = trustedPeers,
        )

    /**
     * **One `AndroidVoiceAudioSession` for the whole process, and that is the point.**
     *
     * ADR-021 §2 makes app-level capture and audio-session lifecycle the responsibility of exactly one
     * object. Constructing a fresh one per voice session would give two things that both believe they
     * own `AudioManager`'s mode, focus and communication device across a reconnect — and the whole
     * reason `VoiceEngine.stop()` and `release()` are separate calls is that the audio session must
     * survive a control-plane blip (ARCHITECTURE §6.2/§6.3). It is also what lets the readiness gate
     * ask whether an endpoint exists *before* an intercom has ever been started.
     *
     * `AUTO_PREFER_BLUETOOTH` because starting the intercom for a ride **is** the explicit intent to
     * use the helmet unit (this phase's brief §9). Nothing scans, pairs or connects a device.
     */
    private val voiceAudioSession =
        AndroidVoiceAudioSession(
            context = context,
            endpointPreference = AudioEndpointPreference.AUTO_PREFER_BLUETOOTH,
            monotonicNowUs = monotonicNowUs,
        )

    val sessionCoordinator: SessionCoordinator

    init {
        requireSecureControlChannel(controlChannel.isSecure, controlChannel.transportLabel)
        sessionCoordinator =
            SessionCoordinator(
                discovery = NsdDiscoveryController(context),
                controlSessionManager = controlSessionManager,
                localIdentity = localIdentity,
                scope = appScope,
                logSink = logSink,
                trustedPeers = trustedPeers,
                environment =
                    SessionEnvironment(
                        monotonicNowUs = monotonicNowUs,
                        nowEpochSeconds = { System.currentTimeMillis() / MILLIS_PER_SECOND },
                        audioEndpointPresent = { voiceAudioSession.hasUsableEndpoint },
                    ),
                // problem 32: the one place `RideForegroundService.stop` is reachable from the FSM's
                // own `ENDING` effect, so a peer BYE (or any other legitimate ENDING path) cannot
                // leave the service orphaned the way `MainActivity`'s in-app button alone could not
                // reach.
                foregroundService = ForegroundServiceController { RideForegroundService.stop(context) },
                buildVoiceController = ::voiceController,
            )
        installRideNotificationCommands()
    }

    /**
     * Wires the ride notification's two actions — the lock-screen control surface
     * (ARCHITECTURE §6.4) — to the one object that owns session state.
     *
     * A direct dispatch with no queue (`RideCommandBus`), because a notification tap with no session
     * to act on should be dropped rather than buffered. `END_INTERCOM` deliberately ends the
     * *intercom*, never the control session: PROTOCOL §7.8 keeps those separate, and "End Voice" is
     * not "Forget peer".
     *
     * **`END_INTERCOM` owns stopping the foreground service, not `RideForegroundService` itself**
     * (this phase's hardening pass, Issue F). `RideCommandBus.dispatch` is a synchronous call, but
     * releasing capture is not — `endIntercomAndAwaitRelease` suspends until
     * `engine.release()`/`audioSession.close()` have actually completed — so this handler launches on
     * [appScope] and calls [RideForegroundService.stop] only once that awaited call returns. This is
     * the one place a lock-screen End Intercom tap and the in-app End button both funnel through, so
     * fixing it here covers both entry points identically.
     */
    private fun installRideNotificationCommands() {
        RideCommandBus.handler = { command ->
            when (command) {
                RideCommand.TOGGLE_MUTE -> {
                    val muted = !sessionCoordinator.voiceDiagnostics.value.userMuted
                    sessionCoordinator.setMicrophoneMuted(muted)
                    RideForegroundService.updateMuteState(context, muted)
                }
                RideCommand.END_INTERCOM ->
                    appScope.launch {
                        // This phase's final hardening pass (Issue 2): a timed-out release must not be
                        // treated as proof the microphone is safe to reclaim — the stop is skipped, and
                        // the diagnostics card already shows the stalled route transition to explain why.
                        when (sessionCoordinator.endIntercomAndAwaitRelease()) {
                            StopReleaseResult.Released, StopReleaseResult.AlreadyReleased -> RideForegroundService.stop(context)
                            StopReleaseResult.TimedOut -> Unit
                        }
                    }
            }
        }
    }

    /**
     * Phase 2a's voice subsystem, built **per authenticated session** by `SessionCoordinator` and
     * nowhere else — which is what makes "one `VoiceController` per two-person session" a structural
     * property rather than a convention (ADR-020).
     *
     * `isLocalLeader` comes from `HELLO_ACK.leader_peer_id`, so the offerer role is ADR-010
     * leadership and nothing else (PROTOCOL §7.3). It is deliberately *not* derived from which side
     * dialled the TCP connection.
     */
    private fun voiceController(isLocalLeader: Boolean): VoiceController =
        VoiceController(
            scope = appScope,
            engine = WebRtcVoiceEngine(context),
            audioSession = voiceAudioSession,
            transport = controlSessionManager.voice,
            isLocalLeader = isLocalLeader,
            // One audio track per peer (ADR-003). A fixed, non-identifying id: a track id crosses the
            // wire inside the SDP, so it must not carry a device name.
            localTrackId = VOICE_TRACK_ID,
            monotonicNowUs = monotonicNowUs,
        )
}
