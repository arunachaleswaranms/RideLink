package com.ridelink.network.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import com.ridelink.core.model.DiscoveredPeer
import com.ridelink.core.model.Platform
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.concurrent.Executor

private const val SERVICE_TYPE = "_ridelink._tcp"
private const val TXT_PROTOCOL_VERSION = "1"
private const val DISCOVERY_HANDLE_BYTES = 16
private const val BYTE_HEX_WIDTH = 2
private const val BYTE_MASK = 0xFF
private const val API_LEVEL_SERVICE_INFO_CALLBACK = 34 // Build.VERSION_CODES.UPSIDE_DOWN_CAKE

/**
 * ARCHITECTURE §4.1: 16 CSPRNG bytes as 32 lowercase hex characters. Never persisted, never
 * derived from `peer_id` or the identity key. Rotated at least every 15 minutes while advertising
 * continues ([DiscoveryHandleRotationPolicy]).
 */
object DiscoveryHandle {
    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(DISCOVERY_HANDLE_BYTES)
        random.nextBytes(bytes)
        return bytes.joinToString("") { byte -> "%0${BYTE_HEX_WIDTH}x".format(byte.toInt() and BYTE_MASK) }
    }
}

sealed class AdvertiseState {
    object Starting : AdvertiseState()

    data class Advertising(
        val serviceName: String,
        val port: Int,
        val discoveryHandle: String,
    ) : AdvertiseState()

    data class Failed(
        val errorCode: Int,
    ) : AdvertiseState()

    object Stopped : AdvertiseState()
}

/** Platform-neutral discovery lifecycle (this session's brief §4D) — Found/Updated/Lost, never a bare "found". */
sealed class DiscoveryEvent {
    data class Found(
        val peer: DiscoveredPeer,
    ) : DiscoveryEvent()

    data class Updated(
        val peer: DiscoveredPeer,
    ) : DiscoveryEvent()

    data class Lost(
        val discoveryHandle: String,
    ) : DiscoveryEvent()
}

/**
 * The complete, exact TXT record content (ARCHITECTURE §4.1 / CLAUDE.md privacy rules): `{v, dh,
 * plat}` and nothing else, ever. This is the single function that builds the attribute set both
 * [NsdDiscoveryController.advertise] and [DiscoveryPrivacyTest] use, so a future accidental
 * addition (`peer_id`, a device name, a library count) fails a laptop unit test instead of
 * shipping. `LinkedHashMap`-backed iteration order (`v`, `dh`, `plat`) is deterministic but not
 * load-bearing — the test asserts the key *set*, not an order.
 */
fun buildTxtRecord(discoveryHandle: String): Map<String, String> =
    linkedMapOf(
        "v" to TXT_PROTOCOL_VERSION,
        "dh" to discoveryHandle,
        "plat" to "android",
    )

private const val INSTANCE_NAME_HANDLE_PREFIX_LENGTH = 8

/**
 * Neutral, ephemeral Bonjour/mDNS **instance name** — deliberately not the device model,
 * manufacturer, a user-chosen name, username, `peer_id`, SPKI or any other durable identifier
 * (this session's brief §6). Derived from the same rotating [DiscoveryHandle] the TXT record's
 * `dh` carries, truncated to 8 hex characters — long enough to disambiguate concurrent
 * advertisements on one LAN, short enough that the instance name itself isn't just a second copy
 * of the full 32-character handle. Rotates exactly when `dh` rotates, by construction: this
 * function is pure and carries no state of its own, so [DiscoveryPrivacyTest] can assert its
 * output directly with no `NsdManager` involved.
 */
fun instanceServiceName(discoveryHandle: String): String = "RideLink-${discoveryHandle.take(INSTANCE_NAME_HANDLE_PREFIX_LENGTH)}"

/**
 * Android `NsdManager`-backed implementation of PROTOCOL §4.1 / ARCHITECTURE §4.1 discovery.
 *
 * The TXT record carries **exactly** `{v, dh, plat}` (CLAUDE.md privacy rules) — no `peer_id`, no
 * SPKI or certificate hash or prefix, no token, no library size, no device name. Known-peer
 * recognition happens after the TLS handshake (Phase 1b), never here.
 *
 * Resolution is API-tiered (this session's brief §4C): API 34+ uses
 * [NsdManager.registerServiceInfoCallback], which tracks a service's live updates (giving
 * Found/Updated/Lost for free from one registration and avoiding the deprecated one-shot
 * `resolveService`); API 31-33 falls back to legacy `resolveService`, and — the bug this replaces
 * — every call gets a **fresh** [NsdManager.ResolveListener] instance, never a reused one.
 */
class NsdDiscoveryController(
    context: Context,
) {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val mainExecutor: Executor = context.applicationContext.mainExecutor

    // Shared between advertise() and browse() (two independent callbackFlows) so a resolution
    // arriving on the browse side can be checked against whatever the advertise side currently
    // considers self — including the just-rotated-away previous handle (this session's brief §8).
    private val selfHandles = SelfDiscoveryHandles()

    /** The dh this controller is currently advertising, so [browse] can filter out self-discovery. */
    val activeDiscoveryHandle: String? get() = selfHandles.currentHandle

    /**
     * Advertises this device on the LAN. The flow stays open until cancelled; unregisters on
     * close. The discovery handle rotates immediately on start and then every [rotationIntervalMs]
     * (default: [DiscoveryHandleRotationPolicy.ROTATION_INTERVAL_MS]) for as long as advertising
     * continues — re-registering is how a TXT-record update is achieved on this platform, since
     * `NsdManager` has no in-place TXT update API. Rotation never touches [port] or any live
     * control-plane socket: discovery and control are wired as two independent systems for
     * exactly this reason.
     */
    fun advertise(
        port: Int,
        rotationIntervalMs: Long = DiscoveryHandleRotationPolicy.ROTATION_INTERVAL_MS,
    ): Flow<AdvertiseState> =
        callbackFlow {
            var registeredListener: NsdManager.RegistrationListener? = null

            fun register(dh: String) {
                // Synchronous: the moment a new dh is chosen it is "self", and (per
                // SelfDiscoveryHandles) so is whatever was self a moment ago, until that old
                // registration is confirmed gone below.
                selfHandles.rotate(dh)
                val serviceInfo =
                    NsdServiceInfo().apply {
                        serviceName = instanceServiceName(dh)
                        serviceType = SERVICE_TYPE
                        setPort(port)
                        buildTxtRecord(dh).forEach { (key, value) -> setAttribute(key, value) }
                    }
                val listener =
                    object : NsdManager.RegistrationListener {
                        override fun onServiceRegistered(registeredInfo: NsdServiceInfo) {
                            trySendBlocking(AdvertiseState.Advertising(registeredInfo.serviceName, port, dh))
                        }

                        override fun onRegistrationFailed(
                            serviceInfo: NsdServiceInfo,
                            errorCode: Int,
                        ) {
                            trySendBlocking(AdvertiseState.Failed(errorCode))
                        }

                        // Confirms the *previous* registration (this listener's own dh, now
                        // superseded) is actually gone from the network — the real signal that
                        // closes the self-discovery transition window, not a fixed timer.
                        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                            selfHandles.clearPrevious()
                        }

                        override fun onUnregistrationFailed(
                            serviceInfo: NsdServiceInfo,
                            errorCode: Int,
                        ) {
                            // Best-effort: an OS-reported failure to unregister still means we
                            // must stop waiting for confirmation, or "previous" would never clear.
                            selfHandles.clearPrevious()
                            trySendBlocking(AdvertiseState.Failed(errorCode))
                        }
                    }
                registeredListener = listener
                nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            }

            trySendBlocking(AdvertiseState.Starting)
            register(DiscoveryHandle.generate())

            val rotationJob =
                launch {
                    while (true) {
                        delay(rotationIntervalMs)
                        registeredListener?.let { runCatching { nsdManager.unregisterService(it) } }
                        register(DiscoveryHandle.generate())
                    }
                }

            awaitClose {
                rotationJob.cancel()
                registeredListener?.let { runCatching { nsdManager.unregisterService(it) } }
                selfHandles.reset()
            }
        }

    /**
     * Browses for peers, emitting the full lifecycle: [DiscoveryEvent.Found] the first time a
     * service resolves, [DiscoveryEvent.Updated] for a subsequent resolution of the same
     * mDNS service name (route/TXT change), and [DiscoveryEvent.Lost] when the browse layer
     * reports it gone. Self-advertisements (matching [activeDiscoveryHandle]) are dropped
     * silently — RideLink may observe its own advertisement, and the discovery handle, not the
     * device name/IP/peer_id, is the only correct way to recognise that (this session's brief §4E).
     */
    @Suppress("LongMethod")
    fun browse(): Flow<DiscoveryEvent> =
        callbackFlow {
            // mDNS service *names*, not discovery handles, are what onServiceLost gives back
            // unresolved — this map is what lets Lost still carry the right dh.
            val tracker = DiscoveryLifecycleTracker(isSelf = { selfHandles.isSelf(it.discoveryHandle) })
            val trackerLock = Any() // NsdManager callbacks can arrive on arbitrary threads

            // API 34+ only (resolveLegacy never populates it): tracks every live
            // ServiceInfoCallback registration by service name so it can be unregistered on
            // service loss, browse stop or controller teardown — never left to leak (§5).
            val callbackRegistry = ServiceInfoCallbackRegistry<NsdManager.ServiceInfoCallback>()

            val discoveryListener =
                object : NsdManager.DiscoveryListener {
                    override fun onStartDiscoveryFailed(
                        serviceType: String,
                        errorCode: Int,
                    ) {
                        close(IllegalStateException("NSD discovery failed to start: error $errorCode"))
                    }

                    override fun onStopDiscoveryFailed(
                        serviceType: String,
                        errorCode: Int,
                    ) {
                        close(IllegalStateException("NSD discovery failed to stop: error $errorCode"))
                    }

                    override fun onDiscoveryStarted(serviceType: String) = Unit

                    override fun onDiscoveryStopped(serviceType: String) = Unit

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        resolveOne(serviceInfo, callbackRegistry) { peer ->
                            val event = synchronized(trackerLock) { tracker.onResolved(serviceInfo.serviceName, peer) }
                            event?.let { trySendBlocking(it) }
                        }
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                        if (Build.VERSION.SDK_INT >= API_LEVEL_SERVICE_INFO_CALLBACK) {
                            unregisterCallback(callbackRegistry.remove(serviceInfo.serviceName))
                        }
                        val event = synchronized(trackerLock) { tracker.onServiceLost(serviceInfo.serviceName) }
                        event?.let { trySendBlocking(it) }
                    }
                }

            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

            awaitClose {
                nsdManager.stopServiceDiscovery(discoveryListener)
                if (Build.VERSION.SDK_INT >= API_LEVEL_SERVICE_INFO_CALLBACK) {
                    callbackRegistry.removeAll().forEach(::unregisterCallback)
                }
            }
        }

    @RequiresApi(API_LEVEL_SERVICE_INFO_CALLBACK)
    private fun unregisterCallback(callback: NsdManager.ServiceInfoCallback?) {
        callback ?: return
        runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
    }

    /** API-tiered resolution (this session's brief §4C). [onResolved] may fire more than once per call on API 34+. */
    private fun resolveOne(
        serviceInfo: NsdServiceInfo,
        callbackRegistry: ServiceInfoCallbackRegistry<NsdManager.ServiceInfoCallback>,
        onResolved: (DiscoveredPeer) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT >= API_LEVEL_SERVICE_INFO_CALLBACK) {
            resolveModern(serviceInfo, callbackRegistry, onResolved)
        } else {
            resolveLegacy(serviceInfo, onResolved)
        }
    }

    @RequiresApi(API_LEVEL_SERVICE_INFO_CALLBACK)
    private fun resolveModern(
        serviceInfo: NsdServiceInfo,
        callbackRegistry: ServiceInfoCallbackRegistry<NsdManager.ServiceInfoCallback>,
        onResolved: (DiscoveredPeer) -> Unit,
    ) {
        val serviceName = serviceInfo.serviceName
        val callback =
            object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    callbackRegistry.recordFailed(serviceName)
                }

                override fun onServiceUpdated(updated: NsdServiceInfo) {
                    parsePeer(updated)?.let(onResolved)
                }

                override fun onServiceLost() = Unit // browse-level onServiceLost (above) is the source of truth

                override fun onServiceInfoCallbackUnregistered() = Unit
            }
        // A duplicate onServiceFound with no intervening Lost (rare, but not forbidden by the
        // API) must not leak the previous registration for this exact service name.
        unregisterCallback(callbackRegistry.record(serviceName, callback))
        runCatching { nsdManager.registerServiceInfoCallback(serviceInfo, mainExecutor, callback) }
            .onFailure { callbackRegistry.recordFailed(serviceName) }
    }

    private fun resolveLegacy(
        serviceInfo: NsdServiceInfo,
        onResolved: (DiscoveredPeer) -> Unit,
    ) {
        // A fresh ResolveListener per call — never reused across concurrent resolutions
        // (deprecated on API 34+, but that tier never reaches this function).
        val listener =
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(
                    serviceInfo: NsdServiceInfo,
                    errorCode: Int,
                ) = Unit // this hit is dropped, browsing continues

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    parsePeer(serviceInfo)?.let(onResolved)
                }
            }
        @Suppress("DEPRECATION")
        nsdManager.resolveService(serviceInfo, listener)
    }

    @Suppress("DEPRECATION", "ReturnCount") // .host works down to minSdk 31; .hostAddresses (API 34+) isn't worth a second code path here
    private fun parsePeer(serviceInfo: NsdServiceInfo): DiscoveredPeer? {
        val txt = serviceInfo.attributes
        val version = txt["v"]?.toString(Charsets.UTF_8)?.toIntOrNull() ?: return null
        val discoveryHandle = txt["dh"]?.toString(Charsets.UTF_8) ?: return null
        val platform =
            when (txt["plat"]?.toString(Charsets.UTF_8)) {
                "android" -> Platform.ANDROID
                "ios" -> Platform.IOS
                else -> null
            }
        val host = serviceInfo.host?.hostAddress ?: return null
        return DiscoveredPeer(
            discoveryHandle = discoveryHandle,
            protocolMajorVersion = version,
            platform = platform,
            host = host,
            port = serviceInfo.port,
        )
    }
}
