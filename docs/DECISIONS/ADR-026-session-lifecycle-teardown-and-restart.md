# ADR-026 — A session may enter `IDLE` only when it is terminal, and a user may start another

**Status:** Accepted · 13 Sep 2026
**Relates to:** [ADR-008](ADR-008-requirement-conflict-resolutions.md) (the state names), [ADR-019](ADR-019-connected-means-authenticated.md) (what `Connected` means), [ADR-021](ADR-021-intercom-transmission-and-capture-ownership.md) (capture ownership, and Amendment A7's `revision_epoch`), [ADR-023](ADR-023-bulk-transfer-session-binding.md) / [ADR-024 A3–A7](ADR-024-synchronized-playback-integration.md) / [ADR-025](ADR-025-inbound-control-frame-provenance.md) (the same "authorised is not still-authorised" family, one and two layers down)
**Governs:** [ARCHITECTURE §3](../ARCHITECTURE.md#3-session-state-machine), [§6.4](../ARCHITECTURE.md#64-android-ride-lifecycle-and-the-background-microphone-rule)
**Vectors:** `protocol/vectors/session-fsm/fsm_vectors.json` — one row changed (`disconnected-retry-to-discovering` now carries `RELEASE_AUDIO_AND_STOP_FOREGROUND_SERVICE`), and both platforms' vector tests now assert that effect in **both** directions rather than only its presence.
**Wire format:** **unchanged.** No new field, no new type, no changed bound, no changed encoding.

## Context

`SessionFsm` has carried two transitions since Phase 1a that **no production code could ever
trigger** (`docs/STATUS.md` §4 problem 53):

| Transition | Event | Emitted by production? |
|---|---|---|
| `ENDING -> IDLE` | `TeardownComplete` | no — on either platform |
| `DISCONNECTED -> DISCOVERING` | `RetryRequested` | no — on either platform |

Both were in the table, mirrored on both platforms, covered by the FSM's own vector tests, and drawn
in ARCHITECTURE §3.1's diagram. Neither existed anywhere outside `SessionFsm` itself. So once a
session reached `ENDING` — a peer `BYE`, or the user ending the ride — or `DISCONNECTED` — the
reconnect budget spent — the app could not return to discovery at all. `MainScreen`'s one button
called `startDiscovery()`, which `SessionFsm` correctly refuses from any state but `IDLE`, so it did
nothing and the rider had to force-quit and relaunch.

On a motorcycle that is not an acceptable end state, and REQUIREMENTS' reconnect story assumes
otherwise.

### Why emitting the events is the easy half

`TeardownComplete` is an **ownership claim**, not a label. Before this change the `ENDING` effect ran:

```
releaseVoiceAndAwait()     ← awaited
foregroundService.stop()   ← only on a proven release
teardownSession()          ← cancels the session job …
    └── scope.launch { controlSessionManager.shutdown() }   ← …and LAUNCHES this, then returns
```

Emitting `TeardownComplete` where `teardownSession()` returns would have moved the FSM to `IDLE` with
a `shutdown()` still pending. A successor started from that `IDLE` would call `startListening()` —
binding a listener, clearing `isShutDown` — and the predecessor's `shutdown()` would then land on top
of it: closing that listener, re-latching `isShutDown` so `promote` refuses every connection the new
session ever completes, and (through `relays.reset()`) detaching the successor's sinks. The same
shape iOS had, with an extra `Task` in `releaseVoice()` doing the sink clearing.

That interleaving was unreachable **only because problem 53 stopped the successor from existing**.
The Phase 2b `AUDIO_STATE` session already hit its shadow: two of its four CI failures were the same
trailing-teardown ordering, "fixed" by avoiding the `ENDING` path in the harness entirely
(`docs/STATUS.md` §2aj). Fixing 53 without fixing the ordering would have turned a latent race into a
live one, which is why both move in this ADR.

## Decision

### 1. The invariant

> **A session may enter `IDLE` only after every effect owned by the ending session has completed. No
> continuation owned by the retired session may mutate coordinator, relay, voice, playback,
> discovery, foreground-service or control-session state after `TeardownComplete`.**

The event name is now literally true, and nothing weaker is acceptable: `TeardownComplete` is the one
event that opens the door a successor walks through.

### 2. One teardown owner, and it answers "when"

`SessionTeardownOwner` (mirrored: `com.ridelink.app.session` / `RideLinkPlatform`) holds the **latest**
teardown and nothing else:

- `retire { … }` chains — a teardown waits for the previous one before it starts, so two of them can
  never interleave their steps on the one shared `ControlSessionManager`;
- `pending` is joinable/awaitable — a **successor** joins it before touching anything shared, so
  "Session B cannot start until Session A is terminal" is structural rather than a claim about which
  coroutine happens to be scheduled first.

`SessionCoordinator.retireSession` is the only caller. It is reached from exactly three places —
`ENDING`'s FSM effect, the user's retry, and Stop/Start Discovery — and is the only thing on either
platform that tears a session down.

### 3. Synchronous capture, then an awaited body

`retireSession` runs in two halves, and the split is the whole safety argument.

**Synchronously, on the caller's stack, before it returns:** the voice controller, the two relay
sinks it installed, the session runtime, the ordered event channels and the "control plane started"
flag are all read out of the coordinator's fields and the fields are cleared. After that line no
field the coordinator holds belongs to the session being retired.

**Then, asynchronously, in the teardown body:** the captured references — and *only* the captured
references — are used, in this order:

1. capture is released and **awaited**, then the foreground service is stopped if and only if the
   release is *proven* (problem 32's rule, unchanged: a `TimedOut` is never proof);
2. every continuation the session started is **cancelled and joined** — `cancelAndJoin` on Android's
   one `SupervisorJob`, `cancel()` then `await task.value` over iOS's explicit `sessionWork`
   registry;
3. `ControlSessionManager.shutdown()` is **awaited**, not launched;
4. and only then, if the transition was into `ENDING`, `TeardownComplete`.

Each captured reference **is** the ownership token. No generation counter is invented, because the
reference itself already answers "whose?" exactly — the same reasoning ADR-024 Amendment A3 gives for
capturing a generation rather than re-reading one, applied at the session layer.

**Cancellation is not completion**, and this is the fourth ADR in a row to say so. A cancelled
coroutine has only been *asked* to stop; `NsdDiscoveryController`'s two `callbackFlow`s do their real
work — `unregisterService`, `stopServiceDiscovery` — in `awaitClose` handlers that run afterwards, and
iOS's `sessionWork` tasks park in `await`s on the `ControlSessionManager` actor that ignore
cancellation entirely. Joining is the proof; cancelling only makes the proof arrive promptly.

iOS carries one extra step the synchronous capture cannot cover: `attachVoice` suspends several times
while *installing* things, so a retired attach could hand the successor the predecessor's voice
subsystem. It re-proves ownership before every install, using the `sessionWork` registry itself as
the token (`ownsSessionWork(id)`) — the registry is emptied synchronously by a retirement, so the
check is exact and, being on the main actor, atomic with the statement that follows it. The teardown
body then re-reads `self.voice` **once**, after every continuation is terminal, to catch an install
that legitimately completed between the capture and its cancellation taking effect. That is the one
point at which re-reading live state is correct rather than ADR-025's defect: the session that could
write it is over, and the successor that will cannot have started, because it is awaiting this very
task.

### 4. `RetryRequested`, and what a retry means

`DISCONNECTED` is ARCHITECTURE §3's "awaiting user", and it stays that way: **the retry is a user
action and never an automatic one.** PROTOCOL §10's ladder is the app's one reconnect loop and its
120 s budget exists on purpose; silently re-entering discovery when that budget is spent would be an
unbounded background loop wearing the radio for a peer that may simply be switched off.

`SessionCoordinator.retryDiscovery()` (`retryDiscovery()` on both platforms) emits `RetryRequested`
and is wired to the one session button, which now shows **Retry** in `DISCONNECTED`, **End Session**
in `CONNECTED`/`RIDE_ACTIVE`/`RECONNECTING`, **Stop Discovery** in `DISCOVERING` and **Start
Discovery** in `IDLE`. Every one of those maps to an event `SessionFsm` accepts from that state; no
button is offered for a transition it would reject.

`endSession()` is new alongside it, emitting `UserEnded` — before this change the only way to reach
`ENDING` at all was the *peer's* `BYE`.

### 5. ARCHITECTURE §3 rule 3 is amended: two deliberate ends, not one

Rule 3 read *"Only `ENDING` may release the audio session and stop the foreground service."* It now
reads:

> Only a **deliberate end** may release the audio session and stop the foreground service. There are
> exactly two: entering `ENDING`, and the user's explicit retry out of `DISCONNECTED`. A link blip
> (`RECONNECTING`) is neither.

This is a clarification of the rule's purpose, not a reversal of it. Rule 3 exists to stop a *link
blip* releasing capture: `RECONNECTING` keeps the microphone and the foreground service, because
ARCHITECTURE §6.4 gives no second chance to reopen a microphone once the screen is locked.
`DISCONNECTED -> DISCOVERING` is the opposite case — the budget is spent, the peer is gone, the user
has explicitly asked to look for one again, and they are by definition looking at the screen to have
asked, so `RideStartPolicy`'s foreground-visible requirement is satisfiable again the moment a peer
returns. Holding the duplex Bluetooth profile open (ADR-016's central risk) for a peer that is not
there is exactly what should not happen.

Keeping the old `VoiceController` across the retry instead was considered and is worse: its
`isLocalLeader` belongs to the session that ended, and ADR-020 makes the WebRTC offerer role a
property of *this* session's ADR-010 leader — a retry may well find a different peer.

**The FSM, not a coordinator, says which ends are deliberate.** `SessionFsm.transitioned` emits
`ReleaseAudioAndStopForegroundService` for both, so "when is audio released" remains a single pure,
mirrored, vector-pinned decision. The vector row changed and both platforms' vector tests were
tightened to assert the effect's **absence** as well as its presence — they previously only checked
presence, so an edit that attached the effect to every transition, `RECONNECTING` included, would
have passed.

### 6. A sink belongs to whoever installed it (`docs/STATUS.md` §4 problem 54)

`ControlSessionManager.shutdown()` used to call `relays.reset()`, which nulled **all seven** relay
sinks. That is right for two of the five families and wrong for the other three:

- `voice` and `audioState` are installed per authenticated session by `SessionCoordinator`, which
  also detaches them — now synchronously, at the instant it retires the session;
- `manifest`, `transfer` and `playback` are installed **once per process**, in the constructors of
  `SharedLibraryCoordinator` and `SyncPlaybackCoordinator`. Those coordinators deliberately outlive a
  control-session boundary — that is what ADR-023 §3's and ADR-025's per-frame generation is *for* —
  and **nothing ever re-installs their sinks**.

So a single Stop Discovery silently and permanently disabled Phase 4 and Phase 5 for the rest of the
process. It was found while making a second session reachable, and it is the reason "problem 53 is
just a product gap" was too generous: without it a restarted session connects and then has no shared
catalogue and no synchronised playback.

`reset()` is now `resetCounters()` on all five relays: counters are this object's own, sinks are not.
Re-installing on `Connected` was considered and rejected — the read loop can deliver a frame before
an event collector observes `Connected`, so it would trade a permanent loss for a startup window.

### 7. What was found on the way, and fixed with it

- **`ReconnectController.cancel()` leaves the spent budget behind.** `shutdown()` now also `reset()`s
  it. Not reachable today (`promote` resets it too), but an exhausted 120 s budget handed to a
  successor is precisely this ADR's class of defect.
- **`startListening` inherited the previous session's `controlState`.** `shutdown()` leaves `ENDED`,
  and `startListening` used to `copy()` the row forward, so a brand-new session reported the dead
  one's ending on the transport banner until a connection happened to promote it. It now installs a
  whole fresh `ControlDiagnostics`. Invisible until a second session became reachable at all.

## Consequences

- `ENDING -> IDLE` and `DISCONNECTED -> DISCOVERING` are production paths for the first time, with
  the ordering guarantee that makes them safe.
- A ride can be ended and a new one started without relaunching the app. TEST_PLAN **I-06**
  ("aeroplane mode 3 min → `DISCONNECTED`; recovers on manual retry") becomes runnable for the first
  time; it remains **pending** because it is a two-device row.
- The successor race the Phase 2b session worked around is now *reachable by design* and closed by
  construction rather than by the absence of a caller.
- iOS's app-target `SessionCoordinator` still has **no test bundle** (`docs/STATUS.md` §4 problem
  22). The ownership primitive was extracted into `RideLinkPlatform` so at least that is directly
  tested there; the wiring around it is proven on Android and mirrored by inspection. This is stated
  rather than smoothed over.
- `ERROR` remains unreachable — nothing emits `FatalError` — so `ErrorAcknowledged` is still a
  transition no production code triggers. That is the **remaining** instance of problem 53's class
  and is recorded as such rather than papered over with a button for a state that cannot occur.

## Alternatives considered

**Emit `TeardownComplete` from wherever the teardown happens to finish.** Rejected: that is the
defect, not the fix. Multiple components independently emitting it, or one emitting it with cleanup
still trailing, is what makes the event a lie.

**Delete `RetryRequested` and route the retry through `UserEnded -> ENDING -> IDLE -> StartDiscovery`.**
A legitimate reading of "a transition nothing triggers" — but it contradicts ARCHITECTURE §3.1's
diagram, loses the distinction between "the user gave up on this peer" and "the user ended the ride",
and would make `DISCONNECTED` a state with no exit of its own.

**Keep capture open across a retry, so rule 3 needs no amendment.** Rejected in §5 above: it holds the
duplex profile for an absent peer and reuses a controller whose offerer role belongs to a dead
session.

**A session generation counter in the coordinator.** Rejected: the captured references already answer
the question exactly, and a second source of truth about "whose session is this?" is what ADR-024
Amendment A7 and ADR-025 are both about.
