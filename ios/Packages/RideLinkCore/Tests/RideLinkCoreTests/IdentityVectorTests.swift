import XCTest
@testable import RideLinkCore

/// `protocol/vectors/identity/identity_vectors.json` — ADR-012 and ADR-017, TEST_PLAN §3.
///
/// The mirror of Android's `IdentityVectorTest`, run against the same file. A DER length encoded
/// one byte differently, an INTEGER padded when it should not be, or a hex digest formatted in
/// uppercase would all produce a different `identity_spki_sha256` on one phone than the other —
/// which presents to the user as an unexplained `pin_mismatch` mid-ride, i.e. exactly what a real
/// attack looks like. That failure belongs here, on a laptop.
final class IdentityVectorTests: XCTestCase {
    private func root() throws -> [String: Any] {
        try Vectors.loadJSON("identity/identity_vectors.json") as! [String: Any] // swiftlint:disable:this force_cast
    }

    private func cases(_ section: String, _ key: String = "cases") throws -> [[String: Any]] {
        try root().dict(section).array(key) as! [[String: Any]] // swiftlint:disable:this force_cast
    }

    private func bytes(_ hex: String) -> [UInt8] {
        stride(from: 0, to: hex.count, by: 2).map { index in
            let start = hex.index(hex.startIndex, offsetBy: index)
            let end = hex.index(start, offsetBy: 2)
            return UInt8(hex[start ..< end], radix: 16)!
        }
    }

    private func hex(_ bytes: [UInt8]) -> String {
        bytes.map { String(format: "%02x", $0) }.joined()
    }

    func testDerLengths() throws {
        for vector in try cases("der_length_vectors") {
            XCTAssertEqual(vector.str("expected_hex"), hex(Der.encodeLength(vector.int("length"))),
                           "der-length/\(vector.str("name"))")
        }
    }

    func testDerIntegers() throws {
        for vector in try cases("der_integer_vectors") {
            XCTAssertEqual(vector.str("expected_hex"), hex(Der.integer(bytes(vector.str("magnitude_hex")))),
                           "der-integer/\(vector.str("name"))")
        }
    }

    func testSubjectPublicKeyInfo() throws {
        for vector in try cases("spki_vectors") {
            let name = vector.str("name")
            let point = bytes(vector.str("point_hex"))
            let expected = vector.dict("expected")
            let spki = try IdentityCertificate.subjectPublicKeyInfo(uncompressedPoint: point)
            XCTAssertEqual(expected.str("spki_der_hex"), hex(spki), "spki/\(name)")
            XCTAssertEqual(expected.int("spki_der_bytes"), spki.count, "spki/\(name)")
            XCTAssertEqual(IdentityCertificate.p256SPKIBytes, spki.count, "spki/\(name)")
            let computed = try IdentityCertificate.identitySpkiSha256(uncompressedPoint: point)
            XCTAssertEqual(expected.str("identity_spki_sha256"), computed.value, "spki/\(name)")
            // Both routes to the pin — rebuild-from-point and hash-an-encoded-SPKI — must agree,
            // because iOS uses the first for a peer and Android uses the second.
            XCTAssertEqual(computed, IdentityCertificate.spkiHash(ofEncoded: spki), "spki/\(name)")
        }
    }

    func testRejectedPublicKeyPoints() throws {
        for vector in try cases("spki_vectors", "rejected_points") {
            let point = bytes(vector.str("point_hex"))
            XCTAssertThrowsError(try IdentityCertificate.subjectPublicKeyInfo(uncompressedPoint: point),
                                 "spki-rejected/\(vector.str("name")) must be rejected: \(vector.str("reason"))")
        }
    }

    func testSpkiHashFormat() throws {
        let section = try root().dict("identity_spki_sha256_format_vectors")
        for vector in section.array("accepted") as! [[String: Any]] { // swiftlint:disable:this force_cast
            let value = vector.str("value")
            XCTAssertTrue(PeerTrust.isWellFormedSpkiHash(value), "accepted/\(vector.str("name"))")
            XCTAssertNotNil(SpkiHash.parse(value), "accepted/\(vector.str("name"))")
        }
        for vector in section.array("rejected") as! [[String: Any]] { // swiftlint:disable:this force_cast
            let value = vector.str("value")
            XCTAssertFalse(PeerTrust.isWellFormedSpkiHash(value),
                           "rejected/\(vector.str("name")): \(vector.str("reason"))")
            // SpkiHash.parse is the wire-safe constructor; init(_:) traps by design and is only
            // for values we generated ourselves, so a malformed value must be rejected here.
            XCTAssertNil(SpkiHash.parse(value), "rejected/\(vector.str("name"))")
        }
    }

    func testTbsCertificates() throws {
        for vector in try cases("tbs_certificate_vectors") {
            let name = vector.str("name")
            let input = vector.dict("input")
            let expected = vector.dict("expected")
            XCTAssertEqual(IdentityCertificate.subjectCommonName, input.str("subject_common_name"),
                           "the vector and the implementation must agree on the subject")
            let tbs = try IdentityCertificate.tbsCertificate(
                uncompressedPoint: bytes(input.str("point_hex")),
                serial: bytes(input.str("serial_hex")),
                notBefore: try XCTUnwrap(UtcTime.parse(input.str("not_before_utc"))),
                notAfter: try XCTUnwrap(UtcTime.parse(input.str("not_after_utc")))
            )
            XCTAssertEqual(expected.str("tbs_der_hex"), hex(tbs), "tbs/\(name)")
            XCTAssertEqual(expected.int("tbs_der_bytes"), tbs.count, "tbs/\(name)")

            if let full = expected.strOpt("certificate_der_hex_with_fabricated_signature") {
                let signature = bytes(expected.str("fabricated_signature_der_hex"))
                let certificate = try IdentityCertificate.certificate(tbsCertificate: tbs, signature: signature)
                XCTAssertEqual(full, hex(certificate), "certificate/\(name)")
            }
        }
    }

    func testPinDecisions() throws {
        for vector in try cases("pin_decision_vectors") {
            let name = vector.str("name")
            let input = vector.dict("input")
            let expected = vector.dict("expected")
            let decision = PeerTrust.decide(
                storedPin: input.strOpt("stored_pin").flatMap(SpkiHash.parse),
                presentedSpki: try XCTUnwrap(SpkiHash.parse(input.str("presented_spki"))),
                helloAdvertisedSpki: input.strOpt("hello_advisory").flatMap(SpkiHash.parse),
                certificateStructurallyValid: input.boolVal("certificate_valid")
            )
            let actual: String
            switch decision {
            case .trusted: actual = "trusted"
            case .pairingRequired: actual = "pairing_required"
            case let .refused(code): actual = code
            }
            XCTAssertEqual(expected.str("decision"), actual, "pin/\(name)")
            if let errorCode = expected.strOpt("error_code") {
                XCTAssertEqual(PinDecision.refused(code: errorCode), decision, "pin/\(name)")
            }
        }
    }

    func testCertificateValidity() throws {
        for vector in try cases("certificate_validity_vectors") {
            let notBefore = try XCTUnwrap(UtcTime.parse(vector.str("not_before_utc")))
            let notAfter = try XCTUnwrap(UtcTime.parse(vector.str("not_after_utc")))
            let now = try XCTUnwrap(UtcTime.parse(vector.str("now_utc")))
            XCTAssertEqual(vector.dict("expected").boolVal("valid"),
                           now.isWithin(notBefore: notBefore, notAfter: notAfter),
                           "validity/\(vector.str("name"))")
        }
    }

    /// Not vector-driven: a round-trip property that would catch a civil-date bug the fixed
    /// vectors above could miss, including the pre-1970 floor-division case Swift's truncating
    /// `/` gets wrong and Kotlin's `Math.floorDiv` does not.
    func testUtcTimeRoundTripsAcrossAwkwardDates() throws {
        let samples = [
            "700101000000Z", // the epoch itself
            "691231235959Z", // one second before it — negative epochSeconds
            "000229120000Z", // 2000 is a leap year (divisible by 400)
            "040229235959Z",
            "491231235959Z", // last instant UTCTime can represent
            "500101000000Z", // first 19xx year under the RFC 5280 pivot
            "260827120000Z",
        ]
        for sample in samples {
            let parsed = try XCTUnwrap(UtcTime.parse(sample), sample)
            XCTAssertEqual(sample, parsed.utcTimeString, "round trip of \(sample)")
        }
        XCTAssertNil(UtcTime.parse("260230120000Z"), "30 February is not a date")
        XCTAssertNil(UtcTime.parse("250229120000Z"), "2025 is not a leap year")
        XCTAssertNil(UtcTime.parse("261301120000Z"), "month 13 is not a month")
        XCTAssertNil(UtcTime.parse("260827250000Z"), "hour 25 is not an hour")
        XCTAssertNil(UtcTime.parse("260827120000"), "the trailing Z is required")
        XCTAssertNil(UtcTime.parse(""), "empty is not a UTCTime")

        // 29 February clamps rather than rolling into 1 March when the target year is not a leap year.
        let leapDay = try XCTUnwrap(UtcTime.parse("240229120000Z"))
        XCTAssertEqual("250228120000Z", leapDay.plusYears(1).utcTimeString)
        XCTAssertEqual("280229120000Z", leapDay.plusYears(4).utcTimeString)
    }

    /// ADR-017 §2: a certificate issued now is backdated a day and lasts ten calendar years.
    func testValidityWindowMatchesAdr017() throws {
        let issuedAt = try XCTUnwrap(UtcTime.parse("260827120000Z"))
        let window = IdentityCertificate.validityWindow(issuedAt: issuedAt)
        XCTAssertEqual("260826120000Z", window.notBefore.utcTimeString)
        XCTAssertEqual("360826120000Z", window.notAfter.utcTimeString)
        XCTAssertTrue(issuedAt.isWithin(notBefore: window.notBefore, notAfter: window.notAfter))
    }
}
