package com.ridelink.app.music

import com.ridelink.core.library.LocalTrackLocation
import com.ridelink.core.model.LocalEntryId
import com.ridelink.core.player.LocalQueueAction
import com.ridelink.core.player.LocalQueueItem
import com.ridelink.core.player.LocalQueueState
import com.ridelink.core.player.PlaybackCommand
import com.ridelink.core.player.Player
import com.ridelink.core.player.PlayerState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-024 Amendment A15 (PR #18 review round 2): **a proof taken before an async boundary does not
 * authorise the effect after it.** A local edit is admitted, and its queue mutation applied, under one
 * local-ownership lifetime; its player effects run later. Each test parks that work at a real
 * suspension point — the entry lookup before `Load`, inside `Load` before `Play`, or before a launched
 * `Stop` runs — moves ownership, releases it, and asserts the player was not touched.
 *
 * [LifetimeGate] reproduces the synchronisation owner's lifetime rule exactly (every ownership flip
 * advances it; local admissions are valid only within their own lifetime). That rule itself is proved
 * against the real `SyncPlaybackCoordinator` and `SyncPlaybackGateAdapter` in
 * `LocalQueueEditOwnershipTest` and the A14 test. No test sleeps; the test scheduler decides ordering.
 */
class LocalPlaybackEffectsLifetimeTest {
    private fun id(n: Int) = LocalEntryId("ffffffff-0000-0000-0000-00000000000$n")

    private val threeWithFirstCurrent =
        LocalQueueState((1..3).map { LocalQueueItem("q$it", id(it), it.toLong()) }, currentId = "q1")

    @Test
    fun `1 select - parked before Load, synchronised activation, released - no stale Load or Play`() =
        runTest(StandardTestDispatcher()) {
            val h = Harness(this)
            h.resolver.park(id(2))
            val edit = assertNotNull(LocalQueueEdits.reduce(threeWithFirstCurrent, listOf(LocalQueueAction.Select("q2")), h.gate))
            assertEquals("q2", edit.state.currentId, "the queue mutation itself was admitted under local ownership")
            h.effects.run(edit.effects, edit.admission)
            runCurrent()
            assertTrue(h.resolver.isParked(id(2)), "premise: parked before the Load proof")

            h.gate.activateSynchronised()
            h.resolver.release(id(2))
            runCurrent()

            assertEquals(emptyList(), h.player.calls, "stale local work touched the player after synchronised activation")
        }

    @Test
    fun `2 clear - parked before Stop, synchronised activation, released - no stale Stop`() =
        runTest(StandardTestDispatcher()) {
            val h = Harness(this)
            val edit = assertNotNull(LocalQueueEdits.reduce(threeWithFirstCurrent, listOf(LocalQueueAction.Clear), h.gate))
            h.effects.run(edit.effects, edit.admission) // launched, not yet run: parked before the Stop proof

            h.gate.activateSynchronised()
            runCurrent()

            assertEquals(emptyList(), h.player.calls)
        }

    @Test
    fun `3 remove current - successor parked before Load, synchronised activation - no q3 Load, Play or Stop`() =
        runTest(StandardTestDispatcher()) {
            val h = Harness(this)
            h.resolver.park(id(3))
            val queue = threeWithFirstCurrent.copy(currentId = "q2")
            val edit = assertNotNull(LocalQueueEdits.reduce(queue, listOf(LocalQueueAction.Remove("q2")), h.gate))
            assertEquals("q3", edit.state.currentId, "premise: LocalQueue hands playback to the successor")
            h.effects.run(edit.effects, edit.admission)
            runCurrent()

            h.gate.activateSynchronised()
            h.resolver.release(id(3))
            runCurrent()

            assertEquals(emptyList(), h.player.calls)
        }

    @Test
    fun `4 ABA - local A, synchronised B, local C - A's parked work stays dead, C's fresh edit runs`() =
        runTest(StandardTestDispatcher()) {
            val h = Harness(this)
            h.resolver.park(id(2))
            val underA = assertNotNull(LocalQueueEdits.reduce(threeWithFirstCurrent, listOf(LocalQueueAction.Select("q2")), h.gate))
            h.effects.run(underA.effects, underA.admission)
            runCurrent()

            h.gate.activateSynchronised() // B
            h.gate.returnToLocal() // C: local again — but a different lifetime
            assertTrue(!h.gate.localQueueLocked(), "premise: ownership is local again")
            h.resolver.release(id(2))
            runCurrent()
            assertEquals(emptyList(), h.player.calls, "\"local again\" resurrected A's stale effect")

            val underC = assertNotNull(LocalQueueEdits.reduce(threeWithFirstCurrent, listOf(LocalQueueAction.Select("q3")), h.gate))
            h.effects.run(underC.effects, underC.admission)
            runCurrent()
            assertEquals(listOf(h.loadOf(3), PlaybackCommand.Play), h.player.calls, "a fresh edit under C works normally")
        }

    @Test
    fun `5 load then play - Play is not authorised by the proof taken before Load`() =
        runTest(StandardTestDispatcher()) {
            val h = Harness(this)
            h.player.parkOn = h.loadOf(4)
            val playNow = listOf(LocalQueueAction.Add(LocalQueueItem("q4", id(4), 4)), LocalQueueAction.Select("q4"))
            val edit = assertNotNull(LocalQueueEdits.reduce(threeWithFirstCurrent, playNow, h.gate))
            h.effects.run(edit.effects, edit.admission)
            runCurrent()
            assertTrue(h.player.parked.isActive, "premise: parked inside Load, before Play")

            h.gate.activateSynchronised()
            h.player.parked.complete(Unit)
            runCurrent()

            assertEquals(listOf<PlaybackCommand>(h.loadOf(4)), h.player.calls, "Play ran on a proof taken before the Load")
        }

    @Test
    fun `6 stop - removing the last current entry, parked before Stop, admission invalidated - no Stop`() =
        runTest(StandardTestDispatcher()) {
            val h = Harness(this)
            val lastCurrent = threeWithFirstCurrent.copy(currentId = "q3")
            val edit = assertNotNull(LocalQueueEdits.reduce(lastCurrent, listOf(LocalQueueAction.Remove("q3")), h.gate))
            assertEquals(null, edit.state.currentId, "premise: LocalQueue stops when the last current entry goes")
            h.effects.run(edit.effects, edit.admission)

            h.gate.activateSynchronised()
            runCurrent()

            assertEquals(emptyList(), h.player.calls)
        }

    @Test
    fun `7 one local lifetime - select, remove current, clear, move, add and play-now all work`() =
        runTest(StandardTestDispatcher()) {
            val h = Harness(this)

            fun applyEdit(
                state: LocalQueueState,
                actions: List<LocalQueueAction>,
            ): LocalQueueState {
                val edit = assertNotNull(LocalQueueEdits.reduce(state, actions, h.gate))
                h.effects.run(edit.effects, edit.admission)
                runCurrent()
                return edit.state
            }

            var queue = applyEdit(threeWithFirstCurrent, listOf(LocalQueueAction.Select("q2")))
            assertEquals(listOf(h.loadOf(2), PlaybackCommand.Play), h.player.calls)
            h.player.calls.clear()

            queue = applyEdit(queue, listOf(LocalQueueAction.Remove("q2")))
            assertEquals("q3", queue.currentId)
            assertEquals(listOf(h.loadOf(3), PlaybackCommand.Play), h.player.calls)
            h.player.calls.clear()

            queue = applyEdit(queue, listOf(LocalQueueAction.Move("q3", 0)))
            assertEquals(listOf("q3", "q1"), queue.items.map { it.id })
            queue = applyEdit(queue, listOf(LocalQueueAction.Add(LocalQueueItem("q5", id(5), 5))))
            assertEquals(3, queue.items.size)
            assertEquals(emptyList(), h.player.calls, "move and add have no player effect")

            queue = applyEdit(queue, listOf(LocalQueueAction.Add(LocalQueueItem("q6", id(6), 6)), LocalQueueAction.Select("q6")))
            assertEquals(listOf(h.loadOf(6), PlaybackCommand.Play), h.player.calls)
            h.player.calls.clear()

            applyEdit(queue, listOf(LocalQueueAction.Clear))
            assertEquals(listOf<PlaybackCommand>(PlaybackCommand.Stop), h.player.calls)

            val pause = assertNotNull(LocalQueueEdits.admit(h.gate))
            h.effects.pause(pause)
            runCurrent()
            assertEquals(PlaybackCommand.Pause, h.player.calls.last())
        }

    @Test
    fun `a local press admitted before synchronised activation does not pause synchronised playback`() =
        runTest(StandardTestDispatcher()) {
            val h = Harness(this)
            val admission = assertNotNull(LocalQueueEdits.admit(h.gate))
            h.effects.pause(admission)
            h.gate.activateSynchronised()
            runCurrent()
            assertEquals(emptyList(), h.player.calls)
            assertNull(LocalQueueEdits.admit(h.gate), "and no fresh local admission while synchronised")
        }

    // --- Round 3: latest local intent within one ownership lifetime ---------------------------------

    private fun TestScope.applyEdit(
        h: Harness,
        state: LocalQueueState,
        vararg actions: LocalQueueAction,
    ): LocalQueueState {
        val edit = assertNotNull(LocalQueueEdits.reduce(state, actions.toList(), h.gate))
        h.effects.run(edit.effects, edit.admission)
        runCurrent()
        return edit.state
    }

    @Test
    fun `R3-1 select A parked before Load, Clear stops, A released - A never loads or plays`() =
        runTest(StandardTestDispatcher()) {
            val h = Harness(this)
            h.resolver.park(id(2))
            val queue = applyEdit(h, threeWithFirstCurrent, LocalQueueAction.Select("q2"))
            assertTrue(h.resolver.isParked(id(2)), "premise: A parked before its Load")

            applyEdit(h, queue, LocalQueueAction.Clear)
            assertEquals(listOf<PlaybackCommand>(PlaybackCommand.Stop), h.player.calls, "premise: the Stop completed")
            h.resolver.release(id(2))
            runCurrent()

            assertEquals(listOf<PlaybackCommand>(PlaybackCommand.Stop), h.player.calls, "A loaded or played after Clear")
        }

    @Test
    fun `R3-2 select A parked, select B starts, A released - A cannot replace B`() =
        runTest(StandardTestDispatcher()) {
            val h = Harness(this)
            h.resolver.park(id(2))
            val queue = applyEdit(h, threeWithFirstCurrent, LocalQueueAction.Select("q2"))
            applyEdit(h, queue, LocalQueueAction.Select("q3"))
            assertEquals(listOf(h.loadOf(3), PlaybackCommand.Play), h.player.calls, "premise: B started")

            h.resolver.release(id(2))
            runCurrent()

            assertEquals(listOf(h.loadOf(3), PlaybackCommand.Play), h.player.calls, "A replaced B")
        }

    @Test
    fun `R3-3a select A parked before Load, Pause, released - A loads but its Play cannot defeat the pause`() =
        runTest(StandardTestDispatcher()) {
            val h = Harness(this)
            h.resolver.park(id(2))
            applyEdit(h, threeWithFirstCurrent, LocalQueueAction.Select("q2"))
            h.effects.pause(assertNotNull(LocalQueueEdits.admit(h.gate)))
            runCurrent()

            h.resolver.release(id(2))
            runCurrent()

            assertEquals(listOf(PlaybackCommand.Pause, h.loadOf(2)), h.player.calls, "the older Play defeated the newer pause")
        }

    @Test
    fun `R3-3b select A parked inside Load, Pause, released - no Play after the pause`() =
        runTest(StandardTestDispatcher()) {
            val h = Harness(this)
            h.player.parkOn = h.loadOf(2)
            applyEdit(h, threeWithFirstCurrent, LocalQueueAction.Select("q2"))
            assertTrue(h.player.parked.isActive, "premise: parked inside Load")
            h.effects.pause(assertNotNull(LocalQueueEdits.admit(h.gate)))
            runCurrent()

            h.player.parked.complete(Unit)
            runCurrent()

            assertEquals(listOf(h.loadOf(2), PlaybackCommand.Pause), h.player.calls, "the older Play defeated the newer pause")
        }

    @Test
    fun `R3-4 ABA - A parked under local A, newer local work under C runs, A stays dead`() =
        runTest(StandardTestDispatcher()) {
            val h = Harness(this)
            h.resolver.park(id(2))
            applyEdit(h, threeWithFirstCurrent, LocalQueueAction.Select("q2"))
            h.gate.activateSynchronised()
            h.gate.returnToLocal()

            applyEdit(h, threeWithFirstCurrent, LocalQueueAction.Select("q3"))
            h.resolver.release(id(2))
            runCurrent()

            assertEquals(listOf(h.loadOf(3), PlaybackCommand.Play), h.player.calls)
        }

    @Test
    fun `R3-5 add, move and removing a non-current entry do not invalidate a selection in flight`() =
        runTest(StandardTestDispatcher()) {
            val h = Harness(this)
            h.resolver.park(id(2))
            var queue = applyEdit(h, threeWithFirstCurrent, LocalQueueAction.Select("q2"))
            queue = applyEdit(h, queue, LocalQueueAction.Add(LocalQueueItem("q4", id(4), 4)))
            queue = applyEdit(h, queue, LocalQueueAction.Move("q4", 0))
            applyEdit(h, queue, LocalQueueAction.Remove("q1"))
            assertEquals(emptyList(), h.player.calls, "premise: none of them touches the player")

            h.resolver.release(id(2))
            runCurrent()

            assertEquals(listOf(h.loadOf(2), PlaybackCommand.Play), h.player.calls, "a harmless edit cancelled the selection")
        }

    @Test
    fun `R3-5 a seek on the selection in flight does not cancel it, and a press after it plays normally`() =
        runTest(StandardTestDispatcher()) {
            val h = Harness(this)
            h.resolver.park(id(2))
            applyEdit(h, threeWithFirstCurrent, LocalQueueAction.Select("q2"))
            h.effects.seek(1_000, assertNotNull(LocalQueueEdits.admit(h.gate)))
            runCurrent()
            h.resolver.release(id(2))
            runCurrent()

            assertEquals(listOf(PlaybackCommand.Seek(1_000), h.loadOf(2), PlaybackCommand.Play), h.player.calls)
        }

    // --- Round 4: a Stop belongs to its selection, and the newest Play intent is carried ------------

    /** Issues an edit's effects **without** running them, so a later press is queued before they run. */
    private fun issueEdit(
        h: Harness,
        state: LocalQueueState,
        vararg actions: LocalQueueAction,
    ): LocalQueueState {
        val edit = assertNotNull(LocalQueueEdits.reduce(state, actions.toList(), h.gate))
        h.effects.run(edit.effects, edit.admission)
        return edit.state
    }

    @Test
    fun `R4-1 Clear a playing queue, Pause before its Stop runs - the queue stays empty and playback stops`() =
        runTest(StandardTestDispatcher()) {
            val h = Harness(this)
            val cleared = issueEdit(h, threeWithFirstCurrent, LocalQueueAction.Clear)
            h.effects.pause(assertNotNull(LocalQueueEdits.admit(h.gate)))
            runCurrent()

            assertTrue(cleared.items.isEmpty())
            assertEquals(listOf(PlaybackCommand.Stop, PlaybackCommand.Pause), h.player.calls, "a later Pause discarded the Clear's Stop")
        }

    @Test
    fun `R4-2 Clear a playing queue, Play on the empty queue before its Stop runs - nothing resumes`() =
        runTest(StandardTestDispatcher()) {
            val h = Harness(this)
            val cleared = issueEdit(h, threeWithFirstCurrent, LocalQueueAction.Clear)
            issueEdit(h, cleared, LocalQueueAction.Play)
            runCurrent()

            assertEquals(listOf<PlaybackCommand>(PlaybackCommand.Stop), h.player.calls, "the cleared track resumed or kept playing")
        }

    @Test
    fun `R4-5 Select A parked, then Play - A starts once loaded and the previous track never plays in between`() =
        runTest(StandardTestDispatcher()) {
            val h = Harness(this)
            h.resolver.park(id(2))
            val queue = issueEdit(h, threeWithFirstCurrent, LocalQueueAction.Select("q2"))
            runCurrent()
            issueEdit(h, queue, LocalQueueAction.Play) // q2 is current: a resume, while A's load is in flight
            runCurrent()
            assertEquals(emptyList(), h.player.calls, "the previous track played while A was loading")

            h.resolver.release(id(2))
            runCurrent()

            assertEquals(listOf(h.loadOf(2), PlaybackCommand.Play), h.player.calls, "the newest Play was lost before the load")
        }

    @Test
    fun `R4-5 Select A parked, Pause then Play - the newest intent is Play, so A starts once loaded`() =
        runTest(StandardTestDispatcher()) {
            val h = Harness(this)
            h.resolver.park(id(2))
            val queue = issueEdit(h, threeWithFirstCurrent, LocalQueueAction.Select("q2"))
            runCurrent()
            h.effects.pause(assertNotNull(LocalQueueEdits.admit(h.gate)))
            issueEdit(h, queue, LocalQueueAction.Play)
            runCurrent()

            h.resolver.release(id(2))
            runCurrent()

            assertEquals(listOf(h.loadOf(2), PlaybackCommand.Play), h.player.calls)
        }

    @Test
    fun `R4-7 Clear, then a new selection before the Stop runs - the selection supersedes it and plays`() =
        runTest(StandardTestDispatcher()) {
            val h = Harness(this)
            val cleared = issueEdit(h, threeWithFirstCurrent, LocalQueueAction.Clear)
            issueEdit(h, cleared, LocalQueueAction.Add(LocalQueueItem("q4", id(4), 4)), LocalQueueAction.Select("q4"))
            runCurrent()

            assertEquals(listOf(h.loadOf(4), PlaybackCommand.Play), h.player.calls)
        }

    @Test
    fun `R4-7 Clear whose Stop has run, then a new selection - it plays normally`() =
        runTest(StandardTestDispatcher()) {
            val h = Harness(this)
            val cleared = issueEdit(h, threeWithFirstCurrent, LocalQueueAction.Clear)
            runCurrent()
            issueEdit(h, cleared, LocalQueueAction.Add(LocalQueueItem("q4", id(4), 4)), LocalQueueAction.Select("q4"))
            runCurrent()

            assertEquals(listOf(PlaybackCommand.Stop, h.loadOf(4), PlaybackCommand.Play), h.player.calls)
        }

    // --- Fixtures ----------------------------------------------------------------------------------

    private inner class Harness(
        scope: TestScope,
    ) {
        val gate = LifetimeGate()
        val player = ParkingPlayer()
        val resolver = ParkingResolver()
        val effects = LocalPlaybackEffects(scope as CoroutineScope, player, resolver::resolve) { gate.isLocalQueueEditStillValid(it) }

        fun loadOf(n: Int) = resolver.loadFor(id(n))
    }

    /** The synchronisation owner's lifetime rule: every ownership flip advances the lifetime. */
    private class LifetimeGate : SyncPlaybackGate {
        private var synchronised = false
        private var lifetime = 0L

        fun activateSynchronised() {
            check(!synchronised)
            synchronised = true
            lifetime++
        }

        fun returnToLocal() {
            check(synchronised)
            synchronised = false
            lifetime++
        }

        override fun interceptPlay() = false

        override fun interceptPause() = false

        override fun interceptSeek(positionMs: Long) = false

        override fun interceptNext() = false

        override fun interceptPrevious() = false

        override fun interceptTrackEnded() = false

        override fun localQueueLocked() = synchronised

        override fun admitLocalQueueEdit() = if (synchronised) null else LocalQueueEditAdmission(lifetime)

        override fun isLocalQueueEditStillValid(admission: LocalQueueEditAdmission) = !synchronised && admission.lifetime == lifetime
    }

    private class ParkingPlayer : Player {
        val calls = mutableListOf<PlaybackCommand>()
        var parkOn: PlaybackCommand? = null
        val parked = CompletableDeferred<Unit>()

        override suspend fun execute(command: PlaybackCommand): Result<Unit> {
            calls += command
            if (command == parkOn) parked.await()
            return Result.success(Unit)
        }

        override val state = PlayerState()

        override fun setStateSink(sink: (PlayerState) -> Unit) = Unit

        override suspend fun release() = Unit
    }

    /** Resolves an entry's Load; a parked entry suspends here — the lookup before the Load proof. */
    private class ParkingResolver {
        private val gates = mutableMapOf<LocalEntryId, CompletableDeferred<Unit>>()
        private val waiting = mutableSetOf<LocalEntryId>()

        fun loadFor(id: LocalEntryId) = PlaybackCommand.Load(id, LocalTrackLocation("content://lifetime/${id.value}"), "t", "a")

        fun park(id: LocalEntryId) {
            gates[id] = CompletableDeferred()
        }

        fun isParked(id: LocalEntryId) = id in waiting

        fun release(id: LocalEntryId) {
            gates.getValue(id).complete(Unit)
        }

        suspend fun resolve(id: LocalEntryId): PlaybackCommand.Load {
            gates[id]?.let {
                waiting += id
                it.await()
                waiting -= id
            }
            return loadFor(id)
        }
    }
}
