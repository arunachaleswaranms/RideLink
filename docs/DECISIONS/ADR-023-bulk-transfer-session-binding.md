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
