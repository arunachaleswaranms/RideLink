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
    private static let reconnectAttemptSettleNs: UInt64 = 1_000_000_000

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
    private var pendingPings: [Int64: CheckedContinuation<ClockSync.Sample, Error>] = [:]

    public private(set) var diagnostics = ControlDiagnostics()
    public var onEvent: (@Sendable (ControlEvent) -> Void)?
    public var onDiagnosticsChanged: (@Sendable (ControlDiagnostics) -> Void)?

    public var reconnectCount: Int {
        get async { await reconnectController.reconnectCount }
    }

    public init(
        localPeerId: PeerId = ProvisionalIdentity.peerId,
        monotonicNowUs: @escaping @Sendable () -> Int64,
        randomFraction: @escaping @Sendable () -> Double = { Double.random(in: -1...1) }
    ) {
        self.localPeerId = localPeerId
        self.monotonicNowUs = monotonicNowUs
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

    /// Binds the OS-selected dynamic port and starts accepting inbound candidates.
    public func startListening(local: LocalHandshakeIdentity) async throws -> UInt16 {
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

    /// Dials a discovered peer. Runs concurrently with `startListening`'s accept loop.
    public func connectTo(host: String, port: UInt16, local: LocalHandshakeIdentity) {
        Task {
            guard let socket = try? await ControlConnection.connect(host: host, port: port) else {
                self.emit(.linkLost(reason: .network))
                return
            }
            await self.handleCandidate(socket: socket, local: local)
        }
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

    private func sendPingAndAwait(socket: ControlConnection, timeoutMs: Int64) async throws -> ClockSync.Sample {
        let t1 = monotonicNowUs()
        try await socket.writeFrame(ControlMessages.ping(localPeerId: localPeerId, sessionId: activeSessionId, seq: seqCounter.nextSeq(), sentAtMonoUs: t1, t1MonoUs: t1))
        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<ClockSync.Sample, Error>) in
            pendingPings[t1] = continuation
            Task {
                try? await Task.sleep(nanoseconds: UInt64(max(0, timeoutMs)) * 1_000_000)
                self.timeoutPing(t1: t1)
            }
        }
    }

    private func timeoutPing(t1: Int64) {
        pendingPings.removeValue(forKey: t1)?.resume(throwing: ControlTransportError.notReady)
    }

    /// Starts `ReconnectController`'s ladder after a genuine network-caused link loss.
    public func beginReconnect(local: LocalHandshakeIdentity, host: String, port: UInt16) async {
        updateDiagnostics { $0.controlState = .reconnecting }
        await reconnectController.start(
            onAttempt: { [weak self] in
                guard let self else { return true }
                await self.connectTo(host: host, port: port, local: local)
                try? await Task.sleep(nanoseconds: Self.reconnectAttemptSettleNs)
                return await self.hasActiveSocket()
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
        endedDeliberately = true
        await reconnectController.cancel()
        acceptTask?.cancel()
        keepaliveTask?.cancel()
        clockSyncTask?.cancel()
        readLoopTask?.cancel()
        if let socket = activeSocket {
            try? await socket.writeFrame(ControlMessages.bye(localPeerId: localPeerId, sessionId: activeSessionId, seq: seqCounter.nextSeq(), sentAtMonoUs: monotonicNowUs(), reason: reason))
            socket.close()
        }
        activeSocket = nil
        listener?.close()
        updateDiagnostics { $0 = ControlDiagnostics(); $0.controlState = .ended }
    }
}
