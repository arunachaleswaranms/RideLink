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
