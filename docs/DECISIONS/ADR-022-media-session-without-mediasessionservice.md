# ADR-022 — A real `MediaSession`, owned by the existing ride foreground service, without subclassing `MediaSessionService`

**Status:** Accepted
**Date:** 5 September 2026
**Phase:** 3 (local music player) closure-audit hardening pass
**Extends:** [ADR-014](ADR-014-initial-module-structure-and-di.md) (module/DI structure),
[ADR-021](ADR-021-intercom-transmission-and-capture-ownership.md) (the one ride foreground service)

---

## 1. Context

`docs/ARCHITECTURE.md` §6.1/§9.2 has said, since before Phase 3 implementation began, that Android
local music playback is "`androidx.media3` `ExoPlayer` inside a `MediaSessionService`" — the standard
Media3 shape for FR-019's background/lock-screen requirement. A closure-audit review of the completed
Phase 3 implementation found that production code never built this: `ExoPlayerMusicPlayer` wraps a
bare `ExoPlayer` with no `MediaSession`, no `MediaSessionService`, and no `MediaController`, anywhere.
`RideForegroundService`'s own KDoc is explicit that this was a deliberate, named gap ("no fake media
session is created to satisfy foreground-service semantics"), not an oversight — but the actual FR-019
lock-screen transport-control requirement was never implemented as ARCHITECTURE's own words describe.

This ADR corrects the implementation gap and, in doing so, corrects one word of ARCHITECTURE's own
description: not "inside a `MediaSessionService`," but inside the existing `RideForegroundService`, via
a real `MediaSession` attached to it directly.

### Why not literally subclass `MediaSessionService`

`RideForegroundService` is, by a wide margin, the single most hardened file in this codebase.
ADR-021 and its three amendments (A1–A3) record eight-plus confirmed defects found and fixed in the
exact area this ADR would touch if done carelessly: capture-device ownership, foreground-service
type computation from two independent subsystems (`intercomActive`, `musicPlaying`), `START_NOT_STICKY`
safety, and the precise ordering of `stopForeground`/`stopSelf` around a still-releasing microphone.
Every one of those fixes depends on **this service owning its own foreground-service-type and
notification lifecycle outright**, computed by `ForegroundServiceTypePolicy` from facts only this
process holds.

`androidx.media3.session.MediaSessionService` is not a neutral base class for that. Its whole
contract is: *you give it one `Player`, and it manages the foreground-service lifecycle and
notification automatically*, tied to that `Player`'s own `isPlaying`/`playWhenReady` state, via
`MediaNotification.Provider`. That model has no concept of:

- a **second, independent** reason (the intercom) to keep the same service alive and foreground even
  while `Player.isPlaying` is false;
- a **`microphone`** foreground-service type existing at all, let alone toggling independently of
  whatever `MediaSessionService`'s own automatic media-only lifecycle decides;
- the specific, already-fixed failure mode ADR-021 Amendment A1 Finding F exists to prevent — a
  service reclaimed by the platform while still holding the microphone, because *something* called
  `stopSelf()`/`stopForeground()` before capture release was proven complete.

Subclassing `MediaSessionService` would mean either fighting its automatic lifecycle management on
every one of those points, or reimplementing it badly under a different name. Both are strictly worse
than the alternative below, and both would put every already-fixed ADR-021 defect back in play under
new code paths no existing regression test covers.

## 2. Decision

**`RideForegroundService` remains a plain `android.app.Service` — not a `MediaSessionService` — and
owns a real `androidx.media3.session.MediaSession` directly, wired to the same `ExoPlayer` instance
`ExoPlayerMusicPlayer` already binds.**

- The service constructs the `MediaSession` once, alongside the player, and releases it in
  `onDestroy()`. No second `Player` and no second queue/state owner is created — the `MediaSession`
  is a **control-surface adapter** in front of the one real player `MusicCoordinator` already owns
  (ADR-014 rule: `app` is the one layer allowed to depend on both `audio` and `data`; the session
  lives in `app`, next to `RideForegroundService`, for the same reason).
- **Lock-screen/system integration goes through the notification, not through `MediaSessionService`'s
  binder.** The existing hand-built `Notification` gains `MediaStyle`
  (`androidx.media.app.NotificationCompat.MediaStyle`, via `NotificationCompat.Builder`, replacing the
  bare `Notification.Builder` used today) carrying the `MediaSession`'s compat token
  (`MediaSession.sessionCompatToken`). This is the same mechanism apps used for lock-screen media
  controls before `MediaBrowserService`/`MediaSessionService` existed, and it still works today — the
  system reads transport-control affordances from a `MediaStyle` notification's session token
  regardless of what kind of component created that session.
- **The existing custom actions (mute, end-intercom) and the new transport actions (play/pause/seek/
  next/previous) coexist in the same one notification.** `MediaStyle` supports both session-driven
  "standard" actions and a service's own custom `Notification.Action`s side by side
  (`setShowActionsInCompactView` selects which subset appears in the collapsed view). Two competing
  notification owners for the same service was never on the table (ADR-021's "no duplicate
  notification/service owners" holds); this is one notification, built by the one existing owner,
  now describing two kinds of control instead of one.
- **`MediaSession.Callback`'s `onPlay`/`onPause`/`onSeekTo`/`onSkipToNext`/`onSkipToPrevious` route
  directly to `MusicCoordinator`** (`play()`/`pause()`/`seek()`/`next()`/`previous()`) — the same
  single queue-owner every other entry point already calls, never a second path to playback state.
- **`ForegroundServiceTypePolicy`, the `intercomActive`/`musicPlaying` flags, `onStartCommand`'s
  action dispatch, `refreshForegroundState`, `onTaskRemoved`, and `START_NOT_STICKY` are completely
  untouched.** The `MediaSession` is additive: it changes what the notification looks like and what a
  lock-screen tap does, never when the service starts, stops, or which foreground type it declares.
  Every ADR-021 invariant this ADR's §1 lists continues to hold exactly as before, because none of the
  code that enforces them changed.

## 3. Consequences

**Good**

- FR-019's actual requirement — lock-screen/notification transport controls for local music — is now
  implemented, matching the intent ARCHITECTURE §6.1 always described.
- Zero new risk to the ADR-021 capture-ownership/foreground-type invariants: the code paths those
  amendments hardened are not modified by this change at all.
- One service, one notification, one player, one queue owner — unchanged from before this ADR.

**Costs / open risks**

- This has not run on a device, exactly like the rest of `RideForegroundService`
  (**REAL-DEVICE LOCAL-MUSIC GATE PENDING**). Whether the system actually renders the `MediaStyle`
  transport controls correctly alongside the custom mute/end-intercom actions, and whether a real
  lock-screen media-button press reaches `MediaSession.Callback` reliably, is TEST_PLAN L-04/L-05
  territory, not proven by a laptop/emulator build.
- A future maintainer reaching for `MediaSessionService` "because that's what the Media3 docs
  recommend" should read this ADR first — the recommendation is correct for a single-purpose media
  app and wrong for a service that already has a second, independent reason to exist.

## 4. Alternatives considered

| Alternative | Why not |
|---|---|
| Subclass `MediaSessionService` | §1's binding platform reason: its automatic lifecycle model has no concept of the microphone type or of a second subsystem keeping the service alive, and reimplementing that around it reopens every ADR-021 defect under new code paths |
| A second, separate `MediaSessionService` alongside `RideForegroundService` | ADR-021 and this phase's brief are explicit: **one** ride foreground service. Two services holding overlapping audio/notification responsibility is exactly the "duplicate notification/service owner" failure mode both this ADR and ADR-021 forbid |
| Leave FR-019 unimplemented, document the gap | Rejected: the audit's instruction is to implement the architecture-selected functionality unless a genuinely binding platform reason makes the documented architecture wrong — here the *class choice* (`MediaSessionService`) was wrong, but the *feature* (a real, system-integrated `MediaSession`) is achievable and is implemented |
| `MediaSessionService` with all automatic foreground/notification behaviour manually overridden off | Technically possible in principle but relies on suppressing library-internal behaviour that is not a supported/stable contract to disable piecemeal, for a benefit (inheriting `MediaSessionService`'s binder-based `MediaBrowser` discovery) this product does not need — there are no external `MediaController` clients (Android Auto, Assistant) in V1 scope |
