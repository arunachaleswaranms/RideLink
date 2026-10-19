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
