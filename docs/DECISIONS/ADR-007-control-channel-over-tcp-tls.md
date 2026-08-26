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

Security: TLS 1.3 with self-signed per-device certificates, **`identity_spki_sha256` pinning**
after first pairing ([ADR-012](ADR-012-spki-peer-identity.md)), and a six-digit verification code
derived from the **TLS exporter secret** so the code is bound to that specific handshake and both
certificates ([PROTOCOL §4.5](../PROTOCOL.md#45-pairing--first-meeting-only)).

## Consequences

- Phase 1 delivers a working, testable, secure session with **no WebRTC dependency at all**. The largest dependency in the project is deferred to Phase 2, and Phase 1 can be verified on two phones without it.
- FR-025 graceful degradation is structural: music sync, queue, catalogue and transfer live on a plane that does not care whether voice works, and vice versa.
- TCP's reliability and ordering are exactly right for commands. `command_seq` handles application-level ordering on top.
- Clock sync over TCP is subject to Nagle and retransmit delay. Mitigated by `TCP_NODELAY` and by minimum-RTT sample selection (ARCHITECTURE §7.1), which discards distorted samples by design. **To measure in Phase 1:** if TCP jitter proves to floor the offset estimate above ~5 ms, add a small unreliable UDP path for `PING`/`PONG` only. Deliberately not built pre-emptively.
- Cost: TLS certificate generation must be implemented on both platforms. Android has `KeyPairGenerator` + Keystore; iOS has `SecKey` but **no certificate-building API**, so a small hand-written DER X.509 encoder is needed. This is the highest-risk item in Phase 1b. There is **no validated fallback** — see Amendment A1 below.
- Cost: two transports to maintain instead of one. Accepted, because one of them was unavoidable.
- Cost: the six-digit SAS depends on a TLS keying-material **exporter** being reachable from public API on both platforms. That is not confirmed on either side, and it is tracked as a second high-severity Phase 1b spike alongside certificate generation. Amendment A1 governs the response if it is absent.

## Alternatives considered

| Option | Rejected because |
|---|---|
| Everything on WebRTC DataChannels | Does not remove the signalling socket, so it adds a second messaging system rather than replacing one. Also couples all control traffic to the health of the voice stack, defeating FR-025 |
| Plain TCP, no TLS | Violates NFR-06 and §11 |
| Hand-rolled X25519 + AES-GCM handshake over plain TCP | Both platforms have the primitives (CryptoKit, Keystore), but the *handshake protocol* would be invented — precisely what the brief forbids |
| QUIC | Attractive (streams solve head-of-line blocking natively) but no first-party server-capable API on Android; would add a large dependency |
| One TLS connection for control *and* bulk | A large transfer would delay `PAUSE` by seconds. Two connections cost almost nothing |

---

## Amendment A1 — 26 August 2026 — secure transport contingency

**Status of the ADR: still Accepted.** The decision is unchanged: TCP + TLS 1.3, self-signed
per-device certificates, `identity_spki_sha256` pinning, and a SAS bound to the TLS exporter. What
changes is the contingency language, because the original text claimed a fallback that had not
been validated.

**What the original said.** "Fallback is TLS-PSK derived from the pairing secret, which Network
framework supports directly."

**Why that is withdrawn.** It reads as a decided, available Plan B and it is not one:

- The claim was only ever checked against the *iOS* side. Whether Android's **public** APIs can act as a TLS 1.3 PSK endpoint — both offering and accepting an externally supplied PSK, with a matching cipher suite and identity hint, without reaching into Conscrypt internals — was never verified. An unverified half of a cross-platform fallback is not a fallback.
- TLS-PSK also removes the thing the current design is built on. The pin becomes a shared secret rather than a per-device public key, so `identity_spki_sha256` (ADR-012) has nothing to pin, certificate re-issuance semantics become moot, and the SAS's channel binding changes character. That is a different security architecture, not a drop-in substitute — and it deserves a design review, not a footnote.
- Naming it as ready created a real hazard: under Phase 1b schedule pressure, "the ADR says we can fall back to PSK" is exactly the sentence that ships an unreviewed security change.

**The corrected contingency.** If self-signed X.509 identity generation proves infeasible or
unstable on either platform — or if the TLS keying-material exporter the SAS depends on turns out
to be unreachable from public API — then **stop and run a focused cross-platform secure-transport
design review.** Produce a written comparison, update or supersede this ADR, and only then
implement.

Explicitly **not** permitted as a response, at any point, under any schedule pressure:

- plain TCP without TLS, in any build that is not a debug build (NFR-06);
- private, hidden or reflection-reached platform APIs;
- a TLS version or cipher profile that undermines the requirements;
- any invented or hand-rolled cryptographic handshake — the brief forbids custom crypto, and this is the exact temptation it forbids.

**Status of an alternate secure transport: *contingency unresolved, pending implementation
spike.*** Not "TLS-PSK is available". Nothing is claimed about a substitute until someone has run
code on both platforms.

**Phase 1b priority.** Two spikes, both high severity, both to be run early and before the rest of
Phase 1b depends on their outcome:

1. **Certificate generation** — self-signed X.509 from a Keystore/Keychain keypair on both platforms; TLS 1.3 handshake completing between an Android and an iOS device; SPKI hash computed identically on both sides.
2. **Exporter availability** — a TLS keying-material exporter reachable from public API on both platforms, producing identical output for the same handshake, matching the construction in [PROTOCOL §4.5.1](../PROTOCOL.md#451-the-six-digit-sas--exact-construction).

Either failing triggers the review above. Neither failing quietly lowers the security bar.
