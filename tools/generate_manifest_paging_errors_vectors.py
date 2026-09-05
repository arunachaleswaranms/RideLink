#!/usr/bin/env python3
"""Generate protocol/vectors/manifest-paging-errors/manifest_paging_errors_vectors.json.

PROTOCOL §8.1 "Receiver rules" / ADR-013 — the receiver-side manifest-sync state machine:
idle -> staging -> validating -> committed, and the twelve-plus ways an in-progress
synchronisation must abort **without ever promoting anything partial**.

Same discipline as `generate_manifest_paging_vectors.py`: an independent third transcription of
the spec, with a small reference reducer here purely to derive expected outcomes deterministically
— not a port of either platform's `ManifestSyncStateMachine`. Edit this generator, never the JSON.

Run:  python3 tools/generate_manifest_paging_errors_vectors.py
"""

from __future__ import annotations

import json
from pathlib import Path

# --- a minimal reference reducer, transcribed from PROTOCOL §8.1 rules 1-10 --------------------


class Aborted(Exception):
    def __init__(self, error: str) -> None:
        super().__init__(error)
        self.error = error


def run_sync(previous_revision: int, events: list[dict]) -> dict:
    """Returns {"error": str|None, "committed": bool, "final_revision": int}.

    `previous_revision` stands in for "the manifest_revision already in force before this sync
    began" — PROTOCOL §8.1 rule 1: staging never becomes live except on full MANIFEST_END
    validation, so on any abort the previous revision is what remains in force.
    """
    open_sync: dict | None = None  # {manifest_id, kind, revision, base_revision, expected_page, staged_entries, staged_removed, total_entries, total_removed}
    error: str | None = None
    committed = False
    final_revision = previous_revision

    def abort_current() -> None:
        nonlocal open_sync
        open_sync = None

    for ev in events:
        kind = ev["kind"]
        try:
            if kind == "Begin":
                # Rule 8: a new Begin implicitly aborts any open sync.
                abort_current()
                if ev["kind_field"] == "delta" and ev["base_revision"] != previous_revision:
                    # Wrong base_revision: PROTOCOL §8.1 Begin table row.
                    raise Aborted("manifest_sequence_error")
                open_sync = {
                    "manifest_id": ev["manifest_id"],
                    "kind_field": ev["kind_field"],
                    "revision": ev["manifest_revision"],
                    "expected_page": 0,
                    "staged_entries": [],
                    "staged_removed": [],
                    "total_entries": ev["total_entries"],
                    "total_removed": ev["total_removed"],
                }
            elif kind == "Page":
                if open_sync is None:
                    raise Aborted("manifest_sequence_error")
                if ev["manifest_id"] != open_sync["manifest_id"] or ev["manifest_revision"] != open_sync["revision"]:
                    raise Aborted("manifest_sequence_error")
                if ev["page_index"] != open_sync["expected_page"]:
                    raise Aborted("manifest_sequence_error")
                if open_sync["kind_field"] == "full" and ev.get("removed"):
                    raise Aborted("manifest_sequence_error")
                open_sync["staged_entries"].extend(ev["entries"])
                open_sync["staged_removed"].extend(ev.get("removed", []))
                open_sync["expected_page"] += 1
            elif kind == "End":
                if open_sync is None:
                    raise Aborted("manifest_sequence_error")
                if ev["manifest_id"] != open_sync["manifest_id"] or ev["manifest_revision"] != open_sync["revision"]:
                    raise Aborted("manifest_sequence_error")
                if ev["page_count"] != open_sync["expected_page"]:
                    raise Aborted("manifest_sequence_error")
                if (
                    ev["total_entries"] != open_sync["total_entries"]
                    or ev["total_removed"] != open_sync["total_removed"]
                    or len(open_sync["staged_entries"]) != open_sync["total_entries"]
                    or len(open_sync["staged_removed"]) != open_sync["total_removed"]
                ):
                    raise Aborted("manifest_sequence_error")
                if ev["digest"] != ev["expected_digest"]:
                    raise Aborted("manifest_digest_mismatch")
                committed = True
                final_revision = open_sync["revision"]
                open_sync = None
            elif kind == "Abort":
                if open_sync is not None and ev["manifest_id"] == open_sync["manifest_id"]:
                    abort_current()
                # An abort for a manifest_id that isn't the open one is simply ignored (stale).
            elif kind == "Timeout":
                if open_sync is not None:
                    raise Aborted("manifest_incomplete")
            elif kind == "ControlLinkLost":
                abort_current()  # rule 7: staging is in-memory/session-scoped only.
            else:
                raise AssertionError(f"unknown event kind {kind}")
            error = None  # this event was handled without a fresh abort
        except Aborted as a:
            error = a.error
            open_sync = None
            # Processing continues after an abort — a later Begin in the same row's event list
            # models "retry", and its own outcome (committed or aborted again) is what the row's
            # final `expect` describes. `error` reflects only the most recent event.

    return {"error": error, "committed": committed, "final_revision": final_revision}


# --- fixture builders ---------------------------------------------------------------------------

MID = "01J9Z4M3RT8V2W5X7Y9Z1A3B5C"
OTHER_MID = "01J9Z4M3RT8V2W5X7Y9Z1A3B5D"


def begin(*, kind_field="full", manifest_id=MID, manifest_revision=7, base_revision=None, total_entries=1, total_removed=0) -> dict:
    return {
        "kind": "Begin",
        "manifest_id": manifest_id,
        "kind_field": kind_field,
        "manifest_revision": manifest_revision,
        "base_revision": base_revision,
        "total_entries": total_entries,
        "total_removed": total_removed,
    }


def page(*, manifest_id=MID, manifest_revision=7, page_index, entries=None, removed=None) -> dict:
    return {
        "kind": "Page",
        "manifest_id": manifest_id,
        "manifest_revision": manifest_revision,
        "page_index": page_index,
        "entries": entries if entries is not None else [{"content_hash": None, "quick_id": f"sha256:{'a' * 64}"}],
        "removed": removed or [],
    }


def end(*, manifest_id=MID, manifest_revision=7, page_count, total_entries=1, total_removed=0, digest="sha256:correct", expected_digest="sha256:correct") -> dict:
    return {
        "kind": "End",
        "manifest_id": manifest_id,
        "manifest_revision": manifest_revision,
        "page_count": page_count,
        "total_entries": total_entries,
        "total_removed": total_removed,
        "digest": digest,
        "expected_digest": expected_digest,
    }


def row(name: str, previous_revision: int, events: list[dict], expect: dict, note: str = "") -> dict:
    result = run_sync(previous_revision, events)
    assert result == expect, f"{name}: computed {result} != declared expect {expect}"
    out = {"name": name, "previous_revision": previous_revision, "events": events, "expect": expect}
    if note:
        out["_note"] = note
    return out


def build() -> list[dict]:
    rows: list[dict] = []
    ONE_PAGE_ENTRIES = [page(page_index=0)]

    rows.append(
        row(
            "happy-path-single-page-commits",
            previous_revision=6,
            events=[begin(), *ONE_PAGE_ENTRIES, end(page_count=1)],
            expect={"error": None, "committed": True, "final_revision": 7},
        )
    )

    # 1. Gap in page_index
    rows.append(
        row(
            "gap-in-page-index-aborts-and-keeps-previous",
            previous_revision=6,
            events=[begin(total_entries=2), page(page_index=0), page(page_index=2, entries=[{"content_hash": None, "quick_id": "sha256:" + "b" * 64}])],
            expect={"error": "manifest_sequence_error", "committed": False, "final_revision": 6},
        )
    )
    # 2. Duplicated page_index
    rows.append(
        row(
            "duplicated-page-index-aborts",
            previous_revision=6,
            events=[begin(), page(page_index=0), page(page_index=0)],
            expect={"error": "manifest_sequence_error", "committed": False, "final_revision": 6},
        )
    )
    # 3. Reordered page_index
    rows.append(
        row(
            "reordered-page-index-aborts",
            previous_revision=6,
            events=[begin(total_entries=2), page(page_index=1), page(page_index=0)],
            expect={"error": "manifest_sequence_error", "committed": False, "final_revision": 6},
        )
    )
    # 4. Wrong manifest_id on a page
    rows.append(
        row(
            "wrong-manifest-id-on-page-aborts",
            previous_revision=6,
            events=[begin(), page(manifest_id=OTHER_MID, page_index=0)],
            expect={"error": "manifest_sequence_error", "committed": False, "final_revision": 6},
        )
    )
    # 5. Wrong manifest_id on END
    rows.append(
        row(
            "wrong-manifest-id-on-end-aborts",
            previous_revision=6,
            events=[begin(), page(page_index=0), end(manifest_id=OTHER_MID, page_count=1)],
            expect={"error": "manifest_sequence_error", "committed": False, "final_revision": 6},
        )
    )
    # 6. Revision differs on a page from what BEGIN declared
    rows.append(
        row(
            "revision-mismatch-on-page-aborts",
            previous_revision=6,
            events=[begin(manifest_revision=7), page(manifest_revision=8, page_index=0)],
            expect={"error": "manifest_sequence_error", "committed": False, "final_revision": 6},
        )
    )
    # 7. Wrong base_revision for a delta
    rows.append(
        row(
            "wrong-base-revision-for-delta-aborts",
            previous_revision=6,
            events=[begin(kind_field="delta", base_revision=5, manifest_revision=7)],
            expect={"error": "manifest_sequence_error", "committed": False, "final_revision": 6},
            note="Receiver's current revision is 6; the delta claims to apply on top of 5.",
        )
    )
    # 8. total_entries mismatch at END
    rows.append(
        row(
            "total-entries-mismatch-at-end-aborts",
            previous_revision=6,
            events=[begin(total_entries=2), page(page_index=0), end(page_count=1, total_entries=2)],
            expect={"error": "manifest_sequence_error", "committed": False, "final_revision": 6},
            note="BEGIN promised 2 entries; only 1 page containing 1 entry was ever staged.",
        )
    )
    # 9. total_removed mismatch at END
    rows.append(
        row(
            "total-removed-mismatch-at-end-aborts",
            previous_revision=6,
            events=[
                begin(kind_field="delta", base_revision=6, total_entries=1, total_removed=1),
                page(page_index=0, removed=[]),
                end(page_count=1, total_entries=1, total_removed=1),
            ],
            expect={"error": "manifest_sequence_error", "committed": False, "final_revision": 6},
        )
    )
    # 10. Digest mismatch at END
    rows.append(
        row(
            "digest-mismatch-at-end-aborts",
            previous_revision=6,
            events=[begin(), page(page_index=0), end(page_count=1, digest="sha256:wrong", expected_digest="sha256:correct")],
            expect={"error": "manifest_digest_mismatch", "committed": False, "final_revision": 6},
        )
    )
    # 11. Timeout mid-sync (retry is a fresh Begin at the transport layer, out of this reducer's scope)
    rows.append(
        row(
            "page-timeout-aborts-and-keeps-previous",
            previous_revision=6,
            events=[begin(total_entries=2), page(page_index=0), {"kind": "Timeout"}],
            expect={"error": "manifest_incomplete", "committed": False, "final_revision": 6},
        )
    )
    rows.append(
        row(
            "retry-after-timeout-succeeds",
            previous_revision=6,
            events=[begin(total_entries=2), page(page_index=0), {"kind": "Timeout"}, begin(total_entries=1), page(page_index=0), end(page_count=1)],
            expect={"error": None, "committed": True, "final_revision": 7},
            note="A fresh Begin after the timeout starts an entirely new sync; the first one's partial state contributes nothing.",
        )
    )
    # 12. removed[] non-empty on a full manifest
    rows.append(
        row(
            "removed-nonempty-on-full-manifest-aborts",
            previous_revision=6,
            events=[begin(kind_field="full"), page(page_index=0, removed=["sha256:" + "c" * 64])],
            expect={"error": "manifest_sequence_error", "committed": False, "final_revision": 6},
        )
    )
    # Bonus: concurrent Begin implicitly aborts the prior one and its own outcome is what counts.
    rows.append(
        row(
            "concurrent-begin-aborts-prior-sync",
            previous_revision=6,
            events=[begin(manifest_revision=7), page(page_index=0), begin(manifest_id=OTHER_MID, manifest_revision=9, total_entries=1), page(manifest_id=OTHER_MID, manifest_revision=9, page_index=0), end(manifest_id=OTHER_MID, manifest_revision=9, page_count=1)],
            expect={"error": None, "committed": True, "final_revision": 9},
            note="The first sync (revision 7) never reaches END; only the second, superseding one commits.",
        )
    )
    # Bonus: MANIFEST_ABORT from the sender, one row per named reason.
    for reason in ("library_changed", "page_oversize", "cancelled", "internal"):
        rows.append(
            row(
                f"sender-abort-{reason}-discards-staging",
                previous_revision=6,
                events=[begin(total_entries=1), page(page_index=0), {"kind": "Abort", "manifest_id": MID, "reason": reason}],
                expect={"error": None, "committed": False, "final_revision": 6},
                note="MANIFEST_ABORT is not itself an error condition: the receiver discards staging silently and keeps the previous manifest.",
            )
        )
    # Bonus: control-plane loss mid-sync discards staging without persisting anything.
    rows.append(
        row(
            "control-link-loss-mid-sync-discards-staging",
            previous_revision=6,
            events=[begin(total_entries=1), page(page_index=0), {"kind": "ControlLinkLost"}],
            expect={"error": None, "committed": False, "final_revision": 6},
        )
    )
    # Bonus: a stale abort for a manifest_id that's already superseded is simply ignored.
    rows.append(
        row(
            "abort-for-already-superseded-manifest-id-is-ignored",
            previous_revision=6,
            events=[
                begin(manifest_revision=7),
                page(page_index=0),
                begin(manifest_id=OTHER_MID, manifest_revision=9, total_entries=1),
                {"kind": "Abort", "manifest_id": MID, "reason": "cancelled"},
                page(manifest_id=OTHER_MID, manifest_revision=9, page_index=0),
                end(manifest_id=OTHER_MID, manifest_revision=9, page_count=1),
            ],
            expect={"error": None, "committed": True, "final_revision": 9},
        )
    )

    return rows


def main() -> None:
    rows = build()
    names = [r["name"] for r in rows]
    assert len(names) == len(set(names)), "duplicate row names"
    payload = {
        "_comment": (
            "PROTOCOL §8.1 receiver rules / ADR-013 — the manifest-sync state machine's failure "
            "modes. Both platforms' receiver runs this same file, so a sequencing bug that would "
            "otherwise show up as 'catalogue silently wrong on one phone' is a laptop unit-test "
            "failure instead. Generated by tools/generate_manifest_paging_errors_vectors.py — an "
            "independent third transcription of the spec. Edit the generator, never this file."
        ),
        "_invariants": [
            "No row may have committed: true together with a non-null error.",
            "Every aborted row's final_revision equals previous_revision: nothing partial is ever "
            "promoted, exactly ADR-013's rule 5.",
            "A concurrent Begin implicitly aborts whatever sync was open, without itself being an "
            "error for the new sync.",
            "MANIFEST_ABORT and a control-link loss both discard staging silently — committed: "
            "false, error: null — because neither is a receiver-side fault.",
        ],
        "rows": rows,
    }
    out = Path(__file__).resolve().parent.parent / "protocol" / "vectors" / "manifest-paging-errors"
    out.mkdir(parents=True, exist_ok=True)
    target = out / "manifest_paging_errors_vectors.json"
    target.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {target} ({len(rows)} rows)")


if __name__ == "__main__":
    main()
