#!/usr/bin/env python3
"""Generate protocol/vectors/phase5-gates/phase5_gates_vectors.json.

The three decision tables ADR-024 Amendment A1 (the Phase 5 closure audit) moved out of the two
coordinators, transcribed here **independently** from the amendment's prose rather than ported from
either platform's `Phase5Gates`:

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

Every table is emitted as a **full cross product** of its inputs, so neither platform can pass by
implementing a subset. Edit this generator, never the JSON.

Run:  python3 tools/generate_phase5_gates_vectors.py
"""

from __future__ import annotations

import json
from pathlib import Path

FRAME_KINDS = ["COMMAND", "LATEST_WINS"]


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
    payload = {
        "_comment": (
            "ADR-024 Amendment A1's three Phase 5 decision tables as full cross products: the bounded "
            "inbound handoff's admission (Finding C), an accepted command's admission against clock "
            "readiness (Finding D), and a retained one-press synchronised Play's readiness (Findings A "
            "and E). Generated by tools/generate_phase5_gates_vectors.py — an independent third "
            "transcription of the amendment's prose, not a port of either platform. Edit the "
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
        ],
        "frame_kinds": FRAME_KINDS,
        "ingress_admissions": ["ADMIT", "COALESCE", "OVERFLOW"],
        "command_admissions": ["APPLY", "DEFER", "OVERFLOW"],
        "pending_play_decisions": ["ISSUE", "WAIT_FOR_QUEUE", "WAIT_FOR_CONTENT", "CANCEL"],
        "default_inbound_capacity": 256,
        "default_deferred_command_capacity": 16,
        "deferred_retry_interval_us": 100_000,
        "ingress": ingress_cases,
        "pending_command": command_cases,
        "pending_play": play_cases,
    }
    out = Path(__file__).resolve().parent.parent / "protocol" / "vectors" / "phase5-gates"
    out.mkdir(parents=True, exist_ok=True)
    target = out / "phase5_gates_vectors.json"
    target.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(
        f"wrote {target} "
        f"({len(ingress_cases)} ingress + {len(command_cases)} pending-command + {len(play_cases)} pending-play rows)"
    )


if __name__ == "__main__":
    main()
