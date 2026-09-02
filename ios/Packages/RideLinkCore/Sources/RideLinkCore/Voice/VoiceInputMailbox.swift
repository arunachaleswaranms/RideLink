import Foundation

/// Where a `VoiceInput` is classified before it ever reaches the pure `VoiceNegotiation` table.
///
/// Priority order for `VoiceInputMailbox.poll` is `.teardown` > `.critical` > `.ice` > `.coalesced`: a
/// pending stop or link loss must never sit behind a flood of trickle-ICE or peer-state spam, and once
/// it is applied the reducer resets to a fresh generation, so anything stale still queued below it
/// becomes inert on its own (the existing `VoiceEngineGeneration` / `voice_session_id` guard).
public enum VoiceMailboxLane: Sendable, Equatable {
    /// `.stopRequested` / `.controlLinkLost`. One slot, latest wins, never refused.
    case teardown
    /// Cannot be silently lost: local start/offer/answer/connectivity, and a peer's Offer/Answer.
    case critical
    /// ICE-candidate-shaped inputs, local or remote. Bounded exactly as PROTOCOL §7.4's own queue is.
    case ice
    /// Only the newest value of its kind is ever meaningful. Fixed slots, always accepted.
    case coalesced
}

/// What `VoiceInputMailbox.offer` did with one input.
public enum VoiceMailboxOutcome: Sendable, Equatable {
    /// Held, in `lane`, to be delivered in FIFO order relative to the rest of that lane.
    case accepted(lane: VoiceMailboxLane)
    /// Replaced a same-kind value that had not been delivered yet. Nothing that still mattered was lost.
    case coalesced
    /// The ICE lane was full; the oldest queued candidate was discarded to hold this one.
    case iceEvicted
    /// The critical lane was full and this input was refused outright. The driver is expected to force
    /// a safe degrade in response — a critical input cannot simply vanish with nothing done about it,
    /// unlike `.iceEvicted` or `.coalesced`.
    case criticalOverflow
}

/// PROTOCOL §7.4/§7.8's bounded mailbox policy, extracted so a laptop test can exhaust it.
///
/// Before this type existed, every `VOICE_*` frame that had already passed the ADR-019 trust gate went
/// straight into an unbounded `AsyncStream` ahead of the pure table — so an authenticated-but-
/// compromised peer could grow `VoiceController`'s memory just by sending frames faster than they were
/// consumed, regardless of any bound the reducer or `PendingCandidates` applied afterward. Every lane
/// here is bounded for that reason, and `.ice`'s bound is the same `VoiceBounds.maxQueuedCandidates`
/// constant `PendingCandidates` already enforces one layer later — one policy, not two that could
/// quietly disagree.
///
/// **Not thread-safe by itself.** `offer` is called from whatever thread produced the input (the
/// control read loop, a WebRTC callback, the UI); `poll` is called only by the single consumer.
/// `VoiceController` — on both platforms — serialises access with its own lock, the same way the
/// unbounded channel it replaces was itself safe to send into from any thread. Pure otherwise: no
/// clock, no Task, no platform type, mirrored line for line as `com.ridelink.core.voice.VoiceInputMailbox`.
public struct VoiceInputMailbox: Sendable {
    /// Generous relative to a real negotiation's actual traffic (one offer, one answer, a handful of
    /// connectivity transitions) while still bounding what an adversarial flood of critical-lane
    /// inputs — repeated `VOICE_OFFER`/`VOICE_ANSWER` frames, chiefly — can hold in memory before
    /// `.criticalOverflow` forces a safe degrade.
    public static let criticalCapacity = 32

    private let criticalCapacity: Int
    private let iceCapacity: Int

    private var teardown: VoiceInput?
    private var critical: [VoiceInput] = []
    private var ice: [VoiceInput] = []
    private var coalesced: [CoalesceKey: VoiceInput] = [:]
    private var coalesceOrder: [CoalesceKey] = []

    /// `.iceEvicted` + `.criticalOverflow`, combined: one honest count of "a well-formed input could
    /// not be held as it arrived."
    public private(set) var overflowCount = 0

    public init(criticalCapacity: Int = VoiceInputMailbox.criticalCapacity, iceCapacity: Int = VoiceBounds.maxQueuedCandidates) {
        self.criticalCapacity = criticalCapacity
        self.iceCapacity = iceCapacity
    }

    @discardableResult
    public mutating func offer(_ input: VoiceInput) -> VoiceMailboxOutcome {
        switch Self.lane(for: input) {
        case .teardown:
            teardown = input
            return .accepted(lane: .teardown)
        case .critical:
            if critical.count >= criticalCapacity {
                overflowCount += 1
                return .criticalOverflow
            }
            critical.append(input)
            return .accepted(lane: .critical)
        case .ice:
            if ice.count >= iceCapacity {
                ice.removeFirst()
                overflowCount += 1
                ice.append(input)
                return .iceEvicted
            }
            ice.append(input)
            return .accepted(lane: .ice)
        case .coalesced:
            let key = Self.coalesceKey(for: input)
            let replaced = coalesced[key] != nil
            coalesced[key] = input
            if !replaced { coalesceOrder.append(key) }
            return replaced ? .coalesced : .accepted(lane: .coalesced)
        }
    }

    /// Removes and returns the next input to apply, in `VoiceMailboxLane` priority order, or `nil` if empty.
    public mutating func poll() -> VoiceInput? {
        if let next = teardown {
            teardown = nil
            return next
        }
        if !critical.isEmpty { return critical.removeFirst() }
        if !ice.isEmpty { return ice.removeFirst() }
        if let key = coalesceOrder.first {
            coalesceOrder.removeFirst()
            return coalesced.removeValue(forKey: key)
        }
        return nil
    }

    public var isEmpty: Bool {
        teardown == nil && critical.isEmpty && ice.isEmpty && coalesced.isEmpty
    }

    /// The whole queued backlog, for diagnostics only — nothing here decides anything from this.
    public var count: Int {
        (teardown == nil ? 0 : 1) + critical.count + ice.count + coalesced.count
    }

    public mutating func clear() {
        teardown = nil
        critical.removeAll()
        ice.removeAll()
        coalesced.removeAll()
        coalesceOrder.removeAll()
    }

    private enum CoalesceKey: Hashable {
        case mute
        case peerState
        case remoteTrack
    }

    private static func coalesceKey(for input: VoiceInput) -> CoalesceKey {
        switch input {
        case .muteRequested:
            return .mute
        case .remoteTrackChanged:
            return .remoteTrack
        case .signalReceived:
            return .peerState
        default:
            preconditionFailure("\(input) is not a coalesced input")
        }
    }

    private static func lane(for input: VoiceInput) -> VoiceMailboxLane {
        switch input {
        case .stopRequested, .controlLinkLost:
            return .teardown
        case .startRequested, .localOfferCreated, .localAnswerCreated, .mediaConnectivityChanged:
            return .critical
        case .signalReceived(let signal, _):
            switch signal {
            case .offer, .answer:
                return .critical
            case .iceCandidate:
                return .ice
            case .state:
                return .coalesced
            }
        case .localCandidateGathered:
            return .ice
        case .remoteTrackChanged:
            return .coalesced
        case .muteRequested:
            return .coalesced
        }
    }
}
