import Foundation
import RideLinkCore

/// ADR-023 — at most one **live** bulk TLS listener per authenticated session (never one per
/// transfer), the same identity as the control connection, SPKI-pinned, single-use-token-authorised
/// per transfer, and bounded to one active transfer at a time (brief §20). A listener never
/// outlives its own session; Amendment A5 lets a session open a *replacement* within its own life,
/// when cancelling a pending accept ends the current one — see `cancelActive(transferId:)`.
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

    /// Production always wires `TlsControlChannel` here — PROTOCOL §1 and CLAUDE.md rule 14 admit no
    /// plaintext production transport, and `SessionCoordinator` is the one call site. Declared as
    /// the `ControlChannel` protocol (exactly as `ControlSessionManager` already declares its own)
    /// so ADR-023 Amendment A5's bind-versus-close publication race can be driven by a test double
    /// whose `bind()` suspends on demand, rather than by hoping a real TLS bind happens to be slow.
    private let channel: any ControlChannel
    private var listener: ControlListener?

    /// ADR-023 Amendment A5 Finding B — the bulk listener's lifetime, bumped whenever a listener is
    /// torn down (`close()`, or `cancelActive` abandoning a pending accept). `ensureListening()`
    /// reads it before suspending in `bind()` and re-reads it before publishing.
    ///
    /// An actor is **reentrant** across `await`, which is exactly the hole this closes: `close()`
    /// could run entirely inside a suspended `ensureListening()`'s `await channel.bind()`, find
    /// `listener` still nil, return having "torn everything down" — and then the resumed bind would
    /// assign its brand-new listener to `listener`, resurrecting a listener for a session that had
    /// already ended. Actor isolation alone never prevented that; bumping this counter *before*
    /// anything is closed, and re-checking it at the publication point, does.
    private var listenerEpoch: Int64 = 0

    /// Finding E's gate — see the type doc comment above. `true` while a `serve`/`fetch` call owns
    /// the one active-transfer slot; a second concurrent call is rejected outright rather than
    /// queued, matching this pass's brief-sanctioned "reject, don't busy-wait" design.
    private var transferInProgress = false

    /// ADR-023 Amendment A5 — which transfer owns this transport right now, and how far along it is.
    /// A3 tracked the socket and its `transfer_id`, both of which exist only *after*
    /// `accept()`/`connect()` returns, so a `TRANSFER_CANCEL` arriving while `serve` was still
    /// parked in `accept()` had nothing to act on and `cancelActive`'s own guard refused (A4's
    /// Finding W recorded the gap and bounded it with a 30 s timeout rather than closing it).
    /// Naming the owner from the moment the wait begins is what makes an explicit cancellation able
    /// to end that wait, and what keeps a cancellation naming a *different* transfer a no-op.
    private enum Operation {
        /// `serve` is parked in `accept()`; no socket exists yet, and the listener is what a cancel
        /// must break.
        case waitingForAccept(transferId: TransferId)
        /// A real connection is open — the A3 state, unchanged in meaning.
        case connected(transferId: TransferId, socket: ControlConnection)

        var transferId: TransferId {
            switch self {
            case .waitingForAccept(let transferId): return transferId
            case .connected(let transferId, _): return transferId
            }
        }
    }

    /// Finding C/D/N, made phase-aware by A5: the operation a `serve`/`fetch` call currently owns,
    /// so `cancelActive(transferId:)` can force it to end — closing the socket it is parked on, or
    /// the listener it is waiting to accept from — rather than merely requesting `Task`
    /// cancellation, which a suspended socket call does not observe until it next unblocks.
    private var operation: Operation?

    /// ADR-023 Amendment A3 established that cancellation must name the `transfer_id` it means: the
    /// closure-audit found that a caller scheduling `Task { await transport.cancelActive() }` and
    /// then immediately, synchronously, releasing `BulkOperationGate` (so a second operation could
    /// acquire the slot and start a *new* `serve`/`fetch` call) let that stale, merely-*scheduled*
    /// cancellation — once it finally ran on this actor — blindly close whatever socket was current
    /// by then, which could belong to the second, unrelated operation. A5 keeps that guarantee and
    /// widens it to the accept phase, where no socket exists yet: see [Operation].
    ///
    /// Test-only visibility, so a deterministic test can wait for a real `serve`/`fetch` call to
    /// actually reach its socket-accepted point rather than guessing with a fixed sleep.
    /// `internal`, reachable only via `@testable import`.
    var activeTransferIdForTesting: TransferId? {
        guard case .connected(let transferId, _) = operation else { return nil }
        return transferId
    }

    /// Test-only: the transfer parked in `accept()`, if any — the deterministic signal an A5
    /// cancel-before-accept test waits on instead of guessing with a sleep.
    var pendingAcceptTransferIdForTesting: TransferId? {
        guard case .waitingForAccept(let transferId) = operation else { return nil }
        return transferId
    }

    /// Test-only: the port currently published, or `nil` if no listener is published at all.
    var listenerPortForTesting: UInt16? { listener?.localPort }

    public init(channel: any ControlChannel, monotonicNowUs: @escaping @Sendable () -> Int64) {
        self.channel = channel
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

    /// ADR-023 Amendment A3, widened by A5: operation-aware cancellation — terminates the in-flight
    /// operation only if it is still the one `transferId` actually owns. A cancel for an operation
    /// that has already finished and been superseded by a different `transfer_id` is a no-op, never
    /// a way to disturb a newer, unrelated operation.
    ///
    /// Both phases terminate, which is the whole of A5's Finding A:
    /// - **connected** — close the connection. Closing it, rather than merely cancelling a `Task`,
    ///   is what actually unblocks a suspended read/write.
    /// - **waitingForAccept** — there is no connection yet, so the *listener* is what the parked
    ///   `accept()` is waiting on: end its lifetime and close it, which resumes every parked waiter
    ///   with an error immediately. `ensureListening()` binds a fresh listener for the next
    ///   transfer, and the bumped `listenerEpoch` stops any bind suspended right now from
    ///   resurrecting the old one.
    ///
    /// Either way `transferId`'s bulk token is dropped: an offer the peer has just cancelled must
    /// not stay authorised for the remainder of its 30 s TTL (ADR-023 §2).
    ///
    /// Idempotent and safe to call when nothing matching is active.
    public func cancelActive(transferId: TransferId) async {
        guard let operation, operation.transferId == transferId else { return }
        switch operation {
        case .connected(_, let socket): socket.close()
        case .waitingForAccept: endListenerLifetime()
        }
        self.operation = nil
        await tokenTable.remove(transferId: transferId)
    }

    /// Ends the current listener's lifetime: nothing bound under the old epoch may be published
    /// afterwards. `ControlListener.close()` resumes every parked `accept()` waiter with an error,
    /// which is what makes a cancelled pending accept return promptly rather than waiting out
    /// A4's 30 s bound.
    private func endListenerLifetime() {
        listenerEpoch += 1
        listener?.close()
        listener = nil
    }

    /// Opens the listener on first need; a later call just returns the already-bound port.
    ///
    /// Amendment A5 Finding B: `bind()` suspends, and this actor is **reentrant** across that
    /// suspension — `close()` can run to completion inside it. The epoch captured before the bind
    /// and re-checked at publication is what stops the resumed call publishing a listener into a
    /// session that has already been torn down.
    public func ensureListening() async throws -> UInt16 {
        if let listener { return listener.localPort }
        let epochAtStart = listenerEpoch
        let bound = try await channel.bind()
        guard epochAtStart == listenerEpoch else {
            bound.close()
            throw BulkTransportError.listenerLifetimeEnded
        }
        // A reentrant caller in the same lifetime may already have published one; take theirs.
        if let listener {
            bound.close()
            return listener.localPort
        }
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

    /// ADR-023 §1: the listener never outlives the session that opened it. Unconditional — a
    /// session boundary closes whatever is active regardless of which `transfer_id` owns it, unlike
    /// `cancelActive(transferId:)`.
    ///
    /// Amendment A5 Finding B: the epoch bump comes **first**, before anything is closed, so an
    /// `ensureListening()` call already suspended inside `bind()` can never publish its result into
    /// this now-dead lifetime — no matter when it resumes.
    public func close() async {
        // A `.waitingForAccept` operation needs no separate action: the listener this just closed
        // is exactly what its parked accept() is waiting on.
        endListenerLifetime()
        if case .connected(_, let socket) = operation { socket.close() }
        operation = nil
        await tokenTable.clear()
    }

    /// Provider side: accept exactly one bulk connection, verify its SPKI and single-use token,
    /// then stream `source`'s chunks to it. SPKI is checked **before** the token is even read
    /// (ADR-023 §4 — two independent checks, neither standing in for the other).
    ///
    /// `expectedChunkCount` is the `chunk_count` the caller already promised in its
    /// `TRANSFER_OFFER` (closure-audit Amendment A4 Finding T) — this method will never write more
    /// frames than that, mirroring the same bound `fetch` enforces from the receiving side.
    public func serve(
        transferId: TransferId,
        expectedPeerSpki: SpkiHash,
        currentGeneration: @Sendable () async -> Int64,
        expectedChunkCount: Int64,
        source: any ChunkSource
    ) async -> BulkServeOutcome {
        guard acquireTransferSlot() else { return .ioError } // Finding E: one active transfer at a time
        defer { releaseTransferSlot() }
        guard let listener else { return .ioError }
        // Amendment A4 Finding W: bounded by the bulk token's own 30 s TTL (ADR-023 §2). This bound
        // stays, as defence in depth for the cases no cancellation ever arrives for — the peer
        // crashed, the negotiation was simply abandoned, or the TRANSFER_CANCEL never reached us.
        // An explicit cancel no longer waits it out: Amendment A5 ends this accept promptly by
        // closing the listener it is parked on. Without the bound, an abandoned negotiation would
        // hold the single `transferInProgress` gate — and the coordinator's `BulkOperationGate`
        // above it — against every transfer in both directions until the next session boundary.
        // Amendment A5 Finding A: claim the pending accept *before* parking in it, so a
        // TRANSFER_CANCEL naming this transfer has something to act on. Set synchronously, with no
        // `await` between the claim and the accept below.
        operation = .waitingForAccept(transferId: transferId)
        guard let socket = try? await listener.accept(timeoutMs: Self.acceptTimeoutMs) else {
            clearOperation(transferId)
            return .ioError
        }
        // Amendment A5: a cancel can land in the instant between accept() returning a connection
        // and this claim. Promote only if this call still owns the pending accept — otherwise the
        // operation was cancelled (or the session closed) and this connection must not be served.
        // Belt-and-braces with the token removal `cancelActive` already did, which would fail the
        // authorisation below anyway; this makes it structural rather than incidental.
        guard case .waitingForAccept(let pending) = operation, pending == transferId else {
            socket.close()
            return .notAuthorized
        }
        operation = .connected(transferId: transferId, socket: socket)
        defer {
            socket.close()
            clearOperation(transferId)
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
            // Closure-audit Amendment A4 Finding T: the provider already declared `chunk_count` in
            // its TRANSFER_OFFER (PROTOCOL §8.2) — emitting more frames than that is the provider
            // violating its own offer, and the requester's Finding K index check would (correctly)
            // reject the whole transfer. Refuse here instead, so a `source` that outruns its
            // declared count — a short-reading handle, or a local file that grew between the size
            // check and the open — fails as this side's own `.ioError` rather than as the peer's
            // `.protocolError`.
            guard index < expectedChunkCount else { return .ioError }
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
    /// [transferId] is ADR-023 Amendment A3's addition — it is what makes
    /// [cancelActive(transferId:)] operation-aware for the requester role exactly as it already is
    /// for [serve]'s provider role.
    public func fetch(
        transferId: TransferId,
        host: String,
        port: UInt16,
        token: String,
        expectedPeerSpki: SpkiHash,
        expectedChunkCount: Int64,
        sink: any ChunkSink
    ) async -> BulkFetchOutcome {
        guard acquireTransferSlot() else { return .connectionLost } // Finding E: one active transfer at a time
        defer { releaseTransferSlot() }
        guard let socket = try? await channel.connect(host: host, port: port) else {
            return .connectionLost
        }
        operation = .connected(transferId: transferId, socket: socket)
        defer {
            socket.close()
            clearOperation(transferId)
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

    /// Clears the operation slot only if `transferId` still owns it — a late cleanup from an
    /// operation a `cancelActive`/`close` already ended never clears a fresher one.
    private func clearOperation(_ transferId: TransferId) {
        if operation?.transferId == transferId { operation = nil }
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

    /// ADR-023 §2's `bulk_token` TTL — the same bound `BulkTokenTable` enforces, in ms.

    private static let acceptTimeoutMs = 30_000
    private static let readBufferBytes = 16_384
}
