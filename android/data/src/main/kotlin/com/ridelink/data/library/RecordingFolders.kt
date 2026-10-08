package com.ridelink.data.library

/** A subfolder of a scanned tree whose name says it holds recordings, and how many supported files
 *  the scan found under it. */
data class RecordingFolder(
    val name: String,
    val trackCount: Int,
)

/**
 * Flags subfolders that are almost certainly phone or voice recordings rather than music.
 *
 * Physical testing imported a whole `Music` folder and pulled in call recordings with it. RideLink
 * does not hide files on its own judgement: this only *names* such folders, so the import summary can
 * say so and offer to leave them out. The user decides, per import.
 *
 * Deliberately narrow: an exact, case-insensitive folder-name match against names phone dialers and
 * recorder apps actually use. "Live Recordings" or "Recordings 1998" do not match. Matching happens
 * on this phone only — no filename leaves the device, and nothing is classified by content.
 */
object RecordingFolders {
    private val NAMES =
        setOf(
            "call recordings",
            "call recording",
            "callrecordings",
            "callrecord",
            "call",
            "recordings",
            "recorder",
            "voice recorder",
            "voice recordings",
            "sound recordings",
            "audio recordings",
        )

    fun isRecordingFolderName(name: String): Boolean = name.trim().lowercase() in NAMES

    /** Groups [discovered] by the outermost recording-like folder above each file, in first-seen
     *  order. A file under no such folder is not counted. */
    fun detect(discovered: List<DiscoveredLocation>): List<RecordingFolder> {
        val counts = LinkedHashMap<String, Int>()
        for (location in discovered) {
            val folder = location.relativeFolders.firstOrNull(::isRecordingFolderName) ?: continue
            counts[folder] = (counts[folder] ?: 0) + 1
        }
        return counts.map { (name, count) -> RecordingFolder(name, count) }
    }

    /** [discovered] without anything under a recording-like folder. */
    fun withoutRecordings(discovered: List<DiscoveredLocation>): List<DiscoveredLocation> =
        discovered.filterNot { location -> location.relativeFolders.any(::isRecordingFolderName) }
}
