#!/usr/bin/env python3
"""Generate protocol/vectors/playback-messages/playback_messages_vectors.json.

Field-level validation for PROTOCOL §5's playback messages (ADR-024 §4): every required field
missing, every field wrong-typed, every numeric bound at and past its edge, the ULID and
`content_hash` formats, the two nullable `PLAYBACK_STATE` identity fields (explicit null versus
absent versus present), and unknown-field tolerance (PROTOCOL §2 rule 1).

A bound enforced on one platform and not the other means a command one phone accepts and the other
refuses — mid-ride, silently. That is what this file exists to turn into a laptop test failure.

Accepted rows additionally carry `expected.encoded`: the payload the parsed message must re-encode
to, so encode and parse are pinned as inverses and an unknown field cannot survive a round trip.

An independent third transcription of §5's shapes and ADR-024 §4's bounds, not a port of either
platform's `PlaybackCodec`. Edit this generator, never the JSON.

Run:  python3 tools/generate_playback_messages_vectors.py
"""

from __future__ import annotations

import json
from pathlib import Path

MAX_WIRE_INT = 9_007_199_254_740_991
MAX_POSITION_MS = 86_400_000
MIN_RATE = 0.5
MAX_RATE = 2.0

# Fabricated test values only — never a real peer_id and never a real file's hash.
PEER = "a3f1000000000001"
HASH = "sha256:" + "1f3a" * 16
ITEM = "01J9Z4M0Q7XK2V8R3T6Y1N5B2C"

HEADER = {
    "command_seq": 87,
    "effective_at_session_us": 90_210_500_000,
    "issued_by": PEER,
    "queue_revision": 12,
}


def rows() -> list[dict]:
    out: list[dict] = []

    def ok(name: str, type_: str, payload: dict, encoded: dict):
        out.append({"name": name, "type": type_, "payload": payload, "expected": {"accepted": True, "encoded": encoded}})

    def bad(name: str, type_: str, payload: dict, rejection: str):
        out.append({"name": name, "type": type_, "payload": payload, "expected": {"accepted": False, "rejection": rejection}})

    # ---- canonical accepted forms, one per type -------------------------------------------------
    play = {**HEADER, "track_hash": HASH, "position_ms": 0, "queue_item_id": ITEM}
    ok("play-canonical", "PLAY", play, play)
    pause = {**HEADER, "position_ms": 45_120}
    ok("pause-canonical", "PAUSE", pause, pause)
    ok("resume-canonical", "RESUME", pause, pause)
    seek = {**HEADER, "target_position_ms": 61_000}
    ok("seek-canonical", "SEEK", seek, seek)
    ok("next-canonical", "NEXT", dict(HEADER), dict(HEADER))
    ok("previous-canonical", "PREVIOUS", dict(HEADER), dict(HEADER))
    report = {
        "track_hash": HASH, "position_ms": 45_120, "at_session_us": 90_260_000_000,
        "playing": True, "playback_rate": 1.0,
    }
    ok("position-report-canonical", "POSITION_REPORT", report, report)
    state = {
        "command_seq": 94, "queue_revision": 13, "track_hash": HASH, "queue_item_id": ITEM,
        "position_ms": 128_400, "playing": True, "at_session_us": 90_990_000_000,
    }
    ok("playback-state-canonical", "PLAYBACK_STATE", state, state)

    # ---- the intent convention (ADR-024 §3): command_seq 0 parses, it is not a codec error -------
    intent = {**HEADER, "command_seq": 0, "position_ms": 1_000}
    ok("pause-intent-command-seq-zero-parses", "PAUSE", intent, intent)

    # ---- unknown fields are ignored, never fatal (PROTOCOL §2 rule 1) ----------------------------
    ok("play-with-unknown-field", "PLAY", {**play, "future_field": "ignored"}, play)
    ok("next-with-unknown-nested-object", "NEXT", {**HEADER, "future": {"a": 1}}, dict(HEADER))

    # ---- unknown type ---------------------------------------------------------------------------
    bad("unknown-type", "PLAY_BACKWARDS", play, "UNKNOWN_TYPE")

    # ---- every header field missing and wrong-typed ---------------------------------------------
    for field in ["command_seq", "effective_at_session_us", "issued_by", "queue_revision"]:
        missing = {k: v for k, v in play.items() if k != field}
        bad(f"play-missing-{field}", "PLAY", missing, "MISSING_FIELD")
        wrong = {**play, field: {"nested": True}}
        bad(f"play-wrong-type-{field}", "PLAY", wrong, "WRONG_FIELD_TYPE")
    # A numeric field sent as a *string* is a wrong type, never coerced.
    bad("play-command-seq-as-string", "PLAY", {**play, "command_seq": "87"}, "WRONG_FIELD_TYPE")
    bad("play-command-seq-as-float", "PLAY", {**play, "command_seq": 1.5}, "WRONG_FIELD_TYPE")

    # ---- header numeric bounds ------------------------------------------------------------------
    bad("play-negative-command-seq", "PLAY", {**play, "command_seq": -1}, "COMMAND_SEQ_OUT_OF_RANGE")
    ok("play-command-seq-at-max", "PLAY", {**play, "command_seq": MAX_WIRE_INT},
       {**play, "command_seq": MAX_WIRE_INT})
    bad("play-command-seq-past-max", "PLAY", {**play, "command_seq": MAX_WIRE_INT + 1}, "COMMAND_SEQ_OUT_OF_RANGE")
    bad("play-negative-queue-revision", "PLAY", {**play, "queue_revision": -1}, "REVISION_OUT_OF_RANGE")
    ok("play-queue-revision-at-max", "PLAY", {**play, "queue_revision": MAX_WIRE_INT},
       {**play, "queue_revision": MAX_WIRE_INT})
    bad("play-queue-revision-past-max", "PLAY", {**play, "queue_revision": MAX_WIRE_INT + 1}, "REVISION_OUT_OF_RANGE")
    bad("play-negative-effective-at", "PLAY", {**play, "effective_at_session_us": -1}, "SESSION_TIME_OUT_OF_RANGE")
    ok("play-effective-at-zero", "PLAY", {**play, "effective_at_session_us": 0},
       {**play, "effective_at_session_us": 0})
    ok("play-effective-at-at-max", "PLAY", {**play, "effective_at_session_us": MAX_WIRE_INT},
       {**play, "effective_at_session_us": MAX_WIRE_INT})
    bad("play-effective-at-past-max", "PLAY", {**play, "effective_at_session_us": MAX_WIRE_INT + 1},
        "SESSION_TIME_OUT_OF_RANGE")

    # ---- issued_by format ------------------------------------------------------------------------
    bad("play-uppercase-issued-by", "PLAY", {**play, "issued_by": PEER.upper()}, "MALFORMED_PEER_ID")
    bad("play-short-issued-by", "PLAY", {**play, "issued_by": PEER[:15]}, "MALFORMED_PEER_ID")
    bad("play-long-issued-by", "PLAY", {**play, "issued_by": PEER + "0"}, "MALFORMED_PEER_ID")
    bad("play-non-hex-issued-by", "PLAY", {**play, "issued_by": "g" * 16}, "MALFORMED_PEER_ID")

    # ---- track_hash format -----------------------------------------------------------------------
    bad("play-missing-track-hash", "PLAY", {k: v for k, v in play.items() if k != "track_hash"}, "MISSING_FIELD")
    bad("play-wrong-type-track-hash", "PLAY", {**play, "track_hash": 7}, "WRONG_FIELD_TYPE")
    bad("play-uppercase-track-hash", "PLAY", {**play, "track_hash": "sha256:" + "1F3A" * 16}, "MALFORMED_CONTENT_HASH")
    bad("play-truncated-track-hash", "PLAY", {**play, "track_hash": "sha256:1f3a"}, "MALFORMED_CONTENT_HASH")
    bad("play-unprefixed-track-hash", "PLAY", {**play, "track_hash": "1f3a" * 16}, "MALFORMED_CONTENT_HASH")
    bad("play-quick-id-shaped-but-wrong-prefix", "PLAY", {**play, "track_hash": "sha1:" + "1f3a" * 16},
        "MALFORMED_CONTENT_HASH")

    # ---- queue_item_id format ---------------------------------------------------------------------
    bad("play-missing-queue-item-id", "PLAY", {k: v for k, v in play.items() if k != "queue_item_id"}, "MISSING_FIELD")
    bad("play-wrong-type-queue-item-id", "PLAY", {**play, "queue_item_id": 1}, "WRONG_FIELD_TYPE")
    bad("play-short-queue-item-id", "PLAY", {**play, "queue_item_id": ITEM[:25]}, "MALFORMED_QUEUE_ITEM_ID")
    bad("play-long-queue-item-id", "PLAY", {**play, "queue_item_id": ITEM + "Z"}, "MALFORMED_QUEUE_ITEM_ID")
    # Crockford base32 excludes I, L, O and U precisely so they cannot be confused with 1/0/V.
    for letter in ["I", "L", "O", "U"]:
        bad(f"play-queue-item-id-with-{letter}", "PLAY", {**play, "queue_item_id": letter + ITEM[1:]},
            "MALFORMED_QUEUE_ITEM_ID")
    bad("play-lowercase-queue-item-id", "PLAY", {**play, "queue_item_id": ITEM.lower()}, "MALFORMED_QUEUE_ITEM_ID")

    # ---- position bounds ---------------------------------------------------------------------------
    for type_, field in [("PLAY", "position_ms"), ("PAUSE", "position_ms"), ("RESUME", "position_ms"),
                         ("SEEK", "target_position_ms")]:
        base = play if type_ == "PLAY" else (seek if type_ == "SEEK" else pause)
        bad(f"{type_.lower()}-missing-{field}", type_, {k: v for k, v in base.items() if k != field}, "MISSING_FIELD")
        bad(f"{type_.lower()}-negative-{field}", type_, {**base, field: -1}, "POSITION_OUT_OF_RANGE")
        ok(f"{type_.lower()}-{field}-at-max", type_, {**base, field: MAX_POSITION_MS}, {**base, field: MAX_POSITION_MS})
        bad(f"{type_.lower()}-{field}-past-max", type_, {**base, field: MAX_POSITION_MS + 1}, "POSITION_OUT_OF_RANGE")

    # ---- POSITION_REPORT --------------------------------------------------------------------------
    for field in ["track_hash", "position_ms", "at_session_us", "playing", "playback_rate"]:
        bad(f"position-report-missing-{field}", "POSITION_REPORT",
            {k: v for k, v in report.items() if k != field}, "MISSING_FIELD")
    bad("position-report-playing-as-string", "POSITION_REPORT", {**report, "playing": "true"}, "WRONG_FIELD_TYPE")
    bad("position-report-rate-as-string", "POSITION_REPORT", {**report, "playback_rate": "1.0"}, "WRONG_FIELD_TYPE")
    ok("position-report-rate-at-min", "POSITION_REPORT", {**report, "playback_rate": MIN_RATE},
       {**report, "playback_rate": MIN_RATE})
    ok("position-report-rate-at-max", "POSITION_REPORT", {**report, "playback_rate": MAX_RATE},
       {**report, "playback_rate": MAX_RATE})
    bad("position-report-rate-below-min", "POSITION_REPORT", {**report, "playback_rate": 0.4999}, "RATE_OUT_OF_RANGE")
    bad("position-report-rate-above-max", "POSITION_REPORT", {**report, "playback_rate": 2.0001}, "RATE_OUT_OF_RANGE")
    bad("position-report-negative-rate", "POSITION_REPORT", {**report, "playback_rate": -1.0}, "RATE_OUT_OF_RANGE")
    for rate in [0.998, 1.002]:
        ok(f"position-report-nudged-rate-{rate}", "POSITION_REPORT", {**report, "playback_rate": rate},
           {**report, "playback_rate": rate})
    ok("position-report-not-playing", "POSITION_REPORT", {**report, "playing": False}, {**report, "playing": False})
    bad("position-report-negative-at-session-us", "POSITION_REPORT", {**report, "at_session_us": -1},
        "SESSION_TIME_OUT_OF_RANGE")

    # ---- PLAYBACK_STATE nullable identity fields ---------------------------------------------------
    nulled = {**state, "track_hash": None, "queue_item_id": None}
    ok("playback-state-explicit-nulls", "PLAYBACK_STATE", nulled, nulled)
    bad("playback-state-absent-track-hash", "PLAYBACK_STATE",
        {k: v for k, v in state.items() if k != "track_hash"}, "MISSING_FIELD")
    bad("playback-state-absent-queue-item-id", "PLAYBACK_STATE",
        {k: v for k, v in state.items() if k != "queue_item_id"}, "MISSING_FIELD")
    bad("playback-state-track-hash-wrong-type", "PLAYBACK_STATE", {**state, "track_hash": 5}, "WRONG_FIELD_TYPE")
    bad("playback-state-malformed-track-hash", "PLAYBACK_STATE", {**state, "track_hash": "nope"},
        "MALFORMED_CONTENT_HASH")
    bad("playback-state-malformed-queue-item-id", "PLAYBACK_STATE", {**state, "queue_item_id": "nope"},
        "MALFORMED_QUEUE_ITEM_ID")
    for field in ["command_seq", "queue_revision", "position_ms", "playing", "at_session_us"]:
        bad(f"playback-state-missing-{field}", "PLAYBACK_STATE",
            {k: v for k, v in state.items() if k != field}, "MISSING_FIELD")

    return out


def main() -> None:
    generated = rows()
    payload = {
        "_comment": (
            "Field-level validation for PROTOCOL §5's playback messages. Every required field "
            "missing, every field wrong-typed, every numeric bound at and one past its edge, the "
            "ULID and content_hash formats, PLAYBACK_STATE's two nullable identity fields, and "
            "unknown-field tolerance. Accepted rows carry `expected.encoded`, which the runner "
            "must get by re-encoding the parsed message — so encode and parse are pinned as "
            "inverses and an unknown field cannot survive a round trip. All identifiers are "
            "fabricated test values. Generated by "
            "tools/generate_playback_messages_vectors.py — an independent third transcription of "
            "§5 and ADR-024 §4. Edit the generator, never this file."
        ),
        "_invariants": [
            "command_seq 0 is a valid wire value (ADR-024 §3's intent marker) and must PARSE — the "
            "role check that rejects it belongs to CommandOrderGate, not to the codec.",
            "A numeric field sent as a JSON string is always WRONG_FIELD_TYPE, never coerced.",
            "An integer field sent as a non-integral number is WRONG_FIELD_TYPE — a rounded "
            "command_seq would order playback wrongly on one platform only.",
            "Uppercase hex is rejected everywhere it appears (content_hash, issued_by), matching "
            "ADR-012's identity rule.",
            "Every accepted row re-encodes to exactly `expected.encoded`, which never contains an "
            "unknown field the input carried.",
        ],
        "bounds": {
            "max_wire_int": MAX_WIRE_INT,
            "max_position_ms": MAX_POSITION_MS,
            "min_playback_rate": MIN_RATE,
            "max_playback_rate": MAX_RATE,
        },
        "rows": generated,
    }
    out = Path(__file__).resolve().parent.parent / "protocol" / "vectors" / "playback-messages"
    out.mkdir(parents=True, exist_ok=True)
    target = out / "playback_messages_vectors.json"
    target.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {target} ({len(generated)} rows)")


if __name__ == "__main__":
    main()
