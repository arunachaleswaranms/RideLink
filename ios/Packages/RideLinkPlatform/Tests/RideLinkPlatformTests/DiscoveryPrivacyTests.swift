import XCTest
@testable import RideLinkPlatform

/// TEST_PLAN §4 "Discovery privacy": the advertised TXT key set is **exactly** `{v, dh, plat}`.
/// Runs against `buildTxtRecord` — the same function `BonjourDiscovery.rotate` calls — so an
/// accidental future addition (`peer_id`, a device name, a library count) fails this test, not
/// merely a manual review. Mirrors Android's `DiscoveryPrivacyTest`.
final class DiscoveryPrivacyTests: XCTestCase {
    func testTxtKeySetIsExactlyVDhPlat() {
        let record = buildTxtRecord(discoveryHandle: "0123456789abcdef0123456789abcdef")
        XCTAssertEqual(Set(record.keys), ["v", "dh", "plat"])
    }

    func testPlatIsAlwaysIosOnThisPlatform() {
        XCTAssertEqual(buildTxtRecord(discoveryHandle: "anything")["plat"], "ios")
    }

    func testNoTxtValueLooksLikeAPeerIdOrSpkiHash() {
        let record = buildTxtRecord(discoveryHandle: "0123456789abcdef0123456789abcdef")
        let peerIdShape = try! NSRegularExpression(pattern: "^[0-9a-f]{16}$")
        let spkiShape = try! NSRegularExpression(pattern: "^[0-9a-f]{64}$")
        for value in record.values {
            let range = NSRange(value.startIndex..., in: value)
            XCTAssertNil(peerIdShape.firstMatch(in: value, range: range), "TXT value '\(value)' looks like a peer_id")
            XCTAssertNil(spkiShape.firstMatch(in: value, range: range), "TXT value '\(value)' looks like an SPKI hash")
        }
    }
}
