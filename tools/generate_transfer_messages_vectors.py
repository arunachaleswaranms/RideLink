#!/usr/bin/env python3
"""Generate protocol/vectors/transfer-messages/transfer_messages_vectors.json.

PROTOCOL §8.2 — the `TRANSFER_*` message layer: `TRANSFER_REQUEST`, `TRANSFER_OFFER`,
`TRANSFER_PROGRESS`, `TRANSFER_RESULT`, `TRANSFER_CANCEL`. Every row is `(type, payload) ->
parsed or rejected`, mirroring `generate_voice_signal_vectors.py`'s shape exactly — this is the
transfer plane's equivalent of `VoiceSignalCodec`, an independent third transcription of the spec,
never a port of either platform's `TransferCodec`. Edit this generator, never the JSON.

Run:  python3 tools/generate_transfer_messages_vectors.py
"""

from __future__ import annotations

import json
from pathlib import Path

CHUNK_SIZE = 65_536  # 64 KiB, PROTOCOL §8.2 — fixed in V1
MAX_TRANSFER_SIZE_BYTES = 2_147_483_648  # 2 GiB defensive cap (ADR-023 / brief §30, §23)
VALID_CANCEL_REASONS = {"user_cancelled", "disconnected", "superseded", "error"}

VALID_HASH = "sha256:" + "1f" * 32
VALID_TRANSFER_ID = "01J9Z4M3RT8V2W5X7Y9Z1A3B5C"  # ULID shape, same as session_id/manifest_id
VALID_BULK_TOKEN = "a1" * 32  # 64 lowercase hex chars, 32 bytes


def content_hash_field_rows() -> list[dict]:
    rows = []
    rows.append(
        row(
            "request-minimal-valid",
            "TRANSFER_REQUEST",
            {"content_hash": VALID_HASH, "transfer_id": VALID_TRANSFER_ID},
            parsed={"kind": "Request", "content_hash": VALID_HASH, "transfer_id": VALID_TRANSFER_ID},
        )
    )
    rows.append(
        row(
            "request-unknown-field-ignored",
            "TRANSFER_REQUEST",
            {"content_hash": VALID_HASH, "transfer_id": VALID_TRANSFER_ID, "future_field": 42},
            parsed={"kind": "Request", "content_hash": VALID_HASH, "transfer_id": VALID_TRANSFER_ID},
        )
    )
    rows.append(row("request-missing-content-hash", "TRANSFER_REQUEST", {"transfer_id": VALID_TRANSFER_ID}, rejected="MISSING_FIELD"))
    rows.append(row("request-missing-transfer-id", "TRANSFER_REQUEST", {"content_hash": VALID_HASH}, rejected="MISSING_FIELD"))
    rows.append(
        row(
            "request-content-hash-wrong-type",
            "TRANSFER_REQUEST",
            {"content_hash": 12345, "transfer_id": VALID_TRANSFER_ID},
            rejected="WRONG_FIELD_TYPE",
        )
    )
    rows.append(
        row(
            "request-content-hash-uppercase-rejected",
            "TRANSFER_REQUEST",
            {"content_hash": "sha256:" + "1F" * 32, "transfer_id": VALID_TRANSFER_ID},
            rejected="MALFORMED_CONTENT_HASH",
        )
    )
    rows.append(
        row(
            "request-content-hash-truncated",
            "TRANSFER_REQUEST",
            {"content_hash": "sha256:" + "1f" * 20, "transfer_id": VALID_TRANSFER_ID},
            rejected="MALFORMED_CONTENT_HASH",
        )
    )
    rows.append(
        row(
            "request-content-hash-missing-prefix",
            "TRANSFER_REQUEST",
            {"content_hash": "1f" * 32, "transfer_id": VALID_TRANSFER_ID},
            rejected="MALFORMED_CONTENT_HASH",
        )
    )
    rows.append(
        row(
            "request-content-hash-non-hex-characters",
            "TRANSFER_REQUEST",
            {"content_hash": "sha256:" + "zz" * 32, "transfer_id": VALID_TRANSFER_ID},
            rejected="MALFORMED_CONTENT_HASH",
        )
    )
    rows.append(
        row(
            "request-transfer-id-malformed-not-ulid-shape",
            "TRANSFER_REQUEST",
            {"content_hash": VALID_HASH, "transfer_id": "not-a-ulid"},
            rejected="MALFORMED_TRANSFER_ID",
        )
    )
    rows.append(
        row(
            "request-transfer-id-lowercase-rejected",
            "TRANSFER_REQUEST",
            {"content_hash": VALID_HASH, "transfer_id": VALID_TRANSFER_ID.lower()},
            rejected="MALFORMED_TRANSFER_ID",
        )
    )
    return rows


def offer_rows() -> list[dict]:
    rows = []
    size = 10_000_000
    chunk_count = (size + CHUNK_SIZE - 1) // CHUNK_SIZE

    def payload(**overrides) -> dict:
        base = {
            "transfer_id": VALID_TRANSFER_ID,
            "size_bytes": size,
            "chunk_size": CHUNK_SIZE,
            "chunk_count": chunk_count,
            "bulk_port": 51000,
            "bulk_token": VALID_BULK_TOKEN,
        }
        base.update(overrides)
        return base

    rows.append(
        row(
            "offer-minimal-valid",
            "TRANSFER_OFFER",
            payload(),
            parsed={
                "kind": "Offer",
                "transfer_id": VALID_TRANSFER_ID,
                "size_bytes": size,
                "chunk_size": CHUNK_SIZE,
                "chunk_count": chunk_count,
                "bulk_port": 51000,
                "bulk_token": VALID_BULK_TOKEN,
            },
        )
    )
    rows.append(row("offer-size-zero-rejected", "TRANSFER_OFFER", payload(size_bytes=0, chunk_count=0), rejected="INVALID_SIZE"))
    rows.append(row("offer-negative-size-rejected", "TRANSFER_OFFER", payload(size_bytes=-1), rejected="INVALID_SIZE"))
    rows.append(
        row(
            "offer-size-exceeds-max-transfer-size-rejected",
            "TRANSFER_OFFER",
            payload(size_bytes=MAX_TRANSFER_SIZE_BYTES + 1, chunk_count=(MAX_TRANSFER_SIZE_BYTES + 1 + CHUNK_SIZE - 1) // CHUNK_SIZE),
            rejected="SIZE_TOO_LARGE",
        )
    )
    rows.append(
        row(
            "offer-size-at-max-transfer-size-accepted",
            "TRANSFER_OFFER",
            payload(size_bytes=MAX_TRANSFER_SIZE_BYTES, chunk_count=(MAX_TRANSFER_SIZE_BYTES + CHUNK_SIZE - 1) // CHUNK_SIZE),
            parsed={
                "kind": "Offer",
                "transfer_id": VALID_TRANSFER_ID,
                "size_bytes": MAX_TRANSFER_SIZE_BYTES,
                "chunk_size": CHUNK_SIZE,
                "chunk_count": (MAX_TRANSFER_SIZE_BYTES + CHUNK_SIZE - 1) // CHUNK_SIZE,
                "bulk_port": 51000,
                "bulk_token": VALID_BULK_TOKEN,
            },
        )
    )
    rows.append(row("offer-chunk-size-not-64kib-rejected", "TRANSFER_OFFER", payload(chunk_size=32768), rejected="UNSUPPORTED_CHUNK_SIZE"))
    rows.append(
        row(
            "offer-chunk-count-inconsistent-with-size-rejected",
            "TRANSFER_OFFER",
            payload(chunk_count=chunk_count + 1),
            rejected="CHUNK_COUNT_MISMATCH",
        )
    )
    rows.append(row("offer-bulk-port-zero-rejected", "TRANSFER_OFFER", payload(bulk_port=0), rejected="PORT_OUT_OF_RANGE"))
    rows.append(row("offer-bulk-port-above-65535-rejected", "TRANSFER_OFFER", payload(bulk_port=65536), rejected="PORT_OUT_OF_RANGE"))
    rows.append(row("offer-bulk-port-at-65535-accepted", "TRANSFER_OFFER", payload(bulk_port=65535), parsed={
        "kind": "Offer", "transfer_id": VALID_TRANSFER_ID, "size_bytes": size, "chunk_size": CHUNK_SIZE,
        "chunk_count": chunk_count, "bulk_port": 65535, "bulk_token": VALID_BULK_TOKEN,
    }))
    rows.append(row("offer-bulk-token-wrong-length-rejected", "TRANSFER_OFFER", payload(bulk_token="a1" * 10), rejected="MALFORMED_BULK_TOKEN"))
    rows.append(row("offer-bulk-token-uppercase-rejected", "TRANSFER_OFFER", payload(bulk_token=("A1" * 32)), rejected="MALFORMED_BULK_TOKEN"))
    rows.append(row("offer-missing-bulk-token", "TRANSFER_OFFER", {k: v for k, v in payload().items() if k != "bulk_token"}, rejected="MISSING_FIELD"))
    return rows


def progress_rows() -> list[dict]:
    rows = []
    rows.append(
        row(
            "progress-minimal-valid",
            "TRANSFER_PROGRESS",
            {"transfer_id": VALID_TRANSFER_ID, "bytes": 500_000, "pct": 5},
            parsed={"kind": "Progress", "transfer_id": VALID_TRANSFER_ID, "bytes": 500_000, "pct": 5},
        )
    )
    rows.append(row("progress-pct-at-zero-accepted", "TRANSFER_PROGRESS", {"transfer_id": VALID_TRANSFER_ID, "bytes": 0, "pct": 0}, parsed={
        "kind": "Progress", "transfer_id": VALID_TRANSFER_ID, "bytes": 0, "pct": 0,
    }))
    rows.append(row("progress-pct-at-100-accepted", "TRANSFER_PROGRESS", {"transfer_id": VALID_TRANSFER_ID, "bytes": 10_000_000, "pct": 100}, parsed={
        "kind": "Progress", "transfer_id": VALID_TRANSFER_ID, "bytes": 10_000_000, "pct": 100,
    }))
    rows.append(row("progress-pct-negative-rejected", "TRANSFER_PROGRESS", {"transfer_id": VALID_TRANSFER_ID, "bytes": 0, "pct": -1}, rejected="PROGRESS_OUT_OF_RANGE"))
    rows.append(row("progress-pct-above-100-rejected", "TRANSFER_PROGRESS", {"transfer_id": VALID_TRANSFER_ID, "bytes": 0, "pct": 101}, rejected="PROGRESS_OUT_OF_RANGE"))
    rows.append(row("progress-bytes-negative-rejected", "TRANSFER_PROGRESS", {"transfer_id": VALID_TRANSFER_ID, "bytes": -1, "pct": 0}, rejected="INVALID_SIZE"))
    return rows


def result_rows() -> list[dict]:
    rows = []
    rows.append(
        row(
            "result-ok-with-matching-sha256",
            "TRANSFER_RESULT",
            {"transfer_id": VALID_TRANSFER_ID, "ok": True, "sha256": VALID_HASH},
            parsed={"kind": "Result", "transfer_id": VALID_TRANSFER_ID, "ok": True, "sha256": VALID_HASH},
        )
    )
    rows.append(
        row(
            "result-failed-with-null-sha256",
            "TRANSFER_RESULT",
            {"transfer_id": VALID_TRANSFER_ID, "ok": False, "sha256": None},
            parsed={"kind": "Result", "transfer_id": VALID_TRANSFER_ID, "ok": False, "sha256": None},
        )
    )
    rows.append(
        row(
            "result-failed-with-absent-sha256",
            "TRANSFER_RESULT",
            {"transfer_id": VALID_TRANSFER_ID, "ok": False},
            parsed={"kind": "Result", "transfer_id": VALID_TRANSFER_ID, "ok": False, "sha256": None},
        )
    )
    rows.append(
        row(
            "result-ok-true-missing-sha256-rejected",
            "TRANSFER_RESULT",
            {"transfer_id": VALID_TRANSFER_ID, "ok": True},
            rejected="MISSING_FIELD",
        )
    )
    rows.append(
        row(
            "result-ok-wrong-type-rejected",
            "TRANSFER_RESULT",
            {"transfer_id": VALID_TRANSFER_ID, "ok": "true", "sha256": VALID_HASH},
            rejected="WRONG_FIELD_TYPE",
        )
    )
    rows.append(
        row(
            "result-sha256-malformed-rejected",
            "TRANSFER_RESULT",
            {"transfer_id": VALID_TRANSFER_ID, "ok": True, "sha256": "not-a-hash"},
            rejected="MALFORMED_CONTENT_HASH",
        )
    )
    return rows


def cancel_rows() -> list[dict]:
    rows = []
    for reason in sorted(VALID_CANCEL_REASONS):
        rows.append(
            row(
                f"cancel-reason-{reason}",
                "TRANSFER_CANCEL",
                {"transfer_id": VALID_TRANSFER_ID, "reason": reason},
                parsed={"kind": "Cancel", "transfer_id": VALID_TRANSFER_ID, "reason": reason},
            )
        )
    rows.append(
        row(
            "cancel-unknown-reason-tolerated-as-unknown",
            "TRANSFER_CANCEL",
            {"transfer_id": VALID_TRANSFER_ID, "reason": "a_future_reason_this_build_does_not_know"},
            parsed={"kind": "Cancel", "transfer_id": VALID_TRANSFER_ID, "reason": "unknown"},
        )
    )
    rows.append(row("cancel-missing-reason", "TRANSFER_CANCEL", {"transfer_id": VALID_TRANSFER_ID}, rejected="MISSING_FIELD"))
    rows.append(row("cancel-missing-transfer-id", "TRANSFER_CANCEL", {"reason": "user_cancelled"}, rejected="MISSING_FIELD"))
    return rows


def unknown_type_row() -> dict:
    return row("unknown-transfer-type-rejected", "TRANSFER_FROBNICATE", {"transfer_id": VALID_TRANSFER_ID}, rejected="UNKNOWN_TYPE")


def row(name: str, type_: str, payload: dict, *, parsed: dict | None = None, rejected: str | None = None) -> dict:
    assert (parsed is None) != (rejected is None), f"{name}: exactly one of parsed/rejected"
    expect = {"parsed": parsed} if parsed is not None else {"rejected": rejected}
    return {"name": name, "type": type_, "payload": payload, "expect": expect}


def build() -> list[dict]:
    rows: list[dict] = []
    rows.extend(content_hash_field_rows())
    rows.extend(offer_rows())
    rows.extend(progress_rows())
    rows.extend(result_rows())
    rows.extend(cancel_rows())
    rows.append(unknown_type_row())
    return rows


def main() -> None:
    rows = build()
    names = [r["name"] for r in rows]
    assert len(names) == len(set(names)), "duplicate row names"
    payload = {
        "_comment": (
            "PROTOCOL §8.2 — the TRANSFER_* message layer. Every row is (type, payload) -> parsed "
            "or rejected, and both platforms' TransferCodec runs this same file, so a bound "
            "enforced on one platform and not the other is a laptop unit-test failure rather than "
            "something two phones discover mid-ride. Generated by "
            "tools/generate_transfer_messages_vectors.py — an independent third transcription of "
            "the spec. Edit the generator, never this file."
        ),
        "_invariant": (
            "No row may expect a malformed TRANSFER_* frame to end the control connection — the "
            "framing was intact, so the frame is dropped and the connection survives, exactly the "
            "VOICE_* rule (PROTOCOL §7.4) applied to the transfer plane."
        ),
        "_test_values_only": "Every content_hash, transfer_id and bulk_token here is fabricated.",
        "bounds": {
            "CHUNK_SIZE": CHUNK_SIZE,
            "MAX_TRANSFER_SIZE_BYTES": MAX_TRANSFER_SIZE_BYTES,
            "VALID_CANCEL_REASONS": sorted(VALID_CANCEL_REASONS),
        },
        "rows": rows,
    }
    out = Path(__file__).resolve().parent.parent / "protocol" / "vectors" / "transfer-messages"
    out.mkdir(parents=True, exist_ok=True)
    target = out / "transfer_messages_vectors.json"
    target.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {target} ({len(rows)} rows)")


if __name__ == "__main__":
    main()
