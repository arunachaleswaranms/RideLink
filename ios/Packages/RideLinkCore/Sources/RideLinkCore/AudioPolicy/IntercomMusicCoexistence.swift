import Foundation

/// The one pure decision table for temporary intercom/music effects.
///
/// It owns neither player nor capture resources. It decides only the temporary coexistence layer:
/// a multiplicative music gain, or a pause explicitly distinguished from the user's own pause.
public struct CoexistenceState: Sendable, Equatable {
    public var generation: Int64
    public var active: Bool
    public var policy: IntercomPolicy
    public var voiceAvailable: Bool
    public var localTransmitting: Bool
    public var peerTransmitting: Bool
    public var musicAvailable: Bool
    public var trackToken: String?
    public var musicPlaying: Bool
    public var trackEnded: Bool
    public var userPaused: Bool
    /// The user's durable/base app volume. Coexistence never mutates this value.
    public var baseVolumePermille: Int
    /// The last coexistence target, not a replacement for `baseVolumePermille`.
    public var targetVolumePermille: Int
    public var pausedByVoice: Bool
    public var pausedTrackToken: String?
    public var routeState: RouteState
    public var interrupted: Bool
    public var routeTransitionTimedOut: Bool
    public var syncAvailable: Bool
    public var fallback: CoexistenceFallback
    public var staleInputCount: Int

    public init(
        generation: Int64 = 0,
        active: Bool = false,
        policy: IntercomPolicy = .default,
        voiceAvailable: Bool = false,
        localTransmitting: Bool = false,
        peerTransmitting: Bool = false,
        musicAvailable: Bool = true,
        trackToken: String? = nil,
        musicPlaying: Bool = false,
        trackEnded: Bool = false,
        userPaused: Bool = false,
        baseVolumePermille: Int = fullGainPermille,
        targetVolumePermille: Int = fullGainPermille,
        pausedByVoice: Bool = false,
        pausedTrackToken: String? = nil,
        routeState: RouteState = .stable,
        interrupted: Bool = false,
        routeTransitionTimedOut: Bool = false,
        syncAvailable: Bool = true,
        fallback: CoexistenceFallback = .none,
        staleInputCount: Int = 0
    ) {
        precondition(generation >= 0)
        precondition((Self.minGainPermille...Self.fullGainPermille).contains(baseVolumePermille))
        precondition((Self.minGainPermille...Self.fullGainPermille).contains(targetVolumePermille))
        self.generation = generation
        self.active = active
        self.policy = policy
        self.voiceAvailable = voiceAvailable
        self.localTransmitting = localTransmitting
        self.peerTransmitting = peerTransmitting
        self.musicAvailable = musicAvailable
        self.trackToken = trackToken
        self.musicPlaying = musicPlaying
        self.trackEnded = trackEnded
        self.userPaused = userPaused
        self.baseVolumePermille = baseVolumePermille
        self.targetVolumePermille = targetVolumePermille
        self.pausedByVoice = pausedByVoice
        self.pausedTrackToken = pausedTrackToken
        self.routeState = routeState
        self.interrupted = interrupted
        self.routeTransitionTimedOut = routeTransitionTimedOut
        self.syncAvailable = syncAvailable
        self.fallback = fallback
        self.staleInputCount = staleInputCount
    }

    public var voiceActive: Bool {
        active && policy.intercomEnabled && voiceAvailable && !interrupted && (localTransmitting || peerTransmitting)
    }

    public static let minGainPermille = 0
    public static let fullGainPermille = 1_000
}

public enum CoexistenceFallback: String, Sendable, Equatable {
    case none
    case voiceUnavailable
    case musicUnavailable
    case routeTransitionTimeout
    case interrupted
    case syncUnavailable
}

public enum CoexistenceInput: Sendable, Equatable {
    case lifetimeStarted(generation: Int64, policy: IntercomPolicy)
    case lifetimeEnded(generation: Int64)
    case policySelected(generation: Int64, policy: IntercomPolicy)
    case voiceChanged(generation: Int64, available: Bool, localTransmitting: Bool, peerTransmitting: Bool)
    case musicChanged(generation: Int64, available: Bool, trackToken: String?, playing: Bool, ended: Bool)
    case userPlaybackIntent(generation: Int64, playing: Bool)
    case baseVolumeChanged(generation: Int64, volumePermille: Int)
    case routeChanged(generation: Int64, routeState: RouteState, interrupted: Bool, transitionTimedOut: Bool)
    case syncAvailabilityChanged(generation: Int64, available: Bool)
}

public enum CoexistenceAction: Sendable, Equatable {
    /// Ramp to an effective volume. The user's base volume remains untouched.
    case rampMusicVolume(targetPermille: Int, durationMs: Int64)
    /// A local temporary suppression, never an authoritative Phase 5 PAUSE command.
    case pauseMusicForVoice(trackToken: String)
    /// Resume only the exact track paused by coexistence.
    case resumeMusicAfterVoice(trackToken: String)

    /// FR-016 / ARCHITECTURE §6.1–6.2's deterministic 150–250 ms envelope.
    public static let rampDurationMs: Int64 = 200
}

public struct CoexistenceOutcome: Sendable, Equatable {
    public let state: CoexistenceState
    public let actions: [CoexistenceAction]
}

/// Pure `(state, input) -> (state, actions)` owner of all coexistence policy decisions.
public enum IntercomMusicCoexistence {
    public static func reduce(state: CoexistenceState, input: CoexistenceInput) -> CoexistenceOutcome {
        if !input.isLifetimeStarted, input.generation != state.generation {
            var stale = state
            stale.staleInputCount += 1
            return CoexistenceOutcome(state: stale, actions: [])
        }

        let applied = apply(state: state, input: input)
        return reconcile(
            before: state,
            applied: applied,
            forceGain: forcesGainReassertion(state: state, input: input)
        )
    }

    private static func apply(state: CoexistenceState, input: CoexistenceInput) -> CoexistenceState {
        var next = state
        switch input {
        case .lifetimeStarted(let generation, let policy):
            guard generation > state.generation else {
                next.staleInputCount += 1
                return next
            }
            next.generation = generation
            next.active = true
            next.policy = policy
            next.voiceAvailable = false
            next.localTransmitting = false
            next.peerTransmitting = false
            next.interrupted = false
            next.routeTransitionTimedOut = false
            next.fallback = .none
        case .lifetimeEnded:
            next.active = false
            next.voiceAvailable = false
            next.localTransmitting = false
            next.peerTransmitting = false
            next.interrupted = false
            next.routeTransitionTimedOut = false
            next.fallback = .none
        case .policySelected(_, let policy):
            next.policy = policy
        case .voiceChanged(_, let available, let localTransmitting, let peerTransmitting):
            next.voiceAvailable = available
            next.localTransmitting = localTransmitting
            next.peerTransmitting = peerTransmitting
        case .musicChanged(_, let available, let trackToken, let playing, let ended):
            let trackChanged = state.trackToken != trackToken
            next.musicAvailable = available
            next.trackToken = trackToken
            next.musicPlaying = playing
            next.trackEnded = ended
            if trackChanged { next.userPaused = false }
            if trackChanged || ended || !available {
                next.pausedByVoice = false
                next.pausedTrackToken = nil
            }
        case .userPlaybackIntent(_, let playing):
            next.userPaused = !playing
            if !playing {
                next.pausedByVoice = false
                next.pausedTrackToken = nil
            }
        case .baseVolumeChanged(_, let volumePermille):
            precondition((CoexistenceState.minGainPermille...CoexistenceState.fullGainPermille).contains(volumePermille))
            next.baseVolumePermille = volumePermille
        case .routeChanged(_, let routeState, let interrupted, let transitionTimedOut):
            next.routeState = routeState
            next.interrupted = interrupted
            next.routeTransitionTimedOut = transitionTimedOut
        case .syncAvailabilityChanged(_, let available):
            next.syncAvailable = available
        }
        return next
    }

    private static func reconcile(
        before: CoexistenceState,
        applied: CoexistenceState,
        forceGain: Bool
    ) -> CoexistenceOutcome {
        var next = applied
        var actions: [CoexistenceAction] = []

        let shouldPause = next.voiceActive && next.policy.onSpeech == .pause
        if shouldPause {
            if next.musicAvailable,
               next.musicPlaying,
               !next.userPaused,
               let token = next.trackToken,
               !next.pausedByVoice {
                actions.append(.pauseMusicForVoice(trackToken: token))
                next.pausedByVoice = true
                next.pausedTrackToken = token
            }
        } else if next.pausedByVoice {
            if let token = next.pausedTrackToken,
               token == next.trackToken,
               next.musicAvailable,
               !next.userPaused,
               !next.trackEnded {
                actions.append(.resumeMusicAfterVoice(trackToken: token))
            }
            next.pausedByVoice = false
            next.pausedTrackToken = nil
        }

        let target = targetVolume(state: next)
        if (forceGain || target != before.targetVolumePermille), next.musicAvailable {
            actions.append(.rampMusicVolume(targetPermille: target, durationMs: CoexistenceAction.rampDurationMs))
        }
        next.targetVolumePermille = target
        next.fallback = fallback(state: next)
        return CoexistenceOutcome(state: next, actions: actions)
    }

    private static func targetVolume(state: CoexistenceState) -> Int {
        let duckPercent: Int
        if state.voiceActive, case .duck(let percent) = state.policy.onSpeech {
            duckPercent = percent
        } else {
            duckPercent = 100
        }
        return state.baseVolumePermille * duckPercent / 100
    }

    private static func fallback(state: CoexistenceState) -> CoexistenceFallback {
        if !state.active { return .none }
        if state.interrupted { return .interrupted }
        if state.routeTransitionTimedOut { return .routeTransitionTimeout }
        if state.policy.intercomEnabled && !state.voiceAvailable { return .voiceUnavailable }
        if !state.musicAvailable { return .musicUnavailable }
        if !state.syncAvailable { return .syncUnavailable }
        return .none
    }

    private static func forcesGainReassertion(state: CoexistenceState, input: CoexistenceInput) -> Bool {
        switch input {
        case .lifetimeStarted(let generation, _): return generation > state.generation
        case .musicChanged(_, _, let trackToken, _, _): return trackToken != state.trackToken && trackToken != nil
        default: return false
        }
    }
}

private extension CoexistenceInput {
    var isLifetimeStarted: Bool {
        if case .lifetimeStarted = self { return true }
        return false
    }

    var generation: Int64 {
        switch self {
        case .lifetimeStarted(let generation, _),
             .lifetimeEnded(let generation),
             .policySelected(let generation, _),
             .voiceChanged(let generation, _, _, _),
             .musicChanged(let generation, _, _, _, _),
             .userPlaybackIntent(let generation, _),
             .baseVolumeChanged(let generation, _),
             .routeChanged(let generation, _, _, _),
             .syncAvailabilityChanged(let generation, _):
            return generation
        }
    }
}
