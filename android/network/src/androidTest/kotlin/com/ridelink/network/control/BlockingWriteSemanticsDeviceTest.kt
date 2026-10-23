package com.ridelink.network.control

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ridelink.core.protocol.Envelope
import com.ridelink.core.protocol.ProtocolVersion
import com.ridelink.core.security.UtcTime
import com.ridelink.network.security.AndroidKeystoreIdentityStore
import com.ridelink.network.security.DeviceIdentity
import com.ridelink.network.security.IdentityResolution
import com.ridelink.network.security.TlsControlChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * STATUS §4 problem 137 — `BlockingWriteSemanticsTest`'s measurement, repeated on Android's own TLS
 * stack: the platform's Conscrypt, the production [TlsControlChannel] and real Keystore identities.
 * The JVM test runs against `conscrypt-openjdk-uber`, whose socket class need not be the one a phone
 * uses, so what `close()` does to a writer blocked in the kernel is measured here rather than assumed.
 *
 * Asserted on every platform: a lock waiter honours its timeout, a writer inside the blocking write
 * ignores cancellation, and [ControlSocket.abort] releases that writer without itself waiting. What a
 * plain `close()` does is **recorded, not asserted** — it is the platform-dependent fact this test
 * exists to report (logcat tag [TAG]).
 *
 * Every alias is disposable and unique to the run, as in `KeystoreTlsIdentityTest`. Nothing here logs
 * key material, an exporter output or a SAS.
 */
@RunWith(AndroidJUnit4::class)
class BlockingWriteSemanticsDeviceTest {
    private val run = UUID.randomUUID().toString().take(RUN_ID_CHARS)
    private val createdAliases = mutableListOf<String>()
    private val now = UtcTime(System.currentTimeMillis() / MILLIS_PER_SECOND)

    @After
    fun deleteEveryAliasThisRunCreated() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        createdAliases.forEach { if (keyStore.containsAlias(it)) keyStore.deleteEntry(it) }
    }

    @Test
    fun onTheAcceptedSocketAbortReleasesAStalledWrite() =
        withTlsPair {
            scope,
            accepted,
            _,
            ->
            measure(scope, accepted, "accepted", closeFirst = false)
        }

    @Test
    fun onTheDialledSocketAbortReleasesAStalledWrite() =
        withTlsPair {
            scope,
            _,
            dialled,
            ->
            measure(scope, dialled, "dialled", closeFirst = false)
        }

    @Test
    fun onTheAcceptedSocketCloseAloneIsRecordedThenAbortReleases() =
        withTlsPair { scope, accepted, _ -> measure(scope, accepted, "accepted, close first", closeFirst = true) }

    @Test
    fun onTheDialledSocketCloseAloneIsRecordedThenAbortReleases() =
        withTlsPair { scope, _, dialled -> measure(scope, dialled, "dialled, close first", closeFirst = true) }

    private suspend fun measure(
        scope: CoroutineScope,
        writerSide: ControlSocket,
        label: String,
        closeFirst: Boolean,
    ) {
        report(label, "TLS socket class = ${sslSocketClassOf(writerSide)}")
        val completed = AtomicInteger()
        val writerFailure = AtomicReference<Throwable?>()
        val writer =
            scope.launch {
                try {
                    while (true) {
                        writerSide.writeFrame(frame("PAD", "x".repeat(PAD_BYTES)))
                        completed.incrementAndGet()
                    }
                } catch (
                    // Recorded and asserted below. Rethrown, it would reach Android's uncaught handler.
                    @Suppress("TooGenericExceptionCaught") t: Throwable,
                ) {
                    writerFailure.set(t)
                }
            }
        withTimeout(FILL_BOUND_MS) {
            var last = -1
            while (completed.get() != last) {
                last = completed.get()
                delay(STALL_SAMPLE_MS)
            }
        }
        report(label, "${completed.get()} x ${PAD_BYTES / 1024} KiB frames before a write blocked")
        assertTrue(writer.isActive, "the writer must be blocked inside a write")

        val lockWaitStart = System.nanoTime()
        assertNull(withTimeoutOrNull(LOCK_WAIT_BOUND_MS) { writerSide.writeFrame(frame("PING", "")) })
        val lockWaitMs = elapsedMs(lockWaitStart)
        report(label, "a lock waiter timed out after $lockWaitMs ms (bound $LOCK_WAIT_BOUND_MS ms)")
        assertTrue(lockWaitMs < LOCK_WAIT_BOUND_MS + SLACK_MS)

        writer.cancel()
        delay(OBSERVE_MS)
        report(label, "$OBSERVE_MS ms after cancel(), the blocked writer completed = ${writer.isCompleted}")
        assertFalse(writer.isCompleted, "cancellation must not be assumed to end a blocking socket write")

        val closeReturned = CountDownLatch(1)
        val closeStart = System.nanoTime()
        if (closeFirst) {
            thread(name = "problem-137-close", isDaemon = true) {
                writerSide.close()
                closeReturned.countDown()
            }
            val returned = closeReturned.await(OBSERVE_MS, TimeUnit.MILLISECONDS)
            report(label, "$OBSERVE_MS ms after close(): close() returned = $returned, blocked writer completed = ${writer.isCompleted}")
        } else {
            closeReturned.countDown()
        }

        val abortStart = System.nanoTime()
        writerSide.abort()
        val abortMs = elapsedMs(abortStart)
        val joined = withTimeoutOrNull(RELEASE_BOUND_MS) { writer.join() }
        val releasedMs = elapsedMs(abortStart)
        val closeReleased = closeReturned.await(RELEASE_BOUND_MS, TimeUnit.MILLISECONDS)
        val cause = generateSequence(writerFailure.get()) { it.cause }.firstOrNull { it is IOException }
        report(
            label,
            "abort() returned in $abortMs ms; writer ended $releasedMs ms after it " +
                "(I/O cause: ${cause?.javaClass?.simpleName}: ${cause?.message}); waiting close() returned = $closeReleased, " +
                "${elapsedMs(closeStart)} ms after it was called",
        )
        assertTrue(abortMs < RELEASE_BOUND_MS, "abort() must not wait for a writer stalled in the kernel")
        assertEquals(Unit, joined, "abort() must release a writer stalled in the kernel, or one close() already released")
        assertTrue(closeReleased, "no close() may still be waiting once abort() has run")
    }

    private fun withTlsPair(body: suspend (CoroutineScope, ControlSocket, ControlSocket) -> Unit) =
        runBlocking<Unit> {
            val server = identity("server")
            val client = identity("client")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val listener = TlsControlChannel(server).bind()
            try {
                val acceptedJob = scope.async { listener.acceptWithin(HANDSHAKE_MS.toInt()) }
                val dialled = withContext(Dispatchers.IO) { TlsControlChannel(client).connect(LOOPBACK, listener.localPort) }
                val accepted = withTimeout(HANDSHAKE_MS) { acceptedJob.await() }
                try {
                    body(scope, accepted, dialled)
                } finally {
                    accepted.abort()
                    dialled.abort()
                }
            } finally {
                listener.close()
                scope.cancel()
            }
        }

    private fun identity(role: String): DeviceIdentity {
        val current = "ridelink.test.$run.$role.v2"
        val legacy = "ridelink.test.$run.$role.v1"
        check(current != AndroidKeystoreIdentityStore.DEFAULT_ALIAS && legacy != AndroidKeystoreIdentityStore.LEGACY_ALIAS)
        createdAliases += listOf(current, legacy)
        return assertIs<IdentityResolution.Ready>(AndroidKeystoreIdentityStore(current, legacy).resolve(now)).identity
    }

    private fun frame(
        type: String,
        pad: String,
    ) = Envelope(
        v = ProtocolVersion.CURRENT,
        type = type,
        sessionId = "test-session",
        senderId = "aaaaaaaaaaaaaaaa",
        msgId = UUID.randomUUID().toString(),
        seq = 1,
        sentAtMonoUs = 1,
        requiresAck = false,
        payload = buildJsonObject { put("pad", pad) },
    )

    private fun report(
        label: String,
        line: String,
    ) {
        Log.i(TAG, "problem 137 evidence [$label]: $line")
    }

    private fun elapsedMs(startNanos: Long) = (System.nanoTime() - startNanos) / NANOS_PER_MS

    private fun sslSocketClassOf(socket: ControlSocket): String =
        ControlSocket::class.java
            .getDeclaredField("socket")
            .apply { isAccessible = true }
            .get(socket)
            .javaClass.name

    private companion object {
        const val TAG = "Problem137Evidence"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val LOOPBACK = "127.0.0.1"
        const val RUN_ID_CHARS = 8
        const val MILLIS_PER_SECOND = 1000L
        const val PAD_BYTES = 200 * 1024
        const val HANDSHAKE_MS = 30_000L
        const val FILL_BOUND_MS = 60_000L
        const val STALL_SAMPLE_MS = 1_000L
        const val LOCK_WAIT_BOUND_MS = 500L
        const val OBSERVE_MS = 2_000L
        const val RELEASE_BOUND_MS = 2_000L
        const val SLACK_MS = 500L
        const val NANOS_PER_MS = 1_000_000L
    }
}
