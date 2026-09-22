package com.ridelink.app.ui

import com.ridelink.core.audiopolicy.AudioConfidence
import com.ridelink.core.audiopolicy.AudioProfile
import com.ridelink.core.audiopolicy.EndpointClass
import com.ridelink.core.audiopolicy.IntercomMode
import com.ridelink.core.audiopolicy.IntercomPolicy
import com.ridelink.core.audiopolicy.MediaQuality
import com.ridelink.core.audiopolicy.RouteState
import com.ridelink.core.library.DecodeStatus
import com.ridelink.core.library.LibraryEntry
import com.ridelink.core.library.LocalTrackLocation
import com.ridelink.core.model.LocalEntryId
import com.ridelink.core.model.QuickId
import com.ridelink.core.model.Track
import com.ridelink.core.player.PlayerState
import com.ridelink.core.protocol.AudioStateEpoch
import com.ridelink.core.protocol.AudioStateMessage
import com.ridelink.core.sessionfsm.SessionStatus
import com.ridelink.core.voice.VoiceStatus
import com.ridelink.network.voice.VoiceDiagnostics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [rideModeUiState]/[nextRideModeVisibility]/[rideConnectionHealth] are pure — no `StateFlow`, no
 * Compose — so the Ride Mode screen's presentation logic is exhaustible here, independently of
 * rendering (brief: "state logic should remain testable independently from rendering").
 */
class RideModeUiStateTest {
    @Test
    fun `every sync state is overridden by loss of connection`() {
        com.ridelink.app.sync.SyncState.entries.forEach { sync ->
            assertEquals("Waiting for peer", rideSyncLabel(SessionStatus.DISCONNECTED, sync))
            assertEquals("Synchronizing when connection returns", rideSyncLabel(SessionStatus.RECONNECTING, sync))
        }
        assertEquals("Synchronized", rideSyncLabel(SessionStatus.RIDE_ACTIVE, com.ridelink.app.sync.SyncState.SYNCED))
        assertEquals("Waiting for content", rideSyncLabel(SessionStatus.RIDE_ACTIVE, com.ridelink.app.sync.SyncState.WAITING_FOR_CONTENT))
    }

    // --- screen visibility (ARCHITECTURE §3 rule 1) -------------------------------------------------

    @Test
    fun `connected does not show Ride Mode`() {
        assertFalse(nextRideModeVisibility(previous = false, status = SessionStatus.CONNECTED, returnTo = null))
    }

    @Test
    fun `ride active always shows Ride Mode`() {
        assertTrue(nextRideModeVisibility(previous = false, status = SessionStatus.RIDE_ACTIVE, returnTo = null))
    }

    @Test
    fun `reconnecting back to ride active keeps Ride Mode showing`() {
        assertTrue(
            nextRideModeVisibility(previous = true, status = SessionStatus.RECONNECTING, returnTo = SessionStatus.RIDE_ACTIVE),
        )
    }

    @Test
    fun `reconnecting back to connected does not show Ride Mode`() {
        assertFalse(
            nextRideModeVisibility(previous = false, status = SessionStatus.RECONNECTING, returnTo = SessionStatus.CONNECTED),
        )
    }

    @Test
    fun `recovered — reconnecting resolves back to ride active`() {
        // The sequence a real recovery produces: RIDE_ACTIVE, then RECONNECTING(returnTo=RIDE_ACTIVE), then RIDE_ACTIVE again.
        var showing = nextRideModeVisibility(previous = false, status = SessionStatus.RIDE_ACTIVE, returnTo = null)
        showing = nextRideModeVisibility(showing, SessionStatus.RECONNECTING, SessionStatus.RIDE_ACTIVE)
        showing = nextRideModeVisibility(showing, SessionStatus.RIDE_ACTIVE, null)
        assertTrue(showing)
    }

    @Test
    fun `disconnected preserves whatever Ride Mode was showing — the budget-exhausted case`() {
        assertTrue(nextRideModeVisibility(previous = true, status = SessionStatus.DISCONNECTED, returnTo = null))
        assertFalse(nextRideModeVisibility(previous = false, status = SessionStatus.DISCONNECTED, returnTo = null))
    }

    // --- connection health ----------------------------------------------------------------------

    @Test
    fun `connection health maps status to the tri-state banner`() {
        assertEquals(RideConnectionHealth.HEALTHY, rideConnectionHealth(SessionStatus.RIDE_ACTIVE))
        assertEquals(RideConnectionHealth.DEGRADED, rideConnectionHealth(SessionStatus.RECONNECTING))
        assertEquals(RideConnectionHealth.DISCONNECTED, rideConnectionHealth(SessionStatus.DISCONNECTED))
    }

    // --- playback / mic / intercom mode ------------------------------------------------------------

    @Test
    fun `playing is reflected from the local player state`() {
        val ui = uiState(playerState = player(playing = true))
        assertTrue(ui.isPlaying)
    }

    @Test
    fun `paused is reflected from the local player state`() {
        val ui = uiState(playerState = player(playing = false))
        assertFalse(ui.isPlaying)
    }

    @Test
    fun `mic muted reads userMuted, never the wire mic_muted alone`() {
        val ui = uiState(voice = voice(userMuted = true, micAlwaysOpen = true))
        assertTrue(ui.micMuted)
    }

    @Test
    fun `mic unmuted`() {
        val ui = uiState(voice = voice(userMuted = false, micAlwaysOpen = true))
        assertFalse(ui.micMuted)
    }

    @Test
    fun `PTT mode is derived from the policy gate, never a second mode flag`() {
        val ui = uiState(voice = voice(policy = IntercomPolicy.MODE_C))
        assertTrue(ui.pttMode)
        assertEquals("Push-to-talk", ui.intercomModeLabel)
    }

    @Test
    fun `intercom disabled — Mode E`() {
        val ui = uiState(voice = voice(policy = IntercomPolicy.MODE_E, status = VoiceStatus.IDLE))
        assertTrue(ui.intercomDisabled)
        assertEquals("Intercom off", ui.intercomModeLabel)
    }

    // --- audio-route health ---------------------------------------------------------------------

    @Test
    fun `audio route degraded — local`() {
        val ui = uiState(voice = voice(mediaQuality = MediaQuality.REDUCED))
        assertTrue(ui.localAudioDegraded)
    }

    @Test
    fun `audio route degraded — peer`() {
        val ui = uiState(peerAudioState = audioStateMessage(mediaQuality = MediaQuality.REDUCED))
        assertTrue(ui.peerAudioDegraded)
    }

    @Test
    fun `audio route full quality on both sides is not flagged degraded`() {
        val ui =
            uiState(
                voice = voice(mediaQuality = MediaQuality.FULL),
                peerAudioState = audioStateMessage(mediaQuality = MediaQuality.FULL),
            )
        assertFalse(ui.localAudioDegraded)
        assertFalse(ui.peerAudioDegraded)
    }

    @Test
    fun `no peer AUDIO_STATE yet is not read as degraded`() {
        val ui = uiState(peerAudioState = null)
        assertFalse(ui.peerAudioDegraded)
    }

    // --- helpers ----------------------------------------------------------------------------------

    private fun uiState(
        status: SessionStatus = SessionStatus.RIDE_ACTIVE,
        reconnectCount: Int = 0,
        playerState: PlayerState = player(),
        currentEntry: LibraryEntry? = entry(),
        voice: VoiceDiagnostics = voice(),
        peerAudioState: AudioStateMessage? = null,
    ): RideModeUiState =
        rideModeUiState(
            status = status,
            reconnectCount = reconnectCount,
            playerState = playerState,
            currentEntry = currentEntry,
            voice = voice,
            peerAudioState = peerAudioState,
        )

    private fun player(playing: Boolean = false): PlayerState = PlayerState(playing = playing, durationMs = 1_000)

    private fun entry(): LibraryEntry =
        LibraryEntry(
            localEntryId = LocalEntryId.parse("00000000-0000-0000-0000-000000000001")!!,
            track =
                Track(
                    contentHash = null,
                    quickId = QuickId.parse("sha256:" + "aa".repeat(32))!!,
                    title = "Test Track",
                    artist = "Test Artist",
                    album = "Test Album",
                    durationMs = 180_000,
                    filename = "test.mp3",
                    codec = "mp3",
                    bitrateKbps = 320,
                    artworkRef = null,
                    sizeBytes = 4_000_000,
                ),
            location = LocalTrackLocation("file:///tmp/test.mp3"),
            decodeStatus = DecodeStatus.INDEXED,
            indexedAtMonoUs = 0,
            lastSeenAtMonoUs = 0,
        )

    private fun voice(
        userMuted: Boolean = false,
        micAlwaysOpen: Boolean = true,
        policy: IntercomPolicy = IntercomPolicy.MODE_A,
        status: VoiceStatus = VoiceStatus.ACTIVE,
        mediaQuality: MediaQuality = MediaQuality.FULL,
    ): VoiceDiagnostics =
        VoiceDiagnostics(
            status = status,
            userMuted = userMuted,
            policy = policy,
            localAudioOpen = micAlwaysOpen,
            // AudioRouteSnapshot.mediaQuality is derived from effectiveOutputProfile
            // (AudioRoute.kt), never a constructor field — so the test drives the profile that
            // produces the media quality it wants, exactly as the real route mapper does.
            route =
                com.ridelink.core.audiopolicy.AudioRouteSnapshot(
                    effectiveOutputProfile =
                        when (mediaQuality) {
                            MediaQuality.REDUCED -> AudioProfile.DUPLEX_WIDEBAND
                            MediaQuality.FULL -> AudioProfile.BUILTIN
                            MediaQuality.UNAVAILABLE -> AudioProfile.NONE
                            MediaQuality.UNKNOWN -> AudioProfile.UNKNOWN
                        },
                ),
        )

    @Suppress("LongParameterList")
    private fun audioStateMessage(mediaQuality: MediaQuality): AudioStateMessage =
        AudioStateMessage(
            revision = 1,
            revisionEpoch = AudioStateEpoch("a".repeat(32)),
            endpointClass = EndpointClass.BLUETOOTH,
            microphoneOpen = true,
            effectiveOutputProfile = AudioProfile.DUPLEX_WIDEBAND,
            effectiveInputProfile = AudioProfile.DUPLEX_WIDEBAND,
            effectiveOutputSampleRateHz = 16_000,
            effectiveInputSampleRateHz = 16_000,
            mediaQuality = mediaQuality,
            routeState = RouteState.STABLE,
            intercomMode = IntercomMode.PTT,
            confidence = AudioConfidence.ASSUMED,
        )
}
