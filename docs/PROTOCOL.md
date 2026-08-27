# RideLink Peer Protocol — v1

**Status:** specification baseline for Phase 1. **Wire version:** `1`.
**Last updated:** 27 August 2026 (Phase 1b security spike — §4.5.1's exporter context wording
corrected against measured platform behaviour; see [ADR-018](DECISIONS/ADR-018-tls-exporter-channel-binding.md)
and [STATUS §2f](STATUS.md#2f-phase-1b-security-spike-27-august-2026-session)).
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
| `MAX_CONTROL_FRAME_BYTES` | **262 144** (256 KiB) — the JSON body, excluding the 4-byte prefix |
| `MANIFEST_PAGE_SOFT_LIMIT_BYTES` | **196 608** (192 KiB) — sender-side page budget, §8.1 |
| `MAX_ENTRIES_PER_PAGE` | **256** |
| Keepalive | `PING` every 2 s; peer declared lost after 6 s of silence |

`MAX_CONTROL_FRAME_BYTES` is a defensive limit and does **not** move: a control message larger
than 256 KiB is a bug or an attack. Payloads that can legitimately grow without bound — the
library manifest being the only one in V1 — are **paginated** rather than accommodated by
raising the cap ([ADR-013](DECISIONS/ADR-013-paginated-manifest-sync.md)). A receiver that reads
a length prefix greater than `MAX_CONTROL_FRAME_BYTES` sends `ERROR/frame_too_large` and closes
the connection without reading the body.

File bodies use a **separate TLS connection** so a large transfer cannot head-of-line-block a
`PAUSE`. Its frames are binary, not JSON (§8.2).

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
| `sender_id` | 16-hex string | yes | Durable `peer_id`, assigned at pairing. Before pairing completes, the sender's provisional `peer_id` proposal (§4.5). |
| `msg_id` | ULID string | yes | Unique per message. Used for ack correlation and duplicate suppression. |
| `seq` | uint64 | yes | Per-sender monotonic counter, starts at 1 per session. Gaps are detectable; duplicates are droppable. |
| `sent_at_mono_us` | uint64 | yes | Sender's **monotonic** microseconds. For diagnostics and RTT only — never for scheduling (§6). |
| `requires_ack` | bool | no (default `false`) | If true, receiver must reply `ACK`. |
| `payload` | object | yes | Type-specific. `{}` when empty — never `null`. |

**Rules that make evolution safe:**

1. Receivers **must ignore unknown `payload` fields** — additive changes are non-breaking and do not need a version bump. This applies to every message type including `MANIFEST_BEGIN` / `MANIFEST_PAGE` / `MANIFEST_END`; an unknown field inside a manifest entry is ignored, not a page error.
2. Receivers **must ignore unknown `type` values** — new message types can be introduced against an older peer.
3. `v` increments **only** on a breaking change to the envelope or to an existing field's meaning.
4. Capability negotiation (§4.3), not version arithmetic, gates optional features.
5. Timestamps are always `_mono_us` (monotonic) or `_session_us` (session clock). No field ever carries wall-clock time. This naming convention is deliberate: a reviewer can spot a scheduling bug by field name alone.

Note that rule 1 and the sizing rules of §8.1 interact: a page is sized by its **encoded byte
length**, never by field count, so an added field cannot silently push a page over the frame cap.

### 2.1 Replay and ordering

- Per sender, a frame with `seq` ≤ the highest already applied is **dropped** (idempotent replay defence).
- A gap in `seq` on a reliable ordered transport means frames were lost, which on TCP means the connection is broken ⇒ transition to `RECONNECTING` rather than attempting repair.
- `msg_id`s seen in the last 256 messages are remembered to suppress duplicates across a reconnect that replays a small tail.
- **Playback commands are ordered by `command_seq` assigned by the leader (§5), never by `seq` or by timestamp comparison.**

---

## 3. Message catalogue

| Group | Types | Phase |
|---|---|---|
| Session | `HELLO`, `HELLO_ACK`, `CAPABILITIES`, `AUDIO_STATE`, `ACK`, `ERROR`, `BYE` | 1 |
| Pairing | `PAIR_REQUEST`, `PAIR_CONFIRM`, `PAIR_RESULT` | 1 |
| Health/clock | `PING`, `PONG`, `METRICS` | 1 |
| Resync | `STATE_REQUEST`, `STATE_SNAPSHOT` | 1 |
| Voice | `VOICE_OFFER`, `VOICE_ANSWER`, `VOICE_ICE`, `VOICE_STATE` | 2 |
| Catalogue | `MANIFEST_REQUEST`, `MANIFEST_BEGIN`, `MANIFEST_PAGE`, `MANIFEST_END`, `MANIFEST_ABORT` | 4 |
| Transfer | `TRANSFER_REQUEST`, `TRANSFER_OFFER`, `TRANSFER_PROGRESS`, `TRANSFER_RESULT`, `TRANSFER_CANCEL` | 4 |
| Playback | `PLAY`, `PAUSE`, `RESUME`, `SEEK`, `NEXT`, `PREVIOUS`, `POSITION_REPORT`, `PLAYBACK_STATE` | 5 |
| Queue | `QUEUE_ADD`, `QUEUE_REMOVE`, `QUEUE_MOVE`, `QUEUE_SNAPSHOT` | 5 |

There is no single-frame `MANIFEST` message and no separate `MANIFEST_DELTA`. Both a full
catalogue and a delta are carried by the same paginated `BEGIN`/`PAGE`/`END` sequence,
distinguished by `MANIFEST_BEGIN.kind` (§8.1).

---

## 4. Session establishment

### 4.1 Handshake

```
        A (initiator)                              B (acceptor)
             │                                          │
             │──── TCP connect + TLS 1.3 handshake ─────►│
             │◄──── certificates exchanged ─────────────►│
             │                                          │
             │  both sides check the SPKI pin. unknown peer → §4.5 pairing,
             │  and the session stays in PAIRING until §4.5 completes
             │                                          │
             │──── HELLO ──────────────────────────────►│
             │◄─── HELLO_ACK ───────────────────────────│
             │                                          │
             │  both sides resolve duplicate connections → §4.2
             │                                          │
             │◄─── CAPABILITIES ───────────────────────►│   (both directions)
             │◄─── AUDIO_STATE ────────────────────────►│   (both directions)
             │◄─── PING/PONG × 11 (clock sync, §6) ────►│
             │                                          │
             ▼                                          ▼
        state = CONNECTED                        state = CONNECTED
```

**The diagram is the trusted path.** For an unknown peer, everything from `CAPABILITIES` onward
waits for §4.5 to complete: the session is in `PAIRING` from the moment the peer is selected until
both users have confirmed the six digits and the pin has been written
([ADR-019](DECISIONS/ADR-019-connected-means-authenticated.md)). A completed TLS handshake is a
transport, not an authenticated session, and it never by itself produces `CONNECTED`.

Before the trust gate opens, a connection may carry only `PING`, `PONG`, `PAIR_REQUEST`,
`PAIR_CONFIRM`, `PAIR_RESULT`, `BYE` and `ERROR`. Anything else is dropped exactly as an unknown
type is (§2 rule 2) — including, in particular, every message type Phase 2 adds, unless it is added
to that list deliberately.

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
  "identity_spki_sha256": "sha256:9f2c4b7e0a1d38f5c6b29e74d0a15f83c47b6e29d81a05f3c7b4e69d2a0f18b5c",
  "conn_tiebreak": "5e2a9c40b7f13d86e0a4c95b28f7d613"
}
```

| Field | Meaning |
|---|---|
| `identity_spki_sha256` | `"sha256:"` ‖ lowercase hex of `SHA-256(DER SubjectPublicKeyInfo)` of the sender's long-term identity key. The **only** pinned identity value ([ADR-012](DECISIONS/ADR-012-spki-peer-identity.md)). 64 hex characters. |
| `conn_tiebreak` | 16 random bytes as 32 lowercase hex characters. Generated per app process per discovery session, stable across every connection that process opens or accepts in that session, never persisted, unrelated to `peer_id` and to the discovery handle. Used **only** by §4.2. |
| `session_id_proposal` | Resolved deterministically on the surviving connection: the **leader's** proposal wins. |

`HELLO_ACK` echoes the accepted `session_id` and `protocol_version`, carries the acceptor's own
`identity_spki_sha256` and `conn_tiebreak`, and states `leader_peer_id` — computed, not
negotiated: the lexicographically smaller `peer_id`
([ARCHITECTURE §5](ARCHITECTURE.md#5-leadership-and-command-ordering)). Both sides compute it
independently and it must agree; disagreement is `ERROR/leader_mismatch`.

**SPKI pin check.** After the TLS handshake, each side computes `identity_spki_sha256` from the
peer's presented certificate and compares it to the stored pin for that `peer_id`:

| Outcome | Action |
|---|---|
| No stored pin for this peer | → §4.5 pairing (SAS confirmation required) |
| Stored pin matches | Silent connect. No code, no prompt — even if the certificate itself was re-issued (§4.5.3) |
| Stored pin does not match | `ERROR/pin_mismatch`, close, surface a security warning. **Never** auto-re-pair |
| Certificate structurally invalid, expired or not yet valid | `ERROR/certificate_invalid` — reported distinctly so clock skew is not misreported as an attack |

The value in `HELLO.identity_spki_sha256` is **advisory only**: it is cross-checked against the
value computed from the TLS certificate and a mismatch is `ERROR/identity_mismatch`. Trust never
derives from a field a peer can choose.

### 4.2 Duplicate and simultaneous connections

Both peers advertise *and* browse, so both may call `connect()` at nearly the same instant and
two TCP connections can exist between the same pair. Exactly one must survive
([ADR-015](DECISIONS/ADR-015-duplicate-connection-resolution.md)).

**The rule.** Once both `conn_tiebreak` values are known on a connection (i.e. after
`HELLO`/`HELLO_ACK`):

> The surviving connection is the one **initiated by the peer with the lexicographically larger
> `conn_tiebreak`**. Equivalently: the peer with the *smaller* `conn_tiebreak` keeps the
> connection it accepted and drops the one it initiated.

Comparison is over the 32-character lowercase hex string, which is byte-order-identical to an
unsigned big-endian comparison of the 16 raw bytes. Both peers see the same pair of values on
both connections and therefore reach the same verdict with no extra round trip.

`conn_tiebreak`, not `peer_id`, is the key. Three reasons:

1. It works **before** pairing, when no durable `peer_id` exists yet. Deduplication must happen before `PAIR_REQUEST`, or a simultaneous first meeting would display two different SAS codes.
2. It keeps connection ownership **independent of leadership**. Leadership is the smaller `peer_id`, always, regardless of which side called `connect()` ([ADR-010](DECISIONS/ADR-010-internal-leader-election.md)). Using `peer_id` for both would make the two rules structurally indistinguishable and an implementation could conflate them without any test noticing.
3. It is ephemeral, so it leaks nothing durable if observed.

**Resolution procedure**

| Situation | Behaviour |
|---|---|
| Second connection's HELLO exchange completes while the peer is still in `CONNECTING` | Apply the rule. The loser receives `BYE { reason: "duplicate_connection" }`, then TLS `close_notify`, then FIN. The winner proceeds to `CAPABILITIES`. |
| `conn_tiebreak` values are byte-identical (probability 2⁻¹²⁸) | Both sides close **both** connections, regenerate `conn_tiebreak`, wait 500–1500 ms jittered, retry. |
| A new inbound connection arrives from a peer whose session has already reached `CONNECTED` | Rejected immediately with `ERROR { code: "session_already_active", fatal: true }` and closed. No HELLO processing, no state change, no effect on the live session. |
| A new inbound connection arrives while in `RECONNECTING` | Treated as a normal reconnect attempt and subject to the rule above; whichever connection survives resumes the existing `session_id`. |

**Invariants this produces**

- Exactly one control connection per peer pair, ever.
- `HELLO` is processed at most once per surviving connection; the loser's `HELLO` produces no session, no clock sync and no capability exchange.
- No reconnect loop: `BYE` already suppresses reconnect (§4.6), and `duplicate_connection` additionally must not increment `reconnect_count`, must not raise a user-visible error, and must not be logged as a fault.
- No duplicated command stream and no split brain: the loser is closed before `command_seq` can be assigned on it.
- First-time pairing and trusted reconnect use the **same** rule, so there is one code path and one set of tests.

Because both peers detect a link loss at the same moment and retry together, this race is
*expected* on every reconnect, not exotic. The jittered backoff of §10 reduces its frequency;
this rule makes its outcome correct.

### 4.3 `CAPABILITIES` — declared, static

Sent once per session in both directions, immediately after §4.2 settles. Describes what a peer
*can* do. Runtime audio state lives in `AUDIO_STATE` (§4.4), not here.

```json
{
  "capabilities_revision": 1,
  "audio": {
    "endpoint_class": "bluetooth",
    "supported_profiles": ["media_stereo", "duplex_wideband", "duplex_narrowband", "builtin"],
    "profile_coupling": "input_forces_output",
    "voice_codecs": ["opus/48000/2", "opus/16000/1"],
    "webrtc_supported": true,
    "playback_rate_control": true,
    "confidence": "assumed"
  },
  "library": { "track_count": 1284, "hash_complete": false, "formats": ["mp3","aac","m4a","flac"] },
  "features": ["transfer.v1", "sync.v1", "queue.v1", "manifest.paged.v1", "vox"],
  "limits": {
    "max_transfer_bytes": 536870912,
    "max_chunk_bytes": 65536,
    "max_control_frame_bytes": 262144,
    "max_manifest_page_bytes": 196608
  }
}
```

`features` is the extension point: a peer offers only what it implements, and both sides
intersect. This is how a newer app stays compatible with an older one without bumping `v`.

`playback_rate_control` matters — the drift ladder's rate-nudge tier
([ARCHITECTURE §7.3](ARCHITECTURE.md#73-drift-correction)) requires it on **both** peers;
without it the ladder degrades to dead-band plus hard seek.

`limits.max_manifest_page_bytes` is the page budget this peer will *accept*. A sender uses
`min(own soft limit, peer's max_manifest_page_bytes)` (§8.1), which makes the budget negotiable
without a version bump.

#### 4.3.1 Audio capability vocabulary

Wire values are deliberately **platform-neutral**: no `A2DP`, `HFP`, `AVAudioSession` or
`AudioManager` string ever appears on the wire
([ADR-016](DECISIONS/ADR-016-effective-audio-capability-model.md)). The mapping from platform
profile names to these values lives in each platform's route layer, in one place.

| Field | Values | Meaning |
|---|---|---|
| `endpoint_class` | `bluetooth` · `wired` · `builtin_speaker` · `builtin_earpiece` · `other` · `unknown` | What kind of audio device this peer's audio is going to |
| `supported_profiles`, `*_profile` | `media_stereo` · `duplex_narrowband` · `duplex_wideband` · `duplex_wide_stereo` · `builtin` · `none` · `unknown` | See below |
| `profile_coupling` | `independent` · `input_forces_output` · `unknown` | **The field that matters.** `input_forces_output` = opening the microphone drags the *output* onto the duplex profile too |
| `confidence` | `measured` · `assumed` · `unknown` | `measured` only once real hardware behaviour is recorded in [`PHASE0_RESULTS.md`](PHASE0_RESULTS.md) |

Profile values, by what they can carry rather than by which Bluetooth profile implements them:

| Value | Duplex? | Quality | Typical reality |
|---|---|---|---|
| `media_stereo` | no | media-quality stereo output only | Bluetooth media streaming |
| `duplex_narrowband` | yes | ≈8 kHz both directions | legacy hands-free |
| `duplex_wideband` | yes | ≈16 kHz both directions | modern hands-free |
| `duplex_wide_stereo` | yes | media-quality output *with* usable input | wired headset, built-in, next-gen Bluetooth audio |
| `builtin` | yes | device speaker + device mic | phone with nothing attached |
| `none` | — | no route | no output device |
| `unknown` | ? | ? | route not yet determined, or the platform did not tell us |

`profile_coupling: "input_forces_output"` is the correction that matters: the previous model
listed an output route and an input route as if they were independent, which is false for the
common Bluetooth case and misleading in exactly the situation the product is most at risk from.

### 4.4 `AUDIO_STATE` — effective, runtime

The **effective duplex state right now**. Sent in both directions at `CONNECTED`, at ride start,
and unsolicited on every change: route change, microphone open or close, profile switch, and both
edges of a route transition.

```json
{
  "revision": 7,
  "endpoint_class": "bluetooth",
  "microphone_open": true,
  "effective_output_profile": "duplex_wideband",
  "effective_input_profile": "duplex_wideband",
  "effective_output_sample_rate_hz": 16000,
  "effective_input_sample_rate_hz": 16000,
  "media_quality": "reduced",
  "route_state": "stable",
  "intercom_mode": "ptt",
  "confidence": "measured"
}
```

| Field | Values / type | Notes |
|---|---|---|
| `revision` | uint64, strictly increasing per sender per session | Receiver drops a lower revision. Reordering cannot resurrect a stale route. |
| `microphone_open` | bool | Whether the capture device is *open*, not whether speech is being transmitted. PTT and VOX gate transmission, not the device (ARCHITECTURE §6.4). |
| `effective_*_profile` | profile enum (§4.3.1) | What is *actually* active, after any coupling has taken effect |
| `effective_*_sample_rate_hz` | int, or `null` if unknown | |
| `media_quality` | `full` · `reduced` · `unavailable` · `unknown` | Derived, not measured: `reduced` whenever `effective_output_profile` is a duplex profile that is not `duplex_wide_stereo` |
| `route_state` | `stable` · `transitioning` | `transitioning` is sent at the start of a route change and superseded by `stable` when it settles. A peer must not report drift or sync failure while its own or its peer's `route_state` is `transitioning`. |
| `intercom_mode` | `continuous` · `vox` · `ptt` · `disabled` | Mirrors `VOICE_STATE.mode`; present here so the diagnostics screen needs one message, not two |
| `confidence` | as §4.3.1 | |

Representable states, all of which the FR-023 diagnostics screen must render for **both** peers:

| Situation | `endpoint_class` | `microphone_open` | `effective_output_profile` | `media_quality` |
|---|---|---|---|---|
| Music only, Bluetooth | `bluetooth` | `false` | `media_stereo` | `full` |
| Intercom active, Bluetooth | `bluetooth` | `true` | `duplex_wideband` | `reduced` |
| Wired headset | `wired` | `true` | `duplex_wide_stereo` | `full` |
| Nothing attached | `builtin_speaker` | `true` | `builtin` | `full` |
| Mid route change | any | any | previous value | previous value, `route_state: transitioning` |
| Platform gave us nothing | `unknown` | `false` | `unknown` | `unknown` |

### 4.5 Pairing — first meeting only

Runs **only on the surviving connection** (§4.2), so exactly one SAS code is ever shown.

```
A                                                                  B
│─ PAIR_REQUEST { display_name, platform, identity_spki_sha256 } ──►│
│                                                                   │
│  both derive sas6 from the TLS exporter (§4.5.1)                  │
│  both display the same 6 digits; both users confirm               │
│                                                                   │
│─ PAIR_CONFIRM { sas6_accepted: true } ───────────────────────────►│
│◄─ PAIR_RESULT { accepted: true, peer_id, identity_spki_sha256 } ───│
```

- On success each side persists `{ peer_id, identity_spki_sha256, display_name, paired_at, last_seen_at }` — the **trusted peer record**. `identity_spki_sha256` is the pin. Only then does the session leave `PAIRING`, and it does so **on this same connection** — a second handshake would produce a second exporter, so the code the users compared would no longer bind the session in use.
- Rate limit: 3 pairing attempts per minute per remote address; on failure, close the connection. There is **no pairing timeout** in v1: two people comparing digits on two screens have no defensible deadline, and an invented one would be a way to end a security check without an answer.
- On failure — either user refuses, a `PAIR_*` frame's advertised identity contradicts the certificate, a frame is malformed, or the exporter is unavailable — **nothing is persisted**, both sides drop the code, and the connection is closed as a *deliberate* end (not a link loss), so §10's reconnect ladder cannot silently re-offer a pairing someone just refused. A peer that refuses sends `ERROR { code: "pairing_rejected", fatal: true }` first; the receiver surfaces the peer's `code` to its user **only** if it is one of §4.6's defined codes, and otherwise reports `pairing_rejected`. A remote peer must not be able to choose the text of a security message.
- The **TLS exporter secret** binds the code to this specific TLS session and to both certificates. A man-in-the-middle terminates two distinct TLS sessions and therefore cannot produce matching codes on the two screens. This is what makes the confirmation a real check rather than theatre.

#### 4.5.1 The six-digit SAS — exact construction

Both platforms must produce byte-identical output from identical input. The algorithm is fully
specified here; there is nothing left to an implementer's judgement.

```
Step 1 — TLS 1.3 exporter (RFC 8446 §7.5)

    label   = "EXPORTER-RideLink-SAS-v1"     ASCII, 24 bytes, no trailing NUL
    context = the TLS 1.3 EMPTY context value
    length  = 32

    S = TLS-Exporter(label, context, length)      # 32 bytes

Step 2 — take exactly the first 4 bytes, most-significant first

    b = S[0], S[1], S[2], S[3]

Step 3 — interpret as an unsigned 32-bit BIG-ENDIAN integer

    n = (b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3]        # 0 .. 4294967295

Step 4 — reduce

    value = n mod 1000000                                       # 0 .. 999999

Step 5 — format

    sas6 = decimal digits of `value`, left-padded with '0' to EXACTLY 6 characters
```

Each platform's concrete call, so nothing is left to inference
([ADR-018](DECISIONS/ADR-018-tls-exporter-channel-binding.md)):

| Platform | Call | Available from |
|---|---|---|
| Android | `android.net.ssl.SSLSockets.exportKeyingMaterial(socket, "EXPORTER-RideLink-SAS-v1", new byte[0], 32)` | API **31** — exactly the ADR-011 `minSdk` |
| Apple | `sec_protocol_metadata_create_secret(metadata, 24, "EXPORTER-RideLink-SAS-v1", 32)` | iOS 12.0 |

Points that were previously ambiguous or wrong, made explicit:

- **Exactly six digits, always.** The old specification took 20 bits (0…1 048 575) and converted to decimal, which can produce **seven** digits. The `mod 1000000` in step 4 is what guarantees the range, and the left-pad in step 5 is what guarantees the width. `000000` is a valid code and must display as six zeroes, never as `0`.
- **Under TLS 1.3 there is no "absent" context.** RFC 8446 §7.5 always hashes a context value, so an absent context and an empty one are the *same input* — unlike RFC 5705 / TLS 1.2, where they differ. This matters because Apple's public API offers no way to pass a present-but-empty context (`sec_protocol_metadata_create_secret_with_context` with `context_len: 0` returns nil), only the context-less call above. Measured equivalent on the one stack that can express both, and cross-checked between three independent TLS 1.3 implementations — [ADR-018](DECISIONS/ADR-018-tls-exporter-channel-binding.md) and [`test-results/phase1b-security-spike-20260827.md`](test-results/phase1b-security-spike-20260827.md).
- **No additional HKDF.** The TLS exporter *is* HKDF-Expand-Label with the label above, so it already provides domain separation. A second KDF layer would add nothing but a second place for the two implementations to disagree.
- **Byte order is big-endian**, stated because it is the single most likely source of a cross-platform mismatch.
- **Bytes 4…31 of `S` are unused** in v1. They are still exported (a fixed 32-byte length keeps the exporter call identical everywhere) and are reserved. A vector asserts that changing them does not change `sas6`.
- **Modulo bias is accepted and quantified.** `2³² = 4294·10⁶ + 967296`, so 967 296 of the million residues occur 4295 times and the rest 4294 — a relative deviation of ~2.3 × 10⁻⁴. Against a 6-digit code with a 3-attempts-per-minute limit, that is irrelevant. Rejection sampling is deliberately *not* used: it would make the function partial and much harder to pin down with golden vectors.
- **`sas6` is never logged, never transmitted, and never persisted.** There is no log path for it at all ([ARCHITECTURE §11](ARCHITECTURE.md#11-privacy-and-security-posture)). `PAIR_CONFIRM` carries a boolean, not the code.

**Contingency — resolved, 27 August 2026.** Both platforms expose a public keying-material
exporter, and the two produce byte-identical output for the same TLS 1.3 connection. Measured in
[`test-results/phase1b-security-spike-20260827.md`](test-results/phase1b-security-spike-20260827.md);
decided in [ADR-018](DECISIONS/ADR-018-tls-exporter-channel-binding.md). The design review that
[ADR-007 Amendment A1](DECISIONS/ADR-007-control-channel-over-tcp-tls.md#amendment-a1--26-august-2026--secure-transport-contingency)
would have required is **not** triggered, and no weaker substitute was needed or taken. What the
spike could not cover — Android's *device* TLS stack, as opposed to the same Conscrypt/BoringSSL
implementation running on a laptop — is closed by integration test I-02, not by this section.

#### 4.5.2 SAS golden vectors

`protocol/vectors/sas/` takes the 32-byte exporter output as hex and asserts `sas6`, so the
vectors run with no TLS handshake and no network. All secrets below are **fabricated test
values**.

| Vector | `n` (first 4 bytes, BE) | `exporter_output_hex` (32 bytes) | `sas6` |
|---|---|---|---|
| `all-zero-first-word` | 0 | `00000000f9194e73f9e9459e3450ea10a179cdf77aafa695beecd3b9344a98d1` | `000000` |
| `exactly-one-million` | 1 000 000 | `000f4240013827398306cf2abe6f7dd4c975c570b198b8be2891abe0917074df` | `000000` |
| `large-multiple-of-modulus` | 200 000 000 | `0bebc2000d679bc68326dea934ef01c33165fb98423adf7996cae630ad1356f1` | `000000` |
| `smallest-nonzero` | 1 | `000000017692c3ad3540bb803c020b3aee66cd8887123234ea0c6e7143c0add7` | `000001` |
| `two-leading-zeroes` | 42 | `0000002a5cc47001f7c1334db3c568ddbb1c8ee51812aa8e75582ca616b1ec31` | `000042` |
| `one-leading-zero` | 99 999 | `0001869ffd5f56b40a79a385708428e7b32ab996a681080a166a2206e750eb48` | `099999` |
| `first-six-digit-value` | 100 000 | `000186a03bb78535cc9555ff19fe3556aaa41c78a0a45c64d49ba2bc56450764` | `100000` |
| `maximum-value` | 999 999 | `000f423f937377f056160fc4b15e0b770c67136a5f03c15205b4d3bf918268fe` | `999999` |
| `maximum-value-wrapped` | 1 999 999 | `001e847f2c5f84023b4c3c6d6124b8441ea4dc9c7e46ef7cf6d31a9ce73ccb29` | `999999` |
| `all-ones` | 4 294 967 295 | `ffffffff32d3acdf6fdf507db2f523b9d98f97d3a998ea5107dce0dc4e24ecf1` | `967295` |

Two further vectors assert properties rather than values:

- `tail-bytes-ignored` — two exporter outputs sharing the first 4 bytes and differing in bytes 4…31 must yield the **same** `sas6`.
- `output-is-six-characters` — for every vector above, `len(sas6) == 6` and every character is `0`–`9`.

#### 4.5.3 Certificate re-issuance versus key rotation

The pin is the **SPKI hash**, not the certificate. That distinction has concrete behaviour:

| Event | SPKI | Result |
|---|---|---|
| Certificate regenerated around the **same** identity keypair (new serial, new validity window, new self-signature) | unchanged | **Still trusted.** Silent connect, no prompt, no SAS. Logged at info as a certificate re-issue with the first 6 hex of the SPKI hash. |
| Certificate re-issued and the identity keypair **changed** | different | `ERROR/pin_mismatch`. Treated as an unknown peer wearing a familiar name. No auto re-pair. |
| Certificate expired or not yet valid | any | `ERROR/certificate_invalid` — a separate code so a device clock problem is not reported as an attack. |

Recovering from a genuine key change requires the user to explicitly forget the peer and pair
again, which means a fresh SAS confirmation on both screens. There is no signed key-rotation
message in v1 and none is planned for it; §12 lists it as reserved. Identity certificates are
issued with a 10-year validity window so that expiry never masquerades as rotation.

### 4.6 `ERROR` and `BYE`

```json
{ "code": "version_mismatch", "message": "peer requires v2", "fatal": true, "context": {} }
```

Codes: `version_mismatch`, `leader_mismatch`, `untrusted_peer`, `pin_mismatch`,
`identity_mismatch`, `certificate_invalid`, `session_already_active`, `pairing_rejected`,
`pairing_rate_limited`, `capability_missing`, `frame_too_large`, `malformed_frame`,
`session_unknown`, `stale_revision`, `manifest_sequence_error`, `manifest_incomplete`,
`manifest_digest_mismatch`, `internal`.

`pin_mismatch` means specifically: *the peer's `identity_spki_sha256` does not equal the stored
pin.* Nothing else is pinned.

`message` is for humans and **must not** contain paths, tokens or the SAS.

`BYE { reason }` is a clean teardown ⇒ `ENDING`, and suppresses the reconnect logic — a
deliberate `BYE` must not trigger an automatic reconnect attempt. Reasons: `user_ended`,
`app_backgrounded_out`, `duplicate_connection` (§4.2), `shutdown`.

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
4. `PLAY` for a `track_hash` not locally present ⇒ do **not** start. Enter `TRANSFER_PENDING`, request transfer (§8.2), and let the leader reschedule. A remote-only track cannot begin synchronised playback (REQUIREMENTS §9.4).
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

Note the division of labour with §4.4: `VOICE_STATE` reports the *WebRTC session*;
`AUDIO_STATE` reports the *local audio route*. They fail independently and a diagnosis usually
needs both.

---

## 8. Catalogue and transfer (Phase 4)

### 8.1 Paginated manifest synchronisation

A library of 1 000–5 000 tracks does not fit in one 256 KiB control frame, so the manifest is
transferred as a bounded sequence of bounded frames
([ADR-013](DECISIONS/ADR-013-paginated-manifest-sync.md)). Full manifests and deltas use the
same framing.

```
receiver                                                         sender
    │── MANIFEST_REQUEST { since_revision, max_page_bytes } ────────►│
    │◄─ MANIFEST_BEGIN { manifest_id, kind, manifest_revision, … } ──│
    │◄─ MANIFEST_PAGE  { manifest_id, page_index: 0, entries[…] } ───│
    │◄─ MANIFEST_PAGE  { manifest_id, page_index: 1, entries[…] } ───│
    │                        …                                       │
    │◄─ MANIFEST_END   { manifest_id, page_count, digest } ──────────│
```

`MANIFEST_REQUEST`

```json
{ "since_revision": 6, "max_page_bytes": 196608 }
```

`since_revision: null` requests a full manifest. Otherwise the sender may answer with a delta
from that revision, or with a full manifest if it cannot (it has no delta history that far back).
The receiver does not get to insist.

`MANIFEST_BEGIN`

```json
{ "manifest_id": "01J9Z4M3RT8V2W5X7Y9Z1A3B5C",
  "kind": "full",
  "manifest_revision": 7,
  "base_revision": null,
  "total_entries": 4820,
  "total_removed": 0,
  "page_count": 19,
  "digest_alg": "ridelink-manifest-v1" }
```

| Field | Notes |
|---|---|
| `manifest_id` | ULID, fresh for every synchronisation attempt. Scopes every page and the end frame. A page whose `manifest_id` is not the open one is a sequence error, never a late arrival to accept. |
| `kind` | `full` \| `delta` |
| `manifest_revision` | the revision this synchronisation *produces* |
| `base_revision` | `delta` only: the revision the delta applies to. Must equal the receiver's current revision, else `ERROR/manifest_sequence_error` and the receiver re-requests with `since_revision: null` |
| `total_entries`, `total_removed` | expected totals, cross-checked at `MANIFEST_END` |
| `page_count` | known up front because the sender builds pages before sending. `null` is permitted for a streaming sender; `MANIFEST_END.page_count` is then authoritative |
| `digest_alg` | fixed at `"ridelink-manifest-v1"` in v1; present so a future algorithm is a field change, not a version bump |

`MANIFEST_PAGE`

```json
{ "manifest_id": "01J9Z4M3RT8V2W5X7Y9Z1A3B5C",
  "manifest_revision": 7,
  "page_index": 0,
  "entries": [
    { "content_hash": "sha256:1f3a…", "quick_id": "sha256:77bd…",
      "work_key": "beatles|come together|259", "title": "Come Together",
      "artist": "The Beatles", "album": "Abbey Road", "duration_ms": 259000,
      "codec": "mp3", "bitrate_kbps": 320, "size_bytes": 10387456,
      "filename": "come-together.mp3", "has_artwork": true }
  ],
  "removed": [] }
```

Entry rules, unchanged from the single-frame form: entries are deliberately minimal, `filename`
is a **basename only** and never a path (REQUIREMENTS §11), and `content_hash` may be `null`
while background hashing is incomplete — such an entry is displayable but **not** transferable
or sync-eligible ([ARCHITECTURE §8.1](ARCHITECTURE.md#81-track-identity-adr-005)).

`removed` carries `content_hash` strings and is used only when `kind` is `delta`. For
`kind: "full"` it is absent or empty; a non-empty `removed` on a full manifest is
`ERROR/manifest_sequence_error`.

**Page sizing — how the frame limit is guaranteed**

1. The sender's budget is `min(MANIFEST_PAGE_SOFT_LIMIT_BYTES, peer.limits.max_manifest_page_bytes)` — 192 KiB by default, i.e. 75 % of the frame cap, leaving room for the envelope and for worst-case JSON escaping.
2. Pages are filled by **encoded byte length**, measured as the frame is built, never by entry count. A page is closed when adding the next entry would exceed the budget, or when it reaches `MAX_ENTRIES_PER_PAGE` (256).
3. Which bound actually binds depends on the library. At a typical ~350 bytes of JSON per entry, 256 entries is about 90 KiB, so the **entry-count cap binds first** for ordinary metadata — the 4 820-entry example above becomes 19 pages. The byte budget binds only when metadata is long or heavily escaped, which is exactly the case it exists for. A receiver must not assume either bound; it reads `page_count`.
4. Display metadata is clamped at manifest-build time: `title`, `artist`, `album` and `filename` are truncated to **512 Unicode scalar values** each. Truncation is display-only; `content_hash`, `quick_id`, `size_bytes` and `duration_ms` are **never** truncated, so identity is unaffected.
5. Therefore a single entry always fits in a single page. Worst case per entry is four clamped strings at ≤512 scalars, ≤4 bytes UTF-8 each, ≤6 bytes JSON-escaped ⇒ ≤48 KiB, plus ~200 bytes of hashes and numbers — comfortably inside 192 KiB. **There is no "entry too large to send" state**, which is the point: pagination works regardless of metadata length.
6. If a page would still exceed the budget (arithmetically impossible after step 4, checked anyway), the sender emits it as a single-entry page. If *that* exceeds `MAX_CONTROL_FRAME_BYTES`, the sender aborts with `MANIFEST_ABORT { reason: "page_oversize" }` rather than emitting a frame it knows the peer must reject.

**`MANIFEST_END`**

```json
{ "manifest_id": "01J9Z4M3RT8V2W5X7Y9Z1A3B5C",
  "manifest_revision": 7,
  "page_count": 19,
  "total_entries": 4820,
  "total_removed": 0,
  "digest": "sha256:5c8e…" }
```

The digest is deterministic and cross-platform, computed over **identity fields in transmission
order only**:

```
h = SHA-256 over the concatenation, in the order the entries were sent:

    for each entry:      utf8(content_hash ?? "")  ‖ 0x1F ‖ utf8(quick_id) ‖ 0x1E
    then for each removal: utf8("-")  ‖ utf8(content_hash) ‖ 0x1E

digest = "sha256:" ‖ lowercase_hex(h)
```

Only identity fields participate, deliberately. Including the full entry JSON would require a
canonical JSON form — key order, number formatting, string escaping — which is a portability
hazard for two independent implementations. The digest's job is to detect a lost, duplicated,
reordered or truncated **page**, and identity fields do that completely. Metadata text is
validated by the per-frame JSON decode, not by the digest. `quick_id` is always present, so
entries still awaiting a `content_hash` contribute meaningfully.

**Receiver rules — the receiver must never treat an incomplete manifest as complete**

1. Pages accumulate in a **staging area**, never in the live catalogue. The previously accepted manifest and revision remain in force, and the UI keeps showing them, until `MANIFEST_END` validates.
2. `page_index` must be exactly the next expected index, ascending from 0 with no gaps. On a reliable ordered transport, anything else is a bug or an attack: a gap, a duplicate or a reordering is `ERROR/manifest_sequence_error` and aborts the whole synchronisation. The receiver does not buffer out-of-order pages and does not attempt repair.
3. A page bearing an unexpected `manifest_id`, or a `manifest_revision` different from the one in `MANIFEST_BEGIN`, is `ERROR/manifest_sequence_error`.
4. At `MANIFEST_END`, the receiver checks `page_count`, `total_entries`, `total_removed` **and** the digest against what it actually staged. Any mismatch ⇒ `ERROR/manifest_digest_mismatch`, staging discarded.
5. **Atomic swap only on full validation.** Exactly as with file transfer (§8.2), nothing partial is ever promoted.
6. `MANIFEST_PAGE_TIMEOUT` = 10 s between consecutive frames of an open synchronisation. On expiry: discard staging, `ERROR/manifest_incomplete`, and retry once after 2 s; a second failure surfaces "catalogue not synchronised" in the UI and leaves the previous manifest in force.
7. Control-plane loss mid-synchronisation ⇒ staging discarded. Manifest sync restarts from a fresh `MANIFEST_REQUEST` after reconciliation (§10). Staging is in-memory and session-scoped; it is never written to disk.
8. At most **one** synchronisation is open per direction. A `MANIFEST_BEGIN` arriving while one is open implicitly aborts the open one (staging discarded) and starts the new one.
9. `MANIFEST_ABORT { manifest_id, reason }` is valid from the sender at any time — the receiver discards staging silently and keeps the previous manifest. Reasons: `library_changed`, `page_oversize`, `cancelled`, `internal`.
10. Restarting is always safe and always from the beginning. There is no partial-manifest resume in V1, and `manifest_id` being fresh per attempt is what makes a restart unambiguous.

**Deltas respect the same limits.** A delta is paginated by exactly the same rules; a
1 000-track library edit produces multiple `MANIFEST_PAGE` frames, not one oversized one. A
delta is applied only after its own `MANIFEST_END` validates, and only if `base_revision`
matched — so a delta can never be applied on top of the wrong base.

### 8.2 Transfer

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

The bulk connection is pinned to the same `identity_spki_sha256` as the control connection; a
bulk connection presenting a different SPKI is closed before the token is read.

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
- `QUEUE_SNAPSHOT` is subject to the frame cap. A queue is user-curated and bounded in practice, but the sender must still check: a snapshot exceeding the cap is sent as a `queue_revision`-consistent sequence in a later protocol revision. **V1 caps the queue at 2 000 items** so this cannot happen — enforced at `QUEUE_ADD` with `ERROR/capability_missing`.

---

## 10. Reconnect and reconciliation

`session_id` survives a reconnect; that is what distinguishes resuming from starting over.

```
link lost ──► RECONNECTING          (local playback CONTINUES; sync suspended)
    │
    ├─ rediscover peer via mDNS, or reuse last known host:port
    ├─ TLS + SPKI pin check (no pairing, no SAS — trust already exists)
    ├─ resolve duplicate connections (§4.2) — both peers retry at once, so this is normal
    ├─ HELLO { session_id = <previous> }  ⇒  resume, not restart
    ├─ re-run clock sync from scratch (11 samples) — the old offset is stale
    ├─ STATE_REQUEST  ──►  STATE_SNAPSHOT
    ├─ re-request the manifest if `manifest_revision` differs (§8.1) — from the beginning
    └─ reconcile, then return to the state we left (CONNECTED or RIDE_ACTIVE)
```

Backoff: 0.5, 1, 2, 4, 8, 8, 8 … seconds with ±20 % jitter, for up to 120 s, then
`DISCONNECTED`. Jitter matters because both phones detect the loss simultaneously and would
otherwise retry in lockstep forever — and every such collision then costs a §4.2 resolution.

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

`STATE_SNAPSHOT` carries `manifest_revision` only — never manifest entries. A revision
difference triggers a paginated synchronisation (§8.1); it never inflates this frame.

Reconciliation rules:

1. The **leader's** snapshot is authoritative. The follower conforms. No merge.
2. The follower adopts the leader's `command_seq` and `queue_revision` wholesale.
3. Playback position is re-derived from `position_ms` + `at_session_us` against the *newly*
   measured offset, then handed to the drift ladder — which usually means one hard seek, since
   >120 ms of divergence over a reconnect is expected.
4. Transfers are **not** resumed in V1; in-flight `.part` files are discarded and re-requested.
   Manifest staging is likewise discarded, not resumed.
5. If the follower was mid-`PLAY` on a track the leader has since changed, the leader's state wins and the follower switches.
6. Both peers re-send `AUDIO_STATE` after reconciliation, because a route may have changed while the link was down.

---

## 11. Test vectors

`protocol/vectors/` is shared by both platforms' unit suites — the mechanism that makes wire
incompatibility a laptop-side test failure instead of a roadside mystery
([ARCHITECTURE §9.3](ARCHITECTURE.md#93-the-shared-seam)).

| File | Asserts |
|---|---|
| `envelope/*.json` | encode/decode round-trip, unknown-field tolerance, unknown-type tolerance, oversize rejection (262 144 + 1), malformed rejection |
| `clock/*.json` | given 11 `(t1,t2,t3,t4)` samples with injected outliers ⇒ expected offset/rtt/jitter |
| `drift/*.json` | given drift series ⇒ expected ladder action (`none`/`nudge`/`seek`/`fail`) |
| `queue/*.json` | concurrent mutation sequences ⇒ expected final queue and revision |
| `manifest/*.json` | two manifests ⇒ expected presence classification and delta |
| `manifest-paging/*.json` | page-splitting for 1 / 1 000 / 5 000 entries and pathological metadata ⇒ expected page boundaries, per-page byte bound, and `MANIFEST_END` digest |
| `manifest-paging-errors/*.json` | missing / duplicated / reordered page, wrong `manifest_id`, wrong `base_revision`, truncated stream, malformed page ⇒ expected rejection and *unchanged* live manifest |
| `ordering/*.json` | out-of-order/duplicate/stale `command_seq` streams ⇒ expected applied set |
| `sas/*.json` | fixed 32-byte exporter output ⇒ expected 6-digit SAS, per the table in §4.5.2 (test-only secrets) |
| `identity/*.json` | SPKI hash formatting, pin match / mismatch, certificate re-issue with unchanged SPKI ⇒ still trusted |
| `dedup/*.json` | `conn_tiebreak` pairs ⇒ which side's initiated connection survives; equal-value tie ⇒ both close |
| `audio-state/*.json` | `AUDIO_STATE` round-trip, `revision` monotonicity, derived `media_quality`, unknown enum value tolerated as `unknown` |

Each is `{ "name", "input", "expected" }`, so a single table-driven runner per platform covers
the file. A vector is added for every protocol bug found on a device — that is the regression
discipline in the brief made concrete.

---

## 12. Reserved for later

Named now so the shape stays compatible; **not** implemented in V1:
`TRANSFER_OFFER.have_chunks[]` (resume), partial-manifest resume
(`MANIFEST_REQUEST.resume_from_page`), signed identity-key rotation, `VOICE_STATE.mode =
ptt_remote`, `METRICS.thermal_state`, `CAPABILITIES.features += "le_audio"`, and group sessions
(which would need `recipient_id` in the envelope — deliberately absent, since two-peer is the V1
scope).
