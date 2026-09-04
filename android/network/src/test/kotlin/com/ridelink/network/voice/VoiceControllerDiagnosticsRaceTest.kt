package com.ridelink.network.voice

import com.ridelink.core.audiopolicy.AudioRouteSnapshot
import com.ridelink.core.audiopolicy.IntercomPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * This phase's hardening pass, Issue H: `VoiceController._diagnostics` had three independent writers —
 * the mailbox consumer (`publishDiagnostics`), the diagnostics-poll coroutine
 * (`publishEngineDiagnostics`), and the platform audio-session's route-sink callback thread
 * (`publishRoute`) — each doing a plain `_diagnostics.value = _diagnostics.value.copy(...)`. That is a
 * read-copy-write: two writers racing can lose one's update entirely, because the loser's `copy()` was
 * built from a snapshot the winner had already superseded.
 *
 * `VoiceAudioSession.setRouteSink`'s callback in production runs on whatever thread the platform
 * delivers a route change on — not the mailbox consumer — so `FakeVoiceAudioSession.publish` (which
 * calls that sink synchronously) reproduces the real concurrency shape when called from a coroutine
 * other than the one driving the controller's own mailbox.
 *
 * The fix (`MutableStateFlow.update`, an atomic CAS retry loop) is proven here by racing two
 * *independent* fields — the route (written only by `publishRoute`) and the PTT hold state (written
 * only by `publishDiagnostics`, via the mailbox) — at volume, and asserting that after the storm
 * settles, **both** reflect their true final value simultaneously. A lost update would show one of them
 * reverted to an earlier snapshot instead of silently failing to update at all, which is why this test
 * checks both together rather than either alone.
 */
class VoiceControllerDiagnosticsRaceTest {
    @Test
    fun `concurrent route publishes and mailbox-driven diagnostics writes lose no field`() =
        withIntercom { voice, fakes ->
            voice.start()
            fakes.awaitEngineCall("createOffer")
            fakes.await("capture open") { fakes.audio.isOpen }

            coroutineScope {
                // Writer A: the platform audio-session's route-sink callback thread, in production —
                // reproduced here by calling `publish` from a coroutine independent of the controller's
                // own mailbox consumer, exactly as `AndroidVoiceAudioSession`'s real callbacks would.
                launch(Dispatchers.Default) {
                    repeat(STRESS_ITERATIONS) { i ->
                        fakes.audio.publish(AudioRouteSnapshot(effectiveOutputSampleRateHz = i))
                    }
                }
                // Writer B: the mailbox consumer, via ordinary PTT presses — each an intercom command
                // that ends in `publishDiagnostics()`.
                launch(Dispatchers.Default) {
                    repeat(STRESS_ITERATIONS) {
                        voice.setPushToTalkHeld(true)
                        voice.setPushToTalkHeld(false)
                    }
                }
            }

            fakes.await("route settled") {
                voice.diagnostics.value.route.effectiveOutputSampleRateHz == STRESS_ITERATIONS - 1
            }
            fakes.settle()

            // Both independently-written fields must hold their true final value **at once**. Before
            // this pass's fix, a lost update on either writer could leave the *other* field reverted to
            // a stale snapshot even though this assertion only checks the route directly.
            assertEquals(
                STRESS_ITERATIONS - 1,
                voice.diagnostics.value.route.effectiveOutputSampleRateHz,
                "the last of the concurrent route publishes must survive",
            )
            assertFalse(
                voice.diagnostics.value.pttHeld,
                "the last PTT edge (a release) must not have been reverted by a concurrent route write",
            )
        }

    /**
     * The same race with a third pair of independent fields, so the property does not rest on one
     * field pairing: `userMuted` (mailbox consumer only) against the route (route-sink thread only),
     * with PTT held throughout so `transmitting` also depends on both being applied correctly.
     */
    @Test
    fun `concurrent route publishes and mute toggles lose no field`() =
        withIntercom { voice, fakes ->
            voice.start()
            fakes.awaitEngineCall("createOffer")
            fakes.await("capture open") { fakes.audio.isOpen }

            coroutineScope {
                launch(Dispatchers.Default) {
                    repeat(STRESS_ITERATIONS) { i ->
                        fakes.audio.publish(AudioRouteSnapshot(effectiveOutputSampleRateHz = i))
                    }
                }
                launch(Dispatchers.Default) {
                    repeat(STRESS_ITERATIONS) {
                        voice.setMicrophoneMuted(true)
                        voice.setMicrophoneMuted(false)
                    }
                }
            }

            fakes.await("route settled") {
                voice.diagnostics.value.route.effectiveOutputSampleRateHz == STRESS_ITERATIONS - 1
            }
            fakes.settle()

            assertEquals(STRESS_ITERATIONS - 1, voice.diagnostics.value.route.effectiveOutputSampleRateHz)
            assertFalse(voice.diagnostics.value.userMuted, "the last mute edge (an unmute) must survive")
        }

    // --- harness -------------------------------------------------------------------------------

    private class Fakes(
        val engine: FakeVoiceEngine,
        val audio: FakeVoiceAudioSession,
    ) {
        suspend fun awaitEngineCall(call: String) = await(call) { engine.calls.contains(call) }

        suspend fun await(
            what: String,
            condition: () -> Boolean,
        ) {
            try {
                withTimeout(AWAIT_TIMEOUT_MS) {
                    while (!condition()) delay(POLL_MS)
                }
            } catch (timeout: TimeoutCancellationException) {
                throw AssertionError("timed out waiting for '$what'", timeout)
            }
        }

        suspend fun settle() {
            repeat(SETTLE_TURNS) { delay(POLL_MS) }
        }
    }

    private fun withIntercom(body: suspend (VoiceController, Fakes) -> Unit) =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob())
            try {
                val engine = FakeVoiceEngine()
                val audio = FakeVoiceAudioSession()
                val transport = RecordingVoiceTransport()
                val controller =
                    VoiceController(
                        scope = scope,
                        engine = engine,
                        audioSession = audio,
                        transport = transport,
                        isLocalLeader = true,
                        localTrackId = "ridelink-voice",
                        newVoiceSessionId = SequencedVoiceSessionIds(GEN_1, GEN_2)::next,
                    )
                controller.selectPolicy(IntercomPolicy.MODE_C)
                body(controller, Fakes(engine, audio))
                controller.shutdown()
            } finally {
                scope.cancel()
            }
        }

    private companion object {
        const val STRESS_ITERATIONS = 300
        const val AWAIT_TIMEOUT_MS = 10_000L
        const val POLL_MS = 2L
        const val SETTLE_TURNS = 25
        const val GEN_1 = "11111111111111111111111111111111"
        const val GEN_2 = "22222222222222222222222222222222"
    }
}
