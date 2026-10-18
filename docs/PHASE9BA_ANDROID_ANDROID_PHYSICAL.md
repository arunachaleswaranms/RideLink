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
