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

ADR-004 Amendment A1 (STATUS problem 131) changes what the tiers are applied to and where a hard seek
lands, and nothing else:
  - the magnitude is the inter-phone error, drift_ms minus the reference's drift (the leader's
    latest POSITION_REPORT against the same timeline); a leader has no reference;
  - a reference is usable only if it is playing, sampled >= 1.5s after the anchor and <= 12s ago,
    and the local sample is >= 1.5s after the anchor and after this device's last hard seek;
    without one, nothing new is decided and a nudge in force is released;
  - a hard seek targets expected + reference drift + restart_compensation_ms, floored at 0;
  - the first usable sample after a seek made with compensation c teaches the restart latency
    c - error, clamped to 0..1000ms, unless the anchor moved in between;
  - a route transition and a pause forget a pending seek; a route transition also forgets the
    learned latency; a new epoch keeps it and resets everything else.

PR #32's review (22 October 2026) added three rules on top:
  - the route is followed first, before the failed and not-playing early returns, so a route change
    forgets the learned latency while paused and after a sync failure too;
  - besides either peer's `transitioning`, a change in this device's own count of route transitions
    begun (`route_change_count`) forgets it, which catches a transition that started and settled
    between two ticks; the state remembers the count the latency was learned under;
  - a reference stamped after this device's own sample (`at_session_us > now_session_us`) is not
    usable.

PR #32 review round 2 (23 October 2026): a reference records the anchor of the timeline its drift
was measured against (`anchor_session_us`, `anchor_position_ms`), and is usable only while that is
still the timeline's anchor. A same-track re-anchor can move the anchor to an earlier instant, where
every freshness rule above would otherwise pass a drift measured against the old one.

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
REFERENCE_SETTLE_US = 1_500_000
MAX_REFERENCE_AGE_US = 12_000_000
MAX_RESTART_COMPENSATION_MS = 1000

ANCHOR_US = 1_000_000
BASE_US = 10_000_000


def initial_state() -> dict:
    return {"nudging": False, "nudge_rate": RATE_NORMAL, "hard_seek_at_session_us": [], "failed": False,
            "restart_compensation_ms": 0, "pending_seek": None, "route_change_count": None}


def new_epoch(state: dict) -> dict:
    return {**initial_state(), "restart_compensation_ms": state["restart_compensation_ms"],
            "route_change_count": state["route_change_count"]}


def follow_route(state: dict, t: dict) -> dict:
    known = state["route_change_count"]
    if t["route_transitioning"] or (known is not None and known != t["route_change_count"]):
        return {**state, "restart_compensation_ms": 0, "pending_seek": None,
                "route_change_count": t["route_change_count"]}
    return {**state, "route_change_count": t["route_change_count"]}


def settled_reference(now_session_us: int, drift_ms: int = 0, anchor_us: int = ANCHOR_US,
                      anchor_pos: int = 0) -> dict:
    return {"drift_ms": drift_ms, "at_session_us": now_session_us - 1_000_000, "playing": True,
            "anchor_session_us": anchor_us, "anchor_position_ms": anchor_pos}


def release_if_nudging(state: dict) -> tuple[dict, dict]:
    if state["nudging"]:
        return {"kind": "RESTORE_RATE"}, {**state, "nudging": False, "nudge_rate": RATE_NORMAL}
    return {"kind": "NONE"}, state


def reference_usable(state: dict, t: dict) -> bool:
    ref = t["reference"]
    if ref is None or not ref["playing"]:
        return False
    if ref["anchor_session_us"] != t["anchor_session_us"] or ref["anchor_position_ms"] != t["anchor_position_ms"]:
        return False
    now = t["now_session_us"]
    earliest = t["anchor_session_us"] + REFERENCE_SETTLE_US
    if ref["at_session_us"] < earliest or now < earliest:
        return False
    if ref["at_session_us"] > now:
        return False
    if now - ref["at_session_us"] > MAX_REFERENCE_AGE_US:
        return False
    seeks = state["hard_seek_at_session_us"]
    return not seeks or now - seeks[-1] >= REFERENCE_SETTLE_US


def evaluate(state: dict, t: dict) -> tuple[dict, dict, int | None]:
    """Returns (action, new_state, error_ms)."""
    state = follow_route(state, t)
    if state["failed"]:
        return {"kind": "NONE"}, state, None

    if not t["playing"]:
        action, after = release_if_nudging({**state, "pending_seek": None})
        return action, after, None

    if t["route_transitioning"]:
        return {"kind": "NONE"}, state, None

    if not reference_usable(state, t):
        action, after = release_if_nudging(state)
        return action, after, None

    ref = t["reference"]
    error = t["drift_ms"] - ref["drift_ms"]

    pending = state["pending_seek"]
    if pending is not None:
        if pending["anchor_session_us"] == t["anchor_session_us"]:
            latency = min(max(pending["compensation_ms"] - error, 0), MAX_RESTART_COMPENSATION_MS)
            state = {**state, "restart_compensation_ms": latency, "pending_seek": None}
        else:
            state = {**state, "pending_seek": None}

    magnitude = abs(error)

    if magnitude > FAIL_MS:
        return ({"kind": "DECLARE_SYNC_FAILURE"},
                {**state, "nudging": False, "nudge_rate": RATE_NORMAL, "failed": True}, error)

    if magnitude > NUDGE_MAX_MS:
        now = t["now_session_us"]
        recent = [x for x in state["hard_seek_at_session_us"] if now - x <= HARD_SEEK_WINDOW_US]
        with_this_one = recent + [now]
        if len(with_this_one) >= MAX_HARD_SEEKS:
            return ({"kind": "DECLARE_SYNC_FAILURE"},
                    {**state, "nudging": False, "nudge_rate": RATE_NORMAL,
                     "hard_seek_at_session_us": with_this_one, "failed": True}, error)
        compensation = state["restart_compensation_ms"]
        target = max(t["expected_position_ms"] + ref["drift_ms"] + compensation, 0)
        return ({"kind": "HARD_SEEK", "position_ms": target},
                {**state, "nudging": False, "nudge_rate": RATE_NORMAL,
                 "hard_seek_at_session_us": with_this_one,
                 "pending_seek": {"compensation_ms": compensation,
                                  "anchor_session_us": t["anchor_session_us"]}}, error)

    if magnitude >= DEAD_BAND_MS:
        rate = RATE_SLOWER if error > 0 else RATE_FASTER
        if state["nudging"] and state["nudge_rate"] == rate:
            return {"kind": "NONE"}, state, error
        return {"kind": "NUDGE", "rate": rate}, {**state, "nudging": True, "nudge_rate": rate}, error

    if state["nudging"] and magnitude < CONVERGED_MS:
        return ({"kind": "RESTORE_RATE"}, {**state, "nudging": False, "nudge_rate": RATE_NORMAL}, error)

    return {"kind": "NONE"}, state, error


def single_rows() -> list[dict]:
    """One evaluation from a fresh state, against a settled zero-drift reference — the tier
    boundaries TEST_PLAN §2 names, both signs. A zero reference makes the error equal the drift, so
    every pre-amendment answer still stands."""
    boundaries = [0, 1, 14, 15, 24, 25, 26, 119, 120, 121, 1999, 2000, 2001, 60_000]
    rows = []

    def row(name, state, t):
        action, after, error = evaluate(state, t)
        return {"name": name, "input": {"state": state, **t},
                "expected": {"action": action, "state": after, "error_ms": error}}

    for magnitude in boundaries:
        for sign in ([1] if magnitude == 0 else [1, -1]):
            drift = magnitude * sign
            t = {"drift_ms": drift, "now_session_us": BASE_US, "expected_position_ms": 45_000,
                 "playing": True, "route_transitioning": False, "anchor_session_us": ANCHOR_US,
                 "reference": settled_reference(BASE_US), "route_change_count": 0, "anchor_position_ms": 0}
            rows.append(row(f"fresh-drift-{drift}ms", initial_state(), t))

    # ADR-004 Amendment A1: the target is where the reference output is, plus the learned restart
    # latency, and it is never negative.
    def at(drift, ref_drift, expected=45_000, state=None):
        t = {"drift_ms": drift, "now_session_us": BASE_US, "expected_position_ms": expected,
             "playing": True, "route_transitioning": False, "anchor_session_us": ANCHOR_US,
             "reference": settled_reference(BASE_US, ref_drift), "route_change_count": 0,
             "anchor_position_ms": 0}
        return state or initial_state(), t

    rows.append(row("a1-common-mode-lag-is-not-chased", *at(-375, -375)))
    rows.append(row("a1-common-mode-jitter-inside-the-dead-band", *at(-380, -372)))
    rows.append(row("a1-differential-lag-hard-seeks-to-the-reference", *at(-575, -375)))
    rows.append(row("a1-differential-lag-in-the-nudge-band", *at(-415, -375)))
    rows.append(row("a1-seek-target-adds-the-learned-latency",
                    *at(-575, -375, state={**initial_state(), "restart_compensation_ms": 550})))
    rows.append(row("a1-seek-target-is-floored-at-zero", *at(-575, -375, expected=100)))
    rows.append(row("a1-common-mode-catastrophe-is-not-a-failure", *at(-3000, -3000)))
    rows.append(row("a1-differential-catastrophe-fails", *at(-2600, -375)))
    return rows


def tick(drift, at_s, playing=True, transitioning=False, expected=45_000, ref_drift=0,
         reference="settled", anchor_us=ANCHOR_US, routes=0, anchor_pos=0):
    now = BASE_US + at_s * 1_000_000
    if reference == "settled":
        ref = settled_reference(now, ref_drift, anchor_us, anchor_pos)
    elif reference is None:
        ref = None
    else:
        # Measured against this tick's own timeline unless the row says otherwise.
        ref = {"anchor_session_us": anchor_us, "anchor_position_ms": anchor_pos, **reference}
    return {
        "drift_ms": drift,
        "now_session_us": now,
        "expected_position_ms": expected,
        "playing": playing,
        "route_transitioning": transitioning,
        "anchor_session_us": anchor_us,
        "reference": ref,
        "route_change_count": routes,
        "anchor_position_ms": anchor_pos,
    }


def sequence_rows() -> list[dict]:
    """Whole drift series — the only way hysteresis, the seek budget and suspension are visible."""
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
            # Session instants here are 10s/20s/85s/90s (the series' base is 10_000_000us). At 85s
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
        # --- ADR-004 Amendment A1 ----------------------------------------------------------------
        (
            "a1-leader-has-no-reference-and-never-corrects",
            [tick(400, 0, reference=None), tick(3000, 5, reference=None), tick(40, 10, reference=None)],
        ),
        (
            "a1-losing-the-reference-releases-an-active-nudge",
            [tick(40, 0), tick(40, 5, reference=None), tick(40, 10)],
        ),
        (
            "a1-a-paused-reference-is-not-usable",
            [tick(400, 0, reference={"drift_ms": 0, "at_session_us": BASE_US - 1_000_000, "playing": False})],
        ),
        (
            "a1-a-reference-sampled-before-it-settled-is-not-usable",
            # Sampled 1.0s after the anchor; usable only from 1.5s.
            [tick(400, 0, reference={"drift_ms": 0, "at_session_us": ANCHOR_US + 1_000_000, "playing": True}),
             tick(400, 0, reference={"drift_ms": 0, "at_session_us": ANCHOR_US + 1_500_000, "playing": True})],
        ),
        (
            "a1-a-stale-reference-is-not-usable",
            # 12.000001s old, then exactly 12s old.
            [tick(400, 15, reference={"drift_ms": 0, "at_session_us": BASE_US + 3_000_000 - 1, "playing": True}),
             tick(400, 15, reference={"drift_ms": 0, "at_session_us": BASE_US + 3_000_000, "playing": True})],
        ),
        (
            "a1-a-local-sample-before-it-settled-is-not-usable",
            # A new anchor 1.0s before the sample: this device's own output may not have begun.
            [tick(400, 0, anchor_us=BASE_US - 1_000_000,
                  reference={"drift_ms": 0, "at_session_us": BASE_US, "playing": True})],
        ),
        (
            "a1-common-mode-lag-is-never-chased",
            [tick(-375, 0, ref_drift=-375), tick(-392, 5, ref_drift=-371), tick(-650, 10, ref_drift=-640),
             tick(-360, 15, ref_drift=-380)],
        ),
        (
            "a1-differential-lag-converges-by-learning-the-restart-latency",
            # The follower's output is 200ms later than the leader's, and restarts 550ms after a seek.
            # The first seek lands on the reference and re-incurs 550ms; its residual teaches 550; the
            # second, compensated, converges; the third tick learns the same 550 and does nothing.
            [tick(-575, 0, ref_drift=-375, expected=45_000),
             tick(-925, 5, ref_drift=-375, expected=50_000),
             tick(-375, 10, ref_drift=-375, expected=55_000),
             tick(-370, 15, ref_drift=-375, expected=60_000)],
        ),
        (
            "a1-a-sample-too-soon-after-this-devices-own-seek-is-held",
            [tick(-575, 0, ref_drift=-375), tick(-925, 1, ref_drift=-375), tick(-925, 5, ref_drift=-375)],
        ),
        (
            "a1-learning-is-clamped-at-zero",
            # The seek overshot: the device is now ahead, so there is no latency to learn.
            [tick(-575, 0, ref_drift=-375), tick(-75, 5, ref_drift=-375)],
        ),
        (
            "a1-learning-is-clamped-at-one-second",
            [tick(-575, 0, ref_drift=-375), tick(-1875, 5, ref_drift=-375)],
        ),
        (
            "a1-a-residual-under-a-new-anchor-teaches-nothing",
            [tick(-575, 0, ref_drift=-375), tick(-925, 5, ref_drift=-375, anchor_us=BASE_US + 2_000_000)],
        ),
        (
            "a1-a-route-transition-forgets-the-learned-latency",
            # Learns 550 at 15s, then the route changes. At 80s both seeks have left the 60s window,
            # so the next seek is uncompensated again: target = expected + reference drift.
            [tick(-575, 0, ref_drift=-375), tick(-925, 5, ref_drift=-375), tick(-375, 10, transitioning=True),
             tick(-575, 70, ref_drift=-375)],
        ),
        # --- PR #32 review: every route change forgets the learned latency -------------------------
        (
            "a1r-a-route-change-while-playing-forgets-the-learned-latency",
            # Learns 550 by 15s; at 70s this device's route count has moved with no transition
            # visible at the tick, and the next seek is uncompensated again.
            [tick(-575, 0, ref_drift=-375), tick(-925, 5, ref_drift=-375), tick(-375, 10, ref_drift=-375),
             tick(-575, 70, ref_drift=-375, routes=1)],
        ),
        (
            "a1r-a-route-transition-while-paused-forgets-the-learned-latency",
            [tick(-575, 0, ref_drift=-375), tick(-925, 5, ref_drift=-375),
             tick(-375, 10, playing=False, transitioning=True), tick(-575, 70, ref_drift=-375)],
        ),
        (
            "a1r-a-route-change-while-paused-between-ticks-forgets-the-learned-latency",
            [tick(-575, 0, ref_drift=-375), tick(-925, 5, ref_drift=-375),
             tick(-375, 10, playing=False, routes=1), tick(-575, 70, ref_drift=-375, routes=1)],
        ),
        (
            "a1r-a-route-change-after-sync-failure-forgets-the-learned-latency",
            # Learns 550, then fails on a 2.5s error; the ladder stays failed and does nothing, but
            # the route change it sees is still applied to what a new epoch would inherit.
            [tick(-575, 0, ref_drift=-375), tick(-925, 5, ref_drift=-375), tick(-2_900, 10, ref_drift=-375),
             tick(-375, 15, routes=1), tick(-375, 20, transitioning=True, routes=2)],
        ),
        (
            "a1r-a-route-change-between-ticks-is-seen-at-the-next-tick",
            # No tick ever sees `transitioning`; the count alone says the route changed in between.
            [tick(-575, 0, ref_drift=-375, routes=4), tick(-925, 5, ref_drift=-375, routes=4),
             tick(-375, 10, ref_drift=-375, routes=4), tick(-375, 15, ref_drift=-375, routes=5)],
        ),
        (
            "a1r-a-pending-seek-under-a-changed-route-teaches-nothing",
            [tick(-575, 0, ref_drift=-375), tick(-925, 5, ref_drift=-375, routes=1)],
        ),
        (
            "a1r-a-future-dated-reference-is-not-usable",
            # 1 us ahead of this device's sample, one hour ahead, then exactly at it.
            [tick(400, 0, reference={"drift_ms": 0, "at_session_us": BASE_US + 1, "playing": True}),
             tick(400, 0, reference={"drift_ms": 0, "at_session_us": BASE_US + 3_600_000_000, "playing": True}),
             tick(400, 0, reference={"drift_ms": 0, "at_session_us": BASE_US, "playing": True})],
        ),
        # --- PR #32 review round 2: a reference belongs to the anchor it was measured against ------
        (
            "a1t-a-reference-measured-against-an-earlier-anchor-instant-is-not-usable",
            # The timeline was re-anchored to an *earlier* instant (a same-track PLAYBACK_STATE can):
            # the old reference is settled, fresh and playing, and still says nothing about this one.
            [tick(-500, 0, anchor_us=ANCHOR_US + 500_000, anchor_pos=300,
                  reference={"drift_ms": 0, "at_session_us": BASE_US - 1_000_000, "playing": True,
                             "anchor_session_us": ANCHOR_US + 2_000_000, "anchor_position_ms": 0})],
        ),
        (
            "a1t-a-reference-measured-against-an-earlier-anchor-position-is-not-usable",
            [tick(-500, 0, anchor_pos=300,
                  reference={"drift_ms": 0, "at_session_us": BASE_US - 1_000_000, "playing": True,
                             "anchor_session_us": ANCHOR_US, "anchor_position_ms": 0})],
        ),
        (
            "a1t-correction-resumes-on-the-first-reference-for-the-new-anchor",
            # Stale reference: nothing. The leader's first report against the new anchor: a 200 ms
            # differential, corrected exactly as before.
            [tick(-500, 0, anchor_us=ANCHOR_US + 500_000, anchor_pos=300,
                  reference={"drift_ms": 0, "at_session_us": BASE_US - 1_000_000, "playing": True,
                             "anchor_session_us": ANCHOR_US, "anchor_position_ms": 0}),
             tick(-500, 5, anchor_us=ANCHOR_US + 500_000, anchor_pos=300, ref_drift=-300)],
        ),
        (
            "a1-a-pause-forgets-a-pending-seek",
            [tick(-575, 0, ref_drift=-375), tick(-925, 5, ref_drift=-375, playing=False),
             tick(-925, 10, ref_drift=-375)],
        ),
    ]

    rows = []
    for name, ticks in series:
        state = initial_state()
        steps = []
        for t in ticks:
            action, state, error = evaluate(state, t)
            steps.append({"input": t, "action": action, "state_after": state, "error_ms": error})
        rows.append({"name": name, "steps": steps})
    return rows


def epoch_rows() -> list[dict]:
    """`new_epoch` and `reset` — what a new playback epoch keeps and what a new session clears."""
    learned = {"nudging": True, "nudge_rate": RATE_FASTER, "hard_seek_at_session_us": [BASE_US, BASE_US + 5_000_000],
               "failed": True, "restart_compensation_ms": 550,
               "pending_seek": {"compensation_ms": 550, "anchor_session_us": ANCHOR_US},
               "route_change_count": 3}
    return [
        {"name": "new-epoch-keeps-only-the-learned-latency-and-its-route", "op": "new_epoch", "state": learned,
         "expected": new_epoch(learned)},
        {"name": "reset-clears-everything", "op": "reset", "state": learned, "expected": initial_state()},
    ]


def main() -> None:
    singles = single_rows()
    sequences = sequence_rows()
    payload = {
        "_comment": (
            "ARCHITECTURE §7.3 / ADR-004's drift ladder, as amended by ADR-004 Amendment A1: "
            "(state, drift, reference, route_state) -> (action, state, error). Boundary rows cover TEST_PLAN §2's 24/25/119/120/121/1999/2000/2001 "
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
            "HARD_SEEK's position_ms is the authoritative timeline's expected position plus the "
            "reference's drift against that same timeline plus restart_compensation_ms, floored at 0 "
            "(ADR-004 Amendment A1). With a zero reference and nothing learned it is exactly the "
            "expected position, which is every pre-amendment row.",
            "Without a usable reference the action is NONE or RESTORE_RATE, never a seek, a nudge or a "
            "failure, and error_ms is null: a leader, which has no reference, never corrects.",
            "restart_compensation_ms is always within 0..max_restart_compensation_ms.",
            "Any step with route_transitioning, or whose route_change_count differs from the state's "
            "route_change_count before it, leaves restart_compensation_ms 0 and pending_seek null — "
            "whether playing, paused or failed (PR #32 review).",
            "A reference with at_session_us > now_session_us is never usable: error_ms is null.",
            "A reference whose anchor_session_us or anchor_position_ms differs from the input's is "
            "never usable: error_ms is null (PR #32 review round 2).",
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
            "reference_settle_us": REFERENCE_SETTLE_US,
            "max_reference_age_us": MAX_REFERENCE_AGE_US,
            "max_restart_compensation_ms": MAX_RESTART_COMPENSATION_MS,
        },
        "single": singles,
        "sequences": sequences,
        "epochs": epoch_rows(),
    }
    out = Path(__file__).resolve().parent.parent / "protocol" / "vectors" / "drift"
    out.mkdir(parents=True, exist_ok=True)
    target = out / "drift_vectors.json"
    target.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    steps = sum(len(r["steps"]) for r in sequences)
    print(f"wrote {target} ({len(singles)} single rows, {len(sequences)} sequences, {steps} steps)")


if __name__ == "__main__":
    main()
