# Architecture Decision Records

One file per significant, hard-to-reverse decision. Format: Status · Context · Decision ·
Consequences · Alternatives considered.

| ADR | Title | Status |
|---|---|---|
| [001](ADR-001-native-android-and-ios.md) | Native Android and iOS, no cross-platform UI framework | Accepted |
| [002](ADR-002-lan-mdns-discovery.md) | LAN/hotspot transport with mDNS/DNS-SD discovery | Accepted |
| [003](ADR-003-webrtc-voice-transport.md) | WebRTC for the voice plane only | Accepted |
| [004](ADR-004-local-synchronized-playback.md) | Replicate files, synchronise playback — never restream | Accepted |
| [005](ADR-005-content-hash-track-identity.md) | Two-tier content hashing for track identity | Accepted |
| [006](ADR-006-json-control-encoding.md) | JSON for control messages in V1 | Accepted |
| [007](ADR-007-control-channel-over-tcp-tls.md) | Control plane on TCP+TLS 1.3, not a WebRTC DataChannel | Accepted |
| [008](ADR-008-requirement-conflict-resolutions.md) | Resolving DOCX ↔ session-instruction conflicts | Accepted |
| [009](ADR-009-ios-music-library-scope.md) | iOS library is app-container only | Accepted |
| [010](ADR-010-internal-leader-election.md) | Internal leader election by peer_id | Accepted |

**Adding one:** next free number, update this table, link it from the relevant section of
`ARCHITECTURE.md`. Superseding an ADR does not delete it — set its status to `Superseded by
ADR-nnn` so the reasoning trail survives.
