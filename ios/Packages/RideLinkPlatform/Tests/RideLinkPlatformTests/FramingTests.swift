import Foundation
import Network
import XCTest
@testable import RideLinkCore
@testable import RideLinkPlatform

/// PROTOCOL §1 framing over real loopback `Network.framework` sockets: `uint32` BE length
/// prefix, 262144-byte cap, enforced by inspecting the length **before** requesting the payload
/// (this session's brief §8) — proven here by declaring a huge length with no body and
/// confirming `.frameTooLarge` returns promptly instead of hanging.
final class FramingTests: XCTestCase {
    /// Framing is transport-neutral, and these tests are about the `uint32` length prefix and the
    /// 256 KiB cap alone — no handshake, no identity, nothing a TLS session would contribute. The
    /// plaintext fixture (test target only) keeps them fast and keeps what they assert unambiguous.
    private let plaintext = PlaintextControlChannelFixture()

    private func envelope(pad: String) -> Envelope {
        Envelope(
            v: 1, type: "PING", sessionId: "s1", senderId: "0123456789abcdef", msgId: "m1",
            seq: 1, sentAtMonoUs: 1000, payload: ["pad": .string(pad)]
        )
    }

    func testFrameAtExactlyTheCapRoundTrips() async throws {
        let listener = try await plaintext.bind()
        async let acceptedFuture = listener.accept()
        let client = try await plaintext.connect(host: "127.0.0.1", port: listener.localPort)
        let server = try await acceptedFuture

        let base = envelope(pad: "")
        let baseSize = try EnvelopeCodec.encode(base).count
        let padding = String(repeating: "a", count: FrameLimits.maxControlFrameBytes - baseSize)
        let big = envelope(pad: padding)
        let encodedSize = try EnvelopeCodec.encode(big).count
        XCTAssertEqual(encodedSize, FrameLimits.maxControlFrameBytes, "test fixture must land exactly on the cap")

        async let writeTask: Void = client.writeFrame(big)
        let result = await server.readFrame()
        try await writeTask

        guard case .frame = result else {
            XCTFail("expected a decoded frame, got \(result)")
            return
        }
        server.close()
        client.close()
        listener.close()
    }

    func testDeclaredLengthOverTheCapIsRejectedWithoutReadingOrHanging() async throws {
        let listener = try await plaintext.bind()
        async let acceptedFuture = listener.accept()
        let rawClient = try makeRawLoopbackConnection(port: listener.localPort)
        let server = try await acceptedFuture

        var lengthBE = UInt32(FrameLimits.maxControlFrameBytes + 1).bigEndian
        let data = Data(bytes: &lengthBE, count: 4)
        try await rawSend(rawClient, data)

        let result = await withTimeout(seconds: 5) { await server.readFrame() }
        guard case .frameTooLarge(let declared) = result else {
            XCTFail("expected frameTooLarge, got \(String(describing: result))")
            return
        }
        XCTAssertTrue(Int(declared) > FrameLimits.maxControlFrameBytes)

        server.close()
        rawClient.cancel()
        listener.close()
    }

    // MARK: - raw loopback helpers (bypass ControlConnection's own cap-checked writeFrame)

    private func makeRawLoopbackConnection(port: UInt16) throws -> NWConnection {
        guard let nwPort = NWEndpoint.Port(rawValue: port) else { throw ControlTransportError.connectFailed("bad port") }
        let connection = NWConnection(host: "127.0.0.1", port: nwPort, using: .tcp)
        let queue = DispatchQueue(label: "test.raw.loopback")
        connection.start(queue: queue)
        return connection
    }

    private func rawSend(_ connection: NWConnection, _ data: Data) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            // Give the connection a brief moment to reach .ready; loopback is fast enough that a
            // short poll is simpler here than a full state-machine bridge for a test-only helper.
            @Sendable func attempt(_ retriesLeft: Int) {
                if connection.state == .ready {
                    connection.send(content: data, completion: .contentProcessed { error in
                        if let error {
                            continuation.resume(throwing: error)
                        } else {
                            continuation.resume(returning: ())
                        }
                    })
                } else if retriesLeft <= 0 {
                    continuation.resume(throwing: ControlTransportError.notReady)
                } else {
                    DispatchQueue.global().asyncAfter(deadline: .now() + 0.05) { attempt(retriesLeft - 1) }
                }
            }
            attempt(100)
        }
    }

    private func withTimeout<T: Sendable>(seconds: Double, _ operation: @escaping @Sendable () async -> T) async -> T? {
        await withTaskGroup(of: T?.self) { group in
            group.addTask { Optional(await operation()) }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                return nil
            }
            let result = await group.next() ?? nil
            group.cancelAll()
            return result
        }
    }
}
