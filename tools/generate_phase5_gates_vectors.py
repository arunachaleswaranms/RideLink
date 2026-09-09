#!/usr/bin/env python3
"""Generate protocol/vectors/phase5-gates/phase5_gates_vectors.json.

The decision tables ADR-024 Amendments A1 and A2 (the two Phase 5 closure audits) moved out of the
two coordinators, transcribed here **independently** from the amendments' prose rather than ported
from either platform's `Phase5Gates`:

  1. `Phase5Ingress` — the bounded post-TCP inbound handoff (Finding C). A reliable ordered TCP
     frame may never disappear silently from a local queue. There is room -> ADMIT. Full, and the
     frame is one whose newest instance subsumes its older ones (`POSITION_REPORT`,
     `PLAYBACK_STATE`, `QUEUE_SNAPSHOT`) with an older sibling queued -> COALESCE. Otherwise ->
     OVERFLOW, which is an explicit, counted, halt-and-reconcile failure and never a drop.

  2. `PendingCommandGate` — what a receiver does with an accepted authoritative command when the
     session clock may not be trustworthy (Finding D). Buffer full -> OVERFLOW. Clock untrusted, or
     anything already queued ahead of it -> DEFER (authoritative order must survive the wait).
     Otherwise -> APPLY. "Accepted for ordering" is not "applied".

  3. `PendingPlayGate` — whether a retained one-press synchronised Play may become a command yet
     (Findings A and E). A superseded request, a dead session or having left synchronised mode ->
     CANCEL. Queue item not yet authoritative -> WAIT_FOR_QUEUE. Not playable here ->
     WAIT_FOR_CONTENT. Not playable on the peer, *when that is this device's question at all* ->
     WAIT_FOR_CONTENT. Otherwise -> ISSUE. The peer half is the leader's question and never a
     follower's, because PROTOCOL §5 rule 4 makes requesting the transfer the leader's job and the
     leader cannot do it without receiving the intent.

  4. `OutboundCommitGate` — whether an outbound Phase 5 frame's local effect may be committed
     (A2 Findings A, B and C). Admission to the bounded outbound path is not delivery, and a
     `send` that returned false is not a send. The frame reached the transport -> COMMIT.
     Otherwise, an AUTHORITATIVE frame -> ABORT_FAIL_CLOSED, because a leader that committed
     locally what the follower never received is exactly the divergence A2 exists to close; an
     INTENT or ADVISORY frame -> ABORT_QUIET, because neither ever owned authority to roll back.

  5. `AuthoritativeHoldGate` — whether an authoritative *state* frame may be applied now or must
     wait behind authoritative work already held (A2 Finding D). Nothing held -> PROCESS_NOW.
     The hold buffer is full -> OVERFLOW, the same explicit halt as the other two overflow
     answers. Otherwise -> HOLD: nothing may overtake a held authoritative command, because a
     `QUEUE_SNAPSHOT` applied ahead of a held `NEXT` changes what that `NEXT` means.

Every table is emitted as a **full cross product** of its inputs, so neither platform can pass by
implementing a subset. Edit this generator, never the JSON.

Run:  python3 tools/generate_phase5_gates_vectors.py
"""

from __future__ import annotations

import json
from pathlib import Path

FRAME_KINDS = ["COMMAND", "LATEST_WINS"]
OUTBOUND_AUTHORITIES = ["AUTHORITATIVE", "INTENT", "ADVISORY"]
OUTBOUND_OUTCOMES = ["SENT", "ADMISSION_REFUSED", "STALE_SESSION", "TRANSPORT_FAILED"]


def ingress(kind: str, queued_total: int, capacity: int, has_queued_same_kind: bool) -> str:
    if capacity <= 0:
        return "OVERFLOW"
    if queued_total < capacity:
        return "ADMIT"
    if kind == "LATEST_WINS" and has_queued_same_kind:
        return "COALESCE"
    return "OVERFLOW"


def pending_command(clock_ready: bool, deferred_count: int, capacity: int) -> str:
    if deferred_count >= capacity:
        return "OVERFLOW"
    if not clock_ready or deferred_count > 0:
        return "DEFER"
    return "APPLY"


def pending_play(
    operation_current: bool,
    session_current: bool,
    sync_enabled: bool,
    queue_settled: bool,
    local_content_ready: bool,
    peer_content_required: bool,
    peer_has_content: bool,
) -> str:
    if not operation_current or not session_current or not sync_enabled:
        return "CANCEL"
    if not queue_settled:
        return "WAIT_FOR_QUEUE"
    if not local_content_ready:
        return "WAIT_FOR_CONTENT"
    if peer_content_required and not peer_has_content:
        return "WAIT_FOR_CONTENT"
    return "ISSUE"


def outbound_commit(authority: str, outcome: str) -> str:
    if outcome == "SENT":
        return "COMMIT"
    if authority == "AUTHORITATIVE":
        return "ABORT_FAIL_CLOSED"
    return "ABORT_QUIET"


def authoritative_hold(held_count: int, capacity: int) -> str:
    if held_count <= 0:
        return "PROCESS_NOW"
    if held_count >= capacity:
        return "OVERFLOW"
    return "HOLD"


def outbound_commit_rows() -> list[dict]:
    """The complete 3 x 4 cross product: no authority and no outcome may be a don't-care."""
    rows = []
    for authority in OUTBOUND_AUTHORITIES:
        for outcome in OUTBOUND_OUTCOMES:
            rows.append(
                {
                    "name": f"outbound-commit-{authority.lower()}-{outcome.lower()}",
                    "input": {"authority": authority, "outcome": outcome},
                    "expected": {"commit": outbound_commit(authority, outcome)},
                }
            )
    return rows


def authoritative_hold_rows() -> list[dict]:
    rows = []
    for capacity in [0, 1, 2, 16]:
        for held_count in [0, 1, 2, 15, 16, 17]:
            rows.append(
                {
                    "name": f"authoritative-hold-cap{capacity}-held{held_count}",
                    "input": {"held_count": held_count, "capacity": capacity},
                    "expected": {"admission": authoritative_hold(held_count, capacity)},
                }
            )
    return rows


def ingress_rows() -> list[dict]:
    rows = []
    for kind in FRAME_KINDS:
        for capacity in [0, 1, 2, 256]:
            for queued_total in [0, 1, 2, 256]:
                for has_same in [False, True]:
                    rows.append(
                        {
                            "name": f"ingress-{kind.lower()}-cap{capacity}-queued{queued_total}-same{int(has_same)}",
                            "input": {
                                "kind": kind,
                                "queued_total": queued_total,
                                "capacity": capacity,
                                "has_queued_same_kind": has_same,
                            },
                            "expected": {"admission": ingress(kind, queued_total, capacity, has_same)},
                        }
                    )
    return rows


def pending_command_rows() -> list[dict]:
    rows = []
    for clock_ready in [False, True]:
        for capacity in [1, 2, 16]:
            for deferred_count in [0, 1, 2, 15, 16, 17]:
                rows.append(
                    {
                        "name": f"pending-command-ready{int(clock_ready)}-cap{capacity}-deferred{deferred_count}",
                        "input": {
                            "clock_ready": clock_ready,
                            "deferred_count": deferred_count,
                            "capacity": capacity,
                        },
                        "expected": {"admission": pending_command(clock_ready, deferred_count, capacity)},
                    }
                )
    return rows


def pending_play_rows() -> list[dict]:
    """The complete 2^7 cross product: no precondition may be implemented as a don't-care."""
    rows = []
    for operation_current in [False, True]:
        for session_current in [False, True]:
            for sync_enabled in [False, True]:
                for queue_settled in [False, True]:
                    for local_content_ready in [False, True]:
                        for peer_content_required in [False, True]:
                            for peer_has_content in [False, True]:
                                inputs = (
                                    operation_current,
                                    session_current,
                                    sync_enabled,
                                    queue_settled,
                                    local_content_ready,
                                    peer_content_required,
                                    peer_has_content,
                                )
                                bits = "".join(str(int(flag)) for flag in inputs)
                                rows.append(
                                    {
                                        "name": f"pending-play-{bits}",
                                        "input": {
                                            "operation_current": operation_current,
                                            "session_current": session_current,
                                            "sync_enabled": sync_enabled,
                                            "queue_settled": queue_settled,
                                            "local_content_ready": local_content_ready,
                                            "peer_content_required": peer_content_required,
                                            "peer_has_content": peer_has_content,
                                        },
                                        "expected": {"decision": pending_play(*inputs)},
                                    }
                                )
    return rows


def main() -> None:
    ingress_cases = ingress_rows()
    command_cases = pending_command_rows()
    play_cases = pending_play_rows()
    commit_cases = outbound_commit_rows()
    hold_cases = authoritative_hold_rows()
    payload = {
        "_comment": (
            "ADR-024's Phase 5 decision tables as full cross products. Amendment A1: the bounded "
            "inbound handoff's admission (Finding C), an accepted command's admission against clock "
            "readiness (Finding D), and a retained one-press synchronised Play's readiness (Findings A "
            "and E). Amendment A2: whether an outbound frame's local effect may be committed "
            "(Findings A/B/C) and whether an authoritative state frame may overtake held authoritative "
            "work (Finding D). Generated by tools/generate_phase5_gates_vectors.py — an independent "
            "third transcription of the amendments' prose, not a port of either platform. Edit the "
            "generator, never this file."
        ),
        "_invariants": [
            "Phase5Ingress never answers ADMIT when queued_total >= capacity, so a full queue can "
            "never grow: the bound is real.",
            "Phase5Ingress never answers COALESCE for a COMMAND frame. An authoritative command is "
            "never superseded by a later one, which is the whole of Finding C.",
            "PendingCommandGate never answers APPLY while deferred_count > 0, so a command that "
            "waited for the clock is never overtaken by one that did not.",
            "PendingCommandGate never answers APPLY when clock_ready is false: no command is ever "
            "scheduled against an untrusted clock, and offset 0 is never substituted for one.",
            "PendingPlayGate answers CANCEL for every input where operation_current, "
            "session_current or sync_enabled is false, regardless of the other three — a superseded "
            "or session-stale Play can never resurrect.",
            "PendingPlayGate answers ISSUE only when operation_current, session_current, "
            "sync_enabled, queue_settled and local_content_ready all hold, and the peer half either "
            "is not this device's question or is satisfied.",
            "PendingPlayGate's answer is independent of peer_has_content whenever "
            "peer_content_required is false — the peer half is the leader's question, never a "
            "follower's (PROTOCOL §5 rule 4).",
            "OutboundCommitGate answers COMMIT for exactly the SENT outcome and for no other, "
            "whatever the authority: admission to the outbound path is not delivery, and a send "
            "that returned false is not a send.",
            "OutboundCommitGate answers ABORT_FAIL_CLOSED for every non-SENT outcome of an "
            "AUTHORITATIVE frame, so a leader can never commit locally what the follower never "
            "received.",
            "OutboundCommitGate never answers ABORT_FAIL_CLOSED for an INTENT or ADVISORY frame: "
            "neither ever owned authority, so there is nothing to fail closed on.",
            "AuthoritativeHoldGate never answers PROCESS_NOW while held_count > 0, so nothing can "
            "overtake a held authoritative command and change its meaning.",
            "AuthoritativeHoldGate never answers HOLD when held_count >= capacity, so the hold "
            "buffer's bound is real and an overflow is explicit rather than an eviction.",
        ],
        "frame_kinds": FRAME_KINDS,
        "ingress_admissions": ["ADMIT", "COALESCE", "OVERFLOW"],
        "command_admissions": ["APPLY", "DEFER", "OVERFLOW"],
        "pending_play_decisions": ["ISSUE", "WAIT_FOR_QUEUE", "WAIT_FOR_CONTENT", "CANCEL"],
        "outbound_authorities": OUTBOUND_AUTHORITIES,
        "outbound_outcomes": OUTBOUND_OUTCOMES,
        "outbound_commits": ["COMMIT", "ABORT_FAIL_CLOSED", "ABORT_QUIET"],
        "hold_admissions": ["PROCESS_NOW", "HOLD", "OVERFLOW"],
        "default_inbound_capacity": 256,
        "default_deferred_command_capacity": 16,
        "default_outbound_capacity": 256,
        "deferred_retry_interval_us": 100_000,
        "ingress": ingress_cases,
        "pending_command": command_cases,
        "pending_play": play_cases,
        "outbound_commit": commit_cases,
        "authoritative_hold": hold_cases,
    }
    out = Path(__file__).resolve().parent.parent / "protocol" / "vectors" / "phase5-gates"
    out.mkdir(parents=True, exist_ok=True)
    target = out / "phase5_gates_vectors.json"
    target.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(
        f"wrote {target} "
        f"({len(ingress_cases)} ingress + {len(command_cases)} pending-command + {len(play_cases)} pending-play "
        f"+ {len(commit_cases)} outbound-commit + {len(hold_cases)} authoritative-hold rows)"
    )


if __name__ == "__main__":
    main()
