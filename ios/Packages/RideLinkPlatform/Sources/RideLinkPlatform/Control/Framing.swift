import Foundation
import Network
import RideLinkCore

/// PHASE 1a PLAINTEXT CONTROL TRANSPORT.
///
/// **`PlainControlTransportPhase1a` — not secure, debug/development builds only.** PROTOCOL §1
/// specifies TCP over **TLS 1.3**; Phase 1b has not started (CLAUDE.md rule 28), so this
/// transport carries the same length-prefixed JSON framing over **plain TCP** via
/// `Network.framework`. Every type in this file is named or documented so a reviewer cannot
/// mistake it for the production transport; callers must gate its use behind a debug-build check
/// (wired in the app target — release builds must not construct a `ControlListener` or
/// `ControlConnection` at all).
///
/// Framing (PROTOCOL §1): `uint32` big-endian byte length, then that many UTF-8 JSON bytes.
/// `FrameLimits.maxControlFrameBytes` is 262144. The length prefix is read and validated
/// **before** the payload is requested from `Network.framework` — `readFrame()` returns
/// `.frameTooLarge` without ever calling `receive(minimumIncompleteLength: length, ...)` for an
/// oversized or negative length.
public enum PlainControlTransportPhase1a {}

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

/// One control-plane TCP connection, plain (Phase 1a), framed per `PlainControlTransportPhase1a`.
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

    private init(connection: NWConnection, isInitiator: Bool, queue: DispatchQueue) {
        self.connection = connection
        self.isInitiator = isInitiator
        self.queue = queue
        connection.start(queue: queue)
    }

    public static func connect(host: String, port: UInt16) async throws -> ControlConnection {
        let queue = DispatchQueue(label: "com.ridelink.platform.control.connection")
        guard let nwPort = NWEndpoint.Port(rawValue: port) else {
            throw ControlTransportError.connectFailed("invalid port \(port)")
        }
        let params = NWParameters.tcp
        if let tcpOptions = params.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options {
            tcpOptions.noDelay = true // CLAUDE.md rule 10 / PROTOCOL §1: TCP_NODELAY
        }

        let nwConnection = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: params)
        let control = ControlConnection(connection: nwConnection, isInitiator: true, queue: queue)
        try await control.waitUntilReady()
        return control
    }

    /// Wraps an already-accepted `NWConnection` from a listener's `newConnectionHandler`.
    public static func fromAccepted(_ nwConnection: NWConnection, queue: DispatchQueue) -> ControlConnection {
        if let tcpOptions = nwConnection.parameters.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options {
            tcpOptions.noDelay = true
        }
        return ControlConnection(connection: nwConnection, isInitiator: false, queue: queue)
    }

    private func waitUntilReady() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let box = SingleResumeContinuation(continuation)
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    box.resume(returning: ())
                case .failed(let error):
                    box.resume(throwing: ControlTransportError.connectFailed("\(error)"))
                case .cancelled:
                    box.resume(throwing: ControlTransportError.connectFailed("cancelled"))
                default:
                    break
                }
            }
        }
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

    public private(set) var localPort: UInt16 = 0

    private init(listener: NWListener, queue: DispatchQueue) {
        self.listener = listener
        self.queue = queue
    }

    public static func bind() async throws -> ControlListener {
        let queue = DispatchQueue(label: "com.ridelink.platform.control.listener")
        let params = NWParameters.tcp
        if let tcpOptions = params.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options {
            tcpOptions.noDelay = true
        }
        let nwListener = try NWListener(using: params) // port 0: OS-selected dynamic port
        let control = ControlListener(listener: nwListener, queue: queue)

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
