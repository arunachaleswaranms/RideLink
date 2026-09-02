package com.ridelink.app

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import com.ridelink.app.service.RideForegroundService
import com.ridelink.app.session.SessionCoordinator
import com.ridelink.app.ui.MainScreen
import com.ridelink.app.ui.SecureTransportUnavailableScreen

class MainActivity : ComponentActivity() {
    /**
     * ARCHITECTURE §6.4 step 2. Requested on an explicit **user action** — never at launch, and never
     * from a background callback: `RECORD_AUDIO` at startup would be asking for a microphone before
     * there is anything to say into it, and the platform's own rules make the foreground-visible
     * moment the only one that works anyway.
     */
    private val requestVoicePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            // FR-025 graceful degradation: a denied microphone is not a failure to abort on. Voice
            // starts anyway, `AndroidVoiceAudioSession.open()` reports the refusal, and the
            // diagnostics card shows `mic: unavailable` so the user is told why rather than left
            // wondering. A denied POST_NOTIFICATIONS costs the lock-screen surface, not the ride.
            val micGranted = granted[android.Manifest.permission.RECORD_AUDIO] == true
            if (micGranted) startRideAndVoice() else pendingVoiceStart?.invoke()
            pendingVoiceStart = null
        }

    private var pendingVoiceStart: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as RideLinkApplication).container
        val deviceDescription = "${Build.MANUFACTURER} ${Build.MODEL}"

        setContent {
            MaterialTheme {
                container.fold(
                    onSuccess = { appContainer ->
                        MainScreen(
                            coordinator = appContainer.sessionCoordinator,
                            deviceDescription = deviceDescription,
                            // Start Voice is routed through the Activity on purpose. ARCHITECTURE
                            // §6.4 steps 4–6: the microphone foreground service must be started
                            // while the app is foreground-visible, and only a resumed Activity can
                            // honestly claim that. A coordinator or a view model cannot.
                            onStartVoice = { onStartVoicePressed(appContainer.sessionCoordinator) },
                        )
                    },
                    // The only way to land here is a device-identity failure. There is deliberately
                    // no plaintext path to offer instead (ADR-007 Amendment A1).
                    onFailure = { SecureTransportUnavailableScreen(reason = it.message.orEmpty()) },
                )
            }
        }
    }

    /**
     * The legal start sequence, in order (ARCHITECTURE §6.4):
     *
     * 1. this is a resumed Activity, so we are foreground-visible;
     * 2. ask for anything missing and come back here;
     * 3. start the microphone foreground service **while still visible**;
     * 4. only then open the capture path, which `VoiceController` does next.
     *
     * Step 3 before step 4 is the whole point. There is no second legal opportunity to open a
     * microphone once the screen is locked, so the service has to exist first.
     */
    private fun onStartVoicePressed(coordinator: SessionCoordinator) {
        val missing = RideForegroundService.requiresRuntimePermissions.filterNot { it.isGranted() }
        if (missing.isNotEmpty()) {
            // Remember what to do if the user declines, so a refusal still starts voice music-only
            // rather than silently doing nothing.
            pendingVoiceStart = { coordinator.startVoice() }
            startVoice = { coordinator.startVoice() }
            requestVoicePermissions.launch(missing.toTypedArray())
            return
        }
        startVoice = { coordinator.startVoice() }
        startRideAndVoice()
    }

    private var startVoice: (() -> Unit)? = null

    private fun startRideAndVoice() {
        if (!RideForegroundService.startFromVisibleUi(this)) {
            // `ForegroundServiceStartNotAllowedException` and friends. ARCHITECTURE §6.4: caught,
            // never retried silently from the background. Voice is not started, so the capture
            // device is never opened without a service holding it.
            return
        }
        startVoice?.invoke()
        startVoice = null
    }

    private fun String.isGranted(): Boolean = checkSelfPermission(this) == PackageManager.PERMISSION_GRANTED
}
