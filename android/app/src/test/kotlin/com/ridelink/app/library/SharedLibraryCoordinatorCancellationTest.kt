package com.ridelink.app.library

import com.ridelink.core.manifest.ManifestEntry
import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.ManifestId
import com.ridelink.core.model.PeerId
import com.ridelink.core.model.QuickId
import com.ridelink.core.model.SessionId
import com.ridelink.core.model.SpkiHash
import com.ridelink.core.model.TransferId
import com.ridelink.core.protocol.TransferMessage
import com.ridelink.core.transfer.TransferStatus
import com.ridelink.data.library.LibraryRepository
import com.ridelink.data.transfer.CacheStorage
import com.ridelink.data.transfer.ManifestGenerator
import com.ridelink.data.transfer.TransferCacheRepository
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.control.LinkLossReason
import com.ridelink.network.transfer.BulkFetchOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-023 Amendment A4 Finding V — coordinator-level proof that a *superseded* requester operation
 * (user-cancelled, or killed by a session boundary) performs no storage work after being
 * superseded. Amendment A1's `OperationFence` already dropped its state *writes*; what it did not
 * stop was the losing task's own `cacheStorage.deletePart(hash)` and `promote`/`commit`, all keyed
 * on nothing but `content_hash` — so a late cleanup could delete the `.part` file a **newer**
 * operation for the same hash had already begun writing (brief §18's one-writer-per-`.part` rule),
 * or publish a verified cache entry and a `TRANSFER_RESULT` for a transfer the user had cancelled.
 *
 * Deterministic throughout: [FakeBulkTransportPort.fetchGate] holds a real transfer genuinely in
 * flight, and [StandardTestDispatcher] decides ordering — never a sleep.
 */
class SharedLibraryCoordinatorCancellationTest {
    /** A real payload and its real SHA-256, so `CacheStorage.promote` — which recomputes the hash
     *  from the bytes on disk (ADR-023 §6) — genuinely succeeds. Without this, every scenario below
     *  would fail promote for the wrong reason and could not tell a wrongly-committed transfer from
     *  a merely-unhashable one. */
    private val payload = ByteArray(OFFER_SIZE_BYTES.toInt()) { (it * 7 % 251).toByte() }
    private val hashX =
        ContentHash(
            "sha256:" +
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(payload)
                    .joinToString("") { "%02x".format(it) },
        )
    private val transferX = TransferId("01ARZ3NDEKTSV4RRFFQ69G5FAV")
    private val transferY = TransferId("01BXAZ3NDEKTSV4RRFFQ69G5FB")
    private val peerA = SpkiHash("sha256:" + "aa".repeat(32))
    private val peerB = SpkiHash("sha256:" + "bb".repeat(32))
    private val peerIdB = PeerId("bbbbbbbbbbbbbbbb")
    private val sessionIdB = SessionId("01BXAZ3NDEKTSV4RRFFQ69G5FB")

    private val tempDirs = mutableListOf<java.io.File>()
    private lateinit var cacheStorage: CacheStorage

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun entry(hash: ContentHash) =
        ManifestEntry(
            contentHash = hash,
            quickId = QuickId("sha256:" + "9".repeat(64)),
            workKey = "wk",
            title = "t",
            artist = "a",
            album = "al",
            durationMs = 1_000,
            codec = "mp3",
            bitrateKbps = 320,
            sizeBytes = OFFER_SIZE_BYTES,
            filename = "t.mp3",
            hasArtwork = false,
        )

    private fun newCoordinator(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        session: FakeTransferSessionPort,
        bulkTransport: BulkTransportPort,
        transferIds: Iterator<TransferId>,
    ): SharedLibraryCoordinator {
        val root = createTempDirectory("ridelink-cancel-test").toFile()
        tempDirs.add(root)
        // [dispatcher], not Dispatchers.IO: every `.part` open/append/delete this test asserts on
        // must be ordered by the same test scheduler as the coroutines that issue them, or the
        // assertions would race real threads.
        cacheStorage = CacheStorage(root, dispatcher)
        val cacheRepository = TransferCacheRepository(cacheStorage, InMemoryTransferCacheDao())
        return SharedLibraryCoordinator(
            scope = scope,
            monotonicNowUs = { 0L },
            manifestGenerator = ManifestGenerator(LibraryRepository(UnusedTrackDao())),
            cacheRepository = cacheRepository,
            cacheStorage = cacheStorage,
            contentResolver = GatedContentResolver(),
            bulkTransport = bulkTransport,
            controlSessionManager = session,
            nextTransferId = { transferIds.next() },
            nextManifestId = { ManifestId("01ARZ3NDEKTSV4RRFFQ69G5FAV") },
        )
    }

    /** Answers whichever `TRANSFER_REQUEST` the coordinator most recently sent, so `runDownload`
     *  gets past negotiation and into a real, gate-held transfer. */
    private fun answerOffer(
        session: FakeTransferSessionPort,
        transferId: TransferId,
    ) {
        session.transfer.sink!!.submit(
            TransferMessage.Offer(transferId, OFFER_SIZE_BYTES, CHUNK_SIZE, CHUNK_COUNT, BULK_PORT, "fake-bulk-token"),
            session.readGeneration(),
        )
    }

    /**
     * **The confirmed Finding V case.** A session boundary force-closes the bulk socket — that is
     * `bulkTransport.close()`'s job (ADR-023 §1) — and force-closing it is exactly what lets the
     * parked `fetch` return. If the transfer's bytes had all already arrived, it returns `OK`, and
     * a pre-A4 build then walked straight on through `promote` → `commit` →
     * `TRANSFER_RESULT{ok: true}` → `COMPLETE`, because `onSessionBoundary()` superseded the fence
     * only *after* that close and cancelled the task only in a coroutine it had merely *launched*.
     * The transfer therefore completed into a session that had already ended, under a peer that had
     * already gone.
     *
     * **What this test proves, precisely.** The operative fix is the resumed operation re-checking
     * the fence before it touches storage: remove that check and this case fails, whichever way
     * `onSessionBoundary`'s statements are ordered. Amendment A4's other half — moving
     * `transferFence.supersede()` *above* `bulkTransport.close()` — is deliberately **not** proven
     * here, and cannot be: it closes a genuinely multi-threaded window (the operation resuming
     * while the fence is still current, so that even a correct fence check passes), and a
     * single-threaded [StandardTestDispatcher] schedules the supersede before the resumed
     * continuation either way. It is kept as an ordering guarantee established by reading the code,
     * not by this test — recorded honestly rather than implied to be covered.
     */
    @Test
    fun `a transfer unblocked by a session boundary cannot promote, commit or report success`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher + Job())
            val session = FakeTransferSessionPort(generation = 1L, peerSpki = peerA)
            val bulkTransport = FakeBulkTransportPort()
            bulkTransport.fetchGate = CompletableDeferred()
            // Every byte arrived before the link dropped, so the force-closed fetch returns OK —
            // and the bytes really are on disk, so promote() would really succeed if it ran.
            bulkTransport.fetchPayload = payload
            bulkTransport.fetchOutcome = BulkFetchOutcome.OK
            val coordinator = newCoordinator(scope, dispatcher, session, bulkTransport, listOf(transferX, transferY).iterator())

            coordinator.requestDownload(entry(hashX))
            runCurrent()
            answerOffer(session, transferX)
            runCurrent()
            assertEquals(TransferStatus.TRANSFERRING, coordinator.downloadStates.value[hashX.value]?.status)
            assertTrue(cacheStorage.partFile(hashX).isFile, "the operation really opened its .part")

            // The boundary: the link drops, then a *different* peer authenticates.
            session.emitEvent(ControlEvent.LinkLost(LinkLossReason.NETWORK))
            runCurrent()
            session.currentAuthGeneration = 2L
            session.currentPeerSpki = peerB
            session.emitEvent(ControlEvent.Connected(peerIdB, sessionIdB, isLocalLeader = false))
            runCurrent()
            assertTrue(bulkTransport.closeCallCount > 0, "onSessionBoundary() really ran and really closed the transport")

            assertEquals(
                TransferStatus.FAILED,
                coordinator.downloadStates.value[hashX.value]?.status,
                "the transfer belonged to a session that has ended — FAILED is terminal, COMPLETE is not reachable",
            )
            assertFalse(
                cacheStorage.hasMediaFile(hashX),
                "a transfer superseded by a session boundary must not promote its bytes into the verified cache",
            )
            assertTrue(coordinator.cachedHashes.value.isEmpty(), "and must not appear as verified-cached")
            assertTrue(
                session.transfer.sent.none { it is TransferMessage.Result && it.ok },
                "and must never report ok: true — least of all to the peer that replaced the one that sent the bytes",
            )
            scope.coroutineContext[Job]!!.cancel()
        }

    /**
     * The same shape one step earlier: the boundary lands while the transfer is still genuinely
     * mid-flight and its bytes are *not* all in, so the force-closed fetch returns a failure. The
     * terminal state must be the session-loss failure, and nothing may be promoted.
     */
    @Test
    fun `a transfer interrupted by a session boundary ends FAILED with no cache side effects`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher + Job())
            val session = FakeTransferSessionPort(generation = 1L, peerSpki = peerA)
            val bulkTransport = FakeBulkTransportPort()
            bulkTransport.fetchGate = CompletableDeferred()
            bulkTransport.fetchOutcome = BulkFetchOutcome.CONNECTION_LOST
            val coordinator = newCoordinator(scope, dispatcher, session, bulkTransport, listOf(transferX, transferY).iterator())

            coordinator.requestDownload(entry(hashX))
            runCurrent()
            answerOffer(session, transferX)
            runCurrent()
            assertEquals(TransferStatus.TRANSFERRING, coordinator.downloadStates.value[hashX.value]?.status)

            session.emitEvent(ControlEvent.LinkLost(LinkLossReason.NETWORK))
            runCurrent()

            assertEquals(TransferStatus.FAILED, coordinator.downloadStates.value[hashX.value]?.status)
            assertFalse(cacheStorage.hasMediaFile(hashX))
            assertFalse(cacheStorage.partFile(hashX).isFile, "the session-boundary path deletes the .part it abandoned")
            assertTrue(session.transfer.sent.none { it is TransferMessage.Result && it.ok })
            scope.coroutineContext[Job]!!.cancel()
        }

    /**
     * **Confirmed already correct — kept as a regression, not as a fix.** The user-cancellation
     * path is safe by construction and this pass changed nothing about it: `cancelDownload` cancels
     * `activeDownloadJob` *before* it force-closes the socket, and every storage call `runDownload`
     * makes afterwards (`deletePart`, `promote`, `cacheRepository.commit`) is a cancellable
     * `suspend` function. The resumed operation therefore unwinds on `CancellationException`
     * instead of continuing, so it never reaches the storage work at all — a different mechanism
     * from Finding V's fence re-check, arriving at the same guarantee. This case pins that
     * ordering, because reversing those two statements would silently reintroduce Finding V on the
     * cancellation path.
     */
    @Test
    fun `a user-cancelled transfer stays CANCELLED and leaves no cache side effects`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher + Job())
            val session = FakeTransferSessionPort(generation = 1L, peerSpki = peerA)
            val bulkTransport = FakeBulkTransportPort()
            bulkTransport.fetchGate = CompletableDeferred()
            bulkTransport.fetchPayload = payload // the bytes did arrive, and really hash to hashX
            bulkTransport.fetchOutcome = BulkFetchOutcome.OK
            val coordinator = newCoordinator(scope, dispatcher, session, bulkTransport, listOf(transferX, transferY).iterator())

            coordinator.requestDownload(entry(hashX))
            runCurrent()
            answerOffer(session, transferX)
            runCurrent()
            assertEquals(TransferStatus.TRANSFERRING, coordinator.downloadStates.value[hashX.value]?.status)

            coordinator.cancelDownload(hashX)
            runCurrent()

            assertEquals(TransferStatus.CANCELLED, coordinator.downloadStates.value[hashX.value]?.status)
            assertFalse(cacheStorage.hasMediaFile(hashX), "a cancelled transfer must not promote its bytes into the verified cache")
            assertFalse(cacheStorage.partFile(hashX).isFile, "and its .part is deleted, so a re-request starts clean (brief §18)")
            assertTrue(coordinator.cachedHashes.value.isEmpty())
            assertTrue(
                session.transfer.sent.none { it is TransferMessage.Result && it.ok },
                "a cancelled transfer must never report ok: true to the peer",
            )
            assertTrue(
                session.transfer.sent.any { it is TransferMessage.Cancel && it.transferId == transferX },
                "PROTOCOL §8.2: the peer is told, rather than left to infer it from a dropped connection",
            )
            scope.coroutineContext[Job]!!.cancel()
        }

    /**
     * Brief §18's one-writer rule, end to end: cancel H, immediately re-request H, and prove the
     * new operation owns a live `.part` that the superseded one did not take with it.
     */
    @Test
    fun `the same content hash cancelled then immediately re-requested has exactly one live part writer`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher + Job())
            val session = FakeTransferSessionPort(generation = 1L, peerSpki = peerA)
            val bulkTransport = FakeBulkTransportPort()
            bulkTransport.fetchGate = CompletableDeferred()
            val coordinator = newCoordinator(scope, dispatcher, session, bulkTransport, listOf(transferX, transferY).iterator())

            coordinator.requestDownload(entry(hashX))
            runCurrent()
            answerOffer(session, transferX)
            runCurrent()
            coordinator.cancelDownload(hashX)
            runCurrent()
            assertEquals(TransferStatus.CANCELLED, coordinator.downloadStates.value[hashX.value]?.status)
            assertFalse(cacheStorage.partFile(hashX).isFile)

            bulkTransport.fetchGate = CompletableDeferred()
            coordinator.requestDownload(entry(hashX))
            runCurrent()
            answerOffer(session, transferY)
            runCurrent()

            assertEquals(
                TransferStatus.TRANSFERRING,
                coordinator.downloadStates.value[hashX.value]?.status,
                "CANCELLED is terminal for that operation, not for the content hash — a fresh request is a fresh operation",
            )
            assertTrue(cacheStorage.partFile(hashX).isFile, "and the fresh operation owns its own .part")
            val requestsSent = session.transfer.sent.filterIsInstance<TransferMessage.Request>()
            assertEquals(2, requestsSent.size, "one TRANSFER_REQUEST per operation — the cancelled one and the fresh one")
            scope.coroutineContext[Job]!!.cancel()
        }

    private companion object {
        const val OFFER_SIZE_BYTES = 128L
        const val CHUNK_SIZE = 65_536
        const val CHUNK_COUNT = 1
        const val BULK_PORT = 45_000
    }
}
