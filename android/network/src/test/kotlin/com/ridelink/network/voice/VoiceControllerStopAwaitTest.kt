package com.ridelink.network.voice

import com.ridelink.core.audiopolicy.IntercomPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `VoiceController.stopAndAwaitRelease()` — the completion-aware stop this phase's hardening pass adds
 * (Issue F). `stop()` alone only *queues* `StopRequested`; the Android microphone foreground service
 * must not be stopped until `engine.release()`/`audioSession.close()` have actually completed
 * (ARCHITECTURE §6.4), which a fire-and-forget `stop()` followed immediately by
 * `RideForegroundService.stop()` could not guarantee.
 *
 * `MainActivity`/`RideForegroundService`/`AppContainer` are Android framework types this environment
 * cannot exercise (docs/STATUS.md §4 problems 15/16), so this proves the property one layer down, at
 * the object those call sites now await: that `stopAndAwaitRelease()` does not return until release is
 * real, that it is a no-op when there is nothing to release, and that it is safe to call repeatedly.
 */
class VoiceControllerStopAwaitTest {
    /**
     * The five-step scenario section 10 of this phase's brief asks for directly: a stop command
     * begins, the "service stop" step has not run, the fake media release happens, the fake
     * `audioSession.close()` finishes, and only then is the service-stop step allowed to run.
     */
    @Test
    fun `stopAndAwaitRelease does not return until the fake audio session has actually closed`() =
        withIntercom { voice, fakes ->
            voice.start()
            fakes.awaitEngineCall("createOffer")
            fakes.await("capture open") { fakes.audio.isOpen }

            val closeGate = CompletableDeferred<Unit>()
            fakes.audio.closeGate = closeGate
            var fgsStopped = false

            val stopJob =
                async {
                    val result = voice.stopAndAwaitRelease()
                    assertEquals(StopReleaseResult.Released, result, "release completed, so the result must say so")
                    // Stands in for `RideForegroundService.stop(context)` — the step ARCHITECTURE §6.4
                    // says must never run before capture is actually released.
                    fgsStopped = true
                }

            // The stop command has begun (engine.release() has already run — release() has no gate and
            // completes immediately per ADR-021 §4's ordering: media factory and capture device first)
            // but the audio session's own close() is deliberately held open.
            fakes.awaitEngineCall("release")
            fakes.settle()
            assertFalse(fgsStopped, "the FGS-stop step must not run before audioSession.close() completes")
            assertEquals(0, fakes.audio.closeCaptureCount, "close() has not finished yet")

            closeGate.complete(Unit)
            withTimeout(AWAIT_TIMEOUT_MS) { stopJob.await() }

            assertTrue(fgsStopped, "the FGS-stop step must run once release is real")
            assertEquals(1, fakes.audio.closeCaptureCount)
        }

    /** No capture was ever opened, so there is nothing to release — the await must not hang or time out. */
    @Test
    fun `stopAndAwaitRelease is an immediate no-op when capture was never opened`() =
        withIntercom { voice, fakes ->
            val result = withTimeout(AWAIT_TIMEOUT_MS) { voice.stopAndAwaitRelease() }
            assertEquals(StopReleaseResult.AlreadyReleased, result, "there was nothing to release")
            assertEquals(0, fakes.audio.openCaptureCount)
            assertEquals(0, fakes.audio.closeCaptureCount)
        }

    /** Repeated End is idempotent: several concurrent awaiters all resolve from the same completion. */
    @Test
    fun `several concurrent stopAndAwaitRelease callers all complete`() =
        withIntercom { voice, fakes ->
            voice.start()
            fakes.awaitEngineCall("createOffer")
            fakes.await("capture open") { fakes.audio.isOpen }

            val callers = List(CONCURRENT_STOP_CALLERS) { async { voice.stopAndAwaitRelease() } }
            val results = withTimeout(AWAIT_TIMEOUT_MS) { callers.map { it.await() } }

            assertEquals(1, fakes.audio.closeCaptureCount, "capture is released exactly once, however many callers awaited it")
            // Each caller's own fast-path check (`audioSession.isOpen`) can legitimately land before or
            // after the single real close() actually finishes, so a caller may correctly see either
            // `Released` (it registered before close() settled) or `AlreadyReleased` (close() had
            // already finished by the time it checked) — both are proof of a genuine release. What must
            // never happen is a timeout, which would mean release was never proven at all.
            assertTrue(
                results.all { it == StopReleaseResult.Released || it == StopReleaseResult.AlreadyReleased },
                "every concurrent caller must see a real result, never a timeout: $results",
            )
        }

    /**
     * This phase's final hardening pass (Issue 2): a stall in `audioSession.close()` must surface as
     * [StopReleaseResult.TimedOut], **never** [StopReleaseResult.Released] — a caller that stopped the
     * Android foreground service on an unproven release would be doing exactly what problem 32's
     * fix exists to prevent. The waiter must also not leak: [VoiceController.pendingStopCompletionCount]
     * returns to zero once the call has timed out (Issue 10), and a *later* real completion of the
     * stalled `close()` must not corrupt a subsequent, independent stop call.
     */
    @Test
    fun `stopAndAwaitRelease times out rather than reporting success, and does not leak the waiter`() =
        withIntercom(stopAwaitTimeoutMs = SHORT_TIMEOUT_MS) { voice, fakes ->
            voice.start()
            fakes.awaitEngineCall("createOffer")
            fakes.await("capture open") { fakes.audio.isOpen }

            val neverCompletes = CompletableDeferred<Unit>()
            fakes.audio.closeGate = neverCompletes

            val result = withTimeout(AWAIT_TIMEOUT_MS) { voice.stopAndAwaitRelease() }
            assertEquals(StopReleaseResult.TimedOut, result, "a stalled close() must never be reported as Released")
            assertEquals(0, voice.pendingStopCompletionCount, "the timed-out waiter must not leak")

            // A later, independent stop call must still resolve correctly and must not be corrupted by
            // the still-stalled first attempt.
            neverCompletes.complete(Unit)
            val second = withTimeout(AWAIT_TIMEOUT_MS) { voice.stopAndAwaitRelease() }
            assertEquals(StopReleaseResult.AlreadyReleased, second, "the earlier close() has now actually finished")
            assertEquals(0, voice.pendingStopCompletionCount)
        }

    /** 100 timed-out waiters in a row must not leave anything behind (Issue 10's explicit stress case). */
    @Test
    fun `repeated timeouts never accumulate waiters`() =
        withIntercom(stopAwaitTimeoutMs = SHORT_TIMEOUT_MS) { voice, fakes ->
            voice.start()
            fakes.awaitEngineCall("createOffer")
            fakes.await("capture open") { fakes.audio.isOpen }
            fakes.audio.closeGate = CompletableDeferred() // never completes

            repeat(REPEATED_TIMEOUT_COUNT) {
                val result = withTimeout(AWAIT_TIMEOUT_MS) { voice.stopAndAwaitRelease() }
                assertEquals(StopReleaseResult.TimedOut, result)
            }
            assertEquals(0, voice.pendingStopCompletionCount, "100 timed-out waiters must return the count to zero")
        }

    /** Shutdown during an already-stopped voice session is safe — no hang, no exception. */
    @Test
    fun `shutdown when voice was never started is safe`() =
        withIntercom { voice, _ ->
            withTimeout(AWAIT_TIMEOUT_MS) { voice.shutdown() }
        }

    /**
     * This phase's timeout-ownership hardening pass, the confirmed-but-unfixed concern
     * `docs/STATUS.md` §2r recorded: `shutdown()` must wait for a release already in flight to
     * actually finish, never cancel it out from under itself. Proven the same way
     * `stopAndAwaitRelease does not return until the fake audio session has actually closed` proves it
     * for that call: `shutdown()` must not return while `close()` is legitimately gated, and once the
     * gate opens, `close()` must be observed to have actually run — not aborted mid-flight.
     */
    @Test
    fun `shutdown waits for an in-flight release rather than cancelling it`() =
        withIntercom { voice, fakes ->
            voice.start()
            fakes.awaitEngineCall("createOffer")
            fakes.await("capture open") { fakes.audio.isOpen }

            val closeGate = CompletableDeferred<Unit>()
            fakes.audio.closeGate = closeGate

            val shutdownJob = async { voice.shutdown() }

            fakes.awaitEngineCall("release")
            fakes.settle()
            assertFalse(shutdownJob.isCompleted, "shutdown() must not return while close() is still gated")
            assertEquals(0, fakes.audio.closeCaptureCount, "close() has not finished yet")

            closeGate.complete(Unit)
            withTimeout(AWAIT_TIMEOUT_MS) { shutdownJob.await() }
            assertEquals(1, fakes.audio.closeCaptureCount, "close() must be allowed to run to completion")
            assertEquals(0, voice.pendingStopCompletionCount)
        }

    /**
     * The exact regression this pass fixes: `stopAndAwaitRelease()` gives up on a stalled `close()`
     * (its own short caller-facing window elapsing), and the caller's very next step —
     * `SessionCoordinator.releaseVoiceAndAwait()`'s unconditional `controller.shutdown()` — must not
     * read that timeout as license to cancel the release `stopAndAwaitRelease()` was still waiting on.
     * Before this pass, `shutdown()`'s `consumerJob?.cancel()` did exactly that, aborting `close()`
     * before its post-close intercom-gate update could run.
     */
    @Test
    fun `shutdown called right after stopAndAwaitRelease times out still lets the same release finish`() =
        withIntercom(stopAwaitTimeoutMs = SHORT_TIMEOUT_MS) { voice, fakes ->
            voice.start()
            fakes.awaitEngineCall("createOffer")
            fakes.await("capture open") { fakes.audio.isOpen }

            val closeGate = CompletableDeferred<Unit>()
            fakes.audio.closeGate = closeGate

            val firstResult = withTimeout(AWAIT_TIMEOUT_MS) { voice.stopAndAwaitRelease() }
            assertEquals(StopReleaseResult.TimedOut, firstResult, "the stalled close() must surface as a timeout")

            // Exactly `releaseVoiceAndAwait`'s own next step: shutdown() runs unconditionally,
            // regardless of the result above, while the release it timed out on is still in flight.
            val shutdownJob = async { voice.shutdown() }
            fakes.settle()
            assertFalse(shutdownJob.isCompleted, "shutdown() must keep waiting for the same still-gated release")
            assertEquals(0, fakes.audio.closeCaptureCount, "the pre-fix bug aborted close() at roughly this point")

            closeGate.complete(Unit)
            withTimeout(AWAIT_TIMEOUT_MS) { shutdownJob.await() }
            assertEquals(1, fakes.audio.closeCaptureCount, "close() must have been allowed to finish, not aborted")
            assertEquals(0, voice.pendingStopCompletionCount, "no waiter — timed-out or shutdown's own — may leak")
        }

    /** Repeated/concurrent shutdown is idempotent: no double release, no hang, no crash. */
    @Test
    fun `repeated shutdown calls are idempotent`() =
        withIntercom { voice, fakes ->
            voice.start()
            fakes.awaitEngineCall("createOffer")
            fakes.await("capture open") { fakes.audio.isOpen }

            val shutdowns = List(CONCURRENT_STOP_CALLERS) { async { voice.shutdown() } }
            withTimeout(AWAIT_TIMEOUT_MS) { shutdowns.forEach { it.await() } }

            assertEquals(1, fakes.audio.closeCaptureCount, "capture is released exactly once, however many shutdowns raced")
            assertEquals(0, voice.pendingStopCompletionCount)

            // A shutdown well after the first has already torn everything down must still be a safe no-op.
            withTimeout(AWAIT_TIMEOUT_MS) { voice.shutdown() }
            assertEquals(1, fakes.audio.closeCaptureCount, "a later shutdown must never re-run the release")
        }

    /** A link loss keeps capture open — `stopAndAwaitRelease` is not what a blip goes through. */
    @Test
    fun `a link loss does not resolve a pending stopAndAwaitRelease call`() =
        withIntercom { voice, fakes ->
            voice.start()
            fakes.awaitEngineCall("createOffer")
            fakes.await("capture open") { fakes.audio.isOpen }

            voice.onControlLinkLost()
            fakes.awaitEngineCall("stop")
            fakes.settle()
            assertEquals(0, fakes.audio.closeCaptureCount, "a link blip must never release capture")

            // A genuine stop, afterward, still resolves normally and releases capture.
            withTimeout(AWAIT_TIMEOUT_MS) { voice.stopAndAwaitRelease() }
            assertEquals(1, fakes.audio.closeCaptureCount)
        }

    // --- harness (mirrors `VoiceControllerIntercomTest`'s, kept local and minimal) -----------------

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
                throw AssertionError(
                    "timed out waiting for '$what' — engine calls: ${engine.calls}, audio calls: ${audio.calls}",
                    timeout,
                )
            }
        }

        suspend fun settle() {
            repeat(SETTLE_TURNS) { delay(POLL_MS) }
        }
    }

    private fun withIntercom(
        stopAwaitTimeoutMs: Long = AWAIT_TIMEOUT_MS,
        body: suspend CoroutineScope.(VoiceController, Fakes) -> Unit,
    ) = runBlocking {
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
            controller.stopAwaitTimeoutMs = stopAwaitTimeoutMs
            controller.selectPolicy(IntercomPolicy.MODE_A)
            body(controller, Fakes(engine, audio))
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val SHORT_TIMEOUT_MS = 20L
        const val POLL_MS = 2L
        const val SETTLE_TURNS = 25
        const val CONCURRENT_STOP_CALLERS = 5
        const val REPEATED_TIMEOUT_COUNT = 100
        const val GEN_1 = "11111111111111111111111111111111"
        const val GEN_2 = "22222222222222222222222222222222"
    }
}
