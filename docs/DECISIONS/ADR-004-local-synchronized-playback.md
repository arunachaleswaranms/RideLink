# ADR-004 — Replicate files, synchronise playback; never restream

**Status:** Accepted · 26 Aug 2026 · amended A1 (22 Oct 2026: correct the inter-phone error, not a lag both phones share)

## Context

Both listeners must hear the same track at nearly the same position (FR-013). Two shapes exist:
continuously stream the audio from one phone to the other, or give each phone its own copy and
start them together.

## Decision

**Each phone plays its own local file.** A track is transferred once if missing, then playback is
scheduled against a synchronised clock. Music audio never flows over the peer link during
playback — only commands and position reports.

Mechanics in [ARCHITECTURE §7](../ARCHITECTURE.md#7-clock-synchronisation-and-synchronised-playback):
monotonic clocks only, NTP-style offset estimation with minimum-RTT selection, a future
`effective_at` with `LEAD = max(120 ms, 4 × rtt_p95)`, decoder pre-roll before the deadline, and a
four-tier drift ladder (dead-band < 25 ms · rate-nudge 25–120 ms · hard seek > 120 ms · declare
failure > 2 s).

## Consequences

- Bandwidth during playback is negligible; no transcoding, no quality loss, no re-encode.
- A Wi-Fi drop does **not** interrupt music. Both phones keep playing; only synchronisation pauses. This is what makes FR-025 and UJ-06 achievable rather than aspirational.
- Battery cost is decode-only, not decode + stream.
- Cost: a track must be present on both phones before synchronised play, so first play of a missing track waits for transfer (FR-011, and the §9.4 rule that remote-only tracks cannot start).
- Cost: storage is duplicated across phones, which is why cache caps and clear-cache controls are required (NFR-04).
- Requires playback-rate control on both platforms for the nudge tier. Both have it (`ExoPlayer.setPlaybackParameters`, `AVAudioUnitVarispeed`), and `CAPABILITIES.playback_rate_control` degrades gracefully if one lacks it.

## Alternatives considered

| Option | Rejected because |
|---|---|
| Continuous restream from one phone | Wastes bandwidth and battery; a Wi-Fi blip kills music for the receiver; needs transcoding; contradicts the explicit Design Principle "Each phone plays its own music" |
| One phone plays aloud for both | Physically impossible — each person has their own Bluetooth audio device |
| Fixed startup delay, no clock sync | Crystal drift is 10–50 ppm ⇒ 36–180 ms/hour. Fails the 30-minute drift test by construction |
| Correct drift by seeking only | Audible clicks and repeated words. Hence the rate-nudge tier for the common mid-range case |

---

## See also

**This ADR was unchanged and unamended until Amendment A1 (below, 22 October 2026).** Its mechanics —
never restream, schedule against a synced clock, the four-tier ladder, `LEAD = max(120 ms, 4 ×
rtt_p95)` — are exactly what Phase 5 implemented. A1 keeps all of them. It changes what the ladder is
fed and where a hard seek lands.

[ADR-024](ADR-024-synchronized-playback-integration.md) records the *integration-level* decisions
this one does not cover (the follower→leader intent convention, two unspecified payloads, the
snapshot-only queue replication rule, the corrected queue cap, and where peer content availability
comes from), and the one refinement it does make to ARCHITECTURE §7.3: each device measures its own
drift against the authoritative timeline rather than the leader computing the follower's. It does not
re-open anything decided here.

---

## Amendment A1 — 22 October 2026 — correct the inter-phone error, not a lag both phones share

**Status of the ADR: still Accepted.** Never restream, schedule against a synchronised clock, the
four tiers and their thresholds are unchanged. This amendment changes **what the ladder is fed** and
**where a hard seek lands**. It refines [ADR-024 §10](ADR-024-synchronized-playback-integration.md#10-drift-is-measured-against-the-timeline-by-each-device-for-itself)
(see that ADR's Amendment A16). STATUS §4 problem 131. No wire change.

### Evidence

All of it was gathered before this amendment. None of it is new. The analysis is
[`test-results/phase9ba-problem131-sync-drift-20261020.md`](../test-results/phase9ba-problem131-sync-drift-20261020.md).

1. **Physical, two Android phones on built-in speakers, build `47d7cde`, 19–20 October 2026.**
   - Both phones read `local drift` of −350 to −650 ms within seconds of every synced start and
     resume.
   - Each made 2–3 audible hard seeks and then reported `SYNC_FAILED`.
   - One microphone recorded both loudspeakers on one clock. It put the two outputs **≤ 2 ms
     apart** (n = 78 clicks across PLAY, PAUSE/RESUME, SEEK and steady state).
   - So the reading was almost entirely **common-mode**. What the user hears (FR-013) was already
     right, and every correction was an audible skip on both phones together.
2. **Library bytecode (Media3 1.11.0).** The audio position comes from `AudioTrack` presentation
   timestamps or the playback head. After `play()`, and after a seek while playing (which flushes
   the track), it stays at its start value until output begins. So a hard seek **re-incurs the
   restart latency it was trying to remove**. A seek to `expected(now)` lands about one restart
   latency behind, every time. Any restart latency over 120 ms therefore uses up the 3-seeks-in-60 s
   budget, and the ladder reports `SYNC_FAILED` at about 15 s.
3. **Code.**
   - Both platforms paired a position cached by a 250 ms publication loop with a session instant
     taken *now*. That adds up to −250 ms of drift that does not exist.
   - The leader's `PLAYBACK_STATE` re-anchored the follower with that same cached position.
4. **JVM reproduction** (`SyncPlaybackOutputLatencyTest`, two real coordinators, a player modelling
   a restart latency and the 250 ms publication). It reproduces observation 1's trace.
   - A common 350 ms latency: two seeks, then `SYNC_FAILED` at 15 s.
   - Zero latency with the stale sample alone: −206 ms of false drift and two seeks.

**Not measured, and this amendment does not depend on it:** the size of any phone's restart
latency, whether it differs between a cold start and a warm seek, and any Bluetooth differential.
That is why the decision below learns the restart latency rather than assuming one.

### Decision

1. **Position samples are live and paired with the instant they were read.** `SyncPlayerPort` gains
   one read, `samplePosition()`. On Android it is `ExoPlayer.currentPosition` on the player's
   thread, stamped by the same monotonic clock the coordinator uses. On iOS it is the node's current
   render position, read inside the player actor. The drift tick and the `POSITION_REPORT` use that
   sample at its own session instant, and so do `PLAYBACK_STATE` and `STATE_SNAPSHOT`. The 250 ms
   publication loop still feeds the UI and nothing else in synchronisation.
2. **The ladder acts on the inter-phone error.** The inter-phone error is this device's drift minus
   the **reference** output's drift. Both are measured against the same `PlaybackTimeline`, each at
   its own sampled session instant.
   - The reference is the leader's latest `POSITION_REPORT`, so only the follower corrects. The
     leader is the reference and never corrects for drift.
   - A difference of two timeline-relative drifts is well defined across two instants and a network
     delay. Subtracting raw positions is not (ADR-024 §10's objection), and that is still not done.
   - A lag both phones share is now never chased. A differential lag, such as one phone's Bluetooth
     path, appears in the error and is corrected exactly as before: dead band, nudge, hard seek,
     failure.
3. **A reference sample is usable only when it means what it says.** All of these must hold, and
   the pure table decides each one:
   - it is `playing`;
   - it was sampled at least `REFERENCE_SETTLE_US` (1.5 s) after the timeline's anchor;
   - it is at most `MAX_REFERENCE_AGE_US` (12 s) old;
   - the local sample is also at least 1.5 s after the anchor and after this device's last hard seek.
   The settle window excludes the frozen-position stretch while output starts. 1.5 s is more than
   twice the largest lag observed (about 650 ms, staleness included).
   With no usable reference the ladder makes **no new decision**, and any nudge in force is
   released to exactly 1.0. "Correction never leaves a rate behind" is unchanged.
4. **A hard seek lands where the reference output will be once this device's output restarts.** The
   target is `expected(now) + reference drift + restartCompensationMs`.
   - `restartCompensationMs` is learned by the table from the first usable sample after each of its
     own seeks: `compensation used − error observed`, clamped to 0…`MAX_RESTART_COMPENSATION_MS`
     (1 000 ms; anything longer is a stall, not a latency).
   - It starts at 0. It survives a new playback epoch, because it is a property of this device's
     output. It is forgotten on a route transition, because a route change changes that property,
     and on a full reset.
   - A sample taken under a different anchor than the seek was made under teaches nothing.
   - On a phone whose position includes no restart latency (iOS render time), it stays at 0 and
     the target is ADR-004's original one.

**Unchanged (ADR-004 invariants):**
- the tiers: < 25 ms nothing; 25–120 ms nudge at 0.998/1.002 until < 15 ms; > 120 ms hard seek;
  > 2 000 ms, or a third hard seek in 60 s, sync failure;
- route-transition suspension, and the rule that it does not spend the seek budget;
- hysteresis, and "a nudge already at the requested rate re-emits nothing";
- the rate is always restored to exactly 1.0;
- every decision lives in the pure, mirrored `DriftController`, pinned by `protocol/vectors/drift/`.

### Alternatives rejected

| Option | Rejected because |
|---|---|
| Live sampling alone | Correct and kept (decision 1), but on its own it leaves the ~375 ms common lag above 120 ms. The phones still seek and fail (the reproduction's common-mode case still fails with it) |
| Exclude a per-restart baseline from each phone's own drift | Hides a genuine **differential** lag (helmet Bluetooth against TWS): each phone would subtract its own. Ruled out by this review's constraint not to conceal Bluetooth drift |
| Keep correcting each phone against the timeline, with only the compensated seek | Converges, but still makes a seek per phone on every start and resume. The two phones seek at different tick instants, so in the common-mode case it **creates** an inter-phone offset of a whole restart latency for up to one tick. That is worse than the ≤ 2 ms already measured |
| Compensate the start in the pre-roll (start at `position + latency`) | Cuts the first ~0.4 s of every track and every resume. It also needs a cold-start latency, which observation 3 suggests differs from the warm one and which nobody has measured |
| Both phones correct half the error | Two audible skips instead of one, and each reacts to the other's not-yet-corrected report. The reference moving because of correction is exactly what makes ladders oscillate |

### Consequences

- **Rule 7 (the leader serialises commands only)** gains one internal fact: the leader's output is
  the drift reference. It has no UI and gives neither phone a control the other lacks.
- **Without reports there is no correction.** That covers a link loss, a follower that has not yet
  received a settled leader report, or the first ~1.5 s after a start. ADR-004 already says sync
  pauses on a link drop. At 50 ppm, a five-minute gap accumulates 15 ms, inside the dead band.
- **A leader-side stall** shows up as a follower error and is corrected on the follower, or fails
  there above 2 s. The leader still reports its own drift against the timeline in diagnostics.
- **A device's first-ever hard seek is uncompensated**, because there is nothing to learn from yet.
  It can cost one extra seek, once per process and route.
- **Android ↔ iOS measure different things.** Android's position is presentation time and iOS's is
  render time, which excludes output latency. A mixed pair may carry a platform bias in the error.
  That is unmeasured, and it is recorded, not solved here.
- **Diagnostics** gain `syncErrorMs` (the error the ladder acted on, or none) and
  `restartCompensationMs`, so the physical retest can read both.

### Verification

- **Regenerated vectors** (`tools/generate_drift_vectors.py`, an independent transcription). Every
  pre-existing tier, hysteresis, budget and suspension row is kept, with a settled zero reference, so
  each still asserts its original answer. New rows cover:
  - reference usability (missing, paused, before settle, stale, after a local seek);
  - the error against a non-zero reference;
  - learning, clamping and the anchor rule;
  - survival across `newEpoch` and forgetting on a route transition.
- **Both platforms' coordinator suites** prove:
  - the tick reads the live sample, not the cached state;
  - the leader holds;
  - the follower feeds the leader's settled report into the table.
- `SyncPlaybackOutputLatencyTest` (the reproduction, PR #29) passes unchanged in its assertions.
  It gains a differential-latency case that must **correct** rather than conceal.
- **The physical retest is still required** (TEST_PLAN §5.2's problem-131 note).
