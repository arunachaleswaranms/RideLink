package com.ridelink.app

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.lifecycleScope
import com.ridelink.app.library.SharedLibraryCoordinator
import com.ridelink.app.music.MusicCoordinator
import com.ridelink.app.service.RideForegroundService
import com.ridelink.app.session.SessionCoordinator
import com.ridelink.app.ui.MainScreen
import com.ridelink.app.ui.SecureTransportUnavailableScreen
import com.ridelink.core.audiopolicy.RideStartDecision
import com.ridelink.core.library.LibraryEntry
import com.ridelink.core.manifest.ManifestEntry
import com.ridelink.network.voice.StopReleaseResult
import kotlinx.coroutines.launch

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
     * `ACTION_OPEN_DOCUMENT_TREE` — ARCHITECTURE §8.4's primary Android import path. No runtime
     * permission involved; the grant is the picker result itself (this phase's brief §10).
     */
    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { musicCoordinator?.importTree(it) }
        }

    /** `ACTION_OPEN_DOCUMENT` multi-select — brief §10's explicit "multiple files" import path. */
    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) musicCoordinator?.importFiles(uris)
        }

    private var musicCoordinator: MusicCoordinator? = null

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
                        musicCoordinator = appContainer.musicCoordinator
                        MainScreen(
                            coordinator = appContainer.sessionCoordinator,
                            musicCoordinator = appContainer.musicCoordinator,
                            sharedLibraryCoordinator = appContainer.sharedLibraryCoordinator,
                            deviceDescription = deviceDescription,
                            onStartIntercom = {
                                attemptIntercomStart(appContainer.sessionCoordinator, requestPermissionsIfMissing = true)
                            },
                            onStopIntercom = { stopIntercom(appContainer.sessionCoordinator) },
                            onPlayMusic = { attemptMusicPlay(appContainer.musicCoordinator) },
                            onPlayNow = { entry -> attemptPlayNow(appContainer.musicCoordinator, entry) },
                            onImportFolder = { pickFolder.launch(null) },
                            onImportFiles = { pickFiles.launch(arrayOf("audio/*")) },
                            onPlaySharedTrackLocally = { entry ->
                                attemptPlaySharedTrackLocally(
                                    appContainer.musicCoordinator,
                                    appContainer.sharedLibraryCoordinator,
                                    entry,
                                )
                            },
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

    /**
     * Order matters and is the reverse of the start: the intercom releases capture first, then the
     * service that existed to hold it goes. A microphone foreground service with no microphone is the
     * orphan ARCHITECTURE §6.4's failure table forbids.
     *
     * This phase's hardening pass (Issue F): `endIntercom()` alone only *queues* the stop — the actual
     * `engine.release()`/`audioSession.close()` runs later, on the controller's own consumer. Calling
     * [RideForegroundService.stopIntercom] right after it, as before, could let the platform reclaim
     * the service while it still held the microphone. [SessionCoordinator.endIntercomAndAwaitRelease]
     * suspends until release has actually happened (or until there was nothing to release), so the
     * service is told the intercom ended only once that is true. Phase 3: this drops only the
     * `microphone` type — a music track still playing keeps the service (and `mediaPlayback`) alive.
     *
     * This phase's **final** hardening pass (Issue 2): the awaited result is now explicit
     * ([com.ridelink.network.voice.StopReleaseResult]), and a timeout is never treated as release —
     * leaving the service running on a stalled release is the safer failure than telling the
     * platform a microphone still open is safe to reclaim. The diagnostics card already shows the
     * stuck route transition; nothing here retries automatically, since a silent retry from this
     * path is exactly what ARCHITECTURE §6.4 forbids for a start and this phase's brief §9
     * (`stopAndAwaitRelease` is failure protection, never a success it invents) forbids for an end.
     */
    private fun stopIntercom(coordinator: SessionCoordinator) {
        lifecycleScope.launch {
            when (coordinator.endIntercomAndAwaitRelease()) {
                StopReleaseResult.Released, StopReleaseResult.AlreadyReleased -> RideForegroundService.stopIntercom(this@MainActivity)
                StopReleaseResult.TimedOut -> Unit
            }
        }
    }

    /**
     * The music mirror of [attemptIntercomStart]'s foreground-visible discipline (this phase's
     * brief §16): held to the same rule as the intercom's first start, even though music itself
     * needs no runtime permission and no readiness gate — CLAUDE.md's "Ride Mode starts only from a
     * visible app" is about foreground-service starts in general, and a single consistent rule is
     * simpler to reason about than a second, looser one that exists only for music.
     *
     * [RideForegroundService.updateMusicPlaying] alone (fired reactively by `AppContainer` as
     * playback state changes) is a safe no-op while the service is not yet running, by
     * construction, so the **first** start for music alone has to happen here, exactly once,
     * before actually calling play.
     *
     * This phase's closure-audit hardening pass (Finding E): [RideForegroundService.startMusicFromVisibleUi]
     * returns `false` when the platform refused the start (`ForegroundServiceStartNotAllowedException`
     * and friends, caught inside it) — that return value used to be discarded, so playback proceeded
     * as though background ownership were established even when Android rejected it. Never retried
     * silently from here; [MusicCoordinator.onForegroundServiceStartFailed] records the refusal for
     * the UI, mirroring [attemptIntercomStart]'s existing discipline for its own foreground-service
     * start.
     */
    private fun attemptMusicPlay(musicCoordinator: MusicCoordinator) {
        if (!foregroundVisible) return
        if (!RideForegroundService.startMusicFromVisibleUi(this)) {
            musicCoordinator.onForegroundServiceStartFailed()
            return
        }
        musicCoordinator.play()
    }

    /**
     * [attemptMusicPlay]'s twin for the library screen's "tap a row to play it now" affordance — a
     * real gap found on the emulator, not a hypothetical: [com.ridelink.app.ui.LibraryScreen]'s row
     * tap used to call [MusicCoordinator.playNow] directly, which starts real playback without ever
     * calling [RideForegroundService.startMusicFromVisibleUi] first. `AppContainer`'s reactive
     * `isMusicActive` observer still brought the foreground service up behind that gate's back (via
     * [RideForegroundService.updateMusicPlaying]'s own `startService` call), so the failure was not
     * "music didn't play" but a service started from a path this rule was specifically written to
     * prevent — see [attemptMusicPlay]'s KDoc and ARCHITECTURE §6.4.
     */
    private fun attemptPlayNow(
        musicCoordinator: MusicCoordinator,
        entry: LibraryEntry,
    ) {
        if (!foregroundVisible) return
        if (!RideForegroundService.startMusicFromVisibleUi(this)) {
            musicCoordinator.onForegroundServiceStartFailed()
            return
        }
        musicCoordinator.playNow(entry)
    }

    /**
     * [attemptPlayNow]'s twin for the shared-library screen's "Play" affordance (brief §19). Two
     * cases, per closure-audit Finding G:
     *
     * 1. A Phase 3 imported [LibraryEntry] already shares this [ManifestEntry]'s `content_hash` —
     *    play it exactly like a local library row, unchanged from before this pass.
     * 2. Otherwise, the track exists only as a verified Phase-4 cache entry (never imported) —
     *    [SharedLibraryCoordinator.cachedFile] hands back its on-disk location, and
     *    [MusicCoordinator.playExternalVerifiedCachedTrack] plays it through the *same* one
     *    player/queue, held to the identical foreground-visible discipline as every other first
     *    play of a track (ARCHITECTURE §6.4). There is still no second, cache-file-only player.
     */
    @Suppress("ReturnCount") // one early-out per case in Finding G's KDoc, in that order
    private fun attemptPlaySharedTrackLocally(
        musicCoordinator: MusicCoordinator,
        sharedLibraryCoordinator: SharedLibraryCoordinator,
        entry: ManifestEntry,
    ) {
        val hash = entry.contentHash ?: return
        val localEntry = musicCoordinator.libraryEntries.value.find { it.track.contentHash == hash }
        if (localEntry != null) {
            attemptPlayNow(musicCoordinator, localEntry)
            return
        }
        if (!foregroundVisible) return
        lifecycleScope.launch {
            val file = sharedLibraryCoordinator.cachedFile(hash) ?: return@launch
            if (!RideForegroundService.startMusicFromVisibleUi(this@MainActivity)) {
                musicCoordinator.onForegroundServiceStartFailed()
                return@launch
            }
            musicCoordinator.playExternalVerifiedCachedTrack(hash, file, entry.title, entry.artist)
        }
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            android.Manifest.permission.POST_NOTIFICATIONS
                .isGranted()

    private fun String.isGranted(): Boolean = checkSelfPermission(this) == PackageManager.PERMISSION_GRANTED
}
