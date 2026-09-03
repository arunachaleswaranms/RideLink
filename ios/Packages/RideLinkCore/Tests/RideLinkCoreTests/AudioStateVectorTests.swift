import XCTest

@testable import RideLinkCore

/// Runs `protocol/vectors/audio-state/audio_state_vectors.json` against `AudioStateCodec`,
/// `AudioStatePublisher` and `AudioStateInbox`.
///
/// The mirror is `com.ridelink.core.protocol.AudioStateVectorTest`, running the **same file**. What it
/// pins is the whole of PROTOCOL §4.4: the exact field set, every bound, ADR-016 Amendment A1's
/// `media_quality` derivation, the monotonic `revision` on the sending side, and the
/// drop-anything-not-greater rule on the receiving side.
///
/// It also pins ADR-016's **privacy** rule mechanically: no platform audio vocabulary appears anywhere
/// in the file's data, so a future field that leaked `A2DP` or a headset model would fail a laptop unit
/// test rather than reach a peer.
final class AudioStateVectorTests: XCTestCase {
    private func document() throws -> [String: Any] {
        // swiftlint:disable:next force_cast
        try Vectors.loadJSON("audio-state/audio_state_vectors.json") as! [String: Any]
    }

    // MARK: - the field set

    /// §4.4 has eleven fields and this codec must produce exactly those eleven. The assertion is against
    /// the shared file rather than against a Swift array, so adding a field on one platform only cannot
    /// pass.
    func testTheEncodedFieldSetIsExactlyTheSharedFilesInOrder() throws {
        let doc = try document()
        // swiftlint:disable:next force_cast
        let expected = doc.array("field_order").map { $0 as! String }
        XCTAssertEqual(expected, AudioStateCodec.fields, "field order")
        XCTAssertEqual(Set(expected), Set(AudioStateCodec.encode(sampleMessage()).keys), "encoded key set")
    }

    /// ADR-016's privacy rule, enforced against the vector data itself. `_scanned_keys` names the data
    /// groups; `_comment` and `_invariants` are excluded because they must *name* the forbidden
    /// vocabulary in order to forbid it, and a check that tripped on its own explanation would be
    /// useless.
    func testNoPlatformAudioVocabularyAppearsAnywhereInTheSharedFilesData() throws {
        let doc = try document()
        // swiftlint:disable:next force_cast
        let forbidden = doc.array("_forbidden_substrings").map { $0 as! String }
        // swiftlint:disable:next force_cast
        let scannedKeys = doc.array("_scanned_keys").map { $0 as! String }
        XCTAssertFalse(forbidden.isEmpty, "the file must declare what is forbidden")
        XCTAssertFalse(scannedKeys.isEmpty, "the file must declare what to scan")

        var scanned: [String: Any] = [:]
        for key in scannedKeys { scanned[key] = doc[key] }
        let data = try JSONSerialization.data(withJSONObject: scanned, options: [.sortedKeys])
        let text = String(decoding: data, as: UTF8.self).lowercased()
        for needle in forbidden {
            XCTAssertFalse(text.contains(needle), "forbidden platform vocabulary '\(needle)' reached the vectors")
        }
    }

    /// The bounds both platforms enforce are the file's, not each platform's own opinion.
    func testTheVectorFilesBoundsMatchThisPlatformsConstants() throws {
        let doc = try document()
        let bounds = doc.dict("bounds")
        XCTAssertEqual(bounds.int64("MAX_SAMPLE_RATE_HZ"), AudioStateCodec.maxSampleRateHz)
        XCTAssertEqual(bounds.int64("MAX_REVISION"), AudioStateCodec.maxRevision)
    }

    /// Every value in §4.3.1's closed vocabularies must round-trip through this platform's enums.
    func testEveryVocabularyValueInTheSharedFileRoundTrips() throws {
        let doc = try document()
        let vocabulary = doc.dict("vocabulary")
        for value in strings(vocabulary, "endpoint_class") {
            XCTAssertEqual(value, EndpointClass.parse(value).wire, "endpoint_class \(value)")
        }
        for value in strings(vocabulary, "profile") {
            XCTAssertEqual(value, AudioProfile.parse(value).wire, "profile \(value)")
        }
        for value in strings(vocabulary, "route_state") {
            XCTAssertEqual(value, RouteState.parse(value).wire, "route_state \(value)")
        }
        for value in strings(vocabulary, "intercom_mode") {
            XCTAssertEqual(value, IntercomMode.parse(value).wire, "intercom_mode \(value)")
        }
        for value in strings(vocabulary, "confidence") {
            XCTAssertEqual(value, AudioConfidence.parse(value).wire, "confidence \(value)")
        }
    }

    // MARK: - encode

    func testEveryEncodeRowOfTheSharedFileHolds() throws {
        let doc = try document()
        var checked = 0
        for element in doc.array("encode") {
            // swiftlint:disable:next force_cast
            let row = element as! [String: Any]
            let name = row.str("name")
            let message = AudioStateMessage.from(
                revision: row.int64("revision"),
                snapshot: snapshot(row.dict("snapshot")),
                intercomMode: IntercomMode.parse(row.str("intercom_mode"))
            )
            let expect = row.dict("expect")
            XCTAssertEqual(self.message(expect.dict("message")), message, "row \(name) message")
            XCTAssertEqual(
                canonical(expect.dict("payload")),
                canonical(AudioStateCodec.encode(message)),
                "row \(name) payload"
            )
            // A nullable field that is absent must be an explicit JSON null, not a missing key.
            if let nullables = expect["explicit_nulls"] as? [Any] {
                let encoded = AudioStateCodec.encode(message)
                for nullable in nullables {
                    // swiftlint:disable:next force_cast
                    let key = nullable as! String
                    XCTAssertEqual(encoded[key], JSONValue.null, "row \(name) must encode \(key) as an explicit null")
                }
            }
            checked += 1
        }
        XCTAssertGreaterThan(checked, 0, "the file must contain encode rows")
    }

    // MARK: - parse

    func testEveryParseRowOfTheSharedFileHolds() throws {
        let doc = try document()
        var parsed = 0
        var rejected = 0
        for element in doc.array("parse") {
            // swiftlint:disable:next force_cast
            let row = element as! [String: Any]
            let name = row.str("name")
            let result = AudioStateCodec.parse(payload(row.dict("payload")))
            let expect = row.dict("expect")
            if let expectedMessage = expect["parsed"] as? [String: Any] {
                guard case .parsed(let actual) = result else {
                    XCTFail("row \(name) expected a parse, got \(result)")
                    continue
                }
                XCTAssertEqual(message(expectedMessage), actual, "row \(name) parsed message")
                parsed += 1
            } else {
                let reason = AudioStateRejection(rawValue: expect.str("rejected"))!
                guard case .rejected(let actual) = result else {
                    XCTFail("row \(name) expected a rejection, got \(result)")
                    continue
                }
                XCTAssertEqual(reason, actual, "row \(name) rejection reason")
                rejected += 1
            }
        }
        XCTAssertGreaterThan(parsed, 0, "the file must contain parses")
        XCTAssertGreaterThan(rejected, 0, "the file must contain rejections")
    }

    /// The parser must be total. §4.4 carries no "end the connection" outcome — a malformed
    /// `AUDIO_STATE` is a drop, exactly as for a malformed `VOICE_*` (§7.4) or `PING` (§6) — so it may
    /// never trap on any payload a peer can send.
    func testParsingNeverTrapsWhateverThePayload() {
        let hostile: [[String: JSONValue]] = [
            [:],
            ["revision": .string("nope")],
            ["revision": .number(1.5)],
            ["revision": .null],
            ["revision": .number(1), "endpoint_class": .array([]), "microphone_open": .object([:])],
            ["revision": .number(.infinity)],
            ["revision": .number(.nan)],
            ["revision": .number(1), "effective_output_sample_rate_hz": .number(.infinity)],
        ]
        for payload in hostile {
            // The assertion is that this line returns rather than traps.
            _ = AudioStateCodec.parse(payload)
        }
    }

    // MARK: - media_quality

    func testMediaQualityIsDerivedExactlyAsTheSharedFileSays() throws {
        let doc = try document()
        var checked = 0
        for element in doc.array("media_quality") {
            // swiftlint:disable:next force_cast
            let row = element as! [String: Any]
            let profile = AudioProfile.parse(row.str("effective_output_profile"))
            let expected = MediaQuality.parse(row.str("expect"))
            XCTAssertEqual(
                expected,
                AudioRouteSnapshot(effectiveOutputProfile: profile).mediaQuality,
                "row \(row.str("name"))"
            )
            checked += 1
        }
        XCTAssertEqual(AudioProfile.allCases.count, checked, "every profile value must be covered")
    }

    // MARK: - publisher

    func testEveryPublisherRowOfTheSharedFileHolds() throws {
        let doc = try document()
        for element in doc.array("publisher") {
            // swiftlint:disable:next force_cast
            let row = element as! [String: Any]
            let name = row.str("name")
            var publisher = AudioStatePublisher()
            var lastRevision: Int64 = 0
            for (index, stepElement) in row.array("steps").enumerated() {
                // swiftlint:disable:next force_cast
                let step = stepElement as! [String: Any]
                let snap = snapshot(step.dict("snapshot"))
                let mode = IntercomMode.parse(step.str("intercom_mode"))
                let produced = step.boolVal("force")
                    ? publisher.forceNext(snapshot: snap, intercomMode: mode)
                    : publisher.next(snapshot: snap, intercomMode: mode)
                if let expected = step.int64Opt("expect_revision") {
                    XCTAssertEqual(expected, produced?.revision, "\(name) step \(index) revision")
                    XCTAssertGreaterThan(expected, lastRevision, "\(name) step \(index) must strictly increase")
                    lastRevision = expected
                } else {
                    XCTAssertNil(produced, "\(name) step \(index) expected no publish")
                    XCTAssertEqual(lastRevision, publisher.currentRevision, "\(name) step \(index) moved the revision")
                }
            }
        }
    }

    /// A new control session restarts the numbering: §4.4's revision is per sender per session.
    func testResetForNewSessionRestartsTheRevision() {
        var publisher = AudioStatePublisher()
        let snap = AudioRouteSnapshot(endpointClass: .bluetooth)
        XCTAssertEqual(1, publisher.next(snapshot: snap, intercomMode: .ptt)?.revision)
        publisher.resetForNewSession()
        XCTAssertNil(publisher.published, "a reset forgets what was published")
        XCTAssertEqual(1, publisher.next(snapshot: snap, intercomMode: .ptt)?.revision, "numbering starts again")
    }

    // MARK: - inbox

    func testEveryInboxRowOfTheSharedFileHolds() throws {
        let doc = try document()
        for element in doc.array("inbox") {
            // swiftlint:disable:next force_cast
            let row = element as! [String: Any]
            let name = row.str("name")
            var inbox = AudioStateInbox()
            // swiftlint:disable:next force_cast
            let offers = row.array("offer").map { ($0 as! NSNumber).int64Value }
            // swiftlint:disable:next force_cast
            let expected = row.array("expect_accepted").map { ($0 as! NSNumber).boolValue }
            XCTAssertEqual(offers.count, expected.count, "\(name) malformed row")
            for (index, revision) in offers.enumerated() {
                XCTAssertEqual(
                    expected[index],
                    inbox.accept(sampleMessage(revision: revision)),
                    "\(name) offer \(index) (revision \(revision))"
                )
            }
            XCTAssertEqual(row.int64("expect_current"), inbox.current?.revision, "\(name) final revision")
            XCTAssertEqual(expected.filter { !$0 }.count, inbox.droppedStale, "\(name) dropped count")
        }
    }

    // MARK: - decoding

    private func sampleMessage(revision: Int64 = 1) -> AudioStateMessage {
        AudioStateMessage(
            revision: revision,
            endpointClass: .bluetooth,
            microphoneOpen: true,
            effectiveOutputProfile: .duplexWideband,
            effectiveInputProfile: .duplexWideband,
            effectiveOutputSampleRateHz: 16_000,
            effectiveInputSampleRateHz: 16_000,
            mediaQuality: .reduced,
            routeState: .stable,
            intercomMode: .ptt,
            confidence: .assumed
        )
    }

    private func snapshot(_ spec: [String: Any]) -> AudioRouteSnapshot {
        AudioRouteSnapshot(
            endpointClass: EndpointClass.parse(spec.str("endpoint_class")),
            microphoneOpen: spec.boolVal("microphone_open"),
            effectiveOutputProfile: AudioProfile.parse(spec.str("effective_output_profile")),
            effectiveInputProfile: AudioProfile.parse(spec.str("effective_input_profile")),
            effectiveOutputSampleRateHz: spec.intOpt("effective_output_sample_rate_hz"),
            effectiveInputSampleRateHz: spec.intOpt("effective_input_sample_rate_hz"),
            routeState: RouteState.parse(spec.str("route_state")),
            profileCoupling: ProfileCoupling.parse(spec.str("profile_coupling")),
            confidence: AudioConfidence.parse(spec.str("confidence")),
            interrupted: spec.boolVal("interrupted"),
            lastChangeReason: changeReason(spec.str("last_change_reason")),
            lastTransitionDurationUs: spec.int64Opt("last_transition_duration_us")
        )
    }

    private func message(_ spec: [String: Any]) -> AudioStateMessage {
        AudioStateMessage(
            revision: spec.int64("revision"),
            endpointClass: EndpointClass.parse(spec.str("endpoint_class")),
            microphoneOpen: spec.boolVal("microphone_open"),
            effectiveOutputProfile: AudioProfile.parse(spec.str("effective_output_profile")),
            effectiveInputProfile: AudioProfile.parse(spec.str("effective_input_profile")),
            effectiveOutputSampleRateHz: spec.intOpt("effective_output_sample_rate_hz"),
            effectiveInputSampleRateHz: spec.intOpt("effective_input_sample_rate_hz"),
            mediaQuality: MediaQuality.parse(spec.str("media_quality")),
            routeState: RouteState.parse(spec.str("route_state")),
            intercomMode: IntercomMode.parse(spec.str("intercom_mode")),
            confidence: AudioConfidence.parse(spec.str("confidence"))
        )
    }

    /// The vectors name change reasons in upper case, matching the Kotlin enum constant names.
    private func changeReason(_ raw: String) -> AudioRouteChangeReason {
        AudioRouteChangeReason.allCases.first { $0.rawValue.uppercased() == raw } ?? .unknown
    }

    private func strings(_ dict: [String: Any], _ key: String) -> [String] {
        // swiftlint:disable:next force_cast
        dict.array(key).map { $0 as! String }
    }

    /// Converts a `JSONSerialization` tree into the `JSONValue` the codec reads.
    ///
    /// `NSNumber` is checked against `CFBooleanGetTypeID` rather than by a cast, because
    /// `JSONSerialization` represents `true` as an `NSNumber` too — and the whole point of several parse
    /// rows is that a boolean and a number are *different* JSON types to this parser.
    private func payload(_ spec: [String: Any]) -> [String: JSONValue] {
        spec.mapValues(jsonValue)
    }

    private func jsonValue(_ any: Any) -> JSONValue {
        if any is NSNull { return .null }
        if let number = any as? NSNumber {
            if CFGetTypeID(number) == CFBooleanGetTypeID() { return .bool(number.boolValue) }
            return .number(number.doubleValue)
        }
        if let string = any as? String { return .string(string) }
        if let array = any as? [Any] { return .array(array.map(jsonValue)) }
        if let object = any as? [String: Any] { return .object(object.mapValues(jsonValue)) }
        preconditionFailure("unrepresentable vector value: \(any)")
    }

    /// Both sides of an encode comparison reduced to `key -> string` so a JSON number and a Swift `Int64`
    /// compare by value rather than by representation.
    private func canonical(_ spec: [String: Any]) -> [String: String] {
        var out: [String: String] = [:]
        for (key, value) in spec {
            if value is NSNull { continue }
            if let number = value as? NSNumber {
                out[key] = CFGetTypeID(number) == CFBooleanGetTypeID()
                    ? String(number.boolValue)
                    : String(number.int64Value)
            } else if let string = value as? String {
                out[key] = string
            }
        }
        return out
    }

    private func canonical(_ encoded: [String: JSONValue]) -> [String: String] {
        var out: [String: String] = [:]
        for (key, value) in encoded {
            switch value {
            case .null: continue
            case .bool(let bool): out[key] = String(bool)
            case .number(let number): out[key] = String(Int64(number))
            case .string(let string): out[key] = string
            case .array, .object: preconditionFailure("AUDIO_STATE encodes no nested values")
            }
        }
        return out
    }
}
