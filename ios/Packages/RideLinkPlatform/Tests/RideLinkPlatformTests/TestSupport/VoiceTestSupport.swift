import Foundation
import RideLinkCore
@testable import RideLinkPlatform

/// Records what an authenticated peer's `VOICE_*` frames actually deliver.
final class VoiceSignalSpy: VoiceSignalSink, @unchecked Sendable {
    private let lock = NSLock()
    private var log: [VoiceSignal] = []

    var received: [VoiceSignal] {
        lock.lock()
        defer { lock.unlock() }
        return log
    }

    func submit(_ signal: VoiceSignal) {
        lock.lock()
        defer { lock.unlock() }
        log.append(signal)
    }
}

/// A `VoiceEngine` with no WebRTC in it: it records what it was asked to do and emits whatever a test
/// tells it to.
///
/// **A passing test against this proves the controller, not the codec.** It says nothing about whether
/// real Opus over real DTLS-SRTP works — that is what `VoiceEngineLoopbackTests` (real WebRTC, real
/// media, on this machine) and the real-device gate are for.
actor FakeVoiceEngine: VoiceEngine {
    private(set) var calls: [String] = []
    private(set) var muted: Bool?
    var startResult: Result<Void, VoiceEngineError> = .success(())
    private var sink: (@Sendable (VoiceEngineEvent) -> Void)?
    private var current = VoiceEngineDiagnostics()

    init() {
        current.audioProcessing = AudioProcessingStatus(
            echoCancellationEnabled: true, noiseSuppressionEnabled: true, autoGainControlEnabled: true
        )
    }

    func recordedCalls() -> [String] { calls }

    func mutedState() -> Bool? { muted }

    func setStartResult(_ result: Result<Void, VoiceEngineError>) { startResult = result }

    func setEventSink(_ sink: @escaping @Sendable (VoiceEngineEvent) -> Void) async { self.sink = sink }

    func emit(_ event: VoiceEngineEvent) { sink?(event) }

    func diagnostics() async -> VoiceEngineDiagnostics { current }

    func start(config: VoiceEngineConfig) async -> Result<Void, VoiceEngineError> {
        calls.append("start(\(config.voiceSessionId.value))")
        if case .success = startResult {
            current.transportState = .new
            current.localAudioTrackPresent = true
        }
        return startResult
    }

    func createOffer() async -> Result<Void, VoiceEngineError> {
        calls.append("createOffer")
        return .success(())
    }

    func createAnswer() async -> Result<Void, VoiceEngineError> {
        calls.append("createAnswer")
        return .success(())
    }

    func applyRemoteDescription(kind: SdpKind, sdp: String) async -> Result<Void, VoiceEngineError> {
        calls.append("applyRemote(\(kind == .offer ? "OFFER" : "ANSWER"))")
        return .success(())
    }

    func addRemoteCandidate(
        candidate: String,
        sdpMid: String?,
        sdpMlineIndex: Int
    ) async -> Result<Void, VoiceEngineError> {
        calls.append("addRemoteCandidate(\(sdpMlineIndex))")
        return .success(())
    }

    func setMicrophoneMuted(_ muted: Bool) async {
        self.muted = muted
        calls.append("setMicrophoneMuted(\(muted))")
    }

    func stop() async {
        calls.append("stop")
        current.transportState = .closed
        current.remoteAudioTrackPresent = false
    }

    func release() async {
        calls.append("release")
        current = VoiceEngineDiagnostics()
        current.transportState = .closed
    }

    func refreshDiagnostics() async { calls.append("refreshDiagnostics") }
}

/// A `VoiceAudioSession` that records open/close without touching a real audio route.
actor FakeVoiceAudioSession: VoiceAudioSession {
    private(set) var calls: [String] = []
    private var open = false
    private var snapshot = AudioRouteSnapshot()
    private var sink: (@Sendable (AudioRouteSnapshot) -> Void)?
    var openResult: Result<Void, VoiceEngineError> = .success(())

    init() {}

    func recordedCalls() -> [String] { calls }

    func setOpenResult(_ result: Result<Void, VoiceEngineError>) { openResult = result }

    func isOpen() async -> Bool { open }

    func route() async -> AudioRouteSnapshot { snapshot }

    func setRouteSink(_ sink: @escaping @Sendable (AudioRouteSnapshot) -> Void) async { self.sink = sink }

    func publish(_ next: AudioRouteSnapshot) {
        snapshot = next
        sink?(next)
    }

    func open() async -> Result<Void, VoiceEngineError> {
        calls.append("open")
        if case .success = openResult { open = true }
        return openResult
    }

    func close() async {
        calls.append("close")
        open = false
    }
}

/// Records the `VOICE_*` frames the controller decided to send.
actor RecordingVoiceTransport: VoiceSignalTransport {
    private var log: [VoiceSignal] = []
    var accept = true

    init() {}

    func sentSignals() -> [VoiceSignal] { log }

    func send(_ signal: VoiceSignal) async -> Bool {
        guard accept else { return false }
        log.append(signal)
        return true
    }
}

/// A deterministic generation source, so a test can name the ids it expects to see.
final class SequencedVoiceSessionIds: @unchecked Sendable {
    private let lock = NSLock()
    private let ids: [String]
    private var index = 0

    init(_ ids: String...) { self.ids = ids }

    func next() -> VoiceSessionId {
        lock.lock()
        defer { lock.unlock() }
        let value = ids[min(index, ids.count - 1)]
        index += 1
        return VoiceSessionId(value)
    }
}

extension VoiceSignal {
    /// Convenience for asserting on the kind without switching in every test.
    var testKind: String { kindName }
}
