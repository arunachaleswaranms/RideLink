package com.ridelink.app.resync

import com.ridelink.app.session.FakeForegroundService
import com.ridelink.app.session.FakeVoiceAudioSession
import com.ridelink.app.session.FakeVoiceEngine
import com.ridelink.app.session.NoOpVoiceTransport
import com.ridelink.app.session.ParkingControlChannel
import com.ridelink.app.session.SessionCoordinator
import com.ridelink.app.session.SessionEnvironment
import com.ridelink.app.session.SilentDiscoveryController
import com.ridelink.app.sync.FakeSyncPlayer
import com.ridelink.app.sync.SyncPlaybackCoordinator
import com.ridelink.app.sync.SyncTestValues
import com.ridelink.core.logging.InMemoryLogSink
import com.ridelink.core.model.ConnTiebreak
import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.model.SpkiHash
import com.ridelink.core.playback.PlaybackMessage
import com.ridelink.core.resync.ResyncMessage
import com.ridelink.core.security.InMemoryTrustedPeerStore
import com.ridelink.core.sessionfsm.SessionEvent
import com.ridelink.core.sessionfsm.SessionStatus
import com.ridelink.core.sync.SessionClockEstimate
import com.ridelink.core.voice.AudioProcessingConfig
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.control.ControlSessionManager
import com.ridelink.network.control.LinkLossReason
import com.ridelink.network.control.LocalHandshakeIdentity
import com.ridelink.network.voice.VoiceController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent-review round 3's three blockers, each reproduced against the pre-fix sources before the
 * fix was written and each pinned here.
 *
 * **Blocker A — a deferred reconciliation could deadlock while desynchronised.** `drainDeferredEvents`
 * began with a blanket `if (playbackDesynchronized || queueDesynchronized) return`, and
 * `playbackDesynchronized` clears only when the retained authoritative reconciliation actually
 * applies — from that same drain. A follower whose ingress overflowed and whose `STATE_SNAPSHOT` then
 * had to wait for a fresh clock or a content transfer stayed desynchronised **forever**, however
 * promptly the precondition resolved. Both preconditions are covered, and in both the *originally
 * retained* snapshot is what applies: no second `STATE_SNAPSHOT` is delivered, and the leader's own
 * sent-snapshot count is asserted unchanged across the recovery.
 *
 * **Blocker B — the deferred-then-applied transition had to be reported.** Android tracked it through
 * a diagnostics-flow comparison, which cannot tell "the obligation converged" from "the obligation was
 * discarded"; it is now an explicit generation-carrying signal, mirroring iOS.
 *
 * **Blocker C — production End Ride did not clear ride-segment playback identity.** The order was only
 * ever exercised by tests calling `leaveSynchronizedMode()` by hand. This suite drives the **real**
 * [SessionCoordinator.endRide] — the function `RideModeScreen`'s End Ride button calls — over a real
 * `SessionFsm` and the real [SyncPlaybackCoordinator], and never substitutes a lower seam for it.
 *
 * No wall-clock sleeps: every cadence advance is `runCurrent()` plus the harness's fake monotonic
 * clocks, exactly as [ResyncStressTest] already works.
 */
@Suppress("LongMethod", "LargeClass") // one regression = one end-to-end narrative; round 4's audit belongs beside round 3's fixes
class ResyncRecoveryTest {
    // --- Blocker A -------------------------------------------------------------------------------

    /**
     * Blocker A, clock half. Genuine ingress desynchronisation, then a valid live-generation snapshot
     * whose full playback restore cannot run because the fresh clock is not ready.
     *
     * Before the fix the second half of this test could never pass: the snapshot sat in
     * `deferredEvents` and the drain refused to look at it because `playbackDesynchronized` was set —
     * the very flag applying it would have cleared.
     */
    @Test
    fun `a snapshot deferred for the clock while desynchronized applies automatically, with no second snapshot`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            seedPlayable(pair, listOf(SyncTestValues.hash(1), SyncTestValues.hash(2)))

            // Ride state both peers agree on.
            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()
            assertEquals(SyncTestValues.hash(1), pair.follower.sync.diagnostics.value.currentTrackHash)

            // The leader moves on while the Phase 5 wire is severed, so the follower's only route back
            // to the truth is the resync round trip below.
            pair.leader.syncSession.forwardTo(null)
            pair.leader.sync.playSynchronized(SyncTestValues.hash(2))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()
            pair.leader.syncSession.forwardTo(pair.follower.syncSession)

            pair.follower.player.calls
                .clear()
            pair.follower.syncSession
                .setClock(null)
            pair.follower.sync
                .forceDesynchronizedForTest()
            runCurrent()

            val snapshotsAfterRequest =
                pair.leader.resyncSession
                    .sentOfType<ResyncMessage.StateSnapshot>()
                    .size
            assertEquals(1, snapshotsAfterRequest, "the desync trigger produced exactly one STATE_SNAPSHOT")
            assertEquals(
                ResyncOutcome.DEFERRED,
                pair.follower.resync.diagnostics.value.lastOutcome,
                "the wire round trip is complete; reconciliation is not",
            )
            assertFalse(pair.follower.resync.diagnostics.value.requestPending, "nothing is left to ask for")
            assertTrue(pair.follower.sync.diagnostics.value.ingressDesynchronized, "the desync obligation is still owed")
            assertEquals(1, pair.follower.sync.diagnostics.value.deferredCommandCount, "the snapshot is retained, not dropped")
            assertEquals(
                SyncTestValues.hash(1),
                pair.follower.sync.diagnostics.value.currentTrackHash,
                "nothing is restored yet",
            )
            assertTrue(
                pair.follower.player.calls
                    .isEmpty(),
                "no player effect may happen before the clock is trustworthy: ${pair.follower.player.calls}",
            )

            // The one thing that changes. No second snapshot is delivered — the wire back to the
            // leader is severed first, so one cannot even exist.
            pair.follower.resyncSession
                .forwardTo(null)
            pair.follower.syncSession
                .setClock(READY_CLOCK)
            pair.followerClock.advanceBy(DEFERRED_RETRY_US)
            runCurrent()

            assertEquals(
                snapshotsAfterRequest,
                pair.leader.resyncSession
                    .sentOfType<ResyncMessage.StateSnapshot>()
                    .size,
                "the retained snapshot is what applied — the leader sent no second one",
            )
            assertEquals(
                SyncTestValues.hash(2),
                pair.follower.sync.diagnostics.value.currentTrackHash,
                "the originally retained snapshot restored the leader's real track",
            )
            assertTrue(
                pair.follower.player.calls
                    .contains(FakeSyncPlayer.Call.Select(SyncTestValues.hash(2))),
                "the real player converged: ${pair.follower.player.calls}",
            )
            assertFalse(pair.follower.sync.diagnostics.value.ingressDesynchronized, "applying the repair is what clears the latch")
            assertEquals(0, pair.follower.sync.diagnostics.value.deferredCommandCount, "the retained stream drained")
            assertEquals(
                ResyncOutcome.RECONCILED,
                pair.follower.resync.diagnostics.value.lastOutcome,
                "reconciliation completed automatically, with no second round trip",
            )

            // Incremental authority resumes: a fresh leader command now applies normally.
            pair.follower.resyncSession
                .forwardTo(pair.leader.resyncSession)
            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()
            assertEquals(SyncTestValues.hash(1), pair.follower.sync.diagnostics.value.currentTrackHash)
        }

    /**
     * Blocker A, content half — the identical shape with the *other* precondition, resolved through
     * `content.observeAvailability`'s own callback rather than the retry cadence. The same retained
     * snapshot applies; no second one is delivered and no duplicate transfer is requested.
     */
    @Test
    fun `a snapshot deferred for content while desynchronized applies automatically, with no second snapshot`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            seedPlayable(pair, listOf(SyncTestValues.hash(1)))
            // Deliberately absent from the follower: hash(2), the track the leader moves to.
            pair.leader.content.localHashes
                .add(SyncTestValues.hash(2).value)
            pair.leader.content.peerHashes
                .add(SyncTestValues.hash(2).value)

            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()

            pair.leader.syncSession.forwardTo(null)
            pair.leader.sync.playSynchronized(SyncTestValues.hash(2))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()
            pair.leader.syncSession.forwardTo(pair.follower.syncSession)

            pair.follower.player.calls
                .clear()
            pair.follower.sync
                .forceDesynchronizedForTest()
            runCurrent()

            val snapshotsAfterRequest =
                pair.leader.resyncSession
                    .sentOfType<ResyncMessage.StateSnapshot>()
                    .size
            assertEquals(1, snapshotsAfterRequest)
            assertEquals(ResyncOutcome.DEFERRED, pair.follower.resync.diagnostics.value.lastOutcome)
            assertTrue(pair.follower.sync.diagnostics.value.ingressDesynchronized)
            assertEquals(1, pair.follower.sync.diagnostics.value.deferredCommandCount)
            assertEquals(
                1,
                pair.follower.content.transferRequests
                    .count { it == SyncTestValues.hash(2) },
                "PROTOCOL §5 rule 4's transfer is requested exactly once",
            )

            pair.follower.resyncSession
                .forwardTo(null)
            pair.follower.content
                .completeTransfer(SyncTestValues.hash(2))
            runCurrent()

            assertEquals(
                snapshotsAfterRequest,
                pair.leader.resyncSession
                    .sentOfType<ResyncMessage.StateSnapshot>()
                    .size,
                "the retained snapshot is what applied — the leader sent no second one",
            )
            assertEquals(SyncTestValues.hash(2), pair.follower.sync.diagnostics.value.currentTrackHash)
            assertTrue(
                pair.follower.player.calls
                    .contains(FakeSyncPlayer.Call.Select(SyncTestValues.hash(2))),
                "the real player converged: ${pair.follower.player.calls}",
            )
            assertFalse(pair.follower.sync.diagnostics.value.ingressDesynchronized)
            assertEquals(0, pair.follower.sync.diagnostics.value.deferredCommandCount)
            assertEquals(ResyncOutcome.RECONCILED, pair.follower.resync.diagnostics.value.lastOutcome)
            assertEquals(
                1,
                pair.follower.content.transferRequests
                    .count { it == SyncTestValues.hash(2) },
                "no duplicate transfer request",
            )
        }

    /**
     * Blocker B's ownership rule, Android half: a reconciliation obligation recorded under generation
     * B may not be completed by anything a *successor* generation does. Reconnecting to C clears B's
     * retained stream outright, so B's obligation can never converge — and C's own round trip must
     * then reconcile normally rather than being wedged by the stale one.
     */
    @Test
    fun `a reconciliation deferred under generation B is inert once C authenticates, and C still reconciles`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            seedPlayable(pair, listOf(SyncTestValues.hash(1)))

            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()

            pair.follower.syncSession
                .setClock(null)
            pair.follower.sync
                .forceDesynchronizedForTest()
            runCurrent()
            assertEquals(ResyncOutcome.DEFERRED, pair.follower.resync.diagnostics.value.lastOutcome)

            // Generation C supersedes B before B's clock ever recovers.
            pair.dropLink()
            pair.reconnect(generation = 2)

            assertEquals(
                ResyncOutcome.RECONCILED,
                pair.follower.resync.diagnostics.value.lastOutcome,
                "C's own round trip reconciles; B's stale obligation does not wedge it",
            )
            assertFalse(pair.follower.sync.diagnostics.value.ingressDesynchronized)

            // B's precondition resolving afterwards must produce nothing at all.
            val callsAfterC =
                pair.follower.player.calls
                    .toList()
            pair.followerClock.advanceBy(DEFERRED_RETRY_US)
            runCurrent()
            assertEquals(
                callsAfterC,
                pair.follower.player.calls
                    .toList(),
                "B's retired obligation produced a player effect under C",
            )
        }

    // --- Blocker C -------------------------------------------------------------------------------

    /**
     * Blocker C, at the strongest available seam: the **real** [SessionCoordinator.endRide], the
     * function `RideModeScreen`'s End Ride button calls, over a real `SessionFsm` and the real
     * [SyncPlaybackCoordinator] this app composes. `leaveSynchronizedMode()` is never called by this
     * test — that substitution is exactly what hid the defect.
     */
    @Test
    fun `the production End Ride clears ride-segment identity, and Ride 2 cannot report Ride 1's track`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            seedPlayable(pair, listOf(SyncTestValues.hash(1), SyncTestValues.hash(2)))
            val session = rideSession(this, pair.leader.sync)

            // Ride 1.
            session.startRide()
            assertEquals(SessionStatus.RIDE_ACTIVE, session.state.value.status)
            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()
            assertEquals(
                SyncTestValues.hash(1),
                pair.leader.sync.diagnostics.value.currentTrackHash,
                "Ride 1 established authoritative track X",
            )

            // The production End Ride, and nothing else.
            session.endRide()
            runCurrent()
            assertEquals(SessionStatus.CONNECTED, session.state.value.status, "End Ride is not End Session")
            assertNull(
                pair.leader.sync.diagnostics.value.currentTrackHash,
                "ride-segment playback identity must not survive the ride that created it",
            )

            // Ride 2, with no new authoritative playback yet, then an ordinary reconnect.
            session.startRide()
            assertEquals(SessionStatus.RIDE_ACTIVE, session.state.value.status)
            pair.dropLink()
            pair.reconnect(generation = 2)

            val rideTwoSnapshot =
                pair.leader.resyncSession
                    .sentOfType<ResyncMessage.StateSnapshot>()
                    .last()
            assertNull(
                rideTwoSnapshot.playback
                    ?.trackHash,
                "Ride 2's snapshot reported Ride 1's track: ${rideTwoSnapshot.playback}",
            )
            assertNull(pair.follower.sync.diagnostics.value.currentTrackHash, "the follower must not be told Ride 1's track either")

            // …and Ride 2's own track works normally afterwards.
            pair.leader.sync.playSynchronized(SyncTestValues.hash(2))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()
            assertEquals(SyncTestValues.hash(2), pair.follower.sync.diagnostics.value.currentTrackHash, "Ride 2's track Y converged")

            pair.dropLink()
            pair.reconnect(generation = 3)
            val rideTwoLater =
                pair.leader.resyncSession
                    .sentOfType<ResyncMessage.StateSnapshot>()
                    .last()
            assertEquals(
                SyncTestValues.hash(2),
                rideTwoLater.playback
                    ?.trackHash,
                "Ride 2 reports its own track once it has one",
            )
        }

    /**
     * Section 12's own audit, at the production seam: an End Ride whose cleanup is applied **after** a
     * successor ride has begun must touch nothing. Android's real `endRide()` performs its cleanup
     * synchronously, with no suspension between the FSM transition and the call, so the ordering is
     * unreachable there by construction; this proves the epoch guard that makes it unreachable is
     * actually load-bearing rather than assumed, by presenting the coordinator with exactly the call a
     * reordered iOS hop would make.
     */
    @Test
    fun `an End Ride cleanup applied after Ride 2 has begun clears nothing`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            seedPlayable(pair, listOf(SyncTestValues.hash(1)))
            val session = rideSession(this, pair.leader.sync)

            session.startRide()
            session.endRide()
            runCurrent()
            session.startRide()
            runCurrent()

            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()
            assertEquals(SyncTestValues.hash(1), pair.leader.sync.diagnostics.value.currentTrackHash)

            // Ride 1's End Ride epoch was 2; ride 2's Start Ride took 3. Replaying the older one is
            // precisely "old work + current state = successor mutation", and it must be refused.
            val staleBefore = pair.leader.sync.diagnostics.value.staleRideLifecycleCount
            pair.leader.sync.endRideSegment(rideEpoch = 2)
            runCurrent()

            assertEquals(
                SyncTestValues.hash(1),
                pair.leader.sync.diagnostics.value.currentTrackHash,
                "Ride 1's late cleanup cleared Ride 2's playback identity",
            )
            assertEquals(staleBefore + 1, pair.leader.sync.diagnostics.value.staleRideLifecycleCount, "…and said so, rather than silently")
        }

    /**
     * **The defect CI found in this pass's own new ride regression.** [SyncPlaybackCoordinator]'s
     * `applyPlay` proves the *control* generation before it writes `currentPlaybackIdentity`, the
     * timeline and a fresh playback epoch — and End Ride deliberately does not move that generation,
     * because the session stays alive. So an apply suspended in `content.resolve` when the ride ends
     * resumed afterwards and wrote all of it back over the state `leaveSynchronizedMode` had just
     * retired: ride 1's track reported as ride 2's truth by a different route than Blocker C's.
     *
     * Deterministic rather than load-dependent. The gate's predicate — "a `PLAY` is already on the
     * wire" — is what pins the parked frame to `applyPlay`'s own resolve, which is the only one that
     * happens after the leader commits and before it touches the player. Counting resolve calls does
     * not pin it: how many run first depends on scheduling, and a count-based version of this test
     * passed **vacuously** by parking somewhere harmless.
     */
    @Test
    fun `an apply parked across End Ride writes nothing back`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            seedPlayable(pair, listOf(SyncTestValues.hash(1)))
            val session = rideSession(this, pair.leader.sync)
            session.startRide()

            val gate = CompletableDeferred<Unit>()
            pair.leader.content.resolveGate = gate
            pair.leader.content.resolveGateWhen = {
                pair.leader.syncSession
                    .sentOfType<PlaybackMessage.Play>()
                    .isNotEmpty()
            }
            pair.leader.sync
                .playSynchronized(SyncTestValues.hash(1))
            runCurrent()

            assertTrue(
                pair.leader.syncSession
                    .sentOfType<PlaybackMessage.Play>()
                    .isNotEmpty(),
                "parked before the PLAY was issued — this is not applyPlay's resolve",
            )
            assertTrue(
                pair.leader.player.calls
                    .none { it is FakeSyncPlayer.Call.Select },
                "parked after the player was touched — too late to be applyPlay's resolve: ${pair.leader.player.calls}",
            )

            session.endRide()
            runCurrent()
            assertNull(pair.leader.sync.diagnostics.value.currentTrackHash, "End Ride clears ride-segment identity")

            gate.complete(Unit)
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()

            assertNull(
                pair.leader.sync.diagnostics.value.currentTrackHash,
                "an apply authorised before End Ride wrote its track back afterwards",
            )
        }

    // --- Independent-review round 4, Blocker 1 ----------------------------------------------------

    /**
     * **Blocker 1, Android half.** Android's real `endRide()` runs its cleanup synchronously, so the
     * parked-cleanup *window* is iOS's — but the **rule** round 3 got wrong is shared, and this proves
     * the corrected rule is load-bearing here too by presenting the coordinator with exactly the call
     * a reordered iOS hop would make: ride 1's End Ride epoch, applied after ride 2 has begun and
     * before ride 2 has established anything of its own.
     *
     * Round 3 refused that call because "a newer ride-lifecycle decision has been taken", which left
     * ride 1's `currentPlaybackIdentity` standing as the only thing a ride-2 `STATE_SNAPSHOT` had to
     * report — Property B, broken by the same statement that bought Property A.
     */
    @Test
    fun `an End Ride cleanup applied after Ride 2 has begun still clears Ride 1 when Ride 2 owns nothing`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            seedPlayable(pair, listOf(SyncTestValues.hash(1), SyncTestValues.hash(2)))
            val session = rideSession(this, pair.leader.sync)

            session.startRide()
            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()
            assertEquals(SyncTestValues.hash(1), pair.leader.sync.diagnostics.value.currentTrackHash)

            // Ride 1's End Ride took epoch 2; ride 2's Start Ride then took 3 with nothing established.
            session.endRide()
            runCurrent()
            session.startRide()
            runCurrent()

            val staleBefore = pair.leader.sync.diagnostics.value.staleRideLifecycleCount
            pair.leader.sync.endRideSegment(rideEpoch = 2)
            runCurrent()

            assertNull(
                pair.leader.sync.diagnostics.value.currentTrackHash,
                "Ride 2 inherited Ride 1's playback identity",
            )
            assertEquals(
                staleBefore,
                pair.leader.sync.diagnostics.value.staleRideLifecycleCount,
                "a boundary that owned Ride 1's state must act, not be refused as stale",
            )

            // …and an ordinary reconnect in Ride 2 reports nothing loaded, then Ride 2's own track.
            pair.dropLink()
            pair.reconnect(generation = 2)
            assertNull(
                pair.leader.resyncSession
                    .sentOfType<ResyncMessage.StateSnapshot>()
                    .last()
                    .playback
                    ?.trackHash,
                "Ride 2's snapshot reported Ride 1's track",
            )
            pair.leader.sync.playSynchronized(SyncTestValues.hash(2))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()
            assertEquals(SyncTestValues.hash(2), pair.follower.sync.diagnostics.value.currentTrackHash)
        }

    /**
     * Property A at the same seam, so neither property is bought by weakening the other: with ride 2
     * having established its own track, the identical stale boundary must touch nothing. Together with
     * the test above, this is the whole of Blocker 1's rule on this platform.
     */
    @Test
    fun `an End Ride cleanup applied after Ride 2 established its own track clears nothing`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            seedPlayable(pair, listOf(SyncTestValues.hash(1), SyncTestValues.hash(2)))
            val session = rideSession(this, pair.leader.sync)

            session.startRide()
            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()
            session.endRide()
            runCurrent()
            session.startRide()
            runCurrent()

            pair.leader.sync.playSynchronized(SyncTestValues.hash(2))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()
            assertEquals(SyncTestValues.hash(2), pair.leader.sync.diagnostics.value.currentTrackHash)

            val staleBefore = pair.leader.sync.diagnostics.value.staleRideLifecycleCount
            pair.leader.sync.endRideSegment(rideEpoch = 2)
            runCurrent()

            assertEquals(
                SyncTestValues.hash(2),
                pair.leader.sync.diagnostics.value.currentTrackHash,
                "Ride 1's late cleanup cleared Ride 2's own track",
            )
            assertEquals(
                staleBefore + 1,
                pair.leader.sync.diagnostics.value.staleRideLifecycleCount,
                "…and said so, rather than silently",
            )
        }

    // --- Independent-review round 4, Blocker 2 ----------------------------------------------------

    /**
     * **§15.** A reconciliation deferred for the clock, then End Ride, then the clock becoming ready.
     * The obligation was **discarded**, not applied, so nothing about the precondition resolving may
     * resurrect it — and because End Ride deliberately leaves the authenticated control generation
     * alone, a generation-keyed completion could not tell the difference.
     */
    @Test
    fun `End Ride while clock-deferred cancels the obligation and a ready clock cannot resurrect it`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            seedPlayable(pair, listOf(SyncTestValues.hash(1), SyncTestValues.hash(2)))
            val followerSession = rideSession(this, pair.follower.sync)
            followerSession.startRide()

            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()

            pair.follower.syncSession.setClock(null)
            pair.follower.sync.forceDesynchronizedForTest()
            runCurrent()
            assertEquals(ResyncOutcome.DEFERRED, pair.follower.resync.diagnostics.value.lastOutcome)
            assertEquals(1, pair.follower.sync.diagnostics.value.deferredCommandCount, "the obligation is backed by a retained snapshot")
            pair.follower.player.calls
                .clear()

            followerSession.endRide()
            runCurrent()

            assertEquals(
                ResyncOutcome.CANCELLED,
                pair.follower.resync.diagnostics.value.lastOutcome,
                "the outer obligation never learned its retained snapshot was discarded",
            )
            assertEquals(0, pair.follower.sync.diagnostics.value.deferredCommandCount, "End Ride discarded the retained snapshot")

            // The precondition resolves. Nothing may happen.
            pair.follower.syncSession.setClock(READY_CLOCK)
            pair.followerClock.advanceBy(DEFERRED_RETRY_US * 4)
            runCurrent()

            assertEquals(
                ResyncOutcome.CANCELLED,
                pair.follower.resync.diagnostics.value.lastOutcome,
                "a discarded reconciliation reported success",
            )
            // `leaveSynchronizedMode`'s own `restoreRate()` is ADR-024 Amendment A4 §D's one
            // deliberately unfenced player call and is End Ride's, not the reconciliation's. What must
            // not appear is a *restoration* effect — materialising or loading the snapshot's track.
            assertTrue(
                pair.follower.player.calls
                    .none { it is FakeSyncPlayer.Call.Select },
                "a cancelled reconciliation reached the player: ${pair.follower.player.calls}",
            )
            assertNull(pair.follower.sync.diagnostics.value.currentTrackHash, "a cancelled reconciliation restored Ride 1's identity")
            assertTrue(pair.follower.manifestRefreshCalls.isEmpty(), "a cancelled reconciliation triggered a manifest refresh")
        }

    /**
     * **§16.** The same for the other precondition: deferred for content, End Ride, then the transfer
     * completes. Phase 4's own cache behaviour is deliberately untouched — the track really does
     * become resolvable — and only the synchronisation obligation is cancelled.
     */
    @Test
    fun `End Ride while content-deferred cancels the obligation and a completed transfer cannot resurrect it`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            seedPlayable(pair, listOf(SyncTestValues.hash(1)))
            // Deliberately absent from the follower: hash(2), the track the leader moves to.
            pair.leader.content.localHashes
                .add(SyncTestValues.hash(2).value)
            pair.leader.content.peerHashes
                .add(SyncTestValues.hash(2).value)
            val followerSession = rideSession(this, pair.follower.sync)
            followerSession.startRide()

            // The Phase 5 wire is severed while the leader moves on, exactly as the existing content
            // regression does, so the follower's only route to hash(2) is the resync round trip and
            // the transfer request counted below is the reconciliation's own.
            pair.leader.syncSession.forwardTo(null)
            pair.leader.sync.playSynchronized(SyncTestValues.hash(2))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()
            pair.leader.syncSession.forwardTo(pair.follower.syncSession)
            pair.follower.sync.forceDesynchronizedForTest()
            runCurrent()
            assertEquals(ResyncOutcome.DEFERRED, pair.follower.resync.diagnostics.value.lastOutcome)
            assertEquals(
                1,
                pair.follower.content.transferRequests
                    .count { it == SyncTestValues.hash(2) },
                "PROTOCOL §5 rule 4's transfer was requested",
            )
            pair.follower.player.calls
                .clear()

            followerSession.endRide()
            runCurrent()
            assertEquals(ResyncOutcome.CANCELLED, pair.follower.resync.diagnostics.value.lastOutcome)
            assertEquals(0, pair.follower.sync.diagnostics.value.deferredCommandCount)

            // Phase 4 finishes the transfer it was legitimately asked for.
            pair.follower.content.completeTransfer(SyncTestValues.hash(2))
            runCurrent()

            assertEquals(
                ResyncOutcome.CANCELLED,
                pair.follower.resync.diagnostics.value.lastOutcome,
                "a discarded reconciliation reported success",
            )
            assertTrue(
                pair.follower.player.calls
                    .none { it is FakeSyncPlayer.Call.Select },
                "a cancelled reconciliation reached the player: ${pair.follower.player.calls}",
            )
            assertNull(pair.follower.sync.diagnostics.value.currentTrackHash)
            assertTrue(
                pair.follower.content.localHashes
                    .contains(SyncTestValues.hash(2).value),
                "Phase 4's own cache behaviour must be untouched",
            )
        }

    /**
     * **§13/§14: the case the existing B→C tests cannot reach.** S1 and S2 share control generation B,
     * because End Ride deliberately does not move it. S1 is cancelled by End Ride; S2 is accepted in
     * ride 2 under the same generation and later applies. Only S2 may ever be reported reconciled.
     */
    @Test
    fun `two obligations under one control generation complete only themselves`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            seedPlayable(pair, listOf(SyncTestValues.hash(1), SyncTestValues.hash(2)))
            val followerSession = rideSession(this, pair.follower.sync)
            followerSession.startRide()

            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()

            // S1: deferred for the clock, then cancelled by End Ride.
            pair.follower.syncSession.setClock(null)
            pair.follower.sync.forceDesynchronizedForTest()
            runCurrent()
            assertEquals(ResyncOutcome.DEFERRED, pair.follower.resync.diagnostics.value.lastOutcome)
            val s1CommandSeq = lastSnapshotCommandSeqOnTheWire(pair)
            followerSession.endRide()
            runCurrent()
            assertEquals(ResyncOutcome.CANCELLED, pair.follower.resync.diagnostics.value.lastOutcome)

            // Ride 2, under the **same** control generation. The leader moves on, and S2 is deferred.
            followerSession.startRide()
            pair.leader.sync.playSynchronized(SyncTestValues.hash(2))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()
            pair.follower.sync.forceDesynchronizedForTest()
            runCurrent()
            assertEquals(ResyncOutcome.DEFERRED, pair.follower.resync.diagnostics.value.lastOutcome, "S2 is a genuinely new obligation")
            val s2CommandSeq = lastSnapshotCommandSeqOnTheWire(pair)
            assertTrue(s2CommandSeq != s1CommandSeq, "S2 must be a different snapshot from S1")

            pair.follower.syncSession.setClock(READY_CLOCK)
            pair.followerClock.advanceBy(DEFERRED_RETRY_US)
            runCurrent()

            assertEquals(ResyncOutcome.RECONCILED, pair.follower.resync.diagnostics.value.lastOutcome, "S2 must reconcile")
            assertEquals(
                s2CommandSeq,
                pair.follower.resync.diagnostics.value.lastSnapshotCommandSeq,
                "S1's command_seq was published as S2's reconciliation",
            )
            assertEquals(SyncTestValues.hash(2), pair.follower.sync.diagnostics.value.currentTrackHash)
        }

    /**
     * **§23's fresh-fix audit.** With S1 cancelled and S2 live under the same generation, a late
     * terminal signal naming S1 — applied *or* cancelled — may not alter S2. Both are fired straight at
     * the production callbacks, which is exactly what a delayed drain or a delayed discard would do.
     * S1's obligation id is 1 and S2's is 2 by construction: ids are minted one per accepted snapshot,
     * from 1, in arrival order, and this test delivers exactly two.
     */
    @Test
    fun `a late terminal signal for a cancelled obligation cannot alter the live one`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            seedPlayable(pair, listOf(SyncTestValues.hash(1), SyncTestValues.hash(2)))
            val followerSession = rideSession(this, pair.follower.sync)
            followerSession.startRide()

            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()
            pair.follower.syncSession.setClock(null)
            pair.follower.sync.forceDesynchronizedForTest()
            runCurrent()
            followerSession.endRide()
            runCurrent()
            assertEquals(ResyncOutcome.CANCELLED, pair.follower.resync.diagnostics.value.lastOutcome)

            followerSession.startRide()
            pair.leader.sync.playSynchronized(SyncTestValues.hash(2))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()
            pair.follower.sync.forceDesynchronizedForTest()
            runCurrent()
            assertEquals(ResyncOutcome.DEFERRED, pair.follower.resync.diagnostics.value.lastOutcome)
            val s2CommandSeq = lastSnapshotCommandSeqOnTheWire(pair)
            val publishedBeforeLateSignals = pair.follower.resync.diagnostics.value.lastSnapshotCommandSeq

            // Late S1 applied, then late S1 cancelled. Neither names S2's obligation.
            pair.follower.sync.onReconciliationApplied
                ?.invoke(1L, 1L)
            pair.follower.sync.onReconciliationCancelled
                ?.invoke(1L, 1L)
            runCurrent()

            assertEquals(
                ResyncOutcome.DEFERRED,
                pair.follower.resync.diagnostics.value.lastOutcome,
                "a late S1 signal changed S2's reported outcome",
            )
            assertEquals(
                publishedBeforeLateSignals,
                pair.follower.resync.diagnostics.value.lastSnapshotCommandSeq,
                "a late S1 signal published a reconciliation's values",
            )

            // …and S2 still completes normally afterwards.
            pair.follower.syncSession.setClock(READY_CLOCK)
            pair.followerClock.advanceBy(DEFERRED_RETRY_US)
            runCurrent()
            assertEquals(ResyncOutcome.RECONCILED, pair.follower.resync.diagnostics.value.lastOutcome)
            assertEquals(s2CommandSeq, pair.follower.resync.diagnostics.value.lastSnapshotCommandSeq)
        }

    /**
     * **§24 item 10.** A terminal teardown with an obligation outstanding cancels it — the control
     * lifetime that authorised it has ended — and no late completion may follow.
     */
    @Test
    fun `a terminal teardown with a pending obligation produces no late completion`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            seedPlayable(pair, listOf(SyncTestValues.hash(1)))

            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()
            pair.follower.syncSession.setClock(null)
            pair.follower.sync.forceDesynchronizedForTest()
            runCurrent()
            assertEquals(ResyncOutcome.DEFERRED, pair.follower.resync.diagnostics.value.lastOutcome)

            // The production boundary reaches **both** planes: `SessionCoordinator.applySideEffects`
            // forwards `ControlEvent.LinkLost` to `SyncPlaybackCoordinator` and to `ResyncCoordinator`
            // alike. `ResyncTestPair.dropLink` deliberately only severs the resync half (see its own
            // doc comment), so the sync half is emitted here — and this test, uniquely, never
            // reconnects afterwards, which is what makes the difference observable.
            pair.follower.syncSession.emit(ControlEvent.LinkLost(LinkLossReason.NETWORK))
            pair.dropLink()
            runCurrent()
            assertEquals(
                ResyncOutcome.CANCELLED,
                pair.follower.resync.diagnostics.value.lastOutcome,
                "a lifetime boundary must cancel the obligation it authorised",
            )

            pair.follower.syncSession.setClock(READY_CLOCK)
            pair.followerClock.advanceBy(DEFERRED_RETRY_US * 4)
            runCurrent()
            assertEquals(
                ResyncOutcome.CANCELLED,
                pair.follower.resync.diagnostics.value.lastOutcome,
                "a torn-down obligation completed late",
            )
        }

    /**
     * **Round 4's own fresh-fix defect, found by CI at the exact head — the wire obligation is not the
     * reconciliation obligation.**
     *
     * Two versions of this mistake were made and both are covered. The obligation-identity guard was
     * briefly placed *before* `StateResyncGate.onSnapshotObserved`, making the **wire** request's
     * clear conditional on the **reconciliation** obligation surviving the apply; and a ride-lifetime
     * refusal was briefly reported as `REJECTED_STALE`, which by §21 must *not* clear an outstanding
     * request, because such a snapshot never answered it. Either way a snapshot that genuinely
     * arrived for the live generation left `requestPending` true with nothing that could ever clear
     * it — the conflation round 3's Blocker B removed, re-created by the fix written to strengthen it.
     *
     * Deterministic on both platforms, by different suspensions: iOS parks on its generation gate,
     * Android on the follower's content gate, each of which sits *after* `applyPeerPlaybackState`
     * captured the ride lifetime and *before* anything is retained. The ride then ends while the
     * reconciliation is provably parked.
     */
    @Test
    fun `a snapshot answering a request raised before End Ride still clears the wire request`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            seedPlayable(pair, listOf(SyncTestValues.hash(1)))
            val followerSession = rideSession(this, pair.follower.sync)
            followerSession.startRide()

            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()

            // Park the follower's reconciliation inside `onPeerPlaybackState`'s own content check —
            // after `applyPeerPlaybackState` captured the ride lifetime, before anything is retained.
            val gate = CompletableDeferred<Unit>()
            pair.follower.content.resolveGate = gate
            pair.follower.sync.forceDesynchronizedForTest()
            runCurrent()
            assertTrue(pair.follower.resync.diagnostics.value.requestPending, "the desync trigger left a wire request outstanding")

            // End the ride while it is provably parked, then let it resume.
            followerSession.endRide()
            runCurrent()
            gate.complete(Unit)
            runCurrent()

            assertEquals(
                ResyncOutcome.CANCELLED,
                pair.follower.resync.diagnostics.value.lastOutcome,
                "the ride ended mid-apply, so the reconciliation is cancelled — and that is not the same as the snapshot being stale",
            )
            assertFalse(
                pair.follower.resync.diagnostics.value.requestPending,
                "a snapshot arrived for the live generation, so the wire request must clear whatever happened to the reconciliation",
            )
            assertNull(
                pair.follower.sync.diagnostics.value.currentTrackHash,
                "a reconciliation the ride cancelled may not restore ride 1's playback",
            )
        }

    /**
     * **§24 item 11.** Fifty same-generation cancel/apply cycles on a fresh harness each time. Only S2
     * may ever reconcile, and it must report its own `command_seq`.
     */
    @Test
    fun `fifty same-generation cancel-then-apply cycles complete only the live obligation`() =
        runTest(StandardTestDispatcher()) {
            repeat(STRESS_CYCLES) { i ->
                val pair = ResyncTestPair(this)
                pair.connect(generation = 1)
                seedPlayable(pair, listOf(SyncTestValues.hash(1), SyncTestValues.hash(2)))
                val followerSession = rideSession(this, pair.follower.sync)
                followerSession.startRide()

                pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
                runCurrent()
                pair.leaderClock.advanceBy(LEAD_US)
                runCurrent()
                pair.follower.syncSession.setClock(null)
                pair.follower.sync.forceDesynchronizedForTest()
                runCurrent()
                assertEquals(ResyncOutcome.DEFERRED, pair.follower.resync.diagnostics.value.lastOutcome, "cycle $i: S1 deferred")
                followerSession.endRide()
                runCurrent()
                assertEquals(ResyncOutcome.CANCELLED, pair.follower.resync.diagnostics.value.lastOutcome, "cycle $i: S1 cancelled")

                followerSession.startRide()
                pair.leader.sync.playSynchronized(SyncTestValues.hash(2))
                runCurrent()
                pair.leaderClock.advanceBy(LEAD_US)
                runCurrent()
                pair.follower.sync.forceDesynchronizedForTest()
                runCurrent()
                assertEquals(ResyncOutcome.DEFERRED, pair.follower.resync.diagnostics.value.lastOutcome, "cycle $i: S2 deferred")
                val s2CommandSeq = lastSnapshotCommandSeqOnTheWire(pair)

                pair.follower.syncSession.setClock(READY_CLOCK)
                pair.followerClock.advanceBy(DEFERRED_RETRY_US)
                runCurrent()
                assertEquals(ResyncOutcome.RECONCILED, pair.follower.resync.diagnostics.value.lastOutcome, "cycle $i: S2 reconciled")
                assertEquals(
                    s2CommandSeq,
                    pair.follower.resync.diagnostics.value.lastSnapshotCommandSeq,
                    "cycle $i: S1's values were published",
                )
                assertEquals(SyncTestValues.hash(2), pair.follower.sync.diagnostics.value.currentTrackHash, "cycle $i")
            }
        }

    // --- repetition ------------------------------------------------------------------------------

    /**
     * The four scenarios above, fifty times each, on a fresh harness per iteration. This repo's
     * standing lesson is that almost every real defect here surfaced only on a *second* session or
     * under repeated cycling; a recovery path that converges once is not yet evidence that it
     * converges. Deterministic and in-process — no sleeps, no `--rerun-tasks` loop.
     */
    @Test
    fun `fifty cycles of every round-3 recovery scenario converge every time`() =
        runTest(StandardTestDispatcher()) {
            repeat(STRESS_CYCLES) { i ->
                val clockPair = ResyncTestPair(this)
                clockPair.connect(generation = 1)
                seedPlayable(clockPair, listOf(SyncTestValues.hash(1)))
                clockPair.follower.syncSession
                    .setClock(null)
                clockPair.follower.sync
                    .forceDesynchronizedForTest()
                runCurrent()
                assertEquals(ResyncOutcome.DEFERRED, clockPair.follower.resync.diagnostics.value.lastOutcome, "cycle $i (clock): deferred")
                clockPair.follower.resyncSession
                    .forwardTo(null)
                clockPair.follower.syncSession
                    .setClock(READY_CLOCK)
                clockPair.followerClock.advanceBy(DEFERRED_RETRY_US)
                runCurrent()
                assertEquals(
                    ResyncOutcome.RECONCILED,
                    clockPair.follower.resync.diagnostics.value.lastOutcome,
                    "cycle $i (clock): the retained snapshot must converge without a second round trip",
                )
                assertFalse(clockPair.follower.sync.diagnostics.value.ingressDesynchronized, "cycle $i (clock): latch cleared")

                val contentPair = ResyncTestPair(this)
                contentPair.connect(generation = 1)
                contentPair.leader.content.localHashes
                    .add(SyncTestValues.hash(2).value)
                contentPair.leader.content.peerHashes
                    .add(SyncTestValues.hash(2).value)
                contentPair.leader.sync
                    .playSynchronized(SyncTestValues.hash(2))
                runCurrent()
                contentPair.leaderClock.advanceBy(LEAD_US)
                runCurrent()
                contentPair.follower.sync
                    .forceDesynchronizedForTest()
                runCurrent()
                assertEquals(
                    ResyncOutcome.DEFERRED,
                    contentPair.follower.resync.diagnostics.value.lastOutcome,
                    "cycle $i (content): deferred",
                )
                contentPair.follower.resyncSession
                    .forwardTo(null)
                contentPair.follower.content
                    .completeTransfer(SyncTestValues.hash(2))
                runCurrent()
                assertEquals(
                    ResyncOutcome.RECONCILED,
                    contentPair.follower.resync.diagnostics.value.lastOutcome,
                    "cycle $i (content): the retained snapshot must converge without a second round trip",
                )
                assertFalse(contentPair.follower.sync.diagnostics.value.ingressDesynchronized, "cycle $i (content): latch cleared")

                val ridePair = ResyncTestPair(this)
                ridePair.connect(generation = 1)
                seedPlayable(ridePair, listOf(SyncTestValues.hash(1), SyncTestValues.hash(2)))
                val rideSession = rideSession(this, ridePair.leader.sync)
                rideSession.startRide()
                ridePair.leader.sync
                    .playSynchronized(SyncTestValues.hash(1))
                runCurrent()
                ridePair.leaderClock.advanceBy(LEAD_US)
                runCurrent()
                rideSession.endRide()
                runCurrent()
                assertNull(ridePair.leader.sync.diagnostics.value.currentTrackHash, "cycle $i (ride): identity cleared by End Ride")
                rideSession.startRide()
                ridePair.dropLink()
                ridePair.reconnect(generation = 2)
                val snapshot =
                    ridePair.leader.resyncSession
                        .sentOfType<ResyncMessage.StateSnapshot>()
                        .last()
                assertNull(
                    snapshot.playback
                        ?.trackHash,
                    "cycle $i (ride): Ride 2 reported Ride 1's track",
                )
                ridePair.leader.sync
                    .playSynchronized(SyncTestValues.hash(2))
                runCurrent()
                ridePair.leaderClock.advanceBy(LEAD_US)
                runCurrent()
                assertEquals(
                    SyncTestValues.hash(2),
                    ridePair.follower.sync.diagnostics.value.currentTrackHash,
                    "cycle $i (ride): Ride 2's own track must still work",
                )
            }
        }

    // --- harness ---------------------------------------------------------------------------------

    /**
     * The `command_seq` of the most recent `STATE_SNAPSHOT` the leader actually put on the wire.
     *
     * Read from the wire rather than from `ResyncDiagnostics.lastSnapshotCommandSeq`, because Android
     * publishes that field only from `completeReconciliation` — a deliberate, pre-existing divergence
     * from iOS, which also publishes it on the pending branch. Asserting against the wire is the
     * stronger claim anyway: it is the value a reconciliation *would* report if it completed.
     */
    private fun lastSnapshotCommandSeqOnTheWire(pair: ResyncTestPair): Long =
        pair.leader.resyncSession
            .sentOfType<ResyncMessage.StateSnapshot>()
            .last()
            .commandSeq

    private fun seedPlayable(
        pair: ResyncTestPair,
        hashes: List<ContentHash>,
    ) {
        for (hash in hashes) {
            pair.leader.content.localHashes
                .add(hash.value)
            pair.leader.content.peerHashes
                .add(hash.value)
            pair.follower.content.localHashes
                .add(hash.value)
        }
    }

    /**
     * A **real** [SessionCoordinator] driven to `CONNECTED`, wired to the real
     * [SyncPlaybackCoordinator] this test already owns through the same `RideSegmentOwner` adapter
     * `AppContainer` installs in production. Nothing about the ride path is faked.
     */
    private fun rideSession(
        scope: TestScope,
        sync: SyncPlaybackCoordinator,
    ): SessionCoordinator {
        val background: CoroutineScope = scope.backgroundScope
        val manager =
            ControlSessionManager(
                scope = background,
                monotonicNowUs = { 0L },
                localPeerId = LOCAL_PEER,
                channel = ParkingControlChannel(),
                trustedPeers = InMemoryTrustedPeerStore(),
            )
        val audio = FakeVoiceAudioSession()
        val coordinator =
            SessionCoordinator(
                discovery = SilentDiscoveryController(),
                controlSessionManager = manager,
                localIdentity =
                    LocalHandshakeIdentity(
                        displayName = "ride-test",
                        platform = "android",
                        osVersion = "test",
                        appVersion = "test",
                        connTiebreak = ConnTiebreak("1".repeat(32)),
                        identitySpkiSha256 = SpkiHash("sha256:" + "ab".repeat(32)),
                    ),
                scope = background,
                logSink = InMemoryLogSink(),
                trustedPeers = InMemoryTrustedPeerStore(),
                environment =
                    SessionEnvironment(
                        monotonicNowUs = { 0L },
                        nowEpochSeconds = { 0L },
                        audioEndpointPresent = { true },
                    ),
                foregroundService = FakeForegroundService(),
                buildVoiceController = { isLocalLeader ->
                    VoiceController(
                        scope = background,
                        engine = FakeVoiceEngine(),
                        audioSession = audio,
                        transport = NoOpVoiceTransport(),
                        isLocalLeader = isLocalLeader,
                        localTrackId = "ride-test-track",
                        audioProcessing = AudioProcessingConfig(),
                    )
                },
                rideSegment = SyncRideSegmentOwner(sync),
            )
        coordinator.startDiscovery()
        scope.runCurrent()
        assertTrue(coordinator.applyEvent(SessionEvent.PeerSelected))
        coordinator.handleControlEvent(ControlEvent.PeerTrusted(REMOTE_PEER))
        coordinator.handleControlEvent(
            ControlEvent.Connected(REMOTE_PEER, SessionId("ride-test-session"), isLocalLeader = true, authGeneration = 1L),
        )
        scope.runCurrent()
        assertEquals(SessionStatus.CONNECTED, coordinator.state.value.status)
        return coordinator
    }

    // --- Independent-review round 5, Blocker 2 --------------------------------------------------

    /**
     * **Blocker 2A, Android half.** `restoreFromPlaybackState` discarded [SyncPlaybackCoordinator]'s
     * `applyPlay` result — which was `Unit` and could not have carried one — and returned
     * `APPLIED` unconditionally. `applyPlay` legitimately early-returns when the **ride** that
     * authorised the reconciliation ends inside its `content.resolve`, writing nothing; the
     * obligation's owner was nevertheless told the state had converged, so ride 1's `command_seq`
     * and `manifest_revision` were published as [ResyncOutcome.RECONCILED].
     *
     * **Where this parks, and why that is the whole test.** The existing ride regressions end the
     * ride *before* the snapshot arrives, so the refusal lands at [applyPeerPlaybackState]'s own
     * outer ride guard — a path that already answered `REJECTED_RIDE` correctly. This one parks two
     * frames deeper, inside `applyPlay`'s **own** resolve, past `onPeerPlaybackState`'s clock and
     * content pre-checks and past `restoreFromPlaybackState`'s ride guard. The gate's predicate is
     * what pins it: `lastAppliedCommandSeq == 2` becomes true only once `applyPeerPlaybackState` has
     * adopted the snapshot's sequence number, which happens after the outer pre-check resolve and
     * before `applyPlay`'s. Counting resolves alone does not pin it, and a count-based draft of the
     * ride regression this file already carries passed **vacuously**.
     */
    private suspend fun TestScope.rideCancelledMidApplyCycle(cycle: Int) {
        val pair = ResyncTestPair(this)
        pair.connect(generation = 1)
        seedPlayable(pair, listOf(SyncTestValues.hash(1)))
        // hash(2) and hash(3) resolvable on both sides, so the reconciliations' own content
        // pre-checks pass and the parked frame below is unambiguously `applyPlay`'s.
        for (hash in listOf(SyncTestValues.hash(2), SyncTestValues.hash(3))) {
            pair.leader.content.localHashes
                .add(hash.value)
            pair.leader.content.peerHashes
                .add(hash.value)
            pair.follower.content.localHashes
                .add(hash.value)
        }
        val followerSession = rideSession(this, pair.follower.sync)
        followerSession.startRide()

        pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
        runCurrent()
        pair.leaderClock.advanceBy(LEAD_US)
        runCurrent()
        assertEquals(1L, pair.follower.sync.diagnostics.value.lastAppliedCommandSeq, "cycle $cycle")

        // The leader moves to hash(2) with the Phase 5 wire severed, so the follower's only route to
        // it is the resync round trip — and its `lastAppliedCommandSeq` is still 1 while the
        // snapshot's will be 2. That difference is what pins the parked frame.
        pair.leader.syncSession.forwardTo(null)
        pair.leader.sync.playSynchronized(SyncTestValues.hash(2))
        runCurrent()
        pair.leaderClock.advanceBy(LEAD_US)
        runCurrent()
        pair.leader.syncSession.forwardTo(pair.follower.syncSession)

        val gate = CompletableDeferred<Unit>()
        pair.follower.content.resolveGate = gate
        pair.follower.content.resolveGateWhen = { pair.follower.sync.diagnostics.value.lastAppliedCommandSeq == 2L }
        pair.follower.player.calls
            .clear()

        pair.follower.sync.forceDesynchronizedForTest()
        runCurrent()

        assertNull(pair.follower.content.resolveGateWhen, "cycle $cycle: never parked inside applyPlay's own resolve")
        assertTrue(
            pair.follower.player.calls
                .none { it is FakeSyncPlayer.Call.Select },
            "cycle $cycle: parked after the player was touched: ${pair.follower.player.calls}",
        )
        val s1CommandSeq = lastSnapshotCommandSeqOnTheWire(pair)
        assertEquals(2L, s1CommandSeq, "cycle $cycle")
        assertTrue(pair.follower.resync.diagnostics.value.requestPending, "cycle $cycle: no wire request was outstanding")

        // End Ride while S1 is provably parked, then let it resume.
        followerSession.endRide()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        pair.followerClock.advanceBy(DEFERRED_RETRY_US * 4)
        runCurrent()

        // S1 mutated nothing, and its terminal result is cancellation.
        assertEquals(
            ResyncOutcome.CANCELLED,
            pair.follower.resync.diagnostics.value.lastOutcome,
            "cycle $cycle: a reconciliation the ride ended mid-apply reported success",
        )
        assertNull(pair.follower.sync.diagnostics.value.currentTrackHash, "cycle $cycle: a ride-expired restore wrote currentTrackHash")
        assertTrue(
            pair.follower.player.calls
                .none { it is FakeSyncPlayer.Call.Select },
            "cycle $cycle: a ride-expired restore reached the player: ${pair.follower.player.calls}",
        )
        assertEquals(
            0,
            pair.follower.sync.diagnostics.value.deferredCommandCount,
            "cycle $cycle: S1 claimed a deferral and retained nothing",
        )
        assertFalse(pair.follower.resync.diagnostics.value.requestPending, "cycle $cycle: the wire request was left outstanding")
        assertFalse(
            pair.follower.resync.diagnostics.value.lastSnapshotCommandSeq == s1CommandSeq,
            "cycle $cycle: S1's command_seq was published as reconciled bookkeeping",
        )
        assertTrue(pair.follower.manifestRefreshCalls.isEmpty(), "cycle $cycle: a cancelled reconciliation triggered a manifest refresh")

        // Ride 2, under the **same** authenticated control generation — End Ride never moves it.
        followerSession.startRide()
        pair.leader.sync.playSynchronized(SyncTestValues.hash(3))
        runCurrent()
        pair.leaderClock.advanceBy(LEAD_US)
        runCurrent()
        pair.follower.sync.forceDesynchronizedForTest()
        runCurrent()
        pair.followerClock.advanceBy(DEFERRED_RETRY_US)
        runCurrent()

        val s2CommandSeq = lastSnapshotCommandSeqOnTheWire(pair)
        assertTrue(s2CommandSeq != s1CommandSeq, "cycle $cycle: S2 must be a different snapshot from S1")
        assertEquals(ResyncOutcome.RECONCILED, pair.follower.resync.diagnostics.value.lastOutcome, "cycle $cycle: S2 must reconcile")
        assertEquals(
            s2CommandSeq,
            pair.follower.resync.diagnostics.value.lastSnapshotCommandSeq,
            "cycle $cycle: S1's command_seq was published as S2's reconciliation",
        )
        assertEquals(SyncTestValues.hash(3), pair.follower.sync.diagnostics.value.currentTrackHash, "cycle $cycle")
    }

    @Test
    fun `an End Ride inside applyPlay cancels the obligation rather than reporting APPLIED`() =
        runTest(StandardTestDispatcher()) { rideCancelledMidApplyCycle(cycle = 0) }

    @Test
    fun `fifty ride-cancelled-mid-apply cycles complete only the live obligation`() =
        runTest(StandardTestDispatcher()) {
            for (cycle in 0 until STRESS_CYCLES) rideCancelledMidApplyCycle(cycle)
        }

    // --- Independent-review round 7 (retained work's own ride provenance) -------------------------

    /**
     * **Round 7's Android half, and the one instance of the class this platform can reach.**
     *
     * The iOS blocker has two more orderings than this: a retained event surviving an End Ride that
     * has been *accepted* but whose cleanup is still parked in `launchInSession`. Android cannot
     * reach those — `SessionCoordinator.endRide()` calls `endRideSegment` synchronously, on the same
     * thread, one statement after minting the epoch, so `leaveSynchronizedMode` has already bumped
     * `synchronizedModeEpoch` and discarded `deferredEvents` before any successor ride can exist.
     *
     * What Android *does* reach is the append-time race, and it is the same structural defect: the
     * full-restore pre-check suspends in `content.resolve` (the only suspension between this
     * snapshot's admission and its retention — `estimate()` and `readyEstimate()` are synchronous
     * here), and on the round-6 head the resumed continuation re-proved **neither** lifetime before
     * appending. So a snapshot admitted under ride 1 was written into the held stream *after* End
     * Ride had emptied it, carrying no ride provenance at all — and `drainDeferredEvents` then
     * replayed it against a freshly-read live `synchronizedModeEpoch`, which is a replacement
     * provenance rather than the admitted one.
     *
     * Deterministic: the park is proved (`resolveGateWhen` consumed, nothing yet retained, the wire
     * request outstanding), both ride boundaries are the **real** `SessionCoordinator` ones, and the
     * gate is released only afterwards.
     */
    @Test
    fun `a ride boundary inside the snapshot pre-check is never retained as successor-ride work`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            seedPlayable(pair, listOf(SyncTestValues.hash(1)))
            // Deliberately absent from the follower, so the pre-check's content half is false on
            // resume — that is the branch that retains.
            pair.leader.content.localHashes
                .add(SyncTestValues.hash(2).value)
            pair.leader.content.peerHashes
                .add(SyncTestValues.hash(2).value)
            val followerSession = rideSession(this, pair.follower.sync)
            followerSession.startRide()

            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()

            // The leader moves on with the Phase 5 wire severed, so the follower's only route to the
            // truth is the resync round trip below.
            pair.leader.syncSession.forwardTo(null)
            pair.leader.sync.playSynchronized(SyncTestValues.hash(2))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()
            pair.leader.syncSession.forwardTo(pair.follower.syncSession)

            // Park the **pre-check's** resolve, which is the first the follower takes for this
            // snapshot — `resolvePendingPlay` returns before resolving anything (the follower holds
            // no retained Play; the leader pressed Play), and `applyPlay`'s own resolve is further on.
            val gate = CompletableDeferred<Unit>()
            pair.follower.content.resolveGate = gate
            pair.follower.content.resolveGateWhen = { true }
            pair.follower.player.calls
                .clear()

            pair.follower.sync.forceDesynchronizedForTest()
            runCurrent()

            assertNull(pair.follower.content.resolveGateWhen, "never parked inside the pre-check's resolve")
            assertEquals(
                0,
                pair.follower.sync.diagnostics.value.deferredCommandCount,
                "parked after the append — this is not the window under test",
            )
            assertTrue(pair.follower.resync.diagnostics.value.requestPending, "no wire request was outstanding")
            // …and provably *after* the provenance capture: `onStateSnapshot` takes its
            // `RideAdmission` as its first statement and then awaits the queue half, so the queue
            // having been adopted is structural proof that the capture already happened.
            assertTrue(
                pair.follower.sync.queueState.value.items
                    .any { it.trackHash == SyncTestValues.hash(2) },
                "parked before the queue half ran, so before the ride provenance was captured",
            )

            // Both real ride boundaries, while the snapshot is provably parked.
            followerSession.endRide()
            runCurrent()
            followerSession.startRide()

            gate.complete(Unit)
            runCurrent()
            pair.followerClock.advanceBy(DEFERRED_RETRY_US * 4)
            runCurrent()

            assertEquals(
                0,
                pair.follower.sync.diagnostics.value.deferredCommandCount,
                "the snapshot was retained as successor-ride deferred work",
            )
            assertEquals(
                ResyncOutcome.CANCELLED,
                pair.follower.resync.diagnostics.value.lastOutcome,
                "a reconciliation whose ride ended must reach a terminal cancellation, never a deferral that outlives it",
            )
            assertTrue(
                pair.follower.content.transferRequests
                    .none { it == SyncTestValues.hash(2) },
                "a ride that is over asked Phase 4 for a transfer: ${pair.follower.content.transferRequests}",
            )
            assertNull(pair.follower.sync.diagnostics.value.currentTrackHash, "a ride-expired restore wrote currentTrackHash")
            assertTrue(
                pair.follower.player.calls
                    .none { it is FakeSyncPlayer.Call.Select },
                "a ride-expired restore reached the player: ${pair.follower.player.calls}",
            )
            assertFalse(pair.follower.resync.diagnostics.value.requestPending, "the wire request was left outstanding")
            assertTrue(pair.follower.manifestRefreshCalls.isEmpty(), "a cancelled reconciliation triggered a manifest refresh")
        }

    /**
     * Round 7's liveness half on Android: the identical deferral with **no** ride boundary at all
     * still reconciles from the retained event, against the very admission it was admitted under.
     * The fix must not be "refuse all retained work".
     */
    @Test
    fun `a valid same-ride deferred snapshot still reconciles when its content arrives`() =
        runTest(StandardTestDispatcher()) {
            val pair = ResyncTestPair(this)
            pair.connect(generation = 1)
            seedPlayable(pair, listOf(SyncTestValues.hash(1)))
            pair.leader.content.localHashes
                .add(SyncTestValues.hash(2).value)
            pair.leader.content.peerHashes
                .add(SyncTestValues.hash(2).value)
            val followerSession = rideSession(this, pair.follower.sync)
            followerSession.startRide()

            pair.leader.sync.playSynchronized(SyncTestValues.hash(1))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()

            pair.leader.syncSession.forwardTo(null)
            pair.leader.sync.playSynchronized(SyncTestValues.hash(2))
            runCurrent()
            pair.leaderClock.advanceBy(LEAD_US)
            runCurrent()
            pair.leader.syncSession.forwardTo(pair.follower.syncSession)

            pair.follower.sync.forceDesynchronizedForTest()
            runCurrent()
            assertEquals(ResyncOutcome.DEFERRED, pair.follower.resync.diagnostics.value.lastOutcome)
            assertEquals(
                1,
                pair.follower.sync.diagnostics.value.deferredCommandCount,
                "the obligation must be backed by exactly one retained event",
            )

            // The precondition resolves, and nothing about the ride changes.
            pair.follower.content.completeTransfer(SyncTestValues.hash(2))
            runCurrent()
            pair.followerClock.advanceBy(DEFERRED_RETRY_US * 2)
            runCurrent()

            assertEquals(
                ResyncOutcome.RECONCILED,
                pair.follower.resync.diagnostics.value.lastOutcome,
                "valid same-ride retained work no longer reconciles",
            )
            assertEquals(SyncTestValues.hash(2), pair.follower.sync.diagnostics.value.currentTrackHash)
            assertEquals(
                0,
                pair.follower.sync.diagnostics.value.retiredRideDeferredCount,
                "valid work was discarded as retired",
            )
        }

    /** The exact adapter `AppContainer` installs — this test must not invent a different one. */
    private class SyncRideSegmentOwner(
        private val sync: SyncPlaybackCoordinator,
    ) : com.ridelink.app.session.RideSegmentOwner {
        override fun nextRideEpoch(): Long = sync.rideEpochs.next()

        override fun endRideSegment(rideEpoch: Long) = sync.endRideSegment(rideEpoch)
    }

    private companion object {
        val LOCAL_PEER = PeerId("fedcba9876543210")
        val REMOTE_PEER = PeerId("0123456789abcdef")
        val READY_CLOCK = SessionClockEstimate(offsetToLeaderUs = 0L, rttP95Us = 8_000, ready = true)
        const val DEFERRED_RETRY_US = 100_000L
        const val LEAD_US = 200_000L
        const val STRESS_CYCLES = 50
    }
}
