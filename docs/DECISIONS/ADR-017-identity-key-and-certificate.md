# ADR-017 — Identity key algorithm, and a shared certificate encoder on both platforms

**Status:** Accepted · 27 Aug 2026
**Evidence:** [`docs/test-results/phase1b-security-spike-20260827.md`](../test-results/phase1b-security-spike-20260827.md)

## Context

[ADR-012](ADR-012-spki-peer-identity.md) fixes *what* is pinned — `identity_spki_sha256`, the
SHA-256 of the DER SubjectPublicKeyInfo of a long-term identity key. It does not say what kind of
key that is, how the certificate wrapping it is produced, or what goes in it. Phase 1b has to
answer all three, on two platforms, without inventing cryptography.

[ADR-007 Amendment A1](ADR-007-control-channel-over-tcp-tls.md#amendment-a1--26-august-2026--secure-transport-contingency)
flagged iOS certificate generation as the highest-risk item in the phase — `SecKey` can create and
store keys but there is no first-party API that *builds* a certificate — and required a spike
before anything was built on it. That spike has now run.

## Decision

### 1. One key algorithm, chosen once, for both platforms

| | |
|---|---|
| Key algorithm | **ECDSA on NIST P-256** (`secp256r1` / `prime256v1`) |
| Signature algorithm | **`ecdsa-with-SHA256`**, OID `1.2.840.10045.4.3.2` |
| SPKI encoding | `AlgorithmIdentifier { id-ecPublicKey, prime256v1 }` + uncompressed X9.63 point — **91 bytes, canonical, byte-identical on both platforms** |
| Android | `KeyPairGenerator("EC", "AndroidKeyStore")`, `KeyGenParameterSpec` with `PURPOSE_SIGN`, `DIGEST_SHA256`, `ECGenParameterSpec("secp256r1")` |
| iOS | `SecKeyCreateRandomKey` with `kSecAttrKeyTypeECSECPrimeRandom` / 256 bits, permanent in the Keychain; signing via `SecKeyCreateSignature(.ecdsaSignatureMessageX962SHA256)` |

Not chosen per platform, and not negotiable on the wire: a peer presenting anything other than a
P-256 key is rejected. That is deliberate — it removes algorithm confusion as a class of bug, and
with exactly two devices there is nothing to be gained by flexibility.

RSA was the alternative. Rejected: larger keys, slower on-device signing, and a padding choice
(PKCS#1 v1.5 vs PSS) that has to agree across two stacks — three more ways to differ, for no
benefit. P-256 with SHA-256 is what TLS 1.3 implementations support universally, and the spike
confirms Apple, BoringSSL and OpenSSL all handle it without special-casing.

### 2. Certificate content — deliberately, boringly minimal

| Field | Value |
|---|---|
| Version | v3 |
| Serial | **16 CSPRNG bytes**, high bit cleared so it encodes as a positive INTEGER |
| Signature algorithm | `ecdsa-with-SHA256` (in both the TBS and the outer structure, as RFC 5280 requires) |
| Issuer, Subject | `CN=RideLink Device` — **identical on every device that will ever run this** |
| Validity | `notBefore` = issue time − 24 h, `notAfter` = `notBefore` + 10 years (ADR-012's window, so expiry never masquerades as key rotation) |
| SubjectPublicKeyInfo | the P-256 identity key |
| Extensions | `basicConstraints` (critical, `CA:FALSE`) and `keyUsage` (critical, `digitalSignature`). Nothing else |

**Nothing device-identifying is in the certificate.** No device name, user name, email, hardware
serial, advertising identifier, library information, or `subjectAltName`. The subject is a constant
string, which is the point: identity is the SPKI hash (ADR-012), never the subject text, so the
subject carries no information and therefore leaks none. A certificate is presented in the clear
during the TLS handshake to anyone who can reach the port; treating it as public is the only safe
assumption.

The 24-hour `notBefore` backdate absorbs modest clock skew between two phones so a freshly issued
certificate is not immediately `certificate_invalid` on the peer. This is one of the few places
RideLink uses wall-clock time at all — X.509 validity is *defined* in wall-clock terms by RFC 5280,
so CLAUDE.md's monotonic-clocks rule (which is about scheduling) does not apply and cannot.

### 3. Both platforms build the certificate with the same encoder

A small DER encoder in the pure domain layer — `core.security` on Android, `RideLinkCore.Security`
on iOS — produces the TBSCertificate and assembles the certificate. **Signing is always platform
crypto**: `Signature.getInstance("SHA256withECDSA")` over the Android Keystore key, or
`SecKeyCreateSignature` over the Keychain key. The private key never leaves its store, is never
serialised, and has no code path to a file or a log.

The encoder is **not** a general-purpose ASN.1 framework. It encodes exactly the tags this one
certificate needs, with definite minimal-form lengths and no parser at all, and every structure it
emits is pinned by `protocol/vectors/identity/` on both platforms.

**On iOS there was no alternative** — that was already known. **On Android there was**, and it was
the expected choice: `KeyGenParameterSpec.Builder.setCertificate{Subject,SerialNumber,NotBefore,NotAfter}`
makes the platform auto-issue a self-signed certificate at key-generation time, with no encoder at
all. It is rejected for a reason that only surfaced during the spike:

> The Keystore issues that certificate **when the key is created**, and offers no API to issue a
> *new* certificate around an *existing* Keystore key.

ADR-012's central behaviour — "certificate regenerated around the same identity keypair ⇒ SPKI
unchanged ⇒ still trusted, silent connect" — is then unreachable on Android without regenerating
the key, which changes the SPKI, which is precisely the event ADR-012 says must *never* happen
silently. Re-issuance is not a hypothetical: the 10-year window eventually closes, and a device
whose certificate has expired must be able to mint a new one without becoming a stranger to its
peer.

Two smaller reasons, neither decisive alone: the platform certificate's exact encoding is outside
our control and cannot be pinned by a shared vector; and it cannot be exercised anywhere except on
a physical device, whereas the shared encoder is tested on a laptop against three independent X.509
implementations today.

### 4. Peer identity extraction

| | |
|---|---|
| Android | `X509Certificate.getPublicKey().getEncoded()` **is** the DER SubjectPublicKeyInfo. Hash it directly |
| iOS | `SecCertificateCopyKey` → `SecKeyCopyExternalRepresentation` gives the **raw** X9.63 point, not an SPKI. Rebuild the 91-byte SPKI from the point using the fixed P-256 template, then hash |

The asymmetry is Apple's, not ours. It is contained by rejecting any peer key that is not P-256
(checked via `SecKeyCopyAttributes` before the template is applied) and by a shared vector that
asserts both platforms derive the same `identity_spki_sha256` from the same key.

### 5. The identity as a TLS credential

| | |
|---|---|
| Android | Keystore `PrivateKeyEntry` presented through a `KeyManager`; peer certificates accepted at the TLS layer by a trust manager that defers, because trust is the SPKI pin applied above it |
| iOS | `SecIdentityCreate(nil, certificate, privateKey)` → `sec_identity_create` → `sec_protocol_options_set_local_identity`. **No PKCS#12, no key export** |

`SecIdentityCreateWithCertificate` — the function most iOS examples reach for — is macOS-only
(`SEC_OS_OSX`, `__IPHONE_NA`) and is the wrong one. `SecIdentityCreate` is available from iOS 11.2
and takes the key directly.

Both ends require client authentication (`setNeedClientAuth(true)` /
`sec_protocol_options_set_peer_authentication_required(true)`), so both peers prove possession of
the key behind their pin. A one-sided handshake would leave the connecting side's identity
unbound and the pin check on that side meaningless.

## Consequences

- The two spike risks in [ARCHITECTURE §12](../ARCHITECTURE.md#12-known-architectural-risks) are retired with measurements rather than argument. ADR-007 Amendment A1's design-review trigger does not fire.
- More Android code than the platform strictly requires, in exchange for ADR-012's re-issuance model actually working there, and for the encoder being covered by laptop tests on both platforms instead of device tests on one.
- The DER encoder is security-adjacent code we own. It is deliberately tiny, emits one structure, has no parser, and is pinned byte-for-byte by shared vectors — the same discipline the rest of the wire format gets.
- P-256-only means a future algorithm change is a breaking change requiring re-pairing. With two devices and a "forget peer" button, acceptable; recorded rather than discovered later.
- The certificate carries no information, so nothing about it needs to be redacted in logs. Only `identity_spki_sha256` is logged, at 6 hex characters, exactly as ADR-012 already specifies.

## Alternatives considered

| Option | Rejected because |
|---|---|
| `KeyGenParameterSpec` auto-issued certificate on Android | Cannot re-issue around an existing key, which breaks ADR-012's re-issuance semantics. See §3 |
| A general-purpose ASN.1/DER library on both platforms | Far more surface than one certificate needs; the brief explicitly warns against building a framework here |
| RSA-2048 identity keys | Bigger, slower, and adds a padding-agreement question across two stacks for no benefit |
| Ed25519 | Clean and attractive, but TLS 1.3 support for `ed25519` certificates is less uniformly guaranteed across Android's Conscrypt and Apple's stack than `ecdsa_secp256r1_sha256`, and the spike would have had to prove a second thing. P-256 is the conservative interoperable option the brief asked for |
| Put a `subjectAltName` / device name in the certificate | A durable identifier handed to anyone who can open the port. The mDNS TXT record was cleaned of exactly this in ADR-002 A1; re-introducing it one layer down would undo that |
| Whole-certificate pinning | Already rejected by ADR-012, and §3 above is the concrete reason it would have hurt |

## Amendment A1 — 18 October 2026 — the Android identity key must also authorise `DIGEST_NONE`, and an identity created without it is replaced only by the user

**Evidence:** [`docs/PHASE9BA_ANDROID_ANDROID_PHYSICAL.md`](../PHASE9BA_ANDROID_ANDROID_PHYSICAL.md) (two physical phones, Android 16/API 36 and Android 17/API 37), STATUS §4 problem 122.

### What was wrong

§1 specified the Android key as `KeyGenParameterSpec` with `PURPOSE_SIGN` and **`DIGEST_SHA256`** —
correct for the one signature RideLink makes itself (`SHA256withECDSA` over its own TBSCertificate,
§3), and insufficient for the one Android's TLS stack makes with the same key. For an Android
Keystore EC key, Conscrypt computes the TLS 1.3 `CertificateVerify` digest itself and calls back into
Java to sign **that digest** with `NONEwithECDSA` (`CryptoUpcalls.ecSignDigestWithPrivateKey`).
Keystore enforces a key's digest authorizations, so it refused the operation with
`INCOMPATIBLE_DIGEST`. No Android build could complete a RideLink handshake, as client or as server.
Two physical phones were the first place a handshake was ever attempted with a Keystore key: the JVM
suite uses software keys, which carry no digest authorization at all, and Phase 9A never authenticated
a peer. §5's "Keystore `PrivateKeyEntry` presented through a `KeyManager`" was right; the key behind it
could not do the job.

### Decision

1. **New keys are authorised for `DIGEST_SHA256` *and* `DIGEST_NONE`.** Nothing else in §1 changes:
   still P-256, still `PURPOSE_SIGN | PURPOSE_VERIFY`, still non-exportable, still no user
   authentication (the identity must work with the screen locked). The certificate is still
   `ecdsa-with-SHA256`; the wire, the SPKI encoding and every vector are unchanged.
   `NONEwithECDSA` over SHA-256(m) is the same signature as `SHA256withECDSA` over m. The only new
   capability is signing a digest the platform's TLS stack computed, inside the app's own uid,
   which no other app can reach.
2. **`IdentityKeyAuthorization` is the rule**, pure and JVM-tested: a key is usable only when
   `KeyInfo.getDigests()` contains both. A key generated in a way Keystore does not record as
   capable is deleted at once (it is the entry that call just created) and never handed out.
3. **Keystore cannot add an authorization to an existing key.** Every identity created through
   `8f47a72` is therefore permanently unable to sign a handshake, and the only remedy is a **new key —
   a new SPKI**, which ADR-012 says is a new identity. That is never done silently:
   - Keys generated from now on live under a **new alias**, `ridelink.identity.v2`. The old
     alias, `ridelink.identity.v1`, is read, inspected through `KeyInfo` and reported, and is
     **never modified or deleted**.
   - `AndroidKeystoreIdentityStore.resolve` returns `Ready` or `ReplacementRequired`. It generates a
     key only when no identity exists at all, and never re-issues an incompatible one.
   - On `ReplacementRequired` the app builds **nothing** around the unusable key: no `AppContainer`,
     no listener, no discovery, no pairing. It shows a dedicated screen that explains the
     consequence, and replaces only after an explicit confirmation dialog
     (`replaceIncompatibleIdentity`). That call refuses unless the current identity is actually
     incompatible, so it can never rotate a working identity. It is not certificate re-issuance,
     which keeps the SPKI.
   - The container is then built in-process around the new identity. Trusted-peer records are not
     touched, because they pin the *peers'* keys, which have not changed. A peer that had pinned the
     old SPKI sees `pin_mismatch`, and recovery is the existing forget-and-re-pair with a fresh SAS.
     No physical Android pair has ever completed a handshake, so in practice there is no such pin.
4. **TLS presents exactly the resolved identity.** A keystore can now hold two EC identities, and a
   `KeyManagerFactory` over the whole store may choose either. `DeviceIdentity.keyManagers()` wraps
   the platform key manager in `SingleAliasKeyManager`, which offers and dereferences only the
   resolved alias. It still returns the same opaque Keystore handle, and no key material is touched.

### Rejected alternatives

| Option | Rejected because |
|---|---|
| Replace an incompatible key automatically on upgrade | Changes the phone's SPKI without the user knowing, which ADR-012 forbids however benign the cause |
| Delete the old alias and regenerate under the same name | Destroys the old identity irrecoverably, and a crash between delete and generate leaves no identity at all. Keeping both and pinning the alias is non-destructive |
| A software key, or a key exported to a `PKCS12` keystore, so JSSE can sign with it | Exports the identity key — ADR-017 §3 exists to keep it inside Keystore |
| A custom TLS provider or `KeyManager` that signs with `SHA256withECDSA` itself | Conscrypt hands the callback an already-hashed transcript; re-hashing it would produce a signature over the wrong bytes. Authorising `NONE` is the platform's documented mechanism |
| Keep the identity loadable and only warn in the UI | The app would still advertise and accept connections that cannot authenticate, and the user would see the same unexplained stall that Phase 9B-A did |

### iOS

Not affected by this mechanism: `SecKeyCreateSignature` has no per-key digest authorization. The
iPhone handshake has still never run on hardware, so this is an argument, not a measurement.

### Verification

`KeystoreTlsIdentityTest` (instrumented, `network`, real Keystore keys, disposable aliases in the
test package's own Keystore namespace) passed 8/8 on the `RideLink_API36` emulator, the OnePlus
Nord 5 (Android 16/API 36) and the moto g57 power (Android 17/API 37). With the fix reverted (the key
SHA-256-only again), 4 of the 8 fail on the emulator, for the reason the phones showed. The
emulator also ran the real upgrade: main's build created a legacy key, the fix build updated it in
place, the replacement screen appeared, and confirming it produced a new identity that survived a
restart. Replacing an identity **on the two test phones** is not done here; it waits for the user's
authorisation after independent review.
