#!/usr/bin/env python3
"""Generate protocol/vectors/queue/queue_vectors.json.

PROTOCOL §9's queue algebra (ADR-024 §5/§6): the leader applies mutations and owns
`queue_revision`; a follower only ever adopts a `QUEUE_SNAPSHOT` wholesale. "The snapshot always
wins — there is no merge algorithm to get subtly wrong" is §9's own rule, taken literally, which is
why there is no CRDT anywhere in these vectors.

Rules transcribed here:
  - `queue_item_id` is a ULID minted by the issuer, so re-adding one already present is a **no-op**
    (idempotent under retry), never a second entry and never an error;
  - two genuinely separate adds of the same `track_hash` carry two different ids and correctly
    become two independently removable entries;
  - `order` is sparse in steps of 1024; an insert "next" that finds no gap renumbers;
  - removing the current item hands `current` to whatever now occupies its old position, or clears
    it if nothing does — identical to `LocalQueue`'s rule, deliberately;
  - a no-op mutation must NOT advance `queue_revision`;
  - `NEXT` past the last item stops, `PREVIOUS` at the first stays put — no wraparound in V1;
  - the queue is capped at 1 000 items (ADR-024 §6 corrects §9's arithmetically impossible 2 000).

An independent third transcription, not a port of either platform's `SharedQueue`. Edit this
generator, never the JSON.

Run:  python3 tools/generate_queue_vectors.py
"""

from __future__ import annotations

import json
from pathlib import Path

ORDER_STEP = 1024
MAX_ITEMS = 1000

# Fabricated identifiers only — never a real peer_id or a real file's hash.
PEER_A = "a3f1000000000001"
PEER_B = "b7c1000000000002"


def ulid(n: int) -> str:
    return f"01J9Z4M0Q7XK2V8R3T6Y1N{n:04d}"[:26].ljust(26, "0")


def hash_for(n: int) -> str:
    return "sha256:" + f"{n:064x}"


def add(state: dict, additions: list[dict]) -> dict:
    existing = {i["queue_item_id"] for i in state["items"]}
    fresh = [a for a in additions if a["queue_item_id"] not in existing]
    if not fresh:
        return {"state": state, "changed": False, "rejection": None}
    if len(state["items"]) + len(fresh) > MAX_ITEMS:
        return {"state": state, "changed": False, "rejection": "QUEUE_FULL"}
    working = state
    for item in fresh:
        working = insert(working, item)
    return {"state": {**working, "revision": state["revision"] + 1}, "changed": True, "rejection": None}


def current_index(state: dict) -> int | None:
    if state["current_item_id"] is None:
        return None
    for index, item in enumerate(state["items"]):
        if item["queue_item_id"] == state["current_item_id"]:
            return index
    return None


def insert(state: dict, addition: dict) -> dict:
    items = state["items"]
    index = current_index(state)
    if addition["position"] != "next" or index is None:
        order = (items[-1]["order"] if items else 0) + ORDER_STEP
        entry = {
            "queue_item_id": addition["queue_item_id"],
            "track_hash": addition["track_hash"],
            "added_by": addition["added_by"],
            "order": order,
        }
        return {**state, "items": items + [entry]}
    current = items[index]
    if index + 1 >= len(items):
        entry = {
            "queue_item_id": addition["queue_item_id"],
            "track_hash": addition["track_hash"],
            "added_by": addition["added_by"],
            "order": current["order"] + ORDER_STEP,
        }
        return {**state, "items": items + [entry]}
    following = items[index + 1]
    if following["order"] - current["order"] < 2:
        renumbered = [{**item, "order": (i + 1) * ORDER_STEP} for i, item in enumerate(items)]
        entry = {
            "queue_item_id": addition["queue_item_id"],
            "track_hash": addition["track_hash"],
            "added_by": addition["added_by"],
            "order": renumbered[index]["order"] + ORDER_STEP // 2,
        }
        return {**state, "items": sorted(renumbered + [entry], key=lambda i: i["order"])}
    entry = {
        "queue_item_id": addition["queue_item_id"],
        "track_hash": addition["track_hash"],
        "added_by": addition["added_by"],
        "order": current["order"] + (following["order"] - current["order"]) // 2,
    }
    return {**state, "items": sorted(items + [entry], key=lambda i: i["order"])}


def remove(state: dict, ids: list[str]) -> dict:
    doomed = set(ids)
    removed_index = None
    for index, item in enumerate(state["items"]):
        if item["queue_item_id"] in doomed and item["queue_item_id"] == state["current_item_id"]:
            removed_index = index
            break
    remaining = [i for i in state["items"] if i["queue_item_id"] not in doomed]
    if len(remaining) == len(state["items"]):
        return {"state": state, "changed": False, "rejection": None}
    current_item_id = state["current_item_id"]
    if removed_index is not None:
        current_item_id = remaining[removed_index]["queue_item_id"] if removed_index < len(remaining) else None
    return {
        "state": {**state, "items": remaining, "current_item_id": current_item_id, "revision": state["revision"] + 1},
        "changed": True,
        "rejection": None,
    }


def move(state: dict, queue_item_id: str, to_index: int) -> dict:
    from_index = next((i for i, item in enumerate(state["items"]) if item["queue_item_id"] == queue_item_id), None)
    if from_index is None:
        return {"state": state, "changed": False, "rejection": None}
    target = min(max(to_index, 0), len(state["items"]) - 1)
    if target == from_index:
        return {"state": state, "changed": False, "rejection": None}
    mutable = list(state["items"])
    item = mutable.pop(from_index)
    mutable.insert(target, item)
    renumbered = [{**entry, "order": (i + 1) * ORDER_STEP} for i, entry in enumerate(mutable)]
    return {"state": {**state, "items": renumbered, "revision": state["revision"] + 1}, "changed": True, "rejection": None}


def apply(state: dict, mutation: dict) -> dict:
    if mutation["kind"] == "Add":
        return add(state, mutation["items"])
    if mutation["kind"] == "Remove":
        return remove(state, mutation["queue_item_ids"])
    return move(state, mutation["queue_item_id"], mutation["to_index"])


def step(state: dict, delta: int) -> dict:
    index = current_index(state)
    if index is None:
        if delta > 0 and state["items"]:
            first = state["items"][0]
            return {"state": {**state, "current_item_id": first["queue_item_id"]}, "selected": first, "moved": True}
        return {"state": state, "selected": None, "moved": False}
    target_index = index + delta
    if 0 <= target_index < len(state["items"]):
        target = state["items"][target_index]
        return {"state": {**state, "current_item_id": target["queue_item_id"]}, "selected": target, "moved": True}
    if delta > 0:
        return {"state": {**state, "current_item_id": None}, "selected": None, "moved": True}
    return {"state": state, "selected": state["items"][index], "moved": False}


def empty() -> dict:
    return {"items": [], "current_item_id": None, "revision": 0}


def addition(n: int, position: str = "end", peer: str = PEER_A, track: int | None = None) -> dict:
    return {
        "queue_item_id": ulid(n),
        "track_hash": hash_for(n if track is None else track),
        "added_by": peer,
        "position": position,
    }


def scenarios() -> list[dict]:
    """Whole mutation sequences, because a queue bug is almost always a sequence bug."""
    out: list[dict] = []

    def scenario(name: str, ops: list[dict], start: dict | None = None):
        state = start or empty()
        steps = []
        for op in ops:
            if op["kind"] in ("Add", "Remove", "Move"):
                result = apply(state, op)
                state = result["state"]
                steps.append({"op": op, "changed": result["changed"], "rejection": result["rejection"], "state_after": state})
            elif op["kind"] in ("Next", "Previous"):
                result = step(state, 1 if op["kind"] == "Next" else -1)
                state = result["state"]
                steps.append({"op": op, "selected": result["selected"], "moved": result["moved"], "state_after": state})
            elif op["kind"] == "Select":
                target = next((i for i in state["items"] if i["queue_item_id"] == op["queue_item_id"]), None)
                if target is not None:
                    state = {**state, "current_item_id": op["queue_item_id"]}
                steps.append({"op": op, "state_after": state})
        out.append({"name": name, "steps": steps})

    scenario("empty-queue-next-and-previous", [{"kind": "Next"}, {"kind": "Previous"}])

    scenario("add-three-sparse-orders", [{"kind": "Add", "items": [addition(1), addition(2), addition(3)]}])

    scenario(
        "duplicate-track-added-twice-is-two-entries",
        [{"kind": "Add", "items": [addition(1, track=1), addition(2, track=1)]}],
    )

    scenario(
        "re-adding-the-same-queue-item-id-is-idempotent",
        [
            {"kind": "Add", "items": [addition(1)]},
            {"kind": "Add", "items": [addition(1)]},
        ],
    )

    scenario(
        "partially-duplicated-add-applies-only-the-fresh-item",
        [
            {"kind": "Add", "items": [addition(1), addition(2)]},
            {"kind": "Add", "items": [addition(2), addition(3)]},
        ],
    )

    scenario(
        "remove-a-non-current-item",
        [
            {"kind": "Add", "items": [addition(1), addition(2), addition(3)]},
            {"kind": "Select", "queue_item_id": ulid(1)},
            {"kind": "Remove", "queue_item_ids": [ulid(3)]},
        ],
    )

    scenario(
        "remove-the-current-item-hands-to-its-successor",
        [
            {"kind": "Add", "items": [addition(1), addition(2), addition(3)]},
            {"kind": "Select", "queue_item_id": ulid(2)},
            {"kind": "Remove", "queue_item_ids": [ulid(2)]},
        ],
    )

    scenario(
        "remove-the-last-current-item-clears-current",
        [
            {"kind": "Add", "items": [addition(1)]},
            {"kind": "Select", "queue_item_id": ulid(1)},
            {"kind": "Remove", "queue_item_ids": [ulid(1)]},
        ],
    )

    scenario(
        "remove-the-same-item-twice-second-is-a-no-op",
        [
            {"kind": "Add", "items": [addition(1), addition(2)]},
            {"kind": "Remove", "queue_item_ids": [ulid(1)]},
            {"kind": "Remove", "queue_item_ids": [ulid(1)]},
        ],
    )

    scenario(
        "move-a-removed-item-is-a-no-op",
        [
            {"kind": "Add", "items": [addition(1), addition(2)]},
            {"kind": "Remove", "queue_item_ids": [ulid(1)]},
            {"kind": "Move", "queue_item_id": ulid(1), "to_index": 0},
        ],
    )

    scenario(
        "move-renumbers-deterministically",
        [
            {"kind": "Add", "items": [addition(1), addition(2), addition(3)]},
            {"kind": "Move", "queue_item_id": ulid(3), "to_index": 0},
        ],
    )

    scenario(
        "move-to-the-same-index-is-a-no-op",
        [
            {"kind": "Add", "items": [addition(1), addition(2)]},
            {"kind": "Move", "queue_item_id": ulid(1), "to_index": 0},
        ],
    )

    scenario(
        "move-index-is-clamped",
        [
            {"kind": "Add", "items": [addition(1), addition(2)]},
            {"kind": "Move", "queue_item_id": ulid(1), "to_index": 99},
        ],
    )

    scenario(
        "insert-next-lands-after-current",
        [
            {"kind": "Add", "items": [addition(1), addition(2)]},
            {"kind": "Select", "queue_item_id": ulid(1)},
            {"kind": "Add", "items": [addition(3, position="next", peer=PEER_B)]},
        ],
    )

    # The sparse gap halves on every "next" insert after the current item: 1024, 512, 256, ... The
    # eleventh insert is the first to find a gap below 2 and must renumber the whole list. Written
    # out rather than looped so the vector file shows every intermediate order.
    scenario(
        "insert-next-with-no-gap-renumbers",
        [
            {"kind": "Add", "items": [addition(1), addition(2)]},
            {"kind": "Select", "queue_item_id": ulid(1)},
        ]
        + [{"kind": "Add", "items": [addition(n, position="next")]} for n in range(3, 15)],
    )

    scenario(
        "insert-next-when-current-is-last-appends",
        [
            {"kind": "Add", "items": [addition(1)]},
            {"kind": "Select", "queue_item_id": ulid(1)},
            {"kind": "Add", "items": [addition(2, position="next")]},
        ],
    )

    scenario(
        "next-walks-then-stops-past-the-end",
        [
            {"kind": "Add", "items": [addition(1), addition(2)]},
            {"kind": "Next"},
            {"kind": "Next"},
            {"kind": "Next"},
        ],
    )

    scenario(
        "previous-at-the-first-item-stays-put",
        [
            {"kind": "Add", "items": [addition(1), addition(2)]},
            {"kind": "Select", "queue_item_id": ulid(1)},
            {"kind": "Previous"},
        ],
    )

    scenario(
        "next-racing-a-remove-of-the-following-item",
        [
            {"kind": "Add", "items": [addition(1), addition(2), addition(3)]},
            {"kind": "Select", "queue_item_id": ulid(1)},
            {"kind": "Remove", "queue_item_ids": [ulid(2)]},
            {"kind": "Next"},
        ],
    )

    scenario(
        "leader-serialises-two-simultaneous-adds",
        # Both users press add "at once"; the leader's arrival order is the only thing that decides,
        # and both peers end at the same revision with the same order.
        [
            {"kind": "Add", "items": [addition(1, peer=PEER_A)]},
            {"kind": "Add", "items": [addition(2, peer=PEER_B)]},
        ],
    )

    scenario(
        "one-peer-moves-while-the-other-removes",
        [
            {"kind": "Add", "items": [addition(1), addition(2), addition(3)]},
            {"kind": "Move", "queue_item_id": ulid(1), "to_index": 2},
            {"kind": "Remove", "queue_item_ids": [ulid(2)]},
        ],
    )

    scenario(
        "remove-nothing-does-not-advance-the-revision",
        [
            {"kind": "Add", "items": [addition(1)]},
            {"kind": "Remove", "queue_item_ids": [ulid(99)]},
        ],
    )

    return out


def snapshot_rows() -> list[dict]:
    """A follower adopting a snapshot wholesale — no merge, no revision arithmetic."""
    items = [
        {"queue_item_id": ulid(7), "track_hash": hash_for(7), "added_by": PEER_B, "order": 4096},
        {"queue_item_id": ulid(5), "track_hash": hash_for(5), "added_by": PEER_A, "order": 1024},
        {"queue_item_id": ulid(6), "track_hash": hash_for(6), "added_by": PEER_A, "order": 2048},
    ]
    ordered = sorted(items, key=lambda i: i["order"])
    return [
        {
            "name": "snapshot-sorts-by-order-and-resolves-current-index",
            "input": {"revision": 13, "items": items, "current_index": 1},
            "expected": {"items": ordered, "current_item_id": ordered[1]["queue_item_id"], "revision": 13},
        },
        {
            "name": "snapshot-with-null-current-index",
            "input": {"revision": 14, "items": items, "current_index": None},
            "expected": {"items": ordered, "current_item_id": None, "revision": 14},
        },
        {
            "name": "empty-snapshot-clears-the-queue",
            "input": {"revision": 15, "items": [], "current_index": None},
            "expected": {"items": [], "current_item_id": None, "revision": 15},
        },
        {
            "name": "snapshot-revision-is-adopted-verbatim-not-incremented",
            "input": {"revision": 900, "items": [items[1]], "current_index": 0},
            "expected": {"items": [items[1]], "current_item_id": items[1]["queue_item_id"], "revision": 900},
        },
    ]


def cap_rows() -> list[dict]:
    """The 1 000-item cap (ADR-024 §6). Built programmatically rather than as 1 000 literal rows."""
    # Described by item counts rather than 1 000 literal rows: both runners build the starting queue
    # themselves from `item_count`, so the vector file stays readable and the cap stays exact.
    return [
        {
            "name": "add-that-would-exceed-the-cap-is-rejected",
            "input": {"item_count": MAX_ITEMS, "adding": 1},
            "expected": {"changed": False, "rejection": "QUEUE_FULL", "revision": 0},
        },
        {
            "name": "add-that-exactly-reaches-the-cap-is-accepted",
            "input": {"item_count": MAX_ITEMS - 1, "adding": 1},
            "expected": {"changed": True, "rejection": None, "revision": 1},
        },
    ]


def main() -> None:
    payload = {
        "_comment": (
            "PROTOCOL §9's queue algebra as whole mutation sequences: the leader applies and owns "
            "queue_revision, a follower adopts a QUEUE_SNAPSHOT wholesale. Identifiers are "
            "fabricated test values; no real peer_id and no real file's hash appears here. "
            "Generated by tools/generate_queue_vectors.py — an independent third transcription of "
            "§9. Edit the generator, never this file."
        ),
        "_invariants": [
            "A no-op mutation (re-add of a present id, remove of an absent id, move to the same "
            "index) leaves queue_revision unchanged. A revision that moved without the queue moving "
            "would desynchronise the peers for no reason.",
            "queue_revision advances by exactly one per accepted mutation, never by more.",
            "The same track_hash may appear under two different queue_item_ids and the two entries "
            "are independently removable — queue identity is the ULID, never the content hash and "
            "never quick_id.",
            "Removing the current item selects whatever now occupies its old index, or clears "
            "current if nothing does — identical to LocalQueue's rule.",
            "NEXT past the last item clears current (playback stops); PREVIOUS at the first item "
            "does not move. No wraparound in V1.",
            "A snapshot's revision is adopted verbatim: a follower never increments it itself.",
        ],
        "constants": {"order_step": ORDER_STEP, "max_queue_items": MAX_ITEMS},
        "scenarios": scenarios(),
        "snapshots": snapshot_rows(),
        "cap": cap_rows(),
    }
    out = Path(__file__).resolve().parent.parent / "protocol" / "vectors" / "queue"
    out.mkdir(parents=True, exist_ok=True)
    target = out / "queue_vectors.json"
    target.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    steps = sum(len(s["steps"]) for s in payload["scenarios"])
    print(f"wrote {target} ({len(payload['scenarios'])} scenarios, {steps} steps, "
          f"{len(payload['snapshots'])} snapshots, {len(payload['cap'])} cap rows)")


if __name__ == "__main__":
    main()
