import Foundation
import XCTest

@testable import RideLinkCore

/// Runs `protocol/vectors/manifest-messages/manifest_messages_vectors.json` against
/// `ManifestCodec`.
///
/// The mirror is `com.ridelink.core.protocol.ManifestMessagesVectorTest`, running the **same
/// file**.
final class ManifestMessagesVectorTests: XCTestCase {
    private let expectedRowCount = 35

    private func document() throws -> [String: Any] {
        // swiftlint:disable:next force_cast
        try Vectors.loadJSON("manifest-messages/manifest_messages_vectors.json") as! [String: Any]
    }

    func testEveryRowOfTheSharedFileHolds() throws {
        let doc = try document()
        var checked = 0
        for element in doc.array("rows") {
            guard let row = element as? [String: Any] else { return XCTFail("row is not an object") }
            let name = row.str("name")
            let type = row.str("type")
            let result = ManifestCodec.parse(type: type, payload: jsonPayload(row.dict("payload")))
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
                XCTAssertEqual(ManifestMessageRejection(rawValue: expect.str("rejected")), reason, "vector \(name) rejection reason")
            }
            checked += 1
        }
        XCTAssertEqual(expectedRowCount, checked, "expected \(expectedRowCount) rows")
    }

    /// The bounds are transcribed independently in the generator and in `ManifestCodec`/`ManifestPaging`.
    func testTheVectorFilesBoundsMatchThisPlatformsConstants() throws {
        let bounds = try document().dict("bounds")
        XCTAssertEqual(bounds.int("MAX_ENTRIES_PER_PAGE"), ManifestPaging.maxEntriesPerPage)
    }

    /// A property the row-by-row assertions cannot state: whatever a peer sends, parsing it is
    /// **total**. A malformed `MANIFEST_*` frame is dropped without ending the control connection.
    func testParsingNeverTrapsWhateverThePayload() {
        let hostile: [[String: JSONValue]] = [
            [:],
            ["since_revision": .string("nope")],
            ["manifest_id": .null],
            ["manifest_revision": .number(.infinity)],
            ["manifest_revision": .number(.nan)],
            ["entries": .array([.string("not an object")])],
            ["entries": .object([:])],
            ["removed": .array([.number(1)])],
            ["page_count": .number(-1.5)],
        ]
        for type in ManifestMessageTypes.all.union(["MANIFEST_UNKNOWN", ""]) {
            for payload in hostile {
                // No assertion on the outcome: the assertion is that this line returns at all.
                _ = ManifestCodec.parse(type: type, payload: payload)
            }
        }
    }

    // MARK: - helpers

    // swiftlint:disable:next cyclomatic_complexity function_body_length
    private func assertParsedMatches(_ name: String, _ spec: [String: Any], _ message: ManifestMessage) {
        switch spec.str("kind") {
        case "Request":
            guard case .request(let sinceRevision, let maxPageBytes) = message else {
                return XCTFail("\(name): expected Request")
            }
            XCTAssertEqual(spec.int64Opt("since_revision"), sinceRevision, "\(name): since_revision")
            XCTAssertEqual(spec.int("max_page_bytes"), maxPageBytes, "\(name): max_page_bytes")
        case "Begin":
            guard case .begin(
                let manifestId, let kind, let manifestRevision, let baseRevision,
                let totalEntries, let totalRemoved, let pageCount, let digestAlg
            ) = message else {
                return XCTFail("\(name): expected Begin")
            }
            XCTAssertEqual(spec.str("manifest_id"), manifestId.value, "\(name): manifest_id")
            XCTAssertEqual(spec.str("manifest_kind"), kind.wire, "\(name): kind")
            XCTAssertEqual(spec.int64("manifest_revision"), manifestRevision, "\(name): manifest_revision")
            XCTAssertEqual(spec.int64Opt("base_revision"), baseRevision, "\(name): base_revision")
            XCTAssertEqual(spec.int("total_entries"), totalEntries, "\(name): total_entries")
            XCTAssertEqual(spec.int("total_removed"), totalRemoved, "\(name): total_removed")
            XCTAssertEqual(spec.intOpt("page_count"), pageCount, "\(name): page_count")
            XCTAssertEqual(spec.str("digest_alg"), digestAlg, "\(name): digest_alg")
        case "Page":
            guard case .page(let manifestId, let manifestRevision, let pageIndex, let entries, let removed) = message else {
                return XCTFail("\(name): expected Page")
            }
            XCTAssertEqual(spec.str("manifest_id"), manifestId.value, "\(name): manifest_id")
            XCTAssertEqual(spec.int64("manifest_revision"), manifestRevision, "\(name): manifest_revision")
            XCTAssertEqual(spec.int("page_index"), pageIndex, "\(name): page_index")
            let expectedEntries = spec.array("entries").map { $0 as! [String: Any] } // swiftlint:disable:this force_cast
            XCTAssertEqual(expectedEntries.count, entries.count, "\(name): entry count")
            for (index, expectedEntry) in expectedEntries.enumerated() {
                XCTAssertEqual(expectedEntry.str("quick_id"), entries[index].quickId.value, "\(name): entries[\(index)].quick_id")
                XCTAssertEqual(expectedEntry.str("title"), entries[index].title, "\(name): entries[\(index)].title")
            }
            let expectedRemoved = spec.array("removed").map { $0 as! String } // swiftlint:disable:this force_cast
            XCTAssertEqual(expectedRemoved, removed.map(\.value), "\(name): removed")
        case "End":
            guard case .end(
                let manifestId, let manifestRevision, let pageCount, let totalEntries, let totalRemoved, let digest
            ) = message else {
                return XCTFail("\(name): expected End")
            }
            XCTAssertEqual(spec.str("manifest_id"), manifestId.value, "\(name): manifest_id")
            XCTAssertEqual(spec.int64("manifest_revision"), manifestRevision, "\(name): manifest_revision")
            XCTAssertEqual(spec.int("page_count"), pageCount, "\(name): page_count")
            XCTAssertEqual(spec.int("total_entries"), totalEntries, "\(name): total_entries")
            XCTAssertEqual(spec.int("total_removed"), totalRemoved, "\(name): total_removed")
            XCTAssertEqual(spec.str("digest"), digest, "\(name): digest")
        case "Abort":
            guard case .abort(let manifestId, let reason) = message else {
                return XCTFail("\(name): expected Abort")
            }
            XCTAssertEqual(spec.str("manifest_id"), manifestId.value, "\(name): manifest_id")
            XCTAssertEqual(spec.str("reason"), reason, "\(name): reason")
        default:
            XCTFail("unrecognised parsed kind \(spec.str("kind"))")
        }
    }

    /// Converts `JSONSerialization`'s `Any` tree into the `JSONValue` the codec takes. `NSNull`
    /// must survive as `.null` rather than becoming an absent key, since several rows distinguish
    /// "sent as null" from "not sent at all" (e.g. a `content_hash` awaiting background hashing).
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
