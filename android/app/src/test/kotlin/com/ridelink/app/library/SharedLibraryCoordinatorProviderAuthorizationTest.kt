package com.ridelink.app.library

import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.ManifestId
import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.model.SpkiHash
import com.ridelink.core.model.TransferId
import com.ridelink.core.protocol.TransferMessage
import com.ridelink.data.library.LibraryRepository
import com.ridelink.data.transfer.CacheStorage
import com.ridelink.data.transfer.ContentResolution
import com.ridelink.data.transfer.ManifestGenerator
import com.ridelink.data.transfer.TransferCacheRepository
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.control.LinkLossReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-023 Amendment A3 — coordinator-level proof that [SharedLibraryCoordinator.serveTransferRequest]
 * cannot be exploited by a session boundary landing inside one of its own suspension points. Every
 * scenario below is deterministic (a [CompletableDeferred] or a synchronous hook stands in for
 * timing, never a sleep) and none of them touches a real socket or TLS — see this file's own
 * `TransferPorts.kt`/`TransferPortFakes.kt` for why the fakes exist and what they are exactly the
 * call surface of.
 *
 * Section 17's own point stands: [handleTransferMessage]'s dispatch-entry generation check
 * (Amendment A2's Finding B fix) is real, but every scenario here proves it is *not* the only
 * protection — each defeats it by landing the boundary one step later than dispatch.
 */
class SharedLibraryCoordinatorProviderAuthorizationTest {
    private val hashX = ContentHash("sha256:" + "1".repeat(64))
    private val transferX = TransferId("01ARZ3NDEKTSV4RRFFQ69G5FAV")
    private val transferY = TransferId("01BXAZ3NDEKTSV4RRFFQ69G5FB")
    private val peerA = SpkiHash("sha256:" + "aa".repeat(32))
    private val peerB = SpkiHash("sha256:" + "bb".repeat(32))
    private val peerIdB = PeerId("bbbbbbbbbbbbbbbb")
    private val sessionIdB = SessionId("01BXAZ3NDEKTSV4RRFFQ69G5FB")

    private val tempDirs = mutableListOf<java.io.File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun newCoordinator(
        scope: CoroutineScope,
        session: FakeTransferSessionPort,
        contentResolver: ContentResolverPort,
        bulkTransport: BulkTransportPort,
    ): SharedLibraryCoordinator {
        val root = createTempDirectory("ridelink-coordinator-test").toFile()
        tempDirs.add(root)
        val cacheStorage = CacheStorage(root)
        val cacheRepository = TransferCacheRepository(cacheStorage, EmptyTransferCacheDao())
        val manifestGenerator = ManifestGenerator(LibraryRepository(UnusedTrackDao()))
        return SharedLibraryCoordinator(
            scope = scope,
            monotonicNowUs = { 0L },
            manifestGenerator = manifestGenerator,
            cacheRepository = cacheRepository,
            cacheStorage = cacheStorage,
            contentResolver = contentResolver,
            bulkTransport = bulkTransport,
            controlSessionManager = session,
            nextTransferId = { transferX },
            nextManifestId = { ManifestId("01ARZ3NDEKTSV4RRFFQ69G5FAV") },
        )
    }

    private fun found(sizeBytes: Long = 128L): ContentResolution.Found =
        ContentResolution.Found({ java.io.ByteArrayInputStream(ByteArray(0)) }, sizeBytes)

    /** Section 16/17 — the primary scenario: a boundary lands *while* `contentResolver.resolve()` is suspended. */
    @Test
    fun `stale request cannot acquire the slot after a boundary during content resolve`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher + Job())
            val resolverGate = CompletableDeferred<Unit>()
            val session = FakeTransferSessionPort(generation = 10L, peerSpki = peerA)
            val contentResolver = GatedContentResolver(resolverGate)
            val bulkTransport = FakeBulkTransportPort()
            newCoordinator(scope, session, contentResolver, bulkTransport)
            contentResolver.resolution = found()

            session.transfer.sink!!.submit(TransferMessage.Request(hashX, transferX), session.readGeneration())
            advanceUntilIdle()
            assertEquals(1, contentResolver.resolveCallCount)

            // The session boundary: a reconnect to a different peer while resolve() is suspended.
            session.currentAuthGeneration = 11L
            session.currentPeerSpki = peerB
            session.emitEvent(ControlEvent.Connected(peerIdB, sessionIdB, isLocalLeader = false))
            advanceUntilIdle()
            assertEquals(1, bulkTransport.closeCallCount) // onSessionBoundary() really ran

            resolverGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(0, bulkTransport.ensureListeningCallCount)
            assertTrue(bulkTransport.issuedTokenCalls.isEmpty())
            assertTrue(bulkTransport.serveCalls.isEmpty())
            assertTrue(session.transfer.sent.none { it is TransferMessage.Offer })
        }

    /** Regression B — the boundary lands the instant after `resolve()` returns, before `bulkGate.tryAcquire`. */
    @Test
    fun `stale request cannot acquire the slot when the boundary lands right after resolve returns`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher + Job())
            val session = FakeTransferSessionPort(generation = 10L, peerSpki = peerA)
            lateinit var bulkTransport: FakeBulkTransportPort
            val contentResolver =
                GatedContentResolver(
                    onBeforeReturn = {
                        session.currentAuthGeneration = 11L
                        session.currentPeerSpki = peerB
                    },
                )
            bulkTransport = FakeBulkTransportPort()
            newCoordinator(scope, session, contentResolver, bulkTransport)
            contentResolver.resolution = found()

            session.transfer.sink!!.submit(TransferMessage.Request(hashX, transferX), session.readGeneration())
            advanceUntilIdle()

            assertEquals(0, bulkTransport.ensureListeningCallCount)
            assertTrue(bulkTransport.issuedTokenCalls.isEmpty())
            assertTrue(session.transfer.sent.none { it is TransferMessage.Offer })
        }

    /**
     * Regression C — the same real-suspension mechanism as the primary scenario, but landing the
     * boundary one checkpoint later: *during* `ensureListening()`'s own suspension (the real
     * `BulkTransportManager.ensureListening()` suspends on a `Mutex.withLock`), after
     * [BulkOperationGate][com.ridelink.core.transfer.BulkOperationGate] acquisition has already
     * succeeded. Proves [stillAuthorised] — not [ProviderSessionContext] — is what closes this
     * later gap, since the gate is what a real [onSessionBoundary] invalidates.
     */
    @Test
    fun `stale request cannot mint a token when a boundary lands during ensureListening`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher + Job())
            val session = FakeTransferSessionPort(generation = 10L, peerSpki = peerA)
            val contentResolver = GatedContentResolver()
            val bulkTransport = FakeBulkTransportPort()
            val listeningGate = CompletableDeferred<Unit>()
            bulkTransport.ensureListeningGate = listeningGate
            newCoordinator(scope, session, contentResolver, bulkTransport)
            contentResolver.resolution = found()

            session.transfer.sink!!.submit(TransferMessage.Request(hashX, transferX), session.readGeneration())
            advanceUntilIdle()
            assertEquals(1, bulkTransport.ensureListeningCallCount) // gate acquired, now parked in ensureListening()

            // The session boundary, landing inside that suspension — exactly like the primary
            // scenario, one checkpoint later.
            session.currentAuthGeneration = 11L
            session.currentPeerSpki = peerB
            session.emitEvent(ControlEvent.LinkLost(LinkLossReason.NETWORK))
            advanceUntilIdle()
            assertEquals(1, bulkTransport.closeCallCount) // onSessionBoundary() really ran and invalidated the gate

            listeningGate.complete(Unit)
            advanceUntilIdle()

            assertTrue(bulkTransport.issuedTokenCalls.isEmpty())
            assertTrue(session.transfer.sent.none { it is TransferMessage.Offer })
            assertTrue(bulkTransport.serveCalls.isEmpty())
        }

    // --- ADR-023 Amendment A5 Finding C: gate ownership is only half of "still authorised" -------

    /**
     * **The A5 Finding C regression, at coordinator level.** Every A3 scenario above lands the
     * boundary as a real [onSessionBoundary] run, which invalidates [bulkGate] — so `isOwner` alone
     * was enough to catch them. This one reproduces the state a boundary is *in the middle of*:
     * the live generation/peer have already moved to the next session, and the gate has **not yet**
     * been invalidated. On iOS that window is an `await` (`onSessionBoundary` bumps `sessionEpoch`,
     * then `await`s `bulkTransport.close()`, then invalidates the gate); on Android it is the gap
     * between `ControlSessionManager` bumping `currentAuthGeneration` and the resulting `Connected`
     * event reaching this coordinator's collector. Deliberately no event is emitted here, which is
     * exactly what makes the gate still say "yes".
     *
     * Before A5 this minted a bulk token under the **new** session's generation and put a
     * `TRANSFER_OFFER` on the wire — offering old peer A's requested file to whoever is connected
     * now, the very outcome A3 set out to prevent one checkpoint earlier.
     */
    @Test
    fun `a request cannot mint a token once the live session has moved on, even while it still holds the gate`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher + Job())
            val session = FakeTransferSessionPort(generation = 10L, peerSpki = peerA)
            val contentResolver = GatedContentResolver()
            val bulkTransport = FakeBulkTransportPort()
            val listeningGate = CompletableDeferred<Unit>()
            bulkTransport.ensureListeningGate = listeningGate
            newCoordinator(scope, session, contentResolver, bulkTransport)
            contentResolver.resolution = found()

            session.transfer.sink!!.submit(TransferMessage.Request(hashX, transferX), session.readGeneration())
            advanceUntilIdle()
            assertEquals(1, bulkTransport.ensureListeningCallCount) // gate acquired, now parked in ensureListening()

            // The live session moves — and nothing else. No ControlEvent, so onSessionBoundary()
            // has not run, so bulkGate still names transferX: gate ownership alone still says yes.
            session.currentAuthGeneration = 11L
            session.currentPeerSpki = peerB
            listeningGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(0, bulkTransport.closeCallCount, "the precondition: no boundary has run, so the gate was never invalidated")
            assertTrue(bulkTransport.issuedTokenCalls.isEmpty(), "no token may be minted under a session this request never authorised")
            assertTrue(session.transfer.sent.none { it is TransferMessage.Offer })
            assertTrue(bulkTransport.serveCalls.isEmpty())
        }

    /** The peer half of the same rule — a reconnect to a *different* peer at the same generation is
     *  equally disqualifying (defence in depth, per [ProviderSessionContext]'s own doc comment). */
    @Test
    fun `a request cannot mint a token once the live peer has changed, even while it still holds the gate`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher + Job())
            val session = FakeTransferSessionPort(generation = 10L, peerSpki = peerA)
            val contentResolver = GatedContentResolver()
            val bulkTransport = FakeBulkTransportPort()
            val listeningGate = CompletableDeferred<Unit>()
            bulkTransport.ensureListeningGate = listeningGate
            newCoordinator(scope, session, contentResolver, bulkTransport)
            contentResolver.resolution = found()

            session.transfer.sink!!.submit(TransferMessage.Request(hashX, transferX), session.readGeneration())
            advanceUntilIdle()
            assertEquals(1, bulkTransport.ensureListeningCallCount)

            session.currentPeerSpki = peerB
            listeningGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(0, bulkTransport.closeCallCount)
            assertTrue(bulkTransport.issuedTokenCalls.isEmpty())
            assertTrue(session.transfer.sent.none { it is TransferMessage.Offer })
        }

    /**
     * A5 Finding B's coordinator-visible consequence: `ensureListening()` now *fails* rather than
     * publishing a listener bound into a lifetime a boundary already ended. The provider path must
     * release the shared slot when it does — otherwise every later transfer, in **both** roles,
     * would be blocked until the next session boundary.
     */
    @Test
    fun `a failed ensureListening releases the shared slot instead of wedging it`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher + Job())
            val session = FakeTransferSessionPort(generation = 10L, peerSpki = peerA)
            val contentResolver = GatedContentResolver()
            val bulkTransport = FakeBulkTransportPort()
            bulkTransport.ensureListeningFailure = java.io.IOException("the bulk listener lifetime ended while it was binding")
            newCoordinator(scope, session, contentResolver, bulkTransport)
            contentResolver.resolution = found()

            session.transfer.sink!!.submit(TransferMessage.Request(hashX, transferX), session.readGeneration())
            advanceUntilIdle()

            assertTrue(bulkTransport.issuedTokenCalls.isEmpty())
            assertTrue(session.transfer.sent.none { it is TransferMessage.Offer })

            // The slot really is free: a second request, with a working listener, serves normally.
            bulkTransport.ensureListeningFailure = null
            session.transfer.sink!!.submit(TransferMessage.Request(hashX, transferY), session.readGeneration())
            advanceUntilIdle()
            assertEquals(listOf(transferY), bulkTransport.serveCalls)
        }

    /** Regression E — a `TRANSFER_CANCEL` naming a foreign transfer_id must never touch the real active operation. */
    @Test
    fun `a cancel naming a different transfer cannot disturb the active one`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher + Job())
            val session = FakeTransferSessionPort(generation = 10L, peerSpki = peerA)
            val contentResolver = GatedContentResolver()
            val bulkTransport = FakeBulkTransportPort()
            newCoordinator(scope, session, contentResolver, bulkTransport)
            contentResolver.resolution = found()

            session.transfer.sink!!.submit(TransferMessage.Request(hashX, transferX), session.readGeneration())
            advanceUntilIdle()
            assertEquals(1, bulkTransport.serveCalls.size) // the real transfer is active

            // A cancel for an unrelated transfer_id must be a no-op.
            session.transfer.sink!!.submit(TransferMessage.Cancel(transferY, "user_cancelled"), session.readGeneration())
            advanceUntilIdle()

            assertEquals(0, bulkTransport.cancelActiveCallCount)
        }

    /**
     * A5's other half of the same rule: the cancel that *does* name the genuinely-active transfer
     * is routed to the transport **by that id**, not manager-wide. Held open with [serveGate] so
     * the provider operation is still in flight when the cancel lands — without that it has already
     * released the gate, and a correct implementation is right to ignore the cancel.
     */
    @Test
    fun `a cancel naming the active transfer is routed to the transport by that id`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher + Job())
            val session = FakeTransferSessionPort(generation = 10L, peerSpki = peerA)
            val contentResolver = GatedContentResolver()
            val bulkTransport = FakeBulkTransportPort()
            bulkTransport.serveGate = CompletableDeferred()
            newCoordinator(scope, session, contentResolver, bulkTransport)
            contentResolver.resolution = found()

            session.transfer.sink!!.submit(TransferMessage.Request(hashX, transferX), session.readGeneration())
            advanceUntilIdle()
            assertEquals(1, bulkTransport.serveCalls.size) // genuinely mid-flight, still holding the gate

            session.transfer.sink!!.submit(TransferMessage.Cancel(transferY, "user_cancelled"), session.readGeneration())
            advanceUntilIdle()
            assertTrue(bulkTransport.cancelActiveCalls.isEmpty(), "a cancel for a foreign id must never reach the transport")

            session.transfer.sink!!.submit(TransferMessage.Cancel(transferX, "user_cancelled"), session.readGeneration())
            advanceUntilIdle()
            assertEquals(listOf(transferX), bulkTransport.cancelActiveCalls)
        }

    /** Regression F — cross-role exclusion: a local download queued behind an active provider operation
     *  resumes automatically once the provider operation releases the shared slot. */
    @Test
    fun `a queued local download resumes once the active provider operation releases the slot`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher + Job())
            val session = FakeTransferSessionPort(generation = 10L, peerSpki = peerA)
            val contentResolver = GatedContentResolver()
            val bulkTransport = FakeBulkTransportPort()
            val serveGate = CompletableDeferred<Unit>()
            bulkTransport.serveGate = serveGate
            val coordinator = newCoordinator(scope, session, contentResolver, bulkTransport)
            contentResolver.resolution = found()

            session.transfer.sink!!.submit(TransferMessage.Request(hashX, transferX), session.readGeneration())
            advanceUntilIdle()
            assertEquals(1, bulkTransport.serveCalls.size) // provider now holds the one shared slot, genuinely mid-flight

            val otherHash = ContentHash("sha256:" + "2".repeat(64))
            val entry =
                com.ridelink.core.manifest.ManifestEntry(
                    contentHash = otherHash,
                    quickId =
                        com.ridelink.core.model
                            .QuickId("sha256:" + "2".repeat(64)),
                    workKey = "work",
                    title = "t",
                    artist = "a",
                    album = "al",
                    durationMs = 1,
                    codec = "mp3",
                    bitrateKbps = 128,
                    sizeBytes = 1,
                    filename = "t.mp3",
                    hasArtwork = false,
                )
            coordinator.requestDownload(entry)
            advanceUntilIdle()

            // The provider operation still holds the slot, so the local download must stay queued
            // rather than send its own TRANSFER_REQUEST.
            assertTrue(session.transfer.sent.none { it is TransferMessage.Request && it.contentHash == otherHash })

            // Once the provider operation genuinely finishes and releases the slot, the queued
            // local download resumes automatically (no separate wake-up needed).
            serveGate.complete(Unit)
            advanceUntilIdle()
            assertTrue(session.transfer.sent.any { it is TransferMessage.Request && it.contentHash == otherHash })
        }
}
