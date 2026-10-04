# Phase 9A — Android field readiness and physical qualification (OnePlus Nord 5)

**Status, 4 October 2026: FINAL MERGED-BUILD REQUALIFICATION DONE ON `d2cd71a` (§14); READY FOR
INDEPENDENT REVIEW — PHASE 9A FINAL WITH PEER-DEPENDENT ROWS PENDING.** The merged fixes passed on
the phone. Problem 111: 3,460/3,460 tracks, no ANR; five overlapping imports, no `UNIQUE` crash and
no duplicate rows; a repeat import kept the count. Problem 110: 8/8 certificate-less TLS candidates
rejected, and the listener survived. The compact regression smoke passed. Two findings are recorded
and not fixed: **problem 114 now reaches ANR severity** (a ~20 s main-thread composition on a cold
launch or on clearing search with 3,460 tracks; 4/4), and **problem 116** is new (the lock-screen
player keeps the first track's metadata). No authenticated software peer could be formed on this
network (§14.5), so every peer-dependent Android row remains **PENDING — AUTHENTICATED PEER
UNAVAILABLE**. No production source changed. The section below this one is the historical status
of the `98438d6` run.

**Status, 27 September 2026: FORMAL QUALIFICATION RUN ON THE MERGED BUILD; STOPPED AT
"READY FOR INDEPENDENT REVIEW — PHASE 9A FIX".** Every Android-only row that needs no peer was run
on the reviewed and merged build `98438d6`. That run reproduced **two defects** on the phone
(STATUS §4 problems 110 and 111). Re-running 111's reproduction on the first fix build found **a
third** (the fix exposed a pre-existing indexing race). All three are fixed on branch
`phase9a/qualification-fixes`, pending independent review. Four more findings are recorded and
**not** fixed (problems 112–115).

**Every row that needs an authenticated peer stays PENDING.** Start Ride, Start Intercom, Mode E
and the notification's intercom actions all require one. No iPhone was available, and the one
software-peer attempt (§9) was defeated by problem 110 itself. The results below record what was
observed and nothing more.

Phase 9A is Android-only. It cannot close any iPhone or two-device gate. Every such row below is
**DEFERRED — REQUIRES PHYSICAL IPHONE** and belongs to Phase 9B.

Result labels: `PASS — PHYSICAL ONEPLUS NORD 5` · `FAIL — REPRODUCED` ·
`DEFERRED — REQUIRES PHYSICAL IPHONE` · `DEFERRED — HELMET HARDWARE NOT AVAILABLE` · `NOT APPLICABLE` ·
`SUPPLEMENTARY — PHYSICAL ANDROID + SOFTWARE PEER` · `PENDING` (not yet run).

## 1. Formal baseline

| Item | Value |
|---|---|
| `main` | `98438d692b185771808289f0bcb3ab863336075b` (PR #15 merged; post-merge CI and Security green). `git pull --ff-only` found nothing newer; working tree clean |
| Host | Apple Silicon macOS (Darwin 25.6.0), OpenJDK 21.0.12.1 for Gradle, build-tools 36.0.0 |
| Recorded | 2026-09-27, 15:30–18:10 UTC (21:00–23:40 IST on the phone's clock) |

## 2. Physical device

| Property | Value |
|---|---|
| Manufacturer / model | OnePlus / CPH2707 (OnePlus Nord 5) |
| Android / API | 16 / 36; build `CPH2707_16.0.5.1201(EX01)` |
| `ro.build.fingerprint` | `OnePlus/CPH2707IN/OP6131L1:16/UKQ1.231108.001/V.R4T2.25db0a1-faf42-faf4b:user/release-keys` |
| `ro.boot.qemu` | empty (not an emulator) |
| Connection | wireless debugging, one mDNS `adb-…._adb-tls-connect._tcp` transport, explicit `-s` on every command. The serial and LAN addresses are not recorded |

## 3. Formal build and clean install — PASS — PHYSICAL ONEPLUS NORD 5

`tools/sideload/android.sh build` from the clean formal tree:

```
source_revision: 98438d692b185771808289f0bcb3ab863336075b
working_tree: clean
apk: ridelink-android-98438d692b18.apk
apk_sha256: 88b3bdb6ad61957d36f04c8af936ba1aaf0bf76a6d51901ef6af446887fa8fac
signer_certificate_sha256: 51d88fc76947c7027fa8fad18a77961c4d1e5c14e950c70d6888ada9240b3ee2
version_name: 0.1.0   version_code: 1   build_type: release (not debuggable)
build_tools: 36.0.0   jdk: openjdk version "21.0.12.1" 2026-08-18
built_at_utc: 2026-09-27T15:32:02Z
```

`install -s <serial> --clean`: RideLink was **not installed beforehand** (`pm list packages` was
empty), so the uninstall step removed nothing and this was a first install on empty state. The
install succeeded (`install_mode: clean`, 2026-09-27T15:32:56Z). The installed `base.apk` was pulled
back and was byte-identical (`88b3bdb6…fac`); `versionName=0.1.0`, `versionCode=1`. OxygenOS showed
its own post-install scan screen (`InstallFinishActivity`) first.

**DX-01 provenance:** Export diagnostics log, saved by the user to the phone's Download folder, reads
`source_revision: 98438d692b185771808289f0bcb3ab863336075b`, `platform: android`,
`app_version: 0.1.0 (1)` (§6.9).

## 4. Settings baseline, recorded before anything was changed

| Setting | State at baseline |
|---|---|
| `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `BLUETOOTH_CONNECT`, `READ_MEDIA_AUDIO` | all **not granted** (fresh install; `-g` is never used) |
| Background app-ops (`RUN_IN_BACKGROUND`, `RUN_ANY_IN_BACKGROUND`, `START_FOREGROUND`) | default `allow` |
| Battery optimisation | **optimised** (not on the `deviceidle` whitelist); standby bucket 50 before first launch |
| Wi-Fi / mobile data / Bluetooth / airplane | on / on / on / off |
| Screen timeout | 10 min |
| Lock screen | **real**: `CredentialType: PIN`, `IsLockScreenDisabled: false`, `lockscreen.disabled: 0` |
| Developer options | on; stay-awake off; auto-rotate off |
| Battery saver | off |

**Nothing was changed** for any row below. Every result is **works by default** on OxygenOS unless
the row says otherwise; no OnePlus-specific configuration was needed or applied. The one OEM
behaviour that matters (swipe-kill, §6.5) was observed, not configured around.

## 5. Qualification matrix

| Stage | Row | Result |
|---|---|---|
| 3 | Formal signed build, clean install, provenance | **PASS — PHYSICAL ONEPLUS NORD 5** |
| 4 | Settings baseline | Recorded (§4) |
| 5.1 | Cold launch, render | **PASS — PHYSICAL ONEPLUS NORD 5** (500 ms cold; no clipping; no crash) |
| 5.2 | Activity recreation, Home, Recents | **PASS — PHYSICAL ONEPLUS NORD 5** (§6.6) |
| 5.3 | Screen off/on | **PASS — PHYSICAL ONEPLUS NORD 5** (§6.6) |
| 6 | LM-A import, index, metadata, search, sort, queue, transport | **FAIL — REPRODUCED** for folder import on the formal build (problem 111, 3/3 ANR). Metadata, search, sort, duplicates and transport **PASS — PHYSICAL ONEPLUS NORD 5** with a 13-track folder. Exact duplicate removal **NOT APPLICABLE** locally: the local UI has no queue list (§6.1) |
| 6 | LM-B background playback | **PASS — PHYSICAL ONEPLUS NORD 5** |
| 6 | LM-C screen-off playback (real lock) | **PASS — PHYSICAL ONEPLUS NORD 5** (3 min locked, 147/147 samples playing) |
| 6 | LM-D lock-screen media controls | **PASS — PHYSICAL ONEPLUS NORD 5** |
| 6 | LM-E Recents swipe during music | **PASS — PHYSICAL ONEPLUS NORD 5**: nothing orphaned. On OxygenOS the swipe **kills the process** (§6.5) |
| 7 | AF-01 (`mediaPlayback\|microphone` from Start Ride) | **PENDING**: needs an authenticated peer |
| 7 | AF-02 (Mode E, `mediaPlayback` only) | **PENDING**: Mode E needs a peer. Music alone runs `mediaPlayback` only (`types=0x2`), which is the service half, not AF-02 |
| 7 | AF-04 (background start through a debug receiver) | **NOT APPLICABLE**: the harness does not exist in the repository (no `debug` source set or receiver) |
| 7 | AF-05 (30 min real lock) | **PENDING**. Music-only supplement: 30 min run with a real PIN lock, 31/31 samples playing, no restart. **Not continuous**: the user unlocked the phone for ~3 min at minute 21, so the longest locked span was ~20 min. Session and capture need a peer |
| 7 | AF-06 (`POST_NOTIFICATIONS` denied) | **PENDING** for the ride. Music half observed: the permission was never granted and the lock-screen/shade media player still appeared with full controls (media-session notifications are exempt) |
| 7 | AF-07 (`onTaskRemoved` mid-ride) | **PENDING** for the ride. With music only, the OEM kills the process 17 ms after task removal, so `onTaskRemoved` is not a reliable path on this phone (§6.5) |
| 8 | Problem 101 (End Intercom + recreate) | **PENDING**: needs an intercom, so a peer |
| 9 | Problem 108 (successor intercom) | **PENDING**: needs a peer |
| 10 | V-09 Android half (`RECORD_AUDIO` denied) | **PENDING**: the mic is only requested on Start Intercom, which needs a peer |
| 11 | Notification media controls (shade, lock screen) | **PASS — PHYSICAL ONEPLUS NORD 5** |
| 11 | Notification Mute / End intercom | **PENDING**, and see problem 113: those actions are **not rendered** on this Android 16 build |
| 12 | Helmet Bluetooth unit | **DEFERRED — HELMET HARDWARE NOT AVAILABLE**. Supplementary earbuds result in §7 |
| 13 | Problem 104 observations | Recorded (§7) |
| 14 | Lifecycle stress | **PASS — PHYSICAL ONEPLUS NORD 5** (§6.6) |
| 15 | Battery/thermal baseline | Recorded (§6.8). Does not close R-05 |
| 16 | Optional software peer | Attempted; **not established** (§9). No SUPPLEMENTARY row is claimed |
| 17 | DX-01 / DX-02 | **PASS — PHYSICAL ONEPLUS NORD 5** (§6.9), with one half pinned by unit tests only |

## 6. Evidence

### 6.1 LM-A — library, metadata, search, sort, queue, transport

- **Folder import, formal build: FAIL — REPRODUCED 3/3** (problem 111, §8.2). A folder of more than
  500 files ANR'd each time (`Input dispatching timed out … Waited 5000ms for FocusEvent`), and the
  system killed the app. A 13-track folder imported without an ANR.
- Metadata: titles, artists and albums populated; a track without tags showed
  "Unknown Artist — Unknown Album".
- Search: a title fragment narrowed 13 → 1; clearing it restored 13.
- Sort: Title, Artist, Album and Recent each became the selected chip and reordered the list.
- Queue and duplicates: queueing the same track twice gives two entries. Each copy plays as its own
  entry; Next from the first copy restarted the same track as the second.
- **Exact duplicate removal: NOT APPLICABLE locally.** No local queue list or Remove button exists.
  `MusicCoordinator.removeFromQueue` is implemented; only the peer-shared queue exposes Remove.
- Transport, measured on the real MediaSession:
  - Pause → `PAUSED`, position frozen for 3 s.
  - Play → `PLAYING`.
  - Seek to ~85% and ~15% moved the session position to 135 s and 24 s.
  - Next past the last item stops playback (`LocalQueue`'s documented no-wrap).
  - Previous goes back one item; Previous at the first item is a no-op.
- Queued items **do not start playback**. Play is disabled until Next starts the queue (UX, §11).

### 6.2 LM-B — background — PASS

The same PID throughout Home, then the Settings app, then 20 s on the launcher, then return. There
was one MediaSession, one `AudioTrack` in `started`, one foreground service and one notification at
every check. The UI caught up on return (1:35 / 2:35, Pause shown; the session agreed).

### 6.3 LM-C — screen off and real lock — PASS

Screen off with the PIN keyguard showing (`isKeyguardShowing=true`), 3 min, sampled every second.
147/147 samples were `PLAYING`, and the queue auto-advanced across a track boundary while locked. The
FGS (`types=0x2`), notification and session were present throughout; the PID did not change. A first
attempt ended after 41 s only because the queue ran out (end-of-queue stop is by design); the
service then stopped cleanly.

### 6.4 LM-D — lock-screen and shade controls — PASS

The lock-screen player appeared with all controls. Each tap produced exactly one state change,
with no duplicate command:

| Time | Control | Session |
|---|---|---|
| 21:59:33 | Pause | `PAUSED` |
| 21:59:36 | Play | `PLAYING` |
| 21:59:40 | Next | position reset to 0 |
| 21:59:50 | Previous | position reset to 0 |

The lock-screen Next and Previous reach `MusicSessionPlayer` → `MusicCoordinator.next()`/`previous()`
→ `LocalQueue`. That has no restart-to-zero branch, so each reset is a track change. After unlocking,
the UI agreed with the session (2:27 vs 145 s; title hashes equal).

The notification shade gave the same result: Pause at 22:24:49, Play at 22:24:55. There was still one
session, one player and one FGS.

### 6.5 LM-E — Recents swipe — PASS (and an OxygenOS finding)

Task 29467 (identified fresh) was swiped away by the user while music played:

```
22:03:33.047 wm_destroy_activity … finish-imm:remove-task
22:03:33.064 am_kill : …,o-kill(40) K|null with swipe up
22:03:33.126 am_schedule_service_restart: RideForegroundService, 1000
22:03:34.144 am_proc_start: … service RideForegroundService { serviceType = restartService }
22:03:34.276 am_foreground_service_stop: … STOP_SERVICE
```

**OxygenOS kills the process on swipe** (`o-kill … with swipe up`) 17 ms after removing the task.
The system then respawned a process for the service restart, which never created the service and
sat `cch-empty`. The result:

- The Activity was gone.
- Music stopped; the notification and FGS were gone.
- **Nothing was orphaned.**

RideLink's own `onTaskRemoved` has no reliable chance to run on this phone, which matters for AF-07's
design. The local queue is not restored after process death; the library is.

### 6.6 Lifecycle stress (5.2, 5.3, 14) — PASS

All cycles ran with music playing unless stated. Duplicates were checked every cycle (sessions,
`AudioTrack`s, FGS records, notifications).

| Exercise | Count | Result |
|---|---|---|
| Activity recreation by forced rotation (`user_rotation`, restored to 0) | 20 | 20 destroy / 20 resume; one session, player, FGS and notification in every cycle; music uninterrupted; no ANR or crash |
| Home → return | 10 | identical in every cycle |
| Recents → tap the card (by the user) | 3 | clean pause/resume pairs. The automated attempts returned to the launcher, not the app (harness limitation, not counted) |
| Screen off → on (keyguard stays up) | 10 | 9 identical; 1 sample saw the `AudioTrack` momentarily not `started` while the session stayed `PLAYING`, recorded as an unexplained transient |
| Process death (`am kill`, backgrounded, music stopped) | 1 | `am_kill … kill background`; **no restart** (START_NOT_STICKY); cold relaunch 427 ms; clean IDLE; library intact (13) |
| Force-stop | 1 | `stopped=true`; cold relaunch 342 ms; library and both SAF grants intact |

Pausing keeps the FGS and notification by design (`isMusicActive` is true while a track is loaded),
so lock-screen resume works. Ending the queue stops the service cleanly, with no orphan.

### 6.7 Foreground service observations (music only)

Music alone starts `RideForegroundService` with `types=0x00000002` (`mediaPlayback`) and
`stopIfKilled=true`, without an exception. The microphone type never appeared; `RECORD_AUDIO` was
never requested, because only Start Intercom asks for it.

### 6.8 Battery and thermal baseline (stationary; does not close R-05)

30 min on battery (unplugged; `status: 3` discharging), screen off and PIN-locked except minutes
21–23 (the user unlocked it), music playing from the speaker throughout. RideLink was the only
foreground-service workload; the phone was otherwise idle.

| t (min) | Battery % | Battery °C | Thermal status |
|---|---|---|---|
| 0 | 44 | 36.7 | 0 |
| 5 | 44 | 34.4 | 0 |
| 10 | 44 | 33.2 | 0 |
| 15 | 43 | 32.5 | 0 |
| 20 | 43 | 31.9 | 0 |
| 25 | 43 | 32.9 | 0 |
| 30 | 43 | 32.4 | 0 |

The drain was **1 percentage point in 30 min**, which is the gauge's resolution; no rate is claimed.
The temperature was falling from a recent charge. There was no crash, ANR, process restart or
service restart, and no audio failure: 31/31 samples were playing with one PID. **Not extrapolated**
to a two-hour ride. The intercom, Bluetooth and a peer were all absent.

### 6.9 Diagnostics export on the merged build — DX-01, DX-02 — PASS

- Export #1: the share sheet opened with `ridelink-diagnostics-5374a96d-….txt`. The user saved it to
  Download with a local file app, and it was pulled to the laptop (826 B, 4 events).
  - Header: `source_revision: 98438d692b185771808289f0bcb3ab863336075b`.
  - The discovery handle appears only as `dh:baf3a8…` (6 hex).
  - No IPv4 address, hardware serial, path, SAS, token, key or TLS material. The remaining hex runs
    are monotonic timestamps and Kotlin `Object@hash` identity codes (§12).
- Export #2, cancelled with Back:
  - It showed a **distinct** snapshot, `ridelink-diagnostics-5262db07-….txt`.
  - RideLink returned in the same process (PID unchanged), with no crash, and nothing left the phone.
- The saved copy of #1 was unchanged afterwards (SHA-256 equal).
- **Not observed on the phone:** that the app's private cache snapshot #1 was not mutated. A release
  build cannot be read over `run-as`. That half is pinned by `DiagnosticsShare` unit tests only.
- The share sheet's direct-share row showed personal contacts; no screenshot of it was kept.

## 7. Problem 104 — observations only (stationary; nothing was changed)

RideLink holds **no audio focus** while it plays: the focus stack was empty until another app took
it. All of the below ran on the formal build `98438d6`.

| Row | Setup | What the platform did | What the user heard |
|---|---|---|---|
| 13A | Google Maps navigation, voice guidance on, three prompts (23:16:51, 23:17:50, 23:17:58) | Maps took focus for each prompt. RideLink's `AudioTrack` stayed `started` and was **never ducked** | Music at full volume over the navigation voice |
| 13B | A short phone call (23:19:46–23:20:45) | `MODE_IN_CALL`; telecom took focus; the platform **muted** RideLink's track (`mutedState:stream`). RideLink **never paused**: its session stayed `PLAYING` and the position ran on from 21 s to 79 s. It was unmuted at 23:20:47 | Music stopped during the call and came back after it, about 60 s further into the track |
| 13C | Realme Buds Wireless 5 ANC (**SUPPLEMENTARY**: earbuds, not the helmet). Connected mid-song, then put back in the case, twice | Output moved to A2DP (device 5401, later 5414), then back to the phone speaker (device 2). The session stayed `PLAYING` throughout; no becoming-noisy handling | Music continued **out loud from the phone's loudspeaker** after each disconnect |

For the ADR, the consequence is that a helmet unit dropping mid-ride would play music from the
phone in the rider's pocket. Navigation prompts are not ducked either. During calls the platform's
own muting hides the problem, but the track keeps running underneath.

## 8. Findings

### 8.1 Problem 110 — one failed inbound TLS handshake ends the control listener (both platforms) — FAIL — REPRODUCED

- **Build:** `98438d6` (APK SHA-256 `88b3bdb6…fac`), OnePlus CPH2707, Android 16.
- **Preconditions:** RideLink discovering (Find peer), listener advertised as `_ridelink._tcp`.
- **Steps:** from a laptop on the same Wi-Fi, three TLS 1.3 connections without a client
  certificate, one second apart (Homebrew Python/OpenSSL 3.6.4).
- **Expected:** every connection is answered and rejected.
- **Actual:**
  - Probe 1 was answered (`SSLEOFError` after 104–111 ms).
  - Probes 2 and 3 connected at the TCP level and never received a ServerHello (8 s client timeout).
  - The UI kept "Finding your peer…".
  - Stop searching → Find peer bound a fresh listener, which again answered exactly once.
- **Rate:** 2/2 listener sessions.
- **First noticed** when an emulator software peer's listener went silent after the phone's first dial
  through the bridge failed (§9).
- **Root cause:**
  - Android: `TlsControlChannel.accept` runs the handshake inside `ControlListener.accept`, and
    `ControlSessionManager.acceptOrNull` maps every `IOException` to "listener closed".
  - iOS: `ControlSessionManager.swift`'s `guard let socket = try? await bound.accept() else { return }`,
    where `accept()` throws when `onAccepted` (`awaitReady`) fails.
- **Fix (commit `ebd42c8`):** a typed candidate rejection that the loop skips.
- **Regressions:** `InboundHandshakeFailureTest` / `InboundHandshakeFailureTests`, which fail against
  unmodified production.
- **Physical re-check on fix build `ebd42c8`** (APK SHA-256 `6b59d0e4…880`, update install): the same
  procedure with **5 probes: all 5 answered and rejected**, in 59–164 ms. No ANR, no crash.

### 8.2 Problem 111 — library import and hashing on the main thread (Android) — FAIL — REPRODUCED

- **Build:** `98438d6`.
- **Steps:** Import Folder → choose a folder with more than 500 audio files (the phone's `Music` tree:
  1 supported file at the top level, 3,446 `.mp3` in 10 subfolders).
- **Expected:** indexing proceeds while the UI stays responsive.
- **Actual:** 3/3 ANR within seconds, and the process was killed. All three `data_app_anr` main-thread
  stacks:
  `MusicCoordinator$importTree$1` → `LibraryIndexer.importTree` → `SafLibraryScanner.scanTree` →
  `walk` → `TreeDocumentFile.listFiles` → `ContentResolver.query`.
- **Root cause:** `AppContainer` hands `MusicCoordinator` the `Dispatchers.Main` `appScope`, and no
  entry point switched, although `LibraryIndexer`'s KDoc said the composition root chose IO.
- **Fix (commit `ebd42c8`):** every `LibraryIndexer` entry point confines itself to an injected IO
  dispatcher. The instrumented regression counts main-thread `ContentResolver` reads: 10 before, 0
  after.

**Follow-up defect found by the physical re-check, fixed in commit `de9ea8a`.**

- **Build:** fix build `ebd42c8`.
- **What happened:**
  - The ANR was gone and the UI stayed responsive.
  - The user picked the same folder several times while a pass was running.
  - The count rose (84 → 370 → 761 → about 1,676, by the user's reading).
  - The app then crashed: `am_crash … SQLiteConstraintException: UNIQUE constraint failed:
    tracks.locationUri`, raised in `TrackDao_Impl.insertNew`.
- **Cause:** `indexOrReindex` looks a location up, extracts metadata, then inserts. Two overlapping
  passes over the same files both insert. The race was pre-existing; moving indexing onto the
  multi-threaded IO pool made it ordinary.
- **Fix:** one indexer `Mutex`. Each scan holds it for its whole pass; hashing takes it per file.
- **Regression:** `LibraryIndexerTest.overlappingImportsOfTheSameFilesNeverInsertTheSameLocationTwice`
  uses a barrier between look-up and insert. It reproduced the phone's exception on the emulator
  against `ebd42c8`, and passes now.
- **Also observed:** a crashed or killed import is not resumed on relaunch; nothing rescans a
  persisted tree automatically.

**Physical re-check on fix build `de9ea8a`** (APK SHA-256 `4adf0c1a…9eb`, clean install, the same
folder picked once): **3,447 tracks indexed**, which is every supported file in the tree (3,446 `.mp3` + 1 `.m4a`; the 15 `.amr` files are skipped by the folder walk's extension gate). Subfolders were included. It took about 7.5 min from the pick (23:40:19) to a stable count (23:47:53). There was one process throughout (PID 21986), **0 `am_anr` / `am_crash` / `am_kill` events**, and the user saw no freeze. UI-hierarchy dumps stayed at about 2.6 s while indexing; the slowest single dump (9.6 s) came once all 3,447 rows existed, which is problem 114, not the main thread blocking on I/O. **PASS on the fix build**, which is not the formal build; formal LM-A folder import is re-run after review.

RideLink's app data had been cleared once from system settings between the two re-checks (`am_kill … stop com.ridelink.app due to clear data`, 23:28:12). No command in this session did that; it is recorded because it explains a library count dropping from 13 to 10 before the second fix build's clean install.

### 8.3 Recorded, not fixed

| # | Finding | Evidence | Why not fixed here |
|---|---|---|---|
| 112 | During music-only playback, the ride notification's record reads "RideLink intercom active" / "Microphone open for the intercom." with Mute / End intercom, although no microphone is open | `dumpsys notification --noredact` | This phone does not render it (see 113), so it is not visible here; it is copy and state projection. Phase 9A.5 |
| 113 | **The ride notification's own actions are not rendered.** On Android 13+, a `MediaStyle` notification with a session token is drawn as the media player, with buttons from the session's `PlaybackState`. The user saw only prev/play-pause/next, no Mute and no End intercom. If the intercom uses the same notification, which the code suggests, those controls are unreachable from the shade and lock screen | User observation, music only; `RideForegroundService` always attaches the session | Needs a live intercom (a peer) to confirm. Design needed: custom session actions, or a separate intercom notification |
| 114 | The library list is a plain `Column` composing **every** row (each with its own artwork decode) inside the main screen's single `verticalScroll`, so scrolling a large library is laggy | The user, with about 1,700 tracks; `LibraryScreen.kt:89-99` | A layout restructure (lazy list), not paging. Phase 9A.5 |
| 115 | Reconciliation compares each scan against **every** library row (`missingLocations = previous.keys - discovered.keys`), so importing folder B marks folder A's tracks, and individually imported files, `MISSING` | Code trace (`IndexReconciliation`, `LibraryIndexer.reconcileAndIndex`); not isolated on the phone | Needs scoping reconciliation to the scanned root; separate from the crash fix |

A further observation for the product (no problem number): importing the whole `Music` tree also
indexed a **call-recordings** subfolder the phone keeps there. Phase 4's shared catalogue would then
offer those recordings to the paired phone. Consider warning or excluding recordings; §11.

## 9. Software-peer attempt — not established

The same formal APK was installed in the `RideLink_API36` emulator (the peer's build is irrelevant to
the phone's rows, but it was the same bytes). It was bridged onto the Wi-Fi with a host
`dns-sd -P` proxy record (`v=1`, a random `dh`, `plat=android`) and a TCP relay into `adb forward`.
The phone discovered the proxy and dialled it. The handshake did not complete, and the emulator's
listener then stopped answering TLS entirely: that was problem 110, found here first.

A second, bridge-free try (the emulator's own mDNS advertisement leaks onto the LAN with an
unroutable `10.0.2.16` address) left both sides in "Peer found · Connecting automatically": each
dialled once, failed, and waited for an inbound connection that never came. Because every failed
handshake killed a listener, the bridge was abandoned rather than debugged further (the user chose
to run the peer-free rows and then fix). **No row is labelled SUPPLEMENTARY — PHYSICAL ANDROID +
SOFTWARE PEER.** With problem 110 fixed, a reviewer may consider the bridge again for AF-01/AF-02/
101/108/V-09, **on a reviewed build only**.

## 10. Deferred to Phase 9B — REQUIRES PHYSICAL IPHONE

- Android ↔ iPhone control, pairing and reconnect (I-02, I-07); cross-phone mDNS and hotspot.
- Two-phone voice V-01…V-11 (V-09's Android half is PENDING above, not deferred).
- Synchronised playback S-01…S-12, and any alignment or drift figure.
- iPhone background and lock-screen behaviour; pillion TWS behaviour; audible two-device latency.
- Problem 105; DX-03.
- iOS's side of problem 110 is fixed and unit-tested but has not run on an iPhone.

**PENDING, needing only a peer (any authenticated one):** AF-01, AF-02, AF-05 (session and capture),
AF-06 (ride), AF-07 (ride), problems 101 and 108, V-09's Android half, notification Mute / End
intercom (and problem 113). Still pending after the `d2cd71a` requalification (§14.7).

## 11. UI/UX observations for Phase 9A.5

Recorded while qualifying; **none of these were changed in Phase 9A**. Severity is to usability on
a phone, not to correctness. Screenshots were kept local and uncommitted: every useful one shows
personal track titles, notification icons or share-sheet contacts. Sources: the physical OnePlus
Nord 5 (formal build `98438d6`) and, for iOS wording only, source review.

### 11.1 Copy

| Screen | Issue | Evidence | Severity | Suggested direction |
|---|---|---|---|---|
| Main (both platforms) | Tagline **"Your ride, together"** under the title. Marketing copy that carries no state and pushes the connection card down | Phone, cold launch; `MainScreen.kt`, iOS `Text("Your ride, together")` | Low | Remove it. The title plus the connection state is the header |
| Main, idle | "Connect both phones to the same Wi-Fi or hotspot, then find your peer." / "Looking for the other phone. Open RideLink and find peers there too." mix **peer** and **other phone** in consecutive states | Phone | Low | Pick one noun ("the other phone") for user-facing copy; keep "peer" for diagnostics |
| Main, discovering | "Peer found · Connecting automatically" stays forever when the only dial failed (problem 110's interplay: the app never re-dials) | Phone, twice | **Medium** — the user waits on a state that will not change | A bounded "Couldn't reach the other phone · Try again" state after the dial fails |
| Notification, music only | "RideLink intercom active" / "Microphone open for the intercom." plus Mute / End intercom while only music plays | `dumpsys notification` (problem 112) | **Medium** — a false privacy indicator | Project text and actions from the real state: "Playing music" with media actions only |
| Terminology (both platforms) | Button **Stop Intercom** vs notification **End intercom**; Android **Hold to talk** vs iOS **Start talking / Stop talking**; **Find peer** vs **Find peer again** | Source review (`VoiceCard.kt`, `strings.xml`, iOS views) | Low | One verb per action on both platforms ("End intercom" everywhere), and one PTT phrasing |
| Diagnostics | Internal plan and protocol names shown to the user: "Resync (Phase 7)", "PEER AUDIO_STATE", "SETUP TIMING (not latency)", "TRANSPORT: NOT CONNECTED", "Role violations (stray STATE_REQUEST)", "Last snapshot command_seq"; ALL-CAPS and Title Case headings mixed | Phone | Low (behind a toggle) | Keep the data, drop phase numbers, one heading style; group under plain headings ("Connection", "Sync", "Audio") |
| Diagnostics export | The explanatory paragraph under "Diagnostics log" is long for a button caption | Phone | Low | One line: "Shares a redacted log file. Nothing is sent unless you pick where." |

### 11.2 Layout, hierarchy and controls

| Screen | Issue | Evidence | Severity | Suggested direction |
|---|---|---|---|---|
| Now Playing | **Play is disabled** with items queued and nothing selected; only Next starts the queue | Phone: "3 items in queue", Play `[dis]` | **Medium** — the obvious control does nothing | Enable Play whenever the queue is non-empty, starting at the first item |
| Now Playing | "N items in queue" counts the list, not what is left, so it does not change as tracks play or as Next advances | Phone | Low | "Track 3 of 10", or show "Up next" |
| Queue | No visible local queue: no order, no removal, no reorder. `MusicCoordinator.removeFromQueue` exists, but only the peer-shared queue has a Remove button | Phone; source | **Medium** — LM-A's exact removal is unreachable locally | A compact "Up next" list with swipe-to-remove, reusing the shared queue's row |
| Library | Tapping **Queue** gives no feedback beyond the ripple, and the count it changes is off-screen | Phone | Low | Brief confirmation (snackbar or a check on the button) |
| Library | The Now Playing card grows when playback starts and **shifts the list under the finger**; an automated tap sequence hit the wrong row because of it | Phone | Medium | Reserve the card's height, or keep transport in a fixed bottom bar |
| Transport | Prev / Play in one row with a full-width Next below: an unusual 2 + 1 grid of large tonal cards that reads as default Material | Phone | Low | One row of three, primary Play emphasised, secondary Prev/Next |
| Import | Folder import has no progress or "indexing N tracks" state (and on the formal build it froze the UI, problem 111) | Phone | Medium | A determinate progress row in the library header while indexing |
| Main | Many stacked cards with similar weight (connection, Now Playing, library, diagnostics); the connection state does not dominate when it matters | Phone | Low | One prominent status area; flatter secondary sections |
| Library | **Scrolling is laggy with a large library**: every row, each with its own artwork decode, is composed at once inside the main screen's single scroll (problem 114). The user suggested pages; a lazy list gives the same effect with no paging UI | The user, about 1,700–3,400 tracks | **Medium** | Restructure the main screen so the library is a lazy list (not nested in a scrolling column) |
| Import | Nothing shows for the first minutes of a large folder import: the whole tree is walked before the first track appears, and a killed import is not resumed on relaunch | Phone: ~3,400 files, first tracks after several minutes | Medium | Show "Scanning… N files found", then "Indexing N of M"; offer to resume an interrupted import |
| Import | A whole-`Music` import also indexes the phone's call-recordings subfolder, which the shared catalogue would then offer to the other phone | Phone | **Medium** (privacy) | Show what a folder import will include; exclude or warn about recordings |
| Sort chips | No sort direction indicator | Phone | Low | Arrow on the selected chip |
| After process death | The local queue is silently empty after a process kill or task swipe; the library survives | Phone | Low | Say so ("Queue cleared") or persist the queue |

### 11.3 Where restrained motion would help state understanding

Good candidates: a small indeterminate indicator on "Finding your peer…" and on reconnecting (today
both are static text); a short state transition when "Peer found" appears and when pairing starts;
the play/pause icon morph; a brief success tick when a track is queued; the PTT pressed state; a
subtle "restoring sync" indicator. Not recommended: decorative loops, hero animations, gradients,
parallax, constant motion.

## 12. Observations for the reviewer (not defects)

- FSM triggers in the log render as `SessionEvent$StartDiscovery@597c18f` (seen again in the formal
  export), because `SessionEvent`'s cases are plain Kotlin `object`s. `data object` would print
  `StartDiscovery`. It is left alone, because it touches the pure FSM.
- `swiftlint` and `swiftformat` are not installed on this Mac and are not part of CI, so neither ran
  for the iOS change.
- Instrumented tests (`LibraryIndexerTest`) run only locally, on the API 36 emulator with
  `ANDROID_SERIAL` pinned so nothing was installed on the phone. CI does not run them.
- The emulator's mDNS advertisements leak onto the LAN with an unroutable `10.0.2.16` address. A
  physical phone on the same Wi-Fi discovers and dials them. This is a test-environment hazard for any
  future software-peer bridge.

## 13. Stage 1 checkpoint record (historical, unchanged)

The prerequisite work that preceded this run, kept as it was recorded.

### 13.1 Baseline (Stage 0)

| Item | Value |
|---|---|
| Baseline `main` | `ac7303d5f55f4a416004e9b2413233c47f2ac793` (PR #13 merged); working tree clean; matched the required baseline |
| Recorded | 2026-09-27 12:03 UTC |
| Host | Apple Silicon macOS (Darwin 25.6.0) |
| JDK used for Gradle | OpenJDK 21.0.12.1 (the machine default is Temurin 25; see CLAUDE.md) |
| Android SDK | platform `android-36`, build-tools 36.0.0 |
| adb | 1.0.41, platform-tools 37.0.1-15733141 |
| Xcode | 27 (iOS build only; no iPhone) |

### 13.2 Physical device (Stage 2)

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

### 13.3 Prerequisites (Stage 1)

| Prerequisite | State |
|---|---|
| NFR-08 diagnostics export | Implemented on Android and iOS, pending independent review. [SIDELOAD.md](SIDELOAD.md) § Diagnostics export; TEST_PLAN §3.1h |
| Android sideload procedure | `tools/sideload/android.sh`, pending independent review. [SIDELOAD.md](SIDELOAD.md) § Android |
| iOS personal-team procedure | Documented. **NOT EXECUTED — REQUIRES PHYSICAL IPHONE** |
| Problem 104 (music focus / becoming-noisy) | **Unchanged, deliberately.** Record the behaviour stationary first (Stage 13); decide by ADR or mark it a known limitation before any moving ride |

#### 13.3.1 Procedure validation — pre-review, not a qualification result

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

The filename above records the **earlier prerequisite build** and its single-file implementation.
The review fix now creates a distinct `ridelink-diagnostics-<random UUID>.txt` under
`cache/diagnostics/` for every export, retaining at most four snapshots. An older URI grant cannot
read a newer snapshot. The earlier phone check does not validate this changed implementation;
the two-share procedure remains a pre-review check, not a Phase 9A qualification row.

## 14. Final merged-build requalification (`d2cd71a`, 29 September – 4 October 2026)

This section is the requalification of the **reviewed and merged** fixes for problems 110 and 111
(PR #16). Sections 1–13 above are the historical record of the formal run on `98438d6` and are
unchanged. Times are IST (UTC+05:30), as the phone's clock showed them.

### 14.1 Baseline and gates

| Item | Value |
|---|---|
| `main` | `d2cd71a82ab2a6ec58929ddabf0e01cff4249fa6` (PR #16 merged). `git pull --ff-only` found nothing newer; working tree clean; unchanged when the run resumed on 4 October |
| Post-merge Security | Green on `d2cd71a` (push run and the next scheduled run) |
| Post-merge CI | **First attempt red**: one iOS test, `AVAudioEnginePlayerTests.testPauseThenResumeContinuesFromThePausePointAndEndsExactlyOnce`, read `509` (the fixture's end) against a bound of `456` after a 50 ms resume. PR #16 changed no player code; the PR's own CI and the previous `main` CI were green; the test passed 10/10 locally at `d2cd71a`. Its failed job was re-run with the user's approval and passed; CI is green on attempt 2. Recorded as a CI-runner timing sensitivity of a real-engine test, not as a product result |
| Device | OnePlus CPH2707, Android 16 / API 36, `CPH2707_16.0.5.1201(EX01)`, `ro.boot.qemu` empty. Wireless debugging, explicit `-s` on every command. Serial and LAN addresses not recorded |

`tools/sideload/android.sh build` from the clean tree:

```
source_revision: d2cd71a82ab2a6ec58929ddabf0e01cff4249fa6
working_tree: clean
apk: ridelink-android-d2cd71a82ab2.apk
apk_sha256: 094695fa75d5d1fdfdfe23dd446e7d6e1aeb54b06eadd7369596cf5ddc0e7fba
signer_certificate_sha256: 51d88fc76947c7027fa8fad18a77961c4d1e5c14e950c70d6888ada9240b3ee2
version_name: 0.1.0   version_code: 1   build_type: release (not debuggable)
build_tools: 36.0.0   jdk: openjdk version "21.0.12.1" 2026-08-18
built_at_utc: 2026-09-29T18:01:34Z
```

The signer is the same key as the `98438d6` formal build. **Install mode: clean**, four times, all
with these bytes, each read back from the phone **byte-identical**: 2026-09-29T18:07:10Z (RideLink was
absent beforehand), 18:40:37Z (an empty library for §14.2's overlapping run: OxygenOS refuses
`pm clear` to the shell, `SecurityException … CLEAR_APP_USER_DATA`), 2026-09-30T04:55:22Z (a small
library for the peer attempt, see §14.6), and 2026-10-04T10:49:34Z (RideLink had been uninstalled
from the phone between sessions). The emulator used in §14.5 ran the same bytes (its installed
`base.apk` hashed to `094695fa…`).

**Harness setting changed, then restored:** the screen-off timeout was raised from 10 to 30 min
while the run was unattended (29 Sep 00:19 → restored 01:07; 30 Sep at resume → restored on
4 Oct). `user_rotation` was moved for recreation and restored to 0. Nothing else was changed.

### 14.2 Problem 111 — large library — PASS — PHYSICAL ONEPLUS NORD 5

The same `Music` tree as §8.2 had grown slightly: **3,459 `.mp3` + 1 `.m4a` = 3,460 supported
files** in 10 subfolders. The extension gate skipped, as designed, 2,773 `.awb`, 15 `.amr`, one
`.3ga`, six `.jpg`, a `.nomedia` and one other file.

| Run | Procedure | Result |
|---|---|---|
| **5A single import** | Clean install; Import Folder → `Music`, picked once | Picker returned 23:42:54. The SAF walk ran on a background thread (`DefaultDispatcher` worker and the `externalstorage` provider busy; main thread ~4 s of CPU in total). First rows at 23:47:58 (259), then 641, 1,035, 1,431, 1,860, 2,590, **3,460 by 23:50:56** (**about 8 min**: ~5 min walk, ~3 min inserts) and stable. One process (PID unchanged), **0 `am_anr` / `am_crash` / `am_kill`**, no dropbox ANR or crash dated after 27 September |
| **5C repeat after completion** | Same folder, picked once more after 5A settled | Walk and re-index busy for ~4.5 min (00:03:05–00:07:37). **3,460 in every readable sample** (43) before, during and after: no duplicate rows, nothing lost. Same process, 0 ANR/crash |
| **5B overlapping** | Fresh clean install (empty library). The user picked the same folder **5 times**: at 00:11:52, 00:12:36, 00:13:27, 00:16:26 and 00:18:37–49, the last two while the count was climbing | **No `SQLiteConstraintException`**, no crash, one process (PID 10653) throughout. Counts 2,014 (00:19:22) → 2,880 (00:22:11) → **3,460** (00:22:48 and 00:24:03). Re-read on 30 September after a cold start: **3,460**. Because `tracks.locationUri` is `UNIQUE`, a duplicate would have surfaced as §8.2's crash, not as a larger count |

During 5B the system froze and killed its own `externalstorage` document provider **three times**
mid-walk (00:20:09, 00:20:21, 00:20:31; `oom_adj` 900–925, procstate 19) and restarted it. RideLink
survived every one, and the final count was still complete.

### 14.3 Problem 110 — TLS listener survival — PASS — PHYSICAL ONEPLUS NORD 5

- **Preconditions:** RideLink discovering ("Finding your peer…"), the control listener bound on one
  port and advertised as `_ridelink._tcp` with TXT keys exactly `{dh, plat, v}`.
- **Steps (30 September, 10:10:43):** from the Mac on the same Wi-Fi, **8** TLS 1.3 connections with
  no client certificate, one second apart (Homebrew Python, OpenSSL 3.6.4).
- **Result:** **8/8 answered and rejected** (`SSLEOFError: UNEXPECTED_EOF_WHILE_READING`) in
  58–219 ms. Afterwards: the same port still bound, the same PID, and the UI still "Finding your
  peer…". On the `98438d6` formal build, probe 1 was answered and every later one went silent (§8.1).
- **Legitimate peer afterwards:** not attempted; no authenticated peer was available (§14.5).

### 14.4 Regression smoke (4 October) — PASS — PHYSICAL ONEPLUS NORD 5

This ran on a 21-track library (`Music/RideLink`, §14.6), one process (PID unchanged) throughout,
with 0 `am_anr` / `am_crash` / `am_proc_died`.

| Area | Result |
|---|---|
| Start | Queued items do not start playback; Next starts the queue (known, §11.2). Then `PLAYING`, position advancing; one MediaSession (`androidx.media3.session.id.ridelink.ride`, holding media-button priority), one `RideForegroundService` (`types=0x00000002`), one notification (id 1) |
| Pause / Play | `PAUSED`, position frozen at 18,308 ms for 3 s; Play → `PLAYING` from there |
| Seek | ~75% → 119.6 s; ~20% → 27.0 s; still playing |
| Next / Previous | Track changed (title hash), position reset; Previous returned to the original |
| Home → return | ×2: same PID, still playing, one session, one FGS |
| Activity recreation | 5 forced rotations plus the restore: **6 destroy / 6 create / 6 resume**, music uninterrupted, one session and FGS in every cycle |
| Shade (Quick Settings) controls | Rendered: Previous track, Pause, Next track, output switcher, nothing else. Pause → `PAUSED` with the position frozen; Play resumed the same track (27,551 → 27,789 ms); Next and Previous changed track. A first Play that appeared to change track was the track ending within the 3 s window (paused at 154.5 s of ~155 s) |
| Screen off under the real PIN keyguard | `mWakefulness=Dozing`, `isKeyguardShowing=true`: **12/12 samples `PLAYING`** over ~75 s; same PID, session and FGS |
| Lock-screen controls | Pause → `PAUSED` (position shown live, 00:28); Play → `PLAYING`; Next on the last of three items ended the queue (documented no-wrap), and the service stopped cleanly: no session, no FGS, no notification, no orphan |
| Real unlock | The user unlocked (fingerprint, over the PIN-secured keyguard); the UI agreed with the stopped state (Play shown, last item at 0:00) |

**A new defect was found here (problem 116), recorded and not fixed.** The lock-screen media player
showed the **first** track's title, artist, artwork and duration (02:35) for the whole queue, while
the session's metadata (`dumpsys media_session`) had correctly moved on twice. Playback state and
position on that card were live; only the metadata was stale. By code trace,
`RideForegroundService.buildNotification` posts fixed ride copy plus the session token, and the
notification is re-posted only when the foreground types or mute state change, never on a track
change. That SystemUI takes metadata from the notification post is inferred from the behaviour, not
read from SystemUI's source. Problem 112's copy was reconfirmed in the same `dumpsys notification`:
"RideLink intercom active" / "Microphone open for the intercom." during music only, with no
microphone open.

### 14.5 Software peer — not established — PENDING — AUTHENTICATED PEER UNAVAILABLE

The topology was the `RideLink_API36` emulator on this Mac, running the same bytes. It cannot form
a session with the phone on this network, and the reason is the environment, not the product:

- The emulator's own `_ridelink._tcp` advertisement reaches the LAN, but resolves to its
  NAT address `10.0.2.16`, which the phone cannot route to.
- The emulator does **not** receive LAN mDNS. A decoy `_ridelink._tcp` record registered from the
  Mac was never discovered in 30 s. With both apps discovering for over a minute, neither showed a
  peer, although the Mac saw both advertisements.
- Plain reachability was fine: emulator → phone and Mac → phone answered ICMP, and the Mac firewall
  is off. Only discovery fails.
- The emulator exited twice when its netsim Wi-Fi backend's stream was cancelled.

The remaining route was a bridge: a Mac relay plus a `dns-sd -P` proxy record into the emulator
through `adb forward`. It would expose a local listener on the Wi-Fi. The session's safety policy
refused it, and the user then chose not to run it. No product behaviour is called broken here.
**No row is labelled SUPPLEMENTARY — PHYSICAL ANDROID + SOFTWARE PEER.**

### 14.6 Problem 114 escalated: an ANR on cold launch with a large library — FAIL — REPRODUCED

With the 3,460-track library, the first composition of the main screen blocks the **main thread**
for about 20 s. Any touch in that window ANRs, and OxygenOS then closes the app by itself.

- **Accidental first occurrence (30 Sep, 10:08):** a cold start, and an adb swipe about 4 s later.
  `am_anr` came at 10:08:59 ("Input dispatching timed out … Waited 5000ms for MotionEvent").
  `am_kill … user request after error` followed 1.7 s later.
- **Controlled reproduction, 3/3** (4/4 in all): force-stop, `am start` (`TotalTime` 263–271 ms), then
  one tap on the non-interactive title 3 s later. Each time `am_anr` came ~8.4 s after process start,
  and OxygenOS killed the process ~1.4 s later with no human input.
- **Main-thread stack** (`Runnable`, `utm=820` 9 s after start): Compose
  `SlotWriter.insertSlots` / `moveSlotGapTo` ← `ChangeList.execute` ← `CompositionImpl.applyChanges`
  ← `Recomposer.runRecomposeAndApplyChanges` ← `Choreographer.doFrame`. It is composition, not I/O.
  Problem 111's fix holds.
- **Untouched cold start:** the main thread used **18.5 s of CPU** (~20 s wall) before going idle.
  It rendered 8 frames, with p90/p99 at 4,950 ms.
- **Once composed:**
  - 3 scroll swipes rendered 246 frames: 7.7% janky, p50 18 ms, p90 24 ms, p99 31 ms, and ~0.6 s of
    main-thread CPU per swipe.
  - Sort cost 2.9 s (Artist) and 2.6 s (Title) of main-thread CPU.
  - Search: the first character cost 2.6 s, later characters under 0.1 s.
  - **Clearing the search back to empty cost 18.8 s (~20 s frozen)**, the same full composition. A
    touch in that window would ANR the same way.
- **Artwork:** the placeholder glyph showed in 8/8 visible rows, with no image node. Whether these
  files carry embedded art was not checked.

This is §8.3's problem 114, now at ANR severity. It is **not fixed here**: Phase 9A.5 owns the lazy
library. The peer attempt and the smoke therefore used a 21-track folder after a clean reinstall, so
Activity recreation was not confounded by a 20 s main-thread block.

### 14.7 Matrix for this build

| Row | Result |
|---|---|
| Formal signed build, clean install, provenance (`d2cd71a`) | **PASS — PHYSICAL ONEPLUS NORD 5** |
| Problem 111: single large import (3,460) | **PASS — PHYSICAL ONEPLUS NORD 5** |
| Problem 111 follow-up: overlapping import ×5 | **PASS — PHYSICAL ONEPLUS NORD 5** |
| Repeat import after completion | **PASS — PHYSICAL ONEPLUS NORD 5** |
| Problem 110: 8 bad TLS candidates, listener survives | **PASS — PHYSICAL ONEPLUS NORD 5** |
| Problem 110: legitimate peer afterwards | **PENDING — AUTHENTICATED PEER UNAVAILABLE** |
| Authenticated software peer | Not established (§14.5) |
| AF-01, AF-02, AF-05 (session and capture), AF-06 (ride), AF-07 (ride) | **PENDING — AUTHENTICATED PEER UNAVAILABLE** |
| Problems 101 and 108 | **PENDING — AUTHENTICATED PEER UNAVAILABLE** |
| V-09, Android half | **PENDING — AUTHENTICATED PEER UNAVAILABLE** |
| Problem 113 (intercom Mute / End rendered?) | **PENDING — AUTHENTICATED PEER UNAVAILABLE**. Music-only: the shade and lock screen render only Previous / Play-Pause / Next / output, as §8.3 recorded |
| Regression smoke (§14.4) | **PASS — PHYSICAL ONEPLUS NORD 5** |
| Problem 114 at ANR severity | **FAIL — REPRODUCED** (4/4), deferred to Phase 9A.5 |
| Problem 116 (stale lock-screen metadata) | **FAIL — REPRODUCED** (1/1, observed over three track changes), deferred to Phase 9A.5 |
| Helmet Bluetooth unit | **DEFERRED — HELMET HARDWARE NOT AVAILABLE** |
| Every iPhone and two-device row (§10) | **DEFERRED — REQUIRES PHYSICAL IPHONE** |

**No production source changed in this requalification.** Neither new finding blocks a row that the
available setup can run, and both belong to Phase 9A.5's library and notification work.
