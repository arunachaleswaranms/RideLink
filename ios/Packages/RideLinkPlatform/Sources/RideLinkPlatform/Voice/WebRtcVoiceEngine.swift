import Foundation
import RideLinkCore
import WebRTC

/// The real WebRTC media plane on Apple platforms (ADR-003, ADR-020), behind `RideLinkPlatform.Voice`
/// so it stays replaceable and so `WebRTC` types appear in exactly one file.
///
/// Three properties of this actor are load-bearing rather than cosmetic:
///
/// 1. **ICE is configured with an empty server list** and there is no code path here that can add one
///    — `RTCConfiguration.iceServers` is set from `[]`, and `VoiceEngineConfig` deliberately has no
///    field to carry one (PROTOCOL §7.6). No STUN, no TURN, no accidental egress.
/// 2. **Nothing here logs an SDP or a candidate string.** Candidates are reduced to their `typ`
///    (`IceCandidateType`) before anything else looks at them, so the address and port are never even
///    extracted (PROTOCOL §7.7).
/// 3. **Every value leaving a WebRTC callback is reduced to a `Sendable` primitive inside that
///    callback.** `RTCSessionDescription`, `RTCIceCandidate` and `RTCStatisticsReport` are not
///    `Sendable`, so under Swift 6 strict concurrency the compiler refuses the alternative — which
///    happens to coincide exactly with the wire boundary the protocol already has (ADR-020).
///
/// ### What has and has not been proven
///
/// `VoiceEngineLoopbackTests` drives **two of these against each other on this machine**, over real
/// DTLS-SRTP with real Opus, and asserts host-only candidates. That is real media, and it runs under
/// `swift test` because `stasel/WebRTC`'s XCFramework carries a macOS slice.
///
/// What it is **not**: an iPhone. The audio *device* path — `RTCAudioSession`, a Bluetooth route, a
/// helmet unit, a screen lock — is macOS-absent and iPhone-only, and remains
/// **REAL-DEVICE AUDIO GATE PENDING** (docs/STATUS.md §7).
public actor WebRtcVoiceEngine: VoiceEngine {
    private var factory: RTCPeerConnectionFactory?
    private var peerConnection: RTCPeerConnection?
    private var localTrack: RTCAudioTrack?
    private var audioSource: RTCAudioSource?
    private var delegate: PeerConnectionObserver?

    private var generation: VoiceSessionId?
    private var sink: (@Sendable (VoiceEngineEvent) -> Void)?
    private var current = VoiceEngineDiagnostics()

    public init() {}

    public func setEventSink(_ sink: @escaping @Sendable (VoiceEngineEvent) -> Void) async {
        self.sink = sink
    }

    public func diagnostics() async -> VoiceEngineDiagnostics { current }

    public func start(config: VoiceEngineConfig) async -> Result<Void, VoiceEngineError> {
        await stop()
        Self.initializeSSLOnce()

        // Reused across rebuilds when one already exists: constructing a factory builds a new audio
        // unit, and opening it again is an audible Bluetooth profile renegotiation. See
        // `VoiceEngine.stop`.
        let activeFactory = factory ?? RTCPeerConnectionFactory(encoderFactory: nil, decoderFactory: nil)
        factory = activeFactory

        let rtcConfig = RTCConfiguration()
        // PROTOCOL §7.6. Both peers are on one LAN or one phone's hotspot, so host candidates suffice
        // and an empty list removes an accidental-egress path.
        rtcConfig.iceServers = []
        rtcConfig.sdpSemantics = .unifiedPlan
        // Gather continually so a Wi-Fi -> hotspot interface change on a moving motorcycle can surface
        // a fresh host candidate without a full renegotiation.
        rtcConfig.continualGatheringPolicy = .gatherContinually
        // With no TURN server configured there is nothing to relay through, so the empty server list
        // above is what does the work; `.all` here means "host, and reflexive/relay if they existed".
        rtcConfig.iceTransportPolicy = .all
        // A single audio m-line, bundled and rtcp-muxed: one UDP flow, which is what a narrow duplex
        // link wants.
        rtcConfig.bundlePolicy = .maxBundle
        rtcConfig.rtcpMuxPolicy = .require

        let observer = PeerConnectionObserver(voiceSessionId: config.voiceSessionId) { [weak self] event in
            Task { await self?.forward(event) }
        }
        delegate = observer

        guard
            let connection = activeFactory.peerConnection(
                with: rtcConfig,
                constraints: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil),
                delegate: observer
            )
        else {
            let error = VoiceEngineError.platformFailure("peerConnection returned nil")
            // Not a media callback: start() reporting its own failed outcome must not be suppressed
            // by the generation guard, since `generation` is only installed on success (mirrors
            // Android's WebRtcVoiceEngine.start()). Delivered directly through `sink`, never through
            // `emit`, which is for peer-connection callbacks only.
            sink?(.failed(voiceSessionId: config.voiceSessionId, error: error))
            return .failure(error)
        }
        peerConnection = connection

        let source = audioSource ?? activeFactory.audioSource(with: Self.audioConstraints(config.audioProcessing))
        audioSource = source
        let track = localTrack ?? activeFactory.audioTrack(with: source, trackId: config.localTrackId)
        localTrack = track
        track.isEnabled = true
        // One audio track per peer (ADR-003). Full duplex: this side always adds a sender, and muting
        // later disables the track rather than removing it (PROTOCOL §7.4).
        connection.add(track, streamIds: [Self.streamId])

        generation = config.voiceSessionId
        current = VoiceEngineDiagnostics()
        current.transportState = .new
        current.localAudioTrackPresent = true
        current.audioProcessing = Self.requestedProcessingStatus(config.audioProcessing)
        return .success(())
    }

    public func createOffer() async -> Result<Void, VoiceEngineError> {
        await createDescription(isOffer: true)
    }

    public func createAnswer() async -> Result<Void, VoiceEngineError> {
        await createDescription(isOffer: false)
    }

    private func createDescription(isOffer: Bool) async -> Result<Void, VoiceEngineError> {
        guard let connection = peerConnection, let id = generation else {
            return .failure(.notStarted("engine not started"))
        }
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        // The continuation resumes with a `Sdp` struct, never an `RTCSessionDescription`: the ObjC type
        // is not `Sendable`, so the reduction has to happen inside the callback (ADR-020).
        let created: Result<Sdp, VoiceEngineError> = await withCheckedContinuation { continuation in
            // `@Sendable` explicitly: on iOS the SDK annotates this completion handler as sendable
            // while on macOS it does not, so without it the same source produces a data-race warning
            // on one platform and not the other. The closure only captures the continuation (which is
            // `Sendable`) and reduces the non-`Sendable` `RTCSessionDescription` to a `Sdp` value
            // before it escapes — which is the ADR-020 §7 rule this whole file follows anyway.
            let handler: @Sendable (RTCSessionDescription?, Error?) -> Void = { description, error in
                if let description {
                    continuation.resume(returning: .success(Sdp(description)))
                } else {
                    continuation.resume(returning: .failure(.sdpFailed(error?.localizedDescription ?? "unknown")))
                }
            }
            if isOffer {
                connection.offer(for: constraints, completionHandler: handler)
            } else {
                connection.answer(for: constraints, completionHandler: handler)
            }
        }
        guard case .success(let sdp) = created else {
            if case .failure(let error) = created { return .failure(error) }
            return .failure(.sdpFailed("unknown"))
        }

        if case .failure(let error) = await setLocal(connection, sdp) { return .failure(error) }
        emit(
            expected: id,
            isOffer
                ? .offerCreated(voiceSessionId: id, sdp: sdp.sdp)
                : .answerCreated(voiceSessionId: id, sdp: sdp.sdp)
        )
        return .success(())
    }

    private func setLocal(_ connection: RTCPeerConnection, _ sdp: Sdp) async -> Result<Void, VoiceEngineError> {
        await withCheckedContinuation { continuation in
            connection.setLocalDescription(sdp.description) { error in
                if let error {
                    continuation.resume(returning: .failure(.sdpFailed(error.localizedDescription)))
                } else {
                    continuation.resume(returning: .success(()))
                }
            }
        }
    }

    public func applyRemoteDescription(kind: SdpKind, sdp: String) async -> Result<Void, VoiceEngineError> {
        guard let connection = peerConnection else { return .failure(.notStarted("engine not started")) }
        let description = RTCSessionDescription(type: kind == .offer ? .offer : .answer, sdp: sdp)
        return await withCheckedContinuation { continuation in
            connection.setRemoteDescription(description) { error in
                if let error {
                    continuation.resume(returning: .failure(.sdpFailed(error.localizedDescription)))
                } else {
                    continuation.resume(returning: .success(()))
                }
            }
        }
    }

    /// A candidate WebRTC itself refuses is **counted, not fatal** (PROTOCOL §7.4): a peer can send a
    /// syntactically valid line the stack still dislikes, and one bad candidate must not fail a
    /// negotiation whose other candidates are fine.
    public func addRemoteCandidate(
        candidate: String,
        sdpMid: String?,
        sdpMlineIndex: Int
    ) async -> Result<Void, VoiceEngineError> {
        guard let connection = peerConnection else { return .failure(.notStarted("engine not started")) }
        let type = IceCandidateType.fromCandidateLine(candidate)
        current.observedCandidateTypes.insert(type)
        let ice = RTCIceCandidate(sdp: candidate, sdpMLineIndex: Int32(sdpMlineIndex), sdpMid: sdpMid)
        return await withCheckedContinuation { continuation in
            connection.add(ice) { error in
                continuation.resume(returning: error == nil ? .success(()) : .failure(.candidateRejected(type)))
            }
        }
    }

    /// Gates *transmission*, not the hardware. ARCHITECTURE §6.3: the capture device stays open for a
    /// whole ride segment and PTT/VOX/mute gate what leaves — thrashing a Bluetooth endpoint between
    /// its media and duplex profiles per utterance is the worst thing this product can do to music.
    public func setMicrophoneMuted(_ muted: Bool) async {
        localTrack?.isEnabled = !muted
    }

    public func stop() async {
        // Order matters: drop the generation first, so any delegate call the platform makes during
        // teardown is already inert by the PROTOCOL §7.8 generation guard rather than racing it.
        generation = nil
        delegate = nil
        peerConnection?.close()
        peerConnection = nil
        // The factory, the audio source and the local track deliberately survive — see the protocol
        // doc. Disposing them here would close the audio unit, and reopening it is an audible
        // Bluetooth profile renegotiation on every control-plane blip.
        current.transportState = .closed
        current.iceGatheringState = .new
        current.remoteAudioTrackPresent = false
        current.selectedLocalType = nil
        current.selectedRemoteType = nil
        current.dtlsState = nil
    }

    public func release() async {
        await stop()
        localTrack = nil
        audioSource = nil
        factory = nil
        current = VoiceEngineDiagnostics()
        current.transportState = .closed
    }

    public func refreshDiagnostics() async {
        guard let connection = peerConnection else { return }
        // `RTCStatisticsReport` is not `Sendable`, so it is flattened to primitives **inside** the
        // callback. That is not a workaround: it is also exactly the set of values PROTOCOL §7.7
        // permits, so the fields it forbids are never carried out of the callback at all.
        let flat: [String: [String: String]] = await withCheckedContinuation { continuation in
            connection.statistics { report in
                var out: [String: [String: String]] = [:]
                for (key, stat) in report.statistics {
                    var values: [String: String] = [VoiceStatsMapping.typeKey: stat.type]
                    for (name, value) in stat.values {
                        values[name] = String(describing: value)
                    }
                    out[key] = values
                }
                continuation.resume(returning: out)
            }
        }
        current = VoiceStatsMapping.merge(base: current, flattened: flat)
    }

    // MARK: - platform plumbing

    private func forward(_ event: VoiceEngineEvent) {
        switch event {
        case .localCandidateGathered(_, let candidate, _, _):
            current.observedCandidateTypes.insert(IceCandidateType.fromCandidateLine(candidate))
        case .transportStateChanged(_, let transportState):
            current.transportState = transportState
        case .remoteTrackChanged(_, let present):
            current.remoteAudioTrackPresent = present
        default:
            break
        }
        emit(expected: event.voiceSessionId, event)
    }

    private func emit(expected: VoiceSessionId, _ event: VoiceEngineEvent) {
        // Generation-checked at the source as well as in the table, so a callback from a torn-down
        // connection cannot even reach the controller's queue. Strict on purpose
        // (RideLinkCore.VoiceEngineGeneration): after `stop()`, `generation` is `nil`, and a callback
        // naming any generation -- including a real one that used to be current -- must be inert
        // then. This is for *peer-connection callbacks only*; `start()` reports its own failure
        // directly through `sink`, never through this method.
        guard VoiceEngineGeneration.accepts(active: generation, expected: expected) else { return }
        sink?(event)
    }

    /// Voice-oriented and mono. Not tuned further: ADR-003 says measure before tuning, and nobody has.
    private static func audioConstraints(_ processing: AudioProcessingConfig) -> RTCMediaConstraints {
        RTCMediaConstraints(
            mandatoryConstraints: [
                "googEchoCancellation": processing.echoCancellation ? "true" : "false",
                "googNoiseSuppression": processing.noiseSuppression ? "true" : "false",
                "googAutoGainControl": processing.autoGainControl ? "true" : "false",
            ],
            optionalConstraints: nil
        )
    }

    private static func requestedProcessingStatus(_ processing: AudioProcessingConfig) -> AudioProcessingStatus {
        AudioProcessingStatus(
            echoCancellationEnabled: processing.echoCancellation,
            noiseSuppressionEnabled: processing.noiseSuppression,
            autoGainControlEnabled: processing.autoGainControl,
            // Apple's stack does not report whether AEC is hardware-accelerated, and inventing an
            // answer here would be inventing a measurement. Nil means "the platform has not told us".
            hardwareEchoCancellation: nil
        )
    }

    private static let streamId = "ridelink"
    private static let sslInitialized = SSLInitLatch()

    /// `RTCInitializeSSL` is process-global and must run exactly once before any peer connection.
    private static func initializeSSLOnce() {
        sslInitialized.once { RTCInitializeSSL() }
    }

    /// A `Sendable` reduction of `RTCSessionDescription`, made inside the callback that produced it.
    private struct Sdp: Sendable {
        let type: RTCSdpType
        let sdp: String

        init(_ description: RTCSessionDescription) {
            type = description.type
            sdp = description.sdp
        }

        var description: RTCSessionDescription { RTCSessionDescription(type: type, sdp: sdp) }
    }
}

/// One-shot latch for the process-global `RTCInitializeSSL`.
private final class SSLInitLatch: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false

    func once(_ body: () -> Void) {
        lock.lock()
        defer { lock.unlock() }
        if done { return }
        done = true
        body()
    }
}

/// Bridges `RTCPeerConnectionDelegate` — a non-`Sendable` ObjC protocol invoked on WebRTC's own threads
/// — into `Sendable` `VoiceEngineEvent`s.
///
/// The reduction happens here, in the callback, for the reason ADR-020 records: `RTCIceCandidate` and
/// friends cannot cross an isolation boundary under Swift 6, and the values that *can* are exactly the
/// ones PROTOCOL §7.7 allows out. `@unchecked Sendable` is confined to holding two immutable values.
private final class PeerConnectionObserver: NSObject, RTCPeerConnectionDelegate, @unchecked Sendable {
    private let voiceSessionId: VoiceSessionId
    private let onEvent: @Sendable (VoiceEngineEvent) -> Void

    init(voiceSessionId: VoiceSessionId, onEvent: @escaping @Sendable (VoiceEngineEvent) -> Void) {
        self.voiceSessionId = voiceSessionId
        self.onEvent = onEvent
    }

    func peerConnection(_ connection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        onEvent(
            .localCandidateGathered(
                voiceSessionId: voiceSessionId,
                candidate: candidate.sdp,
                sdpMid: candidate.sdpMid,
                sdpMlineIndex: Int(candidate.sdpMLineIndex)
            )
        )
    }

    func peerConnection(_ connection: RTCPeerConnection, didChange state: RTCPeerConnectionState) {
        onEvent(.transportStateChanged(voiceSessionId: voiceSessionId, state: state.transportState))
    }

    func peerConnection(_ connection: RTCPeerConnection, didAdd receiver: RTCRtpReceiver, streams: [RTCMediaStream]) {
        let present = receiver.track?.kind == kRTCMediaStreamTrackKindAudio
        onEvent(.remoteTrackChanged(voiceSessionId: voiceSessionId, present: present))
    }

    func peerConnection(_ connection: RTCPeerConnection, didRemove receiver: RTCRtpReceiver) {
        onEvent(.remoteTrackChanged(voiceSessionId: voiceSessionId, present: false))
    }

    // Not a failure: PROTOCOL §7.6 expects only host candidates, and a gathering error against a
    // server we never configured is noise. Nothing here records an address.
    func peerConnection(_ connection: RTCPeerConnection, didFailToGatherIceCandidate event: RTCIceCandidateErrorEvent) {}

    func peerConnection(_ connection: RTCPeerConnection, didChange state: RTCSignalingState) {}
    func peerConnection(_ connection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    func peerConnection(_ connection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnection(_ connection: RTCPeerConnection, didChange state: RTCIceConnectionState) {}
    func peerConnection(_ connection: RTCPeerConnection, didChange state: RTCIceGatheringState) {}
    func peerConnection(_ connection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ connection: RTCPeerConnection, didOpen channel: RTCDataChannel) {}

    /// ADR-003 / CLAUDE.md rule 3: control traffic never rides a WebRTC DataChannel, and V1 does not
    /// renegotiate in place — PROTOCOL §7.4 tears down and negotiates afresh instead.
    func peerConnectionShouldNegotiate(_ connection: RTCPeerConnection) {}
}

private extension RTCPeerConnectionState {
    var transportState: MediaTransportState {
        switch self {
        case .new: return .new
        case .connecting: return .connecting
        case .connected: return .connected
        case .disconnected: return .disconnected
        case .failed: return .failed
        case .closed: return .closed
        @unknown default: return .unknown
        }
    }
}

private extension VoiceEngineEvent {
    var voiceSessionId: VoiceSessionId {
        switch self {
        case .offerCreated(let id, _), .answerCreated(let id, _),
             .localCandidateGathered(let id, _, _, _),
             .transportStateChanged(let id, _), .remoteTrackChanged(let id, _),
             .failed(let id, _):
            return id
        }
    }
}
