package com.ridelink.network.control

import com.ridelink.core.audiopolicy.AudioRouteSnapshot
import com.ridelink.core.audiopolicy.EndpointClass
import com.ridelink.core.audiopolicy.IntercomMode
import com.ridelink.core.audiopolicy.RouteState
import com.ridelink.core.model.SessionId
import com.ridelink.core.protocol.AudioStateMessage
import com.ridelink.core.protocol.AudioStatePublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **`docs/STATUS.md` §4 problem 47**, at the seam that contained it: an `AUDIO_STATE` revision floor
 * belongs to exactly one remote sender lifetime, and a floor from a lifetime that is over must not
 * suppress a successor's state.
 *
 * PROTOCOL §4.4's `revision` is "per sender per session" and §4.4.1 says outright that it is **not**
 * reset by a duplicate-connection resolution, a control reconnect or a voice rebuild. So the
 * receiver's inbox keeps its floor across a reconnect on purpose. That is right for a peer whose
 * publisher survived the reconnect and wrong for a peer whose publisher restarted: it comes back at
 * `revision` 1 and every genuine message is dropped as stale until it climbs past the dead
 * lifetime's number. `AudioStateRelay`'s ADR-025 gate cannot see this — the frame is *legitimate*
 * and carries the live generation — because provenance and revision lifetime are different
 * questions. ADR-021 Amendment A7 answers the second one with `revision_epoch`.
 *
 * **Everything below the assertions is production.** Two real `ControlSessionManager`s over real TLS
 * 1.3, the real handshake and trust gate, the real `AudioStatePublisher` on the sending side, the
 * real `AudioStateRelay` on both, the real read loop, the real ADR-025 generation gate, the real
 * codec and the real `AudioStateInboxHolder` behind the sink. Nothing here calls
 * `AudioStateInbox.reset()`, and nothing constructs a message the peer did not actually publish: a
 * lifetime restarts by the same `resetForNewSession` call `SessionCoordinator.startDiscovery` makes.
 *
 * **How a boundary is produced.** The sender's manager is shut down and a fresh one is connected, so
 * the receiver sees a new TLS connection and its authentication generation advances — which is what
 * a reconnect looks like from the receiving side, whether the far app died or only the link did. The
 * two cases are then distinguished by the *publisher*: [Sut.reconnect] keeps it (the app survived)
 * and [Sut.restartSenderLifetime] resets it (the app did not, or the user left and re-entered
 * discovery). That is the whole distinction this amendment exists to make checkable.
 *
 * The mirror is `RideLinkPlatformTests.AudioStateSenderLifetimeTests`.
 */
class AudioStateSenderLifetimeTest {
    // --- case 1: an ordinary reconnect, same sender lifetime ---------------------------------------

    /**
     * The half a naive fix breaks. The publisher survived the link blip, so its `revision` keeps
     * climbing and its epoch does not move — and the receiver must keep the floor rather than start
     * a new one, because a floor that reset here is a floor that cannot refuse a straggler.
     */
    @Test
    fun `an ordinary reconnect keeps the floor and the sender keeps counting`() =
        twoPeers { sut ->
            sut.publishUntil(revision = 10)
            assertEquals(10L, sut.held()?.revision, "the first session's state arrived")
            val lifetime = assertNotNull(sut.held()?.revisionEpoch)

            sut.reconnect()

            sut.publish() // revision 11, same lifetime
            sut.awaitRevision(11)
            assertEquals(lifetime, sut.held()?.revisionEpoch, "a reconnect does not begin a new lifetime")
            assertEquals(0, sut.inbox.droppedRetiredEpoch, "and nothing was treated as retired")
        }

    /**
     * The other half of case 1, and the reason the floor is kept: a delayed frame from *before* the
     * reconnect, at a revision the floor already covers, is still refused. Sent on the live
     * connection, so ADR-025's gate admits it and only §4.4's rule can refuse it.
     */
    @Test
    fun `a delayed lower revision from the same lifetime is still refused after a reconnect`() =
        twoPeers { sut ->
            sut.publishUntil(revision = 10)
            val delayed = assertNotNull(sut.publisher.published).copy(revision = 7)

            sut.reconnect()
            sut.publish()
            sut.awaitRevision(11)

            sut.sendRaw(delayed)
            sut.settle()

            assertEquals(11L, sut.held()?.revision, "a revision the floor covers cannot come back")
            assertEquals(1, sut.inbox.droppedStale, "refused by §4.4's rule, and counted as stale")
            assertEquals(0, sut.inbox.droppedRetiredEpoch, "it is the same lifetime, so not a retired one")
        }

    // --- case 2: a new remote sender lifetime -------------------------------------------------------

    /**
     * **The defect.** Against the pre-fix production sources the peer's `revision` 1 is dropped as
     * stale and `held()` stays at the dead lifetime's revision 50 — for 50 more publishes.
     */
    @Test
    fun `a restarted sender is adopted at revision 1 and does not wait for the old floor`() =
        twoPeers { sut ->
            sut.publishUntil(revision = 50)
            val dead = assertNotNull(sut.held()?.revisionEpoch)

            sut.restartSenderLifetime()

            sut.publish() // the new lifetime's revision 1
            sut.awaitRevision(1)
            val live = assertNotNull(sut.held()?.revisionEpoch)
            assertNotEquals(dead, live, "the restart is announced, not inferred")
            assertEquals(1L, sut.held()?.revision, "and it is the new counter's first value")
            assertEquals(0, sut.inbox.droppedStale, "nothing of the new lifetime's was refused")
        }

    /** And then it orders normally against itself, which is what keeps §4.4's rule meaningful. */
    @Test
    fun `the new lifetime then orders normally against itself`() =
        twoPeers { sut ->
            sut.publishUntil(revision = 50)
            sut.restartSenderLifetime()

            sut.publish()
            sut.awaitRevision(1)
            sut.publish()
            sut.awaitRevision(2)

            val stale = assertNotNull(sut.publisher.published).copy(revision = 1)
            sut.sendRaw(stale)
            sut.settle()

            assertEquals(2L, sut.held()?.revision, "the new lifetime's own floor still holds")
            assertEquals(1, sut.inbox.droppedStale)
        }

    // --- case 3: a delayed old-lifetime frame after the new lifetime began --------------------------

    /**
     * Solving case 2 must not resurrect a stale route. This sends the dead lifetime's frame on the
     * **live** connection, at a revision above the one it left off at — so ADR-025's generation gate
     * admits it (the generation really is live) and the only thing that can refuse it is the inbox
     * knowing that lifetime is over.
     */
    @Test
    fun `a straggler from a replaced lifetime cannot overwrite its successor`() =
        twoPeers { sut ->
            sut.publishUntil(revision = 50)
            val old = assertNotNull(sut.publisher.published)

            sut.restartSenderLifetime()
            sut.publish()
            sut.awaitRevision(1)
            sut.publish()
            sut.awaitRevision(2)
            val live = assertNotNull(sut.held()?.revisionEpoch)

            sut.sendRaw(old.copy(revision = 51))
            sut.settle()

            assertEquals(2L, sut.held()?.revision, "the successor's state stands")
            assertEquals(live, sut.held()?.revisionEpoch)
            assertEquals(1, sut.inbox.droppedRetiredEpoch, "and the straggler is counted, not merely dropped")
            assertEquals(0, sut.manager.audioState.droppedRetiredGeneration, "ADR-025 admitted it — this is the other rule")
        }

    /**
     * The same straggler on the connection it actually belongs to: refused one layer earlier, by
     * ADR-025's gate, before the inbox ever sees it. Both defences are real and neither is the
     * other — this is the row that says so.
     */
    @Test
    fun `a straggler read from the retired connection is refused by the generation gate first`() =
        twoPeers { sut ->
            sut.publishUntil(revision = 50)
            val parked = assertNotNull(sut.manager.currentReadBinding())

            sut.restartSenderLifetime()
            sut.publish()
            sut.awaitRevision(1)

            sut.manager.handleFrame(parked, sut.frameOf(assertNotNull(sut.publisher.published).copy(revision = 99)))

            assertEquals(1L, sut.held()?.revision, "the retired connection's frame changed nothing")
            assertEquals(1, sut.manager.audioState.droppedRetiredGeneration)
            assertEquals(0, sut.inbox.droppedRetiredEpoch, "it never reached the inbox to be counted there")
        }

    // --- case 4: a session with no successor yet ----------------------------------------------------

    /**
     * ADR-025 already covers this and the regression is kept: a frame read under a session that has
     * ended, dispatched before any new session exists, mutates nothing. `liveAuthenticatedGeneration`
     * is null here, which is a different question from "does this generation match" and must not be
     * answered by accident.
     */
    @Test
    fun `a late frame with no successor session at all mutates nothing`() =
        twoPeers { sut ->
            sut.publishUntil(revision = 4)
            val parked = assertNotNull(sut.manager.currentReadBinding())

            sut.endSenderWithNoSuccessor()
            assertEquals(null, sut.manager.liveAuthenticatedGeneration, "there is no live session")

            sut.manager.handleFrame(parked, sut.frameOf(assertNotNull(sut.publisher.published).copy(revision = 5)))

            assertEquals(4L, sut.held()?.revision, "the held state is untouched")
            assertEquals(1, sut.manager.audioState.droppedRetiredGeneration)
        }

    // --- case 5: a new authentication generation alone -----------------------------------------------

    /**
     * The architecture's answer to "does a control reconnect define a new revision namespace" is
     * **no** (PROTOCOL §4.4.1), so changing the authentication generation alone must change nothing
     * about ordering. Asserted against the generation itself rather than inferred.
     */
    @Test
    fun `a new authentication generation alone does not restart the revision namespace`() =
        twoPeers { sut ->
            sut.publishUntil(revision = 3)
            val lifetime = assertNotNull(sut.held()?.revisionEpoch)
            val first = assertNotNull(sut.manager.liveAuthenticatedGeneration)

            sut.reconnect()
            sut.reconnect()

            val third = assertNotNull(sut.manager.liveAuthenticatedGeneration)
            assertTrue(third > first, "two reconnects really did advance the generation ($first -> $third)")

            sut.publish()
            sut.awaitRevision(4)
            assertEquals(lifetime, sut.held()?.revisionEpoch, "the namespace is the publisher's, not the connection's")
            assertEquals(0, sut.inbox.droppedRetiredEpoch)
            assertEquals(0, sut.inbox.droppedStale)
        }

    // --- case 6: the Phase 5 route-transition input --------------------------------------------------

    /**
     * Why this is more than a stale diagnostics row. `AppContainer.routeTransitioning` — the guard
     * that suspends ARCHITECTURE §7.3's drift ladder — reads the peer's last `AUDIO_STATE.route_state`.
     * Before this amendment a restarted peer's `transitioning` could not be adopted, so the receiver
     * kept showing (and acting on) whatever the dead lifetime last said, and a dead `transitioning`
     * would have suspended drift correction for as long as the new counter took to climb.
     */
    @Test
    fun `after a lifetime restart the peer's route transition is adopted, and so is the stable that follows`() =
        twoPeers { sut ->
            sut.publishUntil(revision = 50, routeState = RouteState.STABLE)
            assertEquals(RouteState.STABLE, sut.held()?.routeState)

            sut.restartSenderLifetime()

            sut.publish(routeState = RouteState.TRANSITIONING)
            sut.awaitRevision(1)
            assertEquals(
                RouteState.TRANSITIONING,
                sut.held()?.routeState,
                "a restarted peer's route change must reach the Phase 5 drift guard",
            )

            sut.publish(routeState = RouteState.STABLE)
            sut.awaitRevision(2)
            assertEquals(RouteState.STABLE, sut.held()?.routeState, "and so must the settle that ends it")
        }

    // --- harness ---------------------------------------------------------------------------------

    /**
     * [manager] is the receiver under test and lives for the whole test — every defect here is about
     * state surviving underneath it. [publisher] is the *sender's*, so a lifetime restart is a real
     * `resetForNewSession` on a real publisher rather than a hand-written revision.
     */
    private class Sut(
        val manager: ControlSessionManager,
        val inbox: AudioStateInboxHolder,
        private val session: FsmSession,
        private val receiver: TestPeer,
        private val sender: TestPeer,
        private val scope: CoroutineScope,
        private val port: Int,
    ) {
        var publisher = AudioStatePublisher(AudioStateEpochGenerator.generate())
            private set

        private val senderManagers = mutableListOf<ControlSessionManager>()

        fun held(): AudioStateMessage? = inbox.current

        /** Publishes one observable change through the real publisher and the real sender relay. */
        suspend fun publish(routeState: RouteState = RouteState.STABLE): AudioStateMessage {
            // `forceNext` is §4.4's "reaching CONNECTED publishes regardless" path, and using it here
            // means a row never has to invent a state change to move the counter.
            val message = publisher.forceNext(snapshot(routeState), IntercomMode.PTT)
            assertTrue(senderManagers.last().audioState.send(message), "the sender's relay must accept it")
            return message
        }

        /** Publishes until the sender's counter reaches [revision], then waits for it to arrive. */
        suspend fun publishUntil(
            revision: Long,
            routeState: RouteState = RouteState.STABLE,
        ) {
            while (publisher.currentRevision < revision) publish(routeState)
            awaitRevision(revision)
        }

        /**
         * Sends a message the publisher did not just produce — a straggler, or a revision the floor
         * already covers. Still the real relay, the real codec and the real wire.
         */
        suspend fun sendRaw(message: AudioStateMessage) {
            assertTrue(senderManagers.last().audioState.send(message), "the sender's relay must accept it")
        }

        /** The wire frame for [message], for the two rows that dispatch a parked binding by hand. */
        fun frameOf(message: AudioStateMessage): FrameReadResult.Frame =
            FrameReadResult.Frame(
                ControlMessages.audioState(
                    localPeerId = sender.peerId,
                    sessionId = SessionId("retired"),
                    seq = 1,
                    sentAtMonoUs = MONOTONIC(),
                    message = message,
                ),
                versionOk = true,
            )

        /** A control boundary with the sender's publisher **kept**: the link went, the app did not. */
        suspend fun reconnect() = boundary(restartPublisher = false)

        /**
         * A control boundary with the sender's publisher **restarted** — the same
         * `resetForNewSession` call `SessionCoordinator.startDiscovery` makes, which is the only
         * thing in production that begins a new lifetime.
         */
        suspend fun restartSenderLifetime() = boundary(restartPublisher = true)

        private suspend fun boundary(restartPublisher: Boolean) {
            endSenderWithNoSuccessor()
            if (restartPublisher) publisher.resetForNewSession(AudioStateEpochGenerator.generate())
            connectSender()
        }

        /** Ends the sender's session and brings no successor up, so nothing is authenticated. */
        suspend fun endSenderWithNoSuccessor() {
            senderManagers.last().shutdown()
            withTimeout(FsmSession.TIMEOUT_MS) {
                while (manager.currentReadBinding() != null) delay(POLL_MS)
            }
        }

        suspend fun connectSender() {
            val target = sender.manager(scope, MONOTONIC)
            senderManagers.add(target)
            val before = session.countOf { it is ControlEvent.Connected }
            val targetPort = target.startListening(sender.freshLocal())
            manager.connectTo("127.0.0.1", targetPort, receiver.local)
            target.connectTo("127.0.0.1", port, sender.freshLocal())
            withTimeout(FsmSession.TIMEOUT_MS) {
                while (session.countOf { it is ControlEvent.Connected } <= before) delay(POLL_MS)
            }
        }

        suspend fun awaitRevision(revision: Long) {
            withTimeout(FsmSession.TIMEOUT_MS) {
                while (held()?.revision != revision) delay(POLL_MS)
            }
        }

        /** Lets a frame that must change nothing actually arrive, so "nothing happened" is a result. */
        suspend fun settle() = delay(SETTLE_MS)

        suspend fun shutdownAll() {
            manager.shutdown()
            senderManagers.forEach { it.shutdown() }
        }

        private fun snapshot(routeState: RouteState) = AudioRouteSnapshot(EndpointClass.BLUETOOTH, routeState = routeState)
    }

    private fun twoPeers(body: suspend (Sut) -> Unit) =
        runBlocking {
            val (receiver, sender) = TestSessions.pairedPeers("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val manager = receiver.manager(scope, MONOTONIC)
                val session = FsmSession(receiver, manager)
                session.collectInto(scope)

                // The production sink `SessionCoordinator.attachVoice` installs, holding the
                // production inbox. Nothing in this file touches it except through this path.
                val inbox = AudioStateInboxHolder()
                manager.audioState.sink = AudioStateSink { message -> inbox.accept(message) }

                val port = manager.startListening(receiver.local)
                val sut = Sut(manager, inbox, session, receiver, sender, scope, port)
                sut.connectSender()
                try {
                    body(sut)
                } finally {
                    sut.shutdownAll()
                }
            } finally {
                scope.cancel()
            }
        }

    private companion object {
        val MONOTONIC: () -> Long = { System.nanoTime() / 1_000 }
        const val POLL_MS = 10L
        const val SETTLE_MS = 150L
    }
}
