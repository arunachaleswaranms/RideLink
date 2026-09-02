import Foundation
import XCTest
@testable import RideLinkCore

/// Runs `protocol/vectors/voice-signal/voice_signal_vectors.json` against `VoiceSignalCodec`.
///
/// The mirror is `com.ridelink.core.protocol.VoiceSignalVectorTest`, running the **same file**. A bound
/// enforced on one platform and not the other — an oversize SDP accepted by the iPhone and refused by
/// the OnePlus, say — would otherwise be invisible until two phones met on a ride (CLAUDE.md "Shared
/// protocol vectors — not optional").
final class VoiceSignalVectorTests: XCTestCase {
    /// A floor, not an equality: the generator is expected to grow when a device finds a bug and a
    /// vector is added for it (CLAUDE.md's regression discipline). A *shrunken* file is the bug this
    /// guards against.
    private let expectedMinimumRows = 70

    private func document() throws -> [String: Any] {
        // swiftlint:disable:next force_cast
        try Vectors.loadJSON("voice-signal/voice_signal_vectors.json") as! [String: Any]
    }

    func testEveryRowOfTheSharedVoiceSignalTableHolds() throws {
        let doc = try document()
        var checked = 0
        for element in doc.array("rows") {
            guard let row = element as? [String: Any] else { return XCTFail("row is not an object") }
            let name = row.str("name")
            let result = VoiceSignalCodec.parse(type: row.str("type"), payload: jsonPayload(row.dict("payload")))
            let expect = row.dict("expect")

            if let parsedSpec = expect.dictOpt("parsed") {
                guard case .parsed(let signal) = result else {
                    return XCTFail("vector \(name) expected a parse, got \(result)")
                }
                assertSignal(parsedSpec, signal, name)
            } else {
                guard case .rejected(let reason) = result else {
                    return XCTFail("vector \(name) expected a rejection, got \(result)")
                }
                XCTAssertEqual(reason.rawValue, expect.str("rejected"), "vector \(name) rejection reason")
            }
            checked += 1
        }
        XCTAssertGreaterThanOrEqual(checked, expectedMinimumRows, "expected at least \(expectedMinimumRows) rows")
    }

    /// The bounds are transcribed independently in the generator and in `VoiceBounds`. Asserting they
    /// agree is the point: a bound changed in one place and not the other would otherwise only show up
    /// as a mystery rejection on a device.
    func testTheVectorFilesBoundsMatchThisPlatformsConstants() throws {
        let bounds = try document().dict("bounds")
        XCTAssertEqual(bounds.int("MAX_VOICE_SDP_BYTES"), VoiceBounds.maxSdpBytes)
        XCTAssertEqual(bounds.int("MAX_VOICE_CANDIDATE_BYTES"), VoiceBounds.maxCandidateBytes)
        XCTAssertEqual(bounds.int("MAX_VOICE_SDP_MID_BYTES"), VoiceBounds.maxSdpMidBytes)
        XCTAssertEqual(bounds.int("MAX_VOICE_MLINE_INDEX"), VoiceBounds.maxMlineIndex)
        XCTAssertEqual(bounds.int("MAX_QUEUED_VOICE_CANDIDATES"), VoiceBounds.maxQueuedCandidates)
    }

    /// A property the row-by-row assertions cannot state: whatever a peer sends, parsing it is
    /// **total**. PROTOCOL §7.4 requires a malformed `VOICE_*` frame to be dropped without ending the
    /// control connection, and the way that is guaranteed here is that this function cannot trap —
    /// which on this platform is the sharper risk, because a `precondition` on wire input is a
    /// remotely triggerable crash rather than a catchable error.
    func testParsingNeverTrapsWhateverThePayload() {
        let hostile: [[String: JSONValue]] = [
            [:],
            ["voice_session_id": .null],
            ["sdp": .null],
            ["sdp_mline_index": .number(.greatestFiniteMagnitude)],
            ["sdp_mline_index": .number(.nan)],
            ["sdp_mline_index": .number(.infinity)],
            ["sdp_mline_index": .number(-.infinity)],
            ["mic_muted": .null, "state": .null, "mode": .null],
            ["candidate": .string(" \u{FFFD}")],
            ["voice_session_id": .array([.string("x")])],
        ]
        for type in VoiceMessageTypes.all.union(["VOICE_UNKNOWN", ""]) {
            for payload in hostile {
                // No assertion on the outcome: the assertion is that this line returns at all.
                _ = VoiceSignalCodec.parse(type: type, payload: payload)
            }
        }
    }

    func testFabricatedIdsAreNeverTreatedAsValidWhenMalformed() {
        // A small guard on the *file*, not on the parser: a future edit that accidentally made the
        // uppercase-hex row lowercase would silently stop testing the rule it exists for.
        XCTAssertNil(VoiceSessionId.parse("5E2A9C40B7F13D86E0A4C95B28F7D613"))
        XCTAssertNil(VoiceSessionId.parse(""))
        XCTAssertNil(VoiceSessionId.parse("5e2a9c40b7f13d86e0a4c95b28f7d61"))
    }

    // MARK: - helpers

    private func assertSignal(_ spec: [String: Any], _ actual: VoiceSignal, _ name: String) {
        XCTAssertEqual(spec.str("kind"), actual.kindName, "vector \(name) kind")
        XCTAssertEqual(spec.strOpt("voice_session_id"), actual.voiceSessionId?.value, "vector \(name) id")
        switch actual {
        case .offer(_, let sdp), .answer(_, let sdp):
            XCTAssertEqual(spec.str("sdp"), sdp, "vector \(name) sdp")
        case .iceCandidate(_, let candidate, let mid, let index):
            XCTAssertEqual(spec.str("candidate"), candidate, "vector \(name) candidate")
            XCTAssertEqual(spec.strOpt("sdp_mid"), mid, "vector \(name) sdp_mid")
            XCTAssertEqual(spec.int("sdp_mline_index"), index, "vector \(name) mline index")
        case .state(_, let state, let micMuted, let mode):
            // The vectors name states and modes in upper case, matching the Kotlin enum constant
            // names, so both platforms read one file rather than two spellings of one table.
            XCTAssertEqual(spec.str("state"), state.rawValue.uppercased(), "vector \(name) state")
            XCTAssertEqual(spec.boolVal("mic_muted"), micMuted, "vector \(name) mic_muted")
            XCTAssertEqual(spec.str("mode"), mode.rawValue.uppercased(), "vector \(name) mode")
        }
    }

    /// Converts `JSONSerialization`'s `Any` tree into the `JSONValue` the codec takes.
    ///
    /// `NSNull` must survive as `.null` rather than becoming an absent key: PROTOCOL §7.4 makes
    /// `sdp_mid` and `VOICE_STATE.voice_session_id` nullable, and several vectors distinguish "sent as
    /// null" from "not sent at all". Collapsing the two would make those rows untestable.
    private func jsonPayload(_ raw: [String: Any]) -> [String: JSONValue] {
        raw.mapValues(jsonValue)
    }

    private func jsonValue(_ raw: Any) -> JSONValue {
        switch raw {
        case is NSNull: return .null
        case let value as String: return .string(value)
        case let value as NSNumber:
            // `NSNumber` does not distinguish a JSON `true` from a `1` by type, so the ObjC type
            // encoding is the only way to tell — and the codec's whole point is that a numeric
            // `mic_muted` is a *wrong type*, not a truthy boolean.
            if CFGetTypeID(value) == CFBooleanGetTypeID() { return .bool(value.boolValue) }
            return .number(value.doubleValue)
        case let value as [Any]: return .array(value.map(jsonValue))
        case let value as [String: Any]: return .object(value.mapValues(jsonValue))
        default: return .null
        }
    }
}
