import Foundation

/// The 10 states of ARCHITECTURE §3.
public enum SessionStatus: String, Sendable, Equatable {
    case idle = "IDLE"
    case discovering = "DISCOVERING"
    case pairing = "PAIRING"
    case connecting = "CONNECTING"
    case connected = "CONNECTED"
    case rideActive = "RIDE_ACTIVE"
    case reconnecting = "RECONNECTING"
    case disconnected = "DISCONNECTED"
    case ending = "ENDING"
    case error = "ERROR"
}

/// `returnTo` is only meaningful when `status` is `.reconnecting`: it records which state the
/// reconnect attempt is trying to return to (ARCHITECTURE §3 rule 1 — "RECONNECTING returns to
/// the state it left, never skips forward"). It is always `.connected` or `.rideActive`.
public struct FsmState: Sendable, Equatable {
    public let status: SessionStatus
    public let returnTo: SessionStatus?

    public init(status: SessionStatus, returnTo: SessionStatus? = nil) {
        precondition(
            (status == .reconnecting) == (returnTo != nil),
            "returnTo must be set if and only if status is RECONNECTING"
        )
        if let returnTo {
            precondition(returnTo == .connected || returnTo == .rideActive, "returnTo must be CONNECTED or RIDE_ACTIVE")
        }
        self.status = status
        self.returnTo = returnTo
    }

    public static let initial = FsmState(status: .idle)
}

public enum LinkLossReason: Sendable, Equatable {
    case network
    case bye
}

public enum SessionEvent: Sendable, Equatable {
    case startDiscovery
    case cancelDiscovery
    case peerSelected
    case pairingRejectedOrTimeout
    case pairingSucceeded
    case connectionEstablished
    case connectionFailed
    case reconnectSucceeded
    case reconnectBudgetExhausted
    case retryRequested
    case startRide
    case endRide
    case linkLost(reason: LinkLossReason)
    case userEnded
    case fatalError(reason: String)
    case errorAcknowledged
    case teardownComplete
    /// Closing a duplicate connection (ADR-015) is not a fault and not a transition attempt.
    case duplicateConnectionClosed
}

public enum Effect: Sendable, Equatable {
    case logTransition(from: FsmState, to: FsmState, trigger: SessionEvent)
    /// Only ENDING may release the audio session and stop the foreground service (ARCHITECTURE §3 rule 3).
    case releaseAudioAndStopForegroundService
}

public enum FsmResult: Sendable, Equatable {
    case transitioned(newState: FsmState, effects: [Effect])
    /// An event that is not a legal transition from the current state. State is unchanged.
    case rejected(currentState: FsmState, event: SessionEvent)
    /// An event that is legitimately a no-op: not a transition, and — unlike `.rejected` — not a
    /// fault either. The only current case is `.duplicateConnectionClosed` (ARCHITECTURE §3 rule 6).
    case ignored(currentState: FsmState, event: SessionEvent, reason: String)
}

/// `(state, event) -> (state, effects)`. Pure: no platform types, no networking, no clock reads
/// (CLAUDE.md rule 9 / ARCHITECTURE §2's "hard rule").
public enum SessionFsm {
    public static func transition(_ state: FsmState, _ event: SessionEvent) -> FsmResult {
        if case .duplicateConnectionClosed = event {
            return .ignored(currentState: state, event: event, reason: "duplicate_connection_close_is_not_a_fault")
        }

        // Fatal errors are legal from any state except ERROR itself; handled first because they
        // short-circuit the per-state table below.
        if case .fatalError = event, state.status != .error {
            return transitioned(from: state, to: FsmState(status: .error), trigger: event)
        }

        if let newState = computeNextState(state, event) {
            return transitioned(from: state, to: newState, trigger: event)
        }
        return .rejected(currentState: state, event: event)
    }

    // swiftlint:disable:next cyclomatic_complexity function_body_length
    private static func computeNextState(_ state: FsmState, _ event: SessionEvent) -> FsmState? {
        let s = state.status
        switch event {
        case .startDiscovery:
            return s == .idle ? FsmState(status: .discovering) : nil

        case .cancelDiscovery:
            return s == .discovering ? FsmState(status: .idle) : nil

        case .peerSelected:
            return s == .discovering ? FsmState(status: .pairing) : nil

        case .pairingRejectedOrTimeout:
            return s == .pairing ? FsmState(status: .discovering) : nil

        case .pairingSucceeded:
            return s == .pairing ? FsmState(status: .connecting) : nil

        case .connectionEstablished:
            return s == .connecting ? FsmState(status: .connected) : nil

        case .connectionFailed:
            return s == .connecting ? FsmState(status: .reconnecting, returnTo: .connected) : nil

        case .reconnectSucceeded:
            guard s == .reconnecting, let returnTo = state.returnTo else { return nil }
            return FsmState(status: returnTo)

        case .reconnectBudgetExhausted:
            return s == .reconnecting ? FsmState(status: .disconnected) : nil

        case .retryRequested:
            return s == .disconnected ? FsmState(status: .discovering) : nil

        case .startRide:
            return s == .connected ? FsmState(status: .rideActive) : nil

        case .endRide:
            if s == .rideActive { return FsmState(status: .connected) }
            if s == .reconnecting, state.returnTo == .rideActive {
                return FsmState(status: .reconnecting, returnTo: .connected)
            }
            if s == .disconnected { return FsmState(status: .ending) }
            return nil

        case .linkLost(let reason):
            switch (reason, s) {
            case (.bye, .connected), (.bye, .rideActive), (.bye, .reconnecting):
                return FsmState(status: .ending)
            case (.network, .connected):
                return FsmState(status: .reconnecting, returnTo: .connected)
            case (.network, .rideActive):
                return FsmState(status: .reconnecting, returnTo: .rideActive)
            default:
                return nil
            }

        case .userEnded:
            switch s {
            case .connected, .rideActive, .reconnecting, .disconnected:
                return FsmState(status: .ending)
            default:
                return nil
            }

        case .errorAcknowledged:
            return s == .error ? FsmState(status: .ending) : nil

        case .teardownComplete:
            return s == .ending ? FsmState(status: .idle) : nil

        case .fatalError, .duplicateConnectionClosed:
            return nil
        }
    }

    private static func transitioned(from: FsmState, to: FsmState, trigger: SessionEvent) -> FsmResult {
        var effects: [Effect] = [.logTransition(from: from, to: to, trigger: trigger)]
        if isDeliberateEnd(from: from, to: to, trigger: trigger) {
            effects.append(.releaseAudioAndStopForegroundService)
        }
        return .transitioned(newState: to, effects: effects)
    }

    /// ARCHITECTURE §3 rule 3's two deliberate ends, and the reason the rule is phrased that way rather
    /// than as "only `ENDING`" (`docs/STATUS.md` §4 problem 53, ADR-026).
    ///
    /// The rule exists to stop a **link blip** releasing capture: `RECONNECTING` keeps the microphone and
    /// the foreground service, because ARCHITECTURE §6.4 gives no second chance to reopen a microphone
    /// once the screen is locked. `DISCONNECTED -> DISCOVERING` is the opposite case. The reconnect
    /// budget is spent, the peer is gone, the user has explicitly asked to start looking for one again,
    /// and they are by definition looking at the screen to have asked — so the ride segment is over, and
    /// holding the duplex Bluetooth profile open (ADR-016's central risk) for a peer that is not there is
    /// exactly what should not happen. Keeping the old `VoiceController` instead would be worse still:
    /// its `isLocalLeader` belongs to the session that ended, and ADR-020 makes the offerer role a
    /// property of *this* session's leader.
    private static func isDeliberateEnd(from: FsmState, to: FsmState, trigger: SessionEvent) -> Bool {
        if to.status == .ending { return true }
        if case .retryRequested = trigger, from.status == .disconnected { return true }
        return false
    }
}
