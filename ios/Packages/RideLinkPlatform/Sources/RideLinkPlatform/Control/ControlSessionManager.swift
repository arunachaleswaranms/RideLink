import Foundation
import Network
import RideLinkCore

public enum ControlState: Sendable, Equatable {
    case idle
    case connecting
    case connected
    case reconnecting
    case disconnected
    case ended
}

/// FR-023 diagnostics surface (this session's brief §17). Never claims security it doesn't have.
public struct ControlDiagnostics: Sendable, Equatable {
    public var transportLabel = "PLAIN / PHASE 1A / NOT SECURE"
    public var controlState: ControlState = .idle
    public var remotePeerId: String?
    public var isLocalLeader: Bool?
    public var rttMs: Double?
    public var clockOffsetUs: Int64?
    public var clockJitterUs: Int64?
    public var reconnectCount = 0

    public init() {}
}

public enum ControlEvent: Sendable {
    case connected(remotePeerId: PeerId, sessionId: SessionId, isLocalLeader: Bool)
    case linkLost(reason: LinkLossReason)
    case duplicateConnectionClosed
    case reconnectBudgetExhausted
}

/// Top-level Phase 1a control-plane orchestrator: binds the listener, accepts inbound and dials
/// outbound candidates, resolves duplicates (`DuplicateConnectionArbiter`), runs the surviving
/// connection's read loop, keepalive and clock-sync bursts (`ClockSync`), and reconnect
/// (`ReconnectController`). One instance per ride session attempt.
///
/// **`PlainControlTransportPhase1a` — plaintext, debug/development builds only.**
public actor ControlSessionManager {
    private static let keepaliveIntervalNs: UInt64 = 2_000_000_000 // PROTOCOL §1
    private static let keepaliveLostThresholdUs: Int64 = 6_000_000 // PROTOCOL §1
    private static let pingTimeoutMs: Int64 = 3_000
    private static let clockBurstSampleCount = 11 // ARCHITECTURE §7.1
    private static let clockBurstSpacingNs: UInt64 = 50_000_000 // ARCHITECTURE §7.1 "~50ms apart"
    private static let clockResyncIntervalNs: UInt64 = 10_000_000_000 // ARCHITECTURE §7.1 "every 10s thereafter"

    private let localPeerId: PeerId
    private let monotonicNowUs: @Sendable () -> Int64
    private let seqCounter = SeqCounter()
    private let arbiter: DuplicateConnectionArbiter
    private let reconnectController: ReconnectController

    private var listener: ControlListener?
    private var acceptTask: Task<Void, Never>?
    private var activeSocket: ControlConnection?
    private var activeSessionId = SessionId("n/a")
    private var readLoopTask: Task<Void, Never>?
    private var keepaliveTask: Task<Void, Never>?
    private var clockSyncTask: Task<Void, Never>?
    private var lastPongAtMonoUs: Int64 = .min
    private var clockState: ClockSync.EstimatorState?
    private var endedDeliberately = false
    /// Distinct from `endedDeliberately` (which also becomes true after an ordinary BYE, where
    /// this manager stays alive and should accept a future reconnect). `isShutDown` is only ever
    /// true between `shutdown()` and the next `startListening()`, and it is what guards `promote`
    /// against a handshake/dedup resolution that was already in flight when `shutdown()` was
    /// called from completing *after* teardown (this session's brief §9/§10) — no per-candidate
    /// task is individually cancelled, so this is the backstop.
    private var isShutDown = false
    private var pendingPings: [Int64: CheckedContinuation<ClockSync.Sample, Error>] = [:]

    public private(set) var diagnostics = ControlDiagnostics()
    public var onEvent: (@Sendable (ControlEvent) -> Void)?
    public var onDiagnosticsChanged: (@Sendable (ControlDiagnostics) -> Void)?

    public var reconnectCount: Int {
        get async { await reconnectController.reconnectCount }
    }

    private let connectTimeoutMs: Int64

    /// - Parameter connectTimeoutMs: bounds `ControlConnection.connect`, matching Android's
    ///   `ControlSocket.connect(..., connectTimeoutMs = 5000)`. Injectable so tests can exercise
    ///   an unreachable-peer reconnect attempt without waiting out a real 5s timeout per attempt.
    public init(
        localPeerId: PeerId = ProvisionalIdentity.peerId,
        monotonicNowUs: @escaping @Sendable () -> Int64,
        randomFraction: @escaping @Sendable () -> Double = { Double.random(in: -1...1) },
        connectTimeoutMs: Int64 = 5000
    ) {
        self.localPeerId = localPeerId
        self.monotonicNowUs = monotonicNowUs
        self.connectTimeoutMs = connectTimeoutMs
        self.arbiter = DuplicateConnectionArbiter(localPeerId: localPeerId, initialConnTiebreak: ConnTiebreakGenerator.generate())
        self.reconnectController = ReconnectController(randomFraction: randomFraction) { ms in
            try? await Task.sleep(nanoseconds: UInt64(max(0, ms)) * 1_000_000)
        }
    }

    public func setOnEvent(_ handler: @escaping @Sendable (ControlEvent) -> Void) { onEvent = handler }

    public func setOnDiagnosticsChanged(_ handler: @escaping @Sendable (ControlDiagnostics) -> Void) { onDiagnosticsChanged = handler }

    private func emit(_ event: ControlEvent) { onEvent?(event) }

    private func updateDiagnostics(_ mutate: (inout ControlDiagnostics) -> Void) {
        mutate(&diagnostics)
        onDiagnosticsChanged?(diagnostics)
    }

    /// Binds the OS-selected dynamic port and starts accepting inbound candidates. This instance
    /// is reused across sessions (`SessionCoordinator` constructs it once), so a fresh
    /// `startListening` after a prior `shutdown()` must un-latch `isShutDown` — otherwise
    /// `promote` would keep refusing every connection this new session ever completes.
    public func startListening(local: LocalHandshakeIdentity) async throws -> UInt16 {
        isShutDown = false
        let bound = try await ControlListener.bind()
        listener = bound
        acceptTask = Task {
            while !Task.isCancelled {
                guard let socket = try? await bound.accept() else { return }
                Task { await self.handleCandidate(socket: socket, local: local) }
            }
        }
        return bound.localPort
    }

    /// Exposes the bound listener so `Discovery` can attach Bonjour advertising to the same
    /// socket rather than binding a second, unused TCP port (this session's brief §4B).
    public func underlyingListener() -> NWListener? { listener?.underlyingListener }

    /// Dials a discovered peer. Runs concurrently with `startListening`'s accept loop. May emit
    /// `.linkLost(.network)` on failure — used for the *top-level* (first) connection attempt
    /// only. `ReconnectController` must never call this directly: it already owns its own
    /// retry decision, and a failed attempt re-emitting `.linkLost` would let
    /// `SessionCoordinator` start a second, nested reconnect loop on top of the one already
    /// running (this session's brief §3). It calls `attemptConnection` instead.
    public func connectTo(host: String, port: UInt16, local: LocalHandshakeIdentity) {
        Task {
            let succeeded = await self.attemptConnection(host: host, port: port, local: local)
            if !succeeded {
                self.emit(.linkLost(reason: .network))
            }
        }
    }

    /// Internal connection primitive: dials, runs the handshake and duplicate-connection
    /// resolution to completion, and reports success/failure directly as a return value —
    /// **no `ControlEvent` is emitted here**. This is what `ReconnectController`'s ladder calls
    /// (`beginReconnect` below), so a failed attempt simply advances the same ladder rather than
    /// triggering another `.linkLost` -> `beginReconnect` cycle.
    private func attemptConnection(host: String, port: UInt16, local: LocalHandshakeIdentity) async -> Bool {
        guard let socket = try? await ControlConnection.connect(host: host, port: port, connectTimeoutMs: connectTimeoutMs) else {
            return false
        }
        await handleCandidate(socket: socket, local: local)
        return hasActiveSocket()
    }

    private func handleCandidate(socket: ControlConnection, local: LocalHandshakeIdentity) async {
        if activeSocket != nil {
            await rejectAsAlreadyActive(socket)
            return
        }

        let localWithTiebreak = local.with(connTiebreak: await arbiter.connTiebreak)
        let outcome: HandshakeOutcome
        do {
            outcome =
                socket.isInitiator
                ? try await ControlHandshake.performAsInitiator(socket: socket, localPeerId: localPeerId, seqCounter: seqCounter, monotonicNowUs: monotonicNowUs, local: localWithTiebreak)
                : try await ControlHandshake.performAsAcceptor(socket: socket, localPeerId: localPeerId, seqCounter: seqCounter, monotonicNowUs: monotonicNowUs, local: localWithTiebreak)
        } catch {
            socket.close()
            return
        }

        switch outcome {
        case .success:
            await resolveCandidate(DuplicateConnectionArbiter.Candidate(socket: socket, outcome: outcome))
        case .rejected, .connectionClosed:
            socket.close()
        }
    }

    private func resolveCandidate(_ candidate: DuplicateConnectionArbiter.Candidate) async {
        switch await arbiter.register(candidate) {
        case .awaitingRival(let registered):
            try? await Task.sleep(nanoseconds: DuplicateConnectionArbiter.gracePeriodNs)
            if let lone = await arbiter.finalizeIfStillLone(registered) {
                await promote(lone)
            }
        case .survivor(let winner, let loser):
            if let loser { closeLoser(loser) }
            await promote(winner)
        case .tieRetry:
            candidate.socket.close()
        }
    }

    private func rejectAsAlreadyActive(_ socket: ControlConnection) async {
        try? await socket.writeFrame(
            ControlMessages.error(
                localPeerId: localPeerId,
                sessionId: SessionId("n/a"),
                seq: 1,
                sentAtMonoUs: monotonicNowUs(),
                code: errorCodeSessionAlreadyActive,
                message: "a control session is already active",
                fatal: true
            )
        )
        socket.close()
    }

    private func closeLoser(_ candidate: DuplicateConnectionArbiter.Candidate) {
        guard case .success(_, _, let sessionId, _) = candidate.outcome else { return }
        Task {
            try? await candidate.socket.writeFrame(
                ControlMessages.bye(localPeerId: self.localPeerId, sessionId: sessionId, seq: self.seqCounter.nextSeq(), sentAtMonoUs: self.monotonicNowUs(), reason: byeReasonDuplicateConnection)
            )
            candidate.socket.close()
        }
        // ARCHITECTURE §3 rule 6: not a fault, not a state transition, must not touch reconnect_count.
        emit(.duplicateConnectionClosed)
    }

    private func promote(_ candidate: DuplicateConnectionArbiter.Candidate) async {
        guard case .success(let remotePeerId, _, let sessionId, let leaderPeerId) = candidate.outcome else { return }
        guard !isShutDown else {
            candidate.socket.close()
            return
        }
        if activeSocket != nil {
            closeLoser(candidate)
            return
        }
        activeSocket = candidate.socket
        activeSessionId = sessionId
        endedDeliberately = false
        clockState = nil
        lastPongAtMonoUs = monotonicNowUs()
        await reconnectController.reset()

        let isLeader = leaderPeerId.value == localPeerId.value
        updateDiagnostics {
            $0.controlState = .connected
            $0.remotePeerId = remotePeerId.description
            $0.isLocalLeader = isLeader
        }
        emit(.connected(remotePeerId: remotePeerId, sessionId: sessionId, isLocalLeader: isLeader))

        readLoopTask = Task { await self.readLoop(socket: candidate.socket, sessionId: sessionId) }
        keepaliveTask = Task { await self.keepaliveLoop(socket: candidate.socket) }
        clockSyncTask = Task { await self.clockSyncLoop(socket: candidate.socket) }
    }

    private func readLoop(socket: ControlConnection, sessionId: SessionId) async {
        while !Task.isCancelled {
            switch await socket.readFrame() {
            case .frame(let envelope, _):
                await handleFrame(socket: socket, sessionId: sessionId, envelope: envelope)
            case .malformed:
                continue // PROTOCOL §2: log and continue, framing itself is intact
            case .frameTooLarge:
                try? await socket.writeFrame(
                    ControlMessages.error(localPeerId: localPeerId, sessionId: sessionId, seq: seqCounter.nextSeq(), sentAtMonoUs: monotonicNowUs(), code: errorCodeFrameTooLarge, message: "frame exceeds cap", fatal: true)
                )
                await endConnection(socket, reason: .network)
                return
            case .connectionClosed:
                if !endedDeliberately { await endConnection(socket, reason: .network) }
                return
            }
        }
    }

    private func handleFrame(socket: ControlConnection, sessionId: SessionId, envelope: Envelope) async {
        let payload = envelope.payload
        switch envelope.type {
        case "PING":
            guard let t1 = payload["t1_mono_us"]?.int64Value else { return }
            let t2 = monotonicNowUs()
            let t3 = monotonicNowUs()
            try? await socket.writeFrame(ControlMessages.pong(localPeerId: localPeerId, sessionId: sessionId, seq: seqCounter.nextSeq(), sentAtMonoUs: monotonicNowUs(), t1MonoUs: t1, t2MonoUs: t2, t3MonoUs: t3))
        case "PONG":
            guard
                let t1 = payload["t1_mono_us"]?.int64Value,
                let t2 = payload["t2_mono_us"]?.int64Value,
                let t3 = payload["t3_mono_us"]?.int64Value
            else { return }
            let t4 = monotonicNowUs()
            // t2/t3 are peer-controlled; `ClockSync.Sample.rttUs`/`offsetUs` use plain (trapping)
            // Int64 arithmetic deliberately, so a genuine overflow is a loud crash during
            // development rather than a silently wrapped, wrong number reaching the estimator —
            // but that means an adversarial or badly broken peer picking extreme t2/t3 values
            // could **crash the app** by triggering that trap. Reject the sample here, using
            // overflow-*reporting* arithmetic, before it is ever constructed (this session's
            // brief §11) — the same way a malformed field is dropped above. ClockSync's own
            // algorithm and shared vectors are untouched.
            guard isPlausibleClockSample(t1: t1, t2: t2, t3: t3, t4: t4) else { return }
            lastPongAtMonoUs = t4
            pendingPings.removeValue(forKey: t1)?.resume(returning: ClockSync.Sample(t1MonoUs: t1, t2MonoUs: t2, t3MonoUs: t3, t4MonoUs: t4))
            updateDiagnostics { $0.rttMs = Double((t4 - t1) - (t3 - t2)) / 1000.0 }
        case "BYE":
            await endConnection(socket, reason: .bye)
        case "ERROR":
            if payload["fatal"]?.boolValue == true { await endConnection(socket, reason: .bye) }
        default:
            break // PROTOCOL §2 rule 2: unknown types ignored, logged, not fatal
        }
    }

    private func endConnection(_ socket: ControlConnection, reason: LinkLossReason) async {
        guard activeSocket === socket else { return }
        activeSocket = nil
        endedDeliberately = reason != .network
        keepaliveTask?.cancel()
        clockSyncTask?.cancel()
        socket.close()
        failAllPendingPings(with: ControlTransportError.notReady)

        switch reason {
        case .network:
            updateDiagnostics { $0.controlState = .reconnecting }
            emit(.linkLost(reason: reason))
        case .bye:
            updateDiagnostics { $0.controlState = .ended }
            emit(.linkLost(reason: reason))
        case .duplicateConnection, .userEnded:
            break
        }
    }

    private func keepaliveLoop(socket: ControlConnection) async {
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: Self.keepaliveIntervalNs)
            if Task.isCancelled { return }
            if monotonicNowUs() - lastPongAtMonoUs > Self.keepaliveLostThresholdUs {
                await endConnection(socket, reason: .network)
                return
            }
            _ = try? await sendPingAndAwait(socket: socket, timeoutMs: Self.pingTimeoutMs)
        }
    }

    private func clockSyncLoop(socket: ControlConnection) async {
        await runClockBurst(socket: socket) // ARCHITECTURE §7.1: 11 samples at CONNECTING
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: Self.clockResyncIntervalNs)
            if Task.isCancelled { return }
            await runClockBurst(socket: socket) // ... and every 10s thereafter
        }
    }

    private func runClockBurst(socket: ControlConnection) async {
        var samples: [ClockSync.Sample] = []
        for _ in 0..<Self.clockBurstSampleCount {
            if let sample = try? await sendPingAndAwait(socket: socket, timeoutMs: Self.pingTimeoutMs) {
                samples.append(sample)
            }
            try? await Task.sleep(nanoseconds: Self.clockBurstSpacingNs)
        }
        guard !samples.isEmpty else { return }
        let result = ClockSync.applyWindow(previous: clockState, samples: samples)
        clockState = result.newState
        updateDiagnostics {
            $0.clockOffsetUs = result.offsetUs ?? $0.clockOffsetUs
            $0.clockJitterUs = result.jitterUs ?? $0.clockJitterUs
            if let rtt = result.rttUs { $0.rttMs = Double(rtt) / 1000.0 }
        }
    }

    /// Registers the waiter **before** writing the PING frame. `writeFrame` suspends this actor,
    /// and a fast (e.g. loopback) peer can have `handleFrame`'s `"PONG"` case run on this same
    /// actor before `writeFrame` returns — if the waiter were registered after the write, that
    /// PONG would find no entry in `pendingPings` and be silently dropped until timeout (this
    /// session's brief §2). Registering first, synchronously, before any suspension closes the
    /// race: `withCheckedThrowingContinuation`'s body runs on this actor's isolation with no
    /// `await` in it, so `pendingPings[t1] = continuation` happens atomically with respect to any
    /// other actor-isolated code, including `handleFrame`.
    private func sendPingAndAwait(socket: ControlConnection, timeoutMs: Int64) async throws -> ClockSync.Sample {
        let t1 = monotonicNowUs()
        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<ClockSync.Sample, Error>) in
            pendingPings[t1] = continuation
            Task {
                do {
                    try await socket.writeFrame(ControlMessages.ping(localPeerId: self.localPeerId, sessionId: self.activeSessionId, seq: self.seqCounter.nextSeq(), sentAtMonoUs: t1, t1MonoUs: t1))
                } catch {
                    self.failPing(t1: t1, error: error)
                    return
                }
                try? await Task.sleep(nanoseconds: UInt64(max(0, timeoutMs)) * 1_000_000)
                self.timeoutPing(t1: t1)
            }
        }
    }

    /// Write failure: remove the pending entry and resume (throw) exactly once. `removeValue`
    /// returns `nil` if the PONG or a timeout already claimed this entry, making this a no-op in
    /// that case rather than a double-resume.
    private func failPing(t1: Int64, error: Error) {
        pendingPings.removeValue(forKey: t1)?.resume(throwing: error)
    }

    private func timeoutPing(t1: Int64) {
        pendingPings.removeValue(forKey: t1)?.resume(throwing: ControlTransportError.notReady)
    }

    /// Live-wire acceptance check for a raw `(t1,t2,t3,t4)` PONG sample, run **before** it is
    /// ever turned into a `ClockSync.Sample` (this session's brief §11). `ClockSync`'s own
    /// algorithm and shared vectors are untouched — this only rejects a sample whose arithmetic
    /// would overflow the (trapping) `Int64` subtraction `rttUs`/`offsetUs` use, or whose
    /// resulting rtt is non-positive, using overflow-*reporting* subtraction so a peer-controlled
    /// `t2`/`t3` can never trigger that trap or silently produce a plausible-looking wrong number.
    /// `rtt <= 0` mirrors `ClockSync`'s own `rttUs > 0` outlier filter — this check exists so an
    /// overflowing sample never reaches even that filter with a corrupted value.
    private func isPlausibleClockSample(t1: Int64, t2: Int64, t3: Int64, t4: Int64) -> Bool {
        let (aDiff, aOverflow) = t4.subtractingReportingOverflow(t1)
        guard !aOverflow else { return false }
        let (bDiff, bOverflow) = t3.subtractingReportingOverflow(t2)
        guard !bOverflow else { return false }
        let (rtt, rttOverflow) = aDiff.subtractingReportingOverflow(bDiff)
        guard !rttOverflow else { return false }
        return rtt > 0
    }

    /// Teardown (`endConnection`/`shutdown`) must fail every outstanding waiter rather than
    /// leave it to time out on a socket that is already dead (this session's brief §10).
    private func failAllPendingPings(with error: Error) {
        let waiters = pendingPings
        pendingPings.removeAll()
        for (_, continuation) in waiters { continuation.resume(throwing: error) }
    }

    /// Starts `ReconnectController`'s ladder after a genuine network-caused link loss. Each
    /// attempt calls `attemptConnection` (not `connectTo`) so a failed attempt reports directly
    /// back to this same, already-running ladder instead of emitting an event that could start a
    /// second one (this session's brief §3).
    public func beginReconnect(local: LocalHandshakeIdentity, host: String, port: UInt16) async {
        updateDiagnostics { $0.controlState = .reconnecting }
        await reconnectController.start(
            onAttempt: { [weak self] in
                guard let self else { return true }
                return await self.attemptConnection(host: host, port: port, local: local)
            },
            onExhausted: { [weak self] in
                guard let self else { return }
                await self.updateDiagnostics { $0.controlState = .disconnected }
                await self.emit(.reconnectBudgetExhausted)
            }
        )
        let count = await reconnectController.reconnectCount
        updateDiagnostics { $0.reconnectCount = count }
    }

    private func hasActiveSocket() -> Bool { activeSocket != nil }

    public func shutdown(reason: String = byeReasonShutdown) async {
        isShutDown = true
        endedDeliberately = true
        await reconnectController.cancel()
        acceptTask?.cancel()
        keepaliveTask?.cancel()
        clockSyncTask?.cancel()
        readLoopTask?.cancel()
        // Candidate sockets the arbiter is still holding (awaiting a rival, or mid grace-period)
        // are real open sockets — close them rather than leaving them to resolve on their own
        // after this manager is gone (this session's brief §9/§10).
        for candidate in await arbiter.drainAll() {
            candidate.socket.close()
        }
        if let socket = activeSocket {
            try? await socket.writeFrame(ControlMessages.bye(localPeerId: localPeerId, sessionId: activeSessionId, seq: seqCounter.nextSeq(), sentAtMonoUs: monotonicNowUs(), reason: reason))
            socket.close()
        }
        activeSocket = nil
        listener?.close()
        failAllPendingPings(with: ControlTransportError.notReady)
        updateDiagnostics { $0 = ControlDiagnostics(); $0.controlState = .ended }
    }
}
