import Foundation
import RideLinkCore
@testable import RideLinkPlatform

/// Records what an authenticated peer's `VOICE_*` frames actually deliver.
/// Records what the receiver's `AUDIO_STATE` sink actually got (PROTOCOL §4.4).
final class AudioStateSpy: AudioStateSink, @unchecked Sendable {
    private let lock = NSLock()
    private var log: [AudioStateMessage] = []

    var received: [AudioStateMessage] {
        lock.lock()
        defer { lock.unlock() }
        return log
    }

    func submit(_ message: AudioStateMessage) {
        lock.lock()
        defer { lock.unlock() }
        log.append(message)
    }
}

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

    /// Empties the call log, so an assertion about what happened *after* a point is not satisfied by
    /// something that happened before it.
    func clearCalls() { calls.removeAll() }

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
///
/// `openCaptureCount` and `closeCaptureCount` exist for one specific test:
/// `VoiceControllerIntercomTests` presses PTT fifty times and asserts they stay at 1 and 0. That is the
/// laptop half of TEST_PLAN A-10, which asserts the same invariant against a real TWS set's recorded
/// output — the capture device is opened once for a ride segment, and PTT gates transmission rather than
/// hardware (ARCHITECTURE §6.3).
actor FakeVoiceAudioSession: VoiceAudioSession {
    private(set) var calls: [String] = []
    private var open = false
    private var snapshot = AudioRouteSnapshot()
    private var sink: (@Sendable (AudioRouteSnapshot) -> Void)?
    var openResult: Result<Void, VoiceAudioSessionError> = .success(())

    /// How many times the capture path was **actually** opened (a no-op re-open does not count).
    private(set) var openCaptureCount = 0
    private(set) var closeCaptureCount = 0

    init() {}

    func recordedCalls() -> [String] { calls }

    func captureCounts() -> (opened: Int, closed: Int) { (openCaptureCount, closeCaptureCount) }

    func setOpenResult(_ result: Result<Void, VoiceAudioSessionError>) { openResult = result }

    func isOpen() async -> Bool { open }

    func route() async -> AudioRouteSnapshot { snapshot }

    func setRouteSink(_ sink: @escaping @Sendable (AudioRouteSnapshot) -> Void) async { self.sink = sink }

    func publish(_ next: AudioRouteSnapshot) {
        snapshot = next
        sink?(next)
    }

    func open() async -> Result<Void, VoiceAudioSessionError> {
        calls.append("open")
        // The real sessions are idempotent — `IosVoiceAudioSession.open` returns early when already
        // open, and `AndroidVoiceAudioSession` likewise — so an already-open session does not count as a
        // second capture open. Mirroring that here is what makes the A-10 counters mean the same thing
        // as the hardware measurement will.
        if open { return .success(()) }
        if case .success = openResult {
            open = true
            openCaptureCount += 1
        }
        return openResult
    }

    func close() async {
        calls.append("close")
        if open { closeCaptureCount += 1 }
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
