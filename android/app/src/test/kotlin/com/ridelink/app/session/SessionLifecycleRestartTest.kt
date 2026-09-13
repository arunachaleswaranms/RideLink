package com.ridelink.app.session

import com.ridelink.core.audiopolicy.AudioRouteSnapshot
import com.ridelink.core.logging.InMemoryLogSink
import com.ridelink.core.model.ConnTiebreak
import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.model.SpkiHash
import com.ridelink.core.protocol.VoiceSignal
import com.ridelink.core.security.InMemoryTrustedPeerStore
import com.ridelink.core.sessionfsm.SessionEvent
import com.ridelink.core.sessionfsm.SessionStatus
import com.ridelink.core.voice.AudioProcessingConfig
import com.ridelink.core.voice.AudioProcessingStatus
import com.ridelink.core.voice.IceGatheringState
import com.ridelink.core.voice.MediaTransportState
import com.ridelink.core.voice.SdpKind
import com.ridelink.core.voice.VoiceAudioSession
import com.ridelink.core.voice.VoiceEngine
import com.ridelink.core.voice.VoiceEngineConfig
import com.ridelink.core.voice.VoiceEngineDiagnostics
import com.ridelink.core.voice.VoiceEngineEvent
import com.ridelink.core.voice.VoiceSignalTransport
import com.ridelink.network.control.ControlChannel
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.control.ControlListener
import com.ridelink.network.control.ControlSessionManager
import com.ridelink.network.control.ControlSocket
import com.ridelink.network.control.ControlState
import com.ridelink.network.control.LinkLossReason
import com.ridelink.network.control.LocalHandshakeIdentity
import com.ridelink.network.discovery.AdvertiseState
import com.ridelink.network.discovery.DiscoveryController
import com.ridelink.network.discovery.DiscoveryEvent
import com.ridelink.network.manifest.ManifestSink
import com.ridelink.network.playback.PlaybackSink
import com.ridelink.network.playback.QueueSink
import com.ridelink.network.transfer.TransferSink
import com.ridelink.network.voice.VoiceController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * **`docs/STATUS.md` §4 problem 53**, at the seam it lived in: `SessionFsm` has always had
 * `ENDING -> IDLE` on `TeardownComplete` and `DISCONNECTED -> DISCOVERING` on `RetryRequested`, and
 * **nothing in the app emitted either event**. So a session that ended stayed ended, a reconnect
 * budget that ran out stayed run out, and the only way to start a second ride was to force-quit.
 *
 * Emitting them is the easy half. The hard half — and what most of this file is about — is that
 * `TeardownComplete` is an **ownership claim**, not a label:
 *
 * > A session may enter `IDLE` only after every effect owned by the ending session has completed.
 * > No continuation owned by the retired session may mutate state after `TeardownComplete`.
 *
 * Before this pass the `ENDING` effect ended with `teardownSession()`, which cancelled the session
 * job and then **launched** `ControlSessionManager.shutdown()` and returned. Emitting
 * `TeardownComplete` at that point would have let a successor bind a listener that the predecessor's
 * still-pending `shutdown()` then closed, re-latched `isShutDown` behind it, and — through
 * `relays.reset()` — detached the successor's sinks. That interleaving was unreachable only because
 * problem 53 stopped the successor from existing at all; fixing 53 without fixing the ordering would
 * have made it reachable, which is why both move together here.
 *
 * **Everything under the assertions is production.** A real `SessionCoordinator`, a real
 * `ControlSessionManager`, a real `VoiceController` over a fake audio session, the real
 * `SessionFsm`, the real `SessionGate`, the real `AudioStatePublisher`. Nothing here applies
 * `TeardownComplete` or `RetryRequested` itself — the whole point is to watch production emit them —
 * and the two `internal` seams used (`applyEvent`, `handleControlEvent`) are the same ones
 * `SessionCoordinatorEndingEffectTest` uses, and are used only to *stand in for a socket*, never to
 * stand in for a lifecycle decision.
 *
 * `ParkingControlChannel.bind` never returns, on purpose: `ControlListener`'s constructor is
 * `internal` to `:network`, so a listener cannot be built here at all. That turns out to be the
 * right shape anyway — `bindCalls` is exactly the observable these tests need ("has the successor
 * reached the control plane yet?"), and a bind that parks is a faithful stand-in for one that is
 * merely slow.
 */
class SessionLifecycleRestartTest {
    // ----------------------------------------------------------------------------------------
    // A. ENDING completes on its own
    // ----------------------------------------------------------------------------------------

    /**
     * The headline of problem 53. A peer `BYE` reaches `ENDING`, and **production alone** — no test
     * injection of `TeardownComplete` anywhere — carries it to `IDLE`.
     */
    @Test
    fun `a peer BYE ends in IDLE without anything injecting TeardownComplete`() =
        withSession { sut ->
            sut.connect()
            sut.coordinator.startIntercom()
            sut.awaitTrue("capture open") { sut.audio.isOpen }

            sut.coordinator.handleControlEvent(ControlEvent.LinkLost(LinkLossReason.BYE))
            assertEquals(SessionStatus.ENDING, sut.coordinator.state.value.status)

            sut.awaitTrue("IDLE") { sut.coordinator.state.value.status == SessionStatus.IDLE }
            assertEquals(1, sut.audio.closeCaptureCount, "capture was released before IDLE")
            assertEquals(1, sut.fgs.stopCalls, "the foreground service was stopped before IDLE")
            assertEquals(
                ControlState.ENDED,
                sut.coordinator.controlDiagnostics.value.controlState,
                "the control plane was shut down before IDLE, not after it",
            )
        }

    /** The same, from this phone rather than the peer's. `endSession()` is the only emitter of `UserEnded`. */
    @Test
    fun `the user ending the session reaches IDLE the same way`() =
        withSession { sut ->
            sut.connect()
            sut.coordinator.endSession()
            assertEquals(SessionStatus.ENDING, sut.coordinator.state.value.status)
            sut.awaitTrue("IDLE") { sut.coordinator.state.value.status == SessionStatus.IDLE }
            assertEquals(1, sut.fgs.stopCalls)
        }

    /**
     * `ENDING -> IDLE` must not open before the teardown is terminal, so while a capture release is
     * still in flight the session stays in `ENDING` — and `startDiscovery()` stays refused, because
     * `SessionFsm` accepts it only from `IDLE`. This is the "prove it is impossible" half of the
     * successor-race requirement: on the `ENDING` path a successor genuinely cannot begin.
     */
    @Test
    fun `a stalled release holds ENDING open and refuses a successor outright`() =
        withSession { sut ->
            sut.connect()
            sut.coordinator.startIntercom()
            sut.awaitTrue("capture open") { sut.audio.isOpen }
            val closeGate = CompletableDeferred<Unit>()
            sut.audio.closeGate = closeGate

            sut.coordinator.handleControlEvent(ControlEvent.LinkLost(LinkLossReason.BYE))
            sut.awaitTrue("release in flight") { sut.audio.closeCalls > 0 }
            delay(SETTLE_MS)

            assertEquals(SessionStatus.ENDING, sut.coordinator.state.value.status)
            sut.coordinator.startDiscovery()
            assertEquals(
                SessionStatus.ENDING,
                sut.coordinator.state.value.status,
                "a successor may not start while the predecessor's teardown is unfinished",
            )
            assertEquals(1, sut.channel.bindCalls.get(), "and it must not have reached the control plane")

            closeGate.complete(Unit)
            sut.awaitTrue("IDLE once the release finishes") { sut.coordinator.state.value.status == SessionStatus.IDLE }
        }

    // ----------------------------------------------------------------------------------------
    // C. a second session, and that it is genuinely a new one
    // ----------------------------------------------------------------------------------------

    @Test
    fun `a second discovery session after a deliberate end is a new sender lifetime`() =
        withSession { sut ->
            sut.connect()
            val firstEpoch = sut.coordinator.audioStateSenderEpoch
            sut.coordinator.handleControlEvent(ControlEvent.LinkLost(LinkLossReason.BYE))
            sut.awaitTrue("IDLE") { sut.coordinator.state.value.status == SessionStatus.IDLE }

            sut.coordinator.startDiscovery()
            assertEquals(SessionStatus.DISCOVERING, sut.coordinator.state.value.status)
            assertNotEquals(
                firstEpoch,
                sut.coordinator.audioStateSenderEpoch,
                "ADR-021 Amendment A7: a new discovery session mints a new revision_epoch",
            )
            assertNull(sut.coordinator.peerAudioState.value, "the peer's held state belonged to the old session")
            sut.awaitTrue("the successor reached the control plane") { sut.channel.bindCalls.get() == 2 }
        }

    /** And the successor's own session works end to end — a second `CONNECTED` on the same coordinator. */
    @Test
    fun `the second session reaches CONNECTED and attaches its own voice subsystem`() =
        withSession { sut ->
            sut.connect()
            sut.coordinator.handleControlEvent(ControlEvent.LinkLost(LinkLossReason.BYE))
            sut.awaitTrue("IDLE") { sut.coordinator.state.value.status == SessionStatus.IDLE }
            assertNull(sut.manager.voice.sink, "the first session's voice sink was detached with it")

            sut.coordinator.startDiscovery()
            sut.awaitTrue("the successor reached the control plane") { sut.channel.bindCalls.get() == 2 }
            sut.connectFromDiscovering()

            assertEquals(SessionStatus.CONNECTED, sut.coordinator.state.value.status)
            assertNotNull(sut.manager.voice.sink, "the second session installed its own voice sink")
            assertNotNull(sut.manager.audioState.sink, "and its own AUDIO_STATE sink")
            assertEquals(2, sut.controllersBuilt.get(), "a new controller, not the retired one")
        }

    // ----------------------------------------------------------------------------------------
    // B / G. a retired teardown may not touch a successor
    // ----------------------------------------------------------------------------------------

    /**
     * **The adversarial case the brief asks for.** Session A's teardown is parked at its latest
     * meaningful suspension point — the awaited capture release — while Session B is brought all the
     * way to `CONNECTED` with its own voice controller, its own relay sinks and its own sender
     * lifetime. Then A's continuation is released.
     *
     * The interleaving is reached through the `handleControlEvent` seam, which stands in for a
     * socket: production's own `previousSession.join()` would not let B's *control plane* start this
     * early (`a successor's control plane waits for the predecessor's teardown` below asserts
     * exactly that). What this test isolates is the other half — that A's teardown detaches only
     * what A owned. Before this pass it did not: `ControlSessionManager.shutdown()` nulled every
     * relay sink, and `teardownSession()`'s trailing `releaseVoice()` nulled the coordinator's, so a
     * teardown arriving late took the successor's voice subsystem with it.
     */
    @Test
    fun `a parked teardown from the retired session cannot clear the successor's state`() =
        withSession { sut ->
            sut.connect()
            sut.coordinator.startIntercom()
            sut.awaitTrue("capture open") { sut.audio.isOpen }
            val closeGate = CompletableDeferred<Unit>()
            sut.audio.closeGate = closeGate

            // Exhaust the reconnect budget so the retry path — the one place the FSM lets a successor
            // begin while a predecessor is still being torn down — is the one under test.
            sut.coordinator.handleControlEvent(ControlEvent.LinkLost(LinkLossReason.NETWORK))
            assertEquals(SessionStatus.RECONNECTING, sut.coordinator.state.value.status)
            sut.coordinator.handleControlEvent(ControlEvent.ReconnectBudgetExhausted)
            assertEquals(SessionStatus.DISCONNECTED, sut.coordinator.state.value.status)

            sut.coordinator.retryDiscovery()
            assertEquals(SessionStatus.DISCOVERING, sut.coordinator.state.value.status)
            sut.awaitTrue("A's release is in flight") { sut.audio.closeCalls > 0 }

            // Session B, live, while A is still parked inside its release.
            sut.connectFromDiscovering()
            val successorVoiceSink = assertNotNull(sut.manager.voice.sink)
            val successorAudioSink = assertNotNull(sut.manager.audioState.sink)
            val successorEpoch = sut.coordinator.audioStateSenderEpoch
            val successorControllers = sut.controllersBuilt.get()

            closeGate.complete(Unit)
            sut.awaitTrue("A's teardown finished") { sut.fgs.stopCalls == 1 }
            delay(SETTLE_MS)

            assertSame(successorVoiceSink, sut.manager.voice.sink, "B's voice sink survived A's teardown")
            assertSame(successorAudioSink, sut.manager.audioState.sink, "B's AUDIO_STATE sink survived it")
            assertEquals(successorEpoch, sut.coordinator.audioStateSenderEpoch, "B's sender lifetime is untouched")
            assertEquals(successorControllers, sut.controllersBuilt.get(), "no controller was rebuilt")
            assertTrue(
                sut.coordinator.evaluateIntercomStart(
                    appForegroundVisible = true,
                    micPermissionGranted = true,
                    notificationsPermissionGranted = true,
                ) is com.ridelink.core.audiopolicy.RideStartDecision.Allowed,
                "B's voice controller is still live, so the readiness gate still sees an authenticated session",
            )
            assertEquals(1, sut.fgs.stopCalls, "A's teardown stopped the service once; B's start did not re-stop it")
        }

    /**
     * The other half of the same invariant, on the seam production actually takes: the successor's
     * control plane does not start until the predecessor's teardown is terminal.
     *
     * `bindCalls` is the observable, and the capture release is what holds the teardown open. Before
     * this pass Session B called `startListening()` immediately and A's still-launched
     * `ControlSessionManager.shutdown()` landed on top of it.
     */
    @Test
    fun `a successor's control plane waits for the predecessor's teardown`() =
        withSession { sut ->
            sut.connect()
            sut.coordinator.startIntercom()
            sut.awaitTrue("capture open") { sut.audio.isOpen }
            val closeGate = CompletableDeferred<Unit>()
            sut.audio.closeGate = closeGate

            sut.coordinator.handleControlEvent(ControlEvent.LinkLost(LinkLossReason.NETWORK))
            sut.coordinator.handleControlEvent(ControlEvent.ReconnectBudgetExhausted)
            sut.coordinator.retryDiscovery()

            sut.awaitTrue("A's release is in flight") { sut.audio.closeCalls > 0 }
            delay(SETTLE_MS)
            assertEquals(1, sut.channel.bindCalls.get(), "B must not have bound a listener yet")
            assertEquals(0, sut.fgs.stopCalls, "and A's teardown has not finished")

            closeGate.complete(Unit)
            sut.awaitTrue("B reached the control plane") { sut.channel.bindCalls.get() == 2 }
            assertEquals(1, sut.fgs.stopCalls, "A's deliberate end stopped the foreground service exactly once")
        }

    // ----------------------------------------------------------------------------------------
    // D. a reconnect is not a session end
    // ----------------------------------------------------------------------------------------

    @Test
    fun `a link blip keeps the sender lifetime, the capture device and the foreground service`() =
        withSession { sut ->
            sut.connect()
            sut.coordinator.startIntercom()
            sut.awaitTrue("capture open") { sut.audio.isOpen }
            val epoch = sut.coordinator.audioStateSenderEpoch

            sut.coordinator.handleControlEvent(ControlEvent.LinkLost(LinkLossReason.NETWORK))
            assertEquals(SessionStatus.RECONNECTING, sut.coordinator.state.value.status)
            sut.coordinator.handleControlEvent(
                ControlEvent.Connected(REMOTE_PEER, SessionId("s2"), isLocalLeader = true),
            )
            delay(SETTLE_MS)

            assertEquals(SessionStatus.CONNECTED, sut.coordinator.state.value.status)
            assertEquals(epoch, sut.coordinator.audioStateSenderEpoch, "a reconnect is one sender lifetime, not two")
            assertEquals(0, sut.audio.closeCalls, "a link blip must never release capture")
            assertEquals(0, sut.fgs.stopCalls, "nor stop the foreground service")
            assertEquals(1, sut.controllersBuilt.get(), "nor rebuild the voice controller")
            assertEquals(1, sut.channel.bindCalls.get(), "nor re-enter discovery")
        }

    // ----------------------------------------------------------------------------------------
    // E / F. retry, and retry from the wrong place
    // ----------------------------------------------------------------------------------------

    @Test
    fun `an exhausted reconnect budget is recoverable by an explicit user retry`() =
        withSession { sut ->
            sut.connect()
            val firstEpoch = sut.coordinator.audioStateSenderEpoch

            sut.coordinator.handleControlEvent(ControlEvent.LinkLost(LinkLossReason.NETWORK))
            sut.coordinator.handleControlEvent(ControlEvent.ReconnectBudgetExhausted)
            assertEquals(SessionStatus.DISCONNECTED, sut.coordinator.state.value.status)

            sut.coordinator.retryDiscovery()

            assertEquals(SessionStatus.DISCOVERING, sut.coordinator.state.value.status)
            assertNotEquals(firstEpoch, sut.coordinator.audioStateSenderEpoch, "a retry is a new discovery session")
            sut.awaitTrue("the retry reached the control plane") { sut.channel.bindCalls.get() == 2 }
            sut.connectFromDiscovering()
            assertEquals(SessionStatus.CONNECTED, sut.coordinator.state.value.status)
        }

    /** ARCHITECTURE §3 rule 3's second deliberate end (ADR-026): a retry releases capture and the service. */
    @Test
    fun `a user retry releases capture and stops the foreground service`() =
        withSession { sut ->
            sut.connect()
            sut.coordinator.startIntercom()
            sut.awaitTrue("capture open") { sut.audio.isOpen }

            sut.coordinator.handleControlEvent(ControlEvent.LinkLost(LinkLossReason.NETWORK))
            sut.coordinator.handleControlEvent(ControlEvent.ReconnectBudgetExhausted)
            sut.coordinator.retryDiscovery()

            sut.awaitTrue("capture released") { sut.audio.closeCaptureCount == 1 }
            sut.awaitTrue("foreground service stopped") { sut.fgs.stopCalls == 1 }
        }

    @Test
    fun `retry is refused from every state but DISCONNECTED`() =
        withSession { sut ->
            assertEquals(SessionStatus.IDLE, sut.coordinator.state.value.status)
            sut.coordinator.retryDiscovery()
            assertEquals(SessionStatus.IDLE, sut.coordinator.state.value.status)
            assertEquals(0, sut.channel.bindCalls.get())

            sut.connect()
            sut.coordinator.retryDiscovery()
            assertEquals(SessionStatus.CONNECTED, sut.coordinator.state.value.status)
            delay(SETTLE_MS)
            assertEquals(1, sut.channel.bindCalls.get(), "a refused retry starts nothing")
            assertEquals(0, sut.fgs.stopCalls, "and releases nothing")
        }

    // ----------------------------------------------------------------------------------------
    // Stop Discovery, the one restart path that already existed
    // ----------------------------------------------------------------------------------------

    @Test
    fun `stop then start discovery serialises the two sessions' control planes`() =
        withSession { sut ->
            sut.coordinator.startDiscovery()
            sut.awaitTrue("bound") { sut.channel.bindCalls.get() == 1 }

            sut.coordinator.cancelDiscovery()
            assertEquals(SessionStatus.IDLE, sut.coordinator.state.value.status)
            sut.coordinator.startDiscovery()
            assertEquals(SessionStatus.DISCOVERING, sut.coordinator.state.value.status)

            sut.awaitTrue("the second session bound") { sut.channel.bindCalls.get() == 2 }
            delay(SETTLE_MS)
            assertEquals(
                ControlState.IDLE,
                sut.coordinator.controlDiagnostics.value.controlState,
                "the predecessor's shutdown ran before the successor's startListening, not after it — " +
                    "ControlState.ENDED here would mean the successor's control plane had been shut down",
            )
        }

    /** `cancelDiscovery` from a state the FSM refuses must not retire anything. */
    @Test
    fun `cancel discovery is a no-op outside DISCOVERING`() =
        withSession { sut ->
            sut.connect()
            sut.coordinator.cancelDiscovery()
            delay(SETTLE_MS)
            assertEquals(SessionStatus.CONNECTED, sut.coordinator.state.value.status)
            assertNotNull(sut.manager.voice.sink, "a refused cancel retires nothing")
            assertFalse(sut.audio.closeCalls > 0)
        }

    // ==========================================================================================
    // harness
    // ==========================================================================================

    // ----------------------------------------------------------------------------------------
    // H. the same boundary, fifty times (the thirty-fifth session's §S2-2 sweep)
    // ----------------------------------------------------------------------------------------

    /**
     * Everything above proves **a** restart is correct, which is what ADR-026 needed. This proves
     * fifty are, and it exists because the class of defect problem 54 belonged to is not visible in
     * one: a sink detached once and never re-installed, a latch that fails to un-latch, a counter
     * that accumulates, a listener bound twice, a controller retained into a session it does not
     * belong to. Each is invisible at N = 1 and obvious at N = 50.
     *
     * The rule asserted once per cycle: **what belongs to the process survives every boundary
     * unchanged, and what belongs to a session exists during exactly its own.**
     */
    @Test
    fun `fifty end-to-restart cycles leak nothing and disable nothing`() =
        withSession { sut ->
            // Installed **once**, exactly as `SharedLibraryCoordinator` and `SyncPlaybackCoordinator`
            // install theirs in their constructors: once per process, and never re-installed.
            val manifestSink = ManifestSink { _, _ -> }
            val transferSink = TransferSink { _, _ -> }
            val playbackSink = PlaybackSink { _, _ -> }
            val queueSink = QueueSink { _, _ -> }
            sut.manager.manifest.sink = manifestSink
            sut.manager.transfer.sink = transferSink
            sut.manager.playback.playbackSink = playbackSink
            sut.manager.playback.queueSink = queueSink

            val epochs = mutableSetOf<com.ridelink.core.protocol.AudioStateEpoch>()

            repeat(CYCLES) { i ->
                val cycle = i + 1
                sut.coordinator.startDiscovery()
                assertEquals(SessionStatus.DISCOVERING, sut.coordinator.state.value.status, "cycle $cycle could not start")
                // `startListening` is what un-latches `isShutDown`, so one bind per cycle is also the
                // proof the latch released — and exactly one is the proof no listener was bound twice.
                sut.awaitTrue("cycle $cycle bound the control plane") { sut.channel.bindCalls.get() == cycle }

                epochs += sut.coordinator.audioStateSenderEpoch
                sut.connectFromDiscovering()

                assertNotNull(sut.manager.voice.sink, "cycle $cycle installed no voice sink")
                assertNotNull(sut.manager.audioState.sink, "cycle $cycle installed no AUDIO_STATE sink")
                assertEquals(cycle, sut.controllersBuilt.get(), "cycle $cycle must build its own controller, never reuse a retired one")
                assertSame(manifestSink, sut.manager.manifest.sink, "cycle $cycle lost the manifest sink mid-session")
                assertSame(playbackSink, sut.manager.playback.playbackSink, "cycle $cycle lost the playback sink mid-session")

                sut.coordinator.handleControlEvent(ControlEvent.LinkLost(LinkLossReason.BYE))
                sut.awaitTrue("cycle $cycle reached IDLE") { sut.coordinator.state.value.status == SessionStatus.IDLE }

                // Still the very same objects after the teardown has fully completed. This is what
                // problem 54 failed on the *first* cycle, and what any future re-introduction of a
                // blanket `reset()` would fail on again.
                assertSame(manifestSink, sut.manager.manifest.sink, "cycle $cycle's teardown took the manifest sink")
                assertSame(transferSink, sut.manager.transfer.sink, "cycle $cycle's teardown took the transfer sink")
                assertSame(playbackSink, sut.manager.playback.playbackSink, "cycle $cycle's teardown took the playback sink")
                assertSame(queueSink, sut.manager.playback.queueSink, "cycle $cycle's teardown took the queue sink")

                // …and the per-session ones went with the session that owned them.
                assertNull(sut.manager.voice.sink, "cycle $cycle's voice sink outlived its session")
                assertNull(sut.manager.audioState.sink, "cycle $cycle's AUDIO_STATE sink outlived its session")
                assertNull(sut.coordinator.peerAudioState.value, "cycle $cycle inherited a dead session's peer state")
            }

            assertEquals(
                CYCLES,
                epochs.size,
                "ADR-021 Amendment A7: every session is its own sender lifetime, so no revision_epoch may repeat",
            )
            assertEquals(CYCLES, sut.channel.bindCalls.get(), "exactly one listener bind per session, never two")
            assertEquals(CYCLES, sut.controllersBuilt.get(), "exactly one voice controller per session")
        }

    /**
     * The same sweep with the intercom actually started, because capture is the resource whose
     * mishandling is least recoverable — ARCHITECTURE §6.4 forbids reopening a microphone from the
     * background, so a cycle that leaked one would strand it for the rest of the process.
     */
    @Test
    fun `twenty intercom cycles open and release capture exactly once each`() =
        withSession { sut ->
            repeat(INTERCOM_CYCLES) { i ->
                val cycle = i + 1
                sut.coordinator.startDiscovery()
                sut.awaitTrue("cycle $cycle bound") { sut.channel.bindCalls.get() == cycle }
                sut.connectFromDiscovering()

                sut.coordinator.startIntercom()
                sut.awaitTrue("cycle $cycle opened capture") { sut.audio.isOpen }
                assertEquals(cycle, sut.audio.openCaptureCount, "cycle $cycle must open capture exactly once")

                sut.coordinator.handleControlEvent(ControlEvent.LinkLost(LinkLossReason.BYE))
                sut.awaitTrue("cycle $cycle reached IDLE") { sut.coordinator.state.value.status == SessionStatus.IDLE }

                assertFalse(sut.audio.isOpen, "cycle $cycle left the capture device open past its session")
                assertEquals(cycle, sut.audio.closeCaptureCount, "cycle $cycle must release capture exactly once")
                assertEquals(cycle, sut.fgs.stopCalls, "the foreground service is stopped once per deliberate end")
            }
        }

    private class Sut(
        val coordinator: SessionCoordinator,
        val manager: ControlSessionManager,
        val channel: ParkingControlChannel,
        val audio: FakeVoiceAudioSession,
        val fgs: FakeForegroundService,
        val controllersBuilt: AtomicInteger,
    ) {
        /** `IDLE -> … -> CONNECTED`, through the production FSM and the production trust gate. */
        suspend fun connect() {
            coordinator.startDiscovery()
            awaitTrue("bound") { channel.bindCalls.get() >= 1 }
            connectFromDiscovering()
        }

        /**
         * The walk a real discovery would take from `DISCOVERING` on: a peer is selected, the stored
         * pin matches, the trust gate passes. `PeerSelected` is applied directly because
         * `maybeConnect` needs an mDNS `Found` and this harness has no listener to advertise on.
         */
        suspend fun connectFromDiscovering() {
            assertTrue(coordinator.applyEvent(SessionEvent.PeerSelected))
            coordinator.handleControlEvent(ControlEvent.PeerTrusted(REMOTE_PEER))
            assertEquals(SessionStatus.CONNECTING, coordinator.state.value.status)
            coordinator.handleControlEvent(
                ControlEvent.Connected(REMOTE_PEER, SessionId("test-session"), isLocalLeader = true),
            )
            assertEquals(SessionStatus.CONNECTED, coordinator.state.value.status)
        }

        suspend fun awaitTrue(
            what: String,
            condition: () -> Boolean,
        ) {
            try {
                withTimeout(AWAIT_TIMEOUT_MS) { while (!condition()) delay(POLL_MS) }
            } catch (timeout: TimeoutCancellationException) {
                throw AssertionError("timed out waiting for '$what'", timeout)
            }
        }
    }

    /**
     * A `ControlChannel` whose `bind()` records the attempt and then never returns.
     *
     * `ControlListener`'s constructor is `internal` to `:network`, so one cannot be built from `:app`
     * at all. Parking is the honest alternative, and it happens to be exactly what these tests need:
     * `bindCalls` answers "has this session reached the control plane?", which is the whole question
     * the teardown ordering is about.
     */
    private class ParkingControlChannel : ControlChannel {
        override val transportLabel: String = "test"
        override val isSecure: Boolean = true
        val bindCalls = AtomicInteger(0)
        private val never = CompletableDeferred<Unit>()

        override suspend fun bind(): ControlListener {
            bindCalls.incrementAndGet()
            never.await()
            error("unreachable: this channel never finishes binding")
        }

        override suspend fun connect(
            host: String,
            port: Int,
        ): ControlSocket = error("not used by this test")
    }

    private class SilentDiscoveryController : DiscoveryController {
        override fun advertise(
            port: Int,
            rotationIntervalMs: Long,
        ): Flow<AdvertiseState> = emptyFlow()

        override fun browse(): Flow<DiscoveryEvent> = emptyFlow()
    }

    private class FakeForegroundService : ForegroundServiceController {
        @Volatile var stopCalls = 0
            private set

        override fun stop() {
            stopCalls += 1
        }
    }

    /** Mirrors `SessionCoordinatorEndingEffectTest.FakeVoiceAudioSession`, kept local and minimal. */
    private class FakeVoiceAudioSession : VoiceAudioSession {
        @Volatile var closeCaptureCount = 0
            private set

        @Volatile var openCaptureCount = 0
            private set

        @Volatile var closeCalls = 0
            private set

        override var isOpen: Boolean = false
            private set

        override var route: AudioRouteSnapshot = AudioRouteSnapshot()
            private set

        var closeGate: CompletableDeferred<Unit>? = null
        private var sink: ((AudioRouteSnapshot) -> Unit)? = null

        override fun setRouteSink(sink: (AudioRouteSnapshot) -> Unit) {
            this.sink = sink
        }

        override suspend fun open(): Result<Unit> {
            if (isOpen) return Result.success(Unit)
            isOpen = true
            openCaptureCount += 1
            sink?.invoke(route)
            return Result.success(Unit)
        }

        override suspend fun close() {
            closeCalls += 1
            closeGate?.await()
            if (isOpen) closeCaptureCount += 1
            isOpen = false
        }
    }

    private class FakeVoiceEngine : VoiceEngine {
        override var diagnostics: VoiceEngineDiagnostics =
            VoiceEngineDiagnostics(audioProcessing = AudioProcessingStatus(true, true, true, false))
        private var sink: ((VoiceEngineEvent) -> Unit)? = null

        override fun setEventSink(sink: (VoiceEngineEvent) -> Unit) {
            this.sink = sink
        }

        override suspend fun start(config: VoiceEngineConfig): Result<Unit> {
            diagnostics = diagnostics.copy(transportState = MediaTransportState.NEW, localAudioTrackPresent = true)
            return Result.success(Unit)
        }

        override suspend fun createOffer(): Result<Unit> = Result.success(Unit)

        override suspend fun createAnswer(): Result<Unit> = Result.success(Unit)

        override suspend fun applyRemoteDescription(
            kind: SdpKind,
            sdp: String,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun addRemoteCandidate(
            candidate: String,
            sdpMid: String?,
            sdpMlineIndex: Int,
        ): Result<Unit> = Result.success(Unit)

        override fun setMicrophoneMuted(muted: Boolean) = Unit

        override suspend fun stop() {
            diagnostics =
                diagnostics.copy(
                    transportState = MediaTransportState.CLOSED,
                    iceGatheringState = IceGatheringState.NEW,
                    remoteAudioTrackPresent = false,
                )
        }

        override suspend fun release() {
            diagnostics = VoiceEngineDiagnostics(transportState = MediaTransportState.CLOSED)
        }

        override suspend fun refreshDiagnostics() = Unit
    }

    private class NoOpVoiceTransport : VoiceSignalTransport {
        override suspend fun send(signal: VoiceSignal): Boolean = false
    }

    private fun withSession(body: suspend (Sut) -> Unit) =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob())
            try {
                val channel = ParkingControlChannel()
                val audio = FakeVoiceAudioSession()
                val fgs = FakeForegroundService()
                val controllersBuilt = AtomicInteger(0)
                val manager =
                    ControlSessionManager(
                        scope = scope,
                        monotonicNowUs = { 0L },
                        localPeerId = PeerId("fedcba9876543210"),
                        channel = channel,
                        trustedPeers = InMemoryTrustedPeerStore(),
                    )
                val coordinator =
                    SessionCoordinator(
                        discovery = SilentDiscoveryController(),
                        controlSessionManager = manager,
                        localIdentity =
                            LocalHandshakeIdentity(
                                displayName = "test-device",
                                platform = "android",
                                osVersion = "test",
                                appVersion = "test",
                                connTiebreak = ConnTiebreak("1".repeat(32)),
                                identitySpkiSha256 = SpkiHash("sha256:" + "ab".repeat(32)),
                            ),
                        scope = scope,
                        logSink = InMemoryLogSink(),
                        trustedPeers = InMemoryTrustedPeerStore(),
                        environment =
                            SessionEnvironment(
                                monotonicNowUs = { 0L },
                                nowEpochSeconds = { 0L },
                                audioEndpointPresent = { true },
                            ),
                        foregroundService = fgs,
                        buildVoiceController = { isLocalLeader ->
                            controllersBuilt.incrementAndGet()
                            VoiceController(
                                scope = scope,
                                engine = FakeVoiceEngine(),
                                audioSession = audio,
                                transport = NoOpVoiceTransport(),
                                isLocalLeader = isLocalLeader,
                                localTrackId = "test-track",
                                audioProcessing = AudioProcessingConfig(),
                            )
                        },
                    )
                body(Sut(coordinator, manager, channel, audio, fgs, controllersBuilt))
            } finally {
                scope.cancel()
            }
        }

    private companion object {
        val REMOTE_PEER = PeerId("0123456789abcdef")
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val POLL_MS = 2L
        const val SETTLE_MS = 60L
        const val CYCLES = 50
        const val INTERCOM_CYCLES = 20
    }
}
