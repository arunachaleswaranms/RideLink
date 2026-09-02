# Architecture Decision Records

One file per significant, hard-to-reverse decision. Format: Status · Context · Decision ·
Consequences · Alternatives considered.

| ADR | Title | Status |
|---|---|---|
| [001](ADR-001-native-android-and-ios.md) | Native Android and iOS, no cross-platform UI framework | Accepted |
| [002](ADR-002-lan-mdns-discovery.md) | LAN/hotspot transport with mDNS/DNS-SD discovery | Accepted · **amended A1** (26 Aug 2026 — no stable identity in TXT records) |
| [003](ADR-003-webrtc-voice-transport.md) | WebRTC for the voice plane only | Accepted |
| [004](ADR-004-local-synchronized-playback.md) | Replicate files, synchronise playback — never restream | Accepted |
| [005](ADR-005-content-hash-track-identity.md) | Two-tier content hashing for track identity | Accepted |
| [006](ADR-006-json-control-encoding.md) | JSON for control messages in V1 | Accepted · manifest cost resolved by ADR-013 |
| [007](ADR-007-control-channel-over-tcp-tls.md) | Control plane on TCP+TLS 1.3, not a WebRTC DataChannel | Accepted · **amended A1** (26 Aug 2026 — secure-transport contingency; TLS-PSK fallback withdrawn) |
| [008](ADR-008-requirement-conflict-resolutions.md) | Resolving DOCX ↔ session-instruction conflicts | Accepted |
| [009](ADR-009-ios-music-library-scope.md) | iOS library is app-container only | Accepted |
| [010](ADR-010-internal-leader-election.md) | Internal leader election by peer_id | Accepted · **amended A1** (26 Aug 2026 — leadership ≠ connection ownership) |
| [011](ADR-011-platform-baselines.md) | Platform baselines: Android API 31/36/36, iOS 26.0 | Accepted |
| [012](ADR-012-spki-peer-identity.md) | Peer identity is the SPKI SHA-256, not the certificate | Accepted |
| [013](ADR-013-paginated-manifest-sync.md) | Paginated manifest synchronisation | Accepted |
| [014](ADR-014-initial-module-structure-and-di.md) | Pragmatic initial module structure, and manual DI | Accepted |
| [015](ADR-015-duplicate-connection-resolution.md) | Deterministic duplicate-connection resolution | Accepted |
| [016](ADR-016-effective-audio-capability-model.md) | Effective audio capability model, not independent routes | Accepted |
| [017](ADR-017-identity-key-and-certificate.md) | P-256 identity key, and a shared certificate encoder on both platforms | Accepted |
| [018](ADR-018-tls-exporter-channel-binding.md) | The SAS channel binding is a TLS 1.3 exporter with an empty context | Accepted |
| [019](ADR-019-connected-means-authenticated.md) | `Connected` means the trust gate passed, not that TLS came up | Accepted |
| [020](ADR-020-webrtc-voice-foundation.md) | Phase 2a voice foundation: pinned WebRTC distributions, leader-is-offerer, host-only ICE, and the `stop`/`release` audio-session split | Accepted |

ADRs 011–016 and the three amendments came out of the pre-Phase-1 correction pass recorded in
[`../STATUS.md`](../STATUS.md#2-what-changed-in-the-correction-pass). ADRs 017–018 came out of the
Phase 1b security spike, and are backed by measurements in
[`../test-results/phase1b-security-spike-20260827.md`](../test-results/phase1b-security-spike-20260827.md)
rather than by argument — they close ADR-007 Amendment A1's two open risks. ADR-019 came out of the
Phase 1b security-state review: every mechanism 017 and 018 specify was implemented correctly and
then joined together by one event too few, so an unknown peer could reach `CONNECTED` before the
six digits were shown.

ADR-020 answers the four things ADR-003 left open — which WebRTC distribution, who offers, how ICE
stays local, and what owns the microphone — and is backed by
[`../test-results/phase2a-webrtc-spike-20260828.md`](../test-results/phase2a-webrtc-spike-20260828.md),
including real DTLS-SRTP/Opus media measured on this machine. It also records the audio-session
`stop`/`release` split, which is the decision ADR-003's "known Phase 2/6 risk" turned into.

**Adding one:** next free number, update this table, link it from the relevant section of
`ARCHITECTURE.md`.

**Changing one:** never rewrite an accepted decision in place. Either append a clearly labelled,
dated `## Amendment An` section — appropriate when the decision stands and a detail of it changes
— or write a superseding ADR and set the old one's status to `Superseded by ADR-nnn`. Either way
the original reasoning stays readable, and an "Alternatives considered" row that names a
now-withdrawn option keeps its historical value only if the current status is unambiguous.
