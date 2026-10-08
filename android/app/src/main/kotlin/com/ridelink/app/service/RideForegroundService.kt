package com.ridelink.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.C
import androidx.media3.session.MediaSession
import com.ridelink.app.MainActivity
import com.ridelink.app.R
import com.ridelink.app.music.MusicCoordinator
import com.ridelink.core.audiopolicy.ForegroundServiceTypeNeed
import com.ridelink.core.audiopolicy.ForegroundServiceTypePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Objects
import java.util.concurrent.atomic.AtomicBoolean
import androidx.media3.common.Player as Media3Player

/**
 * What the ride notification's own controls ask the app to do.
 *
 * A closed set rather than free-form intent extras, so the lock-screen surface cannot ask for anything
 * the UI cannot. Delivered through [RideCommandBus].
 */
enum class RideCommand {
    /** Toggle the user's Mute. Distinct from PTT: mute is a latch, PTT is a position. */
    TOGGLE_MUTE,

    /** End the intercom. **Not** end the session — PROTOCOL §7.8 keeps those separate. */
    END_INTERCOM,
}

/**
 * The one-hop bus between [RideForegroundService]'s notification actions and the app's session owner.
 *
 * A direct dispatch with a single `@Volatile` handler, deliberately **not** a queue: this phase's
 * brief §38 requires every new input stream to have an explicit finite buffering policy, and "no
 * buffer at all" is the strongest one available. A notification tap that arrives with no handler
 * installed is dropped, which is correct — there is no session to act on.
 *
 * One process, one service, one coordinator, so there is nothing to route: `AppContainer` installs the
 * handler and clears it on teardown.
 */
object RideCommandBus {
    @Volatile
    var handler: ((RideCommand) -> Unit)? = null

    fun dispatch(command: RideCommand) {
        handler?.invoke(command)
    }
}

/**
 * The one-hop bridge from the composition root's already-built player and queue owner to this
 * service's `MediaSession` (ADR-022) — the same shape and reasoning as [RideCommandBus] above, just
 * carrying the opposite direction's wiring. `AppContainer` sets both fields once, at process start,
 * well before any user action can start this service; [RideForegroundService.onCreate] reads them
 * once to build the session. Volatile, not a queue, for the same reason [RideCommandBus] is not
 * one: there is nothing to buffer — either the one real player/coordinator pair is wired by the
 * time this service instance is created, or the notification falls back to its pre-ADR-022 shape
 * for that instance (see [RideForegroundService.buildNotification]).
 *
 * `player` is `androidx.media3.common.Player`, never `androidx.media3.exoplayer.ExoPlayer` —
 * [MusicSessionPlayer] only needs the platform-neutral Media3 surface, and narrowing the type here
 * keeps this bridge from becoming a second way to reach ExoPlayer-specific behaviour.
 */
object RideMediaSessionSource {
    @Volatile
    var player: Media3Player? = null

    @Volatile
    var coordinator: MusicCoordinator? = null
}

/**
 * The voice facts the intercom notification shows, published by `AppContainer` from the voice
 * controller's diagnostics (Phase 9A.5). One source for the in-app Mute button and the notification's
 * own Mute action alike — the notification used to learn the mute state only from its own action's
 * intent extra, so an in-app mute left it saying "Mute", and every other refresh reset it.
 */
object RideNotificationSource {
    data class Voice(
        val microphoneOpen: Boolean = false,
        val muted: Boolean = false,
    )

    val voice = MutableStateFlow(Voice())
}

/**
 * The one ride foreground service (ARCHITECTURE §6.4).
 *
 * Its whole reason for existing is a platform rule, not a convenience: **modern Android forbids
 * starting a microphone foreground service from the background.** The design is built around that
 * rather than trying to work around it —
 *
 * ```
 * 1  RideLink is visibly open (a resumed Activity)                        <- precondition
 * 2  RECORD_AUDIO / POST_NOTIFICATIONS granted, or handled if denied
 * 3  Readiness gate: session authenticated, audio endpoint present
 * 4  User taps START INTERCOM
 * 5  Still foreground-visible: start THIS service with the types this ride needs
 * 6  Still foreground-visible: acquire focus, select the device, OPEN capture
 * 7  User may now lock the screen
 * 8  This service maintains the session and capture for the rest of the ride
 * ```
 *
 * — and step 5 is why it is started from a visible activity and never from a callback. The *decision*
 * that a start is legal is [com.ridelink.core.audiopolicy.RideStartPolicy]'s, which is pure and
 * unit-tested on both platforms; what is here is the platform call.
 *
 * Phase 3: the requested type set is now [ForegroundServiceTypePolicy]'s pure function of
 * (intercom active, music playing), computed fresh on every call that could change either — never
 * a type the app is not honestly using at that moment. [intercomActive]/[musicPlaying] are
 * companion-level flags rather than instance fields because the platform is free to recreate this
 * `Service` object at any point while it keeps running; there is exactly one real instance of it in
 * this app at a time, so this is the same "one owner" invariant CLAUDE.md rule 8 already requires,
 * expressed the way a `Service`'s own lifecycle forces it to be expressed. **No fake media session
 * is created to satisfy foreground-service semantics** — a type is requested only when the
 * corresponding real subsystem is actually active.
 *
 * `START_NOT_STICKY`, deliberately (ARCHITECTURE §6.4's failure table): nothing may restart a
 * microphone foreground service in the background after process death. The user starts the ride again
 * explicitly.
 *
 * **Never run on a device.** A foreground service's actual behaviour — whether the type is accepted,
 * whether capture survives a screen lock, whether `ForegroundServiceStartNotAllowedException` ever
 * fires in practice, whether the notification actions behave on a lock screen — is
 * **REAL-DEVICE INTERCOM GATE PENDING** (docs/STATUS.md §7, TEST_PLAN V-08/AF-01/AF-05). This file
 * compiles and is wired; that is all anyone may conclude from it.
 */
class RideForegroundService : Service() {
    /**
     * ADR-022: a real, system-integrated `MediaSession`, owned by this service exactly the way the
     * ADR requires — built here in [onCreate], released in [onDestroy], wired to the one real
     * player [RideMediaSessionSource] was handed, never a second player or a second queue owner.
     * `null` only if this instance was created before `AppContainer` finished wiring
     * [RideMediaSessionSource] (should not happen in practice — `AppContainer` exists before any
     * user action can start this service — but this is a foreground service the platform is free to
     * recreate, so it is read defensively rather than assumed non-null).
     */
    private var mediaSession: MediaSession? = null

    /** Lives exactly as long as this service instance; only observes, never owns session state. */
    private var scope: CoroutineScope? = null

    /** True once this instance has called `startForeground`, until it stops being foreground. */
    private var foreground = false

    /** What each notification id currently shows, so an unchanged notice is never re-posted. */
    private val posted = mutableMapOf<Int, Any>()

    /**
     * STATUS §4 problem 116: SystemUI's shade and lock-screen media card re-read the session only when
     * the media notification is posted, so a track change must re-post it. This listens to the one
     * player for exactly the changes the card shows — item, metadata (title, artist, artwork),
     * timeline (duration) and play state — never position, which the card tracks from the session.
     */
    private val playerListener =
        object : Media3Player.Listener {
            override fun onEvents(
                player: Media3Player,
                events: Media3Player.Events,
            ) {
                if (REPOST_EVENTS.any(events::contains)) repostContent()
            }
        }

    @androidx.media3.common.util.UnstableApi // MusicSessionPlayer (a ForwardingPlayer) is opt-in in this Media3 version.
    override fun onCreate() {
        super.onCreate()
        val player = RideMediaSessionSource.player
        val coordinator = RideMediaSessionSource.coordinator
        if (player != null && coordinator != null) {
            mediaSession =
                MediaSession
                    .Builder(this, MusicSessionPlayer(player, coordinator))
                    .setId(MEDIA_SESSION_ID)
                    .setSessionActivity(openAppIntent())
                    .build()
            player.addListener(playerListener)
        }
        scope = MainScope().also { scope -> scope.launch { RideNotificationSource.voice.collect { repostContent() } } }
    }

    override fun onDestroy() {
        // Only the session, never the player: `MediaSession.release()` tears down this service's
        // own control surface (listeners, connected controllers) and nothing else. The real
        // `ExoPlayer` behind [RideMediaSessionSource.player] is owned by `AppContainer`/
        // `ExoPlayerMusicPlayer` for the lifetime of the whole process, not by this service, which
        // the platform is free to create and destroy independently of a ride ever happening.
        RideMediaSessionSource.player?.removeListener(playerListener)
        scope?.cancel()
        scope = null
        // Both notifications belong to this service. The platform removes only the one in the
        // foreground slot when a service stops; the other was posted with `notify` and would outlive
        // it — found by IntercomNotificationSurfaceTest: a hard stop() during intercom + music left a
        // stale media notification behind.
        cancelAll()
        foreground = false
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @androidx.media3.common.util.UnstableApi // buildMediaNotification uses MediaSession.platformToken (opt-in).
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        ensureChannels()
        when (intent?.action) {
            ACTION_TOGGLE_MUTE -> RideCommandBus.dispatch(RideCommand.TOGGLE_MUTE)
            ACTION_END_INTERCOM -> {
                // This phase's hardening pass (Issue F): `stopSelf()` used to run immediately after
                // dispatch, but `RideCommandBus`'s handler only *queues* the stop request — the actual
                // `engine.release()`/`audioSession.close()` runs later, asynchronously. Stopping the
                // service here could let the platform reclaim it while it still held the microphone,
                // which is exactly the orphan ARCHITECTURE §6.4 forbids, just reached from the other
                // direction. `AppContainer`'s handler now awaits real release and calls
                // `RideForegroundService.stop()` itself once that is true — this action's whole job is
                // to dispatch and get out of the way.
                RideCommandBus.dispatch(RideCommand.END_INTERCOM)
                return START_NOT_STICKY
            }
            ACTION_MEDIA_PREVIOUS -> RideMediaSessionSource.coordinator?.previous()
            ACTION_MEDIA_PLAY_PAUSE ->
                RideMediaSessionSource.coordinator?.let { music ->
                    if (music.playerState.value.playing) music.pause() else music.play()
                }
            ACTION_MEDIA_NEXT -> RideMediaSessionSource.coordinator?.next()
            ACTION_START_INTERCOM -> intercomActive.set(true)
            ACTION_START_MUSIC -> musicPlaying.set(true)
            ACTION_UPDATE_MUSIC_PLAYING -> musicPlaying.set(intent.getBooleanExtra(EXTRA_MUSIC_PLAYING, false))
        }
        refreshForegroundState()
        return START_NOT_STICKY
    }

    /**
     * Recomputes the required type set from the two facts this process actually knows right now
     * ([intercomActive], [musicPlaying]) via [ForegroundServiceTypePolicy] — never a type this
     * service is not honestly using — and calls [ServiceCompat.startForeground] again with it.
     * Re-calling `startForeground` while already foreground is how a running service updates its
     * declared type set; there is no separate "update type" platform API.
     *
     * **A real crash found on the emulator**: when neither is active any more (the last track
     * finished and the intercom was never started, say), [needs] is empty, and calling
     * `startForeground` with an empty type set throws `InvalidForegroundServiceTypeException`
     * ("type none ... has been prohibited") on API 36 — a foreground service may drop to *no*
     * declared type. The correct response to "nothing needs this service any more" is to stop
     * being foreground and let the service go, the same outcome
     * [stopIfNothingActiveElseRefresh] already reaches from its own callers, just reached here too
     * so every path through [onStartCommand] is covered, not only the ones that go through
     * [stopIntercom]/[stopMusic].
     *
     * Phase 9A.5: the notification *content* is [RideNotificationPlanner]'s, and the foreground slot
     * goes to whichever notification the plan puts there (the intercom's when it is on, otherwise
     * the media notification). Types are still exactly [ForegroundServiceTypePolicy]'s.
     */
    @androidx.media3.common.util.UnstableApi
    private fun refreshForegroundState() {
        val needs = ForegroundServiceTypePolicy.requiredTypes(intercomActive.get(), musicPlaying.get())
        val plan = RideNotificationPlanner.plan(currentInputs())
        if (needs.isEmpty() || plan == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            cancelAll()
            foreground = false
            stopSelf()
            return
        }
        val platformTypes =
            needs.fold(0) { acc, need ->
                acc or
                    when (need) {
                        ForegroundServiceTypeNeed.MICROPHONE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        ForegroundServiceTypeNeed.MEDIA_PLAYBACK -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    }
            }
        val foregroundId = idFor(plan.foreground)
        val notice: Any = if (plan.foreground == RideSurface.INTERCOM) plan.intercom!! else plan.media!!
        // ServiceCompat, not startForeground directly: it is the call that carries the service type
        // across API levels, and getting the type wrong is a crash on API 34+, not a warning.
        ServiceCompat.startForeground(this, foregroundId, build(notice), platformTypes)
        posted[foregroundId] = notice
        foreground = true
        postSecondary(plan)
    }

    /**
     * Re-posts whatever content changed — a new track, a duration now known, play/pause, mute — and
     * nothing else. Not a type change: those go through [refreshForegroundState]. Called on the
     * main thread, from the player listener and the voice collector.
     */
    @androidx.media3.common.util.UnstableApi
    private fun repostContent() {
        if (!foreground) return
        val plan = RideNotificationPlanner.plan(currentInputs()) ?: return
        val foregroundId = idFor(plan.foreground)
        val notice: Any = if (plan.foreground == RideSurface.INTERCOM) plan.intercom!! else plan.media!!
        notifyIfChanged(foregroundId, notice)
        postSecondary(plan)
    }

    /** Posts the plan's non-foreground notification if it has one, and cancels every id it does not use. */
    @androidx.media3.common.util.UnstableApi
    private fun postSecondary(plan: RideNotificationPlan) {
        val wanted = mutableSetOf(idFor(plan.foreground))
        if (plan.foreground == RideSurface.INTERCOM && plan.media != null) {
            notifyIfChanged(MEDIA_NOTIFICATION_ID, plan.media)
            wanted += MEDIA_NOTIFICATION_ID
        }
        (ALL_NOTIFICATION_IDS - wanted).forEach { id ->
            if (posted.remove(id) != null) getSystemService(NotificationManager::class.java).cancel(id)
        }
    }

    @androidx.media3.common.util.UnstableApi
    private fun notifyIfChanged(
        id: Int,
        notice: Any,
    ) {
        if (posted[id] == notice) return
        getSystemService(NotificationManager::class.java).notify(id, build(notice))
        posted[id] = notice
    }

    private fun cancelAll() {
        val manager = getSystemService(NotificationManager::class.java)
        ALL_NOTIFICATION_IDS.forEach(manager::cancel)
        posted.clear()
    }

    private fun currentInputs(): RideNotificationInputs {
        val voice = RideNotificationSource.voice.value
        val player = RideMediaSessionSource.player
        val metadata = player?.mediaMetadata
        return RideNotificationInputs(
            intercomActive = intercomActive.get(),
            microphoneOpen = voice.microphoneOpen,
            muted = voice.muted,
            musicActive = musicPlaying.get(),
            musicPlaying = player?.isPlaying == true,
            trackTitle = metadata?.title?.toString(),
            trackArtist = metadata?.artist?.toString(),
            mediaRevision =
                Objects.hash(
                    player?.currentMediaItem?.mediaId,
                    metadata,
                    player?.duration?.takeIf { it != C.TIME_UNSET },
                ),
        )
    }

    @androidx.media3.common.util.UnstableApi
    private fun build(notice: Any): Notification =
        when (notice) {
            is IntercomNotice -> buildIntercomNotification(notice)
            is MediaNotice -> buildMediaNotification(notice)
            else -> error("unknown notice ${notice.javaClass.simpleName}")
        }

    /**
     * ARCHITECTURE §6.4: a task swiped from Recents ends the session cleanly rather than leaving an
     * orphaned service holding a microphone.
     *
     * No direct `stopSelf()` here either (Issue F, same reasoning as `ACTION_END_INTERCOM` above):
     * `RideCommandBus`'s handler awaits real release before it calls [stop] itself.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        RideCommandBus.dispatch(RideCommand.END_INTERCOM)
        super.onTaskRemoved(rootIntent)
    }

    private fun ensureChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        // Phase 9A.5: the intercom's channel alerts visually so its controls are allowed on the lock
        // screen, but it makes no sound and no vibration. The music channel stays silent. The
        // pre-9A.5 combined channel is removed rather than left orphaned.
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        if (manager.getNotificationChannel(INTERCOM_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(INTERCOM_CHANNEL_ID, getString(R.string.intercom_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply {
                        description = getString(R.string.intercom_channel_description)
                        setSound(null, null)
                        enableVibration(false)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    },
            )
        }
        if (manager.getNotificationChannel(MUSIC_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(MUSIC_CHANNEL_ID, getString(R.string.music_channel_name), NotificationManager.IMPORTANCE_LOW)
                    .apply {
                        description = getString(R.string.music_channel_description)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    },
            )
        }
    }

    /**
     * The intercom's notification (STATUS §4 problems 112/113). Deliberately **not** `MediaStyle`:
     * on Android 13+ SystemUI draws a media-style notification as the media player and ignores its
     * own actions, which is how Mute and End intercom disappeared. A plain notification's actions
     * are rendered in the shade and on the lock screen. It names no peer and no device
     * (ARCHITECTURE §11): anyone holding the phone can read it.
     */
    private fun buildIntercomNotification(notice: IntercomNotice): Notification =
        NotificationCompat
            .Builder(this, INTERCOM_CHANNEL_ID)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent())
            .apply {
                notice.actions.forEach { action ->
                    val (label, intentAction, request) =
                        when (action) {
                            IntercomNoticeAction.MUTE -> Triple("Mute", ACTION_TOGGLE_MUTE, REQUEST_TOGGLE_MUTE)
                            IntercomNoticeAction.UNMUTE -> Triple("Unmute", ACTION_TOGGLE_MUTE, REQUEST_TOGGLE_MUTE)
                            IntercomNoticeAction.END_INTERCOM -> Triple("End intercom", ACTION_END_INTERCOM, REQUEST_END_INTERCOM)
                        }
                    addAction(NotificationCompat.Action.Builder(NO_ACTION_ICON, label, commandIntent(intentAction, request)).build())
                }
            }.build()

    /**
     * The music notification: `MediaStyle` carrying the one `MediaSession`'s token (ADR-022), so
     * SystemUI draws it as the media player from the session. Title and text are the track's own,
     * for surfaces that show the notification itself. On Android 12 (API 31–32), which still draws a
     * media notification from its own actions, it carries Previous / Play-Pause / Next; Android 13+
     * takes those from the session and they are left off.
     */
    @androidx.media3.common.util.UnstableApi // MediaSession.platformToken is opt-in in this Media3 version.
    private fun buildMediaNotification(notice: MediaNotice): Notification {
        val builder =
            NotificationCompat
                .Builder(this, MUSIC_CHANNEL_ID)
                .setContentTitle(notice.title)
                .setContentText(notice.text)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(notice.playing)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openAppIntent())
        val style = MediaStyle()
        mediaSession?.let { style.setMediaSession(MediaSessionCompat.Token.fromToken(it.platformToken)) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            builder.addAction(NO_ACTION_ICON, "Previous", commandIntent(ACTION_MEDIA_PREVIOUS, REQUEST_MEDIA_PREVIOUS))
            builder.addAction(
                NO_ACTION_ICON,
                if (notice.playing) "Pause" else "Play",
                commandIntent(ACTION_MEDIA_PLAY_PAUSE, REQUEST_MEDIA_PLAY_PAUSE),
            )
            builder.addAction(NO_ACTION_ICON, "Next", commandIntent(ACTION_MEDIA_NEXT, REQUEST_MEDIA_NEXT))
            style.setShowActionsInCompactView(0, 1, 2)
        }
        return builder.setStyle(style).build()
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun commandIntent(
        action: String,
        requestCode: Int,
    ): PendingIntent =
        PendingIntent.getForegroundService(
            this,
            requestCode,
            Intent(this, RideForegroundService::class.java).setAction(action),
            // IMMUTABLE because nothing outside this app may fill in any part of it, and the action is
            // the whole payload.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        /** The pre-9A.5 single channel, deleted on first start (see [ensureChannels]). */
        private const val LEGACY_CHANNEL_ID = "ridelink.ride"
        private const val INTERCOM_CHANNEL_ID = "ridelink.intercom"
        private const val MUSIC_CHANNEL_ID = "ridelink.music"
        private const val INTERCOM_NOTIFICATION_ID = 1
        private const val MEDIA_NOTIFICATION_ID = 2
        private val ALL_NOTIFICATION_IDS = setOf(INTERCOM_NOTIFICATION_ID, MEDIA_NOTIFICATION_ID)

        private fun idFor(surface: RideSurface): Int =
            when (surface) {
                RideSurface.INTERCOM -> INTERCOM_NOTIFICATION_ID
                RideSurface.MEDIA -> MEDIA_NOTIFICATION_ID
            }

        /** Every player event that changes what the media card shows — and nothing positional. */
        private val REPOST_EVENTS =
            intArrayOf(
                Media3Player.EVENT_MEDIA_ITEM_TRANSITION,
                Media3Player.EVENT_MEDIA_METADATA_CHANGED,
                Media3Player.EVENT_TIMELINE_CHANGED,
                Media3Player.EVENT_IS_PLAYING_CHANGED,
                Media3Player.EVENT_PLAYBACK_STATE_CHANGED,
            )

        // ADR-022. A fixed id, not the Media3 default: this service only ever has one real session,
        // and the same reasoning "not a machine-specific detail" already applies elsewhere in this file.
        private const val MEDIA_SESSION_ID = "ridelink.ride"

        // NotificationCompat.Action's int-icon constructor with 0 means "no icon," matching the bare
        // platform Notification.Action.Builder's `null` icon this file used before ADR-022.
        private const val NO_ACTION_ICON = 0
        private const val ACTION_START_INTERCOM = "com.ridelink.ride.START_INTERCOM"
        private const val ACTION_START_MUSIC = "com.ridelink.ride.START_MUSIC"
        private const val ACTION_UPDATE_MUSIC_PLAYING = "com.ridelink.ride.UPDATE_MUSIC_PLAYING"
        private const val ACTION_TOGGLE_MUTE = "com.ridelink.ride.TOGGLE_MUTE"
        private const val ACTION_END_INTERCOM = "com.ridelink.ride.END_INTERCOM"
        private const val ACTION_MEDIA_PREVIOUS = "com.ridelink.ride.MEDIA_PREVIOUS"
        private const val ACTION_MEDIA_PLAY_PAUSE = "com.ridelink.ride.MEDIA_PLAY_PAUSE"
        private const val ACTION_MEDIA_NEXT = "com.ridelink.ride.MEDIA_NEXT"

        /** No-op besides the type/notification recompute every `onStartCommand` already does at the
         *  end — used when one of [intercomActive]/[musicPlaying] changed via a path (like
         *  [stopIntercom]) that does not itself carry a more specific action. */
        private const val ACTION_REFRESH = "com.ridelink.ride.REFRESH"
        private const val EXTRA_MUSIC_PLAYING = "music_playing"
        private const val REQUEST_TOGGLE_MUTE = 1
        private const val REQUEST_END_INTERCOM = 2
        private const val REQUEST_OPEN_APP = 3
        private const val REQUEST_MEDIA_PREVIOUS = 4
        private const val REQUEST_MEDIA_PLAY_PAUSE = 5
        private const val REQUEST_MEDIA_NEXT = 6

        /**
         * Companion-level, not instance state — deliberately (see the class KDoc). Exactly one real
         * instance of this service exists in this process at a time, so these two flags together are
         * the single source [ForegroundServiceTypePolicy] reads from, however many times Android
         * recreates the `Service` object around them.
         */
        private val intercomActive = AtomicBoolean(false)
        private val musicPlaying = AtomicBoolean(false)

        /**
         * **Must be called from a resumed Activity** (ARCHITECTURE §6.4 step 5). Starting from a
         * background callback is what `ForegroundServiceStartNotAllowedException` exists to refuse, and
         * this project's rule is to work within the platform's background rules rather than around
         * them.
         *
         * The exception is caught rather than propagated: the correct response is to tell the user to
         * bring RideLink to the front, never to retry silently from the background.
         *
         * @return false if the platform refused, which the caller surfaces as
         *   [com.ridelink.core.audiopolicy.VoiceFailure.FOREGROUND_SERVICE_START_FAILED].
         */
        fun startFromVisibleUi(context: Context): Boolean =
            runCatching {
                context.startForegroundService(
                    Intent(context, RideForegroundService::class.java).setAction(ACTION_START_INTERCOM),
                )
            }.isSuccess

        /**
         * Local-music-only start (this phase's brief §16's "music-only playback must work without
         * the microphone/intercom being active"). Held to the same foreground-visible discipline as
         * [startFromVisibleUi] — CLAUDE.md's "Ride Mode starts only from a visible app" rule is about
         * foreground-service starts in general, not specifically the microphone, and a consistent
         * rule is simpler to reason about than a second, looser one for music.
         */
        fun startMusicFromVisibleUi(context: Context): Boolean =
            runCatching {
                context.startForegroundService(
                    Intent(context, RideForegroundService::class.java).setAction(ACTION_START_MUSIC),
                )
            }.isSuccess

        /**
         * Tells the already-running service whether music is playing right now, so it can add or
         * drop the `mediaPlayback` type — a no-op (and does **not** start the service) if it is not
         * already running, since play/pause on a track nobody imported yet must not itself trigger a
         * foreground-service start.
         */
        fun updateMusicPlaying(
            context: Context,
            playing: Boolean,
        ) {
            if (!musicPlaying.get() && !playing) return
            runCatching {
                context.startService(
                    Intent(context, RideForegroundService::class.java)
                        .setAction(ACTION_UPDATE_MUSIC_PLAYING)
                        .putExtra(EXTRA_MUSIC_PLAYING, playing),
                )
            }
        }

        /**
         * Ends the **intercom's** hold on this service — stops it entirely only if music is not also
         * keeping it alive, otherwise drops just the `microphone` type. The reverse of
         * [startFromVisibleUi]; never a blind [stop].
         */
        fun stopIntercom(context: Context) {
            intercomActive.set(false)
            stopIfNothingActiveElseRefresh(context)
        }

        /** The music-only mirror of [stopIntercom]. */
        fun stopMusic(context: Context) {
            musicPlaying.set(false)
            stopIfNothingActiveElseRefresh(context)
        }

        private fun stopIfNothingActiveElseRefresh(context: Context) {
            if (!intercomActive.get() && !musicPlaying.get()) {
                stop(context)
            } else {
                context.startService(Intent(context, RideForegroundService::class.java).setAction(ACTION_REFRESH))
            }
        }

        /** A hard, unconditional stop — [onTaskRemoved] and a full app teardown, never a normal
         *  end-of-intercom or end-of-music path (use [stopIntercom]/[stopMusic] for those). */
        fun stop(context: Context) {
            intercomActive.set(false)
            musicPlaying.set(false)
            context.stopService(Intent(context, RideForegroundService::class.java))
        }

        /** Exposed so a readiness gate can explain *why* voice is unavailable rather than just failing. */
        val requiresRuntimePermissions: List<String> =
            buildList {
                add(android.Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                add(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
    }
}
