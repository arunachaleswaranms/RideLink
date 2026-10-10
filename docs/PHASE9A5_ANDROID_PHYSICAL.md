# Phase 9A.5 — Android physical qualification, 10 October 2026

**Verdict: BLOCKED.** This is a partial physical run of the merged build. L-07 and the scoped L-08 fixture checks passed;
other rows have incomplete physical or manual subcases. No Phase 9 or V1 readiness is claimed.
This document supplements, and does not replace, [Phase 9A's historical qualification](PHASE9A_ANDROID_PHYSICAL.md).

## Baseline, device and installation

| Field | Verified value |
|---|---|
| Tested source / fetched origin/main | `4104e81fa5ce9bd197d2d1b6769966e47be590d1` |
| Tested source tree | `71f5fd0bec0c7c2621be4f69b2a274e6e55cf0f5` |
| Approved PR #18 head | `a36a274bebdc8f11f042bf08250a4ddb7d04182e` (user-supplied baseline; not rebuilt) |
| Hardware | Physical OnePlus Nord 5 / OnePlus CPH2707; wireless ADB; emulator listed separately and never used |
| OS | Android 16, API 36; `OnePlus/CPH2707IN/OP6131L1:16/UKQ1.231108.001/V.R4T2.25db0a1-faf42-faf4b:user/release-keys` |
| APK | `ridelink-android-4104e81fa5ce.apk`, existing signed sideload artifact built 2026-10-10T07:02:36Z from a clean checkout |
| APK SHA-256 | `7cdcfc29c945fbd461e98f57f31e141c29b34101b3d857c9df0a8e9f5b80fb75` |
| Certificate SHA-256 | `51d88fc76947c7027fa8fad18a77961c4d1e5c14e950c70d6888ada9240b3ee2` |
| Package / version | `com.ridelink.app`, `0.1.0` (1), release; minSdk 31, targetSdk 36 |
| Installation | User-approved **in-place update**, completed 2026-10-10T13:06:30Z; no uninstall or data clear |
| Installed bytes / signer | Helper pulled installed APK back and required exact SHA-256 equality; therefore signer matches the verified signed artifact |
| Historical installed APK | Read back before replacement: SHA-256 `094695fa75d5d1fdfdfe23dd446e7d6e1aeb54b06eadd7369596cf5ddc0e7fba`; same certificate; old artifact and records preserved |

Before installation, `git status`, HEAD/tree, fetched origin/main, device properties, installed
version and signer were checked. The source revision was independently found in `classes3.dex`
and generated release `BuildConfig`; `aapt2 dump badging`, `apksigner verify` and
`zipalign -c -P 16 4` verified artifact details. The existing clean-build provenance was reused;
no build or completed software suite was rerun. The in-app diagnostics export was not verified
in this run and remains a provenance cross-check to perform.

[Post-merge CI 38026957397](https://github.com/arunachaleswaranms/RideLink/actions/runs/38026957397)
is the user-verified successful baseline. The existing
[Security run 38026957430](https://github.com/arunachaleswaranms/RideLink/actions/runs/38026957430)
was read through the GitHub API: exact tested SHA, attempt 2, completed/success. No Security rerun
was requested. The approved software baseline is separate from these physical results.

The original checkout already had three modified documentation files. They remain untouched by
this evidence branch, which uses a separate worktree from the exact merged main. No production
source changed.

## Results against TEST_PLAN §4.3a

All rows use the hardware, build and signer above. Partial successes do not pass an entire row.

| Test | Actual sequence and observation | Expected / remaining requirement | Result |
|---|---|---|---|
| L-06 | First cold launch `am start -W`: COLD, Status ok, TotalTime 394 ms, WaitTime 401 ms; migrated library had 21 tracks. Later resumed UI had 53 tracks after an unobserved interval; that change is not attributed to an automated test. A read-only inventory found 3,480 supported files under Music/Recordings, not a confirmed music-only source. No large library was imported by this run | With ~3,460 actual music tracks: immediate touch, search/clear, every sort, rapid scroll and repeated navigation, without ANR or multi-second freezes. Music folder location still needs confirmation; do not import sensitive recordings to satisfy the count | **BLOCKED** |
| L-07 | Through real SAF UI imported A (2 new), B (1 new), and one individually selected file (1 new). Deleted only the created `QA-DeleteOnlyThisFixture.flac`, then re-imported A: “1 track · 0 new · 1 no longer found”. UI showed only that fixture as File not found. Retained A/Alpha, B/Bravo and single/Charlie each reached PLAYING in RideLink's MediaSession | A rescan must affect only its source; B and the individual file remain playable, only the deleted A file missing | **PASS — PHYSICAL** |
| L-08 | Music scanning showed 0 then 60 found; Cancel at 13:08:15Z produced Import cancelled at 13:08:17Z and left count 21. No Music import was confirmed by automation. Disposable PrivacyMusic preview counted 2 supported tracks including a nested subfolder, named Recordings (1), defaulted exclusion on, offered Import 1 track; completion was “1 track · 1 new”. Search showed QA-Nested and no QA-DisposableRecording | A dedicated 20-file CancelImport fixture showed “Checking 3 of 20”; Cancel produced Import cancelled and retained count 58. Re-import completed “20 tracks · 20 new”, count 78. These meet §4.3a counts/progress/cancellation criteria. Cancellation after partial indexing writes was not observed | **PASS — PHYSICAL (fixture scope)** |
| Q-01 | Real tracks: added T1/T2/T1, Play started first; moved T2 up, removed second T1 only (3→2, current T1 retained), selected T2. Clear confirmation appeared; Cancel retained 5 entries later in the run. Confirmed Clear emptied queue and removed RideLink's session; subsequent empty-queue Play left no session immediately or after 3 seconds. Fresh fixture additions gave stable entries. Next/Previous moved between duplicate Alpha entries; Pause held 3441 ms across two samples and Play resumed. Rapid Alpha→Bravo→Charlie ended on Charlie. With a fresh active Alpha, Clear then the empty-queue Play control (~0.343 s between tap returns) left no session immediately or 3 s later | Remaining: Clear→Pause and Play during a proven in-flight load. Coordinate timing is a physical smoke, not deterministic suspension-point race coverage | **MANUAL REQUIRED — partial** |
| N-01 | Music-only app notification id 2, ridelink.music, PUBLIC/transport/MediaStyle; title/artist matched selected T2. Quick Settings rendered same title/artist and Previous/Pause/Next. No intercom notification or microphone/intercom wording in the app-only notification block | Real lock-screen card and human audio confirmation pending. Quick Settings is the device's rendered media surface; notification-list dump alone is insufficient | **MANUAL REQUIRED — partial** |
| N-02 | T2 real-track metadata matched Quick Settings. Three distinct synthetic transitions (Alpha, Bravo, Charlie) each matched Quick Settings title, Unknown Artist and Pause control; session metadata agreed. Synthetic files have no artwork, duration 120 s. One intermediate capture showed the notification list rather than Quick Settings; it was excluded from media-card comparison and the correct panel recaptured | Actual lock-screen title/artist/artwork/duration/status across three tracks and full duration/artwork inspection remain manual | **MANUAL REQUIRED — partial** |
| Lifecycle | 13 samples across about 74 s: PLAYING throughout, position 100672→174653 ms, foreground service throughout, mediaPlayback type 0x00000002. Device observed Dozing, real keyguard showing. Samples demonstrate supported background audio continuing | Two Home/return cycles and a Settings task switch stayed PLAYING. A fresh five-rotation run plus restore gave 6 destroy/create/resume cycles, PLAYING at every sample, 5/5 mediaPlayback foreground-service samples; settings restored to original 0/0. An earlier run reached the 120-s end of the final fixture and is excluded from uninterrupted-recreation proof. Active Clear stopped playback; normal Back exit left no session/service. Real unlock/catch-up and lock-screen control interactions still need explicit manual evidence | **MANUAL REQUIRED — partial** |

T1/T2 are labels for private music; metadata hashes in the sanitized evidence identify matching
states without publishing titles. A “PLAYING” claim above uses **RideLink's own session block**,
not another application's session.

## Fixture boundaries and execution limits

A new dedicated `RideLinkQualification-20261010` directory was confirmed absent before creation.
Six labelled 120-second low-volume synthetic WAV fixtures were created; WAV is unsupported, and
the initial A preview correctly counted zero. They were encoded to supported FLAC using system
`afconvert`, then pushed to explicit paths. A repeated directory push also created an unused
nested fixtures directory; no personal media was involved. Only the named created A FLAC was
deleted. The retained fixtures are A/QA-Alpha, B/QA-Bravo, Single/QA-Charlie and
PrivacyMusic/Nested/QA-Nested; PrivacyMusic/Recordings/QA-DisposableRecording was excluded. WAVs
and unused copies were not imported. A further 20 labelled FLAC copies (6.7 MB total) under
CancelImport exercised Checking-phase cancellation and full re-import; these are retained test
fixtures. The final library count is 78, including test data, not the required ~3,460-track library. Cleanup is limited to explicitly created fixtures; no library
reset, database patch, personal-file deletion or unrelated-source reconciliation occurred.

Lock-screen captures were black and UIAutomator reported “could not get idle state”. It leaves
old XML behind even when its process exits zero. Two stale lock-screen XML copies were detected
and **excluded**; the helper now requires a successful fresh-dump message before reading XML.
No lock-screen PASS is inferred. The user was asked for actual visual/audio observations;
“done” confirmed unlock availability only, not an explicit perception result.

One rapid-add sequence produced fewer entries than intended. A stable retry with a fresh hierarchy
after each tap correctly added entries. The first sequence is **inconclusive**, not a product
regression or a passing rapid-add test: asynchronous UI transitions and later foreground
interruptions prevent reliable attribution. A phone call interrupted the subsequent transport
sequence; the foreground guard refused input. Calls and personal applications were not operated.

The first recreation window crossed fixture end-of-queue; positions near 117 s of 120 s explain its session ending. It is preserved as an inconclusive uninterrupted-playback attempt, followed by the fresh successful run. No new product defect is confirmed. No current-run RideLink ANR/crash/process-death event was
found in the collected scoped event output; this is limited to the observed interval and does not
qualify unexecuted tests. No functional fix is proposed or made.

## Evidence and reproducibility

Sanitized evidence is in [evidence/phase9a5-android-20261010/](evidence/phase9a5-android-20261010/):

- `command-ledger.json`: UTC timestamps, actual ADB argument sequences, durations, exit status and safe results. `$RIDELINK_SERIAL` replaces the private physical serial; every executed command used the explicit verified serial. A completed tap command is not itself a behavioral PASS.
- `media-observations.json`: app-session presence/state/position, synthetic titles or metadata hashes; unrelated media sessions omitted.
- `background-summary.json`: 13 playback and foreground-service samples.
- `ui-observations.json`: whitelisted synthetic labels, counts, controls and recording-exclusion state.
- `lifecycle-summary.json`: scoped recreation/fault counts, service checks, restored settings and Clear/Play timing.
- `import-cancellation.json`: private screenshot observation of Checking 3 of 20, cancellation and retry counts.

Private raw logs/XML/screenshots, installed historical APK readback, command outputs, the preserved
local-change patch and build provenance remain at `/private/tmp/ridelink-9a5-physical-20261010/`.
These temporary files contain personal metadata and are **not committed or attached**. Raw
notification/shade evidence includes unrelated content; only the redacted app-specific summary
is suitable for review. Temporary files are not durable published evidence.

Core executed commands, with the serial redacted:

```sh
git status --short --branch
git fetch origin
git rev-parse HEAD HEAD^{tree} origin/main origin/main^{tree}
adb devices -l
adb -s "$RIDELINK_SERIAL" shell getprop ro.product.model
adb -s "$RIDELINK_SERIAL" shell getprop ro.build.version.release
adb -s "$RIDELINK_SERIAL" shell getprop ro.build.version.sdk
tools/sideload/android.sh verify android/app/build/sideload/ridelink-android-4104e81fa5ce.apk
tools/sideload/android.sh install -s "$RIDELINK_SERIAL" android/app/build/sideload/ridelink-android-4104e81fa5ce.apk
adb -s "$RIDELINK_SERIAL" shell am start -W -n com.ridelink.app/.MainActivity
adb -s "$RIDELINK_SERIAL" shell uiautomator dump /data/local/tmp/ridelink-qualification.xml
adb -s "$RIDELINK_SERIAL" shell dumpsys media_session
adb -s "$RIDELINK_SERIAL" shell dumpsys activity services com.ridelink.app
adb -s "$RIDELINK_SERIAL" shell rm /sdcard/RideLinkQualification-20261010/A/QA-DeleteOnlyThisFixture.flac
```

## Deferred gates and next action

**DEFERRED:** Q-02 synchronized queue authority; N-03/N-04 authenticated live-intercom notifications;
all peer-dependent Android gates; physical iPhone; two-device sync/voice; Bluetooth headset and
helmet audio. No authenticated second physical device or required audio hardware was qualified.

Next: confirm the real music-library source, complete remaining UI tests with the phone available,
and obtain explicit manual audio/lock-screen observations. Record failures without changing app
code. Any functional correction requires a separate fix branch/PR, regression tests and independent
review. This evidence PR stays draft and must not be treated as qualification acceptance.
