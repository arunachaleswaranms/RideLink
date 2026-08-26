package com.ridelink.network.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.ridelink.core.model.DiscoveredPeer
import com.ridelink.core.model.Platform
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.security.SecureRandom

private const val SERVICE_TYPE = "_ridelink._tcp"
private const val TXT_PROTOCOL_VERSION = "1"
private const val DISCOVERY_HANDLE_BYTES = 16
private const val BYTE_HEX_WIDTH = 2
private const val BYTE_MASK = 0xFF

/**
 * ARCHITECTURE §4.1: 16 CSPRNG bytes as 32 lowercase hex characters. Never persisted, never
 * derived from `peer_id` or the identity key. Regenerate per advertising session and rotate at
 * least every 15 minutes while advertising continues (not yet wired up here — Phase 1a scaffolds
 * generation only; the rotation timer lands with the full ride-session lifecycle).
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
    ) : AdvertiseState()

    data class Failed(
        val errorCode: Int,
    ) : AdvertiseState()

    object Stopped : AdvertiseState()
}

/**
 * Android `NsdManager`-backed implementation of PROTOCOL §4.1 / ARCHITECTURE §4.1 discovery.
 *
 * The TXT record carries **exactly** `{v, dh, plat}` (CLAUDE.md privacy rules) — no `peer_id`, no
 * SPKI or certificate hash or prefix, no token, no library size, no device name. Known-peer
 * recognition happens after the TLS handshake (Phase 1b), never here.
 */
class NsdDiscoveryController(
    context: Context,
) {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    /** Advertises this device on the LAN. The flow stays open until cancelled; unregisters on close. */
    fun advertise(
        localServiceName: String,
        port: Int,
    ): Flow<AdvertiseState> =
        callbackFlow {
            val serviceInfo =
                NsdServiceInfo().apply {
                    serviceName = localServiceName
                    serviceType = SERVICE_TYPE
                    setPort(port)
                    setAttribute("v", TXT_PROTOCOL_VERSION)
                    setAttribute("dh", DiscoveryHandle.generate())
                    setAttribute("plat", "android")
                }

            trySendBlocking(AdvertiseState.Starting)

            val listener =
                object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(registeredInfo: NsdServiceInfo) {
                        trySendBlocking(AdvertiseState.Advertising(registeredInfo.serviceName, port))
                    }

                    override fun onRegistrationFailed(
                        serviceInfo: NsdServiceInfo,
                        errorCode: Int,
                    ) {
                        trySendBlocking(AdvertiseState.Failed(errorCode))
                    }

                    override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                        trySendBlocking(AdvertiseState.Stopped)
                    }

                    override fun onUnregistrationFailed(
                        serviceInfo: NsdServiceInfo,
                        errorCode: Int,
                    ) {
                        trySendBlocking(AdvertiseState.Failed(errorCode))
                    }
                }

            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)

            awaitClose { nsdManager.unregisterService(listener) }
        }

    /** Browses for peers. Resolves each discovery hit so [DiscoveredPeer] carries host/port and TXT fields. */
    fun browse(): Flow<DiscoveredPeer> =
        callbackFlow {
            val resolveListener =
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(
                        serviceInfo: NsdServiceInfo,
                        errorCode: Int,
                    ) {
                        // A resolve failure just means this hit is dropped, not that browsing stops.
                    }

                    @Suppress("ReturnCount")
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val txt = serviceInfo.attributes
                        val version = txt["v"]?.toString(Charsets.UTF_8)?.toIntOrNull() ?: return
                        val discoveryHandle = txt["dh"]?.toString(Charsets.UTF_8) ?: return
                        val platform =
                            when (txt["plat"]?.toString(Charsets.UTF_8)) {
                                "android" -> Platform.ANDROID
                                "ios" -> Platform.IOS
                                else -> null
                            }
                        val host = serviceInfo.host?.hostAddress ?: return
                        trySendBlocking(
                            DiscoveredPeer(
                                discoveryHandle = discoveryHandle,
                                protocolMajorVersion = version,
                                platform = platform,
                                host = host,
                                port = serviceInfo.port,
                            ),
                        )
                    }
                }

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
                        nsdManager.resolveService(serviceInfo, resolveListener)
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                }

            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

            awaitClose { nsdManager.stopServiceDiscovery(discoveryListener) }
        }
}
