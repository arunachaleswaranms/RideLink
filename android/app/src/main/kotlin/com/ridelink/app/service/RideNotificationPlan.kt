package com.ridelink.app.service

/**
 * The facts the ride notifications are drawn from — each one owned elsewhere and only *read* here.
 *
 * [intercomActive]/[musicActive] are [RideForegroundService]'s own type flags (what the service is
 * holding a foreground type for). [microphoneOpen]/[muted] come from the voice controller's
 * diagnostics. The track fields come from the one Media3 player the `MediaSession` wraps.
 */
data class RideNotificationInputs(
    val intercomActive: Boolean,
    val microphoneOpen: Boolean,
    val muted: Boolean,
    val musicActive: Boolean,
    val musicPlaying: Boolean = false,
    val trackTitle: String? = null,
    val trackArtist: String? = null,
    /** Changes whenever the player's metadata, duration or item changes — see [MediaNotice.revision]. */
    val mediaRevision: Int = 0,
)

/** Which of the two notifications a plan places in the foreground slot. */
enum class RideSurface { INTERCOM, MEDIA }

enum class IntercomNoticeAction { MUTE, UNMUTE, END_INTERCOM }

/** The intercom's own notification: plain, never `MediaStyle`, so its actions are rendered. */
data class IntercomNotice(
    val title: String,
    val text: String,
    val actions: List<IntercomNoticeAction>,
)

/**
 * The music notification, carrying the one `MediaSession`'s token. SystemUI draws the shade and
 * lock-screen media card from the session, but only re-reads it when this notification is posted —
 * so [revision] is part of its identity, and a new track is a new notice that must be re-posted
 * (STATUS §4 problem 116).
 */
data class MediaNotice(
    val title: String,
    val text: String,
    val playing: Boolean,
    val revision: Int,
)

/**
 * What the ride notifications say and which one holds the foreground slot — a pure function of
 * [RideNotificationInputs], so every combination is a unit test rather than a branch inside framework
 * code (Phase 9A.5 §14).
 *
 * - **Music only:** one media notification. Nothing mentions an intercom or a microphone, because
 *   neither is in use (STATUS §4 problem 112: it used to say "RideLink intercom active" /
 *   "Microphone open for the intercom" over music-only playback).
 * - **Intercom only:** one intercom notification, with Mute/Unmute (once the microphone is open)
 *   and End intercom.
 * - **Both:** the intercom notification holds the foreground slot and the media notification is
 *   posted beside it. Two notifications, one service, one `MediaSession` (ADR-022 Amendment A1).
 * - **Neither:** `null` — the service stops being foreground, exactly as before.
 *
 * Problem 113 is why the intercom is never folded into the media notification: on Android 13+
 * SystemUI renders a `MediaStyle` notification as the media player, with buttons from the session's
 * playback state, and ignores the notification's own actions — Mute and End were never shown.
 *
 * Nothing here names the other phone, its user or a device: a notification is readable on a lock
 * screen by anyone holding the phone (ARCHITECTURE §11). A track title is shown, as the media card
 * already shows it from the session.
 */
object RideNotificationPlanner {
    fun plan(inputs: RideNotificationInputs): RideNotificationPlan? {
        val intercom = if (inputs.intercomActive) intercomNotice(inputs) else null
        val media = if (inputs.musicActive) mediaNotice(inputs) else null
        return when {
            intercom != null -> RideNotificationPlan(RideSurface.INTERCOM, intercom, media)
            media != null -> RideNotificationPlan(RideSurface.MEDIA, null, media)
            else -> null
        }
    }

    private fun intercomNotice(inputs: RideNotificationInputs): IntercomNotice =
        IntercomNotice(
            title = "Intercom on",
            text =
                when {
                    !inputs.microphoneOpen -> "Starting the microphone…"
                    inputs.muted -> "Microphone muted"
                    else -> "Microphone on"
                },
            actions =
                buildList {
                    if (inputs.microphoneOpen) add(if (inputs.muted) IntercomNoticeAction.UNMUTE else IntercomNoticeAction.MUTE)
                    add(IntercomNoticeAction.END_INTERCOM)
                },
        )

    private fun mediaNotice(inputs: RideNotificationInputs): MediaNotice =
        MediaNotice(
            title = inputs.trackTitle?.takeIf { it.isNotBlank() } ?: "RideLink",
            text =
                inputs.trackArtist?.takeIf { it.isNotBlank() }
                    ?: if (inputs.musicPlaying) "Playing music" else "Music paused",
            playing = inputs.musicPlaying,
            revision = inputs.mediaRevision,
        )
}

data class RideNotificationPlan(
    val foreground: RideSurface,
    val intercom: IntercomNotice?,
    val media: MediaNotice?,
)
