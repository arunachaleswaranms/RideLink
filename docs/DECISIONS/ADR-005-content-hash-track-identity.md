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

## Amendment A1 — 5 September 2026 — `quick_id` was implemented as authoritative identity; corrected

**Status of the ADR: still Accepted.** This section corrects an implementation defect against the
decision above, not the decision itself — the two-tier model and the role split in the table at the
top of this ADR were always right. A closure-audit hardening pass on Phase 3 found that both
platforms' actual code contradicted this ADR's own "Role" column.

### The bug

`quick_id` is `SHA-256(size_bytes ‖ first 64 KiB ‖ last 64 KiB)` — a sample, not a full-file digest.
Two files over 128 KiB with the same size and identical first/last 64 KiB windows but a **different
middle** produce the **same** `quick_id` while being genuinely different content. This is not a
SHA-256 collision; it is a deterministic, constructible consequence of only sampling part of the
file, reproduced directly in both platforms' test suites by this amendment's regression fixtures.

Despite that, production code used `quick_id` as cross-row identity:

- **Android:** `quickId` was a `UNIQUE` Room index, and `upsert`'s `OnConflictStrategy.REPLACE`
  meant inserting a row with an already-used `quickId` **deleted the old row and inserted the new
  one** — a silent, irreversible merge of two different files' rows the moment two locations
  produced the same sample.
- **iOS:** an imported file's app-container destination filename was derived from `quick_id`, and
  import **skipped copying** a file whose destination already existed — so a colliding file's bytes
  were never even copied into the sandbox, an even more severe loss (the original picker URL is
  never touched again after import, per ADR-009, so there was no way to recover it afterward).
- Both platforms' `core.library`/`RideLinkCore.Library` reconciliation operated on bare
  `Set<QuickId>`, explicitly documenting "two files with byte-identical content collapse to one
  QuickId" as intended FR-010 behaviour — correct only on the false premise that a shared `quick_id`
  proves shared content.
- Both platforms additionally keyed their artwork cache filename on `quick_id`, which would have let
  two different files' cover art collide onto the same cached file for the same underlying reason.

## Decision (amendment)

**`quick_id` is demoted to exactly the roles this ADR's table always granted it: indexing, change
detection and catalogue display. It is never used to decide that two rows are the same content, and
it carries no uniqueness constraint at either the database or the filesystem-naming level on either
platform.**

A new value, **`local_entry_id`**, is introduced on both platforms as the real per-row identity:

- Generated once, the moment a location is first indexed (a random UUID; no relationship to the
  file's bytes, so it cannot collide the way a content-derived value can).
- Carried forward unchanged across every rescan that finds the same location again, or that detects
  the location's content changed in place (`quick_id` differs from what was last stored for that
  *same* location — a safe comparison, because both values are already known to describe one
  location).
- The key the player, the local queue and the artwork cache use to mean "this exact row" — not
  `quick_id`, and not `content_hash` (still absent until the lazy background pass reaches a row, so
  making local playback wait on it would make a freshly-imported track briefly unplayable for no
  reason a local player has — unchanged from this ADR's original reasoning).
- `location_uri` (the on-disk/SAF/GRDB location) becomes the schema-level `UNIQUE` key instead of
  `quick_id`: two distinct on-disk locations are, definitionally, two distinct rows, so this can never
  suffer the same false-collapse failure.

**A rename is no longer invisible.** The previous design silently followed a reappearing `quick_id`
to a new location and treated it as the same row, moved — relying on exactly the unsafe
cross-location comparison this amendment removes. A renamed file now surfaces as one row going
`MISSING` plus one new row at the new location. This is a real, accepted regression in convenience,
not a correctness gap: a false "new track" costs one re-index; a false merge silently destroyed data.

**Phase 3 does not attempt cross-row duplicate collapsing by `content_hash` either**, deliberately.
FR-010 ("preventing unnecessary transfer of identical files with different names") is about
*transfer*, which does not exist until Phase 4/5 — collapsing local library rows today would mean
deleting one out from under a local queue/player reference for a requirement Phase 3 has no consumer
for yet. Two byte-identical files therefore correctly show as two independent rows in Phase 3's local
catalogue; real, authoritative duplicate detection for transfer is Phase 4/5 scope, keyed on
`content_hash` equality once both sides have it, never on `quick_id`.

### Verification

Both platforms' library-indexer test suites gained a deterministic regression fixture: two files
over 128 KiB, identical size and first/last 64 KiB windows, differing only in the middle —
constructed directly (not hoped for), proving `quick_id(A) == quick_id(B)` while
`content_hash(A) != content_hash(B)`, and proving the full indexing pipeline keeps them as two
distinct rows with two distinct `local_entry_id`s, before and after the background hashing pass
computes both `content_hash` values. `core.library.IndexReconciliation`/
`RideLinkCore.Library.IndexReconciliation`'s pure test suites were extended for the new
location-keyed reconciliation shape (new/unchanged/changed/missing, rather than the old
new/still-present/missing over bare `quick_id` sets).

No wire shape changed — `local_entry_id` is local-only bookkeeping, never sent, never persisted past
a from-scratch reindex, exactly like `LocalTrackLocation`.
