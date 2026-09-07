import Foundation
import RideLinkCore
import XCTest
@testable import RideLinkPlatform

/// The bulk transport (ADR-023), end to end over **real loopback TCP with a real TLS 1.3
/// handshake** — same discipline as `TlsControlChannelTests`: what a bulk connection actually does
/// is what a laptop test must prove, not what the design doc says it should do.
///
/// The Kotlin mirror is `com.ridelink.network.transfer.BulkTransportManagerTest`.
final class TransferManagerTests: XCTestCase {
    private func manager(_ identity: DeviceIdentity) -> TransferManager {
        TransferManager(
            tlsChannel: TestTlsSupport.channel(identity),
            monotonicNowUs: { Int64(DispatchTime.now().uptimeNanoseconds / 1000) }
        )
    }

    private actor ArrayChunkSource: ChunkSource {
        private var pieces: [[UInt8]]
        private var index = 0

        init(_ pieces: [[UInt8]]) { self.pieces = pieces }

        func nextChunk() async -> [UInt8]? {
            guard index < pieces.count else { return nil }
            let chunk = pieces[index]
            index += 1
            return chunk
        }
    }

    private actor RecordingChunkSink: ChunkSink {
        private var log: [(Int64, [UInt8])] = []

        var received: [(Int64, [UInt8])] { log }

        func onChunk(index: Int64, bytes: [UInt8]) async {
            log.append((index, bytes))
        }
    }

    func testHappyPathTransfersEveryChunkInOrder() async throws {
        let alice = try TestTlsSupport.freshIdentity()
        let bob = try TestTlsSupport.freshIdentity()
        let server = manager(alice)
        let client = manager(bob)

        let port = try await server.ensureListening()
        let transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5C")
        let generation: Int64 = 1
        let token = await server.issueToken(transferId: transferId, generation: generation)

        let chunk0 = (0..<100).map { UInt8($0) }
        let chunk1 = (0..<200).map { UInt8(($0 * 3) & 0xFF) }
        let source = ArrayChunkSource([chunk0, chunk1])
        let sink = RecordingChunkSink()

        async let serveTask = server.serve(
            transferId: transferId, expectedPeerSpki: bob.identitySpkiSha256,
            currentGeneration: { generation }, expectedChunkCount: 2, source: source)
        let fetchResult = await client.fetch(
            transferId: transferId, host: "127.0.0.1", port: port, token: token, expectedPeerSpki: alice.identitySpkiSha256,
            expectedChunkCount: 2, sink: sink)
        let serveResult = await serveTask

        XCTAssertEqual(.ok, fetchResult)
        XCTAssertEqual(.ok, serveResult)

        let received = await sink.received
        XCTAssertEqual(2, received.count)
        XCTAssertEqual(0, received[0].0)
        XCTAssertEqual(chunk0, received[0].1)
        XCTAssertEqual(1, received[1].0)
        XCTAssertEqual(chunk1, received[1].1)
    }

    func testClientRejectsAProviderPresentingTheWrongSpki() async throws {
        let alice = try TestTlsSupport.freshIdentity()
        let bob = try TestTlsSupport.freshIdentity()
        let mallory = try TestTlsSupport.freshIdentity() // a third identity, never the expected peer
        let server = manager(mallory)
        let client = manager(bob)

        let port = try await server.ensureListening()
        let transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5D")
        let token = await server.issueToken(transferId: transferId, generation: 1)
        let source = ArrayChunkSource([[UInt8](repeating: 0, count: 10)])

        async let serveTask = server.serve(
            transferId: transferId, expectedPeerSpki: bob.identitySpkiSha256, currentGeneration: { 1 }, expectedChunkCount: 1, source: source)
        let fetchResult = await client.fetch(
            transferId: transferId, host: "127.0.0.1", port: port, token: token, expectedPeerSpki: alice.identitySpkiSha256,
            expectedChunkCount: 1, sink: RecordingChunkSink())
        _ = await serveTask

        XCTAssertEqual(.notAuthorized, fetchResult)
    }

    func testServerRejectsAConnectionWhoseTokenDoesNotMatch() async throws {
        let alice = try TestTlsSupport.freshIdentity()
        let bob = try TestTlsSupport.freshIdentity()
        let server = manager(alice)
        let client = manager(bob)

        let port = try await server.ensureListening()
        let transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5E")
        _ = await server.issueToken(transferId: transferId, generation: 1) // real token minted, client never learns it
        let wrongToken = String(repeating: "ab", count: 32)
        let source = ArrayChunkSource([[UInt8](repeating: 0, count: 10)])

        async let serveTask = server.serve(
            transferId: transferId, expectedPeerSpki: bob.identitySpkiSha256, currentGeneration: { 1 }, expectedChunkCount: 1, source: source)
        let fetchResult = await client.fetch(
            transferId: transferId, host: "127.0.0.1", port: port, token: wrongToken, expectedPeerSpki: alice.identitySpkiSha256,
            expectedChunkCount: 1, sink: RecordingChunkSink())
        let serveResult = await serveTask

        // The client's connection succeeds at the TLS/SPKI layer and it dutifully sends the wrong
        // token; the server closes without ever streaming a chunk, so the client's read loop sees
        // EOF before satisfying expectedChunkCount.
        XCTAssertEqual(.connectionLost, fetchResult)
        XCTAssertEqual(.notAuthorized, serveResult)
    }

    func testATokenFromASupersededGenerationIsRejected() async throws {
        let alice = try TestTlsSupport.freshIdentity()
        let bob = try TestTlsSupport.freshIdentity()
        let server = manager(alice)
        let client = manager(bob)

        let port = try await server.ensureListening()
        let transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5F")
        let staleToken = await server.issueToken(transferId: transferId, generation: 1) // minted under generation 1
        await server.onNewGeneration(2) // a reconnect re-authenticates: generation moves to 2
        let source = ArrayChunkSource([[UInt8](repeating: 0, count: 10)])

        async let serveTask = server.serve(
            transferId: transferId, expectedPeerSpki: bob.identitySpkiSha256, currentGeneration: { 2 }, expectedChunkCount: 1, source: source)
        let fetchResult = await client.fetch(
            transferId: transferId, host: "127.0.0.1", port: port, token: staleToken, expectedPeerSpki: alice.identitySpkiSha256,
            expectedChunkCount: 1, sink: RecordingChunkSink())
        let serveResult = await serveTask

        XCTAssertEqual(.connectionLost, fetchResult)
        XCTAssertEqual(.notAuthorized, serveResult)
    }

    func testAMultiChunkFileLargerThanOneReadBufferStillReassemblesCorrectlyInOrder() async throws {
        let alice = try TestTlsSupport.freshIdentity()
        let bob = try TestTlsSupport.freshIdentity()
        let server = manager(alice)
        let client = manager(bob)

        let port = try await server.ensureListening()
        let transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5G")
        let generation: Int64 = 1
        let token = await server.issueToken(transferId: transferId, generation: generation)

        // A file bigger than TransferManager's internal 16 KiB read buffer, split into chunks at
        // the RLB1 payload bound (64 KiB) exactly as a real disk-backed chunker would — forcing
        // several reads-and-reassemble cycles through the same code path a real large file would.
        let big = (0..<200_000).map { UInt8($0 % 251) }
        var pieces: [[UInt8]] = []
        var offset = 0
        while offset < big.count {
            let end = min(offset + BulkFraming.maxChunkPayloadBytes, big.count)
            pieces.append(Array(big[offset..<end]))
            offset = end
        }
        // Read out before `pieces` is sent into the actor below — referencing it again afterwards
        // is what Swift 6's region isolation (correctly) rejects.
        let chunkCount = Int64(pieces.count)
        let source = ArrayChunkSource(pieces)
        let sink = RecordingChunkSink()

        async let serveTask = server.serve(
            transferId: transferId, expectedPeerSpki: bob.identitySpkiSha256,
            currentGeneration: { generation }, expectedChunkCount: chunkCount, source: source)
        let fetchResult = await client.fetch(
            transferId: transferId, host: "127.0.0.1", port: port, token: token, expectedPeerSpki: alice.identitySpkiSha256,
            expectedChunkCount: chunkCount, sink: sink)
        let serveResult = await serveTask

        XCTAssertEqual(.ok, fetchResult)
        XCTAssertEqual(.ok, serveResult)

        let received = await sink.received
        XCTAssertEqual(pieces.count, received.count)
        let reassembled = received.reduce(into: [UInt8]()) { acc, entry in acc.append(contentsOf: entry.1) }
        XCTAssertEqual(big, reassembled)
    }

    // MARK: - Closure-audit Finding E: actor reentrancy does not by itself cap concurrency

    func testASecondConcurrentServeCallIsRejectedRatherThanQueued() async throws {
        let alice = try TestTlsSupport.freshIdentity()
        let bob = try TestTlsSupport.freshIdentity()
        let server = manager(alice)
        let client = manager(bob)

        let port = try await server.ensureListening()
        let transferId1 = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5H")
        let transferId2 = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5K")
        let generation: Int64 = 1
        let token1 = await server.issueToken(transferId: transferId1, generation: generation)
        _ = await server.issueToken(transferId: transferId2, generation: generation)

        // Launched as a Task so it can suspend inside `listener.accept()` while nothing has
        // connected yet — the exact reentrancy window Finding E identified: the doc comment this
        // pass corrected claimed an actor's own serialized execution already prevented a second
        // overlapping call, which is false across a suspension point.
        async let firstServe: BulkServeOutcome = server.serve(
            transferId: transferId1, expectedPeerSpki: bob.identitySpkiSha256,
            currentGeneration: { generation }, expectedChunkCount: 1, source: ArrayChunkSource([[UInt8](repeating: 0, count: 10)]))

        // Give the first call a moment to actually reach its `accept()` suspension point.
        try await Task.sleep(nanoseconds: 200_000_000)

        let secondOutcome = await server.serve(
            transferId: transferId2, expectedPeerSpki: bob.identitySpkiSha256,
            currentGeneration: { generation }, expectedChunkCount: 1, source: ArrayChunkSource([[UInt8](repeating: 0, count: 10)]))
        XCTAssertEqual(.ioError, secondOutcome, "a second concurrent serve() must be rejected, not queued behind the first")

        // Let the first one complete normally, proving the gate does not wedge the real operation.
        let fetchResult = await client.fetch(
            transferId: transferId1, host: "127.0.0.1", port: port, token: token1, expectedPeerSpki: alice.identitySpkiSha256,
            expectedChunkCount: 1, sink: RecordingChunkSink())
        let firstOutcome = await firstServe
        XCTAssertEqual(.ok, fetchResult)
        XCTAssertEqual(.ok, firstOutcome)
    }

    // MARK: - Closure-audit Findings C/D/N: cancelActive force-closes a stuck in-flight operation

    func testCancelActiveUnblocksAFetchGenuinelyStuckWaitingForChunksThatWillNeverArrive() async throws {
        let alice = try TestTlsSupport.freshIdentity()
        let bob = try TestTlsSupport.freshIdentity()
        let server = manager(alice)
        let client = manager(bob)

        let port = try await server.ensureListening()
        let transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5J")
        let generation: Int64 = 1
        let token = await server.issueToken(transferId: transferId, generation: generation)
        // The server sends one chunk, then hangs (never sends chunk 2, never closes) — the socket
        // stays genuinely open with the client's read loop blocked in a real blocking socket read,
        // exactly the state a user-cancelled or session-lost transfer leaves behind if nothing ever
        // force-closes the connection.
        let hangingSource = HangingAfterFirstChunkSource(firstChunk: [UInt8](repeating: 0, count: 10))

        async let serveResult: BulkServeOutcome = server.serve(
            transferId: transferId, expectedPeerSpki: bob.identitySpkiSha256,
            currentGeneration: { generation }, expectedChunkCount: 5, source: hangingSource)
        async let fetchResult: BulkFetchOutcome = client.fetch(
            transferId: transferId, host: "127.0.0.1", port: port, token: token, expectedPeerSpki: alice.identitySpkiSha256,
            expectedChunkCount: 5, sink: RecordingChunkSink())

        // Give the real loopback connection time to actually deliver the one chunk the server does
        // send, so the client is genuinely parked waiting for more.
        try await Task.sleep(nanoseconds: 300_000_000)
        await client.cancelActive(transferId: transferId)

        let outcome = await fetchResult
        XCTAssertTrue(
            outcome == .connectionLost || outcome == .ioError,
            "a force-closed fetch must return promptly with a failure outcome, never hang"
        )
        await hangingSource.release()
        _ = await serveResult
    }

    /// ADR-023 Amendment A4 Finding T: `chunk_count` in a `TRANSFER_OFFER` is a promise (PROTOCOL
    /// §8.2), and the requester enforces it — a frame past the declared count is a
    /// `.protocolError`. The provider must therefore refuse to emit one at all, so a `ChunkSource`
    /// that outruns its own declared count (a short-reading handle, or a local file that grew
    /// between the size check and the open) fails as this side's own `.ioError` rather than as the
    /// peer's protocol violation. The Kotlin mirror is
    /// `BulkTransportManagerTest.provider refuses to write more frames than the chunk_count it declared`.
    func testProviderRefusesToWriteMoreFramesThanTheChunkCountItDeclared() async throws {
        let alice = try TestTlsSupport.freshIdentity()
        let bob = try TestTlsSupport.freshIdentity()
        let server = manager(alice)
        let client = manager(bob)

        let port = try await server.ensureListening()
        let transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5K")
        let generation: Int64 = 1
        let token = await server.issueToken(transferId: transferId, generation: generation)
        // Four frames available, but the offer promised two.
        let source = ArrayChunkSource(Array(repeating: [UInt8](repeating: 0, count: 10), count: 4))
        let sink = RecordingChunkSink()

        async let serveResult: BulkServeOutcome = server.serve(
            transferId: transferId, expectedPeerSpki: bob.identitySpkiSha256,
            currentGeneration: { generation }, expectedChunkCount: 2, source: source)
        let fetchResult = await client.fetch(
            transferId: transferId, host: "127.0.0.1", port: port, token: token,
            expectedPeerSpki: alice.identitySpkiSha256, expectedChunkCount: 2, sink: sink)

        let serveOutcome = await serveResult
        let receivedIndices = await sink.received.map(\.0)
        XCTAssertEqual(.ioError, serveOutcome, "the provider must stop itself, not be stopped by the peer")
        // The requester got exactly the two frames it was promised. It then expects a clean
        // provider close; the cap above makes `serve` return (closing the socket) rather than write
        // a third frame, so this is a clean EOF, not a trailing byte.
        XCTAssertEqual([0, 1], receivedIndices)
        XCTAssertEqual(.ok, fetchResult)

        await server.close()
        await client.close()
    }

    func testCancelActiveIsASafeNoOpWhenNothingIsActive() async throws {
        let alice = try TestTlsSupport.freshIdentity()
        let server = manager(alice)
        let transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5N")
        await server.cancelActive(transferId: transferId)
        await server.cancelActive(transferId: transferId)
    }

    // MARK: - ADR-023 Amendment A3: operation-aware cancellation and awaited teardown ordering

    /// The exact race the closure audit found (spec section 5/18): a coordinator schedules
    /// `Task { await transport.cancelActive(transferId: A) }` and does *not* await it before moving
    /// on — here, standing in for that, the cancel for A is issued deliberately late, well after A
    /// has already finished a completely ordinary transfer and a *second*, genuinely in-flight
    /// transfer B has taken over the one shared socket. Before this amendment, a manager-wide
    /// `cancelActive()` would have blindly closed whatever `activeSocket` was current — B's — the
    /// moment this call finally ran. `cancelActive(transferId:)` must instead see that
    /// `activeTransferId` no longer names A and do nothing.
    func testADelayedCancelForAFinishedTransferCannotCloseALaterOnesSocket() async throws {
        let alice = try TestTlsSupport.freshIdentity()
        let bob = try TestTlsSupport.freshIdentity()
        let server = manager(alice)
        let client = manager(bob)

        let port = try await server.ensureListening()
        let transferIdA = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5T")
        let transferIdB = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5W")
        let generation: Int64 = 1
        let tokenA = await server.issueToken(transferId: transferIdA, generation: generation)

        // A completes an entirely ordinary transfer — its own cleanup clears activeTransferId/
        // activeSocket, exactly like a legitimate completion, not a cancellation.
        async let serveA: BulkServeOutcome = server.serve(
            transferId: transferIdA, expectedPeerSpki: bob.identitySpkiSha256,
            currentGeneration: { generation }, expectedChunkCount: 1, source: ArrayChunkSource([[UInt8](repeating: 0, count: 10)]))
        let fetchA = await client.fetch(
            transferId: transferIdA, host: "127.0.0.1", port: port, token: tokenA, expectedPeerSpki: alice.identitySpkiSha256,
            expectedChunkCount: 1, sink: RecordingChunkSink())
        _ = await serveA
        XCTAssertEqual(.ok, fetchA)

        // B starts a second, later transfer over the same manager — genuinely mid-flight, its own
        // socket really open and parked waiting for more chunks.
        let tokenB = await server.issueToken(transferId: transferIdB, generation: generation)
        let hangingSource = HangingThenOneMoreChunkSource(
            firstChunk: [UInt8](repeating: 0, count: 10), secondChunk: [UInt8](repeating: 1, count: 10)
        )
        async let serveB: BulkServeOutcome = server.serve(
            transferId: transferIdB, expectedPeerSpki: bob.identitySpkiSha256,
            currentGeneration: { generation }, expectedChunkCount: 5, source: hangingSource)
        async let fetchB: BulkFetchOutcome = client.fetch(
            transferId: transferIdB, host: "127.0.0.1", port: port, token: tokenB, expectedPeerSpki: alice.identitySpkiSha256,
            expectedChunkCount: 2, sink: RecordingChunkSink())
        // Deterministically wait for B's socket to actually be accepted (activeTransferId really
        // set to B), rather than guessing with a fixed sleep.
        while await server.activeTransferIdForTesting != transferIdB {
            try await Task.sleep(nanoseconds: 10_000_000)
        }

        // The stale cancellation meant for A — long finished — arrives only now. It must be a
        // no-op: `activeTransferId` currently names B, not A.
        await server.cancelActive(transferId: transferIdA)

        await hangingSource.release()
        let outcomeB = await serveB
        let fetchOutcomeB = await fetchB
        XCTAssertEqual(.ok, outcomeB, "B's transfer must complete normally — the stale cancel(A) must not have touched it")
        XCTAssertEqual(.ok, fetchOutcomeB)
    }

    /// The precondition `SharedLibraryCoordinator.onSessionBoundary`'s Amendment A3 fix depends on:
    /// `close()` must have genuinely finished all teardown — old listener gone, old socket gone —
    /// by the time its `await` returns, not merely have scheduled that work. (The coordinator now
    /// `await`s this call before invalidating `BulkOperationGate` or letting any new-session activity
    /// begin, precisely so a delayed old-session teardown can never race a new session's listener.)
    func testCloseCompletesFullyBeforeReturningSoANewListenerWorksImmediatelyAfter() async throws {
        let alice = try TestTlsSupport.freshIdentity()
        let bob = try TestTlsSupport.freshIdentity()
        let server = manager(alice)

        let oldPort = try await server.ensureListening()
        let transferId = TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5V")
        let generation: Int64 = 1
        _ = await server.issueToken(transferId: transferId, generation: generation)
        let hangingSource = HangingAfterFirstChunkSource(firstChunk: [UInt8](repeating: 0, count: 10))
        async let serveResult: BulkServeOutcome = server.serve(
            transferId: transferId, expectedPeerSpki: bob.identitySpkiSha256,
            currentGeneration: { generation }, expectedChunkCount: 5, source: hangingSource)
        try await Task.sleep(nanoseconds: 200_000_000) // let the socket genuinely open

        await server.close()
        await hangingSource.release()
        _ = await serveResult

        // The old listener must genuinely be gone — connecting to its port must fail outright.
        let staleConnection = try? await TestTlsSupport.channel(bob).connect(host: "127.0.0.1", port: oldPort)
        XCTAssertNil(staleConnection, "the old listener must not still be accepting after close() returns")

        // A fresh listener opens cleanly right after — exactly what a new session's own
        // `ensureListening()` does immediately once the now-`await`ed close() has returned.
        let newPort = try await server.ensureListening()
        XCTAssertNotEqual(oldPort, newPort)
    }

    // MARK: - Closure-audit Finding K: frame ordering/count validation

    /// Writes raw, deliberately malformed RLB1 frames directly to a socket — bypassing
    /// `TransferManager.serve`'s own always-sequential `ChunkSource` loop entirely, since that API
    /// has no way to construct an out-of-order/duplicate/extra frame. Consumes and discards the
    /// token bytes exactly like a real provider would, without validating them — this harness is
    /// testing the *requester*'s (`fetch`) framing validation, not the provider's authorization.
    private static func writeRawFrames(_ listener: ControlListener, _ frames: [(UInt32, [UInt8])]) async throws {
        let socket = try await listener.accept()
        var tokenBytes: [UInt8] = []
        while tokenBytes.count < 32 {
            guard let chunk = await socket.readRawBytes(maxLength: 32 - tokenBytes.count) else { break }
            tokenBytes.append(contentsOf: chunk)
        }
        for (index, payload) in frames {
            try await socket.writeRawBytes(BulkFraming.encodeFrame(chunkIndex: index, payload: payload))
        }
        socket.close()
    }

    func testADuplicateChunkIndexIsRejectedAsAProtocolErrorNotMerelyCounted() async throws {
        let alice = try TestTlsSupport.freshIdentity()
        let bob = try TestTlsSupport.freshIdentity()
        let client = manager(bob)
        let rawServer = TestTlsSupport.channel(alice)
        let listener = try await rawServer.bind()
        defer { listener.close() }

        async let serverTask: Void = try Self.writeRawFrames(listener, [(0, [UInt8](repeating: 0, count: 10)), (0, [UInt8](repeating: 0, count: 10))])
        let fetchResult = await client.fetch(
            transferId: TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5P"), host: "127.0.0.1", port: listener.localPort, token: String(repeating: "0", count: 64),
            expectedPeerSpki: alice.identitySpkiSha256, expectedChunkCount: 2, sink: RecordingChunkSink())
        _ = try await serverTask

        XCTAssertEqual(.protocolError, fetchResult)
    }

    func testASkippedChunkIndexIsRejectedAsAProtocolError() async throws {
        let alice = try TestTlsSupport.freshIdentity()
        let bob = try TestTlsSupport.freshIdentity()
        let client = manager(bob)
        let rawServer = TestTlsSupport.channel(alice)
        let listener = try await rawServer.bind()
        defer { listener.close() }

        async let serverTask: Void = try Self.writeRawFrames(listener, [(0, [UInt8](repeating: 0, count: 10)), (2, [UInt8](repeating: 0, count: 10))])
        let fetchResult = await client.fetch(
            transferId: TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5Q"), host: "127.0.0.1", port: listener.localPort, token: String(repeating: "0", count: 64),
            expectedPeerSpki: alice.identitySpkiSha256, expectedChunkCount: 3, sink: RecordingChunkSink())
        _ = try await serverTask

        XCTAssertEqual(.protocolError, fetchResult)
    }

    func testAnOutOfOrderChunkIndexIsRejectedAsAProtocolError() async throws {
        let alice = try TestTlsSupport.freshIdentity()
        let bob = try TestTlsSupport.freshIdentity()
        let client = manager(bob)
        let rawServer = TestTlsSupport.channel(alice)
        let listener = try await rawServer.bind()
        defer { listener.close() }

        async let serverTask: Void = try Self.writeRawFrames(listener, [(1, [UInt8](repeating: 0, count: 10)), (0, [UInt8](repeating: 0, count: 10))])
        let fetchResult = await client.fetch(
            transferId: TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5R"), host: "127.0.0.1", port: listener.localPort, token: String(repeating: "0", count: 64),
            expectedPeerSpki: alice.identitySpkiSha256, expectedChunkCount: 2, sink: RecordingChunkSink())
        _ = try await serverTask

        XCTAssertEqual(.protocolError, fetchResult)
    }

    func testAnExtraFrameBeyondTheOffersDeclaredChunkCountIsRejected() async throws {
        let alice = try TestTlsSupport.freshIdentity()
        let bob = try TestTlsSupport.freshIdentity()
        let client = manager(bob)
        let rawServer = TestTlsSupport.channel(alice)
        let listener = try await rawServer.bind()
        defer { listener.close() }

        // expectedChunkCount below is 1 — this second, in-sequence frame is still one frame too
        // many and must be rejected, not silently accepted because the earlier count was already
        // satisfied by a *different* code path.
        async let serverTask: Void = try Self.writeRawFrames(listener, [(0, [UInt8](repeating: 0, count: 10)), (1, [UInt8](repeating: 0, count: 10))])
        let fetchResult = await client.fetch(
            transferId: TransferId("01J9Z4M3RT8V2W5X7Y9Z1A3B5S"), host: "127.0.0.1", port: listener.localPort, token: String(repeating: "0", count: 64),
            expectedPeerSpki: alice.identitySpkiSha256, expectedChunkCount: 1, sink: RecordingChunkSink())
        _ = try await serverTask

        XCTAssertEqual(.protocolError, fetchResult)
    }
}

/// A `ChunkSource` that yields one chunk, then suspends indefinitely until `release()` is called —
/// modelling a transfer stuck mid-stream so `cancelActive` has something real to unblock.
private actor HangingAfterFirstChunkSource: ChunkSource {
    private let firstChunk: [UInt8]
    private var served = false
    private var continuation: CheckedContinuation<Void, Never>?

    init(firstChunk: [UInt8]) { self.firstChunk = firstChunk }

    func nextChunk() async -> [UInt8]? {
        if !served {
            served = true
            return firstChunk
        }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            self.continuation = continuation
        }
        return nil
    }

    func release() {
        continuation?.resume()
        continuation = nil
    }
}

/// Like [HangingAfterFirstChunkSource], but resumes with one further real chunk after [release]
/// instead of ending the stream — so a caller that wants the transfer to genuinely *succeed* once
/// released (rather than end via the provider closing early) has an outcome-accurate source to
/// pair with a client `expectedChunkCount` of 2.
private actor HangingThenOneMoreChunkSource: ChunkSource {
    private let firstChunk: [UInt8]
    private let secondChunk: [UInt8]
    private var served = 0
    private var continuation: CheckedContinuation<Void, Never>?

    init(firstChunk: [UInt8], secondChunk: [UInt8]) {
        self.firstChunk = firstChunk
        self.secondChunk = secondChunk
    }

    func nextChunk() async -> [UInt8]? {
        if served == 0 {
            served = 1
            return firstChunk
        }
        if served == 1 {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                self.continuation = continuation
            }
            served = 2
            return secondChunk
        }
        return nil
    }

    func release() {
        continuation?.resume()
        continuation = nil
    }
}
