# ADR-004 — Replicate files, synchronise playback; never restream

**Status:** Accepted · 26 Aug 2026

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

**This ADR is unchanged and unamended.** Its mechanics — never restream, schedule against a synced
clock, the four-tier ladder, `LEAD = max(120 ms, 4 × rtt_p95)` — are exactly what Phase 5
implemented.

[ADR-024](ADR-024-synchronized-playback-integration.md) records the *integration-level* decisions
this one does not cover (the follower→leader intent convention, two unspecified payloads, the
snapshot-only queue replication rule, the corrected queue cap, and where peer content availability
comes from), and the one refinement it does make to ARCHITECTURE §7.3: each device measures its own
drift against the authoritative timeline rather than the leader computing the follower's. It does not
re-open anything decided here.
