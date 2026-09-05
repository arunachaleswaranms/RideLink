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
/// `TransferManager` are actors").
///
/// **Closure-audit Finding E — corrected.** This type's documentation used to claim an actor's own
/// serialized execution caps concurrency at one active transfer for free, the way Android's
/// `Mutex activeTransferMutex` does. That is not true: Swift actors are *reentrant* across a
/// suspension point (`await`), and `serve`/`fetch` both suspend repeatedly — `listener.accept()`,
/// every socket read/write. A second, unstructured call can run its synchronous prologue while the
/// first is parked at one of those `await`s, so nothing here previously stopped two concurrent
/// bulk operations. [transferInProgress] is the explicit gate that actually enforces brief §20's
/// "one active transfer per session" cap — acquired synchronously, with no `await` between the
/// check and the set, so two overlapping calls cannot both win it.
public actor TransferManager {
    public let tokenTable: BulkTokenTable

    private let tlsChannel: TlsControlChannel
    private var listener: ControlListener?

    /// Finding E's gate — see the type doc comment above. `true` while a `serve`/`fetch` call owns
    /// the one active-transfer slot; a second concurrent call is rejected outright rather than
    /// queued, matching this pass's brief-sanctioned "reject, don't busy-wait" design.
    private var transferInProgress = false

    /// Finding C/D/N: the socket a `serve`/`fetch` call currently holds, so [cancelActive] can force
    /// it closed — unblocking whatever blocking read/accept the active operation is parked in —
    /// rather than merely requesting `Task` cancellation, which a suspended socket call does not
    /// observe until it next unblocks on its own.
    private var activeSocket: ControlConnection?

    public init(tlsChannel: TlsControlChannel, monotonicNowUs: @escaping @Sendable () -> Int64) {
        self.tlsChannel = tlsChannel
        self.tokenTable = BulkTokenTable(monotonicNowUs: monotonicNowUs)
    }

    private func acquireTransferSlot() -> Bool {
        guard !transferInProgress else { return false }
        transferInProgress = true
        return true
    }

    private func releaseTransferSlot() {
        transferInProgress = false
    }

    /// Closure-audit Finding C/D/N: forcibly unblocks and terminates whatever `serve`/`fetch` call
    /// is currently in flight, if any — a user cancellation, a session/link loss, or a peer's
    /// `TRANSFER_CANCEL` for the transfer this manager is actively serving/fetching. Closing the
    /// socket, not merely requesting `Task` cancellation, is what actually unblocks a blocking
    /// `accept()`/read/write. Idempotent and safe to call when nothing is active.
    public func cancelActive() {
        activeSocket?.close()
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

    /// Finding M: see `BulkTokenTable.tryIssue` — `nil` if `transferId` already has a live,
    /// unconsumed token, rather than silently invalidating it.
    public func tryIssueToken(transferId: TransferId, generation: Int64) async -> String? {
        await tokenTable.tryIssue(transferId: transferId, generation: generation)
    }

    /// Call on every fresh authentication (ADR-023 §3) — sweeps tokens from any earlier generation.
    public func onNewGeneration(_ generation: Int64) async {
        await tokenTable.sweepBelow(generation)
    }

    /// ADR-023 §1: the listener never outlives the session that opened it.
    public func close() async {
        listener?.close()
        listener = nil
        cancelActive()
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
        guard acquireTransferSlot() else { return .ioError } // Finding E: one active transfer at a time
        defer { releaseTransferSlot() }
        guard let listener else { return .ioError }
        guard let socket = try? await listener.accept() else { return .ioError }
        activeSocket = socket
        defer {
            socket.close()
            if activeSocket === socket { activeSocket = nil }
        }

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
        guard acquireTransferSlot() else { return .connectionLost } // Finding E: one active transfer at a time
        defer { releaseTransferSlot() }
        guard let socket = try? await tlsChannel.connect(host: host, port: port) else {
            return .connectionLost
        }
        activeSocket = socket
        defer {
            socket.close()
            if activeSocket === socket { activeSocket = nil }
        }

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
                    // Closure-audit Finding K: PROTOCOL §8.2's explicit chunk_index only means
                    // something if it is checked. Reject anything but the exact expected next
                    // index — duplicate, skipped, out-of-order, or a frame beyond the offer's own
                    // declared chunk_count — rather than merely counting frames and trusting the
                    // final whole-file hash to catch it.
                    guard received < expectedChunkCount, Int64(frame.chunkIndex) == received else {
                        return .protocolError
                    }
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
        // Finding K (section 12): satisfying `expectedChunkCount` is not by itself proof that
        // nothing more was sent. `buffer` here is whatever `BulkFraming` could not yet fully parse
        // when the loop above stopped reading — a non-empty leftover is the start of an extra
        // frame already received but never counted. A well-behaved provider (`serve`) closes its
        // socket immediately after its last chunk, so one more read is expected to see EOF;
        // anything else — more bytes, not a clean close — means the provider sent past its own
        // declared chunk_count.
        guard buffer.isEmpty else { return .protocolError }
        if let trailing = await socket.readRawBytes(maxLength: readBuf), !trailing.isEmpty {
            return .protocolError
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
