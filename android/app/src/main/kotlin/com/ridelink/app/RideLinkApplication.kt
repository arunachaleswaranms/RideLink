package com.ridelink.app

import android.app.Application
import com.ridelink.app.di.AppContainer

class RideLinkApplication : Application() {
    // `Result` is a value class, and Kotlin forbids `lateinit` on one — hence a nullable backing
    // field with a non-null accessor, which keeps the same "set exactly once in onCreate" shape.
    private var containerOrNull: Result<AppContainer>? = null

    /**
     * Failure is a first-class outcome, not a crash. [AppContainer] creates or loads the device
     * identity in Android Keystore; if that fails there is no certificate, no pin and no channel
     * binding, and ADR-007 Amendment A1 forbids falling back to anything unencrypted. So the app
     * starts, shows what went wrong, and does nothing else — which is more useful to whoever has
     * to diagnose it than an `onCreate` crash the user cannot read.
     */
    val container: Result<AppContainer>
        get() = checkNotNull(containerOrNull) { "AppContainer read before Application.onCreate" }

    override fun onCreate() {
        super.onCreate()
        containerOrNull = runCatching { AppContainer(this) }
    }
}
