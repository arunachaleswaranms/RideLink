package com.ridelink.app

import android.app.Application
import com.ridelink.app.di.AppContainer
import com.ridelink.core.security.UtcTime
import com.ridelink.network.security.AndroidKeystoreIdentityStore
import com.ridelink.network.security.DeviceIdentity
import com.ridelink.network.security.IdentityResolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the process can offer the user, decided once the device identity has been resolved
 * (ADR-017 Amendment A1).
 */
sealed interface AppStartup {
    class Ready(
        val container: AppContainer,
    ) : AppStartup

    /**
     * The identity key cannot sign a TLS handshake and must be replaced before RideLink can
     * connect (STATUS §4 problem 122). Replacing it is a new SPKI, so it waits for an explicit
     * user decision; nothing is built around the unusable key in the meantime. That intentionally
     * includes local music: there is no [AppContainer], so there is no player, until the user
     * decides. The library and settings on disk are untouched.
     *
     * [incompatibleIdentity] is already redacted (`spki:` + 6 hex), for display only.
     */
    class IdentityReplacementRequired(
        val incompatibleIdentity: String,
        val replacing: Boolean,
    ) : AppStartup

    /**
     * The device identity could not be created or loaded at all. ADR-007 Amendment A1 forbids
     * falling back to anything unencrypted, so the app shows why and does nothing else.
     */
    class Failed(
        val error: Throwable,
    ) : AppStartup
}

class RideLinkApplication : Application() {
    private val identityStore = AndroidKeystoreIdentityStore()

    // Null only before onCreate; every reader runs after it.
    private val startupState = MutableStateFlow<AppStartup?>(null)

    val startup: StateFlow<AppStartup?> = startupState.asStateFlow()

    /**
     * Failure is a first-class outcome, not a crash: a container exists only when the identity is
     * usable. Anything else — an identity awaiting the user's replacement decision, or one that
     * could not be loaded — reads as a failure here, and [startup] says which.
     */
    val container: Result<AppContainer>
        get() =
            when (val startup = checkNotNull(startupState.value) { "AppContainer read before Application.onCreate" }) {
                is AppStartup.Ready -> Result.success(startup.container)
                is AppStartup.IdentityReplacementRequired -> Result.failure(IllegalStateException("device identity replacement required"))
                is AppStartup.Failed -> Result.failure(startup.error)
            }

    override fun onCreate() {
        super.onCreate()
        startupState.value =
            runCatching { identityStore.resolve(wallClockNow()) }.fold(
                onSuccess = { resolution ->
                    when (resolution) {
                        is IdentityResolution.Ready -> containerFor(resolution.identity)
                        is IdentityResolution.ReplacementRequired ->
                            AppStartup.IdentityReplacementRequired(resolution.incompatibleSpki.toString(), replacing = false)
                    }
                },
                onFailure = { AppStartup.Failed(it) },
            )
    }

    /**
     * The user's explicit, confirmed decision to replace an identity that cannot sign a TLS
     * handshake (ADR-017 Amendment A1). Does nothing in any other state, so a stray or repeated
     * call can never replace a working identity — the store refuses that too.
     */
    fun replaceIncompatibleIdentity() {
        val current = startupState.value as? AppStartup.IdentityReplacementRequired ?: return
        if (current.replacing) return
        startupState.value = AppStartup.IdentityReplacementRequired(current.incompatibleIdentity, replacing = true)
        MainScope().launch {
            val replaced = withContext(Dispatchers.IO) { runCatching { identityStore.replaceIncompatibleIdentity(wallClockNow()) } }
            startupState.value = replaced.fold(onSuccess = ::containerFor, onFailure = { AppStartup.Failed(it) })
        }
    }

    private fun containerFor(identity: DeviceIdentity): AppStartup =
        runCatching { AppContainer(this, identity) }.fold(onSuccess = { AppStartup.Ready(it) }, onFailure = { AppStartup.Failed(it) })

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L

        /**
         * The wall-clock read the identity needs: certificate validity is defined in wall-clock
         * terms by RFC 5280 and has no monotonic alternative — see [UtcTime] for why this is the
         * permitted exception to CLAUDE.md's monotonic-clocks rule.
         */
        fun wallClockNow(): UtcTime = UtcTime(System.currentTimeMillis() / MILLIS_PER_SECOND)
    }
}
