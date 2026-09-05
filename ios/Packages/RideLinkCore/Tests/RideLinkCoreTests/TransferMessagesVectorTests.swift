import Foundation
import XCTest

@testable import RideLinkCore

/// Runs `protocol/vectors/transfer-messages/transfer_messages_vectors.json` against
/// `TransferCodec`.
///
/// The mirror is `com.ridelink.core.protocol.TransferMessagesVectorTest`, running the **same
/// file**.
final class TransferMessagesVectorTests: XCTestCase {
    private let expectedRowCount = 44

    private func document() throws -> [String: Any] {
        // swiftlint:disable:next force_cast
        try Vectors.loadJSON("transfer-messages/transfer_messages_vectors.json") as! [String: Any]
    }

    func testEveryRowOfTheSharedFileHolds() throws {
        let doc = try document()
        var checked = 0
        for element in doc.array("rows") {
            guard let row = element as? [String: Any] else { return XCTFail("row is not an object") }
            let name = row.str("name")
            let type = row.str("type")
            let result = TransferCodec.parse(type: type, payload: jsonPayload(row.dict("payload")))
            let expect = row.dict("expect")

            if let parsedSpec = expect.dictOpt("parsed") {
                guard case .parsed(let message) = result else {
                    XCTFail("vector \(name) expected a parse, got \(result)")
                    continue
                }
                assertParsedMatches(name, parsedSpec, message)
            } else {
                guard case .rejected(let reason) = result else {
                    XCTFail("vector \(name) expected a rejection, got \(result)")
                    continue
                }
                XCTAssertEqual(TransferMessageRejection(rawValue: expect.str("rejected")), reason, "vector \(name) rejection reason")
            }
            checked += 1
        }
        XCTAssertEqual(expectedRowCount, checked, "expected \(expectedRowCount) rows")
    }

    /// The bounds are transcribed independently in the generator and in `TransferBounds`.
    func testTheVectorFilesBoundsMatchThisPlatformsConstants() throws {
        let bounds = try document().dict("bounds")
        XCTAssertEqual(bounds.int("CHUNK_SIZE"), TransferBounds.chunkSize)
        XCTAssertEqual(bounds.int64("MAX_TRANSFER_SIZE_BYTES"), TransferBounds.maxTransferSizeBytes)
        let expectedReasons = Set(bounds.array("VALID_CANCEL_REASONS").map { $0 as! String }) // swiftlint:disable:this force_cast
        XCTAssertEqual(expectedReasons, TransferBounds.validCancelReasons)
    }

    /// A property the row-by-row assertions cannot state: whatever a peer sends, parsing it is
    /// **total**. A malformed `TRANSFER_*` frame is dropped without ending the control connection,
    /// so this function must never trap on any payload.
    func testParsingNeverTrapsWhateverThePayload() {
        let hostile: [[String: JSONValue]] = [
            [:],
            ["content_hash": .null],
            ["transfer_id": .array([.string("x")])],
            ["size_bytes": .number(.infinity)],
            ["size_bytes": .number(.nan)],
            ["chunk_count": .number(-1)],
            ["ok": .null, "sha256": .null],
            ["ok": .string("true")],
        ]
        for type in TransferMessageTypes.all.union(["TRANSFER_UNKNOWN", ""]) {
            for payload in hostile {
                // No assertion on the outcome: the assertion is that this line returns at all.
                _ = TransferCodec.parse(type: type, payload: payload)
            }
        }
    }

    // MARK: - helpers

    private func assertParsedMatches(_ name: String, _ spec: [String: Any], _ message: TransferMessage) {
        switch spec.str("kind") {
        case "Request":
            guard case .request(let contentHash, let transferId) = message else {
                return XCTFail("\(name): expected Request")
            }
            XCTAssertEqual(spec.str("content_hash"), contentHash.value, "\(name): content_hash")
            XCTAssertEqual(spec.str("transfer_id"), transferId.value, "\(name): transfer_id")
        case "Offer":
            guard case .offer(
                let transferId, let sizeBytes, let chunkSize, let chunkCount, let bulkPort, let bulkToken
            ) = message else {
                return XCTFail("\(name): expected Offer")
            }
            XCTAssertEqual(spec.str("transfer_id"), transferId.value, "\(name): transfer_id")
            XCTAssertEqual(spec.int64("size_bytes"), sizeBytes, "\(name): size_bytes")
            XCTAssertEqual(spec.int("chunk_size"), chunkSize, "\(name): chunk_size")
            XCTAssertEqual(spec.int("chunk_count"), chunkCount, "\(name): chunk_count")
            XCTAssertEqual(spec.int("bulk_port"), bulkPort, "\(name): bulk_port")
            XCTAssertEqual(spec.str("bulk_token"), bulkToken, "\(name): bulk_token")
        case "Progress":
            guard case .progress(let transferId, let bytes, let pct) = message else {
                return XCTFail("\(name): expected Progress")
            }
            XCTAssertEqual(spec.str("transfer_id"), transferId.value, "\(name): transfer_id")
            XCTAssertEqual(spec.int64("bytes"), bytes, "\(name): bytes")
            XCTAssertEqual(spec.int("pct"), pct, "\(name): pct")
        case "Result":
            guard case .result(let transferId, let ok, let sha256) = message else {
                return XCTFail("\(name): expected Result")
            }
            XCTAssertEqual(spec.str("transfer_id"), transferId.value, "\(name): transfer_id")
            XCTAssertEqual(spec.boolVal("ok"), ok, "\(name): ok")
            XCTAssertEqual(spec.strOpt("sha256"), sha256?.value, "\(name): sha256")
        case "Cancel":
            guard case .cancel(let transferId, let reason) = message else {
                return XCTFail("\(name): expected Cancel")
            }
            XCTAssertEqual(spec.str("transfer_id"), transferId.value, "\(name): transfer_id")
            XCTAssertEqual(spec.str("reason"), reason, "\(name): reason")
        default:
            XCTFail("unrecognised parsed kind \(spec.str("kind"))")
        }
    }

    /// Converts `JSONSerialization`'s `Any` tree into the `JSONValue` the codec takes. `NSNull`
    /// must survive as `.null` rather than becoming an absent key, since several rows distinguish
    /// "sent as null" from "not sent at all" (e.g. `TRANSFER_RESULT.sha256` when `ok: false`).
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
