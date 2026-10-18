# Phase 9B-A — Android ↔ Android physical integration, 18 October 2026

**Verdict: BLOCKED AT CHECKPOINT 1.** I-01 discovery passed on two physical Android phones. I-02
secure pairing **FAILED**, because the TLS 1.3 handshake cannot complete: Android Keystore refuses
the signature Conscrypt needs for the TLS `CertificateVerify` with `INCOMPATIBLE_DIGEST`. That is
an application defect, reproduced on both phones and recorded as STATUS §4 problem 122. Every later
two-phone row depends on an authenticated session and is **BLOCKED**, not failed. No application
code changed in this evidence run. No Android-to-iOS result, Phase 9 completion or V1 readiness is
claimed.

## Baseline

| Field | Verified value |
|---|---|
| Verified origin/main at start | `8f47a72fd7dc80d6985f72b0a9eb8bad0256cd1f` |
| Installed source revision (both phones) | `4104e81fa5ce9bd197d2d1b6769966e47be590d1` |
| Difference `4104e81..8f47a72` | Documentation and evidence files only (`docs/`, `docs/test-results/`); no `android/`, `ios/`, `protocol/` or `tools/` change, so the installed code is the code at `8f47a72` |
| APK | `ridelink-android-4104e81fa5ce.apk`, the existing signed sideload artifact (clean tree, built 2026-10-10T07:02:36Z, release, not debuggable) — **not rebuilt** for this run |
| APK SHA-256 | `7cdcfc29c945fbd461e98f57f31e141c29b34101b3d857c9df0a8e9f5b80fb75` |
| Signer certificate SHA-256 | `51d88fc76947c7027fa8fad18a77961c4d1e5c14e950c70d6888ada9240b3ee2` |
| Package / version | `com.ridelink.app`, `0.1.0` (1), minSdk 31, targetSdk 36 |

The user chose to install the existing `4104e81` artifact on Device B rather than rebuild at
`8f47a72`, because the two revisions differ only in documentation. The in-app diagnostics export
therefore reports `source_revision` `4104e81`; that is expected, not a provenance gap.

## Devices

`adb devices -l` listed four transports. Two of them (one `ip:port`, one mDNS `adb-tls-connect`)
reported the same `ro.serialno`, so they are **one** physical phone; one was an emulator, which was
never used. Every command used an explicit `-s` selector. Serials and LAN addresses are not
recorded.

| | Device A | Device B |
|---|---|---|
| Model | OnePlus Nord 5 (CPH2707) | Motorola moto g57 power |
| Android / API | 16 / 36 | 17 / 37 |
| Build fingerprint | `OnePlus/CPH2707IN/OP6131L1:16/UKQ1.231108.001/V.R4T2.25db0a1-faf42-faf4b:user/release-keys` | `motorola/mumba_gp/mumba:17/A171WAAH.31-9-4/64c052-221d7:user/release-keys` |
| Physical | Yes (`ro.boot.qemu` empty) | Yes (`ro.boot.qemu` empty) |
| Users | Owner only | Owner + a managed work profile. RideLink is installed for the owner user only |
| RideLink before this run | Installed: `4104e81` (in-place update of 2026-10-10, Phase 9A.5) | **Not installed** |
| RideLink after this run | Unchanged | First install of the same artifact (user-approved) |

### Installation and read-back

| Device | Action | Installed-APK read-back |
|---|---|---|
| A | None. The installed `base.apk` was pulled back and hashed | SHA-256 `7cdcfc29…fb75`; `apksigner verify` signer `51d88fc7…3ee2` — identical to the provenance record |
| B | `tools/sideload/android.sh install -s <B> …4104e81fa5ce.apk`, first install, 2026-10-18T08:41:38Z, no `-g` | The helper pulled the installed `base.apk` back and required exact SHA-256 equality: `7cdcfc29…fb75`. `versionName=0.1.0`, `versionCode=1` |

No uninstall, data clear, key regeneration or trust change was made on either phone.

## Network topology

| Check | Result |
|---|---|
| Same Wi-Fi | Yes. Both phones' associated SSID **and** BSSID hash to the same value (values not recorded) — the same access point, 802.11n |
| Addressing | Both on one private IPv4 /24 (addresses not recorded) |
| Phone ↔ phone ICMP | A→B 4/4, B→A 4/4 |
| Phone ↔ phone TCP | A dialled a `nc` listener on B; B received the probe string. No client isolation |
| Multicast / mDNS | Proven by I-01 below (each app's `NsdManager` resolved the other) |
| ADB port forwarding | Not used. RideLink's traffic went phone to phone over the access point |

## Results

| ID | Status | Observed |
|---|---|---|
| I-01 Discovery | **PASS** | B was already searching. Tapping **Find other phone** on A, both screens showed *Other phone found. Connecting…* at the first UI poll, **0.6 s** after A's tap — within the 5 s target, in both directions. Diagnostics: *Discovered peers (current)* = 1 on each phone, so no duplicate or stale entry. A second discovery cycle (Stop searching → Find other phone on both) found the peer again immediately. No cloud or backend involved |
| I-02 Secure pairing | **FAIL** | Both phones dialled each other (A→B and B→A, observed as `ESTABLISHED` TCP sockets owned by RideLink), and both connections closed within a second. No six-digit code was ever shown. After 15 s the UI's stall label read *Couldn't reach the other phone yet*; *Control state* stayed `Idle`, *Peer identity* `—`. Root cause below |
| I-03 Trusted reconnect | **BLOCKED** | Needs I-02 |
| I-26 End Ride and restart | **BLOCKED** | Needs an authenticated session |
| V-01, V-02, V-04, V-05, V-06 Intercom | **BLOCKED** | Needs an authenticated session |
| Intercom modes (continuous, PTT, coexistence, mute) | **BLOCKED** | Needs an authenticated session. VOX remains PENDING REAL AUDIO INPUT regardless |
| S-01, S-02, S-06, S-08, Q-02 Synchronized playback | **BLOCKED** | Needs an authenticated session |
| Shared catalogue and file transfer | **BLOCKED** | Needs an authenticated session |
| N-03 Live intercom notification | **BLOCKED** | Needs a live intercom |
| N-04 Notification permission | **NOT RUN** | Checkpoint order stopped at Checkpoint 1 |
| Background / lock-screen / AF-series two-phone checks | **BLOCKED** | Needs an authenticated session |
| I-05, I-06, V-07, S-12 Reconnection and resilience | **BLOCKED** | Needs an authenticated session |
| S-03 and other measured alignment gates | **NOT RUN** | No synchronized playback was possible; no alignment figure exists |

No user audible observation was requested or recorded, because no voice or synchronized-playback
session could be established.

## I-02 root cause — Keystore refuses Conscrypt's TLS signature

### What the evidence shows

1. **The failure is in the TLS handshake, on the side presenting the RideLink identity.** A plain
   TLS 1.3 client (OpenSSL 4.0.3 on the test Mac, no client certificate) was pointed at each
   phone's RideLink listener. Both listeners answered the ClientHello with a HelloRetryRequest,
   accepted the second ClientHello, sent a ChangeCipherSpec, and closed the connection — at
   exactly the point where the server must sign its `CertificateVerify` with the identity key.
   The probe is not a RideLink peer and was rejected as one; it changed no trust state.
2. **Android Keystore refused the signing operation.** At the time of every attempt — the two
   phone-to-phone discovery cycles and the Mac probes — each phone's `keystore2` logged
   `Failed to begin operation … Error::Km(INCOMPATIBLE_DIGEST)`. Device B (Android 17) attributes
   the error to RideLink's own app uid and the app's `System.err` carries the stack:

   ```
   android.security.KeyStoreException: Incompatible digest (internal Keystore code: -13)
     at android.security.keystore2.AndroidKeyStoreSignatureSpiBase.ensureKeystoreOperationInitialized
     at java.security.Signature.initSign
     at com.android.org.conscrypt.CryptoUpcalls.signDigestWithPrivateKey(CryptoUpcalls.java:82)
     at com.android.org.conscrypt.CryptoUpcalls.ecSignDigestWithPrivateKey(CryptoUpcalls.java:70)
     at com.android.org.conscrypt.NativeCrypto.ENGINE_SSL_read_direct(Native Method)
     …
     at com.android.org.conscrypt.ConscryptEngineSocket.startHandshake
     at com.ridelink.network.security.TlsControlChannel.finishHandshake(TlsControlChannel.kt:234)
     at com.ridelink.network.security.TlsControlChannel$accept$2.invokeSuspend(TlsControlChannel.kt:196)
   ```

   Device A (Android 16) logs the same `keystore2` error without a uid. Its timestamps match A's
   attempts to the second (14:19:05 and 14:23:42 local, the two discovery cycles; 14:25:04, the
   Mac probe of A's listener). Attribution on A is by that timing correlation plus the identical
   mechanism; it is not attributed by uid.
3. **Cause.** For an Android Keystore EC key, Conscrypt computes the TLS 1.3 handshake hash itself
   and calls back into Java to sign the **digest**, using `NONEwithECDSA`
   (`CryptoUpcalls.ecSignDigestWithPrivateKey`). RideLink's identity key is generated with
   `KeyGenParameterSpec.setDigests(KeyProperties.DIGEST_SHA256)` only
   (`AndroidKeystoreIdentityStore.kt`, as specified by ADR-017 §1), so Keystore refuses a
   `NONE`-digest operation. Certificate issuance (`SHA256withECDSA`) is unaffected, which is why
   the identity is created successfully on first launch and the failure appears only when two
   phones try to authenticate.
4. **Not environmental.** Wi-Fi, mDNS, TCP reachability and clock agreement (within about one
   second, irrelevant to the certificate's 24-hour back-dated `notBefore`) were all verified. The
   two phones run different Android versions (16 and 17) and fail identically.

### Why earlier testing did not find it

The JVM unit tests run TLS over Conscrypt-OpenJDK with **software** keys, which have no digest
authorization. No test had ever performed a TLS handshake with an Android Keystore key, and Phase 9A
never authenticated a peer: its emulator peer was not routable over mDNS. This run is the first
time a RideLink handshake was attempted with a Keystore-backed identity.

### Severity

**Critical / release-blocking (Android).** No Android build at this source can complete a RideLink
TLS handshake, as client or as server. Every two-device feature depends on it. Fail-closed: no
unauthenticated session was reached, and no security property was weakened. The iPhone signs
through `SecKeyCreateSignature`, a different mechanism, and has not been tested on hardware.

### Proposed fix (separate draft PR, not part of this evidence)

Authorise `DIGEST_NONE` alongside `DIGEST_SHA256` on new identity keys. Keystore cannot add an
authorization to an existing key, so the identity keys already on both phones cannot be repaired
in place. Recovering them means an explicit identity replacement (new SPKI, fresh SAS), which the
user has **not** authorised on these phones pending independent review.

## Test conduct notes

- **An off-app tap.** During setup, one scripted tap was sent to Device A after the user had
  switched it to another app. Its text was not retained or recorded. From then on, every scripted
  tap first checked that RideLink was the focused window, and aborted otherwise.
- **Raw logs are private.** Full `logcat` buffers from both phones were preserved off-repository,
  because they contain unrelated personal and application data. Only the excerpt above is published.
- **Nothing published that could identify the phones or their owners.** No ADB serials, LAN
  addresses, SSIDs, account names, music names, pairing codes, keys or TLS secrets are published.
- **Phones left as found.** Both phones were left at *Not connected*, with RideLink and its
  identity keys intact.

## Exact next step

1. Independent review of the fix PR (`DIGEST_NONE` for new keys, detection of keys created
   without it, explicit identity replacement, and a Keystore-backed instrumented handshake test).
2. After review, and only with the user's explicit authorisation, replace each phone's
   incompatible identity through the reviewed flow. No trusted pin exists between these two
   phones yet, so no existing pairing is invalidated.
3. Re-run Checkpoint 1 from I-01, then continue through Checkpoints 2–6 in order.

---

# Continuation — post-fix physical retest, 18 October 2026 (evening)

The section above is the original run and is left as it was. **Its I-02 FAIL stands.** This
section records a separate retest after problem 122's fix merged (PR #21).

**Verdict: problem 122's fix is verified on both phones; I-02 FAILS again, for two new reasons.**

What worked:

- Both phones replaced their identity only after the user confirmed it in the app.
- The two phones then completed a mutually authenticated TLS 1.3 handshake with Android Keystore
  keys, with no `INCOMPATIBLE_DIGEST`.
- Both showed the six-digit code. The user confirmed the codes matched, and each phone pinned the
  other's correct new SPKI.

Why I-02 still fails: Device A's session never reached `CONNECTED`. Two new defects caused it:

- **Problem 123:** an unhandled socket-write exception crashed Device B's app.
- **Problem 124:** Device A's session state machine was left in `DISCOVERING`, on top of a live,
  authenticated control session.

Every later row is **BLOCKED**. No application code changed in this run; the fixes are a separate
draft PR. No Android-to-iOS, Phase 9 or V1 readiness is claimed.

## Baseline

| Field | Verified value |
|---|---|
| origin/main | `2ec06efcd447bc38e6e52543e8ba6bb2d38bd85c` (PR #21 merge). Its tree `dc1709df…` is identical to PR #21's reviewed head `c8fe109` |
| Build | `tools/sideload/android.sh build`, run from a clean isolated worktree at that commit, with the existing sideload key (no new key). Build-tools 36.0.0, JDK 21.0.12.1, built 2026-10-18T15:07:54Z |
| APK | `ridelink-android-2ec06efcd447.apk`; release, not debuggable; APK Signature Scheme v3; one signer; `zipalign -c -P 16 4` OK; minSdk 31, targetSdk 36 |
| APK SHA-256 | `ff0cfdf080cafbfae9c408cd7eaeb8386105ac642b859d162549bd6aa94871cc` (new; not the old `7cdcfc29…`) |
| Signer certificate SHA-256 | `51d88fc76947c7027fa8fad18a77961c4d1e5c14e950c70d6888ada9240b3ee2` (the same key, so an in-place update is possible) |
| Revision stamp | The commit string is compiled into the dex. Both phones' in-app diagnostics exports report `source_revision: 2ec06efcd447bc38e6e52543e8ba6bb2d38bd85c` |

### CI on the merged revision

| Check | Result |
|---|---|
| CI | Success |
| Security: CodeQL java-kotlin, gitleaks, gradle-wrapper-validation, local-only-policy | Success |
| Security: dependency-review | Skipped (it runs only on pull requests) |
| Security: CodeQL swift | **Not passed.** It exceeded the job's 45-minute `timeout-minutes` on the original run, and again on a rerun (attempt 2). GitHub reports both as *cancelled*, and they are not counted as passes. The PR #21 run of the same job finished in 42 minutes, so the limit is marginal on the macOS runners. Raising it is a workflow change and is not made here |

## Devices and installation

`adb devices -l` listed three transports: one per phone, plus an emulator, which was never used.
Every command used an explicit `-s` selector, and every RideLink operation was for user 0. Serials,
addresses and SSIDs are not recorded.

| | Device A | Device B |
|---|---|---|
| Model / Android / API | OnePlus Nord 5 (CPH2707) / 16 / 36 | Motorola moto g57 power / 17 / 37 |
| Physical | Yes (`ro.boot.qemu` empty) | Yes |
| Before | `4104e81` build. The installed `base.apk` read back as `7cdcfc29…fb75`, signer `51d88fc7…3ee2` | The same |
| Install (user-approved, in-place update; no uninstall, no `--clean`, no data clear) | `tools/sideload/android.sh install -s <A> …`, 2026-10-18T15:12:55Z | `adb install -r --user 0 …`, 2026-10-18T15:15:50Z. The user chose `--user 0` so that the managed work profile could not be included. The script's read-back check was then repeated by hand |
| Installed-APK read-back | SHA-256 `ff0cfdf0…71cc`, identical to the build; signer `51d88fc7…3ee2` | The same |
| App data kept | Data-directory inodes unchanged across the update (CE and DE) | The same |
| Other users | Dual-app user: RideLink still not installed | Work profile: RideLink still not installed; the profile was not accessed |
| Runtime permissions | Unchanged across the update (diffed) | Unchanged (diffed) |

### Network

- Both phones were associated with the same SSID. It was compared by hash and not recorded.
- Both were on one private /24.
- ICMP worked in both directions: 3/3 each way. RTT was 100–700 ms, consistent with Wi-Fi power
  saving.

## Checkpoint D — explicit identity replacement

| Check | Device A | Device B |
|---|---|---|
| On launch, the app showed *"This phone's secure identity needs replacing"* | Yes | Yes |
| The screen says music is unavailable until the user decides | Yes | Yes |
| While waiting: RideLink-owned TCP sockets (read from `/proc/net/tcp{,6}` by app uid; the tables were readable) | 0. No listener, no discovery | 0 |
| Legacy identity (shown under *Show identity details*) | `spki:e6a9d2…` | `spki:8ac575…` |
| The user approved the replacement in the session, then confirmed it **by hand** in the app's dialog. Nothing was automated | Done | Done |
| After the replacement: normal main screen, no crash, no ANR, no Keystore error from RideLink | Yes | Yes |
| New identity (*This device*, diagnostics card) | `spki:8eb849…`, which differs from the legacy one | `spki:5e38e3…`, which differs from the legacy one |
| Library after the replacement | 78 tracks. This equals the last recorded count (Phase 9A.5 evidence: `library_count_after_retry: 78`) | 0 tracks. Expected: first installed on 18 October and nothing has been imported |

What could and could not be observed on the phones:

- **Directly observed:** the new key's TLS capability. It is shown by the successful Keystore-backed
  handshake below, with no `INCOMPATIBLE_DIGEST` logged on either phone.
- **Not observable on a release build:** the presence of the preserved `ridelink.identity.v1` entry,
  and the key's exact digest list and non-exportability. The build is not debuggable, so there is no
  `run-as` and no Keystore listing. These rest on the reviewed code path:
  `replaceIncompatibleIdentity` writes only `v2` and never deletes the legacy alias.
  `KeystoreTlsIdentityTest` (8/8 on both of these phones, TEST_PLAN §4) checks them on real
  Keystore keys.
- **Trust store:** nothing was written to it at replacement time. The only trust records written in
  this run are the pairing pins below.

## Results

| ID | Status | Observed |
|---|---|---|
| I-01 Discovery | **PASS** | B: *Find other phone*; about 6 s later, A: *Find other phone*. At the first poll after A's tap (≤ 3.0 s; the poll interval was about 2.7 s), both phones were already on *Check the code*. *Discovered peers (current)* = 1 on each, so no duplicate entry. B's dial reached A's listener. No backend was involved |
| I-02 Secure pairing | **FAIL** | The TLS 1.3 handshake succeeded with Keystore keys, the code was shown on both phones, the user confirmed the codes matched, and each phone pinned the other's new SPKI. **But A's session never reached `CONNECTED`** (problem 124), after B's app crashed (problem 123). Details below |
| Problem 122 fix (Keystore TLS signing) | **PASS (physical)** | Mutual TLS 1.3 handshake, `TLS_AES_128_GCM_SHA256` on both; no `INCOMPATIBLE_DIGEST`. Cross-consistent identities: A sees peer `5e38e3…`, which is B's own; B sees `8eb849…`, which is A's own. Leader agreement: B `true`, A `false`. B's `peer_id` prefix is the smaller of the two, as ADR-010 requires. Clock sync was symmetric (offsets +2306109.957 s and −2306109.954 s; the two phones' monotonic uptimes differ). Exactly one TCP connection existed between the phones, B→A, plus one listener on each |
| I-03, I-26 | **BLOCKED** | Needs I-02 |
| V-01, V-02, V-04, V-05, V-06; intercom modes, including PTT | **BLOCKED** | Needs an authenticated session on both phones. VOX is PENDING REAL AUDIO INPUT regardless |
| S-01, S-02, S-06, S-08, Q-02, S-11 | **BLOCKED** | |
| Shared catalogue and file transfer | **BLOCKED** | |
| N-03, N-04; AF-series two-phone checks | **BLOCKED** | |
| I-05, I-06, V-07, S-12, repeated I-26 | **BLOCKED** | Problem 123 would make these crash-prone even once I-02 passes |
| S-03 and other measured alignment gates | **NOT RUN** | No alignment figure exists |

The user's only observation was that the codes on the two screens matched. No digits were read,
logged or recorded: the harness redacted any all-digit value of six digits or more in the pipe
before writing anything.

## What happened (times UTC, cross-checked against each phone's event log)

| Time | Device A | Device B |
|---|---|---|
| 15:25:21 | | *Find other phone* (`IDLE → DISCOVERING → PAIRING`, first process) |
| 15:25:27 | *Find other phone*: `IDLE → DISCOVERING → PAIRING (PeerSelected)`; *pairing required with peer:40be2f…* | |
| ≤ 15:25:30 | Code shown; peer labelled *Nearby phone* | Code shown; peer labelled with A's model |
| — | The code prompt waited unanswered. The user was not yet at the phones | |
| 15:35:27 | **Screen off**, exactly 600 s after the last interaction (the phone's screen timeout). The activity stops, and RideLink has no foreground service before a ride | |
| 15:35:31 | | **App crashes:** `FATAL EXCEPTION: main … sun.net.ConnectionResetException: Connection reset … ControlSocket$writeFrame (Framing.kt:84)`, in a `StandaloneCoroutine` on `Dispatchers.Main`; `am_crash`, then `am_proc_died` |
| 15:35:32 | `InetDiagMessage: Destroyed live tcp sockets for uids={RideLink}`, issued by the system. This is about 1 s after B's crash timestamp, within the inter-phone clock skew: the reset B received is this destruction. Then `PAIRING → DISCOVERING (PairingRejectedOrTimeout)` | |
| 15:35:57 | OEM freezer freezes RideLink (screen off) | |
| 16:04:13 | User unlocks A; RideLink is unfrozen. It shows *Searching…* | |
| 16:04:36 | | The user relaunches the app (new process): `IDLE → DISCOVERING → PAIRING`; dials A |
| 16:04:4x | *pairing required with peer:40be2f…* on B's **inbound** connection, while A's state machine is in `DISCOVERING` | *pairing required with peer:59f1b6…* |
| 16:04:49 | User confirms a match on both. A logs *paired with peer:40be2f… (spki:5e38e3…)* and *voice subsystem attached (offerer=false)*, and **no state transition** | *paired with peer:59f1b6… (spki:8eb849…)*; `PAIRING → CONNECTING (PairingSucceeded)`; `CONNECTING → CONNECTED` |
| 16:05–16:09 | Stays at *Searching… / Couldn't reach the other phone yet*. The diagnostics card says *Control state: Connected* | Connected; *Start ride* and intercom controls shown |

The 10-minute wait before the first confirmation was a test-conduct artefact. It is why A's screen
timed out. But the protocol deliberately has **no pairing timeout** (PROTOCOL §4.5), and a phone
going to sleep or losing its network mid-pairing is an ordinary event. So both failures below are
application defects, not environmental ones.

## Problem 123 — a control-socket write exception crashes the app (Critical)

### Code path

- `ControlSessionManager.readLoop` → `handleFrame` → `"PING"` → `handlePing` writes the `PONG` with
  `socket.writeFrame(…)` and catches nothing (`ControlSessionManager.kt:1044`).
- The read loop runs in a `launch` on the application scope,
  `CoroutineScope(SupervisorJob() + Dispatchers.Main)` (`AppContainer.kt:106`), which has no
  exception handler.
- So an `IOException` from that one write escapes to the main thread's uncaught-exception handler
  and kills the process.

### Why this write is the one

- Every other control write site is wrapped in `runCatching`, and `readFrame` maps `IOException` to
  `ConnectionClosed`.
- `PING` is answered before authentication as well as after.

### Who can trigger it

- Any loss of the peer's connection between reading a `PING` and writing its `PONG` can crash the
  app. That includes a peer's screen-off socket destruction, Wi-Fi loss and aeroplane mode, so
  I-05/V-07/S-12 would be crash-prone.
- As a class, any device on the same network that can reach the pre-pairing `PING` exchange can
  crash RideLink remotely (denial of service).
- **No exploit procedure is recorded.**

## Problem 124 — a pairing that completes on a surviving connection is dropped in `DISCOVERING` (High)

### How it happened

1. A's `PAIRING` attempt ended with the link loss above.
2. The FSM returned to `DISCOVERING`.
3. A then kept advertising, accepted B's later dial, and ran the pairing on that **inbound**
   connection.

### What the gate did

- `SessionCoordinator` passes control events through `SessionGate.sessionEventFor(event, status)`.
- In `DISCOVERING`, `PairingSucceeded`, `PeerTrusted` and `Connected` all map to `null`, so the FSM
  never moves. Nothing is logged for them either.
- Their side effects still ran: the trust pin was written, voice was attached and the clock sync
  started.
- The result: an authenticated control session the session layer does not know about, with no way
  forward in the UI.

### What the vectors say

`protocol/vectors/session-gate/` pins those three rows to `null`. So the shared vectors currently
treat this drop as correct, and the fix is a state-machine change under rule 15 / ADR-019.

A's own dial also latches `connectAttempted`, so A never dials again by itself. Other routes into
the same state exist (a losing duplicate dial, or a refused redundant handshake, while another
connection is pairing). None is covered by a test that runs the real FSM with an inbound pairing.

### Security

The trust gate held: no peer reached `CONNECTED` without both users' confirmation and a written pin.
The defect is liveness and consistency, not authentication.

## Test conduct notes

- Every scripted tap first checked that RideLink was the focused window. No tap touched a security
  confirmation: the replacement dialog and *They match* were the user's own presses.
- Taps were used only for navigation: *Find other phone*, disclosures and scrolling.
- The diagnostics exports were made from the phones' own share sheets into Downloads and pulled
  over ADB. Their event lines are redacted by construction.
- Raw `logcat` buffers and the exports are kept off-repository.
- Phones left as found:
  - A: RideLink open at *Searching…*, with its new identity and a pin for B.
  - B: *Connected*, with its new identity and a pin for A.
  - Nothing was uninstalled, cleared or deleted, and no legacy key was touched.

## Exact next step

1. A separate fix PR, for independent review:
   - **123:** a failed `PONG` write must end the connection, never crash the process.
   - **124:** a pairing (or trusted connect) that completes while the FSM is in `DISCOVERING` must
     reach `CONNECTED` through the trust gate. This needs an ADR-019 amendment and session-gate
     vector changes.
   - Both need regression tests on the real coordinator.
2. Install the reviewed build as an in-place update.
3. Because both phones now hold pins for each other, the next run starts at I-03 (trusted
   reconnect, which also proves A's pin). A first-time I-02 needs the user's authorisation to
   *Forget* the pairing on both phones.
4. Then continue through Checkpoints F–K in order.
