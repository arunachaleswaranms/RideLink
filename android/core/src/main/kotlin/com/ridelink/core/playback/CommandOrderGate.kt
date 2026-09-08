package com.ridelink.core.playback

/**
 * What a receiver does with an inbound playback command, decided by `command_seq` **alone**
 * (PROTOCOL §2.1: "Playback commands are ordered by `command_seq` assigned by the leader (§5),
 * never by `seq` or by timestamp comparison").
 */
enum class CommandOrderDecision {
    /** A newer authoritative command: apply it. */
    ACCEPT,

    /** Exactly the `command_seq` already applied — a replayed frame. Drop, do not re-apply. */
    DUPLICATE,

    /** Older than what has already been applied. Drop; never rewind playback (PROTOCOL §5 rule 1). */
    STALE,

    /**
     * `command_seq == 0` — a follower's intent, not an authoritative command. Only the leader may
     * act on it (by assigning a real sequence and broadcasting); a follower drops it, because a
     * follower has nothing to serialise.
     */
    INTENT,

    /**
     * The sender is not the peer allowed to have assigned this number: an authoritative
     * (`command_seq >= 1`) command arriving *at the leader*, or an intent (`command_seq == 0`)
     * arriving *at a follower*. Either means the peer believes it holds a role ADR-010 says it does
     * not. Dropped and counted, never applied — this is what makes "a follower cannot fabricate an
     * authoritative `command_seq`" a checked property rather than an assumption.
     */
    ROLE_VIOLATION,
}

/**
 * PROTOCOL §5's ordering rules as a pure decision, mirrored on both platforms and pinned by
 * `protocol/vectors/ordering/`.
 *
 * It answers only "may this frame be applied, given what has already been applied and who this
 * device is". It deliberately knows nothing about `effective_at_session_us` — whether an accepted
 * command is scheduled or applied immediately is [ScheduledCommand]'s question, and conflating the
 * two is exactly how a late-but-newest command ends up wrongly discarded (this phase's brief §24).
 */
object CommandOrderGate {
    /**
     * @param lastAppliedSeq the highest `command_seq` this device has applied in the current
     *   session, or `null` before the first one. Reset on every session boundary — a `command_seq`
     *   from a previous authenticated session is meaningless, not merely old (ADR-023 §3's lesson).
     */
    fun decide(
        role: PlaybackRole,
        lastAppliedSeq: Long?,
        incomingSeq: Long,
    ): CommandOrderDecision =
        when {
            incomingSeq == PlaybackBounds.UNASSIGNED_COMMAND_SEQ ->
                if (role == PlaybackRole.LEADER) CommandOrderDecision.INTENT else CommandOrderDecision.ROLE_VIOLATION
            role == PlaybackRole.LEADER -> CommandOrderDecision.ROLE_VIOLATION
            lastAppliedSeq == null -> CommandOrderDecision.ACCEPT
            incomingSeq == lastAppliedSeq -> CommandOrderDecision.DUPLICATE
            incomingSeq < lastAppliedSeq -> CommandOrderDecision.STALE
            else -> CommandOrderDecision.ACCEPT
        }
}

/**
 * Whether an accepted command's `effective_at_session_us` is still ahead of us, and by how much.
 *
 * PROTOCOL §5 rule 2 is explicit that a deadline already in the past is **applied immediately and
 * counted**, never skipped and never scheduled backwards: "Never skip the command; never schedule
 * into the past."
 */
sealed class ScheduledCommandDecision {
    /** Wait until [atLocalMonoUs] on this device's own monotonic clock, then apply. */
    data class Schedule(
        val atLocalMonoUs: Long,
    ) : ScheduledCommandDecision()

    /** The deadline has passed. Apply now and record [latenessUs] as a late-command diagnostic. */
    data class ApplyImmediately(
        val latenessUs: Long,
    ) : ScheduledCommandDecision()
}

/** Maps an authoritative `effective_at_session_us` onto this device's local monotonic timeline. */
object ScheduledCommand {
    fun decide(
        effectiveAtSessionUs: Long,
        nowLocalMonoUs: Long,
        offsetToLeaderUs: Long,
    ): ScheduledCommandDecision {
        val deadlineLocalMonoUs = effectiveAtSessionUs - offsetToLeaderUs
        return if (deadlineLocalMonoUs > nowLocalMonoUs) {
            ScheduledCommandDecision.Schedule(deadlineLocalMonoUs)
        } else {
            ScheduledCommandDecision.ApplyImmediately(nowLocalMonoUs - deadlineLocalMonoUs)
        }
    }
}
