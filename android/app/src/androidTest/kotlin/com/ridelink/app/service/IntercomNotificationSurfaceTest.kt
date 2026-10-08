package com.ridelink.app.service

import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ridelink.app.MainActivity
import com.ridelink.app.RideLinkApplication
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * STATUS §4 problem 113, as far as software can take it: the intercom's controls live on a plain
 * notification whose actions Android renders, never on the `MediaStyle` notification whose actions
 * Android 13+ SystemUI ignores.
 *
 * This drives the real [RideForegroundService] through its real start paths and feeds the voice facts
 * through [RideNotificationSource], the same seam `AppContainer` publishes to. It opens **no**
 * microphone and starts no voice session — the service only holds the foreground type — so it says
 * nothing about a live intercom. Whether the shade and lock screen show these buttons during a real
 * intercom is the physical check that remains **PENDING — AUTHENTICATED PEER UNAVAILABLE**.
 */
@RunWith(AndroidJUnit4::class)
class IntercomNotificationSurfaceTest {
    private val app = ApplicationProvider.getApplicationContext<RideLinkApplication>()
    private val notifications = app.getSystemService(NotificationManager::class.java)

    /**
     * Production asks for both before an intercom start (MainActivity's permission step); the test
     * grants them the way a user would have, on the emulator only. POST_NOTIFICATIONS matters here in
     * a way it did not before Phase 9A.5: a media-session notification is exempt from it, a plain one
     * is not — so with notifications denied the intercom's controls are not shown at all (the
     * foreground service still runs and the system still lists it).
     */
    @Before
    fun grantPermissions() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(app.packageName, android.Manifest.permission.RECORD_AUDIO)
        automation.grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() {
        RideForegroundService.stop(app)
        RideNotificationSource.voice.value = RideNotificationSource.Voice()
        awaitUntil("all ride notifications gone") { notifications.activeNotifications.isEmpty() }
    }

    @Test
    fun theIntercomNotificationIsPlainAndCarriesItsOwnControls() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertTrue(RideForegroundService.startFromVisibleUi(it)) }
            awaitIntercom(text = "Starting the microphone…", actions = listOf("End intercom"))

            RideNotificationSource.voice.value = RideNotificationSource.Voice(microphoneOpen = true, muted = false)
            val live = awaitIntercom(text = "Microphone on", actions = listOf("Mute", "End intercom"))
            assertFalse(isMediaStyle(live), "Android 13+ ignores a media notification's own actions (problem 113)")
            assertEquals(Notification.VISIBILITY_PUBLIC, live.notification.visibility, "the controls must be allowed on the lock screen")

            RideNotificationSource.voice.value = RideNotificationSource.Voice(microphoneOpen = true, muted = true)
            awaitIntercom(text = "Microphone muted", actions = listOf("Unmute", "End intercom"))
        }
    }

    @Test
    fun withMusicToo_theIntercomKeepsItsControlsAndMusicKeepsTheMediaSessionNotification() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            RideNotificationSource.voice.value = RideNotificationSource.Voice(microphoneOpen = true, muted = false)
            scenario.onActivity {
                assertTrue(RideForegroundService.startFromVisibleUi(it))
                assertTrue(RideForegroundService.startMusicFromVisibleUi(it))
            }
            val intercom = awaitIntercom(text = "Microphone on", actions = listOf("Mute", "End intercom"))
            assertFalse(isMediaStyle(intercom))
            awaitUntil("media notification beside it") { notifications.activeNotifications.any { it.id == MEDIA_ID && isMediaStyle(it) } }
            val media = notifications.activeNotifications.single { it.id == MEDIA_ID }
            val mediaText = "${media.notification.extras.getCharSequence(
                Notification.EXTRA_TITLE,
            )} ${media.notification.extras.getCharSequence(Notification.EXTRA_TEXT)}"
            assertFalse(mediaText.contains("intercom", ignoreCase = true), mediaText)

            // Ending the intercom while music continues leaves only the media notification.
            RideForegroundService.stopIntercom(app)
            awaitUntil("intercom notification removed, music kept") {
                notifications.activeNotifications.let { active -> active.none { it.id == INTERCOM_ID } && active.any { it.id == MEDIA_ID } }
            }
        }
    }

    private fun awaitIntercom(
        text: String,
        actions: List<String>,
    ): StatusBarNotification {
        var found: StatusBarNotification? = null
        awaitUntil("intercom notification \"$text\" with $actions") {
            found =
                notifications.activeNotifications.firstOrNull { posted ->
                    posted.id == INTERCOM_ID &&
                        posted.notification.extras
                            .getCharSequence(Notification.EXTRA_TITLE)
                            ?.toString() == "Intercom on" &&
                        posted.notification.extras
                            .getCharSequence(Notification.EXTRA_TEXT)
                            ?.toString() == text &&
                        posted.notification.actions
                            .orEmpty()
                            .map { it.title.toString() } == actions
                }
            found != null
        }
        return found!!
    }

    private fun isMediaStyle(posted: StatusBarNotification): Boolean =
        posted.notification.extras.getString(Notification.EXTRA_TEMPLATE) == Notification.MediaStyle::class.java.name

    private fun awaitUntil(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(POLL_MS)
        }
        val seen =
            notifications.activeNotifications.map {
                Triple(
                    it.id,
                    it.notification.extras.getCharSequence(Notification.EXTRA_TEXT),
                    it.notification.actions.orEmpty().map { a ->
                        a.title
                    },
                )
            }
        assertEquals(what, "timed out; active notifications: $seen")
    }

    private companion object {
        const val INTERCOM_ID = 1
        const val MEDIA_ID = 2
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 100L
    }
}
