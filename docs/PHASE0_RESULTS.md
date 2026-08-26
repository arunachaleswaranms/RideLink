# Phase 0 — Feasibility Results

> **Status: AWAITING USER INPUT.**
> Phase 0 was completed successfully by the user off-repo and **must not be repeated**.
> Its measured outputs were not supplied to the repository. They are inputs to **Phase 6**
> (intercom + music coexistence), not to Phases 1–5, so implementation proceeds meanwhile.
>
> Until this file is filled in, Phase 6 defaults to **Mode C (push-to-talk)** — the only mode
> that cannot be broken by an HFP profile switch. See [ADR-008 §4](DECISIONS/ADR-008-requirement-conflict-resolutions.md).

## Hardware

| Item | Value |
|---|---|
| Rider phone / OS | OnePlus Nord 5 / _TBD_ |
| Helmet Bluetooth unit — make/model | _TBD_ |
| Helmet unit firmware | _TBD_ |
| Pillion phone / OS | iPhone 17 Pro Max / _TBD_ |
| Pillion TWS — make/model | _TBD_ |

## Test outcomes (REQUIREMENTS §12)

| Test | Result | Notes |
|---|---|---|
| P0-T1 Media only | _TBD_ | |
| P0-T2 Voice only | _TBD_ | |
| P0-T3 Mic + music | _TBD_ | **Key question:** did the route switch A2DP → HFP? What happened to music quality? |
| P0-T4 Cross-platform intercom (≥30 min) | _TBD_ | |
| P0-T5 Screen locked | _TBD_ | |
| P0-T6 Reconnect | _TBD_ | |
| P0-T7 Noise | _TBD_ | |

## Decisions that came out of Phase 0

| Question (REQUIREMENTS §24) | Answer |
|---|---|
| **Selected intercom mode (A/B/C/D/E)** | _TBD_ — drives the Phase 6 default |
| Android BT profile + sample rate when mic is open | _TBD_ |
| Does the TWS behave differently with its mic active? | _TBD_ |
| Background/lock-screen viable for a long session? | _TBD_ |
| **Most stable network topology** (common Wi-Fi / Android hotspot / iPhone hotspot) | _TBD_ — drives Phase 1 test I-07 priority |
| **Measured end-to-end voice latency** | _TBD_ ms |
| Minimum music formats needed for the real library | _TBD_ |
| Default iPhone cache cap | _TBD_ |

## Go / no-go

**Recorded outcome:** GO (per user).
**Conditions or caveats:** _TBD_
