#!/usr/bin/env python3
"""Generate protocol/vectors/queue-messages/queue_messages_vectors.json.

Field-level validation for PROTOCOL §9's queue-replication messages (ADR-024 §5/§6): every required
field missing, every field wrong-typed, the ULID/`content_hash`/`peer_id` formats inside array
elements, the item-count bounds, `current_index` as a required-but-nullable field, duplicate
`queue_item_id`s inside one frame, and the `position` vocabulary degrading unknown values to `end`
rather than rejecting the frame.

`status` is deliberately absent from every shape here. PROTOCOL §9 listed it on `QUEUE_SNAPSHOT`
while saying in the same paragraph that it is "derived locally from presence, never trusted from
the peer"; ADR-024 §6 removes it, because a field that must never be trusted has no reason to be
sent.

An independent third transcription, not a port of either platform's `QueueCodec`. Edit this
generator, never the JSON.

Run:  python3 tools/generate_queue_messages_vectors.py
"""

from __future__ import annotations

import json
from pathlib import Path

MAX_WIRE_INT = 9_007_199_254_740_991
MAX_QUEUE_ITEMS = 1000

PEER_A = "a3f1000000000001"
PEER_B = "b7c1000000000002"
HASH = "sha256:" + "1f3a" * 16


def ulid(n: int) -> str:
    return f"01J9Z4M0Q7XK2V8R3T6Y1N{n:04d}"[:26].ljust(26, "0")


ITEM_A = ulid(1)
ITEM_B = ulid(2)


def rows() -> list[dict]:
    out: list[dict] = []

    def ok(name: str, type_: str, payload: dict, encoded: dict):
        out.append({"name": name, "type": type_, "payload": payload, "expected": {"accepted": True, "encoded": encoded}})

    def bad(name: str, type_: str, payload: dict, rejection: str):
        out.append({"name": name, "type": type_, "payload": payload, "expected": {"accepted": False, "rejection": rejection}})

    add_item = {"queue_item_id": ITEM_A, "track_hash": HASH, "added_by": PEER_A, "position": "end"}
    add = {"command_seq": 92, "queue_revision": 13, "items": [add_item]}
    ok("queue-add-canonical", "QUEUE_ADD", add, add)

    two = {**add, "items": [add_item, {**add_item, "queue_item_id": ITEM_B, "added_by": PEER_B, "position": "next"}]}
    ok("queue-add-two-items-both-positions", "QUEUE_ADD", two, two)

    # The same track queued twice as two distinct entries — the property brief §27 requires.
    dup_track = {**add, "items": [add_item, {**add_item, "queue_item_id": ITEM_B}]}
    ok("queue-add-same-track-twice-different-ids", "QUEUE_ADD", dup_track, dup_track)

    remove = {"command_seq": 93, "queue_revision": 14, "queue_item_ids": [ITEM_A, ITEM_B]}
    ok("queue-remove-canonical", "QUEUE_REMOVE", remove, remove)

    move = {"command_seq": 94, "queue_revision": 15, "queue_item_id": ITEM_A, "to_index": 2}
    ok("queue-move-canonical", "QUEUE_MOVE", move, move)

    snap_item = {"queue_item_id": ITEM_A, "track_hash": HASH, "added_by": PEER_A, "order": 1024}
    snapshot = {"queue_revision": 13, "items": [snap_item], "current_index": 0}
    ok("queue-snapshot-canonical", "QUEUE_SNAPSHOT", snapshot, snapshot)
    ok("queue-snapshot-null-current-index", "QUEUE_SNAPSHOT",
       {**snapshot, "current_index": None}, {**snapshot, "current_index": None})
    empty_snapshot = {"queue_revision": 20, "items": [], "current_index": None}
    ok("queue-snapshot-empty-is-legitimate", "QUEUE_SNAPSHOT", empty_snapshot, empty_snapshot)

    # The intent convention (ADR-024 §3) parses here too.
    ok("queue-add-intent-command-seq-zero", "QUEUE_ADD", {**add, "command_seq": 0}, {**add, "command_seq": 0})

    # Unknown fields ignored (PROTOCOL §2 rule 1), including inside an item.
    ok("queue-add-with-unknown-top-level-field", "QUEUE_ADD", {**add, "future": 1}, add)
    ok("queue-add-with-unknown-item-field", "QUEUE_ADD",
       {**add, "items": [{**add_item, "future": "x"}]}, add)
    # `status` is exactly such an unknown field now, and a peer that still sends it is tolerated —
    # the value is ignored, never adopted (ADR-024 §6).
    ok("queue-snapshot-legacy-status-field-is-ignored", "QUEUE_SNAPSHOT",
       {**snapshot, "items": [{**snap_item, "status": "ready"}]}, snapshot)

    bad("unknown-type", "QUEUE_SHUFFLE", add, "UNKNOWN_TYPE")

    # ---- headers ---------------------------------------------------------------------------------
    for type_, base in [("QUEUE_ADD", add), ("QUEUE_REMOVE", remove), ("QUEUE_MOVE", move)]:
        slug = type_.lower().replace("_", "-")
        for field in ["command_seq", "queue_revision"]:
            bad(f"{slug}-missing-{field}", type_, {k: v for k, v in base.items() if k != field}, "MISSING_FIELD")
            bad(f"{slug}-wrong-type-{field}", type_, {**base, field: "12"}, "WRONG_FIELD_TYPE")
        bad(f"{slug}-negative-command-seq", type_, {**base, "command_seq": -1}, "COMMAND_SEQ_OUT_OF_RANGE")
        bad(f"{slug}-command-seq-past-max", type_, {**base, "command_seq": MAX_WIRE_INT + 1}, "COMMAND_SEQ_OUT_OF_RANGE")
        bad(f"{slug}-negative-queue-revision", type_, {**base, "queue_revision": -1}, "REVISION_OUT_OF_RANGE")
        bad(f"{slug}-queue-revision-past-max", type_, {**base, "queue_revision": MAX_WIRE_INT + 1}, "REVISION_OUT_OF_RANGE")

    # ---- QUEUE_ADD items --------------------------------------------------------------------------
    bad("queue-add-missing-items", "QUEUE_ADD", {k: v for k, v in add.items() if k != "items"}, "MISSING_FIELD")
    bad("queue-add-items-wrong-type", "QUEUE_ADD", {**add, "items": {"a": 1}}, "WRONG_FIELD_TYPE")
    bad("queue-add-empty-items", "QUEUE_ADD", {**add, "items": []}, "EMPTY_ITEM_LIST")
    bad("queue-add-item-not-an-object", "QUEUE_ADD", {**add, "items": ["nope"]}, "WRONG_FIELD_TYPE")
    bad("queue-add-duplicate-item-ids-in-one-frame", "QUEUE_ADD",
        {**add, "items": [add_item, add_item]}, "DUPLICATE_QUEUE_ITEM_ID")
    for field in ["queue_item_id", "track_hash", "added_by", "position"]:
        bad(f"queue-add-item-missing-{field}", "QUEUE_ADD",
            {**add, "items": [{k: v for k, v in add_item.items() if k != field}]}, "MISSING_FIELD")
        bad(f"queue-add-item-wrong-type-{field}", "QUEUE_ADD",
            {**add, "items": [{**add_item, field: 7}]}, "WRONG_FIELD_TYPE")
    bad("queue-add-item-malformed-queue-item-id", "QUEUE_ADD",
        {**add, "items": [{**add_item, "queue_item_id": "nope"}]}, "MALFORMED_QUEUE_ITEM_ID")
    bad("queue-add-item-malformed-track-hash", "QUEUE_ADD",
        {**add, "items": [{**add_item, "track_hash": "sha256:zz"}]}, "MALFORMED_CONTENT_HASH")
    bad("queue-add-item-uppercase-track-hash", "QUEUE_ADD",
        {**add, "items": [{**add_item, "track_hash": "sha256:" + "1F3A" * 16}]}, "MALFORMED_CONTENT_HASH")
    bad("queue-add-item-malformed-added-by", "QUEUE_ADD",
        {**add, "items": [{**add_item, "added_by": "nope"}]}, "MALFORMED_PEER_ID")
    # An unrecognised `position` degrades to `end` rather than rejecting the frame — PROTOCOL §2
    # rule 2's forward-compatibility posture applied to a value rather than a type.
    ok("queue-add-item-unknown-position-degrades-to-end", "QUEUE_ADD",
       {**add, "items": [{**add_item, "position": "somewhere_else"}]}, add)

    # ---- QUEUE_REMOVE ids --------------------------------------------------------------------------
    bad("queue-remove-missing-ids", "QUEUE_REMOVE",
        {k: v for k, v in remove.items() if k != "queue_item_ids"}, "MISSING_FIELD")
    bad("queue-remove-ids-wrong-type", "QUEUE_REMOVE", {**remove, "queue_item_ids": ITEM_A}, "WRONG_FIELD_TYPE")
    bad("queue-remove-empty-ids", "QUEUE_REMOVE", {**remove, "queue_item_ids": []}, "EMPTY_ITEM_LIST")
    bad("queue-remove-id-not-a-string", "QUEUE_REMOVE", {**remove, "queue_item_ids": [1]}, "WRONG_FIELD_TYPE")
    bad("queue-remove-malformed-id", "QUEUE_REMOVE", {**remove, "queue_item_ids": ["nope"]}, "MALFORMED_QUEUE_ITEM_ID")
    # The same id twice is a legitimate frame — the *reducer* makes the second a no-op (queue/), and
    # the codec has no business rejecting it.
    ok("queue-remove-same-id-twice-parses", "QUEUE_REMOVE",
       {**remove, "queue_item_ids": [ITEM_A, ITEM_A]}, {**remove, "queue_item_ids": [ITEM_A, ITEM_A]})

    # ---- QUEUE_MOVE ---------------------------------------------------------------------------------
    bad("queue-move-missing-queue-item-id", "QUEUE_MOVE",
        {k: v for k, v in move.items() if k != "queue_item_id"}, "MISSING_FIELD")
    bad("queue-move-malformed-queue-item-id", "QUEUE_MOVE", {**move, "queue_item_id": "nope"},
        "MALFORMED_QUEUE_ITEM_ID")
    bad("queue-move-missing-to-index", "QUEUE_MOVE", {k: v for k, v in move.items() if k != "to_index"},
        "MISSING_FIELD")
    bad("queue-move-to-index-wrong-type", "QUEUE_MOVE", {**move, "to_index": "2"}, "WRONG_FIELD_TYPE")
    bad("queue-move-negative-to-index", "QUEUE_MOVE", {**move, "to_index": -1}, "INDEX_OUT_OF_RANGE")
    ok("queue-move-to-index-zero", "QUEUE_MOVE", {**move, "to_index": 0}, {**move, "to_index": 0})
    ok("queue-move-to-last-allowed-index", "QUEUE_MOVE", {**move, "to_index": MAX_QUEUE_ITEMS - 1},
       {**move, "to_index": MAX_QUEUE_ITEMS - 1})
    bad("queue-move-to-index-at-cap", "QUEUE_MOVE", {**move, "to_index": MAX_QUEUE_ITEMS}, "INDEX_OUT_OF_RANGE")

    # ---- QUEUE_SNAPSHOT ------------------------------------------------------------------------------
    bad("queue-snapshot-missing-revision", "QUEUE_SNAPSHOT",
        {k: v for k, v in snapshot.items() if k != "queue_revision"}, "MISSING_FIELD")
    bad("queue-snapshot-missing-items", "QUEUE_SNAPSHOT",
        {k: v for k, v in snapshot.items() if k != "items"}, "MISSING_FIELD")
    bad("queue-snapshot-missing-current-index", "QUEUE_SNAPSHOT",
        {k: v for k, v in snapshot.items() if k != "current_index"}, "MISSING_FIELD")
    bad("queue-snapshot-current-index-wrong-type", "QUEUE_SNAPSHOT", {**snapshot, "current_index": "0"},
        "WRONG_FIELD_TYPE")
    bad("queue-snapshot-current-index-negative", "QUEUE_SNAPSHOT", {**snapshot, "current_index": -1},
        "INDEX_OUT_OF_RANGE")
    bad("queue-snapshot-current-index-past-item-count", "QUEUE_SNAPSHOT", {**snapshot, "current_index": 1},
        "INDEX_OUT_OF_RANGE")
    bad("queue-snapshot-current-index-set-on-empty-items", "QUEUE_SNAPSHOT",
        {"queue_revision": 1, "items": [], "current_index": 0}, "INDEX_OUT_OF_RANGE")
    bad("queue-snapshot-duplicate-item-ids", "QUEUE_SNAPSHOT",
        {**snapshot, "items": [snap_item, snap_item], "current_index": 0}, "DUPLICATE_QUEUE_ITEM_ID")
    for field in ["queue_item_id", "track_hash", "added_by", "order"]:
        bad(f"queue-snapshot-item-missing-{field}", "QUEUE_SNAPSHOT",
            {**snapshot, "items": [{k: v for k, v in snap_item.items() if k != field}]}, "MISSING_FIELD")
    bad("queue-snapshot-item-wrong-type-order", "QUEUE_SNAPSHOT",
        {**snapshot, "items": [{**snap_item, "order": "1024"}]}, "WRONG_FIELD_TYPE")
    bad("queue-snapshot-item-negative-order", "QUEUE_SNAPSHOT",
        {**snapshot, "items": [{**snap_item, "order": -1}]}, "ORDER_OUT_OF_RANGE")
    bad("queue-snapshot-item-order-past-max", "QUEUE_SNAPSHOT",
        {**snapshot, "items": [{**snap_item, "order": MAX_WIRE_INT + 1}]}, "ORDER_OUT_OF_RANGE")
    ok("queue-snapshot-item-order-at-max", "QUEUE_SNAPSHOT",
       {**snapshot, "items": [{**snap_item, "order": MAX_WIRE_INT}]},
       {**snapshot, "items": [{**snap_item, "order": MAX_WIRE_INT}]})

    return out


def main() -> None:
    generated = rows()
    payload = {
        "_comment": (
            "Field-level validation for PROTOCOL §9's queue messages. `status` appears in no shape "
            "here: §9 called it untrusted in the same paragraph that put it on the wire, and "
            "ADR-024 §6 removes it — a peer that still sends it is tolerated as an unknown field "
            "and the value is ignored. All identifiers are fabricated test values. Generated by "
            "tools/generate_queue_messages_vectors.py — an independent third transcription of §9. "
            "Edit the generator, never this file."
        ),
        "_invariants": [
            "No accepted row's `encoded` output contains a `status` field, whatever the input "
            "carried — the wire shape has none (ADR-024 §6).",
            "current_index is required but nullable: absent is MISSING_FIELD, explicit null is the "
            "representable 'nothing selected' state, and any value outside the item list is "
            "INDEX_OUT_OF_RANGE — including a set index on an empty snapshot.",
            "Two items with the same queue_item_id in one frame are rejected; the same track_hash "
            "under two different ids is accepted, because queue identity is the ULID.",
            "An unknown `position` value degrades to `end` and never rejects the frame.",
            "A QUEUE_REMOVE naming the same id twice PARSES — making the second a no-op is the "
            "reducer's job (protocol/vectors/queue/), not the codec's.",
        ],
        "bounds": {"max_wire_int": MAX_WIRE_INT, "max_queue_items": MAX_QUEUE_ITEMS},
        "rows": generated,
    }
    out = Path(__file__).resolve().parent.parent / "protocol" / "vectors" / "queue-messages"
    out.mkdir(parents=True, exist_ok=True)
    target = out / "queue_messages_vectors.json"
    target.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {target} ({len(generated)} rows)")


if __name__ == "__main__":
    main()
