import Foundation

/// A deliberately tiny DER **encoder**. Not an ASN.1 framework, and not a parser
/// (ADR-017 §3): it emits exactly the structures RideLink's one identity certificate needs and
/// nothing else.
///
/// It exists because iOS has no first-party API that *builds* a certificate, and because Android's
/// `KeyGenParameterSpec` can only issue one at key-generation time — which makes ADR-012's
/// "re-issue a certificate around the same key" behaviour unreachable there. Both platforms
/// therefore use this same encoder; `com.ridelink.core.security.Der` is the line-for-line mirror,
/// and every structure it emits is pinned by `protocol/vectors/identity/`.
///
/// Rules, all of which the vectors check:
/// - definite lengths only, always in **minimal** form (short form below 128, else the shortest long form);
/// - INTEGERs are non-negative, minimally encoded, with a single `0x00` pad added only when the top bit would otherwise make the value negative;
/// - no indefinite lengths, no BER, no re-ordering, no optional-field cleverness.
///
/// Cryptographic signing is never done here — it is always the platform's
/// (`SecKeyCreateSignature` over a Keychain key, `Signature` over an Android Keystore key). This
/// file only arranges bytes.
public enum Der {
    private static let maxShortFormLength = 0x7F
    private static let longFormFlag = 0x80
    private static let highBit: UInt8 = 0x80

    public static let tagBoolean: UInt8 = 0x01
    public static let tagInteger: UInt8 = 0x02
    public static let tagBitString: UInt8 = 0x03
    public static let tagOctetString: UInt8 = 0x04
    public static let tagObjectIdentifier: UInt8 = 0x06
    public static let tagUTF8String: UInt8 = 0x0C
    public static let tagUTCTime: UInt8 = 0x17
    public static let tagSequence: UInt8 = 0x30
    public static let tagSet: UInt8 = 0x31
    private static let tagContextConstructed: UInt8 = 0xA0

    /// DER definite length, minimal form. The likeliest place two hand-written encoders diverge.
    public static func encodeLength(_ length: Int) -> [UInt8] {
        precondition(length >= 0, "DER length cannot be negative")
        if length <= maxShortFormLength { return [UInt8(length)] }
        var body: [UInt8] = []
        var remaining = length
        while remaining > 0 {
            body.insert(UInt8(remaining & 0xFF), at: 0)
            remaining >>= 8
        }
        return [UInt8(longFormFlag | body.count)] + body
    }

    /// tag ‖ length ‖ body.
    public static func tlv(_ tag: UInt8, _ body: [UInt8]) -> [UInt8] {
        [tag] + encodeLength(body.count) + body
    }

    public static func sequence(_ items: [UInt8]...) -> [UInt8] { tlv(tagSequence, items.flatMap { $0 }) }

    public static func set(_ items: [UInt8]...) -> [UInt8] { tlv(tagSet, items.flatMap { $0 }) }

    public static func objectIdentifier(_ encodedOid: [UInt8]) -> [UInt8] { tlv(tagObjectIdentifier, encodedOid) }

    /// BIT STRING with no unused trailing bits — the only form this certificate needs.
    public static func bitString(_ body: [UInt8]) -> [UInt8] { tlv(tagBitString, [0x00] + body) }

    public static func octetString(_ body: [UInt8]) -> [UInt8] { tlv(tagOctetString, body) }

    public static func utf8String(_ value: String) -> [UInt8] { tlv(tagUTF8String, Array(value.utf8)) }

    /// UTCTime, `YYMMDDHHMMSSZ`. Callers pass an already-formatted `UtcTime`.
    public static func utcTime(_ value: String) -> [UInt8] {
        precondition(isUTCTimeFormatted(value), "UTCTime must be YYMMDDHHMMSSZ")
        return tlv(tagUTCTime, Array(value.utf8))
    }

    public static func boolean(_ value: Bool) -> [UInt8] { tlv(tagBoolean, [value ? 0xFF : 0x00]) }

    /// `[n] EXPLICIT` — a constructed context-specific tag wrapping an already-encoded value.
    public static func explicit(_ tagNumber: UInt8, _ body: [UInt8]) -> [UInt8] {
        precondition(tagNumber <= 0x1E, "only low-tag-number context tags are supported")
        return tlv(tagContextConstructed | tagNumber, body)
    }

    /// Non-negative INTEGER in minimal two's-complement form: redundant leading zero bytes are
    /// stripped, and exactly one `0x00` is prepended when the leading bit would otherwise be read
    /// as a sign bit.
    public static func integer(_ magnitude: [UInt8]) -> [UInt8] {
        var start = 0
        while start + 1 < magnitude.count, magnitude[start] == 0x00, (magnitude[start + 1] & highBit) == 0 {
            start += 1
        }
        let trimmed = magnitude.isEmpty ? [0x00] : Array(magnitude[start...])
        let body = (trimmed[0] & highBit) != 0 ? [0x00] + trimmed : trimmed
        return tlv(tagInteger, body)
    }

    public static func integer(_ value: Int) -> [UInt8] {
        precondition(value >= 0, "only non-negative INTEGERs are encoded")
        if value == 0 { return integer([0x00]) }
        var body: [UInt8] = []
        var remaining = value
        while remaining > 0 {
            body.insert(UInt8(remaining & 0xFF), at: 0)
            remaining >>= 8
        }
        return integer(body)
    }

    private static func isUTCTimeFormatted(_ value: String) -> Bool {
        value.count == 13 && value.last == "Z" && value.dropLast().allSatisfy { $0.isASCII && $0.isNumber }
    }
}

/// The DER object identifiers this certificate uses, and only those.
public enum Oid {
    /// 1.2.840.10045.2.1 — id-ecPublicKey.
    public static let ecPublicKey: [UInt8] = [0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01]

    /// 1.2.840.10045.3.1.7 — prime256v1 / secp256r1 / NIST P-256.
    public static let prime256v1: [UInt8] = [0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07]

    /// 1.2.840.10045.4.3.2 — ecdsa-with-SHA256. Parameters MUST be absent (RFC 5758).
    public static let ecdsaWithSHA256: [UInt8] = [0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x04, 0x03, 0x02]

    /// 2.5.4.3 — id-at-commonName.
    public static let commonName: [UInt8] = [0x55, 0x04, 0x03]

    /// 2.5.29.19 — basicConstraints.
    public static let basicConstraints: [UInt8] = [0x55, 0x1D, 0x13]

    /// 2.5.29.15 — keyUsage.
    public static let keyUsage: [UInt8] = [0x55, 0x1D, 0x0F]
}
