package com.ridelink.core.audiopolicy

/**
 * Which absolute value an [IntercomInput] assigns. One slot per kind — that is the whole bound.
 *
 * [poll] returns kinds in **declaration order**, which is chosen rather than incidental: policy and
 * capture first because they reset the gate's transient state, then the two overrides that can only
 * ever *stop* transmission, then the gate inputs themselves. Draining in that order means a batch
 * that arrived together can never leave transmission on when any member of it says it should be off.
 */
enum class IntercomCommandKind {
    POLICY,
    CAPTURE,
    INTERRUPTED,
    USER_MUTED,
    PTT_HELD,
    VOX_LEVEL,
}

/** What [IntercomCommandMailbox.offer] did with one input. */
enum class IntercomMailboxOutcome {
    /** Held. No same-kind value was waiting. */
    ACCEPTED,

    /**
     * Replaced a same-kind value that had not been delivered yet. Nothing that still mattered was
     * lost, because every input this mailbox carries is an **absolute** assignment
     * ([TransmissionState]) — the newest value of a kind is the only one whose effect survives.
     */
    COALESCED,
}

/**
 * The intercom command queue, **bounded by construction** rather than by a capacity check.
 *
 * This phase's brief §38 requires every new input stream to have an explicit finite buffering
 * policy. This one's is the strongest available: at most one pending value per
 * [IntercomCommandKind], so the queue can never exceed [IntercomCommandKind.entries]`.size`
 * entries no matter how fast a user (or an accessibility service, or a stuck touch) produces them.
 * There is no overflow path because there is nothing to overflow.
 *
 * **Why coalescing is safe here and is not safe for `VOICE_*`.** `VoiceInputMailbox` needs FIFO
 * lanes because a `VOICE_OFFER` and a `VOICE_ANSWER` are *distinct occurrences* that each have to be
 * applied. Every input here is instead "the current position of a control": the newest press state,
 * the newest mute state, the newest policy. Collapsing two of the same kind loses only the interval
 * between them.
 *
 * The one thing that collapse costs is visible and deliberate: a press *and* its release arriving
 * before a single drain coalesce to "not held", so an utterance shorter than one drain window is not
 * transmitted. That is the safe direction of the error — the alternative rounding would leave
 * transmission stuck **on** after a release, which this phase's brief §25 forbids outright.
 *
 * **Not thread-safe by itself**, exactly like `VoiceInputMailbox`: [offer] is called from the UI or a
 * platform callback, [poll] only from the single consumer, and `VoiceController` serialises the two.
 * Pure otherwise, and mirrored line for line as `RideLinkCore.IntercomCommandMailbox`.
 */
class IntercomCommandMailbox {
    private val slots = HashMap<IntercomCommandKind, IntercomInput>()

    /** Diagnostics only. Nothing decides anything from this. */
    var coalescedCount: Int = 0
        private set

    fun offer(input: IntercomInput): IntercomMailboxOutcome {
        val replaced = slots.put(kindFor(input), input) != null
        if (replaced) coalescedCount += 1
        return if (replaced) IntercomMailboxOutcome.COALESCED else IntercomMailboxOutcome.ACCEPTED
    }

    /** Removes and returns the next input to apply, in [IntercomCommandKind] order, or null if empty. */
    fun poll(): IntercomInput? {
        for (kind in IntercomCommandKind.entries) {
            slots.remove(kind)?.let { return it }
        }
        return null
    }

    fun isEmpty(): Boolean = slots.isEmpty()

    val size: Int get() = slots.size

    fun clear() {
        slots.clear()
    }

    companion object {
        /** The bound, stated as a number so a test can assert it rather than infer it. */
        const val CAPACITY = 6

        fun kindFor(input: IntercomInput): IntercomCommandKind =
            when (input) {
                is IntercomInput.PolicySelected -> IntercomCommandKind.POLICY
                is IntercomInput.CaptureOpen -> IntercomCommandKind.CAPTURE
                is IntercomInput.Interrupted -> IntercomCommandKind.INTERRUPTED
                is IntercomInput.UserMuted -> IntercomCommandKind.USER_MUTED
                is IntercomInput.PttHeld -> IntercomCommandKind.PTT_HELD
                // A level and a tick both answer "where is the VOX gate now", so they share a slot:
                // a tick that arrives after a level is superseded by it, and vice versa.
                is IntercomInput.SpeechLevel, is IntercomInput.VoxTick -> IntercomCommandKind.VOX_LEVEL
            }
    }
}
