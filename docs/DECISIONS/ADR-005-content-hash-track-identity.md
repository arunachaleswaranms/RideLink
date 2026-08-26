# ADR-005 — Two-tier content hashing for track identity

**Status:** Accepted · 26 Aug 2026

## Context

Tracks must be identified so that identical files with different names are not transferred twice
(FR-010), and so that a `PLAY` command refers unambiguously to the same bytes on both phones.
REQUIREMENTS §9.2 mandates a content hash as authoritative and warns that metadata alone must not
determine identity.

Hashing an entire music library on a phone is expensive: 1 000 tracks × ~8 MB is 8 GB of reads.
Doing that eagerly on every scan would violate NFR-03 (battery) and make first launch unusable.

## Decision

Two tiers, with a clear rule about which one is authoritative.

| Tier | Definition | Cost | Role |
|---|---|---|---|
| `quick_id` | `SHA-256(size_bytes ‖ first 64 KiB ‖ last 64 KiB)` | ~1 ms/file | indexing, change detection, catalogue display |
| `content_hash` | `SHA-256(whole file)` | ~50 ms/file | **authoritative** — transfer dedupe, transfer validation, every protocol reference |

- `content_hash` is computed lazily in a background job.
- **A track without a `content_hash` is not sync-eligible and not transferable.** The invariant is structural rather than something to remember: the eligibility check is the null check.
- Files shorter than 128 KiB hash the whole file once for `quick_id` (no double-counting the overlapping window).

`content_hash` identifies **an exact file**, not a musical work — two different rips of the same
song are correctly two tracks, since they cannot substitute for each other in a byte-for-byte
transfer.

For UI grouping only, a non-authoritative `work_key` =
`normalize(artist) ‖ normalize(title) ‖ round(duration_ms, 2s)` clusters near-duplicates
visually. It **never** drives identity, transfer or playback. The brief's warning about
filenames applies just as much to fuzzy metadata keys.

## Consequences

- Indexing 1 000 tracks is fast (`quick_id` only); full hashing completes in the background.
- The catalogue can display tracks before they are transferable, so the UI must show a "preparing" state rather than pretending they are ready.
- A file edited in place changes both hashes; `quick_id` detects it cheaply on rescan.
- Transfer validation is genuinely end-to-end: the receiver recomputes SHA-256 **from the file it wrote to disk**, so truncated writes and disk-full conditions are caught, not just network corruption.
- SHA-256 is a platform primitive on both sides (`MessageDigest`, CryptoKit) — no dependency, no custom crypto.

## Alternatives considered

| Option | Rejected because |
|---|---|
| Filename + size | Explicitly warned against; renames and re-tags break it |
| Metadata triple only | Same song from two sources has different bytes; would cause corrupt "dedupe" |
| Full SHA-256 eagerly at scan | Unacceptable first-run cost and battery drain |
| Audio-fingerprint identity (Chromaprint) | Solves a different problem (same *work*, different bytes) and cannot validate a byte transfer. Would also add a dependency. The `work_key` covers the UI need |
| Hash only the audio frames, skipping tags | Attractive for dedupe across re-tagged files, but then the hash no longer validates the transferred file. Rejected: integrity beats cleverness |
