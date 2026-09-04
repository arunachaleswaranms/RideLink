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
/// ### Phase 2b final hardening (this pass)
///
/// **Notifications are delivered through one bounded, generation-tagged path, scoped to one open
/// generation.** `AudioSessionSignalBox` now (a) is created fresh on every `open()` rather than reused
/// for the actor's whole lifetime — a box `finish()`ed by a prior `close()` stays dead forever, so
/// reusing one across End → Start Intercom silently stopped delivering notifications for every session
/// after the first; (b) stamps each signal with the generation captured **at the `NotificationCenter`
/// callback boundary**, not re-derived when the signal is later processed — reading `lifecycle.generation`
/// at processing time stamps every event with whatever generation is current *then*, which defeats the
/// reducer's generation guard by construction; and (c) is drained by explicit priority polling
/// (`mediaServicesReset` before `interruption` before `routeChanged`) rather than raw `AsyncStream`
/// arrival order, so a reset can never sit behind a route change it must invalidate.
///
/// **Observers are registered before the platform is asked to change, and stay registered through a
/// close's restoring call.** The confirming `.categoryChange` notification cannot be missed because it
/// was never listened for.
///
/// **The route transition settles on a platform callback, never on a timer — and the timeout that
/// protects against a missing callback is actually scheduled now.** Every transition start (a changed
/// `RouteTransitionState.startedAtMonoUs`) arms one generation-tagged timeout task; a settle or a newer
/// transition cancels/replaces it; `close()`/a reset cancel it outright. `pollTransitionTimeout()` — the
/// public entry point — remains for direct callers and tests; it is no longer the *only* way the timeout
/// ever runs.
///
/// **Nothing here has run on a phone.** `AVAudioSession` is unavailable on macOS, so it cannot be
/// exercised by `swift test`, and no physical iPhone is available in this environment.
/// `IosAudioRouteMapper`, `AudioSessionLifecycle` and `AudioSessionSignalBox` are pure/platform-agnostic
/// and are unit-tested; everything else in this class is **REAL-DEVICE INTERCOM GATE PENDING**
/// (docs/STATUS.md §7, TEST_PLAN IA-01…IA-09, V-01…V-11).
public actor IosVoiceAudioSession: VoiceAudioSession {
    private let session = AVAudioSession.sharedInstance()
    private var observers: [NSObjectProtocol] = []
    private var snapshot = AudioRouteSnapshot()
    private var sink: (@Sendable (AudioRouteSnapshot) -> Void)?
    private var lifecycle = AudioSessionState()
    private var lastReason: AudioRouteChangeReason = .unknown
    private let monotonicNowUs: @Sendable () -> Int64

    /// This open generation's signal path — `nil` whenever the session is closed. Recreated fresh on
    /// every `open()`, which is what makes a prior generation's `finish()` harmless rather than
    /// permanent (see the type doc above and `AudioSessionSignalBox`'s own doc for why).
    private var signals: AudioSessionSignalBox?
    private var doorbell: ConflatedSignal?
    private var consumerTask: Task<Void, Never>?

    /// Failure protection for the current transition, if one is outstanding. Scheduled/cancelled by
    /// `manageTransitionTimeout`, keyed to the generation active when it was scheduled — a reset firing
    /// mid-window cannot let this stale timer settle the *new* generation's transition, because the event
    /// it eventually applies still names the old one and the reducer's own guard drops it.
    private var transitionTimeoutTask: Task<Void, Never>?

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

        // A fresh signal path for this generation, and the observers that will confirm the request below
        // — registered **before** the request is made, so the platform's confirming notification cannot
        // arrive into a session nobody is listening to yet (this phase's hardening pass, Issue D).
        let box = AudioSessionSignalBox()
        let bell = ConflatedSignal()
        signals = box
        doorbell = bell
        startConsumer(box: box, bell: bell)
        registerObservers(generation: lifecycle.generation, box: box, bell: bell)

        do {
            try session.setCategory(
                .playAndRecord,
                mode: .voiceChat,
                options: [.allowBluetoothHFP, .allowBluetoothA2DP, .duckOthers]
            )
            try session.setActive(true)
        } catch {
            unregisterObservers()
            stopConsumer()
            signals = nil
            doorbell = nil
            return fail(.audioSessionActivationFailed)
        }
        apply(.opened(generation: lifecycle.generation, atMonoUs: monotonicNowUs()))
        return .success(())
    }

    public func close() async {
        guard lifecycle.open else { return }
        // Observers stay registered through the restoring call below, so a `.categoryChange`
        // confirming *this* close has a chance to be observed (Issue D) — removing them is one of the
        // very last steps, once nothing further this generation's box needs to receive remains.
        try? session.setCategory(.playback, mode: .default, options: [])
        apply(.closed(generation: lifecycle.generation, atMonoUs: monotonicNowUs()))
        unregisterObservers()
        transitionTimeoutTask?.cancel()
        transitionTimeoutTask = nil
        stopConsumer()
        signals?.finish()
        signals = nil
        doorbell = nil
    }

    /// **Failure protection, never the definition of success** (this phase's brief §15). Now actually
    /// scheduled by `manageTransitionTimeout` after every transition-starting event, in addition to
    /// remaining callable directly (tests, or a caller that wants to force a check). If the platform
    /// never confirmed the change, the transition is declared settled and *counted as a timeout* so the
    /// diagnostics can say the number came from a timer rather than from `AVAudioSession`.
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

    private func startConsumer(box: AudioSessionSignalBox, bell: ConflatedSignal) {
        consumerTask?.cancel()
        consumerTask = Task { [weak self] in
            for await _ in bell.stream {
                guard let self else { return }
                await self.drainSignals(box: box)
            }
        }
    }

    private func stopConsumer() {
        consumerTask?.cancel()
        consumerTask = nil
        // `cancel()` alone is only the cooperative signal — it does not by itself end a `for await`
        // still suspended on the doorbell's stream (STATUS's own precedent for `OrderedEventChannel`).
        // `finish()` is what actually returns control to `startConsumer`'s loop.
        doorbell?.finish()
    }

    /// Drains `box` to empty, in `AudioSessionSignal.Kind` priority order (`AudioSessionSignalBox.poll`)
    /// rather than arrival order — a reset can never be stuck behind a route change it must invalidate.
    private func drainSignals(box: AudioSessionSignalBox) async {
        while let delivered = box.poll() {
            handle(delivered)
        }
    }

    private func registerObservers(generation: Int, box: AudioSessionSignalBox, bell: ConflatedSignal) {
        guard observers.isEmpty else { return }
        let center = NotificationCenter.default
        // Every closure below reduces the notification to a `Sendable` value **inside** the callback and
        // hands it to the box together with `generation` — captured here, at registration time, and
        // never re-read later. A `Notification` is not `Sendable`, and the reason value is the only part
        // that may leave the route layer anyway (ADR-016 — a platform route description is a privacy
        // leak).
        observers = [
            center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: nil) { note in
                let raw = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt)
                    .flatMap(AVAudioSession.RouteChangeReason.init(rawValue:)) ?? .unknown
                box.offer(.routeChanged(reason: IosAudioRouteMapper.changeReason(raw)), generation: generation, doorbell: bell)
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
                let signal: AudioSessionSignal = began
                    ? .interruptionBegan
                    : .interruptionEnded(shouldResume: options.contains(.shouldResume))
                box.offer(signal, generation: generation, doorbell: bell)
            },
            center.addObserver(
                forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: nil
            ) { _ in
                box.offer(.mediaServicesReset, generation: generation, doorbell: bell)
            },
        ]
    }

    private func unregisterObservers() {
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        observers.removeAll()
    }

    private func handle(_ delivered: GeneratedAudioSessionSignal) {
        let now = monotonicNowUs()
        let generation = delivered.generation
        switch delivered.signal {
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
    /// lines up (ADR-020 Amendment A2's rule, applied to the audio session) — and, since this hardening
    /// pass, by comparison against the generation the notification was actually stamped with at the
    /// callback boundary, not whatever generation happens to be current when this runs.
    private func apply(_ event: AudioSessionEvent) {
        let previousStartedAt = lifecycle.transition.startedAtMonoUs
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
                // Every audio object this process holds is invalid. The old observers and this
                // generation's signal path belonged to the superseded generation, so both go; the user
                // restarting the intercom is what rebuilds — `open()` creates entirely fresh ones — and
                // the diagnostics card makes the reset visible rather than limping through it.
                unregisterObservers()
                stopConsumer()
                signals?.finish()
                signals = nil
                doorbell = nil
                try? session.setCategory(.playback, mode: .default, options: [])
            case .reportFailure:
                break // recorded in `lifecycle.lastFailure`
            }
        }
        manageTransitionTimeout(previousStartedAt: previousStartedAt)
    }

    /// Arms or disarms the failure-protection timeout for whatever transition `apply` just produced.
    ///
    /// Compares `startedAtMonoUs` rather than the bare `transitioning` flag: a burst of route callbacks
    /// within the *same* transition (`RouteTransitionTracker.begin` deliberately keeps the original start
    /// instant) must not re-arm a fresh window, but a reset's brand-new transition — which can begin while
    /// the *old* generation was still mid-transition — must.
    private func manageTransitionTimeout(previousStartedAt: Int64?) {
        if lifecycle.transition.transitioning {
            if lifecycle.transition.startedAtMonoUs != previousStartedAt {
                scheduleTransitionTimeout(generation: lifecycle.generation)
            }
        } else {
            transitionTimeoutTask?.cancel()
            transitionTimeoutTask = nil
        }
    }

    /// One timeout task per active transition. `generation` is captured here, at schedule time — not
    /// re-read when the sleep resolves — so a generation that has since moved on renders this task inert
    /// via the reducer's own guard rather than by hoping the cancellation raced correctly.
    private func scheduleTransitionTimeout(generation: Int) {
        transitionTimeoutTask?.cancel()
        transitionTimeoutTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(RouteTransitionTracker.defaultTimeoutUs) * 1_000)
            guard !Task.isCancelled else { return }
            await self?.timeoutTransition(generation: generation)
        }
    }

    private func timeoutTransition(generation: Int) {
        apply(
            .transitionTimeoutCheck(
                generation: generation,
                atMonoUs: monotonicNowUs(),
                timeoutUs: RouteTransitionTracker.defaultTimeoutUs
            )
        )
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
