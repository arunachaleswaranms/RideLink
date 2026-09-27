import Darwin
import XCTest
@testable import RideLinkCore
@testable import RideLinkPlatform

/// STATUS §4 problem 110, reproduced first on the physical OnePlus Nord 5 (Phase 9A) and present
/// here by the same shape: one inbound connection whose TLS handshake failed ended the control
/// accept loop for the rest of the session. The listener stayed bound and advertised, so later
/// peers connected at the TCP level and then waited for a TLS answer that never came.
///
/// A failed candidate is that candidate's failure, never the listener's. Real TLS 1.3 with real
/// identities; the failing candidates are the two cheapest things any host on the Wi-Fi can do.
/// Mirrors Android's `InboundHandshakeFailureTest`.
final class InboundHandshakeFailureTests: XCTestCase {
    private static let failedCandidates = 3
    /// Far above a real handshake on loopback; the pre-fix listener never answers at all.
    private static let testBoundSeconds = 10

    private func manager(_ peer: TestPeer) -> ControlSessionManager {
        ControlSessionManager(
            localPeerId: peer.peerId,
            channel: peer.channel(),
            trustedPeers: peer.trustedPeers,
            monotonicNowUs: { Int64(DispatchTime.now().uptimeNanoseconds / 1000) },
            nowEpochSeconds: { TestTlsSupport.nowEpochSeconds }
        )
    }

    func testFailedInboundHandshakesDoNotStopTheListenerFromServingARealPeer() async throws {
        let (sutPeer, fakePeer) = try TestSessions.pairedPeers("9999999999999999", "1111111111111111", aName: "SUT", bName: "fake")
        let sut = manager(sutPeer)
        let port = try await sut.startListening(local: sutPeer.local)

        for _ in 0 ..< Self.failedCandidates {
            // Bytes that are not a ClientHello: the server handshake fails. Waiting for EOF means the
            // next candidate is only accepted after this one has definitely failed.
            let closedBySut = try Self.sendNotTlsAndAwaitClose(port: port, boundSeconds: Self.testBoundSeconds)
            XCTAssertTrue(closedBySut, "a failing candidate was never closed: the listener stopped accepting")
            // EOF in the middle of the server handshake.
            try Self.connectAndHangUp(port: port)
        }

        let real: ControlConnection
        do {
            real = try await fakePeer.channel().connect(host: "127.0.0.1", port: port)
        } catch {
            await sut.shutdown()
            return XCTFail("the listener stopped accepting after a failed inbound handshake: \(error)")
        }
        let outcome = try await ControlHandshake.performAsInitiator(
            socket: real, localPeerId: fakePeer.peerId, seqCounter: SeqCounter(),
            monotonicNowUs: { Int64(DispatchTime.now().uptimeNanoseconds / 1000) },
            local: fakePeer.local, trustedPeers: fakePeer.trustedPeers
        )
        guard case .success = outcome else {
            real.close()
            await sut.shutdown()
            return XCTFail("a well-behaved peer must still be served after the failed ones: \(outcome)")
        }
        real.close()
        await sut.shutdown()
    }

    /// Returns true if the SUT closed the connection (EOF) within the bound, false on timeout.
    private static func sendNotTlsAndAwaitClose(port: UInt16, boundSeconds: Int) throws -> Bool {
        let fd = try connectedSocket(port: port)
        defer { close(fd) }
        var timeout = timeval(tv_sec: boundSeconds, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        let garbage = Array("GET / HTTP/1.1\r\nHost: ridelink\r\n\r\n".utf8)
        _ = garbage.withUnsafeBytes { write(fd, $0.baseAddress, $0.count) }
        var buffer = [UInt8](repeating: 0, count: 512)
        while true {
            let n = buffer.withUnsafeMutableBytes { read(fd, $0.baseAddress, $0.count) }
            if n == 0 { return true } // orderly close by the SUT
            if n < 0 { return errno == ECONNRESET } // a reset is a close too; EAGAIN is the timeout
        }
    }

    private static func connectAndHangUp(port: UInt16) throws {
        close(try connectedSocket(port: port))
    }

    private static func connectedSocket(port: UInt16) throws -> Int32 {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { throw POSIXError(.EIO) }
        var address = sockaddr_in()
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = port.bigEndian
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        let result = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard result == 0 else {
            close(fd)
            throw POSIXError(.ECONNREFUSED)
        }
        return fd
    }
}
