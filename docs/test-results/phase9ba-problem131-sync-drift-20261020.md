# Problem 131: synchronized-playback drift chases a common-mode lag (investigation)

**Status:** root cause **partly established**. No production code changed. Reproduced on the JVM by
`android/app/src/test/kotlin/com/ridelink/app/sync/SyncPlaybackOutputLatencyTest.kt`, which is
**expected to fail** on current code. Physical retest pending.
**Date:** 20 October 2026. Base: `main` at `3627c3d`. The physical evidence came from build `47d7cde`.

## 1. Physical evidence (as reported; not re-measured here)

Two Android phones with built-in speakers: OnePlus Nord 5 (Android 16) as follower A, and moto g57
power (Android 17) as leader B. The tracks were 5-minute AAC m4a click tracks at 44.1 kHz. Tested
19–20 October 2026.

1. Within seconds of *Play on both phones*, both phones showed `local drift` of about −350 to
   −650 ms, with about ±120 ms jitter between samples and no trend. `peer drift` was similar. The
   ladder made 2–3 hard seeks and then reported `SYNC_FAILED` (*Music sync paused*), while music
   kept playing. This repeated on every new track (auto-advance) and after every RESUME (−400 to
   −650 ms right after a resume). `schedule error` read 100–716 µs.
2. One Mac microphone recorded both loudspeakers, so both phones were measured on one clock (0.5 ms
   resolution, validated two-click template fit). The inter-phone output offset was **≤ 2 ms**
   (n = 78 clicks): steady-state median 0, pause/resume 1.75 ms, seek ≤ 0.5 ms, fresh PLAY start
   0.75 ms.
3. In the first ~20 s after a synced start, playback audibly jumped **+250.25 ms** and then
   **−15.5 ms** on both phones together. The inter-phone offset did not change across the jumps.

## 2. Established facts (code, library bytecode and physical data)

**E1. The drift reading is not inter-phone error.** Both phones read about −0.5 s against the
shared timeline, yet the acoustic rig measured them ≤ 2 ms apart (observations 1 and 2). Most of
the reading is therefore something common to both phones. The rig had no timeline reference, so it
cannot say whether both outputs were *also* about 0.5 s late against the timeline.

**E2. A hard seek cannot remove a lag that the seek itself re-incurs, and the ladder has no model of
that lag.**
- `DriftController` seeks to the expected position *at the sample instant*:
  `android/core/src/main/kotlin/com/ridelink/core/playback/DriftController.kt:159`
  (`HardSeek(input.expectedPositionMs)`).
- `applyCorrection` performs that seek as-is:
  `android/app/src/main/kotlin/com/ridelink/app/sync/SyncPlaybackCoordinator.kt:3868-3877`.
- The third seek inside 60 s becomes `DeclareSyncFailure` (`DriftController.kt:148-155`). Ticks
  run every 5 s (`core/.../playback/PlaybackModels.kt:54`, `SyncPlaybackCoordinator.kt:3746-3752`).
  So a lag over 120 ms that survives a seek reaches `SYNC_FAILED` about 15 s into the epoch.
- `applyPlay` resets the drift state and `hardSeekCount` on every new epoch
  (`SyncPlaybackCoordinator.kt:3455`, `:3463-3470`), so the cycle repeats on every track.
- In Media3 1.11.0, the audio position comes from the `AudioTrack`, not from the `play()` call. The
  bytecode (`javap`) shows `AudioTrackPositionTracker.getCurrentPositionUs` reading
  `AudioTimestampPoller.getTimestampPositionUs` (presentation timestamps) or the playback-head
  estimate. It also shows a one-shot `onPositionAdvancing` callback that fires only when that
  position first moves after a start or reset.
- So after `play()`, and after a `seekTo()` while playing (which flushes the track), the reported
  position stays at its start value until output actually begins. Any such restart latency appears
  as negative drift. A hard seek re-incurs it.

**E3. Stale position, paired with a fresh instant (hypothesis b).**
- `ExoPlayerMusicPlayer` publishes `positionMs` only from a `delay(250)` loop
  (`android/audio/src/main/kotlin/com/ridelink/audio/player/ExoPlayerMusicPlayer.kt:211-220`,
  `:269`).
- `tickOnce` reads that cached value (`SyncPlaybackCoordinator.kt:3786`) and pairs it with a
  session instant computed *now* (`:3785`, `:3811`, `:3825`).
- The error is always negative and runs from 0 to about 250 ms, so its mean is about −125 ms. That
  matches observation 1's ±120 ms jitter, and it is drift by definition only. The JVM model shows
  that on its own, with zero output latency, it produces readings up to −206 ms, two hard seeks and
  `SYNC_FAILED`.
- iOS has the same flaw. `AVAudioEnginePlayer` publishes on a 250 ms tick
  (`ios/Packages/RideLinkPlatform/Sources/RideLinkPlatform/Player/AVAudioEnginePlayer.swift:296-319`,
  `:357`), and `tickOnce` reads the cached state
  (`.../Sync/SyncPlaybackCoordinator+Inbound.swift:1733`, `:1762`).

**E4. The leader re-anchors the follower with the same stale, lagging number.**
- After a hard seek and after `SYNC_FAILED`, the leader emits `PLAYBACK_STATE`
  (`SyncPlaybackCoordinator.kt:3876`, `:3890`). Its `position_ms` is the cached
  `player.playerState.value.positionMs`, and its `at_session_us` is *now* (`:3941`, `:3958`,
  `:3960`).
- The follower re-anchors its timeline to that and **resets its drift state, seek budget
  included** (`:4312-4320`).
- In the JVM model, this turns a common-mode bias into a real inter-phone offset of 193–552 ms,
  and the follower makes 5 hard seeks instead of 3. **Observation 2 did not see this.** Either the
  recordings did not cover the window after the leader's `SYNC_FAILED`, or the model's restart
  assumption is wrong in a way that matters (§3). This is the first thing the retest must settle.

**E5. The pre-roll is issued but never awaited (hypothesis d, partly).**
- `applyPlay` runs `Select → Load → Seek` and then arms `Start`
  (`SyncPlaybackCoordinator.kt:3475-3497`). `Load` returns as soon as `exoPlayer.prepare()` is
  *called* (`ExoPlayerMusicPlayer.kt:145-146`), not when the player is `READY`.
- ARCHITECTURE §7.2 says the player is "prepared and paused at `position_ms`, released at the
  deadline", but nothing checks that it is prepared.
- A track still preparing at the deadline adds start lag. That is one candidate for the larger
  cold-start lag that observation 3 implies. Its size is unmeasured.

**E6. Hypothesis (c), wrong anchor or units, is rejected except through E3 and E4.**
- `applyPlay` anchors the timeline at `(positionMs, effectiveAtSessionUs)` (`:3457`) and arms the
  start for the same instant (`:3497`).
- `applyTransport` and `applySeek` re-anchor at their own `effective_at` (`:3529`, `:3556`).
- `PlaybackTimeline` converts µs to ms consistently (`PlaybackTimeline.kt:38-51`).
- The defect is the pairing of a stale position with a fresh instant, not the anchor.

**E7. What the existing measurements did and did not measure.**
- The `schedule error` diagnostic is the sleeper's wake time against its deadline, taken *before*
  the player is told to start (`SyncPlaybackCoordinator.kt:3700-3706`).
- The emulator's `SyncScheduledPlaybackTest` (`android/audio/src/androidTest/.../SyncScheduledPlaybackTest.kt:105-112`)
  measured the same wake error (1.4–3.1 ms) plus the time until `playing == true` was published.
  It **never sampled the position after the start**, so the output start-up latency was invisible
  to it.
- TEST_PLAN's "proves … a start at a monotonic deadline" is true of the *command*, not of the
  output.

## 3. Inference (consistent with all observations; not measured)

- **Size and source.** Taking E3's mean of about −125 ms off the observed −350 to −650 ms leaves a
  common lag of about **−375 ms**. Its most likely source is Android's `AudioTrack` start/restart
  latency, as Media3's presentation-timestamp position reports it (E2).
- **The model's fit.** `SyncPlaybackOutputLatencyTest` with a 350 ms restart latency and a 253 ms
  tick reproduces the physical trace:
  - drift −423 to −556 ms;
  - two hard seeks, at 5 s and 10 s;
  - `SYNC_FAILED` at 15 s;
  - the two phones aligned with each other until the leader's `PLAYBACK_STATE` re-anchors the
    follower.
- **Observation 3, under this model.**
  - The first jump, +250 ms, would be the cold-start lag L1 minus the warm restart lag G after a
    seek.
  - The second, −15.5 ms, would be the difference between two warm restarts.
  - The model reproduces these only if L1 ≠ G. The test uses a single latency.
- **What the data cannot decide.** It cannot tell whether the *audio* is also late by about
  375 ms, or whether only Media3's reported position lags the audio. Both readings fit E1 and
  observation 2. They diverge only in E4's window.
- **The platforms measure different things.** iOS's position is render time
  (`playerNode.lastRenderTime` / `playerTime`, `AVAudioEnginePlayer.swift:321-326`), which excludes
  output latency. Android's is presentation time. So the two platforms' "actual position" are
  probably different physical quantities, which matters for any fix that must hold on
  Android ↔ iOS and over Bluetooth.

## 4. Why no fix in this change

The rule is to fix only when a fix is clearly correct and consistent with ADR-004 and ADR-024.

1. **Sample the live position at sample time** (fixes E3, and E4's staleness). This is clearly
   correct, on both platforms. On its own it does **not** clear the defect, because a lag of about
   375 ms is still over 120 ms, so the phones still reach `SYNC_FAILED`.
2. **Exclude a per-restart baseline.** This stops chasing a common-mode lag, but it also hides a
   *differential* output latency (helmet Bluetooth against TWS). Today the nudge tier corrects a
   differential under 120 ms. Losing that is a regression the evidence cannot rule out.
3. **Compensate the restart latency in the seek target and pre-roll.** This corrects both kinds,
   but it needs a learned latency in the pure, mirrored table (ADR-004 amendment and vectors). It
   also costs at least one audible seek per epoch until the pre-roll is compensated, and
   observation 3 suggests the cold-start and warm-restart latencies differ.
4. **Correct inter-phone error from the peer's reports.** This changes ADR-024 §10, under which
   each phone corrects itself against the timeline.

Options 2–4 are architectural decisions, and the Android ↔ Android speaker data alone cannot
validate them.

## 5. Recommended next steps (one variable at a time)

1. Fix E3 and E4 alone, on both platforms. Give `SyncPlayerPort` a live, timestamped position read
   (ExoPlayer `currentPosition` on the main thread; AVAudioEngine render time). Use it in
   `tickOnce`, in `emitPlaybackStateFrame` and on iOS. `SyncPlaybackOutputLatencyTest`'s
   stale-sample case must then pass; the common-mode case must still fail.
2. Instrument the restart latency on the phone: the monotonic time from each `start()`/`seek()` to
   the first position advance (Media3 `onAudioPositionAdvancing`, or polling `currentPosition`).
   Log it to the diagnostics export, never to a wall clock (rule 5).
3. Physical retest (TEST_PLAN §5.2, problem 131 note).
   - Record more than 60 s after each PLAY, RESUME and auto-advance.
   - Export both phones' diagnostics with sample timestamps.
   - Measure the inter-phone offset across the leader's `SYNC_FAILED` `PLAYBACK_STATE` (E4).
   - Repeat over Bluetooth on at least one phone.
4. Then choose among options 2–4 in an ADR-004 amendment, with the measured latencies (L1, G, and
   the Bluetooth differential) as evidence.

## 6. Resolution (22 October 2026, software only)

[ADR-004 Amendment A1](../DECISIONS/ADR-004-local-synchronized-playback.md#amendment-a1--22-october-2026--correct-the-inter-phone-error-not-a-lag-both-phones-share)
chose among §4's options on the evidence above. [ADR-024 Amendment A16](../DECISIONS/ADR-024-synchronized-playback-integration.md#amendment-a16--22-october-2026--10s-drift-is-now-measured-against-the-leaders-and-a-correction-re-states-nothing)
records what that changes in Phase 5.

| Finding | Disposition |
|---|---|
| E1 (common-mode) | The ladder acts on the inter-phone error against the leader's settled report, so only the follower corrects. This is option 4, the variant §4 left open. It is chosen because it is the one that neither chases a common lag (option 2's aim) nor hides a differential one (option 2's flaw) |
| E2 (a seek re-incurs the lag) | The hard-seek target adds a restart latency learned from the seek's own residual. This is option 3, without assuming any measured value |
| E3 (stale sample) | Live, instant-stamped reads on both platforms: §5 step 1, as recommended |
| E4 (leader re-anchoring) | The leader no longer corrects, so it no longer emits a correction-caused `PLAYBACK_STATE`. The remaining snapshots use live samples. Because the error is a difference of two drifts against the **same** timeline, a re-anchor cancels out of it either way |
| E5 (pre-roll not awaited) | Unchanged and recorded. It lengthens a cold start, but under A1 a lag both phones share is no longer corrected, so it no longer causes a seek |
| §5 step 2 (restart-latency instrumentation) | Partly. The learned `restartCompensationMs` and the inter-phone `syncErrorMs` are now on both diagnostics screens. A direct per-restart measurement is still not built |

`SyncPlaybackOutputLatencyTest` now passes. Its two original cases are unchanged. A third case, a
differential lag that must be corrected, was added. §5 steps 3 and 4's physical retest is still
required. TEST_PLAN §5.2's problem-131 note has been rewritten for the amended model.
