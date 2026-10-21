# Phase 9B-A — Android ↔ Android full qualification, run 1 (19 October 2026)

**Verdict: ANDROID-ANDROID QUALIFICATION BLOCKED.**

What this run established on two physical Android phones:

- Problems 123 and 124 are physically resolved for every path exercised.
- Trusted reconnection works.
- **Real two-way voice is audible and was confirmed by the user**, in both directions, in continuous
  mode and in push-to-talk.
- Mute works.

Why the verdict is still BLOCKED:

- **Problem 125** is a new High defect, reproduced five times under ordinary conditions. Its fix is
  draft PR #24, which is neither reviewed nor installed.
- Several mandatory gates were not run, chiefly synchronised music, shared library and transfer,
  coexistence, the lifecycle rows and the long-duration gates.
- A fresh first-time pairing (I-02) is impossible through any normal path, because neither app has
  a *Forget peer* control (problem 126).

Four further defects were recorded (problems 127–130). No application code changed in this
evidence run. No Android ↔ iOS, helmet Bluetooth, road-test, Phase 9 or V1 claim is made.

The earlier runs are in [PHASE9BA_ANDROID_ANDROID_PHYSICAL.md](PHASE9BA_ANDROID_ANDROID_PHYSICAL.md).
They are left unchanged; the original I-02 FAIL stands.

## 1. Scope

- **In scope:** Android ↔ Android only, on two physical phones on one Wi-Fi access point.
- **Audio route:** each phone's built-in earpiece and microphone. No Bluetooth endpoint was connected.
- **Conditions:** stationary and indoors.
- **Equipment:** there was no audio recorder or measurement rig. Every audio result is the user's
  audible confirmation, and no latency or alignment figure was measured.
- **How it ended:** the user ended the run early, after about 3.3 hours, at the checkpoint in §17.
  They chose to publish the evidence and resume after PR #24.

## 2. Source revision

| Field | Verified value |
|---|---|
| origin/main | `47d7cdef5601a5c01f692227aadedafeb7e93ed1` (PR #23 merge). Its tree `6532575a…` is byte-identical to PR #23's reviewed head `88fd88a` |
| CI on main | CI: success |
| Security on main | gitleaks, CodeQL java-kotlin, wrapper-validation and local-only-policy: success. dependency-review: skipped (it runs on pull requests only). **CodeQL swift was still running** when the run began, so it is not counted as passed |
| Build tree | A clean isolated worktree on branch `phase9ba/android-android-full-qualification`. The main checkout's unrelated uncommitted edits were not touched |
| Revision stamp | All 25 in-app diagnostics exports taken during the run report `source_revision: 47d7cdef5601a5c01f692227aadedafeb7e93ed1` |

## 3. APK provenance

| Field | Value |
|---|---|
| Build | `tools/sideload/android.sh build` with the existing sideload key; no new key. Built 2026-10-19T03:43:38Z with build-tools 36.0.0 and JDK 21.0.12.1 |
| APK | `ridelink-android-47d7cdef5601.apk`: release, not debuggable |
| Signing | APK Signature Scheme v3, one signer |
| Alignment and SDK | `zipalign -c -P 16 4` OK; minSdk 31, targetSdk 36 |
| APK SHA-256 | `afafa4c13bb82fb9198c5f8396cd476fb7833b862654139e13487b6b5a752b65` |
| Signer certificate SHA-256 | `51d88fc76947c7027fa8fad18a77961c4d1e5c14e950c70d6888ada9240b3ee2`, the same key as the installed build |
| Before (both phones) | Installed `base.apk` read back as `ff0cfdf0…71cc` (source `2ec06ef`), signer `51d88fc7…3ee2` |
| Install | User-approved, in-place, with no uninstall, no `--clean` and no data clear. **A:** `tools/sideload/android.sh install -s <A>` at 03:45:55Z. **B:** `adb install -r --user 0` at 03:46:55Z, so the managed work profile was never touched |
| Read-back | Each phone's installed `base.apk` was pulled and hashed: `afafa4c1…2b65` on both, identical |
| Preserved state | CE/DE data-directory inodes unchanged on both phones, install users unchanged and the permission table unchanged (all diffed). Identities `spki:8eb849…` (A) and `spki:5e38e3…` (B) unchanged. Libraries unchanged: A 78 tracks, B 0 |
| Pins | Proved by I-03's silent connect (§6) |

## 4. Device inventory

`adb devices -l` listed three transports: one mDNS wireless-debugging transport per phone, plus an
emulator, which was never used. Every command used an explicit `-s` selector, and every RideLink
operation was for user 0. Serials, addresses and SSIDs are not recorded.

| | Device A | Device B |
|---|---|---|
| Model | OnePlus Nord 5 (CPH2707) | Motorola moto g57 power |
| Android / API | 16 / 36 | 17 / 37 |
| Physical | Yes (`ro.boot.qemu` empty) | Yes |
| Role (ADR-010) | Follower (`Local leader: false`) | **Leader** (`true`) |
| Lock screen | Secure keyguard | |
| Runtime permissions at start | None granted | None granted |
| Permissions granted in the run | `RECORD_AUDIO` and `POST_NOTIFICATIONS`, by the user, through the app's own prompts | The same |

## 5. Network topology

- One shared Wi-Fi access point, with both phones on it. The SSID is not recorded.
- No hotspot topology was run.
- The test Mac was on the same network, and was used only for wireless ADB and the mDNS capture.
- Many short control-link drops happened while the user carried the phones between rooms:
  - **12** `LinkLost(NETWORK)` on A and **11** on B during intercom testing.
  - Most recovered automatically within 1.2–4.3 s at the control level.
  - Whether the drops are radio or roaming conditions or the control plane's 6 s silence rule
    firing under Wi-Fi power saving is **not established** (INCONCLUSIVE). Each drop is what exposed
    problem 125.

## 6. Security and pairing results

| ID | Status | Evidence |
|---|---|---|
| I-01 Discovery | **PASS** | Both phones were *Connected* at the first UI poll, 0.8 s after the second phone's tap. *Discovered peers (current)* was 1 on each. Repeated about 10 times across the run, always well under 5 s |
| I-03 Trusted reconnect | **PASS** | Both processes were freshly started by the update, then *Find other phone* was tapped on B (03:50:08Z) and on A (03:50:09Z). Both logs show `known peer …, silent connect`, then `PAIRING → CONNECTING → CONNECTED`, with **no code prompt**. Transport `TLS 1.3 / MUTUAL / SPKI-PINNED`, `TLS_AES_128_GCM_SHA256`. The identities match each other (A sees `5e38e3…`, B sees `8eb849…`). Leader: B `true`, A `false`. Reconnect count 0. **Exactly one control connection** (one ESTABLISHED socket per side, plus each listener). Both sides logged the redundant dial as `DuplicateConnectionClosed … not_a_fault` |
| I-02 Fresh first-time pairing | **BLOCKED (problem 126)** | Neither app has a *Forget peer* control (§13). The only other routes are uninstalling or clearing data, which the user excluded and which would also destroy the identity. The historical I-02 FAIL stands; trusted reconnection does not close it |
| Checkpoint E: pairing interruption | **BLOCKED (problem 126)** | Needs a fresh pairing |
| I-16 Simultaneous first-time pairing | **BLOCKED (problem 126)** | |
| I-20 Identity key rotation | **BLOCKED (problem 126); not run** | Also needs key regeneration, which is excluded |
| I-04, I-19, I-21, I-14, I-18 | **DEFERRED** | Each needs a trust-store edit, certificate re-issue, a device clock change, a test build or a debug socket tool. All were excluded or unavailable |
| I-22 mDNS privacy | **PASS (TXT content); rotation PARTIAL** | Captured with `dns-sd -B/-L` from the test Mac: two adverts, `RideLink-58ad4ec5` and `RideLink-e80272cf`. Their TXT is exactly `v=1 dh=<32 hex> plat=android`. Neither `dh` contains either `peer_id` prefix (`40be2f`, `59f1b6`) or SPKI prefix (`8eb849`, `5e38e3`). `dh` rotation across sessions is shown by the apps' own logs (A `6805f8 → b0a107 → 58ad4e → …`, B `5517e9 → cd5556 → a420c5 → e80272 → …`) but has **one** independent capture, not two |
| Unauthenticated `CONNECTED` | **None observed** | Every `CONNECTING` in every log is preceded by `known peer …, silent connect` for the same connection |

## 7. Problems 122, 123 and 124: physical regression outcomes

| Problem | Status | Evidence |
|---|---|---|
| 122 (Keystore TLS) | **Still fixed** | Over 15 Keystore-backed mutual TLS 1.3 handshakes, with no `INCOMPATIBLE_DIGEST` |
| 123 (`PONG` write crash) | **PASS: ordinary link loss never crashed either app** | Loss conditions: End session (BYE); peer app force-stopped (twice); A's screen off with no foreground service (the original trigger: OxygenOS dropped A's link about 5 s after screen-off); about 20 natural Wi-Fi drops; and a cellular call. `logcat -b crash` held no RideLink entry on either phone, and nothing reported `am_crash` or `FATAL`. Each loss produced exactly one `LinkLost` per side. **That the specific `PING`→`PONG` write race was hit is not claimed:** nothing shows a `PONG` write failing. The deterministic regression `PongWriteFailureTest` covers it |
| 124 (verdict dropped in `DISCOVERING`) | **PASS (trusted path, physical); SAS path BLOCKED (126)** | Exercised deliberately four times: A searched alone, then B searched, B dialled A, and A received the trusted inbound connection while still searching. A's log: `IDLE → DISCOVERING`, `known peer peer:40be2f…, silent connect`, **`DISCOVERING → CONNECTING (PairingSucceeded)`** with no `PeerSelected`, then `CONNECTED`. Both reached *Connected*, with one control connection, one voice subsystem attach per side and no code prompt. The SAS variant (one confirmation must stay out of `CONNECTING`) needs a fresh pairing |

Problem 124's 7-point checklist:

- **A:** PASS.
- **B** (a previous attempt ends, then the peer dials in): PASS on the trusted path. B's own dial failed, then A dialled into B's `DISCOVERING`, 05:52Z and 05:55Z.
- **C, D:** PASS.
- **E** (TLS alone cannot bypass the gate): no counter-example in any log. The negative SAS case is BLOCKED by 126.
- **F, G:** PASS.

## 8. Connection and reconnection matrix

| ID | Status | Notes |
|---|---|---|
| I-05 Short interruption | **FAIL (problem 125)** | The first ordinary drop in a process recovers in 1.2–4.3 s. But after any reconnect completed by the **peer's** dial, the local reconnect ladder is orphaned. A drop more than ~120 s later goes `RECONNECTING → DISCONNECTED` in **19–160 ms**, with zero attempts. Seen 5 times (§13). `reconnect_count` cannot be read either: the card is published once per ladder start and was stale (0 after a reconnect, 29 later). Aeroplane mode was not used |
| I-06 Extended interruption, then Retry | **PASS (app-terminated variant); aeroplane variant NOT RUN** | B was force-stopped for over 120 s. A reached `DISCONNECTED` with *Search again*, which re-entered `DISCOVERING` and reconnected to B. In this instance A's `DISCONNECTED` was itself early, through problem 125 |
| I-15 Simultaneous trusted connect | **INCONCLUSIVE** | Many near-simultaneous dials happened naturally. Each ended with one connection, one session and the duplicate closed as not-a-fault. Nothing was triggered within the required ~50 ms, so I-15 is not claimed. One natural simultaneous reconnect raised a false security alert (problem 130) |
| I-17 Repeated simultaneous reconnect | **BLOCKED (problem 125)**; not run | Needs AP toggling (disruptive) and a fixed ladder |
| I-26 End Session, then a second session | **PARTIAL** | Both sides passed `ENDING → IDLE` (`TeardownComplete` 35 ms after End session on A). A second session re-advertised with a new `dh`, re-authenticated and re-attached voice, in the same processes, many times. Shared catalogue, synchronised playback and the new `revision_epoch` were not checked, and problem 128 affects the retry flavour |
| I-07, I-08, I-09, I-10, I-11, I-12, I-13, I-23, I-24, I-25 | **NOT RUN** | The run stopped at the checkpoint. I-08 clock figures: RTT 13–204 ms and jitter 3.0–11.4 ms were seen on the card; this is not a 5-minute measurement |

## 9. WebRTC and intercom matrix

Intercom tests were under the default PTT mode and under *Always on · music lowered* (continuous).
The audio route on both phones was `BUILTIN_EARPIECE` and the built-in microphone, confirmed in
`dumpsys audio` and on the route card.

| ID | Status | Evidence |
|---|---|---|
| V-01 Start from the leader | **PASS** | B (leader) started; A was asked for consent (*"The other phone wants to talk"*) and started. Both sessions `ACTIVE` on one `voice:3c3379…`, B `OFFERER`, A `ANSWERER`, `aec/ns/agc` on. Media from B's card: peer connection `CONNECTED`, DTLS connected, SRTP `AES_CM_128_HMAC_SHA1_80`, DTLS `TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256`, `audio/opus 48000 Hz`, 3419 packets sent and 3263 received. Microphone foreground service type `0x80` on each. **Audible in both directions, confirmed by the user** (continuous mode, 04:44–04:58Z) |
| V-02 Start from the follower | **PASS** | A (follower) pressed Start first (05:55:24Z) and B was asked for consent. Then one session, `voice:1d4394…`, with B `OFFERER` and A `ANSWERER`: the leader offered, never the first presser. Offer and answer counts are not shown on the UI, so "exactly one" rests on the single shared session ID |
| V-03 Simultaneous start | **PASS (negotiation)** | Taps dispatched to both phones within ≈0.3 s (05:57:23Z): one session, `voice:bd6cad…`, on both, with no second negotiation. A's capture failed in this instance because a cellular call was in progress on A (below) |
| V-04 Host-only ICE | **PASS** | Selected candidate `HOST / HOST` on both phones |
| V-05 Mute and unmute | **PASS (audible, user-confirmed)** | A muted: `mic (device) open, muted`, `transmitting false`, `wire mic_muted true`; inaudible on B. Unmuted: audible again. Same session and capture device throughout. **Gap:** B's screen shows no peer-muted indicator |
| V-06 End Voice | **PARTIAL (problem 129)** | End intercom on A returns both to *Intercom off*, the control session stays *Connected*, and A's recorder and player are released. But 2 of 3 in-app ends left A's microphone-typed foreground service and a stale *"Intercom on — Starting the microphone…"* notification for over 3.5 minutes |
| PTT | **PASS (audible, user-confirmed), with one INCONCLUSIVE attempt** | 20 s hold on A at 05:24:22Z: audible on B. The first 25 s hold (04:31:20Z) was not heard. Packets were flowing then, and it came before the earpiece-only route had been explained, so it is recorded as test conduct, not a failure. Every press and release opens and closes a new platform recorder (problem 127) |
| V-07 Voice recovery after loss | **INCONCLUSIVE** | Natural drops with voice active rebuilt voice with a new `voice_session_id` (`41ce8a → 97248c`, `85b618 → e53aa0`). The leader rebuilt and waited for the answerer's consent, as §7.3 and §7.8 require. Audio after a rebuild was not confirmed by ear. The platform recorder was re-created on each rebuild (problem 127) |
| V-08 Voice while screen-locked | **INCONCLUSIVE** | A woke 5.3 s and 125 ms after each sleep (picked up and unlocked), so it never stayed locked. B's screen was off for about 188 s, but nobody listened then. Not claimed |
| V-09 `RECORD_AUDIO` denied | **NOT RUN** | |
| V-10 Topologies | **NOT RUN** | Shared AP only |
| V-11 Mouth-to-ear latency | **DEFERRED (equipment)** | No recording or measurement rig. The card's setup timings are explicitly not latency. **The <200 ms target is not claimed** |
| VOX (Mode B) | **Not tested** | Still PENDING REAL AUDIO INPUT, as before |
| Cellular call during intercom (A-06, Android half, built-in route) | **PASS (unplanned)** | A received a real cellular call during V-03. `MODE_IN_CALL` was owned by the system and RideLink's recorder was `silenced:true`. The app showed *"Audio is busy. Finish other audio activity, then try again."* (`AUDIO_SESSION_ACTIVATION_FAILED`). No crash, and the control and voice sessions survived. After the call the recorder was un-silenced, but the app did **not** recover its microphone by itself; End intercom then Start intercom recovered it |
| N-03 Live intercom notification | **PARTIAL** | Present on both phones with *Intercom on — Microphone on* and Mute / End intercom actions. The notification's **End intercom** (pressed by the user on A) worked: it removed the service and the notification. The stale notification after an in-app end is problem 129 |

## 10. Synchronised music matrix

**NOT RUN**: S-01…S-12 and Q-02.
- B's library is empty. The run stopped before a synthetic fixture was imported and transferred.
- No alignment figure exists, and **no synchronisation target is claimed**.
- S-09 (30 minutes) and S-11 (10 minutes locked) cannot produce a meaningful result while problem
  125 is open. In this run the session was disturbed every few minutes.

## 11. Shared-library and file-transfer matrix

**NOT RUN.** The connected cards did show the catalogue exchange reaching B: *Other phone's music* on
B showed **77** tracks, against A's library of **78**. The difference was not investigated; possible
causes are a duplicate content hash or a non-shareable item. I-10, I-11, I-23 and I-24: NOT RUN.

## 12. Notification and lifecycle results

| ID | Status | Evidence |
|---|---|---|
| AF-01 (FGS types; capture opens) | **Partial evidence (two-phone)** | Intercom start produced `RideForegroundService` with `types=0x00000080` (microphone) on each phone. The music-and-intercom combination was not run |
| AF-03 | **NOT RUN** | |
| AF-04 / AF-09 (background start refused) | **Observed incidentally** | The first *Start intercom* after granting the microphone permission was refused on both phones: *"Bring RideLink to the front, then try again."* (`intercom start refused: BACKGROUND_START_REFUSED`), because the permission dialog had taken the foreground. Safe, no retry. A UX observation (§16) |
| N-04, AF-02, AF-05, AF-06, AF-07, AF-08, AF-10 | **NOT RUN** | AF-05 (30 minutes locked) is also blocked in practice by problem 125 |
| Earlier one-phone results | **Unchanged** | Phase 9A and 9A.5 one-phone rows stand as recorded. This run adds no contradicting evidence |

## 13. Defects found in this run

| # | Severity | Summary | Status |
|---|---|---|---|
| **125** | **High** | **Orphaned reconnect ladder.** `promote()` resets but does not cancel `ReconnectController`. After a reconnect completed by the peer's dial, the ladder keeps dialling during the live session, exhausts ~120 s later (`rejected ReconnectBudgetExhausted from CONNECTED`, seen 117–121 s after each inbound reconnect), writes `controlState = DISCONNECTED` into diagnostics, and leaves the budget spent. The next drop gets **zero** attempts. The FSM then drops the peer's re-authentication in `DISCONNECTED`, giving *Disconnected* over a live control session with voice capturing and no intercom controls on screen. Reproduced 5 times, on both phones, from an app kill, screen-off and ordinary Wi-Fi drops | **FIXED IN SOFTWARE** in [draft PR #24](https://github.com/arunachaleswaranms/RideLink/pull/24) (head `82910c2`), pending independent review. Not installed; physical retest pending |
| **126** | **Medium** | **No *Forget peer* control on either platform.** `SessionCoordinator.forgetPeer` exists on Android and iOS, and nothing in production calls it. ADR-012, ARCHITECTURE §4.3 and PROTOCOL §4.5.3 all make *forget and re-pair* the only recovery from a genuine key change or `pin_mismatch`. In the shipped apps the only recourse is clearing app data, which also destroys the identity. It also blocks fresh-pairing qualification (I-02, I-16, I-20, Checkpoint E) | Open; no fix branch |
| **127** | **Medium–High** (the Bluetooth impact is unmeasured) | **The platform recorder closes and reopens per mute and per PTT utterance, and on every voice rebuild.** `dumpsys audio` recording events on A: `rec stop` at the instant of each PTT release (09:59:25, 10:01:45, 10:54:42 local) and of a mute (10:40:44); a **new** recorder session at each press or unmute (2969 → 2985 → … → 3241, 3321); and new sessions on every rebuild, on both phones. CLAUDE.md rule 17 and ADR-021 require the capture device to stay open for the ride segment, and that PTT, VOX and mute gate only the outbound track, precisely so that a Bluetooth endpoint is not renegotiated per press. RideLink's own state (`mic (device) open`) stays open; the WebRTC audio device module's `AudioRecord` does not. Root cause inside WebRTC is not established. On a helmet unit this is the A-10 risk | Open |
| **128** | **Medium** | **A peer in `ENDING` accepts and authenticates a new connection and attaches voice.** After A's *Search again* BYE'd B, B went `CONNECTED → ENDING` and its teardown took 5.1 s. In that window B's still-open listener accepted A's new dial, ran the trusted check (`known peer …, silent connect`) and logged `voice subsystem attached`. Then `ENDING → IDLE` BYE'd it, and A followed to `IDLE`. Effect: *Search again* lands at *Not connected*. Rule 21 forbids session work after `ENDING` begins. Nothing was left running afterwards: no recorder, player, service, notification or socket | Open |
| **129** | **Medium** (privacy surface) | **In-app *End intercom* intermittently leaves the microphone foreground service and a stale notification.** 2 of 3 in-app ends on A left `types=0x00000080` and *"Intercom on — Starting the microphone…"* (with End intercom) posted for over 3.5 minutes, while the app said *Intercom off / Microphone off* and no recorder ran. The notification's own End intercom cleared it. `IntercomStopOwner.requestStop()` releases the service only on `Released`/`AlreadyReleased` with an unchanged start epoch; which branch was taken is not visible, because the diagnostics export does not log intercom actions | **FIXED IN SOFTWARE** in [draft PR #26](https://github.com/arunachaleswaranms/RideLink/pull/26), pending independent review. The `TimedOut` branch, through a caller window shorter than the release (ADR-021 A9). Not installed; physical retest pending |
| **130** | **Low–Medium** | **A benign simultaneous reconnect raised a security alert.** During a natural drop at which both ladders dialled, A logged `handshake refused: unexpected_message_type` and showed a **"Connection refused"** security card. The reconnect succeeded 1.85 s later. Security warnings that fire on ordinary races train users to dismiss real ones (ADR-012) | Open |

## 14. End Ride and restart results

End Ride was not exercised. End session and its successor sessions are described under I-26 (§8):
`ENDING → IDLE` completes on its own, and later sessions re-advertise and re-authenticate in the same
process. With problem 128, a *Search again* from `DISCONNECTED` can terminate the peer's fresh
session.

## 15. Human-confirmed observations

All of these are the user's own words, given in answer to one question at a time. No SAS digits
were read or recorded. No security confirmation was pressed by automation.

| Time (UTC) | Question | Answer |
|---|---|---|
| ~04:32 | PTT hold on A (25 s): heard on B? | "nothing" (INCONCLUSIVE; §9) |
| ~04:47 | Continuous mode, A → B? | "now i able to hear" |
| ~04:48 | Continuous mode, B → A? | "yes, B to A is also audible" |
| ~05:13 | A muted: A heard on B? | "yes, works as expected" (recorded as inaudible, as expected; the user was told this interpretation and did not correct it) |
| ~05:15 | A unmuted: audible again? | "yes, audible again" |
| ~05:25 | PTT hold on A (20 s): heard on B? | "yes, audible" |
| ~06:40 | Did you tap End intercom on A around 06:15? | "yes, i give end intercom" |

The user also pressed the notification's *End intercom* on A. They took a real cellular call on A
during V-03.

## 16. Objective measurements and observations

- **Timing:** no mouth-to-ear latency, alignment, drift or `PAUSE`-under-transfer measurement was
  possible (equipment). The setup timings on the card are not latency.
- **Reconnect times:** ordinary drops recovered in 1.2–4.3 s at the control level. Two took 16 s and
  180 s.

UI and diagnostics observations, not filed as problems:

- The intercom card keeps *"The connection to the other phone was lost."* after a successful
  reconnect.
- The first *Start intercom* after granting the microphone permission is refused as a background
  start, so the user must press again.
- The `PAIRING` status reads *Check the code* during a **trusted** dial, when no code exists.
- There is no peer-muted indicator.
- The intercom mode resets to PTT when a session is retired.
- The app does not recover its microphone after `AUDIO_SESSION_ACTIVATION_FAILED` once the
  competing call ends.
- The diagnostics export records no intercom start, end, mute or voice rebuild events. This made
  problems 129 and A's 06:15Z stop undiagnosable from the phone.
- The diagnostics card is missing a value for *Clock offset* in the accessibility tree.
- There is no live microphone or peer audio-level indicator. The user suggested one; it is standard
  in call apps, and WebRTC `getStats()` `audioLevel` would support a UI meter, though not VOX.
- Intercom audio routes to the earpiece only, with no speaker option on the built-in route.

## 17. Outstanding risks

- Problem 125 makes every multi-minute two-phone result unreliable until PR #24 is reviewed,
  merged and installed.
- Problem 127 may make PTT and mute renegotiate a Bluetooth headset per press, which is the
  product's top risk (ARCHITECTURE §6.3). It must be understood before A-10 or helmet testing.
- Problem 126 leaves no supported recovery from `pin_mismatch` on either platform.

## 18. Deferred and not-run cases, and their prerequisites

| Cases | Prerequisite |
|---|---|
| I-02, I-16, I-20, Checkpoint E | A *Forget peer* path (problem 126) and the user's approval to forget |
| I-04, I-19, I-21, I-14, I-18 | A safe test harness (trust-store edit, certificate re-issue, clock or version test build, debug socket tool). None is allowed on these release installs |
| I-05, I-17, S-09, I-12, S-11, AF-05, A-04 | PR #24 reviewed, merged and installed (problem 125) |
| I-07, V-10 | User approval to change Wi-Fi or hotspot topology, and a plan to recover wireless ADB |
| S-01…S-12, Q-02, I-09, I-25, I-26 (music half) | A non-personal synthetic fixture on both phones, imported or transferred |
| Shared library and transfer, I-10, I-11, I-23, I-24 | Synthetic fixtures. A 40 MB file for I-10; a 1 000+ track approved fixture for I-23 (DEFERRED until approved) |
| V-11, S-03/S-04/S-05, I-08, I-12 | Measurement equipment: a two-channel recorder, or a defined acoustic click-track method, plus a clock-sync log export |
| V-08, V-09, N-04, AF-02/03/06/07/08/10 | Next session; V-09 and N-04 need a recorded, restored permission change |
| Helmet or TWS Bluetooth (A-series) | Separate hardware gate; nothing here applies to it |

## 19. Final qualification decision

| Statement | Supported? |
|---|---|
| A. Secure Android-to-Android connectivity works | **Yes, with problem 125.** Mutual TLS 1.3, SPKI-pinned, with no bypass observed |
| B. First-time pairing works | **Not established** (BLOCKED by 126; the historical FAIL stands) |
| C. Trusted reconnection works | **Yes** (I-03) |
| D. Bidirectional voice intercom works | **Yes**, confirmed by the user, on the built-in earpiece and microphone |
| E. Supported intercom modes work | **Partly.** Continuous and PTT are audible; mute works. VOX is pending; Mode D and Mode E were not tested. Problem 127 is open |
| F. Synchronised music works | **Not tested** |
| G. Shared queue consistency works | **Not tested** |
| H. Shared catalogue and file transfer work | **Not tested.** The catalogue exchange was visible (77 of 78 tracks) |
| I. Voice and music coexistence works | **Not tested** |
| J. Lock-screen and background behaviour work | **Not established** |
| K. Ordinary network interruptions recover safely | **No.** The first drop recovers, but problem 125 strands a phone on the next |
| L. End Session and the second ride work | **Partly.** Sessions only; music not checked; problem 128 |
| M. Long-duration synchronisation targets are measured and satisfied | **No measurement exists** |
| N. No known critical or high-severity blockers remain | **No.** Problem 125 is High (fix in review) and 127 is Medium–High |

**ANDROID-ANDROID QUALIFICATION BLOCKED.**

## Test conduct

- Every scripted tap first checked that RideLink was the focused window.
- When the user was using another app on B, input to B stopped. RideLink was brought back to the
  front only with the user's go-ahead. One set of swipes landed in the Files app after a
  diagnostics export; it was harmless, with no taps.
- **Never automated:** security confirmations, permission grants and the notification's
  *End intercom*. Permission grants and *End intercom* were pressed by the user.
- Raw `logcat`, `dumpsys` output and the 25 diagnostics exports are kept off-repository. Only
  sanitised excerpts appear here.
- Nothing that identifies the phones or their owners is published: no serials, addresses, SSIDs,
  contact names, Bluetooth addresses, music names, codes, keys or TLS secrets.
- The fix for problem 125 was developed in parallel by a separate agent with no phone access, on a
  separate branch (draft PR #24). This evidence branch contains no production code.

**Phones left as found (cleanup, 07:12–07:18Z):**

Steps:

1. The user agreed to end the intercom and the session.
2. On A: *End intercom*, then *End session*. A released everything.
3. B was locked and stranded in problem 125's `DISCONNECTED` state.
   - Its control connection had closed after A's BYE.
   - It **kept the `microphone`-typed foreground service and an *"Intercom on — Microphone on"*
     notification** with no recorder running.
   - The `DISCONNECTED` screen offers no End intercom or End session.
4. After the user unlocked B: *Search again*, whose teardown released the audio and the service,
   then *Stop searching*. This is a further consequence of problem 125: a stranded phone keeps its
   microphone service until the user retries.

Final state:

| | Device A | Device B |
|---|---|---|
| RideLink | *Not connected* | *Not connected* |
| Foreground service, recorder, player, RideLink notification, sockets | None | None |
| Identity | Unchanged (`8eb849…`) | Unchanged (`5e38e3…`) |
| Pin for the other phone | Kept | Kept |
| Library | Unchanged (78 tracks) | Unchanged (0 tracks) |
| Runtime permissions | Microphone and notifications now granted, by the user | The same |
| Nothing uninstalled, cleared or forgotten | Yes | Yes |

## Exact next step

1. Independent review of draft PR #24 (problem 125). Then merge, build, install in place and
   physically retest using TEST_PLAN's problem 125 procedure.
2. Separate fix work for problems 126–130, each in its own reviewed PR. Problem 127 first, because
   it bears on the Bluetooth gate.
3. Resume this qualification at §10–§12 with a synthetic fixture: synchronised music, library and
   transfer, coexistence, then the lifecycle rows. After that, the long-duration gates on the fixed
   build.

---

# Run 2 — continuation (19–20 October 2026)

**Verdict: ANDROID-ANDROID QUALIFICATION BLOCKED.**

Run 1 above is unchanged; its results (including the historical I-02 FAIL) stand. Run 2 ran on the
**same installed build** (`47d7cde`), because PR #24 (problem 125) was still a draft with its Swift
CodeQL job cancelled, and no other fix was reviewed. Every result below is therefore a result for
`47d7cde`. Device B was available intermittently and was released at the end of this run (§R2.12):
**no further two-phone testing is possible on these devices.**

What run 2 established:

- **Synchronised music works functionally on two phones**, and its alignment was **measured**: the two
  phones' loudspeaker output was within **≤ 2 ms** of each other across 78 clicks (PLAY start, PAUSE
  and RESUME, SEEK, steady state), using a validated single-microphone method (§R2.5).
- **But the drift controller misreads that alignment** (problem 131): both phones report −0.4 to
  −0.65 s of drift, hard-seek (audibly) and then declare `SYNC_FAILED` on every start, resume and
  auto-advance. Ride Mode shows *"Music sync paused"* while the music plays in sync.
- **Shared catalogue and bidirectional transfer work**, including a 43.6 MB track and a
  1,133-entry (≥ 5-page) manifest.
- **Music and intercom coexist**: voice was audible both ways over synced music (user-confirmed), PTT
  ducks the peer's music to 25 % and restores it, and mute affects voice only.
- **Problems 125, 127 and 129 reproduced again**, and four new problems were found (132–135).
- **I-10 fails** its 200 ms criterion: a follower's PAUSE took 0.5–0.65 s with no transfer and 1.9 s
  during the 40 MB transfer.

No application code changed in this evidence run. No Android ↔ iOS, Bluetooth, road-test, Phase 9 or
V1 claim is made.

## R2.1 Source, provenance and baseline

| Field | Value |
|---|---|
| Installed build (both phones, unchanged from run 1) | `47d7cde`, APK `afafa4c1…2b65`, signer `51d88fc7…3ee2`, read back on both at the start of run 2 |
| Install in run 2 | **None.** PR #24 was not merged (draft; Swift CodeQL cancelled twice at the 45-minute job limit; PR #28 raises that limit and is a draft) |
| `origin/main` during the run | `3627c3d` |
| Diagnostics exports | 8 exports pulled, all `source_revision: 47d7cdef5601a5c01f692227aadedafeb7e93ed1`; kept off-repository |
| Fix PRs opened during the run (drafts, none merged, none installed) | #26 (problem 129), #27 (problem 126, Forget peer), #28 (CI: Swift CodeQL limit), #29 (problem 131 investigation; no production change) |

## R2.2 Devices, environment and fixtures

- Device A: OnePlus Nord 5 (Android 16), ADR-010 **follower**. Device B: moto g57 power (Android 17),
  **leader**. Owner user 0 only; B's managed work profile was never touched (ADB has no access to it).
  A's OxygenOS app-clone user was never used.
- One shared Wi-Fi access point; built-in speaker, earpiece and microphone only; stationary, indoors.
- Permissions at the start of run 2: A `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `BLUETOOTH_CONNECT`
  granted; B the same plus `ACCESS_LOCAL_NETWORK`. No permission was changed in run 2 (the approved
  permission-denied rows were not reached).
- **Fixtures, all synthetic and generated on the test Mac** (never committed):
  - Seven 5-minute and two 36-minute AAC click tracks: a sharp click every 1.000 s, a per-track click
    pitch (1.6–3.2 kHz) and N low beeps every 10 s identifying track N. The two 36-minute tracks are
    43.6 MB each (I-10, S-09).
  - 1,050 unique one-second tracks (8.2 MB) for I-23/I-24.
  - Imported through the app's own *Import a folder* (SAF) from dedicated `Music/RideLinkTest` and
    `Music/RideLinkManifest` folders, each its own library source (ADR-005 A2); no personal file was
    moved or changed. A: tracks 01–04, 07, 08 and the 1,050 set; B: tracks 05–06.

## R2.3 Security and connection results

| ID | Run 2 status | Evidence |
|---|---|---|
| I-03 | **PASS (×6)** | Every relaunch of both apps reconnected silently (`known peer …, silent connect`), no code prompt, leader B |
| I-01 | **PASS, with a new failure mode** | Discovery itself was immediate every time. But after simultaneous app restarts, each phone's dial was refused **3–46 ms after `PeerSelected`** and never retried (problem 133); *Stop searching → Find* recovered |
| Problem 125 | **Reproduced twice more on `47d7cde`** | A (02:41:50Z drop, peer-dial reconnect after 14.1 s, `rejected ReconnectBudgetExhausted from CONNECTED` 138 s after the drop) and B (same signature 122 s after a peer-dial reconnect). On B this also removed the whole synchronised-playback section from the main screen until a clean restart (inference: the stale control state run 1 described) |
| Problem 128 family | **Observed again** | 02:15Z: B dialled A while A was `DISCONNECTED`; A authenticated it but its FSM dropped it; B showed *Connected* for 32 s; A's *Search again* then BYE'd B (`ENDING → IDLE` in 31 ms) |
| I-26 | **PASS (functional), partial** | End session on A: both `Not connected`, no service, no notification, no recorder. A second session in the **same processes** (PIDs unchanged) connected, the catalogue resynchronised (A 2 / B 1,133), leader unchanged, synchronised playback ran with `command_seq` restarting at 1 under session generation 2. Not checked: a new `AUDIO_STATE` `revision_epoch` and refusal of the old session's authority |
| I-05 | **Not closable on this build** | Ordinary drops recovered in 8.9–49 s; problem 125 armed after each peer-dial recovery |
| I-02, I-16, I-20, pairing interruption | **BLOCKED (126) → now F** | Forget peer is in draft PR #27, unreviewed |
| I-04, I-11, I-14, I-18, I-19, I-21 | **Not run** | Software or harness only (category C/F); I-21's clock change was not attempted on the phones |
| I-22 | Unchanged from run 1 | Two fresh adverts were seen from the Mac during run 2; TXT not re-captured |

## R2.4 Shared catalogue and file transfer

| ID / case | Status | Evidence |
|---|---|---|
| Catalogue exchange | **PASS** | B saw 82 → 83 of A's 83 → 84 tracks; A saw B's 2. One pre-existing personal track on A is never offered (as in run 1, 77 of 78) |
| UJ-05 / transfer A → B | **PASS** | Test 03 (4.9 MB) in ≈ 5 s; 01, 02, 04 *Downloaded*; **Test 07, 43.6 MB, *Downloaded*** (< ≈ 50 s) |
| Transfer B → A | **PASS** | Tests 05 and 06 *Downloaded* on A |
| Persistence | **PASS** | Received copies survived B's process death and later sessions |
| Storage model | By design (ADR-023) | Received tracks live in the transfer cache and play through *Play here* / synced playback; they do not change the library count |
| I-10 | **FAIL (200 ms criterion); functional PASS** | Pause from the follower, measured from A's wall clock read in the same shell call as the tap to each phone's `PAUSED` MediaSession timestamp: **baseline 518 / 642 / 644 ms** (B ≈ 375–555 ms, cross-device skew corrected, approximate). **During a 43.6 MB A → B transfer: A 1,913 ms, B ≈ 1.75 s.** Both still paused at the identical position (98,383 ms); the transfer completed while paused. Whether the extra ≈ 1.3 s is the app's control path or shared radio queueing is not established |
| I-23 | **PASS, except one sub-criterion** | A imported 1,050 synthetic tracks in 97 s (library 1,134). After the next connection B showed **1,133**, exactly A's offered count. `MAX_ENTRIES_PER_PAGE` is 256, so ≥ 5 pages were transferred and the `MANIFEST_END` digest check passed (a mismatch discards staging). "Every frame < 256 KiB" is **not observable** on a release build |
| Live catalogue update | Observation | While connected, B did not see A's newly imported tracks (still 83 after > 60 s); they appeared after the next connection. The protocol pulls the manifest on connect and has no change notice, so this is by design but surprising to a user |
| I-24 | **INCONCLUSIVE** | A was force-stopped 2.0, 3.0 and 4.0 s after tapping *Find*; B never displayed a partial count in any sample and the retry restored 1,133, but UI sampling (≈ 2.5 s) is too coarse to prove no partial catalogue was ever shown |
| I-11 | Not run | Needs a corrupting harness (F) |

## R2.5 Synchronised music

| ID | Status | Evidence |
|---|---|---|
| S-01 | **PASS (functional)** | B *Play on both phones* (17:33:57Z): both played, `command_seq` 1 on both; **user: "one clean click"** with the phones side by side. Problem 131 began immediately |
| S-02 | **PASS (functional)** | Follower intents (Resume, Next, Previous, Pause, Play) each became exactly one leader-stamped command, identical `last command_seq` on both |
| S-03 | **Functional PASS; alignment measured** | Fresh PLAY start: offset **0.75 ms on every click** (n = 15) |
| S-04 | **Functional PASS; alignment measured** | Pause landed at the **identical position** on both every time (47,456; 137,132; 183,916; 98,383 ms). Pause → resume recording: offset **1.75 ms** on all 11 clicks; both stopped and restarted together |
| S-05 | **Functional PASS; alignment measured** | Seek to 0:00: both at position 0, one command; recording: offset **≤ 0.5 ms** (n = 13) |
| S-06 | **PASS** | Next/Previous from each phone (`command_seq` 5–8): one command each, same track on both, no double skip |
| S-07 | **PASS (criterion), with an observation** | 4 trials of a pause on A and a seek on B fired concurrently: always exactly two consecutive `command_seq` and an identical end state. In 3 of 4 the end state was *paused at the running position*: the seek's effect was lost (the pause's stamped position overrides it) |
| S-08 | **PARTIAL** | Add from each phone and remove from the other: identical order and `queue_revision / size` (5 / 3) on both. **Move cannot be tested: the Android UI has no reorder control** |
| I-09 | **PASS** | 3 valid trials from a verified pause: both tapped Play together; two leader-stamped commands, one track, both playing, no double skip |
| Q-02 | **PARTIAL** | During synced playback A's library is read-only (*"Playing on both phones. Tracks here can't be played or queued on this phone alone…"*), *Add to Up Next* hidden, tapping a row changes nothing. The return of controls after *Play on this phone only* / End Ride was not checked |
| S-09, I-12, S-11, S-12, I-08 | **NOT RUN** | Need PR #24 installed; S-09/I-12 would currently only show problem 131's `SYNC_FAILED` |
| S-10 | NOT RUN | Route transition; needs a Bluetooth endpoint |

**Measurement method (S-03/S-04/S-05).**
- One microphone (the test Mac's built-in input) recorded both phones' loudspeakers, so both are
  timed on a single clock. A was ≈ 10 cm and B ≈ 40 cm from the microphone (placed by the user).
- Each click was fitted as `a·T(t) + b·T(t − d)`, where T is the averaged single-phone click envelope
  (2 kHz band, 0.25 ms bins); d is the inter-phone offset.
- **Validation:** synthetic mixes with known offsets of 2.00 and 5.00 ms were recovered exactly; a
  single phone alone reads ≤ 0.5 ms. Each phone recorded alone (the other muted through
  `cmd media_session volume`, then restored to its exact prior value) showed only the same room/filter
  artefact (+40 ms, 3 %) and the track's beep leakage, both excluded.
- **Limits:** resolution ≈ 0.5 ms; B's extra ≈ 30 cm path adds ≈ +0.87 ms, uncorrected in the figures;
  which phone leads is not identified; the Mac input has automatic gain, so levels are not used; a
  whole-second offset was ruled out with the 10-second beeps. This is the built-in loudspeaker path
  only: **no Bluetooth figure is implied.**

| Recording | Clicks | min | median | p95 | max |
|---|---|---|---|---|---|
| Steady state, 2 min into a track | 15 | 0.00 | 0.00 | 0.50 | 0.50 ms |
| Pause → resume | 11 | 1.75 | 1.75 | 1.75 | 1.75 ms |
| First 25 s after a synced start | 24 | 0.00 | 1.75 | 2.00 | 2.00 ms |
| Seek | 13 | 0.00 | 0.00 | 0.00 | 0.00 ms (≤ 0.5) |
| Fresh PLAY start | 15 | 0.00 | 0.75 | 0.75 | 0.75 ms |

In the "first 25 s" recording the **shared playback jumped +250.25 ms and then −15.5 ms**, on both
phones together (the offset was unchanged across both jumps): the drift controller's hard seeks,
audible as skips (problem 131).

## R2.6 Music and intercom coexistence

| ID | Status | Evidence |
|---|---|---|
| I-25 (built-in route) | **PASS (functional)** | Synced music on both, intercom on in *Always on · music lowered* (Mode A): **voice clear in both directions over the music, music kept playing (user-confirmed)**. Media: DTLS connected, SRTP `AES_CM_128_HMAC_SHA1_80`, `audio/opus 48000`, HOST/HOST, loss 5 of 3,290, jitter 5 ms, media RTT 9 ms. Route `BUILTIN_EARPIECE`, media quality `FULL`, coupling `INDEPENDENT`, so the duplex-profile/`reduced` half of I-25 needs Bluetooth (E). Peer `AUDIO_STATE` revision 4, mode `CONTINUOUS` |
| Mode A ducking | By design, misleading label | Gain 1000/1000 throughout and the music was not lowered (user-confirmed). ADR-027 A1 deliberately gives continuous transmission no duck, so *"Always on · music lowered"* does not lower music |
| PTT ducking | **PASS** | While A held Push-to-talk, B's music gain went **1000 → 250 within 4 s** and back to 1000 within 3 s of release (ramp revision 7 → 8 → 9) |
| Route transition | Observation | Every intercom start on both phones ended in `fallback ROUTE_TRANSITION_TIMEOUT` (≈ 5.01–5.02 s; confidence `ASSUMED`): the platform never reported the route settled. It did not block PTT ducking |
| Mute with music | **PASS (music unaffected)** | Music kept playing on both while A was muted. The recorder was stopped on mute (problem 127, below) |
| AF-01 | **PASS (two phones, music + intercom)** | `RideForegroundService` `types=0x82` (mediaPlayback \| microphone) on both |
| N-03 | **PASS, with problem 129** | Intercom and media notifications coexist on both. The notification's *End intercom* (pressed by the user on A) removed the service and both notifications and stopped the recorder, with the session still *Connected* |
| V-06 / problem 129 | **FAIL, 3 of 3** | Each in-app *End intercom* on A: screen *Intercom off*, but the service kept `0x82` and the notification still read *"Intercom on — Starting the microphone…"* at +10 s and +60 s |
| Problem 127 | **Reproduced twice** | Each mute on A logged `rec stop` for RideLink's `VOICE_COMMUNICATION` recorder (sessions 5801, 5809) |

## R2.7 Lifecycle, background and notifications

| ID | Status | Evidence |
|---|---|---|
| AF-02 | **PASS (two phones)** | During synced playback with the intercom off, both ran `types=0x2` only (`ridelink.music`), with no RideLink recorder, before and during a ride |
| AF-07 | **FAIL on the moto (Android 17)** | Mid-ride on B, the RideLink task was swiped from Recents (04:06:17Z): the task disappeared, but **46 s later the process, the `mediaPlayback` service, the notification, the music and the control session (A *Connected*) were all still running.** The required `onTaskRemoved → ENDING` did not happen (problem 135). The OnePlus kills the process instead (Phase 9A) |
| AF-08 | **PASS (approximation)** | Mid-ride on A, `am force-stop` (a release build allows no other kill): no restart for 35 s, no service or notification; on relaunch A showed *Not connected* and did not resume the ride |
| AF-07 incidental | Not a test | The user swiped B's RideLink from Recents at 02:07:35Z while B had no foreground service (exit-info `REMOVE TASK`, importance 400) |
| Background reconnect | **New problem 132** | After synced playback ended (no foreground service), A lost its link at ≈ 18:07Z and showed *Reconnecting…* for **≈ 8 h** with no logged event; `ReconnectBudgetExhausted` was logged ≈ 60 s after the app was opened (02:10Z). Inference: the frozen cached process suspended the ladder |
| Media metadata | Observation (problem 116 family) | On the receiving phone, a transferred track has a `null` MediaSession title and shows *"Untitled track"*; B's media notification kept showing a previous track's title while another track played |
| AF-03, AF-04, AF-05, AF-06, AF-09, N-04, V-08, V-09 | **NOT RUN** | Time and B's availability; AF-05/V-08 also need PR #24. The permission changes were approved but not reached |

## R2.8 Defects

| # | Severity | Summary | Status |
|---|---|---|---|
| **131** | **Medium–High** | The drift controller chases a common-mode output latency: both phones read −0.35 to −0.93 s while measured ≤ 2 ms apart, hard-seek (audible +250 ms skip), then `SYNC_FAILED` (≈ 15 s after every start, resume or auto-advance); Ride Mode shows *"Music sync paused"* | Investigation in draft PR #29: hard seeks cannot converge (each re-incurs `AudioTrack` restart lag), the position sample is up to 250 ms stale (both platforms), the leader propagates it, pre-roll is not awaited. No fix yet |
| **132** | **Low** | A session that loses its link while the app is backgrounded without a foreground service stays *Reconnecting…* indefinitely (8 h observed); the ladder only runs once the app is opened | Open; inference (process freezing) not established |
| **133** | **Medium** | After both apps restart, each phone's dial is refused within 3–46 ms of `PeerSelected` and is never retried (*"Couldn't reach the other phone yet"*); only *Stop searching → Find* recovers | Open; root cause not established (a stale resolved address is a hypothesis; the log does not record the address) |
| **134** | **Medium** | A follower's *Play on both phones* for a track in the **leader's own library** leaves the leader at `WAITING_FOR_CONTENT` (*"Waiting for the track to download…"*) with no command allocated, until the leader skips. Reproduced 2 of 2 (two sessions). The same track played normally when reached through *Next* | Open |
| **135** | **Medium** | AF-07 on Android 17: swiping RideLink from Recents mid-ride does not end the session or release the foreground service | Open; whether `onTaskRemoved` is not delivered or not handled is not established |
| 125, 127, 129 | as run 1 | Reproduced again (§R2.3, §R2.6) | Fixes: #24 (125), #26 (129); 127 open |

UX observations, not filed as problems:

- A phone can add only the **other** phone's tracks to the shared queue (its own library rows offer
  only *Add to Up Next*), and its own tracks then appear as *"Shared track"* in its queue view.
- There is no reorder control for the shared queue.
- *Always on · music lowered* never lowers music (ADR-027 A1 by design).
- The catalogue does not update while connected.
- A seek that races a pause is silently lost (S-07).

## R2.9 Human-confirmed observations

| Time (UTC) | Question | Answer |
|---|---|---|
| 19 Oct ≈ 17:35 | S-01, phones side by side: one click or a double click? | "One clean click" |
| 20 Oct ≈ 03:33 | I-25: voice over the music, each direction? | "Both directions clear" |
| 20 Oct ≈ 03:33 | Music loudness with the intercom on (Mode A)? | "Same loudness" |
| 20 Oct ≈ 03:59 | Pressed the notification's *End intercom* on A? | "Tapped End intercom" |
| 20 Oct | Who removed B's RideLink from Recents at 02:07Z? | The user |

## R2.10 Test conduct

- Security confirmations, permission grants and the notification's *End intercom* were never automated.
- **B was in use for other apps several times** (messaging, a video call, social media). Each time all
  input to B stopped; one screenshot taken while another app was in front was deleted unviewed.
  Several link losses coincided with these periods.
- **A mis-tap:** during an automated scroll-and-tap sequence on B's *Other phone's music* list, a tap
  landed on the Download button of **one personal (non-fixture) track** from A, which was copied into
  RideLink's private storage on B. The user chose removal; it was removed by uninstalling RideLink from
  B (§R2.12). Its name is not recorded. Taps on list rows are now title-checked before they fire.
- A parsing mistake briefly printed other apps' notification text into the operator's terminal; none
  of it was recorded, and the filter was corrected to RideLink's own records.
- Twice the Files app (opened by a diagnostics export) stayed in front of RideLink on A; it was closed
  with Back.
- Media volumes and the Mac's input gain were changed briefly for calibration and restored to their
  exact prior values (A 135/160, B 13/15, Mac input 51).
- Raw exports, recordings and dumps are kept off-repository. No serials, addresses, SSIDs, contact
  names, personal track names, codes, keys or secrets appear here.

## R2.11 Coverage audit (every two-phone row)

Category: **A** needs two physical Androids; **B** OnePlus alone later; **C** emulator/software;
**D** needs an iPhone; **E** needs Bluetooth or measurement equipment; **F** needs another implementation
change. Device B is no longer available, so every unfinished **A** row needs a second Android phone again.

| ID | Requirement | Run 1 | Run 2 | Remaining work | One phone? |
|---|---|---|---|---|---|
| I-01 | Discovery < 5 s | PASS | PASS (+ problem 133) | — | No |
| I-02 | First-time pairing | FAIL (historical); BLOCKED (126) | BLOCKED (126) | Review/merge #27, install, forget, pair (A + F) | No |
| I-03 | Trusted reconnect | PASS | PASS ×6 | — | No |
| I-05 | Short interruption | FAIL (125) | Not closable (125) | Install #24, PROC-125 (A + F) | No |
| I-06 | 3-min interruption, retry | PASS (app-kill) | — | Aeroplane variant after #24 (A) | No |
| I-07 | Hotspot topologies | NOT RUN | NOT RUN | Android hotspot (A); iPhone hotspot (D) | No |
| I-08 | 5-min clock sync | NOT RUN | NOT RUN | After #24; offset-log export (A + F) | No |
| I-09 | Simultaneous play | NOT RUN | **PASS** | — | No |
| I-10 | 40 MB transfer + PAUSE ≤ 200 ms | NOT RUN | **FAIL (200 ms)**; functional PASS | Fix/decide; precise latency (A + E) | No |
| I-11 | Corrupted chunk | NOT RUN | NOT RUN | Corrupting harness (C/F) | Yes (emulator) |
| I-12 | 30-min drift | NOT RUN | NOT RUN | #24 + problem 131 fix; script (A + E + F) | No |
| I-13 | Voice + music through Wi-Fi loss | NOT RUN | NOT RUN | After #24 (A) | No |
| I-15 | Simultaneous trusted connect | INCONCLUSIVE | Natural cases only | After #24 (A) | No |
| I-16 | Simultaneous first pairing | BLOCKED (126) | BLOCKED (126) | After #27 (A + F) | No |
| I-17 | 10 AP toggles | BLOCKED (125) | NOT RUN | After #24 (A) | No |
| I-21 | Clock forward → `certificate_invalid` | DEFERRED | NOT RUN | Emulator preferred (C) | Yes (emulator) |
| I-23 | 1,000+ manifest | NOT RUN | **PASS** (frame size unobservable) | — | No |
| I-24 | Interrupted manifest | NOT RUN | INCONCLUSIVE | Fine-grained observation (A, or C) | No |
| I-25 | Intercom over music | NOT RUN | **PASS (built-in)** | Bluetooth profile half (E) | No |
| I-26 | End Session + second ride | PARTIAL | **PASS (functional), partial** | `revision_epoch`, old-authority refusal (A) | No |
| V-01–V-05 | Voice basics | PASS | Re-observed | — | No |
| V-06 | End Voice | PARTIAL (129) | **FAIL 3/3 (129)** | Install #26, retest (A) | No |
| V-07 | Voice after Wi-Fi loss | INCONCLUSIVE | NOT RUN | After #24 (A) | No |
| V-08 | Voice while locked | INCONCLUSIVE | NOT RUN | A | No |
| V-09 | `RECORD_AUDIO` denied | NOT RUN | NOT RUN (approved) | A | Partly (AF-03 half on one phone) |
| V-10 | Topologies | NOT RUN | NOT RUN | A + D | No |
| V-11 | Mouth-to-ear latency | DEFERRED | DEFERRED | Equipment (E); the single-mic method could be adapted | No |
| S-01, S-02 | Synced start | NOT RUN | **PASS (functional)** | iPhone half (D) | No |
| S-03, S-04, S-05 | Alignment, measured | NOT RUN | **Measured ≤ 2 ms (speaker)** | Stereo-recorder cross-check (E) | No |
| S-06 | Next/Previous | NOT RUN | **PASS** | D half | No |
| S-07 | Concurrent controls | NOT RUN | **PASS** (seek-lost observation) | D half | No |
| S-08 | Queue replication | NOT RUN | **PARTIAL** (no move control) | Move needs UI (F) | No |
| S-09 | 30-min drift | NOT RUN | NOT RUN | #24 + 131 fix + script (A + E + F) | No |
| S-10 | Route transition | NOT RUN | NOT RUN | Bluetooth (E) | No |
| S-11 | 10-min locked synced play | NOT RUN | NOT RUN | After #24 (A) | No |
| S-12 | Wi-Fi break during play | NOT RUN | NOT RUN | After #24 (A) | No |
| Q-02 | Read-only during synced ride | NOT RUN | **PARTIAL** | Controls-return check (A) | No |
| N-03 | Intercom notification | PARTIAL | **PASS with 129** | — | No |
| N-04 | Notifications denied | NOT RUN | NOT RUN (approved) | A | Partly |
| AF-01 | FGS types with intercom | Partial | **PASS** (`0x82`) | — | No |
| AF-02 | Music-only FGS | NOT RUN | **PASS** (`0x2`) | — | No |
| AF-03 | Mic denied ride | NOT RUN | NOT RUN (approved) | A / B | Partly |
| AF-04 | Background-start refusal | Incidental | — | Harness (F) | Yes |
| AF-05 | 30-min locked ride | NOT RUN | NOT RUN | After #24 (A) | No |
| AF-06 | Notifications denied ride | NOT RUN | NOT RUN (approved) | A / B | Partly |
| AF-07 | Recents swipe mid-ride | NOT RUN | **FAIL on moto (135)** | Fix + retest on Android 17 (A + F) | Partly (OnePlus kills the process) |
| AF-08 | Process kill mid-ride | NOT RUN | **PASS (force-stop approx.)** | — | Yes |
| AF-09 | Intercom from background | Incidental | NOT RUN | A | Partly |
| AF-10 | Manifest | Phase 9A | — | — | Yes |
| A-02, A-04, A-06, A-07 | Audio hardware rows | Partial | — | Bluetooth/helmet (E); built-in A-04 after #24 | No |
| UJ-05 | Missing track flow | NOT RUN | **PASS** | — | No |
| PROC-124 | Problem 124 procedure | Trusted half PASS | — | SAS half after #27 (A + F) | No |
| PROC-125 | Problem 125 procedure | — | NOT RUN (#24 unmerged) | Install #24 (A) | No |
| PROC-101 | Problem 101 on device | NOT RUN | NOT RUN | A | Partly |
| PROC-102 | Device migration | NOT RUN | Skipped (destructive) | Needs a factory reset | No |
| V1-OFFLINE | Mobile data off | NOT RUN | NOT RUN | A | No |
| I-04, I-14, I-18, I-19, I-20 | Negative security | DEFERRED | NOT RUN | Harness/emulator (C/F); I-20 after #27 | Yes (emulator) |

## R2.12 End of run and state left behind

| | Device A (OnePlus) | Device B (moto) |
|---|---|---|
| RideLink | Installed, `47d7cde`, *Not connected* | **Uninstalled for the owner user** (user-approved) to remove the stray track; this also removed B's RideLink identity and its pin for A |
| Identity / pin for the other phone | Unchanged (`8eb849…`); **still holds a pin for B's old identity** | Removed with the app |
| Test files | `Music/RideLinkTest` (6 fixtures) and `Music/RideLinkManifest` (1,050 fixtures) remain, imported as two sources | `Music/RideLinkTest` deleted; 16 RideLink diagnostics exports in Download deleted (user-approved) |
| Services, recorder, notifications | None | None (app removed) |
| Work profile | — | Never accessed |

**Afterwards, at the user's request, the same `47d7cde` APK (`afafa4c1…2b65`, signer `51d88fc7…3ee2`)
was installed fresh on B for the owner user only (read-back hash identical; not yet launched; no
runtime permissions granted).** On first launch it will create a new identity with no pins.

Any future two-phone run with this reinstalled B will present a **new** identity to A, which A will refuse
as a pin mismatch until A forgets B. That needs the Forget peer control (PR #27); it is also the first
genuine opportunity for I-02.

## R2.13 Qualification decision

| Statement | Supported? |
|---|---|
| Discovery, trusted reconnect, mutual TLS + SPKI pinning | **Yes** (problems 125, 133 degrade recovery) |
| First-time pairing | **Not established** (126; historical FAIL stands) |
| Bidirectional intercom audible; continuous and PTT | **Yes** (run 1), and over synced music (run 2) |
| Mute disrupts nothing but voice | **No** for the capture device (problem 127); music unaffected |
| Voice start/stop cleans up | **No** (problem 129, 3 of 3) |
| Synchronised music works | **Functionally yes; alignment measured ≤ 2 ms on speakers**; but the drift controller declares `SYNC_FAILED` and skips audibly (problem 131), and a follower request for the leader's own track wedges (problem 134) |
| Queue consistency | **Yes** for add/remove; move untestable |
| Shared catalogue and bidirectional transfer; integrity | **Yes** (I-23 PASS; I-11 not run) |
| Control not blocked by bulk transfer | **No** (I-10: 1.9 s) |
| Music and intercom coexist | **Yes** on the built-in route (I-25, PTT ducking) |
| Screen-lock operation, long-duration stability | **Not established** (not run) |
| Foreground services and notifications | **Partly** (AF-01, AF-02, N-03 PASS; 129 and 135 FAIL) |
| Short/extended reconnect, stale budgets | **No** (problem 125, reproduced again) |
| Process death and second ride | **Yes** (AF-08 approximation, I-26 functional) |
| No open High-severity physical blocker | **No** (125 High; fix unmerged) |
| Every two-phone row accounted for | **Yes** (§R2.11) |

**ANDROID-ANDROID QUALIFICATION BLOCKED.**

## R2.14 Exact next steps

1. Review and merge #28 (CI), then let #24's Swift CodeQL produce a real result; review #24, #26, #27.
2. Fix problem 131 (start with PR #29's stale-position finding on both platforms), then 134, 135, 133.
3. When a second Android phone is available again: install the merged build in place on A and fresh on
   the second phone, use Forget peer on A, and run I-02/I-16; then PROC-125, S-11/S-12/I-13/V-07,
   AF-05/V-08, S-09/I-12 (after 131), I-17, the hotspot rows, and the permission rows.
