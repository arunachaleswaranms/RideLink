# ADR-008 — Resolving DOCX ↔ session-instruction conflicts

**Status:** Accepted · 26 Aug 2026

## Context

Two authoritative inputs exist: the source-of-truth DOCX (`RideLink_Requirements_and_Implementation_Plan.docx`,
baseline 1.0, 22 Aug 2026) and this build's master development instruction. They were written at
different times and disagree in four places. `REQUIREMENTS.md` transcribes the DOCX faithfully and
deliberately does **not** silently reconcile anything; the reconciliation lives here.

## Decision

### 1. Music synchronisation target — DOCX is stricter; adopt both

| Source | Initial | Stretch |
|---|---|---|
| DOCX §3.2 | < 100 ms | < 50 ms |
| Master instruction | ≤ 150 ms | ≤ 75 ms |

**Resolution — a three-level target, not a compromise:**

- **≤ 150 ms — hard acceptance floor.** Exceeding this fails the phase.
- **< 100 ms — product target.** The DOCX figure; what we design for.
- **< 50 ms — stretch.**

Taking the looser number as *the* target would quietly discard a requirement, and taking only the
stricter one would misreport the pass/fail line. The drift ladder
([ARCHITECTURE §7.3](../ARCHITECTURE.md#73-drift-correction)) holds steady-state error under
25 ms, so all three are consistent with one design.

### 2. Session state names — implement the superset

The DOCX §15 lists 7 states; the master instruction lists 10. The DOCX set is a subset with two
renames. **Implement the 10-state superset**, mapping `READY → CONNECTED` and `RIDING →
RIDE_ACTIVE`, and adding `DISCOVERING`, `DISCONNECTED`, `ERROR` — which the DOCX leaves implicit
but which the app needs as real, nameable states (a stalled discovery and an exhausted reconnect
budget are distinct situations a user must be able to see). Mapping table and legal transitions:
[ARCHITECTURE §3](../ARCHITECTURE.md#3-session-state-machine).

### 3. Repository layout — master instruction wins, existing dirs preserved

DOCX §20 proposes `docs/requirements/`, `docs/architecture/`, `scripts/`, plus `SECURITY.md` /
`PRIVACY.md` / `CONTRIBUTING.md`. The master instruction specifies flat files
(`docs/REQUIREMENTS.md`, `docs/ARCHITECTURE.md`, …) and `docs/DECISIONS/`.

**Resolution:** flat files as instructed — fewer, findable documents beat a directory per topic at
this size. The committed repo already contains `tools/`, so that is kept instead of introducing
`scripts/`. `docs/test-results/` and `docs/audio-tests/` are adopted from the DOCX because
per-run result files genuinely need a directory. `SECURITY.md` / `PRIVACY.md` / `CONTRIBUTING.md`
are deferred to Phase 8 (documentation hardening); their content currently lives in
REQUIREMENTS §11 and ARCHITECTURE §11.

### 4. Phase 0 status — complete, but results not yet recorded

The DOCX makes Phase 0 a mandatory gate. The user states it passed. **Phase 0 is not repeated.**

However, Phase 0's outputs — the selected intercom mode, the helmet unit model, the most stable
network topology, and the measured voice latency (DOCX §24) — have not been supplied. These are
**inputs to Phase 6, not to Phases 1–5**, so implementation proceeds. Until they are recorded in
[`PHASE0_RESULTS.md`](../PHASE0_RESULTS.md), Phase 6 assumes **Mode C (push-to-talk)**, the only
mode that cannot be broken by HFP profile switching.

## Consequences

- `REQUIREMENTS.md` stays a faithful transcription and never drifts from the DOCX. All divergence is discoverable here.
- Test gates use ≤ 150 ms as pass/fail and report the measured value against < 100 ms, so a passing-but-mediocre result is visible rather than hidden.
- The 10-state machine is what both platforms implement and what the shared FSM vectors assert.
- If the user later supplies a different Phase 0 mode, only the Phase 6 default changes — the policy object in ARCHITECTURE §6.3 already expresses all five modes.

## Alternatives considered

| Option | Rejected because |
|---|---|
| Master instruction supersedes the DOCX wholesale | Would discard the stricter, better-justified 100 ms target |
| Edit REQUIREMENTS.md to match the instruction | Destroys the audit trail against the source document |
| Block on Phase 0 results before any implementation | The unknowns gate Phase 6 only; blocking Phases 1–5 would waste the session |
