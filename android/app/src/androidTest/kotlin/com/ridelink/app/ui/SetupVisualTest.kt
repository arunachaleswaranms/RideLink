package com.ridelink.app.ui

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.ridelink.core.library.DecodeStatus
import com.ridelink.core.library.LibraryEntry
import com.ridelink.core.library.LibraryQuery
import com.ridelink.core.library.LocalTrackLocation
import com.ridelink.core.manifest.ManifestEntry
import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.LocalEntryId
import com.ridelink.core.model.PeerId
import com.ridelink.core.model.QuickId
import com.ridelink.core.model.Track
import com.ridelink.core.playback.SharedQueueItem
import com.ridelink.core.playback.SharedQueueState
import com.ridelink.core.player.LocalQueueItem
import com.ridelink.core.player.LocalQueueState
import com.ridelink.core.player.PlayerState
import com.ridelink.core.sessionfsm.SessionStatus
import com.ridelink.core.transfer.TransferStatus
import com.ridelink.data.library.ImportProgress
import com.ridelink.data.library.RecordingFolder
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
                listOf("PAIR_CODE", "VOICE", "QUEUE", "MUSIC_EMPTY", "SECURITY", "MUSIC_PLAYING", "MUSIC_PAUSED") +
                FULL_SCREEN
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
                                    if (name in FULL_SCREEN) {
                                        Box(
                                            Modifier
                                                .fillMaxSize()
                                                .semantics { contentDescription = "Fixture setup-$name" }
                                                .safeDrawingPadding()
                                                .onGloballyPositioned { layout.countDown() },
                                        ) { FullScreenFixture(name, hash, peer) }
                                        return@Surface
                                    }
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
                                                    NowPlayingUi(PlayerState(), null, null, null, TransportAvailability(false, false)),
                                                    onPlay = {},
                                                    onPause = {},
                                                    onSeek = {},
                                                    onNext = {},
                                                    onPrevious = {},
                                                )
                                            "MUSIC_PLAYING", "MUSIC_PAUSED" ->
                                                NowPlayingCard(
                                                    NowPlayingUi(
                                                        PlayerState(
                                                            localEntryId = LocalEntryId.parse("00000000-0000-0000-0000-000000000001")!!,
                                                            playing = name == "MUSIC_PLAYING",
                                                            durationMs = 180000,
                                                            positionMs = 60000,
                                                        ),
                                                        "The long way home",
                                                        "Evening Roads",
                                                        null,
                                                        TransportAvailability(true, true),
                                                    ),
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

    /** Screens that fill the window — a lazy list needs a finite height, so these never sit inside
     *  the scrolling fixture column. Every title here is synthetic. */
    @Composable
    private fun FullScreenFixture(
        name: String,
        hash: ContentHash,
        peer: PeerId,
    ) {
        val small = SYNTHETIC_TITLES.mapIndexed { i, title -> fixtureEntry(i, title) }
        val large =
            (0 until 5_000).map {
                fixtureEntry(
                    it,
                    "${SYNTHETIC_TITLES[it % SYNTHETIC_TITLES.size]} ${it / SYNTHETIC_TITLES.size + 1}",
                )
            }
        when (name) {
            "LIBRARY" -> LibraryContent(LibraryUiState(LibraryQuery(), small, small.size, small[1].localEntryId), LibraryActions())
            "LIBRARY_LARGE" -> LibraryContent(LibraryUiState(LibraryQuery(), large, large.size, null), LibraryActions())
            "LIBRARY_SEARCH" ->
                LibraryContent(
                    LibraryUiState(
                        LibraryQuery(searchText = "road"),
                        large.filter { it.track.title.contains("Road") }.take(40),
                        large.size,
                        null,
                    ),
                    LibraryActions(),
                )
            "LIBRARY_EMPTY" -> LibraryContent(LibraryUiState(LibraryQuery(), emptyList(), 0, null), LibraryActions())
            "IMPORT_CONFIRM" ->
                LibraryContent(
                    LibraryUiState(
                        LibraryQuery(),
                        small,
                        small.size,
                        null,
                        importProgress =
                            ImportProgress.AwaitingConfirmation(
                                "Music",
                                842,
                                listOf(RecordingFolder("Call Recordings", 37), RecordingFolder("Recordings", 5)),
                            ),
                    ),
                    LibraryActions(),
                )
            "IMPORT_PROGRESS" ->
                LibraryContent(
                    LibraryUiState(
                        LibraryQuery(),
                        small,
                        small.size,
                        null,
                        importProgress = ImportProgress.Indexing("Music", ImportProgress.Stage.READING, 320, 800),
                        importBusy = true,
                    ),
                    LibraryActions(),
                )
            "UP_NEXT" -> {
                val rows =
                    listOf(
                        UpNextRow("u1", "Coast Road", "Evening Roads", null),
                        UpNextRow("u2", "Night ferry", "Evening Roads", null),
                        UpNextRow("u3", "Coast Road", "Evening Roads", null),
                        UpNextRow("u4", "Headwind", "Evening Roads", null),
                    )
                UpNextContent(
                    rows,
                    LocalQueueState(rows.map { LocalQueueItem(it.id, small[0].localEntryId, 0) }, currentId = "u2"),
                    synchronized = false,
                    actions = UpNextActions(),
                ) {
                    MiniPlayer(
                        NowPlayingUi(
                            PlayerState(localEntryId = small[0].localEntryId, playing = true, durationMs = 200_000, positionMs = 70_000),
                            "Night ferry",
                            "Evening Roads",
                            null,
                            TransportAvailability(true, true),
                        ),
                        {},
                        {},
                        {},
                    )
                }
            }
            "TRANSFER" ->
                SharedMusicContent(
                    SharedMusicUiState(
                        remoteEntries =
                            SYNTHETIC_TITLES.mapIndexed { i, title ->
                                ManifestEntry(
                                    ContentHash("sha256:" + "%064x".format(i + 1)),
                                    QuickId("sha256:" + "%064x".format(i + 1)),
                                    "fixture-$i",
                                    title,
                                    "Evening Roads",
                                    "Mountain journey",
                                    180000,
                                    "mp3",
                                    320,
                                    10000,
                                    "fixture-$i.mp3",
                                    false,
                                )
                            },
                        playableHere = setOf("sha256:" + "%064x".format(1), "sha256:" + "%064x".format(2)),
                        inLibrary = setOf("sha256:" + "%064x".format(1)),
                        downloadStates = mapOf("sha256:" + "%064x".format(3) to DownloadState(TransferStatus.TRANSFERRING, 4000, 10000)),
                        sharedQueue = SharedQueueState(listOf(SharedQueueItem("first", hash, peer, 0)), "first"),
                        syncAvailable = true,
                    ),
                    SharedMusicActions(),
                )
        }
    }

    private fun fixtureEntry(
        i: Int,
        title: String,
    ) = LibraryEntry(
        localEntryId = LocalEntryId("00000000-0000-0000-0000-%012d".format(i)),
        track =
            Track(
                contentHash = null,
                quickId = QuickId("sha256:" + "%064x".format(i)),
                title = title,
                artist = "Evening Roads",
                album = "Mountain journey",
                durationMs = 180_000,
                filename = "fixture-$i.mp3",
                codec = "mp3",
                bitrateKbps = 320,
                artworkRef = null,
                sizeBytes = 1,
            ),
        location = LocalTrackLocation("content://fixture/$i"),
        decodeStatus = if (i == 5) DecodeStatus.MISSING else DecodeStatus.INDEXED,
        indexedAtMonoUs = i.toLong(),
        lastSeenAtMonoUs = i.toLong(),
    )

    private companion object {
        val FULL_SCREEN =
            listOf(
                "LIBRARY",
                "LIBRARY_LARGE",
                "LIBRARY_SEARCH",
                "LIBRARY_EMPTY",
                "IMPORT_CONFIRM",
                "IMPORT_PROGRESS",
                "UP_NEXT",
                "TRANSFER",
            )
        val SYNTHETIC_TITLES =
            listOf(
                "The long way home",
                "Coast Road",
                "Gravel and rain",
                "Night ferry",
                "Switchbacks",
                "Fuel stop",
                "Road to the pass",
                "Headwind",
            )
    }
}
