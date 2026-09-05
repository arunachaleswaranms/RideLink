#!/usr/bin/env python3
"""Generate protocol/vectors/manifest-paging/manifest_paging_vectors.json.

PROTOCOL §8.1 / ADR-013 — paginated manifest synchronisation: page assembly, the 512-Unicode-
scalar display-metadata clamp, and the MANIFEST_END digest.

Like `generate_intercom_vectors.py` and `generate_voice_fsm_vectors.py`, this is a **third,
independent implementation**, written from `docs/PROTOCOL.md` §8.1 and
`docs/DECISIONS/ADR-013-paginated-manifest-sync.md` rather than ported from either platform's
`ManifestPageBuilder`. Two ports of each other share their misreadings; only an independent
transcription is evidence.

Scale (1 000 / 5 000-entry) coverage is deliberately **not** here: committing thousands of literal
entries is wasted repo weight for a value each platform can already generate deterministically
on its own, and the risk that scale coverage exists for — "does pagination obey its own byte/count
bounds at scale" — is a per-platform property that does not require two implementations to agree
byte-for-byte. That coverage lives in each platform's own unit tests instead
(`docs/TEST_PLAN.md` §2). This file's job is the small, exact, shared cases: boundary counts,
the clamp (including a case a UTF-16-code-unit or grapheme-cluster count would get wrong — the
spec says Unicode *scalar values*, and Kotlin's `String.length`/Swift's `String.count` are neither),
and the digest algorithm, byte for byte.

Edit this generator, never the JSON.

Run:  python3 tools/generate_manifest_paging_vectors.py
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

MANIFEST_PAGE_SOFT_LIMIT_BYTES = 196_608  # 192 KiB, PROTOCOL §1
MAX_ENTRIES_PER_PAGE = 256
DISPLAY_CLAMP_SCALARS = 512


def content_hash_for(seed: str) -> str:
    return "sha256:" + hashlib.sha256(f"content:{seed}".encode()).hexdigest()


def quick_id_for(seed: str) -> str:
    return "sha256:" + hashlib.sha256(f"quick:{seed}".encode()).hexdigest()


def entry(
    seed: str,
    *,
    title: str = "Track",
    artist: str = "Artist",
    album: str = "Album",
    duration_ms: int = 200_000,
    codec: str = "mp3",
    bitrate_kbps: int = 192,
    size_bytes: int = 5_000_000,
    filename: str = "track.mp3",
    has_artwork: bool = False,
    content_hash: str | None = None,
    quick_id: str | None = None,
) -> dict:
    """A raw (pre-clamp) manifest entry, matching PROTOCOL §8.1's MANIFEST_PAGE example shape."""
    return {
        "content_hash": content_hash_for(seed) if content_hash is None else content_hash,
        "quick_id": quick_id_for(seed) if quick_id is None else quick_id,
        "work_key": f"{artist.lower()}|{title.lower()}|{round(duration_ms / 2000) * 2}",
        "title": title,
        "artist": artist,
        "album": album,
        "duration_ms": duration_ms,
        "codec": codec,
        "bitrate_kbps": bitrate_kbps,
        "size_bytes": size_bytes,
        "filename": filename,
        "has_artwork": has_artwork,
    }


def clamp_scalars(s: str, limit: int = DISPLAY_CLAMP_SCALARS) -> str:
    """ADR-013 rule 3: title/artist/album/filename truncated to 512 *Unicode scalar values*.

    Deliberately `list(s)[:limit]` rather than `s[:limit]` bytes/UTF-16-units — a Python `str` is
    already a sequence of Unicode code points, so this is the scalar-value count the spec means.
    Kotlin's `String.length` counts UTF-16 code units and Swift's `String.count` counts extended
    grapheme clusters; neither is this, which is exactly why one vector below uses a
    supplementary-plane character to catch an implementation using the wrong API.
    """
    return "".join(list(s)[:limit])


def clamp_entry(e: dict) -> dict:
    out = dict(e)
    for field in ("title", "artist", "album", "filename"):
        out[field] = clamp_scalars(e[field])
    return out


def entry_json_bytes(e: dict) -> int:
    """Encoded byte length used for page-budget accounting — compact, UTF-8."""
    return len(json.dumps(e, separators=(",", ":"), ensure_ascii=False).encode("utf-8"))


def paginate(entries: list[dict], budget_bytes: int = MANIFEST_PAGE_SOFT_LIMIT_BYTES) -> list[list[dict]]:
    """ADR-013's page-sizing rule: close a page when the next entry would exceed the byte budget,
    or when it reaches MAX_ENTRIES_PER_PAGE — whichever binds first. A single entry is always
    placed even if, after clamping, it alone would exceed the budget (arithmetically shouldn't
    happen per the 48 KiB worst case, but the rule is "always place at least one entry per page,
    never emit an empty page")."""
    pages: list[list[dict]] = []
    current: list[dict] = []
    current_bytes = 0
    for e in entries:
        clamped = clamp_entry(e)
        size = entry_json_bytes(clamped)
        would_be = current_bytes + size + (1 if current else 0)  # +1 for the joining comma
        if current and (would_be > budget_bytes or len(current) >= MAX_ENTRIES_PER_PAGE):
            pages.append(current)
            current = [clamped]
            current_bytes = size
        else:
            current.append(clamped)
            current_bytes += size + (1 if len(current) > 1 else 0)
    if current:
        pages.append(current)
    return pages


def digest_for(entries: list[dict], removed: list[str]) -> str:
    """PROTOCOL §8.1's exact MANIFEST_END digest: identity fields only, transmission order."""
    h = hashlib.sha256()
    for e in entries:
        h.update((e.get("content_hash") or "").encode("utf-8"))
        h.update(b"\x1f")
        h.update(e["quick_id"].encode("utf-8"))
        h.update(b"\x1e")
    for r in removed:
        h.update(b"-")
        h.update(r.encode("utf-8"))
        h.update(b"\x1e")
    return "sha256:" + h.hexdigest()


def page_row(name: str, entries: list[dict], *, budget_bytes: int = MANIFEST_PAGE_SOFT_LIMIT_BYTES, removed: list[str] | None = None) -> dict:
    removed = removed or []
    pages = paginate(entries, budget_bytes)
    all_clamped = [clamp_entry(e) for e in entries]
    return {
        "name": name,
        "budget_bytes": budget_bytes,
        "entries": entries,
        "removed": removed,
        "expect": {
            "page_count": len(pages),
            "entries_per_page": [len(p) for p in pages],
            "pages": pages,
            "digest": digest_for(all_clamped, removed),
        },
    }


def build() -> list[dict]:
    rows: list[dict] = []

    rows.append(page_row("single-entry-one-page", [entry("a")]))
    rows.append(page_row("two-entries-fit-one-page", [entry("a"), entry("b")]))
    rows.append(page_row("empty-manifest-zero-pages", []))

    # Count cap binds before the byte budget at ordinary metadata sizes (ADR-013's "256-entry
    # cap binds first" claim, made concrete): 257 minimal entries, generous per-page budget.
    many = [entry(f"n{i}", title=f"T{i}", filename=f"t{i}.mp3") for i in range(257)]
    rows.append(page_row("count-cap-closes-page-at-256", many))

    # Byte budget binds before the count cap when metadata is large: 10 entries with a budget
    # small enough that only a handful fit per page.
    long_title = "Long Title " * 20  # ~220 chars, well under the 512 clamp but bulks up JSON size
    padded = [entry(f"p{i}", title=long_title, artist="An Artist Name", album="An Album Name") for i in range(10)]
    rows.append(page_row("byte-budget-closes-page-before-count-cap", padded, budget_bytes=1200))

    # Clamp correctness: an oversized title, and — the one a wrong counting API gets wrong — a
    # title built from a supplementary-plane character (U+1F3B5 MUSICAL NOTE, one Unicode scalar
    # value but two UTF-16 code units and, in some Swift grapheme-cluster counts, one "character"
    # only by coincidence). 600 repetitions clamps to exactly 512.
    rows.append(
        {
            "name": "oversized-title-clamps-to-512-scalars",
            "entries": [entry("clamp1", title="A" * 600)],
            "expect": {"clamped_title_scalar_count": DISPLAY_CLAMP_SCALARS, "clamped_title": "A" * 512},
        }
    )
    supplementary = "\U0001f3b5" * 600  # 600 scalar values, 1200 UTF-16 code units
    rows.append(
        {
            "name": "supplementary-plane-title-clamps-by-scalar-value-not-utf16-units",
            "entries": [entry("clamp2", title=supplementary)],
            "expect": {
                "clamped_title_scalar_count": DISPLAY_CLAMP_SCALARS,
                "clamped_title": "\U0001f3b5" * 512,
            },
        }
    )
    rows.append(
        {
            "name": "identity-fields-never-truncated",
            "entries": [entry("clamp3", title="short")],
            "expect": {
                "content_hash_unchanged": True,
                "quick_id_unchanged": True,
                "size_bytes_unchanged": True,
                "duration_ms_unchanged": True,
            },
        }
    )
    rows.append(
        {
            "name": "under-limit-title-is-not-modified",
            "entries": [entry("clamp4", title="A" * 100)],
            "expect": {"clamped_title_scalar_count": 100, "clamped_title": "A" * 100},
        }
    )

    # Digest — identity fields only, order-dependent, quick_id always contributes even without
    # a content_hash (entry still awaiting background hashing).
    e1, e2, e3 = entry("d1"), entry("d2"), entry("d3")
    rows.append(page_row("digest-of-three-entries-no-removals", [e1, e2, e3]))
    rows.append(
        {
            "name": "digest-order-dependence-reordered-entries-differ",
            "entries": [e2, e1, e3],
            "expect": {"digest": digest_for([clamp_entry(e) for e in [e2, e1, e3]], [])},
            "_note": "Same three entries as digest-of-three-entries-no-removals, different order -> different digest.",
        }
    )
    e_pending = entry("pending", content_hash=None)  # content_hash still being background-hashed
    rows.append(
        {
            "name": "digest-includes-entry-awaiting-content-hash",
            "entries": [e_pending],
            "expect": {"digest": digest_for([clamp_entry(e_pending)], [])},
        }
    )
    rows.append(
        {
            "name": "delta-digest-includes-removed-content-hashes",
            "entries": [e1],
            "removed": [content_hash_for("d2"), content_hash_for("d3")],
            "expect": {
                "digest": digest_for(
                    [clamp_entry(e1)],
                    [content_hash_for("d2"), content_hash_for("d3")],
                )
            },
        }
    )

    return rows


def main() -> None:
    rows = build()
    names = [r["name"] for r in rows]
    assert len(names) == len(set(names)), "duplicate row names"
    payload = {
        "_comment": (
            "PROTOCOL §8.1 / ADR-013 — manifest page assembly, the 512-Unicode-scalar display "
            "clamp, and the MANIFEST_END digest. Both platforms' page builder runs this same file, "
            "so a page-boundary or digest disagreement is a laptop unit-test failure rather than a "
            "catalogue that silently fails to sync between two real phones. Generated by "
            "tools/generate_manifest_paging_vectors.py — an independent third transcription of the "
            "spec. Edit the generator, never this file."
        ),
        "_invariants": [
            "A page is closed when the next entry would exceed budget_bytes, or when it reaches "
            "256 entries — whichever binds first. Never an empty page.",
            "title/artist/album/filename are clamped to 512 Unicode *scalar values*, not UTF-16 "
            "code units and not grapheme clusters — see the supplementary-plane-character row.",
            "content_hash, quick_id, size_bytes and duration_ms are never truncated or otherwise "
            "modified by the clamp.",
            "The digest is computed over content_hash/quick_id pairs and removed content_hash "
            "values, in transmission order, and nothing else — reordering the same entries must "
            "change the digest.",
            "An entry with content_hash: null (background hashing incomplete) still contributes "
            "its quick_id to the digest.",
        ],
        "constants": {
            "manifest_page_soft_limit_bytes": MANIFEST_PAGE_SOFT_LIMIT_BYTES,
            "max_entries_per_page": MAX_ENTRIES_PER_PAGE,
            "display_clamp_scalars": DISPLAY_CLAMP_SCALARS,
        },
        "rows": rows,
    }
    out = Path(__file__).resolve().parent.parent / "protocol" / "vectors" / "manifest-paging"
    out.mkdir(parents=True, exist_ok=True)
    target = out / "manifest_paging_vectors.json"
    target.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {target} ({len(rows)} rows)")


if __name__ == "__main__":
    main()
