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
import com.ridelink.core.audiopolicy.RideStartDecision

/**
 * The one place that can honestly claim "the app is foreground-visible", which is why
 * ARCHITECTURE §6.4's start sequence runs from here and not from a view model or the coordinator.
 *
 * Three lifecycle facts live here and nowhere else:
 *
 * 1. **Foreground visibility** ([foregroundVisible]) — a resumed Activity, and the precondition for a
 *    first microphone start. `RideStartPolicy` decides what to do about it; this only reports it.
 * 2. **Permission results**, requested on an explicit user action rather than at launch.
 * 3. **Backgrounding while PTT is held** — `onPause` releases the gate, because this phase's brief §25
 *    forbids leaving transmission stuck on and a composable is not told about backgrounding.
 */
class MainActivity : ComponentActivity() {
    /**
     * ARCHITECTURE §6.4 step 2. Requested on an explicit **user action** — never at launch, and never
     * from a background callback: `RECORD_AUDIO` at startup would be asking for a microphone before
     * there is anything to say into it, and the platform's own rules make the foreground-visible
     * moment the only one that works anyway.
     */
    private val requestVoicePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Whatever the user answered, re-run the readiness gate rather than assuming. A denied
            // microphone produces a named refusal the UI shows (FR-025 graceful degradation); a denied
            // POST_NOTIFICATIONS produces a warning and an allowed start.
            pendingCoordinator?.let { attemptIntercomStart(it, requestPermissionsIfMissing = false) }
            pendingCoordinator = null
        }

    private var pendingCoordinator: SessionCoordinator? = null
    private var coordinator: SessionCoordinator? = null

    /**
     * Whether this Activity is resumed. The only honest source for
     * [com.ridelink.core.audiopolicy.RideStartRequest.appForegroundVisible].
     */
    private var foregroundVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as RideLinkApplication).container
        val deviceDescription = "${Build.MANUFACTURER} ${Build.MODEL}"

        setContent {
            MaterialTheme {
                container.fold(
                    onSuccess = { appContainer ->
                        coordinator = appContainer.sessionCoordinator
                        MainScreen(
                            coordinator = appContainer.sessionCoordinator,
                            deviceDescription = deviceDescription,
                            onStartIntercom = {
                                attemptIntercomStart(appContainer.sessionCoordinator, requestPermissionsIfMissing = true)
                            },
                            onStopIntercom = { stopIntercom(appContainer.sessionCoordinator) },
                        )
                    },
                    // The only way to land here is a device-identity failure. There is deliberately
                    // no plaintext path to offer instead (ADR-007 Amendment A1).
                    onFailure = { SecureTransportUnavailableScreen(reason = it.message.orEmpty()) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        foregroundVisible = true
    }

    override fun onPause() {
        // This phase's brief §25: a PTT press outstanding when the app goes to the background must not
        // leave transmission on. Capture is deliberately **not** touched — the ride segment continues,
        // and ARCHITECTURE §6.4 gives no second chance to reopen a microphone once the screen is
        // locked, which is the whole reason the gate and the device are separate things.
        foregroundVisible = false
        coordinator?.onAppBackgrounded()
        super.onPause()
    }

    /**
     * The legal start sequence, in order (ARCHITECTURE §6.4):
     *
     * 1. this is a resumed Activity, so [foregroundVisible] is a fact rather than a hope;
     * 2. ask for anything missing and come back here;
     * 3. **decide** — `RideStartPolicy`, pure and unit-tested on both platforms;
     * 4. start the microphone foreground service **while still visible**;
     * 5. only then open the capture path, which `VoiceController` does next.
     *
     * Step 4 before step 5 is the whole point. There is no second legal opportunity to open a
     * microphone once the screen is locked, so the service has to exist first.
     */
    @Suppress("ReturnCount") // one early-out per ARCHITECTURE §6.4 step, in that order
    private fun attemptIntercomStart(
        coordinator: SessionCoordinator,
        requestPermissionsIfMissing: Boolean,
    ) {
        val missing = RideForegroundService.requiresRuntimePermissions.filterNot { it.isGranted() }
        if (requestPermissionsIfMissing && missing.isNotEmpty()) {
            pendingCoordinator = coordinator
            requestVoicePermissions.launch(missing.toTypedArray())
            return
        }

        val decision =
            coordinator.evaluateIntercomStart(
                appForegroundVisible = foregroundVisible,
                micPermissionGranted =
                    android.Manifest.permission.RECORD_AUDIO
                        .isGranted(),
                notificationsPermissionGranted = notificationsGranted(),
            )
        // A refusal is already recorded on the coordinator and rendered by the intercom card, by name.
        // Nothing is retried here, and nothing is retried silently from the background — ever.
        val allowed = decision as? RideStartDecision.Allowed ?: return

        if (allowed.startForegroundServiceWithMicrophone && !RideForegroundService.startFromVisibleUi(this)) {
            // `ForegroundServiceStartNotAllowedException` and friends. ARCHITECTURE §6.4: caught,
            // never retried silently from the background. Voice is not started, so the capture device
            // is never opened without a service holding it.
            coordinator.onForegroundServiceStartFailed()
            return
        }
        coordinator.startIntercom()
    }

    private fun stopIntercom(coordinator: SessionCoordinator) {
        // Order matters and is the reverse of the start: the intercom releases capture first, then the
        // service that existed to hold it goes. A microphone foreground service with no microphone is
        // the orphan ARCHITECTURE §6.4's failure table forbids.
        coordinator.endIntercom()
        RideForegroundService.stop(this)
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            android.Manifest.permission.POST_NOTIFICATIONS
                .isGranted()

    private fun String.isGranted(): Boolean = checkSelfPermission(this) == PackageManager.PERMISSION_GRANTED
}
