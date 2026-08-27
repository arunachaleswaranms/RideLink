import CryptoKit
import Foundation

/// The RideLink device identity: a P-256 public key, its `identity_spki_sha256`, and the
/// self-signed X.509 certificate that carries it on the wire (ADR-012, ADR-017).
///
/// Everything here is pure byte arrangement. Key generation, signing and storage belong to the
/// platform — the iOS Keychain or Android Keystore — and no private key ever reaches this layer.
/// `com.ridelink.core.security.IdentityCertificate` is the line-for-line mirror; both are pinned
/// by `protocol/vectors/identity/identity_vectors.json`.
public enum IdentityCertificate {
    /// Uncompressed X9.63 point: `0x04 ‖ X(32) ‖ Y(32)`. The only public-key form v1 accepts.
    public static let p256UncompressedPointBytes = 65
    private static let uncompressedPointTag: UInt8 = 0x04

    /// SubjectPublicKeyInfo for a P-256 key is always exactly this long.
    public static let p256SPKIBytes = 91

    /// ADR-017 §2: identical on every device that will ever run this, so it carries no information
    /// and therefore leaks none. Identity is the SPKI hash, never the subject text.
    public static let subjectCommonName = "RideLink Device"

    /// ADR-012: expiry must never masquerade as key rotation, so the window is generous.
    public static let validityYears = 10

    /// Backdate to absorb modest clock skew between the two phones (ADR-017 §2).
    public static let notBeforeBackdateSeconds: Int64 = 24 * 60 * 60

    /// 16 CSPRNG bytes, high bit cleared by the caller so it encodes as a positive INTEGER.
    public static let serialBytes = 16

    /// X.509 v3 encodes as INTEGER 2.
    private static let x509VersionV3 = 2

    /// keyUsage = digitalSignature: BIT STRING, 7 unused bits, bit 0 set.
    private static let keyUsageDigitalSignature: [UInt8] = [0x03, 0x02, 0x07, 0x80]

    public enum IdentityError: Error, Equatable {
        case invalidPublicKeyPoint(String)
        case invalidCertificateInput(String)
    }

    /// Builds the 91-byte SubjectPublicKeyInfo for a P-256 key from its raw uncompressed point.
    ///
    /// iOS needs this for *every* key, its own and a peer's, because
    /// `SecKeyCopyExternalRepresentation` hands back the bare point rather than an SPKI
    /// (ADR-017 §4). Android gets a peer's SPKI for free from `PublicKey.getEncoded()` and only
    /// needs this when building its own certificate. One implementation each, checked against the
    /// same vector, is what keeps the two `identity_spki_sha256` values equal.
    public static func subjectPublicKeyInfo(uncompressedPoint: [UInt8]) throws -> [UInt8] {
        try validate(point: uncompressedPoint)
        return Der.sequence(
            Der.sequence(Der.objectIdentifier(Oid.ecPublicKey), Der.objectIdentifier(Oid.prime256v1)),
            Der.bitString(uncompressedPoint)
        )
    }

    /// `identity_spki_sha256` for a P-256 key: `"sha256:"` ‖ lowercase hex of SHA-256(SPKI).
    public static func identitySpkiSha256(uncompressedPoint: [UInt8]) throws -> SpkiHash {
        spkiHash(ofEncoded: try subjectPublicKeyInfo(uncompressedPoint: uncompressedPoint))
    }

    /// `identity_spki_sha256` for an already-encoded SubjectPublicKeyInfo.
    public static func spkiHash(ofEncoded subjectPublicKeyInfoDER: [UInt8]) -> SpkiHash {
        let digest = SHA256.hash(data: Data(subjectPublicKeyInfoDER))
        return SpkiHash("sha256:" + digest.map { String(format: "%02x", $0) }.joined())
    }

    /// The TBSCertificate — everything that gets signed. Deterministic: the same inputs produce
    /// the same bytes on both platforms, which is what the shared vector asserts.
    ///
    /// - Parameter serial: `serialBytes` CSPRNG bytes with the high bit already cleared.
    public static func tbsCertificate(uncompressedPoint: [UInt8],
                                      serial: [UInt8],
                                      notBefore: UtcTime,
                                      notAfter: UtcTime) throws -> [UInt8] {
        try validate(point: uncompressedPoint)
        guard !serial.isEmpty else {
            throw IdentityError.invalidCertificateInput("certificate serial must not be empty")
        }
        guard notBefore.epochSeconds < notAfter.epochSeconds else {
            throw IdentityError.invalidCertificateInput("notBefore must precede notAfter")
        }
        let name = Der.sequence(Der.set(Der.sequence(
            Der.objectIdentifier(Oid.commonName),
            Der.utf8String(subjectCommonName)
        )))
        return Der.sequence(
            Der.explicit(0, Der.integer(x509VersionV3)),
            Der.integer(serial),
            signatureAlgorithm(),
            name, // issuer — identical to subject, because it is self-signed
            Der.sequence(Der.utcTime(notBefore.utcTimeString), Der.utcTime(notAfter.utcTimeString)),
            name, // subject
            try subjectPublicKeyInfo(uncompressedPoint: uncompressedPoint),
            extensions()
        )
    }

    /// Wraps a signed TBSCertificate into the final X.509 Certificate.
    ///
    /// - Parameter signature: the platform's ECDSA output over SHA-256 of the TBSCertificate,
    ///   already in X9.62 `SEQUENCE { r, s }` DER — which is what both
    ///   `SecKeyCreateSignature(.ecdsaSignatureMessageX962SHA256)` and
    ///   `Signature("SHA256withECDSA")` return.
    public static func certificate(tbsCertificate: [UInt8], signature: [UInt8]) throws -> [UInt8] {
        guard !signature.isEmpty else {
            throw IdentityError.invalidCertificateInput("signature must not be empty")
        }
        return Der.sequence(tbsCertificate, signatureAlgorithm(), Der.bitString(signature))
    }

    /// The ADR-017 validity window for a certificate issued at `issuedAt`: backdated a day to
    /// absorb clock skew, then `validityYears` calendar years wide.
    public static func validityWindow(issuedAt: UtcTime) -> (notBefore: UtcTime, notAfter: UtcTime) {
        let notBefore = issuedAt.minusSeconds(notBeforeBackdateSeconds)
        return (notBefore, notBefore.plusYears(validityYears))
    }

    private static func signatureAlgorithm() -> [UInt8] {
        Der.sequence(Der.objectIdentifier(Oid.ecdsaWithSHA256))
    }

    private static func extensions() -> [UInt8] {
        Der.explicit(3, Der.sequence(
            // basicConstraints, critical, CA:FALSE (an empty SEQUENCE — cA defaults to FALSE)
            Der.sequence(Der.objectIdentifier(Oid.basicConstraints), Der.boolean(true),
                         Der.octetString(Der.sequence())),
            // keyUsage, critical, digitalSignature only
            Der.sequence(Der.objectIdentifier(Oid.keyUsage), Der.boolean(true),
                         Der.octetString(keyUsageDigitalSignature))
        ))
    }

    /// v1 accepts exactly one key shape. Rejecting anything else here removes algorithm confusion
    /// as a class of bug (ADR-017 §1) — a compressed point, a different curve or a different
    /// algorithm never reaches the hash, so it can never become somebody's pinned identity.
    private static func validate(point: [UInt8]) throws {
        guard point.count == p256UncompressedPointBytes else {
            throw IdentityError.invalidPublicKeyPoint(
                "P-256 public key must be \(p256UncompressedPointBytes) bytes, was \(point.count)")
        }
        guard point[0] == uncompressedPointTag else {
            throw IdentityError.invalidPublicKeyPoint(
                "P-256 public key must be in uncompressed X9.63 form (leading 0x04)")
        }
    }
}
