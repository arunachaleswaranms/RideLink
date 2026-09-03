package com.ridelink.network.voice

import com.ridelink.core.audiopolicy.AudioRouteChangeReason
import com.ridelink.core.audiopolicy.AudioRouteSnapshot
import com.ridelink.core.audiopolicy.IntercomMode
import com.ridelink.core.audiopolicy.IntercomPolicy
import com.ridelink.core.audiopolicy.VoiceFailure
import com.ridelink.core.protocol.VoiceMode
import com.ridelink.core.protocol.VoiceSessionId
import com.ridelink.core.protocol.VoiceSignal
import com.ridelink.core.voice.MediaTransportState
import com.ridelink.core.voice.VoiceAudioSessionFailure
import com.ridelink.core.voice.VoiceEngineEvent
import com.ridelink.core.voice.VoiceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 2b's intercom lifecycle, driven through the **real** [VoiceController] against fakes.
 *
 * `IntercomVectorTest` proves the pure gate. This proves the wiring, which is the part a pure table
 * cannot express: that a PTT press reaches the engine's track and **nothing else**, that fifty of
 * them leave the capture device exactly as they found it, that a policy switch is announced on both
 * wire planes, and that a link blip and a rebuild do not reopen capture.
 *
 * **None of this is evidence that voice works.** A fake engine proves the controller; real Opus over
 * real DTLS-SRTP is proven on the Apple side by `VoiceEngineLoopbackTests` and on Android not at all
 * yet (docs/STATUS.md §4 problem 22). TEST_PLAN A-10 is the hardware form of the central assertion
 * below and remains pending.
 */
class VoiceControllerIntercomTest {
    // --- the capture-open invariant (this phase's brief §32; TEST_PLAN A-10's laptop half) --------

    /**
     * **The regression test this phase exists to leave behind.**
     *
     * Start Intercom opens capture once. Fifty press/release cycles follow. The capture device is
     * never opened again and never closed, and every press reaches the engine as a track-enable —
     * because thrashing a Bluetooth endpoint between its media and duplex profiles per utterance is
     * the single worst thing this product can do to music (ARCHITECTURE §6.3).
     *
     * It would fail immediately if a future change routed PTT through
     * [com.ridelink.core.voice.VoiceAudioSession] instead of through the track.
     */
    @Test
    fun `fifty PTT presses never reopen or close the capture device`() =
        withIntercom(IntercomPolicy.MODE_C) { voice, fakes ->
            voice.start()
            fakes.awaitEngineCall("createOffer")
            assertEquals(1, fakes.audio.openCaptureCount, "Start Intercom opens capture exactly once")

            val generationBefore = voice.diagnostics.value.voiceSessionPrefix
            fakes.engine.calls.clear()

            repeat(PRESS_COUNT) {
                voice.setPushToTalkHeld(true)
                fakes.awaitEngineCall("setMicrophoneMuted(false)")
                voice.setPushToTalkHeld(false)
                fakes.awaitEngineCall("setMicrophoneMuted(true)")
                fakes.engine.calls.clear()
            }

            assertEquals(1, fakes.audio.openCaptureCount, "$PRESS_COUNT presses must not reopen capture")
            assertEquals(0, fakes.audio.closeCaptureCount, "$PRESS_COUNT presses must not close capture")
            assertTrue(fakes.audio.isOpen, "capture is still open for the ride segment")
            assertEquals(
                generationBefore,
                voice.diagnostics.value.voiceSessionPrefix,
                "PTT must not change voice_session_id",
            )
            assertFalse(
                fakes.engine.calls.any { it.startsWith("start(") || it == "stop" || it == "release" },
                "PTT must not rebuild the peer connection",
            )
        }

    /** And the same for mute, which is the other thing a naive implementation would route to hardware. */
    @Test
    fun `mute and unmute never touch the capture device or rebuild media`() =
        withIntercom(IntercomPolicy.MODE_A) { voice, fakes ->
            voice.start()
            fakes.awaitEngineCall("createOffer")
            val generationBefore = voice.diagnostics.value.voiceSessionPrefix
            fakes.engine.calls.clear()

            repeat(MUTE_CYCLES) {
                voice.setMicrophoneMuted(true)
                fakes.awaitEngineCall("setMicrophoneMuted(true)")
                voice.setMicrophoneMuted(false)
                fakes.awaitEngineCall("setMicrophoneMuted(false)")
            }

            assertEquals(1, fakes.audio.openCaptureCount)
            assertEquals(0, fakes.audio.closeCaptureCount)
            assertEquals(generationBefore, voice.diagnostics.value.voiceSessionPrefix)
            assertFalse(
                fakes.engine.calls.any { it.startsWith("start(") || it == "stop" || it == "release" },
                "mute must not rebuild the peer connection",
            )
        }

    // --- the gate itself, through the controller ------------------------------------------------

    @Test
    fun `under PTT nothing is transmitted until the button is held`() =
        withIntercom(IntercomPolicy.MODE_C) { voice, fakes ->
            voice.start()
            fakes.awaitEngineCall("createOffer")
            // The gate is the single source of `mic_muted`, so the wire says "transmitting silence"
            // from the moment capture opens rather than claiming otherwise until the first press.
            // Awaited on the observable state rather than on an engine call: the engine is told the
            // gate's value when the peer connection is built, so the call name is already in the log by
            // the time capture opens and a name-based await would prove nothing.
            fakes.await("gate closed") { voice.diagnostics.value.micMuted }
            assertFalse(voice.diagnostics.value.transmitting)
            assertEquals(true, fakes.engine.muted, "the track must be disabled before the first press")

            voice.setPushToTalkHeld(true)
            fakes.awaitEngineCall("setMicrophoneMuted(false)")
            assertTrue(voice.diagnostics.value.transmitting)
            assertTrue(voice.diagnostics.value.pttHeld)
            assertFalse(voice.diagnostics.value.micMuted)
        }

    @Test
    fun `under full duplex transmission starts as soon as capture opens`() =
        withIntercom(IntercomPolicy.MODE_A) { voice, fakes ->
            voice.start()
            fakes.awaitEngineCall("createOffer")
            fakes.await("transmitting") { voice.diagnostics.value.transmitting }
            assertFalse(voice.diagnostics.value.micMuted)
            assertTrue(voice.diagnostics.value.policy.fullDuplex)
        }

    /** Mode E has no intercom: nothing transmits, whatever the user presses. */
    @Test
    fun `under mode E no press transmits`() =
        withIntercom(IntercomPolicy.MODE_E) { voice, fakes ->
            voice.start()
            fakes.awaitEngineCall("createOffer")
            voice.setPushToTalkHeld(true)
            // Nothing to await: the gate cannot open. Give the consumer a turn, then assert.
            fakes.settle()
            assertFalse(voice.diagnostics.value.transmitting)
            assertEquals(IntercomMode.DISABLED, voice.diagnostics.value.intercomMode)
        }

    /** This phase's brief §25: backgrounding while held releases the gate, not the device. */
    @Test
    fun `backgrounding while held stops transmitting and keeps capture open`() =
        withIntercom(IntercomPolicy.MODE_C) { voice, fakes ->
            voice.start()
            fakes.awaitEngineCall("createOffer")
            voice.setPushToTalkHeld(true)
            fakes.awaitEngineCall("setMicrophoneMuted(false)")

            voice.onAppBackgrounded()
            // Awaited on the observable state rather than on an engine call: `setMicrophoneMuted(true)`
            // is already in the log from the capture-open reconciliation, so a call-name await would
            // return immediately and prove nothing.
            fakes.await("stopped transmitting") { !voice.diagnostics.value.transmitting }
            assertFalse(voice.diagnostics.value.transmitting, "must never be left stuck on")
            assertFalse(voice.diagnostics.value.pttHeld)
            assertEquals(0, fakes.audio.closeCaptureCount, "the ride segment keeps its capture device")
        }

    /** A platform interruption wins over a held button, and it is a route fact, not a media failure. */
    @Test
    fun `an interruption stops transmitting without closing capture`() =
        withIntercom(IntercomPolicy.MODE_A) { voice, fakes ->
            voice.start()
            fakes.awaitEngineCall("createOffer")
            fakes.await("transmitting") { voice.diagnostics.value.transmitting }

            fakes.audio.publish(
                AudioRouteSnapshot(interrupted = true, lastChangeReason = AudioRouteChangeReason.INTERRUPTION_BEGAN),
            )
            fakes.await("interruption stopped it") { !voice.diagnostics.value.transmitting }
            assertEquals(0, fakes.audio.closeCaptureCount)

            fakes.audio.publish(
                AudioRouteSnapshot(interrupted = false, lastChangeReason = AudioRouteChangeReason.INTERRUPTION_ENDED),
            )
            fakes.await("transmitting again") { voice.diagnostics.value.transmitting }
        }

    // --- policy switching -----------------------------------------------------------------------

    /** A mode change is announced on both wire planes and touches neither capture nor the generation. */
    @Test
    fun `switching policy announces the new mode on the wire without rebuilding anything`() =
        withIntercom(IntercomPolicy.MODE_C) { voice, fakes ->
            voice.start()
            fakes.awaitEngineCall("createOffer")
            val generationBefore = voice.diagnostics.value.voiceSessionPrefix
            fakes.engine.calls.clear()

            voice.selectPolicy(IntercomPolicy.MODE_A)
            // **Two independently-observable things, so both are awaited.** `transport.send` happens
            // inside the action loop and `publishDiagnostics` runs after it, so the wire frame is
            // visible a few instructions before the diagnostics that describe it. Asserting the
            // diagnostics straight after a frame-based await is a race this test lost about one run in
            // ten — found by the §52 stress pass, not by a single run.
            fakes.await("continuous announced on the wire") {
                fakes.transport.sent
                    .filterIsInstance<VoiceSignal.State>()
                    .any { it.mode == VoiceMode.CONTINUOUS }
            }
            fakes.await("continuous in the diagnostics") {
                voice.diagnostics.value.intercomMode == IntercomMode.CONTINUOUS
            }
            assertEquals(1, fakes.audio.openCaptureCount)
            assertEquals(0, fakes.audio.closeCaptureCount)
            assertEquals(generationBefore, voice.diagnostics.value.voiceSessionPrefix)
            assertFalse(fakes.engine.calls.any { it.startsWith("start(") || it == "stop" || it == "release" })
        }

    /** Switching into PTT must not inherit a press from the previous policy. */
    @Test
    fun `switching from full duplex to PTT stops transmitting`() =
        withIntercom(IntercomPolicy.MODE_A) { voice, fakes ->
            voice.start()
            fakes.awaitEngineCall("createOffer")
            fakes.await("transmitting") { voice.diagnostics.value.transmitting }

            voice.selectPolicy(IntercomPolicy.MODE_C)
            fakes.await("gate closed by the switch") { !voice.diagnostics.value.transmitting }
            assertFalse(voice.diagnostics.value.pttHeld)
            assertEquals(true, fakes.engine.muted, "the track must be disabled by the switch")
        }

    // --- reconnect and teardown -----------------------------------------------------------------

    /**
     * PROTOCOL §7.8: a control-link blip drops the media transport and **keeps** capture, then a
     * rebuild is a fresh generation. The gate's state survives the blip, so a held button does not
     * silently become a transmission on the new generation.
     */
    @Test
    fun `a link loss keeps capture and the rebuild does not reopen it`() =
        withIntercom(IntercomPolicy.MODE_C) { voice, fakes ->
            voice.start()
            fakes.awaitEngineCall("createOffer")
            val firstGeneration = voice.diagnostics.value.voiceSessionPrefix

            voice.onControlLinkLost()
            fakes.awaitEngineCall("stop")
            assertEquals(0, fakes.audio.closeCaptureCount, "a link blip must not close capture")
            assertEquals(VoiceFailure.CONTROL_LINK_LOST, voice.diagnostics.value.lastFailure)

            voice.start()
            fakes.await("rebuilt") { voice.diagnostics.value.voiceSessionPrefix != firstGeneration }
            assertEquals(1, fakes.audio.openCaptureCount, "the rebuild reuses the open capture device")
            assertEquals(0, fakes.audio.closeCaptureCount)
        }

    /** A press that arrives against a torn-down generation cannot transmit on the next one. */
    @Test
    fun `a stale media callback cannot re-enable transmission`() =
        withIntercom(IntercomPolicy.MODE_C) { voice, fakes ->
            voice.start()
            fakes.awaitEngineCall("createOffer")
            voice.onControlLinkLost()
            fakes.awaitEngineCall("stop")

            // A connectivity callback from the generation that has just been torn down.
            fakes.engine.emit(
                VoiceEngineEvent.TransportStateChanged(VoiceSessionId(GEN_1), MediaTransportState.CONNECTED),
            )
            fakes.settle()
            assertEquals(VoiceStatus.IDLE, voice.diagnostics.value.status, "a stale callback is inert")
            assertFalse(voice.diagnostics.value.transmitting)
        }

    /** An explicit stop releases capture — the one case that may, because a user is present. */
    @Test
    fun `Stop Intercom releases capture and closes the gate`() =
        withIntercom(IntercomPolicy.MODE_A) { voice, fakes ->
            voice.start()
            fakes.awaitEngineCall("createOffer")
            fakes.await("transmitting") { voice.diagnostics.value.transmitting }

            voice.stop()
            fakes.awaitEngineCall("release")
            fakes.await("capture released") { fakes.audio.closeCaptureCount == 1 }
            assertFalse(voice.diagnostics.value.transmitting)
            assertFalse(voice.diagnostics.value.localAudioOpen)
        }

    // --- failures (this phase's brief §41) ------------------------------------------------------

    /** A denied microphone is named, the session is untouched, and nothing transmits. */
    @Test
    fun `a denied microphone is reported by name and nothing transmits`() =
        withIntercom(IntercomPolicy.MODE_A, audioFailure = VoiceFailure.MIC_PERMISSION_DENIED) { voice, fakes ->
            voice.start()
            fakes.awaitEngineCall("createOffer")
            fakes.settle()
            assertEquals(VoiceFailure.MIC_PERMISSION_DENIED, voice.diagnostics.value.lastFailure)
            assertFalse(voice.diagnostics.value.transmitting, "no capture path means no transmission")
            assertFalse(voice.diagnostics.value.localAudioOpen)
            assertEquals(0, fakes.audio.openCaptureCount)
        }

    @Test
    fun `a media failure is reported as WEBRTC_FAILED rather than a generic failure`() =
        withIntercom(IntercomPolicy.MODE_A) { voice, fakes ->
            voice.start()
            fakes.awaitEngineCall("createOffer")
            val generation = VoiceSessionId(GEN_1)
            fakes.engine.emit(VoiceEngineEvent.TransportStateChanged(generation, MediaTransportState.FAILED))
            fakes.await("failed") { voice.diagnostics.value.lastFailure == VoiceFailure.WEBRTC_FAILED }
            assertEquals(0, fakes.audio.closeCaptureCount, "a media failure does not close capture")
        }

    // --- setup timing ---------------------------------------------------------------------------

    /**
     * The software setup figures, from a clock the test supplies. **Not latency** — mouth-to-ear is
     * TEST_PLAN A-09/V-11 and needs hardware.
     */
    @Test
    fun `setup timings are recorded from the supplied monotonic clock`() =
        withIntercom(IntercomPolicy.MODE_A) { voice, fakes ->
            voice.start()
            fakes.awaitEngineCall("createOffer")
            // The fake engine records that it was asked for an offer but does not author one, so the
            // test supplies the callback a real `WebRtcVoiceEngine` would — which is the input the
            // local-SDP milestone is taken from.
            fakes.engine.emit(VoiceEngineEvent.OfferCreated(VoiceSessionId(GEN_1), "v=0\r\n"))
            fakes.engine.emit(VoiceEngineEvent.RemoteTrackChanged(VoiceSessionId(GEN_1), present = true))
            fakes.await("setup recorded") { voice.diagnostics.value.setup.setupMs != null }

            val setup = voice.diagnostics.value.setup
            assertTrue(setup.captureOpenMs != null, "capture-open must be timed")
            assertTrue(setup.localDescriptionMs != null, "the local SDP must be timed")
            assertTrue(
                (setup.setupMs ?: 0.0) >= (setup.captureOpenMs ?: 0.0),
                "the end-to-end figure cannot precede a stage inside it",
            )
        }

    // --- harness --------------------------------------------------------------------------------

    private class Fakes(
        val engine: FakeVoiceEngine,
        val audio: FakeVoiceAudioSession,
        val transport: RecordingVoiceTransport,
    ) {
        suspend fun awaitEngineCall(call: String) = await(call) { engine.calls.contains(call) }

        /**
         * Polls rather than latches, deliberately: every path under test crosses the controller's
         * single consumer coroutine, and a poll with a timeout says "this happened" without the test
         * needing to know how many drains it took. A timeout fails with what was actually seen.
         */
        suspend fun await(
            what: String,
            condition: () -> Boolean,
        ) {
            try {
                withTimeout(AWAIT_TIMEOUT_MS) {
                    while (!condition()) delay(POLL_MS)
                }
            } catch (timeout: TimeoutCancellationException) {
                // The name is the whole value of the failure message: "timed out waiting for
                // transmitting" says what did not happen, where a bare timeout does not.
                throw AssertionError(
                    "timed out waiting for '$what' — engine calls: ${engine.calls}, audio calls: ${audio.calls}",
                    timeout,
                )
            }
        }

        /** Gives the consumer several turns, for the assertions that are about something *not* happening. */
        suspend fun settle() {
            repeat(SETTLE_TURNS) { delay(POLL_MS) }
        }
    }

    private fun withIntercom(
        policy: IntercomPolicy,
        audioFailure: VoiceFailure? = null,
        body: suspend (VoiceController, Fakes) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val engine = FakeVoiceEngine()
            val audio = FakeVoiceAudioSession()
            audioFailure?.let { audio.openResult = Result.failure(VoiceAudioSessionFailure(it)) }
            val transport = RecordingVoiceTransport()
            var clock = 1_000_000L
            val controller =
                VoiceController(
                    scope = scope,
                    engine = engine,
                    audioSession = audio,
                    transport = transport,
                    isLocalLeader = true,
                    localTrackId = "ridelink-voice",
                    // A monotonic clock that advances a fixed step per read, so the setup figures are
                    // deterministic and non-zero without any real time passing.
                    monotonicNowUs = {
                        clock += CLOCK_STEP_US
                        clock
                    },
                    newVoiceSessionId = SequencedVoiceSessionIds(GEN_1, GEN_2, GEN_3)::next,
                )
            controller.selectPolicy(policy)
            body(controller, Fakes(engine, audio, transport))
            controller.shutdown()
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val PRESS_COUNT = 50
        const val MUTE_CYCLES = 10
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val POLL_MS = 2L
        const val SETTLE_TURNS = 25
        const val CLOCK_STEP_US = 1_000L
        const val GEN_1 = "11111111111111111111111111111111"
        const val GEN_2 = "22222222222222222222222222222222"
        const val GEN_3 = "33333333333333333333333333333333"
    }
}
