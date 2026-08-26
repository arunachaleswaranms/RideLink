# ADR-006 — JSON for control messages in V1

**Status:** Accepted · 26 Aug 2026

## Context

The control plane carries pairing, capabilities, clock sync, playback commands, queue
replication and manifests. The session instructions permit JSON for V1 "unless there is a
concrete reason to introduce Protobuf".

## Decision

**Length-prefixed UTF-8 JSON**, one object per frame, `uint32` big-endian length prefix, 256 KiB
frame cap. Envelope defined in [PROTOCOL §2](../PROTOCOL.md#2-envelope).

Codecs: `kotlinx-serialization-json` (compile-time codegen, no reflection) and Swift `Codable`.
Both give exact control over the emitted shape, which matters because two independent
implementations must agree byte-for-byte on the vectors.

**File bodies are exempt.** Bulk transfer chunks are binary frames
([PROTOCOL §8.1](../PROTOCOL.md#82-transfer)) — base64 would add 33 % to the largest payloads
in the system for no benefit.

## Consequences

- Wire traffic is human-readable, so a packet capture or a log line is directly debuggable. For a two-person system whose hardest bugs are timing and ordering, that is worth real bytes.
- Additive schema changes are non-breaking (unknown fields ignored), so `v` rarely needs to move.
- Control volume is tiny: `POSITION_REPORT` every 5 s, `PING` every 2 s, `METRICS` every 10 s, plus user commands. Even a 1 000-track manifest is a few hundred KB, sent once per session and then deltas.
- Cost: manifests exceed one frame and must be **paginated**. This cost was noted here but not
  originally paid on the wire: `MANIFEST_DELTA` only helps *after* a successful first sync, and a
  delta over a large library edit overflows too. Resolved by
  [ADR-013](ADR-013-paginated-manifest-sync.md) — a `MANIFEST_BEGIN` / `MANIFEST_PAGE` × n /
  `MANIFEST_END` sequence, sized by encoded bytes, with the 256 KiB frame cap left untouched.
- Cost: JSON has no native integer-vs-float distinction in some parsers. Mitigated by keeping all timestamps as integer microseconds and asserting integrality in the vectors.
- Not suitable for per-packet audio — which is why voice is SRTP and never JSON.

## Alternatives considered

| Option | Rejected because |
|---|---|
| Protobuf | Real benefits (compact, typed, generated) but adds a codegen step to both toolchains and makes debugging opaque. No concrete performance need at this volume. Reconsider if manifests or message rates grow materially |
| CBOR / MessagePack | Compactness we do not need, at the cost of readability we do |
| Custom binary | Hand-rolled parsers are exactly where framing bugs live |

**Revisit trigger:** if control-plane bandwidth or parse cost ever shows up in a Phase 5 or 7
measurement, migrate the envelope to Protobuf behind the existing codec interface. The
`v` field and the vector suite make that a contained change.
