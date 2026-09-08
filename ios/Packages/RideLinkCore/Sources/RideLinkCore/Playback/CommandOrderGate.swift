import Foundation

/// What a receiver does with an inbound playback command, decided by `command_seq` **alone**
/// (PROTOCOL §2.1: "Playback commands are ordered by `command_seq` assigned by the leader (§5),
/// never by `seq` or by timestamp comparison").
///
/// Raw values match the Kotlin enum constant names exactly, since `protocol/vectors/ordering/`
/// names them that way and both platforms read the same file.
public enum CommandOrderDecision: String, Sendable, Equatable {
    /// A newer authoritative command: apply it.
    case accept = "ACCEPT"
    /// Exactly the `command_seq` already applied — a replayed frame. Drop, do not re-apply.
    case duplicate = "DUPLICATE"
    /// Older than what has already been applied. Drop; never rewind playback (PROTOCOL §5 rule 1).
    case stale = "STALE"
    /// `command_seq == 0` — a follower's intent, not an authoritative command. Only the leader may
    /// act on it (by assigning a real sequence and broadcasting); a follower drops it, because a
    /// follower has nothing to serialise.
    case intent = "INTENT"
    /// The sender is not the peer allowed to have assigned this number: an authoritative
    /// (`command_seq >= 1`) command arriving *at the leader*, or an intent (`command_seq == 0`)
    /// arriving *at a follower*. Either means the peer believes it holds a role ADR-010 says it does
    /// not. Dropped and counted, never applied — this is what makes "a follower cannot fabricate an
    /// authoritative `command_seq`" a checked property rather than an assumption.
    case roleViolation = "ROLE_VIOLATION"
}

/// PROTOCOL §5's ordering rules as a pure decision, mirrored on both platforms and pinned by
/// `protocol/vectors/ordering/`.
///
/// It answers only "may this frame be applied, given what has already been applied and who this
/// device is". It deliberately knows nothing about `effective_at_session_us` — whether an accepted
/// command is scheduled or applied immediately is `ScheduledCommand`'s question, and conflating the
/// two is exactly how a late-but-newest command ends up wrongly discarded (this phase's brief §24).
public enum CommandOrderGate {
    /// - Parameter lastAppliedSeq: the highest `command_seq` this device has applied in the current
    ///   session, or `nil` before the first one. Reset on every session boundary — a `command_seq`
    ///   from a previous authenticated session is meaningless, not merely old (ADR-023 §3's lesson).
    public static func decide(role: PlaybackRole, lastAppliedSeq: Int64?, incomingSeq: Int64) -> CommandOrderDecision {
        if incomingSeq == PlaybackBounds.unassignedCommandSeq {
            return role == .leader ? .intent : .roleViolation
        }
        if role == .leader { return .roleViolation }
        guard let lastAppliedSeq else { return .accept }
        if incomingSeq == lastAppliedSeq { return .duplicate }
        if incomingSeq < lastAppliedSeq { return .stale }
        return .accept
    }
}

/// Whether an accepted command's `effective_at_session_us` is still ahead of us, and by how much.
///
/// PROTOCOL §5 rule 2 is explicit that a deadline already in the past is **applied immediately and
/// counted**, never skipped and never scheduled backwards: "Never skip the command; never schedule
/// into the past."
public enum ScheduledCommandDecision: Sendable, Equatable {
    /// Wait until this instant on this device's own monotonic clock, then apply.
    case schedule(atLocalMonoUs: Int64)
    /// The deadline has passed. Apply now and record the lateness as a late-command diagnostic.
    case applyImmediately(latenessUs: Int64)
}

/// Maps an authoritative `effective_at_session_us` onto this device's local monotonic timeline.
public enum ScheduledCommand {
    public static func decide(effectiveAtSessionUs: Int64, nowLocalMonoUs: Int64, offsetToLeaderUs: Int64) -> ScheduledCommandDecision {
        let deadlineLocalMonoUs = effectiveAtSessionUs - offsetToLeaderUs
        if deadlineLocalMonoUs > nowLocalMonoUs {
            return .schedule(atLocalMonoUs: deadlineLocalMonoUs)
        }
        return .applyImmediately(latenessUs: nowLocalMonoUs - deadlineLocalMonoUs)
    }
}
