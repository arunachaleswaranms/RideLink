# ADR-003 — WebRTC for the voice plane only

**Status:** Accepted · 26 Aug 2026

## Context

Full-duplex low-latency voice (FR-003, NFR-01, target < 200 ms) needs a jitter buffer, a
low-latency codec, packet-loss concealment, echo cancellation, noise suppression, gain control
and encryption. Building that is a multi-year project, and the requirements document already
recommends WebRTC (§7.4, §19).

## Decision

Use **WebRTC for the voice plane only**: Opus over DTLS-SRTP, with the built-in audio processing
(AEC / NS / AGC) exposed as tunable and individually disableable for testing (FR-005 requires
real riding tests, which means being able to turn a suspect DSP stage off and measure again).

ICE is configured with an **empty server list** — host candidates only. Both peers are on the
same LAN, so no STUN or TURN is needed. This also removes an accidental egress path.

Control messaging, catalogue and file transfer do **not** use WebRTC — see
[ADR-007](ADR-007-control-channel-over-tcp-tls.md).

## Consequences

- Get a production-grade real-time audio pipeline, DTLS-SRTP encryption and Opus without writing any of it. No custom cryptography, per the brief.
- WebRTC's `AudioDeviceModule` wants to own microphone and speaker. On Android it manages `AudioRecord`/`AudioTrack` itself and prefers `VOICE_COMMUNICATION` mode; on iOS it drives `AVAudioSession` toward `.playAndRecord` + `.voiceChat`. Music playback shares that route, so the interaction between WebRTC's session management and our own is a **known Phase 2/6 risk** to measure, not assume.
- Large dependency (tens of MB per platform) with community-published artifacts — see the risk register in ARCHITECTURE §12. Isolated behind `net:voice` / `RLVoice` so it is replaceable.
- Signalling (SDP/ICE) must ride some other channel — which the control plane already is.
- WebRTC's own file-recording hooks are deliberately not compiled in: no code path writes voice to disk (REQUIREMENTS §11).

## Alternatives considered

| Option | Rejected because |
|---|---|
| Raw UDP + Opus, hand-rolled jitter buffer | Reimplements the hard parts (PLC, adaptive buffering, AEC) badly; needs custom crypto — the brief forbids it |
| WebRTC for voice *and* everything else | Still needs out-of-band signalling, so it does not remove the control channel — it just adds coupling. See ADR-007 |
| Platform voice APIs (CallKit / ConnectionService) | Built for telephony, not peer intercom; heavy system integration; no cross-platform wire compatibility |
