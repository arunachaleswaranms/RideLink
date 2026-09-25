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
 * left a `microphone` service running with nothing captured. These pin the property the fix rests
 * on: the release and the stop that follows it belong to the process scope, not the caller's.
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
    fun `a timed-out release never releases the service`() {
        owner.requestStop()
        release.complete(StopReleaseResult.TimedOut)
        processScope.runCurrent()
        assertEquals(0, serviceReleases, "a stalled release is not proof the microphone is safe to reclaim")
    }
}
