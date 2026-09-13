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
 * discards the remote signals it outranks — see [VoiceInputMailbox.offer]. This doc used to claim
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

    fun offer(input: VoiceInput): VoiceMailboxOutcome =
        when (val lane = laneFor(input)) {
            VoiceMailboxLane.TEARDOWN -> {
                // STATUS §4 problem 50. A `ControlLinkLost` ends the control lifetime that admitted
                // every `SignalReceived` currently queued below it, and this lane outranks all of
                // them — so applying it first would reset the reducer and *then* hand it a retired
                // peer's offer, which `offerReceived` would accept as a fresh one (its generation
                // guard is skipped when `voiceSessionId` is null) and answer on a dead link.
                //
                // The teardown that jumps the queue takes ownership of the remote work it jumped, and
                // **offer** time is where that ownership is least wrong: a later lifetime's frames are
                // normally offered strictly after this call and are untouched, so this is not a blanket
                // flush, even if the consumer is starved for the whole reconnect.
                //
                // **This is scoped by arrival order, not by lifetime identity, and the difference is
                // real** (STATUS §4 problem 60). The claim that used to stand here — "nothing remote
                // can be offered between the lifetime ending and this call" — was re-audited and is
                // **false as written**. It rests on two orderings neither this type nor its callers
                // enforce. First, `VoiceSignalRelay.deliver` reads the live generation and then calls
                // `sink.submit` with no lock spanning the two, while `endConnection` clears the
                // authenticated connection from another coroutine — so a retired frame can pass the
                // check, be overtaken by the whole teardown, and be offered *after* this discard.
                // Second, `ControlEvent.LinkLost` reaches `VoiceController.onControlLinkLost` through
                // `SessionCoordinator`'s event consumer, not synchronously from `endConnection`, while
                // an **inbound** promotion can authenticate a successor without passing through that
                // consumer at all — so a successor's frame can be offered before this runs.
                //
                // Both windows are instruction-wide and neither is reproducible at any seam this layer
                // exposes, which is why they are recorded rather than papered over. What closes them by
                // construction is carrying the admitting generation to the sink and giving this type a
                // retired-generation floor, so "whose work is this" stops being a question about when
                // it arrived. That is recorded as the follow-up in ADR-020 Amendment A3 rather than
                // done here, because it changes `VoiceSignalSink` on both platforms.
                //
                // What is **not** in doubt any more is the other direction: a send whose `Boolean`
                // came back late cannot reach this branch at all, because a send failure is
                // `NegotiationSendFailed` and not a lifetime boundary (problem 57).
                //
                // Local inputs are deliberately kept: this user's consent, the engine's own
                // callbacks and the intercom gate's state are not the retired peer's to withdraw,
                // and the engine callbacks carry their own `voice_session_id` guard already.
                // `StopRequested` shares this lane but is **not** a lifetime boundary — the link is
                // still up when a user presses End Voice — so it discards nothing.
                if (input == VoiceInput.ControlLinkLost) discardRetiredRemoteSignals()
                // Latest wins, except that a pending stop is never displaced — see [VoiceMailboxLane.TEARDOWN].
                // The discard above still happened: ownership of the retired lifetime's queued remote work
                // belongs to the link loss whether or not its own slot survives, and a `StopRequested`
                // applied in its place tears the same media down and releases capture as well.
                if (!(teardown == VoiceInput.StopRequested && input == VoiceInput.ControlLinkLost)) teardown = input
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

    fun clear() {
        teardown = null
        sendFailure = null
        terminalPeerState.clear()
        critical.clear()
        ice.clear()
        coalesced.clear()
    }

    /**
     * Removes every queued [VoiceInput.SignalReceived] — and nothing else — from the four lanes that
     * can hold one. See [offer]'s [VoiceMailboxLane.TEARDOWN] branch for why this is the teardown's
     * responsibility and why offer time is the only instant at which it is exact.
     */
    private fun discardRetiredRemoteSignals() {
        discardedRetiredSignalCount +=
            terminalPeerState.count { it is VoiceInput.SignalReceived } +
            critical.count { it is VoiceInput.SignalReceived } +
            ice.count { it is VoiceInput.SignalReceived } +
            coalesced.values.count { it is VoiceInput.SignalReceived }
        terminalPeerState.removeAll { it is VoiceInput.SignalReceived }
        critical.removeAll { it is VoiceInput.SignalReceived }
        ice.removeAll { it is VoiceInput.SignalReceived }
        // The only coalesced kind a peer produces is PEER_STATE; MUTE/MODE/REMOTE_TRACK are local.
        coalesced.values.removeAll { it is VoiceInput.SignalReceived }
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
                VoiceInput.StopRequested, VoiceInput.ControlLinkLost -> VoiceMailboxLane.TEARDOWN
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
