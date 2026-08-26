import Foundation
import XCTest
@testable import RideLinkCore

/// protocol/vectors/envelope/envelope_vectors.json, PROTOCOL §2, TEST_PLAN §2/§11.
final class EnvelopeCodecVectorTests: XCTestCase {
    func testEnvelopeVectors() throws {
        let root = try Vectors.loadJSON("envelope/envelope_vectors.json") as! [String: Any] // swiftlint:disable:this force_cast
        let vectors = root.array("vectors") as! [[String: Any]] // swiftlint:disable:this force_cast

        for vector in vectors {
            let name = vector.str("name")
            try runVector(name: name, vector: vector)
        }
    }

    private func runVector(name: String, vector: [String: Any]) throws {
        let input = vector.dict("input")
        let expected = vector.dict("expected")

        let result: DecodeResult
        if let bodyJson = input.strOpt("body_json") {
            result = EnvelopeCodec.decode(bodyJson)
        } else if let padToBytes = input.intOpt("pad_to_bytes") {
            let bytes = try buildPaddedFrame(padToBytes: padToBytes, template: input.dict("template"))
            if let expectedLen = expected.intOpt("encoded_byte_length") {
                XCTAssertEqual(bytes.count, expectedLen, "[\(name)] padded fixture must land exactly on the target length")
            }
            result = EnvelopeCodec.decode(Array(bytes))
        } else {
            XCTFail("[\(name)] vector input has neither body_json nor pad_to_bytes")
            return
        }

        let expectDecodes = expected.boolVal("decodes")
        switch result {
        case .success(let envelope, let versionOk):
            XCTAssertTrue(expectDecodes, "[\(name)] expected decode failure but got success")
            if let expectedVersionOk = expected.boolOpt("version_ok") {
                XCTAssertEqual(versionOk, expectedVersionOk, "[\(name)] version_ok mismatch")
            }
            if let expectedDecoded = expected.dictOpt("decoded") {
                assertDecodedMatches(name: name, expectedDecoded: expectedDecoded, envelope: envelope)
            }
        case .failure(let errorCode):
            XCTAssertTrue(!expectDecodes, "[\(name)] expected decode success but got failure(\(errorCode))")
            if let expectedCode = expected.strOpt("error_code") {
                XCTAssertEqual(errorCode, expectedCode, "[\(name)] error_code mismatch")
            }
        }
    }

    private func assertDecodedMatches(name: String, expectedDecoded: [String: Any], envelope: Envelope) {
        if let v = expectedDecoded.intOpt("v") { XCTAssertEqual(v, envelope.v, "[\(name)] v mismatch") }
        if let type = expectedDecoded.strOpt("type") { XCTAssertEqual(type, envelope.type, "[\(name)] type mismatch") }
        if let sessionId = expectedDecoded.strOpt("session_id") {
            XCTAssertEqual(sessionId, envelope.sessionId, "[\(name)] session_id mismatch")
        }
        if let senderId = expectedDecoded.strOpt("sender_id") {
            XCTAssertEqual(senderId, envelope.senderId, "[\(name)] sender_id mismatch")
        }
        if let msgId = expectedDecoded.strOpt("msg_id") { XCTAssertEqual(msgId, envelope.msgId, "[\(name)] msg_id mismatch") }
        if let seq = expectedDecoded.intOpt("seq") { XCTAssertEqual(Int64(seq), envelope.seq, "[\(name)] seq mismatch") }
        if let sentAt = expectedDecoded.intOpt("sent_at_mono_us") {
            XCTAssertEqual(Int64(sentAt), envelope.sentAtMonoUs, "[\(name)] sent_at_mono_us mismatch")
        }
        if let requiresAck = expectedDecoded.boolOpt("requires_ack") {
            XCTAssertEqual(requiresAck, envelope.requiresAck, "[\(name)] requires_ack mismatch")
        }
        if let expectedPayload = expectedDecoded.dictOpt("payload") {
            for (key, _) in expectedPayload {
                XCTAssertNotNil(envelope.payload[key], "[\(name)] payload.\(key) missing")
            }
        }
    }

    /// Implements the padding recipe documented in protocol/README.md.
    private func buildPaddedFrame(padToBytes: Int, template: [String: Any]) throws -> Data {
        var payload = template.dict("payload")
        XCTAssertEqual(payload.strOpt("pad") ?? "", "", "template.payload.pad must start as the empty string")

        let baseData = try JSONSerialization.data(withJSONObject: template, options: [])
        let baseLen = baseData.count
        let padLen = padToBytes - baseLen
        XCTAssertTrue(padLen >= 0, "pad_to_bytes (\(padToBytes)) is smaller than the unpadded template (\(baseLen))")

        payload["pad"] = String(repeating: "a", count: max(padLen, 0))
        var paddedTemplate = template
        paddedTemplate["payload"] = payload

        return try JSONSerialization.data(withJSONObject: paddedTemplate, options: [])
    }
}
