import XCTest
@testable import RideLinkPlatform

/// TEST_PLAN §4 "Discovery privacy": the advertised TXT key set is **exactly** `{v, dh, plat}`.
/// Runs against `buildTxtRecord` — the same function `BonjourDiscovery.rotate` calls — so an
/// accidental future addition (`peer_id`, a device name, a library count) fails this test, not
/// merely a manual review. Mirrors Android's `DiscoveryPrivacyTest`.
final class DiscoveryPrivacyTests: XCTestCase {
    /// ADR-029 Amendment A2: `SessionCoordinator` logs this state into the exportable sink.
    func testAnAdvertisingStateLogsTheDiscoveryHandleAsSixHexAndNeverTheInstanceName() {
        let handle = "0123456789abcdef0123456789abcdef"
        let logged = "\(AdvertiseState.advertising(serviceName: instanceServiceName(discoveryHandle: handle), discoveryHandle: handle))"
        XCTAssertEqual(logged, "advertising(dh:012345…)")
        XCTAssertFalse(logged.contains("0123456"), "no more than 6 hex of the handle may reach a log line")
    }

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

    /// This session's brief §6: the Bonjour **instance name** — separate from the TXT record —
    /// must not carry `UIDevice.current.name`, a device model, or any other durable identifier.
    /// `instanceServiceName` is the exact function `BonjourDiscovery.rotate` calls when
    /// constructing `NWListener.Service(name:...)`, so this exercises the real value that would
    /// go on the wire.
    func testInstanceServiceNameContainsNoKnownDeviceOrUserValues() {
        let deviceValues = [
            "iPhone", "iPhone 17 Pro Max", "John's iPhone", "Arun", "Arun's Phone", "Rider", "Pillion",
        ]
        let name = instanceServiceName(discoveryHandle: "0123456789abcdef0123456789abcdef")
        for forbidden in deviceValues {
            XCTAssertFalse(
                name.lowercased().contains(forbidden.lowercased()),
                "instance name '\(name)' must not contain device/user value '\(forbidden)'"
            )
        }
    }

    func testInstanceServiceNameDoesNotCarryTheFullThirtyTwoCharacterHandle() {
        let dh = "0123456789abcdef0123456789abcdef"
        let name = instanceServiceName(discoveryHandle: dh)
        XCTAssertFalse(name.contains(dh), "the full dh is unnecessarily durable-looking in an instance name")
    }

    func testInstanceServiceNameRotatesWithTheDiscoveryHandle() {
        let nameA = instanceServiceName(discoveryHandle: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        let nameB = instanceServiceName(discoveryHandle: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        XCTAssertNotEqual(nameA, nameB, "a rotated dh must produce a different instance name")
    }
}
