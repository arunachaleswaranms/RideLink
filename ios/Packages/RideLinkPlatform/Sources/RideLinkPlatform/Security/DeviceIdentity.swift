import CryptoKit
import Foundation
import RideLinkCore
import Security

/// The device's long-term RideLink identity, in the shape the TLS layer needs it
/// (ADR-012, ADR-017).
///
/// The private key never appears here as bytes. It stays behind an opaque `SecKey` handle whose
/// material lives in the Keychain, and the only operation performed with it is
/// `SecKeyCreateSignature` — there is no export, no serialisation and no log path.
public struct DeviceIdentity: @unchecked Sendable {
    /// `identity_spki_sha256`, the only pinned value in the system (ADR-012).
    public let identitySpkiSha256: SpkiHash

    /// The DER certificate this device presents during the TLS handshake.
    public let certificateDER: Data

    /// What `Network.framework` needs as a local TLS identity.
    public let secIdentity: SecIdentity

    /// The raw uncompressed X9.63 point of the identity public key.
    public let publicKeyPoint: [UInt8]
}

public enum DeviceIdentityError: Error, CustomStringConvertible {
    case keyGenerationFailed(String)
    case publicKeyUnavailable(String)
    case signingFailed(String)
    case certificateRejectedByParser
    case identityCreationFailed
    case keychainFailure(OSStatus)
    case unsupportedKey(String)

    public var description: String {
        switch self {
        case let .keyGenerationFailed(reason): return "identity key generation failed: \(reason)"
        case let .publicKeyUnavailable(reason): return "identity public key unavailable: \(reason)"
        case let .signingFailed(reason): return "certificate signing failed: \(reason)"
        case .certificateRejectedByParser: return "SecCertificateCreateWithData rejected the encoded certificate"
        case .identityCreationFailed: return "SecIdentityCreate returned nil"
        case let .keychainFailure(status): return "keychain operation failed with OSStatus \(status)"
        case let .unsupportedKey(reason): return "unsupported identity key: \(reason)"
        }
    }
}

/// Creates and loads the device identity (ADR-017 §1: P-256, `ecdsa-with-SHA256`, one algorithm
/// for both platforms).
///
/// **Where the key lives.** `.keychain` stores a *permanent* key under an application tag, which
/// is what the app uses. `.ephemeral` produces a transient key that vanishes with the process; it
/// exists because an unsigned test binary has no keychain entitlement, so it is the only way the
/// certificate encoding, `SecIdentityCreate` and the TLS handshake can be exercised by
/// `swift test` on a laptop. It is named for what it is and never selected by the app.
///
/// **Lifetime of the keychain key, by platform, stated because it differs and users notice:**
///
/// | Event | Key survives? |
/// |---|---|
/// | App restart / device reboot | Yes |
/// | App upgrade (same signing identity, same keychain access group) | Yes |
/// | Uninstall / reinstall | **Usually yes on iOS** — unlike Android, iOS has historically left
///   keychain items behind after an app is deleted. Not relied upon: a reinstall that *does* lose
///   it simply produces a new identity, which is a `pin_mismatch` on the peer and is recovered by
///   forget-and-re-pair, exactly as ADR-012 specifies |
/// | "Reset all content and settings" | No |
///
/// A different key *is* a different identity (ADR-012), so losing it is never silently papered
/// over — it produces a security warning the user resolves deliberately.
public struct DeviceIdentityStore {
    public enum Storage: Sendable {
        /// Production: a permanent Keychain key under `applicationTag`.
        case keychain(applicationTag: String)
        /// **Test only.** A transient key that needs no keychain entitlement and does not persist.
        case ephemeral
    }

    public static let defaultApplicationTag = "com.ridelink.identity.v1"

    private let storage: Storage

    public init(storage: Storage = .keychain(applicationTag: defaultApplicationTag)) {
        self.storage = storage
    }

    /// Returns the existing identity, or creates one on first run.
    ///
    /// If a stored certificate has fallen outside its validity window, a fresh one is issued
    /// **around the same key**: the SPKI is unchanged, so peers stay trusted and no six-digit code
    /// is shown (ADR-012, PROTOCOL §4.5.3).
    ///
    /// - Parameter now: wall-clock time, supplied by the caller. X.509 validity is defined in
    ///   wall-clock terms by RFC 5280 and has no monotonic alternative — see `UtcTime` for why
    ///   this is the one permitted exception to CLAUDE.md's monotonic-clocks rule.
    public func loadOrCreate(now: UtcTime) throws -> DeviceIdentity {
        let privateKey: SecKey
        switch storage {
        case let .keychain(applicationTag):
            privateKey = try loadKeychainKey(applicationTag: applicationTag) ?? generateKeychainKey(applicationTag: applicationTag)
        case .ephemeral:
            privateKey = try generateKey(permanent: false, applicationTag: nil)
        }
        return try issueIdentity(privateKey: privateKey, now: now)
    }

    /// Builds the certificate and the `SecIdentity` around an existing key. Re-issuing is a
    /// first-class operation, not an afterthought: ADR-012 pins the key, so a fresh certificate
    /// for an old key must be a non-event.
    public func issueIdentity(privateKey: SecKey, now: UtcTime) throws -> DeviceIdentity {
        let point = try uncompressedPoint(of: privateKey)
        let (notBefore, notAfter) = IdentityCertificate.validityWindow(issuedAt: now)
        let tbs = try IdentityCertificate.tbsCertificate(
            uncompressedPoint: point,
            serial: Self.freshSerial(),
            notBefore: notBefore,
            notAfter: notAfter
        )

        var error: Unmanaged<CFError>?
        guard let signature = SecKeyCreateSignature(
            privateKey, .ecdsaSignatureMessageX962SHA256, Data(tbs) as CFData, &error
        ) as Data? else {
            throw DeviceIdentityError.signingFailed("\(error?.takeRetainedValue().localizedDescription ?? "unknown")")
        }

        let der = Data(try IdentityCertificate.certificate(tbsCertificate: tbs, signature: [UInt8](signature)))
        // Round-tripping through Apple's own parser here means a malformed encoding fails at
        // creation, on this device, rather than as an unexplained handshake failure on the peer.
        guard let certificate = SecCertificateCreateWithData(nil, der as CFData) else {
            throw DeviceIdentityError.certificateRejectedByParser
        }
        // SecIdentityCreate, NOT SecIdentityCreateWithCertificate: the latter is macOS-only
        // (`SEC_OS_OSX` / `__IPHONE_NA`) and is the function most iOS examples reach for by
        // mistake. This one takes the key directly and needs no PKCS#12 round trip, so the private
        // key is never exported (ADR-017 §5).
        guard let identity = SecIdentityCreate(nil, certificate, privateKey) else {
            throw DeviceIdentityError.identityCreationFailed
        }

        return DeviceIdentity(
            identitySpkiSha256: try IdentityCertificate.identitySpkiSha256(uncompressedPoint: point),
            certificateDER: der,
            secIdentity: identity,
            publicKeyPoint: point
        )
    }

    /// 16 CSPRNG bytes with the top bit cleared, so the DER INTEGER is unambiguously positive
    /// without a leading pad byte (ADR-017 §2).
    public static func freshSerial() -> [UInt8] {
        var serial = [UInt8](repeating: 0, count: IdentityCertificate.serialBytes)
        _ = SecRandomCopyBytes(kSecRandomDefault, serial.count, &serial)
        serial[0] &= 0x7F
        return serial
    }

    // MARK: - Key material

    private func generateKey(permanent: Bool, applicationTag: String?) throws -> SecKey {
        var attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
        ]
        if permanent, let applicationTag {
            attributes[kSecPrivateKeyAttrs as String] = [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: Data(applicationTag.utf8),
                // The identity must be usable while the screen is locked, which is the normal
                // state of a phone in a tank bag mid-ride (ARCHITECTURE §6.4). Hence
                // AfterFirstUnlock rather than WhenUnlocked, and no biometric gate.
                kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            ]
        } else {
            attributes[kSecPrivateKeyAttrs as String] = [kSecAttrIsPermanent as String: false]
        }

        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            throw DeviceIdentityError.keyGenerationFailed("\(error?.takeRetainedValue().localizedDescription ?? "unknown")")
        }
        return key
    }

    private func generateKeychainKey(applicationTag: String) throws -> SecKey {
        try generateKey(permanent: true, applicationTag: applicationTag)
    }

    private func loadKeychainKey(applicationTag: String) throws -> SecKey? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrApplicationTag as String: Data(applicationTag.utf8),
            kSecReturnRef as String: true,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        switch status {
        case errSecSuccess:
            guard let item else { return nil }
            // A CFTypeRef from a kSecReturnRef query on kSecClassKey is a SecKey.
            return (item as! SecKey) // swiftlint:disable:this force_cast
        case errSecItemNotFound:
            return nil
        default:
            throw DeviceIdentityError.keychainFailure(status)
        }
    }

    /// The raw uncompressed X9.63 point for a P-256 key.
    ///
    /// `SecKeyCopyExternalRepresentation` returns the bare point rather than a
    /// SubjectPublicKeyInfo, which is the asymmetry ADR-017 §4 records: Android reads an SPKI
    /// straight out of `PublicKey.getEncoded()`, iOS has to rebuild one. The length and leading
    /// `0x04` are checked by `IdentityCertificate`, so a non-P-256 key can never reach the hash
    /// and become somebody's pinned identity.
    private func uncompressedPoint(of privateKey: SecKey) throws -> [UInt8] {
        guard let publicKey = SecKeyCopyPublicKey(privateKey) else {
            throw DeviceIdentityError.publicKeyUnavailable("SecKeyCopyPublicKey returned nil")
        }
        var error: Unmanaged<CFError>?
        guard let raw = SecKeyCopyExternalRepresentation(publicKey, &error) as Data? else {
            throw DeviceIdentityError.publicKeyUnavailable(
                "\(error?.takeRetainedValue().localizedDescription ?? "unknown")")
        }
        return [UInt8](raw)
    }
}

/// Certificate facts RideLink needs from a peer, derived with Apple's own X.509 implementation
/// rather than by parsing DER ourselves.
public enum PeerCertificateInspector {
    /// `identity_spki_sha256` for a peer certificate (ADR-012).
    ///
    /// Rebuilt from the peer's public key, and rejected unless it is a P-256 key in the canonical
    /// encoding — so a peer offering RSA or a different curve is refused before its key can be
    /// hashed into something that looks like an identity.
    public static func identitySpkiSha256(of certificate: SecCertificate) throws -> SpkiHash {
        guard let key = SecCertificateCopyKey(certificate) else {
            throw DeviceIdentityError.publicKeyUnavailable("SecCertificateCopyKey returned nil")
        }
        guard let attributes = SecKeyCopyAttributes(key) as? [String: Any],
              let keyType = attributes[kSecAttrKeyType as String] as? String,
              keyType == (kSecAttrKeyTypeECSECPrimeRandom as String),
              let bits = attributes[kSecAttrKeySizeInBits as String] as? Int, bits == 256
        else {
            throw DeviceIdentityError.unsupportedKey("peer key is not P-256 (ADR-017 §1)")
        }
        var error: Unmanaged<CFError>?
        guard let raw = SecKeyCopyExternalRepresentation(key, &error) as Data? else {
            throw DeviceIdentityError.publicKeyUnavailable(
                "\(error?.takeRetainedValue().localizedDescription ?? "unknown")")
        }
        return try IdentityCertificate.identitySpkiSha256(uncompressedPoint: [UInt8](raw))
    }

    /// The structural half of PROTOCOL §4.1's certificate check: well-formed, self-signature
    /// verifies, inside its validity window.
    ///
    /// Implemented by evaluating the certificate **against itself as the anchor** with a basic
    /// X.509 policy, which is exactly "is this a valid self-signed certificate right now" and
    /// deliberately *not* a chain or hostname check — RideLink has no CA and no name to check
    /// (ARCHITECTURE §4.3). A false here becomes `ERROR/certificate_invalid`, a distinct code from
    /// `pin_mismatch` so a phone with a wrong clock is not reported to its owner as an attack.
    public static func isStructurallyValid(_ certificate: SecCertificate) -> Bool {
        var trust: SecTrust?
        let policy = SecPolicyCreateBasicX509()
        guard SecTrustCreateWithCertificates(certificate, policy, &trust) == errSecSuccess,
              let trust
        else {
            return false
        }
        guard SecTrustSetAnchorCertificates(trust, [certificate] as CFArray) == errSecSuccess,
              SecTrustSetAnchorCertificatesOnly(trust, true) == errSecSuccess
        else {
            return false
        }
        return SecTrustEvaluateWithError(trust, nil)
    }
}
