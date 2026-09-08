#!/usr/bin/env python3
"""Generate protocol/vectors/drift/drift_vectors.json.

ARCHITECTURE §7.3 / ADR-004's four-tier drift ladder, plus the three rules layered on top of it
(hysteresis, the hard-seek budget, and suspension while a route is transitioning).

The tiers, transcribed from ARCHITECTURE §7.3's table and TEST_PLAN §2's boundary list:

    |drift_ms| <  25        dead band, do nothing
    25 <= |drift_ms| <= 120 rate nudge, x(1 -/+ 0.002)
    120 < |drift_ms| <= 2000 hard seek to the expected position
    |drift_ms| > 2000       declare sync failure

On top:
  - a nudge disengages only once |drift| drops below 15ms, never merely below the 25ms engage
    threshold — that gap is the whole anti-oscillation guarantee;
  - a nudge already at the requested rate re-emits nothing;
  - the third qualifying hard seek within 60s becomes a sync failure instead of a third seek;
  - while either peer reports `AUDIO_STATE.route_state: "transitioning"` nothing at all happens,
    and the hard-seek counter does not advance;
  - a pause releases any nudge in force, so rate 1.0 is never left behind.

An independent third transcription, not a port of either platform's `DriftController`. Edit this
generator, never the JSON.

Run:  python3 tools/generate_drift_vectors.py
"""

from __future__ import annotations

import json
from pathlib import Path

DEAD_BAND_MS = 25
NUDGE_MAX_MS = 120
FAIL_MS = 2000
CONVERGED_MS = 15
RATE_SLOWER = 0.998
RATE_NORMAL = 1.0
RATE_FASTER = 1.002
MAX_HARD_SEEKS = 3
HARD_SEEK_WINDOW_US = 60_000_000


def initial_state() -> dict:
    return {"nudging": False, "nudge_rate": RATE_NORMAL, "hard_seek_at_session_us": [], "failed": False}


def evaluate(state: dict, drift_ms: int, now_session_us: int, expected_position_ms: int,
             playing: bool, route_transitioning: bool) -> tuple[dict, dict]:
    """Returns (action, new_state)."""
    if state["failed"]:
        return {"kind": "NONE"}, state

    if not playing:
        if state["nudging"]:
            return ({"kind": "RESTORE_RATE"},
                    {**state, "nudging": False, "nudge_rate": RATE_NORMAL})
        return {"kind": "NONE"}, state

    if route_transitioning:
        return {"kind": "NONE"}, state

    magnitude = abs(drift_ms)

    if magnitude > FAIL_MS:
        return ({"kind": "DECLARE_SYNC_FAILURE"},
                {**state, "nudging": False, "nudge_rate": RATE_NORMAL, "failed": True})

    if magnitude > NUDGE_MAX_MS:
        recent = [t for t in state["hard_seek_at_session_us"] if now_session_us - t <= HARD_SEEK_WINDOW_US]
        with_this_one = recent + [now_session_us]
        if len(with_this_one) >= MAX_HARD_SEEKS:
            return ({"kind": "DECLARE_SYNC_FAILURE"},
                    {**state, "nudging": False, "nudge_rate": RATE_NORMAL,
                     "hard_seek_at_session_us": with_this_one, "failed": True})
        return ({"kind": "HARD_SEEK", "position_ms": expected_position_ms},
                {**state, "nudging": False, "nudge_rate": RATE_NORMAL,
                 "hard_seek_at_session_us": with_this_one})

    if magnitude >= DEAD_BAND_MS:
        target = RATE_SLOWER if drift_ms > 0 else RATE_FASTER
        if state["nudging"] and state["nudge_rate"] == target:
            return {"kind": "NONE"}, state
        return ({"kind": "NUDGE", "rate": target}, {**state, "nudging": True, "nudge_rate": target})

    if state["nudging"] and magnitude < CONVERGED_MS:
        return ({"kind": "RESTORE_RATE"}, {**state, "nudging": False, "nudge_rate": RATE_NORMAL})

    return {"kind": "NONE"}, state


def single_rows() -> list[dict]:
    """One evaluation from a fresh state — the tier boundaries TEST_PLAN §2 names, both signs."""
    boundaries = [0, 1, 14, 15, 24, 25, 26, 119, 120, 121, 1999, 2000, 2001, 60_000]
    rows = []
    for magnitude in boundaries:
        for sign in ([1] if magnitude == 0 else [1, -1]):
            drift = magnitude * sign
            state = initial_state()
            action, new_state = evaluate(state, drift, 1_000_000, 45_000, True, False)
            rows.append(
                {
                    "name": f"fresh-drift-{drift}ms",
                    "input": {
                        "state": state,
                        "drift_ms": drift,
                        "now_session_us": 1_000_000,
                        "expected_position_ms": 45_000,
                        "playing": True,
                        "route_transitioning": False,
                    },
                    "expected": {"action": action, "state": new_state},
                }
            )
    return rows


def sequence_rows() -> list[dict]:
    """Whole drift series — the only way hysteresis, the seek budget and suspension are visible."""
    us = 1_000_000

    def tick(drift, at_s, playing=True, transitioning=False, expected=45_000):
        return {
            "drift_ms": drift,
            "now_session_us": us + at_s * 1_000_000,
            "expected_position_ms": expected,
            "playing": playing,
            "route_transitioning": transitioning,
        }

    series = [
        (
            "hysteresis-no-oscillation-around-25ms",
            # Engages at 30, must NOT release at 20 (>= 15), releases only at 10.
            [tick(30, 0), tick(20, 5), tick(24, 10), tick(16, 15), tick(10, 20), tick(20, 25)],
        ),
        (
            "repeated-identical-nudge-emits-once",
            [tick(40, 0), tick(45, 5), tick(50, 10)],
        ),
        (
            "nudge-direction-flip-re-emits",
            [tick(40, 0), tick(-40, 5), tick(-45, 10)],
        ),
        (
            "three-hard-seeks-in-60s-declares-failure",
            [tick(300, 0), tick(300, 10), tick(300, 20), tick(300, 30)],
        ),
        (
            "seek-outside-the-60s-window-does-not-count",
            # Session instants here are 1s/11s/76s/81s (the series' base is 1_000_000us). At 76s
            # both earlier seeks are outside the 60s window and are evicted, so this is the first
            # seek in the window again and the fourth seek in the series still does not fail.
            [tick(300, 0), tick(300, 10), tick(300, 75), tick(300, 80)],
        ),
        (
            "route-transitioning-suspends-everything",
            [tick(300, 0, transitioning=True), tick(3000, 5, transitioning=True), tick(30, 10, transitioning=True)],
        ),
        (
            "route-transition-does-not-advance-the-seek-budget",
            [tick(300, 0), tick(300, 5, transitioning=True), tick(300, 10, transitioning=True), tick(300, 15), tick(300, 20)],
        ),
        (
            "suspension-does-not-undo-an-active-nudge",
            [tick(40, 0), tick(40, 5, transitioning=True), tick(10, 10)],
        ),
        (
            "pause-releases-an-active-nudge",
            [tick(40, 0), tick(40, 5, playing=False), tick(40, 10, playing=False)],
        ),
        (
            "catastrophic-drift-fails-immediately-and-stays-failed",
            [tick(2001, 0), tick(10, 5), tick(300, 10)],
        ),
        (
            "hard-seek-clears-an-active-nudge",
            [tick(40, 0), tick(300, 5), tick(10, 10)],
        ),
        (
            "dead-band-from-fresh-state-does-nothing",
            [tick(0, 0), tick(24, 5), tick(-24, 10)],
        ),
        (
            "not-playing-from-fresh-state-does-nothing",
            [tick(300, 0, playing=False), tick(3000, 5, playing=False)],
        ),
    ]

    rows = []
    for name, ticks in series:
        state = initial_state()
        steps = []
        for t in ticks:
            action, state = evaluate(
                state, t["drift_ms"], t["now_session_us"], t["expected_position_ms"],
                t["playing"], t["route_transitioning"],
            )
            steps.append({"input": t, "action": action, "state_after": state})
        rows.append({"name": name, "steps": steps})
    return rows


def main() -> None:
    singles = single_rows()
    sequences = sequence_rows()
    payload = {
        "_comment": (
            "ARCHITECTURE §7.3 / ADR-004's drift ladder: (state, drift, route_state) -> "
            "(action, state). Boundary rows cover TEST_PLAN §2's 24/25/119/120/121/1999/2000/2001 "
            "list in both signs; the sequences are where hysteresis, the 3-seeks-in-60s budget and "
            "route suspension become visible at all. Generated by "
            "tools/generate_drift_vectors.py — an independent third transcription of the ladder. "
            "Edit the generator, never this file."
        ),
        "_invariants": [
            "No action other than RESTORE_RATE or NUDGE ever changes the playback rate, and "
            "RESTORE_RATE always means exactly 1.0 — 0.998 and 1.002 appear only inside NUDGE.",
            "While route_transitioning the action is always NONE and hard_seek_at_session_us never "
            "grows — a route change must not consume the seek budget it did not cause.",
            "A nudge engages at |drift| >= 25 and releases only at |drift| < 15; there is no input "
            "that both engages and releases, which is what makes oscillation impossible.",
            "Once failed is true every subsequent action is NONE, whatever the drift.",
            "Every DECLARE_SYNC_FAILURE row leaves nudging false and nudge_rate exactly 1.0.",
            "HARD_SEEK's position_ms is always the expected position from the authoritative "
            "timeline, never a peer-reported one.",
        ],
        "constants": {
            "dead_band_ms": DEAD_BAND_MS,
            "nudge_max_ms": NUDGE_MAX_MS,
            "fail_ms": FAIL_MS,
            "converged_ms": CONVERGED_MS,
            "rate_slower": RATE_SLOWER,
            "rate_normal": RATE_NORMAL,
            "rate_faster": RATE_FASTER,
            "max_hard_seeks_in_window": MAX_HARD_SEEKS,
            "hard_seek_window_us": HARD_SEEK_WINDOW_US,
        },
        "single": singles,
        "sequences": sequences,
    }
    out = Path(__file__).resolve().parent.parent / "protocol" / "vectors" / "drift"
    out.mkdir(parents=True, exist_ok=True)
    target = out / "drift_vectors.json"
    target.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    steps = sum(len(r["steps"]) for r in sequences)
    print(f"wrote {target} ({len(singles)} single rows, {len(sequences)} sequences, {steps} steps)")


if __name__ == "__main__":
    main()
