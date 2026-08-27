# ADR-018 — The SAS channel binding is a TLS 1.3 exporter with an empty context

**Status:** Accepted · 27 Aug 2026
**Evidence:** [`docs/test-results/phase1b-security-spike-20260827.md`](../test-results/phase1b-security-spike-20260827.md)
**Amends the wording of:** [PROTOCOL §4.5.1](../PROTOCOL.md#451-the-six-digit-sas--exact-construction). The algorithm and the golden vectors are unchanged.

## Context

The six-digit pairing code is what makes RideLink's first-meeting confirmation a real check rather
than theatre: it is derived from the TLS keying-material exporter, so it is bound to that specific
handshake and to both certificates, and a man-in-the-middle terminating two distinct TLS sessions
cannot make the two screens agree.

Two things about that were unverified, and both were tracked as high-severity Phase 1b spikes
([ADR-007 Amendment A1](ADR-007-control-channel-over-tcp-tls.md#amendment-a1--26-august-2026--secure-transport-contingency),
[ARCHITECTURE §12](../ARCHITECTURE.md#12-known-architectural-risks),
[STATUS §4](../STATUS.md#4-known-problems) problems 2 and 9):

1. whether a keying-material exporter is reachable from **public** API on both platforms at all;
2. whether the two platforms produce **identical bytes** for the same connection.

PROTOCOL §4.5.1 additionally specified the exporter input as:

> `context = zero-length, but PRESENT` *(an empty context, not an absent one)*

That sentence is inherited from RFC 5705, where a *present but empty* context and an *absent*
context are genuinely different inputs and produce different output. It was written before anyone
had looked at what the two platforms' APIs can actually express.

## Decision

### 1. Both platforms have a public exporter. The spike is passed.

| Platform | Call | Availability |
|---|---|---|
| Android | `android.net.ssl.SSLSockets.exportKeyingMaterial(socket, label, context, length)` | **API 31** — exactly the ADR-011 `minSdk`, verified against `api-versions.xml`, not assumed |
| Apple | `sec_protocol_metadata_create_secret(metadata, label_len, label, exporter_length)` | iOS 12.0 — far below the ADR-011 iOS 26.0 baseline |

Measured on one TLS 1.3 connection between Apple's `Network.framework` and Conscrypt-over-BoringSSL:
**byte-identical output.** A third implementation (OpenSSL 3.6.3) produces the same bytes for its
own handshake with the Apple endpoint. `STATUS` problem 9's assumption is now a measurement.

### 2. The context is specified as TLS 1.3's empty context value, not as "present but zero-length"

`sec_protocol_metadata_create_secret_with_context(…, context_len: 0, …)` **returns nil.** Apple's
public API has no way to pass a present-but-empty context — tested with a valid non-nil pointer and
a length of zero, specifically so the nil could not be blamed on a bad pointer.

Under TLS 1.3 this costs nothing, because the distinction does not exist there. RFC 8446 §7.5
defines the exporter as

```
TLS-Exporter(label, context_value, key_length) =
    HKDF-Expand-Label(Derive-Secret(Secret, label, ""),
                      "exporter", Hash(context_value), key_length)
```

— the context value is *always* hashed, so an absent context and an empty one are the same input.
The spike measures exactly that on the one stack that can express both: Conscrypt with `null` and
Conscrypt with `new byte[0]` produce identical bytes, and a non-empty context produces different
bytes (so the parameter is not simply being ignored).

**The specification therefore reads:**

```
label   = "EXPORTER-RideLink-SAS-v1"      ASCII, 24 bytes, no trailing NUL
context = the TLS 1.3 empty context value
length  = 32

S = TLS-Exporter(label, context, length)
```

with each platform's concrete call named, so no implementer has to infer it:

| Platform | Call |
|---|---|
| Android | `SSLSockets.exportKeyingMaterial(socket, "EXPORTER-RideLink-SAS-v1", new byte[0], 32)` |
| Apple | `sec_protocol_metadata_create_secret(metadata, 24, "EXPORTER-RideLink-SAS-v1", 32)` |

Android passes `new byte[0]` rather than `null` because it reads as the deliberate choice it is.
The two are measured to be equivalent under TLS 1.3, and RideLink is TLS 1.3 only, so nothing rests
on which one is written — but a reader should not have to know that to trust the line.

**Steps 2–5 of PROTOCOL §4.5.1 are untouched**: first 4 bytes, big-endian `uint32`, `mod 1000000`,
left-pad to exactly 6 digits. `protocol/vectors/sas/` starts from exporter *output* and is
therefore unaffected — all twelve vectors keep passing unchanged, which is the point of having
specified them that way.

### 3. Consequences for "TLS 1.3 only, no silent fallback"

The spike found that Conscrypt's **server-side** `SSLSession.getProtocol()` reports `TLSv1.2` on a
connection that is unambiguously TLS 1.3 (only TLS 1.3 enabled; a TLS-1.3-only cipher suite
negotiated; the client side reporting `TLSv1.3` correctly). Re-reading later does not fix it.

So the no-fallback requirement is **not** enforced by asserting on `getProtocol()` — that assertion
would reject good connections. It is enforced structurally instead:

1. `setEnabledProtocols(["TLSv1.3"])` / `set_min_tls_protocol_version(.TLSv13)` + `set_max_tls_protocol_version(.TLSv13)` on **both** ends, so nothing else can be negotiated; and
2. a post-handshake assertion that the negotiated **cipher suite** is one of the three TLS 1.3 suites.

This is correct whether or not Android's bundled Conscrypt shares the reporting bug, which is why
it was chosen over waiting for a device to tell us.

## Consequences

- The channel binding survives intact. No HKDF layer was added, no peer identifiers or certificate hashes were concatenated in, and nothing weaker was substituted — the outcomes ADR-007 Amendment A1 explicitly forbids as responses to a failed spike, none of which are needed.
- The exporter is reachable at exactly `minSdk 31`. Had it been API 34, the response would have been to raise `minSdk` (both devices are far above it); it is not, so ADR-011 is untouched.
- One protocol sentence changes and no protocol *bytes* change. The vectors' independence from the TLS layer is what made that possible, and is worth preserving in future protocol work.
- Bytes 4…31 of the 32-byte export remain unused and reserved, as PROTOCOL §4.5.1 already says. The fixed 32-byte length is kept so the exporter call is identical everywhere.
- **Still unproven:** that Android's *device* Conscrypt behaves like Conscrypt-on-JVM. The substitution is stated in the results file and closed by integration test I-02 on the two real phones, not by this ADR.

## Alternatives considered

| Option | Rejected because |
|---|---|
| Keep "zero-length but PRESENT" and have iOS pass an empty context | Measured: Apple's API returns nil for a zero-length context. The words would describe something no iOS build can do |
| Use a non-empty context (e.g. both `peer_id`s, or both certificate hashes) | Buys nothing — TLS 1.3 already binds the exporter to the full handshake including both certificates — and adds an ordering/serialisation question for the two implementations to disagree about. PROTOCOL §4.5.1 already rejects this reasoning for the same reason it rejects a second HKDF |
| Hash the exporter output again before reducing to six digits | Pure addition of a second place to differ. Explicitly rejected by PROTOCOL §4.5.1 |
| Fall back to a non-exporter channel binding | Not needed: the spike passed. Had it failed, ADR-007 Amendment A1 requires a design review, not a substitution |
| Assert `SSLSession.getProtocol() == "TLSv1.3"` after the handshake | Measured false on Conscrypt's server side for a genuine TLS 1.3 connection. Would reject good sessions |
