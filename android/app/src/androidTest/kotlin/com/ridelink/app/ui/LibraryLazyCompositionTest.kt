package com.ridelink.app.ui

import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ridelink.app.MainActivity
import com.ridelink.core.library.DecodeStatus
import com.ridelink.core.library.LibraryEntry
import com.ridelink.core.library.LibraryQuery
import com.ridelink.core.library.LibrarySort
import com.ridelink.core.library.LocalTrackLocation
import com.ridelink.core.model.LocalEntryId
import com.ridelink.core.model.QuickId
import com.ridelink.core.model.Track
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/**
 * STATUS §4 problem 114: the library must not compose every row.
 *
 * With 3,460 tracks on the OnePlus Nord 5 the old `Column { entries.forEach { … } }` spent ~20 s of
 * main-thread time composing rows on a cold launch, and the same again when a search was cleared.
 * These tests render the **real** [LibraryContent] — real rows, real artwork placeholder, real
 * search/sort chrome — with 5,000 synthetic tracks and count how many row nodes exist in the
 * semantics tree. An eager list puts all 5,000 there; a lazy one only what fits on screen plus
 * prefetch. The bound is deliberately loose (a phone in landscape at the smallest font still shows
 * far fewer than [MAX_COMPOSED_ROWS] rows) so it fails only on the defect, never on a layout tweak.
 *
 * Timings are logged under [TAG] for the record; they are emulator figures, not phone figures, and
 * nothing asserts on them.
 */
@RunWith(AndroidJUnit4::class)
class LibraryLazyCompositionTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val library: List<LibraryEntry> = (0 until LARGE).map(::entry)

    @Test
    fun aFiveThousandTrackLibraryComposesOnlyTheRowsOnScreen() {
        var state by mutableStateOf(LibraryUiState(LibraryQuery(), library, LARGE, currentEntryId = null))
        val started = SystemClock.elapsedRealtime()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { RideLinkTheme { LibraryContent(state, LibraryActions()) } } }
            compose.waitForIdle()
            Log.i(TAG, "first composition of $LARGE tracks: ${SystemClock.elapsedRealtime() - started} ms")
            assertBounded("cold render")

            // Search narrows the list, then clearing it restores all 5,000 — the second freeze.
            state = state.copy(query = LibraryQuery(searchText = "Track 1"), entries = library.filter { it.track.title == "Track 1" })
            compose.waitForIdle()
            val cleared = SystemClock.elapsedRealtime()
            state = state.copy(query = LibraryQuery(), entries = library)
            compose.waitForIdle()
            Log.i(TAG, "clear search back to $LARGE tracks: ${SystemClock.elapsedRealtime() - cleared} ms")
            assertBounded("after clearing search")

            val sorted = SystemClock.elapsedRealtime()
            state = state.copy(query = LibraryQuery(sort = LibrarySort.ARTIST), entries = library.sortedBy { it.track.artist })
            compose.waitForIdle()
            Log.i(TAG, "sort change over $LARGE tracks: ${SystemClock.elapsedRealtime() - sorted} ms")
            assertBounded("after a sort change")

            val scrolled = SystemClock.elapsedRealtime()
            compose.onNodeWithTag(LIBRARY_LIST_TAG).performScrollToIndex(LARGE - 1)
            compose.waitForIdle()
            Log.i(TAG, "scroll to the last of $LARGE tracks: ${SystemClock.elapsedRealtime() - scrolled} ms")
            assertBounded("after scrolling to the end")
        }
    }

    @Test
    fun theLibraryRowsLiveInsideTheLazyListNotTheScreensOwnScroll() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent { RideLinkTheme { LibraryContent(LibraryUiState(LibraryQuery(), library, LARGE, null), LibraryActions()) } }
            }
            compose.waitForIdle()
            // performScrollToIndex exists only on a lazy list's semantics; a Column inside a
            // verticalScroll has no index to scroll to and this throws.
            compose.onNodeWithTag(LIBRARY_LIST_TAG).performScrollToIndex(LARGE / 2)
            compose.waitForIdle()
            assertBounded("mid-list")
        }
    }

    private fun assertBounded(moment: String) {
        val composed = compose.onAllNodesWithTag(LIBRARY_ROW_TAG, useUnmergedTree = true).fetchSemanticsNodes().size
        Log.i(TAG, "$moment: $composed row nodes for $LARGE tracks")
        assertTrue(composed in 1..MAX_COMPOSED_ROWS, "$moment: $composed rows composed for $LARGE tracks — the list is not lazy")
    }

    private fun entry(i: Int) =
        LibraryEntry(
            localEntryId = LocalEntryId("00000000-0000-0000-0000-%012d".format(i)),
            track =
                Track(
                    contentHash = null,
                    quickId = QuickId("sha256:" + "%064x".format(i)),
                    title = "Track ${i % 900}",
                    artist = "Artist ${i % 120}",
                    album = "Album ${i % 400}",
                    durationMs = 180_000,
                    filename = "track-$i.mp3",
                    codec = "mp3",
                    bitrateKbps = 320,
                    artworkRef = null,
                    sizeBytes = 1,
                ),
            location = LocalTrackLocation("content://fixture/$i"),
            decodeStatus = DecodeStatus.INDEXED,
            indexedAtMonoUs = i.toLong(),
            lastSeenAtMonoUs = i.toLong(),
        )

    private companion object {
        const val LARGE = 5_000
        const val MAX_COMPOSED_ROWS = 60
        const val TAG = "RideLinkLibraryPerf"
    }
}
