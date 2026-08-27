# Phase 1b security spike — self-signed X.509 and TLS exporter interoperability

**Date:** 27 August 2026
**Harness:** [`tools/spikes/phase1b-tls-exporter/run.sh`](../../tools/spikes/phase1b-tls-exporter/) — re-runnable, self-contained, deletes every key it creates
**Purpose:** answer the two questions [ADR-007 Amendment A1](../DECISIONS/ADR-007-control-channel-over-tcp-tls.md#amendment-a1--26-august-2026--secure-transport-contingency)
requires be answered *before* the rest of Phase 1b is built on them, and which
[STATUS §4](../STATUS.md#4-known-problems) problems 1, 2 and 9 record as unverified assumptions.

> **Verdict: both spikes pass. No design review is triggered.** The channel binding in
> [PROTOCOL §4.5.1](../PROTOCOL.md#451-the-six-digit-sas--exact-construction) is implementable
> from public API on both platforms, and the two stacks produce byte-identical exporter output
> for the same TLS 1.3 connection.
>
> **One protocol wording correction is required** (§4 below): Apple's public API cannot express a
> "zero-length but present" exporter context. Under TLS 1.3 that distinction does not exist, which
> the measurements here confirm — but the PROTOCOL text as written names a parameter no Apple
> caller can supply. Recorded in [ADR-017](../DECISIONS/ADR-017-identity-key-and-certificate.md)
> and [ADR-018](../DECISIONS/ADR-018-tls-exporter-channel-binding.md).

## 1. What was and was not run

| | |
|---|---|
| **Ran on** | This development laptop (macOS 26.5, Apple silicon). Everything below is a laptop measurement |
| **Apple side** | **Real.** Apple's shipping `Network.framework` + `Security.framework`, iOS SDK 27.0 headers, built with Swift 6.4. The same frameworks and the same functions the iPhone runs |
| **Android side** | **A stand-in, not the phone.** Conscrypt 2.6.3 over BoringSSL on the JVM. See §5 for exactly what that does and does not prove |
| **Not run** | Anything on the OnePlus Nord 5, anything on a physical iPhone, anything on the iOS Simulator. The Phase 1a real-device gate is still open and this spike does not touch it |

## 2. API availability — checked against the installed SDKs, not from memory

| Question | Answer | Evidence |
|---|---|---|
| Public TLS exporter on Android? | **Yes.** `android.net.ssl.SSLSockets.exportKeyingMaterial(SSLSocket, String label, byte[] context, int length)` | `android.jar` API 36 + `data/api-versions.xml` |
| From which API level? | **31** — exactly the ADR-011 `minSdk`. `SSLEngines.exportKeyingMaterial` is also 31 | `api-versions.xml`: `since="31"` on the method (the *class* `android.net.ssl.SSLSockets` is since 29) |
| Public TLS exporter on Apple? | **Yes.** `sec_protocol_metadata_create_secret` and `sec_protocol_metadata_create_secret_with_context` | `Security.framework/Headers/SecProtocolMetadata.h`, `API_AVAILABLE(macos(10.14), ios(12.0))` |
| Can iOS build an X.509 certificate? | **No first-party API** — confirmed. `SecCertificateCreateWithData` only *parses* DER | `SecCertificate.h` |
| Can iOS turn a cert + Keychain key into a TLS identity without PKCS#12? | **Yes.** `SecIdentityCreate(allocator, cert, privateKey)`, `API_AVAILABLE(ios(11.2))`. (`SecIdentityCreateWithCertificate` is macOS-only — `SEC_OS_OSX` / `__IPHONE_NA` — and is *not* the one to use) | `SecIdentity.h` |
| Can Android build an X.509 certificate? | **Yes**, two ways: `KeyGenParameterSpec.Builder.setCertificate{Subject,SerialNumber,NotBefore,NotAfter}` auto-issues one at key-generation time, or the app encodes its own | `KeyGenParameterSpec$Builder`, API 23 |

**STATUS problem 9 is closed:** "`minSdk 31` is assumed to be the level where a public TLS exporter
is available" was an assumption. It is now measured, and it is correct. `minSdk` does not move.

## 3. Measurements

Four experiments. Numbers below are from the run logged on 27 Aug 2026; the exporter values differ
on every run (fresh handshake, fresh keys) — what matters is which values are *equal to each other*
within a run.

### E1 — Conscrypt ↔ Conscrypt (both ends the Android stack)

```
JVM_CLIENT_EXPORTER_NULLCTX  = 56bd8ef9e323ec90f9f8b4f733705e6e8d88c56e31dfa6d63bb5b9d882e0a9bc
JVM_SERVER_EXPORTER_NULLCTX  = 56bd8ef9e323ec90f9f8b4f733705e6e8d88c56e31dfa6d63bb5b9d882e0a9bc
JVM_CLIENT_EXPORTER_EMPTYCTX = 56bd8ef9e323ec90f9f8b4f733705e6e8d88c56e31dfa6d63bb5b9d882e0a9bc
JVM_SERVER_EXPORTER_EMPTYCTX = 56bd8ef9e323ec90f9f8b4f733705e6e8d88c56e31dfa6d63bb5b9d882e0a9bc
JVM_SERVER_EXPORTER_CTX010203= ceb8b3ffe9d382ab84aea0304a9e36efb1ca30df85217d68147bdb32687693ee
```

- Both endpoints agree. ✅
- **`null` context and zero-length context produce identical bytes under TLS 1.3.** ✅ This is RFC 8446 §7.5 behaviour (TLS 1.3 always hashes a context value, empty or not, so "absent" collapses into "empty"); under RFC 5705 / TLS 1.2 the two differ. Measured, not assumed.
- A genuinely non-empty context *does* change the output, so the parameter is not being ignored. ✅

### E2 — Apple ↔ Apple, `Network.framework`, mutual TLS 1.3

```
APPLE_SERVER_CERT_DER_BYTES        = 341        (built by the hand-written DER encoder)
APPLE_SERVER_CERT_PARSED_BY_APPLE  = true       (SecCertificateCreateWithData accepted it)
APPLE_SERVER_CERT_SUBJECT_SUMMARY  = RideLink Device
RESULT_APPLE_TLS13                 = true
RESULT_APPLE_EXPORTERS_AGREE       = true
RESULT_APPLE_EMPTYCTX_VARIANT_USABLE = false    ← see §4
RESULT_APPLE_SPKI_PIN_OBSERVED     = true
```

This is the whole iOS identity chain working end to end with **no PKCS#12, no key export, no
private API**: `SecKeyCreateRandomKey` → `SecKeyCreateSignature` over our TBSCertificate →
`SecCertificateCreateWithData` → `SecIdentityCreate` → `sec_identity_create` → `NWListener`
/`NWConnection` TLS 1.3 → `sec_protocol_metadata_create_secret`.

### E3 — Apple client ↔ Conscrypt server — **the cross-stack experiment**

One TLS 1.3 connection, two independent implementations, both exporting under PROTOCOL §4.5.1:

```
APPLE_CLIENT_EXPORTER_NOCTX   = aa46b048673eecd2783d4cfa8435a04cdd051683acbd8a0a1771bea39ce11e53
JVM_SERVER_EXPORTER_NULLCTX   = aa46b048673eecd2783d4cfa8435a04cdd051683acbd8a0a1771bea39ce11e53
JVM_SERVER_EXPORTER_EMPTYCTX  = aa46b048673eecd2783d4cfa8435a04cdd051683acbd8a0a1771bea39ce11e53
APPLE_CLIENT_NEGOTIATED       = TLSv1.3
```

**Byte-identical.** This is the evidence ADR-007 Amendment A1 asked for.

The same run also proves the certificate and pin path across the two stacks:

```
JVM_SERVER_PEER_CERT_DER_BYTES   = 341
JVM_SERVER_PEER_CERT_SUBJECT     = CN=RideLink Device
JVM_SERVER_PEER_CERT_SIGALG      = SHA256withECDSA
JVM_SERVER_PEER_SELFSIG_VERIFIES = true      ← our DER, verified by an independent X.509 parser
JVM_SERVER_PEER_VALIDITY_OK      = true
APPLE_CLIENT_OWN_SPKI_SHA256  == JVM_SERVER_PEER_SPKI_SHA256      ✅
APPLE_CLIENT_PEER_SPKI_SHA256 == JVM_SERVER_OWN_SPKI_SHA256       ✅
```

The SPKI hash the Apple side computes by rebuilding SubjectPublicKeyInfo from the peer's public key
equals the one the JVM computes from `PublicKey.getEncoded()`, in both directions. The pin is
interoperable.

Applying PROTOCOL §4.5.1 to that exporter output: `n = 2856759368`, `sas6 = 759368`.

### E4 — Apple client ↔ OpenSSL 3.6.3 server (a third, unrelated stack)

```
OpenSSL "Keying material": DB9DD74FEDA96A2506F44FB39BD629EB238EF07A24E3DAE01918655393538622
APPLE_CLIENT_EXPORTER_NOCTX = db9dd74feda96a2506f44fb39bd629eb238ef07a24e3dae01918655393538622
```

Identical. Three independent TLS 1.3 implementations agree, so the construction is standard
behaviour rather than a two-stack coincidence. (`openssl s_server -keymatexport` uses
`use_context = 0`, i.e. the context-less form.)

### Verdict block, verbatim

```
PASS  E1 Conscrypt exporter reachable from public-equivalent API
PASS  E1 both Conscrypt endpoints agree
PASS  E1 TLS 1.3: null context == zero-length context
PASS  E1 a non-empty context does change the output
PASS  E3 CROSS-STACK Apple exporter == Conscrypt exporter, same handshake
PASS  E3 Apple negotiated TLS 1.3
PASS  E3 SPKI pin values cross-match in both directions
PASS  E3 RideLink's own DER certificate: self-signature verifies under an independent X.509 parser
PASS  E3 that certificate is within its validity window
PASS  E4 third stack (OpenSSL) agrees with Apple on the same handshake
```

## 4. Findings that change what gets built

### 4.1 Apple cannot supply a zero-length exporter context

`sec_protocol_metadata_create_secret_with_context(metadata, …, context_len: 0, …)` returns
**nil**. Tested with a valid (non-nil) pointer and a length of 0, precisely so that a nil return
could not be blamed on a bad pointer — the first attempt passed a dummy pointer for an empty Swift
array, and that ambiguity was removed before the result was believed.

PROTOCOL §4.5.1 currently says `context = zero-length, but PRESENT (an empty context, not an
absent one)`. That wording comes from RFC 5705, where the distinction is real. **RideLink is
TLS 1.3 only, where it is not** — E1 measures `null == empty` on the one stack that can express
both. So the requirement is satisfiable, but the sentence describes a parameter Apple's API has no
way to accept, and an implementer following it literally on iOS would conclude the protocol is
unimplementable.

**Resolution:** the exporter *input* is unchanged and no vector changes. PROTOCOL §4.5.1 is
reworded to specify the TLS 1.3 exporter with an empty context value, and to state that
"context-less" and "zero-length context" are the same call under TLS 1.3 — with each platform's
concrete call named. See [ADR-018](../DECISIONS/ADR-018-tls-exporter-channel-binding.md).
`protocol/vectors/sas/` is untouched: it starts from exporter *output*, which does not change.

### 4.2 Conscrypt's server-side `SSLSession.getProtocol()` misreports TLS 1.3 as TLS 1.2

Reproduced in every run, on the **server** socket only:

```
JVM_SERVER_ENABLED             = TLSv1.3       (nothing else was ever enabled)
JVM_SERVER_SESSION_GETPROTOCOL = TLSv1.2       ← wrong
JVM_SERVER_CIPHER              = TLS_AES_128_GCM_SHA256   (a TLS-1.3-only suite)
JVM_CLIENT_SESSION_GETPROTOCOL = TLSv1.3       (client side is correct)
```

Re-reading it after a delay does not fix it, and `getHandshakeSession()` is null by then.

**Consequence:** the "TLS 1.3 only, never a silent fallback" check must **not** be implemented as
`session.getProtocol() == "TLSv1.3"` — that assertion would fail on a connection that is in fact
TLS 1.3. RideLink enforces the requirement structurally instead: `setEnabledProtocols(["TLSv1.3"])`
on both ends makes anything else impossible to negotiate, and the post-handshake assertion checks
the **negotiated cipher suite** is one of the three TLS 1.3 suites. Observed in Conscrypt 2.6.3 on
the JVM; whether Android's bundled Conscrypt shares the bug is **unverified** — but the check
above is correct either way, which is why it was chosen.

### 4.3 The Android certificate is built by RideLink, not by `KeyGenParameterSpec`

`KeyGenParameterSpec` can auto-issue a self-signed certificate, and that was the expected Android
path. It is **not** the path taken, for a reason that only became clear here: it issues the
certificate *at key-generation time*, and there is no API to issue a **new** certificate around an
**existing** Keystore key. ADR-012's entire re-issuance model — "certificate regenerated around the
same identity keypair ⇒ still trusted" — is therefore not expressible through it. See
[ADR-017](../DECISIONS/ADR-017-identity-key-and-certificate.md).

### 4.4 ARCHITECTURE §12's severity estimate for iOS X.509 was accurate

"~150 lines, well-trodden" — the spike encoder is about that size, and Apple's own parser, BoringSSL
and OpenSSL all accept its output on the first serious attempt. The risk was real (there is no
API) but the mitigation was correctly sized.

## 5. What this does **not** prove

Stated plainly, because the substitution in §1 is the load-bearing caveat.

1. **Conscrypt-on-JVM is not the OnePlus Nord 5.** It is the same TLS implementation Android ships and the same entry point `android.net.ssl.SSLSockets.exportKeyingMaterial` delegates to, but it is a different build, a different version, and not the device. **The device must confirm it.**
2. **Nothing here used Android Keystore.** Hardware-backed key generation, `Signature` over a non-exportable Keystore key, and whether a `KeyManager` can present a Keystore-resident key to Conscrypt are all **unverified**.
3. **Nothing here used the iOS Keychain.** The spike keys are `kSecAttrIsPermanent: false` transients, because an unsigned command-line tool has no keychain entitlement. Persistence, survival across restart/upgrade, and `SecIdentityCreate` with a *Keychain-resident* key are **unverified** on device.
4. **No two-radio test.** Both endpoints were on loopback.
5. **The SAS was never shown to a human.** The pairing flow, its rate limit and its UI are not in scope here.

These become integration tests I-02, I-03, I-04, I-16, I-19, I-20 and I-21 in
[`TEST_PLAN §5`](../TEST_PLAN.md#5-l4--two-device-integration-the-two-real-phones), and they gate
Phase 1b — not this document.

## 6. Reproducing

```sh
./tools/spikes/phase1b-tls-exporter/run.sh
```

Needs JDK 21, Homebrew `openssl@3`, Xcode, and one Maven Central fetch for Conscrypt (test-only,
never shipped, never added to either app's dependency graph). Every key it generates is a throwaway
in a `mktemp -d` that is removed on exit. Exporter values change per run; the equalities do not.
