#!/usr/bin/env python3
"""Generate protocol/vectors/session-clock/session_clock_vectors.json.

ARCHITECTURE §7.1's session-clock mapping and §7.2's scheduling lead (ADR-004, ADR-024 §2):

    session_us = local_mono_us + offset_to_leader_us
    local_mono_us = session_us - offset_to_leader_us
    rtt_p95_us    = nearest-rank p95, index = ceil(0.95 * n) - 1 over the ascending sort
    LEAD          = clamp(max(120_000, 4 * rtt_p95_us), 120_000, 2_000_000)

Plus PROTOCOL §5 rule 2's schedule-or-apply-immediately decision, which is where a "never schedule
into the past" bug would otherwise hide.

An independent third transcription of the arithmetic above, not a port of either platform's
`SessionClock`. Edit this generator, never the JSON.

Run:  python3 tools/generate_session_clock_vectors.py
"""

from __future__ import annotations

import json
import math
from pathlib import Path

MIN_LEAD_US = 120_000
MAX_LEAD_US = 2_000_000
LEAD_MULTIPLIER = 4


def rtt_p95_us(rtts: list[int]) -> int | None:
    if not rtts:
        return None
    ordered = sorted(rtts)
    rank = math.ceil(0.95 * len(ordered))
    index = min(max(rank - 1, 0), len(ordered) - 1)
    return ordered[index]


def lead_us(rtt_p95: int | None) -> int:
    rtt = max(rtt_p95 or 0, 0)
    scaled = MAX_LEAD_US if rtt > MAX_LEAD_US else rtt * LEAD_MULTIPLIER
    return min(max(scaled, MIN_LEAD_US), MAX_LEAD_US)


def mapping_rows() -> list[dict]:
    cases = [
        ("leader-offset-is-identity", 1_000_000, 0),
        ("follower-positive-offset", 1_000_000, 250_000),
        ("follower-negative-offset", 1_000_000, -46_912_337_666),
        ("zero-local-mono", 0, 7),
        ("large-monotonic-uptime", 9_007_199_254_000_000, -1_000),
    ]
    rows = []
    for name, local_mono_us, offset in cases:
        session = local_mono_us + offset
        rows.append(
            {
                "name": name,
                "input": {"local_mono_us": local_mono_us, "offset_to_leader_us": offset},
                "expected": {
                    "session_us": session,
                    # Round-tripping is the property that actually matters: whatever the offset, a
                    # deadline expressed in session time must land back on the same local instant.
                    "round_trip_local_mono_us": session - offset,
                },
            }
        )
    return rows


def p95_rows() -> list[dict]:
    cases = [
        ("empty-window-has-no-measurement", []),
        ("single-sample", [8_000]),
        ("two-samples-takes-the-larger", [8_000, 20_000]),
        ("twenty-samples-nineteenth-of-twenty", [i * 1_000 for i in range(1, 21)]),
        ("hundred-samples", [i * 100 for i in range(1, 101)]),
        ("unsorted-input", [50_000, 1_000, 9_000, 3_000, 2_000]),
        ("all-identical", [4_242] * 11),
        ("one-huge-outlier-dominates-p95", [1_000] * 19 + [900_000]),
    ]
    rows = []
    for name, rtts in cases:
        p95 = rtt_p95_us(rtts)
        rows.append(
            {
                "name": name,
                "input": {"rtts_us": rtts},
                "expected": {"rtt_p95_us": p95, "lead_us": lead_us(p95)},
            }
        )
    return rows


def lead_rows() -> list[dict]:
    # The floor binds until 4 x rtt_p95 crosses it, i.e. at rtt_p95 = 30_000us exactly.
    cases = [
        ("no-measurement-yields-the-floor", None),
        ("zero-rtt-yields-the-floor", 0),
        ("29999us-still-the-floor", 29_999),
        ("30000us-is-exactly-the-floor", 30_000),
        ("30001us-exceeds-the-floor", 30_001),
        ("typical-lan-rtt", 8_400),
        ("congested-wifi-rtt", 120_000),
        ("pathological-rtt-clamped", 5_000_000),
        ("negative-rtt-treated-as-zero", -1),
    ]
    return [
        {"name": name, "input": {"rtt_p95_us": rtt}, "expected": {"lead_us": lead_us(rtt)}}
        for name, rtt in cases
    ]


def schedule_rows() -> list[dict]:
    """PROTOCOL §5 rule 2: schedule a future deadline, apply a past one immediately and count it."""
    cases = [
        ("future-deadline-on-the-leader", 1_000_000, 500_000, 0),
        ("future-deadline-on-a-follower-positive-offset", 1_000_000, 500_000, 250_000),
        ("future-deadline-on-a-follower-negative-offset", 1_000_000, 500_000, -250_000),
        ("deadline-exactly-now-applies-immediately", 500_000, 500_000, 0),
        ("deadline-one-microsecond-past", 499_999, 500_000, 0),
        ("deadline-long-past-records-lateness", 100_000, 5_000_000, 0),
        ("past-deadline-through-a-negative-offset", 1_000_000, 900_000, -200_000),
    ]
    rows = []
    for name, effective_at_session_us, now_local_mono_us, offset in cases:
        deadline_local = effective_at_session_us - offset
        if deadline_local > now_local_mono_us:
            expected = {"decision": "SCHEDULE", "at_local_mono_us": deadline_local, "lateness_us": None}
        else:
            expected = {
                "decision": "APPLY_IMMEDIATELY",
                "at_local_mono_us": None,
                "lateness_us": now_local_mono_us - deadline_local,
            }
        rows.append(
            {
                "name": name,
                "input": {
                    "effective_at_session_us": effective_at_session_us,
                    "now_local_mono_us": now_local_mono_us,
                    "offset_to_leader_us": offset,
                },
                "expected": expected,
            }
        )
    return rows


def main() -> None:
    payload = {
        "_comment": (
            "ARCHITECTURE §7.1's session-clock mapping, §7.2's LEAD = max(120ms, 4 x rtt_p95) and "
            "PROTOCOL §5 rule 2's schedule-or-apply-immediately decision. Every value is monotonic "
            "or session microseconds; nothing here has ever seen a wall clock. Generated by "
            "tools/generate_session_clock_vectors.py — an independent third transcription of the "
            "arithmetic. Edit the generator, never this file."
        ),
        "_invariants": [
            "session_us = local_mono_us + offset_to_leader_us, and the inverse round-trips exactly "
            "for every offset including large negative ones.",
            "rtt_p95_us is nearest-rank with index ceil(0.95*n)-1 over the ascending sort, so both "
            "platforms pick the same sample rather than interpolating differently.",
            "LEAD never falls below 120000us and never exceeds 2000000us.",
            "A null rtt_p95_us yields the 120000us floor — never a fabricated zero measurement.",
            "A deadline at or before now is APPLY_IMMEDIATELY with a non-negative lateness; it is "
            "never SCHEDULE with a past instant (PROTOCOL §5 rule 2: never schedule into the past).",
        ],
        "constants": {
            "min_lead_us": MIN_LEAD_US,
            "max_lead_us": MAX_LEAD_US,
            "lead_rtt_multiplier": LEAD_MULTIPLIER,
        },
        "mapping": mapping_rows(),
        "rtt_p95": p95_rows(),
        "lead": lead_rows(),
        "schedule": schedule_rows(),
    }
    out = Path(__file__).resolve().parent.parent / "protocol" / "vectors" / "session-clock"
    out.mkdir(parents=True, exist_ok=True)
    target = out / "session_clock_vectors.json"
    target.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    total = len(payload["mapping"]) + len(payload["rtt_p95"]) + len(payload["lead"]) + len(payload["schedule"])
    print(f"wrote {target} ({total} rows)")


if __name__ == "__main__":
    main()
