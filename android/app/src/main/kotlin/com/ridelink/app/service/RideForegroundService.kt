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
import androidx.media3.session.MediaSession
import com.ridelink.app.R
import com.ridelink.app.music.MusicCoordinator
import com.ridelink.core.audiopolicy.ForegroundServiceTypeNeed
import com.ridelink.core.audiopolicy.ForegroundServiceTypePolicy
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
                    .build()
        }
    }

    override fun onDestroy() {
        // Only the session, never the player: `MediaSession.release()` tears down this service's
        // own control surface (listeners, connected controllers) and nothing else. The real
        // `ExoPlayer` behind [RideMediaSessionSource.player] is owned by `AppContainer`/
        // `ExoPlayerMusicPlayer` for the lifetime of the whole process, not by this service, which
        // the platform is free to create and destroy independently of a ride ever happening.
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Lint-only, no behaviour change: refreshForegroundState (unmodified below) calls the
    // @UnstableApi buildNotification, and the annotation must propagate to every caller in the
    // chain up to here. onStartCommand's own action dispatch is exactly as it was before ADR-022.
    @androidx.media3.common.util.UnstableApi
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        ensureChannel()
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
            ACTION_START_INTERCOM -> intercomActive.set(true)
            ACTION_START_MUSIC -> musicPlaying.set(true)
            ACTION_UPDATE_MUSIC_PLAYING -> musicPlaying.set(intent.getBooleanExtra(EXTRA_MUSIC_PLAYING, false))
        }
        refreshForegroundState(muted = intent?.getBooleanExtra(EXTRA_MUTED, false) == true)
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
     */
    @androidx.media3.common.util.UnstableApi // buildNotification (below) is opt-in in this Media3 version.
    private fun refreshForegroundState(muted: Boolean) {
        val needs = ForegroundServiceTypePolicy.requiredTypes(intercomActive.get(), musicPlaying.get())
        if (needs.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
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
        // ServiceCompat, not startForeground directly: it is the call that carries the service type
        // across API levels, and getting the type wrong is a crash on API 34+, not a warning.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(muted = muted), platformTypes)
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

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.ride_channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.ride_channel_description) },
        )
    }

    /**
     * Ongoing and non-dismissible while the service runs (ARCHITECTURE §6.4), and it carries the two
     * actions the lock screen needs: mute and end-intercom — exactly as before ADR-022.
     *
     * It names no peer and no device: a notification is visible on a lock screen to anyone holding the
     * phone, which makes it the same kind of surface as an mDNS TXT record (ARCHITECTURE §11).
     *
     * **ADR-022 addition**: `NotificationCompat.Builder`, not the bare platform `Notification.Builder`
     * used before this ADR, so a `MediaStyle` (`androidx.media.app.NotificationCompat.MediaStyle`) can
     * carry [mediaSession]'s compat token. That token is what turns this into a real, system-integrated
     * media notification — the play/pause/skip-next/skip-previous transport controls it exposes reach
     * the lock screen through the session itself ("the system reads transport-control affordances from
     * a `MediaStyle` notification's session token regardless of what kind of component created that
     * session," ADR-022 §2), never through a `PendingIntent` this method builds, and never by adding a
     * third/fourth custom action here — [onStartCommand]'s action dispatch stays exactly as it was.
     * [mediaSession] can be `null` (see its own KDoc); the notification degrades to its pre-ADR-022
     * shape rather than crashing.
     *
     * The two custom actions keep their existing order in the compact view (this phase's judgement
     * call — neither the ADR nor the brief pins an exact ordering): mute first, end-intercom second,
     * unchanged from what shipped before this ADR.
     */
    @androidx.media3.common.util.UnstableApi // MediaSession.platformToken (below) is opt-in in this Media3 version.
    private fun buildNotification(muted: Boolean): Notification {
        val builder =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.ride_notification_title))
                .setContentText(
                    getString(
                        if (muted) R.string.ride_notification_text_muted else R.string.ride_notification_text,
                    ),
                ).setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .addAction(
                    NotificationCompat.Action
                        .Builder(
                            NO_ACTION_ICON,
                            getString(if (muted) R.string.ride_action_unmute else R.string.ride_action_mute),
                            commandIntent(ACTION_TOGGLE_MUTE, REQUEST_TOGGLE_MUTE),
                        ).build(),
                ).addAction(
                    NotificationCompat.Action
                        .Builder(
                            NO_ACTION_ICON,
                            getString(R.string.ride_action_end_intercom),
                            commandIntent(ACTION_END_INTERCOM, REQUEST_END_INTERCOM),
                        ).build(),
                )
        mediaSession?.let { session ->
            builder.setStyle(
                MediaStyle()
                    .setMediaSession(MediaSessionCompat.Token.fromToken(session.platformToken))
                    .setShowActionsInCompactView(MUTE_ACTION_INDEX, END_INTERCOM_ACTION_INDEX),
            )
        }
        return builder.build()
    }

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
        private const val CHANNEL_ID = "ridelink.ride"
        private const val NOTIFICATION_ID = 1

        // ADR-022. A fixed id, not the Media3 default: this service only ever has one real session,
        // and the same reasoning "not a machine-specific detail" already applies elsewhere in this file.
        private const val MEDIA_SESSION_ID = "ridelink.ride"

        // NotificationCompat.Action's int-icon constructor with 0 means "no icon," matching the bare
        // platform Notification.Action.Builder's `null` icon this file used before ADR-022.
        private const val NO_ACTION_ICON = 0
        private const val MUTE_ACTION_INDEX = 0
        private const val END_INTERCOM_ACTION_INDEX = 1
        private const val ACTION_START_INTERCOM = "com.ridelink.ride.START_INTERCOM"
        private const val ACTION_START_MUSIC = "com.ridelink.ride.START_MUSIC"
        private const val ACTION_UPDATE_MUSIC_PLAYING = "com.ridelink.ride.UPDATE_MUSIC_PLAYING"
        private const val ACTION_TOGGLE_MUTE = "com.ridelink.ride.TOGGLE_MUTE"
        private const val ACTION_END_INTERCOM = "com.ridelink.ride.END_INTERCOM"

        /** No-op besides the type/notification recompute every `onStartCommand` already does at the
         *  end — used when one of [intercomActive]/[musicPlaying] changed via a path (like
         *  [stopIntercom]) that does not itself carry a more specific action. */
        private const val ACTION_REFRESH = "com.ridelink.ride.REFRESH"
        private const val EXTRA_MUTED = "muted"
        private const val EXTRA_MUSIC_PLAYING = "music_playing"
        private const val REQUEST_TOGGLE_MUTE = 1
        private const val REQUEST_END_INTERCOM = 2

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
         * Refreshes the ongoing notification so the lock-screen surface reflects the current mute
         * state. Safe from the background: the service is **already** foreground, so this is an update
         * rather than a start, and it is a no-op if the service is not running.
         */
        fun updateMuteState(
            context: Context,
            muted: Boolean,
        ): Boolean =
            runCatching {
                context.startService(
                    Intent(context, RideForegroundService::class.java)
                        .setAction(ACTION_TOGGLE_MUTE)
                        .putExtra(EXTRA_MUTED, muted),
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
