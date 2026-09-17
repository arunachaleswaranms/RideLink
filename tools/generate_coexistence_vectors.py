#!/usr/bin/env python3
"""Generate protocol/vectors/coexistence/coexistence_vectors.json.

This is the platform-independent Phase 6 semantic table. Android and Apple execute the generated
rows against their mirrored reducers. Edit this generator, never the JSON.

Run:  python3 tools/generate_coexistence_vectors.py
"""

from __future__ import annotations

import json
from pathlib import Path

RAMP_MS = 200


def voice(available: bool, local: bool, peer: bool, generation: int | None = None) -> dict:
    event = {
        "kind": "VoiceChanged",
        "available": available,
        "local_transmitting": local,
        "peer_transmitting": peer,
    }
    if generation is not None:
        event["generation"] = generation
    return event


def policy(mode: str) -> dict:
    return {"kind": "PolicySelected", "policy_id": mode}


def music(available: bool, token: str | None, playing: bool, ended: bool) -> dict:
    return {
        "kind": "MusicChanged",
        "available": available,
        "track_token": token,
        "playing": playing,
        "ended": ended,
    }


def scenario(
    name: str,
    mode: str,
    events: list[dict],
    actions: list[str],
    target: int,
    *,
    base: int = 1000,
    fallback: str = "NONE",
    stale: int = 0,
) -> dict:
    return {
        "name": name,
        "policy_id": mode,
        "base_volume_permille": base,
        "events": events,
        "expect_actions": actions,
        "expect_target_permille": target,
        "expect_base_permille": base,
        "expect_fallback": fallback,
        "expect_stale_count": stale,
    }


def scenarios() -> list[dict]:
    return [
        scenario(
            "mode-a-duck-and-exact-restore", "MODE_A",
            [voice(True, True, False), voice(True, False, False)],
            ["Ramp(250,200)", "Ramp(1000,200)"], 1000,
        ),
        scenario(
            "mode-b-synthetic-vox-duck", "MODE_B",
            [voice(True, True, False), voice(True, False, False)],
            ["Ramp(250,200)", "Ramp(1000,200)"], 1000,
        ),
        scenario(
            "mode-c-multiplies-eighty-percent-base", "MODE_C",
            [voice(True, True, False), voice(True, False, False)],
            ["Ramp(280,200)", "Ramp(800,200)"], 800, base=800,
        ),
        scenario(
            "mode-d-temporary-pause-and-resume", "MODE_D",
            [voice(True, True, False), music(True, "track-a", False, False), voice(True, False, False)],
            ["Pause(track-a)", "Resume(track-a)"], 1000,
        ),
        scenario(
            "mode-e-never-changes-music-for-voice", "MODE_E",
            [voice(False, True, True)], [], 1000,
        ),
        scenario(
            "duplicate-and-rapid-reversal", "MODE_C",
            [voice(True, True, False), voice(True, True, False), voice(True, False, False), voice(True, False, False)],
            ["Ramp(350,200)", "Ramp(1000,200)"], 1000,
        ),
        scenario(
            "policy-change-while-ducked", "MODE_C",
            [voice(True, True, False), policy("MODE_A")],
            ["Ramp(350,200)", "Ramp(250,200)"], 250,
        ),
        scenario(
            "duck-to-pause-restores-gain", "MODE_C",
            [voice(True, True, False), policy("MODE_D")],
            ["Ramp(350,200)", "Pause(track-a)", "Ramp(1000,200)"], 1000,
        ),
        scenario(
            "user-pause-wins-over-mode-d-resume", "MODE_D",
            [voice(True, True, False), {"kind": "UserPlaybackIntent", "playing": False}, voice(True, False, False)],
            ["Pause(track-a)"], 1000,
        ),
        scenario(
            "voice-unavailable-restores-music", "MODE_C",
            [voice(True, True, False), voice(False, False, False)],
            ["Ramp(350,200)", "Ramp(1000,200)"], 1000, fallback="VOICE_UNAVAILABLE",
        ),
        scenario(
            "music-unavailable-does-not-stop-voice", "MODE_C",
            [music(False, None, False, False), voice(True, True, False)],
            [], 350, fallback="MUSIC_UNAVAILABLE",
        ),
        scenario(
            "interruption-restores-then-reapplies-duck", "MODE_A",
            [
                voice(True, True, False),
                {"kind": "RouteChanged", "route_state": "TRANSITIONING", "interrupted": True,
                 "transition_timed_out": False},
                {"kind": "RouteChanged", "route_state": "STABLE", "interrupted": False,
                 "transition_timed_out": False},
            ],
            ["Ramp(250,200)", "Ramp(1000,200)", "Ramp(250,200)"], 250,
        ),
        scenario(
            "route-timeout-is-explicit-fallback", "MODE_C",
            [{"kind": "RouteChanged", "route_state": "STABLE", "interrupted": False,
              "transition_timed_out": True}],
            [], 1000, fallback="ROUTE_TRANSITION_TIMEOUT",
        ),
        scenario(
            "track-replacement-reasserts-duck", "MODE_C",
            [voice(True, True, False), music(True, "track-b", True, False)],
            ["Ramp(350,200)", "Ramp(350,200)"], 350,
        ),
        scenario(
            "teardown-restores-duck", "MODE_C",
            [voice(True, True, False), {"kind": "LifetimeEnded"}],
            ["Ramp(350,200)", "Ramp(1000,200)"], 1000,
        ),
        scenario(
            "stale-predecessor-event-is-inert", "MODE_C",
            [{"kind": "LifetimeStarted", "generation": 2, "policy_id": "MODE_C"},
             voice(True, True, False, generation=1)],
            ["Ramp(1000,200)"], 1000, fallback="VOICE_UNAVAILABLE", stale=1,
        ),
        scenario(
            "reconnect-restores-predecessor-mode-d-pause", "MODE_D",
            [voice(True, True, False), music(True, "track-a", False, False),
             {"kind": "LifetimeStarted", "generation": 2, "policy_id": "MODE_C"},
             voice(True, True, False, generation=1)],
            ["Pause(track-a)", "Resume(track-a)", "Ramp(1000,200)"], 1000,
            fallback="VOICE_UNAVAILABLE", stale=1,
        ),
        scenario(
            "sync-failure-is-independent-fallback", "MODE_C",
            [{"kind": "SyncAvailabilityChanged", "available": False}, voice(True, True, False)],
            ["Ramp(350,200)"], 350, fallback="SYNC_UNAVAILABLE",
        ),
        scenario(
            "mode-switch-a-c-a-recomputes-duck", "MODE_A",
            [voice(True, True, False), policy("MODE_C"), policy("MODE_A")],
            ["Ramp(250,200)", "Ramp(350,200)", "Ramp(250,200)"], 250,
        ),
        scenario(
            "mode-switch-c-d-e-c-clears-temporary-effects", "MODE_C",
            [voice(True, True, False), policy("MODE_D"), music(True, "track-a", False, False),
             policy("MODE_E"), policy("MODE_C")],
            ["Ramp(350,200)", "Pause(track-a)", "Ramp(1000,200)", "Resume(track-a)",
             "Ramp(350,200)"], 350,
        ),
        scenario(
            "mode-switch-b-c-b-recomputes-duck", "MODE_B",
            [voice(True, True, False), policy("MODE_C"), policy("MODE_B")],
            ["Ramp(250,200)", "Ramp(350,200)", "Ramp(250,200)"], 250,
        ),
    ]


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    target = root / "protocol" / "vectors" / "coexistence" / "coexistence_vectors.json"
    document = {
        "version": 1,
        "ramp_duration_ms": RAMP_MS,
        "measurement_status": "SOFTWARE_ONLY_PHYSICAL_QUALIFICATION_DEFERRED",
        "scenarios": scenarios(),
    }
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
