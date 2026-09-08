package com.ridelink.app.sync

import com.ridelink.core.playback.IngressAdmission
import com.ridelink.core.playback.Phase5FrameKind
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [Phase5FrameQueue] on its own, without a coordinator — the mechanics ADR-024 Amendment A1
 * Finding C rests on. The mirror is `RideLinkPlatformTests.Phase5FrameQueueTests`, asserting the
 * same properties, and the *policy* both implementations consult is pinned for both by
 * `protocol/vectors/phase5-gates/`.
 *
 * The property that matters: **a frame this queue accepted is a frame the consumer will see, in
 * arrival order.** Its predecessor (`Channel(onBufferOverflow = DROP_OLDEST)`) satisfied neither
 * half — it evicted accepted frames, and it reported success while doing so.
 */
class Phase5FrameQueueTest {
    /** A test frame: a name, plus whether a newer sibling may supersede it. */
    private data class Frame(
        val name: String,
        val family: String? = null,
    )

    private fun queue(capacity: Int) =
        Phase5FrameQueue<Frame>(
            capacity = capacity,
            kindOf = { if (it.family == null) Phase5FrameKind.COMMAND else Phase5FrameKind.LATEST_WINS },
            coalesceKeyOf = { it.family },
        )

    @Test
    fun `frames are handed to the consumer in arrival order`() =
        runTest(StandardTestDispatcher()) {
            val subject = queue(capacity = 8)
            listOf("a", "b", "c").forEach { assertEquals(IngressAdmission.ADMIT, subject.offer(Frame(it))) }
            val drained = async { listOf(subject.take(), subject.take(), subject.take()) }
            runCurrent()
            assertEquals(listOf("a", "b", "c"), drained.await().map { it?.name })
        }

    /**
     * The whole of the finding. `DROP_OLDEST` would have evicted "a" here and returned success while
     * doing it; a refusal is the honest answer and the caller can act on it.
     */
    @Test
    fun `a full queue refuses the newcomer rather than evicting what it already accepted`() =
        runTest(StandardTestDispatcher()) {
            val subject = queue(capacity = 2)
            assertEquals(IngressAdmission.ADMIT, subject.offer(Frame("a")))
            assertEquals(IngressAdmission.ADMIT, subject.offer(Frame("b")))
            assertEquals(IngressAdmission.OVERFLOW, subject.offer(Frame("c")))
            assertEquals(1, subject.stats.overflowCount)
            assertEquals(2, subject.size, "the bound is real: a refusal never grows the queue")

            val drained = async { listOf(subject.take(), subject.take()) }
            runCurrent()
            assertEquals(listOf("a", "b"), drained.await().map { it?.name }, "what was accepted is what arrives")
        }

    /** A latest-wins frame supersedes its own older sibling, and takes the newer arrival position. */
    @Test
    fun `a latest-wins frame coalesces onto its own family and keeps the newest position`() =
        runTest(StandardTestDispatcher()) {
            val subject = queue(capacity = 2)
            assertEquals(IngressAdmission.ADMIT, subject.offer(Frame("report-1", family = "REPORT")))
            assertEquals(IngressAdmission.ADMIT, subject.offer(Frame("command")))
            assertEquals(IngressAdmission.COALESCE, subject.offer(Frame("report-2", family = "REPORT")))
            assertEquals(1, subject.stats.coalescedCount)
            assertEquals(0, subject.stats.overflowCount)

            val drained = async { listOf(subject.take(), subject.take()) }
            runCurrent()
            assertEquals(
                listOf("command", "report-2"),
                drained.await().map { it?.name },
                "the newest report replaced the oldest, at the newest arrival position",
            )
        }

    /** Two different latest-wins families never supersede each other. */
    @Test
    fun `a frame of one latest-wins family never supersedes another`() =
        runTest(StandardTestDispatcher()) {
            val subject = queue(capacity = 1)
            assertEquals(IngressAdmission.ADMIT, subject.offer(Frame("snapshot", family = "SNAPSHOT")))
            assertEquals(
                IngressAdmission.OVERFLOW,
                subject.offer(Frame("state", family = "STATE")),
                "a POSITION_REPORT must never supersede a PLAYBACK_STATE, or the reverse",
            )
        }

    /** An authoritative command is never superseded, no matter what is queued. */
    @Test
    fun `a command is never coalesced`() =
        runTest(StandardTestDispatcher()) {
            val subject = queue(capacity = 1)
            assertEquals(IngressAdmission.ADMIT, subject.offer(Frame("first")))
            assertEquals(IngressAdmission.OVERFLOW, subject.offer(Frame("second")))
        }

    /**
     * Idleness means what it says: nothing buffered. The iOS mirror expresses the same fact as
     * "a consumer is parked", which its continuation-based wait makes directly observable.
     */
    @Test
    fun `idleness means nothing is buffered`() =
        runTest(StandardTestDispatcher()) {
            val subject = queue(capacity = 4)
            assertTrue(subject.isIdle, "a fresh queue is idle")
            subject.offer(Frame("a"))
            assertFalse(subject.isIdle, "a buffered frame is work outstanding")
            val drained = async { subject.take() }
            runCurrent()
            drained.await()
            assertTrue(subject.isIdle, "and it is idle again once the consumer has taken it")
        }

    /** A parked consumer is handed the next frame directly, without it ever touching the buffer. */
    @Test
    fun `a parked consumer is resumed by the next frame`() =
        runTest(StandardTestDispatcher()) {
            val subject = queue(capacity = 4)
            val parked = async { subject.take() }
            runCurrent()
            assertEquals(IngressAdmission.ADMIT, subject.offer(Frame("late")))
            runCurrent()
            assertEquals("late", parked.await()?.name)
            assertEquals(0, subject.size)
        }

    /** Teardown releases a parked consumer, so the drain loop ends rather than waiting forever. */
    @Test
    fun `close releases a parked consumer and refuses later frames`() =
        runTest(StandardTestDispatcher()) {
            val subject = queue(capacity = 4)
            val parked = async { subject.take() }
            runCurrent()
            subject.close()
            runCurrent()
            assertNull(parked.await(), "a closed queue ends the drain instead of stalling it")
            assertEquals(IngressAdmission.OVERFLOW, subject.offer(Frame("after")))
        }

    /** Whatever the offer sequence, the consumer's stream is a subsequence of it, in order. */
    @Test
    fun `the drained sequence is always an in-order subsequence of what was offered`() =
        runTest(StandardTestDispatcher()) {
            val subject = queue(capacity = 3)
            val offered = mutableListOf<String>()
            val accepted = mutableListOf<String>()
            for (index in 0 until 40) {
                val frame = if (index % 4 == 0) Frame("r$index", family = "REPORT") else Frame("c$index")
                offered.add(frame.name)
                if (subject.offer(frame) != IngressAdmission.OVERFLOW) accepted.add(frame.name)
            }
            subject.close()
            val drained = async { drainAll(subject) }
            runCurrent()
            val result = drained.await()

            // Every drained frame was offered, in the order it was offered.
            var cursor = -1
            for (name in result) {
                val position = offered.indexOf(name)
                assertTrue(position > cursor, "arrival order must hold: $name arrived out of sequence")
                cursor = position
            }
            assertTrue(result.isNotEmpty())
            assertTrue(
                result.none { it.startsWith("c") && it !in accepted },
                "no command reaches the consumer that the queue did not accept",
            )
        }

    private suspend fun drainAll(subject: Phase5FrameQueue<Frame>): List<String> {
        val drained = mutableListOf<String>()
        while (true) {
            val item = subject.take() ?: return drained
            drained.add(item.name)
        }
    }
}
