import Foundation

/// Which absolute value an `IntercomInput` assigns. One slot per kind — that is the whole bound.
///
/// `poll` returns kinds in **declaration order**, which is chosen rather than incidental: policy and
/// capture first because they reset the gate's transient state, then the two overrides that can only ever
/// *stop* transmission, then the gate inputs themselves. Draining in that order means a batch that
/// arrived together can never leave transmission on when any member of it says it should be off.
public enum IntercomCommandKind: Int, Sendable, Equatable, CaseIterable {
    case policy
    case capture
    case interrupted
    case userMuted
    case pttHeld
    case voxLevel
}

/// What `IntercomCommandMailbox.offer` did with one input.
public enum IntercomMailboxOutcome: Sendable, Equatable {
    /// Held. No same-kind value was waiting.
    case accepted
    /// Replaced a same-kind value that had not been delivered yet. Nothing that still mattered was lost,
    /// because every input this mailbox carries is an **absolute** assignment (`TransmissionState`) — the
    /// newest value of a kind is the only one whose effect survives.
    case coalesced
}

/// The intercom command queue, **bounded by construction** rather than by a capacity check.
///
/// This phase's brief §38 requires every new input stream to have an explicit finite buffering policy.
/// This one's is the strongest available: at most one pending value per `IntercomCommandKind`, so the
/// queue can never exceed `IntercomCommandKind.allCases.count` entries no matter how fast a user (or an
/// accessibility service, or a stuck touch) produces them. There is no overflow path because there is
/// nothing to overflow.
///
/// **Why coalescing is safe here and is not safe for `VOICE_*`.** `VoiceInputMailbox` needs FIFO lanes
/// because a `VOICE_OFFER` and a `VOICE_ANSWER` are *distinct occurrences* that each have to be applied.
/// Every input here is instead "the current position of a control": the newest press state, the newest
/// mute state, the newest policy. Collapsing two of the same kind loses only the interval between them.
///
/// The one thing that collapse costs is visible and deliberate: a press *and* its release arriving before
/// a single drain coalesce to "not held", so an utterance shorter than one drain window is not
/// transmitted. That is the safe direction of the error — the alternative rounding would leave
/// transmission stuck **on** after a release, which this phase's brief §25 forbids outright.
///
/// **Not thread-safe by itself**, exactly like `VoiceInputMailbox`: `offer` is called from the UI or a
/// platform callback, `poll` only from the single consumer, and `VoiceController` serialises the two.
/// Pure otherwise, and mirrored line for line as `com.ridelink.core.audiopolicy.IntercomCommandMailbox`.
public struct IntercomCommandMailbox: Sendable {
    /// The bound, stated as a number so a test can assert it rather than infer it.
    public static let capacity = 6

    private var slots: [IntercomCommandKind: IntercomInput] = [:]

    /// Diagnostics only. Nothing decides anything from this.
    public private(set) var coalescedCount = 0

    public init() {}

    @discardableResult
    public mutating func offer(_ input: IntercomInput) -> IntercomMailboxOutcome {
        let kind = Self.kind(for: input)
        let replaced = slots.updateValue(input, forKey: kind) != nil
        if replaced { coalescedCount += 1 }
        return replaced ? .coalesced : .accepted
    }

    /// Removes and returns the next input to apply, in `IntercomCommandKind` order, or nil if empty.
    public mutating func poll() -> IntercomInput? {
        for kind in IntercomCommandKind.allCases {
            if let input = slots.removeValue(forKey: kind) { return input }
        }
        return nil
    }

    public var isEmpty: Bool { slots.isEmpty }

    public var count: Int { slots.count }

    public mutating func clear() {
        slots.removeAll()
    }

    public static func kind(for input: IntercomInput) -> IntercomCommandKind {
        switch input {
        case .policySelected: return .policy
        case .captureOpen: return .capture
        case .interrupted: return .interrupted
        case .userMuted: return .userMuted
        case .pttHeld: return .pttHeld
        // A level and a tick both answer "where is the VOX gate now", so they share a slot: a tick that
        // arrives after a level is superseded by it, and vice versa.
        case .speechLevel, .voxTick: return .voxLevel
        }
    }
}
