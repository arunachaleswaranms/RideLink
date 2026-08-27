# ADR-019 — `Connected` means the trust gate passed, not that TLS came up

**Status:** Accepted · 27 Aug 2026
**Relates to:** [ADR-012](ADR-012-spki-peer-identity.md) (the pin), [ADR-015](ADR-015-duplicate-connection-resolution.md) (which connection pairs), [ADR-018](ADR-018-tls-exporter-channel-binding.md) (what the six digits are bound to)
**Governs:** [ARCHITECTURE §3](../ARCHITECTURE.md#3-session-state-machine) and [§4.3](../ARCHITECTURE.md#43-identity-and-pairing), [PROTOCOL §4.1](../PROTOCOL.md#41-handshake) and [§4.5](../PROTOCOL.md#45-pairing--first-meeting-only)
**Vectors:** [`protocol/vectors/session-gate/gate_vectors.json`](../../protocol/vectors/session-gate/gate_vectors.json)

## Context

Phase 1b built every security mechanism the design calls for — TLS 1.3 with mutual
authentication, `identity_spki_sha256` pinning, an exporter-bound six-digit SAS, persisted trust —
and then wired them into the session state machine with one event too few.

`ControlSessionManager.promote()` emitted `ControlEvent.Connected` the moment duplicate-connection
resolution (§4.2) picked a survivor. `SessionCoordinator` received it while the FSM was in
`PAIRING` and, having nothing else that could carry `PAIRING -> CONNECTING`, applied
`PairingSucceeded`. The observable result, on both platforms:

```
TLS handshake  ->  SPKI checked  ->  dedup  ->  Connected
                                                   |
                          PAIRING -> CONNECTING -> CONNECTED
                                                   |
                                          ...then beginPairing(),
                                          then the six-digit code appears
```

An **unknown** peer reached `CONNECTED` before the SAS was displayed, let alone confirmed. Every
individual mechanism was correct and every one of them was tested; what was untested was the
sentence joining them, and no unit test failed. Discovered by review, not by the suite — which is
the part worth remembering.

The root confusion is a category error that is easy to make and hard to see: **a TLS socket is a
transport, and an authenticated RideLink session is a claim about the person at the other end.**
Conflating them makes "the bytes are encrypted" indistinguishable from "we know who this is",
which is exactly the distinction pairing exists to draw.

## Decision

### 1. `ControlEvent.Connected` has one meaning, and it is a security claim

> The surviving secure control connection **has passed the RideLink trust gate** and may be
> treated as authenticated by the session FSM.

It does *not* mean "TLS and `HELLO` succeeded". It is emitted from exactly one function on each
platform — `activateAuthenticatedSession` — reachable by exactly two routes, and never from the
handshake or from candidate promotion.

### 2. There are exactly two ways through the gate, and a new event carries the silent one

| Peer | Route | Events, in order |
|---|---|---|
| Stored pin matches (PROTOCOL §4.1 silent connect) | no user action | `PeerTrusted` → `Connected` |
| Unknown (PROTOCOL §4.5 first meeting) | both users confirm the six digits, pin written | `PairingRequired` → *(two humans)* → `PairingSucceeded` → `Connected` |
| Stored pin differs (ADR-012) | refused | `HandshakeRefused{pin_mismatch}` — and no `Connected`, ever |

`ControlEvent.PeerTrusted` is new. It exists so that the trusted path has an event of its own to
carry `PAIRING -> CONNECTING`, which is what allows `Connected` to stop doubling as implicit
pairing success. Adding one event was preferred to the alternatives in "Alternatives considered".

### 3. The FSM is unchanged. The gate is a separate, pure table

No new state, no new `SessionEvent`, no changed transition: `protocol/vectors/session-fsm/` passes
untouched. What is new is `SessionGate` — `(ControlEvent, SessionStatus) -> SessionEvent?` — pure,
mirrored line for line on both platforms, and pinned by a shared 120-row vector file that is the
complete cross-product of every control event and every session status.

The one row that matters:

> No `Connected` row, from any status, may produce `PairingSucceeded`.

`SessionCoordinator` keeps ownership of the state (CLAUDE.md rule 8) — it owns the `FsmState`,
applies what the gate returns, and performs the side effects (persisting `last_seen_at`, raising a
security alert, starting a reconnect). What moved out is a translation table, not state.

### 4. `PAIRING` now genuinely spans the whole first meeting

ARCHITECTURE §3 always said `PAIRING` means "trust being established". It now does: the session
enters it when a peer is selected and leaves it only when the gate opens or the pairing ends.
Two consequences follow, and both are deliberate:

- **A link that dies mid-pairing must not wedge the session.** Neither `LinkLost` variant is a
  legal event in `PAIRING`, so `SessionGate` maps both to `PairingRejectedOrTimeout` — pairing did
  not happen, and `PAIRING -> DISCOVERING` is the documented exit for that.
- **`connect_attempted` is deliberately not re-armed** on the way back to `DISCOVERING`. A pairing
  the user just refused must not be re-offered by the next mDNS `Found` event.

### 5. Transport tasks and session tasks start at different moments

| Starts at | What | Why |
|---|---|---|
| promotion (TLS up) | read loop, keepalive | PROTOCOL §4.5's pairing frames arrive on this same connection, and a link that dies mid-pairing has to be noticed |
| the trust gate opening | clock-sync burst | ARCHITECTURE §7.1 places the opening burst at `CONNECTING`, which is now genuinely after authentication |

The read loop additionally drops any frame outside a closed pre-authentication list —
`PING`, `PONG`, `PAIR_REQUEST`, `PAIR_CONFIRM`, `PAIR_RESULT`, `BYE`, `ERROR` — until the gate has
opened. Today that changes nothing, because those are the only types implemented; it exists so
that a Phase 2 message type is inert before authentication unless it is added to the list on
purpose. `PING`/`PONG` in particular can never mark authentication complete.

### 6. Pairing completes on the connection that is already open

The six digits are bound to one exporter (ADR-018). Pairing succeeding therefore continues on the
**same socket** — no second dial, no second handshake, no second exporter — or the users would
have approved a session that is no longer the one in use. A regression test counts the transport's
dials and asserts one per side across the whole flow.

### 7. A pairing that fails closes deliberately, and the peer's reason is bounded

A refusal writes no pin, clears the six digits on both screens, sends `ERROR{fatal}` and ends the
connection as a **deliberate** close (`user_ended`), so it can never re-enter the reconnect ladder.
The peer's `code` is surfaced to the local user only if it is one of PROTOCOL §4.6's defined
codes; anything else becomes `pairing_rejected`. A remote peer chooses what it sends, and it must
not choose what a security message says.

## Consequences

- The Phase 1b acceptance criterion is now expressible and testable: *for an unknown peer there is no execution path that reaches `CONNECTED` before SAS confirmation on both sides and trust persistence.* It is pinned by the shared gate vectors and by real-TLS integration suites on both platforms.
- `PAIRING` lasting for the whole exchange makes the FSM state visible in the UI mean what it says, and makes `StartRide` structurally impossible before authentication.
- Diagnostics show `CONNECTING` rather than `CONNECTED` while a code is on screen. That is more honest and it is a user-visible change.
- One extra event crosses the manager/coordinator boundary on the trusted path. Cheap.
- **Not addressed here:** a pairing *timeout*. PROTOCOL §4.5 specifies a rate limit (3 attempts per minute) and no timeout, so none was invented. If one is wanted it belongs in PROTOCOL first.

## Alternatives considered

| Option | Why not |
|---|---|
| **Keep `Connected` where it was and have `SessionCoordinator` check `pinDecision`** | Puts the security boundary in the UI-facing layer and duplicates it per platform. The boundary belongs where the connection is owned |
| **Let `Connected` keep meaning "TLS up" and add `Authenticated` alongside** | Two events that both sound like success, one of which is a trap. The safer name is the one that already exists everywhere; making it mean the safe thing is better than adding a second name and hoping callers pick correctly |
| **Reuse `PairingSucceeded` for the trusted path (no `PeerTrusted`)** | It would mean emitting "pairing succeeded" when no pairing happened, and `SessionCoordinator` would persist a trusted-peer record on every reconnect. A lie in an event name is exactly how this bug happened the first time |
| **Collapse `PAIRING` and `CONNECTING` into one state** | Loses the distinction the FSM exists to draw, and `protocol/vectors/session-fsm/` would have to change. The states were right; only the event that moved between them was wrong |
| **Add a `PENDING_TRUST` state** | The state already exists and is called `PAIRING`. A new state would have to be reflected in ARCHITECTURE §3, the vectors, and both platforms, to express something the existing one already means |
| **Tear down and re-dial once pairing succeeds** | A second handshake produces a second exporter, so the code the users compared would no longer bind the session in use (ADR-018) |
