import XCTest
@testable import RideLinkCore
@testable import RideLinkPlatform

/// A peer that completes TLS and then says nothing used to park the `HELLO`/`HELLO_ACK` receive
/// indefinitely: `waitUntilReady` bounds TCP and TLS only, and the keepalive that detects silence
/// starts after promotion. Both directions over real TLS 1.3 with real identities — the silent side
/// is a genuine TLS endpoint that simply never speaks the control protocol. Mirrors Android's
/// `HelloExchangeDeadlineTest`.
final class HelloExchangeDeadlineTests: XCTestCase {
    /// Also bounds the well-behaved peer's HELLO in the first test, so it leaves a loaded CI runner
    /// room for a cold first HELLO/HELLO_ACK; still far below `testBoundNs`.
    private static let deadlineMs: Int64 = 1_500
    /// Far above the deadline, far below "forever": the pre-fix code never finishes at all.
    private static let testBoundNs: UInt64 = 10_000_000_000

    private func manager(_ peer: TestPeer) -> ControlSessionManager {
        ControlSessionManager(
            localPeerId: peer.peerId,
            channel: peer.channel(),
            trustedPeers: peer.trustedPeers,
            monotonicNowUs: { Int64(DispatchTime.now().uptimeNanoseconds / 1000) },
            nowEpochSeconds: { TestTlsSupport.nowEpochSeconds },
            helloExchangeTimeoutMs: Self.deadlineMs
        )
    }

    func testAnInboundPeerThatNeverSendsHelloIsDisconnectedAndTheListenerStillServesARealPeer() async throws {
        let (sutPeer, fakePeer) = try TestSessions.pairedPeers("9999999999999999", "1111111111111111", aName: "SUT", bName: "fake")
        let sut = manager(sutPeer)
        let port = try await sut.startListening(local: sutPeer.local)

        let silent = try await fakePeer.channel().connect(host: "127.0.0.1", port: port)
        // The SUT must close it; nothing else will ever arrive. The test bounds itself by closing
        // the socket too, and records whether it had to. The closure captures only locals, as
        // production's watchdog does: Swift 6.3's region-isolation checker rejects `Self.` inside a
        // `Task` here.
        let boundNs = Self.testBoundNs
        let bound = Task<Bool, Never> { [silent] in
            try? await Task.sleep(nanoseconds: boundNs)
            guard !Task.isCancelled else { return false }
            silent.close()
            return true
        }
        let read = await silent.readFrame()
        bound.cancel()
        let closedByTest = await bound.value
        guard case .connectionClosed = read else { return XCTFail("expected the SUT to close the connection, read \(read)") }
        XCTAssertFalse(closedByTest, "the SUT never closed a peer that sent no HELLO")
        silent.close()

        let real = try await fakePeer.channel().connect(host: "127.0.0.1", port: port)
        let outcome = try await ControlHandshake.performAsInitiator(
            socket: real, localPeerId: fakePeer.peerId, seqCounter: SeqCounter(),
            monotonicNowUs: { Int64(DispatchTime.now().uptimeNanoseconds / 1000) },
            local: fakePeer.local, trustedPeers: fakePeer.trustedPeers
        )
        guard case .success = outcome else {
            XCTFail("a well-behaved peer must still be served after the silent one: \(outcome)")
            real.close()
            await sut.shutdown()
            return
        }
        real.close()
        await sut.shutdown()
    }

    func testADialledPeerThatNeverAnswersHelloEndsTheAttemptInsteadOfParkingIt() async throws {
        let (sutPeer, fakePeer) = try TestSessions.pairedPeers("9999999999999999", "1111111111111111", aName: "SUT", bName: "fake")
        let listener = try await fakePeer.channel().bind()
        // Accepts TLS and then holds the connection open without ever reading or writing.
        let held = Task { try await listener.accept() }

        let sut = manager(sutPeer)
        let (events, continuation) = AsyncStream<ControlEvent>.makeStream()
        await sut.setOnEvent { continuation.yield($0) }
        await sut.connectTo(host: "127.0.0.1", port: listener.localPort, local: sutPeer.local)

        let lost = Task<Bool, Never> {
            for await event in events {
                if case .linkLost = event { return true }
            }
            return false
        }
        let boundNs = Self.testBoundNs
        let bound = Task { [continuation] in
            try? await Task.sleep(nanoseconds: boundNs)
            continuation.finish()
        }
        let sawLinkLost = await lost.value
        bound.cancel()
        XCTAssertTrue(sawLinkLost, "the dial never ended although the peer never answered HELLO")

        (try? await held.value)?.close()
        listener.close()
        await sut.shutdown()
    }
}
