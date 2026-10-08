package com.ridelink.app.service

import android.app.Notification
import android.app.NotificationManager
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ridelink.app.MainActivity
import com.ridelink.app.RideLinkApplication
import com.ridelink.core.library.LibraryEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * STATUS §4 problems 112 and 116 through the real app: the real `MusicCoordinator`, ExoPlayer,
 * `MediaSession` and [RideForegroundService], three real (synthetic) files, three track changes.
 *
 * Before Phase 9A.5 the ride notification was posted with fixed intercom copy and re-posted only when
 * the foreground types or mute state changed, so its title never followed the track, and over
 * music-only playback it said "RideLink intercom active" / "Microphone open for the intercom".
 * SystemUI's media card re-reads the session when that notification is posted; this proves the post
 * now happens on every track change, with that track's own title, and that music-only playback posts
 * no intercom notification and no intercom wording. What SystemUI then *draws* is the physical
 * check that remains (three-track lock-screen progression on the OnePlus).
 */
@RunWith(AndroidJUnit4::class)
class RideNotificationMetadataTest {
    private val app = ApplicationProvider.getApplicationContext<RideLinkApplication>()
    private val notifications = app.getSystemService(NotificationManager::class.java)

    @Test
    fun eachTrackChangeRepostsTheMediaNotificationWithThatTracksTitle() {
        val music = app.container.getOrThrow().musicCoordinator
        val titles = listOf("Coast Road", "Night ferry", "Headwind")
        val uris = titles.map { fixture("$it.m4a") }
        music.imports.importFiles(uris)
        val entries: List<LibraryEntry> =
            runBlocking {
                withTimeout(30_000) { music.libraryEntries.first { rows -> titles.all { t -> rows.any { it.track.title == t } } } }
            }.filter { it.track.title in titles }.sortedBy { titles.indexOf(it.track.title) }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                // The fixtures are about a second long. After each change the test waits for that
                // track to be the one playing, then pauses it, so a track cannot end and
                // auto-advance underneath an assertion. The change itself — load, metadata,
                // notification — is what is under test and is unaffected.
                scenario.onActivity {
                    assertTrue(RideForegroundService.startMusicFromVisibleUi(it))
                    music.playNow(entries[0])
                }
                holdOn(scenario, entries[0])
                scenario.onActivity {
                    music.addToQueue(entries[1])
                    music.addToQueue(entries[2])
                }
                awaitMediaTitle(titles[0])
                assertMusicOnlyCopy()

                scenario.onActivity { music.next() }
                holdOn(scenario, entries[1])
                awaitMediaTitle(titles[1])

                scenario.onActivity { music.next() }
                holdOn(scenario, entries[2])
                awaitMediaTitle(titles[2])
                assertMusicOnlyCopy()
            } finally {
                scenario.onActivity {
                    music.clearQueue()
                    RideForegroundService.stopMusic(it)
                }
                awaitUntil("notifications cleared") { notifications.activeNotifications.isEmpty() }
            }
        }
    }

    /** Waits until [entry] is the player's playing track, then pauses it there. */
    private fun holdOn(
        scenario: ActivityScenario<MainActivity>,
        entry: LibraryEntry,
    ) {
        val music = app.container.getOrThrow().musicCoordinator
        awaitUntil("${entry.track.title} playing") {
            music.playerState.value.let { it.localEntryId == entry.localEntryId && it.playing }
        }
        scenario.onActivity { music.pause() }
        awaitUntil("${entry.track.title} paused") { !music.playerState.value.playing }
    }

    private fun assertMusicOnlyCopy() {
        val active = notifications.activeNotifications
        assertNull(active.firstOrNull { it.id == INTERCOM_ID }, "problem 112: no intercom notification during music-only playback")
        active.forEach { posted ->
            val text =
                listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT).joinToString(" ") {
                    posted.notification.extras
                        .getCharSequence(it)
                        ?.toString()
                        .orEmpty()
                }
            assertFalse(text.contains("intercom", ignoreCase = true), "music-only notification text: $text")
            assertFalse(text.contains("microphone", ignoreCase = true), "music-only notification text: $text")
        }
    }

    private fun awaitMediaTitle(title: String) =
        awaitUntil("media notification titled $title") {
            notifications.activeNotifications.any {
                it.id == MEDIA_ID &&
                    it.notification.extras
                        .getCharSequence(Notification.EXTRA_TITLE)
                        ?.toString() == title
            }
        }

    private fun awaitUntil(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(POLL_MS)
        }
        val seen = notifications.activeNotifications.map { it.id to it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) }
        assertEquals("$what", "timed out; active notifications: $seen")
    }

    /** An untagged, playable synthetic file under the name whose stem becomes its title. */
    private fun fixture(name: String): Uri {
        val dir = File(app.filesDir, "notification-fixtures").apply { mkdirs() }
        val file = File(dir, name)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("no_metadata.m4a").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return Uri.fromFile(file)
    }

    private companion object {
        const val INTERCOM_ID = 1
        const val MEDIA_ID = 2
        const val TIMEOUT_MS = 15_000L
        const val POLL_MS = 100L
    }
}
