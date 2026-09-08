import Foundation
import XCTest

@testable import RideLinkCore

/// Runs `protocol/vectors/playback-messages/playback_messages_vectors.json` against
/// `PlaybackCodec`. The mirror is `com.ridelink.core.protocol.PlaybackMessagesVectorTest`, running
/// the **same file**.
///
/// Accepted rows assert the **re-encoded** payload rather than the parsed value's fields: that pins
/// `encode` and `parse` as inverses in one assertion, and is what makes "an unknown field cannot
/// survive a round trip" checkable rather than assumed.
final class PlaybackMessagesVectorTests: XCTestCase {
    private func document() throws -> [String: Any] {
        // swiftlint:disable:next force_cast
        try Vectors.loadJSON("playback-messages/playback_messages_vectors.json") as! [String: Any]
    }

    func testEveryRowOfTheSharedFileHolds() throws {
        let doc = try document()
        var accepted = 0
        var rejected = 0
        for element in doc.array("rows") {
            guard let row = element as? [String: Any] else { return XCTFail("row is not an object") }
            let name = row.str("name")
            let type = row.str("type")
            let expected = row.dict("expected")
            let result = PlaybackCodec.parse(type: type, payload: JSONValueBridge.payload(row.dict("payload")))
            if expected.boolVal("accepted") {
                guard case .parsed(let message) = result else {
                    XCTFail("vector \(name) expected a parse, got \(result)")
                    continue
                }
                XCTAssertEqual(type, PlaybackCodec.wireType(message), "\(name): wire type round trip")
                let encodedExpected = JSONValueBridge.value(expected.dict("encoded"))
                let encodedActual = JSONValue.object(PlaybackCodec.encode(message))
                XCTAssertTrue(
                    JSONValueBridge.equal(encodedExpected, encodedActual),
                    "\(name): re-encoded payload\nexpected \(encodedExpected)\nactual   \(encodedActual)"
                )
                accepted += 1
            } else {
                guard case .rejected(let reason) = result else {
                    XCTFail("vector \(name) expected a rejection, got \(result)")
                    continue
                }
                XCTAssertEqual(PlaybackMessageRejection(rawValue: expected.str("rejection")), reason, "\(name): rejection reason")
                rejected += 1
            }
        }
        XCTAssertEqual(doc.array("rows").count, accepted + rejected, "every row must be classified")
        XCTAssertTrue(accepted > 0 && rejected > 0, "the file must exercise both outcomes")
    }

    /// The bounds are transcribed independently in the generator and in `PlaybackBounds`.
    func testTheVectorFilesBoundsMatchThisPlatformsConstants() throws {
        let bounds = try document().dict("bounds")
        XCTAssertEqual(bounds.int64("max_wire_int"), PlaybackBounds.maxWireInt)
        XCTAssertEqual(bounds.int64("max_position_ms"), PlaybackBounds.maxPositionMs)
        XCTAssertEqual(bounds.doubleVal("min_playback_rate"), PlaybackBounds.minPlaybackRate)
        XCTAssertEqual(bounds.doubleVal("max_playback_rate"), PlaybackBounds.maxPlaybackRate)
    }
}
