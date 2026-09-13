package com.ridelink.core.voice

import com.ridelink.core.protocol.VoiceBounds
import com.ridelink.core.protocol.VoiceSignal
import com.ridelink.core.protocol.VoiceWireState

/**
 * Where a [VoiceInput] is classified before it ever reaches the pure [VoiceNegotiation] table.
 *
 * Priority order for [VoiceInputMailbox.poll] is [TEARDOWN] > [SEND_FAILURE] > [TERMINAL_PEER_STATE] >
 * [CRITICAL] > [ICE] > [COALESCED]: a pending stop or link loss must never sit behind a flood of trickle-ICE or
 * peer-state spam. That ordering is deliberate, and it is also why [VoiceInput.ControlLinkLost]
 * discards the remote signals it outranks — **the ones its own retired generation admitted, and only
 * those** (STATUS §4 problem 60); see [VoiceInputMailbox.offer]. This doc used to claim
 * that anything stale queued below a teardown "becomes inert on its own" via the
 * [VoiceEngineGeneration] / `voice_session_id` guard. **That was false** for the two branches that
 * *begin* a negotiation rather than advance one (`offerReceived`'s full accept and
 * `peerWantsVoice`): both are guarded only when `voiceSessionId` is non-null, and a teardown resets
 * it to null, so the guard is skipped exactly when it is needed (STATUS §4 problem 50).
 * [TERMINAL_PEER_STATE] sits directly below [TEARDOWN] and above
 * [CRITICAL] so a peer's own teardown signal is never delayed behind a flood of offers/answers, and
 * strictly above [COALESCED] so it can never be classified alongside — and therefore silently
 * overwritten by — an ordinary peer-state update.
 */
enum class VoiceMailboxLane {
    /**
     * [VoiceInput.StopRequested] / [VoiceInput.ControlLinkLost]. One slot, never refused.
     *
     * Latest wins with **one exception**: a pending [VoiceInput.StopRequested] is never displaced
     * (STATUS §4 problem 57). A stop is a strict superset of a link loss — it also releases the
     * capture device — and it is the only input `VoiceController.shutdown()` and
     * `stopAndAwaitRelease()` can ever complete on, so overwriting one means an unbounded wait on a
     * release that will now never be applied, and therefore a `SessionCoordinator.retireSession`
     * that can never emit `TeardownComplete` (ADR-026 / rule 21).
     */
    TEARDOWN,

    /**
     * [VoiceInput.NegotiationSendFailed]. One slot, latest wins, never refused.
     *
     * A lane of its own rather than a second occupant of [TEARDOWN], for two reasons that are both
     * defects it closes (STATUS §4 problem 57). It must **outrank** [CRITICAL], because the whole
     * point is to return the table to `IDLE` before the successor lifetime's queued
     * `VOICE_OFFER` is reduced — reduced against a still-live retired negotiation, that offer is a
     * `GENERATION_MISMATCH` and is dropped. And it must **not share** [TEARDOWN]'s single slot,
     * because a send failure arriving from the consumer's own resume would otherwise replace a
     * pending teardown, taking either the link loss's ownership of queued remote work or the stop's
     * capture release with it.
     */
    SEND_FAILURE,

    /**
     * A peer's own `VOICE_STATE { state: closed | failed }`. Unlike an ordinary peer-state update
     * (`negotiating`/`connecting`/`active`/`idle`/`unknown`), the reducer gives these teardown
     * semantics ([VoiceNegotiation]'s `teardownFromPeer`), so a later ordinary update must never be
     * allowed to coalesce over — and thereby erase — one still sitting here undelivered.
     */
    TERMINAL_PEER_STATE,

    /** Cannot be silently lost: local start/offer/answer/connectivity, and a peer's Offer/Answer. */
    CRITICAL,

    /** ICE-candidate-shaped inputs, local or remote. Bounded exactly as PROTOCOL §7.4's own queue is. */
    ICE,

    /** Only the newest value of its kind is ever meaningful. Fixed slots, always accepted. */
    COALESCED,
}

/** What [VoiceInputMailbox.offer] did with one input. */
sealed class VoiceMailboxOutcome {
    /** Held, in [lane], to be delivered in FIFO order relative to the rest of that lane. */
    data class Accepted(
        val lane: VoiceMailboxLane,
    ) : VoiceMailboxOutcome()

    /** Replaced a same-kind value that had not been delivered yet. Nothing that still mattered was lost. */
    object Coalesced : VoiceMailboxOutcome()

    /** The ICE lane was full; the oldest queued candidate was discarded to hold this one. */
    object IceEvicted : VoiceMailboxOutcome()

    /**
     * The critical lane was full and this input was refused outright. The driver is expected to
     * force a safe degrade in response — a critical input cannot simply vanish with nothing done
     * about it, unlike [IceEvicted] or [Coalesced].
     */
    object CriticalOverflow : VoiceMailboxOutcome()

    /**
     * The terminal-peer-state lane was full and this input was refused outright. Exactly like
     * [CriticalOverflow] — refusing a `closed`/`failed` signal outright and forcing a safe degrade is
     * simpler and strictly safer than evicting an *earlier* terminal event to make room for this one,
     * which would risk discarding the one signal the lane exists to protect.
     */
    object TerminalPeerStateOverflow : VoiceMailboxOutcome()

    /**
     * A [VoiceInput.SignalReceived] whose admitting control generation has already been retired
     * (STATUS §4 problem 60). Refused without being held, and **not** an overflow: nothing was lost
     * that still mattered, so this must never drive the [CriticalOverflow] degrade.
     *
     * This is the "admitted after retirement" half of the fix. The "queued before retirement" half
     * is [VoiceInputMailbox.discardedRetiredSignalCount]; between them there is no instant at which
     * a retired lifetime's semantic work can reach [VoiceNegotiation], and neither half depends on
     * the order the two arrived in.
     */
    object RetiredGeneration : VoiceMailboxOutcome()
}

/**
 * PROTOCOL §7.4/§7.8's bounded mailbox policy, extracted so a laptop test can exhaust it.
 *
 * Before this type existed, every `VOICE_*` frame that had already passed the ADR-019 trust gate
 * went straight into an unbounded channel ahead of the pure table — so an authenticated-but-
 * compromised peer could grow `VoiceController`'s memory just by sending frames faster than they
 * were consumed, regardless of any bound the reducer or [PendingCandidates] applied afterward. Every
 * lane here is bounded for that reason, and [VoiceMailboxLane.ICE]'s bound is the same
 * [VoiceBounds.MAX_QUEUED_CANDIDATES] constant [PendingCandidates] already enforces one layer later
 * — one policy, not two that could quietly disagree.
 *
 * **Not thread-safe by itself.** [offer] is called from whatever thread produced the input (the
 * control read loop, a WebRTC callback, the UI); [poll] is called only by the single consumer.
 * `VoiceController` — on both platforms — serialises access with its own lock, the same way the
 * unbounded `Channel`/`AsyncStream` it replaces was itself safe to send into from any thread. Pure
 * otherwise: no clock, no coroutine, no platform type, mirrored line for line as
 * `RideLinkPlatform.VoiceInputMailbox`.
 */
class VoiceInputMailbox(
    private val criticalCapacity: Int = CRITICAL_CAPACITY,
    private val iceCapacity: Int = VoiceBounds.MAX_QUEUED_CANDIDATES,
    private val terminalPeerStateCapacity: Int = TERMINAL_PEER_STATE_CAPACITY,
) {
    private var teardown: VoiceInput? = null
    private var sendFailure: VoiceInput? = null
    private val terminalPeerState = ArrayDeque<VoiceInput>()
    private val critical = ArrayDeque<VoiceInput>()
    private val ice = ArrayDeque<VoiceInput>()
    private val coalesced = LinkedHashMap<CoalesceKey, VoiceInput>()

    /**
     * [VoiceMailboxOutcome.IceEvicted] + [VoiceMailboxOutcome.CriticalOverflow] +
     * [VoiceMailboxOutcome.TerminalPeerStateOverflow], combined: one honest count of "a well-formed
     * input could not be held as it arrived."
     */
    var overflowCount: Int = 0
        private set

    /**
     * How many queued peer signals were discarded because the control lifetime that admitted them
     * ended before they were applied (STATUS §4 problem 50).
     *
     * Surfaced rather than silent, for the same reason [overflowCount] is: "the peer's offer never
     * arrived" and "it arrived and its link died before we got to it" are different facts, and only
     * the second one says the ride hit a blip rather than a bug.
     */
    var discardedRetiredSignalCount: Int = 0
        private set

    /**
     * How many peer signals were refused on arrival because the control lifetime that admitted them
     * had **already** been retired (STATUS §4 problem 60).
     *
     * The counterpart to [discardedRetiredSignalCount] and separate from it on purpose: the two
     * count the same fact caught at the two different instants it can be caught at, and a ride where
     * this one is non-zero is a ride where a frame outlived its own lifetime's teardown rather than
     * merely sitting behind it.
     */
    var refusedRetiredSignalCount: Int = 0
        private set

    /**
     * **The highest control authentication generation known to have been retired**, or null while
     * none has been (STATUS §4 problem 60).
     *
     * A monotonic floor is correct here, and that rests on facts about the *producer* rather than on
     * anything this type could enforce, so they are stated:
     *
     * 1. `ControlSessionManager.activateAuthenticatedSession` is the only place a generation is
     *    allocated, it does `authenticationGeneration += 1`, and nothing anywhere resets that
     *    counter — `shutdown()` un-latches the manager for reuse without touching it.
     * 2. A generation is therefore never reused, and a successor's is always strictly greater than
     *    every predecessor's, *including* across a `shutdown()`/`startListening()` cycle.
     * 3. A genuinely new ride session builds a **new** `VoiceController`, and therefore a new
     *    mailbox with a null floor: `SessionCoordinator.retireSession` clears `voice` synchronously,
     *    so `attachVoice` constructs a fresh one. The floor can never outlive the manager whose
     *    counter produced it.
     *
     * What a floor deliberately does **not** assume is arrival order. ADR-024 Amendment A7 made
     * generation *arrival* non-monotonic on purpose (`A, B, A` reaches a consumer), and this is
     * unaffected: retirement is a statement about a lifetime, not about when its frames turn up. A
     * `ControlLinkLost` for an older generation arriving after a newer one has already been retired
     * raises the floor to neither — [maxOf] keeps it where it was.
     *
     * A single `Long?` rather than a set: a set would have to be bounded, and a bound would have to
     * evict, and an evicted entry is a retired lifetime silently becoming live again. Monotonicity
     * is what makes one number both exact and unbounded-memory-free.
     */
    var retiredControlGenerationFloor: Long? = null
        private set

    /**
     * **The newest control generation this mailbox has ever admitted a peer signal from**, or null
     * before any.
     *
     * The *implied* half of retirement, and it is what makes the rule hold without waiting for a
     * `ControlLinkLost` to arrive. `ControlSessionManager` holds exactly one
     * `authenticatedConnection` at a time and allocates a strictly greater generation for each, so
     * observing a frame admitted by generation B **proves** that A ended before B was activated —
     * whatever order the two lifetimes' events reach this type in, and whether or not A's own
     * boundary has been delivered yet.
     *
     * Without this, closing the window would rest on `ControlLinkLost(A)` arriving before A's late
     * frame, which is precisely the timing assumption STATUS §4 problem 60 is about. With it, the
     * one case a boundary alone could not reach is closed too: an A-generation signal that passed
     * `VoiceSignalRelay`'s liveness check an instant before the teardown, and is offered while B's
     * work is already here. In the [VoiceMailboxLane.COALESCED] lane that signal would otherwise
     * **overwrite** B's — and PROTOCOL §7.3's intent-to-talk lives in that lane, so losing it wedges
     * voice for the ride segment.
     *
     * Strictly `<`, never `<=`: a signal from the same generation as the newest admitted one is the
     * live lifetime's own, and coalescing among those is the lane's whole purpose.
     */
    var newestAdmittedControlGeneration: Long? = null
        private set

    /**
     * Classifies one input, and — for the two inputs that carry a control-lifetime identity —
     * decides it against that identity rather than against what happens to be queued.
     *
     * **STATUS §4 problem 60.** Until this type knew which control generation admitted a peer
     * signal, a `ControlLinkLost` could only express "discard every remote signal queued right now",
     * which is a statement about *arrival order*. Two productions orderings made that wrong in both
     * directions, and neither is a race this type or its callers serialise:
     *
     * - **A retired lifetime's signal offered after its own link loss.** `VoiceSignalRelay.deliver`
     *   reads the live generation and then calls `sink.submit`, with no lock spanning the two, while
     *   `endConnection` clears the authenticated record from another coroutine (Android) or another
     *   actor (iOS). A frame that passed the check can be overtaken by the whole teardown and land
     *   *after* the discard — and `offerReceived`'s generation guard is skipped from `IDLE`, so it
     *   would be answered on a dead link. That is problem 50 reappearing by a different route.
     * - **A successor lifetime's signal deleted by a delayed link loss.** `ControlEvent.LinkLost`
     *   reaches `VoiceController.onControlLinkLost` through `SessionCoordinator`'s event consumer,
     *   never synchronously from `endConnection`; on iOS it is deferred once more into
     *   `launchInSession`. Meanwhile an **inbound** promotion authenticates a successor through
     *   `ControlSessionManager.promote`, which waits on nothing that consumer does — so the
     *   successor's own `VOICE_OFFER` can be admitted, submitted and queued before the predecessor's
     *   link loss is even dequeued. A blanket discard then deletes it, and the wedge is problem 56's.
     *
     * Both are closed by identity instead of by timing. [VoiceInput.SignalReceived] carries the
     * generation that admitted it — immutable provenance from `ReadFrameBinding`, never re-read from
     * live state — and [VoiceInput.ControlLinkLost] carries the generation that ended. The rule is
     * then symmetric and order-free:
     *
     * > A semantic `VOICE_*` input may affect [VoiceNegotiation] only while the control generation
     * > that admitted it has not been retired. Retiring generation A may discard or refuse A's
     * > semantic work, and may never discard or refuse B's.
     *
     * Applied at **both** instants, because either alone is insufficient: a signal already queued
     * when its lifetime is retired is discarded here, and one arriving afterwards is refused here.
     */
    fun offer(input: VoiceInput): VoiceMailboxOutcome {
        // The "admitted after retirement" half. Checked before the lane is even chosen: a refused
        // signal occupies nothing, so it cannot overflow a lane and cannot force a degrade.
        if (input is VoiceInput.SignalReceived) {
            if (isStale(input.controlGeneration)) {
                refusedRetiredSignalCount += 1
                return VoiceMailboxOutcome.RetiredGeneration
            }
            admitGeneration(input.controlGeneration)
        }
        return when (val lane = laneFor(input)) {
            VoiceMailboxLane.TEARDOWN -> {
                // A `ControlLinkLost` naming a generation ends that generation, here and permanently:
                // the floor only ever rises, so a link loss for an *older* lifetime arriving after a
                // newer one has already been retired cannot lower it and cannot un-retire anything.
                //
                // A null generation retires nothing, and there are exactly two producers of one — a
                // connection that never authenticated, and the mailbox-overflow degrade. Neither is
                // a lifetime boundary, so neither owns anybody's queued work. The overflow case is a
                // deliberate narrowing of what this branch used to do (CLAUDE.md rule 22): an
                // overflow is a local fact about this device's own bound, every signal still queued
                // belongs to a lifetime that is still live, and deleting live work because something
                // else went wrong is the very defect above. The degrade itself is unchanged — the
                // reducer still returns to `IDLE` and still stops the media transport.
                //
                // Local inputs are never discarded on any path: this user's consent, the engine's
                // own callbacks and the intercom gate's state are not the retired peer's to
                // withdraw, and the engine callbacks carry their own `voice_session_id` guard.
                // `StopRequested` shares this lane but is **not** a lifetime boundary — the link is
                // still up when a user presses End Voice — so it retires nothing and discards
                // nothing.
                // The teardown itself is **never** suppressed, whichever lifetime it names. A
                // boundary applied after a successor's work has already been *reduced* can retire
                // the successor's negotiation, and suppressing it to avoid that was tried and
                // rejected: admission is not application, so "a newer generation admitted something"
                // does not imply its negotiation is live, and suppressing on that premise leaves a
                // dead lifetime's negotiation standing — which `offerReceived` then answers with
                // `GENERATION_MISMATCH` for every offer the successor sends. That residue is
                // recorded as STATUS §4 problem 61 rather than half-fixed here; it needs the pure
                // table to know which control lifetime owns a negotiation, which is an ADR-scale
                // change and not problem 60's.
                if (input is VoiceInput.ControlLinkLost) retire(input.retiredControlGeneration)
                // Latest wins, except that a pending stop is never displaced — see [VoiceMailboxLane.TEARDOWN].
                // The retirement above still happened: ownership of the retired lifetime's queued remote work
                // belongs to the link loss whether or not its own slot survives, and a `StopRequested`
                // applied in its place tears the same media down and releases capture as well.
                if (!(teardown == VoiceInput.StopRequested && input is VoiceInput.ControlLinkLost)) teardown = input
                VoiceMailboxOutcome.Accepted(lane)
            }
            VoiceMailboxLane.SEND_FAILURE -> {
                sendFailure = input
                VoiceMailboxOutcome.Accepted(lane)
            }
            VoiceMailboxLane.TERMINAL_PEER_STATE -> {
                if (terminalPeerState.size >= terminalPeerStateCapacity) {
                    overflowCount += 1
                    VoiceMailboxOutcome.TerminalPeerStateOverflow
                } else {
                    terminalPeerState.addLast(input)
                    VoiceMailboxOutcome.Accepted(lane)
                }
            }
            VoiceMailboxLane.CRITICAL -> {
                if (critical.size >= criticalCapacity) {
                    overflowCount += 1
                    VoiceMailboxOutcome.CriticalOverflow
                } else {
                    critical.addLast(input)
                    VoiceMailboxOutcome.Accepted(lane)
                }
            }
            VoiceMailboxLane.ICE -> {
                if (ice.size >= iceCapacity) {
                    ice.removeFirst()
                    overflowCount += 1
                    ice.addLast(input)
                    VoiceMailboxOutcome.IceEvicted
                } else {
                    ice.addLast(input)
                    VoiceMailboxOutcome.Accepted(lane)
                }
            }
            VoiceMailboxLane.COALESCED -> {
                val replaced = coalesced.put(coalesceKeyFor(input), input) != null
                if (replaced) VoiceMailboxOutcome.Coalesced else VoiceMailboxOutcome.Accepted(lane)
            }
        }
    }

    /**
     * Whether [generation]'s control lifetime has ended, by either of the two things that can say
     * so: its own boundary ([retiredControlGenerationFloor]), or the existence of a newer one
     * ([newestAdmittedControlGeneration]). Both are needed — see each field's own doc.
     */
    private fun isStale(generation: Long): Boolean {
        val floor = retiredControlGenerationFloor
        val newest = newestAdmittedControlGeneration
        // `<=` against the floor (that generation itself ended) and `<` against the newest admitted
        // (that one is still live, and coalescing among its own signals is the lane's whole purpose).
        return (floor != null && generation <= floor) || (newest != null && generation < newest)
    }

    /**
     * Records that [generation] admitted a peer signal. A generation newer than any seen before
     * retires every older one by implication, so the sweep runs here exactly as it does on an
     * explicit boundary.
     */
    private fun admitGeneration(generation: Long) {
        val newest = newestAdmittedControlGeneration
        if (newest != null && generation <= newest) return
        newestAdmittedControlGeneration = generation
        discardRetiredRemoteSignals()
    }

    /**
     * Ends [generation], raising the monotonic floor and discarding the work it owned — the "queued
     * before retirement" half. A null [generation] is not a lifetime boundary and does neither.
     */
    private fun retire(generation: Long?) {
        if (generation == null) return
        val floor = retiredControlGenerationFloor
        retiredControlGenerationFloor = if (floor == null) generation else maxOf(floor, generation)
        discardRetiredRemoteSignals()
    }

    /** Removes and returns the next input to apply, in [VoiceMailboxLane] priority order, or `null` if empty. */
    @Suppress("ReturnCount") // one early-out per lane, in priority order -- splitting it hides that order
    fun poll(): VoiceInput? {
        teardown?.let {
            teardown = null
            return it
        }
        sendFailure?.let {
            sendFailure = null
            return it
        }
        if (terminalPeerState.isNotEmpty()) return terminalPeerState.removeFirst()
        if (critical.isNotEmpty()) return critical.removeFirst()
        if (ice.isNotEmpty()) return ice.removeFirst()
        val key = coalesced.keys.firstOrNull() ?: return null
        return coalesced.remove(key)
    }

    fun isEmpty(): Boolean =
        teardown == null &&
            sendFailure == null &&
            terminalPeerState.isEmpty() &&
            critical.isEmpty() &&
            ice.isEmpty() &&
            coalesced.isEmpty()

    /** The whole queued backlog, for diagnostics only — nothing here decides anything from this. */
    val size: Int
        get() =
            (if (teardown != null) 1 else 0) + (if (sendFailure != null) 1 else 0) +
                terminalPeerState.size + critical.size + ice.size + coalesced.size

    /**
     * Drops the whole queued backlog. [retiredControlGenerationFloor] and
     * [newestAdmittedControlGeneration] deliberately survive: a retired lifetime is never
     * un-retired, and the only caller is `VoiceController.shutdown()`, after which this mailbox is
     * never offered to again.
     */
    fun clear() {
        teardown = null
        sendFailure = null
        terminalPeerState.clear()
        critical.clear()
        ice.clear()
        coalesced.clear()
    }

    /**
     * Removes every queued [VoiceInput.SignalReceived] **whose admitting generation has ended** —
     * and nothing else — from the four lanes that can hold one. Run whenever either half of
     * [isStale] moves.
     *
     * The predicate is the whole fix: it used to be `it is SignalReceived`, which discarded a
     * successor lifetime's freshly admitted offer along with the predecessor's (STATUS §4 problem
     * 60). Local inputs match no branch of it and never could.
     */
    private fun discardRetiredRemoteSignals() {
        val retired: (VoiceInput) -> Boolean = { it is VoiceInput.SignalReceived && isStale(it.controlGeneration) }
        discardedRetiredSignalCount +=
            terminalPeerState.count(retired) +
            critical.count(retired) +
            ice.count(retired) +
            coalesced.values.count(retired)
        terminalPeerState.removeAll(retired)
        critical.removeAll(retired)
        ice.removeAll(retired)
        // The only coalesced kind a peer produces is PEER_STATE; MUTE/MODE/REMOTE_TRACK are local.
        coalesced.values.removeAll(retired)
    }

    private enum class CoalesceKey { MUTE, MODE, PEER_STATE, REMOTE_TRACK }

    private fun coalesceKeyFor(input: VoiceInput): CoalesceKey =
        when (input) {
            is VoiceInput.MuteRequested -> CoalesceKey.MUTE
            is VoiceInput.ModeSelected -> CoalesceKey.MODE
            is VoiceInput.RemoteTrackChanged -> CoalesceKey.REMOTE_TRACK
            is VoiceInput.SignalReceived -> CoalesceKey.PEER_STATE
            else -> error("$input is not a coalesced input")
        }

    companion object {
        /**
         * Generous relative to a real negotiation's actual traffic (one offer, one answer, a
         * handful of connectivity transitions) while still bounding what an adversarial flood of
         * critical-lane inputs — repeated `VOICE_OFFER`/`VOICE_ANSWER` frames, chiefly — can hold
         * in memory before [VoiceMailboxOutcome.CriticalOverflow] forces a safe degrade.
         */
        const val CRITICAL_CAPACITY = 32

        /**
         * A single negotiation produces at most one terminal peer state naturally — `closed` xor
         * `failed`, once, per generation. This bounds a peer that floods repeated terminal frames
         * (e.g. across several rapid teardown/rebuild cycles within one control session) rather than
         * assuming good behaviour, while staying far larger than any real ride's handful of
         * teardown/rebuild cycles would ever approach.
         */
        const val TERMINAL_PEER_STATE_CAPACITY = 8

        /** True for exactly the two PROTOCOL §7.4 wire states the reducer gives teardown semantics. */
        private fun VoiceWireState.isTerminal(): Boolean = this == VoiceWireState.CLOSED || this == VoiceWireState.FAILED

        fun laneFor(input: VoiceInput): VoiceMailboxLane =
            when (input) {
                VoiceInput.StopRequested, is VoiceInput.ControlLinkLost -> VoiceMailboxLane.TEARDOWN
                is VoiceInput.NegotiationSendFailed -> VoiceMailboxLane.SEND_FAILURE
                is VoiceInput.StartRequested,
                is VoiceInput.LocalOfferCreated,
                is VoiceInput.LocalAnswerCreated,
                is VoiceInput.MediaConnectivityChanged,
                -> VoiceMailboxLane.CRITICAL
                is VoiceInput.SignalReceived ->
                    when (val signal = input.signal) {
                        is VoiceSignal.Offer, is VoiceSignal.Answer -> VoiceMailboxLane.CRITICAL
                        is VoiceSignal.IceCandidate -> VoiceMailboxLane.ICE
                        is VoiceSignal.State ->
                            if (signal.state.isTerminal()) VoiceMailboxLane.TERMINAL_PEER_STATE else VoiceMailboxLane.COALESCED
                    }
                is VoiceInput.LocalCandidateGathered -> VoiceMailboxLane.ICE
                is VoiceInput.RemoteTrackChanged -> VoiceMailboxLane.COALESCED
                is VoiceInput.MuteRequested -> VoiceMailboxLane.COALESCED
                // Absolute, like mute: only the newest selected mode is meaningful, and losing an
                // intermediate one loses nothing the peer needed to be told.
                is VoiceInput.ModeSelected -> VoiceMailboxLane.COALESCED
            }
    }
}
