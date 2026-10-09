package com.ridelink.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ridelink.app.MainActivity
import com.ridelink.core.library.DecodeStatus
import com.ridelink.core.library.LibraryEntry
import com.ridelink.core.library.LibraryQuery
import com.ridelink.core.library.LocalTrackLocation
import com.ridelink.core.model.LocalEntryId
import com.ridelink.core.model.QuickId
import com.ridelink.core.model.Track
import com.ridelink.core.player.LocalQueueItem
import com.ridelink.core.player.LocalQueueState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PR #18 review, the presentation half: while synchronised transport owns playback, local Up Next
 * and the library offer **no** action that would edit the local queue — not disabled, absent, so a
 * screen reader cannot reach one either. The authority is `MusicCoordinator`'s admission
 * (`MusicCoordinatorQueuePlayTest`); this proves the screen does not pretend otherwise. Each case
 * renders locally first, as a control, then synchronised.
 */
@RunWith(AndroidJUnit4::class)
class LocalQueueReadOnlyUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val rows = listOf(UpNextRow("u1", "Coast Road", "Evening Roads", null), UpNextRow("u2", "Night ferry", "Evening Roads", null))
    private val queue = LocalQueueState(rows.map { LocalQueueItem(it.id, LocalEntryId("eeeeeeee-0000-0000-0000-000000000001"), 0) }, "u2")

    @Test
    fun synchronisedUpNextExposesNoSelectRemoveMoveOrClear() {
        var synchronized by mutableStateOf(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { RideLinkTheme { UpNextContent(rows, queue, synchronized, UpNextActions()) } } }
            compose.waitForIdle()
            assertEquals(2, clickableRows(UP_NEXT_ROW_TAG), "control: local rows play when tapped")
            assertTrue(count(hasText("Clear")) == 1, "control: Clear is offered locally")
            assertTrue(count(hasContentDescription("Remove", substring = true)) == 2, "control: Remove is offered locally")

            synchronized = true
            compose.waitForIdle()
            assertEquals(
                2,
                compose.onAllNodesWithTag(UP_NEXT_ROW_TAG, useUnmergedTree = true).fetchSemanticsNodes().size,
                "rows stay visible",
            )
            assertEquals(0, clickableRows(UP_NEXT_ROW_TAG), "a row can still select while synchronised")
            assertEquals(0, count(hasText("Clear")), "Clear is offered while synchronised")
            assertEquals(0, count(hasContentDescription("Remove", substring = true)), "Remove is offered while synchronised")
            assertEquals(0, count(hasContentDescription("Move", substring = true)), "Move is offered while synchronised")
            assertEquals(1, count(hasText("Playing on both phones", substring = true)), "the read-only state is explained")
        }
    }

    @Test
    fun synchronisedLibraryRowsNeitherPlayNorAdd() {
        var locked by mutableStateOf(false)
        val entries = (1..3).map(::entry)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    RideLinkTheme {
                        LibraryContent(
                            LibraryUiState(LibraryQuery(), entries, entries.size, null, queueLocked = locked),
                            LibraryActions(),
                        )
                    }
                }
            }
            compose.waitForIdle()
            assertEquals(3, clickableRows(LIBRARY_ROW_TAG), "control: local rows play when tapped")
            assertEquals(3, count(hasContentDescription("to Up Next", substring = true)), "control: add is offered locally")

            locked = true
            compose.waitForIdle()
            assertEquals(0, clickableRows(LIBRARY_ROW_TAG), "a library row can still play-now while synchronised")
            assertEquals(0, count(hasContentDescription("to Up Next", substring = true)), "add is offered while synchronised")
        }
    }

    private fun clickableRows(tag: String) = count(hasTestTag(tag) and hasClickAction())

    private fun count(matcher: androidx.compose.ui.test.SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun entry(i: Int) =
        LibraryEntry(
            localEntryId = LocalEntryId("eeeeeeee-0000-0000-0000-00000000000$i"),
            track =
                Track(
                    null,
                    QuickId("sha256:" + "%064x".format(i)),
                    "Track $i",
                    "Artist",
                    "Album",
                    1000,
                    "t$i.mp3",
                    "mp3",
                    320,
                    null,
                    1,
                ),
            location = LocalTrackLocation("content://read-only/$i"),
            decodeStatus = DecodeStatus.INDEXED,
            indexedAtMonoUs = i.toLong(),
            lastSeenAtMonoUs = i.toLong(),
        )
}
