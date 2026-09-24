package com.ridelink.app.ui

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ridelink.app.MainActivity
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/** Rendering fixtures only: never mutate a production coordinator or simulate authentication. */
@RunWith(AndroidJUnit4::class)
class RideVisualTest {
    @Test
    fun renderRideStateMatrix() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val names =
            listOf(
                "idle",
                "intercom",
                "music",
                "combined",
                "ptt",
                "muted",
                "reconnecting",
                "disconnected",
                "sync-problem",
                "long-title",
                "waiting",
            )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            names.forEach { name ->
                val drawn = CountDownLatch(1)
                scenario.onActivity { activity ->
                    val activeMusic = name !in setOf("idle", "intercom")
                    val ui =
                        RideModeUiState(
                            connectionHealth =
                                when (name) {
                                    "reconnecting" -> RideConnectionHealth.DEGRADED
                                    "disconnected" -> RideConnectionHealth.DISCONNECTED
                                    else -> RideConnectionHealth.HEALTHY
                                },
                            reconnectCount = 0,
                            trackTitle =
                                if (activeMusic) {
                                    if (name ==
                                        "long-title"
                                    ) {
                                        "The long way home through the mountains and beyond the horizon"
                                    } else {
                                        "The long way home"
                                    }
                                } else {
                                    null
                                },
                            trackArtist = if (activeMusic) "Evening Roads" else null,
                            isPlaying = activeMusic && name != "waiting",
                            hasTrackLoaded = activeMusic,
                            micAvailable = name !in setOf("idle", "music"),
                            micMuted = name == "muted",
                            pttMode = true,
                            pttHeld = name == "ptt",
                            intercomDisabled = name in setOf("idle", "music"),
                            intercomModeLabel = "Push to Talk",
                            localAudioDegraded = false,
                            peerAudioDegraded = false,
                        )
                    activity.setContent {
                        Box(Modifier.semantics { contentDescription = "Fixture ride-$name" }.onGloballyPositioned { drawn.countDown() }) {
                            RideModeContent(
                                ui = ui,
                                syncText =
                                    when (name) {
                                        "reconnecting" -> "Music sync waits for connection"
                                        "sync-problem" -> "Music sync paused"
                                        "waiting" -> "Waiting for the track to download…"
                                        "idle", "intercom", "disconnected" -> "Local playback"
                                        else -> "Synchronized"
                                    },
                                voiceText = if (name in setOf("idle", "music")) "Intercom not started" else "Intercom active",
                                microphoneText =
                                    when (name) {
                                        "idle", "music" -> "Microphone unavailable"
                                        "muted" -> "Muted"
                                        "ptt" -> "Transmitting"
                                        else -> "Microphone ready"
                                    },
                                policyText = "C · Push to Talk / duck music",
                                onPrevious = {},
                                onPlayPause = {},
                                onNext = {},
                                onToggleMute = {},
                                onPushToTalkHeld = {},
                                onReconnect = {},
                                onEndRide = {},
                            )
                        }
                    }
                }
                assertTrue(drawn.await(10, TimeUnit.SECONDS), "Ride layout did not render: $name")
                instrumentation.waitForIdleSync()
                captureScreen(scenario, "ride-$name")
                if (name == "waiting") {
                    val scroll = accessibilityNodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull { it.isScrollable }
                    if (scroll != null) assertTrue(scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                    captureScreen(scenario, "ride-$name", "-scrolled")
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    val endRide = accessibilityNodes(root).first { it.text?.toString() == "End Ride" }
                    val screen = Rect().also { root.getBoundsInScreen(it) }
                    val control = Rect().also { endRide.getBoundsInScreen(it) }
                    assertTrue(endRide.isVisibleToUser && screen.contains(control), "End Ride must be reachable after scrolling")
                }
            }
        }
    }
}
