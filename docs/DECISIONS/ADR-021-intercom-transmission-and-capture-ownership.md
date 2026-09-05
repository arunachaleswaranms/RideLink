# ADR-021 — Intercom transmission gating and capture ownership

**Status:** Accepted
**Date:** 4 September 2026
**Phase:** 2b (intercom integration / audio lifecycle)
**Supersedes:** nothing. **Extends:** [ADR-003](ADR-003-webrtc-voice-transport.md),
[ADR-016](ADR-016-effective-audio-capability-model.md),
[ADR-020](ADR-020-webrtc-voice-foundation.md)

---

## 1. Context

Phase 2a gave RideLink a working voice plane: an authenticated signalling path, host-only ICE,
DTLS-SRTP, Opus, a deterministic offerer, and a shared negotiation table. It always sent
`VOICE_STATE { mode: "continuous" }`, because there was no intercom policy for it to report.

REQUIREMENTS §8 asks for five intercom modes (A–E) and ARCHITECTURE §6.3 already describes them as
**one policy object rather than five code paths**. What did not exist was the thing that *interprets*
that object: something that decides, moment to moment, whether outbound audio leaves this phone.

Two constraints make that decision unusually easy to get wrong, and they are the reason this ADR
exists rather than a few `if`s in the controller:

1. **Opening and closing a Bluetooth microphone per utterance is the worst thing this product can do
   to music.** It thrashes the endpoint between its media and duplex profiles — an audible 0.5–2 s
   route change each way (ARCHITECTURE §6.2), on the one shared resource both users are listening to.
   ARCHITECTURE §6.3 already says so; nothing enforced it.
2. **On Android, first-time microphone capture cannot legally begin from the background**
   (ARCHITECTURE §6.4). A push-to-talk press with the screen locked must therefore not be the moment
   the microphone is first opened — there is no second legal opportunity.

Both point the same way: **the capture device is a ride-segment resource, and speech gating is a
per-moment decision layered over it.** The obvious implementation — "PTT down opens the mic, PTT up
closes it" — violates both constraints at once, and would do so in a way that only a helmet unit at
speed would reveal.

The Phase 1b security bug (ADR-019) and `docs/STATUS.md` §4 problem 20 also apply here: a decision
that lives in a driver's `when`/`switch` is a decision no test suite can exhaust. The negotiation
table (ADR-020) was extracted for exactly that reason and the same reasoning governs this one.

---

## 2. Decision — capture ownership

**There is exactly one app-level owner of the platform audio session and the capture path, and it is
`VoiceAudioSession` (`AndroidVoiceAudioSession` / `IosVoiceAudioSession`), driven by exactly one
`VoiceController`, constructed at exactly one site.**

| Concern | Owner | Never |
|---|---|---|
| Platform audio session, focus, communication route, capture path | `VoiceAudioSession` | Not `VoiceController`, not `SessionCoordinator`, not the UI |
| WebRTC's own `AudioDeviceModule`/audio unit internals | the media stack, inside `WebRtcVoiceEngine` | Not reached from anywhere else |
| *When* capture opens and closes | `VoiceController`, from the negotiation table's two actions (`StartLocalAudio`, `ReleaseLocalAudio`) | Never from a PTT press, a mute, a policy change, a route change or a link blip |
| Whether a start is *legal* | `RideStartPolicy` (pure, shared) | Never inferred from whether a call succeeded |
| "The app is foreground-visible" | the resumed `Activity` / the SwiftUI scene phase | Never claimed by a coordinator or a view model, which cannot know |

Two consequences are load-bearing:

- **One `AndroidVoiceAudioSession` per process, not per voice session.** `AppContainer` constructs it
  once and hands the same instance to every `VoiceController` it builds. Two instances would be two
  objects that each believe they own `AudioManager`'s mode, focus and communication device across a
  reconnect — and the whole reason `VoiceEngine.stop()` and `release()` are separate calls (ADR-020
  §6) is that the audio session must *survive* a control-plane blip. It is also what lets the
  readiness gate ask whether an endpoint exists before an intercom has ever been started.
- **The media stack's local audio track is not the capture device.** Gating happens on the track;
  the device is untouched. See §4.

---

## 3. Decision — the policy model

ARCHITECTURE §6.3's object, as a shared pure type (`IntercomPolicy`, mirrored in
`core.audiopolicy` and `RideLinkCore.AudioPolicy`):

```
policy := { mic_always_open: Bool,
            gate: none | vox(threshold_dbfs, hangover_ms) | ptt | disabled,
            on_speech: duck(to_pct) | pause,
            music_quality_priority: high | yield_to_voice }
```

Modes A–E are five *values* of this type. **No code branches on a mode id**; the five presets exist
for the UI and for the diagnostics screen, and `IntercomPolicy.byId` is the only place they are
looked up.

**`mic_always_open: false` refers to whether speech is transmitted, never to whether the capture
device is opened and closed.** That sentence was already in ARCHITECTURE §6.3; this ADR is what makes
it structural rather than aspirational (§4).

**Full duplex remains the primary capability.** `gate: none` is the no-gate policy and is what Modes
A and D use; `IntercomPolicy.fullDuplex` reports it. PTT and VOX are fallbacks layered over the *same
live capture path* and the *same* WebRTC session — never a different transport, and never a
downgrade of the media plane's ability to send and receive simultaneously.

### The two mode vocabularies differ by one value, deliberately

PROTOCOL §4.4 describes `AUDIO_STATE.intercom_mode` as mirroring `VOICE_STATE.mode` while listing
**four** values against that field's **three**. That was a contradiction in the specification, and
resolving it is part of this decision:

| Field | Values | Why |
|---|---|---|
| `VOICE_STATE.mode` (§7.4) | `continuous` · `vox` · `ptt` | Describes the gate of a **live voice session**, so it never has to describe the absence of one |
| `AUDIO_STATE.intercom_mode` (§4.4) | `continuous` · `vox` · `ptt` · `disabled` | Describes the **local audio state**, which is meaningful with no voice session at all — Mode E is exactly that |

Mode E maps to `intercom_mode: "disabled"` and to `mode: "ptt"`, because ARCHITECTURE §6.3 spells
Mode E as "ptt-disabled" — it *is* a PTT policy whose button is never released. `IntercomMode` is
therefore a superset type rather than an alias, and the two mappings are one function each on
`IntercomPolicy`. **No wire value or bound changed**; the vocabularies were always these, and this is
the resolution of which field carries which.

### The default is Mode C, and that is not a measurement

`docs/PHASE0_RESULTS.md` is still awaiting the user's Phase 0 numbers, so **no device measurement has
selected a mode.** ARCHITECTURE §6.3 and [ADR-008 §4](ADR-008-requirement-conflict-resolutions.md)
both name Mode C (PTT) as the safest assumption until it is filled in, because it is the only mode
that cannot be broken by a duplex-profile switch mid-utterance. `IntercomPolicy.DEFAULT` is Mode C
for that reason and no other; the shared vectors state it, the tests assert it, and the intercom card
says so on screen. Nothing in this phase may be read as evidence that Mode C was validated on
hardware.

---

## 4. Decision — transmission gating

**Gating happens at the WebRTC audio-track level and nowhere else.**

```
IntercomTransmission (pure, shared, vector-pinned)
        │  transmitting = f(policy, captureOpen, userMuted, pttHeld, voxOpen, interrupted)
        ▼
VoiceController      offers  VoiceInput.MuteRequested(!transmitting)
        ▼
VoiceNegotiation     emits   SetMicrophoneMuted(muted) + SendVoiceState(..., mic_muted, ...)
        ▼
VoiceEngine          sets    localTrack.setEnabled(!muted)   /   RTCAudioTrack.isEnabled = !muted
```

`IntercomTransmission` is a pure `(state, input) -> (state, actions)` reducer, mirrored line for line
on both platforms and pinned by `protocol/vectors/intercom/` — a third, independent transcription of
ARCHITECTURE §6.3 written from the document rather than ported from either reducer. Its rule is one
expression:

```
transmitting = captureOpen && !interrupted && !userMuted && gateOpen(policy, pttHeld, voxOpen)
```

Five properties follow, and every one of them is a test:

1. **PTT touches nothing but the track.** Fifty press/release cycles produce 100 track-enable flips,
   **one** capture open, **zero** capture closes, no `PeerConnection` rebuild, and no change of
   `voice_session_id`. `VoiceControllerIntercomTest[s]` counts exactly that against a fake audio
   session; TEST_PLAN **A-10** is the same assertion against a real helmet unit's recorded output and
   remains pending.
2. **The gate has no capture action to emit.** `IntercomAction` has three cases and none of them is
   "open" or "close" — the invariant is enforced by the vocabulary, not by review. The shared vector
   file asserts it over every row.
3. **Mute wins over an open gate; an interruption and a closed capture path win over everything.**
4. **A policy switch cannot inherit a gate.** Switching *into* PTT clears `pttHeld`; switching out of
   VOX clears `voxOpen`. Otherwise a switch would open transmission on a button nobody is holding.
5. **The gate is the single source of `VOICE_STATE.mic_muted`.** PROTOCOL §7.4 defines that field as
   "this peer is transmitting silence", which is exactly `!transmitting`. There is deliberately no
   second path to `setMicrophoneMuted`.

Two implementation notes that are decisions rather than details:

- **The driver takes the gate's absolute value, not the `SetTransmitting` diff.** The reducer's
  actions are a diff, which is right for a table (a restated unchanged value would be noise in the
  vectors) and wrong for a driver: with a gated policy, capture opening leaves `transmitting` false on
  both sides of the transition, so no diff is emitted while the negotiation table still holds its
  `micMuted = false` default — and the wire would claim this side is transmitting. `VoiceNegotiation`'s
  `mute` is idempotent, so offering the absolute value on every intercom input costs nothing and
  closes that gap.
- **A new peer connection is a new track, and its enabled state comes from the gate.** Both engines
  enable the local track when they build it, which is correct for full duplex and wrong for every
  gated policy: under PTT a reconnect rebuild would go live before the first press. The controller
  pushes the gate's current value immediately after every successful `engine.start`, including a
  rebuild.

---

## 5. Decision — the audio-session lifecycle is a shared pure reducer

Neither `AVAudioSession` nor `AudioManager` can be executed off a device
(`docs/STATUS.md` §4 problem 23), so **every decision either of them would make is moved into
`AudioSessionLifecycle`**, a pure reducer shared by both platforms and unit-tested on both. What is
left in `AndroidVoiceAudioSession` and `IosVoiceAudioSession` is API calls.

It owns four things:

1. **`stable -> transitioning -> stable`** as observable state with a **measured** duration
   (`RouteTransitionState`, monotonic microseconds). ARCHITECTURE §6.2's "roughly 0.5–2 s" is an
   expectation *from documentation*; this records what actually happened, which is what TEST_PLAN
   IA-03 asks for.
2. **A transition settles on a platform callback, never on elapsed time.** Android uses
   `AudioManager.OnCommunicationDeviceChangedListener` (API 31+, exactly the ADR-011 `minSdk`); iOS
   uses `routeChangeNotification` with reason `.categoryChange`. A timeout exists **only** so a
   platform that never confirms cannot latch `route_state: transitioning` for the rest of a ride, and
   every use of it is counted (`timedOutCount`) so a timer-derived number is never mistaken for a
   measurement.
3. **`shouldResume` is read, not assumed.** An interruption that ends without it leaves the session
   inactive (TEST_PLAN IA-06). Reactivating anyway is how an app ends up fighting the platform for a
   route the user gave to something else.
4. **A strict generation guard on the audio session**, which is ADR-020 Amendment A2's rule applied
   one layer out: a media-services reset increments the generation, and **every** callback naming any
   other generation is inert — including a real one that used to be current.

Notifications reach the reducer through **one ordered mailbox with one consumer**
(`AudioSessionSignalBox`), not a `Task` per callback. A `Task` per callback preserves only the order
events were *created* in, which is STATUS §2h's bug; and the mailbox is coalesced by kind and
therefore **bounded by construction**, which is safe precisely because each signal answers "what is
the platform's state now" and the handler re-derives the route from the platform when it applies one.
A media-services reset gets its own slot so it can never be coalesced away by a later route change.

---

## 6. Decision — VOX is implemented as a policy and gate; its level source is deferred

`IntercomTransmission` implements VOX in full: a threshold, a hangover, a deterministic
open/renew/close state machine driven by monotonic microseconds, and vector rows covering every edge
including "quiet inside the hangover", "quiet past the hangover" and "a tick with no new level".

**No microphone-driven level source is wired on either platform, and that is deliberate.**

| Platform | What the pinned stack offers | Why it is not enough |
|---|---|---|
| Android (`io.github.webrtc-sdk:android:144.7559.14`) | `audioLevel` / `totalAudioEnergy` on the statistics report | RideLink polls statistics every 2 s. Speech gating needs tens of milliseconds — three orders of magnitude out |
| Apple (`stasel/WebRTC` `152.0.0`) | the same statistics fields | Same |
| Either | a raw-PCM samples callback plus a hand-written detector | This phase's brief §7 forbids inventing a DSP detector to fill a box, and ADR-003 forbids custom echo/noise DSP for the same reason: an unmeasured detector is worse than an honest gap |

So the honest state is recorded rather than hidden: `VoiceDiagnostics.voxLevelSourceAvailable` is
`false`, the intercom card **says on screen** that selecting Mode B cannot open the gate yet, and this
is marked **PENDING REAL AUDIO INPUT / LATER HARDENING**. The threshold and hangover defaults
(−35 dBFS, 700 ms) are reasoned starting points for TEST_PLAN A-14, not tuned values, and the shared
vector file says so in `_measurement_status`.

---

## 7. Decision — `AUDIO_STATE` is implemented, on the authenticated path only

PROTOCOL §4.4 has specified `AUDIO_STATE` since Phase 1's correction pass; Phase 2b implements it.
**No wire field, value or bound changed.** What was added is the code, the bounds enforcement, the
revision rule on both sides, and a shared vector set (`protocol/vectors/audio-state/`).

| Property | How |
|---|---|
| `AUDIO_STATE` is absent from PROTOCOL §4.1's pre-authentication frame list | An unauthenticated peer's frame is dropped by `ControlSessionManager`'s allowlist before the dispatch can reach the relay, exactly as for `VOICE_*` (§7.1). Proven over **real TLS** with two real unpaired peers, with the refusals *counted* so the test cannot pass vacuously |
| A malformed frame is dropped and the control connection survives | Total, non-throwing codec, mirroring `VoiceSignalCodec`. §4.4 carries no "end the connection" outcome |
| `revision` is strictly increasing per sender per session | `AudioStatePublisher` owns it, does not reset it across a reconnect or a voice rebuild, and returns nothing when the state is unchanged — so `revision` means "the state changed", not "a callback fired" |
| A lower or equal revision is dropped | `AudioStateInbox`. Reordering cannot resurrect a stale route; a retransmit changes nothing |
| No platform vocabulary reaches the wire | The codec has an explicit field list, and both platforms' tests scan the shared vector data for `a2dp`, `hfp`, `sco`, `AVAudioSession`, `AudioManager`, a device name and a headset model |
| `revision` is bounded at 2^53 − 1 | A JSON number is a `Double` in the iOS decoder, so a larger value could not round-trip identically on both platforms. A bound both platforms enforce beats a range only one can represent — the same reasoning as `MAX_VOICE_MLINE_INDEX` |

`AudioRouteSnapshot` remains a **superset** of the §4.4 message: `interrupted`, `lastChangeReason`
and `lastTransitionDurationUs` are diagnostics the wire does not carry, and the codec's explicit
field list is what keeps them off it.

---

## 8. Alternatives considered

| Alternative | Why not |
|---|---|
| **PTT opens and closes the capture device** | The obvious implementation, and it violates both constraints in §1 at once. It is also the failure Phase 0 was built to measure, and A-10 exists to prove it never happens |
| **Five code paths, one per mode** | ARCHITECTURE §6.3 rejected this before implementation started. Five paths means five places for a gate bug, and a policy the user can change becomes a branch the tests must cross-product |
| **Gate by removing the RTP sender / renegotiating** | A renegotiation per utterance, on a link whose whole budget is one narrow duplex flow. `track.setEnabled(false)` is what the WebRTC API is for |
| **Gate by tearing down and rebuilding the `PeerConnection`** | Worse than the above, and it would change `voice_session_id` per utterance, defeating the generation guard's purpose |
| **Put the transmission decision in `VoiceController`** | Exactly the shape of the Phase 1b security bug (ADR-019) and STATUS §4 problem 20: a decision inside a driver is a decision no suite can exhaust. It is a table, so it is a table |
| **Add `disabled` to `VOICE_STATE.mode`** | A wire change, to describe the absence of the very session that frame is about. `AUDIO_STATE.intercom_mode` already carried the value and is the field about local audio state |
| **Write a VOX detector from raw PCM this phase** | §6. An unmeasured hand-written detector would be a box ticked, not a feature — and ADR-003 already declines custom DSP on the same grounds |
| **Settle route transitions on a fixed delay** | This phase's brief §15 forbids it, and rightly: a sleep that happens to be long enough on one device is a bug on the next one. The platform's own callback is the signal; the timeout is protection and is counted separately |
| **Let each platform own its own audio-session state machine** | Neither can be executed off-device, so both would be untestable — and they would diverge, which is the failure ADR-019 was written about |

---

## 9. Consequences

**Good**

- The product's largest risk is now enforced rather than described: nothing in the transmission path
  can touch the capture device, because the vocabulary has no way to say it.
- Every mode is one value of one shared type, pinned by a third independent implementation, so a gate
  implemented differently on the two phones fails a laptop test rather than a ride.
- `AVAudioSession`/`AudioManager` decisions are testable for the first time — the route transition
  lifecycle, `shouldResume`, a media-services reset and a stale callback all have laptop coverage
  where before they had none.
- Both peers can see each other's effective audio state, which is what ADR-016 existed for.
- Route transitions produce a **measured** duration instead of a documented expectation.

**Costs and open risks**

- **Nothing here has run on a phone.** No microphone, no speaker, no Bluetooth, no foreground service,
  no lock screen. TEST_PLAN V-01…V-11 and A-12…A-15 are the gate and every one of them is pending.
- **VOX cannot open its gate** until a level source exists (§6). Selecting Mode B today means "never
  transmits", and the UI says so.
- **The route mappers' `assumed` values are still assumed.** Phase 2b did not measure any hardware,
  so `confidence` stays `assumed` on both platforms and their tests still assert it.
- **A gated policy sends one extra `VOICE_STATE` when capture opens**, to correct `mic_muted` from
  "not yet open" to the gate's value. Both frames are truthful and §7.4 sends `VOICE_STATE` on change,
  but it is one more frame than Phase 2a sent and two Phase 2a tests were updated to assert content
  rather than frame count.
- **No latency figure exists.** The setup timings this phase adds (`VoiceSetupTimeline`) measure how
  long the *app* took to bring voice up. Mouth-to-ear latency (A-09/V-11) includes two Bluetooth hops
  and cannot be inferred from them or from network RTT. The <200 ms target remains unmeasured.

---

## 10. Verification

- `protocol/vectors/intercom/` — 58 rows over the transmission table, plus the five presets and the
  declared default, run by **both** platforms from the same file. Generated by
  `tools/generate_intercom_vectors.py`, an independent third transcription.
- `protocol/vectors/audio-state/` — 7 encode, 46 parse, 7 media-quality, 8 publisher and 6 inbox
  rows (74 in all), run by **both** platforms. Generated by `tools/generate_audio_state_vectors.py`, likewise
  independent, which self-checks its own privacy invariant before writing.
- `protocol/vectors/voice-fsm/` — 7 new rows for `ModeSelected`, with two new stated invariants: a
  mode change emits only `SendVoiceState` and never touches the status or local audio.
- `IntercomTransmissionTest[s]`, `IntercomCommandMailboxTest[s]`, `AudioSessionLifecycleTest[s]`,
  `RideStartPolicyTest[s]`, `VoiceSetupTimelineTest[s]` — mirrored pure suites on both platforms.
- `VoiceControllerIntercomTest[s]` — the wiring, including the 50-press capture invariant.
- `VoiceAuthenticationGateTest[s]` — extended to `AUDIO_STATE` over real TLS, both halves.
- `AudioSessionSignalBoxTests` — the iOS notification mailbox's bound, coalescing and ordering.
- `AndroidCommunicationDeviceSelectorTest` — the pure half of Android route selection.

**What none of it proves:** that voice works. See §9.

---

## Amendment A1 — 4 September 2026 — final hardening: real ordering, real registration order, a real timeout, and a completion-aware stop

**Status of the ADR: still Accepted.** Every finding below is a correction to how §4/§5's decisions
were *implemented*, not a change to what they decided. An independent review of the Phase 2b
implementation (§2m) found eight candidate issues; all eight were confirmed against the actual code
before anything was changed, and every fix keeps the invariants this ADR exists for: the transmission
gate still cannot touch capture, the capture device is still a ride-segment resource, `VoiceEngine.stop()`
and `release()` are still separate calls, and full duplex is still primary.

### Finding A — iOS's notification box was one-shot, not per-session

`IosVoiceAudioSession` held **one** `AudioSessionSignalBox` for its whole process lifetime
(`private let signals = AudioSessionSignalBox()`), and `close()` called `signals.finish()`. `finish()`
sets a permanent flag with no way to clear it, so every notification offered to that box after the
**first** End Intercom was silently dropped — a second Start Intercom would never see a route change, an
interruption or a media-services reset again, for the rest of the process's life. `IosVoiceAudioSession`
now creates a fresh `AudioSessionSignalBox` (and a fresh doorbell and a fresh consumer `Task`) on every
`open()`, exactly as `SessionCoordinator` already creates a fresh `OrderedEventChannel` per
`startDiscovery()`. `finish()` still poisons the instance it is called on — that is correct, and is what
makes a stale offer from an already-closed generation a no-op — but poisoning one instance no longer
poisons every instance that will ever exist.

### Finding B — the generation stamped on an iOS signal was read at the wrong time

`AudioSessionSignal` carried no generation of its own. `IosVoiceAudioSession.handle` read
`lifecycle.generation` — the actor's **current** generation — at the moment a queued signal was finally
processed, not the generation that was live when the platform notification actually fired. Since
`AudioSessionLifecycle.reduce`'s generation guard compares `event.generation` against `state.generation`,
and both values were being read from the same live property one line apart, the guard could never reject
anything an iOS-sourced event: it was structurally impossible for the two to disagree. `AudioSessionSignal`
now carries no generation itself, but `AudioSessionSignalBox.offer` takes one as an explicit parameter,
supplied by `registerObservers(generation:...)` — read **once**, at registration time, and closed over by
each `NotificationCenter` callback for that generation's whole life. A signal now carries the generation
it was actually captured under, and the reducer's guard is what it always claimed to be.

### Finding C — the "safety priority" was documentation, not behaviour

`AudioSessionSignal.Kind`'s doc comment described a drain order — `mediaServicesReset` before
`interruption` before `routeChanged` — as the reason a reset could never be coalesced away by a route
change. The implementation was a raw `AsyncStream`, which only ever delivers in **arrival** order; nothing
reordered by kind. Combined with Finding B, a reset that physically arrived after a route-change
notification (plausible: the OS can emit a route change as a side effect of the same event that produces
the reset) would have been drained after it and — before this pass — silently promoted to the reset's own
new generation. `AudioSessionSignalBox` is no longer an `AsyncStream` wrapper: `offer` fills a bounded slot
per kind and rings a `ConflatedSignal` doorbell (the same primitive `VoiceController`'s mailbox already
uses for exactly this reason); the consumer polls `poll()` in explicit `Kind` priority order on every ring,
draining to empty before waiting for the next one. `AudioSessionSignalBoxTests` proves this directly —
every arrival permutation of the three kinds drains in the fixed safety order, including under concurrent
producers.

### Finding D — a listener registered after the request it must confirm

Both platforms called the API that changes the route **before** registering the callback that confirms
it, on `open()`, and unregistered the callback **before** the restoring call on `close()`:

- iOS: `registerObservers()` ran after `setCategory`/`setActive`, and `unregisterObservers()` ran before
  the restoring `setCategory(.playback, ...)`.
- Android: `registerAudioDeviceCallback`/`addOnCommunicationDeviceChangedListener` were registered after
  `requestFocusAndCommunicationMode()`/`selectCommunicationDevice()`, and `releasePlatformSession()`
  unregistered them **before** `clearCommunicationDevice()`/the mode restore.

A confirmation that can arrive synchronously to very shortly after the call that provoked it could
therefore be missed on both the open and the close path, on both platforms. Both platforms now register
before requesting and unregister only after every call that could still provoke a confirming callback has
been made.

### Finding E — the transition timeout was never scheduled

`pollTransitionTimeout()` exists on both `IosVoiceAudioSession` and `AndroidVoiceAudioSession`, is not
part of the shared `VoiceAudioSession` protocol/interface, and — confirmed by grepping the whole app on
both platforms — had **no caller anywhere in production code**. A platform that never confirmed a
transition would leave `route_state: transitioning` latched for the rest of a ride, because the one thing
that was supposed to protect against it was dead code. Both platform classes now schedule one
generation-tagged timeout task whenever `apply` produces a transition whose `startedAtMonoUs` actually
changed (a burst of callbacks within the *same* transition does not re-arm it, matching
`RouteTransitionTracker.begin`'s own "keep the original start instant" rule), cancel it the moment the
transition settles, and — since the generation is captured at *schedule* time, the same discipline as
Finding B — a stale timer from a superseded generation is inert via the reducer's own guard rather than by
racing a cancellation. `RouteTransitionTracker`/`AudioSessionLifecycle` themselves are untouched: this is
entirely who calls `pollTransitionTimeout` and when, not what it decides.

### Finding F — the Android foreground service could stop before capture actually released

`MainActivity.stopIntercom()` called `coordinator.endIntercom()` (which only **queues**
`VoiceInput.StopRequested` on `VoiceController`'s mailbox) and then `RideForegroundService.stop()`
immediately afterward, with nothing between them. `RideForegroundService`'s lock-screen `END_INTERCOM`
action had the identical shape: dispatch, then `stopSelf()`. Both could let Android reclaim the foreground
service while `engine.release()`/`audioSession.close()` were still running on `VoiceController`'s
consumer, which is the microphone-holding-orphan failure ARCHITECTURE §6.4 exists to forbid, reached from
the stop side rather than the start side.

`VoiceController` gained `stopAndAwaitRelease()`: it offers `StopRequested` exactly as `stop()` does, but
suspends on a `CompletableDeferred` that `apply` resolves once `StopRequested` has been **fully** applied
— including any `VoiceAction.ReleaseLocalAudio` it produced, since `perform` is suspend and actions run
sequentially rather than being fired off. Several concurrent callers are supported (idempotent End) and
all resolve together; a call with nothing to release (capture never opened) resolves immediately; a link
loss does not resolve it, because a link loss never goes through `StopRequested`'s release path at all.
`SessionCoordinator.endIntercomAndAwaitRelease()`, `MainActivity.stopIntercom()` (via `lifecycleScope`) and
`AppContainer`'s `RideCommandBus` handler for `END_INTERCOM` (via `appScope`) all now await this before
calling `RideForegroundService.stop()`; `RideForegroundService` itself no longer calls `stopSelf()` from
either the lock-screen action or `onTaskRemoved` — dispatching and stopping the service are no longer the
same step. This is bounded by a 5 s timeout as failure protection only, on the same principle as Finding
E's timeout: the real completion signal is the deferred resolving, never the timeout firing.

### Finding G — iOS voice diagnostics reached `SessionCoordinator` through a Task per callback

`SessionCoordinator.attachVoice` wrapped `VoiceController.setOnDiagnosticsChanged`'s callback in a fresh
`Task { @MainActor in ... }` per call — the exact pattern STATUS §2h fixed for `ControlEvent`, and
explicitly left unfixed here at the time ("out of scope per this session's brief"). Since
`AudioStatePublisher`'s `revision` is derived from the diagnostics sequence, an out-of-order delivery could
make a stale route or transmission snapshot the one a peer treats as authoritative. `SessionCoordinator`
now drains an `OrderedEventChannel<VoiceDiagnostics>` with one long-lived consumer `Task`, recreated per
`attachVoice` and finished in `releaseVoice`, mirroring `controlEventChannel` exactly. `VoiceController`'s
own diagnostics emission (`publishDiagnostics`/`publishEngineDiagnostics`) already runs actor-isolated and
serially with respect to its own mailbox consumer; this fix closes the one confirmed out-of-order path
between the controller and the coordinator. (`VoiceController.attach`'s own `Task`-per-engine-event and
`Task`-per-route-event forwarding are a related, narrower, pre-existing pattern this pass did not touch —
see "What did not change" below.)

### Finding H — Android's `_diagnostics` had three unsynchronized writers

`VoiceController.publishDiagnostics` (the mailbox consumer), `publishEngineDiagnostics` (the
diagnostics-poll `Job`, a separate coroutine) and `publishRoute` (called from whatever thread the platform
audio-session's route-sink callback runs on) each did a plain
`_diagnostics.value = _diagnostics.value.copy(...)`. That is a read-copy-write: two of the three racing
could lose one's update entirely, because the loser's `copy()` was built from a snapshot the winner had
already superseded. All three now use `MutableStateFlow.update { it.copy(...) }`, an atomic
compare-and-retry loop, so a losing writer retries against the winner's result instead of silently
reverting it. `VoiceControllerDiagnosticsRaceTest` proves it directly: two independent fields, written only
by different one of the three sites, hammered concurrently at volume, both hold their true final value
simultaneously afterward.

### What did not change

`VoiceNegotiation`'s table, `IntercomTransmission`'s table, `AudioSessionLifecycle`/`RouteTransitionTracker`
themselves, `protocol/vectors/intercom/`, `protocol/vectors/audio-state/`, the pre-authentication
`VOICE_*`/`AUDIO_STATE` refusal, the offerer rule, and the transmission gate's action vocabulary (still no
capture case) are all untouched — every fix above is in the platform driver layer or, for Findings B/C, in
the previously-`AsyncStream`-shaped mailbox around it, never in a pure reducer. Two adjacent patterns this
pass found but deliberately left alone, because they are outside what was reviewed and reopening them risks
exactly the scope creep this ADR's own alternatives table (§8) warns against: `VoiceController.attach`'s
`Task`-per-engine-event and `Task`-per-route-event forwarding (a narrower version of Finding G, one layer
lower, guarded today by running on top of an already-bounded, already-ordered mailbox rather than by the
same fix); and the `Effect.ReleaseAudioAndStopForegroundService` FSM effect, whose name promises an Android
foreground-service stop it does not actually perform (the only caller of `RideForegroundService.stop` in
the whole app remains `MainActivity`) — a pre-existing gap, not a regression from Finding F's fix, and
unrelated to any of the eight findings above.

### Verification

- `AudioSessionSignalBoxTests` (iOS, `RideLinkPlatform`) — rewritten for the new generation-tagged,
  priority-polling API: every kind reachable, coalescing, generation preservation, safety-order draining
  under every arrival permutation and under concurrent producers, and the reuse-after-`finish()` property
  Finding A exists for (several open→finish cycles in a row, each delivering normally). Run **50
  consecutive times, 0 failures**.
- `VoiceControllerStopAwaitTest` (Android, `network`) — `stopAndAwaitRelease` does not return until the
  fake audio session's `close()` actually completes (a controllable suspend gate proves the ordering, not
  just the outcome); a no-op when nothing was open; several concurrent callers all resolve; a link loss
  does not resolve a pending call; shutdown when voice was never started is safe.
- `VoiceControllerDiagnosticsRaceTest` (Android, `network`) — the two independent-field races described in
  Finding H, at 300 iterations each. Run alongside `VoiceControllerStopAwaitTest`, **50 consecutive times,
  0 failures**.
- `SessionCoordinator`'s own diagnostics-channel wiring (Finding G) inherits the same limitation
  `docs/STATUS.md` §4 problem 20 already records: the app target has no test target on either platform, so
  the wiring itself is proven only by inspection and by `OrderedEventChannel`'s own generic ordering
  proof (`OrderedEventChannelTests`) — not a new gap this pass introduced.
- Findings D and E live entirely in `IosVoiceAudioSession`/`AndroidVoiceAudioSession`, neither of which can
  be exercised off a device (`AVAudioSession` does not exist on macOS; `AudioManager` needs a device or
  emulator, docs/STATUS.md §4 problems 22/23). The registration-order and timeout-scheduling changes there
  are **REAL-DEVICE INTERCOM GATE PENDING**, exactly like everything else in those two classes — this pass
  narrows what is untested (the pure `RouteTransitionTracker`/`AudioSessionLifecycle` logic the timeout
  drives was already covered and is unchanged) without claiming to close the device gate.
- Full suites: Android `test ktlintCheck detekt lint assembleDebug assembleRelease` and iOS
  `RideLinkCore`/`RideLinkPlatform` `swift test` plus `xcodebuild` Debug and Release simulator builds all
  green — see `docs/STATUS.md` §2n and §3 for exact counts.

## Amendment A2 — 4 September 2026 — the five gaps Amendment A1 named but did not fix, plus problem 32

**Status of the ADR: still Accepted.** A second independent review, run specifically over what Amendment
A1's own "What did not change" section flagged as out of scope, named five candidate issues plus
`docs/STATUS.md` §4 problem 32. All five were confirmed against the actual code before anything changed;
one (Finding 5 below) proved to be a real ordering hazard once traced through, not a false alarm, and is
fixed rather than merely documented. As with A1: every fix stays in the platform driver layer, a test
seam, or the FSM-effect wiring — no pure reducer's decision table, no wire shape, no security property
moved.

### Finding 1 — the closing (and opening) route transition began after the platform call, not before

`AndroidVoiceAudioSession.close()` called `releasePlatformSession()` — which can synchronously provoke
`AudioManager.OnCommunicationDeviceChangedListener` — and only applied `AudioSessionEvent.Closed`
afterward; `open()`'s `apply(Opened(...))` ran only after `selectCommunicationDevice()` had already
returned. `RouteTransitionTracker.settle` is a no-op unless `RouteTransitionState.transitioning` is
already true, so a confirmation arriving before `Opened`/`Closed` ever ran was silently discarded — the
transition `Opened`/`Closed` began immediately afterward then waited for a confirmation that had already
come and gone, settling only via Finding E's five-second timeout. `AudioSessionLifecycle` gained two new
events, mirrored on both platforms: `OpenRequested`/`CloseRequested` (begin the transition only — they do
not touch `AudioSessionState.open`) and `OpenAborted` (the platform call `OpenRequested` preceded failed
before completing; settles the transition immediately and leaves `open` false, rather than leaving it
latched for the timeout). Both platform classes now apply `OpenRequested`/`CloseRequested` **before** the
platform call that can confirm it, and `Opened`/`Closed` **after** — and `Opened`/`Closed` no longer call
`RouteTransitionTracker.begin` themselves, so a confirmation that already settled the transition in
between cannot be resurrected into a fresh, spurious `TRANSITIONING`. `AudioSessionLifecycleTest[s]` proves
both directions: a settling `RouteChanged` landing between `OpenRequested`/`Opened` (or
`CloseRequested`/`Closed`) is observed, not dropped, and the later confirmation event does not re-begin an
already-settled transition.

**A second, related defect surfaced while fixing this on iOS: `close()` tore down its own fallback.**
Immediately after applying `.closed`, `IosVoiceAudioSession.close()` unregistered its `NotificationCenter`
observers, cancelled `transitionTimeoutTask`, and stopped the notification consumer (`stopConsumer()`,
which finishes the doorbell that drives it) — all synchronously, all before the transition it had just
begun could possibly have settled by either of the two things that were supposed to settle it: a real
`.categoryChange` confirmation (which the actor cannot even process until `close()`'s own non-suspending
body finishes running, since actors do not preempt between suspension points) would arrive into a box with
no consumer left to drain it, and the timeout task that would otherwise catch a missing confirmation was
cancelled before it could ever fire. The transition was left latched at `transitioning` with nothing left
to ever publish its settlement — silently, since nothing crashes and nothing times out anymore either.
Fixed by making `close()` wait: a new `awaitTransitionSettled()` suspends (via a `CheckedContinuation`)
until `apply` — from *either* a real confirmation or the timeout — reports the transition no longer
transitioning, and only then does `close()` unregister observers, cancel the (already-fired-or-irrelevant)
timeout, and tear down the consumer. This cannot hang past the same five-second window Finding E's timeout
already bounds. Android's `close()` does not share this defect: its failure-protection timeout is an
independent `scope.launch` coroutine, armed by `manageTransitionTimeout` and never touched by
`releasePlatformSession()`'s callback unregistration, so it keeps running and will still settle the
transition even if the real callback loses the unregistration race — Android's fallback was never at risk
of being killed by its own `close()`, only (as already true before this pass, via Finding D) at risk of
missing the *faster* real-confirmation path in that same race. This half of the fix is **REAL-DEVICE
INTERCOM GATE PENDING** like the rest of `IosVoiceAudioSession`: `AVAudioSession` does not exist on macOS,
so `swift test` builds and exercises only the macOS stand-in, never the `#if os(iOS)` branch this change
is in — verified instead by `xcodebuild` Debug and Release simulator builds, which compile the real branch
and were rerun clean after this change specifically.

### Finding 2 — `stopAndAwaitRelease()`'s timeout was indistinguishable from success

`VoiceController.stopAndAwaitRelease()` (Amendment A1, Finding F) called
`withTimeoutOrNull(STOP_AWAIT_TIMEOUT_MS) { completion.await() }` and discarded the result — the function
returned `Unit` whether `StopRequested` actually finished or the call merely gave up waiting for it, and a
timed-out `CompletableDeferred` was never removed from `pendingStopCompletions`, leaking one entry per
stall. A caller (`SessionCoordinator`, `MainActivity`, `AppContainer`) could not tell a proven release from
an unproven one, which is the exact failure mode Finding F's fix existed to prevent, reached one layer
later. `stopAndAwaitRelease()` now returns `StopReleaseResult` — `Released`, `AlreadyReleased` (nothing was
open; still queues the stop for idempotency), or `TimedOut` (the waiter is removed before returning; never
treated as success) — and every caller inspects it: the Android microphone foreground service is stopped
only on `Released`/`AlreadyReleased`, never on `TimedOut`. `stopAwaitTimeoutMs` moved from the five-second
companion constant alone to also being a settable property, so a test can prove `TimedOut` in milliseconds
rather than waiting out the real window. `VoiceControllerStopAwaitTest` gained coverage for: a stalled
`close()` reported as `TimedOut` rather than `Released`; the waiter count returning to zero after a
timeout, including 100 in a row; a later real completion of a stalled `close()` not corrupting an
independent, subsequent stop call; and several concurrent callers each seeing a genuine
`Released`/`AlreadyReleased` result, never a timeout.

### Finding 3 — problem 32: the `ENDING` effect never actually stopped the foreground service

Confirmed exactly as `docs/STATUS.md` §4 problem 32 and Amendment A1's own "what did not change" section
described it: `SessionFsm`'s `Effect.ReleaseAudioAndStopForegroundService` promised a foreground-service
stop; `SessionCoordinator.runEffect` called `releaseVoice()` (fire-and-forget) and `teardownSession()` and
never called `RideForegroundService.stop`. `MainActivity` remained the only caller in the app. Every
legitimate ENDING path this app can currently reach other than the user's own End Intercom button — in
practice, a peer `BYE` — releases capture correctly and then leaves the service orphaned.

Fixed by making `SessionCoordinator` the **one** owner of the ENDING order, via a small seam rather than a
`Context`: `ForegroundServiceController` (a `fun interface` with one method, `stop()`), a new constructor
parameter, supplied in production by `AppContainer` as `ForegroundServiceController { RideForegroundService.stop(context) }`.
`runEffect`'s handling of `ReleaseAudioAndStopForegroundService` now: awaits capture release via a new
`releaseVoiceAndAwait()` (which calls `VoiceController.stopAndAwaitRelease()`, Finding 2's `StopReleaseResult`,
before finishing the rest of the teardown); calls `foregroundService.stop()` only on
`Released`/`AlreadyReleased`, never on `TimedOut`; and always runs `teardownSession()` (control/discovery)
afterward regardless of the audio result, since the control session is being torn down either way. The
`applySideEffects` handling of a `BYE`-reasoned `LinkLost` no longer eagerly released voice itself — that
was a second, uncoordinated release path racing the FSM's own effect; `LinkLost(BYE)` always drives
`CONNECTED`/`RIDE_ACTIVE`/`RECONNECTING` to `ENDING` in this app (nothing else currently reaches `BYE` from
a state where voice could be attached), so the `ENDING` effect is now the single place release actually
happens for that path.

Proving this needed `SessionCoordinator` to be constructible in a JVM test without a real `NsdManager` or
TLS socket — the reason no `SessionCoordinator`-level test existed before (`docs/STATUS.md` §4 problem
20's residual limitation). `NsdDiscoveryController`'s two methods were extracted into a `DiscoveryController`
interface (no behaviour change; `NsdDiscoveryController` remains the only production implementation), and
`applyEvent`/`handleControlEvent` were widened from `private` to `internal` as an explicit test seam
(CLAUDE.md rule 8 still holds: `SessionCoordinator` remains the one thing that decides what a control event
means, including in the test). `SessionCoordinatorEndingEffectTest` (new, `app` module) drives a real
`SessionCoordinator` — real `SessionFsm`/`SessionGate`, a real `VoiceController` wired to a fake engine/audio
session/transport, a fake `ForegroundServiceController` — through `StartDiscovery -> PeerSelected ->
PeerTrusted -> Connected -> LinkLost(BYE)` and proves: capture release completes before the foreground
service is told to stop; a timed-out release never stops it; a `NETWORK` link loss never releases capture
or stops the service; a repeated/duplicate `BYE` cannot double-fire the effect; and a `BYE` with voice never
started still stops the service. This is the first test in the repository to exercise `SessionCoordinator`
itself rather than only its collaborators.

### Finding 4 — iOS route snapshots reached `VoiceController` through a Task per callback

`VoiceController.attach()`'s `audioSession.setRouteSink` callback (synchronous, non-isolated, so it cannot
call into the actor directly) wrapped every snapshot in `Task { await self?.publishRoute(snapshot) }` — the
same `Task`-per-event shape Amendment A1's Finding G fixed for voice diagnostics, and STATUS §2h fixed for
`ControlEvent`, explicitly left unfixed here at the time. Two tasks created in order are not guaranteed to
*run* in that order, so a `transitioning` snapshot could be observed after a later `stable` one — read by
`AUDIO_STATE`'s revision rule and by `IntercomTransmission`'s `Interrupted` input, both of which care about
order. Replaced with `routeChannel`, an `OrderedEventChannel<AudioRouteSnapshot>` (the same primitive
`SessionCoordinator.voiceDiagnosticsChannel` already uses) created fresh in `attach()` and torn down in
`shutdown()` exactly like `SessionCoordinator`'s own channel: cancel the consumer, then finish the channel,
so a stale sink call from an already-shut-down controller becomes a silent no-op rather than a mutation of
whatever replaces it. `VoiceControllerRouteOrderingTests` (new) proves 100 snapshots are observed in the
exact order published (via a monotonic marker, since `publishRoute` also forwards every route through the
intercom mailbox's `.interrupted` input, whose own consumer republishes diagnostics again once it runs — a
real, pre-existing, harmless duplication this test accounts for rather than asserting a fragile 1:1 callback
count) and that a snapshot published after `shutdown()` is never observed. Run 50 consecutive times, 0
failures.

### Finding 5 — the iOS engine-event Task, reviewed and folded into the ordered path rather than left alone

Amendment A1 named `VoiceController.attach`'s `Task { await self?.noteEngineEvent(event) }` — recording
`VoiceSetupTimeline` marks and `lastFailure` from each engine callback — as a narrower version of Finding
G, deliberately not fixed because it appeared to update only "first-write-wins setup marks" and "absolute
failure metadata." Traced through for this pass: `VoiceSetupTimer.mark` has no generation guard of its own,
and `VoiceController.start()` resets `setupTimeline` (`VoiceSetupTimer.restart`) directly, synchronously,
outside the mailbox. A `noteEngineEvent` `Task` from a *superseded* negotiation, still in flight when
`start()` begins a new one, could therefore record a stale timestamp as the **new** generation's own
first-write-wins mark — corrupting the V-01 setup-timing measurement (diagnostic only; no negotiation
decision reads `setupTimeline`, so this was never a correctness or security gap, only a measurement one).
Fixed rather than left, since the fix is free: `noteEngineEvent` is deleted, and the same marks/failure are
now derived from the `VoiceInput` `apply()` is about to reduce anyway (`noteFromInput`, called at the top of
`apply`, before `VoiceNegotiation.reduce` runs) — the same ordered, single-consumer call that already
applies the corresponding table transition, with no second hop and no `Task` left to race. This mirrors
`VoiceController.kt`'s `engineEventToInput`, which already computed the equivalent marks synchronously at
the callback boundary using a lock rather than an actor hop — the two platforms now reach the same
ordering guarantee by the idiom each language's concurrency model actually allows.

### What did not change

Everything Amendment A1's own "what did not change" list named is still untouched by this pass too:
`VoiceNegotiation`, `IntercomTransmission`, `RouteTransitionTracker`'s own arithmetic, every shared vector
file, the pre-authentication `VOICE_*`/`AUDIO_STATE` refusal, the offerer rule, and the transmission gate's
capture-free action vocabulary. `VoiceController.shutdown()`'s direct (non-mailbox) call to `apply(.stopRequested)`,
running on whatever coroutine/task called `shutdown()` concurrently with the mailbox consumer's own `apply`
calls, is a pre-existing latent concern this pass noticed but did not change — it predates this session,
is not one of the five confirmed findings, and touching it was out of this pass's scope.

### Verification

- `AudioSessionLifecycleTest`/`AudioSessionLifecycleTests` (both platforms) — new cases for
  `OpenRequested`/`CloseRequested`/`OpenAborted`, including the settling-in-between proof for both
  directions; the three pre-existing tests that called `Opened`/`Closed` standalone were updated for the
  now-paired contract. Run 20 (Android) / 50 (iOS) consecutive times, 0 failures.
- `VoiceControllerStopAwaitTest` (Android) — see Finding 2. Run 20 consecutive times, 0 failures.
- `SessionCoordinatorEndingEffectTest` (Android, new) — see Finding 3. Run 20 consecutive times, 0
  failures.
- `VoiceControllerRouteOrderingTests` (iOS, new) — see Finding 4. Run 50 consecutive times, 0 failures.
- Full suites: Android `test ktlintCheck detekt lint assembleDebug assembleRelease` and iOS
  `RideLinkCore`/`RideLinkPlatform` `swift test` plus `xcodebuild` Debug and Release simulator builds all
  green — see `docs/STATUS.md` §2o and §3 for exact counts.
- Stress counts below 50 (20, for the three new/changed Android JVM suites) reflect wall-clock time spent
  on this pass, not a weaker standard of evidence — each is a deterministic, gate-controlled test with no
  real sleep-based timing, so a single clean run and a flaky one are equally informative; 20 consecutive
  clean runs is the same kind of proof as 50, just fewer of them. An early stress attempt that ran two
  Gradle invocations against this project concurrently produced spurious Kotlin-daemon-contention failures
  unrelated to any of the five findings; re-run in isolation, all four Android suites above are clean.

---

## Amendment A3 — 4 September 2026 — the Android route-close listener still tore down before its own confirmation, not just before the transition began

**Status of the ADR: still Accepted.** A third, narrowly-scoped review, requested specifically against
the one residual Amendment A2's Finding 1 named for Android and then judged acceptable: "Android's
fallback was never at risk of being killed by its own `close()`, only ... at risk of missing the
*faster* real-confirmation path in that same race." Traced through rather than left there, "missing
the faster path" turns out to have an observable cost Amendment A2 did not account for: a **normal,
successful** platform confirmation reported as a **timeout**. Confirmed against the actual code before
anything changed; fixed in the platform driver layer only, as with A1 and A2 — no pure reducer's
decision table, no wire shape, no security property moved.

### The finding

Amendment A1's Finding D made `AndroidVoiceAudioSession.releasePlatformSession()` unregister
`OnCommunicationDeviceChangedListener` **last**, after `clearCommunicationDevice()`, the mode restore
and the focus abandonment — fixing the specific defect named at the time, where unregistration
happened *before* the platform calls that could provoke a confirmation. What Finding D's fix did not
account for is that "after every call that *could* provoke a confirmation" and "after the confirmation
those calls *actually* produced" are different instants whenever `clearCommunicationDevice()`'s
confirmation is asynchronous rather than synchronous. `releasePlatformSession()` ran all four steps —
including the unregister — in one uninterrupted synchronous function body, so a confirmation arriving
even slightly after `clearCommunicationDevice()` returns (rather than from inside the call itself) had
already lost its listener by the time it would have arrived. The result was not a hang — Amendment
A2's Finding 1 correctly established that Android's failure-protection timeout runs independently and
still settles the transition — but a **mischaracterization**: a route change the platform genuinely
confirmed was recorded as `RouteTransitionState.timedOutCount + 1`, indistinguishable in the
diagnostics from a platform that never confirmed anything at all. The five-second window this ADR's
own Amendment A1 built specifically as failure protection, never as the definition of success, had
quietly become the *ordinary* close path whenever the real confirmation was not fast enough to beat
`unregisterPlatformCallbacks()`.

### The fix

`AndroidVoiceAudioSession.close()`'s single `releasePlatformSession()` call is split into what it was
always conceptually two different responsibilities: `requestPlatformRestore()` (clears the
communication device, restores the previous mode, abandons focus — every call that could still
provoke `deviceChangedListener`) and `unregisterPlatformCallbacks()` (unchanged, still last). Between
them, `close()` now suspends on a new `TransitionSettlementGate`
(`android/audio/src/main/kotlin/com/ridelink/audio/route/TransitionSettlementGate.kt`) until the
closing transition `apply(CloseRequested(...))` began has actually settled — by `deviceChangedListener`
or by the existing failure-protection timeout, whichever comes first — and only then does the unregister
run. `TransitionSettlementGate` holds no Android type and makes no decision of its own: it reads
`AudioSessionState.transition.transitioning` (unchanged, still `AudioSessionLifecycle`'s field) and
resumes a suspended `close()` call once `apply()`'s outcome says that is now false. It handles all three
timing cases: a confirmation that lands synchronously inside `requestPlatformRestore()` (the transition
is already settled by the time `awaitSettled()` is reached, so it returns without ever suspending — no
double-settle, no re-opened transition); a confirmation that lands asynchronously afterward (the
listener is still registered to receive it, `close()` is genuinely suspended, and the confirmation's own
`apply()` call resumes it); and no confirmation at all (the existing timeout, armed by
`manageTransitionTimeout` exactly as before, settles the transition and resumes `close()` — now
correctly counted as `timedOutCount + 1`, because in this case it actually is one). The generation guard
inside `AudioSessionLifecycle.reduce` is untouched and still governs which events can settle anything; a
`TransitionTimeoutCheck` or `RouteChanged` for a superseded generation is inert exactly as before.

`open()`'s two failure-abort paths were updated only mechanically, to call the same two split functions
back-to-back with no await between them — `AudioSessionEvent.OpenAborted` settles the transition
synchronously and unconditionally inside the reducer itself (`RouteTransitionTracker.settle`, not
waiting on any platform confirmation), so there is nothing for a still-registered listener to catch on
that path, unlike the ordinary close it did not need the same treatment as. `open()`'s ordering
otherwise, and the whole of iOS, are untouched by this pass — inspection did not find the same defect on
either.

### Verification

`TransitionSettlementGateTest` (Android, new — the gate primitive in isolation: already-settled
short-circuits without suspending, a genuine suspend/resume across a `kotlinx-coroutines-test`
`StandardTestDispatcher`, a settlement notification with nothing waiting on it is a no-op, and a
repeated notification after resume does not double-complete) and
`AndroidVoiceAudioSessionCloseOrderingTest` (Android, new — a structural mirror of `close()`'s exact
call sequence, built from the same two production pieces with every `AudioManager` call replaced by a
recorded fake, since `AndroidVoiceAudioSession` cannot be constructed in a JVM test at all) together
prove: the listener stays registered through a synchronous confirmation, through an asynchronous one,
and through a route timeout; unregistration never runs before settlement; a mismatched-generation
timeout is ignored while the real one still settles; and repeated/idempotent `close()` calls do not
re-run the platform sequence. This proves the *ordering policy*, not `AudioManager`'s actual callback
timing — that half remains **REAL-DEVICE INTERCOM GATE PENDING** like the rest of
`AndroidVoiceAudioSession`, unchanged by this pass. `docs/STATUS.md` §4 problem 23's scope is
unchanged; only the *shape* of what is untested there narrowed slightly, from "does the listener see a
callback at all" toward "is the listener still there when it does."

Full suites: Android `test ktlintCheck detekt lint assembleDebug assembleRelease` green — see
`docs/STATUS.md` §2p and §3 for exact counts. iOS untouched by this pass (Android-only finding, no
shared type changed): `RideLinkCore`/`RideLinkPlatform` `swift test` and `xcodebuild` Debug/Release
simulator builds re-run clean as a regression check, unchanged counts.

## Amendment A4 — 5 September 2026 — the caller-wait timeout and the release it waits for were fighting over the same job

**Status of the ADR: still Accepted.** The Phase 3 closure-audit pass (fifteenth session) found one
more defect in this same family and reported it, deliberately, **without a fix** — that pass's brief
forbade mixing a Phase 2b redesign into a Phase 3 pass (`docs/STATUS.md` §2r). This is the dedicated
follow-up pass that closes it. Confirmed against the actual code before anything changed; fixed in
the platform driver layer only, exactly as A1–A3: no pure reducer's decision table, no wire shape,
no security property moved.

### The finding

`VoiceController.stopAndAwaitRelease()`'s caller-facing failure-protection window
(`STOP_AWAIT_TIMEOUT_MS`, 5 s) and `AndroidVoiceAudioSession.close()`'s inner route-settlement
failure-protection window (`RouteTransitionTracker.DEFAULT_TIMEOUT_US`, also 5 s) are not the
independent "5 s + 5 s" budget a naive reading suggests. The outer window starts counting the moment
`stopAndAwaitRelease()` registers its waiter, before the consumer coroutine has even been scheduled
to begin `engine.release()`/`audioSession.close()`; the inner window starts counting only once that
work has actually begun. The outer timeout is therefore structurally guaranteed to fire at or before
the inner one, never independently of it — so a caller can, and in practice does, give up on a
release that is still legitimately in flight.

`StopReleaseResult.TimedOut`'s own documentation already said this is not a failure of the release
itself. What Amendment A2's Finding 2 did not account for is the caller's actual next step:
`SessionCoordinator.releaseVoiceAndAwait()` calls `VoiceController.shutdown()` **unconditionally**,
regardless of the result `stopAndAwaitRelease()` just returned. `shutdown()` called
`apply(VoiceInput.StopRequested)` **directly**, on whatever coroutine invoked it — racing
the consumer coroutine's own `apply` calls over the unsynchronized `state` field — and then called
`consumerJob?.cancel()` unconditionally. Whenever `shutdown()` ran while the consumer coroutine was
still genuinely suspended inside `close()`'s route-settlement wait (the exact case a `TimedOut`
result implies is common, given the two windows' relative starts above), that cancellation aborted
`close()` mid-flight — before `unregisterPlatformCallbacks()` and the post-close intercom-gate
update (`offerIntercom(CaptureOpen(false))`) could run. The consequence was concrete, not
theoretical: a leaked `AudioManager.OnCommunicationDeviceChangedListener`/`AudioDeviceCallback`
registration, and a transmission gate left believing capture was still open when it was not.

### The fix

**There is exactly one release operation — the `StopRequested` application the mailbox's single
consumer already runs — and now two independent caller-waits over it, neither of which may cancel
it.** `stopAndAwaitRelease()` was already correct on this point (Amendment A2, Finding 2): its
`withTimeoutOrNull` abandons *waiting* on `pendingStopCompletions` without touching the consumer or
the `CompletableDeferred` other callers may still be waiting on. `shutdown()` is rewritten to be the
same kind of caller: it registers its own waiter in `pendingStopCompletions`, offers `StopRequested`
through the ordinary mailbox (never a direct `apply` call), and suspends on that waiter's
completion — with **no caller-side timeout of its own**. Giving up early here was exactly the bug:
`shutdown()` must not treat "I stopped waiting" as license to cancel the consumer coroutine that is
still doing the release. Waiting unconditionally is safe rather than an unbounded hang because the
only suspension a `StopRequested` application can be doing is `close()`'s own inner route-settlement
wait, which is already bounded by `RouteTransitionTracker.DEFAULT_TIMEOUT_US` as failure protection;
`shutdown()` adds no second, competing bound — it simply outlasts the one that already exists. Only
once that completion resolves does `shutdown()` cancel `diagnosticsPollJob`/`consumerJob` and close
the mailboxes, matching this ADR's own required order: release finishes, *then* background
resources are freed, never the other way around.

`shutdown()` is also now idempotent by construction (a new `isShutDown` flag, checked and set
atomically under the same lock that guards `pendingStopCompletions`): a second call, concurrent or
long afterward, is an immediate no-op, so a repeated `stopAndAwaitRelease()`/`shutdown()`/`shutdown()`
sequence cannot re-offer `StopRequested` into a mailbox nothing will ever drain again and hang
forever waiting on a completion nothing can resolve. Removing `shutdown()`'s direct, unmailboxed
`apply` call also removes the concurrent-mutation-of-`state` hazard that came with it — a side
benefit, not a second finding, since it falls directly out of routing `shutdown()` through the same
single-consumer path every other input already uses.

Neither `AndroidVoiceAudioSession.close()` nor `TransitionSettlementGate` needed a change: both were
already correct (Amendments A1–A3 already fixed their internal ordering and settlement logic). The
whole defect was in how `VoiceController.shutdown()` reacted to a caller-facing timeout it does not
itself own.

### iOS

Inspected and found not to have the same flaw. `VoiceController` on iOS is an actor with no
`stopAndAwaitRelease`/`StopReleaseResult` equivalent at all: `shutdown()` directly `await`s
`apply(.stopRequested)` to completion, with no caller-facing timeout wrapping that wait in the first
place, so there is no second timeout to race against and nothing for a `shutdown()` to cancel out
from under. No iOS code changed; `swift test` for both packages and `xcodebuild` Debug/Release
simulator builds were re-run as a regression check only.

### Verification

- `VoiceControllerStopAwaitTest` (Android, `network`) — three new cases: `shutdown()` does not
  return while a gated `close()` is still in flight, and the `close()` it was waiting on is observed
  to have actually run once the gate opens (not aborted mid-flight); the exact regression shape —
  `stopAndAwaitRelease()` times out on a stalled `close()`, `shutdown()` is called immediately
  afterward on the same stalled release, and the release is still allowed to finish; and repeated/
  concurrent `shutdown()` calls are idempotent, release capture exactly once, and leak no waiter.
  Confirmed against the pre-fix code first — both new regression tests fail there, reproducing the
  leaked listener/skipped gate update directly, before passing against the fix.
- `SessionCoordinatorEndingEffectTest` (Android, `app`) — the same regression proven one layer up,
  through the real `ENDING` effect: a `BYE`-driven release stalls past `stopAndAwaitRelease()`'s short
  test timeout, `releaseVoiceAndAwait()` moves on to `shutdown()`, and the stalled `close()` is later
  observed to complete — impossible had `shutdown()` cancelled the coroutine running it.
- Full suites: Android `test ktlintCheck detekt lint assembleDebug assembleRelease` — **531 unit
  tests, 0 failures** (was 527, +4: the three new `VoiceControllerStopAwaitTest` cases and the one new
  `SessionCoordinatorEndingEffectTest` case). iOS `RideLinkCore` **207/207**, `RideLinkPlatform`
  **219/219** (both unchanged), `xcodebuild` Debug **and** Release simulator builds green, zero new
  warnings.
- The four new/changed Android JVM suites (`VoiceControllerStopAwaitTest`,
  `SessionCoordinatorEndingEffectTest`, run together with the full `network`/`app` module suites they
  live in) were run **100 consecutive times** against the fix, with `--rerun-tasks` so nothing was
  served from cache: **100/100 clean, 0 failures.** See `docs/STATUS.md` §2s for the exact command and
  the isolation note (an early attempt run concurrently with an unrelated background Gradle
  invocation produced spurious daemon-contention failures unrelated to this fix, the same class of
  issue Amendment A2 recorded; re-run in isolation, all runs are clean).
- Real-emulator regression check on `RideLink_API36` (no new instrumented tests added — this fix
  touches no Android framework type, only the pure-JVM-testable `network` module driver): the Phase 3
  closure-audit's own `:data`/`:app` instrumented suites re-run clean, confirming this change does not
  regress Android foreground-service-type ownership (`MICROPHONE` / `MEDIA_PLAYBACK` / both, and the
  stop-one-keep-the-other orderings) — see `docs/STATUS.md` §2s for the exact counts.

---

## Amendment A5 — 5 September 2026 — a proven-complete release still reported its own stale timeout

**Status of the ADR: still Accepted.** A second, narrower follow-up to Amendment A4, found while
independently verifying that amendment's own fix rather than by a separate audit pass. Fixed in the
same platform-driver layer as A1–A4: no pure reducer, no wire shape, no security property moved, and
`VoiceController.shutdown()` itself needed no further change — the gap was one layer up, in how its
caller read the two calls' results together.

### The finding

Amendment A4 made `VoiceController.shutdown()` wait for the exact release
`stopAndAwaitRelease()` was waiting on, rather than cancelling it — and proved, by construction, that
by the time `shutdown()` returns, that release has actually finished (§ "The fix" above). But
`SessionCoordinator.releaseVoiceAndAwait()` captures `stopAndAwaitRelease()`'s result **before**
calling `shutdown()`, and simply returned that captured value afterward:

```kotlin
val result = controller.stopAndAwaitRelease()
...
controller.shutdown()
...
return result   // stale: still whatever stopAndAwaitRelease() said before shutdown() ran
```

Whenever `stopAndAwaitRelease()` had returned `StopReleaseResult.TimedOut` — its own short
caller-facing window having elapsed while `close()` was still legitimately in flight, exactly the
case Amendment A4 exists for — `shutdown()`'s subsequent wait would then finish proving that same
release complete, and `releaseVoiceAndAwait()` would still hand its caller the *old* `TimedOut`.
`SessionCoordinator.runEffect`'s `ENDING` handling reads exactly that value to decide whether
`RideForegroundService.stop()` ever runs, and treats `TimedOut` as "leave the foreground service
running." The consequence: a release that had, by the time the coroutine reached `return result`,
already been proven complete could still leave the Android microphone foreground service running
indefinitely — an orphaned service holding a microphone nothing was using any more, for exactly the
one path (a stalled-then-recovered release) Amendment A4's own fix was supposed to make safe to stop
after.

### The fix

`releaseVoiceAndAwait()` now distinguishes the value it captures from the one it returns.
`stopAndAwaitRelease()`'s result is still the truth at the moment it returns — `Released` and
`AlreadyReleased` are unconditionally correct as of *that* instant and are returned unchanged. Only
`TimedOut` is re-examined: because `shutdown()` runs unconditionally next and is, in every path this
coordinator ever exercises, the *first* call to `shutdown()` on this controller (`voice` is set to
`null` immediately after `stopAndAwaitRelease()` returns, before `shutdown()` is even called, which is
what stops `releaseVoice()`'s own fire-and-forget `shutdown()` call from ever reaching the same
controller instance — see that method's own doc), `shutdown()`'s idempotency guard cannot have already
made this call a no-op. So its wait is genuine: it registers its own waiter on the same
`pendingStopCompletions` list `stopAndAwaitRelease()` used, and only returns once the mailbox
consumer has fully applied the very `StopRequested` that timed-out waiter was still waiting on —
including its `ReleaseLocalAudio` action, if there was one to run. By the time `shutdown()` returns
below that call, an initial `TimedOut` therefore no longer describes reality, and is promoted to
`Released`:

```kotlin
private suspend fun releaseVoiceAndAwait(): StopReleaseResult {
    val controller = voice ?: return StopReleaseResult.AlreadyReleased
    val initialResult = controller.stopAndAwaitRelease()
    voice = null
    ...
    controller.shutdown()
    ...
    return if (initialResult == StopReleaseResult.TimedOut) StopReleaseResult.Released else initialResult
}
```

This is deliberately not a blanket "timeout never happened" rewrite: it is scoped to the one call site
that has just observed the proof, stated as such in the method's own doc comment, and it changes
nothing about `StopReleaseResult`, `VoiceController.shutdown()`, or `stopAndAwaitRelease()` itself —
a caller of `stopAndAwaitRelease()` on its own (`SessionCoordinator.endIntercomAndAwaitRelease`, used
by the UI's own End Voice path, which never calls `shutdown()` afterward) still sees a genuine
`TimedOut` exactly as before, because nothing there subsequently proves the release complete. The
`SessionFsm.Effect.ReleaseAudioAndStopForegroundService` handler's own `TimedOut` branch is
unchanged and kept: it is the last line of defence against ever stopping the foreground service on an
unproven release, and stays correct on its own even though, in today's code, `releaseVoiceAndAwait()`
no longer reaches it with a release that has actually finished.

### iOS

Not applicable. iOS's `VoiceController` has no `stopAndAwaitRelease`/`StopReleaseResult` construct at
all (Amendment A4's own note) and `SessionCoordinator`'s Swift counterpart has no equivalent captured-
then-stale-result path to have the same defect in the first place — inspected, no code changed.

### Verification

- `SessionCoordinatorEndingEffectTest` (Android, `app`) — `shutdown after a release timeout still
  lets the same stalled release finish` gained the assertion this amendment is about: after the
  stalled `close()` is completed and observed to finish, the foreground service is now asserted to
  **eventually stop, exactly once** — the exact assertion the pre-fix version of this test was
  missing (it proved `closeCaptureCount` reached 1 but never checked `fgs.stopCalls` afterward).
  Confirmed against the pre-fix code first: with the promotion removed, this exact assertion times
  out — the foreground service never stops, because `releaseVoiceAndAwait()` still returned the
  stale `TimedOut` it had captured before `shutdown()` proved the release complete. A second test,
  `an audio release timeout does not stop the foreground service`, is kept and documented as the
  deliberately distinct case — the release there never completes at all, so the foreground service
  correctly never stops, and this amendment must not (and does not) change that.
- Full suites: Android `test ktlintCheck detekt lint assembleDebug assembleRelease` — see
  `docs/STATUS.md` §2t for the exact counts. iOS `RideLinkCore`/`RideLinkPlatform` and Debug/Release
  simulator builds re-run as a clean regression check only, no iOS code changed.
- The affected suite was run 100 consecutive times with `--rerun-tasks`, isolated from any other
  concurrent Gradle process — see `docs/STATUS.md` §2t for the exact count and the isolation note.
