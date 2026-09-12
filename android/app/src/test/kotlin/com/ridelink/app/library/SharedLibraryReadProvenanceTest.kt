package com.ridelink.app.library

import com.ridelink.core.manifest.ManifestEntry
import com.ridelink.core.manifest.ManifestKind
import com.ridelink.core.manifest.ManifestPaging
import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.ManifestId
import com.ridelink.core.model.PeerId
import com.ridelink.core.model.QuickId
import com.ridelink.core.model.SessionId
import com.ridelink.core.model.SpkiHash
import com.ridelink.core.model.TransferId
import com.ridelink.core.protocol.ManifestMessage
import com.ridelink.core.protocol.TransferMessage
import com.ridelink.data.library.LibraryRepository
import com.ridelink.data.transfer.CacheStorage
import com.ridelink.data.transfer.ContentResolution
import com.ridelink.data.transfer.ManifestGenerator
import com.ridelink.data.transfer.TransferCacheRepository
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.control.LinkLossReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **ADR-025 §1 at the Phase 4 seam that contained it** — `docs/STATUS.md` §4 problem 44, the finding
 * ADR-024 Amendment A7 confirmed and deliberately did not fix.
 *
 * `SharedLibraryCoordinator`'s `ManifestSink`/`TransferSink` lambdas are invoked **synchronously
 * from `ControlSessionManager.handleFrame`**, and each used to read a live value *there*
 * (`currentAuthGeneration`). A7 proved that `handleFrame` can legitimately be entered with a
 * **retired** binding, so the live read returned the *successor's* number, `handleManifestMessage`'s
 * re-check compared that number against itself and passed, and a Session A `MANIFEST_PAGE` mutated
 * Session B's catalogue — precisely what ADR-023 Amendment A2's Finding S exists to prevent.
 *
 * `RetiredSessionProvenanceTest` pins the same rule one layer down, at the relay, over two real TLS
 * sessions. This file pins what that layer protects: the catalogue and the transfer state a rider
 * actually sees. Everything here is deterministic — [StandardTestDispatcher] plus the narrow fakes
 * `TransferPorts.kt` declares, no sockets, no sleeps.
 *
 * **How the pre-fix ordering is expressed.** The defect needs the capture to happen *after* the
 * boundary, because that is when a live read returns the wrong number. So each scenario moves the
 * fake session on **first** and only then submits the frame, tagged with the generation the read
 * that produced it was authorised by. Post-fix that tag is a Session A number and the guard refuses
 * it; pre-fix the sink ignored the tag entirely and read Session B's number right there.
 *
 * The mirror is `RideLinkPlatformTests.SharedLibraryReadProvenanceTests`.
 */
class SharedLibraryReadProvenanceTest {
    private val hashX = ContentHash("sha256:" + "1".repeat(64))
    private val transferX = TransferId("01ARZ3NDEKTSV4RRFFQ69G5FAV")
    private val peerA = SpkiHash("sha256:" + "aa".repeat(32))
    private val peerB = SpkiHash("sha256:" + "bb".repeat(32))
    private val peerIdB = PeerId("bbbbbbbbbbbbbbbb")
    private val sessionIdB = SessionId("01BXAZ3NDEKTSV4RRFFQ69G5FB")

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    /**
     * **Requirement A.** A Session A `MANIFEST_BEGIN`/`PAGE`/`END` dispatched after Session B became
     * live must not put a single entry into Session B's catalogue.
     */
    @Test
    fun `a MANIFEST read under Session A cannot populate Session B's catalogue`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher + Job())
            val session = FakeTransferSessionPort(generation = 10L, peerSpki = peerA)
            val coordinator = newCoordinator(scope, session)
            advanceUntilIdle()

            // A real reconnect, in the order production performs it: the manager's generation moves
            // at `activateAuthenticatedSession`, and the `Connected` event that runs
            // `onSessionBoundary` is dispatched afterwards.
            session.currentAuthGeneration = 11L
            session.currentPeerSpki = peerB
            session.emitEvent(ControlEvent.Connected(peerIdB, sessionIdB, isLocalLeader = false))
            advanceUntilIdle()

            // The parked read-loop dispatch finally runs. Its frame was read under generation 10.
            submitManifestSync(session, generation = STALE_GENERATION)
            advanceUntilIdle()

            assertEquals(
                emptyList(),
                coordinator.remoteEntries.value,
                "a Session A manifest must never become Session B's catalogue",
            )
            scope.coroutineContext[Job]?.cancel()
        }

    /** The half that must keep working: Session B's own manifest still populates Session B. */
    @Test
    fun `Session B's own MANIFEST still populates Session B's catalogue`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher + Job())
            val session = FakeTransferSessionPort(generation = 10L, peerSpki = peerA)
            val coordinator = newCoordinator(scope, session)
            advanceUntilIdle()

            session.currentAuthGeneration = 11L
            session.currentPeerSpki = peerB
            session.emitEvent(ControlEvent.Connected(peerIdB, sessionIdB, isLocalLeader = false))
            advanceUntilIdle()

            submitManifestSync(session, generation = LIVE_GENERATION)
            advanceUntilIdle()

            assertEquals(
                listOf(hashX),
                coordinator.remoteEntries.value.map { it.contentHash },
                "B's own manifest is B's catalogue",
            )
            scope.coroutineContext[Job]?.cancel()
        }

    /**
     * **Requirement B.** `TRANSFER_REQUEST` is the state-changing provider message: it is what makes
     * this device resolve content, open a bulk listener, mint a one-shot token and send an offer
     * (ADR-023). A Session A request dispatched after Session B is live must reach **none** of that
     * — otherwise old peer A's request is served over the connection whichever peer is here now
     * holds, under that peer's SPKI and that session's generation.
     */
    @Test
    fun `a TRANSFER_REQUEST read under Session A cannot be served to Session B's peer`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher + Job())
            val session = FakeTransferSessionPort(generation = 10L, peerSpki = peerA)
            val contentResolver = GatedContentResolver()
            val bulkTransport = FakeBulkTransportPort()
            newCoordinator(scope, session, contentResolver, bulkTransport)
            contentResolver.resolution = found()
            advanceUntilIdle()

            session.currentAuthGeneration = 11L
            session.currentPeerSpki = peerB
            session.emitEvent(ControlEvent.Connected(peerIdB, sessionIdB, isLocalLeader = false))
            advanceUntilIdle()

            session.transfer.sink!!.submit(TransferMessage.Request(hashX, transferX), STALE_GENERATION)
            advanceUntilIdle()

            assertEquals(0, contentResolver.resolveCallCount, "a retired session's request resolves nothing")
            assertEquals(0, bulkTransport.ensureListeningCallCount, "and opens no bulk listener")
            assertEquals(emptyList(), bulkTransport.issuedTokenCalls, "and mints no bulk token")
            assertEquals(emptyList(), session.transfer.sent, "and sends no offer")
            scope.coroutineContext[Job]?.cancel()
        }

    /** And Session B's own `TRANSFER_REQUEST` is still served, through the same path. */
    @Test
    fun `Session B's own TRANSFER_REQUEST is still served`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher + Job())
            val session = FakeTransferSessionPort(generation = 10L, peerSpki = peerA)
            val contentResolver = GatedContentResolver()
            val bulkTransport = FakeBulkTransportPort()
            newCoordinator(scope, session, contentResolver, bulkTransport)
            contentResolver.resolution = found()
            advanceUntilIdle()

            session.currentAuthGeneration = 11L
            session.currentPeerSpki = peerB
            session.emitEvent(ControlEvent.Connected(peerIdB, sessionIdB, isLocalLeader = false))
            advanceUntilIdle()

            session.transfer.sink!!.submit(TransferMessage.Request(hashX, transferX), LIVE_GENERATION)
            advanceUntilIdle()

            assertEquals(1, contentResolver.resolveCallCount, "B's own request is resolved")
            assertEquals(1, bulkTransport.issuedTokenCalls.size, "and gets a token for B's generation")
            assertEquals(11L, bulkTransport.issuedTokenCalls.single().second)
            scope.coroutineContext[Job]?.cancel()
        }

    /**
     * The case `currentAuthGeneration` alone could never have refused, and the reason ADR-025 §1
     * compares against `liveAuthenticatedGeneration` instead: **between a link loss and the next
     * authenticated session there is no live owner at all**, but `currentAuthGeneration` keeps
     * reporting the last number it assigned, so a stale frame carrying that very number matched it.
     */
    @Test
    fun `a MANIFEST read under Session A cannot be applied after the link has gone`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher + Job())
            val session = FakeTransferSessionPort(generation = 10L, peerSpki = peerA)
            val coordinator = newCoordinator(scope, session)
            advanceUntilIdle()

            // The link drops. No new session yet — `currentAuthGeneration` is still 10.
            session.linkDown = true
            session.emitEvent(ControlEvent.LinkLost(LinkLossReason.NETWORK))
            advanceUntilIdle()

            submitManifestSync(session, generation = LIVE_GENERATION_BEFORE_LOSS)
            advanceUntilIdle()

            assertEquals(
                emptyList(),
                coordinator.remoteEntries.value,
                "a session that has ended has no catalogue to repopulate",
            )
            scope.coroutineContext[Job]?.cancel()
        }

    // --- harness ----------------------------------------------------------------------------------

    private fun newCoordinator(
        scope: CoroutineScope,
        session: FakeTransferSessionPort,
        contentResolver: ContentResolverPort = GatedContentResolver(),
        bulkTransport: BulkTransportPort = FakeBulkTransportPort(),
    ): SharedLibraryCoordinator {
        val root = createTempDirectory("ridelink-provenance-test").toFile()
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
            nextManifestId = { ManifestId(MANIFEST_ID) },
        )
    }

    /** One complete PROTOCOL §8.1 sync — `BEGIN`, one `PAGE` with one entry, `END` — all tagged [generation]. */
    private fun submitManifestSync(
        session: FakeTransferSessionPort,
        generation: Long,
    ) {
        val sink = session.manifest.sink!!
        val manifestId = ManifestId(MANIFEST_ID)
        sink.submit(
            ManifestMessage.Begin(
                manifestId = manifestId,
                kind = ManifestKind.FULL,
                manifestRevision = 1,
                baseRevision = null,
                totalEntries = 1,
                totalRemoved = 0,
                pageCount = 1,
                digestAlg = "ridelink-manifest-v1",
            ),
            generation,
        )
        sink.submit(
            ManifestMessage.Page(
                manifestId = manifestId,
                manifestRevision = 1,
                pageIndex = 0,
                entries = listOf(entry()),
                removed = emptyList(),
            ),
            generation,
        )
        sink.submit(
            ManifestMessage.End(
                manifestId = manifestId,
                manifestRevision = 1,
                pageCount = 1,
                totalEntries = 1,
                totalRemoved = 0,
                digest = ManifestPaging.digest(listOf(ManifestPaging.clampEntry(entry())), emptyList()),
            ),
            generation,
        )
    }

    private fun entry() =
        ManifestEntry(
            contentHash = hashX,
            quickId = QuickId("sha256:" + "2".repeat(64)),
            workKey = "a|t",
            title = "t",
            artist = "a",
            album = "al",
            durationMs = 1_000,
            codec = "mp3",
            bitrateKbps = 128,
            sizeBytes = 128,
            filename = "t.mp3",
            hasArtwork = false,
        )

    private fun found(sizeBytes: Long = 128L): ContentResolution.Found =
        ContentResolution.Found({ ByteArrayInputStream(ByteArray(0)) }, sizeBytes)

    private companion object {
        const val MANIFEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV"

        /** Session A's number, on a manager whose live session is now Session B (11). */
        const val STALE_GENERATION = 10L
        const val LIVE_GENERATION = 11L

        /** Session A's number, on a manager whose link has gone and which has no live session at all. */
        const val LIVE_GENERATION_BEFORE_LOSS = 10L
    }
}
