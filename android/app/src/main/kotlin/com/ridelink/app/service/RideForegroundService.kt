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
import androidx.core.app.ServiceCompat
import com.ridelink.app.R

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
 * Phase 2b declares only [ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE] at runtime. `mediaPlayback`
 * is declared in the manifest, because one service carries both types for a ride, but it is **not**
 * requested yet: there is no player until Phase 3, and requesting a type the app does not use is the
 * kind of thing that gets an app killed rather than trusted. **No fake media session is created to
 * satisfy foreground-service semantics** — the microphone type is the honest one for what this service
 * currently holds.
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
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        ensureChannel()
        when (intent?.action) {
            ACTION_TOGGLE_MUTE -> RideCommandBus.dispatch(RideCommand.TOGGLE_MUTE)
            ACTION_END_INTERCOM -> {
                RideCommandBus.dispatch(RideCommand.END_INTERCOM)
                // The command ends the intercom; the service ends with it, because a microphone
                // foreground service with no microphone open is exactly the orphan ARCHITECTURE §6.4
                // forbids.
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // ServiceCompat, not startForeground directly: it is the call that carries the service type
        // across API levels, and getting the type wrong is a crash on API 34+, not a warning.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(muted = intent?.getBooleanExtra(EXTRA_MUTED, false) == true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        return START_NOT_STICKY
    }

    /**
     * ARCHITECTURE §6.4: a task swiped from Recents ends the session cleanly rather than leaving an
     * orphaned service holding a microphone.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        RideCommandBus.dispatch(RideCommand.END_INTERCOM)
        stopSelf()
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
     * actions the lock screen needs: mute and end-intercom.
     *
     * It names no peer and no device: a notification is visible on a lock screen to anyone holding the
     * phone, which makes it the same kind of surface as an mDNS TXT record (ARCHITECTURE §11).
     */
    private fun buildNotification(muted: Boolean): Notification =
        Notification
            .Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.ride_notification_title))
            .setContentText(
                getString(
                    if (muted) R.string.ride_notification_text_muted else R.string.ride_notification_text,
                ),
            ).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .addAction(
                Notification.Action
                    .Builder(
                        null,
                        getString(if (muted) R.string.ride_action_unmute else R.string.ride_action_mute),
                        commandIntent(ACTION_TOGGLE_MUTE, REQUEST_TOGGLE_MUTE),
                    ).build(),
            ).addAction(
                Notification.Action
                    .Builder(
                        null,
                        getString(R.string.ride_action_end_intercom),
                        commandIntent(ACTION_END_INTERCOM, REQUEST_END_INTERCOM),
                    ).build(),
            ).build()

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
        private const val ACTION_START = "com.ridelink.ride.START"
        private const val ACTION_TOGGLE_MUTE = "com.ridelink.ride.TOGGLE_MUTE"
        private const val ACTION_END_INTERCOM = "com.ridelink.ride.END_INTERCOM"
        private const val EXTRA_MUTED = "muted"
        private const val REQUEST_TOGGLE_MUTE = 1
        private const val REQUEST_END_INTERCOM = 2

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
                    Intent(context, RideForegroundService::class.java).setAction(ACTION_START),
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
                        .setAction(ACTION_START)
                        .putExtra(EXTRA_MUTED, muted),
                )
            }.isSuccess

        fun stop(context: Context) {
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
