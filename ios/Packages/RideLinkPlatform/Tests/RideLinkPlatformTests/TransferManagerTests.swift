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
            currentGeneration: { generation }, source: source)
        let fetchResult = await client.fetch(
            host: "127.0.0.1", port: port, token: token, expectedPeerSpki: alice.identitySpkiSha256,
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
            transferId: transferId, expectedPeerSpki: bob.identitySpkiSha256, currentGeneration: { 1 }, source: source)
        let fetchResult = await client.fetch(
            host: "127.0.0.1", port: port, token: token, expectedPeerSpki: alice.identitySpkiSha256,
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
            transferId: transferId, expectedPeerSpki: bob.identitySpkiSha256, currentGeneration: { 1 }, source: source)
        let fetchResult = await client.fetch(
            host: "127.0.0.1", port: port, token: wrongToken, expectedPeerSpki: alice.identitySpkiSha256,
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
            transferId: transferId, expectedPeerSpki: bob.identitySpkiSha256, currentGeneration: { 2 }, source: source)
        let fetchResult = await client.fetch(
            host: "127.0.0.1", port: port, token: staleToken, expectedPeerSpki: alice.identitySpkiSha256,
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
        let source = ArrayChunkSource(pieces)
        let sink = RecordingChunkSink()

        async let serveTask = server.serve(
            transferId: transferId, expectedPeerSpki: bob.identitySpkiSha256,
            currentGeneration: { generation }, source: source)
        let fetchResult = await client.fetch(
            host: "127.0.0.1", port: port, token: token, expectedPeerSpki: alice.identitySpkiSha256,
            expectedChunkCount: Int64(pieces.count), sink: sink)
        let serveResult = await serveTask

        XCTAssertEqual(.ok, fetchResult)
        XCTAssertEqual(.ok, serveResult)

        let received = await sink.received
        XCTAssertEqual(pieces.count, received.count)
        let reassembled = received.reduce(into: [UInt8]()) { acc, entry in acc.append(contentsOf: entry.1) }
        XCTAssertEqual(big, reassembled)
    }
}
