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
        /**
         * The authentication generation this frame was produced under — what Amendment A6 makes any
         * loss it causes belong to.
         */
        val generation: Long = 1,
    )

    private fun queue(capacity: Int) =
        Phase5FrameQueue<Frame>(
            capacity = capacity,
            kindOf = { if (it.family == null) Phase5FrameKind.COMMAND else Phase5FrameKind.LATEST_WINS },
            coalesceKeyOf = { it.family },
            generationOf = { it.generation },
        )

    /**
     * Every loss the queue is holding, flattened — the shape the pre-A6 `stats` property reported
     * without saying whose the losses were.
     */
    private fun totals(subject: Phase5FrameQueue<Frame>): Pair<Int, Int> {
        val losses = subject.drainLosses()
        return losses.sumOf { it.overflowCount } to losses.sumOf { it.coalescedCount }
    }

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
            assertEquals(1, totals(subject).first)
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
            val counted = totals(subject)
            assertEquals(1, counted.second)
            assertEquals(0, counted.first)

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

    // --- Amendment A7: the loss ledger under non-monotonic generation arrival --------------------

    /**
     * **The ledger defect A7 found, in full.**
     *
     * A6 bucketed losses by *adjacency* — a new bucket whenever the incoming generation differed
     * from the newest one — and, once past eight buckets, evicted the oldest **by arrival** and
     * folded its counts into the next oldest by arrival. Both steps rested on one written
     * assumption: that generations arrive monotonically, so the two oldest buckets are both
     * retired.
     *
     * A7 makes that assumption false, and does so as a *consequence of its own fix*. Binding each
     * inbound frame to the connection that authorised its read means a read loop whose session has
     * ended still dispatches the frame it had already read — after the successor session's read
     * loop has begun offering. `A, B, A` reaches [Phase5FrameQueue.offer], so buckets alternate,
     * nine buckets can be as few as **two** generations, and the fold target is then the newest
     * generation — which may be **live**.
     *
     * The consequence is not cosmetic. A follower answers a live-generation loss by latching
     * `playbackDesynchronized`/`queueDesynchronized`, which decide whether incremental authoritative
     * commands are applied at all. Folding a dead session's loss into the live generation is
     * therefore the *exact* cross-session halt A6 existed to remove, re-entering through the
     * ledger's back door.
     *
     * Against the A6 fold this asserts `0` retired losses attributed to generation 9 and observes
     * all eight of generation 1's folded onto it.
     */
    @Test
    fun `an alternating generation run never folds a retired loss onto the live generation`() =
        runTest(StandardTestDispatcher()) {
            val subject = queue(capacity = 1)
            subject.offer(Frame("occupant"))

            // Nine distinct generations' worth of refusals, but delivered *alternating* with the
            // newest one — the arrival order A7's own fix makes reachable.
            val live = 9L
            for (generation in 1L..8L) {
                repeat(2) { assertEquals(IngressAdmission.OVERFLOW, subject.offer(Frame("old", generation = generation))) }
                assertEquals(IngressAdmission.OVERFLOW, subject.offer(Frame("live", generation = live)))
            }

            val losses = subject.drainLosses()
            // The correctness property first, because it is the one that halts a ride: the live
            // generation must own its own eight refusals and not one of anybody else's.
            assertEquals(
                8,
                losses.filter { it.generation == live }.sumOf { it.overflowCount },
                "a retired generation's loss was folded onto the live one — the A6 cross-session halt, via the ledger",
            )
            assertTrue(
                losses.filter { it.generation != live }.all { it.generation < live },
                "everything a fold could target is strictly older than the live generation",
            )
            assertEquals(
                24,
                losses.sumOf { it.overflowCount },
                "and nothing is discarded — every refusal is still accounted for somewhere",
            )
            assertTrue(
                losses.size <= Phase5FrameQueue.MAX_LOSS_GENERATIONS,
                "the ledger stays bounded: ${losses.size} buckets",
            )
            assertEquals(
                losses.size,
                losses.map { it.generation }.distinct().size,
                "one bucket per generation — the bound must count generations, not adjacency runs",
            )
        }

    /**
     * The same-generation half the fix must not weaken: a generation's own events accumulate into
     * its own bucket wherever they arrive, so ordering within a generation is preserved as the
     * counts it is expressed by, and a late arrival never opens a second bucket for a generation
     * that already has one.
     */
    @Test
    fun `a late arrival joins its own generation's bucket rather than opening a second`() =
        runTest(StandardTestDispatcher()) {
            val subject = queue(capacity = 1)
            subject.offer(Frame("occupant"))

            subject.offer(Frame("a1", generation = 1))
            subject.offer(Frame("b1", generation = 2))
            subject.offer(Frame("a2", generation = 1)) // the read loop of the ended session, late

            val losses = subject.drainLosses()
            assertEquals(listOf(1L, 2L), losses.map { it.generation }, "two generations, in first-arrival order")
            assertEquals(2, losses.first { it.generation == 1L }.overflowCount, "both of generation 1's are its own")
            assertEquals(1, losses.first { it.generation == 2L }.overflowCount, "and generation 2 keeps only its own")
        }

    /** Coalescing obeys the same ownership rule as overflow — it is an event, and it has an owner. */
    @Test
    fun `coalescing is attributed by generation under non-monotonic arrival too`() =
        runTest(StandardTestDispatcher()) {
            val subject = queue(capacity = 1)
            assertEquals(IngressAdmission.ADMIT, subject.offer(Frame("r0", family = "REPORT", generation = 1)))
            assertEquals(IngressAdmission.COALESCE, subject.offer(Frame("r1", family = "REPORT", generation = 2)))
            assertEquals(IngressAdmission.COALESCE, subject.offer(Frame("r2", family = "REPORT", generation = 1)))

            val losses = subject.drainLosses()
            assertEquals(1, losses.first { it.generation == 1L }.coalescedCount, "the late generation 1 event is generation 1's")
            assertEquals(1, losses.first { it.generation == 2L }.coalescedCount)
            assertEquals(0, losses.sumOf { it.overflowCount }, "a coalesce is not a refusal")
        }

    private suspend fun drainAll(subject: Phase5FrameQueue<Frame>): List<String> {
        val drained = mutableListOf<String>()
        while (true) {
            val item = subject.take() ?: return drained
            drained.add(item.name)
        }
    }
}
