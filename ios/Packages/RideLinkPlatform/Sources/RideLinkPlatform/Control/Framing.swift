import Foundation
import Network
import RideLinkCore

/// Control-plane framing (PROTOCOL §1): `uint32` big-endian byte length, then that many UTF-8 JSON
/// bytes. `FrameLimits.maxControlFrameBytes` is 262144, and the length prefix is read and
/// validated **before** the payload is requested from `Network.framework` — `readFrame()` returns
/// `.frameTooLarge` without ever calling `receive(minimumIncompleteLength: length, ...)` for an
/// oversized or negative length.
///
/// Transport-neutral by design. Whether the bytes underneath are TLS records (production, the only
/// option — see `ControlChannel`) or plaintext (a test fixture) is decided by the `ControlChannel`
/// that produced the connection, and nothing in this file knows or cares.

public enum FrameReadResult: Sendable {
    case frame(Envelope, versionOk: Bool)
    /// The 4-byte length prefix exceeded the cap (or was negative). No body was requested.
    case frameTooLarge(declaredLength: Int32)
    /// The body was read in full but failed to decode (PROTOCOL §2 `malformed_frame`).
    case malformed(errorCode: String)
    /// EOF or an I/O error mid-read: the peer is gone.
    case connectionClosed
}

public enum ControlTransportError: Error, Sendable {
    case connectFailed(String)
    case writeFailed(String)
    case notReady
}

/// Swift 6 strict concurrency forbids mutating a captured `var` from inside an escaping
/// `@Sendable` closure (`NWConnection`/`NWListener` state handlers can fire more than once, and
/// a `CheckedContinuation` traps if resumed twice). This box makes "resume exactly once" safe and
/// explicit rather than reaching for `@unchecked Sendable` on a bare flag.
private final class SingleResumeContinuation<T: Sendable>: @unchecked Sendable {
    private let continuation: CheckedContinuation<T, Error>
    private let lock = NSLock()
    private var didResume = false

    init(_ continuation: CheckedContinuation<T, Error>) {
        self.continuation = continuation
    }

    func resume(returning value: T) {
        lock.lock()
        defer { lock.unlock() }
        guard !didResume else { return }
        didResume = true
        continuation.resume(returning: value)
    }

    func resume(throwing error: Error) {
        lock.lock()
        defer { lock.unlock() }
        guard !didResume else { return }
        didResume = true
        continuation.resume(throwing: error)
    }
}

/// One control-plane connection, framed per the rules above and protected by whatever transport
/// the `ControlChannel` that produced it established — TLS 1.3 in production.
///
/// `NWConnection` delivers every callback on `queue`, a single serial `DispatchQueue` this type
/// owns exclusively — that serialization is what makes `@unchecked Sendable` correct here rather
/// than a shortcut: nothing outside this type ever touches `connection` or the pending
/// continuations directly, and every access happens on `queue`. This mirrors the precedent
/// already established by `BonjourDiscovery` in this package.
public final class ControlConnection: @unchecked Sendable {
    private let connection: NWConnection
    public let isInitiator: Bool
    private let queue: DispatchQueue
    private let securityLock = NSLock()
    private var attachedSecurity: (any ChannelSecurity)?

    /// Non-nil on every production connection. Nil only on the test-only plaintext fixture.
    /// Populated by the `ControlChannel` once the transport handshake has completed, which is the
    /// earliest moment the peer's certificate and the exporter exist.
    public var security: (any ChannelSecurity)? {
        securityLock.lock()
        defer { securityLock.unlock() }
        return attachedSecurity
    }

    public func attachSecurity(_ security: any ChannelSecurity) {
        securityLock.lock()
        defer { securityLock.unlock() }
        attachedSecurity = security
    }

    /// The TLS metadata for this connection, so a `ControlChannel` can read the peer certificate
    /// and the keying-material exporter. Nil on a plaintext connection.
    public var securityProtocolMetadata: sec_protocol_metadata_t? {
        (connection.metadata(definition: NWProtocolTLS.definition) as? NWProtocolTLS.Metadata)?
            .securityProtocolMetadata
    }

    private init(connection: NWConnection, isInitiator: Bool, queue: DispatchQueue) {
        self.connection = connection
        self.isInitiator = isInitiator
        self.queue = queue
        connection.start(queue: queue)
    }

    /// `connectTimeoutMs` mirrors Android's `ControlSocket.connect(host, port, connectTimeoutMs =
    /// 5000)`. Without it, a connection to an address that actively refuses (`ECONNREFUSED`) can
    /// sit in `NWConnection`'s `.waiting` state **indefinitely** — Network.framework treats
    /// `.waiting` as a retryable condition it may resolve on its own if the network path changes,
    /// and never surfaces it as `.failed` on its own. Left unbounded, a single unreachable-peer
    /// reconnect attempt would hang forever, stalling the whole ladder on attempt one (this
    /// session's brief §2/§9 — a reconnect attempt must fail within a bounded time, not hang).
    public static func connect(
        host: String,
        port: UInt16,
        parameters: NWParameters,
        connectTimeoutMs: Int64 = 5000
    ) async throws -> ControlConnection {
        let queue = DispatchQueue(label: "com.ridelink.platform.control.connection")
        guard let nwPort = NWEndpoint.Port(rawValue: port) else {
            throw ControlTransportError.connectFailed("invalid port \(port)")
        }
        if let tcpOptions = parameters.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options {
            tcpOptions.noDelay = true // CLAUDE.md rule 10 / PROTOCOL §1: TCP_NODELAY
        }

        let nwConnection = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: parameters)
        let control = ControlConnection(connection: nwConnection, isInitiator: true, queue: queue)
        try await control.waitUntilReady(timeoutMs: connectTimeoutMs)
        return control
    }

    /// Wraps an already-accepted `NWConnection` from a listener's `newConnectionHandler`.
    public static func fromAccepted(_ nwConnection: NWConnection, queue: DispatchQueue) -> ControlConnection {
        if let tcpOptions = nwConnection.parameters.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options {
            tcpOptions.noDelay = true
        }
        return ControlConnection(connection: nwConnection, isInitiator: false, queue: queue)
    }

    /// Races the real ready/failed state against a timeout. **Not** implemented with
    /// `withThrowingTaskGroup`: cancelling a sibling task that is suspended inside a
    /// `CheckedContinuation` does not resume it — cancellation is cooperative, and a
    /// `stateUpdateHandler`-driven continuation has no cancellation checkpoint of its own — so a
    /// task-group "race" would leave the group waiting forever for the losing child to actually
    /// finish, even after `cancelAll()`. Using a single `SingleResumeContinuation` that *either*
    /// the state handler *or* the timer can resume (whichever comes first; the loser is a no-op)
    /// is what actually bounds this call. On either path losing, `connection.cancel()` releases
    /// the underlying socket so a timed-out attempt does not linger.
    /// Waits until the connection is usable. Public because a `ControlChannel` with a transport
    /// handshake of its own — TLS — has to wait for `.ready` on an *accepted* connection too
    /// before the peer certificate and the exporter exist.
    public func awaitReady(timeoutMs: Int64 = 5000) async throws {
        try await waitUntilReady(timeoutMs: timeoutMs)
    }

    private func waitUntilReady(timeoutMs: Int64) async throws {
        do {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                let box = SingleResumeContinuation(continuation)
                // Checked before the handler is installed: a connection can already be `.ready` by
                // the time this is called (an accepted one usually is), and a state handler only
                // fires on *subsequent* changes — so without this the wait would hang forever on
                // exactly the common case.
                if case .ready = connection.state { box.resume(returning: ()) }
                connection.stateUpdateHandler = { state in
                    switch state {
                    case .ready:
                        box.resume(returning: ())
                    case .failed(let error):
                        box.resume(throwing: ControlTransportError.connectFailed("\(error)"))
                    case .cancelled:
                        box.resume(throwing: ControlTransportError.connectFailed("cancelled"))
                    default:
                        break // includes `.waiting` — see connect()'s doc comment
                    }
                }
                Task {
                    try? await Task.sleep(nanoseconds: UInt64(max(0, timeoutMs)) * 1_000_000)
                    box.resume(throwing: ControlTransportError.connectFailed("timed out connecting after \(timeoutMs)ms"))
                }
            }
        } catch {
            connection.cancel()
            throw error
        }
    }

    /// The remote peer's address, as reported by `Network.framework`'s own view of the connection.
    /// `"unknown"` mirrors Android's `ControlSocket.remoteHost` fallback — used only to dial the
    /// bulk connection back to the same host the control connection is already talking to
    /// (ADR-023), never for anything security-relevant (that is the SPKI pin, not this).
    public var remoteHost: String {
        if case .hostPort(let host, _) = connection.currentPath?.remoteEndpoint ?? connection.endpoint {
            return "\(host)"
        }
        return "unknown"
    }

    public func writeFrame(_ envelope: Envelope) async throws {
        let bytes = try EnvelopeCodec.encode(envelope)
        guard bytes.count <= FrameLimits.maxControlFrameBytes else {
            throw ControlTransportError.writeFailed("refusing to send an outgoing frame (\(bytes.count) bytes) over the cap")
        }
        var lengthBE = UInt32(bytes.count).bigEndian
        var payload = Data(bytes: &lengthBE, count: 4)
        payload.append(bytes)
        try await send(payload)
    }

    private func send(_ data: Data) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(
                content: data,
                completion: .contentProcessed { error in
                    if let error {
                        continuation.resume(throwing: ControlTransportError.writeFailed("\(error)"))
                    } else {
                        continuation.resume(returning: ())
                    }
                }
            )
        }
    }

    public func readFrame() async -> FrameReadResult {
        guard let lengthBytes = await receiveExactly(4), lengthBytes.count == 4 else {
            return .connectionClosed
        }
        let declaredLength = lengthBytes.withUnsafeBytes { $0.load(as: UInt32.self) }.bigEndian
        let signedLength = Int32(bitPattern: declaredLength)

        // The length is validated BEFORE any payload read is requested — an attacker-specified
        // oversized length never causes an oversized buffer request.
        if signedLength < 0 || Int(signedLength) > FrameLimits.maxControlFrameBytes {
            return .frameTooLarge(declaredLength: signedLength)
        }

        guard Int(signedLength) > 0 else {
            return .malformed(errorCode: "malformed_frame")
        }
        guard let body = await receiveExactly(Int(signedLength)), body.count == Int(signedLength) else {
            return .connectionClosed
        }

        switch EnvelopeCodec.decode(body) {
        case .success(let envelope, let versionOk):
            return .frame(envelope, versionOk: versionOk)
        case .failure(let errorCode):
            return .malformed(errorCode: errorCode)
        }
    }

    /// PROTOCOL §8.2's bulk plane — raw byte I/O that bypasses the JSON envelope framing entirely.
    /// Never called from the control read loop; used only by the bulk transport (ADR-023), which
    /// reuses this same TLS+SPKI-pinned connection machinery for its own, differently-framed
    /// (RLB1) connection rather than duplicating the TLS setup. Mirrors Android's
    /// `ControlSocket.writeRawBytes`/`readRawBytes` addition to `Framing.kt`.
    public func writeRawBytes(_ bytes: [UInt8]) async throws {
        try await send(Data(bytes))
    }

    /// Reads whatever is available in one receive call, up to `maxLength` bytes (may be fewer).
    /// `nil` at EOF or on any error — there is no separate "negative length" sentinel the way a
    /// blocking `InputStream.read` needs, because `Network.framework`'s completion already
    /// distinguishes "some bytes" from "the connection is done".
    public func readRawBytes(maxLength: Int) async -> [UInt8]? {
        await withCheckedContinuation { (continuation: CheckedContinuation<[UInt8]?, Never>) in
            connection.receive(minimumIncompleteLength: 1, maximumLength: maxLength) { data, _, _, _ in
                if let data, !data.isEmpty {
                    continuation.resume(returning: [UInt8](data))
                } else {
                    continuation.resume(returning: nil)
                }
            }
        }
    }

    private func receiveExactly(_ count: Int) async -> Data? {
        await withCheckedContinuation { (continuation: CheckedContinuation<Data?, Never>) in
            connection.receive(minimumIncompleteLength: count, maximumLength: count) { data, _, _, _ in
                if let data, data.count == count {
                    continuation.resume(returning: data)
                } else {
                    continuation.resume(returning: nil)
                }
            }
        }
    }

    public func close() {
        connection.cancel()
    }
}

/// Binds an OS-selected dynamic TCP port (PROTOCOL §1) and accepts inbound `ControlConnection`s.
/// See the type-level doc comment on `ControlConnection` for why `@unchecked Sendable` is correct
/// here: every access is confined to `queue`.
public final class ControlListener: @unchecked Sendable {
    private let listener: NWListener
    private let queue: DispatchQueue
    private var pendingConnections: [NWConnection] = []
    private var waitingContinuations: [CheckedContinuation<ControlConnection, Error>] = []
    private var isCancelled = false
    private let onAccepted: @Sendable (ControlConnection) async throws -> Void

    public private(set) var localPort: UInt16 = 0

    private init(
        listener: NWListener,
        queue: DispatchQueue,
        onAccepted: @escaping @Sendable (ControlConnection) async throws -> Void
    ) {
        self.listener = listener
        self.queue = queue
        self.onAccepted = onAccepted
    }

    /// - Parameter onAccepted: run on each accepted connection before `accept()` returns it. This
    ///   is where a TLS channel waits for the handshake and attaches `ChannelSecurity`, so that
    ///   neither this type nor `ControlSessionManager` has to know a transport exists. A throw
    ///   closes the connection and fails that `accept()` only.
    public static func bind(
        parameters: NWParameters,
        onAccepted: @escaping @Sendable (ControlConnection) async throws -> Void = { _ in }
    ) async throws -> ControlListener {
        let queue = DispatchQueue(label: "com.ridelink.platform.control.listener")
        if let tcpOptions = parameters.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options {
            tcpOptions.noDelay = true
        }
        let nwListener = try NWListener(using: parameters) // port 0: OS-selected dynamic port
        let control = ControlListener(listener: nwListener, queue: queue, onAccepted: onAccepted)

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let box = SingleResumeContinuation(continuation)
            nwListener.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    box.resume(returning: ())
                case .failed(let error):
                    box.resume(throwing: ControlTransportError.connectFailed("\(error)"))
                default:
                    break
                }
            }
            nwListener.newConnectionHandler = { [weak control] newConnection in
                control?.enqueue(newConnection)
            }
            nwListener.start(queue: queue)
        }
        control.localPort = nwListener.port?.rawValue ?? 0
        return control
    }

    /// Exposes the underlying `NWListener` so `Discovery` can attach Bonjour advertising to the
    /// *same* listener rather than binding a second, unused TCP port (this session's brief §4B).
    public var underlyingListener: NWListener { listener }

    private func enqueue(_ nwConnection: NWConnection) {
        queue.async { [weak self] in
            guard let self else { return }
            if let waiter = self.waitingContinuations.first {
                self.waitingContinuations.removeFirst()
                waiter.resume(returning: ControlConnection.fromAccepted(nwConnection, queue: self.queue))
            } else {
                self.pendingConnections.append(nwConnection)
            }
        }
    }

    public func accept() async throws -> ControlConnection {
        let connection = try await acceptRaw()
        do {
            try await onAccepted(connection)
        } catch {
            connection.close()
            throw error
        }
        return connection
    }

    private func acceptRaw() async throws -> ControlConnection {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<ControlConnection, Error>) in
            queue.async { [weak self] in
                guard let self else {
                    continuation.resume(throwing: ControlTransportError.connectFailed("listener deallocated"))
                    return
                }
                if self.isCancelled {
                    continuation.resume(throwing: ControlTransportError.connectFailed("listener closed"))
                    return
                }
                if !self.pendingConnections.isEmpty {
                    let nwConnection = self.pendingConnections.removeFirst()
                    continuation.resume(returning: ControlConnection.fromAccepted(nwConnection, queue: self.queue))
                } else {
                    self.waitingContinuations.append(continuation)
                }
            }
        }
    }

    public func close() {
        queue.async { [weak self] in
            guard let self else { return }
            self.isCancelled = true
            for waiter in self.waitingContinuations {
                waiter.resume(throwing: ControlTransportError.connectFailed("listener closed"))
            }
            self.waitingContinuations.removeAll()
        }
        listener.cancel()
    }
}
