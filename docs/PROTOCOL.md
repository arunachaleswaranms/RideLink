# RideLink Peer Protocol — v1

**Status:** specification baseline for Phase 1. **Wire version:** `1`.
Machine-readable schemas and golden vectors land in `protocol/` during Phase 1, alongside the
codecs that consume them.

---

## 1. Scope and framing

The **control plane** carries everything except real-time voice: pairing, capabilities, clock
sync, playback commands, queue replication, catalogue manifests and transfer negotiation
([ARCHITECTURE §1.1](ARCHITECTURE.md#11-the-three-data-planes)).

| Property | Value |
|---|---|
| Transport | TCP over TLS 1.3, local network only |
| Service | `_ridelink._tcp` (DNS-SD), dynamic port |
| Encoding | UTF-8 JSON, one object per frame ([ADR-006](DECISIONS/ADR-006-json-control-encoding.md)) |
| Framing | `uint32` big-endian byte length ‖ JSON bytes |
| Max frame | 256 KiB — control messages are small; anything larger is a bug or an attack |
| Keepalive | `PING` every 2 s; peer declared lost after 6 s of silence |

File bodies use a **separate TLS connection** so a large transfer cannot head-of-line-block a
`PAUSE`. Its frames are binary, not JSON (§8).

Voice never touches this plane: it is WebRTC/DTLS-SRTP, negotiated *through* it (§7).

---

## 2. Envelope

Every control frame is an object with a fixed envelope and a type-specific `payload`.

```json
{
  "v": 1,
  "type": "PLAY",
  "session_id": "01J9Z4M0Q7XK2V8R3T6Y1N5B",
  "sender_id": "b7c1e0d9a4f28356",
  "msg_id": "01J9Z4M128H3PQ4R5S6T7U8V9W",
  "seq": 412,
  "sent_at_mono_us": 88123456789,
  "requires_ack": true,
  "payload": { }
}
```

| Field | Type | Required | Meaning |
|---|---|---|---|
| `v` | int | yes | Protocol **major** version. Mismatch ⇒ refuse the session with `ERROR/version_mismatch`. |
| `type` | string | yes | Message type (§4–§9). Unknown types are **ignored, logged, not fatal** — this is the forward-compatibility rule. |
| `session_id` | ULID string | yes | Identifies one connected session. Regenerated on every fresh `CONNECTING`, **preserved across `RECONNECTING`**. |
| `sender_id` | 16-hex string | yes | Durable `peer_id`, assigned at pairing. |
| `msg_id` | ULID string | yes | Unique per message. Used for ack correlation and duplicate suppression. |
| `seq` | uint64 | yes | Per-sender monotonic counter, starts at 1 per session. Gaps are detectable; duplicates are droppable. |
| `sent_at_mono_us` | uint64 | yes | Sender's **monotonic** microseconds. For diagnostics and RTT only — never for scheduling (§6). |
| `requires_ack` | bool | no (default `false`) | If true, receiver must reply `ACK`. |
| `payload` | object | yes | Type-specific. `{}` when empty — never `null`. |

**Rules that make evolution safe:**

1. Receivers **must ignore unknown `payload` fields** — additive changes are non-breaking and do not need a version bump.
2. Receivers **must ignore unknown `type` values** — new message types can be introduced against an older peer.
3. `v` increments **only** on a breaking change to the envelope or to an existing field's meaning.
4. Capability negotiation (§4.2), not version arithmetic, gates optional features.
5. Timestamps are always `_mono_us` (monotonic) or `_session_us` (session clock). No field ever carries wall-clock time. This naming convention is deliberate: a reviewer can spot a scheduling bug by field name alone.

### 2.1 Replay and ordering

- Per sender, a frame with `seq` ≤ the highest already applied is **dropped** (idempotent replay defence).
- A gap in `seq` on a reliable ordered transport means frames were lost, which on TCP means the connection is broken ⇒ transition to `RECONNECTING` rather than attempting repair.
- `msg_id`s seen in the last 256 messages are remembered to suppress duplicates across a reconnect that replays a small tail.
- **Playback commands are ordered by `command_seq` assigned by the leader (§5), never by `seq` or by timestamp comparison.**

---

## 3. Message catalogue

| Group | Types | Phase |
|---|---|---|
| Session | `HELLO`, `HELLO_ACK`, `CAPABILITIES`, `ACK`, `ERROR`, `BYE` | 1 |
| Pairing | `PAIR_REQUEST`, `PAIR_CONFIRM`, `PAIR_RESULT` | 1 |
| Health/clock | `PING`, `PONG`, `METRICS` | 1 |
| Resync | `STATE_REQUEST`, `STATE_SNAPSHOT` | 1 |
| Voice | `VOICE_OFFER`, `VOICE_ANSWER`, `VOICE_ICE`, `VOICE_STATE` | 2 |
| Catalogue | `MANIFEST_REQUEST`, `MANIFEST`, `MANIFEST_DELTA` | 4 |
| Transfer | `TRANSFER_REQUEST`, `TRANSFER_OFFER`, `TRANSFER_PROGRESS`, `TRANSFER_RESULT`, `TRANSFER_CANCEL` | 4 |
| Playback | `PLAY`, `PAUSE`, `RESUME`, `SEEK`, `NEXT`, `PREVIOUS`, `POSITION_REPORT`, `PLAYBACK_STATE` | 5 |
| Queue | `QUEUE_ADD`, `QUEUE_REMOVE`, `QUEUE_MOVE`, `QUEUE_SNAPSHOT` | 5 |

---

## 4. Session establishment

### 4.1 Handshake

```
        A (initiator)                              B (acceptor)
             │                                          │
             │──── TCP connect + TLS 1.3 handshake ─────►│
             │◄──── certificates exchanged ─────────────►│
             │                                          │
             │  both sides check pin. unknown peer → §4.3 pairing
             │                                          │
             │──── HELLO ──────────────────────────────►│
             │◄─── HELLO_ACK ───────────────────────────│
             │◄─── CAPABILITIES ───────────────────────►│   (both directions)
             │◄─── PING/PONG × 11 (clock sync, §6) ────►│
             │                                          │
             ▼                                          ▼
        state = CONNECTED                        state = CONNECTED
```

`HELLO` payload:

```json
{
  "peer_id": "b7c1e0d9a4f28356",
  "display_name": "Rider",
  "platform": "android",
  "os_version": "15",
  "app_version": "0.1.0",
  "protocol_versions": [1],
  "session_id_proposal": "01J9Z4M0Q7XK2V8R3T6Y1N5B",
  "cert_fingerprint": "sha256:9f2c…"
}
```

`HELLO_ACK` echoes the accepted `session_id` and `protocol_version`, and states
`leader_peer_id` — computed, not negotiated: the lexicographically smaller `peer_id`
([ARCHITECTURE §5](ARCHITECTURE.md#5-leadership-and-command-ordering)). Both sides compute it
independently and it must agree; disagreement is `ERROR/leader_mismatch`.

`session_id_proposal` is resolved deterministically: the **leader's** proposal wins, so a
simultaneous mutual connect converges without a tie-break round.

### 4.2 `CAPABILITIES`

```json
{
  "audio": {
    "voice_codecs": ["opus/48000/2", "opus/16000/1"],
    "webrtc_supported": true,
    "playback_rate_control": true,
    "output_route": "bluetooth_a2dp",
    "input_route": "bluetooth_hfp",
    "sample_rate": 48000
  },
  "library": { "track_count": 1284, "hash_complete": false, "formats": ["mp3","aac","m4a","flac"] },
  "features": ["transfer.v1", "sync.v1", "queue.v1", "vox"],
  "limits": { "max_transfer_bytes": 536870912, "max_chunk_bytes": 65536 }
}
```

`features` is the extension point: a peer offers only what it implements, and both sides
intersect. This is how a newer app stays compatible with an older one without bumping `v`.

`playback_rate_control` matters — the drift ladder's rate-nudge tier
([ARCHITECTURE §7.3](ARCHITECTURE.md#73-drift-correction)) requires it on **both** peers;
without it the ladder degrades to dead-band plus hard seek.

### 4.3 Pairing (first meeting only)

```
A                                                            B
│─ PAIR_REQUEST { display_name, platform, cert_fingerprint } ►│
│                                                             │
│  both derive sas6 = SAS(tls_exporter("RIDELINK-PAIR-v1", 32))
│  both display the same 6 digits; both users confirm
│                                                             │
│─ PAIR_CONFIRM { sas6_accepted: true } ─────────────────────►│
│◄─ PAIR_RESULT { accepted: true, peer_id, cert_fingerprint } ─│
```

- `sas6` = decimal of the first 20 bits of `HKDF-SHA256(exporter_secret, "RIDELINK-SAS-v1")`, zero-padded to 6 digits.
- The **TLS exporter secret** binds the code to this specific TLS session and to both certificates. A man-in-the-middle terminates two distinct TLS sessions and therefore cannot produce matching codes on the two screens. This is what makes the confirmation a real check rather than theatre.
- `sas6` is never logged, never transmitted, and never stored.
- On success each side persists `{peer_id, cert_fingerprint, display_name, paired_at}`.
- Rate limit: 3 pairing attempts per minute per remote address; on failure, close the connection.

### 4.4 `ERROR` and `BYE`

```json
{ "code": "version_mismatch", "message": "peer requires v2", "fatal": true, "context": {} }
```

Codes: `version_mismatch`, `leader_mismatch`, `untrusted_peer`, `pin_mismatch`,
`pairing_rejected`, `pairing_rate_limited`, `capability_missing`, `frame_too_large`,
`malformed_frame`, `session_unknown`, `internal`.

`message` is for humans and **must not** contain paths, tokens or the SAS.
`BYE { reason }` is a clean teardown ⇒ `ENDING`, and suppresses the reconnect logic — a
deliberate `BYE` must not trigger an automatic reconnect attempt.

---

## 5. Playback commands

Any peer may *issue*; the **leader** assigns order. A follower sends its intent to the leader;
the leader stamps `command_seq` and `effective_at_session_us`, then broadcasts to both. Both
apply on receipt. Determinism is structural: there is one serialisation point.

Common fields on every playback command payload:

| Field | Meaning |
|---|---|
| `command_seq` | uint64, leader-assigned, strictly increasing. **The ordering authority.** |
| `effective_at_session_us` | session-clock instant at which the command takes audible effect |
| `issued_by` | `peer_id` of the user who pressed the button (for UI attribution) |
| `queue_revision` | queue version this command assumes; stale ⇒ reject and resync |

```json
// PLAY
{ "command_seq": 87, "effective_at_session_us": 90210500000,
  "track_hash": "sha256:1f3a…", "position_ms": 0,
  "queue_item_id": "01J9…", "queue_revision": 12, "issued_by": "b7c1…" }

// PAUSE                                          // SEEK
{ "command_seq": 88, "position_ms": 45120,        { "command_seq": 90, "target_position_ms": 61000,
  "effective_at_session_us": 90260000000,           "effective_at_session_us": 90512000000,
  "queue_revision": 12, "issued_by": "a3f1…" }      "queue_revision": 12, "issued_by": "a3f1…" }

// NEXT / PREVIOUS
{ "command_seq": 91, "effective_at_session_us": 90600000000,
  "queue_revision": 12, "issued_by": "b7c1…" }
```

Rules:

1. `command_seq` ≤ last applied ⇒ **drop**. Late duplicates cannot rewind playback.
2. `effective_at_session_us` already passed ⇒ apply immediately and record the lateness in `METRICS`. Never skip the command; never schedule into the past.
3. `queue_revision` older than local ⇒ reply `ERROR/stale_revision`; the issuer refreshes via `STATE_REQUEST`. This is what stops two people pressing *next* from double-skipping.
4. `PLAY` for a `track_hash` not locally present ⇒ do **not** start. Enter `TRANSFER_PENDING`, request transfer (§8), and let the leader reschedule. A remote-only track cannot begin synchronised playback (REQUIREMENTS §9.4).
5. Scheduling lead: `LEAD = max(120 ms, 4 × rtt_p95)`.

`POSITION_REPORT` (every 5 s, both directions) drives drift detection:

```json
{ "track_hash": "sha256:1f3a…", "position_ms": 45120,
  "at_session_us": 90260000000, "playing": true, "playback_rate": 1.0 }
```

`PLAYBACK_STATE` is the full authoritative snapshot the leader emits after any correction or
reconnect — the reconciliation anchor, not an incremental update.

---

## 6. Clock synchronisation

```json
// PING                                  // PONG
{ "t1_mono_us": 88123456789 }            { "t1_mono_us": 88123456789,
                                           "t2_mono_us": 41200000123,
                                           "t3_mono_us": 41200000456 }
```

Estimator, outlier rejection and the EWMA are specified in
[ARCHITECTURE §7.1](ARCHITECTURE.md#71-offset-estimation). The wire contract is only: **echo
`t1` unchanged, and report receive and send instants separately.** Collapsing `t2`/`t3` into one
timestamp would fold the responder's processing delay into the offset estimate — which is
precisely the error the four-timestamp form exists to cancel.

`METRICS` (every 10 s, diagnostics only — FR-023):

```json
{ "rtt_ms": 8.4, "rtt_p95_ms": 19.1, "jitter_ms": 2.1, "packet_loss_pct": 0.0,
  "clock_offset_us": -46912337666, "clock_offset_stddev_us": 1400,
  "music_drift_ms": 12, "reconnect_count": 1, "voice_state": "active" }
```

---

## 7. Voice negotiation (Phase 2)

The control plane is the signalling channel for WebRTC. It carries SDP and ICE and nothing else
audio-related; media flows over DTLS-SRTP directly between the phones.

```
leader ── VOICE_OFFER  { sdp, ice_ufrag_hint }  ──► follower
leader ◄─ VOICE_ANSWER { sdp } ──────────────────── follower
       ◄─ VOICE_ICE    { candidate, mid } ────────►  (both, until complete)
       ◄─ VOICE_STATE  { state, mic_muted, mode } ─►  (both, on change)
```

`VOICE_STATE.state` ∈ `idle | negotiating | connecting | active | failed | closed`.
`mode` ∈ `continuous | vox | ptt` — the modes of REQUIREMENTS §8.

Since both peers are on the same LAN, host candidates suffice: **no STUN, no TURN, no ICE
servers**. This is what keeps the voice path genuinely internet-free (FR-024). ICE is configured
with an empty server list, which also removes an accidental-egress path.

`VOICE_STATE` failing must not disturb music (FR-025) — the two planes are independent by
construction.

---

## 8. Catalogue and transfer (Phase 4)

`MANIFEST` entries are deliberately minimal — no absolute paths, per REQUIREMENTS §11:

```json
{ "manifest_revision": 7, "complete": true, "entries": [
  { "content_hash": "sha256:1f3a…", "quick_id": "sha256:77bd…",
    "work_key": "beatles|come together|259", "title": "Come Together",
    "artist": "The Beatles", "album": "Abbey Road", "duration_ms": 259000,
    "codec": "mp3", "bitrate_kbps": 320, "size_bytes": 10387456,
    "filename": "come-together.mp3", "has_artwork": true }
]}
```

`filename` is a **basename only**. `content_hash` may be `null` while background hashing is
incomplete; such an entry is displayable but **not** transferable or sync-eligible
([ARCHITECTURE §8.1](ARCHITECTURE.md#81-track-identity-adr-005)).
`MANIFEST_DELTA { manifest_revision, added[], removed[] }` carries subsequent changes.

### 8.1 Transfer

```
requester                                              provider
    │── TRANSFER_REQUEST { content_hash, transfer_id } ──►│
    │◄─ TRANSFER_OFFER { transfer_id, size_bytes,        │
    │                    chunk_size, chunk_count,        │
    │                    bulk_port, bulk_token } ────────│
    │                                                     │
    │═══ second TLS connection to bulk_port ═════════════►│
    │═══ binary chunk frames ════════════════════════════►│
    │                                                     │
    │◄─ TRANSFER_PROGRESS { transfer_id, bytes, pct } ────│   (either direction)
    │◄─ TRANSFER_RESULT { transfer_id, ok, sha256 } ──────│
```

Bulk frames are binary, not JSON — base64 would cost 33 % on the largest payloads for no benefit:

```
┌────────────┬────────────┬────────────┬──────────────┐
│ magic 'RLB1'│ chunk_index│ byte_length│ payload      │
│ 4 bytes     │ uint32 BE  │ uint32 BE  │ ≤ 64 KiB     │
└────────────┴────────────┴────────────┴──────────────┘
```

`bulk_token` is 32 random bytes, single-use, valid 30 s, sent as the first frame on the bulk
connection. It binds the bulk connection to the authorised request so that TLS alone is not the
only thing standing between a peer and arbitrary file reads. **Never logged.**

Explicit `chunk_index` in every frame is what leaves the door open for resume: a future
`TRANSFER_OFFER` may include `have_chunks[]` and the provider sends only the gaps. Resume is out
of V1 scope (FR-011 "if practical") but costs nothing to keep possible.

Integrity, per REQUIREMENTS §11 — the receiver:

1. streams chunks to `cache/incoming/<content_hash>.part`, one chunk in RAM at a time;
2. on the last chunk, checks byte count, then recomputes SHA-256 **from the file on disk**;
3. compares against the requested `content_hash` *and* `TRANSFER_RESULT.sha256`;
4. on match, `rename()` into the library — atomic, same filesystem;
5. on mismatch, deletes the `.part` and reports `TRANSFER_RESULT { ok: false }`.

Hashing the file as written, rather than the bytes as received, is the point: it catches
truncated writes and disk-full conditions, not just network corruption.

`TRANSFER_CANCEL { transfer_id, reason }` is valid from either side at any time; both drop the
bulk connection and the requester deletes its `.part`.

---

## 9. Queue replication (Phase 5)

```json
// QUEUE_ADD                                    // QUEUE_SNAPSHOT
{ "command_seq": 92, "queue_revision": 13,      { "queue_revision": 13, "items": [
  "items": [ { "queue_item_id": "01J9…",          { "queue_item_id": "01J9…",
               "track_hash": "sha256:1f3a…",        "track_hash": "sha256:1f3a…",
               "added_by": "a3f1…",                 "added_by": "a3f1…", "order": 0,
               "position": "end" } ] }              "status": "ready" } ],
                                                  "current_index": 0 }
```

- The leader owns `queue_revision`, incrementing on every accepted mutation.
- `queue_item_id` is a ULID minted by the *issuer*, so an add is idempotent under retry.
- `order` uses sparse integers (steps of 1024) so `QUEUE_MOVE` rarely needs to renumber.
- `status` ∈ `ready | remote_only | transferring | unavailable` — derived locally from presence, never trusted from the peer.
- A follower mutation that loses a race is rejected with `ERROR/stale_revision`; the follower applies the next `QUEUE_SNAPSHOT`. **The snapshot always wins** — there is no merge algorithm to get subtly wrong.

---

## 10. Reconnect and reconciliation

`session_id` survives a reconnect; that is what distinguishes resuming from starting over.

```
link lost ──► RECONNECTING          (local playback CONTINUES; sync suspended)
    │
    ├─ rediscover peer via mDNS, or reuse last known host:port
    ├─ TLS + pin check (no pairing, no SAS — trust already exists)
    ├─ HELLO { session_id = <previous> }  ⇒  resume, not restart
    ├─ re-run clock sync from scratch (11 samples) — the old offset is stale
    ├─ STATE_REQUEST  ──►  STATE_SNAPSHOT
    └─ reconcile, then return to the state we left (CONNECTED or RIDE_ACTIVE)
```

Backoff: 0.5, 1, 2, 4, 8, 8, 8 … seconds with ±20 % jitter, for up to 120 s, then
`DISCONNECTED`. Jitter matters because both phones detect the loss simultaneously and would
otherwise retry in lockstep forever.

`STATE_SNAPSHOT` is the authoritative reconciliation payload (FR-021):

```json
{ "leader_peer_id": "a3f1…", "command_seq": 94, "queue_revision": 13,
  "playback": { "track_hash": "sha256:1f3a…", "position_ms": 128400,
                "playing": true, "at_session_us": 90990000000 },
  "queue": { "…": "QUEUE_SNAPSHOT shape" },
  "manifest_revision": 7,
  "transfers_in_flight": [ { "transfer_id": "01J9…", "content_hash": "sha256:…",
                             "bytes_done": 4194304 } ] }
```

Reconciliation rules:

1. The **leader's** snapshot is authoritative. The follower conforms. No merge.
2. The follower adopts the leader's `command_seq` and `queue_revision` wholesale.
3. Playback position is re-derived from `position_ms` + `at_session_us` against the *newly*
   measured offset, then handed to the drift ladder — which usually means one hard seek, since
   >120 ms of divergence over a reconnect is expected.
4. Transfers are **not** resumed in V1; in-flight `.part` files are discarded and re-requested.
5. If the follower was mid-`PLAY` on a track the leader has since changed, the leader's state wins and the follower switches.

---

## 11. Test vectors

`protocol/vectors/` is shared by both platforms' unit suites — the mechanism that makes wire
incompatibility a laptop-side test failure instead of a roadside mystery
([ARCHITECTURE §9.3](ARCHITECTURE.md#93-the-shared-seam)).

| File | Asserts |
|---|---|
| `envelope/*.json` | encode/decode round-trip, unknown-field tolerance, unknown-type tolerance, oversize rejection, malformed rejection |
| `clock/*.json` | given 11 `(t1,t2,t3,t4)` samples with injected outliers ⇒ expected offset/rtt/jitter |
| `drift/*.json` | given drift series ⇒ expected ladder action (`none`/`nudge`/`seek`/`fail`) |
| `queue/*.json` | concurrent mutation sequences ⇒ expected final queue and revision |
| `manifest/*.json` | two manifests ⇒ expected presence classification and delta |
| `ordering/*.json` | out-of-order/duplicate/stale `command_seq` streams ⇒ expected applied set |
| `sas/*.json` | fixed exporter secret ⇒ expected 6-digit SAS (test-only secrets) |

Each is `{ "name", "input", "expected" }`, so a single table-driven runner per platform covers
the file. A vector is added for every protocol bug found on a device — that is the regression
discipline in the brief made concrete.

---

## 12. Reserved for later

Named now so the shape stays compatible; **not** implemented in V1:
`TRANSFER_OFFER.have_chunks[]` (resume), `VOICE_STATE.mode = ptt_remote`,
`METRICS.thermal_state`, `CAPABILITIES.features += "le_audio"`, group sessions (would need
`recipient_id` in the envelope — deliberately absent, since two-peer is the V1 scope).
