# Phase 9A.5 — Android physical qualification, 10 October 2026

**Verdict: READY FOR INDEPENDENT REVIEW WITH EXPLICIT LIMITATIONS.** N-01, N-02, audible transport/Clear and lifecycle observations are user-confirmed. Q-01B remains physically **INCONCLUSIVE**; its submission with this limit was adjudicated under the user’s delegated judgment. L-06 scale stress is **WAIVED**, with performance unverified. This is not an unconditional Android physical qualification PASS. No Phase 9 or V1 readiness is claimed.
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
| L-06 | First cold launch `am start -W`: COLD, Status ok, TotalTime 394 ms, WaitTime 401 ms; migrated library had 21 tracks. Later resumed UI had 53 tracks after an unobserved interval; that change is not attributed to an automated test. A read-only inventory found 3,480 supported files under Music/Recordings, not a confirmed music-only source. No large library was imported by this run | With ~3,460 actual music tracks: immediate touch, search/clear, every sort, rapid scroll and repeated navigation, without ANR or multi-second freezes. The user confirms importing, displaying, browsing and playing the current intended music library work normally: **USER-REPORTED PASS for normal-library functional use**. Most of the approximately 3,460 discovered audio files are personal call recordings. The user explicitly waives the scale-specific stress test for this Phase 9A.5 qualification; it was not executed and large-library responsiveness remains unverified | **USER-REPORTED PASS (normal use); WAIVED (scale stress)** |
| L-07 | Through real SAF UI imported A (2 new), B (1 new), and one individually selected file (1 new). Deleted only the created `QA-DeleteOnlyThisFixture.flac`, then re-imported A: “1 track · 0 new · 1 no longer found”. UI showed only that fixture as File not found. Retained A/Alpha, B/Bravo and single/Charlie each reached PLAYING in RideLink's MediaSession | A rescan must affect only its source; B and the individual file remain playable, only the deleted A file missing | **PASS — PHYSICAL** |
| L-08 | Music scanning showed 0 then 60 found; Cancel at 13:08:15Z produced Import cancelled at 13:08:17Z and left count 21. No Music import was confirmed by automation. Disposable PrivacyMusic preview counted 2 supported tracks including a nested subfolder, named Recordings (1), defaulted exclusion on, offered Import 1 track; completion was “1 track · 1 new”. Search showed QA-Nested and no QA-DisposableRecording | A dedicated 20-file CancelImport fixture showed “Checking 3 of 20”; Cancel produced Import cancelled and retained count 58. Re-import completed “20 tracks · 20 new”, count 78. These meet §4.3a counts/progress/cancellation criteria. Cancellation after partial indexing writes was not observed | **PASS — PHYSICAL (fixture scope)** |
| Q-01 | Previous physical queue/duplicate/reorder/removal, transport and Clear→Play checks retained. Continuation: actual music reached PLAYING; Clear followed by Android media Pause (0.147 s after Clear tap return) left queue empty and no session/service at two samples. Pause was unavailable in the cleared UI; app receipt of the late Pause was not established. The user confirmed audible playback, correct Previous/Next songs and lock-screen Pause/Resume. A candidate Play followed a genuine BUFFERING sample, but the load completed within the command interval | Physical Q-01B overlap is **INCONCLUSIVE**; deterministic suspension-point software regression PASS remains separate. User confirmed final Clear stopped music without restart. User delegated disposition: submit Q-01B as INCONCLUSIVE for independent review; overlap remains unverified | **PARTIAL PHYSICAL + USER-CONFIRMED PASS; Q-01B INCONCLUSIVE** |
| N-01 | Music-only playback on the actual locked OnePlus: user confirmed audible continuation, correct title/artist/artwork/duration/progress and Previous/Pause/Next. User-operated lock-screen Pause produced PAUSED at 20826 ms, unchanged over two samples; later user explicitly confirmed audible lock-screen Resume. Fresh app-only notification blocks contained only music id 2, PUBLIC/transport/MediaStyle, no false microphone/intercom text; foreground service was music-only. User confirmed no false labels in the M2 check | Black/stale automated lock-screen captures remain excluded. Actual rendering/audio PASS relies on user observation, supported by scoped device state | **PASS — USER-CONFIRMED + AUTOMATED PHYSICAL SUBCHECKS** |
| N-02 | Three distinct actual music tracks M1/M2/M3: user confirmed each lock-screen title/artist/artwork/duration/progress against the audible song. Previous to M2 and Next to M3 produced distinct matching metadata hashes. Fresh unlocked Quick Settings captures for all three matched app-notification title/artist, with Previous/Pause/Next and an artwork view | Compact OnePlus Quick Settings duration/progress: **NOT AVAILABLE** in captured card. XML artwork-view presence does not independently verify bitmap content; artwork comparison is user-reported on the actual lock screen. Prior synthetic checks retained separately | **PASS — USER-CONFIRMED LOCK SCREEN; AUTOMATED PHYSICAL QS TITLE/ARTIST/CONTROLS** |
| Lifecycle | Earlier background/recreation evidence retained. Continuation: Home→lock for 15 s→wake showed real keyguard and PLAYING at 32962 ms; user confirmed audible continuation. User-operated lock-screen Pause held 20826 ms across two samples; Resume later reached PLAYING and was explicitly confirmed audible. User confirmed unlock/return shows correct song, advancing position, playing state and three-track queue. Final Clear and normal Back exit left empty queue and no session/service | User explicitly confirmed final Clear stopped audible music and it stayed silent. Actual Pause sample was on M3 after intervening track advancement, not on the originally started M1; metadata hashes preserve attribution | **PASS — USER-CONFIRMED + AUTOMATED PHYSICAL SUBCHECKS** |

T1/T2 and M1/M2/M3 are labels for private music; metadata hashes in the sanitized evidence identify matching
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
fixtures. The earlier run ended with a library count of 78, including test data, not the required ~3,460-track library. Cleanup is limited to explicitly created fixtures; no library
reset, database patch, personal-file deletion or unrelated-source reconciliation occurred.

Lock-screen captures were black and UIAutomator reported “could not get idle state”. It leaves
old XML behind even when its process exits zero. Two stale lock-screen XML copies were detected
and **excluded**; the helper now requires a successful fresh-dump message before reading XML.
No lock-screen PASS is inferred from those captures. In that earlier run, the user was asked for actual visual/audio observations;
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
- `final-manual-continuation.json`: new UTC command ledger, app-specific session/notification comparisons, user observations, load-race limits and stop/exit evidence. Earlier evidence files are unchanged.

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

Current next action: independent review of the updated evidence and the explicitly retained
physical Q-01B limitation. Final audible Clear/exit confirmation is complete. No confirmed
functional defect requires a fix. Any future functional correction requires a separate fix branch/PR,
regression tests and independent review. This evidence PR stays draft; review is not acceptance.

## Final manual qualification continuation

The user explicitly confirmed normal music-library importing, displaying, browsing and playback,
and waived the original approximately 3,460-track L-06 stress criterion for the current Phase 9A.5
qualification because most discovered audio files are call recordings. This is a user-reported
functional observation and a scope decision, not a measured stress-test PASS. No recordings were
imported and no large synthetic library was generated. Performance at the original scale remains
an unverified risk. The original TEST_PLAN criterion is retained above and in §4.3a.

Before continuing, the isolated evidence worktree was clean at
`9896ed8c0b864e037600741766e748671b186736`; PR #19 remained open and draft on the existing
evidence branch. A fresh fetch confirmed the same merged application HEAD and tree. The original
checkout's three pre-existing modified documentation files were preserved. The physical CPH2707
was verified again on Android 16/API 36 using its explicit ADB serial; the emulator was not used.
The installed APK was read back again and the sideload verification helper returned the exact
APK and certificate hashes in the provenance table. No rebuild, installation, data clear or file
deletion was performed in this continuation.

Initial read-only observation at 2026-10-10T16:09:23Z found no RideLink MediaSession or foreground
service and no RideLink UI in the fresh hierarchy. The user was asked to open RideLink and prepare
three recognizable actual music tracks, labelled M1/M2/M3 for privacy. At that checkpoint the
manual observations were pending; the completed observations and current verdict are recorded below.

### Subsequent environment verification and Clear/Pause checkpoint (16:14–16:18 UTC)

At this checkpoint the original checkout still had its three pre-existing documentation edits.
The isolated evidence worktree also already had pending edits to this report, STATUS and TEST_PLAN;
those edits were preserved. Remote main and PR #19 head remained exactly `4104e81` and `9896ed8`;
the PR was open and draft. Physical model/API and installed APK readback were verified again,
with the exact APK and certificate hashes above. The emulator was excluded.

Q-01A: one recognizable actual music track was queued and started. RideLink's session progressed
from BUFFERING to PLAYING, with position 8864 ms and speed 1.0 at 16:16:47Z. Clear was confirmed;
an Android `KEYCODE_MEDIA_PAUSE` input completed 0.147 s after the Clear tap returned. A fresh
hierarchy showed an empty queue and no Pause control. At 16:17:31Z and 16:17:55Z RideLink had no
MediaSession or foreground service; a later fresh hierarchy still showed an empty queue. This
passes the observed stop/removal subcheck. **The UI did not allow Pause after Clear, and receipt
of the hardware Pause by RideLink was not established: this does not prove the app-level late-Pause
race.** Existing deterministic software regressions remain separate evidence. Process-scoped
RideLink logs and timestamped commands were preserved privately.

Q-01B remains **INCONCLUSIVE**: BUFFERING was genuinely observed, but no subsequent Play input
was shown to overlap that load. No physical race PASS is inferred from the eventual PLAYING state.
At that checkpoint, three recognizable real music tracks were queued for M1/M2/M3 observations,
with playback ready at Play; Q-01C, N-01, N-02 and lifecycle observations were pending. Their later
completion is recorded below. Checkpoint raw evidence remains private under
`/private/tmp/ridelink-9a5-final-20261010/`; the published final continuation JSON contains only
sanitized results. No app source, installed APK or personal media files changed.

### Completed human observations and automated continuation (16:21–16:43 UTC)

The user confirmed M1 remained audible through Home/background/lock, and the real lock-screen
card displayed the correct title, artist, artwork and duration/progress, with Previous/Pause/Next.
A user-operated lock-screen Pause stopped audible music and produced PAUSED at 20826 ms across
two samples four seconds apart. During the human-response interval playback had advanced to M3;
the raw capture's M1-prefixed filename is historical and is not track attribution. The session
metadata hash identifies M3. The user subsequently confirmed audible lock-screen Resume.

Automated media Previous selected M2 and Next selected M3 on RideLink's verified media-button
target. The user confirmed the audible song and real lock-screen metadata for each. M2's explicit
question also covered false microphone/intercom labels; the user answered “yes, all match”. After
M3, the user answered “yes, all perfect” to the questions covering its audible song/metadata,
unlock/return state and queue consistency, and audible lock-screen Resume. These are **user-reported
physical observations**, not automated perception. A bare earlier “done” was not used alone as
audible proof. The original Q-01A audible start was not observed by the assistant; its app-session
state proves the automated playback-state subcheck only.

Unlocked Quick Settings was reliably captured with fresh hierarchies for all three actual tracks.
Each title and artist exactly matched that track's scoped app-notification fields, with distinct
privacy-safe hashes and Previous/Pause/Next controls. An artwork image view was present; XML does
not prove its pixels match. User artwork comparisons apply to the real lock-screen cards.
Duration/progress was not exposed by the captured compact Quick Settings card: **NOT AVAILABLE**.
No SystemUI artwork or duration PASS is manufactured from the earlier synthetic files.

A bounded Q-01B attempt captured M1 BUFFERING immediately before an Android Play input and PLAYING
immediately afterward. The sampled player update timestamps differ by about 60 ms. This proves
loading occurred and the correct selected track eventually played, but not that Play reached the
coordinator while loading was still in flight. M2 was already PLAYING before its candidate input.
Therefore the physical race is **INCONCLUSIVE**, separate from existing deterministic regression
PASS. No delay hook, application instrumentation, rebuild or code change was introduced.

Final Clear emptied the three-track queue. Normal Back exit left no RideLink MediaSession or
foreground service. The last 3000 event-log lines, filtered to RideLink and this continuation's
local-time window, contained zero ANR, crash or process-death events. This is bounded observation,
not exhaustive failure proof. The user subsequently explicitly confirmed that final Clear stopped
music and it stayed silent. No new functional product defect is confirmed.

### Final disposition for independent review

The user explicitly confirmed final audible Clear/no-restart behavior and delegated the Q-01B
submission decision. The resulting disposition is **submit for independent review with physical
Q-01B INCONCLUSIVE** and the **existing deterministic software regression PASS** separately
recorded. The suggestion to mark it PASS is not execution evidence: no physical load/Play overlap
is claimed. This adjudicates submission scope; independent acceptance remains outstanding.

Q-01A's observed physical stop/removal behavior passed with Pause unavailable after Clear. The
late application-level Pause race is covered by existing deterministic software evidence, not
proven by the media-key input. Q-01C audible Play/Pause/Previous/Next/Clear is user-confirmed.
N-01 and N-02 real lock-screen observations and lifecycle background/unlock/transport/normal
stop-exit are complete for this scope. Compact Quick Settings duration/progress stays NOT AVAILABLE;
its artwork pixels were not independently compared by automation. L-06 normal-library use is
USER-REPORTED PASS; original scale stress is WAIVED and large-library performance remains unverified.
No critical application defect was discovered in this continuation. All peer/iOS/two-device/
Bluetooth/helmet gates above stay DEFERRED. No Phase 9B work, merge or release-readiness claim.

Only this report, STATUS, TEST_PLAN and the new sanitized final continuation JSON are updated.
The previous successful evidence files, historical Phase 9A report, original checkout's local
changes and original artifact hashes are preserved. No rebuild, reinstall, data clear, media-file
deletion or app code change occurred in this continuation. Documentation/JSON consistency,
local-link, privacy and diff checks are the relevant validation; previously completed application
and security suites were not rerun.
