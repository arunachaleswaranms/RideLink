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
/// ### What Phase 2b added, and why it is mostly not here
///
/// **Every decision this class used to make now lives in `AudioSessionLifecycle`**, the pure reducer
/// shared with Android: the `stable -> transitioning -> stable` sequence, `shouldResume` versus not, a
/// media-services reset invalidating the previous generation, and the strict guard that makes a callback
/// from a superseded generation inert. That is deliberate and is the direct lesson of ADR-019 and of
/// `docs/STATUS.md` §4 problem 20 — `AVAudioSession` does not exist on macOS, so anything with a
/// *decision* in it has to be somewhere `swift test` can reach.
///
/// **Notifications are delivered through one ordered mailbox, not a `Task` per callback.** A `Task` per
/// notification preserves only the order they were *created* in (STATUS §2h), and here the relative
/// order matters: an interruption ending and a route change arriving together must not be applied
/// backwards. `AudioSessionSignalBox` is coalesced by kind and therefore bounded by construction (this
/// phase's brief §38/§39), which is safe precisely because each kind is re-derived from the platform's
/// *current* state rather than describing a distinct historical occurrence.
///
/// **The route transition settles on a platform callback, never on a timer.** `routeChangeNotification`
/// with reason `.categoryChange` is what confirms the change RideLink asked for; `pollTransitionTimeout`
/// exists only so a platform that never confirms cannot leave `route_state: transitioning` latched for
/// the rest of a ride, and every use of it is counted (`RouteTransitionState.timedOutCount`).
///
/// **Nothing here has run on a phone.** `AVAudioSession` is unavailable on macOS, so it cannot be
/// exercised by `swift test`, and no physical iPhone is available in this environment.
/// `IosAudioRouteMapper` and `AudioSessionLifecycle` are pure and are unit-tested; everything in this
/// class is **REAL-DEVICE INTERCOM GATE PENDING** (docs/STATUS.md §7, TEST_PLAN IA-01…IA-09, V-01…V-11).
public actor IosVoiceAudioSession: VoiceAudioSession {
    private let session = AVAudioSession.sharedInstance()
    private var observers: [NSObjectProtocol] = []
    private var snapshot = AudioRouteSnapshot()
    private var sink: (@Sendable (AudioRouteSnapshot) -> Void)?
    private var lifecycle = AudioSessionState()
    private var lastReason: AudioRouteChangeReason = .unknown
    private let monotonicNowUs: @Sendable () -> Int64

    /// The one ordered path from `NotificationCenter` into this actor. See the type doc.
    private let signals = AudioSessionSignalBox()
    private var consumerTask: Task<Void, Never>?

    public init(
        monotonicNowUs: @escaping @Sendable () -> Int64 = { Int64(DispatchTime.now().uptimeNanoseconds / 1000) }
    ) {
        self.monotonicNowUs = monotonicNowUs
    }

    public func isOpen() async -> Bool { lifecycle.open }

    public func route() async -> AudioRouteSnapshot { snapshot }

    /// The last named reason this session refused or was interrupted (this phase's brief §41).
    public func lastFailure() async -> VoiceFailure? { lifecycle.lastFailure }

    public func setRouteSink(_ sink: @escaping @Sendable (AudioRouteSnapshot) -> Void) async {
        self.sink = sink
    }

    /// Returns a failure rather than throwing, and never proceeds *pretending* to have a microphone: a
    /// denied record permission means the ride continues music-only with an amber status, which is
    /// FR-025's graceful degradation, not an error to swallow. Every refusal is **named**
    /// (`VoiceAudioSessionError`) rather than collapsed into one bucket.
    public func open() async -> Result<Void, VoiceAudioSessionError> {
        if lifecycle.open { return .success(()) }
        guard await hasMicrophonePermission() else {
            return fail(.micPermissionDenied)
        }
        do {
            try session.setCategory(
                .playAndRecord,
                mode: .voiceChat,
                options: [.allowBluetoothHFP, .allowBluetoothA2DP, .duckOthers]
            )
            try session.setActive(true)
        } catch {
            return fail(.audioSessionActivationFailed)
        }
        startConsumer()
        registerObservers()
        apply(.opened(generation: lifecycle.generation, atMonoUs: monotonicNowUs()))
        return .success(())
    }

    public func close() async {
        guard lifecycle.open else { return }
        unregisterObservers()
        // Back to the music-only configuration (ARCHITECTURE §6.2), not to "inactive": a ride may still
        // be playing music, and deactivating the session would stop it.
        try? session.setCategory(.playback, mode: .default, options: [])
        apply(.closed(generation: lifecycle.generation, atMonoUs: monotonicNowUs()))
        consumerTask?.cancel()
        consumerTask = nil
        signals.finish()
    }

    /// **Failure protection, never the definition of success** (this phase's brief §15). A caller may
    /// poll this while a transition is outstanding; if the platform never confirmed the change, the
    /// transition is declared settled and *counted as a timeout* so the diagnostics can say the number
    /// came from a timer rather than from `AVAudioSession`.
    public func pollTransitionTimeout() async {
        apply(
            .transitionTimeoutCheck(
                generation: lifecycle.generation,
                atMonoUs: monotonicNowUs(),
                timeoutUs: RouteTransitionTracker.defaultTimeoutUs
            )
        )
    }

    // MARK: - the three notifications ARCHITECTURE §6.2 requires

    private func startConsumer() {
        guard consumerTask == nil else { return }
        let stream = signals.stream
        let box = signals
        consumerTask = Task { [weak self] in
            for await delivered in stream {
                guard let self else { return }
                // The element the stream handed over may have been superseded while it waited, so the
                // newest value for that kind is taken from the box rather than trusting the delivery.
                // That is what makes the coalescing correct rather than merely cheap.
                guard let signal = box.take(delivered) else { continue }
                await self.handle(signal)
            }
        }
    }

    private func registerObservers() {
        guard observers.isEmpty else { return }
        let center = NotificationCenter.default
        let box = signals
        // Every closure below reduces the notification to a `Sendable` value **inside** the callback and
        // hands it to the box, which is safe to call from any context and never allocates a `Task`. A
        // `Notification` is not `Sendable`, and the reason value is the only part that may leave the
        // route layer anyway (ADR-016 — a platform route description is a privacy leak).
        observers = [
            center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: nil) { note in
                let raw = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt)
                    .flatMap(AVAudioSession.RouteChangeReason.init(rawValue:)) ?? .unknown
                box.offer(.routeChanged(reason: IosAudioRouteMapper.changeReason(raw)))
            },
            center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: nil) { note in
                let info = note.userInfo
                let began = (info?[AVAudioSessionInterruptionTypeKey] as? UInt)
                    == AVAudioSession.InterruptionType.began.rawValue
                // `.shouldResume` is the platform saying the session may be re-activated. An interruption
                // that ends **without** it means stay inactive (ARCHITECTURE §6.2, TEST_PLAN IA-06), and
                // reading the option rather than assuming is the whole difference between the two.
                let options = AVAudioSession.InterruptionOptions(
                    rawValue: (info?[AVAudioSessionInterruptionOptionKey] as? UInt) ?? 0
                )
                box.offer(
                    began
                        ? .interruptionBegan
                        : .interruptionEnded(shouldResume: options.contains(.shouldResume))
                )
            },
            center.addObserver(
                forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: nil
            ) { _ in
                box.offer(.mediaServicesReset)
            },
        ]
    }

    private func unregisterObservers() {
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        observers.removeAll()
    }

    private func handle(_ signal: AudioSessionSignal) {
        let now = monotonicNowUs()
        let generation = lifecycle.generation
        switch signal {
        case .routeChanged(let reason):
            lastReason = reason
            // `.categoryChange` is the platform confirming the configuration change **we** asked for, so
            // it settles the transition. Anything else — a device unplugged, a new one appearing — is a
            // change we did not ask for and begins a transition of its own (TEST_PLAN IA-05).
            apply(
                .routeChanged(
                    generation: generation,
                    reason: reason,
                    atMonoUs: now,
                    settles: reason == .categoryChange
                )
            )
        case .interruptionBegan:
            lastReason = .interruptionBegan
            apply(.interruptionBegan(generation: generation, atMonoUs: now))
        case .interruptionEnded(let shouldResume):
            lastReason = .interruptionEnded
            apply(.interruptionEnded(generation: generation, shouldResume: shouldResume, atMonoUs: now))
        case .mediaServicesReset:
            lastReason = .mediaServicesReset
            apply(.mediaServicesReset(generation: generation, atMonoUs: now))
        }
    }

    /// Drives the shared reducer and performs what it returns.
    ///
    /// The generation check is inside the reducer, not here, which is the point: a notification still in
    /// flight from before a media-services reset is inert by comparison rather than by hoping the timing
    /// lines up (ADR-020 Amendment A2's rule, applied to the audio session).
    private func apply(_ event: AudioSessionEvent) {
        let outcome = AudioSessionLifecycle.reduce(state: lifecycle, event: event)
        lifecycle = outcome.state
        for action in outcome.actions {
            switch action {
            case .publishSnapshot(let routeState):
                publish(routeState: routeState)
            case .reactivate:
                // Only reached when the platform said `.shouldResume`.
                try? session.setActive(true)
            case .rebuildAfterReset:
                // Every audio object this process holds is invalid. The old observers belonged to the
                // superseded generation, so they go; the user restarting the intercom is what rebuilds,
                // and the diagnostics card makes the reset visible rather than limping through it.
                unregisterObservers()
                try? session.setCategory(.playback, mode: .default, options: [])
            case .reportFailure:
                break // recorded in `lifecycle.lastFailure`
            }
        }
    }

    /// Recomputes the snapshot from the platform's current view and hands it to the sink.
    ///
    /// Every field but `interrupted`, `routeState` and `lastTransitionDurationUs` comes from
    /// `IosAudioRouteMapper`, which is the one place an Apple port type becomes ADR-016 vocabulary
    /// (PROTOCOL §4.3.1).
    private func publish(routeState: RouteState) {
        let route = session.currentRoute
        let outputPort = route.outputs.first.map { IosAudioRouteMapper.portKind($0.portType) }
        var next = IosAudioRouteMapper.map(
            outputPort: outputPort,
            hasInput: !route.inputs.isEmpty,
            microphoneOpen: lifecycle.open,
            duplexCategoryActive: session.category == .playAndRecord,
            sampleRateHz: Int(session.sampleRate),
            lastChangeReason: lastReason,
            routeState: routeState,
            interrupted: lifecycle.interrupted
        )
        next.lastTransitionDurationUs = lifecycle.transition.lastDurationUs
        snapshot = next
        sink?(next)
    }

    private func fail(_ failure: VoiceFailure) -> Result<Void, VoiceAudioSessionError> {
        apply(.failed(generation: lifecycle.generation, failure: failure))
        return .failure(VoiceAudioSessionError(failure))
    }

    private func hasMicrophonePermission() async -> Bool {
        switch AVAudioApplication.shared.recordPermission {
        case .granted:
            return true
        case .undetermined:
            // Requested here, and only here, because this method is reached exclusively from an explicit
            // Start Intercom action (ARCHITECTURE §6.4 step 2/6) — never at launch.
            return await AVAudioApplication.requestRecordPermission()
        default:
            return false
        }
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

    public init(monotonicNowUs: @escaping @Sendable () -> Int64 = { 0 }) {
        _ = monotonicNowUs
    }

    public func isOpen() async -> Bool { false }

    public func route() async -> AudioRouteSnapshot { AudioRouteSnapshot() }

    public func lastFailure() async -> VoiceFailure? { .noAudioEndpoint }

    public func setRouteSink(_ sink: @escaping @Sendable (AudioRouteSnapshot) -> Void) async {
        self.sink = sink
    }

    public func open() async -> Result<Void, VoiceAudioSessionError> {
        .failure(VoiceAudioSessionError(.noAudioEndpoint))
    }

    public func close() async {}

    public func pollTransitionTimeout() async {}
}

#endif
