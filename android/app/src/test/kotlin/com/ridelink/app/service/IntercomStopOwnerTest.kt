package com.ridelink.app.service

import com.ridelink.network.voice.StopReleaseResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The in-app End button used to await capture release on `MainActivity.lifecycleScope`, so an
 * Activity recreated or destroyed inside the release window lost the foreground-service stop and
 * left a `microphone` service running with nothing captured. These pin the owner's side of the fix:
 * [IntercomStopOwner.requestStop] does not make its caller wait, so the release and the service stop
 * after it run on the process scope and survive the caller being cancelled. (That `MainActivity`
 * calls the owner rather than awaiting itself is a wiring fact these cannot see.) They also pin that
 * a stop belongs to the intercom it was requested against.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IntercomStopOwnerTest {
    private val dispatcher = StandardTestDispatcher()
    private val processScope = TestScope(dispatcher)
    private val release = CompletableDeferred<StopReleaseResult>()
    private var serviceReleases = 0
    private val owner =
        IntercomStopOwner(
            scope = processScope,
            endIntercomAndAwaitRelease = { release.await() },
            releaseForegroundService = { serviceReleases++ },
        )

    @Test
    fun `the caller being destroyed while release is awaited still releases the service`() {
        // Stands in for an Activity's lifecycleScope: it asks for the stop, then is cancelled
        // (rotation, finish) before capture release completes.
        val activityScope = CoroutineScope(Job() + dispatcher)
        activityScope.launch { owner.requestStop() }
        processScope.runCurrent()

        activityScope.cancel()
        processScope.runCurrent()
        assertEquals(0, serviceReleases, "the service must not be released before capture is")

        release.complete(StopReleaseResult.Released)
        processScope.runCurrent()
        assertEquals(1, serviceReleases)
    }

    @Test
    fun `nothing to release still releases the service exactly once`() {
        owner.requestStop()
        release.complete(StopReleaseResult.AlreadyReleased)
        processScope.runCurrent()
        assertEquals(1, serviceReleases)
    }

    @Test
    fun `a start after the stop was requested keeps the new intercom's service`() {
        owner.requestStop()
        owner.noteStart() // End, then Start again, before the first release has completed
        release.complete(StopReleaseResult.Released)
        processScope.runCurrent()
        assertEquals(0, serviceReleases, "a stale stop must not drop the microphone type from the new intercom")
    }

    @Test
    fun `a stop requested after a restart still releases the service`() {
        owner.noteStart()
        owner.requestStop()
        release.complete(StopReleaseResult.Released)
        processScope.runCurrent()
        assertEquals(1, serviceReleases)
    }

    @Test
    fun `a timed-out release never releases the service`() {
        owner.requestStop()
        release.complete(StopReleaseResult.TimedOut)
        processScope.runCurrent()
        assertEquals(0, serviceReleases, "a stalled release is not proof the microphone is safe to reclaim")
    }
}
