# ADR-012 — Peer identity is the SPKI SHA-256, not the certificate

**Status:** Accepted · 26 Aug 2026

## Context

The baseline documents referred to the pinned peer identity by two different names for the same
thing, and by one name for a *different* thing:

- `PROTOCOL.md` used `cert_fingerprint` in `HELLO`, `PAIR_REQUEST`, `PAIR_RESULT` and in the trusted-peer record;
- `ARCHITECTURE.md` §4 and `ADR-007` said "certificate SPKI fingerprint";
- `ARCHITECTURE.md` §11 said "certificate fingerprints → first 6 hex" in the redaction rules;
- the mDNS TXT record advertised `fp6`, "first 6 hex of TLS cert fingerprint".

Those are not synonyms. A certificate fingerprint is a hash over the whole DER certificate —
serial number, validity window, subject, signature and all. An SPKI fingerprint is a hash over
just the SubjectPublicKeyInfo, i.e. over the public key. They differ in exactly the case that
matters: **the same key wrapped in a newly generated self-signed certificate.**

Self-signed identity certificates get regenerated for ordinary reasons: a validity window
expires, the DER encoder changes, the app is reinstalled while the Keystore/Keychain key
survives, a bug fix changes a field. If the pin is over the certificate bytes, every one of those
turns a trusted phone into an unknown peer demanding a fresh SAS confirmation mid-ride — and
worse, trains the user to accept re-pairing prompts, which is precisely the reflex a MITM needs.

## Decision

There is **one** durable identity value, one name for it, and one place it is pinned.

```
identity_spki_sha256  =  "sha256:" ‖ lowercase_hex( SHA-256( DER SubjectPublicKeyInfo ) )
```

64 hex characters after the prefix. It is the hash of the long-term identity **public key**, not
of any certificate that wraps it.

Applied everywhere, with no remaining alias:

| Surface | Field / behaviour |
|---|---|
| `HELLO`, `HELLO_ACK` | `identity_spki_sha256` (advisory; cross-checked against the TLS certificate, mismatch ⇒ `ERROR/identity_mismatch`) |
| `PAIR_REQUEST`, `PAIR_RESULT` | `identity_spki_sha256` |
| Trusted-peer record | `{ peer_id, identity_spki_sha256, display_name, paired_at, last_seen_at }` |
| Reconnect validation | compare computed SPKI hash against the stored pin; nothing else is pinned |
| Bulk transfer connection | pinned to the same `identity_spki_sha256` as the control connection |
| Logs / diagnostics | `identity_spki_sha256` → first 6 hex, by construction |
| mDNS TXT | **absent entirely** — see ADR-002 Amendment A1 |
| Vectors | `protocol/vectors/identity/` |

The name `cert_fingerprint` is retired. It described the value inaccurately, and an inaccurate
name for a security-critical field is a latent bug: an implementer who reads `cert_fingerprint`
and hashes the certificate produces something that interoperates until the day a certificate is
re-issued.

### Validation semantics

Trust is a property of the key. The certificate is validated for *structure* — well-formed DER,
self-signature verifies, within its validity window — but its serial, subject, validity dates and
whole-certificate hash are **not** pinned.

| Event | SPKI | Result |
|---|---|---|
| Certificate regenerated around the same identity keypair | unchanged | **Still trusted.** Silent connect. Logged at info as a re-issue, with the first 6 hex of the SPKI hash |
| Identity keypair changed | different | `ERROR/pin_mismatch`. An unknown peer, regardless of what else matches. Never auto-re-paired |
| Certificate expired or not yet valid | any | `ERROR/certificate_invalid` — a distinct code, so a device clock problem is not reported to the user as an attack |

Recovery from a genuine key change is explicit and user-driven: forget the peer, pair again, fresh
SAS on both screens. There is no signed key-rotation message in V1, and PROTOCOL §12 lists it as
reserved rather than planned. Identity certificates are issued with a 10-year validity window so
that expiry never masquerades as rotation.

## Consequences

- Certificate re-issuance is a non-event, so nothing trains the user to click through a security prompt.
- A different key always fails the pin. The security property that actually matters is unchanged and now stated in terms of the thing it protects.
- One name, everywhere, matching what the bytes are. A reviewer can no longer be misled by the field name.
- Certificate *revocation* is not expressible — there is no mechanism to distrust a key while keeping the peer. With two devices and a "forget peer" button, that is adequate.
- Because the certificate's validity window is not pinned, an attacker who obtains the private key is not locked out by certificate expiry. That is already true of any pinned-key scheme, and the threat model here (a passive or active attacker on the same Wi-Fi, no key extraction from Keystore/Keychain) does not cover key theft. Recorded rather than implied.
- Log output changes: `identity_spki_sha256` truncated to 6 hex is still enough to correlate two log lines and far too little to identify a device off the network.

## Alternatives considered

| Option | Rejected because |
|---|---|
| Pin the whole-certificate SHA-256 | Breaks trust on ordinary certificate re-issuance, and teaches the user to accept re-pair prompts — the exact reflex an attacker wants |
| Pin both, accept if either matches | The weaker check wins, so it is equivalent to pinning SPKI while carrying twice the state |
| Pin the raw public key bytes instead of the SPKI DER | SPKI includes the algorithm identifier, so the same raw bytes cannot be reinterpreted under a different algorithm. Cheap, standard, and one less thing to reason about |
| Keep the name `cert_fingerprint` and document that it means SPKI | A security field whose name contradicts its content. Rejected outright |
| Use a certificate subject / CN as identity | Attacker-chosen, unauthenticated, meaningless in a self-signed world |

## Amendment A1 — 19 October 2026 — *Forget peer* is a user-visible control, and it cannot be bypassed

**Found on two physical Android phones** (Phase 9B-A full qualification; STATUS §4 problem 126;
[evidence](../PHASE9BA_ANDROID_ANDROID_FULL_QUALIFICATION.md#13-defects-found-in-this-run)). The
decision above makes "forget the peer, pair again, fresh SAS on both screens" the **only** recovery
from a genuine key change or `pin_mismatch`, and this ADR's own consequences lean on "a 'forget peer'
button". Neither app had one. `SessionCoordinator.forgetPeer` existed on both platforms, and nothing in
production called it. The only recourse was clearing app data, which also destroys this phone's own
identity, and fresh-pairing qualification (TEST_PLAN I-02, I-16, I-20) could not run. Nothing in the
decision above changes. This amendment says how the forget is offered and what it must guarantee.

### Decision

1. **A deliberate, confirmed control on both platforms.** A *Paired phones* section lists every
   trusted record by display name and the pin redacted to 6 hex (ARCHITECTURE §11). *Forget* opens a
   confirmation that says the next connection will show a six-digit code to compare and confirm
   again, that the other phone should forget this one too, and that this phone's identity, music and
   settings are not changed.
2. **It removes one record and nothing else.** `TrustedPeerStore.forget(peer_id)` removes that
   `peer_id`'s pin. It never touches another peer's record, the Keystore/Keychain identity, the local
   `peer_id`, the library or settings; the store has no path to any of them. A removal that cannot be
   made durable **throws** and leaves the record trusted, in memory and on disk. iOS's `forget` used to
   be `try?`, so a failed write was silently reported as a forget; it now throws, like Android's.
3. **Only in `IDLE`** (`ForgetPeerPolicy`, mirrored on both platforms). The coordinator refuses the
   forget in every other status (`REFUSED_SESSION_ACTIVE`), and the UI disables the button and says
   why, using the same policy. Of the two designs the brief allowed, this one is chosen over "forgetting the
   connected peer ends the session" because:
   - a session has exactly **one** end path, ADR-026's `SessionCoordinator.retireSession` (CLAUDE.md
     rule 21). A forget that ended the session would be a second way in, and from `DISCOVERING`,
     `PAIRING` and `CONNECTING` there is no `UserEnded` edge to reuse, so the forget would have to
     invent one. The user already has *End session* and *Stop searching*;
   - `IDLE` is the only status in which no control plane belongs to the current session: nothing is
     listening, dialling, reconnecting, pairing or authenticated. `ENDING -> IDLE` is reached only
     after the teardown owner has awaited `ControlSessionManager.shutdown()`;
   - it also resets `connectAttempted`, so the next *Find other phone* re-dials and meets the code.
     A forget from `DISCOVERING` would leave that latch set, and nothing would re-dial;
   - forgetting a live session's peer is not a recovery scenario. `pin_mismatch` never
     authenticates, so a live session is by definition one whose pin matched.
4. **The trust gate re-asks the pin, serialised with the forget.** `PeerTrust.decide` runs inside
   the `HELLO` exchange, and the connection is promoted only after duplicate resolution's 300 ms grace
   period. A forget landing in between was silently overruled: the stale `Trusted` verdict passed
   the gate. Now `promote` re-reads the stored pin for a `Trusted` verdict (`PeerTrust.recheck`):
   - absent means `PairingRequired`, so PROTOCOL §4.5's code is shown, never a silent connect;
   - equal means `Trusted`;
   - different means `pin_mismatch`, refused exactly as the handshake would have refused it.

   The re-check and the trusted path up to `activateAuthenticatedSession` run without suspending.
   On Android they run under the monitor that `TrustRevocationGate.forget` takes. On iOS they run on
   the `ControlSessionManager` actor that `forgetTrustedPeer` is isolated to. So a forget lands either
   before the re-check, which sends the peer to the code, or after an activation. Point 3 makes the
   second case impossible in a live session. The tail of a retiring session (`CancelDiscovery` reaches
   `IDLE` before its teardown finishes) is closed by `shutdown()` before any successor starts, as
   rule 21 already requires.
5. **No other trust cache exists to go stale.** The pin is read from the store at both points. The
   UI's list is a projection refreshed after every coordinator write, and nothing reads trust from
   it. `SessionGate` decides from events, and `PeerTrusted` is emitted only after the re-check. Each
   step of the reconnect ladder is a fresh dial with a fresh handshake read.

### What it does not do

- **Forgetting is local.** If only this phone forgets, the other phone still holds a valid pin for
  this phone's unchanged key. It therefore passes its *own* trust gate silently, while this phone
  shows the six digits and, since nobody on the other side is ever asked to confirm, never
  authenticates. This fails closed, and no wire change is made to "tell" the other phone, which would
  need an authenticated message from a peer that is no longer trusted. The confirmation and the
  TEST_PLAN procedure say to forget on both phones.
- **It does not end an authenticated connection.** It cannot meet one, by point 3.
- **No wire change, and no vector moved.** `recheck` is the pin half of `decide`, already pinned by
  `vectors/identity/`. It is exhausted by unit tests on both platforms, including against `decide`
  itself.

### Verification

- `ForgetPeerTrustGateTest` (Android, `:network`) and `ForgetPeerTrustGateTests` (iOS,
  `RideLinkPlatform`) use real managers over real loopback TLS 1.3, with a store that runs the
  forget the instant the handshake's own lookup has returned the pin. In those tests:
  - the raced connection shows the code and pairs afresh only after both users confirm;
  - one-sided trust never authenticates the phone that forgot;
  - a pin replaced in the window is `pin_mismatch`.

  On both platforms, three of the four cases fail against the unamended `promote`. The fourth (both phones
  forgot before dialling) is the normal path and passes either way.
- `SessionCoordinatorForgetPeerTest` (Android, `:app`, real coordinator) proves four things:
  - only the selected record goes and the identity prefix is unchanged;
  - the forget is refused in `DISCOVERING`, `PAIRING`, `CONNECTED`, `RIDE_ACTIVE` and
    `RECONNECTING`, with nothing changed and nothing torn down;
  - *End session* then reaches `IDLE` through `TeardownComplete`, after which the forget succeeds;
  - a persistence failure reports `FAILED` and keeps the phone listed.

  The iOS app target has no test bundle, so its coordinator is covered through the shared
  `ForgetPeerPolicy` and `UiPresentation` tests only.
- **Physical:** pending. See TEST_PLAN's problem 126 section for the I-02/I-16 procedure.

## Amendment A2 — 21 October 2026 — a forget admitted in `IDLE` cannot act inside a newer session

**Found by independent review of PR #27** (the Amendment A1 change, before merge). Amendment A1
point 4 says a forget that lands after an activation is impossible "in a live session" by point 3.
On iOS that did not hold. `SessionCoordinator.forgetPeer` read `ForgetPeerPolicy` on the main actor
and then awaited `ControlSessionManager.forgetTrustedPeer`, which is a hop to another actor. The main
actor is free during that hop, so *Find other phone* can start a session in it. That session can
dial, read the pin, pass the trust gate and authenticate before the forget's write is scheduled, and
the write then removed a **live** session's pin. A scratch reproduction against the unamended
PR #27 head (`bcc2d75`) showed exactly that: status `CONNECTED`, pin gone. Android was not affected.
Its `forgetPeer` reads the policy and writes under `TrustRevocationGate`'s monitor in one
non-suspending call, and both it and *Find other phone* are UI-thread click handlers.

### Decision

1. **A forget is admitted by one idle period, and a session start spends it.** iOS's
   `ForgetPeerFence` counts session starts. `forgetPeer` takes a `ForgetPeerAdmission` in the same
   synchronous step as its policy read. `beginDiscoverySession` calls `sessionBegan()` in the same
   synchronous step as `IDLE -> DISCOVERING`, before any of the new session's work.
2. **The check and the write are atomic.** `ControlSessionManager.forgetTrustedPeer` now requires the
   admission and runs the store write under the fence's lock only if no session has begun since. It
   returns false otherwise, and the coordinator reports `refusedSessionActive` with nothing changed.
   A `sessionBegan()` that arrives while a write is in progress waits for it, so that write is
   ordered before the session and the session's handshake reads a store without the pin. There is no
   third order.
3. **Nothing else moves.** A refused forget ends nothing, so `retireSession` stays the one teardown
   owner (ADR-026, CLAUDE.md rule 21). The forget is still offered only in `IDLE`. The A1 re-check in
   `promote` stays as the defence for a handshake already in flight. No wire change, and no vector
   moved.
4. **Android is not mirrored, deliberately.** It has no suspension to fence. A test holds
   `forgetPeer` to a non-suspending function type, so making it `suspend` fails to compile there
   first.

The lock is held across one atomic file replace of the trusted-peer list. `sessionBegan()` can wait
on it only when a session is started during the write itself. An actor or an `await` there would
re-open the window the fence closes, because the start must be synchronous with the transition.

### Recovery from `pin_mismatch` through the available UI

The review also asked for a test of recovery from `pin_mismatch` through the controls the app actually
offers. The session button's mapping moved from `MainScreen` (untestable, since the app target has no
test bundle) to `UiPresentation.sessionAction` (unchanged), so the test drives the production mapping.
The recovery is:
1. A refused handshake leaves the phone in `DISCOVERING`. `SessionGate` turns `.handshakeRefused`
   into `pairingRejectedOrTimeout` from `PAIRING`, and into nothing from `DISCOVERING`.
2. *Stop searching* is the button offered there, and *Forget* is disabled with its reason.
3. *Stop searching* reaches `IDLE`, where *Forget* is enabled and succeeds.
4. *Find other phone* then meets the new key as a first meeting, confirmed on both screens.

### Verification

- `ForgetPeerSessionRaceTests` (iOS, `RideLinkPlatform`), with real managers over real loopback
  TLS 1.3, and the coordinator's statements performed in the coordinator's own order:
  - **the review's race:** forget admitted in `.idle`, then a session started and authenticated
    silently on the pin, then the write. The write is refused, the pin is intact, and the session
    stays `CONNECTED`;
  - **the legitimate order:** written before the session began, the session shows the code and
    never connects silently;
  - **at the lock:** a session start issued while a write is in progress waits for it (absence
    proved over 300 ms), then every earlier admission is spent;
  - the fence admits only in `.idle`;
  - **`pin_mismatch` recovery end to end:** a reinstalled phone B (same `peer_id`, new key) is
    refused. *Stop searching* reaches `.idle`, *Forget* succeeds, and *Find other phone* pairs the
    new key only after both users confirm.
- `SessionCoordinatorForgetPeerTest` (Android, real coordinator):
  - the same `pin_mismatch` recovery through `sessionAction`, `cancelDiscovery` and `forgetPeer`;
  - the non-suspension pin.
- **Not covered:**
  - the iOS app-target coordinator still has no test bundle. Its two statements (`admit` beside the
    status read, and `sessionBegan()` beside the transition) are performed by the test in the same
    order, not executed from the coordinator itself.
  - **Physical:** pending.
