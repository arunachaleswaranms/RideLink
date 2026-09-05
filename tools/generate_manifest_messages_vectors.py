#!/usr/bin/env python3
"""Generate protocol/vectors/manifest-messages/manifest_messages_vectors.json.

PROTOCOL §8.1 — the `MANIFEST_*` message layer: `MANIFEST_REQUEST`, `MANIFEST_BEGIN`,
`MANIFEST_PAGE`, `MANIFEST_END`, `MANIFEST_ABORT`. Every row is `(type, payload) -> parsed or
rejected`, the same shape as `generate_transfer_messages_vectors.py` and
`generate_voice_signal_vectors.py` — field validation for the message *envelope*, distinct from
`manifest-paging/` (page-assembly arithmetic) and `manifest-paging-errors/` (the receiver
sequencing state machine, which consumes already-parsed events). Independent third transcription,
not a port of either platform's `ManifestCodec`. Edit this generator, never the JSON.

Run:  python3 tools/generate_manifest_messages_vectors.py
"""

from __future__ import annotations

import json
from pathlib import Path

MAX_ENTRIES_PER_PAGE = 256
MAX_DISPLAY_FIELD_BYTES = 4096  # defensive parse-time bound; the 512-scalar clamp is a sender-side build rule (ADR-013), this is the receiver's DoS guard

VALID_MID = "01J9Z4M3RT8V2W5X7Y9Z1A3B5C"
VALID_HASH = "sha256:" + "1f" * 32
VALID_QID = "sha256:" + "77" * 32


def minimal_entry(**overrides) -> dict:
    e = {
        "content_hash": VALID_HASH,
        "quick_id": VALID_QID,
        "work_key": "artist|title|200",
        "title": "Title",
        "artist": "Artist",
        "album": "Album",
        "duration_ms": 200000,
        "codec": "mp3",
        "bitrate_kbps": 192,
        "size_bytes": 5000000,
        "filename": "t.mp3",
        "has_artwork": False,
    }
    e.update(overrides)
    return e


def row(name: str, type_: str, payload: dict, *, parsed: dict | None = None, rejected: str | None = None) -> dict:
    assert (parsed is None) != (rejected is None), f"{name}: exactly one of parsed/rejected"
    expect = {"parsed": parsed} if parsed is not None else {"rejected": rejected}
    return {"name": name, "type": type_, "payload": payload, "expect": expect}


def build() -> list[dict]:
    rows: list[dict] = []

    # --- MANIFEST_REQUEST -------------------------------------------------------------------------
    rows.append(row("request-full-sync-null-since-revision", "MANIFEST_REQUEST", {"since_revision": None, "max_page_bytes": 196608}, parsed={"kind": "Request", "since_revision": None, "max_page_bytes": 196608}))
    rows.append(row("request-incremental-sync", "MANIFEST_REQUEST", {"since_revision": 6, "max_page_bytes": 196608}, parsed={"kind": "Request", "since_revision": 6, "max_page_bytes": 196608}))
    rows.append(row("request-missing-max-page-bytes-is-required", "MANIFEST_REQUEST", {"since_revision": None}, rejected="MISSING_FIELD"))
    rows.append(row("request-since-revision-negative-rejected", "MANIFEST_REQUEST", {"since_revision": -1, "max_page_bytes": 196608}, rejected="INVALID_REVISION"))
    rows.append(row("request-max-page-bytes-wrong-type", "MANIFEST_REQUEST", {"since_revision": None, "max_page_bytes": "196608"}, rejected="WRONG_FIELD_TYPE"))

    # --- MANIFEST_BEGIN ----------------------------------------------------------------------------
    def begin_payload(**overrides) -> dict:
        base = {
            "manifest_id": VALID_MID, "kind": "full", "manifest_revision": 7, "base_revision": None,
            "total_entries": 4820, "total_removed": 0, "page_count": 19, "digest_alg": "ridelink-manifest-v1",
        }
        base.update(overrides)
        return base

    rows.append(row("begin-full-minimal-valid", "MANIFEST_BEGIN", begin_payload(), parsed={
        "kind": "Begin", "manifest_id": VALID_MID, "manifest_kind": "full", "manifest_revision": 7,
        "base_revision": None, "total_entries": 4820, "total_removed": 0, "page_count": 19, "digest_alg": "ridelink-manifest-v1",
    }))
    rows.append(row("begin-delta-with-base-revision", "MANIFEST_BEGIN", begin_payload(kind="delta", base_revision=6), parsed={
        "kind": "Begin", "manifest_id": VALID_MID, "manifest_kind": "delta", "manifest_revision": 7,
        "base_revision": 6, "total_entries": 4820, "total_removed": 0, "page_count": 19, "digest_alg": "ridelink-manifest-v1",
    }))
    rows.append(row("begin-streaming-null-page-count-accepted", "MANIFEST_BEGIN", begin_payload(page_count=None), parsed={
        "kind": "Begin", "manifest_id": VALID_MID, "manifest_kind": "full", "manifest_revision": 7,
        "base_revision": None, "total_entries": 4820, "total_removed": 0, "page_count": None, "digest_alg": "ridelink-manifest-v1",
    }))
    rows.append(row("begin-malformed-manifest-id-not-ulid", "MANIFEST_BEGIN", begin_payload(manifest_id="not-a-ulid"), rejected="MALFORMED_MANIFEST_ID"))
    rows.append(row("begin-manifest-id-lowercase-rejected", "MANIFEST_BEGIN", begin_payload(manifest_id=VALID_MID.lower()), rejected="MALFORMED_MANIFEST_ID"))
    rows.append(row("begin-unknown-kind-rejected", "MANIFEST_BEGIN", begin_payload(kind="partial"), rejected="INVALID_MANIFEST_KIND"))
    rows.append(row("begin-missing-digest-alg", "MANIFEST_BEGIN", {k: v for k, v in begin_payload().items() if k != "digest_alg"}, rejected="MISSING_FIELD"))
    rows.append(row("begin-negative-manifest-revision-rejected", "MANIFEST_BEGIN", begin_payload(manifest_revision=-1), rejected="INVALID_REVISION"))
    rows.append(row("begin-negative-total-entries-rejected", "MANIFEST_BEGIN", begin_payload(total_entries=-1), rejected="INVALID_REQUEST"))

    # --- MANIFEST_PAGE -----------------------------------------------------------------------------
    def page_payload(entries=None, **overrides) -> dict:
        base = {"manifest_id": VALID_MID, "manifest_revision": 7, "page_index": 0, "entries": entries if entries is not None else [minimal_entry()], "removed": []}
        base.update(overrides)
        return base

    rows.append(row("page-minimal-valid", "MANIFEST_PAGE", page_payload(), parsed={
        "kind": "Page", "manifest_id": VALID_MID, "manifest_revision": 7, "page_index": 0,
        "entries": [minimal_entry()], "removed": [],
    }))
    rows.append(row("page-entry-with-null-content-hash-accepted", "MANIFEST_PAGE", page_payload(entries=[minimal_entry(content_hash=None)]), parsed={
        "kind": "Page", "manifest_id": VALID_MID, "manifest_revision": 7, "page_index": 0,
        "entries": [minimal_entry(content_hash=None)], "removed": [],
    }))
    rows.append(row("page-entry-malformed-content-hash-rejects-whole-page", "MANIFEST_PAGE", page_payload(entries=[minimal_entry(content_hash="not-a-hash")]), rejected="ENTRY_FIELD_INVALID"))
    rows.append(row("page-entry-malformed-quick-id-rejects-whole-page", "MANIFEST_PAGE", page_payload(entries=[minimal_entry(quick_id="not-a-hash")]), rejected="ENTRY_FIELD_INVALID"))
    rows.append(row("page-entry-missing-quick-id-rejects-whole-page", "MANIFEST_PAGE", page_payload(entries=[{k: v for k, v in minimal_entry().items() if k != "quick_id"}]), rejected="ENTRY_FIELD_INVALID"))
    long_title = "A" * (MAX_DISPLAY_FIELD_BYTES + 1)
    rows.append(row("page-entry-oversized-title-rejects-whole-page", "MANIFEST_PAGE", page_payload(entries=[minimal_entry(title=long_title)]), rejected="ENTRY_FIELD_TOO_LARGE"))
    long_filename = "f" * (MAX_DISPLAY_FIELD_BYTES + 1) + ".mp3"
    rows.append(row("page-entry-oversized-filename-rejects-whole-page", "MANIFEST_PAGE", page_payload(entries=[minimal_entry(filename=long_filename)]), rejected="ENTRY_FIELD_TOO_LARGE"))
    rows.append(row("page-too-many-entries-rejected", "MANIFEST_PAGE", page_payload(entries=[minimal_entry() for _ in range(MAX_ENTRIES_PER_PAGE + 1)]), rejected="TOO_MANY_ENTRIES"))
    rows.append(row("page-negative-page-index-rejected", "MANIFEST_PAGE", page_payload(page_index=-1), rejected="INVALID_REQUEST"))
    rows.append(row("page-removed-with-malformed-hash-rejected", "MANIFEST_PAGE", page_payload(removed=["not-a-hash"]), rejected="ENTRY_FIELD_INVALID"))
    rows.append(row("page-unknown-field-on-entry-ignored", "MANIFEST_PAGE", page_payload(entries=[dict(minimal_entry(), future_field=1)]), parsed={
        "kind": "Page", "manifest_id": VALID_MID, "manifest_revision": 7, "page_index": 0,
        "entries": [minimal_entry()], "removed": [],
    }))

    # --- MANIFEST_END ------------------------------------------------------------------------------
    def end_payload(**overrides) -> dict:
        base = {"manifest_id": VALID_MID, "manifest_revision": 7, "page_count": 19, "total_entries": 4820, "total_removed": 0, "digest": "sha256:" + "5c" * 32}
        base.update(overrides)
        return base

    rows.append(row("end-minimal-valid", "MANIFEST_END", end_payload(), parsed={
        "kind": "End", "manifest_id": VALID_MID, "manifest_revision": 7, "page_count": 19,
        "total_entries": 4820, "total_removed": 0, "digest": "sha256:" + "5c" * 32,
    }))
    rows.append(row("end-malformed-digest-rejected", "MANIFEST_END", end_payload(digest="not-a-hash"), rejected="MALFORMED_DIGEST"))
    rows.append(row("end-negative-page-count-rejected", "MANIFEST_END", end_payload(page_count=-1), rejected="INVALID_REQUEST"))

    # --- MANIFEST_ABORT ----------------------------------------------------------------------------
    for reason in ("library_changed", "page_oversize", "cancelled", "internal"):
        rows.append(row(f"abort-reason-{reason}", "MANIFEST_ABORT", {"manifest_id": VALID_MID, "reason": reason}, parsed={"kind": "Abort", "manifest_id": VALID_MID, "reason": reason}))
    rows.append(row("abort-unknown-reason-tolerated-as-unknown", "MANIFEST_ABORT", {"manifest_id": VALID_MID, "reason": "a_future_reason"}, parsed={"kind": "Abort", "manifest_id": VALID_MID, "reason": "unknown"}))
    rows.append(row("abort-missing-manifest-id", "MANIFEST_ABORT", {"reason": "cancelled"}, rejected="MISSING_FIELD"))

    rows.append(row("unknown-manifest-type-rejected", "MANIFEST_FROBNICATE", {"manifest_id": VALID_MID}, rejected="UNKNOWN_TYPE"))

    return rows


def main() -> None:
    rows = build()
    names = [r["name"] for r in rows]
    assert len(names) == len(set(names)), "duplicate row names"
    payload = {
        "_comment": (
            "PROTOCOL §8.1 — the MANIFEST_* message envelope layer. Every row is (type, payload) -> "
            "parsed or rejected, and both platforms' ManifestCodec runs this same file. Distinct "
            "from manifest-paging/ (page-assembly arithmetic) and manifest-paging-errors/ (the "
            "receiver sequencing state machine, which consumes already-parsed events) — this file "
            "is the field-level parse boundary those two build on. Generated by "
            "tools/generate_manifest_messages_vectors.py — an independent third transcription of "
            "the spec. Edit the generator, never this file."
        ),
        "_invariant": (
            "No row may expect a malformed MANIFEST_* frame to end the control connection — the "
            "framing was intact, so the frame is dropped and the connection survives, exactly the "
            "VOICE_*/TRANSFER_* rule applied to the catalogue plane."
        ),
        "_test_values_only": "Every manifest_id, content_hash, quick_id and digest here is fabricated.",
        "bounds": {"MAX_ENTRIES_PER_PAGE": MAX_ENTRIES_PER_PAGE, "MAX_DISPLAY_FIELD_BYTES": MAX_DISPLAY_FIELD_BYTES},
        "rows": rows,
    }
    out = Path(__file__).resolve().parent.parent / "protocol" / "vectors" / "manifest-messages"
    out.mkdir(parents=True, exist_ok=True)
    target = out / "manifest_messages_vectors.json"
    target.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {target} ({len(rows)} rows)")


if __name__ == "__main__":
    main()
