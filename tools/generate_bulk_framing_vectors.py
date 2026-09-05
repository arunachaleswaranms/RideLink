#!/usr/bin/env python3
"""Generate protocol/vectors/bulk-framing/bulk_framing_vectors.json.

PROTOCOL §8.2 — the RLB1 bulk-frame header: `magic(4) | chunk_index(uint32 BE) | byte_length
(uint32 BE) | payload(<= 64 KiB)`. Every row is a raw buffer (as hex) -> the frame(s) a correct
parser must produce, or the incomplete/invalid outcome it must produce instead — **before any
allocation sized by the untrusted length field**.

Independent third transcription, not a port of either platform's `BulkFraming`. The one thing this
file does not attempt is "arbitrary split boundary" coverage (1 byte at a time, header split mid-
frame, etc.) — that is a property of *how bytes arrive*, not of one static buffer, so it belongs to
each platform's own transport test, fed with this file's `multiple-frames-in-one-buffer` row's
bytes split at every boundary a test cares to try (`docs/TEST_PLAN.md`, brief §34). What belongs
here is the parser's pure decision on a complete-or-not buffer.

Edit this generator, never the JSON.

Run:  python3 tools/generate_bulk_framing_vectors.py
"""

from __future__ import annotations

import json
from pathlib import Path

MAGIC = b"RLB1"
MAX_CHUNK_PAYLOAD_BYTES = 65_536
HEADER_LEN = 12  # 4 (magic) + 4 (chunk_index) + 4 (byte_length)


def u32(n: int) -> bytes:
    return n.to_bytes(4, "big")


def frame_bytes(chunk_index: int, payload: bytes, *, magic: bytes = MAGIC, declared_length: int | None = None) -> bytes:
    length = len(payload) if declared_length is None else declared_length
    return magic + u32(chunk_index) + u32(length) + payload


def row_valid(name: str, buf: bytes, frames: list[dict], leftover: bytes = b"") -> dict:
    return {
        "name": name,
        "buffer_hex": buf.hex(),
        "expect": {"outcome": "parsed", "frames": frames, "leftover_hex": leftover.hex()},
    }


def row_incomplete(name: str, buf: bytes) -> dict:
    return {"name": name, "buffer_hex": buf.hex(), "expect": {"outcome": "incomplete"}}


def row_invalid(name: str, buf: bytes, reason: str) -> dict:
    return {"name": name, "buffer_hex": buf.hex(), "expect": {"outcome": "invalid", "reason": reason}}


def frame_dict(chunk_index: int, payload: bytes) -> dict:
    return {"chunk_index": chunk_index, "byte_length": len(payload), "payload_hex": payload.hex()}


def build() -> list[dict]:
    rows: list[dict] = []

    one_byte = b"\x42"
    max_payload = bytes((i % 256 for i in range(MAX_CHUNK_PAYLOAD_BYTES)))

    rows.append(row_valid("valid-single-frame-one-byte-payload", frame_bytes(0, one_byte), [frame_dict(0, one_byte)]))
    rows.append(row_valid("valid-single-frame-exact-64kib-boundary", frame_bytes(0, max_payload), [frame_dict(0, max_payload)]))
    rows.append(row_valid("valid-single-frame-empty-payload", frame_bytes(0, b""), [frame_dict(0, b"")]))
    rows.append(row_valid("valid-frame-with-max-uint32-chunk-index", frame_bytes(0xFFFFFFFF, one_byte), [frame_dict(0xFFFFFFFF, one_byte)]))

    two_frames = frame_bytes(0, b"\x01\x02\x03") + frame_bytes(1, b"\x04\x05")
    rows.append(
        row_valid(
            "multiple-frames-in-one-buffer",
            two_frames,
            [frame_dict(0, b"\x01\x02\x03"), frame_dict(1, b"\x04\x05")],
        )
    )
    one_frame_plus_partial_next_header = frame_bytes(0, b"\x01\x02\x03") + MAGIC + u32(1)  # only 8 of the next frame's 12 header bytes
    rows.append(
        row_valid(
            "one-complete-frame-plus-incomplete-next-header-leftover",
            one_frame_plus_partial_next_header,
            [frame_dict(0, b"\x01\x02\x03")],
            leftover=MAGIC + u32(1),
        )
    )

    rows.append(row_invalid("invalid-bad-magic", b"XXXX" + u32(0) + u32(1) + one_byte, "bad_magic"))
    rows.append(row_invalid("invalid-chunk-one-byte-over-64kib", frame_bytes(0, b"", declared_length=MAX_CHUNK_PAYLOAD_BYTES + 1) + b"\x00" * (MAX_CHUNK_PAYLOAD_BYTES + 1), "chunk_too_large"))
    rows.append(row_invalid("invalid-length-top-bit-set-is-too-large-not-negative", MAGIC + u32(0) + u32(0x80000000), "chunk_too_large"))
    rows.append(row_invalid("invalid-length-max-uint32-is-too-large", MAGIC + u32(0) + u32(0xFFFFFFFF), "chunk_too_large"))

    rows.append(row_incomplete("incomplete-empty-buffer", b""))
    rows.append(row_incomplete("incomplete-magic-only-4-bytes", MAGIC))
    rows.append(row_incomplete("incomplete-header-11-of-12-bytes", MAGIC + u32(0) + u32(5)[:3]))
    rows.append(row_incomplete("incomplete-header-present-payload-truncated", frame_bytes(0, b"", declared_length=10) + b"\x01\x02\x03"))
    rows.append(row_incomplete("incomplete-exactly-header-no-payload-yet-for-nonzero-length", MAGIC + u32(0) + u32(5)))

    return rows


def main() -> None:
    rows = build()
    names = [r["name"] for r in rows]
    assert len(names) == len(set(names)), "duplicate row names"
    payload = {
        "_comment": (
            "PROTOCOL §8.2 — the RLB1 bulk-frame header. Both platforms' BulkFraming parser runs "
            "this same file, so a header-bound mistake (an unchecked allocation, a signed-overflow "
            "misread) is a laptop unit-test failure rather than a crash triggered by a malicious or "
            "malformed peer. Generated by tools/generate_bulk_framing_vectors.py — an independent "
            "third transcription of the spec. Edit the generator, never this file."
        ),
        "_invariants": [
            "byte_length is read as an unsigned 32-bit value. A value whose top bit would be set in "
            "a signed 32-bit read (0x80000000) and the maximum value (0xFFFFFFFF) are both simply "
            "'too large' — there is no separate 'negative length' outcome, because there is no "
            "signed interpretation to produce one from in a correct implementation.",
            "No frame whose declared byte_length exceeds 65536 may be allocated for at all — the "
            "length is checked before any buffer sized by it is created.",
            "An incomplete buffer (not enough bytes yet for the header, or for the header plus its "
            "declared payload) is 'incomplete', never 'invalid' — more bytes may still arrive on a "
            "live socket, and incomplete must never be reported as a parse error.",
            "chunk_index carries no ordering constraint at this layer — arbitrary values, including "
            "the maximum uint32, parse successfully. Ordering is enforced by the transfer state "
            "machine, not the frame parser.",
        ],
        "constants": {"magic": MAGIC.decode("ascii"), "header_len_bytes": HEADER_LEN, "max_chunk_payload_bytes": MAX_CHUNK_PAYLOAD_BYTES},
        "rows": rows,
    }
    out = Path(__file__).resolve().parent.parent / "protocol" / "vectors" / "bulk-framing"
    out.mkdir(parents=True, exist_ok=True)
    target = out / "bulk_framing_vectors.json"
    target.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {target} ({len(rows)} rows)")


if __name__ == "__main__":
    main()
