#!/usr/bin/env python3
"""Generate protocol/vectors/manifest/manifest_vectors.json.

`protocol/README.md`'s vector table already names this directory: "two manifests ⇒ expected
presence classification and delta." Two related, purely local computations that sit above the
wire format proper:

1. **Presence classification** — given the set of content_hash values this phone has locally and
   the set a connected peer's manifest advertises, classify each hash as `LOCAL_ONLY`, `PEER_ONLY`
   or `BOTH` (ARCHITECTURE §8.2's static half; the transfer-in-progress states live in
   `transfer-fsm/` instead, since they come from the transfer state machine, not from set
   membership).
2. **Manifest delta** — given an old and a new full manifest snapshot, the `added` entries and
   `removed` content_hash values a sender would place in a `kind: "delta"` `MANIFEST_BEGIN`/
   `MANIFEST_PAGE` sequence (PROTOCOL §8.1).

Independent third transcription, not a port of either platform's presence/delta computation. Edit
this generator, never the JSON.

Run:  python3 tools/generate_manifest_vectors.py
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path


def ch(seed: str) -> str:
    return "sha256:" + hashlib.sha256(f"content:{seed}".encode()).hexdigest()


def entry(seed: str, **overrides) -> dict:
    e = {
        "content_hash": ch(seed),
        "quick_id": "sha256:" + hashlib.sha256(f"quick:{seed}".encode()).hexdigest(),
        "title": overrides.pop("title", f"Track {seed}"),
    }
    e.update(overrides)
    return e


def classify(local_hashes: set[str], peer_hashes: set[str]) -> dict:
    all_hashes = sorted(local_hashes | peer_hashes)
    result = {}
    for h in all_hashes:
        in_local, in_peer = h in local_hashes, h in peer_hashes
        if in_local and in_peer:
            result[h] = "BOTH"
        elif in_local:
            result[h] = "LOCAL_ONLY"
        else:
            result[h] = "PEER_ONLY"
    return result


def presence_row(name: str, local_seeds: list[str], peer_seeds: list[str]) -> dict:
    local_hashes = {ch(s) for s in local_seeds}
    peer_hashes = {ch(s) for s in peer_seeds}
    return {
        "name": name,
        "local_content_hashes": sorted(local_hashes),
        "peer_content_hashes": sorted(peer_hashes),
        "expect": {"presence_by_content_hash": classify(local_hashes, peer_hashes)},
    }


def delta(old: list[dict], new: list[dict]) -> dict:
    old_by_hash = {e["content_hash"]: e for e in old}
    new_by_hash = {e["content_hash"]: e for e in new}
    added = [e for h, e in new_by_hash.items() if h not in old_by_hash]
    removed = sorted(h for h in old_by_hash if h not in new_by_hash)
    return {"added": added, "removed": removed}


def delta_row(name: str, old: list[dict], new: list[dict]) -> dict:
    return {"name": name, "old_manifest": old, "new_manifest": new, "expect": delta(old, new)}


def build() -> list[dict]:
    rows: list[dict] = []

    rows.append(presence_row("both-empty", [], []))
    rows.append(presence_row("local-only-peer-has-nothing", ["a"], []))
    rows.append(presence_row("peer-only-local-has-nothing", [], ["a"]))
    rows.append(presence_row("both-sides-have-the-same-track", ["a"], ["a"]))
    rows.append(
        presence_row(
            "mixed-catalogue",
            local_seeds=["a", "b", "shared1", "shared2"],
            peer_seeds=["shared1", "shared2", "c"],
        )
    )
    rows.append(
        presence_row(
            "same-quickid-different-content-hash-are-independently-classified",
            local_seeds=["variantA"],
            peer_seeds=["variantB"],
        )
    )

    e_a, e_b, e_c = entry("a"), entry("b"), entry("c")
    rows.append(delta_row("delta-no-change", [e_a, e_b], [e_a, e_b]))
    rows.append(delta_row("delta-one-added", [e_a], [e_a, e_b]))
    rows.append(delta_row("delta-one-removed", [e_a, e_b], [e_a]))
    rows.append(delta_row("delta-one-added-one-removed", [e_a, e_b], [e_a, e_c]))
    rows.append(delta_row("delta-from-empty-is-a-full-add", [], [e_a, e_b]))
    rows.append(delta_row("delta-to-empty-removes-everything", [e_a, e_b], []))
    e_a_retitled = entry("a", title="Retitled Track A")
    rows.append(
        {
            "name": "delta-metadata-only-change-same-content-hash-is-not-added-or-removed",
            "old_manifest": [e_a],
            "new_manifest": [e_a_retitled],
            "expect": delta([e_a], [e_a_retitled]),
            "_note": "content_hash is unchanged (same bytes), so this is neither an add nor a "
            "remove even though title differs — content_hash equality is the only identity that "
            "matters here (ADR-005).",
        }
    )

    return rows


def main() -> None:
    rows = build()
    names = [r["name"] for r in rows]
    assert len(names) == len(set(names)), "duplicate row names"
    payload = {
        "_comment": (
            "Presence classification (LOCAL_ONLY/PEER_ONLY/BOTH, ARCHITECTURE §8.2) and manifest "
            "delta computation (PROTOCOL §8.1), both keyed on content_hash equality only — never "
            "quick_id (ADR-005 Amendment A1). Both platforms' availability/delta logic runs this "
            "same file. Generated by tools/generate_manifest_vectors.py — an independent third "
            "transcription of the spec. Edit the generator, never this file."
        ),
        "_invariants": [
            "Classification and delta are computed on content_hash sets only; quick_id never "
            "participates, so two entries sharing a quick_id but differing in content_hash are "
            "always classified/diffed independently.",
            "A metadata-only change (same content_hash, different title/artist/etc.) is neither "
            "an add nor a remove in a delta — content_hash is the only identity that matters.",
        ],
        "rows": rows,
    }
    out = Path(__file__).resolve().parent.parent / "protocol" / "vectors" / "manifest"
    out.mkdir(parents=True, exist_ok=True)
    target = out / "manifest_vectors.json"
    target.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {target} ({len(rows)} rows)")


if __name__ == "__main__":
    main()
