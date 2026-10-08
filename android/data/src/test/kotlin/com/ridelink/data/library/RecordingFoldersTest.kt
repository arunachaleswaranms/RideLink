package com.ridelink.data.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phase 9A.5 §9: recording-like folders are named to the user, narrowly, and never guessed at. */
class RecordingFoldersTest {
    private fun file(
        name: String,
        vararg folders: String,
    ) = DiscoveredLocation("file:///$name", name, 1, folders.toList())

    @Test
    fun `dialer and recorder folder names match case-insensitively`() {
        listOf("Call Recordings", "call", "Recordings", "RECORDER", "Voice Recorder", "CallRecordings").forEach {
            assertTrue(RecordingFolders.isRecordingFolderName(it), it)
        }
    }

    @Test
    fun `music folders that merely mention recording do not match`() {
        listOf("Live Recordings", "Recordings 1998", "Studio", "Records", "Calling You", "Music").forEach {
            assertFalse(RecordingFolders.isRecordingFolderName(it), it)
        }
    }

    @Test
    fun `detection groups by the outermost recording folder and counts its files`() {
        val discovered =
            listOf(
                file("song.mp3", "Rock"),
                file("a.m4a", "Recordings", "Call"),
                file("b.m4a", "Recordings"),
                file("c.m4a", "Call Recordings"),
            )

        assertEquals(
            listOf(RecordingFolder("Recordings", 2), RecordingFolder("Call Recordings", 1)),
            RecordingFolders.detect(discovered),
        )
        assertEquals(listOf("song.mp3"), RecordingFolders.withoutRecordings(discovered).map { it.filename })
    }

    @Test
    fun `a file at the scanned root is never a recording`() {
        val discovered = listOf(file("call.mp3"))

        assertTrue(RecordingFolders.detect(discovered).isEmpty())
        assertEquals(discovered, RecordingFolders.withoutRecordings(discovered))
    }
}
