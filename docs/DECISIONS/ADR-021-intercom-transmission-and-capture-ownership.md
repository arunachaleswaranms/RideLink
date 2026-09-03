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
