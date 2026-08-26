# ADR-009 — iOS music library is app-container only

**Status:** Accepted · 26 Aug 2026

## Context

FR-006/FR-007 require indexing local audio and recursive folder ingestion "where platform
file-access rules allow". Android and iOS differ fundamentally here, and the difference is not
symmetric.

On Android, `MediaStore.Audio` plus a user-granted document tree (`ACTION_OPEN_DOCUMENT_TREE`)
reaches effectively the whole on-device library, with readable file paths suitable for hashing
and transfer.

On iOS there is no equivalent. `MPMediaLibrary` can *enumerate* synced songs, but:

- Apple Music / DRM-protected items expose no readable file and cannot be exported at all;
- even for non-protected items, export goes through `AVAssetExportSession` and re-encodes rather than yielding the original bytes;
- re-encoded bytes have a different SHA-256, which breaks content-hash identity ([ADR-005](ADR-005-content-hash-track-identity.md)) and makes byte-for-byte transfer validation impossible.

## Decision

The iOS library is **the app's own container only**. Tracks arrive by explicit user import —
`UIDocumentPickerViewController`, Files app drag-and-drop, the share sheet, or peer transfer from
the rider's phone. Imported folders are indexed recursively. `MPMediaLibrary` is **not used at
all**.

## Consequences

- Every track in the iOS catalogue has real, readable, hashable bytes. Content-hash identity and transfer validation hold without exceptions or special cases.
- The pillion's catalogue starts **empty**, which is a genuine UX asymmetry rather than a bug. The pre-ride screen must make import prominent, and the shared catalogue will initially show most tracks as `PEER_ONLY`.
- In practice the rider's Android phone is the library of record and the iPhone acquires tracks over the peer link (which is exactly UJ-05, the "missing track" journey).
- Deliberately *not* showing the user's Apple Music library is the right call even though it looks like a missing feature: listing tracks that can never be shared or synchronised would be a worse experience than not listing them.
- The architecture stays symmetric — either phone can be the source of a transfer. Only initial content differs.
- Apple Music via official APIs remains deferred (REQUIREMENTS §9.1 P2, §22), and this decision does not foreclose it.

## Alternatives considered

| Option | Rejected because |
|---|---|
| `MPMediaLibrary` enumeration + `AVAssetExportSession` | DRM items unexportable; re-encoding breaks content-hash identity and transfer validation; partial and confusing library |
| Show `MPMediaLibrary` items as playable-but-unshareable | Two classes of track with different capabilities; heavy UI complexity for a feature the user cannot rely on mid-ride |
| Require the user to pre-copy their library into the app | Effectively what import is, but framing it as a requirement rather than an action; peer transfer already covers the gap |
