import Foundation
import XCTest

@testable import RideLinkCore

/// Runs `protocol/vectors/queue-messages/queue_messages_vectors.json` against `QueueCodec`. The
/// mirror is `com.ridelink.core.protocol.QueueMessagesVectorTest`, running the **same file**.
final class QueueMessagesVectorTests: XCTestCase {
    private func document() throws -> [String: Any] {
        // swiftlint:disable:next force_cast
        try Vectors.loadJSON("queue-messages/queue_messages_vectors.json") as! [String: Any]
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
            let result = QueueCodec.parse(type: type, payload: JSONValueBridge.payload(row.dict("payload")))
            if expected.boolVal("accepted") {
                guard case .parsed(let message) = result else {
                    XCTFail("vector \(name) expected a parse, got \(result)")
                    continue
                }
                XCTAssertEqual(type, QueueCodec.wireType(message), "\(name): wire type round trip")
                let encodedExpected = JSONValueBridge.value(expected.dict("encoded"))
                let encodedActual = JSONValue.object(QueueCodec.encode(message))
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
                XCTAssertEqual(QueueMessageRejection(rawValue: expected.str("rejection")), reason, "\(name): rejection reason")
                rejected += 1
            }
        }
        XCTAssertEqual(doc.array("rows").count, accepted + rejected, "every row must be classified")
        XCTAssertTrue(accepted > 0 && rejected > 0, "the file must exercise both outcomes")
    }

    /// ADR-024 §6: `status` was removed from the wire because PROTOCOL §9 itself called it
    /// untrusted. A structural scan of what this codec can *emit* is what keeps it removed — an
    /// encoder that quietly reintroduced it would otherwise only be caught by a reviewer noticing.
    func testTheEncoderNeverEmitsAStatusField() throws {
        let doc = try document()
        for element in doc.array("rows") {
            guard let row = element as? [String: Any] else { continue }
            guard row.dict("expected").boolVal("accepted") else { continue }
            let result = QueueCodec.parse(type: row.str("type"), payload: JSONValueBridge.payload(row.dict("payload")))
            guard case .parsed(let message) = result else {
                XCTFail("\(row.str("name")) should have parsed")
                continue
            }
            XCTAssertFalse(
                JSONValueBridge.containsKeyAnywhere(.object(QueueCodec.encode(message)), "status"),
                "\(row.str("name")): encoder emitted a status field"
            )
        }
    }

    func testTheVectorFilesBoundsMatchThisPlatformsConstants() throws {
        let bounds = try document().dict("bounds")
        XCTAssertEqual(bounds.int64("max_wire_int"), PlaybackBounds.maxWireInt)
        XCTAssertEqual(bounds.int("max_queue_items"), PlaybackBounds.maxQueueItems)
    }
}
