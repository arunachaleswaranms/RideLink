import Foundation

/// Everything the "am I transmitting?" decision depends on.
///
/// Every field is an **absolute** value rather than an edge, which is what makes
/// `IntercomCommandMailbox` able to coalesce commands by kind without losing anything: the newest
/// value of a kind is the only one whose effect survives.
///
/// **The reducer is not commutative across kinds, and it must not be.** A `.policySelected` or a
/// `.captureOpen(false)` deliberately *resets* the gate's transient state, so neither commutes with a
/// `.pttHeld`. That is why `IntercomCommandMailbox` drains kinds in a fixed order with those two
/// first: the guarantee callers actually need is that a batch of commands lands on the same state
/// whatever order it **arrived** in, and a fixed drain order gives that without pretending the reducer
/// is order-blind. `IntercomCommandMailboxTests` asserts it as a property over every arrival
/// permutation.
///
/// No clock, no I/O, no platform type. Monotonic microseconds are *passed in* where they are needed
/// (CLAUDE.md rules 5 and 9), exactly as `SessionFsm` takes time as a parameter.
public struct TransmissionState: Sendable, Equatable {
    public var policy: IntercomPolicy
    /// The capture device and platform audio session are open for this ride segment. Distinct from
    /// whether speech is being transmitted — that is `transmitting` — and it is the distinction
    /// PROTOCOL §4.4 draws between `AUDIO_STATE.microphone_open` and `VOICE_STATE.mic_muted`.
    public var captureOpen: Bool
    /// The user pressed Mute. Survives a policy change and a link blip; only the user clears it.
    public var userMuted: Bool
    /// The PTT control is held down **right now**. Cleared by release, cancel and backgrounding.
    public var pttHeld: Bool
    /// The VOX gate is open. Driven by `.speechLevel`; see `TransmissionGate.vox`.
    public var voxOpen: Bool
    /// Monotonic deadline, in microseconds, at which an open VOX gate closes if nothing renews it.
    public var voxHangoverUntilMonoUs: Int64?
    /// A platform interruption — a call, Siri, another app taking the session (ADR-016).
    public var interrupted: Bool

    public init(
        policy: IntercomPolicy = .default,
        captureOpen: Bool = false,
        userMuted: Bool = false,
        pttHeld: Bool = false,
        voxOpen: Bool = false,
        voxHangoverUntilMonoUs: Int64? = nil,
        interrupted: Bool = false
    ) {
        self.policy = policy
        self.captureOpen = captureOpen
        self.userMuted = userMuted
        self.pttHeld = pttHeld
        self.voxOpen = voxOpen
        self.voxHangoverUntilMonoUs = voxHangoverUntilMonoUs
        self.interrupted = interrupted
    }

    /// **The one rule.** Every clause is a reason *not* to transmit except the last, which is the
    /// policy's own gate.
    ///
    /// The capture check comes first and is not negotiable: transmission cannot precede an open capture
    /// path, and a PTT press must never be what opens one (ARCHITECTURE §6.4).
    public var transmitting: Bool {
        if !captureOpen { return false }
        if interrupted { return false }
        if userMuted { return false }
        switch policy.gate {
        case .none: return true
        case .vox: return voxOpen
        case .ptt: return pttHeld
        case .disabled: return false
        }
    }

    /// What `VOICE_STATE.mic_muted` reports: "this peer is transmitting silence" (PROTOCOL §7.4).
    public var micMutedForWire: Bool { !transmitting }
}

/// What drives `IntercomTransmission`. Each is an absolute assignment, never an edge — see
/// `TransmissionState`.
public enum IntercomInput: Sendable, Equatable {
    case policySelected(IntercomPolicy)
    case userMuted(Bool)
    /// The PTT control's current position. `false` covers release, touch-cancel, an accessibility gesture
    /// that never delivers an "up", and the app being backgrounded — every one of which must leave
    /// transmission **off** rather than stuck on (this phase's brief §25).
    case pttHeld(Bool)
    case captureOpen(Bool)
    case interrupted(Bool)
    /// A measured input level, in dBFS, at a monotonic instant. Only meaningful under
    /// `TransmissionGate.vox`; ignored under every other gate rather than silently changing state.
    ///
    /// **No production code path supplies this yet** — see `TransmissionGate.vox` for exactly why, and
    /// ADR-021 §6. The rule below is implemented and tested; its level source is pending.
    case speechLevel(levelDbfs: Double, atMonoUs: Int64)
    /// Time passed with no new level. Closes an open VOX gate whose hangover has expired, so a
    /// silent-but-still-open gate is a bounded condition rather than a permanent one.
    case voxTick(atMonoUs: Int64)
}

/// What the driver must do. Deliberately tiny: this layer decides transmission and nothing else.
public enum IntercomAction: Sendable, Equatable {
    /// Enable or disable the **outbound audio track**, never the capture device (this phase's brief §6).
    /// On Android that is `AudioTrack.setEnabled`; on Apple `RTCAudioTrack.isEnabled`. Emitted only when
    /// the value actually changes, so a held PTT does not re-issue it per input.
    case setTransmitting(Bool)
    /// The policy's `VOICE_STATE.mode` changed and the peer must be told (PROTOCOL §7.4).
    case announceVoiceMode(VoiceMode)
    /// The policy's `AUDIO_STATE.intercom_mode` changed, so a fresh `AUDIO_STATE` is due (§4.4).
    case publishAudioState
}

public struct IntercomOutcome: Sendable, Equatable {
    public let state: TransmissionState
    public let actions: [IntercomAction]

    public init(state: TransmissionState, actions: [IntercomAction]) {
        self.state = state
        self.actions = actions
    }
}

/// The intercom transmission gate, as a pure `(state, input) -> (state, actions)` reducer.
///
/// It is a separate type for the same reason `SessionGate` (ADR-019) and `VoiceNegotiation` (ADR-020)
/// are: the properties that matter here are properties of *this table*, and a table can be exhausted by
/// a laptop unit test on both platforms —
///
/// - full duplex transmits with no gate at all;
/// - PTT gates transmission and **nothing else**, so 50 presses produce 100 track-enable flips and zero
///   capture-device operations;
/// - mute wins over an open gate;
/// - an interruption and a closed capture path both win over everything;
/// - a policy switch cannot leave transmission on in a mode that should not be transmitting.
///
/// `com.ridelink.core.audiopolicy.IntercomTransmission` is the mirror; the two must agree case for case,
/// and `protocol/vectors/intercom/` is what makes a disagreement fail a build instead of a ride.
///
/// It owns no session state, holds no trust, reads no clock and knows nothing about WebRTC.
public enum IntercomTransmission {
    public static func reduce(state: TransmissionState, input: IntercomInput) -> IntercomOutcome {
        let next = apply(state, input)
        return IntercomOutcome(state: next, actions: actionsFor(before: state, after: next))
    }

    private static func apply(_ state: TransmissionState, _ input: IntercomInput) -> TransmissionState {
        switch input {
        case .policySelected(let policy):
            return policySelected(state, policy)
        case .userMuted(let muted):
            var next = state
            next.userMuted = muted
            return next
        case .pttHeld(let held):
            var next = state
            next.pttHeld = held
            return next
        case .captureOpen(let open):
            return captureChanged(state, open)
        case .interrupted(let interrupted):
            var next = state
            next.interrupted = interrupted
            return next
        case .speechLevel(let levelDbfs, let atMonoUs):
            return speechLevel(state, levelDbfs: levelDbfs, atMonoUs: atMonoUs)
        case .voxTick(let atMonoUs):
            return voxTick(state, nowMonoUs: atMonoUs)
        }
    }

    /// A policy change resets the gate's own transient state but never the user's mute and never the
    /// capture path. Leaving `pttHeld` set while switching *into* PTT would open transmission on a button
    /// nobody is holding; leaving `voxOpen` set while switching out of VOX would leave a stale gate
    /// deciding nothing.
    private static func policySelected(_ state: TransmissionState, _ policy: IntercomPolicy) -> TransmissionState {
        var next = state
        next.policy = policy
        next.pttHeld = false
        next.voxOpen = false
        next.voxHangoverUntilMonoUs = nil
        return next
    }

    /// Losing the capture path clears the gate too. Keeping `pttHeld` across a capture close would mean a
    /// later reopen started transmitting without a fresh press.
    private static func captureChanged(_ state: TransmissionState, _ open: Bool) -> TransmissionState {
        var next = state
        next.captureOpen = open
        if !open {
            next.pttHeld = false
            next.voxOpen = false
            next.voxHangoverUntilMonoUs = nil
        }
        return next
    }

    private static func speechLevel(
        _ state: TransmissionState,
        levelDbfs: Double,
        atMonoUs: Int64
    ) -> TransmissionState {
        guard case .vox(let thresholdDbfs, let hangoverMs) = state.policy.gate else { return state }
        if levelDbfs >= thresholdDbfs {
            var next = state
            next.voxOpen = true
            next.voxHangoverUntilMonoUs = atMonoUs + hangoverMs * microsPerMs
            return next
        }
        return closeIfHangoverExpired(state, nowMonoUs: atMonoUs)
    }

    private static func voxTick(_ state: TransmissionState, nowMonoUs: Int64) -> TransmissionState {
        guard case .vox = state.policy.gate else { return state }
        return closeIfHangoverExpired(state, nowMonoUs: nowMonoUs)
    }

    private static func closeIfHangoverExpired(_ state: TransmissionState, nowMonoUs: Int64) -> TransmissionState {
        guard state.voxOpen else { return state }
        var next = state
        guard let deadline = state.voxHangoverUntilMonoUs else {
            next.voxOpen = false
            return next
        }
        guard nowMonoUs >= deadline else { return state }
        next.voxOpen = false
        next.voxHangoverUntilMonoUs = nil
        return next
    }

    /// Actions are a **diff**, not a restatement. That is what makes the capture-open invariant
    /// checkable: nothing here can ever emit a capture operation, and a repeated input emits no action at
    /// all.
    private static func actionsFor(before: TransmissionState, after: TransmissionState) -> [IntercomAction] {
        var actions: [IntercomAction] = []
        if before.transmitting != after.transmitting {
            actions.append(.setTransmitting(after.transmitting))
        }
        if before.policy.voiceWireMode != after.policy.voiceWireMode {
            actions.append(.announceVoiceMode(after.policy.voiceWireMode))
        }
        if before.policy.intercomWireMode != after.policy.intercomWireMode {
            actions.append(.publishAudioState)
        }
        return actions
    }

    private static let microsPerMs: Int64 = 1_000
}
