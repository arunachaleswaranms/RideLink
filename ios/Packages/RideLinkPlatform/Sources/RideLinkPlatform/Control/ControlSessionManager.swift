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

/// FR-023 diagnostics surface. Never claims security it doesn't have.
public struct ControlDiagnostics: Sendable, Equatable {
    public var transportLabel = "NOT CONNECTED"
    public var controlState: ControlState = .idle
    public var remotePeerId: String?
    public var isLocalLeader: Bool?
    public var rttMs: Double?
    public var clockOffsetUs: Int64?
    public var clockJitterUs: Int64?
    public var reconnectCount = 0
    /// First 6 hex of the peer's `identity_spki_sha256`, per the ARCHITECTURE §11 redaction rule.
    public var peerIdentityPrefix: String?
    /// Negotiated TLS version, so the UI can show what actually protects the link.
    public var negotiatedProtocol: String?

    public init() {}
}

/// What the pairing screen shows. `sas6` is the six digits the user compares with the other phone
/// — displayed and then discarded. It is never logged, never persisted and never sent
/// (PROTOCOL §4.5.1, ARCHITECTURE §11), so this value must not outlive the prompt.
public struct PairingPrompt: Sendable, Equatable {
    public let sas6: String
    public let remotePeerId: PeerId
    public var peerDisplayName: String

    public init(sas6: String, remotePeerId: PeerId, peerDisplayName: String) {
        self.sas6 = sas6
        self.remotePeerId = remotePeerId
        self.peerDisplayName = peerDisplayName
    }
}

public enum ControlEvent: Sendable {
    case connected(remotePeerId: PeerId, sessionId: SessionId, isLocalLeader: Bool)
    case linkLost(reason: LinkLossReason)
    case duplicateConnectionClosed
    case reconnectBudgetExhausted
    /// The peer is unknown and PROTOCOL §4.5 pairing is required. Raised only on the **surviving**
    /// connection (§4.2), so exactly one six-digit code is ever shown.
    case pairingRequired(remotePeerId: PeerId)
    /// Both users confirmed the six digits and the trusted-peer record is written (PROTOCOL §4.5).
    case pairingSucceeded(peer: TrustedPeer)
    /// Pairing ended without a pin being written. The value is a PROTOCOL §4.6 code.
    case pairingFailed(code: String)
    /// The handshake was refused. `pin_mismatch` is the serious one (ADR-012).
    case handshakeRefused(code: String)
}

/// Top-level control-plane orchestrator: binds the listener, accepts inbound and dials outbound
/// candidates, resolves duplicates (`DuplicateConnectionArbiter`), applies the SPKI pin decision,
/// runs the surviving connection's read loop, keepalive and clock-sync bursts (`ClockSync`), and
/// reconnect (`ReconnectController`). One instance per ride session attempt.
///
/// It knows nothing about TLS. The `channel` it is given decides how bytes are protected, and the
/// only channel a shipped build can construct is `TlsControlChannel`.
public actor ControlSessionManager {
    private static let keepaliveIntervalNs: UInt64 = 2_000_000_000 // PROTOCOL §1
    private static let keepaliveLostThresholdUs: Int64 = 6_000_000 // PROTOCOL §1
    private static let pingTimeoutMs: Int64 = 3_000
    private static let clockBurstSampleCount = 11 // ARCHITECTURE §7.1
    private static let clockBurstSpacingNs: UInt64 = 50_000_000 // ARCHITECTURE §7.1 "~50ms apart"
    private static let clockResyncIntervalNs: UInt64 = 10_000_000_000 // ARCHITECTURE §7.1 "every 10s thereafter"

    private let localPeerId: PeerId
    private let channel: any ControlChannel
    private let trustedPeers: any TrustedPeerStore
    private let nowEpochSeconds: @Sendable () -> Int64
    private var pairing: PairingExchange?
    private var localIdentity: LocalHandshakeIdentity?
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
    private let pingRequests = PingRequestRegistry()

    public private(set) var diagnostics = ControlDiagnostics()

    /// Non-nil exactly while a first-meeting pairing is awaiting the two users. Cleared as soon as
    /// the exchange settles either way, so the six digits do not linger after they stop meaning
    /// anything.
    public private(set) var pairingPrompt: PairingPrompt?
    public var onPairingPromptChanged: (@Sendable (PairingPrompt?) -> Void)?
    public var onEvent: (@Sendable (ControlEvent) -> Void)?
    public var onDiagnosticsChanged: (@Sendable (ControlDiagnostics) -> Void)?

    public var reconnectCount: Int {
        get async { await reconnectController.reconnectCount }
    }

    /// Diagnostic-only: how many PING requests are currently awaiting their PONG. Exists so a
    /// test (or a future on-device diagnostics screen) can tell "stuck waiting on the network"
    /// apart from "never even asked" when a clock burst runs slower than expected — this is
    /// exactly the visibility CI-stabilization work needed when
    /// `testRepeatedClockBurstsAllCompleteQuickly` failed on a shared runner with only a bare
    /// `notReady` to go on.
    public var pendingPingCount: Int { pingRequests.count }

    /// How long a dial may take before it is abandoned now belongs to the `ControlChannel`, which
    /// is the thing that actually opens the socket — see `TlsControlChannel(connectTimeoutMs:)`.
    public init(
        localPeerId: PeerId,
        channel: any ControlChannel,
        trustedPeers: any TrustedPeerStore,
        monotonicNowUs: @escaping @Sendable () -> Int64,
        /// Wall clock, for the `pairedAt`/`lastSeenAt` fields of a trusted-peer record only. Those
        /// are human-facing timestamps in a stored record, never used for scheduling, so CLAUDE.md's
        /// monotonic-clocks rule does not apply — and a monotonic value would be meaningless across
        /// reboots, which is exactly what a persisted record has to survive.
        nowEpochSeconds: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970) },
        randomFraction: @escaping @Sendable () -> Double = { Double.random(in: -1...1) }
    ) {
        self.localPeerId = localPeerId
        self.channel = channel
        self.trustedPeers = trustedPeers
        self.nowEpochSeconds = nowEpochSeconds
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

    /// Binds the OS-selected dynamic port and starts accepting inbound candidates. This instance
    /// is reused across sessions (`SessionCoordinator` constructs it once), so a fresh
    /// `startListening` after a prior `shutdown()` must un-latch `isShutDown` — otherwise
    /// `promote` would keep refusing every connection this new session ever completes.
    public func startListening(local: LocalHandshakeIdentity) async throws -> UInt16 {
        isShutDown = false
        localIdentity = local
        updateDiagnostics { $0.transportLabel = self.channel.transportLabel }
        let bound = try await channel.bind()
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
        guard let socket = try? await channel.connect(host: host, port: port) else {
            return false
        }
        await handleCandidate(socket: socket, local: local)
        return hasActiveSocket()
    }

    private func handleCandidate(socket: ControlConnection, local: LocalHandshakeIdentity) async {
        localIdentity = local
        if activeSocket != nil {
            await rejectAsAlreadyActive(socket)
            return
        }

        let localWithTiebreak = local.with(connTiebreak: await arbiter.connTiebreak)
        let outcome: HandshakeOutcome
        do {
            outcome =
                socket.isInitiator
                ? try await ControlHandshake.performAsInitiator(
                    socket: socket, localPeerId: localPeerId, seqCounter: seqCounter,
                    monotonicNowUs: monotonicNowUs, local: localWithTiebreak, trustedPeers: trustedPeers)
                : try await ControlHandshake.performAsAcceptor(
                    socket: socket, localPeerId: localPeerId, seqCounter: seqCounter,
                    monotonicNowUs: monotonicNowUs, local: localWithTiebreak, trustedPeers: trustedPeers)
        } catch {
            socket.close()
            return
        }

        switch outcome {
        case .success:
            await resolveCandidate(DuplicateConnectionArbiter.Candidate(socket: socket, outcome: outcome))
        case .rejected(let code):
            await refuse(socket, code: code)
        case .connectionClosed:
            socket.close()
        }
    }

    /// Tells the peer why before closing, then surfaces it. `pin_mismatch` in particular must reach
    /// the user as a security warning rather than vanishing into a reconnect loop (ADR-012), which
    /// is why this emits an event instead of only closing the connection.
    private func refuse(_ socket: ControlConnection, code: String) async {
        try? await socket.writeFrame(
            ControlMessages.error(
                localPeerId: localPeerId,
                sessionId: SessionId("n/a"),
                seq: seqCounter.nextSeq(),
                sentAtMonoUs: monotonicNowUs(),
                code: code,
                message: "handshake refused",
                fatal: true
            )
        )
        socket.close()
        emit(.handshakeRefused(code: code))
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
        guard case .success(_, _, let sessionId, _, _, _) = candidate.outcome else { return }
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
        guard case .success(let remotePeerId, _, let sessionId, let leaderPeerId, let peerSpki, let pinDecision) =
            candidate.outcome else { return }
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
            $0.transportLabel = self.channel.transportLabel
            $0.controlState = .connected
            $0.remotePeerId = remotePeerId.description
            $0.isLocalLeader = isLeader
            $0.peerIdentityPrefix = peerSpki.description
            $0.negotiatedProtocol = candidate.socket.security?.negotiatedProtocolDescription
        }
        emit(.connected(remotePeerId: remotePeerId, sessionId: sessionId, isLocalLeader: isLeader))
        // PROTOCOL §4.5: pairing runs only on the surviving connection, so this happens here —
        // after duplicate resolution — and never in the handshake itself. That is what guarantees
        // exactly one six-digit code is ever displayed, even on a simultaneous first meeting.
        if case .pairingRequired = pinDecision {
            await beginPairing(socket: candidate.socket, remotePeerId: remotePeerId, peerSpki: peerSpki)
        }

        readLoopTask = Task { await self.readLoop(socket: candidate.socket, sessionId: sessionId) }
        keepaliveTask = Task { await self.keepaliveLoop(socket: candidate.socket) }
        clockSyncTask = Task { await self.clockSyncLoop(socket: candidate.socket) }
    }

    // MARK: - PROTOCOL §4.5 pairing

    private func updatePairingPrompt(_ prompt: PairingPrompt?) {
        pairingPrompt = prompt
        onPairingPromptChanged?(prompt)
    }

    public func setOnPairingPromptChanged(_ handler: @escaping @Sendable (PairingPrompt?) -> Void) {
        onPairingPromptChanged = handler
    }

    /// Starts PROTOCOL §4.5 pairing on the surviving connection. The six digits come from the TLS
    /// exporter for **this** handshake (§4.5.1), which is what makes the comparison a real
    /// channel-binding check rather than decoration.
    private func beginPairing(socket: ControlConnection, remotePeerId: PeerId, peerSpki: SpkiHash) async {
        let exchange = PairingExchange(
            remotePeerId: remotePeerId,
            peerIdentitySpkiSha256: peerSpki,
            isInitiator: socket.isInitiator,
            trustedPeers: trustedPeers,
            nowEpochSeconds: nowEpochSeconds
        )
        pairing = exchange
        switch exchange.begin(derivedSas6: socket.security?.deriveSas6()) {
        case .failed(let code):
            await failPairing(socket: socket, code: code)
        default:
            guard let sas6 = exchange.sas6 else { return }
            updatePairingPrompt(PairingPrompt(sas6: sas6, remotePeerId: remotePeerId, peerDisplayName: exchange.peerDisplayName))
            emit(.pairingRequired(remotePeerId: remotePeerId))
            if socket.isInitiator, let local = localIdentity {
                try? await socket.writeFrame(
                    ControlMessages.pairRequest(
                        localPeerId: localPeerId,
                        sessionId: activeSessionId,
                        seq: seqCounter.nextSeq(),
                        sentAtMonoUs: monotonicNowUs(),
                        displayName: local.displayName,
                        platform: local.platform,
                        identitySpkiSha256: local.identitySpkiSha256
                    )
                )
            }
        }
    }

    /// The user's answer on **this** device. Both users must confirm before any pin is written —
    /// one screen's "yes" is only half of the check PROTOCOL §4.5 describes.
    public func confirmPairing(accepted: Bool) async {
        guard let exchange = pairing, let socket = activeSocket else { return }
        await applyPairingStep(socket: socket, exchange: exchange, step: exchange.onLocalDecision(accepted: accepted))
    }

    private func applyPairingStep(socket: ControlConnection, exchange: PairingExchange, step: PairingExchange.Step) async {
        switch step {
        case .wait:
            break
        case .sendPairConfirm:
            try? await socket.writeFrame(
                ControlMessages.pairConfirm(
                    localPeerId: localPeerId, sessionId: activeSessionId,
                    seq: seqCounter.nextSeq(), sentAtMonoUs: monotonicNowUs(), accepted: true))
        case .sendPairResultAccepted:
            if let local = localIdentity {
                try? await socket.writeFrame(
                    ControlMessages.pairResult(
                        localPeerId: localPeerId, sessionId: activeSessionId,
                        seq: seqCounter.nextSeq(), sentAtMonoUs: monotonicNowUs(),
                        accepted: true, identitySpkiSha256: local.identitySpkiSha256))
            }
            if let peer = exchange.completedPeer() { succeedPairing(peer) }
        case .succeeded(let peer):
            succeedPairing(peer)
        case .failed(let code):
            await failPairing(socket: socket, code: code)
        }
    }

    private func succeedPairing(_ peer: TrustedPeer) {
        pairing = nil
        updatePairingPrompt(nil) // the six digits stop meaning anything the moment this settles
        emit(.pairingSucceeded(peer: peer))
    }

    private func failPairing(socket: ControlConnection, code: String) async {
        pairing = nil
        updatePairingPrompt(nil)
        emit(.pairingFailed(code: code))
        // PROTOCOL §4.5: "on failure, close the connection." A half-paired session is not a state
        // worth having — the peer is still untrusted, so nothing may be done over it.
        await refuse(socket, code: code)
    }

    /// Pairing frames arrive on the same read loop as everything else. Every field is peer-chosen,
    /// so each is read through a non-trapping accessor and a malformed one ends the exchange rather
    /// than the process — the same rule the handshake follows.
    ///
    /// A pairing frame with no exchange in progress is dropped, not treated as an error: it is what
    /// a duplicated or late frame looks like after the exchange has already settled.
    private func handlePairingFrame(socket: ControlConnection, type: String, payload: [String: JSONValue]) async {
        guard let exchange = pairing else { return }
        let step: PairingExchange.Step
        switch type {
        case "PAIR_REQUEST":
            guard let spki = payload["identity_spki_sha256"]?.stringValue.flatMap(SpkiHash.parse) else {
                return await failPairing(socket: socket, code: errorCodeMalformedFrame)
            }
            step = exchange.onPairRequest(
                displayName: payload["display_name"]?.stringValue ?? "",
                advertisedSpki: spki)
            // The peer's name only becomes known here, and the prompt is already on screen by then
            // — refresh it rather than showing a blank name.
            if var prompt = pairingPrompt {
                prompt.peerDisplayName = exchange.peerDisplayName
                updatePairingPrompt(prompt)
            }
        case "PAIR_CONFIRM":
            guard let accepted = payload["sas6_accepted"]?.boolValue else {
                return await failPairing(socket: socket, code: errorCodeMalformedFrame)
            }
            step = exchange.onPairConfirm(accepted: accepted)
        default:
            guard let accepted = payload["accepted"]?.boolValue,
                  let spki = payload["identity_spki_sha256"]?.stringValue.flatMap(SpkiHash.parse)
            else {
                return await failPairing(socket: socket, code: errorCodeMalformedFrame)
            }
            step = exchange.onPairResult(accepted: accepted, advertisedSpki: spki)
        }
        await applyPairingStep(socket: socket, exchange: exchange, step: step)
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
            pingRequests.succeed(id: t1, with: ClockSync.Sample(t1MonoUs: t1, t2MonoUs: t2, t3MonoUs: t3, t4MonoUs: t4))
            updateDiagnostics { $0.rttMs = Double((t4 - t1) - (t3 - t2)) / 1000.0 }
        case "PAIR_REQUEST", "PAIR_CONFIRM", "PAIR_RESULT":
            await handlePairingFrame(socket: socket, type: envelope.type, payload: envelope.payload)
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

    /// Issues the burst's pings **~50ms apart, without waiting for each one's PONG before sending
    /// the next** (CI-stabilization note). The previous, fully-sequential design (`await` each
    /// ping's full round trip — up to its 3s timeout — before sending the next) meant a single
    /// slow-but-not-lost PONG serialized the *entire* burst: N moderately delayed round trips add
    /// up, and one genuinely lost PONG cost the full 3s by itself. Pipelining bounds the burst's
    /// total duration by roughly `spacing × (count − 1) + the single slowest round trip`, not the
    /// *sum* of all of them — this is what let `testRepeatedClockBurstsAllCompleteQuickly` still
    /// occasionally miss its 2.5s deadline under real scheduling contention even after the
    /// orphan-timeout-task fix (GitHub Actions run 33033917411, and reproduced locally at roughly
    /// 1-in-10 runs on a loaded machine). `sendPingAndAwait`'s own registration-before-write
    /// ordering, id-collision guard and orphan-timeout cancellation are all per-request and
    /// unaffected by issuing several at once.
    private func runClockBurst(socket: ControlConnection) async {
        var pingTasks: [Task<ClockSync.Sample?, Never>] = []
        for _ in 0..<Self.clockBurstSampleCount {
            if Task.isCancelled { break }
            pingTasks.append(Task { try? await self.sendPingAndAwait(socket: socket, timeoutMs: Self.pingTimeoutMs) })
            try? await Task.sleep(nanoseconds: Self.clockBurstSpacingNs)
            if Task.isCancelled { break }
        }
        var samples: [ClockSync.Sample] = []
        for task in pingTasks {
            if let sample = await task.value {
                samples.append(sample)
            }
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
    /// PONG would find no entry in `pingRequests` and be silently dropped until timeout (this
    /// session's brief §2). Registering first, synchronously, before any suspension closes the
    /// race: `withCheckedThrowingContinuation`'s body runs on this actor's isolation with no
    /// `await` in it, so `pingRequests.register(...)` happens atomically with respect to any
    /// other actor-isolated code, including `handleFrame`.
    ///
    /// **CI-stabilization fix:** `id` is reserved via `pingRequests.reserveUniqueId`, not the raw
    /// `monotonicNowUs()` read — two calls landing on the same microsecond (the keepalive loop and
    /// the clock-sync burst can both be mid-`sendPingAndAwait` at once) must never silently
    /// overwrite each other's waiter. The timeout `Task` is itself cancelled by
    /// `PingRequestRegistry.succeed`/`fail` the instant a request resolves, so a fast PONG never
    /// leaves an orphaned timeout sleeping out its full duration — that pile-up of near-
    /// simultaneous stale wakeups, under real CI scheduling contention, is what delayed actual PONG
    /// handling enough to blow an unrelated ping's timeout (`testRepeatedClockBurstsAllCompleteQuickly`,
    /// GitHub Actions run 33033917411). The `Task.isCancelled` guard after the sleep additionally
    /// ensures a cancelled timeout task can never call `fail` at all — closing the (astronomically
    /// unlikely but provable) hazard of a stale wakeup targeting a since-reused id.
    private func sendPingAndAwait(socket: ControlConnection, timeoutMs: Int64) async throws -> ClockSync.Sample {
        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<ClockSync.Sample, Error>) in
            let t1 = self.pingRequests.reserveAndRegister(startingFrom: self.monotonicNowUs(), continuation: continuation)
            let timeoutTask = Task {
                try? await Task.sleep(nanoseconds: UInt64(max(0, timeoutMs)) * 1_000_000)
                guard !Task.isCancelled else { return }
                self.timeoutPing(t1: t1)
            }
            self.pingRequests.attachTimeout(id: t1, timeoutTask: timeoutTask)
            Task {
                do {
                    try await socket.writeFrame(ControlMessages.ping(localPeerId: self.localPeerId, sessionId: self.activeSessionId, seq: self.seqCounter.nextSeq(), sentAtMonoUs: t1, t1MonoUs: t1))
                } catch {
                    self.failPing(t1: t1, error: error)
                }
            }
        }
    }

    /// Write failure: fail exactly once and cancel the still-sleeping timeout task. No-op if the
    /// PONG or a timeout already claimed this entry.
    private func failPing(t1: Int64, error: Error) {
        pingRequests.fail(id: t1, with: error)
    }

    private func timeoutPing(t1: Int64) {
        pingRequests.fail(id: t1, with: ControlTransportError.notReady)
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
    /// leave it to time out on a socket that is already dead (this session's brief §10), and
    /// must cancel their timeout tasks so none linger past this manager's lifetime.
    private func failAllPendingPings(with error: Error) {
        pingRequests.failAll(with: error)
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
        pairing = nil
        updatePairingPrompt(nil)
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
