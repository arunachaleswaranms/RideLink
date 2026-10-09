# Phase 9A.5 — release UX and large-library reliability

Baseline: `main` at `5575269ed1183972524ac9552f9c6fa444540da5` (PR #17).
Branch: `phase9a-5/release-ux-library`. **Not merged; stopped for independent review.**
Nothing here is a physical qualification result. The OnePlus Nord 5 was attached over wireless adb
throughout and **nothing was installed on it**: every instrumented run pinned `ANDROID_SERIAL` to
the `RideLink_API36` emulator.

Phase 9A's physical run found five defects and a UX backlog
([PHASE9A_ANDROID_PHYSICAL.md](PHASE9A_ANDROID_PHYSICAL.md) §11, §14; STATUS §4 problems 112–116).
This phase owns them. The networking, security, session, voice and synchronisation architecture is
unchanged: no wire format, vector, FSM, trust or authority rule moved.

## 1. What changed, by problem

| Problem | Root cause | Fix | Decision record |
|---|---|---|---|
| **114** — cold launch / clear search ANR with 3,460 tracks | The library was a `Column` composing every row inside the home screen's `verticalScroll`; a `LazyColumn` cannot live there (infinite height) | The library, Up Next and the other phone's music are separate destinations, each a `LazyColumn` with the remaining finite height; the home screen is bounded | Presentation only — no ADR |
| **115** — importing folder B marked folder A `MISSING` | Reconciliation's previous set was every row (`missing = all − this scan`) | Rows carry provenance (tree / MediaStore / file; Room v3 with a real migration); `ScopedReconciliation` lets a scan mark only its own rows missing | ADR-005 Amendment A2 |
| **112** — music-only notification said "intercom active" / "Microphone open" | One notification with fixed intercom copy | `RideNotificationPlanner` projects copy and actions from what is actually active | ADR-022 Amendment A1 |
| **113** — Mute / End intercom never drawn on Android 13+ | They were actions on the `MediaStyle` notification, which SystemUI draws from the session and whose own actions it ignores | The intercom has its own plain notification; music keeps the media-session notification | ADR-022 Amendment A1 |
| **116** — lock-screen card kept the first track's metadata | SystemUI reads the session when the notification is posted; it was re-posted only on type/mute changes | The service re-posts the media notification on item, metadata, duration and play-state changes, de-duplicated by content | ADR-022 Amendment A1 |

Found and fixed along the way:

- **Now Playing depended on the library search** (both platforms, pre-existing). The current track
  was found by scanning the search-filtered list, so typing a search made Now Playing — and on iOS
  the lock screen's Now Playing info — show "Shared track". It is resolved by `LocalEntryId` now.
  The shared-track "play here" lookup had the same dependency and now queries the repository.
- **The other phone's catalogue was eager** (both platforms, pre-existing): problem 114's shape with
  the peer's library as input, plus a second eager "playable on both phones" list. Now one lazy list.
- **A hard `stop()` left the secondary notification behind** (introduced and fixed in this branch,
  found by `IntercomNotificationSurfaceTest`): the platform removes only the foreground notification
  when a service stops. `onDestroy` now cancels both.
- **In-app Mute did not update the notification**, and every other refresh reset it to "Mute"
  (pre-existing). The voice facts now have one source, `RideNotificationSource`.
- **Library sorting ran on the main thread** (pre-existing): Room emits off-main, but the `map`
  ran in the collector's context — the main-thread coordinator scope — once per inserted row during
  an import. It now runs on `Dispatchers.Default`, with a total, deterministic order.
- **Thumbnails were decoded at up to 1,024 px** for a 48 dp row; they are now downsampled to the
  drawn size and cached.

## 2. Library performance — measured, then kept simple

Brief §6 asked to measure before adding Paging. The repository's Kotlin sort of 5,000 rows takes
about 1 ms on the JVM (`LibraryRepositorySortTest`, logged); the cost that froze the phone was
composition, not data. So: no Paging, no SQL-side sort. The sort moved off the main thread and
became total (ties broken by `localEntryId`).

`LibraryLazyCompositionTest`, real `LibraryContent` with 5,000 synthetic tracks, API 36 emulator:

| Moment | Row nodes composed | Time |
|---|---|---|
| First render (includes Activity launch) | 12 | 1,642 ms |
| Clear search back to 5,000 | 12 | 116 ms |
| Sort change | 12 | 155 ms |
| Scroll to the last track | 13 | 100 ms |

These are emulator figures. They are not a phone measurement and are not asserted on; the test
asserts only that no more than 60 rows ever exist. The old eager shape does not reach the assertion:
composing 5,000 rows exhausted the emulator's 192 MB heap inside Compose's slot table.

## 3. Import progress and folder privacy

A folder import is now **walk → summary → index**. The walk writes nothing. The summary names the
folder, says that subfolders are included, gives the real track count, and names any subfolder whose
exact name says it holds phone recordings ("Call Recordings", "Recordings", "Recorder", …; "Live
Recordings" does not match). A switch, on by default when such a folder exists, leaves them out of
that import; a skipped file is neither indexed nor marked missing. Nothing leaves the phone and
nothing is classified by content.

Progress shows only numbers the indexer knows: "Scanning “Music”… · Found 842 tracks" (an
indeterminate bar — a walk does not know its total), "Checking 320 of 842", "Indexing 120 of 800",
then "Imported “Music” · 842 tracks · 12 new". Content hashing is reported separately as
"Preparing tracks for sharing… 120 of 3,460". Cancel stops the job; what was written stays valid,
and re-importing the folder finishes it. No quick ID, hash or URI is shown.

## 4. Queue and transport

- **Play with tracks queued and nothing selected starts the first one** (`LocalQueueAction.Play`,
  mirrored in RideLinkCore). A selected track is resumed. A synchronised session still takes the
  press first through the existing gate (ADR-024 A14); the local rule does not touch that path.
- **Up Next** (both platforms): tap to play, move up/down (Android) or Edit/drag (iOS), remove,
  clear with confirmation. Every action names a queue entry id; duplicates stay separate entries.
  While synchronised playback is on, the screen says the shared queue decides what plays.
- **Transport**: Previous · Play/Pause · Next, Play/Pause the one filled, larger control. Now Playing
  keeps its height when playback starts (the seek row is always there), and seeks once when a drag
  ends. Library and Up Next carry a fixed-height mini player. Ride Mode keeps 72 dp targets with the
  same hierarchy.

## 5. Copy

"Your ride, together" is removed on both platforms. Primary UI says "other phone"; "peer" stays in
diagnostics. One phrase per idea on both platforms:

| Idea | Before | Now |
|---|---|---|
| Start discovery / retry | Find peer / Find peer again | Find other phone / Search again |
| End the intercom | Stop Intercom (button), End intercom (notification) | End intercom (everywhere) |
| Push to talk | Hold to talk / "Push to Talk held · Release to stop" / iOS "Start talking" | Hold to talk / Talking — release to stop / Push to talk unavailable / Unmute to talk |
| Connection | No peer connected / Finding your peer… / Verify pairing | Not connected / Searching… / Check the code |
| Found | Peer found · Connecting automatically (forever) | Other phone found. Connecting… → after 15 s: Couldn't reach the other phone yet… |
| Intercom state | Intercom not started / Intercom active / Microphone ready / Transmitting | Intercom off / Intercom on / Microphone on / Talking |
| Modes | A · Continuous / duck music … | Always on · music lowered, Push to talk · music lowered, … (letters stay in diagnostics) |
| Sync | Local playback / Synchronized | Playing on this phone / Playing on both phones |
| Notification (music only) | RideLink intercom active / Microphone open for the intercom. | Track title / artist (or "Playing music") |

## 6. Motion — every animation and why it exists

| Motion | State it communicates | Reduced motion |
|---|---|---|
| Small spinner beside the connection title (Android `CircularProgressIndicator`, iOS `ProgressView`) | The app is working: searching, connecting, reconnecting, ending. A still dot otherwise | Still dot |
| Connection title cross-fade (180 ms) | The state changed | Instant |
| "Other phone found" fade/expand in | Discovery found the other phone | Instant |
| "Paired" check for 2 s (Android) | Pairing just succeeded | Instant |
| PTT 3 % scale + colour + elevation while held | The controller reports talking (follows the reported state, never the finger) | Instant |
| Indeterminate bar while scanning; determinate bar while checking/indexing/preparing | Work in progress, with a total only when one is known | Static bar (system scale) |
| Mini player 2 dp progress line | Position in the current track | — (not animated) |
| Snackbar / toast "Added … to Up Next" | The add happened | Platform default |

No decorative loop, hero animation, gradient, particle or parallax was added. Compose animations
also follow the system animator duration scale.

## 7. Design principles adopted

No external UI reference collection was inspected for this phase (none was available to the
session). The principles come from the platforms' own conventions (Material 3, Apple's HIG) and
from the Phase 9A observations:

1. State before decoration: the connection line is the first thing on screen, in words, with a dot
   or spinner — never colour alone.
2. Sections are headings and spacing, not bordered boxes; a surface is used only for a unit you act
   on as one thing (Now Playing, a pairing code, an alert, a group of links).
3. Long lists are destinations, never inline: anything that grows with a library is lazy and has a
   finite height.
4. One primary control per area: Play/Pause, Start ride, Import — the rest is secondary or tonal.
5. Predictable layout: things that appear when playback starts (seek bar, times, mini player) are
   always present, so nothing moves under a finger.
6. Numbers are real or absent: no invented percentage, no "connecting automatically" that cannot be
   true.
7. Diagnostics stay precise and stay behind disclosures.

## 8. Accessibility

Transport buttons have "Previous track", "Play"/"Pause" (with a Playing/Paused state), "Next track";
library rows merge into one node with a "Play" click label and a "Now playing" state; add/remove/move
buttons name their track; headings carry heading semantics; PTT exposes its state plus "Start
talking"/"Stop talking" actions; the import switch is a toggleable row; progress text is a polite live
region. Touch targets are at least 48 dp (rows 64 dp, Ride Mode 72 dp). Text uses theme type scales
and wraps or ellipsises rather than clipping.

## 9. Still physical — after merge, on the OnePlus Nord 5 with ~3,460 tracks

TEST_PLAN §4.3a: L-06 (cold launch, clear search, rapid scroll, sort — no ANR), L-07 (folder A/B and a
single file stay independent), L-08 (import summary, recordings, progress, cancel), Q-01 (queue, Play,
Up Next), N-01 (music-only notification copy), N-02 (three-track lock-screen metadata progression),
lifecycle smoke. **N-03 (live intercom notification controls) and N-04 stay PENDING — AUTHENTICATED
PEER UNAVAILABLE.**

## 10. Validation

The exact commands, counts and the one intermittent iOS stress-test observation are in
[STATUS.md](STATUS.md)'s 9 October 2026 entry, recorded after the final source change: Android unit
1,152/1,152, instrumented 75/75 (API 36 emulator), ktlint/detekt/lint clean, Debug and Release
assembled; iOS Core 363, Platform 679 (0 failures, 1 skipped), Debug and Release simulator builds;
cross-platform gate 17/17; local-only audit and gitleaks clean.
