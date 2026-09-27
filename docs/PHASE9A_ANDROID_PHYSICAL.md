# Phase 9A — Android field readiness and physical qualification (OnePlus Nord 5)

**Status, 27 September 2026: STAGE 1 CHECKPOINT. No qualification row has been run.** Both Phase 9
prerequisites ([PHASE9_READINESS.md](PHASE9_READINESS.md) §6 items 1–2) were missing at the baseline,
so they were implemented first on branch `phase9a/field-readiness` (ADR-029 Amendment A2). Formal
qualification must run against the **reviewed and merged** build, never against the unreviewed
prerequisite commit. This file records what was observed and nothing more.

Phase 9A is Android-only. It cannot close any iPhone or two-device gate. Every such row below is
**DEFERRED — REQUIRES PHYSICAL IPHONE** and belongs to Phase 9B.

Result labels: `PASS — PHYSICAL ONEPLUS NORD 5` · `FAIL — REPRODUCED` ·
`DEFERRED — REQUIRES PHYSICAL IPHONE` · `DEFERRED — HELMET HARDWARE NOT AVAILABLE` · `NOT APPLICABLE` ·
`SUPPLEMENTARY — PHYSICAL ANDROID + SOFTWARE PEER` · `PENDING` (not yet run).

## 1. Baseline (Stage 0)

| Item | Value |
|---|---|
| Baseline `main` | `ac7303d5f55f4a416004e9b2413233c47f2ac793` (PR #13 merged); working tree clean; matched the required baseline |
| Recorded | 2026-09-27 12:03 UTC |
| Host | Apple Silicon macOS (Darwin 25.6.0) |
| JDK used for Gradle | OpenJDK 21.0.12.1 (the machine default is Temurin 25; see CLAUDE.md) |
| Android SDK | platform `android-36`, build-tools 36.0.0 |
| adb | 1.0.41, platform-tools 37.0.1-15733141 |
| Xcode | 27 (iOS build only; no iPhone) |

## 2. Physical device (Stage 2)

| Property | Value |
|---|---|
| `ro.product.manufacturer` | OnePlus |
| `ro.product.model` | CPH2707 (OnePlus Nord 5) |
| `ro.build.version.release` | 16 |
| `ro.build.version.sdk` | 36 |
| `ro.build.fingerprint` | `OnePlus/CPH2707IN/OP6131L1:16/UKQ1.231108.001/V.R4T2.25db0a1-faf42-faf4b:user/release-keys` |
| `ro.boot.qemu` | empty (not an emulator) |
| Connection | wireless debugging |
| adb transports | two, **one phone**: a wireless `ip:port` transport and an mDNS `adb-<serial>-…._adb-tls-connect._tcp` transport. Both report the same `ro.serialno`. No emulator attached |
| Serial used | the wireless `ip:port` transport, with an explicit `-s` on every command. The hardware serial and LAN address are deliberately **not recorded**: they are persistent or personal identifiers, and nothing in the evidence depends on them |
| RideLink before this phase | **not installed** |

## 3. Prerequisites (Stage 1)

| Prerequisite | State |
|---|---|
| NFR-08 diagnostics export | Implemented on Android and iOS, pending independent review. [SIDELOAD.md](SIDELOAD.md) § Diagnostics export; TEST_PLAN §3.1h |
| Android sideload procedure | `tools/sideload/android.sh`, pending independent review. [SIDELOAD.md](SIDELOAD.md) § Android |
| iOS personal-team procedure | Documented. **NOT EXECUTED — REQUIRES PHYSICAL IPHONE** |
| Problem 104 (music focus / becoming-noisy) | **Unchanged, deliberately.** Record the behaviour stationary first (Stage 13); decide by ADR or mark it a known limitation before any moving ride |

### 3.1 Procedure validation — pre-review, not a qualification result

This was run on the unreviewed prerequisite commit **only to show that the procedure is repeatable on
the real phone**. It is not DX-01 and it closes nothing. RideLink was uninstalled afterwards, so
the formal Stage 3 starts from a genuine clean install.

- `keystore`: created the key outside the repository (directory mode 700, file mode 600; password
  in the login Keychain). A second run **refused** to replace it. The first attempt failed silently
  on a `pipefail`/SIGPIPE bug in the script; that was found and fixed (commit `5250042`) before any
  build was made. The same review then found the `ERR` trap meant to name a failing line never fired
  inside functions without `set -E`; that is fixed too.
- `build` refused a tree containing one untracked file. From the clean commit
  `5250042b39afde8cf6e6e6d99125d3e2a568baec` it produced:
  - APK SHA-256 `c624fef8bf346aeeb9d51b9cc41b03d33367036bea66df5cee99d4639ad36d87`
  - signer certificate SHA-256 `51d88fc76947c7027fa8fad18a77961c4d1e5c14e950c70d6888ada9240b3ee2`
  - `versionName` 0.1.0, `versionCode` 1, release (not debuggable)

  The full revision string is present in `classes3.dex`. `verify` reproduced both digests.
- `install` without `-s` was **refused**. With an explicit `-s` it installed (first install,
  2026-09-27 12:27 UTC), pulled the installed `base.apk` back, and found it **byte-identical**.
- On the phone: cold launch 678 ms. The Phase 8.5 main screen and the new Diagnostics log card
  rendered. **Export diagnostics log** opened the system share sheet with one file,
  `ridelink-diagnostics.txt`, 374 B (header only: no session had started, so there were no events).
  Cancelling with Back returned to RideLink in the same process. `adb shell content read` of the
  provider URI was refused ("not exported from UID 10339"). No share target was chosen. The chooser's
  screenshot was deleted unrecorded, because its direct-share row shows the owner's personal contacts.
- Not observed on the phone: the exported file's *content*. Reading it needs the user to pick a
  target. Content is pinned by the unit tests; DX-01 covers the phone.

## 4. Settings baseline (Stage 4) — PENDING

Record before changing anything: notification permission, microphone permission, battery
optimisation, background activity, Wi-Fi, mobile data, Bluetooth, screen timeout, and lock-screen
configuration. Record any OEM change as "works by default" versus "works only after user
configuration".

## 5. Qualification matrix — all PENDING until the prerequisite PR is merged

| Stage | Row | Result |
|---|---|---|
| 3 | Install one known build (clean), with a provenance record | PENDING |
| 5 | Basic physical smoke (UI, recreate, background, Recents, screen off/on, library, search, sort, duplicate queue items, exact removal, local transport) | PENDING |
| 6 | LM-A … LM-E (local music, background, screen off, lock-screen controls, Recents swipe) | PENDING |
| 7 | AF-01, AF-02, AF-05 (real lock screen), AF-06, AF-07 | PENDING |
| 7 | AF-04 (background start through a debug-only broadcast receiver) | **Not available in the repository.** No `debug` source set or receiver exists (TEST_PLAN §4.1 names one). Building it would be new scope, so record it NOT APPLICABLE unless it is added under review |
| 8 | Problem 101 (End intercom + recreate in the release window) | PENDING |
| 9 | Problem 108 (restart in the old release window) | PENDING |
| 10 | V-09 Android half (`RECORD_AUDIO` denied) | PENDING |
| 11 | Notification actions (Mute, End Intercom), lock screen, background | PENDING |
| 12 | Helmet Bluetooth unit | PENDING, or DEFERRED — HELMET HARDWARE NOT AVAILABLE |
| 13 | Problem 104: navigation prompt, call, route loss (observe only) | PENDING |
| 14 | Activity/process lifecycle (20 recreations; process death versus force-stop) | PENDING |
| 15 | Battery/thermal baseline (stationary; does not close R-05) | PENDING |
| 16 | Optional software peer | PENDING (SUPPLEMENTARY only) |
| — | DX-01, DX-02 (export on the merged build) | PENDING |

## 6. Deferred to Phase 9B — REQUIRES PHYSICAL IPHONE

Android ↔ iPhone control, pairing and reconnect (I-02, I-07); cross-phone mDNS and hotspot;
two-phone voice V-01…V-11 (except V-09's Android half); synchronised playback S-01…S-12 and any
alignment or drift figure; iPhone background and lock-screen behaviour; pillion TWS behaviour; the
audible two-device latency; problem 105; DX-03.

## 7. Observations for the reviewer (not defects)

- FSM triggers in the log render as `SessionEvent$StartDiscovery@f325091`, because `SessionEvent`'s
  cases are plain Kotlin `object`s. This is readable and not sensitive, but noisy in a field export.
  `data object` would print `StartDiscovery`. It was deliberately left alone, because it touches the
  pure FSM and is outside a prerequisite PR.
