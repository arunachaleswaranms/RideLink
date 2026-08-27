# Phase 1b security spike — TLS exporter and self-signed X.509 interoperability

Throwaway experiment, kept because it is re-runnable evidence rather than a claim. **Nothing here
is production code and nothing here is compiled into either app.**

## What it answers

[ADR-007 Amendment A1](../../../docs/DECISIONS/ADR-007-control-channel-over-tcp-tls.md#amendment-a1--26-august-2026--secure-transport-contingency)
says two capabilities must be demonstrated before the rest of Phase 1b depends on them, and that
either one failing triggers a design review rather than a weaker substitute:

1. **Certificate generation** — a self-signed X.509 built from a platform-held keypair, a TLS 1.3 handshake completing between an Apple and an Android endpoint, and the same SPKI hash computed on both sides.
2. **Exporter availability** — a TLS keying-material exporter reachable from *public* API on both platforms, producing identical output for the same handshake, matching [PROTOCOL §4.5.1](../../../docs/PROTOCOL.md#451-the-six-digit-sas--exact-construction).

Results: [`docs/test-results/phase1b-security-spike-20260827.md`](../../../docs/test-results/phase1b-security-spike-20260827.md).

## Run it

```sh
./run.sh
```

Requires JDK 21 (`/opt/homebrew/opt/openjdk@21`, override with `JAVA_HOME_21`), Homebrew
`openssl@3` (override with `OPENSSL`), and Xcode. It fetches Conscrypt once from Maven Central.

Every private key it uses is a throwaway generated into a `mktemp -d` that is deleted on exit. No
key, certificate or PKCS#12 file is ever written into the repository, and none is committed.

## The four experiments

| | Endpoints | Settles |
|---|---|---|
| E1 | Conscrypt ↔ Conscrypt | Is the exporter reachable at all? Under TLS 1.3, is a `null` context the same as a zero-length one? |
| E2 | Apple ↔ Apple | Does a hand-encoded DER certificate survive `SecCertificateCreateWithData` → `SecIdentityCreate` → `NWListener` TLS 1.3? |
| E3 | **Apple ↔ Conscrypt** | **The one that matters.** Same handshake, both stacks export — identical bytes? Do the SPKI pins cross-match? |
| E4 | Apple ↔ OpenSSL | A third, unrelated implementation, so E3 cannot be a two-stack coincidence |

## The substitution, stated up front

The Apple side is **real**: Apple's shipping `Network.framework` and `Security.framework`, the same
functions iOS runs. The Android side is **not** a phone — it is Conscrypt-over-BoringSSL on the JVM,
which is the TLS implementation Android ships and the same entry point that the public
`android.net.ssl.SSLSockets.exportKeyingMaterial` (API 31+) delegates to. That is a strong proxy and
it is not the device. Android Keystore, the iOS Keychain, and two real radios are all untouched
here; they are the Phase 1b real-device gate, not this harness.

## Files

| File | |
|---|---|
| `run.sh` | Orchestrates all four experiments and prints a PASS/FAIL verdict block |
| `AppleExporterSpike.swift` | Apple endpoint: minimal DER X.509 encoder, `SecKey` signing, `SecIdentityCreate`, `NWListener`/`NWConnection` TLS 1.3, `sec_protocol_metadata_create_secret` |
| `ConscryptExporterSpike.java` | Android-stand-in endpoint: Conscrypt TLS 1.3, `Conscrypt.exportKeyingMaterial`, X.509 checks on the peer certificate |

The DER encoder in `AppleExporterSpike.swift` is the *prototype* of the production one. The shipped
version lives in `RideLinkCore.Security` / `core.security`, is mirrored on both platforms, and is
pinned by `protocol/vectors/identity/`.
