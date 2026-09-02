package com.ridelink.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.ridelink.app.R

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
 * 3  Readiness gate: session CONNECTED, audio endpoint present
 * 4  User taps START VOICE
 * 5  Still foreground-visible: start THIS service with the types this ride needs
 * 6  Still foreground-visible: acquire focus, select the device, OPEN capture
 * 7  User may now lock the screen
 * 8  This service maintains the session and capture for the rest of the ride
 * ```
 *
 * — and step 5 is why it is started from a visible activity and never from a callback.
 *
 * Phase 2a declares only [ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE]. `mediaPlayback` is
 * declared in the manifest, because one service carries both types for a ride, but it is **not**
 * requested at runtime yet: there is no player until Phase 5, and requesting a type the app does not
 * use is the kind of thing that gets an app killed rather than trusted.
 *
 * `START_NOT_STICKY`, deliberately (ARCHITECTURE §6.4's failure table): nothing may restart a
 * microphone foreground service in the background after process death. The user starts the ride again
 * explicitly.
 *
 * **Never run on a device.** A foreground service's actual behaviour — whether the type is accepted,
 * whether capture survives a screen lock, whether `ForegroundServiceStartNotAllowedException` ever
 * fires in practice — is **REAL-DEVICE AUDIO GATE PENDING** (docs/STATUS.md §7). This file compiles
 * and is wired; that is all anyone may conclude from it.
 */
class RideForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        ensureChannel()
        // ServiceCompat, not startForeground directly: it is the call that carries the service type
        // across API levels, and getting the type wrong is a crash on API 34+, not a warning.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        return START_NOT_STICKY
    }

    /**
     * ARCHITECTURE §6.4: a task swiped from Recents ends the session cleanly rather than leaving an
     * orphaned service holding a microphone.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
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
     * Ongoing and non-dismissible while the service runs (ARCHITECTURE §6.4). The text says the
     * microphone is open, because it is, and a user is entitled to see that without opening the app.
     *
     * It names no peer and no device: a notification is visible on a lock screen to anyone holding the
     * phone, which makes it the same kind of surface as an mDNS TXT record (ARCHITECTURE §11).
     */
    private fun buildNotification(): Notification =
        Notification
            .Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.ride_notification_title))
            .setContentText(getString(R.string.ride_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "ridelink.ride"
        private const val NOTIFICATION_ID = 1

        /**
         * **Must be called from a resumed Activity** (ARCHITECTURE §6.4 step 5). Starting from a
         * background callback is what `ForegroundServiceStartNotAllowedException` exists to refuse,
         * and this project's rule is to work within the platform's background rules rather than
         * around them.
         *
         * The exception is caught rather than propagated: the correct response is to tell the user to
         * bring RideLink to the front, never to retry silently from the background.
         */
        fun startFromVisibleUi(context: Context): Boolean =
            runCatching {
                context.startForegroundService(Intent(context, RideForegroundService::class.java))
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
