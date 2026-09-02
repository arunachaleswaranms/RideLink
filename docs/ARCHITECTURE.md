# RideLink — Architecture

**Status:** baseline for Phases 1–2a. **Last updated:** 28 August 2026 (Phase 2a — WebRTC
distributions pinned and reviewed, `core`/`RideLinkCore` package listings brought up to date, and
the community-artifact risk closed with evidence. See
[ADR-020](DECISIONS/ADR-020-webrtc-voice-foundation.md)).
Requirement IDs (`FR-nnn`, `NFR-nn`) refer to [`REQUIREMENTS.md`](REQUIREMENTS.md).
Binding decisions live in [`DECISIONS/`](DECISIONS/). What changed in the correction pass and
why is recorded in [`STATUS.md`](STATUS.md).

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

Control frames are capped at **256 KiB** and that cap does not move. The only payload that grows
without bound is the library manifest, and it is **paginated** rather than accommodated by a
bigger cap ([PROTOCOL §8.1](PROTOCOL.md#81-paginated-manifest-synchronisation),
[ADR-013](DECISIONS/ADR-013-paginated-manifest-sync.md)).

### 1.2 Platform baselines

Binding. Recorded in [ADR-011](DECISIONS/ADR-011-platform-baselines.md).

| | Android | iOS |
|---|---|---|
| Minimum | `minSdk = 31` (Android 12) | **iOS 26.0** |
| Compile against | `compileSdk = 36` | latest installed iOS SDK (Xcode 27.0 beta, confirmed — ADR-011 Amendment A2) |
| Target | `targetSdk = 36` | — |
| Language / toolchain | Kotlin, JVM target 21 (Gradle toolchain pinned; see §10.1) | Swift 6, strict concurrency |
| Reference device | OnePlus Nord 5 | iPhone 17 Pro Max |

There is exactly one device of each kind and no store release, so supporting older OS versions
buys nothing and costs availability branches in the highest-risk code. API 31 is also the level
that introduces `AudioManager.setCommunicationDevice()`, the clean way to route the helmet unit
— below it we would be back to the deprecated `startBluetoothSco()` dance in precisely the
subsystem we least want workarounds in.

**Tooling state, verified 26 Aug 2026 on this machine:**

| Tool | State | Path |
|---|---|---|
| JDK 21 | ✅ installed (Homebrew `openjdk@21`, 21.0.12.1) | `/opt/homebrew/opt/openjdk@21` |
| Android SDK — platform 36, build-tools 36.1.0, platform-tools 37.0.1 | ✅ installed, licences accepted | `/opt/homebrew/share/android-commandlinetools` |
| Gradle | ❌ **not installed globally, deliberately** | the project uses its own committed wrapper |
| Swift / macOS SDK | ✅ Swift 6.3.2, macOS 26.5 SDK (Command Line Tools) | — |
| **Xcode / iOS SDK** | ✅ Xcode 27.0 beta, iOS SDK 27.0 | `/Applications/Xcode-beta.app` |

JDK 25 (Temurin) is also present and is the default `java` on `PATH`; JDK 21 is keg-only and not
symlinked into the system JVM directory, so it is reached by explicit path. That is intentional —
it pins the build's JDK without changing the machine's default.

Xcode is installed and the iOS SDK is confirmed at 27.0 — newer than the 26.0 baseline, which
stays as the deployment target unchanged (ADR-011 Amendment A2). `swift test` for
`RideLinkCore` now runs.

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

Layers 2 and 3 are *packages inside one module per platform* rather than a module each (§9);
the boundary is enforced by the module being a plain JVM / plain Swift library, so the platform
SDK is not even on its compile classpath.

Layer 1 is the only place allowed to know that Bluetooth exists.

---

## 3. Session state machine

The DOCX (§15) and this build's instructions name states differently. We implement the superset;
the mapping is recorded in [ADR-008](DECISIONS/ADR-008-requirement-conflict-resolutions.md).

| Implemented state | DOCX §15 name | Meaning |
|---|---|---|
| `IDLE` | IDLE | No session. Nothing on the network. |
| `DISCOVERING` | *(implicit)* | Browsing/advertising mDNS. No peer chosen. |
| `PAIRING` | PAIRING | Peer chosen; trust being established. Spans the TLS handshake, the SPKI pin check, duplicate-connection resolution and — for an unknown peer — the whole of the §4.5 SAS exchange. A TLS socket existing does **not** leave this state ([ADR-019](DECISIONS/ADR-019-connected-means-authenticated.md)). |
| `CONNECTING` | CONNECTING | The peer is **authenticated**: the pin matched, or both users confirmed the six digits and the record was written. Capability exchange and the opening clock-sync burst run here. |
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
6. Losing a duplicate connection (§4.2) is **not** a state transition and **not** a fault. The rejected socket never had a session to leave.
7. **`PAIRING -> CONNECTING` is the trust gate, and only two things open it:** the stored SPKI pin matched, or both users confirmed the six digits and the trusted-peer record was written. A completed TLS handshake is neither ([ADR-019](DECISIONS/ADR-019-connected-means-authenticated.md)). The control plane says so with two distinct events — `PeerTrusted` and `PairingSucceeded` — and `Connected` means *"the trust gate has already passed"*, never *"TLS came up"*.
8. A link lost while in `PAIRING` is a pairing that did not happen: it exits via `PairingRejectedOrTimeout` to `DISCOVERING`, and the peer is **not** re-dialled automatically, so a refusal cannot be re-offered by the next mDNS `Found`.

The `(ControlEvent, status) -> SessionEvent` table that encodes rules 6–8 is `SessionGate` on both
platforms, pinned by [`protocol/vectors/session-gate/`](../protocol/vectors/session-gate/) — the
complete cross-product, so a platform that disagrees fails a laptop unit test.

---

## 4. Discovery, connection and pairing

### 4.1 Discovery

FR-001, [ADR-002](DECISIONS/ADR-002-lan-mdns-discovery.md). DNS-SD service type
`_ridelink._tcp`, local domain. Android `NsdManager`; iOS `NWListener` + `NWBrowser`. Both apps
advertise *and* browse — neither is a designated server, so either person can start the session
(Design Principle: "Phones are peers").

**TXT records carry no stable identity whatsoever.** Anyone on the Wi-Fi can read them, so
anything durable in a TXT record is a passive tracking handle (NFR-05, §11).

| Key | Example | Notes |
|---|---|---|
| `v` | `1` | protocol major version |
| `dh` | `9c40b7f1…` (32 hex) | **ephemeral discovery handle** — 16 CSPRNG bytes. Not derived from `peer_id`, not derived from the identity key, never persisted |
| `plat` | `android` \| `ios` | optional, UI labelling only |

That is the complete set. Explicitly **absent**: any long-term `peer_id`, any certificate or
SPKI fingerprint or prefix of one, any pairing token, any SAS material, any library size or track
count, and any user- or device-chosen display name.

`dh` rotation rule: regenerated whenever advertising starts, at least every 15 minutes while
advertising continues, and always after a session ends. It exists only so that two
advertisements observed a second apart can be recognised as the same *advertisement*, which mDNS
needs; it is not an identity.

**Consequence, accepted deliberately:** a discovered peer cannot be labelled "known" before a
connection exists. Known-peer recognition happens *after* the TLS handshake, by SPKI pin (§4.3).
The UX that a stable fingerprint used to buy is recovered without the privacy cost: when exactly
one trusted peer exists and auto-connect is enabled, tapping a discovered peer — or simply
finding one — attempts a silent trusted connect, which either succeeds with no prompt or falls
back to the pairing flow. The user sees the same two outcomes as before; the network sees nothing
durable.

What remains observable and cannot be hidden: the service type itself (so, that RideLink is
running), the IP address, and the port. mDNS requires a service type; this is accepted and
documented rather than pretended away.

### 4.2 Duplicate and simultaneous connections

Both peers advertise and browse, so both can call `connect()` at the same moment — and on every
reconnect they do, because both detect the loss together. The resolution rule, the
`conn_tiebreak` field it uses, and the reason it is deliberately *not* `peer_id` are specified in
[PROTOCOL §4.2](PROTOCOL.md#42-duplicate-and-simultaneous-connections) and
[ADR-015](DECISIONS/ADR-015-duplicate-connection-resolution.md).

Three architectural consequences worth stating here:

- `SessionCoordinator` owns at most one live control connection. Candidate connections that have completed TLS but not yet resolved §4.2 are held by the transport layer, not by the coordinator, so a losing socket can never touch session state.
- Deduplication completes **before** `PAIRING` proceeds, so exactly one SAS code is ever shown. A simultaneous first meeting cannot put two different codes on the two screens.
- Connection ownership is independent of leadership (§5). `conn_tiebreak` (§4.2) and `peer_id` (§5) are uncorrelated by construction, so which side's outbound connection survives says nothing about which side leads. **No implementation may assume `initiator == leader`, and none may assume `acceptor == leader`** — either assumption happens to hold by coincidence in some lab runs and fails on a ride when the coincidence breaks ([ADR-015 Amendment A2](DECISIONS/ADR-015-duplicate-connection-resolution.md#amendment-a2--26-august-2026--correction-connection-ownership-does-not-determine-leadership)).

### 4.3 Identity and pairing

Durable peer identity is **`identity_spki_sha256`** — SHA-256 of the DER-encoded
SubjectPublicKeyInfo of the device's long-term identity key, formatted `"sha256:"` ‖ 64 lowercase
hex characters ([ADR-012](DECISIONS/ADR-012-spki-peer-identity.md)). It is the only pinned value
anywhere in the system. The certificate is a wrapper; the key is the identity.

**Pairing — first meeting only:**

1. Both on the same LAN/hotspot; both in `DISCOVERING`.
2. One user taps the discovered peer → `PAIRING`. **The session stays in `PAIRING` for everything that follows.**
3. TLS 1.3 handshake with self-signed certificates, both sides. Nothing is trusted yet — the socket is a transport, not an authenticated session.
4. Duplicate connections resolved (§4.2). Everything below happens on the survivor only.
5. Each side derives a **six-digit verification code** from the TLS exporter secret bound to both certificates, by the exact algorithm in [PROTOCOL §4.5.1](PROTOCOL.md#451-the-six-digit-sas--exact-construction) — so a man-in-the-middle produces different codes on the two screens.
6. Both users see the code; both confirm it matches. This is the "explicit local approval" of §11, and it is a real channel-binding check, not decoration.
7. On confirmation each side stores a **trusted peer record**: `{ peer_id, identity_spki_sha256, display_name, paired_at, last_seen_at }`, in Keystore-backed / Keychain-backed storage.
8. **Only now** does the session leave `PAIRING`, on the connection that is already open — no second handshake, or the code the users compared would no longer bind the session in use ([ADR-019](DECISIONS/ADR-019-connected-means-authenticated.md) §6).

If pairing ends without a pin — either user refuses, a `PAIR_*` frame contradicts the certificate,
or the exporter is unavailable — nothing is stored, both screens drop the code, the connection is
closed **deliberately** so the reconnect ladder cannot re-offer it, and the session returns to
`DISCOVERING`.

**Subsequent connections** are silent: TLS + SPKI pin check, no code, no prompt.

| What changed on the peer | Result |
|---|---|
| Certificate re-issued around the **same** identity key | Still trusted, silent connect. The pin is the key, not the certificate bytes |
| Identity key changed (different SPKI) | `ERROR/pin_mismatch`, refused, surfaced as a security warning, **never** auto-re-paired |
| Certificate expired / not yet valid | `ERROR/certificate_invalid` — distinct, so a device clock problem is not reported as an attack |

Recovering from a real key change requires the user to explicitly forget the peer and pair again,
with a fresh SAS confirmation. There is no key-rotation protocol in V1
([PROTOCOL §4.5.3](PROTOCOL.md#453-certificate-re-issuance-versus-key-rotation)).

### 4.4 Fallback when mDNS is blocked

Deferred to Phase 1b. Some hotspots and enterprise APs block multicast, so a manual path offers
`host:port` plus the six-digit code, presentable as a QR code. The protocol is identical; only
peer *location* changes.

---

## 5. Leadership and command ordering

No user-visible master (Design Principle: "Phones are peers"; FR-014 requires *deterministic*
conflict resolution). Internally one peer is **leader**, purely to serialise commands and own
the session clock.

- **Election:** the peer with the lexicographically smaller `peer_id` leads. `peer_id` is fixed at pairing, so this is stable, needs no negotiation, and cannot flap.
- **Independent of the transport.** Leadership has nothing to do with which side called `connect()` or which socket survived §4.2. [ADR-010](DECISIONS/ADR-010-internal-leader-election.md) and [ADR-015](DECISIONS/ADR-015-duplicate-connection-resolution.md) use deliberately different keys so the two concerns cannot be conflated by accident.
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
| Background / lock screen | One foreground service, declared `mediaPlayback` and (when the intercom is part of the ride) `microphone` — see §6.4 |
| Voice | WebRTC `PeerConnection` with the built-in `AudioDeviceModule` (owns its own `AudioRecord`/`AudioTrack`, HW AEC/NS/AGC where present). Implemented in `network/voice/WebRtcVoiceEngine`; the session and route half is `audio/route/AndroidVoiceAudioSession`, and the two are deliberately separate calls to tear down (ADR-020 §6) |
| Route + focus | `AudioManager` — `setCommunicationDevice()` (API 31+, our `minSdk`) for the helmet unit, `AudioFocusRequest` with `WILL_PAUSE_WHEN_DUCKED = false` so *we* control ducking, `AudioDeviceCallback` for connect/disconnect |
| Ducking | `ExoPlayer.volume` ramped over ~150–250 ms, never stepped (FR-016) |

### 6.2 iOS

| Concern | Mechanism |
|---|---|
| Music playback | `AVAudioEngine` + `AVAudioPlayerNode` (chosen over `AVAudioPlayer` for sample-accurate scheduling — see §7) |
| Background / lock screen | `UIBackgroundModes: audio`; `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter` |
| Voice | WebRTC `RTCPeerConnection` over the duplex session configuration below. Implemented in `RideLinkPlatform/Voice/WebRtcVoiceEngine`; the session and route half is `RideLinkPlatform/Route/IosVoiceAudioSession`, and the two are deliberately separate calls to tear down (ADR-020 §6) |
| Route + interruption | `AVAudioSession.routeChangeNotification`, `interruptionNotification`, `mediaServicesWereResetNotification` — all three handled explicitly, not just the first (FR-019, NFR-02) |
| Ducking | `AVAudioMixerNode.outputVolume` ramp, same 150–250 ms envelope |

**Two audio-session configurations, switched only on an explicit user action.** Not one
configuration with options that happen to cover both cases — that was the mistaken model.

| Ride phase | Category | Mode | Options | Resulting route |
|---|---|---|---|---|
| Music only (intercom off, or Mode E) | `.playback` | `.default` | — | media-quality stereo output |
| Intercom active | `.playAndRecord` | `.voiceChat` | `[.allowBluetoothHFP, .allowBluetoothA2DP, .duckOthers]` | duplex, reduced-quality output **and** input on the same device |

`.allowBluetoothHFP` is the current spelling; the old `.allowBluetooth` is deprecated. With an
iOS 26.0 deployment target (§1.2) no availability branch is needed, which is one reason the
baseline was set there.

`.allowBluetoothA2DP` is listed in the duplex configuration but **must not be read as "media
output plus duplex input at the same time."** With a live input on a Bluetooth device, the output
follows the input onto the duplex profile. That is the whole shape of the product's biggest risk
and it is modelled explicitly on the wire as `profile_coupling: "input_forces_output"`
([PROTOCOL §4.3.1](PROTOCOL.md#431-audio-capability-vocabulary)).

Switching between the two configurations is an audible route change costing roughly 0.5–2 s. It
therefore happens **only** when the user turns the intercom on or off, is announced in the UI,
and is reported to the peer as `AUDIO_STATE { route_state: "transitioning" }`. It never happens
per utterance.

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

**Critical clarification.** `mic_always_open: false` in Modes B, C and E refers to *whether
speech is transmitted*, **not** to whether the capture device is repeatedly opened and closed.
The capture device is opened once, while the app is visible, and stays open for the whole ride
segment (§6.4). VOX and PTT gate the outbound audio, not the hardware.

Two independent reasons force this, and they agree:

1. **Audio quality.** Opening and closing a Bluetooth microphone per utterance thrashes the endpoint between its media and duplex profiles — the single worst thing this product can do to music, and the exact failure Phase 0 was built to measure.
2. **Platform rules.** On Android, first-time microphone capture cannot legally begin from the background (§6.4). A PTT press with the screen locked must not be the moment the mic is first opened.

**Open input:** the mode Phase 0 actually validated. Until recorded in
[`PHASE0_RESULTS.md`](PHASE0_RESULTS.md), Phase 6 defaults to **Mode C (PTT)** as the safest
assumption, because it is the only mode that cannot be broken by a duplex-profile switch
mid-utterance.

### 6.4 Android ride lifecycle and the background-microphone rule

Modern Android forbids starting a microphone foreground service from the background. The design
does not work around that; it is built around it. Ride Mode is a foreground-initiated action, full
stop.

**Required start sequence — the only legal one:**

```
1  RideLink is visibly open (a resumed Activity).                       ← precondition
2  Permissions granted:  RECORD_AUDIO (if intercom),  POST_NOTIFICATIONS,
   BLUETOOTH_CONNECT.  Denials are handled, not assumed away.
3  Readiness gate checks: peer session CONNECTED, audio endpoint present,
   whether the intercom is part of THIS ride.
4  User taps START RIDE.
5  While still foreground-visible, start the ride foreground service with the
   service types this ride needs:
       mediaPlayback                        (always)
       mediaPlayback | microphone           (intercom included)
6  Still foreground-visible: acquire audio focus, select the communication
   device, and OPEN the capture path. This is the moment mic capture becomes
   legal, and it is the only moment it can.
7  User may now lock the screen or switch apps.
8  The foreground service maintains the session, playback and capture for the
   rest of the ride.
```

Step 6 is why the capture device stays open for the whole segment (§6.3): there is no second
legal opportunity to open it once the app is no longer visible.

**Manifest surface**

| Declaration | Why |
|---|---|
| `RECORD_AUDIO` (runtime) | intercom capture |
| `INTERNET` | local TCP/TLS sockets and WebRTC. Cannot be scoped to the LAN; §11 records that no code path contacts a non-link-local address |
| `POST_NOTIFICATIONS` (runtime, API 33+) | the foreground-service notification, which is also the lock-screen control surface |
| `BLUETOOTH_CONNECT` (runtime, API 31+) | enumerate and name the helmet unit for `setCommunicationDevice()` and for the readiness UI |
| `FOREGROUND_SERVICE` | normal permission |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | required for the `mediaPlayback` service type |
| `FOREGROUND_SERVICE_MICROPHONE` | required for the `microphone` service type |
| `<service android:foregroundServiceType="mediaPlayback|microphone">` | one service, both types declared; the *runtime* start specifies only the types this ride actually uses |

**Verify in the Phase 1 scaffolding spike, do not assume:** whether `NsdManager` mDNS discovery
requires `NEARBY_WIFI_DEVICES` on API 33+. Our reading is that it does not — that permission
governs Wi-Fi scanning, Aware and Direct, not DNS-SD — but this is a permission-manifest
decision that must be settled by running discovery on the real device, not by reading.

**Failure modes, all of which must be handled explicitly**

| Failure | Behaviour |
|---|---|
| `RECORD_AUDIO` denied | Ride starts **music-only**. Service starts with `mediaPlayback` only; the mic is never opened; status shows amber "intercom unavailable — microphone permission". FR-025 satisfied, and test A-07 asserts music still works |
| User wants the intercom later, app already backgrounded | Not possible, and the UI says so plainly: "bring RideLink to the front to turn on the intercom". No attempt to start a microphone FGS from the background |
| `POST_NOTIFICATIONS` denied | The service still runs and the ride still works, but the user loses the lock-screen control surface. The readiness gate asks for it once and explains why |
| `ForegroundServiceStartNotAllowedException` | Caught, logged as a state-machine event with reason, surfaced as "could not start the ride — open RideLink and try again". **Never** retried silently from the background |
| Task swiped from Recents | `onTaskRemoved` ends the session cleanly through `ENDING`: audio released, sockets closed, no orphaned service |
| Process death | Service is `START_NOT_STICKY`. Nothing restarts a microphone FGS in the background. On next launch the app restores persisted session state and the user starts the ride again explicitly |
| Doze / battery optimisation | Handled by *being* a compliant foreground service with a media session. The user may be advised once, in the UI, to exempt RideLink for long rides. No programmatic bypass of platform background rules, ever |

The notification is ongoing and non-dismissible while the service runs, and shows session state,
intercom on/off, and actions for mute, play/pause and end-ride.

### 6.5 The effective-audio-state model

Both peers need to know what the *other* peer's audio is actually doing, because "the pillion
can hear you but her music went narrowband" is a diagnosis, not a guess. Two messages carry it
([ADR-016](DECISIONS/ADR-016-effective-audio-capability-model.md)):

| Message | Content | Frequency |
|---|---|---|
| `CAPABILITIES.audio` | declared and static: endpoint class, supported profiles, **`profile_coupling`**, codecs, rate-control support, confidence | once per session |
| `AUDIO_STATE` | effective and runtime: mic open, effective input/output profile and sample rate, derived media quality, route transition, intercom mode | at `CONNECTED`, at ride start, and on every change |

Wire values are platform-neutral by rule — no `A2DP`, `HFP`, `AVAudioSession` or `AudioManager`
string ever appears on the wire. Each platform's route layer maps its own profile names to the
shared vocabulary in exactly one place, which is also the one place Phase 0's measured results
land: they flip `confidence` from `assumed` to `measured` and fix the profile mapping for the
real hardware.

The old model listed an output route and an input route as independent fields. That was wrong for
the ordinary Bluetooth case and wrong in exactly the place the product is most fragile. The
correction is a single explicit field, `profile_coupling`, plus effective state rather than
per-direction wishes.

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

The ladder is **suspended** while either peer reports `AUDIO_STATE.route_state:
"transitioning"` (§6.5). A route change stalls the render path for a moment; correcting for that
would chase an artefact and could trip the sync-failure counter for no reason.

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

**Manifests are paginated, not sent as one message.** A 5 000-track manifest is on the order of
a megabyte of JSON and cannot fit the 256 KiB control-frame cap — and the cap is right, so the
manifest changes shape instead. The wire form is a bounded `MANIFEST_BEGIN` → *n* ×
`MANIFEST_PAGE` → `MANIFEST_END` sequence, sized by encoded bytes rather than entry count, with
a deterministic digest at the end
([PROTOCOL §8.1](PROTOCOL.md#81-paginated-manifest-synchronisation),
[ADR-013](DECISIONS/ADR-013-paginated-manifest-sync.md)).

Two properties the domain layer must preserve:

- **Nothing partial is ever promoted.** Pages accumulate in staging; the live catalogue and its `manifest_revision` change only when `MANIFEST_END` validates page count, entry counts and digest. An interrupted synchronisation leaves the previous manifest untouched and restarts from the beginning. This is the same rule as file transfer's atomic promote (§8.3), applied to metadata.
- **Deltas obey the same limits.** `manifest_revision` still drives incremental sync, and a delta is carried by the same paginated framing, so a large library edit produces several bounded frames rather than one oversized one.

Nothing is transferred automatically — transfer is on demand or explicit (FR-011, §9.4).

Manifest paging lives in `core`'s `manifest` package: page assembly, the digest, and the
receiver's staging state machine are all pure functions over lists, and are therefore driven
entirely by shared vectors (`manifest-paging/`, `manifest-paging-errors/`).

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
- The bulk connection is pinned to the same `identity_spki_sha256` as the control connection.
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
(`AVAudioFile` decodes FLAC) — cheap to allow, so it is allowed, but not a V1 acceptance
criterion.

---

## 9. Module structure

Clean **boundaries** matter; a large *count* of build modules does not. For a two-device personal
project, ~20 Gradle modules would mean convention plugins, a version-catalogue-per-module
discipline and a dependency-wiring surface far larger than the code it separates. The boundaries
below are the same ones the layering of §2 describes; only the number of build units is smaller
([ADR-014](DECISIONS/ADR-014-initial-module-structure-and-di.md)).

### 9.1 Android — five Gradle modules

```
android/
├── settings.gradle.kts
├── gradle/libs.versions.toml         # single version catalogue (all pins)
├── gradlew, gradle/wrapper/          # committed — reproducible builds
│
├── app/            [Android app]     Compose UI, navigation, SessionCoordinator,
│                                     manual DI graph, foreground service, notification
│   └── src/main/kotlin/…/{ui/, session/, service/, di/}
│
├── core/           [pure JVM]        THE DOMAIN. No Android SDK on its classpath.
│   └── …/{model/, protocol/, sessionfsm/, sync/, security/, voice/,
│           audiopolicy/, queue/, manifest/, hashing/, logging/}
│
├── network/        [Android lib]     …/{discovery/, control/, security/, transfer/, voice/}
├── audio/          [Android lib]     …/{player/, route/}
└── data/           [Android lib]     …/{database/, library/, settings/, trustedpeers/}
```

**How the hard rule of §2 is enforced:** `core` is a plain `kotlin("jvm")` library. The Android
SDK is not on its compile classpath, so `import android.*` does not compile — a stronger and
cheaper guarantee than a lint rule. `core` is also where every shared test vector runs, on the
JVM, in seconds, with no emulator.

**Dependency direction**

```
app  ──►  network │ audio │ data  ──►  core
app  ──►  core
```

- `core` depends on nothing in the project.
- `network`, `audio` and `data` never depend on each other. Anything they need in common belongs in `core`.
- `app` is the only module that may see everything, which is exactly what a composition root and an orchestrator need.
- Feature UI does not own domain state. `SessionCoordinator` lives in `app/session/`, is the single owner of session state (§3 rule 4), and holds the *pure* FSM from `core`. View models observe it; none of them hold a connection.

`app` is therefore the module to watch: if `session/` grows uncomfortable inside it, extracting
`session` to a sixth module is a *move*, not a redesign, precisely because the FSM it wraps is
already pure and already in `core`. Same for splitting `network` if the voice wrapper becomes
large. Packages are promoted to modules when there is a reason, not in advance.

No `build-logic/` convention plugins in the initial build: five build files do not contain enough
duplication to justify them. `gradle/libs.versions.toml` still pins every version.

### 9.2 iOS — two local packages plus the app target

```
ios/
├── RideLink.xcodeproj                # committed: project.pbxproj + shared schemes
├── RideLink/                         # @main, navigation, SwiftUI views,
│                                     # SessionCoordinator, DI wiring, Info.plist
│
└── Packages/
    ├── RideLinkCore/                 # THE DOMAIN — one target, mirrors android core/
    │   └── Sources/RideLinkCore/{Model/, Protocol/, SessionFSM/, Sync/, Security/,
    │                              Voice/, AudioPolicy/, Queue/, Manifest/,
    │                              Hashing/, Logging/}
    │
    └── RideLinkPlatform/             # Apple frameworks — one target
        └── Sources/RideLinkPlatform/{Discovery/, Control/, Security/, Transfer/,
                                      Voice/, Player/, Route/, Library/}
```

`RideLinkCore`'s import allowlist is **`Foundation` and `CryptoKit` only.** Forbidden: `UIKit`,
`SwiftUI`, `AVFoundation`, `Network`, `CoreBluetooth`, `MediaPlayer`. Enforcement is mechanical
rather than aspirational: `RideLinkCore` builds and its tests run under `swift test` for
**macOS**, so an accidental iOS-only import fails on a laptop in seconds. That is the exact
mirror of the JVM guarantee on the Android side, and it is the reason `RideLinkFeatures` is not a
separate package — SwiftUI views live in the app target where they can see everything, and
isolating them buys nothing.

Concurrency: `SessionCoordinator`, `ControlChannel` and `TransferManager` are `actor`s — each
owns mutable state touched from network and UI contexts, which is exactly the case actors are
for. `RideLinkCore` types are `Sendable` value types.

### 9.3 The shared seam

`protocol/` at the repo root is the single source of truth for the wire format, consumed by
both platforms' test suites:

```
protocol/
├── README.md
├── schema/          # JSON Schema per message type (added in Phase 1 alongside the codecs)
└── vectors/         # golden encode/decode cases + expected sync/queue/diff outcomes
```

Both Android `core` and `RideLinkCore` run the *same* `vectors/` files. A wire incompatibility
then fails a unit test on a laptop instead of appearing as a mystery on a motorcycle. The same
technique covers the clock estimator, the drift ladder, queue conflicts, manifest paging, the
SAS, SPKI pin checks and connection deduplication —
[PROTOCOL §11](PROTOCOL.md#11-test-vectors) is the full list.

---

## 10. Build tooling and dependencies

Selection rule from the brief: platform API first, then a well-established library, then a
small specialised one. Everything pinned.

### 10.1 Android

| Need | Choice | Justification |
|---|---|---|
| Build | Gradle KTS + version catalogue | reproducible, one place for pins. No convention plugins at five modules (§9.1) |
| SDK levels | `minSdk 31`, `compileSdk 36`, `targetSdk 36` | [ADR-011](DECISIONS/ADR-011-platform-baselines.md) |
| JDK | Gradle toolchain pinned to **JDK 21**; JVM target 21 | The machine's default `java` is Temurin 25, ahead of what AGP supports. JDK 21 is installed at `/opt/homebrew/opt/openjdk@21`. Pin it explicitly — `jvmToolchain(21)` plus `org.gradle.java.installations.paths` in `gradle.properties` — rather than inheriting `JAVA_HOME`, or the first build fails confusingly |
| Language/UI | Kotlin, Jetpack Compose (BOM) | required by brief |
| Async | kotlinx-coroutines, Flow | required by brief |
| JSON | kotlinx-serialization-json | compile-time codegen, no reflection, exact-shape control for the envelope |
| DB + search | Room (+ FTS4) | official; FTS gives FR-008 search without hand-rolled indexing |
| Playback | androidx.media3 (ExoPlayer + MediaSession) | format coverage, precise seek, playback-rate control, `MediaSessionService` solves FR-019 |
| Voice | `io.github.webrtc-sdk:android` **`144.7559.14`** (Chromium M144), BSD-3-Clause | Google publishes no current Maven artifact; this is the maintained community build of *unmodified* upstream WebRTC. **Pinned exactly** (never a range) and supply-chain reviewed: four ABIs, only `org/webrtc` + `org/jni_zero` classes, no permission or service in its manifest, and no telemetry endpoint in the binary. [ADR-020](DECISIONS/ADR-020-webrtc-voice-foundation.md), [evidence](test-results/phase2a-webrtc-spike-20260828.md) |
| DI | **manual constructor injection** | No framework in V1. [ADR-014 §2](DECISIONS/ADR-014-initial-module-structure-and-di.md#2-dependency-injection) |
| Preferences | DataStore + Keystore | Keystore for the device identity keypair; no secret in plaintext prefs |
| Tests | JUnit, kotlin.test, Turbine, MockK, Robolectric, androidx.test, **Conscrypt (test-only)** | Turbine for Flow assertions; Robolectric keeps route-logic tests off-device. Conscrypt is `testImplementation` **only** and never reaches an APK: Android's own TLS stack *is* Conscrypt, and its exporter is reached on device through `android.net.ssl.SSLSockets`, a class that does not exist on a plain JVM — without it, the test that proves two peers derive the same six-digit SAS could not run anywhere but a phone (ADR-018) |
| Static analysis | Android Lint + ktlint + detekt | detekt catches the complexity that Lint does not |

Discovery, TLS, audio routing, hashing: **platform APIs, no dependency.**

### 10.2 iOS

| Need | Choice | Justification |
|---|---|---|
| Build | Xcode project + two local SPM packages | no extra tooling; `swift test` for the pure package |
| Deployment target | **iOS 26.0** | [ADR-011](DECISIONS/ADR-011-platform-baselines.md) |
| Language/UI | Swift 6, SwiftUI, strict concurrency | required by brief |
| Discovery/transport | Network framework | `NWBrowser`/`NWListener`/`NWConnection` — named in the brief; includes TLS |
| Crypto/hash | CryptoKit | SHA-256, HKDF — platform, audited |
| Playback | AVFoundation (`AVAudioEngine`) | sample-accurate `scheduleSegment(at:)` is required by §7.2 |
| Audio session | AVAudioSession | required by brief; two configurations per §6.2 |
| DB + search | **GRDB.swift** | needs SQLite **FTS5** for search parity with Room FTS; Core Data/SwiftData make FTS awkward. Actively maintained, single-purpose. *Alternative considered:* raw SQLite3 C API — no dependency but materially more code |
| Voice | `stasel/WebRTC` **`151.0.0`** exact (Chromium M151), BSD-3-Clause | Maintained SPM distribution of *unmodified* upstream WebRTC. An SPM `binaryTarget`, so its SHA-256 is verified at resolve time — checked independently against the published release. `NSPrivacyTracking: false`, no collected data types, no tracking domains, no upload endpoint. Its XCFramework carries a **macOS** slice, which is what lets `swift test` run real DTLS-SRTP/Opus media on a laptop. [ADR-020](DECISIONS/ADR-020-webrtc-voice-foundation.md), [evidence](test-results/phase2a-webrtc-spike-20260828.md) |
| DI | manual constructor injection | same decision as Android |
| Tests | Swift Testing + XCTest | Swift Testing for the pure package; XCTest for anything needing a host app |
| Static analysis | SwiftLint + SwiftFormat | standard, CI-friendly |

### 10.3 Dependency count

Three third-party dependencies **shipped**: WebRTC (×2 platforms) and GRDB. Everything else is
platform API. No DI framework, no analytics SDK, no ad SDK, no crash reporter, no network client
library (NFR-05, §11). WebRTC is the only large one, and it is the one the brief mandates.

One **test-only** dependency: Conscrypt on the Android side, for the reason in §10.1. It is in no
`implementation` configuration, so it cannot reach an APK — and the count above is about what
ships, which is what NFR-05 is about.

Identity and TLS add **no** dependency on either platform: Android Keystore, the iOS Keychain,
`Network.framework` and JSSE are all platform API, and the DER encoder is ~150 lines of our own
pure code pinned by shared vectors (ADR-017).

---

## 11. Privacy and security posture

Encoded as build- and code-level rules, not just intentions:

1. Android `INTERNET` permission is required for local sockets and cannot be scoped to LAN; there is no code path that contacts a non-link-local address. iOS declares `NSLocalNetworkUsageDescription` and Bonjour services only. Full Android permission list: §6.4.
2. No analytics, advertising, telemetry or crash-reporting SDK. A dependency-allowlist check in CI is a Phase 8 deliverable.
3. Logging goes through `core`'s `logging` package / `RideLinkCore.Logging`, which redact by construction: file paths → basename only, `peer_id` → first 6 chars, `identity_spki_sha256` → first 6 hex, `conn_tiebreak` → first 6 hex, discovery handle → first 6 hex. **Pairing SAS codes, TLS keys, exporter secrets and bulk tokens have no log path at all** — not a redacted one, none.
4. No microphone audio is ever written to disk. WebRTC's recorder hooks are not compiled in. Voice exists only in RAM and only while a session is active.
5. Session keys are TLS 1.3 ephemeral (fresh per connection). Only the long-term identity keypair persists, in Keystore / Keychain. `identity_spki_sha256` is the pinned identity; certificates around that key may be re-issued freely (§4.3).
6. **mDNS TXT records carry nothing durable** (§4.1). No `peer_id`, no certificate or SPKI fingerprint or prefix, no token, no library size, no device name. Known-peer recognition happens after the TLS handshake, never on the wire in the clear.
7. `.gitignore` excludes keystores, `.jks`, `.p12`, `.pfx`, `.mobileprovision`, private keys, local secrets, personal music, imported audio, raw recordings and `.part` files. Only synthetic fixtures under `test-media/synthetic/` are committed.

---

## 12. Known architectural risks

| Risk | Severity | Where it bites | Mitigation |
|---|---|---|---|
| ~~Self-signed X.509 identity generation on iOS~~ **Resolved 27 Aug 2026** | ~~High~~ Low | Phase 1b | Spiked and measured. A ~150-line DER encoder plus `SecKeyCreateSignature` produces a certificate that Apple's own parser, BoringSSL and OpenSSL all accept, and `SecIdentityCreate` turns it into a `Network.framework` TLS identity with no PKCS#12 and no key export. [ADR-017](DECISIONS/ADR-017-identity-key-and-certificate.md), [results](test-results/phase1b-security-spike-20260827.md). Residual: none of it has run against the iOS Keychain on a device |
| ~~TLS keying-material **exporter** availability on both platforms~~ **Resolved 27 Aug 2026** | ~~High~~ Low | Phase 1b | Both platforms expose one from public API (`SSLSockets.exportKeyingMaterial`, API 31 — exactly `minSdk`; `sec_protocol_metadata_create_secret`, iOS 12). Apple and Conscrypt/BoringSSL produce **byte-identical** output for the same TLS 1.3 connection, cross-checked against OpenSSL. [ADR-018](DECISIONS/ADR-018-tls-exporter-channel-binding.md), [results](test-results/phase1b-security-spike-20260827.md). Residual: the Android side was measured on Conscrypt-on-JVM, not on the phone |
| ~~WebRTC dependency is community-published on both platforms~~ **Closed 28 Aug 2026** | ~~Medium~~ Low | Phase 2a | Both distributions pinned **exactly**, licences confirmed BSD-3-Clause, Apple's XCFramework SHA-256 verified independently, both binaries checked for telemetry (none: no upload endpoint, `NSPrivacyTracking: false`), release builds proven, and both isolated behind `network/voice` / `RideLinkPlatform.Voice`. [ADR-020](DECISIONS/ADR-020-webrtc-voice-foundation.md), [evidence](test-results/phase2a-webrtc-spike-20260828.md). **Residual:** Android is on Chromium M144 and Apple on M151 because neither distribution publishes the other's milestone — interop-safe by WebRTC's design, but the two real stacks have never spoken to each other |
| **Voice media has never run on a phone** | **High** | Phase 2a gate | Real WebRTC media *is* proven locally — two real engines, host-only ICE, DTLS-SRTP, Opus, under `swift test` on macOS. What is absent is every device-specific part: `RTCAudioSession`, `AudioManager`, a Bluetooth route, a helmet unit, a screen lock, and the Android media path at all (`PeerConnectionFactory.initialize` needs a `Context`). Closed by the §7 device gate, not by more unit tests |
| `AVAudioEngine` scheduling precision on real hardware | Medium | Phase 5 | Measure, do not assume; loopback test in TEST_PLAN §5 |
| mDNS blocked on hotspots/enterprise APs | Medium | Phase 1 | Manual `host:port` + QR fallback (Phase 1b) |
| Phase 0 results not yet recorded | Medium | Phase 6 | Does not block Phases 1–5; default to Mode C until known. `AUDIO_STATE.confidence` stays `assumed` until they are |
| Bluetooth duplex-profile switch degrades music | **High** | Phase 6 | Product-level: mode policy in §6.3, one-open-capture-device rule in §6.4, and the coupling model in §6.5 all exist for this |
| **Xcode not installed** on the build machine | Medium | Phase 1 | Install before iOS scaffolding, then confirm the iOS SDK version against the ADR-011 baseline (§1.2). The Android side (JDK 21 + SDK 36) is installed and verified |
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
