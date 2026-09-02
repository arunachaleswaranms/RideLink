# ADR-016 — Effective audio capability model, not independent routes

**Status:** Accepted · 26 Aug 2026

## Context

The baseline `CAPABILITIES` message described a peer's audio like this:

```json
"audio": {
  "output_route": "bluetooth_a2dp",
  "input_route":  "bluetooth_hfp",
  "sample_rate":  48000
}
```

Read plainly, that says: media-quality stereo output *and* hands-free microphone input, both
active, at 48 kHz. For a single Bluetooth endpoint that is not what happens. Opening the
microphone moves the device onto its duplex profile, and the **output follows** — so the real
state is a duplex profile in both directions at 16 kHz or less, with music quality reduced.

This is not a cosmetic modelling error. It is wrong about the single highest-risk behaviour in the
product — the one REQUIREMENTS §8 devotes five intercom modes to, the one Phase 0 existed to
measure, and the one that appears twice in the risk register. A peer that reports independent
routes reports "everything is fine" at precisely the moment the pillion's music has collapsed to
narrowband. Worse, the diagnostics screen (FR-023) would faithfully display that. The model also
had no way to express a route mid-transition, a wired headset, or "the platform did not tell us",
so any of those became a lie of omission.

It also embedded platform vocabulary — `a2dp`, `hfp` — in wire values, which couples the protocol
to Bluetooth profile names that neither platform's API uses consistently and that mean nothing for
a wired or built-in route.

## Decision

Split declared capability from effective runtime state, and describe the **effective duplex
state** rather than per-direction wishes.

| Message | Content | Cadence |
|---|---|---|
| `CAPABILITIES.audio` | Declared, static: `endpoint_class`, `supported_profiles`, **`profile_coupling`**, `voice_codecs`, `webrtc_supported`, `playback_rate_control`, `confidence` | once per session |
| `AUDIO_STATE` | Effective, runtime: `microphone_open`, `effective_input_profile`, `effective_output_profile`, effective sample rates, `media_quality`, `route_state`, `intercom_mode`, `revision` | at `CONNECTED`, at ride start, and on **every** change |

Wire detail: [PROTOCOL §4.3.1](../PROTOCOL.md#431-audio-capability-vocabulary) and
[§4.4](../PROTOCOL.md#44-audio_state--effective-runtime).

Four choices carry the weight:

**1. `profile_coupling` is the field that fixes the bug.** Values `independent` /
`input_forces_output` / `unknown`. `input_forces_output` states outright that opening the
microphone drags the output onto the duplex profile too. It replaces an implicit and false
assumption with an explicit and checkable declaration, and it is the first thing to look at when
diagnosing "why did the music get worse".

**2. Effective state, not intended state.** `effective_output_profile` is what is *actually*
active after any coupling has taken effect — so the honest answer in the ordinary Bluetooth
intercom case is a duplex profile at 16 kHz with `media_quality: "reduced"`, and both users can
see it. `media_quality` is derived, not measured: `reduced` whenever the effective output profile
is a duplex profile other than `duplex_wide_stereo`.

**3. Wire values are platform-neutral, by rule.** No `A2DP`, `HFP`, `SCO`, `AVAudioSession` or
`AudioManager` string ever appears on the wire. Profiles are named for what they can *carry*:

| Value | Duplex? | Quality | Typical reality |
|---|---|---|---|
| `media_stereo` | no | media-quality stereo output only | Bluetooth media streaming |
| `duplex_narrowband` | yes | ≈8 kHz both ways | legacy hands-free |
| `duplex_wideband` | yes | ≈16 kHz both ways | modern hands-free |
| `duplex_wide_stereo` | yes | media quality *with* usable input | wired headset, built-in, next-gen Bluetooth audio |
| `builtin` | yes | device speaker + mic | phone with nothing attached |
| `none` / `unknown` | — | — | no route / not yet determined |

Each platform's route layer maps its own profile names to this vocabulary in exactly one place.
That one place is also where Phase 0's measured results land.

**4. Ignorance is representable.** `confidence` ∈ `measured` / `assumed` / `unknown` — `measured`
only once real hardware behaviour is recorded in `PHASE0_RESULTS.md`; `assumed` until then, which
is the truth. `route_state` ∈ `stable` / `transitioning` makes a route change a first-class state
rather than a moment when every other field is quietly stale. `unknown` is a legal value for every
enum, and an unrecognised enum value received from a peer is treated as `unknown` rather than
rejected — forward compatibility applies to audio vocabulary too.

### iOS naming and session configurations

Two related corrections follow from the same insight.

`AVAudioSession.CategoryOptions.allowBluetooth` is deprecated; the current spelling is
**`.allowBluetoothHFP`**, and the architecture examples now use it. With the iOS 26.0 deployment
target of ADR-011 there is no availability branch — one of the reasons that baseline was chosen.

More importantly, `.allowBluetoothHFP` and `.allowBluetoothA2DP` listed together must not be read
as "media output plus duplex input, simultaneously". The design therefore uses **two audio-session
configurations**, switched only on an explicit user action (ARCHITECTURE §6.2):

| Ride phase | Category | Mode | Options |
|---|---|---|---|
| Music only | `.playback` | `.default` | — |
| Intercom active | `.playAndRecord` | `.voiceChat` | `[.allowBluetoothHFP, .allowBluetoothA2DP, .duckOthers]` |

Switching between them is an audible route change of roughly 0.5–2 s. It happens only when the
user turns the intercom on or off, is announced in the UI, and is reported to the peer as
`route_state: "transitioning"`. Never per utterance — which is also why the capture device is
opened once per ride segment and stays open (ARCHITECTURE §6.3, §6.4).

## Consequences

- The FR-023 diagnostics screen can show the truth for both peers, including "your pillion's music is in narrowband because her mic is open". That is a diagnosis; the old model could only produce a false reassurance.
- The drift ladder gains a correctness fix: it is **suspended** while either peer reports `route_state: "transitioning"` (ARCHITECTURE §7.3). A route switch stalls the render path briefly, and correcting for that would chase an artefact and could trip the three-hard-seeks-in-60 s sync-failure counter for no reason.
- Phase 0's results now have somewhere to go. Filling in `PHASE0_RESULTS.md` flips `confidence` to `measured` and fixes the profile mapping for the real helmet unit and earbuds, without a protocol change.
- The protocol no longer encodes Bluetooth profile names, so a wired headset, the built-in speaker and any future audio transport are describable in the same fields.
- Cost: one new message type and a second thing to keep in sync with the local audio route. Mitigated by `revision` being strictly increasing, so a reordered `AUDIO_STATE` can never resurrect a stale route.
- Cost: `AUDIO_STATE` is emitted more often than `CAPABILITIES` was — on every route change, mic toggle and transition edge. Still a handful of sub-1 KiB frames per ride.
- Cost: `media_quality` is derived from the profile rather than measured from the audio. It is a label for the user and the log, not a measurement, and is documented as such.

## Alternatives considered

| Option | Rejected because |
|---|---|
| Keep `output_route` / `input_route` as independent fields | Wrong about the ordinary Bluetooth case, and wrong in the exact place the product is most fragile |
| Add a boolean `music_degraded` to the existing shape | Records the symptom without the cause, so it cannot explain *why* or express wired, built-in, transitioning or unknown states |
| Put Bluetooth profile names (`a2dp`, `hfp`, `sco`) on the wire | Couples the protocol to one transport's vocabulary; meaningless for wired and built-in routes; and the two platforms' APIs do not name profiles consistently anyway |
| Report raw platform route descriptions as free text | Unparseable by the peer, un-vectorable in tests, and a privacy leak: device names are personally identifying |
| One combined message instead of `CAPABILITIES` + `AUDIO_STATE` | Static negotiation data and high-frequency runtime state have different cadences and different consumers. Merging them means re-sending library counts and limits on every route change |
| Wait for Phase 0 results before modelling any of this | The model has to exist for the results to land in. `confidence: "assumed"` is how the current state of knowledge is represented honestly in the meantime |

---

## Amendment A1 — 28 August 2026 — correction: `media_quality` is about *narrowed* duplex, not duplex

**Status of the ADR: still Accepted.** No wire value, no field and no enum changes. What changes is
one derivation rule, which was internally inconsistent.

Found while implementing the shared audio vocabulary in Phase 2a
([ADR-020](ADR-020-webrtc-voice-foundation.md)): this ADR contradicted itself about `builtin`.

**Decision → choice 2** said:

> `media_quality` is derived, not measured: `reduced` whenever the effective output profile is a
> duplex profile other than `duplex_wide_stereo`.

**Decision → choice 4's representable-states table** said:

| Situation | `endpoint_class` | `microphone_open` | `effective_output_profile` | `media_quality` |
|---|---|---|---|---|
| Nothing attached | `builtin_speaker` | `true` | `builtin` | `full` |

`builtin` **is** duplex and **is not** `duplex_wide_stereo`, so the prose gives `reduced` and the
table gives `full`. Both were written in the same change; nothing had implemented either, so nothing
had noticed.

**The table is right and the prose was imprecise.** `media_quality` answers one question — *has
opening the microphone cost the music anything?* — and for a phone's own speaker and microphone the
answer is no. That is the same fact `profile_coupling: "independent"` already states for that route;
the prose rule contradicted it. The prose was written while thinking about Bluetooth duplex
profiles, where "duplex" and "narrowed" happen to coincide, and it generalised the coincidence.

**Corrected rule, and the only one now implemented:**

> `media_quality` is `reduced` whenever the effective output profile is a **narrowed** duplex profile
> — `duplex_narrowband` or `duplex_wideband`. `duplex_wide_stereo` and `builtin` are duplex but not
> narrowed and are therefore `full`. `none` is `unavailable`; `unknown` is `unknown`.

It is expressed once, as `AudioProfile.isNarrowedDuplex` in `core.audiopolicy` /
`RideLinkCore.AudioPolicy`, and `AudioRouteSnapshot.mediaQuality` is derived from it on both
platforms — so the correction cannot be applied to one phone and not the other.

Consequences: none on the wire. Every value in the table above is unchanged, including the ordinary
Bluetooth intercom case (`duplex_wideband` ⇒ `reduced`), which is the one the product actually cares
about. The corrected rule only changes what would have been reported for the built-in route and for
a future `duplex_wide_stereo` Bluetooth endpoint — and in both cases it changes it from a false
"your music is degraded" to the truth.

`docs/PROTOCOL.md` §4.4's derivation sentence is corrected to match in the same change.
