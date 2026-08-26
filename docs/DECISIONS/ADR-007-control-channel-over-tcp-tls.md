# ADR-007 — Control plane on TCP + TLS 1.3, not a WebRTC DataChannel

**Status:** Accepted · 26 Aug 2026

## Context

WebRTC is already required for voice ([ADR-003](ADR-003-webrtc-voice-transport.md)) and offers
reliable ordered DataChannels (SCTP over DTLS). Reusing them for control messages and file
transfer would mean one transport and one security context.

The blocking fact: **WebRTC cannot bootstrap itself.** Establishing a `PeerConnection` requires
exchanging SDP offers/answers and ICE candidates over some channel that already works. That
signalling channel must exist before WebRTC does, and with no server it has to be a direct
socket between the phones.

So the socket is not optional. The real question is whether to build a *second* messaging system
on top of WebRTC once the first one already exists.

## Decision

One **TCP + TLS 1.3** connection is the control plane. It carries pairing, capabilities, clock
sync, WebRTC signalling, playback commands, queue replication, manifests and transfer
negotiation. WebRTC is scoped to voice media only.

File bodies use a **second TLS connection** to the same peer, so a 40 MB transfer cannot
head-of-line-block a `PAUSE` command. Authorised by a single-use `bulk_token` from
`TRANSFER_OFFER`.

Security: TLS 1.3 with self-signed per-device certificates, SPKI fingerprint pinning after
first pairing, and a 6-digit verification code derived from the **TLS exporter secret** so the
code is bound to that specific handshake and both certificates
([PROTOCOL §4.3](../PROTOCOL.md#43-pairing-first-meeting-only)).

## Consequences

- Phase 1 delivers a working, testable, secure session with **no WebRTC dependency at all**. The largest dependency in the project is deferred to Phase 2, and Phase 1 can be verified on two phones without it.
- FR-025 graceful degradation is structural: music sync, queue, catalogue and transfer live on a plane that does not care whether voice works, and vice versa.
- TCP's reliability and ordering are exactly right for commands. `command_seq` handles application-level ordering on top.
- Clock sync over TCP is subject to Nagle and retransmit delay. Mitigated by `TCP_NODELAY` and by minimum-RTT sample selection (ARCHITECTURE §7.1), which discards distorted samples by design. **To measure in Phase 1:** if TCP jitter proves to floor the offset estimate above ~5 ms, add a small unreliable UDP path for `PING`/`PONG` only. Deliberately not built pre-emptively.
- Cost: TLS certificate generation must be implemented on both platforms. Android has `KeyPairGenerator` + Keystore; iOS has `SecKey` but **no certificate-building API**, so a small hand-written DER X.509 encoder is needed. This is the highest-risk item in Phase 1b — fallback is TLS-PSK derived from the pairing secret, which Network framework supports directly.
- Cost: two transports to maintain instead of one. Accepted, because one of them was unavoidable.

## Alternatives considered

| Option | Rejected because |
|---|---|
| Everything on WebRTC DataChannels | Does not remove the signalling socket, so it adds a second messaging system rather than replacing one. Also couples all control traffic to the health of the voice stack, defeating FR-025 |
| Plain TCP, no TLS | Violates NFR-06 and §11 |
| Hand-rolled X25519 + AES-GCM handshake over plain TCP | Both platforms have the primitives (CryptoKit, Keystore), but the *handshake protocol* would be invented — precisely what the brief forbids |
| QUIC | Attractive (streams solve head-of-line blocking natively) but no first-party server-capable API on Android; would add a large dependency |
| One TLS connection for control *and* bulk | A large transfer would delay `PAUSE` by seconds. Two connections cost almost nothing |
