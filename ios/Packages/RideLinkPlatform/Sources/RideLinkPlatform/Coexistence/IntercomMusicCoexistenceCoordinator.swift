import Foundation
import RideLinkCore

@MainActor
public protocol MusicCoexistencePort: AnyObject {
    var coexistencePlayerState: PlayerState { get }
    var coexistenceBaseVolumePermille: Int { get }
    var coexistenceEvents: (any CoexistenceEventSink)? { get set }

    func beginCoexistenceLifetime(_ generation: Int64) async
    func applyCoexistenceGain(generation: Int64, volumePermille: Int) async -> Bool
    func pauseForVoice(generation: Int64, trackToken: String) async -> Bool
    func resumeAfterVoice(generation: Int64, trackToken: String) async -> Bool
}

@MainActor
public protocol CoexistenceEventSink: AnyObject {
    func onMusicChanged(_ state: PlayerState)
    func onPlaybackIntent(playing: Bool)
    func onBaseVolumeChanged(_ volumePermille: Int)
}

public struct CoexistenceDiagnostics: Sendable, Equatable {
    public var generation: Int64 = 0
    public var targetVolumePermille = CoexistenceState.fullGainPermille
    public var appliedVolumePermille = CoexistenceState.fullGainPermille
    public var pausedByVoice = false
    public var routeState: RouteState = .stable
    public var fallback: CoexistenceFallback = .none
    public var rampRevision: Int64 = 0
    public var rampCancellationCount = 0
    public var pauseCount = 0
    public var resumeCount = 0
    public var staleInputCount = 0

    public init() {}
}

/// The sole iOS driver of Phase 6 music effects. The mirrored pure reducer makes every decision;
/// this type serializes effects and owns the cancellable, generation-bound 200 ms ramp.
@MainActor
public final class IntercomMusicCoexistenceCoordinator: CoexistenceEventSink {
    public typealias Sleeper = @Sendable (UInt64) async -> Void

    public private(set) var diagnostics = CoexistenceDiagnostics()
    private var diagnosticsObserver: (@MainActor (CoexistenceDiagnostics) -> Void)?

    private weak var music: (any MusicCoexistencePort)?
    private let sleeper: Sleeper
    private var state = CoexistenceState()
    private var nextGeneration: Int64 = 0
    private var effectTail: Task<Void, Never>?
    private var rampTask: Task<Void, Never>?
    private var rampRevision: Int64 = 0
    private var appliedVolumePermille = CoexistenceState.fullGainPermille
    private var rampCancellationCount = 0
    private var pauseCount = 0
    private var resumeCount = 0

    public init(
        music: any MusicCoexistencePort,
        sleeper: @escaping Sleeper = { nanoseconds in try? await Task.sleep(nanoseconds: nanoseconds) }
    ) {
        self.music = music
        self.sleeper = sleeper
        music.coexistenceEvents = self
        onBaseVolumeChanged(music.coexistenceBaseVolumePermille)
        onMusicChanged(music.coexistencePlayerState)
    }

    @discardableResult
    public func beginLifetime(policy: IntercomPolicy) -> Int64 {
        nextGeneration += 1
        let generation = nextGeneration
        let outcome = IntercomMusicCoexistence.reduce(state: state, input: .lifetimeStarted(generation: generation, policy: policy))
        state = outcome.state
        effectTail?.cancel()
        rampTask?.cancel()
        effectTail = Task { @MainActor [weak self] in
            guard let self, let music = self.music else { return }
            await music.beginCoexistenceLifetime(generation)
            await self.perform(generation: generation, actions: outcome.actions)
        }
        publishDiagnostics()
        return generation
    }

    public func endLifetime(_ generation: Int64) { submit(.lifetimeEnded(generation: generation)) }

    /// Terminal lifecycle seam: restoration and any cancelled ramp are complete on return.
    public func awaitLifetimeEnded() async { await awaitEffectsSettled() }
    public func selectPolicy(_ policy: IntercomPolicy, generation: Int64) { submit(.policySelected(generation: generation, policy: policy)) }

    public func updateVoice(
        generation: Int64,
        available: Bool,
        localSpeechActive: Bool,
        peerSpeechActive: Bool,
        speechActivityAvailable: Bool,
        routeState: RouteState,
        interrupted: Bool,
        transitionTimedOut: Bool
    ) {
        submit(.voiceChanged(
            generation: generation,
            available: available,
            localSpeechActive: localSpeechActive,
            peerSpeechActive: peerSpeechActive,
            speechActivityAvailable: speechActivityAvailable
        ))
        submit(.routeChanged(generation: generation, routeState: routeState, interrupted: interrupted, transitionTimedOut: transitionTimedOut))
    }

    public func updateSyncAvailability(_ available: Bool) {
        submit(.syncAvailabilityChanged(generation: state.generation, available: available))
    }

    /// Observable completion seam for deterministic lifecycle tests; production never needs a sleep.
    public func awaitEffectsSettled() async {
        _ = await effectTail?.value
        _ = await rampTask?.value
    }

    public func setDiagnosticsObserver(_ observer: @escaping @MainActor (CoexistenceDiagnostics) -> Void) {
        diagnosticsObserver = observer
        observer(diagnostics)
    }

    public func onMusicChanged(_ player: PlayerState) {
        submit(.musicChanged(
            generation: state.generation,
            available: player.error == nil,
            trackToken: player.localEntryId?.value,
            playing: player.playing,
            ended: player.ended
        ))
    }

    public func onPlaybackIntent(playing: Bool) { submit(.userPlaybackIntent(generation: state.generation, playing: playing)) }
    public func onBaseVolumeChanged(_ volumePermille: Int) { submit(.baseVolumeChanged(generation: state.generation, volumePermille: volumePermille)) }

    private func submit(_ input: CoexistenceInput) {
        let outcome = IntercomMusicCoexistence.reduce(state: state, input: input)
        state = outcome.state
        if !outcome.actions.isEmpty { enqueue(generation: state.generation, actions: outcome.actions) }
        publishDiagnostics()
    }

    private func enqueue(generation: Int64, actions: [CoexistenceAction]) {
        let previous = effectTail
        effectTail = Task { @MainActor [weak self] in
            _ = await previous?.value
            guard let self, !Task.isCancelled else { return }
            await self.perform(generation: generation, actions: actions)
        }
    }

    private func perform(generation: Int64, actions: [CoexistenceAction]) async {
        guard let music else { return }
        for action in actions {
            switch action {
            case .rampMusicVolume(let targetPermille, let durationMs):
                startRamp(generation: generation, targetPermille: targetPermille, durationMs: durationMs)
            case .pauseMusicForVoice(let token):
                if await music.pauseForVoice(generation: generation, trackToken: token) { pauseCount += 1 }
            case .resumeMusicAfterVoice(let token):
                if await music.resumeAfterVoice(generation: generation, trackToken: token) { resumeCount += 1 }
            }
        }
        publishDiagnostics()
    }

    private func startRamp(generation: Int64, targetPermille: Int, durationMs: Int64) {
        if rampTask != nil { rampCancellationCount += 1 }
        rampTask?.cancel()
        rampRevision += 1
        let revision = rampRevision
        let start = appliedVolumePermille
        let sleepNs = UInt64(durationMs / Int64(Self.rampSteps)) * 1_000_000
        rampTask = Task { @MainActor [weak self] in
            guard let self else { return }
            for step in 1...Self.rampSteps {
                await sleeper(sleepNs)
                guard !Task.isCancelled, revision == rampRevision, generation == state.generation, let music else { return }
                let fraction = Double(step) / Double(Self.rampSteps)
                let value = Int((Double(start) + Double(targetPermille - start) * fraction).rounded())
                guard await music.applyCoexistenceGain(generation: generation, volumePermille: value) else { return }
                appliedVolumePermille = value
                publishDiagnostics()
            }
        }
        publishDiagnostics()
    }

    private func publishDiagnostics() {
        diagnostics.generation = state.generation
        diagnostics.targetVolumePermille = state.targetVolumePermille
        diagnostics.appliedVolumePermille = appliedVolumePermille
        diagnostics.pausedByVoice = state.pausedByVoice
        diagnostics.routeState = state.routeState
        diagnostics.fallback = state.fallback
        diagnostics.rampRevision = rampRevision
        diagnostics.rampCancellationCount = rampCancellationCount
        diagnostics.pauseCount = pauseCount
        diagnostics.resumeCount = resumeCount
        diagnostics.staleInputCount = state.staleInputCount
        diagnosticsObserver?(diagnostics)
    }

    private static let rampSteps = 10
}
