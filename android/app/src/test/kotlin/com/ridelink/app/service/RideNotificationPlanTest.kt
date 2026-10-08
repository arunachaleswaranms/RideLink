package com.ridelink.app.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * STATUS §4 problems 112, 113 and 116 at the level where the decisions live: what each combination
 * of intercom, microphone, mute and music shows, and when the media notification must be re-posted.
 */
class RideNotificationPlanTest {
    private fun plan(
        intercom: Boolean = false,
        micOpen: Boolean = false,
        muted: Boolean = false,
        music: Boolean = false,
        playing: Boolean = false,
        title: String? = null,
        artist: String? = null,
        revision: Int = 0,
    ) = RideNotificationPlanner.plan(RideNotificationInputs(intercom, micOpen, muted, music, playing, title, artist, revision))

    private fun RideNotificationPlan.allText(): String =
        listOfNotNull(intercom?.title, intercom?.text, media?.title, media?.text).joinToString(" ").lowercase()

    @Test
    fun `music only says nothing about an intercom or a microphone`() {
        val plan = assertNotNull(plan(music = true, playing = true, title = "Coast Road", artist = "Evening Roads"))

        assertEquals(RideSurface.MEDIA, plan.foreground)
        assertNull(plan.intercom, "problem 112: no intercom notification while no intercom runs")
        assertEquals(MediaNotice("Coast Road", "Evening Roads", playing = true, revision = 0), plan.media)
        assertFalse("intercom" in plan.allText())
        assertFalse("microphone" in plan.allText())
    }

    @Test
    fun `music only without track metadata still names nothing it is not doing`() {
        assertEquals("Playing music", plan(music = true, playing = true)?.media?.text)
        assertEquals("Music paused", plan(music = true, playing = false)?.media?.text)
        assertEquals("RideLink", plan(music = true, title = " ")?.media?.title)
    }

    @Test
    fun `intercom only shows mute and end on its own notification`() {
        val plan = assertNotNull(plan(intercom = true, micOpen = true))

        assertEquals(RideSurface.INTERCOM, plan.foreground)
        assertNull(plan.media)
        assertEquals("Microphone on", plan.intercom?.text)
        assertEquals(listOf(IntercomNoticeAction.MUTE, IntercomNoticeAction.END_INTERCOM), plan.intercom?.actions)
    }

    @Test
    fun `a muted intercom says so and offers unmute`() {
        val intercom = plan(intercom = true, micOpen = true, muted = true)?.intercom

        assertEquals("Microphone muted", intercom?.text)
        assertEquals(listOf(IntercomNoticeAction.UNMUTE, IntercomNoticeAction.END_INTERCOM), intercom?.actions)
    }

    @Test
    fun `an intercom whose microphone is not open yet offers end but no mute`() {
        val intercom = plan(intercom = true, micOpen = false)?.intercom

        assertEquals("Starting the microphone…", intercom?.text)
        assertEquals(listOf(IntercomNoticeAction.END_INTERCOM), intercom?.actions)
    }

    @Test
    fun `music and intercom together are two notifications, the intercom holding the foreground slot`() {
        val plan = assertNotNull(plan(intercom = true, micOpen = true, music = true, playing = true, title = "Night ferry"))

        assertEquals(RideSurface.INTERCOM, plan.foreground)
        assertNotNull(plan.intercom)
        assertEquals("Night ferry", plan.media?.title)
    }

    @Test
    fun `neither active means no notification at all`() {
        assertNull(plan())
        assertNull(plan(micOpen = true, muted = true), "stale voice facts alone never post anything")
    }

    @Test
    fun `problem 116 - a new track is a new media notice, the same track is the same notice`() {
        val first = plan(music = true, playing = true, title = "Coast Road", revision = 1)?.media
        val second = plan(music = true, playing = true, title = "Night ferry", revision = 2)?.media
        val artworkOnly = plan(music = true, playing = true, title = "Coast Road", revision = 3)?.media
        val again = plan(music = true, playing = true, title = "Coast Road", revision = 1)?.media

        assertNotEquals(first, second, "a title change must re-post")
        assertNotEquals(first, artworkOnly, "artwork or duration arriving under the same title must re-post too")
        assertEquals(first, again, "an unchanged notice must not be re-posted")
    }

    @Test
    fun `every combination keeps the invariants`() {
        for (bits in 0 until 32) {
            val intercom = bits and 1 != 0
            val micOpen = bits and 2 != 0
            val muted = bits and 4 != 0
            val music = bits and 8 != 0
            val playing = bits and 16 != 0
            val plan = plan(intercom, micOpen, muted, music, playing)
            val label = "intercom=$intercom mic=$micOpen muted=$muted music=$music playing=$playing"

            assertEquals(!intercom && !music, plan == null, label)
            if (plan == null) continue
            assertEquals(intercom, plan.intercom != null, label)
            assertEquals(music, plan.media != null, label)
            assertEquals(if (intercom) RideSurface.INTERCOM else RideSurface.MEDIA, plan.foreground, label)
            if (!intercom) {
                assertFalse("microphone" in plan.allText() || "intercom" in plan.allText(), label)
            } else {
                val actions = plan.intercom!!.actions
                assertTrue(IntercomNoticeAction.END_INTERCOM in actions, label)
                assertEquals(micOpen, IntercomNoticeAction.MUTE in actions || IntercomNoticeAction.UNMUTE in actions, label)
                if (micOpen) assertEquals(muted, IntercomNoticeAction.UNMUTE in actions, label)
            }
        }
    }
}
