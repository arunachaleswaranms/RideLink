import Foundation

/// Where a `VoiceInput` is classified before it ever reaches the pure `VoiceNegotiation` table.
///
/// Priority order for `VoiceInputMailbox.poll` is `.teardown` > `.terminalPeerState` > `.critical` >
/// `.ice` > `.coalesced`: a pending stop or link loss must never sit behind a flood of trickle-ICE or
/// peer-state spam. That ordering is deliberate, and it is also why `.controlLinkLost` discards the
/// remote signals it outranks -- see `VoiceInputMailbox.offer`. This doc used to claim that anything
/// stale queued below a teardown "becomes inert on its own" via the `VoiceEngineGeneration` /
/// `voice_session_id` guard. **That was false** for the two branches that *begin* a negotiation rather
/// than advance one (`offerReceived`'s full accept and `peerWantsVoice`): both are guarded only when
/// `voiceSessionId` is non-nil, and a teardown resets it to nil, so the guard is skipped exactly when
/// it is needed (STATUS §4 problem 50). `.terminalPeerState` sits directly below `.teardown` and above
/// `.critical` so a peer's own teardown signal is never delayed behind a flood of offers/answers, and
/// strictly above `.coalesced` so it can never be classified alongside -- and therefore silently
/// overwritten by -- an ordinary peer-state update.
public enum VoiceMailboxLane: Sendable, Equatable {
    /// `.stopRequested` / `.controlLinkLost`. One slot, latest wins, never refused.
    case teardown
    /// A peer's own `VOICE_STATE { state: closed | failed }`. Unlike an ordinary peer-state update
    /// (`negotiating`/`connecting`/`active`/`idle`/`unknown`), the reducer gives these teardown
    /// semantics (`VoiceNegotiation`'s `teardownFromPeer`), so a later ordinary update must never be
    /// allowed to coalesce over -- and thereby erase -- one still sitting here undelivered.
    case terminalPeerState
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
    /// The terminal-peer-state lane was full and this input was refused outright. Exactly like
    /// `.criticalOverflow` -- refusing a `closed`/`failed` signal outright and forcing a safe degrade
    /// is simpler and strictly safer than evicting an *earlier* terminal event to make room for this
    /// one, which would risk discarding the one signal the lane exists to protect.
    case terminalOverflow
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
    private let terminalPeerStateCapacity: Int

    private var teardown: VoiceInput?
    private var terminalPeerState: [VoiceInput] = []
    private var critical: [VoiceInput] = []
    private var ice: [VoiceInput] = []
    private var coalesced: [CoalesceKey: VoiceInput] = [:]
    private var coalesceOrder: [CoalesceKey] = []

    /// A single negotiation produces at most one terminal peer state naturally -- `closed` xor
    /// `failed`, once, per generation. This bounds a peer that floods repeated terminal frames (e.g.
    /// across several rapid teardown/rebuild cycles within one control session) rather than assuming
    /// good behaviour, while staying far larger than any real ride's handful of teardown/rebuild
    /// cycles would ever approach.
    public static let terminalPeerStateCapacity = 8

    /// `.iceEvicted` + `.criticalOverflow` + `.terminalOverflow`, combined: one honest count of "a
    /// well-formed input could not be held as it arrived."
    public private(set) var overflowCount = 0

    /// How many queued peer signals were discarded because the control lifetime that admitted them
    /// ended before they were applied (STATUS §4 problem 50).
    ///
    /// Surfaced rather than silent, for the same reason `overflowCount` is: "the peer's offer never
    /// arrived" and "it arrived and its link died before we got to it" are different facts, and only
    /// the second one says the ride hit a blip rather than a bug.
    public private(set) var discardedRetiredSignalCount = 0

    public init(
        criticalCapacity: Int = VoiceInputMailbox.criticalCapacity,
        iceCapacity: Int = VoiceBounds.maxQueuedCandidates,
        terminalPeerStateCapacity: Int = VoiceInputMailbox.terminalPeerStateCapacity
    ) {
        self.criticalCapacity = criticalCapacity
        self.iceCapacity = iceCapacity
        self.terminalPeerStateCapacity = terminalPeerStateCapacity
    }

    @discardableResult
    public mutating func offer(_ input: VoiceInput) -> VoiceMailboxOutcome {
        switch Self.lane(for: input) {
        case .teardown:
            // STATUS §4 problem 50. A `.controlLinkLost` ends the control lifetime that admitted every
            // `.signalReceived` currently queued below it, and this lane outranks all of them -- so
            // applying it first would reset the reducer and *then* hand it a retired peer's offer,
            // which `offerReceived` would accept as a fresh one (its generation guard is skipped when
            // `voiceSessionId` is nil) and answer on a dead link.
            //
            // The teardown that jumps the queue takes ownership of the remote work it jumped. Doing it
            // here, at **offer** time, is what makes it exact rather than a race: `endConnection`
            // clears the authenticated connection *before* it emits `.linkLost`, and
            // `VoiceSignalRelay.deliver` refuses any frame whose generation is not the live one, so
            // nothing remote can be offered between the lifetime ending and this call. A later
            // lifetime's frames are offered strictly after it and are untouched -- which is why this is
            // not a blanket flush and cannot discard a valid fresh generation's work, even if the
            // consumer is starved for the whole reconnect.
            //
            // Local inputs are deliberately kept: this user's consent, the engine's own callbacks and
            // the intercom gate's state are not the retired peer's to withdraw, and the engine
            // callbacks carry their own `voice_session_id` guard already. `.stopRequested` shares this
            // lane but is **not** a lifetime boundary -- the link is still up when a user presses End
            // Voice -- so it discards nothing.
            if case .controlLinkLost = input { discardRetiredRemoteSignals() }
            teardown = input
            return .accepted(lane: .teardown)
        case .terminalPeerState:
            if terminalPeerState.count >= terminalPeerStateCapacity {
                overflowCount += 1
                return .terminalOverflow
            }
            terminalPeerState.append(input)
            return .accepted(lane: .terminalPeerState)
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

    /// Removes every queued `.signalReceived` -- and nothing else -- from the four lanes that can hold
    /// one. See `offer`'s `.teardown` branch for why this is the teardown's responsibility and why
    /// offer time is the only instant at which it is exact.
    private mutating func discardRetiredRemoteSignals() {
        func isPeerSignal(_ input: VoiceInput) -> Bool {
            if case .signalReceived = input { return true }
            return false
        }
        discardedRetiredSignalCount +=
            terminalPeerState.filter(isPeerSignal).count
                + critical.filter(isPeerSignal).count
                + ice.filter(isPeerSignal).count
                + coalesced.values.filter(isPeerSignal).count
        terminalPeerState.removeAll(where: isPeerSignal)
        critical.removeAll(where: isPeerSignal)
        ice.removeAll(where: isPeerSignal)
        // The only coalesced kind a peer produces is `.peerState`; mute/mode/remote-track are local.
        let retiredKeys = coalesced.filter { isPeerSignal($0.value) }.map(\.key)
        for key in retiredKeys {
            coalesced.removeValue(forKey: key)
            coalesceOrder.removeAll { $0 == key }
        }
    }

    /// Removes and returns the next input to apply, in `VoiceMailboxLane` priority order, or `nil` if empty.
    public mutating func poll() -> VoiceInput? {
        if let next = teardown {
            teardown = nil
            return next
        }
        if !terminalPeerState.isEmpty { return terminalPeerState.removeFirst() }
        if !critical.isEmpty { return critical.removeFirst() }
        if !ice.isEmpty { return ice.removeFirst() }
        if let key = coalesceOrder.first {
            coalesceOrder.removeFirst()
            return coalesced.removeValue(forKey: key)
        }
        return nil
    }

    public var isEmpty: Bool {
        teardown == nil && terminalPeerState.isEmpty && critical.isEmpty && ice.isEmpty && coalesced.isEmpty
    }

    /// The whole queued backlog, for diagnostics only — nothing here decides anything from this.
    public var count: Int {
        (teardown == nil ? 0 : 1) + terminalPeerState.count + critical.count + ice.count + coalesced.count
    }

    public mutating func clear() {
        teardown = nil
        terminalPeerState.removeAll()
        critical.removeAll()
        ice.removeAll()
        coalesced.removeAll()
        coalesceOrder.removeAll()
    }

    private enum CoalesceKey: Hashable {
        case mute
        case mode
        case peerState
        case remoteTrack
    }

    private static func coalesceKey(for input: VoiceInput) -> CoalesceKey {
        switch input {
        case .muteRequested:
            return .mute
        case .modeSelected:
            return .mode
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
            case .state(_, let wire, _, _):
                return isTerminal(wire) ? .terminalPeerState : .coalesced
            }
        case .localCandidateGathered:
            return .ice
        case .remoteTrackChanged:
            return .coalesced
        case .muteRequested:
            return .coalesced
        // Absolute, like mute: only the newest selected mode is meaningful, and losing an intermediate one
        // loses nothing the peer needed to be told.
        case .modeSelected:
            return .coalesced
        }
    }

    /// True for exactly the two PROTOCOL §7.4 wire states the reducer gives teardown semantics.
    private static func isTerminal(_ wire: VoiceWireState) -> Bool {
        wire == .closed || wire == .failed
    }
}
