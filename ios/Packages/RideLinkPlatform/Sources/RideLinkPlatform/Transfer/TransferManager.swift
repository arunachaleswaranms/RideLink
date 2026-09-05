import Foundation
import RideLinkCore

/// ADR-023 — one bulk TLS listener per authenticated **session** (not per transfer), the same
/// identity as the control connection, SPKI-pinned, single-use-token-authorised per transfer, and
/// bounded to one active transfer at a time (brief §20).
///
/// Reuses `TlsControlChannel` wholesale for the bulk connection's TLS setup rather than
/// duplicating it — same mutual TLS 1.3, same accept-then-pin-one-layer-up shape (ADR-007,
/// ADR-012, ADR-017) — and `ControlConnection`'s raw byte I/O (`writeRawBytes`/`readRawBytes`)
/// instead of its JSON envelope framing, which the bulk plane never uses.
///
/// An `actor`, per ARCHITECTURE §9.2 ("`SessionCoordinator`, `ControlChannel` and
/// `TransferManager` are actors"). Where Android's `BulkTransportManager` needs an explicit
/// `Mutex activeTransferMutex` to cap concurrency at one active transfer per session, an actor's
/// own serialized execution already gives that for free: two overlapping calls to `serve`/`fetch`
/// on the same `TransferManager` simply run one after the other, with no separate lock to get
/// wrong.
public actor TransferManager {
    public let tokenTable: BulkTokenTable

    private let tlsChannel: TlsControlChannel
    private var listener: ControlListener?

    public init(tlsChannel: TlsControlChannel, monotonicNowUs: @escaping @Sendable () -> Int64) {
        self.tlsChannel = tlsChannel
        self.tokenTable = BulkTokenTable(monotonicNowUs: monotonicNowUs)
    }

    /// Opens the listener on first need; a later call just returns the already-bound port.
    public func ensureListening() async throws -> UInt16 {
        if let listener { return listener.localPort }
        let bound = try await tlsChannel.bind()
        listener = bound
        return bound.localPort
    }

    public func issueToken(transferId: TransferId, generation: Int64) async -> String {
        await tokenTable.issue(transferId: transferId, generation: generation)
    }

    /// Call on every fresh authentication (ADR-023 §3) — sweeps tokens from any earlier generation.
    public func onNewGeneration(_ generation: Int64) async {
        await tokenTable.sweepBelow(generation)
    }

    /// ADR-023 §1: the listener never outlives the session that opened it.
    public func close() async {
        listener?.close()
        listener = nil
        await tokenTable.clear()
    }

    /// Provider side: accept exactly one bulk connection, verify its SPKI and single-use token,
    /// then stream `source`'s chunks to it. SPKI is checked **before** the token is even read
    /// (ADR-023 §4 — two independent checks, neither standing in for the other).
    public func serve(
        transferId: TransferId,
        expectedPeerSpki: SpkiHash,
        currentGeneration: @Sendable () async -> Int64,
        source: any ChunkSource
    ) async -> BulkServeOutcome {
        guard let listener else { return .ioError }
        guard let socket = try? await listener.accept() else { return .ioError }
        defer { socket.close() }

        guard let peerSpki = socket.security?.peerIdentitySpkiSha256, peerSpki == expectedPeerSpki else {
            return .notAuthorized
        }
        guard let tokenBytes = await readExactly(socket, count: Self.tokenBytes) else {
            return .connectionLost
        }
        let presented = Self.hexEncode(tokenBytes)
        guard await tokenTable.validateAndConsume(
            transferId: transferId, presentedToken: presented, currentGeneration: currentGeneration()
        ) else {
            return .notAuthorized
        }

        var index: Int64 = 0
        while let chunk = await source.nextChunk() {
            do {
                try await socket.writeRawBytes(BulkFraming.encodeFrame(chunkIndex: UInt32(truncatingIfNeeded: index), payload: chunk))
            } catch {
                return .ioError
            }
            index += 1
        }
        return .ok
    }

    /// Requester side: dial the provider's bulk port, present the token, stream chunks into `sink`.
    public func fetch(
        host: String,
        port: UInt16,
        token: String,
        expectedPeerSpki: SpkiHash,
        expectedChunkCount: Int64,
        sink: any ChunkSink
    ) async -> BulkFetchOutcome {
        guard let socket = try? await tlsChannel.connect(host: host, port: port) else {
            return .connectionLost
        }
        defer { socket.close() }

        guard let peerSpki = socket.security?.peerIdentitySpkiSha256, peerSpki == expectedPeerSpki else {
            return .notAuthorized
        }
        guard let tokenBytes = Self.hexDecode(token) else { return .protocolError }
        do {
            try await socket.writeRawBytes(tokenBytes)
        } catch {
            return .ioError
        }

        var buffer: [UInt8] = []
        var received: Int64 = 0
        let readBuf = Self.readBufferBytes
        while received < expectedChunkCount {
            guard let chunk = await socket.readRawBytes(maxLength: readBuf) else {
                return .connectionLost
            }
            buffer.append(contentsOf: chunk)
            switch BulkFraming.parseAll(buffer) {
            case .parsed(let frames, let leftover):
                for frame in frames {
                    await sink.onChunk(index: Int64(frame.chunkIndex), bytes: frame.payload)
                    received += 1
                }
                buffer = leftover
            case .incomplete:
                continue
            case .invalid:
                return .protocolError
            }
        }
        return .ok
    }

    private func readExactly(_ socket: ControlConnection, count: Int) async -> [UInt8]? {
        var out: [UInt8] = []
        out.reserveCapacity(count)
        while out.count < count {
            guard let chunk = await socket.readRawBytes(maxLength: count - out.count) else { return nil }
            out.append(contentsOf: chunk)
        }
        return out
    }

    private static func hexEncode(_ bytes: [UInt8]) -> String {
        bytes.map { String(format: "%02x", $0) }.joined()
    }

    private static func hexDecode(_ s: String) -> [UInt8]? {
        guard s.count.isMultiple(of: 2) else { return nil }
        var bytes: [UInt8] = []
        bytes.reserveCapacity(s.count / 2)
        var index = s.startIndex
        while index < s.endIndex {
            let next = s.index(index, offsetBy: 2)
            guard let byte = UInt8(s[index..<next], radix: 16) else { return nil }
            bytes.append(byte)
            index = next
        }
        return bytes
    }

    private static let tokenBytes = 32
    private static let readBufferBytes = 16_384
}
