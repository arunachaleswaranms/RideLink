import Foundation
import XCTest
@testable import RideLinkCore

/// protocol/vectors/sas/sas_vectors.json, PROTOCOL §4.5.1-4.5.2, TEST_PLAN §2/§11.
final class SasVectorTests: XCTestCase {
    private func hexToBytes(_ hex: String) -> [UInt8] {
        var bytes: [UInt8] = []
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            bytes.append(UInt8(hex[index..<next], radix: 16)!)
            index = next
        }
        return bytes
    }

    func testValueVectors() throws {
        let root = try Vectors.loadJSON("sas/sas_vectors.json") as! [String: Any] // swiftlint:disable:this force_cast
        let vectors = root.array("value_vectors") as! [[String: Any]] // swiftlint:disable:this force_cast

        for vector in vectors {
            let name = vector.str("name")
            let hex = vector.dict("input").str("exporter_output_hex")
            let expectedSas6 = vector.dict("expected").str("sas6")
            let actual = Sas.deriveSas6(hexToBytes(hex))
            XCTAssertEqual(actual, expectedSas6, "[\(name)] sas6 mismatch")
            XCTAssertEqual(actual.count, 6, "[\(name)] sas6 must always be exactly 6 characters")
            XCTAssertTrue(actual.allSatisfy { $0.isNumber }, "[\(name)] sas6 must be all decimal digits")
        }
    }

    func testTailBytesIgnored() throws {
        let root = try Vectors.loadJSON("sas/sas_vectors.json") as! [String: Any] // swiftlint:disable:this force_cast
        let propertyVectors = root.array("property_vectors") as! [[String: Any]] // swiftlint:disable:this force_cast
        let vector = propertyVectors.first { $0.str("name") == "tail-bytes-ignored" }! // swiftlint:disable:this force_unwrapping
        let input = vector.dict("input")

        let a = Sas.deriveSas6(hexToBytes(input.str("exporter_output_hex_a")))
        let b = Sas.deriveSas6(hexToBytes(input.str("exporter_output_hex_b")))
        XCTAssertEqual(a, b, "differing bytes 4..31 must not change sas6")
    }

    func testOutputIsSixCharacters() throws {
        let root = try Vectors.loadJSON("sas/sas_vectors.json") as! [String: Any] // swiftlint:disable:this force_cast
        let valueVectors = root.array("value_vectors") as! [[String: Any]] // swiftlint:disable:this force_cast

        for vector in valueVectors {
            let hex = vector.dict("input").str("exporter_output_hex")
            let sas6 = Sas.deriveSas6(hexToBytes(hex))
            XCTAssertEqual(sas6.count, 6, "[\(vector.str("name"))] produced a non-6-digit sas6")
            XCTAssertTrue(sas6.allSatisfy { $0.isNumber })
        }
    }
}
