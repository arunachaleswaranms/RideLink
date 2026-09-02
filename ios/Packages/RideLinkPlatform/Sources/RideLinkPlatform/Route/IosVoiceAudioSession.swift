import Foundation
import RideLinkCore

#if os(iOS)
import AVFoundation

/// The iOS half of the audio route: `AVAudioSession`'s duplex configuration, its three notifications,
/// and the route mapped into ADR-016's platform-neutral vocabulary by `IosAudioRouteMapper`.
///
/// ARCHITECTURE §6.2 specifies **two** audio-session configurations and this class owns the switch
/// between them:
///
/// | Ride phase | Category | Mode | Options |
/// |---|---|---|---|
/// | music only | `.playback` | `.default` | — |
/// | intercom active | `.playAndRecord` | `.voiceChat` | `[.allowBluetoothHFP, .allowBluetoothA2DP, .duckOthers]` |
///
/// `.allowBluetoothHFP` is the current spelling; the deprecated `.allowBluetooth` appears nowhere. With
/// the ADR-011 iOS 26.0 deployment target there is no availability branch, which is one of the reasons
/// that baseline was chosen.
///
/// **`.allowBluetoothA2DP` here must not be read as "media output plus duplex input at the same time".**
/// With a live input on a Bluetooth device the output follows the input onto the duplex profile. That is
/// the whole shape of the product's biggest risk, and it is reported as
/// `profileCoupling: .inputForcesOutput` rather than wished away.
///
/// All three notifications ARCHITECTURE §6.2 names are handled, not just the first: route change,
/// interruption, **and** media-services-reset.
///
/// **Nothing here has run on a phone.** `AVAudioSession` is unavailable on macOS, so it cannot be
/// exercised by `swift test`, and no physical iPhone is available in this environment.
/// `IosAudioRouteMapper` is pure and is unit-tested; everything in this class is
/// **REAL-DEVICE AUDIO GATE PENDING** (docs/STATUS.md §7).
public actor IosVoiceAudioSession: VoiceAudioSession {
    private let session = AVAudioSession.sharedInstance()
    private var observers: [NSObjectProtocol] = []
    private var open = false
    private var snapshot = AudioRouteSnapshot()
    private var sink: (@Sendable (AudioRouteSnapshot) -> Void)?

    public init() {}

    public func isOpen() async -> Bool { open }

    public func route() async -> AudioRouteSnapshot { snapshot }

    public func setRouteSink(_ sink: @escaping @Sendable (AudioRouteSnapshot) -> Void) async {
        self.sink = sink
    }

    /// Returns a failure rather than throwing, and never proceeds *pretending* to have a microphone: a
    /// denied record permission means the ride continues music-only with an amber status, which is
    /// FR-025's graceful degradation, not an error to swallow.
    public func open() async -> Result<Void, VoiceEngineError> {
        if open { return .success(()) }
        guard await hasMicrophonePermission() else {
            publish(snapshot.withMicrophone(open: false))
            return .failure(.notStarted("microphone permission not granted"))
        }
        do {
            try session.setCategory(
                .playAndRecord,
                mode: .voiceChat,
                options: [.allowBluetoothHFP, .allowBluetoothA2DP, .duckOthers]
            )
            try session.setActive(true)
            registerObservers()
            open = true
            // ADR-016 makes a route change a first-class state rather than a moment when every other
            // field is quietly stale, and ARCHITECTURE §7.3 suspends the drift ladder while either peer
            // is transitioning. Switching configuration is an audible 0.5–2 s change, so it is reported
            // as `transitioning` and then as `stable`.
            refresh(reason: .categoryChange, routeState: .transitioning)
            refresh(reason: .categoryChange, routeState: .stable)
            return .success(())
        } catch {
            return .failure(.platformFailure("audio session activation failed"))
        }
    }

    public func close() async {
        guard open else { return }
        open = false
        unregisterObservers()
        // Back to the music-only configuration (ARCHITECTURE §6.2), not to "inactive": a ride may still
        // be playing music, and deactivating the session would stop it.
        try? session.setCategory(.playback, mode: .default, options: [])
        refresh(reason: .categoryChange, routeState: .transitioning)
        refresh(reason: .categoryChange, routeState: .stable)
    }

    // MARK: - the three notifications ARCHITECTURE §6.2 requires

    private func registerObservers() {
        let center = NotificationCenter.default
        observers = [
            center.addObserver(
                forName: AVAudioSession.routeChangeNotification, object: nil, queue: nil
            ) { [weak self] note in
                // Reduced to a `Sendable` enum *here*, before the Task hop: a `Notification` is not
                // `Sendable`, and the reason value is the only part that may leave the route layer
                // anyway (ADR-016 — a platform route description is a privacy leak).
                let raw = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt)
                    .flatMap(AVAudioSession.RouteChangeReason.init(rawValue:)) ?? .unknown
                let mapped = IosAudioRouteMapper.changeReason(raw)
                Task { await self?.handleRouteChange(mapped) }
            },
            center.addObserver(
                forName: AVAudioSession.interruptionNotification, object: nil, queue: nil
            ) { [weak self] note in
                let began = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt)
                    == AVAudioSession.InterruptionType.began.rawValue
                Task { await self?.handleInterruption(began: began) }
            },
            center.addObserver(
                forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: nil
            ) { [weak self] _ in
                Task { await self?.handleMediaServicesReset() }
            },
        ]
    }

    private func unregisterObservers() {
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        observers.removeAll()
    }

    private func handleRouteChange(_ reason: AudioRouteChangeReason) {
        refresh(reason: reason, routeState: .transitioning)
        refresh(reason: reason, routeState: .stable)
    }

    /// An interruption is an *audio route* fact, not a WebRTC session state. PROTOCOL §7.4 keeps
    /// `VOICE_STATE` for the media session and `AUDIO_STATE` for the route, and conflating them would
    /// make "a call came in" indistinguishable from "the peer connection failed".
    private func handleInterruption(began: Bool) {
        publish(
            snapshot.withInterruption(
                began,
                reason: began ? .interruptionBegan : .interruptionEnded
            )
        )
    }

    /// The third notification, and the one most implementations forget: the media server died and every
    /// audio object this process holds is now invalid. Reported rather than silently limped through —
    /// recovery is the user restarting voice, which the diagnostics card makes visible.
    private func handleMediaServicesReset() {
        open = false
        publish(
            AudioRouteSnapshot(
                microphoneOpen: false,
                routeState: .transitioning,
                confidence: .assumed,
                lastChangeReason: .mediaServicesReset
            )
        )
    }

    private func refresh(reason: AudioRouteChangeReason, routeState: RouteState) {
        let route = session.currentRoute
        let outputPort = route.outputs.first.map { IosAudioRouteMapper.portKind($0.portType) }
        publish(
            IosAudioRouteMapper.map(
                outputPort: outputPort,
                hasInput: !route.inputs.isEmpty,
                microphoneOpen: open,
                duplexCategoryActive: session.category == .playAndRecord,
                sampleRateHz: Int(session.sampleRate),
                lastChangeReason: reason,
                routeState: routeState,
                interrupted: snapshot.interrupted
            )
        )
    }

    private func publish(_ next: AudioRouteSnapshot) {
        snapshot = next
        sink?(next)
    }

    private func hasMicrophonePermission() async -> Bool {
        switch AVAudioApplication.shared.recordPermission {
        case .granted:
            return true
        case .undetermined:
            // Requested here, and only here, because this method is reached exclusively from an explicit
            // Start Voice action (ARCHITECTURE §6.4 step 2/6) — never at launch.
            return await AVAudioApplication.requestRecordPermission()
        default:
            return false
        }
    }
}

private extension AudioRouteSnapshot {
    func withMicrophone(open: Bool) -> AudioRouteSnapshot {
        var copy = self
        copy.microphoneOpen = open
        return copy
    }

    func withInterruption(_ interrupted: Bool, reason: AudioRouteChangeReason) -> AudioRouteSnapshot {
        var copy = self
        copy.interrupted = interrupted
        copy.lastChangeReason = reason
        return copy
    }
}

#else

/// The macOS stand-in, so `RideLinkPlatform` still builds and tests for macOS (which is what lets the
/// shared vectors and the real WebRTC loopback run under `swift test` — ADR-020).
///
/// It is **not** a fake for tests to assert against and must never be mistaken for one: it refuses to
/// open, so anything that depends on a capture device is visibly unavailable rather than quietly
/// pretending. `AVAudioSession` does not exist on macOS, and RideLink does not ship there.
public actor IosVoiceAudioSession: VoiceAudioSession {
    private var sink: (@Sendable (AudioRouteSnapshot) -> Void)?

    public init() {}

    public func isOpen() async -> Bool { false }

    public func route() async -> AudioRouteSnapshot { AudioRouteSnapshot() }

    public func setRouteSink(_ sink: @escaping @Sendable (AudioRouteSnapshot) -> Void) async {
        self.sink = sink
    }

    public func open() async -> Result<Void, VoiceEngineError> {
        .failure(.notStarted("AVAudioSession is unavailable on macOS; RideLink does not ship there"))
    }

    public func close() async {}
}

#endif
