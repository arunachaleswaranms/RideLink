import Foundation
import XCTest

@testable import RideLinkCore

/// Runs `protocol/vectors/resync-messages/resync_messages_vectors.json` against `ResyncCodec`
/// (PROTOCOL §10, Phase 7, ADR-028).
///
/// The mirror is `com.ridelink.core.protocol.ResyncMessagesVectorTest`, running the **same file**.
/// Unlike `ManifestMessagesVectorTests`, this vector file's schema is `expected.accepted` +
/// `expected.encoded` (accepted) or `expected.rejection` (refused) — simpler because `STATE_REQUEST`/
/// `STATE_SNAPSHOT` have no message-shape variants to discriminate.
final class ResyncMessagesVectorTests: XCTestCase {
    private let expectedRowCount = 59

    private func document() throws -> [String: Any] {
        // swiftlint:disable:next force_cast
        try Vectors.loadJSON("resync-messages/resync_messages_vectors.json") as! [String: Any]
    }

    func testEveryRowOfTheSharedFileHolds() throws {
        let doc = try document()
        var checked = 0
        for element in doc.array("rows") {
            guard let row = element as? [String: Any] else { return XCTFail("row is not an object") }
            let name = row.str("name")
            let type = row.str("type")
            let result = ResyncCodec.parse(type: type, payload: jsonPayload(row.dict("payload")))
            let expect = row.dict("expected")

            if expect.boolOpt("accepted") == true {
                guard case .parsed(let message) = result else {
                    XCTFail("vector \(name) expected acceptance, got \(result)")
                    continue
                }
                let expectedEncoded = jsonPayload(expect.dict("encoded"))
                XCTAssertEqual(expectedEncoded, ResyncCodec.encode(message), "vector \(name): re-encoded payload")
            } else {
                guard case .rejected(let reason) = result else {
                    XCTFail("vector \(name) expected a rejection, got \(result)")
                    continue
                }
                XCTAssertEqual(ResyncMessageRejection(rawValue: expect.str("rejection")), reason, "vector \(name) rejection reason")
            }
            checked += 1
        }
        XCTAssertEqual(expectedRowCount, checked, "expected \(expectedRowCount) rows")
    }

    /// The bounds are transcribed independently in the generator and in `ResyncCodec`/`ResyncBounds`.
    func testTheVectorFilesBoundsMatchThisPlatformsConstants() throws {
        let bounds = try document().dict("bounds")
        XCTAssertEqual(bounds.int64("max_wire_int"), PlaybackBounds.maxWireInt)
        XCTAssertEqual(bounds.int64("max_position_ms"), PlaybackBounds.maxPositionMs)
        XCTAssertEqual(bounds.int("max_transfers_in_flight"), ResyncBounds.maxTransfersInFlight)
    }

    /// A property the row-by-row assertions cannot state: whatever a peer sends, parsing it is
    /// **total**. A malformed `STATE_REQUEST`/`STATE_SNAPSHOT` frame is dropped without ending the
    /// control connection.
    func testParsingNeverTrapsWhateverThePayload() {
        let hostile: [[String: JSONValue]] = [
            [:],
            ["leader_peer_id": .null],
            ["command_seq": .number(.infinity)],
            ["command_seq": .number(.nan)],
            ["playback": .string("not an object")],
            ["queue": .array([])],
            ["transfers_in_flight": .object([:])],
            ["transfers_in_flight": .array([.string("not an object")])],
        ]
        for type in ResyncMessageTypes.all.union(["STATE_UNKNOWN", ""]) {
            for payload in hostile {
                // No assertion on the outcome: the assertion is that this line returns at all.
                _ = ResyncCodec.parse(type: type, payload: payload)
            }
        }
    }

    // MARK: - helpers

    private func jsonPayload(_ raw: [String: Any]) -> [String: JSONValue] {
        raw.mapValues(jsonValue)
    }

    private func jsonValue(_ raw: Any) -> JSONValue {
        switch raw {
        case is NSNull: return .null
        case let value as String: return .string(value)
        case let value as NSNumber:
            if CFGetTypeID(value) == CFBooleanGetTypeID() { return .bool(value.boolValue) }
            return .number(value.doubleValue)
        case let value as [Any]: return .array(value.map(jsonValue))
        case let value as [String: Any]: return .object(value.mapValues(jsonValue))
        default: return .null
        }
    }
}
