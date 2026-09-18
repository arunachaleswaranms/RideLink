# ADR-027 — One generation-bound coordinator owns intercom/music coexistence

**Status:** Accepted · 17 Sep 2026  
**Relates to:** [ADR-016](ADR-016-effective-audio-capability-model.md), [ADR-021](ADR-021-intercom-transmission-and-capture-ownership.md), [ADR-024](ADR-024-synchronized-playback-integration.md), [ADR-026](ADR-026-session-lifecycle-teardown-and-restart.md)  
**Governs:** ARCHITECTURE §6.2–§6.5 and TEST_PLAN Phase 6 software closure  
**Vectors:** `protocol/vectors/coexistence/coexistence_vectors.json`  
**Wire format:** unchanged. `AUDIO_STATE` remains the effective-route report defined by ADR-016.

## Context

Phase 6 needs ducking, pause-on-speech, route-transition fallback and independent media failure
without creating a second player or allowing voice, music, UI and platform callbacks to compete over
volume. Mode D also creates an authority question: its pause may either be a synchronized Phase 5
command or a local temporary suppression. Making it an authoritative command would let one endpoint's
speech rewrite the shared playback timeline and could make the two endpoints disagree as speech edges
cross on the network.

On iOS, `AVAudioSession` is process-global. The existing music and voice wrappers both configured it
directly, which was safe only while coexistence was out of scope. Phase 6 cannot leave two owners.

## Decision

1. `IntercomMusicCoexistence` is the one pure policy table on both platforms. It consumes policy,
   accepted local/peer transmission, player state, base volume, route/interruption state, availability
   and lifetime identity. Modes A–E remain `IntercomPolicy` values; the reducer never implements five
   independent subsystems.
2. `IntercomMusicCoexistenceCoordinator` is the sole executor of music coexistence effects. The UI,
   `VoiceController`, audio-session layer and `MusicCoordinator` do not independently duck or resume.
3. Ducking is a temporary multiplier. The reducer computes `base_volume × duck_percent`; it never
   writes the user's base volume. The platform driver interpolates ten deterministic gain steps over
   200 ms. Reversal cancels the prior ramp and starts from the last applied value.
4. Mode D is a **local temporary suppression**, not a Phase 5 `PAUSE`. It preserves the shared
   authoritative timeline. Resume is allowed only for the exact track coexistence paused, and only
   when the user has not paused and the track has neither ended nor been replaced.
5. Every effect carries a coexistence generation down to the real player. Installing a successor
   generation invalidates delayed predecessor gain, pause and resume operations at the renderer
   boundary. Session teardown emits restoration and joins the session-owned producer; a successor
   never accepts an old continuation.
6. PTT and VOX remain WebRTC outbound-track gates. They never open or close capture, recreate a peer
   connection, change `voice_session_id`, or reconfigure the audio session per speech edge.
7. On iOS, `IosAudioSessionCoordinator` is the only writer of `AVAudioSession` category, mode,
   options and active state. Music and voice report their needs to it. Voice-active configuration
   wins; closing voice restores music configuration when music remains active. Android retains its
   existing one process-wide `AndroidVoiceAudioSession`; coexistence changes only the existing
   player's gain or temporary play state and creates no second `AudioManager` owner.
8. Fallback is explicit and prioritized: interruption, route-transition timeout, voice unavailable,
   music unavailable, synchronized playback unavailable. A voice failure restores ordinary music;
   a music failure leaves voice running. No fallback starts an autonomous retry loop.
9. `AUDIO_STATE` remains effective, not desired. Route transitions begin before platform
   reconfiguration, settle from platform callback where available, and expose timeout settlement only
   as local diagnostics. Phase 5 continues to suspend drift correction while either peer reports
   `transitioning`; no coexistence path invokes or spends its hard-seek budget.

## Consequences

- Modes A/B duck to 25%, Mode C to 35%, Mode D temporarily pauses, and Mode E produces no voice
  coexistence effect through the same semantic table on both platforms.
- Duplicate edges are idempotent; rapid reversal is deterministic; reconnect, teardown and track
  replacement cannot leave a stale duck or resurrect an old track.
- The VOX reducer is software-complete against synthetic level input. Production VOX remains
  **PENDING REAL AUDIO INPUT / LATER HARDENING** because the pinned public WebRTC APIs do not expose a
  sufficiently fast microphone level source. The two-second statistics poll is not used as a gate.
- Simulator/emulator and pure-state evidence can close the software implementation, but audible
  click quality, Bluetooth profiles, microphone behavior, route latency and ride/wind behavior remain
  **DEFERRED — PHYSICAL QUALIFICATION**.

## Alternatives rejected

- **Let each subsystem manipulate music independently.** This has no deterministic conflict rule and
  cannot make stale completion inert.
- **Send a synchronized Phase 5 pause for Mode D speech.** Speech is endpoint-local and transient;
  turning it into shared playback authority changes the timeline and creates cross-peer races.
- **Close capture on every PTT/VOX edge.** This violates ADR-021 and causes route/profile thrash.
- **Use the WebRTC statistics poll as VOX input.** Its cadence is unsuitable for speech gating.
- **Treat a route timeout as measured settlement.** A timeout is failure protection, not evidence of
  the platform's transition time.

## Amendment A1 — 18 Sep 2026 (independent review, two confirmed blockers)

An independent review of the initial implementation found two confirmed, reachable defects — both
reproduced against the unmodified pre-fix sources before either was changed, matching this
codebase's standing audit discipline. Neither required rewriting the software-closure work this ADR
already accepted; both were narrow, targeted fixes.

**Blocker 1 — continuous transmission is not speech.** The coexistence reducer treated
`localTransmitting || peerTransmitting` as "speech is happening." For `TransmissionGate.none`
(Modes A and D) the outbound track is enabled for the whole ride segment the moment capture opens
(ARCHITECTURE §6.3), so this made Mode A duck — and Mode D pause — music **permanently**, the
instant the intercom started, regardless of whether anyone was speaking. The peer side had the same
defect: it read `VOICE_STATE.mic_muted == false` alone, which for a continuous peer means nothing
about speech either.

Fixed by introducing `SpeechActivity` (`.active` / `.inactive` / `.unavailable`) as a value
genuinely distinct from `transmitting`, mirrored on both platforms
(`TransmissionState.speechActivity`, `peerSpeechActivity(mode:transmittingOnWire:)`).
`TransmissionGate.none` has no honest speech signal at all and reports `.unavailable`, never a
fabricated `.active` or a silently-identical-to-`.active` value. `TransmissionGate.ptt` and `.vox`
have a genuine signal — a button a human is actually holding, or a level a human actually
produced — so their own gate state **is** the honest proxy; VOX's gate simply never opens in
production today (no fast level source — unchanged from ADR-021 §6), which correctly reports
`.inactive` rather than fabricating activity. `CoexistenceState.voiceActive` now requires
`localSpeechActive || peerSpeechActive` from this honest source, and a new
`CoexistenceFallback.speechActivityUnavailable` — surfaced whenever voice is up, the policy wants an
on-speech effect, and neither side has a signal — makes the resulting silence an explicit,
diagnosable state rather than an indistinguishable "nobody is talking." Mode A and Mode D now
produce **no** duck or pause from continuous transmission alone; Mode C's PTT-driven duck and Mode
B's synthetic-VOX-driven duck are unchanged, because both already carried a genuine signal.
`VoiceDiagnostics.transmitting` keeps its existing meaning ("the outbound track is enabled") — it was
never wrong, only insufficient for coexistence — and gained siblings `localSpeechActivity` and
`peerSpeechActivity` alongside it, never a replacement.

**Blocker 2 — a reconnect could relabel a predecessor's voice snapshot as the successor's own.**
`VoiceController` is deliberately retained across a control-plane reconnect (ADR-020 §6), and the one
diagnostics collector `SessionCoordinator.attachVoice` installs at first construction keeps
forwarding every snapshot it publishes to coexistence for the controller's whole lifetime — spanning
as many control lifetimes as the ride segment does. Neither the diagnostics value nor the forwarding
code carried any record of *which* control lifetime produced a given snapshot, so a reconnect's own
`attachVoice` branch synchronously reused whatever snapshot the collector had last written — A's, if
A was still the freshest thing on record the instant B authenticated — and applied it to B's
brand-new coexistence generation. This is the exact class ADR-024 Amendment A7 already named
("never read a live generation, epoch or session id to decide what a frame you already have
belongs to"), reached one layer up: at the coexistence-forwarding seam, which A7 did not touch.

Fixed the same way A7 fixed it: provenance travels with the value instead of being reconstructed at
consumption time. `VoiceDiagnostics` gained `controlGeneration`, stamped once, inside
`VoiceController.publishDiagnostics`, from the already-existing, already-audited
`VoiceNegotiationState.negotiationControlGeneration` (ADR-020 rule 23's own ownership field) — no
new authority source. `SessionCoordinator` records `voiceControlGeneration` from the same
`authGeneration` it already receives on `Connected`, and `updateCoexistence` refuses (and counts) any
diagnostics snapshot whose `controlGeneration` does not match it, rather than forwarding it under
whatever coexistence generation happens to be live. The former synchronous reconnect-branch seed —
`updateCoexistence(generation, _voiceDiagnostics.value)` — is removed outright: a freshly begun
coexistence generation starts neutral, and the same persistent collector applies the successor's own
genuine diagnostics once they arrive, exactly as it always has for the *first* control lifetime.

A narrow sweep for the same class elsewhere in Phase 6 (route state, sync availability, ramp and
pause/resume completion, policy events) found no second instance: route/interruption state rides
inside the same now-provenanced `VoiceDiagnostics`; sync availability and policy selection are
locally-sourced facts with no cross-boundary asynchronous gap; and every ramp/pause/resume completion
already re-proves its own generation against the player before taking effect (unchanged from the
initial implementation, and correct).

No wire change. The shared `protocol/vectors/coexistence/` vectors moved — `VoiceChanged` carries
`local_speech_active` / `peer_speech_active` / `speech_activity_available` instead of the removed
`local_transmitting` / `peer_transmitting`, and two rows (`mode-a-continuous-track-enabled-is-not-speech`,
`mode-d-continuous-track-enabled-is-not-speech`) were added — because the *pure table's* input shape
changed to demand an honest signal, not because its policy changed.
