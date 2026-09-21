#!/usr/bin/env python3
"""Generate protocol/vectors/resync-messages/resync_messages_vectors.json.

Field-level validation for PROTOCOL §10's `STATE_REQUEST`/`STATE_SNAPSHOT` (Phase 7, ADR-028):
every required field missing, every field wrong-typed, every numeric bound at and one past its
edge, the nullable `playback` field (absent / explicit null / present with its own two nullable
identity fields), the nested `queue` object (PROTOCOL's own "QUEUE_SNAPSHOT shape"), the
`transfers_in_flight` bound, content-hash/queue-item-id/peer-id/transfer-id format checks, and
unknown-field tolerance (PROTOCOL §2 rule 1).

Accepted rows carry `expected.encoded`: the payload the parsed message must re-encode to, so
encode and parse are pinned as inverses.

An independent third transcription of §10's shape, not a port of either platform's `ResyncCodec`.
Edit this generator, never the JSON.

Run:  python3 tools/generate_resync_vectors.py
"""

from __future__ import annotations

import json
from pathlib import Path

MAX_WIRE_INT = 9_007_199_254_740_991
MAX_POSITION_MS = 86_400_000
MAX_TRANSFERS_IN_FLIGHT = 64

# Fabricated test values only — never a real peer_id, hash, or transfer id.
LEADER = "a3f1000000000001"
HASH = "sha256:" + "1f3a" * 16
HASH2 = "sha256:" + "77bd" * 16
ITEM = "01J9Z4M0Q7XK2V8R3T6Y1N5B2C"
ITEM2 = "01J9Z4M128H3PQ4R5S6T7V8W9X"
TRANSFER_ID = "01J9Z4M3RT8V2W5X7Y9Z1A3B5C"
ADDED_BY = "b7c1e0d9a4f28356"

CANONICAL_PLAYBACK = {
    "track_hash": HASH,
    "queue_item_id": ITEM,
    "position_ms": 128_400,
    "playing": True,
    "at_session_us": 90_990_000_000,
}

CANONICAL_QUEUE_ITEM = {"queue_item_id": ITEM, "track_hash": HASH, "added_by": ADDED_BY, "order": 1024}

CANONICAL_QUEUE = {"queue_revision": 13, "items": [CANONICAL_QUEUE_ITEM], "current_index": 0}

CANONICAL_TRANSFER = {"transfer_id": TRANSFER_ID, "content_hash": HASH2, "bytes_done": 4_194_304}

CANONICAL_SNAPSHOT = {
    "leader_peer_id": LEADER,
    "command_seq": 94,
    "queue_revision": 13,
    "playback": CANONICAL_PLAYBACK,
    "queue": CANONICAL_QUEUE,
    "manifest_revision": 7,
    "transfers_in_flight": [CANONICAL_TRANSFER],
}


def rows() -> list[dict]:
    out: list[dict] = []

    def ok(name: str, type_: str, payload: dict, encoded: dict):
        out.append({"name": name, "type": type_, "payload": payload, "expected": {"accepted": True, "encoded": encoded}})

    def bad(name: str, type_: str, payload: dict, rejection: str):
        out.append({"name": name, "type": type_, "payload": payload, "expected": {"accepted": False, "rejection": rejection}})

    # ---- STATE_REQUEST: no payload at all ---------------------------------------------------------
    ok("state-request-empty-payload", "STATE_REQUEST", {}, {})
    ok("state-request-tolerates-unknown-field", "STATE_REQUEST", {"future_field": "ignored"}, {})

    # ---- STATE_SNAPSHOT: canonical, complete -------------------------------------------------------
    ok("state-snapshot-canonical-complete", "STATE_SNAPSHOT", CANONICAL_SNAPSHOT, CANONICAL_SNAPSHOT)

    # ---- STATE_SNAPSHOT: minimal — nothing loaded, empty queue, no transfers -----------------------
    minimal = {
        "leader_peer_id": LEADER,
        "command_seq": 0,
        "queue_revision": 0,
        "playback": None,
        "queue": {"queue_revision": 0, "items": [], "current_index": None},
        "manifest_revision": 0,
        "transfers_in_flight": [],
    }
    ok("state-snapshot-minimal-nothing-loaded", "STATE_SNAPSHOT", minimal, minimal)

    # ---- playback present but nothing loaded (both identity fields explicit null) ------------------
    playback_nothing_loaded = {
        **CANONICAL_SNAPSHOT,
        "playback": {"track_hash": None, "queue_item_id": None, "position_ms": 0, "playing": False, "at_session_us": 0},
    }
    ok("state-snapshot-playback-present-nothing-loaded", "STATE_SNAPSHOT", playback_nothing_loaded, playback_nothing_loaded)

    # ---- unknown top-level and nested fields are ignored (PROTOCOL §2 rule 1) ----------------------
    ok(
        "state-snapshot-unknown-top-level-field",
        "STATE_SNAPSHOT",
        {**CANONICAL_SNAPSHOT, "future_field": "ignored"},
        CANONICAL_SNAPSHOT,
    )
    with_unknown_playback_field = {**CANONICAL_SNAPSHOT, "playback": {**CANONICAL_PLAYBACK, "future": 1}}
    ok("state-snapshot-unknown-playback-field", "STATE_SNAPSHOT", with_unknown_playback_field, CANONICAL_SNAPSHOT)
    with_unknown_transfer_field = {
        **CANONICAL_SNAPSHOT,
        "transfers_in_flight": [{**CANONICAL_TRANSFER, "future": 1}],
    }
    ok("state-snapshot-unknown-transfer-field", "STATE_SNAPSHOT", with_unknown_transfer_field, CANONICAL_SNAPSHOT)

    # ---- unknown type -------------------------------------------------------------------------------
    bad("unknown-type", "STATE_REQUEST_BACKWARDS", CANONICAL_SNAPSHOT, "UNKNOWN_TYPE")

    # ---- top-level required fields: missing and wrong-typed -----------------------------------------
    for field in ["leader_peer_id", "command_seq", "queue_revision", "playback", "queue", "manifest_revision", "transfers_in_flight"]:
        missing = {k: v for k, v in CANONICAL_SNAPSHOT.items() if k != field}
        bad(f"state-snapshot-missing-{field}", "STATE_SNAPSHOT", missing, "MISSING_FIELD")

    for field, rejection in [
        ("leader_peer_id", "WRONG_FIELD_TYPE"),
        ("command_seq", "WRONG_FIELD_TYPE"),
        ("queue_revision", "WRONG_FIELD_TYPE"),
        ("manifest_revision", "WRONG_FIELD_TYPE"),
    ]:
        wrong = {**CANONICAL_SNAPSHOT, field: {"nested": True}}
        bad(f"state-snapshot-wrong-type-{field}", "STATE_SNAPSHOT", wrong, rejection)

    bad("state-snapshot-queue-wrong-type", "STATE_SNAPSHOT", {**CANONICAL_SNAPSHOT, "queue": "not-an-object"}, "WRONG_FIELD_TYPE")
    bad(
        "state-snapshot-transfers-in-flight-wrong-type",
        "STATE_SNAPSHOT",
        {**CANONICAL_SNAPSHOT, "transfers_in_flight": "not-an-array"},
        "WRONG_FIELD_TYPE",
    )
    bad(
        "state-snapshot-playback-wrong-type",
        "STATE_SNAPSHOT",
        {**CANONICAL_SNAPSHOT, "playback": "not-an-object-or-null"},
        "MALFORMED_PLAYBACK",
    )

    # ---- leader_peer_id format ------------------------------------------------------------------
    bad(
        "state-snapshot-malformed-leader-peer-id",
        "STATE_SNAPSHOT",
        {**CANONICAL_SNAPSHOT, "leader_peer_id": "not-16-hex"},
        "MALFORMED_PEER_ID",
    )

    # ---- integer bounds: command_seq, queue_revision, manifest_revision ----------------------------
    for field, rejection in [
        ("command_seq", "COMMAND_SEQ_OUT_OF_RANGE"),
        ("queue_revision", "REVISION_OUT_OF_RANGE"),
        ("manifest_revision", "MANIFEST_REVISION_OUT_OF_RANGE"),
    ]:
        # `queue_revision` is stated twice on the wire (once at the envelope, once inside the reused
        # QUEUE_SNAPSHOT shape) and the codec deliberately normalises to the envelope's value on
        # re-encode (the nested one is parsed-but-discarded — ResyncCoordinator never sends them out
        # of sync in the first place) — so an accepted round-trip row must keep both in step.
        def with_field(value: int) -> dict:
            payload = {**CANONICAL_SNAPSHOT, field: value}
            if field == "queue_revision":
                payload["queue"] = {**CANONICAL_QUEUE, "queue_revision": value}
            return payload

        at_bound = with_field(MAX_WIRE_INT)
        ok(f"state-snapshot-{field}-at-max-wire-int", "STATE_SNAPSHOT", at_bound, at_bound)
        bad(f"state-snapshot-{field}-over-max-wire-int", "STATE_SNAPSHOT", with_field(MAX_WIRE_INT + 1), rejection)
        bad(f"state-snapshot-{field}-negative", "STATE_SNAPSHOT", with_field(-1), rejection)

    # ---- playback: required fields missing / wrong-typed -------------------------------------------
    for field in ["track_hash", "queue_item_id", "position_ms", "playing", "at_session_us"]:
        missing_pb = {k: v for k, v in CANONICAL_PLAYBACK.items() if k != field}
        bad(f"state-snapshot-playback-missing-{field}", "STATE_SNAPSHOT", {**CANONICAL_SNAPSHOT, "playback": missing_pb}, "MISSING_FIELD")

    bad(
        "state-snapshot-playback-malformed-track-hash",
        "STATE_SNAPSHOT",
        {**CANONICAL_SNAPSHOT, "playback": {**CANONICAL_PLAYBACK, "track_hash": "not-a-hash"}},
        "MALFORMED_CONTENT_HASH",
    )
    bad(
        "state-snapshot-playback-malformed-queue-item-id",
        "STATE_SNAPSHOT",
        {**CANONICAL_SNAPSHOT, "playback": {**CANONICAL_PLAYBACK, "queue_item_id": "not-a-ulid"}},
        "MALFORMED_QUEUE_ITEM_ID",
    )
    bad(
        "state-snapshot-playback-position-negative",
        "STATE_SNAPSHOT",
        {**CANONICAL_SNAPSHOT, "playback": {**CANONICAL_PLAYBACK, "position_ms": -1}},
        "POSITION_OUT_OF_RANGE",
    )
    at_pos_bound_pb = {**CANONICAL_PLAYBACK, "position_ms": MAX_POSITION_MS}
    ok(
        "state-snapshot-playback-position-at-max",
        "STATE_SNAPSHOT",
        {**CANONICAL_SNAPSHOT, "playback": at_pos_bound_pb},
        {**CANONICAL_SNAPSHOT, "playback": at_pos_bound_pb},
    )
    bad(
        "state-snapshot-playback-position-over-max",
        "STATE_SNAPSHOT",
        {**CANONICAL_SNAPSHOT, "playback": {**CANONICAL_PLAYBACK, "position_ms": MAX_POSITION_MS + 1}},
        "POSITION_OUT_OF_RANGE",
    )
    bad(
        "state-snapshot-playback-at-session-us-negative",
        "STATE_SNAPSHOT",
        {**CANONICAL_SNAPSHOT, "playback": {**CANONICAL_PLAYBACK, "at_session_us": -1}},
        "SESSION_TIME_OUT_OF_RANGE",
    )
    bad(
        "state-snapshot-playback-at-session-us-over-max",
        "STATE_SNAPSHOT",
        {**CANONICAL_SNAPSHOT, "playback": {**CANONICAL_PLAYBACK, "at_session_us": MAX_WIRE_INT + 1}},
        "SESSION_TIME_OUT_OF_RANGE",
    )

    # ---- queue: reuses QUEUE_SNAPSHOT's own validation, exercised via a couple of representative
    # shapes (the exhaustive queue-shape cross product already lives in queue-messages/) ------------
    bad(
        "state-snapshot-queue-missing-queue-revision",
        "STATE_SNAPSHOT",
        {**CANONICAL_SNAPSHOT, "queue": {"items": [], "current_index": None}},
        "MALFORMED_QUEUE",
    )
    bad(
        "state-snapshot-queue-duplicate-item-id",
        "STATE_SNAPSHOT",
        {**CANONICAL_SNAPSHOT, "queue": {"queue_revision": 13, "items": [CANONICAL_QUEUE_ITEM, CANONICAL_QUEUE_ITEM], "current_index": 0}},
        "MALFORMED_QUEUE",
    )
    bad(
        "state-snapshot-queue-current-index-out-of-range",
        "STATE_SNAPSHOT",
        {**CANONICAL_SNAPSHOT, "queue": {"queue_revision": 13, "items": [CANONICAL_QUEUE_ITEM], "current_index": 5}},
        "MALFORMED_QUEUE",
    )
    two_items_queue = {
        "queue_revision": 13,
        "items": [CANONICAL_QUEUE_ITEM, {"queue_item_id": ITEM2, "track_hash": HASH2, "added_by": ADDED_BY, "order": 2048}],
        "current_index": 1,
    }
    ok(
        "state-snapshot-queue-two-items",
        "STATE_SNAPSHOT",
        {**CANONICAL_SNAPSHOT, "queue": two_items_queue},
        {**CANONICAL_SNAPSHOT, "queue": two_items_queue},
    )

    # ---- transfers_in_flight: bound, format, multiple entries ---------------------------------------
    ok("state-snapshot-transfers-empty", "STATE_SNAPSHOT", {**CANONICAL_SNAPSHOT, "transfers_in_flight": []}, {**CANONICAL_SNAPSHOT, "transfers_in_flight": []})
    at_transfer_bound = [
        {"transfer_id": f"01J9Z4M3RT8V2W5X7Y9Z1A{str(i).zfill(4)}", "content_hash": HASH2, "bytes_done": i}
        for i in range(MAX_TRANSFERS_IN_FLIGHT)
    ]
    ok(
        "state-snapshot-transfers-at-max",
        "STATE_SNAPSHOT",
        {**CANONICAL_SNAPSHOT, "transfers_in_flight": at_transfer_bound},
        {**CANONICAL_SNAPSHOT, "transfers_in_flight": at_transfer_bound},
    )
    over_transfer_bound = at_transfer_bound + [CANONICAL_TRANSFER]
    bad(
        "state-snapshot-transfers-over-max",
        "STATE_SNAPSHOT",
        {**CANONICAL_SNAPSHOT, "transfers_in_flight": over_transfer_bound},
        "TOO_MANY_TRANSFERS",
    )
    for field in ["transfer_id", "content_hash", "bytes_done"]:
        missing_t = {k: v for k, v in CANONICAL_TRANSFER.items() if k != field}
        bad(
            f"state-snapshot-transfer-missing-{field}",
            "STATE_SNAPSHOT",
            {**CANONICAL_SNAPSHOT, "transfers_in_flight": [missing_t]},
            "MISSING_FIELD",
        )
    bad(
        "state-snapshot-transfer-malformed-transfer-id",
        "STATE_SNAPSHOT",
        {**CANONICAL_SNAPSHOT, "transfers_in_flight": [{**CANONICAL_TRANSFER, "transfer_id": "not-a-ulid"}]},
        "MALFORMED_TRANSFER_ID",
    )
    bad(
        "state-snapshot-transfer-malformed-content-hash",
        "STATE_SNAPSHOT",
        {**CANONICAL_SNAPSHOT, "transfers_in_flight": [{**CANONICAL_TRANSFER, "content_hash": "not-a-hash"}]},
        "MALFORMED_CONTENT_HASH",
    )
    bad(
        "state-snapshot-transfer-bytes-done-negative",
        "STATE_SNAPSHOT",
        {**CANONICAL_SNAPSHOT, "transfers_in_flight": [{**CANONICAL_TRANSFER, "bytes_done": -1}]},
        "BYTES_DONE_OUT_OF_RANGE",
    )
    bad(
        "state-snapshot-transfer-bytes-done-over-max",
        "STATE_SNAPSHOT",
        {**CANONICAL_SNAPSHOT, "transfers_in_flight": [{**CANONICAL_TRANSFER, "bytes_done": MAX_WIRE_INT + 1}]},
        "BYTES_DONE_OUT_OF_RANGE",
    )

    return out


def main() -> None:
    generated = rows()
    payload = {
        "_comment": (
            "Field-level validation for PROTOCOL §10's STATE_REQUEST/STATE_SNAPSHOT (Phase 7, "
            "ADR-028). Every required field missing, every field wrong-typed, every numeric bound "
            "at and one past its edge, the nullable playback field and its own two nullable "
            "identity fields, the nested queue object (QUEUE_SNAPSHOT's own shape, reused "
            "verbatim), the transfers_in_flight bound, and unknown-field tolerance. Accepted rows "
            "carry `expected.encoded`, so encode and parse are pinned as inverses. All identifiers "
            "are fabricated test values. Generated by tools/generate_resync_vectors.py — an "
            "independent third transcription of §10. Edit the generator, never this file."
        ),
        "_invariants": [
            "STATE_REQUEST carries no payload; unknown fields on it are tolerated and ignored.",
            "STATE_SNAPSHOT.playback is nullable at the top level (no synchronised timeline this "
            "session) and, when present, its track_hash/queue_item_id are independently nullable "
            "(nothing currently loaded) — the same 'nothing loaded is representable' rule "
            "PLAYBACK_STATE already has, because ADR-028 derives this shape from it.",
            "STATE_SNAPSHOT.queue is literally QUEUE_SNAPSHOT's own shape (queue_revision, items, "
            "current_index), reused rather than duplicated, so its malformed cases collapse to one "
            "MALFORMED_QUEUE reason here — the exhaustive per-field cross product is "
            "queue-messages/'s job, not this file's.",
            "transfers_in_flight is capped at 64 (ResyncBounds.MAX_TRANSFERS_IN_FLIGHT) — generous "
            "headroom, never a realistic count, in the same spirit as the 1 000-item queue cap.",
            "A numeric field sent as a JSON string is always WRONG_FIELD_TYPE, never coerced.",
            "Uppercase hex is rejected everywhere it appears (content_hash, leader_peer_id, "
            "transfer_id), matching ADR-012's identity rule.",
            "Every accepted row re-encodes to exactly `expected.encoded`, which never contains an "
            "unknown field the input carried.",
        ],
        "bounds": {
            "max_wire_int": MAX_WIRE_INT,
            "max_position_ms": MAX_POSITION_MS,
            "max_transfers_in_flight": MAX_TRANSFERS_IN_FLIGHT,
        },
        "rows": generated,
    }
    out = Path(__file__).resolve().parent.parent / "protocol" / "vectors" / "resync-messages"
    out.mkdir(parents=True, exist_ok=True)
    target = out / "resync_messages_vectors.json"
    target.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {target} ({len(generated)} rows)")


if __name__ == "__main__":
    main()
