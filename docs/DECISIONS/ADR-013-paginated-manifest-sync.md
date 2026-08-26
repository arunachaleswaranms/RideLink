# ADR-013 — Paginated manifest synchronisation

**Status:** Accepted · 26 Aug 2026

## Context

Two baseline decisions contradicted each other.

1. `PROTOCOL.md` §1 caps a control frame at **256 KiB**, on the reasoning that control messages are small and anything larger is a bug or an attack. That is a good limit and worth keeping.
2. `PROTOCOL.md` §8 represented the entire library manifest as **one** `MANIFEST` message: `{ manifest_revision, complete, entries: [...] }`.

A manifest entry carries two 71-character hash strings, a work key, title, artist, album,
duration, codec, bitrate, size, basename and an artwork flag — call it 250–400 bytes of JSON in
practice, more with long or non-Latin metadata. So:

| Library | Approximate manifest JSON |
|---|---|
| 500 tracks | ~150 KB — fits, barely |
| 1 000 tracks | ~300 KB — **exceeds the cap** |
| 5 000 tracks | ~1.5 MB — exceeds it sixfold |

REQUIREMENTS and the test plan both assume 1 000–5 000 tracks. So the very first catalogue
exchange on a real library would have hit `ERROR/frame_too_large`. ADR-006 even noted the cost
("manifests exceed one frame and must be chunked or delta'd") but the wire format never grew the
mechanism — `MANIFEST_DELTA` only helps *after* a successful first sync, and a delta over a large
library edit overflows too.

Raising the cap is the wrong fix. The cap is a defence: it bounds the memory a peer can be made
to allocate from a single length prefix, before any authentication of content. Sizing it to the
largest conceivable manifest would forfeit that for the sake of one message type.

## Decision

Keep `MAX_CONTROL_FRAME_BYTES = 262144`, unchanged and unchangeable in v1. Replace the
single-frame `MANIFEST` with a **bounded sequence of bounded frames**:

```
MANIFEST_REQUEST { since_revision, max_page_bytes }
      ↓
MANIFEST_BEGIN { manifest_id, kind, manifest_revision, base_revision,
                 total_entries, total_removed, page_count, digest_alg }
      ↓
MANIFEST_PAGE  { manifest_id, manifest_revision, page_index, entries[], removed[] }   × n
      ↓
MANIFEST_END   { manifest_id, manifest_revision, page_count,
                 total_entries, total_removed, digest }
```

Full wire detail: [PROTOCOL §8.1](../PROTOCOL.md#81-paginated-manifest-synchronisation).

Five choices inside that shape carry the weight:

**1. One framing for both full manifests and deltas.** `MANIFEST_BEGIN.kind` is `full` or
`delta`; a delta additionally carries `base_revision` and uses the `removed[]` array. There is no
separate `MANIFEST_DELTA` message, because a delta has exactly the same unbounded-size problem
and would otherwise need its own pagination scheme — two schemes to keep consistent, two sets of
vectors, two places for the bug.

**2. The size guarantee is in bytes, not entries.** The sender measures the frame as it builds it
and closes the page when the next entry would exceed
`min(192 KiB, peer.limits.max_manifest_page_bytes)`. A secondary cap of 256 entries per page also
applies, and at ordinary metadata sizes it is the one that actually fires — but it is a
convenience bound, not the guarantee. A *purely* count-based rule (say "500 entries per page")
would be a latent overflow the first time somebody has an album with a very long title, or a
library tagged in a script with 3-byte UTF-8 characters, and the failure would only reproduce on
that user's real library. 192 KiB is 75 % of the cap, leaving room for the envelope and
worst-case JSON escaping.

**3. Display metadata is clamped so that a single entry always fits.** `title`, `artist`, `album`
and `filename` are truncated to 512 Unicode scalars at manifest-build time. Identity fields —
`content_hash`, `quick_id`, `size_bytes`, `duration_ms` — are never truncated. The arithmetic:
four fields × 512 scalars × ≤4 bytes UTF-8 × ≤6 bytes escaped ⇒ ≤48 KiB, plus ~200 bytes of
hashes and numbers, comfortably inside a 192 KiB page. Hence there is **no "entry too large"
state** and no way for one pathological file to stall catalogue sync. That is what "pagination
must work regardless of metadata length" requires, and it needs this clamp to be true rather than
merely likely.

**4. Order is strict and gaps are fatal.** `page_index` must be exactly the next expected index,
ascending from 0. On a reliable ordered transport a gap, duplicate or reordering is a bug or an
attack, not a network event to repair — so the receiver does not buffer out-of-order pages and
does not attempt reassembly. It aborts the synchronisation with
`ERROR/manifest_sequence_error` and restarts from the beginning. Restarting a metadata sync is
cheap; a subtly wrong catalogue is not.

**5. Nothing partial is ever promoted.** Pages accumulate in an in-memory staging area. The live
catalogue and its `manifest_revision` change only when `MANIFEST_END` validates `page_count`,
`total_entries`, `total_removed` and the digest. An interrupted sync leaves the previous manifest
in force and untouched. This is deliberately the same rule as file transfer's atomic promote —
`.part` then `rename()` — applied to metadata.

The `MANIFEST_END` digest is computed over **identity fields in transmission order only**:

```
h = SHA-256 over, in the order sent:
      each entry:    utf8(content_hash ?? "") ‖ 0x1F ‖ utf8(quick_id) ‖ 0x1E
      each removal:  utf8("-") ‖ utf8(content_hash) ‖ 0x1E
digest = "sha256:" ‖ lowercase_hex(h)
```

Identity fields only, because including the full entry JSON would require a canonical JSON form
— key order, number formatting, escaping — and that is a portability hazard for two independent
implementations that must agree byte-for-byte. The digest's job is detecting lost, duplicated,
reordered or truncated *pages*, and identity fields do that completely; metadata text is already
validated by the per-frame JSON decode. `quick_id` is always present, so entries still awaiting a
`content_hash` still contribute.

## Consequences

- A 5 000-track library synchronises in 20 frames of ≤192 KiB each (the 256-entry cap binds first at ordinary metadata sizes; the byte budget binds when metadata is long). Every frame is individually inside the cap, and the cap keeps doing its defensive job.
- The receiver's peak memory is one page plus the staging structure, not one whole manifest as a single JSON string. On a phone with a large library that is a material difference.
- Manifest sync is interruptible with no partial-state risk: at worst the previous catalogue stays and the sync is retried. There is no half-updated catalogue and no way to conclude "complete" from a truncated stream.
- Forward compatibility is preserved. Unknown fields inside `MANIFEST_BEGIN`, a page, an entry or `MANIFEST_END` are ignored per the envelope rules, and because pages are sized by measured bytes an added field cannot silently push a page over the cap.
- Cost: more messages and a receiver-side state machine (idle → staging → validating → committed) that did not exist before. It lives in `core`'s `manifest` package as pure functions and is therefore driven entirely by shared vectors — `manifest-paging/` for the happy paths and boundaries, `manifest-paging-errors/` for the twelve failure cases in TEST_PLAN §2.
- Cost: display metadata may be truncated at 512 scalars. Acceptable and documented; identity is never affected, and no realistic tag is near that length.
- Resume of a partially transferred manifest is **not** in V1. `MANIFEST_REQUEST.resume_from_page` is named in PROTOCOL §12 so it stays possible; a fresh `manifest_id` per attempt is what keeps a restart unambiguous.
- `STATE_SNAPSHOT` carries `manifest_revision` only, never entries, so reconnect reconciliation cannot reintroduce an oversized frame by another route.

## Alternatives considered

| Option | Rejected because |
|---|---|
| Raise the frame cap to 4 MB | Forfeits the defensive bound for every message type to accommodate one, and still has a ceiling — just a less honest one. A 20 000-track library would break it again |
| Send the manifest over the bulk (file transfer) connection | Reuses a plane built for opaque byte streams to carry structured state, needs its own framing and integrity story anyway, and couples catalogue sync to transfer availability |
| Compress the manifest and keep one frame | Buys perhaps 5× and moves the ceiling rather than removing it. Adds a compression dependency or hand-rolled codec to the *control* plane, and a compressed frame is a decompression-bomb surface right where the size cap used to protect us |
| Paginate by fixed entry count | Overflows the moment metadata is long or non-Latin. The failure would be rare, data-dependent and only reproducible on the user's real library — the worst possible bug shape |
| Rely on `MANIFEST_DELTA` alone | Only works after a successful first full sync, which is the exact case that was broken. And a large library edit overflows a delta too |
| Buffer and reorder out-of-order pages | Adds a reassembly buffer and its edge cases to defend against something that cannot happen on TCP unless something is already wrong |
| Digest over canonical JSON of each entry | Requires two independent implementations to agree on key order, number formatting and escaping. A cross-platform mismatch factory for no extra detection power |
