#!/usr/bin/env python3
"""Generate protocol/vectors/intercom/intercom_vectors.json.

ARCHITECTURE §6.3 / ADR-021 — the intercom transmission gate:
`(policy, state, input) -> (state, actions)`.

Like `generate_session_gate_vectors.py` and `generate_voice_fsm_vectors.py`, this is a **third,
independent implementation**, written from `docs/ARCHITECTURE.md` §6.3 and
`docs/DECISIONS/ADR-021-intercom-transmission-and-capture-ownership.md` rather than ported from
either platform's reducer. That independence is the only thing that makes the vectors evidence
rather than a restatement: two ports of each other share their misreadings, and a shared misreading
is exactly the class of bug ADR-019 was written about.

The invariant this file exists to defend is the one A-10 will later test with real hardware: **the
transmission gate never touches the capture device.** No row may produce an action that opens or
closes capture, because no such action exists in the vocabulary at all — which is the point.

Edit this generator, never the JSON.

Run:  python3 tools/generate_intercom_vectors.py
"""

from __future__ import annotations

import json
from pathlib import Path

# --- the five REQUIREMENTS §8 modes, transcribed from ARCHITECTURE §6.3 -----------------------
#
#   Mode A = {true,  none,         duck(25%), high}
#   Mode B = {false, vox,          duck(25%), high}
#   Mode C = {false, ptt,          duck(35%), high}
#   Mode D = {true,  none,         pause,     yield}
#   Mode E = {false, ptt-disabled, —,         high}

VOX_THRESHOLD_DBFS = -35.0
VOX_HANGOVER_MS = 700
MICROS_PER_MS = 1_000

PRESETS: dict[str, dict] = {
    "MODE_A": {
        "mic_always_open": True,
        "gate": {"kind": "none"},
        "on_speech": {"kind": "duck", "to_percent": 25},
        "music_quality_priority": "HIGH",
    },
    "MODE_B": {
        "mic_always_open": False,
        "gate": {"kind": "vox", "threshold_dbfs": VOX_THRESHOLD_DBFS, "hangover_ms": VOX_HANGOVER_MS},
        "on_speech": {"kind": "duck", "to_percent": 25},
        "music_quality_priority": "HIGH",
    },
    "MODE_C": {
        "mic_always_open": False,
        "gate": {"kind": "ptt"},
        "on_speech": {"kind": "duck", "to_percent": 35},
        "music_quality_priority": "HIGH",
    },
    "MODE_D": {
        "mic_always_open": True,
        "gate": {"kind": "none"},
        "on_speech": {"kind": "pause"},
        "music_quality_priority": "YIELD_TO_VOICE",
    },
    "MODE_E": {
        "mic_always_open": False,
        "gate": {"kind": "disabled"},
        "on_speech": {"kind": "duck", "to_percent": 100},
        "music_quality_priority": "HIGH",
    },
}

# PROTOCOL §7.4 has three `VOICE_STATE.mode` values; §4.4's `AUDIO_STATE.intercom_mode` has four.
# ARCHITECTURE §6.3 spells Mode E "ptt-disabled", so `ptt` is the honest VOICE_STATE answer for it
# and `disabled` is the honest AUDIO_STATE one. That asymmetry is deliberate — see ADR-021 §3.
VOICE_WIRE_MODE = {"none": "CONTINUOUS", "vox": "VOX", "ptt": "PTT", "disabled": "PTT"}
INTERCOM_WIRE_MODE = {"none": "CONTINUOUS", "vox": "VOX", "ptt": "PTT", "disabled": "DISABLED"}


def preset_row(policy_id: str) -> dict:
    policy = PRESETS[policy_id]
    gate_kind = policy["gate"]["kind"]
    return {
        "id": policy_id,
        "mic_always_open": policy["mic_always_open"],
        "gate": policy["gate"],
        "on_speech": policy["on_speech"],
        "music_quality_priority": policy["music_quality_priority"],
        "voice_wire_mode": VOICE_WIRE_MODE[gate_kind],
        "intercom_wire_mode": INTERCOM_WIRE_MODE[gate_kind],
        "full_duplex": gate_kind == "none",
        "intercom_enabled": gate_kind != "disabled",
    }


def state(
    policy_id: str = "MODE_C",
    capture_open: bool = False,
    user_muted: bool = False,
    ptt_held: bool = False,
    vox_open: bool = False,
    vox_hangover_until_mono_us: int | None = None,
    interrupted: bool = False,
) -> dict:
    return {
        "policy_id": policy_id,
        "capture_open": capture_open,
        "user_muted": user_muted,
        "ptt_held": ptt_held,
        "vox_open": vox_open,
        "vox_hangover_until_mono_us": vox_hangover_until_mono_us,
        "interrupted": interrupted,
    }


def transmitting_of(s: dict) -> bool:
    """The one rule, written straight out of ARCHITECTURE §6.3 rather than read off a reducer."""
    if not s["capture_open"]:
        return False
    if s["interrupted"]:
        return False
    if s["user_muted"]:
        return False
    gate = PRESETS[s["policy_id"]]["gate"]["kind"]
    if gate == "none":
        return True
    if gate == "vox":
        return s["vox_open"]
    if gate == "ptt":
        return s["ptt_held"]
    return False  # disabled


def actions_for(before: dict, after: dict) -> list[dict]:
    """Actions are a diff, never a restatement (ADR-021 §4)."""
    out: list[dict] = []
    if transmitting_of(before) != transmitting_of(after):
        out.append({"kind": "SetTransmitting", "transmitting": transmitting_of(after)})
    before_gate = PRESETS[before["policy_id"]]["gate"]["kind"]
    after_gate = PRESETS[after["policy_id"]]["gate"]["kind"]
    if VOICE_WIRE_MODE[before_gate] != VOICE_WIRE_MODE[after_gate]:
        out.append({"kind": "AnnounceVoiceMode", "mode": VOICE_WIRE_MODE[after_gate]})
    if INTERCOM_WIRE_MODE[before_gate] != INTERCOM_WIRE_MODE[after_gate]:
        out.append({"kind": "PublishAudioState"})
    return out


def apply(before: dict, inp: dict) -> dict:
    """The state half of the reducer, also written from the architecture rather than the code."""
    after = dict(before)
    kind = inp["kind"]
    if kind == "PolicySelected":
        # A policy change resets the gate's transient state but never mute and never capture.
        after["policy_id"] = inp["policy_id"]
        after["ptt_held"] = False
        after["vox_open"] = False
        after["vox_hangover_until_mono_us"] = None
    elif kind == "UserMuted":
        after["user_muted"] = inp["muted"]
    elif kind == "PttHeld":
        after["ptt_held"] = inp["held"]
    elif kind == "CaptureOpen":
        after["capture_open"] = inp["open"]
        if not inp["open"]:
            after["ptt_held"] = False
            after["vox_open"] = False
            after["vox_hangover_until_mono_us"] = None
    elif kind == "Interrupted":
        after["interrupted"] = inp["interrupted"]
    elif kind == "SpeechLevel":
        gate = PRESETS[before["policy_id"]]["gate"]
        if gate["kind"] != "vox":
            return after
        if inp["level_dbfs"] >= gate["threshold_dbfs"]:
            after["vox_open"] = True
            after["vox_hangover_until_mono_us"] = inp["at_mono_us"] + gate["hangover_ms"] * MICROS_PER_MS
        else:
            after = close_if_expired(after, inp["at_mono_us"])
    elif kind == "VoxTick":
        if PRESETS[before["policy_id"]]["gate"]["kind"] != "vox":
            return after
        after = close_if_expired(after, inp["at_mono_us"])
    else:
        raise AssertionError(f"unknown input kind {kind}")
    return after


def close_if_expired(s: dict, now_mono_us: int) -> dict:
    out = dict(s)
    if not out["vox_open"]:
        return out
    deadline = out["vox_hangover_until_mono_us"]
    if deadline is None:
        out["vox_open"] = False
        return out
    if now_mono_us >= deadline:
        out["vox_open"] = False
        out["vox_hangover_until_mono_us"] = None
    return out


def row(name: str, before: dict, inp: dict) -> dict:
    after = apply(before, inp)
    return {
        "name": name,
        "state": before,
        "input": inp,
        "expect": {
            "state": after,
            "actions": actions_for(before, after),
            "transmitting": transmitting_of(after),
        },
    }


def build() -> list[dict]:
    rows: list[dict] = []

    # =============================================================================================
    # Full duplex (Modes A and D) — the product's primary capability. No gate at all.
    # =============================================================================================
    for mode in ("MODE_A", "MODE_D"):
        rows.append(row(f"{mode.lower()}-capture-open-transmits-immediately", state(mode), {"kind": "CaptureOpen", "open": True}))
        rows.append(
            row(
                f"{mode.lower()}-ptt-press-is-irrelevant-under-no-gate",
                state(mode, capture_open=True),
                {"kind": "PttHeld", "held": True},
            )
        )
        rows.append(
            row(
                f"{mode.lower()}-mute-stops-transmission-without-closing-capture",
                state(mode, capture_open=True),
                {"kind": "UserMuted", "muted": True},
            )
        )
        rows.append(
            row(
                f"{mode.lower()}-unmute-resumes-transmission",
                state(mode, capture_open=True, user_muted=True),
                {"kind": "UserMuted", "muted": False},
            )
        )
        rows.append(
            row(
                f"{mode.lower()}-interruption-stops-transmission",
                state(mode, capture_open=True),
                {"kind": "Interrupted", "interrupted": True},
            )
        )
        rows.append(
            row(
                f"{mode.lower()}-interruption-ending-resumes-transmission",
                state(mode, capture_open=True, interrupted=True),
                {"kind": "Interrupted", "interrupted": False},
            )
        )

    # =============================================================================================
    # PTT (Mode C) — the default until docs/PHASE0_RESULTS.md is filled in.
    # =============================================================================================
    rows.append(row("ptt-capture-open-does-not-transmit", state("MODE_C"), {"kind": "CaptureOpen", "open": True}))
    rows.append(row("ptt-press-transmits", state("MODE_C", capture_open=True), {"kind": "PttHeld", "held": True}))
    rows.append(
        row(
            "ptt-release-stops-transmitting",
            state("MODE_C", capture_open=True, ptt_held=True),
            {"kind": "PttHeld", "held": False},
        )
    )
    rows.append(
        row(
            "ptt-press-twice-is-idempotent-and-emits-nothing",
            state("MODE_C", capture_open=True, ptt_held=True),
            {"kind": "PttHeld", "held": True},
        )
    )
    rows.append(
        row(
            "ptt-release-twice-is-idempotent-and-emits-nothing",
            state("MODE_C", capture_open=True),
            {"kind": "PttHeld", "held": False},
        )
    )
    # This phase's brief §25: a cancel, a lost touch, an accessibility gesture with no "up", and the
    # app being backgrounded are all the same absolute assignment — and all leave transmission off.
    rows.append(
        row(
            "ptt-cancel-while-held-stops-transmitting",
            state("MODE_C", capture_open=True, ptt_held=True),
            {"kind": "PttHeld", "held": False},
        )
    )
    rows.append(
        row(
            "ptt-press-without-capture-cannot-transmit",
            state("MODE_C"),
            {"kind": "PttHeld", "held": True},
        )
    )
    rows.append(
        row(
            "ptt-mute-wins-over-a-held-button",
            state("MODE_C", capture_open=True, ptt_held=True),
            {"kind": "UserMuted", "muted": True},
        )
    )
    rows.append(
        row(
            "ptt-interruption-wins-over-a-held-button",
            state("MODE_C", capture_open=True, ptt_held=True),
            {"kind": "Interrupted", "interrupted": True},
        )
    )
    rows.append(
        row(
            "ptt-capture-closing-while-held-clears-the-button",
            state("MODE_C", capture_open=True, ptt_held=True),
            {"kind": "CaptureOpen", "open": False},
        )
    )
    rows.append(
        row(
            "ptt-capture-reopening-does-not-resume-a-stale-press",
            state("MODE_C"),
            {"kind": "CaptureOpen", "open": True},
        )
    )

    # =============================================================================================
    # VOX (Mode B) — threshold and hangover. The state machine is real; its level source is not
    # wired (ADR-021 §6), which is why these rows drive it with a supplied level.
    # =============================================================================================
    rows.append(
        row(
            "vox-level-above-threshold-opens-the-gate",
            state("MODE_B", capture_open=True),
            {"kind": "SpeechLevel", "level_dbfs": -20.0, "at_mono_us": 1_000_000},
        )
    )
    rows.append(
        row(
            "vox-level-exactly-at-threshold-opens-the-gate",
            state("MODE_B", capture_open=True),
            {"kind": "SpeechLevel", "level_dbfs": VOX_THRESHOLD_DBFS, "at_mono_us": 1_000_000},
        )
    )
    rows.append(
        row(
            "vox-level-below-threshold-does-not-open-the-gate",
            state("MODE_B", capture_open=True),
            {"kind": "SpeechLevel", "level_dbfs": -60.0, "at_mono_us": 1_000_000},
        )
    )
    rows.append(
        row(
            "vox-quiet-within-hangover-keeps-the-gate-open",
            state("MODE_B", capture_open=True, vox_open=True, vox_hangover_until_mono_us=1_700_000),
            {"kind": "SpeechLevel", "level_dbfs": -60.0, "at_mono_us": 1_500_000},
        )
    )
    rows.append(
        row(
            "vox-quiet-past-hangover-closes-the-gate",
            state("MODE_B", capture_open=True, vox_open=True, vox_hangover_until_mono_us=1_700_000),
            {"kind": "SpeechLevel", "level_dbfs": -60.0, "at_mono_us": 1_700_000},
        )
    )
    rows.append(
        row(
            "vox-tick-past-hangover-closes-the-gate-with-no-new-level",
            state("MODE_B", capture_open=True, vox_open=True, vox_hangover_until_mono_us=1_700_000),
            {"kind": "VoxTick", "at_mono_us": 2_000_000},
        )
    )
    rows.append(
        row(
            "vox-tick-within-hangover-changes-nothing",
            state("MODE_B", capture_open=True, vox_open=True, vox_hangover_until_mono_us=1_700_000),
            {"kind": "VoxTick", "at_mono_us": 1_100_000},
        )
    )
    rows.append(
        row(
            "vox-renewed-level-extends-the-hangover",
            state("MODE_B", capture_open=True, vox_open=True, vox_hangover_until_mono_us=1_700_000),
            {"kind": "SpeechLevel", "level_dbfs": -10.0, "at_mono_us": 1_500_000},
        )
    )
    rows.append(
        row(
            "vox-mute-wins-over-an-open-gate",
            state("MODE_B", capture_open=True, vox_open=True, vox_hangover_until_mono_us=1_700_000),
            {"kind": "UserMuted", "muted": True},
        )
    )
    rows.append(
        row(
            "vox-level-is-ignored-under-ptt",
            state("MODE_C", capture_open=True),
            {"kind": "SpeechLevel", "level_dbfs": 0.0, "at_mono_us": 1_000_000},
        )
    )
    rows.append(
        row(
            "vox-level-is-ignored-under-no-gate",
            state("MODE_A", capture_open=True),
            {"kind": "SpeechLevel", "level_dbfs": 0.0, "at_mono_us": 1_000_000},
        )
    )
    rows.append(
        row(
            "vox-tick-is-ignored-under-ptt",
            state("MODE_C", capture_open=True, ptt_held=True),
            {"kind": "VoxTick", "at_mono_us": 9_000_000},
        )
    )

    # =============================================================================================
    # Mode E — music only. Nothing ever transmits, and no press or level changes that.
    # =============================================================================================
    rows.append(row("disabled-capture-open-never-transmits", state("MODE_E"), {"kind": "CaptureOpen", "open": True}))
    rows.append(
        row(
            "disabled-ptt-press-never-transmits",
            state("MODE_E", capture_open=True),
            {"kind": "PttHeld", "held": True},
        )
    )
    rows.append(
        row(
            "disabled-level-never-transmits",
            state("MODE_E", capture_open=True),
            {"kind": "SpeechLevel", "level_dbfs": 0.0, "at_mono_us": 1_000_000},
        )
    )

    # =============================================================================================
    # Policy switching — including the two switches that must not leave transmission on.
    # =============================================================================================
    rows.append(
        row(
            "switch-from-full-duplex-to-ptt-stops-transmitting",
            state("MODE_A", capture_open=True),
            {"kind": "PolicySelected", "policy_id": "MODE_C"},
        )
    )
    rows.append(
        row(
            "switch-from-ptt-held-to-full-duplex-clears-the-button-and-transmits",
            state("MODE_C", capture_open=True, ptt_held=True),
            {"kind": "PolicySelected", "policy_id": "MODE_A"},
        )
    )
    rows.append(
        row(
            "switch-into-ptt-does-not-inherit-a-held-button",
            state("MODE_A", capture_open=True, ptt_held=True),
            {"kind": "PolicySelected", "policy_id": "MODE_C"},
        )
    )
    rows.append(
        row(
            "switch-out-of-vox-clears-a-stale-open-gate",
            state("MODE_B", capture_open=True, vox_open=True, vox_hangover_until_mono_us=1_700_000),
            {"kind": "PolicySelected", "policy_id": "MODE_C"},
        )
    )
    rows.append(
        row(
            "switch-to-mode-e-stops-transmitting-and-reports-disabled",
            state("MODE_A", capture_open=True),
            {"kind": "PolicySelected", "policy_id": "MODE_E"},
        )
    )
    rows.append(
        row(
            "switch-from-mode-c-to-mode-e-changes-audio-state-mode-but-not-voice-state-mode",
            state("MODE_C", capture_open=True),
            {"kind": "PolicySelected", "policy_id": "MODE_E"},
        )
    )
    rows.append(
        row(
            "switch-between-modes-a-and-d-changes-nothing-observable-on-the-wire",
            state("MODE_A", capture_open=True),
            {"kind": "PolicySelected", "policy_id": "MODE_D"},
        )
    )
    rows.append(
        row(
            "switch-preserves-user-mute",
            state("MODE_A", capture_open=True, user_muted=True),
            {"kind": "PolicySelected", "policy_id": "MODE_C"},
        )
    )
    rows.append(
        row(
            "switch-preserves-capture-open",
            state("MODE_C", capture_open=True),
            {"kind": "PolicySelected", "policy_id": "MODE_B"},
        )
    )
    rows.append(
        row(
            "reselecting-the-same-policy-emits-nothing",
            state("MODE_C", capture_open=True),
            {"kind": "PolicySelected", "policy_id": "MODE_C"},
        )
    )

    # =============================================================================================
    # Capture and interruption, across every mode — the two overrides that can only ever stop.
    # =============================================================================================
    for mode in PRESETS:
        rows.append(
            row(
                f"{mode.lower()}-capture-close-never-transmits-after",
                state(mode, capture_open=True, ptt_held=True, vox_open=True, vox_hangover_until_mono_us=9_000_000),
                {"kind": "CaptureOpen", "open": False},
            )
        )
        rows.append(
            row(
                f"{mode.lower()}-interruption-while-gated-open-stops-transmitting",
                state(mode, capture_open=True, ptt_held=True, vox_open=True, vox_hangover_until_mono_us=9_000_000),
                {"kind": "Interrupted", "interrupted": True},
            )
        )

    return rows


def main() -> None:
    rows = build()
    names = [r["name"] for r in rows]
    assert len(names) == len(set(names)), "duplicate row names"
    payload = {
        "_comment": (
            "ARCHITECTURE §6.3 / ADR-021 — the intercom transmission gate: (policy, state, input) -> "
            "(state, actions). Both platforms' IntercomTransmission reducer runs this same file, so a "
            "PTT or VOX gate implemented differently on the two phones is a laptop unit-test failure "
            "rather than something a ride discovers. Generated by tools/generate_intercom_vectors.py "
            "— an independent third transcription of the spec. Edit the generator, never this file."
        ),
        "_invariants": [
            "No action in this file opens or closes the capture device, because the vocabulary has no "
            "such action. PTT and VOX gate transmission, not hardware (ARCHITECTURE §6.3), and TEST_PLAN "
            "A-10 is the hardware test of the same property.",
            "No row may report transmitting=true while capture_open is false. Transmission cannot "
            "precede an open capture path, and a PTT press must never be what opens one "
            "(ARCHITECTURE §6.4).",
            "No row may report transmitting=true while user_muted or interrupted is true.",
            "No row under gate 'disabled' may report transmitting=true (Mode E is music only).",
            "Every SetTransmitting action must match the resulting state's transmitting value, and "
            "must be absent when it did not change — actions are a diff, not a restatement.",
            "A PolicySelected row must never leave ptt_held or vox_open set: switching into a gate "
            "must not inherit a button nobody is holding.",
        ],
        "_measurement_status": (
            "MODE_C is the default because docs/PHASE0_RESULTS.md is still awaiting the user's Phase 0 "
            "numbers, per ARCHITECTURE §6.3 and ADR-008 §4 — not because any device measurement "
            "selected it. The VOX threshold and hangover here are reasoned starting points for "
            "TEST_PLAN A-14, not tuned values, and no microphone-driven level source is wired on "
            "either platform (ADR-021 §6)."
        ),
        "default_policy_id": "MODE_C",
        "vox_defaults": {"threshold_dbfs": VOX_THRESHOLD_DBFS, "hangover_ms": VOX_HANGOVER_MS},
        "presets": [preset_row(policy_id) for policy_id in PRESETS],
        "rows": rows,
    }
    out = Path(__file__).resolve().parent.parent / "protocol" / "vectors" / "intercom"
    out.mkdir(parents=True, exist_ok=True)
    target = out / "intercom_vectors.json"
    target.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {target} ({len(rows)} rows)")


if __name__ == "__main__":
    main()
