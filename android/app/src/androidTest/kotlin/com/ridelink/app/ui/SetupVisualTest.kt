package com.ridelink.app.ui

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ridelink.app.MainActivity
import com.ridelink.app.library.DownloadState
import com.ridelink.app.music.CoexistenceDiagnostics
import com.ridelink.core.audiopolicy.IntercomPolicy
import com.ridelink.core.audiopolicy.VoiceFailure
import com.ridelink.core.library.LibraryQuery
import com.ridelink.core.manifest.ManifestEntry
import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.LocalEntryId
import com.ridelink.core.model.PeerId
import com.ridelink.core.model.QuickId
import com.ridelink.core.playback.SharedQueueItem
import com.ridelink.core.playback.SharedQueueState
import com.ridelink.core.player.PlayerState
import com.ridelink.core.sessionfsm.SessionStatus
import com.ridelink.core.transfer.TransferStatus
import com.ridelink.network.control.PairingPrompt
import com.ridelink.network.voice.VoiceDiagnostics
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SetupVisualTest {
    @Test
    fun renderSetupStates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val names =
            SessionStatus.entries.map { it.name } +
                listOf("PAIR_CODE", "VOICE", "QUEUE", "MUSIC_EMPTY", "SECURITY", "LIBRARY", "TRANSFER", "MUSIC_PLAYING", "MUSIC_PAUSED")
        val hash = ContentHash("sha256:" + "a".repeat(64))
        val peer = PeerId("0123456789abcdef")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val requested = InstrumentationRegistry.getArguments().getString("fixture")
            names.filter { requested == null || requested == it }.forEach { name ->
                val layout = CountDownLatch(1)
                scenario.onActivity { activity ->
                    activity.setContent {
                        key(name) {
                            RideLinkTheme {
                                Surface(color = MaterialTheme.colorScheme.background) {
                                    Column(
                                        Modifier
                                            .fillMaxSize()
                                            .semantics { contentDescription = "Fixture setup-$name" }
                                            .safeDrawingPadding()
                                            .verticalScroll(rememberScrollState())
                                            .padding(RideSpace.xl)
                                            .onGloballyPositioned { layout.countDown() },
                                        verticalArrangement = Arrangement.spacedBy(RideSpace.lg),
                                    ) {
                                        Text("RideLink", style = MaterialTheme.typography.headlineMedium)
                                        when (name) {
                                            "PAIR_CODE" ->
                                                PairingCard(
                                                    PairingPrompt("123456", peer, "Your peer’s phone with a long name"),
                                                ) {}
                                            "VOICE" ->
                                                VoiceCard(
                                                    voice = VoiceDiagnostics(lastFailure = VoiceFailure.MIC_PERMISSION_DENIED),
                                                    coexistence = CoexistenceDiagnostics(),
                                                    policy = IntercomPolicy.DEFAULT,
                                                    peerAudioState = null,
                                                    refusal = null,
                                                    onStartIntercom = {},
                                                    onStopIntercom = {},
                                                    onToggleMute = {},
                                                    onPushToTalkHeld = {},
                                                    onSelectPolicy = {},
                                                )
                                            "QUEUE" ->
                                                SharedQueueContent(
                                                    SharedQueueState(
                                                        listOf(
                                                            SharedQueueItem("first", hash, peer, 0),
                                                            SharedQueueItem("second", hash, peer, 1),
                                                        ),
                                                        "first",
                                                    ),
                                                    mapOf(hash.value to "The long way home through the mountains"),
                                                    {},
                                                )
                                            "MUSIC_EMPTY" ->
                                                NowPlayingCard(
                                                    PlayerState(),
                                                    null,
                                                    0,
                                                    onPlay = {},
                                                    onPause = {},
                                                    onSeek = {},
                                                    onNext = {},
                                                    onPrevious = {},
                                                )
                                            "LIBRARY" -> LibraryScreen(LibraryQuery(), emptyList(), {}, {}, {}, {}, {}, {})
                                            "TRANSFER" ->
                                                SharedLibraryScreen(
                                                    listOf(
                                                        ManifestEntry(
                                                            hash,
                                                            QuickId(hash.value),
                                                            "fixture",
                                                            "The long way home",
                                                            "Evening Roads",
                                                            "Mountain journey",
                                                            180000,
                                                            "mp3",
                                                            320,
                                                            10000,
                                                            "fixture.mp3",
                                                            false,
                                                        ),
                                                    ),
                                                    emptyList(),
                                                    mapOf(hash.value to DownloadState(TransferStatus.TRANSFERRING, 4000, 10000)),
                                                    emptySet(),
                                                    {},
                                                    {},
                                                    {},
                                                )
                                            "MUSIC_PLAYING", "MUSIC_PAUSED" ->
                                                NowPlayingCard(
                                                    PlayerState(
                                                        localEntryId = LocalEntryId.parse("00000000-0000-0000-0000-000000000001")!!,
                                                        playing =
                                                            name == "MUSIC_PLAYING",
                                                        durationMs = 180000,
                                                        positionMs = 60000,
                                                    ),
                                                    null,
                                                    2,
                                                    title = "The long way home",
                                                    artist = "Evening Roads",
                                                    onPlay = {},
                                                    onPause = {},
                                                    onSeek = {},
                                                    onNext = {},
                                                    onPrevious = {},
                                                )
                                            "SECURITY" -> SecurityAlertCard("pin_mismatch") {}
                                            else -> ConnectionSummary(SessionStatus.valueOf(name), if (name == "DISCOVERING") 1 else 0)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                assertTrue(layout.await(10, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                captureScreen(scenario, "setup-$name")
                if (name == "LIBRARY" && InstrumentationRegistry.getArguments().getString("keyboard") == "true") {
                    val search =
                        accessibilityNodes(instrumentation.uiAutomation.rootInActiveWindow)
                            .first { it.className?.toString() == "android.widget.EditText" }
                    assertTrue(search.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    awaitKeyboard(scenario, visible = true)
                    captureScreen(scenario, "setup-$name", "-keyboard")
                    instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    awaitKeyboard(scenario, visible = false)
                }
            }
        }
    }
}
