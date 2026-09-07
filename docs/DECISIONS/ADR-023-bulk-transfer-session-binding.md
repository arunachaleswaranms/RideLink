# ADR-023 — Bulk transfer session binding, listener lifecycle and cache trust model

**Status:** Accepted · 5 September 2026

## Context

`PROTOCOL.md` §8.2 and `ARCHITECTURE.md` §8.3 already specify the transfer wire shape in full:
`TRANSFER_REQUEST → TRANSFER_OFFER(bulk_port, bulk_token) → chunked RLB1 frames on a second TLS
connection → TRANSFER_RESULT`, the bulk connection pinned to the control connection's
`identity_spki_sha256`, and a single-use, 30 s `bulk_token` as the first bytes on that connection.
[ADR-015](ADR-015-duplicate-connection-resolution.md)'s rejected-alternatives table already
establishes *why* the bulk plane is deliberately separate from the control connection's own
dedup/ownership rules: it is "authorised per transfer by a `bulk_token` … and must not inherit a
control connection's role."

None of that says how long the bulk listener lives, what ends a `bulk_token`'s validity besides
its own TTL, or what stops a transfer authorised under one control session from being honoured
after that session has died and a new one has taken its place. Phase 4's own requirements (a
reconnect must invalidate old transfer authorisation; a stale completion must not mutate a new
session's state; a token issued to one peer must not be replayable by another) need an explicit
answer, not an inferred one — this is exactly the shape of gap that produced the real bug behind
[ADR-019](ADR-019-connected-means-authenticated.md): a lower-layer success (there, a TLS
handshake; here, a socket presenting the right SPKI) quietly standing in for an authorisation
decision it does not by itself make.

Two smaller things are likewise unwritten anywhere: what makes a transferred file *trustworthy*
enough to hand to the player or to serve onward to a peer request, and a restatement — because
Phase 4 is the first place it has an operational rather than purely structural consequence — of
why `QuickId` can never appear anywhere in this design as transfer identity.

## Decision

**1. Bulk listener lifecycle — one per authenticated session, not one per transfer.** The bulk
TLS listener is opened lazily, the first time either side needs a transfer, on an ephemeral local
port, using the same identity key and certificate as the control connection
([ADR-017](ADR-017-identity-key-and-certificate.md)). It is closed synchronously whenever the
owning control session ends — `BYE`, a fatal `ERROR`, a reconnect that produces a new
`session_id`, or ordinary app teardown — and never outlives that session, never spans two
`session_id`s even for the same peer.

**2. Transfer/token issuance.** `transfer_id` is minted by the requester (fresh ULID, unpredictable,
never derived from `content_hash` alone). The provider mints `bulk_token` — 32 CSPRNG bytes — in
its `TRANSFER_OFFER`, holding it server-side in a table keyed by `(session_id, transfer_id)` with a
30 s TTL and single-use consumption. The token is delivered only inside the already-authenticated,
encrypted control channel and is never itself requestable or observable over the bulk connection.

**3. Session/generation binding is what makes a stale transfer inert.** Every row in the token
table, and every in-flight transfer-state entry, is tagged with the `session_id` active when it was
created. A bulk connection presenting a token, or a control message referencing a `transfer_id`, is
honoured only if the *current* `session_id` matches the one recorded at issuance. A mismatch is
`ERROR { code: "not_authorized" }` and the stale entry is discarded outright, never carried forward
into the new session. This is the concrete mechanism behind PROTOCOL §10 rule 4 ("transfers are not
resumed in V1") and behind the brief's requirement that a reconnect cannot let an old transfer
inject a completion into a new one, and that a token minted for one peer cannot be replayed by
another peer entirely (who in any case presents a different SPKI at the TLS layer — this makes that
protection explicit and layered rather than incidental).

**4. SPKI pinning is checked first, the token second — independently.** PROTOCOL §8.2 already
requires the bulk connection to close before the token is even read if its SPKI does not match the
control connection's. This ADR makes explicit that these are two independent checks, not one
standing in for the other: a correct SPKI without a valid, session-matched token is still refused,
and vice versa is not possible (the token cannot be read at all before the SPKI check passes).

**5. Never reuse a pairing-derived secret as `bulk_token`.** The SAS secret, the TLS exporter
output and any value derived from either has no path into `bulk_token` generation. `bulk_token` is
independently generated CSPRNG output, so compromising one secret's derivation cannot compromise
the other's — the same "distinct purposes, distinct lifetimes" discipline ADR-015 already applies
to `conn_tiebreak`/`session_id`/the discovery handle.

**6. Cache trust model.** A cache entry is *verified* — and only then playable or servable to a
peer's own request — once, in order: the full expected byte count has been received; the whole-file
SHA-256 computed **from the bytes as written to disk** (never from the bytes as received over the
socket) equals the requested `content_hash`; and the `.part` → final `rename()` has completed. A
cache-metadata row's verified flag is set exactly once, at commit, and the only way to unset it is
to delete the row entirely — there is no "re-verify to unset" path, because a corrupted promoted
file is a bug to be found and fixed elsewhere, not a state this design tries to repair in place.

**7. Content-serving consistency, without rehashing on every request.** A provider resolving
`content_hash` to bytes re-checks, before streaming, that the underlying row's last-known
size/modification signal has not changed since it was last confirmed to match that `content_hash`.
A detected change fails the request with `FILE_CHANGED` rather than serving bytes under an
assumption that may no longer hold. A full rehash on every single serve is not required: Phase 3's
lazy hashing job assigns `content_hash` once per stable file to begin with (ADR-005), so the only
real risk is a file edited or replaced on disk after indexing, which the cheap staleness check
catches.

**8. `QuickId` is never transfer identity, cache key, or dedup key — anywhere in this design.**
Restated here because Phase 4 is the first place a `QuickId` collision between two files with
different `content_hash` bytes has an operational consequence (two independently transferable
files, never merged) rather than only the data-model consequence [ADR-005 Amendment
A1](ADR-005-content-hash-track-identity.md) already fixed.

## Consequences

- One additional piece of session state per side: the token table plus a session tag on in-flight
  transfer state. It is small, in-memory only, and never persisted — consistent with the existing
  rule that bulk tokens have no log path and no durability.
- A control session tearing down mid-transfer always fails that transfer cleanly
  (`CONNECTION_LOST`) instead of leaving an orphaned, still-authorised bulk channel.
- Reconnect always requires a fresh `TRANSFER_REQUEST`/`TRANSFER_OFFER` round; no transfer
  authorisation survives a session boundary. This matches and extends PROTOCOL §10 rule 4: resuming
  would require authorisation scoped to the new session, at which point it is a new transfer, not a
  resumed one.
- Because the listener is per-session, sequential transfers within one session reuse it — concurrency
  is separately capped at one active transfer per session (an implementation choice, not a security
  property, and revisitable without touching this ADR).
- A verified cache entry survives session teardown and reconnect — it is independent of any
  session's transfer state — but the transfer that produced it does not. A repeated request for the
  same `content_hash` after a fresh connection is answered by the cache-hit path, never by opening a
  new bulk connection.

## Alternatives considered

| Option | Rejected because |
|---|---|
| One bulk listener per transfer, opened fresh per `TRANSFER_OFFER` and closed on completion | Unnecessary socket/certificate churn for several small transfers in one ride; the single-use `bulk_token` already gives per-transfer authorisation, so per-transfer socket lifetime buys no additional safety |
| Encode `session_id` directly into `bulk_token`'s bytes rather than a side lookup table | Turns a boring fixed-shape random blob into a structured value that has to be parsed and that risks leaking session-id length/format; a lookup table keeps the token itself meaningless on its own |
| Reuse the SAS secret or TLS exporter output as `bulk_token` | Explicitly ruled out by the brief and by the one-secret-one-purpose precedent already set for `conn_tiebreak`/`session_id`/the discovery handle |
| Rehash the whole file on every serve request regardless of change detection | Wasteful phone I/O (NFR-03) when `content_hash` is already guaranteed stable by Phase 3's lazy-hash job for an unchanged file; a cheap staleness check plus `FILE_CHANGED` covers the actual risk |
| Let a reconnect's new session inherit the old session's in-flight transfer state | Reintroduces exactly the "stale success implies authorisation" shape of bug ADR-019 fixed, just one layer up |

## Amendment A1 — 5 September 2026 — closure-audit hardening: this ADR's decisions, correctly implemented

**Status of the ADR: still Accepted.** The original Phase 4 pass built everything §1–§8 above describe and was CI-green on both platforms, but an independent closure audit found that several of this ADR's own decisions were not correctly wired into production code — the design was right; the implementation had gaps. Every item below is a code fix, not a design change; no wire shape, ADR decision, or protocol field moved.

**1. Live auth-generation lookup (§3), not a captured snapshot.** Both platforms' `serveTransferRequest` read `currentAuthGeneration` once and closed over that captured value for both `issueToken`/`issue` *and* the `currentGeneration` callback handed to `serve`/`BulkTransportManager.serve` — meaning a reconnect's generation bump during an in-flight `serve()` call was invisible to it, silently defeating this ADR's central "reconnect invalidates every outstanding token" claim. Fixed on both platforms: the generation is read fresh, independently, at issuance and again inside the `currentGeneration` closure at consumption time.

**2. Android bulk listener bound to session lifetime (§1), not left running.** `SharedLibraryCoordinator.onSessionBoundary()` cleared catalogue/queue state but never called `BulkTransportManager.close()`/`onNewGeneration()` — the bulk listener and its token table outlived every reconnect. iOS already called `bulkTransport.close()` on link loss but not on a fresh `.connected`; both platforms now close the bulk transport (listener + token table) on **every** session boundary (`Connected` and `LinkLost` alike), reopened lazily via `ensureListening()` the next time a transfer needs it — one explicit lifecycle owner, not calls sprinkled across the coordinator.

**3. Active transfer operation ownership.** Neither platform's `cancelDownload()` (or its Android/iOS equivalent) actually stopped the in-flight transfer — it mutated UI/queue state while the real `runDownload`/`fetch` coroutine or `Task` kept running, streaming bytes, and could still commit a "CANCELLED" transfer as COMPLETE. Fixed with an explicit operation-ownership primitive on both platforms — `core.transfer.OperationFence` (Kotlin) / `RideLinkCore.Transfer.OperationFence` (Swift), pure, mirrored, and unit-tested — that fences every `downloadStates` write behind a per-operation token: a superseded operation (cancelled, or invalidated by a session boundary) can never again mutate state, no matter how late its own cleanup runs. `BulkTransportManager.cancelActive()` / `TransferManager.cancelActive()` (new) force-close the socket the active `serve`/`fetch` call holds, so cancellation actually unblocks blocking I/O rather than merely requesting cooperative coroutine/Task cancellation. `.part` is deleted on both the cancelling and the session-boundary path, so a subsequent request for the same `content_hash` never races a still-writing old task.

**4. Session-bound cancellation (§3) reaches session loss too**, not just explicit user cancellation — `onSessionBoundary()`/the Android and iOS equivalents now cancel the active operation's `Job`/`Task`, force-close its bulk socket, and mark its state `FAILED{CONNECTION_LOST}` (a terminal state), exactly like an explicit cancel does for `CANCELLED`.

**5. Stale-result guard.** The `OperationFence` above is the concrete mechanism behind "old-session COMPLETE cannot mutate new session state" and "CANCELLED → COMPLETE is impossible" — both are now structurally impossible (a fence-token mismatch drops the write), not merely unlikely. Inbound `MANIFEST_*` handling gets the equivalent guard from a session-epoch value captured at message-dispatch time and re-checked once the handler actually runs — a plain generation counter's semantics on Android, a small lock-backed `SessionEpoch` counter on iOS (necessary there because the dispatch closure runs synchronously, off `@MainActor`, from `ManifestRelay`'s own actor, and cannot `await` back into `ControlSessionManager` to read a generation directly).

**6. One-active-transfer enforcement, iOS.** `TransferManager`'s own doc comment claimed actor isolation alone caps concurrency at one active transfer, "the way Android's `Mutex activeTransferMutex` does." That claim was wrong: Swift actors are reentrant across a suspension point, and `serve`/`fetch` both suspend repeatedly (`accept()`, every socket read/write) — a second, unstructured call could run its synchronous prologue while the first was parked at one of those `await`s. Fixed with an explicit `transferInProgress` gate, acquired/released synchronously with no `await` between check and set, so two overlapping calls cannot both win it; a second concurrent call is rejected outright rather than queued (this pass's chosen design — simpler than a hand-rolled async semaphore, and sufficient since Android's own cap is likewise "one active transfer," just enforced by blocking rather than rejecting).

**7. Peer `TRANSFER_CANCEL` (PROTOCOL §8.2) is now actually handled**, on both requester and provider roles. Production code on both platforms parsed `TRANSFER_CANCEL` but took no action on receipt and never sent one on local cancellation — cancellation relied entirely on "the bulk connection dropping," which nothing made happen. `cancelDownload()` now sends `TRANSFER_CANCEL{reason: "user_cancelled"}` to the peer; the provider-side handler force-closes the active bulk connection via `cancelActive()`, but only if the cancel names the `transfer_id` currently being served — a cancel for a stale, foreign, or already-finished transfer is a no-op.

**8. Cache-commit-then-report ordering (§6).** iOS's `runDownload` used `try? cacheRepository.commit(...)`, silently swallowing a metadata-commit failure and still sending `TRANSFER_RESULT{ok: true}` / marking the transfer `COMPLETE` — a state divergence between what the peer was told and what was actually persisted. Fixed to `try`/`catch`: a commit failure now routes to `FAILED`/`ok: false`, never `COMPLETE`. (Android's equivalent path had no `try`/`catch` at all, so a commit exception propagated uncaught — not a false-COMPLETE bug, but a different real one: it permanently wedged the one-active-transfer queue, since the cleanup that clears `activeDownload` never ran. Fixed the same way, with `runCatching` around the commit.)

**9. Sender-side max-transfer-size guard (PROTOCOL §8.2 / `TransferBounds.maxTransferSizeBytes`).** Both platforms' `serveTransferRequest` built and sent a `TRANSFER_OFFER` for a local file with no size check at all, relying entirely on the *receiver's* `TransferCodec.parseOffer` to reject an oversized offer after the fact — wasted round-trip at best, and (Android) a provider left holding the one-active-transfer slot for the full 30 s token TTL waiting for a requester that will never connect, starving every other transfer. Both platforms now check `sizeBytes <= TransferBounds.maxTransferSizeBytes` before constructing the offer at all.

**10. Bulk frame ordering (PROTOCOL §8.2's explicit `chunk_index`), not merely counted.** Both platforms' requester-side `fetch` counted arriving frames toward `expectedChunkCount` without checking that each frame's `chunk_index` was the exact expected next value, and stopped reading the instant the count was satisfied — never checking whether an unread, already-parsed leftover or an unread trailing byte on the wire represented one frame too many. Both now reject a duplicate, skipped, out-of-order, or extra chunk index as `PROTOCOL_ERROR`, and after satisfying the count, read once more expecting a clean provider-initiated close (EOF) — anything else is also `PROTOCOL_ERROR`. Whole-file SHA-256 verification remains the authoritative integrity check; this closes the framing-level gap PROTOCOL §8.2 describes but did not, until now, enforce.

**11. Bulk-token hardening.** `BulkTokenTable.issue` on both platforms silently overwrote any existing entry for a `transfer_id`, including a still-live, unconsumed one — a resent or replayed `TRANSFER_REQUEST` reusing an id could invalidate a token someone was about to present, with no signal why. Both platforms gained `tryIssue`, which returns "no token" instead of overwriting a live entry; production callers use it. Token comparison (`entry.token != presentedToken`) also moved to a constant-time compare on both platforms — low real-world severity (this check runs over an already TLS/SPKI-authenticated local link), but cheap and clean to fix, so it was.

**12. Cache-only playback ownership (brief §19, architectural).** Neither platform's Shared Library UI offered "Play" for a verified Phase-4 cache entry that was never imported into the Phase 3 library — only for a `content_hash` that also happened to match an imported row. Closed on both platforms without a second player, a second queue, or a fake imported-library row: `MusicCoordinator` gains a small, coordinator-local map from a freshly minted `LocalEntryId` to the cache file's location (`ExternalCacheSource`/its Swift mirror), populated only by `playExternalVerifiedCachedTrack`, and the existing `LocalQueue`/`Player` are used exactly as they are for an imported track. Provenance stays distinct — nothing is ever written to `LibraryRepository` for a cache-only play — and eviction now also protects whichever cache entry is currently playing (`activeCacheHash`, threaded into every `TransferCacheRepository.commit`'s `locked` set), closing the latent gap this feature would otherwise have reopened.

**13. `QuickId` used as SwiftUI row identity (§8's rule, iOS only).** `SharedLibraryView`'s `ForEach` keyed rows on `\.quickId.value` alone — this ADR's §8 and ADR-005 Amendment A1 both already establish that `QuickId` is a 128 KiB sample, not guaranteed unique, so two genuinely different manifest entries sharing one would silently collapse onto one SwiftUI row (undefined behaviour, per this codebase's own `LibraryView`/`MusicCoordinator` comments about the same class of bug). Fixed with `ManifestEntry.rowId` (`RideLinkCore`, pure, unit-tested): `quickId` composited with `contentHash` when present, so two entries sharing a `QuickId` but carrying different `ContentHash`es are provably distinct rows. Android's `SharedLibraryScreen` never had this bug — it iterates a plain `Column`, not a keyed lazy list.

**Confirmed already correct — no fix.** Two findings this audit checked came back negative: iOS's `SharedLibraryCoordinator.availability(for:)` already queries `TransferCacheRepository.isVerifiedCached` directly (a real DB read) rather than any session-scoped map, so persisted-cache availability across a process restart was already correct there; only Android's `SharedLibraryScreen` inferred cache state from session-scoped `DownloadState` and needed a persisted `verifiedHashes()`/`cachedHashes` fix. `TRANSFER_RESULT` handling (as opposed to `TRANSFER_CANCEL`, item 7 above) was already correct on both platforms — the requester trusts only its own verification, never a peer's reported result, matching PROTOCOL §8.2's design exactly.

**Documented, unchanged limitation — not a fix.** §7's staleness check (`size == indexed size`) cannot detect a same-size, different-content replacement of a local file after indexing. This is unchanged by this amendment: the whole-file SHA-256 verification on the receiving side still catches it before any bytes are trusted, so the cost of the gap is a wasted transfer attempt, never a corrupted cache — exactly the tradeoff §7 already accepted. Adding a stronger cheap signal (a stored modification time) would require a Phase 3 storage schema migration on both platforms, which this pass judged disproportionate to a gap with no integrity consequence.

**Deliberately not changed.** The wire format (§2's token shape, §3's session/generation binding rule, §6's cache-trust ordering, §7's staleness check) is exactly as this ADR already specified — every item above is a production bug against an already-correct decision, not a new decision. `docs/STATUS.md` §2u records the original implementation session; the closure-audit session that produced this amendment is recorded separately, distinguishing "CI-green" from "correct," per this project's own discipline (ADR-019's precedent).

## Amendment A2 — 7 September 2026 — a second closure-audit pass finds two coordinator/session-ownership gaps A1 itself did not close

**Status of the ADR: still Accepted.** A1 (above) was CI-green on both platforms and closed eighteen real findings, but a narrower, independent follow-up review — scoped to exactly two lifecycle/session-ownership questions A1 did not ask — found two more. Both are production bugs against this ADR's already-correct decisions, exactly like every item in A1; neither moves a wire shape, a protocol field, or this ADR's own §1–§8.

**Finding A — provider-side transfer ownership was a plain, unguarded var.** `serveTransferRequest` set a bare `activeServeTransferId = request.transferId` *after* minting a token and sending the offer, then launched `BulkTransportManager.serve`/`TransferManager.serve` in an unstructured `Task`/coroutine. Nothing stopped a second, concurrent `TRANSFER_REQUEST` from overwriting that var before the first request's `serve()` call had actually acquired the transport's own one-active-operation slot (Android's `activeTransferMutex`, iOS's `transferInProgress` gate) — so a peer `TRANSFER_CANCEL` naming the *first* transfer could arrive while the var pointed at the *second*, and be silently ignored (the real transfer keeps running, uncancelled), or a `TRANSFER_CANCEL` naming the second, not-yet-actually-serving transfer could reach `cancelActive()` and force-close the *first* transfer's real, unrelated socket — cancelling the wrong operation entirely. iOS's explicit `transferInProgress` gate (Finding E, A1) narrows this window but does not close it: the coordinator's own bookkeeping var is set before, not atomically with, that gate's acquisition. The same class of bug reaches across roles, not just within the provider role: `activeDownload` (requester) and `activeServeTransferId` (provider) were two independent, uncoordinated fields, even though both roles share the same single real bulk socket underneath (`BulkTransportManager`/`TransferManager`'s own mutex/gate already treats `serve` and `fetch` as one shared slot) — so a `cancelDownload()` racing an inbound `TRANSFER_REQUEST`, or vice versa, could call `cancelActive()` and close the *other* role's genuinely active socket.

**Finding A — fixed with one pure, mirrored ownership primitive, not a var.** `core.transfer.BulkOperationGate` (Kotlin) / `RideLinkCore.Transfer.BulkOperationGate` (Swift) is the single authoritative owner of the one bulk-operation slot a session may have active — `Requester(transferId, contentHash)` or `Provider(transferId, contentHash, peerSpki, sessionGeneration)`, never both. `tryAcquire` refuses outright whenever the slot is already held (by either role), so ownership can never be silently overwritten; `TRANSFER_CANCEL` routing (`handlePeerCancel`) asks `bulkGate.isOwner(transferId)` rather than trusting a var that may already be stale; `releaseIfOwner` is keyed on `transfer_id` alone (a fresh, unpredictable ULID per §2, never reused) and silently no-ops for anyone but the current holder, so a stale/late release from a superseded operation can never clear a fresher one — the same "late cleanup cannot mutate what superseded it" property `OperationFence` already gives transfer-state writes, applied here to ownership itself. `invalidate()` (called from `onSessionBoundary`, unconditionally, alongside `bulkTransport.close()`'s own force-close of whatever real socket was active) frees the slot no matter who holds it, so an old holder's own eventual cleanup finds nothing left to (mis)clear.

- **`serveTransferRequest`** now acquires the gate *before* ever sending a `TRANSFER_OFFER` or minting a token — a denied acquisition sends no offer at all (no wire shape exists for "busy," per this ADR's own §2 discussion of what the token table can and cannot express; the peer's own negotiation timeout resolves it, exactly as an unreachable peer would).
- **`cancelDownload`/`handlePeerCancel`** now call the transport's real `cancelActive()` only when `bulkGate.isOwner(transferId)` is true for the transfer named — never merely because a coordinator-level queue/bookkeeping var happens to match.
- **Cross-role exclusivity** (brief §17/§18; already the transport layer's own contract — see "concurrency is separately capped at one active transfer per session" above) is now enforced one layer up too: `pumpQueue` acquires the gate as `Requester` before ever sending a local `TRANSFER_REQUEST`, and simply leaves the head of `downloadQueue` queued (no new second queue) if the slot is already held by an active provider operation; the provider operation's own cleanup calls `pumpQueue()` again once it releases the slot, so a deferred local download resumes automatically. Neither role can "steal" `cancelActive()` from the other, because only one of them can ever hold the gate at a time.
- **Provider operation identity snapshots the peer/session that authorised it** (`peerSpki`, `sessionGeneration`) at acquisition time — a deliberately separate concept from the token table's own **live** generation re-check at consumption time (§3's "reconnect invalidates every outstanding token" guarantee), which this amendment leaves untouched.

**Finding B — inbound `TRANSFER_*` dispatch had no session-generation guard, unlike `MANIFEST_*`.** A1's Finding S added a live-generation capture-and-recheck to inbound `MANIFEST_*` dispatch (captured in the sink closure at message-dispatch time, off the wire; rechecked once the scheduled coroutine/Task actually runs) specifically to stop a session boundary from letting a stale message mutate the new session's state. `TRANSFER_*` dispatch (`TransferSink`/`TransferSinkAdapter`) never got the same guard: `TRANSFER_REQUEST`, `TRANSFER_OFFER`, `TRANSFER_PROGRESS`, `TRANSFER_RESULT` and `TRANSFER_CANCEL` were all dispatched to `handleTransferMessage` with no captured generation/epoch at all, so a message read off the wire under session A, but not actually processed until after a reconnect had already produced session B, could still run against session B's current peer SPKI/generation — the same "lower-layer success standing in for authorisation" shape ADR-019 already named, one layer up.

**Finding B — fixed by giving `TRANSFER_*` dispatch the exact same guard `MANIFEST_*` already had.** Android's `TransferSink` lambda now captures `controlSessionManager.currentAuthGeneration` at dispatch time, exactly like `ManifestSink`'s lambda; `handleTransferMessage(message, generation)` drops the message before touching `bulkGate`, `pendingOfferTransferId`, or any provider/requester state if that captured generation no longer matches the live one. iOS's `TransferSinkAdapter` now captures `sessionEpoch.current()` at dispatch time (the same lock-backed counter `ManifestSinkAdapter` already used, for the same reason: the dispatch closure runs synchronously, off `@MainActor`, from the relay's own actor, and cannot `await` back into `ControlSessionManager` to read a generation directly); `handleTransferMessage(_:epoch:)` drops the message on a mismatch. Applying the guard once, at the top of `handleTransferMessage`, before the `switch`, covers every `TRANSFER_*` variant uniformly — a stale `TRANSFER_REQUEST` cannot be served under the new peer's SPKI/generation, a stale `TRANSFER_OFFER` cannot satisfy a new session's pending request (structurally: it is dropped before `onOfferReceived` ever runs), and a stale `TRANSFER_CANCEL` cannot cancel a new session's transfer.

**Bulk concurrency contract, made explicit rather than left implicit.** This ADR's own Consequences section already states it: "concurrency is separately capped at one active transfer per session... an implementation choice, not a security property." `BulkTransportManager`'s `activeTransferMutex` and `TransferManager`'s `transferInProgress` already enforced this **across both roles** at the transport layer (one shared mutex/gate guards both `serve` and `fetch`) — Finding A's cross-role gap was that the *coordinator* layer had no equivalent single owner, only two independent per-role vars. `BulkOperationGate` closes that gap without changing the contract: one bulk operation total per session, requester or provider, never both, exactly as this ADR's own text already said.

**Tests.** `core.transfer.BulkOperationGateTest` (Android, 9 cases) / `RideLinkCore.Transfer.BulkOperationGateTests` (iOS, 9 cases) are pure, deterministic proofs of every ownership/cancel-routing/cross-role property above — no coroutines, no sockets, no timing — mirroring exactly how `OperationFenceTest`/`OperationFenceTests` already prove the sibling transfer-state-write fence, and for the same reason this ADR's own A1 disclosed: a dedicated `SharedLibraryCoordinator`-level real-TLS integration test remains **not** written, for the identical, already-disclosed reason (app/platform-target test source sets do not expose the real-TLS test harness living in `network`'s/`RideLinkPlatform`'s own test source sets, without a `testFixtures`/separate-test-target export this pass again judged disproportionate to build correctly under its own time budget — the same call A1 made, restated rather than silently repeated). Findings A and B are therefore verified by: (a) code inspection against the fix, (b) the pure `BulkOperationGate` unit tests proving the ownership primitive itself is correct, and (c) full Debug/Release builds succeeding on both platforms with the complete existing test suite re-run clean. `docs/STATUS.md` §2w records this session in full, including exact test counts and gate results.
