# ADR-015 — Deterministic duplicate-connection resolution

**Status:** Accepted · 26 Aug 2026

## Context

Both peers advertise *and* browse (ADR-002), because either person must be able to start the
session. The direct consequence is that both can discover each other at the same moment and both
can call `connect()`, producing **two** TCP connections between the same pair of phones.

This is not an exotic race. It is the *normal* case on every reconnect: both phones detect the
link loss within milliseconds of each other and both start retrying. The jittered backoff of
PROTOCOL §10 reduces collisions but cannot eliminate them, and is not meant to.

The baseline handled one symptom and missed the cause. It specified that
`HELLO.session_id_proposal` is resolved in the leader's favour "so a simultaneous mutual connect
converges without a tie-break round" — but agreeing on a `session_id` does not decide which
*socket* dies. Two connections agreeing on the same `session_id` is arguably worse than two
disagreeing, because it looks like success. Nothing in the baseline said:

- which physical connection survives;
- what happens to the other one;
- whether `HELLO` gets processed twice;
- whether both connections can carry commands;
- what stops the loser from immediately reconnecting.

Unresolved, the failure modes are: two `command_seq` streams, two clock-sync exchanges producing
different offsets, a reconnect loop as each side closes the other's connection and retries, and —
in the first-time-pairing case — **two different SAS codes on the two screens**, which would make
a legitimate pairing look exactly like a man-in-the-middle attack.

## Decision

Add one ephemeral field to `HELLO` / `HELLO_ACK` and one comparison rule.

```
conn_tiebreak : 16 CSPRNG bytes, as 32 lowercase hex characters
```

Generated once per app process per discovery session. Stable across every connection that process
opens or accepts during that session — which is what makes the comparison consistent on both
sockets. Never persisted. Not derived from `peer_id`, the identity key, or the mDNS discovery
handle.

> **The rule.** Once both `conn_tiebreak` values are known on a connection (i.e. after
> `HELLO`/`HELLO_ACK`), the surviving connection is the one **initiated by the peer with the
> lexicographically larger `conn_tiebreak`**.

Comparison is over the 32-character lowercase hex string, which is byte-order-identical to an
unsigned big-endian comparison of the raw 16 bytes. Both peers observe the same pair of values on
both sockets, so both reach the same verdict with no additional round trip and no timers.

Full procedure, including the already-`CONNECTED` case and the astronomically unlikely tie:
[PROTOCOL §4.2](../PROTOCOL.md#42-duplicate-and-simultaneous-connections).

### Why `conn_tiebreak` and not `peer_id`

`peer_id` is the obvious candidate and it is wrong for three reasons, in increasing order of
importance:

1. **It does not exist yet during first-time pairing.** Deduplication has to complete *before*
   `PAIR_REQUEST`, or a simultaneous first meeting shows two SAS codes. A rule that only works for
   already-trusted peers would leave the worst case uncovered.

2. **It would tie connection ownership to leadership.** Leadership is the smaller `peer_id`
   (ADR-010) and is deliberately independent of who dialled. Using the same key for both makes the
   two rules structurally indistinguishable, and an implementation could conflate "I called
   `connect()`" with "I am the leader" while every test still passed — until the day the other
   phone happened to dial first.

3. **It is durable.** `conn_tiebreak` is ephemeral, so it leaks nothing lasting to an observer,
   which matters given ADR-002 Amendment A1 just removed the last durable value from discovery.

The direction of the comparison — *larger* tiebreak's outbound connection survives — is chosen so
that on the surviving connection the leader is the **acceptor**, not the initiator. That is
deliberate: it means any code that quietly assumes "initiator ⇒ leader" fails the `dedup/*.json`
vectors immediately, instead of working by coincidence in the lab and breaking on a ride.

### Behaviour

| Situation | Behaviour |
|---|---|
| Second connection's HELLO exchange completes while still in `CONNECTING` | Apply the rule. Loser receives `BYE { reason: "duplicate_connection" }`, then TLS `close_notify`, then FIN. Winner proceeds to `CAPABILITIES` |
| `conn_tiebreak` values identical (probability 2⁻¹²⁸) | Both sides close **both** connections, regenerate `conn_tiebreak`, wait 500–1500 ms jittered, retry |
| Inbound connection from a peer whose session already reached `CONNECTED` | `ERROR { code: "session_already_active", fatal: true }`, closed immediately. No HELLO processing, no state change, no effect on the live session |
| Inbound connection while `RECONNECTING` | Ordinary reconnect attempt, subject to the rule. The survivor resumes the existing `session_id` |

Closing a duplicate is **not** a fault and **not** a state transition (ARCHITECTURE §3 rule 6).
`duplicate_connection` must not increment `reconnect_count`, must not raise a user-visible error,
and must not be logged as an error. `BYE` already suppresses reconnect, so the loser does not
retry — which is what prevents the loop.

## Consequences

- Exactly one control connection per peer pair, ever. Enforced by a rule both sides evaluate identically from data they both hold.
- `HELLO` is processed at most once per surviving connection. The loser produces no session, no clock sync, no capability exchange and no `command_seq`.
- Split brain is structurally impossible: the loser is closed before any `command_seq` can be assigned on it.
- Exactly one SAS code is ever displayed, because deduplication precedes `PAIRING`. This is the failure this ADR most exists to prevent.
- First-time pairing and trusted reconnect use the **same** rule, so there is one code path and one set of vectors rather than two subtly different ones.
- No distributed consensus, no election protocol, no timers, no extra round trip. For two peers, a shared comparison over values both already hold is sufficient — and anything heavier would be complexity for its own sake.
- Cost: one extra 32-character field on two message types, and a transport-layer holding area for connections that have completed TLS but not yet resolved. `SessionCoordinator` never sees a candidate connection, which is what keeps a losing socket away from session state.
- Cost: `conn_tiebreak` is a third random per-session value alongside `session_id` and the mDNS discovery handle. Distinct purposes, distinct lifetimes, deliberately not shared — reusing one for two jobs is how the `peer_id` mistake would have been made.
- Consequence for `session_id`: the leader's proposal still wins, but the resolution now happens only on the surviving connection, so there is no longer a case where two sockets agree on a `session_id` and both think they own it.

## Alternatives considered

| Option | Rejected because |
|---|---|
| Compare `peer_id`s | Does not exist before pairing — leaving the two-SAS-codes case uncovered — and welds connection ownership to leadership. See above |
| Only one side listens; the other only dials | Contradicts "phones are peers": whichever person opened their app second could never start the session |
| Whoever's `HELLO` arrives first wins | Depends on local timing, so the two peers can disagree. Non-deterministic by construction — exactly what this ADR exists to remove |
| Compare `session_id` ULIDs | ULIDs embed a timestamp, so this is timestamp comparison in disguise, over two unsynchronised clocks, at the one moment when the clock offset has not been measured yet |
| Compare socket addresses / ports | Ephemeral ports are OS-assigned and can collide in ordering; carries no meaning; and on a hotspot the addressing is asymmetric |
| Accept both connections, use one for control and one for bulk | Confuses two planes with different lifetimes and authorisation models. The bulk plane is authorised per transfer by a `bulk_token` (PROTOCOL §8.2) and must not inherit a control connection's role |
| Lock-step handshake with an explicit tie-break round | An extra round trip on every single reconnect to handle a case a stateless comparison already handles |
| Ignore it and let TCP sort itself out | It does not. TCP has no opinion about which of two independent connections is the application's session |
