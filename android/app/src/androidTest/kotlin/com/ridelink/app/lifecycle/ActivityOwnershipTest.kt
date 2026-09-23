package com.ridelink.app.lifecycle

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ridelink.app.MainActivity
import com.ridelink.app.RideLinkApplication
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

/** Runs against the real Application container and Activity, including configuration recreation. */
@RunWith(AndroidJUnit4::class)
class ActivityOwnershipTest {
    @Test
    fun recreationAndForegroundRestorationKeepOneAuthorityOwner() {
        val app = ApplicationProvider.getApplicationContext<RideLinkApplication>()
        val owner = app.container.getOrThrow()
        val session = owner.sessionCoordinator
        val music = owner.musicCoordinator
        val sync = owner.syncPlaybackCoordinator
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            repeat(20) {
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.recreate()
                scenario.onActivity { activity ->
                    val current = (activity.application as RideLinkApplication).container.getOrThrow()
                    assertSame(owner, current)
                    assertSame(session, current.sessionCoordinator)
                    assertSame(music, current.musicCoordinator)
                    assertSame(sync, current.syncPlaybackCoordinator)
                    assertFalse(session.voiceDiagnostics.value.pttHeld)
                }
            }
        }
    }
}
