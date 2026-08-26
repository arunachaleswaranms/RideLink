# RideLink — Architecture

**Status:** baseline for Phase 1. **Last updated:** 26 August 2026.
Requirement IDs (`FR-nnn`, `NFR-nn`) refer to [`REQUIREMENTS.md`](REQUIREMENTS.md).
Binding decisions live in [`DECISIONS/`](DECISIONS/).

---

## 1. System shape

Two native apps, one documented wire protocol, no server.

```
┌──────────────────────┐                                  ┌──────────────────────┐
│  Rider — Android     │                                  │  Pillion — iOS       │
│  OnePlus Nord 5      │                                  │  iPhone 17 Pro Max   │
│                      │                                  │                      │
│  ┌────────────────┐  │      ── local IP network ──      │  ┌────────────────┐  │
│  │  RideLink app  │◄─┼──────────────────────────────────┼─►│  RideLink app  │  │
│  └───────┬────────┘  │   Wi-Fi LAN  or  phone hotspot   │  └───────┬────────┘  │
│          │           │                                  │          │           │
│      Bluetooth       │   • mDNS/DNS-SD  discovery        │      Bluetooth       │
│          │           │   • TCP+TLS      control          │          │           │
│          ▼           │   • TCP+TLS      file transfer    │          ▼           │
│  Helmet BT unit      │   • WebRTC/SRTP  voice            │  TWS earbuds         │
│  (speakers + mic)    │                                  │  (buds + mic)        │
└──────────────────────┘                                  └──────────────────────┘
```

Two independent radio layers, deliberately never mixed (REQUIREMENTS §7):

- **Bluetooth** is *only* the link between one phone and that phone's own audio device. Neither phone ever talks Bluetooth to the other phone.
- **IP over Wi-Fi** is the *only* phone-to-phone transport (FR-002).

### 1.1 The three data planes

| Plane | Transport | Reliability | Carries | Phase |
|---|---|---|---|---|
| **Control** | TCP + TLS 1.3, length-prefixed JSON | reliable, ordered | pairing, capabilities, clock sync, playback commands, queue replication, manifests, transfer negotiation, health | 1 |
| **Media (voice)** | WebRTC audio, DTLS-SRTP, Opus | unreliable, real-time | microphone audio both directions | 2 |
| **Bulk (files)** | TCP + TLS 1.3, chunked frames on a second connection | reliable, ordered | music file bodies | 4 |

Rationale for splitting rather than putting everything on WebRTC DataChannels: WebRTC needs an
out-of-band signalling channel to exchange SDP before it exists at all. That channel is
unavoidable, so we build it first, make it good, and let it carry everything that is not
real-time audio. See [ADR-003](DECISIONS/ADR-003-webrtc-voice-transport.md) and
[ADR-007](DECISIONS/ADR-007-control-channel-over-tcp-tls.md).

Consequence that matters for FR-025 (graceful degradation): music sync, queue, catalogue and
transfer all live on the control plane and therefore keep working when the voice plane fails,
and vice versa.

---

## 2. Layering (identical concepts on both platforms)

Both apps implement the same five layers. Only the bottom two are platform-specific in a
meaningful way; the middle layers are line-for-line ports of the same state machines so that
shared test vectors validate both.

```
┌───────────────────────────────────────────────────────────────┐
│ 5  UI            Compose  /  SwiftUI                          │  platform idiom
├───────────────────────────────────────────────────────────────┤
│ 4  Feature state Session · Library · Player · Transfer stores  │  ports of one design
├───────────────────────────────────────────────────────────────┤
│ 3  Domain        state machines, sync maths, manifest diff,     │  PURE, no platform
│                  conflict resolution, queue algebra            │  types — unit-testable
├───────────────────────────────────────────────────────────────┤
│ 2  Protocol      envelope codec, message types, validation      │  driven by shared
│                  sequence/replay rules                          │  test vectors
├───────────────────────────────────────────────────────────────┤
│ 1  Platform      NsdManager│NWBrowser · TLS sockets · WebRTC    │  native, thin,
│                  Media3│AVAudioEngine · MediaStore│FileProvider │  behind interfaces
└───────────────────────────────────────────────────────────────┘
```

**The hard rule (NFR-07):** layer 3 contains no `android.*`, no `Foundation` networking, no
audio types, and no clock reads. It takes time and inputs as parameters and returns decisions.
Everything difficult about RideLink — drift correction, command ordering, reconnect
reconciliation — lives there and is therefore testable on a laptop with no phone attached.

Layer 1 is the only place allowed to know that Bluetooth exists.

---

## 3. Session state machine

The DOCX (§15) and this build's instructions name states differently. We implement the superset;
the mapping is recorded in [ADR-008](DECISIONS/ADR-008-requirement-conflict-resolutions.md).

| Implemented state | DOCX §15 name | Meaning |
|---|---|---|
| `IDLE` | IDLE | No session. Nothing on the network. |
| `DISCOVERING` | *(implicit)* | Browsing/advertising mDNS. No peer chosen. |
| `PAIRING` | PAIRING | Peer chosen; trust being established (first meeting only). |
| `CONNECTING` | CONNECTING | TLS handshake + `HELLO`/`HELLO_ACK` + capability exchange. |
| `CONNECTED` | READY | Control plane up, clock synced, audio routes checked. Pre-ride. |
| `RIDE_ACTIVE` | RIDING | Ride Mode engaged: intercom and/or music session running. |
| `RECONNECTING` | RECONNECTING | Control plane lost; local playback and state retained. |
| `DISCONNECTED` | *(implicit)* | Reconnect budget exhausted; awaiting user. |
| `ENDING` | ENDING | Deliberate teardown; persisting state. |
| `ERROR` | *(implicit)* | Unrecoverable fault with a diagnosable cause. |

### 3.1 Legal transitions

```
IDLE ──────────► DISCOVERING ──────► PAIRING ──────► CONNECTING
  ▲                   │                  │                │
  │                   │ (cancel)         │ (reject/       │ (fail)
  │                   ▼                  │  timeout)      ▼
  │              ┌─ IDLE ◄───────────────┘         RECONNECTING
  │              │                                   │      │
  │              │                          (success)│      │(budget spent)
  │              │                                   ▼      ▼
  │              │        ┌──────────────────► CONNECTED   DISCONNECTED
  │              │        │                      │  ▲            │
  │              │        │        (Start Ride)  │  │(End Ride)  │(retry)
  │              │        │                      ▼  │            ▼
  │              │        │                  RIDE_ACTIVE ──► DISCOVERING
  │              │        │                      │
  │              │        │      (link lost)     │
  │              │        └──── RECONNECTING ◄───┘
  │              │
  └──────────────┴──── ENDING ◄──── {CONNECTED, RIDE_ACTIVE, RECONNECTING, DISCONNECTED}
                         │
  ERROR ◄── (fatal from any state) ──► ENDING ──► IDLE
```

Rules that prevent the bugs this shape is designed to avoid:

1. `RECONNECTING` **returns to the state it left** (`CONNECTED` or `RIDE_ACTIVE`), never skips forward. A link blip must not silently start a ride.
2. `RECONNECTING` never tears down the local player. Music keeps playing; only *synchronisation* is suspended (FR-021, UJ-06).
3. Only `ENDING` may release the audio session and stop the foreground service.
4. There is exactly one owner of this state per app (`SessionCoordinator`). No view model may hold connection state of its own — this is the explicit anti-requirement in the brief.
5. Every transition is logged with a monotonic timestamp, prior state, trigger and reason. This log is the primary debugging artefact (NFR-08).

---

## 4. Discovery and pairing

**Discovery (FR-001, [ADR-002](DECISIONS/ADR-002-lan-mdns-discovery.md)):** DNS-SD service type
`_ridelink._tcp`, local domain. Android `NsdManager`; iOS `NWListener` +
`NWBrowser`. Both apps advertise *and* browse — neither is a designated server, so either
person can start the session (Design Principle: "Phones are peers").

TXT records carry only non-secret routing hints:

| Key | Example | Notes |
|---|---|---|
| `v` | `1` | protocol major version |
| `pid` | `a3f1…` (16 hex) | **rotating** public peer handle, not the long-term identity |
| `plat` | `android` \| `ios` | for UI labelling |
| `fp6` | `4B7E9C` | first 6 hex of TLS cert fingerprint — lets a *known* peer be recognised without a handshake |

No display name, no serial number, no library size, no token in the TXT record. Anyone on the
Wi-Fi can read these (NFR-05, §11).

**Pairing — first meeting only:**

1. Both on the same LAN/hotspot; both in `DISCOVERING`.
2. One user taps the discovered peer → `PAIRING`.
3. TLS 1.3 handshake with self-signed certificates, both sides. Nothing is trusted yet.
4. Each side derives a **6-digit verification code** from the TLS exporter secret bound to both
   certificates — so a man-in-the-middle produces different codes on the two screens.
5. Both users see the code; both confirm it matches. This is the "explicit local approval"
   of §11, and it is a real channel-binding check, not decoration.
6. On confirmation each side stores the peer's certificate SPKI fingerprint plus a durable
   `peer_id`. Trust established.

**Subsequent connections** are silent: TLS + pinned-fingerprint check, no code, no prompt. A
certificate that does not match the pin is refused and surfaced as a security warning — never
auto-re-paired ("Do not accept arbitrary nearby peers after initial trust is established").

**Fallback (deferred to Phase 1b):** if mDNS fails — some hotspots and enterprise APs block
multicast — a manual path offers `host:port` + the 6-digit code, presentable as a QR code. The
protocol is identical; only peer *location* changes.

---

## 5. Leadership and command ordering

No user-visible master (Design Principle: "Phones are peers"; FR-014 requires *deterministic*
conflict resolution). Internally one peer is **leader**, purely to serialise commands and own
the session clock.

- **Election:** the peer with the lexicographically smaller `peer_id` leads. `peer_id` is fixed at pairing, so this is stable, needs no negotiation, and cannot flap.
- **Recovery:** with two devices there is no quorum question. On reconnect the same rule re-elects the same leader. If the leader is the one that vanished, the survivor keeps playing locally and resumes as follower on reconnect.
- **Ordering:** any peer may *issue* a command. The follower sends its intent to the leader; the leader assigns the authoritative `command_seq` and `effective_at`, then broadcasts. Both apply. Simultaneous conflicting commands are resolved by the leader's arrival order — deterministic by construction rather than by timestamp comparison.
- **Latency cost:** a follower-issued command costs one extra half-RTT (~5–20 ms on a local link). Negligible against the ≥120 ms `effective_at` scheduling lead described in §7.
- **Optimistic local feedback:** the issuing device may update its *UI* immediately (button state) but must not change *audio* until the leader's broadcast arrives. This keeps the UI responsive without ever letting the two devices diverge audibly.

---

## 6. Audio architecture

Voice and music are separate engines that share one route. This is the highest-risk area of the
product (REQUIREMENTS §8) and the reason Phase 0 existed.

### 6.1 Android

| Concern | Mechanism |
|---|---|
| Music playback | `androidx.media3` `ExoPlayer` inside a `MediaSessionService` |
| Background / lock screen | `MediaSessionService` (media foreground service type) + a ride foreground service for the peer session (FR-019) |
| Voice | WebRTC `PeerConnection` with the built-in `AudioDeviceModule` (owns its own `AudioRecord`/`AudioTrack`, HW AEC/NS/AGC where present) |
| Route + focus | `AudioManager` — `setCommunicationDevice()` (API 31+) for the helmet unit, `AudioFocusRequest` with `WILL_PAUSE_WHEN_DUCKED = false` so *we* control ducking, `AudioDeviceCallback` for connect/disconnect |
| Ducking | `ExoPlayer.volume` ramped over ~150–250 ms, never stepped (FR-016) |

### 6.2 iOS

| Concern | Mechanism |
|---|---|
| Music playback | `AVAudioEngine` + `AVAudioPlayerNode` (chosen over `AVAudioPlayer` for sample-accurate scheduling — see §7) |
| Background / lock screen | `UIBackgroundModes: audio`; `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter` |
| Voice | WebRTC `RTCPeerConnection`; session category `.playAndRecord`, mode `.voiceChat`, options `[.allowBluetooth, .allowBluetoothA2DP, .duckOthers?]` |
| Route + interruption | `AVAudioSession.routeChangeNotification`, `interruptionNotification`, `mediaServicesWereResetNotification` — all three handled explicitly, not just the first (FR-019, NFR-02) |
| Ducking | `AVAudioMixerNode.outputVolume` ramp, same 150–250 ms envelope |

### 6.3 Intercom modes

Modes A–E from REQUIREMENTS §8 are implemented as one policy object, not five code paths.
Whichever mode Phase 0 selected becomes the default; the rest stay reachable because the brief
requires configurability and because wind noise may change the answer at speed.

```
mode := { mic_always_open: Bool,
          gate: none | vox(threshold, hangover) | ptt,
          on_speech: duck(to_pct) | pause,
          music_quality_priority: high | yield_to_voice }
```

Mode A = `{true, none, duck(25%), high}` · Mode B = `{false, vox, duck(25%), high}` ·
Mode C = `{false, ptt, duck(35%), high}` · Mode D = `{true, none, pause, yield}` ·
Mode E = `{false, ptt-disabled, —, high}`.

**Open input:** the mode Phase 0 actually validated. Until recorded in
`PHASE0_RESULTS.md`, Phase 6 defaults to **Mode C (PTT)** as the safest assumption, because it
is the only mode that cannot be broken by HFP profile switching.

---

## 7. Clock synchronisation and synchronised playback

Satisfies FR-013. Uses monotonic clocks only — `System.nanoTime()` / `SystemClock.elapsedRealtimeNanos()`
on Android, `DispatchTime.now().uptimeNanoseconds` (`mach_absolute_time`) on iOS. Wall-clock time
is never used for scheduling; NTP steps and user clock edits would otherwise inject jumps.

### 7.1 Offset estimation

The two devices' monotonic clocks have unrelated epochs, so we estimate the offset with an
NTP-style exchange on the control plane:

```
A sends  PING  { t1 = A.mono() }
B replies PONG { t1, t2 = B.mono() on receive, t3 = B.mono() on send }
A records t4 = A.mono() on receive

rtt    = (t4 - t1) - (t3 - t2)
offset = ((t2 - t1) + (t3 - t4)) / 2      # add to A's clock to get B's
```

Filtering, because a single sample on Wi-Fi is worthless:

1. Collect 11 samples, ~50 ms apart, at `CONNECTING` and every 10 s thereafter.
2. Discard any sample whose `rtt` exceeds `2 × min(rtt)` in the window — asymmetric queueing delay is the dominant error and it only ever *inflates* RTT.
3. Take the **offset of the minimum-RTT sample** as the estimate; the lowest-RTT exchange is the least distorted one.
4. Smooth across windows with an EWMA (α = 0.2) and expose the residual spread as jitter (FR-023).
5. Reject a new estimate that moves the offset by more than 30 ms unless two consecutive windows agree — this rejects transient Wi-Fi power-save stalls.

`session_time` = leader's monotonic clock. Every device converts:
`session_time = local_mono + offset_to_leader` (leader's offset is 0).

### 7.2 Scheduled start

```
1. resolve track by content_hash
2. if peer lacks it → transfer, then confirm possession (§8)
3. leader picks  effective_at = session_now + LEAD,  LEAD = max(120ms, 4 × rtt_p95)
4. leader broadcasts PLAY { track_hash, position_ms, effective_at, command_seq }
5. each device converts effective_at → its own local monotonic deadline
6. each device pre-rolls the decoder, then starts at that deadline
```

Pre-roll before the deadline is what makes this work — both platforms need tens of milliseconds
to open and prime a decoder, and doing it after the deadline guarantees a late start.
Android: `ExoPlayer` prepared and paused at `position_ms`, released at the deadline.
iOS: `AVAudioPlayerNode.scheduleSegment(at: AVAudioTime(hostTime:))` — the engine starts it in
the render thread, which is materially more precise than a timer callback.

### 7.3 Drift correction

Two independent crystals will diverge (typically 10–50 ppm ⇒ 36–180 ms/hour), so measure and
correct — but the brief is explicit that constant seeking for tiny errors is wrong.

Every 5 s both devices report `POSITION_REPORT { track_hash, position_ms, at_session_time }`.
The leader computes `drift = follower_position − expected_position`, then applies a **dead-band
ladder**:

| \|drift\| | Action | Why |
|---|---|---|
| < 25 ms | nothing | below perceptual threshold for two co-located listeners; correcting adds artefacts |
| 25–120 ms | **rate nudge**: playback rate ×(1 ± 0.002) until drift < 15 ms, then restore 1.0 | 0.2 % is ~3.5 cents — inaudible; corrects 100 ms over ~50 s |
| > 120 ms | **hard seek** to the computed position | already audible as echo; a single clean seek beats a long slew |
| > 2000 ms, or 3 hard seeks in 60 s | declare sync failure, drop to independent playback, surface amber status | honours FR-025 rather than seeking forever |

Rate nudging is the interesting part and is available on both platforms:
`ExoPlayer.setPlaybackParameters(PlaybackParameters(speed))` and
`AVAudioUnitVarispeed` / `AVAudioEngine`'s rate. Both resample rather than change pitch
audibly at 0.2 %.

**Targets** (reconciled in [ADR-008](DECISIONS/ADR-008-requirement-conflict-resolutions.md)):
hard acceptance ≤150 ms, product target <100 ms, stretch <50 ms. The dead-band above is set so
that steady-state error stays under 25 ms, which leaves ample margin.

---

## 8. Music library, catalogue and transfer

### 8.1 Track identity ([ADR-005](DECISIONS/ADR-005-content-hash-track-identity.md))

Two-tier, because hashing a large library on a phone is I/O- and battery-expensive (NFR-03):

| Tier | Definition | Cost | Used for |
|---|---|---|---|
| `quick_id` | `SHA-256(size_bytes ‖ first 64 KiB ‖ last 64 KiB)` | ~1 ms/file | indexing, change detection, catalogue display |
| `content_hash` | `SHA-256(entire file)` — **authoritative** | ~50 ms/file | transfer dedupe, transfer validation, all protocol references |

`content_hash` is computed lazily in a background job and **always** before a track can be
offered, requested, or played in a synchronised session. A track without a `content_hash` is
not eligible for sync — this makes the invariant structural rather than a thing to remember.

`content_hash` identifies **an exact file**, not a musical work. Two different rips of the same
song are two tracks, correctly, since they cannot substitute for each other in a byte-transfer.
To keep the UI sane we additionally derive a non-authoritative `work_key` =
`normalize(artist) ‖ normalize(title) ‖ round(duration_ms, 2s)` used *only* to group
near-duplicates visually. It never drives transfer or identity — the brief's warning about
filenames applies equally to fuzzy metadata keys.

### 8.2 Catalogue

On reaching `CONNECTED`, peers exchange a compact manifest (`quick_id`, `content_hash`,
`work_key`, and display metadata only — never full paths, per §11). Each side computes
presence locally:

`LOCAL_ONLY` · `PEER_ONLY` · `BOTH` · `TRANSFER_PENDING` · `TRANSFERRING` · `TRANSFER_FAILED`

Manifests are diffed by `content_hash` with a manifest revision number, so reconnects and
library edits send deltas rather than the whole list. Nothing is transferred automatically —
transfer is on demand or explicit (FR-011, §9.4).

### 8.3 Transfer (Phase 4)

Chunked frames on a dedicated TLS connection, so a 40 MB file cannot head-of-line-block a
`PAUSE` command:

```
REQUEST → OFFER(size, chunk_size=64KiB, total) → [CHUNK(idx, bytes)] × n → COMPLETE(sha256)
```

Invariants, all from §11 and the brief's transfer rules:

- Bounded memory: one chunk in flight; stream straight to disk. Never buffer a whole file.
- Write to `cache/incoming/<content_hash>.part`, never into the library.
- On `COMPLETE`: verify byte count **and** recompute SHA-256 over what was written to disk — not over what we think we received.
- **Atomic promote only on match**: `rename()` into the library. A mismatch deletes the partial and reports failure. There is no path by which an unverified byte becomes a library track.
- Progress and cancellation throughout; `.part` files are swept on startup.
- Chunk indices are explicit in the frames, so **resume** is a later addition (skip received indices) and not an architectural change. Deferred from V1, per FR-011's "if practical".

### 8.4 Platform library access — a real asymmetry

| | Android | iOS |
|---|---|---|
| Source | `MediaStore.Audio` + user-granted folders via `ACTION_OPEN_DOCUMENT_TREE` | app container only; user imports via `UIDocumentPicker` / Files / Share sheet |
| Reach | effectively the whole on-device library | **only what was explicitly imported** |
| Recursion | yes, within granted trees (FR-007) | yes, within imported folders |

This is a platform constraint, not a design choice: iOS gives no app read access to another
app's media, and Apple Music tracks are DRM-protected and unreadable as files. So the pillion's
catalogue starts empty and fills by import or peer transfer. `MPMediaLibrary` is deliberately
not used — it cannot yield transferable files for protected content, and it would give the UI a
list of tracks that can never be shared. See
[ADR-009](DECISIONS/ADR-009-ios-music-library-scope.md).

Practically: the rider's Android phone is the library of record and the iPhone acquires tracks
over the peer link. The architecture is symmetric; only the initial content is not.

### 8.5 Formats

MP3, AAC, M4A required (§9.1 P0). FLAC enabled on Android (ExoPlayer, native) and on iOS
(`AVAudioFile` decodes FLAC since iOS 11) — cheap to allow, so it is allowed, but not a V1
acceptance criterion.

---

## 9. Proposed module structure

### 9.1 Android — Gradle multi-module

Module boundaries chosen so that `:core:*` compiles and tests on the JVM with no Android
device or emulator. That is what keeps the difficult logic cheap to test.

```
android/
├── settings.gradle.kts               # module graph
├── gradle/libs.versions.toml         # single version catalogue (all pins)
├── build-logic/                      # convention plugins — no copy-pasted build files
│   └── src/main/kotlin/ridelink.{android-library,android-app,jvm-library,compose}.gradle.kts
│
├── app/                              # assembly only: Application, MainActivity, DI graph, nav
│
├── core/
│   ├── model/          [JVM]  Peer, Track, QueueItem, PlaybackState, AudioRoute, SessionMetrics
│   ├── protocol/       [JVM]  envelope + message codecs, validation, sequence/replay rules
│   ├── session-fsm/    [JVM]  session state machine — pure (state, event) -> (state, effects)
│   ├── sync/           [JVM]  offset estimator, outlier rejection, drift ladder
│   ├── queue/          [JVM]  queue algebra, conflict resolution, revisions
│   ├── manifest/       [JVM]  manifest diff, presence computation
│   ├── hashing/        [JVM]  quick_id + content_hash (streaming)
│   └── logging/        [JVM]  structured diagnostic log, redaction rules
│
├── data/
│   ├── database/       [AND]  Room: tracks, presence, queue, peers, transfers (+ FTS4 search)
│   ├── library/        [AND]  MediaStore + SAF scanning, tag/artwork extraction
│   └── settings/       [AND]  DataStore preferences, trusted-peer store (Keystore-backed)
│
├── net/
│   ├── discovery/      [AND]  NsdManager advertise + browse
│   ├── control/        [AND]  TLS socket, framing, reconnect/backoff, keepalive
│   ├── security/       [AND]  keypair + self-signed cert, pinning, SAS derivation
│   ├── transfer/       [AND]  chunked file send/receive, atomic promote
│   └── voice/          [AND]  WebRTC wrapper: PeerConnection, ADM, stats
│
├── audio/
│   ├── player/         [AND]  Media3 ExoPlayer + MediaSessionService, scheduled start, rate nudge
│   ├── route/          [AND]  AudioManager focus/route/device callbacks
│   └── policy/         [JVM]  intercom-mode policy, VOX gate, ducking envelopes (pure)
│
└── feature/
    ├── preride/        [AND]  readiness checks, pairing UI, mode selector
    ├── ride/           [AND]  Ride Mode
    ├── library/        [AND]  browse, search, presence, transfer actions
    └── diagnostics/    [AND]  FR-023 metrics screen, log export
```

Dependency rule, enforced by convention plugins: `feature → core/data/net/audio → core:model`.
No `core → data`, no `feature → feature`, no `core:* → android.*`.

### 9.2 iOS — SPM local packages + thin app target

Same boundaries. Pure logic in platform-agnostic SPM targets that run under `swift test` with
no simulator.

```
ios/
├── RideLink.xcodeproj                 # app target only (thin)
├── RideLink/                          # @main, AppDelegate, DI, root navigation, Info.plist
│
└── Packages/
    ├── RideLinkCore/                  # [pure Swift — no UIKit/AVFoundation]
    │   └── Sources/
    │       ├── RLModel/               # mirrors core:model
    │       ├── RLProtocol/            # mirrors core:protocol — SAME test vectors
    │       ├── RLSessionFSM/          # mirrors core:session-fsm
    │       ├── RLSync/                # mirrors core:sync
    │       ├── RLQueue/               # mirrors core:queue
    │       ├── RLManifest/            # mirrors core:manifest
    │       ├── RLHashing/             # CryptoKit SHA256, streaming
    │       ├── RLAudioPolicy/         # mirrors audio:policy
    │       └── RLLogging/             # mirrors core:logging
    │
    ├── RideLinkPlatform/              # [Apple frameworks]
    │   └── Sources/
    │       ├── RLDiscovery/           # NWListener + NWBrowser
    │       ├── RLControl/             # NWConnection + TLS, framing, reconnect
    │       ├── RLSecurity/            # SecKey, self-signed identity, pinning, SAS
    │       ├── RLTransfer/            # chunked transfer, atomic promote
    │       ├── RLVoice/               # WebRTC wrapper
    │       ├── RLPlayer/              # AVAudioEngine, scheduled start, varispeed
    │       ├── RLRoute/               # AVAudioSession, route/interruption handling
    │       └── RLLibrary/             # document picker import, AVAsset metadata, GRDB store
    │
    └── RideLinkFeatures/              # [SwiftUI]
        └── Sources/{RLPreRide, RLRide, RLLibraryUI, RLDiagnostics}/
```

Concurrency: `SessionCoordinator`, `ControlChannel` and `TransferManager` are `actor`s —
each owns mutable state touched from network and UI contexts, which is exactly the case actors
are for. Pure `RideLinkCore` types are `Sendable` value types.

### 9.3 The shared seam

`protocol/` at the repo root is the single source of truth for the wire format, consumed by
both platforms' test suites:

```
protocol/
├── README.md
├── schema/          # JSON Schema per message type (added in Phase 1 alongside the codecs)
└── vectors/         # golden encode/decode cases + expected sync/queue/diff outcomes
```

Both `core:protocol` (JVM) and `RLProtocol` (Swift) run the *same* `vectors/` files. A wire
incompatibility then fails a unit test on a laptop instead of appearing as a mystery on a
motorcycle. Same technique for `core:sync` / `RLSync` (offset + drift fixtures) and
`core:queue` / `RLQueue` (conflict fixtures).

---

## 10. Build tooling and dependencies

Selection rule from the brief: platform API first, then a well-established library, then a
small specialised one. Everything pinned.

### 10.1 Android

| Need | Choice | Justification |
|---|---|---|
| Build | Gradle KTS + version catalogue + convention plugins | reproducible, one place for pins |
| Language/UI | Kotlin, Jetpack Compose (BOM) | required by brief |
| Async | kotlinx-coroutines, Flow | required by brief |
| JSON | kotlinx-serialization-json | compile-time codegen, no reflection, exact-shape control for the envelope |
| DB + search | Room (+ FTS4) | official; FTS gives FR-008 search without hand-rolled indexing |
| Playback | androidx.media3 (ExoPlayer + MediaSession) | format coverage, precise seek, playback-rate control, `MediaSessionService` solves FR-019 |
| Voice | `io.github.webrtc-sdk:android` | Google publishes no current Maven artifact; this is the maintained community build of upstream WebRTC. **Pinned and reviewed** — see risk register |
| DI | Hilt | official, low ceremony; alternative is manual DI (also viable) |
| Preferences | DataStore + Keystore | Keystore for the device keypair; no secret in plaintext prefs |
| Tests | JUnit, kotlin.test, Turbine, MockK, Robolectric, androidx.test | Turbine for Flow assertions; Robolectric keeps route-logic tests off-device |
| Static analysis | Android Lint + ktlint + detekt | detekt catches the complexity that Lint does not |

Discovery, TLS, audio routing, hashing: **platform APIs, no dependency.**

### 10.2 iOS

| Need | Choice | Justification |
|---|---|---|
| Build | Xcode project + local SPM packages | no extra tooling; `swift test` for pure targets |
| Language/UI | Swift 6, SwiftUI, strict concurrency | required by brief |
| Discovery/transport | Network framework | `NWBrowser`/`NWListener`/`NWConnection` — named in the brief; includes TLS |
| Crypto/hash | CryptoKit | SHA-256, HKDF — platform, audited |
| Playback | AVFoundation (`AVAudioEngine`) | sample-accurate `scheduleSegment(at:)` is required by §7.2 |
| Audio session | AVAudioSession | required by brief |
| DB + search | **GRDB.swift** | needs SQLite **FTS5** for search parity with Room FTS; Core Data/SwiftData make FTS awkward. Actively maintained, single-purpose. *Alternative considered:* raw SQLite3 C API — no dependency but materially more code |
| Voice | `stasel/WebRTC` (SPM) | maintained SPM distribution of Google's official WebRTC binaries. **Pinned** |
| Tests | Swift Testing + XCTest | Swift Testing for pure logic; XCTest for anything needing a host app |
| Static analysis | SwiftLint + SwiftFormat | standard, CI-friendly |

### 10.3 Dependency count

Four third-party dependencies total: WebRTC (×2 platforms), Hilt, GRDB. Everything else is
platform API. No analytics SDK, no ad SDK, no crash reporter, no network client library
(NFR-05, §11). WebRTC is the only large one, and it is the one the brief mandates.

---

## 11. Privacy and security posture

Encoded as build- and code-level rules, not just intentions:

1. Android `INTERNET` permission is required for local sockets and cannot be scoped to LAN; there is no code path that contacts a non-link-local address. iOS declares `NSLocalNetworkUsageDescription` and Bonjour services only.
2. No analytics, advertising, telemetry or crash-reporting SDK. A dependency-allowlist check in CI is a Phase 8 deliverable.
3. Logging goes through `core:logging` / `RLLogging`, which redact by construction: file paths → basename only, `peer_id` → first 6 chars, certificate fingerprints → first 6 hex. Pairing codes, TLS keys, exporter secrets and session tokens have **no** log path at all.
4. No microphone audio is ever written to disk. WebRTC's recorder hooks are not compiled in. Voice exists only in RAM and only while a session is active.
5. Session keys are TLS 1.3 ephemeral (fresh per connection). Only the long-term identity keypair persists, in Keystore / Keychain.
6. `.gitignore` must exclude keystores, `.mobileprovision`, `.p12`, personal audio. `test-media/` holds only synthetic audio.

---

## 12. Known architectural risks

| Risk | Severity | Where it bites | Mitigation |
|---|---|---|---|
| Self-signed cert + identity generation on iOS | **High** | Phase 1b | `SecKey` gives no cert-building API; needs a small hand-written DER X.509 encoder (~150 lines, well-trodden). Fallback: TLS-PSK from the pairing secret, which Network framework supports directly |
| WebRTC dependency is community-published on both platforms | Medium | Phase 2 | Pin exact versions, vendor the binaries, isolate behind `net:voice`/`RLVoice` so replacement is contained |
| `AVAudioEngine` scheduling precision on real hardware | Medium | Phase 5 | Measure, do not assume; loopback test in TEST_PLAN §5 |
| mDNS blocked on hotspots/enterprise APs | Medium | Phase 1 | Manual `host:port` + QR fallback (Phase 1b) |
| Phase 0 results not yet recorded | Medium | Phase 6 | Does not block Phases 1–5; default to Mode C until known |
| Bluetooth HFP profile switch degrades music | **High** | Phase 6 | Product-level: mode policy in §6.3 exists precisely for this |
| iOS catalogue starts empty (§8.4) | Low | Phase 3–4 | Expected; make import prominent in pre-ride UI |

---

## 13. Where to look next

| Question | Document |
|---|---|
| What must it do? | [`REQUIREMENTS.md`](REQUIREMENTS.md) |
| What goes on the wire? | [`PROTOCOL.md`](PROTOCOL.md) |
| How do we know it works? | [`TEST_PLAN.md`](TEST_PLAN.md) |
| What is done, what is next? | [`STATUS.md`](STATUS.md) |
| Why was it decided that way? | [`DECISIONS/`](DECISIONS/) |
| What did the hardware actually do? | [`PHASE0_RESULTS.md`](PHASE0_RESULTS.md) |
