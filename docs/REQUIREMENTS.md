# RideLink — Product Requirements & Implementation Plan

> **Faithful Markdown transcription of the source of truth.**
>
> | | |
> |---|---|
> | Source document | `docs/RideLink_Requirements_and_Implementation_Plan.docx` |
> | Source SHA-256 | `edd97b1276733123375120326b525cacd682a241b84993b5922ef03da46ca746` |
> | Baseline version | 1.0 |
> | Prepared | 22 August 2026 |
> | Transcribed | 26 August 2026 |
> | Extraction method | `tools/extract_docx.py` (Python standard library only) |
>
> The DOCX remains the authoritative artefact and **must not be modified or deleted**.
> This Markdown file is the convenient day-to-day development reference. Where later
> engineering decisions supersede or refine a statement here, that is recorded in
> `docs/DECISIONS/` and cross-referenced — **it is never edited into this file**.
> If this file and the DOCX ever disagree, the DOCX wins and this file is a transcription bug.

**Subtitle:** Personal-use rider–pillion intercom and synchronized music system — Android rider + iPhone pillion

| Project intent |
|---|
| Build a private, open-source, two-person riding companion that uses each person's phone plus ordinary Bluetooth audio hardware. Core functions: full-duplex rider–pillion communication, synchronized local music, shared controls, peer-to-peer file transfer, and offline-first operation. |

---

## 1. Executive Summary

RideLink is a personal-use, open-source mobile application concept for a motorcycle rider and pillion. The rider uses an Android phone connected to a helmet Bluetooth audio unit; the pillion uses an iPhone connected to standard Bluetooth earbuds or a headset. The phones communicate directly over local Wi-Fi or a peer-to-peer IP path. Bluetooth is used only between each phone and its local audio device.

The application combines two functions that are normally separated: a low-latency voice intercom and a synchronized shared music experience. Either participant can control playback. For local music, each phone should play its own synchronized copy of the track; if a track is missing, the app transfers it over the local peer connection and caches it. This avoids continuously retransmitting music and reduces bandwidth and quality loss.

| Critical feasibility gate |
|---|
| Before full development, Phase 0 must verify real Bluetooth behavior on the selected helmet unit and pillion earbuds. The main risk is that opening a Bluetooth microphone can force a headset from high-quality media mode (A2DP/LE Audio) into a lower-quality bidirectional call profile (HFP/HSP). The project architecture will be finalized only after measuring this behavior on the actual devices. |

---

## 2. Problem Statement and Context

Dedicated motorcycle intercom systems solve rider-to-rider or rider-to-pillion communication but require compatible units in both helmets and can be relatively expensive. Standard Bluetooth earbuds are inexpensive and already owned by many pillion riders, but ordinary phones do not natively provide a reliable cross-platform rider–pillion intercom plus synchronized shared music experience.

- Rider equipment: Android phone, motorcycle helmet, budget helmet Bluetooth audio unit with speakers and microphone.
- Pillion equipment: iPhone 17 Pro Max and ordinary Bluetooth TWS earbuds/headset.
- Typical separation: approximately 1–2 metres; both users are on the same motorcycle.
- Primary environment: moving motorcycle, substantial wind/road noise, frequent screen-off operation, intermittent cellular coverage.
- Personal-use objective: no App Store/Play Store publication requirement for the initial versions.

---

## 3. Goals, Success Criteria and Non-Goals

### 3.1 Product goals

- Enable reliable two-way rider–pillion voice communication using each person's existing Bluetooth audio device.
- Allow both participants to hear the same local music at nearly the same playback position.
- Allow either participant to control play, pause, next, previous, seek and shared queue actions.
- Automatically transfer/copy a missing local track between phones over the local peer connection.
- Remain useful without mobile internet, accounts, cloud servers or subscriptions.
- Continue core audio operation while the phone screen is locked.
- Minimize rider interaction while moving; the design must favour hands-free or pre-ride controls.
- Keep the implementation open source and simple enough for personal maintenance.

### 3.2 Success criteria

| Area | Target / Acceptance Criterion |
|---|---|
| Voice latency | Conversationally usable. Target phone-to-phone application/network latency below ~200 ms where possible; measure end-to-end latency including Bluetooth. |
| Music synchronization | Perceived playback alignment should be tight enough that two listeners sitting together do not hear an obvious echo. Initial target: <100 ms drift; stretch target: <50 ms. |
| Reconnect | Temporary peer disconnect should recover automatically without requiring a full app restart. |
| Background operation | Intercom and music remain functional with the screen locked, subject to platform limitations and permitted background modes. |
| Offline operation | Core intercom and local-library music functions work without internet connectivity. |
| Safety usability | No requirement to manipulate a phone for routine operation once a ride has started. |
| Privacy | No audio, library index, telemetry or personal data sent to a backend by default. |

### 3.3 Explicit non-goals for V1

- Spotify audio capture/rebroadcast.
- YouTube Music audio capture/rebroadcast.
- Apple Music integration.
- More than two participants.
- Long-range rider-to-rider mesh networking.
- Cloud accounts, social features, analytics or advertising.
- Public app-store distribution.
- Navigation/map integration.
- Voice assistant/voice-command system.
- Commercial support for arbitrary Bluetooth hardware.

---

## 4. User Scenarios and Journeys

| ID | Scenario | Expected flow |
|---|---|---|
| UJ-01 | Start a ride | Both users open RideLink → audio devices detected → phones pair/connect → connection health shown → user starts Ride Mode. |
| UJ-02 | Talk while riding | Either person speaks → microphone audio is captured → encoded → sent to peer → played through peer headset/earbuds. Music behavior follows selected ducking/PTT/VOX mode. |
| UJ-03 | Play a shared song | Either user searches shared library → selects track → app verifies the track exists on both phones → transfers if needed → schedules synchronized playback. |
| UJ-04 | Pillion controls music | Pillion selects next track or pauses music on iPhone → command is synchronized to Android → both update playback together. |
| UJ-05 | Missing track | Selected track exists only on Android → iPhone requests transfer → integrity is verified → file cached → synchronized playback begins. |
| UJ-06 | Temporary disconnect | Peer link drops → local playback state is retained → app retries connection → clock/state is re-synchronized → session resumes. |
| UJ-07 | Screen locked | Both users lock phones and put them away → audio session, intercom and playback continue where platform policies permit. |

---

## 5. Functional Requirements

| ID | Capability | Requirement |
|---|---|---|
| FR-001 | Peer pairing | The Android and iOS apps shall discover and establish a trusted two-device session without requiring a cloud account. |
| FR-002 | Peer transport | The application shall use local IP connectivity (local Wi-Fi, hotspot, or platform-supported peer networking) for phone-to-phone communication. Bluetooth shall not be the primary phone-to-phone transport. |
| FR-003 | Full-duplex voice | The application shall support simultaneous two-way voice communication where device/audio routing permits. |
| FR-004 | Voice controls | Each user shall be able to mute/unmute the microphone. The application shall support at least one hands-free mode (continuous voice or VOX) and may support push-to-talk as a fallback. |
| FR-005 | Audio processing | The voice path should use echo cancellation, automatic gain control and noise suppression where available. The app shall not assume these eliminate motorcycle wind noise; real riding tests are mandatory. |
| FR-006 | Music library indexing | The app shall index supported local audio files and extract title, artist, album, duration, artwork, codec/format and a content identifier/hash. |
| FR-007 | Folder ingestion | Users shall be able to designate/import one or more local folders/collections. Subfolders shall be indexed recursively where platform file-access rules allow. |
| FR-008 | Search | Users shall be able to search the local/shared music catalogue by title, artist, album and filename. |
| FR-009 | Shared catalogue | After peer connection, the two apps shall exchange compact library manifests so that both users can see which songs are local, remote-only or already shared. |
| FR-010 | Duplicate detection | Tracks shall be identified using a stable content hash plus metadata, preventing unnecessary transfer of identical files with different names. |
| FR-011 | Peer file transfer | A missing local track shall be transferable directly between phones over the peer connection with integrity validation and resumable transfer if practical. |
| FR-012 | Local cache | Transferred songs shall be cached locally according to user-controlled storage settings. |
| FR-013 | Synchronized playback | When both devices have a track, the app shall schedule playback against synchronized clocks rather than stream the same music continuously from one phone to the other. |
| FR-014 | Shared controls | Either user shall be able to issue play, pause, previous, next and seek commands. Conflict resolution shall be deterministic. |
| FR-015 | Shared queue | Either participant shall be able to add tracks to the shared queue. The queue shall be replicated to both devices. |
| FR-016 | Music ducking | When intercom voice is active, the app shall support reducing music volume instead of abruptly stopping playback where platform audio routing allows. |
| FR-017 | Fallback audio mode | If simultaneous high-quality music and microphone capture is not feasible on selected hardware, the app shall support configurable fallback modes such as push-to-talk, pause-on-talk, or reduced-quality continuous intercom. |
| FR-018 | Ride Mode | The application shall provide a simplified ride screen showing connection status, current track, essential playback controls, microphone state and end-ride action. |
| FR-019 | Background operation | The application shall maintain permitted audio and networking activities while the screen is locked using platform-approved background/audio-session mechanisms. |
| FR-020 | Auto reconnect | The apps shall detect lost peer sessions and attempt controlled reconnection without user intervention. |
| FR-021 | Session resync | After reconnect, the apps shall exchange authoritative playback/queue/session state and restore synchronization. |
| FR-022 | Device state | The app shall expose whether local Bluetooth audio output/input is available and warn before ride start if required audio routes are missing. |
| FR-023 | Diagnostics | A developer diagnostics view shall display current audio route/profile where available, sample rate, peer RTT, jitter, packet loss, clock offset, current music drift and reconnect count. |
| FR-024 | No mandatory backend | Core functionality shall not require an internet backend. |
| FR-025 | Graceful degradation | If the intercom cannot start, local music shall remain usable; if music sync fails, intercom shall remain usable. |

---

## 6. Non-Functional Requirements

| ID | Area | Requirement |
|---|---|---|
| NFR-01 | Latency | Optimize for conversational audio. Use low-latency codecs/settings appropriate for real-time voice and avoid unnecessary buffering. |
| NFR-02 | Reliability | The app should tolerate short Wi-Fi disturbances and Bluetooth reconnections without corrupting session state. |
| NFR-03 | Battery | Avoid constant heavy scanning and unnecessary file retransmission. Measure battery drain during a 2-hour ride simulation. |
| NFR-04 | Storage | Expose cache usage and provide clear/delete cache controls. Do not duplicate tracks unnecessarily. |
| NFR-05 | Privacy | No default telemetry, advertising identifiers, cloud audio processing or remote library synchronization. |
| NFR-06 | Security | Peer sessions shall be authenticated/pair-approved and encrypted. Reject unsolicited peers. |
| NFR-07 | Maintainability | Keep platform audio modules native and isolate protocol/state logic behind stable interfaces. |
| NFR-08 | Observability | Local diagnostic logs shall be exportable for debugging; sensitive audio payloads shall not be logged. |
| NFR-09 | Usability | Critical ride controls shall be large, minimal and operable before motion. The design shall discourage active phone manipulation while riding. |
| NFR-10 | Compatibility | Initial compatibility target is the actual Android rider phone, actual iPhone 17 Pro Max, selected helmet Bluetooth unit and selected pillion earbuds. Broader compatibility is a later goal. |

---

## 7. Proposed Technical Architecture

The system should use a two-layer architecture: Bluetooth for local phone-to-audio-device connectivity, and IP networking for phone-to-phone communication. This avoids trying to make one phone manage two unrelated Bluetooth endpoints.

| Logical architecture |
|---|
| Rider helmet headset ↔ Bluetooth ↔ Android app ↔ local Wi-Fi / peer IP ↔ iOS app ↔ Bluetooth ↔ pillion earbuds. Voice uses a real-time stream. Local music uses replicated files plus synchronized playback commands. |

### 7.1 Android application

- Preferred language: Kotlin.
- Owns Android AudioManager/audio focus/routing integration.
- Maintains rider Bluetooth audio route.
- Hosts/joins peer session.
- Captures local microphone and renders peer voice.
- Indexes local music folders available under Android storage permissions.
- Performs local playback and clock-based synchronization.
- Uses foreground service/background audio mechanisms as required for ride sessions.

### 7.2 iOS application

- Preferred language: Swift.
- Owns AVAudioSession configuration and iOS Bluetooth route handling.
- Maintains pillion TWS/headset route.
- Uses iOS-approved background audio modes for active intercom/playback.
- Indexes imported/local audio available to the app under iOS sandbox/file-picker rules.
- Performs local playback and synchronized control/state handling.

### 7.3 Shared protocol layer

- Peer identity and pairing approval.
- Capability exchange: codec support, audio route state, app version, library size.
- Clock synchronization and RTT estimation.
- Reliable control messages with sequence numbers.
- Shared queue replication.
- Library manifest exchange.
- File transfer metadata and integrity hash.
- Voice session negotiation and health statistics.
- Reconnect and session-state reconciliation.

### 7.4 Suggested transport split

| Traffic | Suggested mechanism | Reason |
|---|---|---|
| Real-time voice | WebRTC audio or equivalent low-latency RTP stack | Proven jitter buffering, codecs, echo/noise processing hooks and cross-platform support. |
| Control/state | Reliable ordered data channel / lightweight socket protocol | Small deterministic messages; easy sequencing and replay protection. |
| Track transfer | Reliable peer data channel or local HTTP/QUIC style transfer | Large payloads need integrity checks, progress and resume capability. |
| Discovery/pairing | Platform local-network discovery / QR/manual pairing fallback | Avoid dependence on cloud identity. |

---

## 8. Audio Routing and Bluetooth Strategy

This is the highest-risk area. A Bluetooth device may expose separate profiles for high-quality media playback and bidirectional call audio. On many devices, enabling microphone input causes the active route to switch into a call-oriented profile with lower bandwidth. The exact behavior varies by headset, phone, operating-system version and vendor implementation.

| Mode | Description | Use |
|---|---|---|
| Mode A – Continuous intercom + music | Voice microphone remains active while synchronized music plays. Music is ducked when speech is detected. | Preferred only if quality and routing remain acceptable. |
| Mode B – VOX | Microphone path activates when speech is detected; music ducks or pauses during speech. | Potential compromise between usability and audio quality. |
| Mode C – Push-to-talk | Music remains in high-quality media mode until a talk action is triggered. | Strong fallback if continuous microphone forces poor music quality. |
| Mode D – Intercom priority | Continuous voice; music quality is allowed to degrade or music is paused. | Fallback for communication-first rides. |
| Mode E – Music only | No intercom microphone session; best possible media quality. | Graceful degradation/testing baseline. |

| Hardware selection rule |
|---|
| Do not purchase a helmet Bluetooth unit solely on advertised Bluetooth version. Confirm real behavior for A2DP/media playback, HFP microphone use, profile switching, speaker thickness/helmet fit, battery endurance and microphone quality. No helmet shell/EPS modification should be required. |

---

## 9. Music Library, Sharing and Synchronization

### 9.1 Supported source strategy

| Priority | Source | V1 decision |
|---|---|---|
| P0 | Local MP3/AAC/M4A (and optionally FLAC where platform decoding supports it) | Required. Primary supported source. |
| P1 | Files imported/shared from either phone | Required where file-access rules permit. |
| P2 | Apple Music via official APIs | Deferred. Evaluate only after V1. |
| P2 | Spotify integration | Deferred. At most official playback/control integration; do not design around capturing/rebroadcasting Spotify audio. |
| P2 | YouTube Music | Deferred; no dependency for V1. |

### 9.2 Track identity

Each track should receive a content hash (for example SHA-256) plus normalized metadata. The hash is authoritative for transfer deduplication. Metadata improves search and display but should not be used alone to determine identity.

### 9.3 Synchronization model

After both phones possess the same track, the host of a playback command sends a future start timestamp and desired position. Each device maps that timestamp to its local synchronized clock and starts local playback. Periodic drift measurement applies small corrections if necessary.

| Control message | Example payload fields |
|---|---|
| PLAY | `track_hash`, `position_ms`, `start_at_session_time`, `sequence` |
| PAUSE | `position_ms`, `effective_at`, `sequence` |
| SEEK | `target_position_ms`, `effective_at`, `sequence` |
| QUEUE_ADD | `track_hash`, `requested_by`, `queue_position`, `sequence` |
| NEXT/PREVIOUS | `effective_at`, `queue_revision`, `sequence` |

### 9.4 Library conflict and ownership rules

- There is no permanent master phone.
- The session has a temporary state leader/elected authority only for conflict resolution and reconnect recovery.
- Latest valid command wins only when sequence/order rules allow it; simultaneous conflicting commands must resolve deterministically.
- A remote-only track cannot begin synchronized playback until required transfer/prebuffer conditions are satisfied.
- User can disable automatic caching or cap cache size.

---

## 10. User Experience Requirements

### 10.1 Pre-ride screen

- Rider phone connected status.
- Pillion phone connected status.
- Helmet Bluetooth output/input status.
- Pillion earbuds output/input status.
- Intercom mode selector.
- Music library/shared track count.
- Battery warning if available through platform/device APIs.
- Single prominent Start Ride action.

### 10.2 Ride Mode

| Element | Behavior |
|---|---|
| Connection status | Simple green/amber/red state for peer and local audio route. |
| Now playing | Track title + artist; no dense browsing interface while moving. |
| Playback | Large play/pause, next, previous controls. |
| Mic | Large mute/unmute indicator/control. |
| Intercom mode | Display only or very simple mode switch; complex settings stay outside Ride Mode. |
| End ride | Stops session cleanly, preserves library and optional queue history. |

### 10.3 Safety-oriented interaction principles

- Configure library, pairing and detailed settings before moving.
- No tiny controls or text-heavy menus in Ride Mode.
- No feature should require repeated phone unlocking during routine riding.
- Prefer automatic reconnect, automatic ducking and passive status indications.
- Voice-command support may be explored later but is not necessary for V1.

---

## 11. Security and Privacy Requirements

- Pairing must require explicit local approval or an out-of-band pairing secret/QR code.
- Peer traffic must be encrypted using modern authenticated encryption provided by the chosen transport stack.
- Do not accept arbitrary nearby peers after initial trust is established.
- Do not upload microphone audio, music catalogue, filenames, hashes or diagnostics to any backend by default.
- Do not log raw voice audio.
- Diagnostic logs should redact or avoid full local file paths where unnecessary.
- File transfers must verify hash before promoting a temporary transfer to the music library/cache.
- Session keys should be ephemeral and rotated per session where practical.
- The project repository must not contain personal music files, signing keys, Apple provisioning material or device secrets.

---

## 12. Phase 0 – Feasibility and Hardware Validation

Phase 0 is mandatory and intentionally small. It should answer whether the desired experience is technically achievable on the actual four-device chain before building the complete application.

| Test | Android + helmet | iPhone + TWS | Pass condition |
|---|---|---|---|
| P0-T1 Media only | Play local music over Bluetooth. | Play local music over Bluetooth. | Stable high-quality output on both devices. |
| P0-T2 Voice only | Capture helmet mic and play received voice. | Capture earbud mic and play received voice. | Two-way conversation usable at rest. |
| P0-T3 Mic + music | Keep local music playing while microphone/session is active. | Same test. | Document whether quality/profile changes; determine usable audio mode. |
| P0-T4 Cross-platform intercom | Android ↔ iPhone local connection. | Android ↔ iPhone local connection. | Conversation remains stable for ≥30 minutes. |
| P0-T5 Screen locked | Lock Android screen. | Lock iPhone screen. | Session continues under platform-approved background behavior. |
| P0-T6 Reconnect | Toggle Wi-Fi/Bluetooth briefly and restore. | Same. | App reconnects or exposes a deterministic recovery path. |
| P0-T7 Noise | Use fan/road-noise simulation, then controlled ride test. | Same. | Speech remains intelligible at intended riding speeds or limitations are clearly understood. |

| Go / no-go decision |
|---|
| GO if cross-platform voice is stable and at least one music+voice operating mode is acceptable. CONDITIONAL GO if music must pause or switch quality during speech but the fallback is acceptable. NO-GO for the chosen hardware if microphone/audio routing is unusable or background operation cannot be made reliable; replace hardware or alter the intercom design before proceeding. |

---

## 13. Detailed Implementation Plan

| Phase | Focus | Work | Exit criterion |
|---|---|---|---|
| Phase 0 | Feasibility harness | Minimal Android/iOS audio test apps; Bluetooth route inspection; local voice stream; lock-screen/background test; measurement report. | Validated audio mode and hardware decision. |
| Phase 1 | Peer session foundation | Pairing, local discovery, secure session establishment, reconnect skeleton, diagnostics. | Two phones establish/recover trusted local sessions. |
| Phase 2 | Intercom MVP | Real-time duplex voice, mute, selected codec, jitter stats, basic audio processing, foreground/background handling. | Stable rider↔pillion voice at rest and during controlled ride. |
| Phase 3 | Local music player | Library import/index/search, metadata/artwork, playback engine, local queue. | Each platform can independently browse/play local music reliably. |
| Phase 4 | Shared library + transfer | Manifest exchange, hashes, remote availability, transfer, verification, cache management. | Either user can access a shared catalogue and obtain missing tracks. |
| Phase 5 | Synchronized playback | Clock sync, scheduled commands, drift measurement/correction, shared queue replication, conflict rules. | Both devices play same local track in perceptual sync. |
| Phase 6 | Intercom + music coexistence | Ducking/VOX/PTT modes, route transition handling, fallback policy. | Selected ride mode provides acceptable communication and music. |
| Phase 7 | Ride Mode + resilience | Simplified UI, automatic reconnect, state resync, long-session battery/thermal tests. | Usable 2+ hour personal ride experience. |
| Phase 8 | Hardening | Security review, diagnostics export, crash recovery, storage cleanup, documentation, repeatable sideload builds. | Stable personal-use release candidate. |

---

## 14. Engineering Work Breakdown

### 14.1 Protocol and session

- Define message schema and versioning.
- Define peer identity/trust model.
- Implement connection-state machine.
- Implement heartbeat, RTT and clock-offset estimator.
- Define authoritative state/reconciliation after reconnect.

### 14.2 Voice

- Choose cross-platform voice transport/codec.
- Implement microphone capture and output routing per platform.
- Measure frame size, buffering and end-to-end latency.
- Integrate platform echo/noise processing where available.
- Add mute, VOX/PTT fallback and audio-mode transitions.

### 14.3 Music

- Create platform-local library database/index.
- Implement metadata scanner and stable hash generation.
- Implement search and queue.
- Implement deterministic local playback controls.
- Implement cache policy and deletion.

### 14.4 Transfer

- Create manifest-diff logic.
- Implement authenticated file request.
- Transfer to temporary location.
- Verify length/hash.
- Promote to cache/library only after validation.
- Support transfer progress and cancellation.

### 14.5 Synchronization

- Implement clock synchronization.
- Schedule future playback events.
- Measure playback position periodically.
- Correct drift without obvious audible jumps where possible.
- Log sync error statistics.

### 14.6 Platform lifecycle

- Android foreground service/background audio handling.
- iOS background audio session and interruption handling.
- Bluetooth connect/disconnect callbacks.
- Audio focus/phone-call interruption handling.
- Screen-lock and app-background tests.

### 14.7 UX

- Pre-ride readiness check.
- Ride Mode.
- Shared library indicators.
- Remote-only/download status.
- Diagnostics screen separate from ride UI.

---

## 15. Session State Model

| State | Meaning | Allowed transition examples |
|---|---|---|
| IDLE | No active peer session. | → DISCOVERING / PAIRING |
| PAIRING | Trust is being established. | → CONNECTING / IDLE |
| CONNECTING | Secure transport is being established. | → READY / RECONNECTING |
| READY | Peer connected; audio devices checked. | → RIDING / RECONNECTING |
| RIDING | Intercom/music session active. | → RECONNECTING / ENDING |
| RECONNECTING | Peer temporarily unavailable; local state retained. | → RIDING / READY / ENDING |
| ENDING | Session teardown and state persistence. | → IDLE |

---

## 16. Core Data Model

| Entity | Key fields |
|---|---|
| Peer | `peer_id`, `display_name`, `trusted_key/fingerprint`, `platform`, `app_version`, `capabilities` |
| Track | `track_hash`, `title`, `artist`, `album`, `duration_ms`, `filename`, `codec`, `bitrate`, `artwork_ref`, `size_bytes` |
| TrackPresence | `track_hash`, `peer_id`, `local_available`, `cached`, `transfer_state` |
| QueueItem | `queue_item_id`, `track_hash`, `added_by`, `order`, `status` |
| PlaybackState | `track_hash`, `position_ms`, `playing`, `sequence`, `effective_session_time` |
| SessionMetrics | `rtt_ms`, `jitter_ms`, `packet_loss`, `clock_offset_ms`, `music_drift_ms`, `reconnect_count` |
| AudioRoute | `output_type`, `input_type`, `sample_rate`, `route_name`, `current_mode/profile_if_exposed` |

---

## 17. Test Strategy and Acceptance Plan

### 17.1 Test layers

- Unit tests: protocol serialization, queue operations, manifest diff, hashing, conflict resolution, clock calculations.
- Integration tests: peer connect/reconnect, file transfer, transfer interruption/resume, state reconciliation.
- Platform tests: Bluetooth route changes, mic activation, audio focus interruptions, screen lock, background/foreground transitions.
- Performance tests: voice latency, packet loss tolerance, file transfer speed, playback drift, battery and thermal behavior.
- Ride tests: stationary helmet test → low-speed closed/controlled environment → normal road ride only after basic stability is established.

### 17.2 V1 acceptance checklist

- Android and iPhone can establish a trusted local session repeatedly.
- Both selected Bluetooth audio devices work with their respective phones.
- Rider and pillion can speak both directions for at least 60 minutes without manual restart.
- At least one acceptable music+intercom operating mode is validated.
- Each app can import/index/search local music.
- A track missing on one phone can be transferred, hash-verified and played.
- Either user can control the shared queue and playback.
- Synchronized music drift remains within the agreed perceptual target for a 30-minute playback test.
- Screen lock does not terminate the intended ride session.
- Peer disconnect/reconnect restores session state.
- No backend/internet is required for core functions.
- No raw audio is written to logs.

---

## 18. Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Bluetooth microphone forces HFP/call-quality audio | High | Phase 0 measurement; support VOX/PTT/pause-on-talk; choose different helmet unit if needed. |
| iOS background restrictions | High | Use approved AVAudioSession/background audio patterns; test early with screen locked. |
| Android vendor audio-routing differences | High | Target actual rider device first; isolate routing logic and instrument diagnostics. |
| Wind noise overwhelms microphone | High | Mic placement, foam/windscreen, DSP, controlled ride tests; treat hardware as part of system. |
| Music playback clocks drift | Medium | Continuous clock estimation and periodic drift correction. |
| Large library transfer consumes storage | Medium | Hash dedupe, cache size limits, transfer-on-demand, clear-cache controls. |
| Local peer network drops | Medium | Reconnect state machine, buffering, session resume. |
| TWS uses only one earbud mic/odd call behavior | Medium | Test chosen TWS; document supported hardware. |
| Phone call interrupts audio session | Medium | Handle interruptions and restore session deliberately. |
| Scope grows into full consumer product | Medium | Keep V1 two-person/personal-use and offline-first. |

---

## 19. Decisions Required Before Coding

| Decision | Current recommendation |
|---|---|
| Helmet Bluetooth unit | Select a budget unit only after confirming speaker fit and return/replacement flexibility; exact model becomes the Phase 0 test target. |
| Pillion earbuds | Use the actual TWS/headset expected for rides; do not assume generic TWS behavior. |
| Voice transport | Start with WebRTC due to cross-platform real-time audio capabilities; replace only if Phase 0 reveals unacceptable overhead. |
| Music formats | MP3 + AAC/M4A first; add FLAC only if storage and platform decoding make sense. |
| Intercom mode | Attempt continuous duplex first; be prepared to make VOX or PTT the practical default. |
| Local-network topology | Prefer a topology usable by both Android and iOS; local hotspot/common Wi-Fi may be more predictable than Android-specific Wi-Fi Direct. |
| Project structure | Native Kotlin + Swift apps with a documented shared protocol. Consider shared core code later. |

---

## 20. Proposed Repository Structure

```
ridelink/
├── android/                 # Kotlin app
├── ios/                     # Swift app
├── protocol/                # Message schemas / protocol documentation
├── docs/
│   ├── requirements/
│   ├── architecture/
│   ├── audio-tests/
│   └── test-results/
├── test-media/              # Only redistributable synthetic/test audio
├── scripts/                 # Build/test/helper scripts
├── SECURITY.md
├── PRIVACY.md
├── CONTRIBUTING.md
└── README.md
```

---

## 21. Milestone Definition

| Milestone | Definition of done |
|---|---|
| M0 – Hardware feasibility | Actual Android + helmet unit + iPhone + TWS complete Phase 0 and a supported audio mode is selected. |
| M1 – Private voice link | Reliable local Android↔iPhone intercom with mute and background behavior. |
| M2 – Local music | Both apps independently index/search/play local libraries. |
| M3 – Shared library | Manifests, remote availability and peer file transfer work. |
| M4 – Synced ride music | Shared queue and synchronized playback meet drift target. |
| M5 – Combined ride session | Intercom + music coexist using chosen continuous/VOX/PTT policy. |
| M6 – Personal release | Long-ride test, reconnect, battery/storage diagnostics, documentation and repeatable sideload build. |

---

## 22. Deferred / Future Enhancements

- Official Apple Music integration if it provides value and both accounts/subscriptions make sense.
- Spotify playlist/control integration without attempting unauthorized audio capture.
- Navigation prompt integration.
- Voice commands for next/pause/mute.
- Three or more riders.
- Dedicated hardware button integration.
- Automatic wind-noise profile tuning.
- LE Audio/Auracast exploration where hardware supports it.
- Optional encrypted ride-history metadata (not audio).
- Public distribution, only if the personal prototype proves broadly useful.

---

## 23. First Development Session Checklist

- Choose/purchase the exact helmet Bluetooth unit and record model/firmware.
- Record exact pillion TWS model and iOS version.
- Create empty repository with `android/`, `ios/`, `protocol/` and `docs/`.
- Create Android audio-route diagnostic screen.
- Create iOS AVAudioSession route diagnostic screen.
- Run local music-only test on both.
- Run microphone-only test on both.
- Run microphone + music test and capture route/quality behavior.
- Build simplest Android↔iPhone local voice path.
- Lock both screens and repeat the voice test.
- Document results and make the Phase 0 GO / CONDITIONAL GO / NO-GO decision.
- Only after the decision, begin the peer-session and intercom MVP implementation.

---

## 24. Open Questions to Resolve During Phase 0

- Which exact helmet Bluetooth unit will be used?
- Which exact TWS/headset will the pillion use?
- When the helmet microphone is enabled, what Bluetooth profile/sample rate does Android expose and what happens to media quality?
- Does the pillion TWS behave differently when its microphone is active?
- Can both apps maintain the required session with screens locked for an extended period?
- Which local-network topology is most stable on the actual Android+iPhone pair: common Wi-Fi, Android hotspot, iPhone hotspot, or another supported local method?
- What real end-to-end voice latency is measured?
- What intercom mode gives the best balance: continuous, VOX or push-to-talk?
- What minimum supported music formats are needed for the user's existing library?
- How much local cache should the iPhone retain by default?

---

## 25. Design Principles

| Principle | Meaning |
|---|---|
| Prove hardware first | Audio routing behavior is a product constraint, not an implementation detail. |
| Local-first | Core ride functionality must not depend on mobile data or a cloud service. |
| Each phone plays its own music | Transfer once, then synchronize local playback instead of continuously restreaming the song. |
| Phones are peers | Either participant can control music; no permanent master device. |
| Voice wins over convenience | When Bluetooth constraints force a tradeoff, predictable communication is more important than preserving maximum music fidelity. |
| Safe interaction | The rider should not need to operate the phone while moving. |
| Build narrowly | Two users, known hardware, personal use. Broader compatibility comes only after the core experience works. |

---

## Transcription notes

1. All 25 sections, all 24 tables and all bullet lists from the DOCX are reproduced above. No requirement text has been added, removed or reworded.
2. Field names that the DOCX rendered as plain prose (for example `track_hash`) are shown in code formatting for readability. This is presentation only.
3. The DOCX was authored before this build's session instructions. Three genuine conflicts between the DOCX and the current session instructions exist (music-sync target, session-state names, repository layout). They are **not** silently reconciled here — see `docs/DECISIONS/ADR-008-requirement-conflict-resolutions.md`.
4. Phase 0 is recorded here as "mandatory" because the DOCX says so. Phase 0 has since been completed by the user; its measured results are still to be recorded in `docs/PHASE0_RESULTS.md`.
