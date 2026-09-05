import Foundation
import XCTest

@testable import RideLinkCore

/// Runs `protocol/vectors/bulk-framing/bulk_framing_vectors.json` against `BulkFraming`.
///
/// The mirror is `com.ridelink.core.transfer.BulkFramingVectorTest`, running the **same file**.
final class BulkFramingVectorTests: XCTestCase {
    private let expectedRowCount = 15

    private func document() throws -> [String: Any] {
        // swiftlint:disable:next force_cast
        try Vectors.loadJSON("bulk-framing/bulk_framing_vectors.json") as! [String: Any]
    }

    func testEveryRowOfTheSharedFileHolds() throws {
        let doc = try document()
        var checked = 0
        for element in doc.array("rows") {
            guard let row = element as? [String: Any] else { return XCTFail("row is not an object") }
            let name = row.str("name")
            let buffer = hexToBytes(row.str("buffer_hex"))
            let expect = row.dict("expect")
            let result = BulkFraming.parseAll(buffer)

            switch expect.str("outcome") {
            case "parsed":
                guard case .parsed(let frames, let leftover) = result else {
                    XCTFail("vector \(name) expected Parsed, got \(result)")
                    continue
                }
                let expectedFrames = expect.array("frames").map { $0 as! [String: Any] } // swiftlint:disable:this force_cast
                XCTAssertEqual(expectedFrames.count, frames.count, "vector \(name): frame count")
                for (index, expectedFrame) in expectedFrames.enumerated() {
                    XCTAssertEqual(
                        UInt32(expectedFrame.int64("chunk_index")),
                        frames[index].chunkIndex,
                        "vector \(name): frames[\(index)].chunk_index"
                    )
                    XCTAssertEqual(
                        expectedFrame.str("payload_hex"),
                        bytesToHex(frames[index].payload),
                        "vector \(name): frames[\(index)].payload"
                    )
                }
                XCTAssertEqual(expect.str("leftover_hex"), bytesToHex(leftover), "vector \(name): leftover")
            case "incomplete":
                guard case .incomplete = result else {
                    XCTFail("vector \(name) expected Incomplete, got \(result)")
                    continue
                }
            case "invalid":
                guard case .invalid(let reason) = result else {
                    XCTFail("vector \(name) expected Invalid, got \(result)")
                    continue
                }
                XCTAssertEqual(expect.str("reason"), reason, "vector \(name): reason")
            default:
                XCTFail("vector \(name): unrecognised outcome")
            }
            checked += 1
        }
        XCTAssertEqual(expectedRowCount, checked, "expected \(expectedRowCount) rows")
    }

    /// Whatever bytes a peer sends, parsing them is total. `parseAll` never traps on any input,
    /// including a buffer far larger than any single frame and one made entirely of the magic
    /// prefix — the same discipline `VoiceSignalCodec`'s equivalent test pins for JSON payloads.
    func testParsingNeverTrapsWhateverTheBuffer() {
        let hostile: [[UInt8]] = [
            [],
            [0x52],
            Array(repeating: 0x52, count: 4096),
            Array("RLB1".utf8) + [0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF],
            Array("XXXX".utf8) + Array(repeating: 0, count: 8),
        ]
        for buffer in hostile {
            // No assertion on the outcome: the assertion is that this line returns at all.
            _ = BulkFraming.parseAll(buffer)
        }
    }

    // MARK: - hex helpers

    private func hexToBytes(_ hex: String) -> [UInt8] {
        var bytes: [UInt8] = []
        bytes.reserveCapacity(hex.count / 2)
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            bytes.append(UInt8(hex[index..<next], radix: 16)!)
            index = next
        }
        return bytes
    }

    private func bytesToHex(_ bytes: [UInt8]) -> String {
        bytes.map { String(format: "%02x", $0) }.joined()
    }
}
