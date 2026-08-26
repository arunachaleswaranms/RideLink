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

ADRs 011–016 and the three amendments came out of the pre-Phase-1 correction pass recorded in
[`../STATUS.md`](../STATUS.md#2-what-changed-in-the-correction-pass).

**Adding one:** next free number, update this table, link it from the relevant section of
`ARCHITECTURE.md`.

**Changing one:** never rewrite an accepted decision in place. Either append a clearly labelled,
dated `## Amendment An` section — appropriate when the decision stands and a detail of it changes
— or write a superseding ADR and set the old one's status to `Superseded by ADR-nnn`. Either way
the original reasoning stays readable, and an "Alternatives considered" row that names a
now-withdrawn option keeps its historical value only if the current status is unambiguous.
