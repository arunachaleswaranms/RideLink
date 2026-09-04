package com.ridelink.audio.route

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.ridelink.core.audiopolicy.AudioRouteChangeReason
import com.ridelink.core.audiopolicy.AudioRouteSnapshot
import com.ridelink.core.audiopolicy.AudioSessionAction
import com.ridelink.core.audiopolicy.AudioSessionEvent
import com.ridelink.core.audiopolicy.AudioSessionLifecycle
import com.ridelink.core.audiopolicy.AudioSessionState
import com.ridelink.core.audiopolicy.RouteState
import com.ridelink.core.audiopolicy.RouteTransitionTracker
import com.ridelink.core.audiopolicy.VoiceFailure
import com.ridelink.core.voice.VoiceAudioSession
import com.ridelink.core.voice.VoiceAudioSessionFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Android half of the audio route: `AudioManager` focus, communication mode and device selection,
 * mapped into ADR-016's platform-neutral vocabulary by [AndroidAudioRouteMapper].
 *
 * Deliberately **not** the microphone itself. WebRTC's `JavaAudioDeviceModule` owns `AudioRecord` and
 * `AudioTrack` (ADR-003), so what this class owns is the *session*: audio focus,
 * `MODE_IN_COMMUNICATION`, and which device the communication route points at. The split matters for a
 * specific reason — the thing that thrashes a Bluetooth endpoint between its media and duplex profiles
 * is this session changing, and it must therefore survive a control-plane blip (`VoiceEngine.stop`
 * versus `release`).
 *
 * ### What Phase 2b added, and why it is mostly not here
 *
 * **Every decision this class used to make now lives in `AudioSessionLifecycle`**, the pure reducer
 * shared with iOS: the `stable -> transitioning -> stable` sequence, the strict generation guard against
 * a stale platform callback, and what an interruption does. That is deliberate and is the direct lesson
 * of ADR-019 and of `docs/STATUS.md` §4 problem 20 — `AudioManager` cannot be executed off-device, so
 * anything with a *decision* in it has to be somewhere a laptop test can reach. What is left here is
 * API calls.
 *
 * **The route transition settles on a platform callback, never on a timer.** `AudioManager`'s
 * [AudioManager.addOnCommunicationDeviceChangedListener] (API 31+, exactly the ADR-011 `minSdk`) is
 * what confirms the change RideLink asked for; [pollTransitionTimeout] exists only so a platform that
 * never confirms cannot leave `route_state: transitioning` latched for the rest of a ride, and every
 * use of it is counted (`RouteTransitionState.timedOutCount`).
 *
 * API level: [AudioManager.setCommunicationDevice] and
 * [AudioManager.getAvailableCommunicationDevices] are API 31+. That is one of the reasons the ADR-011
 * baseline was chosen: below it, routing a helmet unit means the deprecated `startBluetoothSco()`
 * sequence, and no such path exists in this file.
 *
 * **Nothing here has run on a phone.** `AudioManager` cannot be exercised by a JVM unit test and no
 * device or emulator is available in this environment. [AndroidAudioRouteMapper],
 * [AndroidCommunicationDeviceSelector] and `AudioSessionLifecycle` are pure and are unit-tested;
 * everything in this class is **REAL-DEVICE AUDIO GATE PENDING** (docs/STATUS.md §7, TEST_PLAN V-01…V-11
 * and A-12…A-15).
 */
class AndroidVoiceAudioSession(
    private val context: Context,
    /**
     * Where the intercom should route. Supplied by the composition root rather than assumed here, so
     * the endpoint comes from explicit intent (this phase's brief §9).
     */
    private val endpointPreference: AudioEndpointPreference = AudioEndpointPreference.PLATFORM_DEFAULT,
    /** Monotonic microseconds, for the IA-03 transition measurement. Never a wall clock (rule 5). */
    private val monotonicNowUs: () -> Long = { android.os.SystemClock.elapsedRealtimeNanos() / NANOS_PER_MICRO },
    /**
     * Where [scheduleTransitionTimeout] runs its one-shot delay. A `SupervisorJob` of its own by
     * default: this session is a process-lifetime singleton (ADR-021 §2), so its timeout work must
     * outlive any one voice session rather than being cancelled when a caller's own scope is.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : VoiceAudioSession {
    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val lock = Any()
    private var focusRequest: AudioFocusRequest? = null
    private var previousMode: Int = AudioManager.MODE_NORMAL
    private var sink: ((AudioRouteSnapshot) -> Unit)? = null

    /** The shared lifecycle state. Read on callbacks, written only under [lock]. */
    @Volatile
    private var lifecycle = AudioSessionState()

    @Volatile
    private var snapshot = AudioRouteSnapshot()

    override val isOpen: Boolean get() = lifecycle.open

    override val route: AudioRouteSnapshot get() = snapshot

    /** The last named reason this session refused or was interrupted (this phase's brief §41). */
    val lastFailure: VoiceFailure? get() = lifecycle.lastFailure

    /**
     * Whether the platform lists any endpoint usable for communication. Feeds the readiness gate's
     * `audioEndpointPresent`, so "nothing to speak into" is a named refusal rather than a silent start.
     */
    val hasUsableEndpoint: Boolean
        get() =
            runCatching {
                AndroidCommunicationDeviceSelector.hasUsableEndpoint(
                    audioManager.availableCommunicationDevices.map { it.type },
                )
            }.getOrDefault(false)

    override fun setRouteSink(sink: (AudioRouteSnapshot) -> Unit) {
        this.sink = sink
    }

    /**
     * Must be called while the app is foreground-visible (ARCHITECTURE §6.4 step 6).
     *
     * Returns a failure rather than throwing, and never proceeds *pretending* to have a microphone: a
     * denied `RECORD_AUDIO` means the ride continues music-only with an amber status, which is FR-025's
     * graceful degradation, not an exception to swallow. Every refusal is named
     * ([VoiceAudioSessionFailure]) rather than collapsed into one bucket.
     */
    @Suppress("ReturnCount") // one early-out per named failure, in the order §6.4 checks them
    override suspend fun open(): Result<Unit> =
        withContext(Dispatchers.Main.immediate) {
            if (lifecycle.open) return@withContext Result.success(Unit)
            if (!hasRecordAudioPermission()) return@withContext fail(VoiceFailure.MIC_PERMISSION_DENIED)
            if (!hasUsableEndpoint) return@withContext fail(VoiceFailure.NO_AUDIO_ENDPOINT)

            // Registered *before* anything that could provoke the callbacks below confirm — never
            // after. `setCommunicationDevice` can fire `OnCommunicationDeviceChangedListener`
            // synchronously to very shortly after the call returns, and a listener added afterward
            // would miss the very confirmation it exists to observe (this phase's hardening pass,
            // Issue D).
            runCatching { audioManager.registerAudioDeviceCallback(deviceCallback, null) }
            runCatching { audioManager.addOnCommunicationDeviceChangedListener(context.mainExecutor, deviceChangedListener) }

            // The transition begins here — before either platform call below, not when `Opened`
            // confirms success — so a confirming callback either call can produce synchronously (or
            // very shortly after it returns) finds a transition already begun to settle, rather than
            // one that has not started yet (this phase's final hardening pass, Issue 1):
            // `RouteTransitionTracker.settle` silently drops a confirmation that arrives before
            // `begin` has ever run.
            apply(AudioSessionEvent.OpenRequested(lifecycle.generation, monotonicNowUs()))

            val focused = runCatching { requestFocusAndCommunicationMode() }
            if (focused.isFailure) {
                unregisterPlatformCallbacks()
                apply(
                    AudioSessionEvent.OpenAborted(
                        lifecycle.generation,
                        monotonicNowUs(),
                        VoiceFailure.AUDIO_SESSION_ACTIVATION_FAILED,
                    ),
                )
                return@withContext Result.failure(VoiceAudioSessionFailure(VoiceFailure.AUDIO_SESSION_ACTIVATION_FAILED))
            }

            val routed = runCatching { selectCommunicationDevice() }
            if (routed.isFailure) {
                // The session is up but pointing at the wrong endpoint. Named distinctly from an
                // activation failure because the user action is different: reconnect the helmet unit,
                // not restart the app.
                releasePlatformSession()
                apply(AudioSessionEvent.OpenAborted(lifecycle.generation, monotonicNowUs(), VoiceFailure.ROUTE_SELECTION_FAILED))
                return@withContext Result.failure(VoiceAudioSessionFailure(VoiceFailure.ROUTE_SELECTION_FAILED))
            }

            apply(AudioSessionEvent.Opened(lifecycle.generation, monotonicNowUs()))
            Result.success(Unit)
        }

    override suspend fun close() {
        withContext(Dispatchers.Main.immediate) {
            if (!lifecycle.open) return@withContext
            // Begun before `releasePlatformSession()`'s platform calls, for the same reason as
            // `open()` above — `clearCommunicationDevice()` can synchronously confirm the very
            // change this transition exists to track (this phase's final hardening pass, Issue 1).
            apply(AudioSessionEvent.CloseRequested(lifecycle.generation, monotonicNowUs()))
            // The callbacks that could confirm this restoring change stay registered through the calls
            // that request it — `releasePlatformSession` unregisters them only as its **last** step
            // (this phase's hardening pass, Issue D) — so a `.categoryChange`-equivalent confirmation
            // has a chance to be observed before nothing is listening for it any more.
            releasePlatformSession()
            apply(AudioSessionEvent.Closed(lifecycle.generation, monotonicNowUs()))
        }
    }

    /**
     * **Failure protection, never the definition of success** (this phase's brief §15). Scheduled
     * automatically by [manageTransitionTimeout] after every transition-starting event, in addition to
     * remaining callable directly (tests, or a caller that wants to force a check). If the platform
     * never confirmed the change, the transition is declared settled and *counted as a timeout* so the
     * diagnostics can say the number came from a timer rather than from `AudioManager`.
     */
    suspend fun pollTransitionTimeout() {
        withContext(Dispatchers.Main.immediate) {
            apply(AudioSessionEvent.TransitionTimeoutCheck(lifecycle.generation, monotonicNowUs()))
        }
    }

    private fun requestFocusAndCommunicationMode() {
        previousMode = audioManager.mode
        val request =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                // ARCHITECTURE §6.1: false so *we* control ducking with our own 150–250 ms ramp
                // rather than letting the platform pause us.
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(::onFocusChange)
                .build()
        focusRequest = request
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            error("audio focus denied")
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    private fun releasePlatformSession() {
        runCatching { audioManager.clearCommunicationDevice() }
        runCatching { audioManager.mode = previousMode }
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focusRequest = null
        // Unregistered last, once every call that could still provoke a confirming callback has
        // already been made — removing them earlier would mean the very confirmation this generation's
        // closing transition is waiting for can never arrive (this phase's hardening pass, Issue D).
        unregisterPlatformCallbacks()
    }

    private fun unregisterPlatformCallbacks() {
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        runCatching { audioManager.removeOnCommunicationDeviceChangedListener(deviceChangedListener) }
    }

    /**
     * Picks the communication device from an explicit [endpointPreference], among devices the platform
     * lists as *available for communication*, and only while entering a voice session — which is an
     * explicit user action. The choice itself is [AndroidCommunicationDeviceSelector]'s, so it is
     * unit-tested; this is the call.
     *
     * Nothing here scans, pairs or connects a Bluetooth device.
     */
    private fun selectCommunicationDevice() {
        val available = audioManager.availableCommunicationDevices
        val chosenType = AndroidCommunicationDeviceSelector.select(available.map { it.type }, endpointPreference)
        val preferred = chosenType?.let { type -> available.firstOrNull { it.type == type } } ?: return
        if (!audioManager.setCommunicationDevice(preferred)) error("setCommunicationDevice refused")
    }

    private fun onFocusChange(change: Int) {
        // Focus loss is a platform interruption — a call, Siri's equivalent, another app taking the
        // session. ADR-016 keeps that in the *route* report, not in VOICE_STATE (PROTOCOL §7.4), and
        // the reducer is what decides what it means.
        val began =
            change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        val generation = lifecycle.generation
        if (began) {
            apply(AudioSessionEvent.InterruptionBegan(generation, monotonicNowUs()))
        } else {
            // `AUDIOFOCUS_GAIN` after a *transient* loss is the platform handing the session back,
            // which is exactly iOS's `.shouldResume`. A permanent loss never produces a gain, so this
            // branch cannot mistake one for the other.
            apply(AudioSessionEvent.InterruptionEnded(generation, shouldResume = true, atMonoUs = monotonicNowUs()))
        }
    }

    private val deviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                // A device appearing is not the confirmation of a change *we* asked for, so it begins a
                // transition of its own rather than settling one (TEST_PLAN IA-05's Android analogue).
                routeChanged(AudioRouteChangeReason.NEW_DEVICE_AVAILABLE, settles = false)
            }

            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                routeChanged(AudioRouteChangeReason.OLD_DEVICE_UNAVAILABLE, settles = false)
            }
        }

    /**
     * The platform confirming that the communication device is now what we asked for. **This is what
     * settles a transition** — not elapsed time, and not an assumption.
     */
    private val deviceChangedListener =
        AudioManager.OnCommunicationDeviceChangedListener {
            routeChanged(AudioRouteChangeReason.CATEGORY_CHANGE, settles = true)
        }

    private fun routeChanged(
        reason: AudioRouteChangeReason,
        settles: Boolean,
    ) {
        apply(AudioSessionEvent.RouteChanged(lifecycle.generation, reason, monotonicNowUs(), settles))
    }

    /**
     * Drives the shared reducer and performs what it returns.
     *
     * The generation check is inside the reducer, not here, which is the point: a platform callback
     * registered under a superseded generation is inert by comparison rather than by hoping the timing
     * lines up (ADR-020 Amendment A2's rule, applied to the audio session).
     */
    private fun apply(event: AudioSessionEvent) {
        val previousStartedAtUs: Long?
        val outcome =
            synchronized(lock) {
                previousStartedAtUs = lifecycle.transition.startedAtMonoUs
                AudioSessionLifecycle.reduce(lifecycle, event).also { lifecycle = it.state }
            }
        val reason = (event as? AudioSessionEvent.RouteChanged)?.reason ?: lastReason
        lastReason = reason
        for (action in outcome.actions) {
            when (action) {
                is AudioSessionAction.PublishSnapshot -> publish(action.routeState, reason)
                AudioSessionAction.Reactivate -> runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
                // Android has no `mediaServicesWereReset` equivalent, so this is unreachable here and
                // is left as an explicit no-op rather than a silent `else` — the reducer is shared, and
                // a future Android path that needs it should have to look at this line.
                AudioSessionAction.RebuildAfterReset -> Unit
                is AudioSessionAction.ReportFailure -> Unit // recorded in `lifecycle.lastFailure`
            }
        }
        manageTransitionTimeout(previousStartedAtUs)
    }

    @Volatile
    private var lastReason: AudioRouteChangeReason = AudioRouteChangeReason.UNKNOWN

    /**
     * One timeout job per active transition (this phase's hardening pass, Issue E: [pollTransitionTimeout]
     * previously had no caller anywhere in the app, so a platform that never confirmed a change left
     * `route_state: transitioning` latched for the rest of a ride). Guarded by [lock] alongside
     * [lifecycle], since it is read and replaced from both [apply] (Main-immediate) and its own delayed
     * coroutine body.
     */
    private var transitionTimeoutJob: Job? = null

    /**
     * Arms or disarms the failure-protection timeout for whatever transition [apply] just produced.
     *
     * Compares `startedAtMonoUs` rather than the bare `transitioning` flag: a burst of route callbacks
     * within the *same* transition (`RouteTransitionTracker.begin` deliberately keeps the original start
     * instant) must not re-arm a fresh window — but Android has no reset-driven generation bump to worry
     * about (the `RebuildAfterReset` action above is unreachable here), so in practice this only ever
     * arms once per open/close transition and cancels once it settles.
     */
    private fun manageTransitionTimeout(previousStartedAtUs: Long?) {
        val transition = lifecycle.transition
        if (transition.transitioning) {
            if (transition.startedAtMonoUs != previousStartedAtUs) {
                scheduleTransitionTimeout(lifecycle.generation)
            }
        } else {
            cancelTransitionTimeout()
        }
    }

    private fun cancelTransitionTimeout() {
        synchronized(lock) {
            transitionTimeoutJob?.cancel()
            transitionTimeoutJob = null
        }
    }

    /**
     * `generation` is captured here, at schedule time — never re-read when the delay elapses — so a
     * generation that has since moved on renders this job inert via the reducer's own guard rather than
     * by hoping cancellation raced correctly (mirrors the iOS half of this hardening pass exactly).
     */
    private fun scheduleTransitionTimeout(generation: Int) {
        cancelTransitionTimeout()
        val job =
            scope.launch {
                delay(TRANSITION_TIMEOUT_MS)
                withContext(Dispatchers.Main.immediate) {
                    apply(AudioSessionEvent.TransitionTimeoutCheck(generation, monotonicNowUs()))
                }
            }
        synchronized(lock) { transitionTimeoutJob = job }
    }

    /**
     * Recomputes the snapshot from the platform's current view and hands it to the sink.
     *
     * Every field but [AudioRouteSnapshot.interrupted], [AudioRouteSnapshot.routeState] and
     * [AudioRouteSnapshot.lastTransitionDurationUs] comes from [AndroidAudioRouteMapper], which is the
     * one place an Android device type becomes ADR-016 vocabulary (PROTOCOL §4.3.1).
     */
    private fun publish(
        routeState: RouteState,
        reason: AudioRouteChangeReason,
    ) {
        val communicationDevice = runCatching { audioManager.communicationDevice }.getOrNull()
        val inCommunicationMode = runCatching { audioManager.mode == AudioManager.MODE_IN_COMMUNICATION }.getOrDefault(false)
        val mapped =
            AndroidAudioRouteMapper.map(
                deviceType = communicationDevice?.type,
                microphoneOpen = lifecycle.open,
                inCommunicationMode = inCommunicationMode,
                sampleRateHz = platformOutputSampleRateHz(),
                lastChangeReason = reason,
                routeState = routeState,
            )
        val next =
            mapped.copy(
                interrupted = lifecycle.interrupted,
                lastTransitionDurationUs = lifecycle.transition.lastDurationUs,
            )
        snapshot = next
        sink?.invoke(next)
    }

    private fun platformOutputSampleRateHz(): Int? =
        runCatching { audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() }.getOrNull()

    private fun fail(failure: VoiceFailure): Result<Unit> {
        apply(AudioSessionEvent.Failed(lifecycle.generation, failure))
        return Result.failure(VoiceAudioSessionFailure(failure))
    }

    private fun hasRecordAudioPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val NANOS_PER_MICRO = 1_000L

        /** Milliseconds form of `RouteTransitionTracker.DEFAULT_TIMEOUT_US` — one shared window, not two. */
        const val TRANSITION_TIMEOUT_MS: Long = RouteTransitionTracker.DEFAULT_TIMEOUT_US / 1_000L
    }
}
