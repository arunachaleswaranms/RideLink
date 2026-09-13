# CLAUDE.md — RideLink

**This file is committed on purpose.** It is the project's development contract and must survive
a fresh clone on any machine. Keep it concise, keep it free of secrets, tokens, credentials and
machine-specific paths, and keep it in sync with the documents it points at.

## What this is

A **personal, local-only** rider–pillion app for exactly two people on one motorcycle:
full-duplex voice intercom + synchronized shared music. Rider on Android (OnePlus Nord 5,
helmet Bluetooth unit); pillion on iPhone 17 Pro Max (Bluetooth TWS).

No cloud, no accounts, no backend, no analytics, no subscription, no app-store release.

## Source of truth

Read these before changing anything. They are authoritative; this file is a summary.

| Question | File |
|---|---|
| What must it do? | `docs/REQUIREMENTS.md` — faithful transcription of the source DOCX. **Do not edit** to resolve a conflict; record the resolution in an ADR |
| How is it built? | `docs/ARCHITECTURE.md` |
| What's on the wire? | `docs/PROTOCOL.md` |
| How do we verify? | `docs/TEST_PLAN.md` |
| State now / exact next task | `docs/STATUS.md` |
| Why this way? | `docs/DECISIONS/` (ADR-001…026) |
| What was actually measured? | `docs/test-results/` — including the Phase 1b security spike |
| What did the hardware do? | `docs/PHASE0_RESULTS.md` (awaiting user input) |

`docs/RideLink_Requirements_and_Implementation_Plan.docx` is **read-only input**. Never modify it.

## Architecture rules

| # | Rule | Never instead |
|---|---|---|
| 1 | **Native Kotlin/Compose + Swift/SwiftUI.** Shared via protocol spec + golden vectors, not shared UI code | No Flutter/RN/Compose-Multiplatform |
| 2 | **Phone-to-phone = IP over shared Wi-Fi or a phone hotspot.** Discovery = mDNS/DNS-SD `_ridelink._tcp` (`NsdManager` / `NWBrowser`+`NWListener`) | No Multipeer Connectivity, no AWDL, no Wi-Fi Direct, no Bluetooth as the phone-to-phone link |
| 3 | **Three separate data planes.** Control = TCP+TLS 1.3 + JSON. Voice = WebRTC/DTLS-SRTP/Opus. Bulk files = a *second* TLS connection | Don't put control traffic on a WebRTC DataChannel; don't let a 40 MB transfer block a `PAUSE` |
| 4 | **Each phone plays its own local copy.** Transfer once, then schedule against a synced clock | Never restream music peer-to-peer during playback |
| 5 | **Monotonic clocks only** for anything timing-related | Never use wall-clock time for scheduling |
| 6 | **`content_hash` = SHA-256 of the whole file** is authoritative identity. `quick_id` is a cheap index-time tier | Never identify tracks by filename or metadata alone |
| 7 | **Internal leader = smaller `peer_id`**, assigns `command_seq`. Serialises commands only | Never expose a "master phone" in the UI; both users get full controls |
| 8 | **One `SessionCoordinator` owns session state** | Never scatter connection state across view models |
| 9 | **The domain layer is pure** — no platform types, no clock reads, no I/O. Android `core` is a `kotlin("jvm")` module; `RideLinkCore` imports only `Foundation`+`CryptoKit` | Don't put drift maths or FSM logic behind an Android/iOS type |
| 10 | **WebRTC is voice-only**, behind `network/voice` / `RideLinkPlatform.Voice` | Don't spread WebRTC types through the app |
| 11 | **Control frames cap at 256 KiB and the cap does not move.** Unbounded payloads get paginated | Never raise the frame cap to fit a manifest |
| 12 | **`identity_spki_sha256` is the only pinned identity.** SHA-256 of the DER SubjectPublicKeyInfo | Never pin a whole-certificate fingerprint; never call an SPKI hash `cert_fingerprint` |
| 13 | **One identity algorithm for both platforms: ECDSA P-256, `ecdsa-with-SHA256`** (ADR-017). Keys live in Android Keystore / the iOS Keychain and are never exported. RideLink encodes its own certificate with a shared DER encoder; the platform does the signing | Never choose the algorithm per platform; never use Android's `KeyGenParameterSpec` auto-issued certificate (it cannot be re-issued around an existing key, which breaks ADR-012) |
| 14 | **There is no plaintext control transport.** The only plaintext `ControlChannel` lives in a *test* source set, so it cannot be linked into an app at all | Never add a "debug-only" plaintext path to a production source set; never make security conditional on a build flag |
| 16 | **Voice media may never start before the trust gate.** `VOICE_*` is absent from the pre-authentication frame allowlist, and that absence *is* its access control (ADR-020). The ADR-010 leader is always the WebRTC offerer — never the TCP initiator. ICE is an empty server list and `VoiceEngineConfig` has no field that could carry a STUN/TURN server. `VoiceEngine.stop()` drops the peer connection; only `release()` closes the capture device, because reopening it renegotiates the Bluetooth profile and Android forbids reopening a microphone from the background | Never add a voice type to the pre-auth allowlist; never infer the offerer from who dialled; never add an ICE server "just for testing"; never let a link blip release capture |
| 17 | **The intercom transmission gate never touches the capture device.** PTT, VOX and mute gate the *outbound WebRTC audio track* (`AudioTrack.setEnabled` / `RTCAudioTrack.isEnabled`); the capture device and platform audio session are opened once, while foreground-visible, and stay open for the whole ride segment. Every decision lives in the pure, mirrored `IntercomTransmission` table (ADR-021), whose action vocabulary has **no** capture case — that absence *is* the enforcement, and `protocol/vectors/intercom/` pins it. `AUDIO_STATE` is absent from the pre-authentication allowlist for the same reason `VOICE_*` is. **An `AUDIO_STATE` `revision` floor belongs to exactly one `revision_epoch`** (ADR-021 Amendment A7): §4.4's `revision` survives a control reconnect on purpose, so the epoch — 32 hex, re-minted by the same statement that restarts the counter and by nothing else — is what tells a receiver that a restarted counter is a *new* sender lifetime rather than a stale one. A superseded epoch is refused and counted | Never open or close capture per utterance; never route a PTT press to `VoiceAudioSession`; never rebuild the `PeerConnection` for a mute; never branch on a mode id; never flip `confidence` off `assumed` without A-12/A-13; never reset the peer `AUDIO_STATE` inbox on an authenticated reconnect, and never let a `revision` be compared across two epochs |
| 15 | **`ControlEvent.Connected` means "the surviving connection passed the RideLink trust gate"** (ADR-019). `PAIRING -> CONNECTING` opens only on `PeerTrusted` (stored pin matched) or `PairingSucceeded` (both users confirmed and the pin was written). The gate table is `SessionGate` on both platforms, pinned by `vectors/session-gate/` | Never read "TLS and HELLO succeeded" as authentication; never let `Connected` imply pairing success; never start a task that presumes an authenticated peer just because a socket exists |
| 18 | **Phase 5 decides nothing in a coordinator.** Ordering is `CommandOrderGate`, deadline mapping is `ScheduledCommand`, correction is `DriftController`, queue algebra is `SharedQueue`, timing is `SessionClock` — all pure, mirrored and pinned by `protocol/vectors/{ordering,drift,queue,session-clock}/`. The **leader alone** assigns `command_seq`; a follower sends the *same message type* with `command_seq: 0` (ADR-024 §3), and an authoritative `command_seq` arriving at the leader is a role violation. Scheduling is session/monotonic time only, never wall-clock. Every audible effect goes through the ONE `MusicCoordinator`; the system media controls enter that same leader-ordered path through its gate | Never let a coordinator decide ordering or correction; never let a follower allocate a `command_seq`; never add a second player, queue, `MediaSession` or RTT tracker; never leave a drift nudge behind — correction always ends at exactly 1.0; **never let one `SyncPlayerPort` method perform two externally visible effects**, and never express a scheduled action as a closure that could hide a second `await` (ADR-024 A4) |
| 19 | **An authenticated inbound frame is permanently bound to the connection that authorised its read, and to that connection's authentication epoch** (ADR-024 Amendment A7). `readLoop` builds an immutable `ReadFrameBinding` the instant `readFrame()` returns, from an immutable `(connection, generation)` record created once at `activateAuthenticatedSession`; `handleFrame` takes that binding, the pre-authentication gate asks *"was **this** frame's connection an authenticated session when it was read"*, and every generation handed downstream is the binding's. `endConnection` cancels neither read loop, and both resume across a scheduling point — so a frame whose dispatch runs after a reconnect must keep its own generation or be refused, never acquire the successor's | Never re-read `authenticationGeneration` (or any live epoch) at dispatch time to label a frame that has already been read; never add a second generation source; never infer a frame's session from what is live when its work happens to run |
| 21 | **A session may enter `IDLE` only when it is terminal, and `TeardownComplete` is the claim that it is** (ADR-026). The event that opens `ENDING -> IDLE` is the door a successor walks through, so it may be emitted only after every effect owned by the ending session has **completed**: capture released and awaited; every continuation the session started cancelled **and joined**; `ControlSessionManager.shutdown()` awaited, not launched. There is **one** teardown owner per platform — `SessionCoordinator.retireSession` over `SessionTeardownOwner` — it captures everything the ending session owns **synchronously**, before its first suspension, and each captured reference *is* the ownership token. A successor joins that job before touching anything shared. **Cancellation is a request; joining is the proof** — `NsdDiscoveryController`'s `awaitClose` handlers and iOS's actor `await`s all run after a cancel. Two corollaries: ARCHITECTURE §3 rule 3 has **two** deliberate ends, not one (`ENDING`, and the user's retry out of `DISCONNECTED`), and `SessionFsm` — never a coordinator — is what says which; and **a relay sink belongs to whoever installed it**, so `shutdown()` resets counters and detaches nothing | Never emit `TeardownComplete` from wherever the teardown happens to finish; never let a fire-and-forget tail outlive the transition to `IDLE`; never let two components emit it; never clear a mutable "current" sink after `IDLE`; never read live coordinator state from retired teardown work; never use a sleep as lifecycle synchronisation; never invent a generation counter where a captured reference already answers "whose?" |
| 20 | **An inbound frame's authority is the `ReadFrameBinding` its read produced, for *every* message family** (ADR-025). No subsystem downstream of `handleFrame` may discard that provenance and rebuild authority from live session state: `MANIFEST_*`/`TRANSFER_*` carry the generation to their sink, `VOICE_*` and `AUDIO_STATE` are refused at their relay when it is no longer live, and the pre-authentication family (`PING`/`PONG`/`PAIR_*`/`BYE`/`ERROR`) — which is exempt from the generation gate by design and therefore bound to nothing — is answered **only for the connection it was read from**. `ReadFrameBinding.generation` says which session authorised *this frame* and never changes; `liveAuthenticatedGeneration` says which session is authenticated *right now* and is null between sessions. **Comparing them is correct; reading the second to label a frame is the defect.** A frame that is live by this rule may still belong to a *dead sender lifetime*, which is a different question again and is rule 17's `revision_epoch` (ADR-021 Amendment A7) — provenance and state lifetime are not the same guard and both are required. Phase 5 is the one deliberate exception to the relay-level refusal, because ADR-024 A6's ledger must *see* a retired frame to attribute it | Never read a live generation, epoch or session id to decide what a frame you already have belongs to; never conflate `authenticationGeneration` with `voice_session_id`; never use `currentAuthGeneration` where "is there a live session at all" is the question; never assume a family that is exempt from one gate is covered by another |

| 22 | **Admission is not permission to act later; a send that failed is not a send; a send that failed is not a control lifetime that ended; and *which* lifetime ended is a question only provenance can answer** (ADR-020 Amendments A5, A6 and A7). A `VOICE_*` frame a live control lifetime admitted can still be queued when that lifetime ends, and `VoiceInputMailbox` drains `TEARDOWN` ahead of everything — so `ControlLinkLost` resets `VoiceNegotiation` to `IDLE`/`voiceSessionId = null` **first**, and the queued frame is then reduced against that reset. The `voice_session_id` guard does not save it: `offerReceived` and `peerWantsVoice` are guarded only *when there is a generation to compare*, so a teardown removes exactly the thing that would have refused them. **The teardown that jumps the queue owns the remote work it jumped** — `offer` discards queued `SignalReceived` at **offer** time, which is the least-wrong instant but is **scoped by arrival order, not by lifetime identity** (STATUS §4 problem 60 — do not repeat A5's claim that it is "exact rather than a race"; it is not, in either direction). And `SendOffer`/`SendAnswer` **consume** `send`'s `Boolean`, because `VoiceNegotiation.start` is idempotent against a live negotiation by design, so a negotiation advanced with nothing on the wire can never be rebuilt — **as does the answerer's intent-to-talk `SendVoiceState`, the one state update no later one carries** (§7.3: it is an answerer's only wire effect and there is no next one). That consumption is **`NegotiationSendFailed`, never `ControlLinkLost`**: `send` suspends, so its `Boolean` can arrive after a successor lifetime is authenticated, and a lifetime boundary injected then discards the successor's queued offer and erases a pending `StopRequested` — the second of which stops `TeardownComplete` ever being emitted (rule 21). `NegotiationSendFailed` carries the generation the lost frame named, acts only on a live negotiation holding that same generation, has a lane of its own above `CRITICAL`, and owns nothing. **A7 then removed the arrival-order scoping entirely**: `VoiceSignalSink.submit` takes the frame's `ReadFrameBinding.generation`, `VoiceInput.SignalReceived` carries it, `ControlEvent.LinkLost` names the generation that ended (captured in `endConnection` before the record is cleared), and `VoiceInputMailbox` **discards** what a retirement finds queued and **refuses** what arrives after it — against a monotonic retired floor *and* `newestAdmittedControlGeneration`, because one authenticated connection at a time means observing B's frame proves A ended. None of it is on the wire and none of it reaches `VoiceNegotiation`: it is a lifetime identity, not a negotiation one, which is why no vector moved. **The teardown itself is never suppressed** — suppressing a boundary a newer lifetime appeared to supersede was implemented and rejected (admission is not application, so it leaves a *dead* lifetime's negotiation standing instead). The mailbox still delivers **every** boundary to the reducer; what one *means* is rule 23's, not this rule's | Never decide *when* a signal arrived instead of *whose lifetime admitted it*; never re-read a live generation to label a frame already read; never let a mailbox-overflow degrade discard a live lifetime's work; never suppress a lifetime boundary; never conflate `controlGeneration`, `voice_session_id` and `freshVoiceSessionId`; never discard local intent, engine callbacks or capture on a control-lifetime boundary; never let `StopRequested` discard peer work (a local End is not a lifetime boundary) and never let a `ControlLinkLost` displace one; never discard `send`'s result for a frame whose loss strands a negotiation; **never let a failed send — or anything else whose result can outlive the lifetime that authorised it — speak as `ControlLinkLost`**; two events that ask the table for the same thing are not the same event. (A mailbox overflow legitimately still speaks as `ControlLinkLost` — an overflow is decided *now*, about the lifetime that is live *now* — but since ADR-020 Amendment A7 it names **no** generation and therefore owns, and discards, **nothing**: no lifetime ended, so every queued signal belongs to one that is still live) |
| 23 | **A live voice negotiation belongs to the authenticated control lifetime that *established* it, and only that lifetime's boundary — or a newer one — may retire it** (ADR-020 Amendment A8, STATUS §4 problem 61). Rule 22 scopes *inputs*; a reduced input is no longer an input. `VoiceNegotiationState.negotiationControlGeneration` names the owner of whatever negotiation state the value holds — a live status, or a held remote offer, which are mutually exclusive by construction — and `controlLinkLost` retires **unless `owner > retired`**. Three things about that rule are load-bearing. **Ownership is established, never inferred**: only the six transitions that actually create negotiation state set it (`start`'s three branches, `offerReceived`'s two, `peerWantsVoice`), always to the generation carried by the input that created it; `answerReceived` and `candidateReceived` *advance* and do not move it, and `start`'s idempotent early-return does not re-own. **The comparison is "older than", not "different from"**: a boundary naming a *newer* lifetime still retires an older owner, because one authenticated connection exists at a time and generations strictly increase, so a newer lifetime having existed proves the older one ended — without that direction a lost predecessor boundary would strand a dead negotiation forever, which is the rejected suppression's failure reintroduced. **A null `retired` retires unconditionally**, because its producers (the mailbox-overflow degrade, a connection that died before authenticating) are safety valves and not lifetime boundaries. `StartRequested` takes its owner from the caller — `ControlEvent.Connected.authGeneration` for §7.8's rebuild, `liveAuthenticatedGeneration` for a tap — and a **null** one (Start pressed between two links) records consent and opens capture but starts **no** negotiation. None of it is on the wire; the vectors moved because the *table* moved | Never infer ownership from what was merely admitted, observed, or newest; never move an owner on a transition that advances rather than establishes; never suppress a lifetime boundary to protect a successor (scope it instead); never let a `StartRequested` invent a generation inside the pure table, and never let one create a negotiation owned by a lifetime that does not exist — **an un-retirable negotiation is a worse failure than the one being fixed**; never let this owner close capture (rule 17) or stand in for `voice_session_id` or `revision_epoch` — capture lifetime, negotiation lifetime and control-authentication lifetime are three things |
| 24 | **A negotiation's authority reaches its wire, and does not cross a lifetime to get there** (ADR-020 Amendment A9, STATUS §4 problems 63 and 64). Rule 23 says which lifetime *owns* a negotiation; this says that ownership is load-bearing in two further places, and production violated both. **A held remote offer may be answered only by the lifetime that delivered it.** §7.3's held `VOICE_OFFER` is negotiation state, and `start`'s answerer branch used to answer whatever it found and set the owner to the **press's** lifetime — so a tap under a successor applied a predecessor's SDP, reused a `voice_session_id` the offerer had already discarded with its own copy of that link, and moved the owner to the successor, after which the predecessor's boundary was *superseded by rule 23* and inert. That is problem 56's wedge re-created by the rule written to prevent it. Now: equal owner answers it; an **older** owner is discarded (`RETIRED_HELD_OFFER`) and the answerer states §7.3's intent-to-talk afresh under the press's lifetime, which is PROTOCOL §7.8's rebuild through the mechanism that already exists; and a **newer** owner means the *press* is stale — **which A9 answered by refusing, and rule 25 replaced**: the refusal was safe and not live, because nothing else would ever have answered that offer. See rule 25 for what happens now. **And every outbound `VOICE_*` action names the lifetime whose connection it may be written on.** `SendOffer`/`SendAnswer`/`SendVoiceState`/`SendCandidate` are `OutboundVoiceAction` and carry `controlGeneration`, set by the transition that produced them (`stop`'s `closed` reads it **before** the reset); `VoiceSignalTransport.send` takes it; the relay refuses on mismatch **or null** and counts `droppedRetiredGenerationOutbound`; and the writer supplier resolves socket **and** generation from the one immutable `AuthenticatedConnection` record, which is `ReadFrameBinding.of`'s reasoning pointing outwards. `VoiceSignalRelay.send` used to resolve the writer at the moment of the **write** — never the moment of the authorisation, because the mailbox consumer, the engine callback, the dispatcher/actor hop, the write lock and the flush all suspend between the two — so a `VOICE_OFFER` authorised by A was written on B's socket and the peer accepted it as current. A refusal is a plain `false`, answered by `NegotiationSendFailed` and **never** `ControlLinkLost`; it is permanent rather than transient, because generations strictly increase. Engine callbacks stay guarded by `voice_session_id` alone and that is sufficient — 128 CSPRNG bits mean a callback can never match a *different* negotiation, and the case it could not answer (a callback matching a live negotiation whose lifetime has ended) is refused at the send | Never derive an outbound frame's authorising lifetime from the state after the transition — it is right for every branch that existed before A9 and wrong for the first one A9 adds; never resolve a writer from a live socket plus a separately-read generation; never let a held offer be adopted by a lifetime that did not deliver it, and never let a stale press consume one; never give an engine callback its own control-lifetime provenance — the negotiation's owner already answers that, and two sources of one fact is how they come to disagree; never let a send refusal speak as a lifetime boundary, and never widen the bound writer to `AUDIO_STATE`, Phase 4 or Phase 5, each of which already carries a generation to a check of its own |

| 25 | **Local consent outlives a control lifetime; local control authority does not — and when the two meet a newer held remote offer, the offer supplies the authority** (ADR-020 Amendment A10, STATUS §4 problem 66). Rule 24 says a held offer may be answered only by the lifetime that delivered it, and got the *safety* answer right in both directions and the *liveness* answer wrong in one. A `StartRequested` authorised by a lifetime **older** than the one owning a held `VOICE_OFFER` used to record `SUPERSEDED_START_LIFETIME`, open capture and start nothing — leaving the offer held **forever**, because the offerer sends one `VOICE_OFFER` per `voice_session_id` (§7.4), §7.8's rebuild is gated on the *published* `localAudioOpen` and already ran before the press was reduced, and a user who has consented does not press Start again. A9's own regression hid it by supplying a second `start(B)` **that production never sends**. A press carries two separable things: **control authority**, which expires with its link and authorises no write, and **user consent**, which is ride-segment state and is exactly why capture survives a link loss at all. So the three comparisons are three branches, never one generic early return: `held == press` answers it; `held < press` is `RETIRED_HELD_OFFER` and states §7.3's intent afresh; `held > press` **answers it under the held offer's own lifetime** — its `voice_session_id`, its generation on every outbound frame, its boundary as the one that retires it, and `negotiationControlGeneration` deliberately **not** moved to the press's. The two orderings are not symmetric: an older held offer has a stale *remote SDP* that nothing local can repair; a newer one has a live peer still holding that id and waiting, with only consent missing. `SUPERSEDED_START_LIFETIME` now covers a residue that is unreachable by construction — proved, not assumed, by `testNegotiationStateAndItsOwningControlLifetimeArePresentTogetherOrNotAtAll` failing on a vector row for it — and is kept fail-closed for `controlLinkLost`'s null-owner reason. No wire change; the vectors moved. Separately (problem 67): iOS's `startIntercom`/`endIntercom`/`setMicrophoneMuted` wrapped their controller call in a bare `Task` — a continuation the session starts that nothing cancels and nothing **joins**, so `.teardownComplete` could be emitted with a press in flight (rule 21); all three are now `launchInSession`, and **the deferral itself is deliberately unchanged**, because `VoiceController.start` is actor-isolated and removing the hop to make the reproduction impossible would replace a proof with an assumption | Never phrase this as "a stale lifetime may act on a newer one" — the stale press contributes consent and nothing else, and the held offer is what authorises the wire; never move the owner to the consenting lifetime; never collapse the three comparisons into one early return; never treat the two orderings as mirror images; never answer an *older* held offer; never let a test supply an event production cannot produce — **the production state machine must make progress from the events it actually receives**; never remove a scheduling hop to make an ordering untestable |

Reasoning: `docs/DECISIONS/ADR-001…026`.

## Platform stack and baselines

**Binding baselines** (ADR-011): Android `minSdk 31`, `compileSdk 36`, `targetSdk 36`, Gradle
toolchain pinned to JDK 21. iOS deployment target **26.0**, Swift 6 strict concurrency.
Confirm the iOS target against the installed Xcode SDK before Phase 1 iOS scaffolding is done.

- **Android:** `AudioManager` focus/route (`setCommunicationDevice`, API 31+), Media3 `MediaSessionService`, one ride foreground service.
- **iOS:** `AVAudioSession` with **two** configurations — `.playback` for music-only, and `.playAndRecord`/`.voiceChat` with `[.allowBluetoothHFP, .allowBluetoothA2DP, .duckOthers]` for the intercom. `.allowBluetooth` is the deprecated spelling. `UIBackgroundModes: audio`. Handle route change **and** interruption **and** media-services-reset.
- **Bluetooth is only phone↔its own audio device.** Never phone↔phone.
- **iOS library = app container only** (document-picker import). `MPMediaLibrary` is unusable (ADR-009). The pillion's catalogue starts empty and fills by peer transfer.
- **Ride Mode starts only from a visible app** (ARCHITECTURE §6.4). The microphone foreground service and the capture device are opened while foreground-visible, then the screen may be locked. Never start mic capture for the first time from the background. Work within platform background rules; never bypass them.
- **Highest product risk:** opening the mic forces most Bluetooth endpoints from media-quality output onto the duplex profile, degrading music. Output is **not** independent of input — that is modelled as `profile_coupling: "input_forces_output"`. Five intercom modes (A–E) are one policy object for exactly this. Default **Mode C (PTT)** until `docs/PHASE0_RESULTS.md` is filled in. The capture device stays open for a whole ride segment; PTT/VOX gate transmission, not the hardware.

## Directory structure

```
android/     Kotlin app — 5 Gradle modules: app, core, network, audio, data   (planned)
ios/         Swift app — thin Xcode target + RideLinkCore, RideLinkPlatform    (planned)
protocol/    Wire schemas + golden test vectors shared by BOTH platforms' tests
docs/        REQUIREMENTS · ARCHITECTURE · PROTOCOL · TEST_PLAN · STATUS · DECISIONS/ · test-results/
tools/       Local helper scripts (no deps, no network)
```

Android `core` (JVM) and `RideLinkCore` (pure Swift) are mirror images: `core.security` and
`RideLinkCore.Security` in particular are line-for-line ports, because a one-byte difference in
either would produce a different `identity_spki_sha256` on one phone than the other.

## Shared protocol vectors — not optional

Both platforms' unit suites run the **same** `protocol/vectors/*.json`. A wire mismatch must fail
a laptop unit test, not surface on a ride.

- Adding or changing a message shape means adding or updating vectors in the same change.
- A vector that passes on one platform only is a release blocker.
- Every protocol bug found on a device gets a vector added **before** the fix.
- `vectors/sas/` and `vectors/identity/` contain fabricated test values only. Never a real key, token, exporter output or pairing code. Most vector sets are **generated**, each deliberately an independent third implementation of what it pins — `identity/`, `session-gate/`, `voice-signal/`, `voice-fsm/`, `intercom/`, `audio-state/`, and Phase 5's `session-clock/`, `ordering/`, `drift/`, `queue/`, `playback-messages/` and `queue-messages/`. Edit the generator, not the JSON.

## Never change protocol or architecture silently

If a change touches the wire format, the security model, the state machine, module boundaries or
a platform baseline:

1. Say so explicitly in the response — do not fold it into an unrelated change.
2. Update `docs/PROTOCOL.md` / `docs/ARCHITECTURE.md` in the same change.
3. Add an ADR, or append a dated `## Amendment An` to the existing one. Never rewrite an accepted ADR in place; a superseded decision gets `Superseded by ADR-nnn`, not deletion.
4. Update the affected vectors and `docs/TEST_PLAN.md`.
5. Update `docs/STATUS.md`.
6. Leave no stale example behind — grep the repo for the old field name, message shape or module name.

Contradictions between documents are bugs. If two documents disagree, stop and resolve it rather
than picking one.

## Privacy rules (non-negotiable)

- No analytics / ads / telemetry / crash-reporter SDK. No backend. No account.
- **Never write microphone audio to disk.** Voice lives in RAM, only while a session is active.
- Logs go through `core.logging` / `RideLinkCore.Logging`, which redact by construction: paths → basename, `peer_id` → 6 chars, `identity_spki_sha256` → 6 hex, `conn_tiebreak` → 6 hex. **Pairing SAS codes, TLS secrets, exporter output and bulk tokens have no log path at all.**
- **mDNS TXT records carry only `{v, dh, plat}`** — `dh` is an ephemeral rotating handle. No `peer_id`, no SPKI or certificate fingerprint or prefix, no token, no library size, no device name. Anyone on the Wi-Fi can read them. Known-peer recognition happens *after* the TLS handshake.
- Never commit keystores, `.jks`, `.p12`, `.pfx`, `.mobileprovision`, private keys, or personal music. `.gitignore` covers these; keep it that way.
- No unnecessary network requests. No STUN/TURN — ICE uses host candidates only.

## Build / test commands

Both apps are scaffolded and these commands run for real. Toolchain: JDK 21, Android SDK 36
(`/opt/homebrew/share/android-commandlinetools`) and Xcode 27 are all installed. There is
deliberately **no global Gradle**: use the project's wrapper.

**On this machine, prefix every Gradle command with
`-Dorg.gradle.java.home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`** (or set
`org.gradle.java.home` in `~/.gradle/gradle.properties`). Without it the daemon runs on the
machine's default Temurin 25, and `detekt` 1.23.8 fails on every module with a bare `25.0.3` for a
message. CI is unaffected — its daemon is JDK 21 — which is why this only bites locally. See
`docs/STATUS.md` §4 problem 17.

```sh
# Android  (from android/)
./gradlew assembleDebug                  # build
./gradlew :core:test                     # JVM unit tests — fast, no device, runs the vectors
./gradlew test                           # all unit tests
./gradlew connectedAndroidTest           # instrumented
./gradlew ktlintCheck detekt lint        # static analysis

# iOS  (from ios/)
swift test --package-path Packages/RideLinkCore          # pure logic, no simulator
xcodebuild -scheme RideLink -destination 'generic/platform=iOS' build
xcodebuild test -scheme RideLink -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
swiftlint && swiftformat --lint .

# Security spike — re-runnable evidence for the TLS exporter and self-signed X.509 decisions
./tools/spikes/phase1b-tls-exporter/run.sh

# Regenerate the identity vectors (an independent third implementation of the DER encodings)
python3 tools/generate_identity_vectors.py

# Regenerate the trust-gate vectors (ADR-019; an independent third transcription of the table)
python3 tools/generate_session_gate_vectors.py

# Regenerate the Phase 2a voice vectors (ADR-020; both independent third implementations)
python3 tools/generate_voice_signal_vectors.py
python3 tools/generate_voice_fsm_vectors.py

# Regenerate the Phase 2b intercom and AUDIO_STATE vectors (ADR-021; likewise independent)
python3 tools/generate_intercom_vectors.py
python3 tools/generate_audio_state_vectors.py  # + ADR-021 A7's revision_epoch

# Regenerate the Phase 5 synchronisation vectors (ADR-024; all six independent third transcriptions,
# plus the two closure audits' gate tables). ADR-025 adds no vector set — connection identity and
# coroutine/Task lifetime are not distributed decisions (the reason ADR-024 A3–A7 add none either).
python3 tools/generate_session_clock_vectors.py
python3 tools/generate_ordering_vectors.py
python3 tools/generate_drift_vectors.py
python3 tools/generate_queue_vectors.py
python3 tools/generate_playback_messages_vectors.py
python3 tools/generate_queue_messages_vectors.py
python3 tools/generate_phase5_gates_vectors.py

# Requirements doc (DOCX is read-only input; never modify it)
python3 tools/extract_docx.py docs/RideLink_Requirements_and_Implementation_Plan.docx
```

## Definition of done (per phase)

1. Inspect existing code before changing it. 2. Update docs when architecture moves.
3. Smallest coherent increment. 4. Android builds. 5. iOS builds (when relevant).
6. Unit tests pass. 7. Shared vectors pass on **both** platforms. 8. Integration tests pass where
possible. 9. Static analysis clean. 10. Fix what it found. 11. **Update `docs/STATUS.md`** —
current phase, what was verified (not what was written), tests pending, known problems, exact
next task. 12. Summarize exactly what changed.

**Never call a phase done because the code looks right.** If a step can't be automated, write the
exact manual procedure and record the measured result in `docs/test-results/`.
For latency/drift, collect numbers — not impressions. Report failures and skipped steps plainly.

## Debugging

Reproduce → instrument → isolate → hypothesise → **change one variable** → reproduce →
add a regression vector. Don't change several subsystems at once. Every session state
transition is logged with a monotonic timestamp, prior state, trigger and reason — that log is
the primary debugging artefact.

## Out of scope for V1

Spotify / YouTube Music / Apple Music, cloud sync, >2 peers, group riding, social, messaging,
store publishing, payments, analytics, backend. Resume-able file transfer and partial-manifest
resume are deferred, but the chunk and page framing keep both possible.

## Current phase

**Phase 5 — synchronized playback. Software closure is CLAIMED as of the thirty-fifth session
(`docs/STATUS.md` §2al) and re-affirmed after an independent review of that pass (§2am); real-device
validation is a separate claim and is NOT made.** The final audit confirmed problem 50 (reachable, and
worse than STATUS recorded), found and fixed problem 56 (an offer that could not be sent still advanced
the table, and `start`'s idempotence then made the reconnect rebuild a no-op — voice wedged for the ride
segment, no race required), closed problem 41 by executing iOS's production scheduled start and
varispeed (which never needed a simulator — `AVAudioUnitVarispeed` is on macOS), and swept the new
second-session lifecycle fifty times without finding anything.

**The thirty-sixth session then reviewed that pass and found three defects in it** (§2am, ADR-020
Amendment A6), all fixed and all reproduced against the pre-fix sources first: **57** — problem 56's fix
made a *failed send* speak for a *control lifetime*, so a send whose `Boolean` arrived late discarded a
**successor's** queued offer (wedging voice exactly as problem 56 had) and erased a pending
`StopRequested`, which stops `TeardownComplete` ever being emitted (rule 21); **58** — the iOS hard-seek
test seeked to 1 500 ms in a 509 ms fixture, so it passed over **zero scheduled frames** in 38 ms, and it
was hiding a production defect that left `playing == true` with no completion callback (and aborted the
process on a negative local seek); **59** — problem 56's `SendVoiceState` exemption left the
**answerer's** half of the same wedge open. **60** was open and is now **fixed**.

**The thirty-seventh session (`docs/STATUS.md` §2an, ADR-020 Amendment A7) closed problem 60 and opened
problem 61 in the same pass.** Both of 60's windows were re-verified from production before anything
was changed, and the second turned out **not to be a race at all**: `ControlSessionManager.promote`
waits on nothing that consumes the predecessor's `LinkLost`, so a successor authenticates and admits
its own `VOICE_OFFER` while that loss is still unconsumed — now a test over two real TLS sessions on
one real manager. Both are closed by *lifetime identity*: the admitting generation travels from
`ReadFrameBinding` to `VoiceSignalSink.submit` to `VoiceInput.SignalReceived`, `ControlEvent.LinkLost`
names the generation that ended, and `VoiceInputMailbox` both discards and refuses against it. **No
wire change and no vector change** — the reducer reads neither field. Then this pass's own 50-run
stress run found the *state* half of the same window (**61**): a boundary applied after a successor's
work has already been **reduced** retires the successor's negotiation. The suppression that would close
it was implemented, mirrored, tested and **rejected** as strictly worse — admission is not application
— so 61 is recorded rather than half-fixed. **The sharpest lesson yet: this pass's own fix needed a
fix, and its own stress run is what found that, not a later audit.**

**The thirty-eighth session (`docs/STATUS.md` §2ao, ADR-020 Amendment A8) closed problem 61, and is
rule 23 above.** It reproduced the defect from unmodified production on both platforms first — the
Android engine trace is the whole finding: `start(…)`, `applyRemote(OFFER)`, `createAnswer`, then
`stop`. The fix gives the pure table an owner: a negotiation names the authenticated control lifetime
that **established** it, and a boundary retires it only when the lifetime that ended is not older than
that owner. **It is not the rejected suppression**, and P61-B is the regression that proves it — a
successor's offer refused by `GENERATION_MISMATCH` leaves the predecessor the owner, so the
predecessor's own boundary still retires it. Two directions are deliberate and easy to get wrong: a
boundary naming a *newer* lifetime still retires an older owner (or a lost boundary would strand a
dead negotiation forever), and a `StartRequested` with no authenticated lifetime opens capture but
starts **no** negotiation (an un-retirable negotiation is worse than the defect being fixed). **No wire
change; the vectors DID move** — the first of ADR-020's eight amendments where the control lifetime is
part of what the pure table decides. This pass was *handed* its defect by the previous one rather than
having to find it, which makes its finding cheap and its **fix** the thing to audit next.

**The thirty-ninth session (`docs/STATUS.md` §2ap, ADR-020 Amendment A9) did exactly that, and is rule
24 above.** It audited A8's own fix and found **two** reachable defects in it, both reproduced from
unmodified production on both platforms before anything was changed. **63** — a held remote offer could
be *adopted* by a lifetime that did not deliver it: a tap under a successor answered a predecessor's
SDP, reused a `voice_session_id` the offerer had already discarded, and moved the owner to the
successor, after which the predecessor's boundary was *superseded by rule 23 itself* and inert. That is
problem 56's wedge re-created by the amendment written to prevent that class. **64** —
`VoiceSignalRelay.send` resolved the authenticated writer at the moment of the **write** rather than of
the authorisation, so `start(controlGeneration = 1)` produced three frames and **all three went out on
generation 2**; the peer accepted a `VOICE_OFFER` as current on a connection that never authorised it.
Fixed by putting the authorising lifetime on the outbound action and making the transport compare
rather than re-read — ADR-024 Amendment A7's rule pointing outwards. Each half was re-proved in
isolation: reverting one makes exactly its own regressions fail. One hypothesis was **withdrawn on
evidence** rather than asserted (a stale send failure applied after a successor's rebuild is
unreachable, because `SEND_FAILURE` outranks `CRITICAL` and the single consumer is parked inside
`perform`) — and the Android draft of that test had passed *vacuously*, which the iOS run caught.
**No wire change; the vectors moved again.** The standing instruction now has two passes behind it:
**audit the newest fix first** — and this pass's fix is now that code.

**The fortieth session (`docs/STATUS.md` §2aq, ADR-020 Amendment A10) audited A9's own fix and found one
more, and it is rule 25 above.** A9 got the *safety* answer right in both directions of "may this press
answer that held offer?" and the **liveness** answer wrong in one: a stale press meeting a newer held
offer was refused, and nothing would ever have answered that offer — the peer sends one per
`voice_session_id`, §7.8's rebuild had already run and found no consent recorded, and a user who has
consented does not press again. **A9's own regression hid it by supplying a second `start(B)` that
production never sends.** Reproduced first, against unmodified iOS production, at the coordinator's real
decisions — `startIntercom` reads the live generation and then defers the controller call, so the press
arrives behind a successor's offer — and the reproduction asserts progress from the last event production
produces, with no second press anywhere. Android cannot reach the ordering today because its press offers
synchronously, and a real-`SessionCoordinator` test now pins that difference rather than leaving it to
luck. A second, separate finding is recorded on its own (problem 67): three bare `Task`s in
`SessionCoordinator` were continuations nothing joined, which rule 21 forbids. **The standing instruction
has three passes behind it now: audit the newest fix first — and audit what a regression *supplies* as
carefully as what it asserts.**

**One defect is confirmed and OPEN: `docs/STATUS.md` §4 problem 69.** The same ordering wedges a
*null-generation* press — Start pressed in the gap between two links — which rule 25 cannot reach,
because there is no held offer to answer and no lifetime the pure table may name (rule 23). Measured,
not argued: `attachVoice` issues no rebuild, and the press lands to `status == idle`,
`localAudioOpen == true`, nothing sent. It is left open because the obvious coordinator fix would also
fire after a send failure degrades to idle with consent still recorded — a voice-layer retry loop §7.8
forbids, since §10's control ladder is the only reconnect loop in the app. **Do not patch it; design
it**, and prefer making consent-plus-live-generation an explicit reducer input so the retry hazard is
decided in the pure table where a vector can pin it.

**TEST_PLAN §5.2's S-01…S-12 remain pending and no alignment figure exists.** Fourteen passes have each
found something already CI-green. §2al's lesson stands — two of the three areas it investigated were
**described inaccurately in STATUS**, in opposite directions, and a third defect lived entirely inside a
row's stated mitigation, so **a problem row is a hypothesis, not a finding**. §2am adds the one to
carry: **a fix is a hypothesis too, and the freshest fix is the least-audited code in the repository.**
All three of its defects were one session old, green in CI, each already carrying a regression — and one
re-created, by a different route, the exact failure it had been written to remove. **Audit the newest
fix first.**

The history below is kept because its lessons stand. Before that closure:

**Closure-audited seven times. A7's findings and the Phase 4 defect
it deliberately left open (`docs/STATUS.md` §4 problem 44) are both now fixed and green on both
platforms — but software closure was withheld at the time: closing problem 44 meant sweeping every other
inbound family, and that sweep found three more confirmed reachable instances of the same class
(ADR-025, `docs/STATUS.md` §2ai), one of them in PROTOCOL §4.5's two-human pairing gate. Ten passes
have each found something in code that was already CI-green. The real-device synchronized-playback
gate is also still open. Phase 6 and Phase 7 have not started.**

**The tenth pass is rule 21 above (ADR-026, `docs/STATUS.md` §2ak), and its lesson is about what an
audit can even see.** `SessionFsm` has carried `ENDING -> IDLE` and `DISCONNECTED -> DISCOVERING`
since Phase 1a with **no production emitter on either platform**, so an ended session or a spent
reconnect budget meant force-quitting the app. Fixing that was two-thirds ordering work — the
pre-fix `ENDING` effect *launched* `ControlSessionManager.shutdown()` and returned, so emitting
`TeardownComplete` there would have let a successor bind a listener the predecessor then closed. What
matters more: making a second session reachable immediately exposed a defect that had been **one
button press away** all along — `shutdown()` detached the three relay sinks that are installed once
per process and never re-installed, so a single **Stop Discovery** silently disabled Phase 4 and
Phase 5 for the rest of the process. It survived six Phase 4 audits and seven Phase 5 audits because
none of them could start a *second* session in which to notice. **Assume the same of anything else
whose failure needs a second session to observe: that area has effectively never been audited.**

`docs/STATUS.md` is the authority on this and is kept current; the sections below are the
architectural summary for phases 1a–2b and remain accurate for *those* phases. Phase 3 (local music
player, ADR-022), Phase 4 (shared catalogue + `ContentHash`-keyed transfer on a second session-bound
TLS connection, ADR-023) and Phase 5 (clock-scheduled playback, drift correction and a replicated
queue, ADR-004 + ADR-024) all landed after this section was last rewritten and are implementation-
complete on both platforms with their real-device gates open — see `docs/STATUS.md` §2q–§2ah.

**ADR-025 is rule 20 above, and it is A7's lesson finished.** A7 proved a frame's authority must come
from the connection it was read from, and threaded that through Phase 5 **only** — recording the rest
as open. Finishing it found the identical defect in Phase 4's manifest/transfer dispatch (the
already-known problem 44) and then three more nobody had asked about: `VOICE_*`, where a stale
`VOICE_STATE { closed }` with no `voice_session_id` is not a generation mismatch to the reducer and so
tore down the *successor's* live media; `AUDIO_STATE`, whose inbox survives a control boundary by
design; and the **pre-authentication family**, which is exempt from the generation gate by design and
was therefore bound to nothing at all — a retired connection's `PONG` pushed its round trip into the
successor's fresh RTT window, and a retired connection's `PAIR_CONFIRM` supplied the remote half of
PROTOCOL §4.5's two-human gate, writing a pin for a peer whose user never confirmed the six digits.
The standing lesson: **"this family is exempt from that gate" is not the same as "this family is
covered", and an exemption needs a gate of its own.**

**Amendment A7 is the shortest standing lesson of the seven, and it is rule 19 above: a frame's
generation must come from the connection it was read from, never from whatever session is live when
its dispatch happens to run.** Six audits — A1 through A6 — all worked at or below the Phase 5 sink,
and every one of them correctly treated the generation as a *value* it was handed; A6 in particular
went to great lengths to attribute losses to it. A7 found that the value itself had been read from
live state one layer above, so a Session A frame whose read-loop continuation resumed after a
reconnect arrived stamped as Session B's authority, on **both** platforms. Fixing that then made
generation arrival **non-monotonic** (`A, B, A` now reaches the ingress queue), which made A6's
eight-bucket loss ledger unsafe — its fold could re-attribute a dead session's refusal to the live
one and recreate A6's own cross-session halt. The ledger is now one bucket per generation, evicting
the smallest, and the safety argument no longer depends on arrival order at all. **A7 also confirmed
a third finding it did not fix** — Phase 4's manifest/transfer dispatch has the identical
live-generation origin — which A7 left open and **ADR-025 closed**, along with three more instances
of the same class that finishing it exposed. Software closure is still withheld, for the reason the
"Current phase" note above gives.

**Phase 4 has been closure-audited six times** (ADR-023 Amendments A1–A6), each pass finding real
integration/lifecycle defects in code that was already CI-green: eighteen, then two, then two, then
four, then three — and then the one A7 confirmed but did not fix (`docs/STATUS.md` §4 problem 44),
**fixed in A6 / ADR-025**.
**Phase 5 has now been closure-audited seven times** — A1 (ADR-024 Amendment A1):
six findings given, all six confirmed, plus a seventh found by stress-running one of the new
regressions; then A2 (Amendment A2), an independent verification *of A1*, which found five more,
confirmed all five, and found a sixth while fixing the fourth; then A3 (Amendment A3), a verification
*of A2*, which found three more and confirmed all three; then A4 (Amendment A4), a verification *of
A3*, which found four and then two more while building their regressions; then A5 (Amendment A5), a
verification *of A4*, which found three more, plus a crash and a generalisation of A4's own fix while
sweeping for adjacent instances of them; then A6 (Amendment A6), a verification *of A5*, which
confirmed every A5 finding and then found two A5's sweep could not reach, because neither is a
*continuation* resuming; then A7 (Amendment A7), a verification *of A6*, which accepted all of A6 and
then found that the generation A6 was so careful to attribute had itself been read from **live state
one layer above**, plus the loss-ledger consequence of fixing it, plus a third instance in Phase 4 it
deliberately left open. Read all of that as the standing lesson it is — on this codebase, "CI-green" and "correct" are different claims, and the gap
between them has consistently been in session lifetime, cancellation ownership, ordering across
suspensions and platform I/O contracts rather than in the wire format or the pure domain layer.
Nothing here is "final"; assume another audit would find something.

Phase 0 (hardware feasibility) is complete; do **not** repeat it. Phases 1a, 1b, 2a, 2b, 3, 4 and 5
are all implementation-complete and green on both platforms. **The overall "2 Intercom" milestone is
not complete** — its hardware gates (TEST_PLAN A-01, A-02, A-04, A-09 and V-01…V-11) have not run.

**Phase 5** (ADR-004, ADR-024, `docs/STATUS.md` §2aa and the audits in §2ab–§2ah) turned the Phase 1a clock
layer and the Phase 3 player into synchronised playback:

- **Every distributed decision is a pure, mirrored, vector-pinned table** — `CommandOrderGate`, `ScheduledCommand`, `PlaybackTimeline`, `DriftController`, `SharedQueue`, `SessionClock`. The coordinators are wiring and lifetime, never policy. That is ADR-019's direct lesson (rule 18 above).
- **One clock estimator, extended not duplicated.** `ClockSync` gained `rtt_p95` and a bounded window; `SessionClockTracker` owns offset, RTT history and readiness per session. Readiness is *not* "we have a number": an unconfirmed 30 ms step means no new command is scheduled, while playback already in flight keeps its last accepted offset.
- **The follower→leader intent is `command_seq: 0`** — the same message type, no new type (ADR-024 §3). An authoritative `command_seq` arriving at the leader is a role violation, which is what makes "a follower cannot fabricate one" checked rather than assumed.
- **Five specification gaps were found and resolved in ADR-024, not silently in code**: `RESUME` and `PLAYBACK_STATE` had no payload; the intent hop had no message; §9's 2 000-item queue cap does not fit the 256 KiB frame cap (**the cap moved to 1 000; the frame limit did not**); and §9 put `status` on the wire in the same paragraph that called it untrusted (**removed**).
- **Seven shared vector sets**, each an independent third transcription: `session-clock/`, `ordering/`, `drift/`, `queue/`, `playback-messages/`, `queue-messages/`, and the audits' `phase5-gates/`.
- **The Android scheduled-start path runs on the real emulator** — pre-roll, a start at a monotonic deadline, and ADR-004's nudge reaching the real `setPlaybackParameters` and returning to exactly 1.0 (measured sleeper wake error 1.4–3.1 ms). **The iOS half has not run on a simulator**, and `AVAudioUnitVarispeed` has never changed a real rate.
- **Closure audit A1 (ADR-024 Amendment A1) added four invariants worth knowing before touching this phase.** (1) **One ordered path per direction**: allocating a `command_seq`/`queue_revision` and handing the frame to the transport happen in *one* critical section — with **no `await` between them** on iOS, because an `await` there is an actor re-entrancy point — and one consumer drains each direction. A transport write lock orders bytes, not decisions. (2) **The post-transport handoff is lossless**: `Phase5FrameQueue` never evicts; only `POSITION_REPORT`/`PLAYBACK_STATE`/`QUEUE_SNAPSHOT` may supersede their *own* older sibling, and anything else is refused, counted and halts incremental application until authoritative state arrives. (3) **Received is not applied**: `lastReceivedSeq` feeds `CommandOrderGate`, `lastAppliedSeq` moves only when a command takes effect, and a command accepted against an untrusted clock is held in order rather than losing its sequence number. (4) **Ownership is re-proved after every suspension that precedes an externally visible effect** — a superseded correction has *zero* effects, not merely no player call. `Phase5Ingress`, `PendingCommandGate` and `PendingPlayGate` are the pure tables for the first three, pinned by `protocol/vectors/phase5-gates/`.
- **Closure audit A2 (ADR-024 Amendment A2) added one invariant that subsumes several of A1's, and it is the one to hold in mind.** **Being on the outbound queue is not being on the wire, and being on the wire is not the same session's wire.** Concretely: (1) `enqueueOutbound` *answers* whether the frame was accepted, and a refused authoritative frame commits nothing and ends Phase 5 authority for that generation; (2) `command_seq` and `queue_revision` commit at **admission**, `lastAppliedSeq` and the local audible effect only at **send success**, and the commit hook runs on the one outbound consumer so commits are in send order; (3) every outbound frame carries its **authorising generation**, checked both by that consumer *and* by `PlaybackRelay.send`, which takes it as an argument — the coordinator check alone leaves a window the relay closes; (4) `send`'s `Boolean` is consumed, and `outboundSentCount` means the write returned **true**; (5) **nothing overtakes held authoritative work** — the clock-unready buffer holds commands, `QUEUE_SNAPSHOT` and `PLAYBACK_STATE` in arrival order and replays them in it, and §5 rule 3's revision check moves to replay time with them; (6) a correction's `PLAYBACK_STATE` carries the correction's own generation and epoch to the enqueue (`emitPlaybackStateIfOwned`), never a freshly-read live one. `OutboundCommitGate` and `AuthoritativeHoldGate` are the pure tables, pinned by the same `protocol/vectors/phase5-gates/`.
- **Closure audit A3 (ADR-024 Amendment A3) added the lifetime half of A2's invariant, and it is the one that bites hardest.** **A local effect that has been *authorised* is not a local effect that may still *happen* — the session that authorised it can end while it waits.** Concretely: (1) `applyChain` and `scheduledChain` are **session-owned**; a boundary *retires* every node of both, oldest first (one `SupervisorJob` on Android, an explicit live-node registry on iOS, where an unstructured `Task` has no parent), and clearing both tails is what makes Session B never wait for Session A; (2) **cancellation is defence one, never the correctness boundary** — `ExoPlayer.prepare` and every `withCheckedContinuation`-bridged `AVAudioEngine` callback ignore cancellation, so a cancelled node still returns and carries on, and the *generation* each node captured is what stops it; (3) **every apply path proves its authorising generation before its first read of live state**, and proves it for itself rather than trusting its caller — `applyTransport`, `applySeek` and `applyStep` had no proof at all, so a retired command re-anchored the new session's timeline, stepped its queue, and (via `applyStep`'s `selected == null` branch calling `epoch.begin()`) **retired the new session's playback epoch so its armed start never fired**; (4) a retired scheduled action writes **nothing**, diagnostics included — the ownership proof moved ahead of the schedule-error measurement, because "diagnostics only" is not an exemption; (5) a frame **sent** under Session A whose local effect has not yet happened at the boundary is **abandoned**, never replayed into Session B — the deliberate opposite of A2's within-session rule, and ADR-024 A3 §E says why. A3 adds **no vector table**: coroutine and `Task` lifetime is not a distributed decision.
- **Closure audit A6 (ADR-024 Amendment A6) is the same rule applied to what is *not* a continuation, and it is the shortest one to hold: something that outlives a session must not carry that session's verdict into the next one.** `Phase5FrameQueue` deliberately survives an authentication boundary — that is correct and unchanged — but its loss accounting was two cumulative `Int`s the consumer **diffed**, and a difference carries no generation. A frame refused under Session A and observed after Session B activated therefore told Session B *it* had lost a frame, and a follower answers a loss by latching `playbackDesynchronized`/`queueDesynchronized`, which gate whether incremental authoritative commands are applied at all — so **Session B was halted because Session A dropped something**, on both platforms. Every loss now carries the generation of the **frame that caused it** (`generationOf`), the consumer **drains** an ordered `IngressLoss` ledger rather than diffing a total, and a record whose generation has ended becomes `inboundRetiredLossCount` — surfaced, never discarded. Two things this must not do: infer the generation from `currentAuthGeneration` at observation time (that inference *is* the defect) or from the next dequeued frame; and weaken A1 Finding C — within one generation a loss is still observed **before** the frame behind it is dispatched. A boundary-time baseline reset was **considered and rejected**: `offer` runs on the read loop, which may still be producing old-generation frames when the baseline is taken, so correctness would rest on a timing assumption. Second finding: **the unfenced `restoreRate()` exemption covers the player call and nothing after it.** iOS `failClosedOutbound` awaited it and *then* wrote seven diagnostics fields, so a boundary landing in `setRate` put `.transportFailed` and `outboundAuthorityLost` on a session whose transport was fine; every write now precedes the suspension and nothing follows it, `diagnostics.playbackRate` included. Android is **structurally safe** there — all three `restoreRate` callers launch it rather than awaiting it — and is mirrored anyway so a future `await` cannot silently reopen the window. The ledger is capped at 8 generation buckets and eviction **folds** rather than drops. A6 adds **no vector table**, for A3's reason.
- **Closure audit A5 (ADR-024 Amendment A5) is A4's rule applied to everything that is *not* the player, and it is the one to reach for first.** A4 asks whether an operation may still run its next **player effect**; A5 asks whether it may still **mutate coordinator state at all**. **A local mutation that has been authorised is not a local mutation that may still happen** — and unlike a player call, nothing downstream refuses it. Three sites suspended and then wrote with no post-suspension proof of any kind: `admitAuthoritativeCommand` (after `estimate()`, writing `lastReceivedSeq`/`lastAppliedSeq`/`deferredEvents` — a dead session's `command_seq` 50 became the live session's ordering floor, so `CommandOrderGate` then correctly refused the live session's own `command_seq` 1 as stale, permanently); `tickOnce` in **three** more windows between A4's two proofs (`estimate()` → `clockUnready`; `playerState()` → the `POSITION_REPORT` enqueue, whose `owns` proof sat *after* it; `isRouteTransitioning()` → `driftState` and six diagnostics from a dead session's samples); and `onPeerPositionReport`, which carried **no generation to prove** — so a stale report wrote FR-023's figure computed against a track the live session is not playing. Sweeping for the same shape found `drainDeferredEvents` calling `removeFirst()` on a buffer a boundary had already emptied (a **crash**, not a divergence) and `playRequestFence.begin()` after a suspension cancelling the live session's retained Play. Fourth finding: A4's `ownsNow` generalises to **`stillCurrentNow`**, because A5's work legitimately has no playback epoch — an admission decides ordering before any track is chosen — and requiring one would refuse valid work. The pattern is now `await stillCurrent` **then** `stillCurrentNow` **then**, with no `await` between, the mutation; the two have different jobs and neither is redundant. When the epoch does exist the pair is `owns`/`ownsNow`, and a retained epoch is **proved, never re-read** — re-reading answers a different question about a different track. **Android is structurally safe on all three and is deliberately not mirrored**: `estimate()`, `playerState` and `routeTransitioning` are synchronous there, so the suspensions do not exist — a stronger reason than A4's dispatcher accident, and reintroducing one would take a visible port-interface change. A5 adds **no vector table**, for A3's reason.
- **Closure audit A4 (ADR-024 Amendment A4) is A3's rule applied one level deeper, and it changed a *port shape* rather than adding a guard.** A3 asks whether an operation may run at all; A4 asks whether it may still run **its next effect** now that it has suspended. **Authorised to start is not authorised to continue.** Three compounds ran two externally visible effects behind one ownership proof — `applyTransport`'s `pause`→`seek` and `seek`→`start`, `MusicCoordinator.syncPrepare`'s `load`→`seek`, and `syncStop`'s `stop`→clear-the-local-queue — and two of the three were *below* `SyncPlayerPort`, where no coordinator proof could ever reach. So: **every `SyncPlayerPort` method is now exactly one externally visible effect** (`prepare` split into `select`/`load`/`seek`, `stop` into `stop`/`clearSelection`), a scheduled action is a `[PlayerStep]` value rather than a closure that could hide a second `await`, and `runOwnedSteps` re-proves ownership before **every** step. iOS additionally needed a *synchronous* proof (`ownsNow` over a mirrored `liveGeneration`) because its `owns` must `await` the session actor and that `await` is itself a re-entrancy point — Android's is a plain property and never had the window. What is deliberately **not** closed: an indivisible platform effect already dispatched under Session A may complete after it ends (a player exposes no rollback); what is closed is the *next* effect. Two more findings came out of building the regressions, both A3 Finding C's shape one function further along: `tickOnce` read live state after `drainDeferredEvents` and counted a tick a retired session had begun. **Android was not observably defective** — `withContext(Dispatchers.Main.immediate)` from the main thread never suspends, now *measured* on the emulator rather than assumed — but that is an accident of the composition root's dispatcher, so the shape is mirrored anyway. A4 adds **no vector table**, for A3's reason.
- **Nothing ran on a phone, and no audio reached a speaker or a Bluetooth endpoint.** The local two-peer integration measures 47 µs of *mapped session start error* in one process over loopback. **No alignment figure exists**, and the <100 ms product target and <50 ms stretch target must not be described as approached. TEST_PLAN §5.2's S-01…S-12 are what will change that.

**Phase 1b** gave the secure control channel: TLS 1.3 with mutual authentication,
`identity_spki_sha256` pinning, first-meeting SAS pairing and persisted trust, on top of the
Phase 1a discovery/framing/clock-sync layer. The security-state integration bug found after that —
`Connected` being read as implicit pairing success, which let an unknown peer reach `CONNECTED`
before the six digits were shown — is fixed and pinned by shared vectors on both platforms
(rule 15 above, ADR-019, `docs/STATUS.md` §2g).

Both of ADR-007 Amendment A1's open risks are closed with measurements — see
`docs/test-results/phase1b-security-spike-20260827.md`, ADR-017 and ADR-018. **Do not re-open or
re-litigate those two decisions without new measurements.**

**Phase 2a** built the voice plane on top of that channel (ADR-020,
`docs/test-results/phase2a-webrtc-spike-20260828.md`, `docs/STATUS.md` §2i):

- WebRTC pinned **exactly** — `io.github.webrtc-sdk:android:144.7559.14` and `stasel/WebRTC` `exact: "152.0.0"`. Both BSD-3-Clause, Apple's XCFramework SHA-256 verified independently, both binaries read for telemetry (none: no upload endpoint anywhere). **Never widen these to a version range** — a WebRTC minor bump changes a media stack. The Apple pin was `151.0.0` until upstream **deleted that release** and CI 404ed on the binary (ADR-020 Amendment A1, STATUS §4 problem 27): a checksum protects integrity, not availability, so **expect to re-pin this again** and re-verify the macOS slice when you do — losing it would silently take the real media test with it.
- PROTOCOL §7 is now a full specification: `VOICE_OFFER`/`VOICE_ANSWER`/`VOICE_ICE`/`VOICE_STATE`, exact schemas, bounds, and the authentication gate. There is deliberately **no `VOICE_END`** — `VOICE_STATE { state: "closed" }` is the teardown signal.
- **`VOICE_*` is absent from the pre-authentication frame allowlist**, which is the whole of its access control (rule 15's corollary). Adding a voice type to that list is a security change, and a test fails if anyone does.
- The **ADR-010 leader is always the WebRTC offerer**, never the TCP initiator. A follower sends intent; the offerer's response to intent is idempotent, so two simultaneous Start Voice presses produce one negotiation.
- **Host candidates only.** The ICE server list is empty and `VoiceEngineConfig` has *no field* that could carry a STUN or TURN server. Adding one would take a protocol and ADR change, which is the point.
- `VoiceEngine.stop()` and `release()` are **two separate calls**: a control-link blip drops the peer connection but must never close the capture device, because reopening it renegotiates the Bluetooth profile (ARCHITECTURE §6.2/§6.3) and because Android forbids reopening a microphone from the background (§6.4).
- Every decision lives in the pure, mirrored `VoiceNegotiation` table, pinned by `protocol/vectors/voice-fsm/` — not in `VoiceController`. That is the direct lesson of ADR-019 and of STATUS §4 problem 20.

**Real WebRTC media is proven on this machine** — two real engines, host-only candidates, DTLS
connected, `audio/opus` at 48 kHz, deterministic over five runs. It works under `swift test` because
the Apple WebRTC XCFramework carries a macOS slice.

**What is *not* done is anything on a real phone, and no audio has been captured or played
anywhere.** In particular: the Android WebRTC media path has **no test of any kind**
(`PeerConnectionFactory.initialize` needs an Android `Context`); neither `AndroidVoiceAudioSession`
nor `IosVoiceAudioSession` has ever executed on a device, only their pure route mappers are tested;
`RideForegroundService` has never started; every `assumed` value in the two route mappers is a
reasoned guess about unmeasured hardware; and **no latency figure exists**, so the <200 ms target
must not be described as approached, let alone met. The Phase 1b device gate is also still open, and
the Android half of the exporter-equality result was measured against Conscrypt on a laptop rather
than the phone's own TLS stack.

**Phase 2b** turned that transport into an intercom (ADR-021, `docs/STATUS.md` §2m):

- **The transmission gate never touches the capture device** (rule 17 above). PTT, VOX and mute gate the outbound WebRTC audio track; the device opens once, foreground-visible, and stays open for the ride segment. `IntercomTransmission` is the pure mirrored table that decides it, and its action vocabulary has **no** capture case — that absence is the enforcement. `VoiceControllerIntercomTest[s]` counts 50 presses against 1 open and 0 closes; TEST_PLAN **A-10** is the same assertion with a real helmet unit and is **pending**.
- **Full duplex stays primary.** `gate: none` (Modes A and D) is the no-gate policy; PTT and VOX are fallbacks over the *same* live capture path and the *same* WebRTC session.
- **VOX's gate is implemented; its level source is not.** Neither pinned WebRTC distribution exposes a fast per-frame input level, and ADR-021 §6 declines to hand-write a detector to fill the gap. Selecting Mode B today means the gate cannot open, `voxLevelSourceAvailable` is `false`, and the UI says so: **PENDING REAL AUDIO INPUT / LATER HARDENING**.
- **The default is Mode C by architecture, not by measurement.** `docs/PHASE0_RESULTS.md` is still empty, so nothing selected it but ARCHITECTURE §6.3 and ADR-008 §4. Do not present it as validated.
- **`AUDIO_STATE` is implemented, with no wire change**: bounds, the monotonic `revision` on both sides, and the same pre-authentication absence that gates `VOICE_*`. PROTOCOL §4.4's "`intercom_mode` mirrors `VOICE_STATE.mode`" was a contradiction (four values against three) and ADR-021 §3 resolves it — `intercom_mode` is a **superset**, because it describes local audio state rather than a live session.
- **Every `AVAudioSession`/`AudioManager` decision is now in `AudioSessionLifecycle`**, a pure mirrored reducer: `stable -> transitioning -> stable` with a **measured** duration, settled by the platform's own callback and never by a sleep; `shouldResume` read rather than assumed; and a strict generation guard so a callback from before a media-services reset is inert.
- ARCHITECTURE §6.4's readiness sequence is `RideStartPolicy`, pure and mirrored, and its 2^7 cross-product asserts that **no** decision ever opens capture from the background.

**Nothing in Phase 2b ran on a phone, and no audio has been captured or played anywhere.** The
Android WebRTC media path still has **no test of any kind**; `AndroidVoiceAudioSession`,
`IosVoiceAudioSession` and `RideForegroundService` have still never executed on a device; every
`assumed` value in the two route mappers is still a reasoned guess about unmeasured hardware; and
**no latency figure exists.** The setup timings this phase adds (`VoiceSetupTimeline`) measure how
long the *app* took to bring voice up — mouth-to-ear latency (A-09/V-11) includes two Bluetooth hops
and **cannot** be inferred from them or from network RTT, so the <200 ms target must not be described
as approached, let alone met.

Read `docs/STATUS.md` §4 and §7 for exactly what is verified versus pending and for the exact next
task, and `docs/TEST_PLAN.md` §3.1a / §3.1b / §5.1 / §6.1 for the line drawn item by item.
