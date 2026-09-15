import Foundation

/// Where a `VoiceInput` is classified before it ever reaches the pure `VoiceNegotiation` table.
///
/// Priority order for `VoiceInputMailbox.poll` is `.teardown` > `.sendFailure` > `.terminalPeerState` >
/// `.critical` > `.ice` > `.coalesced`: a pending stop or link loss must never sit behind a flood of trickle-ICE or
/// peer-state spam. That ordering is deliberate, and it is also why `.controlLinkLost` discards the
/// remote signals it outranks -- **the ones its own retired generation admitted, and only those**
/// (STATUS §4 problem 60); see `VoiceInputMailbox.offer`. This doc used to claim that anything
/// stale queued below a teardown "becomes inert on its own" via the `VoiceEngineGeneration` /
/// `voice_session_id` guard. **That was false** for the two branches that *begin* a negotiation rather
/// than advance one (`offerReceived`'s full accept and `peerWantsVoice`): both are guarded only when
/// `voiceSessionId` is non-nil, and a teardown resets it to nil, so the guard is skipped exactly when
/// it is needed (STATUS §4 problem 50). `.terminalPeerState` sits directly below `.teardown` and above
/// `.critical` so a peer's own teardown signal is never delayed behind a flood of offers/answers, and
/// strictly above `.coalesced` so it can never be classified alongside -- and therefore silently
/// overwritten by -- an ordinary peer-state update.
public enum VoiceMailboxLane: Sendable, Equatable {
    /// `.stopRequested` / `.controlLinkLost`. One slot, never refused.
    ///
    /// Latest wins with **one exception**: a pending `.stopRequested` is never displaced (STATUS §4
    /// problem 57). A stop is a strict superset of a link loss — it also releases the capture device —
    /// and it is the only input `VoiceController.shutdown()` and `stopAndAwaitRelease()` can ever
    /// complete on, so overwriting one means an unbounded wait on a release that will now never be
    /// applied, and therefore a `SessionCoordinator.retireSession` that can never emit
    /// `.teardownComplete` (ADR-026 / rule 21).
    case teardown
    /// `.negotiationSendFailed`. One slot, latest wins, never refused.
    ///
    /// A lane of its own rather than a second occupant of `.teardown`, for two reasons that are both
    /// defects it closes (STATUS §4 problem 57). It must **outrank** `.critical`, because the whole
    /// point is to return the table to `.idle` before the successor lifetime's queued `VOICE_OFFER` is
    /// reduced — reduced against a still-live retired negotiation, that offer is a
    /// `generationMismatch` and is dropped. And it must **not share** `.teardown`'s single slot,
    /// because a send failure arriving from the consumer's own resume would otherwise replace a
    /// pending teardown, taking either the link loss's ownership of queued remote work or the stop's
    /// capture release with it.
    case sendFailure
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
    /// A `.signalReceived` whose admitting control generation has already been retired (STATUS §4
    /// problem 60). Refused without being held, and **not** an overflow: nothing was lost that still
    /// mattered, so this must never drive the `.criticalOverflow` degrade.
    ///
    /// This is the "admitted after retirement" half of the fix. The "queued before retirement" half is
    /// `discardedRetiredSignalCount`; between them there is no instant at which a retired lifetime's
    /// semantic work can reach `VoiceNegotiation`, and neither half depends on the order the two
    /// arrived in.
    case retiredGeneration
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
    private var sendFailure: VoiceInput?
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

    /// How many peer signals were refused on arrival because the control lifetime that admitted them
    /// had **already** been retired (STATUS §4 problem 60).
    ///
    /// The counterpart to `discardedRetiredSignalCount` and separate from it on purpose: the two count
    /// the same fact caught at the two different instants it can be caught at, and a ride where this
    /// one is non-zero is a ride where a frame outlived its own lifetime's teardown rather than merely
    /// sitting behind it.
    public private(set) var refusedRetiredSignalCount = 0

    /// Local authority events retired while queued; these are not dropped peer signals.
    public private(set) var discardedRetiredAvailabilityCount = 0

    /// Local authority events refused on arrival, separate from refused peer signals.
    public private(set) var refusedRetiredAvailabilityCount = 0

    /// **The highest control authentication generation known to have been retired**, or nil while none
    /// has been (STATUS §4 problem 60).
    ///
    /// A monotonic floor is correct here, and that rests on facts about the *producer* rather than on
    /// anything this type could enforce, so they are stated:
    ///
    /// 1. `ControlSessionManager.activateAuthenticatedSession` is the only place a generation is
    ///    allocated, it does `authenticationGeneration += 1`, and nothing anywhere resets that counter
    ///    -- `shutdown()` un-latches the manager for reuse without touching it.
    /// 2. A generation is therefore never reused, and a successor's is always strictly greater than
    ///    every predecessor's, *including* across a `shutdown()`/`startListening()` cycle.
    /// 3. A genuinely new ride session builds a **new** `VoiceController`, and therefore a new mailbox
    ///    with a nil floor: `SessionCoordinator.retireSession` clears `voice` synchronously, so
    ///    `attachVoice` constructs a fresh one. The floor can never outlive the manager whose counter
    ///    produced it.
    ///
    /// What a floor deliberately does **not** assume is arrival order. ADR-024 Amendment A7 made
    /// generation *arrival* non-monotonic on purpose (`A, B, A` reaches a consumer), and this is
    /// unaffected: retirement is a statement about a lifetime, not about when its frames turn up. A
    /// `.controlLinkLost` for an older generation arriving after a newer one has already been retired
    /// raises the floor to neither -- `max` keeps it where it was.
    ///
    /// A single optional rather than a set: a set would have to be bounded, and a bound would have to
    /// evict, and an evicted entry is a retired lifetime silently becoming live again. Monotonicity is
    /// what makes one number both exact and unbounded-memory-free.
    public private(set) var retiredControlGenerationFloor: Int64?

    /// **The newest control generation this mailbox has ever admitted a peer signal from**, or nil
    /// before any.
    ///
    /// The *implied* half of retirement, and it is what makes the rule hold without waiting for a
    /// `.controlLinkLost` to arrive. `ControlSessionManager` holds exactly one `authenticatedConnection`
    /// at a time and allocates a strictly greater generation for each, so observing a frame admitted by
    /// generation B **proves** that A ended before B was activated — whatever order the two lifetimes'
    /// events reach this type in, and whether or not A's own boundary has been delivered yet.
    ///
    /// Without this, closing the window would rest on `.controlLinkLost(A)` arriving before A's late
    /// frame, which is precisely the timing assumption STATUS §4 problem 60 is about. With it, the one
    /// case a boundary alone could not reach is closed too: an A-generation signal that passed
    /// `VoiceSignalRelay`'s liveness check an instant before the teardown, and is offered while B's work
    /// is already here. In the `.coalesced` lane that signal would otherwise **overwrite** B's — and
    /// PROTOCOL §7.3's intent-to-talk lives in that lane, so losing it wedges voice for the ride segment.
    ///
    /// Strictly `<`, never `<=`: a signal from the same generation as the newest admitted one is the
    /// live lifetime's own, and coalescing among those is the lane's whole purpose.
    public private(set) var newestAdmittedControlGeneration: Int64?

    public init(
        criticalCapacity: Int = VoiceInputMailbox.criticalCapacity,
        iceCapacity: Int = VoiceBounds.maxQueuedCandidates,
        terminalPeerStateCapacity: Int = VoiceInputMailbox.terminalPeerStateCapacity
    ) {
        self.criticalCapacity = criticalCapacity
        self.iceCapacity = iceCapacity
        self.terminalPeerStateCapacity = terminalPeerStateCapacity
    }

    /// Classifies one input, and -- for the two inputs that carry a control-lifetime identity --
    /// decides it against that identity rather than against what happens to be queued.
    ///
    /// **STATUS §4 problem 60.** Until this type knew which control generation admitted a peer signal,
    /// a `.controlLinkLost` could only express "discard every remote signal queued right now", which
    /// is a statement about *arrival order*. Two production orderings made that wrong in both
    /// directions, and neither is a race this type or its callers serialise:
    ///
    /// - **A retired lifetime's signal offered after its own link loss.** `VoiceSignalRelay.deliver`
    ///   reads the live generation and then calls `sink.submit`, with nothing spanning the two, while
    ///   `endConnection` runs on a different actor. A frame that passed the check can be overtaken by
    ///   the whole teardown and land *after* the discard -- and `offerReceived`'s generation guard is
    ///   skipped from `.idle`, so it would be answered on a dead link. That is problem 50 reappearing
    ///   by a different route.
    /// - **A successor lifetime's signal deleted by a delayed link loss.** `.linkLost` reaches
    ///   `VoiceController.onControlLinkLost` through `SessionCoordinator`'s event consumer, never
    ///   synchronously from `endConnection`, and is then deferred once more into `launchInSession`.
    ///   Meanwhile an **inbound** promotion authenticates a successor through
    ///   `ControlSessionManager.promote`, which waits on nothing that consumer does -- so the
    ///   successor's own `VOICE_OFFER` can be admitted, submitted and queued before the predecessor's
    ///   link loss is even dequeued. A blanket discard then deletes it, and the wedge is problem 56's.
    ///
    /// Both are closed by identity instead of by timing. `.signalReceived` carries the generation that
    /// admitted it -- immutable provenance from `ReadFrameBinding`, never re-read from live state --
    /// and `.controlLinkLost` carries the generation that ended. The rule is then symmetric and
    /// order-free:
    ///
    /// > A semantic `VOICE_*` input may affect `VoiceNegotiation` only while the control generation
    /// > that admitted it has not been retired. Retiring generation A may discard or refuse A's
    /// > semantic work, and may never discard or refuse B's.
    ///
    /// Applied at **both** instants, because either alone is insufficient: a signal already queued
    /// when its lifetime is retired is discarded here, and one arriving afterwards is refused here.
    @discardableResult
    public mutating func offer(_ input: VoiceInput) -> VoiceMailboxOutcome {
        // Both frames and availability carry immutable control authority. A newer availability
        // proves older inputs stale just as a newer frame does (ADR-020 A11).
        switch input {
        case .signalReceived(_, let generation, _), .controlAuthenticated(let generation, _):
            if isStale(generation) {
                if case .signalReceived = input {
                    refusedRetiredSignalCount += 1
                } else {
                    refusedRetiredAvailabilityCount += 1
                }
                return .retiredGeneration
            }
            admitGeneration(generation)
        default:
            break
        }
        switch Self.lane(for: input) {
        case .teardown:
            // A `.controlLinkLost` naming a generation ends that generation, here and permanently: the
            // floor only ever rises, so a link loss for an *older* lifetime arriving after a newer one
            // has already been retired cannot lower it and cannot un-retire anything.
            //
            // A nil generation retires nothing, and there are exactly two producers of one -- a
            // connection that never authenticated, and the mailbox-overflow degrade. Neither is a
            // lifetime boundary, so neither owns anybody's queued work. The overflow case is a
            // deliberate narrowing of what this branch used to do (CLAUDE.md rule 22): an overflow is
            // a local fact about this device's own bound, every signal still queued belongs to a
            // lifetime that is still live, and deleting live work because something else went wrong is
            // the very defect above. The degrade itself is unchanged -- the reducer still returns to
            // `.idle` and still stops the media transport.
            //
            // Local inputs are never discarded on any path: this user's consent, the engine's own
            // callbacks and the intercom gate's state are not the retired peer's to withdraw, and the
            // engine callbacks carry their own `voice_session_id` guard. `.stopRequested` shares this
            // lane but is **not** a lifetime boundary -- the link is still up when a user presses End
            // Voice -- so it retires nothing and discards nothing.
            // The teardown itself is **never** suppressed, whichever lifetime it names. A boundary
            // applied after a successor's work has already been *reduced* can retire the successor's
            // negotiation, and suppressing it to avoid that was tried and rejected: admission is not
            // application, so "a newer generation admitted something" does not imply its negotiation
            // is live, and suppressing on that premise leaves a dead lifetime's negotiation standing
            // — which `offerReceived` then answers with `.generationMismatch` for every offer the
            // successor sends. That residue is recorded as STATUS §4 problem 61 rather than
            // half-fixed here; it needs the pure table to know which control lifetime owns a
            // negotiation, which is an ADR-scale change and not problem 60's.
            if case .controlLinkLost(let retired) = input { retire(retired) }
            // Latest wins, except that a pending stop is never displaced — see `.teardown`. The
            // retirement above still happened: ownership of the retired lifetime's queued remote work
            // belongs to the link loss whether or not its own slot survives, and a `.stopRequested`
            // applied in its place tears the same media down and releases capture as well.
            let stopPending: Bool = { if case .stopRequested = teardown { return true }; return false }()
            let isLinkLoss: Bool = { if case .controlLinkLost = input { return true }; return false }()
            if !(stopPending && isLinkLoss) { teardown = input }
            return .accepted(lane: .teardown)
        case .sendFailure:
            sendFailure = input
            return .accepted(lane: .sendFailure)
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


    /// Whether `generation`'s control lifetime has ended, by either of the two things that can say so:
    /// its own boundary (`retiredControlGenerationFloor`), or the existence of a newer one
    /// (`newestAdmittedControlGeneration`). Both are needed — see each property's own doc.
    private func isStale(_ generation: Int64) -> Bool {
        let floor = retiredControlGenerationFloor
        let newest = newestAdmittedControlGeneration
        // `<=` against the floor (that generation itself ended) and `<` against the newest admitted
        // (that one is still live, and coalescing among its own signals is the lane's whole purpose).
        return (floor.map { generation <= $0 } ?? false) || (newest.map { generation < $0 } ?? false)
    }

    /// Records that `generation` admitted a peer signal. A generation newer than any seen before retires
    /// every older one by implication, so the sweep runs here exactly as it does on an explicit boundary.
    private mutating func admitGeneration(_ generation: Int64) {
        if let newest = newestAdmittedControlGeneration, generation <= newest { return }
        newestAdmittedControlGeneration = generation
        discardRetiredRemoteSignals()
    }

    /// Ends `generation`, raising the monotonic floor and discarding the work it owned -- the "queued
    /// before retirement" half. A nil `generation` is not a lifetime boundary and does neither.
    private mutating func retire(_ generation: Int64?) {
        guard let generation else { return }
        retiredControlGenerationFloor = Swift.max(retiredControlGenerationFloor ?? generation, generation)
        discardRetiredRemoteSignals()
    }

    /// Removes every queued `.signalReceived` **whose admitting generation has ended** — and nothing
    /// else — from the four lanes that can hold one. Run whenever either half of `isStale` moves.
    ///
    /// The predicate is the whole fix: it used to be "is this a `.signalReceived`", which discarded a
    /// successor lifetime's freshly admitted offer along with the predecessor's (STATUS §4 problem
    /// 60). Local inputs match no branch of it and never could — except `controlAuthenticated`, which
    /// shares the peer signals' staleness rule (Amendment A11) and therefore the sweep: an
    /// availability event still queued when its own lifetime's boundary lands would otherwise
    /// resurrect that lifetime's record after the boundary cleared it.
    private mutating func discardRetiredRemoteSignals() {
        let stale = isStale
        func isStaleInput(_ input: VoiceInput) -> Bool {
            switch input {
            case .signalReceived(_, let controlGeneration, _):
                return stale(controlGeneration)
            case .controlAuthenticated(let controlGeneration, _):
                return stale(controlGeneration)
            default:
                return false
            }
        }
        let retiredAvailabilityCount = critical.filter {
            if case .controlAuthenticated = $0 { return isStaleInput($0) }
            return false
        }.count
        discardedRetiredAvailabilityCount += retiredAvailabilityCount
        discardedRetiredSignalCount +=
            terminalPeerState.filter(isStaleInput).count
                + critical.filter(isStaleInput).count - retiredAvailabilityCount
                + ice.filter(isStaleInput).count
                + coalesced.values.filter(isStaleInput).count
        terminalPeerState.removeAll(where: isStaleInput)
        critical.removeAll(where: isStaleInput)
        ice.removeAll(where: isStaleInput)
        // The only coalesced kinds a peer produces is `.peerState`; mute/mode/remote-track are local.
        let retiredKeys = coalesced.filter { isStaleInput($0.value) }.map(\.key)
        for key in retiredKeys {
            coalesced.removeValue(forKey: key)
            coalesceOrder.removeAll { $0 == key }
        }
    }

    /// Removes and returns the next input to apply, in `VoiceMailboxLane` priority order, or `nil` if empty.
    public mutating func poll() -> VoiceInput? {
        if let next = teardown {
            teardown = nil
            // Coalesced losses still name the newest lifetime known to have ended.
            if case .controlLinkLost(let retired) = next, retired != nil {
                return .controlLinkLost(retiredControlGeneration: retiredControlGenerationFloor)
            }
            return next
        }
        if let next = sendFailure {
            sendFailure = nil
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
        teardown == nil && sendFailure == nil && terminalPeerState.isEmpty && critical.isEmpty && ice.isEmpty
            && coalesced.isEmpty
    }

    /// The whole queued backlog, for diagnostics only — nothing here decides anything from this.
    public var count: Int {
        (teardown == nil ? 0 : 1) + (sendFailure == nil ? 0 : 1)
            + terminalPeerState.count + critical.count + ice.count + coalesced.count
    }

    /// Drops the whole queued backlog. `retiredControlGenerationFloor` and
    /// `newestAdmittedControlGeneration` deliberately survive: a retired lifetime is never un-retired,
    /// and the only caller is `VoiceController.shutdown()`, after which this mailbox is never offered
    /// to again.
    public mutating func clear() {
        teardown = nil
        sendFailure = nil
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
        case .negotiationSendFailed:
            return .sendFailure
        case .startRequested, .localOfferCreated, .localAnswerCreated, .mediaConnectivityChanged:
            return .critical
        // The successor-lifetime availability event (ADR-020 Amendment A11). Critical rather than
        // coalesced: it must keep FIFO order against a deferred `.startRequested` in the same lane,
        // which is exactly the ordering problem 69 is — the press and the authentication have to
        // reduce in the order they actually happened, and a coalesced slot would let a second
        // authentication overwrite a first before the press between them was ever seen.
        case .controlAuthenticated:
            return .critical
        case .signalReceived(let signal, _, _):
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
