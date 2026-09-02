# RideLink — Test Plan

**Status:** baseline for Phases 1–2a. Last updated 28 August 2026 (Phase 2a — the two new voice
vector sets, §3.1a's "what real WebRTC on a laptop does and does not prove", and the V-01…V-11 /
A-12…A-15 device gates). Derived from REQUIREMENTS §17.
Every phase must satisfy its gate here before `STATUS.md` records it complete.

**Governing rule from the brief:** never report a phase complete because code *looks* correct.
Where a step cannot be automated, this plan gives an exact manual procedure with a recorded
result, not a vague instruction to "check it works".

---

## 1. Layers

| Layer | Runs on | Needs hardware? | Speed | Gate |
|---|---|---|---|---|
| **L1 Unit** | JVM / `swift test` | no | seconds | every commit |
| **L2 Shared vectors** | both platforms, same fixtures | no | seconds | every commit |
| **L3 Instrumented** | emulator / simulator | no | minutes | every phase |
| **L4 Two-device integration** | real Android + real iPhone, Wi-Fi | phones only | minutes | every phase touching the wire |
| **L5 Audio hardware** | + helmet unit + TWS | full chain | manual | Phases 2, 6 |
| **L6 Ride** | on the motorcycle | full chain | manual | Phases 6, 7 |

L1–L3 are automatable and must stay green. L4 is scripted but human-triggered. L5–L6 are
manual with recorded measurements — the brief's rule that latency bugs get measurements, not
impressions.

---

## 2. L1 — Unit tests (pure logic)

Targets Android `core` / `RideLinkCore`, which contain no platform types precisely so this layer
can be exhaustive
([ARCHITECTURE §2](ARCHITECTURE.md#2-layering-identical-concepts-on-both-platforms),
[§9](ARCHITECTURE.md#9-module-structure)).

| Area | Cases |
|---|---|
| Envelope codec | round-trip all types; unknown field ignored; unknown `type` ignored; missing required field rejected; `v` mismatch rejected; **262 144 + 1 bytes rejected**; length prefix larger than the cap rejected *without reading the body*; malformed UTF-8 rejected; `payload: null` rejected |
| Session FSM | every legal transition; **every illegal transition rejected without crashing**; `RECONNECTING` returns to the state it left; `BYE` suppresses reconnect; `ENDING` is the only path that releases audio; **closing a duplicate connection produces no transition and no `reconnect_count` increment** |
| Connection dedup | larger `conn_tiebreak`'s outbound connection survives; both peers compute the same verdict from the same pair; equal tiebreak ⇒ both close and regenerate; inbound connection while `CONNECTED` ⇒ `session_already_active` with no state change; **`HELLO` applied at most once**; the loser never reaches capability exchange |
| SAS derivation | the ten vectors of [PROTOCOL §4.5.2](PROTOCOL.md#452-sas-golden-vectors); output is **always exactly 6 characters**, all digits; `000000` renders as six zeroes; leading-zero cases; values at and past 999 999; big-endian byte order (a little-endian reading must fail the vectors); bytes 4…31 of the exporter output do not affect the result |
| SPKI identity | `identity_spki_sha256` formatting (`sha256:` + 64 lowercase hex, uppercase **rejected**); pin match; **pin mismatch ⇒ `pin_mismatch`, never auto-re-pair**; certificate re-issued with unchanged SPKI ⇒ still trusted; expired certificate ⇒ `certificate_invalid`, *not* `pin_mismatch`, and it outranks a pin mismatch; `HELLO.identity_spki_sha256` disagreeing with the TLS certificate ⇒ `identity_mismatch`, checked **before** pairing is offered |
| **DER encoding** (ADR-017) | definite length in minimal form at the 127/128, 255/256, 65 535/65 536 boundaries; non-negative INTEGER minimal two's-complement (redundant leading zero stripped, necessary one kept); the 91-byte P-256 SubjectPublicKeyInfo; a fixed TBSCertificate byte-for-byte from fixed inputs; **a compressed or wrong-length public-key point is rejected before it can be hashed into an identity** |
| **Certificate validity** | inclusive window boundaries; one second either side; UTCTime round-trip across the epoch, a pre-1970 instant (floor vs truncating division), leap days, and the RFC 5280 2049/1950 pivot; `plusYears` clamps 29 Feb rather than rolling into 1 March |
| **Pairing state machine** (PROTOCOL §4.5) | no pin is written until **both** sides confirm, in either arrival order; either side rejecting writes nothing; a `PAIR_REQUEST`/`PAIR_RESULT` whose advertised identity contradicts the certificate ⇒ `identity_mismatch`; a replayed `PAIR_RESULT` cannot pair twice; **a missing exporter fails the exchange rather than displaying an unbound code**; an existing pin for the same `peer_id` is never overwritten |
| **Trust gate** (ADR-019) | the complete `(ControlEvent, status) -> SessionEvent` cross-product from `session-gate/`, on both platforms; **no `Connected` row, from any status, produces `PairingSucceeded`**; `PairingRequired` produces no transition from any status; `PAIRING -> CONNECTING` is reachable only from `PeerTrusted` or `PairingSucceeded`; a link lost in `PAIRING` (either reason) exits to `DISCOVERING` rather than wedging |
| **Session integration** (ADR-019, real TLS, both platforms) | unknown peer ⇒ the FSM stays in `PAIRING` and **no `Connected` is emitted** before pairing succeeds; exactly one SAS prompt per device even on a simultaneous first meeting, and both show the same six digits; one user confirming alone writes no pin and reaches nothing; both confirming ⇒ one pin per side, code cleared, `PairingRequired` → `PairingSucceeded` → `Connected`, statuses `PAIRING` → `CONNECTING` → `CONNECTED` with neither skipped, and **one dial per side** (pairing must not open a second TLS connection); either side rejecting ⇒ no pin, no `CONNECTED`, `reconnect_count` unchanged; a matching pin ⇒ `PeerTrusted` → `Connected` with no prompt; a re-issued certificate around the same key ⇒ still silent; `forget` ⇒ the next connection asks again; the same `peer_id` with a different key ⇒ `pin_mismatch`, no prompt, stored pin untouched; `StartRide` rejected while `PAIRING` |
| **Trusted-peer persistence** | survives a new store instance; `last_seen_at` refresh allowed, pin replacement refused; `forget` then re-pair allowed; a corrupt store reads as empty rather than crashing at launch; a record with a malformed identifier is dropped, not adopted; writes are atomic; the local `peer_id` is generated once and then stable |
| Clock estimator | offset recovery from synthetic samples with known truth; outlier injection (one 500 ms spike in 11); all-samples-bad ⇒ no estimate rather than a wrong one; EWMA convergence; 30 ms step rejected until confirmed twice |
| Drift ladder | boundary values 24/25/119/120/121/1999/2000/2001 ms; hysteresis (no nudge/restore oscillation); 3-seeks-in-60 s ⇒ sync-failed; rate restored to exactly 1.0 after convergence; **ladder suspended while either peer reports `route_state: "transitioning"`, and a transition does not advance the hard-seek counter** |
| Command ordering | duplicate `command_seq` dropped; stale dropped; past `effective_at` applied immediately and counted; `stale_revision` rejected |
| Queue algebra | add/remove/move; sparse `order` renumbering; concurrent adds converge; `queue_item_id` idempotent under retry; snapshot overwrites local; 2 000-item cap enforced at `QUEUE_ADD` |
| Manifest diff | presence classification for all 6 states; delta correctness; `content_hash: null` entry is displayable but not transferable |
| **Manifest paging — sizing** | page assembly for **1**, **1 000** and **5 000** entries; every emitted page ≤ the negotiated budget *measured as encoded bytes*; `page_count` and `total_entries` match what was emitted; a 5 000-entry manifest whose single-frame form would exceed 256 KiB is emitted as multiple in-cap pages |
| **Manifest paging — pathological metadata** | 512-scalar title/artist/album/filename; 4-byte-UTF-8 (emoji, CJK) metadata; metadata full of characters requiring JSON escapes; **every one still produces at least one valid page and no page over the cap**; identity fields never truncated |
| **Manifest paging — digest** | digest is order-dependent; identical entry sets in a different order produce different digests; `content_hash: null` entries contribute via `quick_id`; both platforms compute byte-identical digests from the same vector |
| **Manifest paging — failures** | missing page (gap in `page_index`); duplicated page; pages reordered; wrong `manifest_id`; `manifest_revision` changed mid-stream; delta whose `base_revision` ≠ local revision; `MANIFEST_END` count mismatch; `MANIFEST_END` digest mismatch; interrupted stream (no `MANIFEST_END`); page-timeout expiry; malformed page JSON; non-empty `removed[]` on `kind: "full"`. **In every case: the correct error code, staging discarded, and the previously accepted manifest and `manifest_revision` unchanged** |
| **Manifest paging — concurrency** | a new `MANIFEST_BEGIN` while one is open aborts the first and discards its staging; `MANIFEST_ABORT` discards staging silently and keeps the previous manifest; restart after abort succeeds |
| Audio state | `AUDIO_STATE` round-trip; `revision` monotonic — a lower revision is dropped; `media_quality` derived correctly from the effective output profile; unrecognised enum value from a peer treated as `unknown`, not rejected; all six representable situations of [PROTOCOL §4.4](PROTOCOL.md#44-audio_state--effective-runtime) encode and decode |
| Hashing | `quick_id` and `content_hash` against fixed vectors; files smaller than 128 KiB (the quick_id window overlaps — must not double-count); empty file; 0-byte and 1-byte edge cases |
| Leader election | smaller `peer_id` wins; stable across reconnect; disagreement ⇒ `leader_mismatch`; **leadership unaffected by which side initiated the surviving connection** |
| Redaction | paths → basename; `peer_id` → 6 chars; `identity_spki_sha256` → 6 hex; `conn_tiebreak` → 6 hex; **SAS / bulk tokens / TLS secrets have no log path at all** (assert by planting a secret and searching the entire emitted log for it) |

**Coverage intent:** `core` / `RideLinkCore` ≥ 85 % line coverage. Coverage is a smoke alarm,
not a goal — the boundary cases above matter more than the number.

---

## 3. L2 — Shared protocol vectors

The seam that keeps two independent implementations honest. Same `protocol/vectors/*.json`,
two table-driven runners, identical expected output. Files listed in
[PROTOCOL §11](PROTOCOL.md#11-test-vectors).

**Gate:** every vector passes on **both** platforms. A vector passing on one platform only is a
release blocker, not a warning.

Vector directories that exist specifically because of the correction pass:

| Directory | Why it must be shared rather than per-platform |
|---|---|
| `sas/` | A big-endian/little-endian or padding disagreement here means two different six-digit codes on the two screens, which is indistinguishable from an attack. Ten fixed exporter outputs, fabricated, with expected `sas6` |
| `manifest-paging/` | Page boundaries must be computed the same way on both sides, or one peer's pages are rejected by the other. 1 / 1 000 / 5 000 entries plus pathological metadata |
| `manifest-paging-errors/` | Twelve failure cases; each asserts the error code *and* that the live manifest is unchanged |
| `identity/` | SPKI formatting and pin semantics, including certificate re-issue with an unchanged key; the DER length/INTEGER encodings and the exact TBSCertificate bytes. A one-byte difference here produces a different `identity_spki_sha256` on one phone than the other, which presents to the user as an unexplained `pin_mismatch` mid-ride — i.e. exactly what a real attack looks like |
| `dedup/` | `conn_tiebreak` pairs ⇒ which side's initiated connection survives. Catches an implementation that conflates initiator with leader |
| `session-gate/` | The trust gate (ADR-019): every `ControlEvent` × every session status ⇒ the `SessionEvent` it implies. A platform that read `Connected` as implicit pairing success would let an unknown peer reach `CONNECTED` before the six digits were shown — which is what happened, and what no per-platform test caught. Generated by `tools/generate_session_gate_vectors.py`, an independent third transcription of the rules; edit the generator, never the JSON |
| `audio-state/` | Shared enum vocabulary and derived `media_quality`, so neither platform invents its own audio words |
| `voice-signal/` | The `VOICE_*` message layer (PROTOCOL §7.4/§7.5): every required field, every wrong type, every bound at and one byte past its limit, both nullable fields, and unknown enum tolerance. A bound enforced on one platform and not the other means an oversize SDP the iPhone accepts and the OnePlus refuses. 70 rows, generated by `tools/generate_voice_signal_vectors.py` |
| `voice-fsm/` | The voice negotiation table (PROTOCOL §7.3/§7.8): `(role, status, input) -> (actions, new status)`. This is where the offerer rule, glare, the generation guard and "a link loss must not close the capture device" live, and all four are properties of the *table*. 52 rows, generated by `tools/generate_voice_fsm_vectors.py`, an independent third transcription; edit the generator, never the JSON |

**Discipline:** every wire bug found on a device gets a vector added *before* the fix.

### 3.1a Phase 2a voice — what is proven on a laptop, and what is not

**Proven, and it is real media rather than a mock.** `VoiceEngineLoopbackTests` (iOS package, runs
on macOS under `swift test`) stands up **two real `WebRtcVoiceEngine`s** and negotiates them against
each other. This is possible because `stasel/WebRTC`'s XCFramework carries a macOS slice, so the
test links the same WebRTC binary an iPhone build would ([ADR-020](DECISIONS/ADR-020-webrtc-voice-foundation.md)).
Asserted, from the stack's own statistics:

| Property | Evidence |
|---|---|
| Host candidates **only** — PROTOCOL §7.6's no-STUN/no-TURN policy is real, not aspirational | gathered candidate-type set is exactly `{host}`; no `srflx`, no `prflx`, no `relay` |
| DTLS-SRTP actually completes | `dtlsState = connected`, `dtlsCipher = TLS_AES_128_GCM_SHA256`, `srtpCipher = SRTP_AES128_CM_HMAC_SHA1_80` |
| Opus is negotiated for an audio-only m-line | `audio/opus`, 48 000 Hz |
| Both peer connections reach `connected`, and each sees the other's track | `transportState = connected` on both; remote track present on both |
| Deterministic | full suite run 5 consecutive times, 0 failures |

**The Phase 2a security invariant is proven over real TLS on both platforms** —
`VoiceAuthenticationGateTest` / `VoiceAuthenticationGateTests`: two real unpaired peers complete
TLS, reach `PAIRING` with an unanswered six-digit code, and one sends every `VOICE_*` frame there
is. None reaches the voice subsystem, the refusals are *counted* (so the test cannot be satisfied by
the frames never being sent), and the same frames from the same peer **are** acted on once both users
confirm.

**Not proven, and none of it may be reported as passing:**

| Absent | Why it cannot be tested here |
|---|---|
| Any audio captured or played | No microphone and no speaker in a headless test run. `packetsSent = 0` in the loopback test is expected: the transport is up, nothing is speaking into it |
| The **Android** media path at all | `PeerConnectionFactory.initialize` requires an Android `Context`, so `WebRtcVoiceEngine` on Android cannot be exercised by a JVM unit test. It compiles and is wired; that is the whole claim |
| `AVAudioSession` (`IosVoiceAudioSession`) | Unavailable on macOS. Its **pure** mapper (`IosAudioRouteMapper`) is unit-tested; the session itself is not |
| `AudioManager` (`AndroidVoiceAudioSession`) | Needs a device. Its **pure** mapper (`AndroidAudioRouteMapper`) is unit-tested; the session itself is not |
| The ride foreground service | Needs a device. Whether the `microphone` service type is accepted and whether capture survives a screen lock are device facts |
| Bluetooth anything, and every latency figure | The whole point of the device gate — see §6 and §9 |

`VoiceControllerTest`/`VoiceControllerTests` drive the controller against **fakes**. They prove the
controller's effects and lifecycle; they say nothing about the codec, and the files say so.

`VoiceInputMailboxTest[s]` exhausts the bounded input mailbox (PROTOCOL §7.5, ADR-020 Amendment A2)
in isolation — lane classification, priority draining, the critical/ICE bounds, coalescing — with
no controller and no coroutine/actor involved. `VoiceEngineGenerationTest[s]` exhausts the strict
generation guard the same way, since neither real `WebRtcVoiceEngine` can be constructed in a host
unit test to prove it directly. `VoiceControllerMailboxTest[s]` proves the wiring: that flooding a
live controller cannot grow its memory past either bound, that a critical-lane overflow degrades
safely without releasing capture or killing the control session, and that `stop`/`onControlLinkLost`
remain processable under a saturated mailbox. Several of its scenarios deliberately flood the
mailbox **before** the consumer exists (`ManualDispatcher` on Android; a deferred `attach()` on
iOS) rather than racing a live one, because whether an overflow is guaranteed or merely likely
depends on how fast the consumer happens to be scheduled — the mailbox's own bound is
enforced synchronously regardless, but a *test* asserting overflow occurred needs the flood to win
deterministically, not by chance on a fast machine.

### 3.1 The secure control channel — what is proven on a laptop, and what is not

Phase 1b's security path is exercised end to end by `TlsControlChannelTest` (Android) and
`TlsControlChannelTests` (iOS), over **real loopback TCP with a real TLS 1.3 handshake**: both
ends complete a mutually authenticated handshake with certificates this codebase encoded and
signed, each derives the other's `identity_spki_sha256`, both derive the **same** six-digit SAS
from the exporter, and the pin decision is asserted for trusted / unknown / changed-key /
expired-certificate / lying-`HELLO` peers.

Two substitutions make that possible on a laptop, and neither is hidden:

| Production | In the suite | Still real |
|---|---|---|
| Android Keystore holds a non-exportable P-256 key | An in-memory `KeyStore` holds a JCE P-256 key | the certificate encoding, the signing call, the `KeyManager` wiring, the SPKI derivation |
| `android.net.ssl.SSLSockets.exportKeyingMaterial` | `Conscrypt.exportKeyingMaterial` — the method the platform class delegates to | the TLS 1.3 handshake, mutual auth, the exporter computation |
| iOS Keychain holds the identity key | `SecKeyCreateRandomKey` with `kSecAttrIsPermanent: false` | everything else: Apple's shipping `Network.framework` and `Security.framework` |

So what the substitutions remove is *where the private key lives* and *which call frame reaches
the exporter* — not the cryptography, the wire format or the protocol. What they leave unproven is
listed in [`test-results/phase1b-security-spike-20260827.md` §5](test-results/phase1b-security-spike-20260827.md)
and is closed by I-02/I-03/I-04/I-19/I-20/I-21 on the two real phones, not by any laptop test.

---

## 4. L3 — Instrumented tests (emulator / simulator)

| Area | Test | Platform |
|---|---|---|
| Database | Room migrations; FTS4 search ranking; 5 000-track insert performance | Android |
| Database | GRDB migrations; FTS5 parity — **same query, same result set as Room** | iOS |
| Library scan | synthetic MP3/AAC/M4A/FLAC fixtures in `test-media/synthetic/`; tag extraction; artwork; malformed/truncated file does not crash the scan | both |
| Player | scheduled start at a future deadline; seek accuracy; rate change applied and restored | both |
| Transfer | loopback to self: 1 KiB / 5 MiB / 50 MiB; corrupt a chunk ⇒ rejected; truncate ⇒ rejected; cancel mid-transfer ⇒ `.part` removed; **no `.part` ever promoted** | both |
| Manifest sync | loopback sync of a 5 000-entry manifest; kill the sender after page 20 ⇒ receiver keeps the previous manifest and reports not-synchronised; retry succeeds | both |
| Discovery | advertise + browse on loopback/emulator network; service resolves | both |
| **Discovery privacy** | advertised TXT key set is a **subset of `{v, dh, plat}`**; **no TXT value equals, contains or prefixes `peer_id`, `identity_spki_sha256` or any 6-hex prefix of either**; `dh` differs between two advertising sessions of the same install; `dh` is not persisted across app restarts; no TXT value contains a device or user name | both |
| Build baseline | `minSdk`/`compileSdk`/`targetSdk` equal 31/36/36; iOS deployment target equals the ADR-011 value — asserted from build configuration so drift fails a check | both |
| Route handling | simulated route change and interruption callbacks fire the right state transitions and emit an `AUDIO_STATE` with `route_state: "transitioning"` then `"stable"` | both |
| Background | Android foreground service survives Doze simulation; iOS background-audio assertion holds | both |

### 4.1 Android ride-lifecycle and foreground-service tests

These exist because [ARCHITECTURE §6.4](ARCHITECTURE.md#64-android-ride-lifecycle-and-the-background-microphone-rule)
is a platform rule the app must obey, not a preference.

| ID | Procedure | Pass condition | Phase |
|---|---|---|---|
| AF-01 | START RIDE from a resumed Activity with all permissions granted, intercom enabled | Service starts with types `mediaPlayback\|microphone`; capture opens; no exception | 1/6 |
| AF-02 | START RIDE with intercom disabled (or Mode E) | Service starts with `mediaPlayback` **only**; microphone never opened — asserted, not assumed | 6 |
| AF-03 | `RECORD_AUDIO` denied, then START RIDE | Ride starts music-only; amber "intercom unavailable"; **music plays** (FR-025); no crash; no mic FGS type requested | 6 |
| AF-04 | Attempt to start the ride from a background entry point (debug-only broadcast receiver) | Refused with a clear message; `ForegroundServiceStartNotAllowedException` caught and logged as a state-machine event; **no silent retry** | 6 |
| AF-05 | Start the ride, then lock the screen for 30 min | Session, playback and capture continue; `reconnect_count` unchanged; notification still present | 2/6 |
| AF-06 | `POST_NOTIFICATIONS` denied, then START RIDE | Session runs; documented loss of the lock-screen control surface; no crash | 6 |
| AF-07 | Swipe the task from Recents mid-ride | `onTaskRemoved` → `ENDING`: audio released, sockets closed, **no orphaned foreground service** | 6 |
| AF-08 | Kill the app process mid-ride | Service does not restart in the background (`START_NOT_STICKY`); on relaunch, state is restored and the ride must be started explicitly again | 6 |
| AF-09 | Enable the intercom while the app is backgrounded | Not attempted; UI instructs the user to bring RideLink to the front. **No background microphone-FGS start, ever** | 6 |
| AF-10 | Inspect the merged manifest | `RECORD_AUDIO`, `INTERNET`, `POST_NOTIFICATIONS`, `BLUETOOTH_CONNECT`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `FOREGROUND_SERVICE_MICROPHONE` present; nothing beyond what §6.4 lists | 1 |

### 4.2 iOS audio-session and route tests

| ID | Procedure | Pass condition | Phase |
|---|---|---|---|
| IA-01 | Configure the music-only session (`.playback`) | Route is media-quality stereo; `AUDIO_STATE` reports `media_stereo` / `media_quality: "full"` | 2 |
| IA-02 | Switch to the intercom session (`.playAndRecord`, `.voiceChat`, `[.allowBluetoothHFP, .allowBluetoothA2DP, .duckOthers]`) | Duplex route activates; `AUDIO_STATE` reports a duplex profile for **both** input and output and `media_quality: "reduced"` — never independent media output plus duplex input | 2 |
| IA-03 | Media-quality → duplex transition, timed | `route_state: "transitioning"` emitted at the start and `"stable"` at the end; measured duration recorded in `docs/test-results/`; the drift ladder is suspended for the duration | 2/6 |
| IA-04 | Duplex → media-quality transition (intercom off) | Route returns to media-quality stereo; `AUDIO_STATE` updated with an incremented `revision` | 6 |
| IA-05 | Disconnect the Bluetooth device mid-session, then reconnect | `routeChangeNotification` handled for both `oldDeviceUnavailable` and `newDeviceAvailable`; route recovers; no crash; `AUDIO_STATE` tracks each step | 6 |
| IA-06 | Inbound phone call during a session | `interruptionNotification` began/ended handled, `.shouldResume` honoured; session restored | 6 |
| IA-07 | Force `mediaServicesWereResetNotification` | Engine graph rebuilt and session re-activated from scratch; playback resumes; asserted, not assumed | 6 |
| IA-08 | Wired headset attached mid-session | `endpoint_class: "wired"`, `duplex_wide_stereo`, `media_quality: "full"` — the model must express this without a Bluetooth-specific special case | 6 |
| IA-09 | No audio device attached | `endpoint_class: "builtin_speaker"`, profile `builtin` | 6 |

---

## 5. L4 — Two-device integration (the two real phones)

Wi-Fi only, no Bluetooth audio yet. This is where most cross-platform defects will surface.

| ID | Procedure | Pass condition | Phase |
|---|---|---|---|
| I-01 | Both apps open on the same Wi-Fi → observe discovery | each sees the other within 5 s | 1 |
| I-02 | Tap peer → compare the 6-digit codes on both screens | codes **identical** and **exactly six digits**; both confirm; session reaches `CONNECTED` | 1 |
| I-03 | Kill and reopen both apps | reconnects silently, **no code prompt** | 1 |
| I-04 | Edit one device's stored pin to a wrong SPKI value, reconnect | refused with `pin_mismatch`, surfaced as a security warning, **no auto re-pair** | 1 |
| I-05 | Aeroplane-mode one phone 10 s, restore | `RECONNECTING` → returns to the state it left; `reconnect_count` = 1 | 1 |
| I-06 | Aeroplane-mode 3 min | `DISCONNECTED`; recovers on manual retry | 1 |
| I-07 | Repeat I-01…I-03 on an Android hotspot, then an iPhone hotspot | all three topologies work, or the failure is documented with a reason | 1 |
| I-08 | Observe clock sync for 5 min | offset stddev < 5 ms; no step > 30 ms | 1 |
| I-09 | Both phones tap *play* within ~100 ms | exactly one track plays; both agree; no double-skip | 5 |
| I-10 | Transfer a 40 MB track while issuing `PAUSE` | `PAUSE` applies within 200 ms — proves the bulk plane does not block control | 4 |
| I-11 | Transfer with a deliberately corrupted chunk | rejected; nothing promoted; clear error | 4 |
| I-12 | Play the same track on both, measure drift for 30 min | steady-state < 25 ms; **never** > 150 ms | 5 |
| I-13 | Voice session up, then break Wi-Fi | music continues (FR-025); voice recovers on reconnect | 6 |
| I-14 | Wrong protocol version (test build) | clean `version_mismatch`, no crash | 1 |
| **I-15** | **Simultaneous connect, trusted peers:** both phones tap connect within ~50 ms (or both restart Wi-Fi together) | **exactly one** control connection survives; `HELLO` processed once; one `session_id`; no reconnect loop; `reconnect_count` **not** incremented by the discarded socket; both agree on the leader | 1 |
| **I-16** | **Simultaneous connect, first-time pairing:** unpair both, then have both users tap the other's peer entry at the same moment | **exactly one SAS code shown, on one connection**; pairing completes once; one trusted-peer record per side | 1 |
| **I-17** | **Repeated simultaneous reconnect:** toggle Wi-Fi on the AP 10 times so both peers always retry together | converges to one connection every time; no session ever splits; no monotonic growth in connection count or error log volume | 1 |
| **I-18** | Third connection attempt against an established session (debug build opens an extra socket) | rejected with `session_already_active`; live session unaffected — no state change, no audio glitch | 1 |
| **I-19** | Regenerate one device's identity **certificate** around the same keypair, reconnect | connects silently; **no SAS prompt**; log shows a certificate re-issue with unchanged SPKI | 1 |
| **I-20** | Regenerate one device's identity **keypair**, reconnect | refused with `pin_mismatch`; security warning; recovery only via explicit forget-and-re-pair with a fresh SAS | 1 |
| **I-21** | Set one device's clock far forward so its certificate is outside its validity window | `certificate_invalid`, **not** `pin_mismatch`; message points at the clock, not at an attack | 1 |
| **I-22** | Capture mDNS traffic with an independent tool (`dns-sd -B`, Wireshark) while both apps advertise | TXT records contain only `{v, dh, plat}`; **no value matches `peer_id`, `identity_spki_sha256` or any 6-hex prefix**; `dh` observed to change across advertising sessions | 1 |
| **I-23** | Sync a real 1 000+ track manifest between the phones | completes; multiple `MANIFEST_PAGE` frames observed; every frame < 256 KiB; digest validates; catalogue counts match on both sides | 4 |
| **I-24** | Kill the sender mid-manifest (after ~half the pages) | receiver keeps its **previous** catalogue and reports not-synchronised; retry after reconnect succeeds; no partial catalogue ever displayed | 4 |
| **I-25** | Open the intercom on one phone while both are playing music | peer's `AUDIO_STATE` shows the duplex profile for output as well as input and `media_quality: "reduced"`; the diagnostics screen shows it; drift ladder suspended during the transition | 6 |

**Drift measurement method (I-12)** — needed because ear-judgement is not a measurement:
both apps log `POSITION_REPORT` pairs with session timestamps; a script in `tools/` computes
the drift series and emits min/median/p95/max. Acceptance is read off the p95. *Independent
cross-check:* record both phones' output on one stereo recorder and cross-correlate the
channels — this validates the app's own numbers rather than trusting them.

**Simultaneous-connect method (I-15…I-17)** — a human cannot reliably tap two phones within
50 ms. Use either: (a) a debug-build "connect at session-clock T" trigger armed on both phones,
or (b) cycle the AP's radio so both peers lose the link at the same instant and retry together.
Method (b) is preferred because it is the real-world case. Record the number of trials and the
number of trials that converged; the pass condition is **all of them**.

---

### 5.1 Phase 2a voice — the two-device gate (V-01…V-11)

All **pending**. None of these can be simulated, and none may be marked passed from a laptop run.

| ID | Procedure | Recorded result |
|---|---|---|
| V-01 | Pair the two phones, reach `CONNECTED`, tap Start Voice on the **rider** (whichever is the leader). Voice reaches `active` on both | pending |
| V-02 | Same, started from the **pillion** (the follower): its intent must reach the leader, which then offers. Exactly one offer, one answer | pending |
| V-03 | Both users tap Start Voice within ~1 s. Exactly **one** `voice_session_id` appears on both diagnostics screens; no second negotiation | pending |
| V-04 | With voice active, check the diagnostics card on both: selected candidate type is `host` on both, and `unexpectedCandidateTypeSeen` is false. **This is the on-device check that PROTOCOL §7.6 held on a real Wi-Fi radio** | pending |
| V-05 | Mute on one phone; the other's diagnostics show `mic_muted` for the peer, and the muted side is inaudible. Unmute restores it | pending |
| V-06 | End Voice on one side; both return to `idle`, capture is released on the side that ended | pending |
| V-07 | Kill Wi-Fi for ~10 s mid-call. Media drops, the control ladder reconnects, voice is rebuilt with a **new** `voice_session_id`, and the capture device was **not** reopened (no audible route change on the Bluetooth endpoint) | pending |
| V-08 | Lock the screen with voice active. Voice continues. Unlock; still active. **Android's foreground-service and iOS's background-audio behaviour are both unverified until this passes** | pending |
| V-09 | Deny `RECORD_AUDIO` on Android. The ride continues, the diagnostics show `mic: unavailable`, and nothing crashes (FR-025) | pending |
| V-10 | Android hotspot, then iPhone hotspot, then a common Wi-Fi AP. Voice comes up on each topology, or the failure is recorded with which topology and why | pending |
| V-11 | Measure end-to-end mouth-to-ear latency on the real chain. **Record the number; do not report the <200 ms target as met until this exists** | pending |

## 6. L5 — Audio hardware (full four-device chain)

Requires the helmet unit and the pillion TWS. Phase 0 covered the feasibility question; these
are regression checks on the built app.

| ID | Procedure | Pass condition | Phase |
|---|---|---|---|
| A-01 | Music to helmet unit; music to TWS | stable, no dropouts, 10 min | 2 |
| A-02 | Two-way voice, stationary, 10 min | intelligible both ways, no runaway echo | 2 |
| A-03 | Mic active while music plays; record the observed profile and sample rate on **both** endpoints | behaviour recorded; `AUDIO_STATE` matches what was measured; `profile_coupling` and `confidence` updated to `measured` in the route layer; a usable mode identified | 6 |
| A-04 | Lock both screens; continue A-02 for 30 min | session survives; audio continues | 2 |
| A-05 | Disconnect and reconnect the helmet unit mid-session | route recovers; no crash; state consistent; `AUDIO_STATE` tracks each step | 6 |
| A-06 | Inbound phone call during a session | interruption handled; session restored after the call | 6 |
| A-07 | Deny microphone permission, then start | clear message; **music still works** (FR-025) | 6 |
| A-08 | Ducking on/off with speech | smooth 150–250 ms ramp, no step or click | 6 |
| A-09 | Voice latency measurement | measured and recorded; target < 200 ms | 2 |
| **A-10** | PTT pressed 50 times over 10 minutes with music playing | **no profile switch per press** — the capture device stays open (ARCHITECTURE §6.3); music quality is constant across all 50 presses; measured, not judged | 6 |
| **A-11** | Turn the intercom off mid-ride, then on again | one deliberate, announced route change each way; measured duration recorded; music resumes at full quality when off | 6 |

**Voice latency method (A-09):** play a sharp click into the rider mic; record the pillion
earbud output and the source on one recorder; measure the offset by cross-correlation. Report
the end-to-end figure *including* both Bluetooth hops, since that is what the humans hear.
Record it in `docs/test-results/` — one row per run, with app version and hardware.

**Profile-stability method (A-10):** record the helmet unit's output continuously for the full
10 minutes on an external recorder. A profile switch is audible as a level and bandwidth
discontinuity; measure the spectral centroid over time and assert it has no steps. This is the
regression test for the product's single largest risk, so it gets a measurement rather than an
opinion.

---

### 6.1 Phase 2a voice — the audio-hardware gate (A-12…A-15)

All **pending**, and all of them are the product's highest risk (ARCHITECTURE §6.5, ADR-016).

| ID | Procedure | Recorded result |
|---|---|---|
| A-12 | Helmet Bluetooth unit on the rider's phone, TWS on the pillion's. Start voice. Record the **actual** `effective_output_profile` and sample rate each platform reports, and whether they match what `AndroidAudioRouteMapper`/`IosAudioRouteMapper` *assumed*. Every `assumed` value in those two files is a guess until this runs | pending |
| A-13 | With music playing, start voice. Record whether the output actually degrades — i.e. whether `profile_coupling: input_forces_output` is true for the real hardware. **This is the single claim ADR-016 exists to make checkable** | pending |
| A-14 | Judge whether WebRTC's AEC/NS/AGC cope with the helmet unit's acoustics at 50 and 100 km/h. Turn each stage off individually (ADR-003 requires them to be individually disableable for exactly this) and record the difference | pending |
| A-15 | Once A-12/A-13 are recorded, flip `confidence` from `assumed` to `measured` in both route mappers and fill in `docs/PHASE0_RESULTS.md`. `AndroidAudioRouteMapperTest`/`IosAudioRouteMapperTests` currently **assert** `assumed`, so this step is what makes those tests change | pending |

## 7. L6 — Ride tests

Ordered by risk. Do not skip forward: REQUIREMENTS §17.1 sequences these deliberately.

| ID | Stage | Checks | Gate |
|---|---|---|---|
| R-01 | Stationary, helmets on, engine off | full pre-ride flow, 15 min conversation | Phase 6 |
| R-02 | Engine running, stationary | vibration and engine noise; mic intelligibility | Phase 6 |
| R-03 | Low speed, controlled/closed area | wind onset, route stability, reconnects | Phase 7 |
| R-04 | Normal road, 30 min | conversation quality, music sync, no manual intervention | Phase 7 |
| R-05 | Long ride, 2 h+ | battery drain both phones, thermal, drift, reconnect count | Phase 7 |

Safety: R-01/R-02 with the bike stationary. R-03 in a closed area. All phone configuration
happens **before** motion — the product rule is also the test rule (NFR-09). Ride Mode is started
from the visible app before setting off, which is also the only legal way to start it
(ARCHITECTURE §6.4).

Record per ride: duration, ambient conditions, reconnect count, drift p95, battery start/end,
subjective intelligibility 1–5, and every anomaly. Template in `docs/test-results/`.

---

## 8. Static analysis and hygiene

| Check | Tool | Gate |
|---|---|---|
| Kotlin style | ktlint | zero violations |
| Kotlin complexity | detekt | zero new issues |
| Android correctness | Android Lint | zero errors; warnings triaged |
| Swift style | SwiftLint | zero violations |
| Swift format | SwiftFormat | `--lint` clean |
| Swift concurrency | Swift 6 strict mode | zero warnings |
| Domain purity (Android) | `core` is a `kotlin("jvm")` module | `import android.*` in `core` does not compile — enforced by the classpath, not a rule |
| Domain purity (iOS) | `swift test` for `RideLinkCore` on **macOS** | an iOS-only import fails on the laptop |
| Platform baseline | script in `tools/` | `minSdk`/`compileSdk`/`targetSdk` = 31/36/36; iOS deployment target = ADR-011 value |
| Dependency allowlist | script in `tools/` | **no analytics/ads/telemetry/crash-reporter SDK** (NFR-05); no DI framework while ADR-014 stands |
| Secret scan | script in `tools/` | no keystores, `.jks`, `.p12`, `.pfx`, `.mobileprovision`, private keys, personal audio |
| Log-hygiene scan | script in `tools/` | no raw-audio write path; **no log path for the SAS, bulk tokens, TLS secrets or exporter output**; `identity_spki_sha256` never logged beyond 6 hex |
| Discovery-privacy scan | script in `tools/` | no code path writes `peer_id`, `identity_spki_sha256` or any prefix of either into an mDNS TXT record |
| Retired-vocabulary scan | script in `tools/` | source and docs contain no `cert_fingerprint`, no `fp6`, no bare `.allowBluetooth`, and no `a2dp`/`hfp` string in a wire value |

The last six are privacy, security and consistency requirements expressed as tests, which is the
only way they stay true.

---

## 9. Phase exit gates

A phase is complete only when **all** of its row passes and `STATUS.md` records the evidence.

| Phase | L1/L2 | L3 | L4 | L5 | L6 | Extra |
|---|---|---|---|---|---|---|
| 1 Peer session | all, incl. SAS · identity · dedup vectors | discovery + privacy, DB, baseline, AF-01/AF-10 | I-01…I-08, I-14…I-22 | — | — | §8 clean |
| **2a Voice foundation** | + `voice-signal/` (70) + `voice-fsm/` (52); real-WebRTC loopback (host-only ICE, DTLS-SRTP, Opus) on macOS; pre-auth `VOICE_*` refusal over real TLS on both platforms | — *(none possible: see §3.1a)* | **V-01…V-11 pending** | **A-12…A-15 pending** | — | §8 clean. **No latency claim** |
| 2 Intercom | + voice state | route sim, IA-01…IA-03 | I-13 | A-01, A-02, A-04, A-09 | — | latency recorded |
| 3 Local music | + library, hashing | library, player | — | A-01 | — | 1 000+ real tracks indexed |
| 4 Shared library | + manifest paging + paging-error vectors | transfer loopback, manifest sync loopback | I-10, I-11, I-23, I-24 | — | — | 50 MB transfer verified; 5 000-entry manifest synced |
| 5 Sync playback | + drift, queue | player scheduling | I-09, I-12 | — | — | drift p95 recorded |
| 6 Coexistence | + audio policy, audio-state vectors | AF-02…AF-09, IA-04…IA-09 | I-13, I-25 | A-03, A-05…A-08, A-10, A-11 | R-01, R-02 | mode chosen and recorded; `confidence` = `measured` |
| 7 Ride Mode | all | all | all | all | R-03…R-05 | battery/thermal recorded |
| 8 Hardening | all | all | all | all | R-05 | security review, log export, sideload build |

---

## 10. V1 acceptance

REQUIREMENTS §17.2, mapped to the tests that prove each line. This is the checklist that decides
whether V1 exists.

| Acceptance item | Proven by |
|---|---|
| Trusted local session, repeatably | I-01, I-02, I-03, I-15…I-18 |
| Both Bluetooth devices work | A-01 |
| 60 min two-way voice, no restart | A-02 extended to 60 min |
| One acceptable music+intercom mode validated | A-03, A-10, R-02 |
| Import / index / search local music | L3 library + 1 000-track real run |
| Missing track transferred, hash-verified, played | I-11 + manual UJ-05 |
| Shared catalogue survives a real library size | I-23, I-24 |
| Either user controls queue and playback | I-09 |
| Drift within target over 30 min | I-12 |
| Screen lock does not end the session | A-04, AF-05 |
| Disconnect/reconnect restores state | I-05, I-06 |
| No backend/internet required | full run with mobile data **off** on both phones |
| No raw audio in logs | §8 log-hygiene scan + manual log review |
| Nothing durable leaks on the local network | I-22 + §8 discovery-privacy scan |
