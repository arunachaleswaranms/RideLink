import XCTest

@testable import RideLinkCore

/// Runs `protocol/vectors/intercom/intercom_vectors.json` against `IntercomTransmission`.
///
/// The mirror is `com.ridelink.core.audiopolicy.IntercomVectorTest`, running the **same file**. What
/// the table encodes is exactly what would otherwise be discovered on a ride: that PTT gates
/// transmission and never the hardware, that mute and an interruption both win over an open gate, that
/// a policy switch cannot inherit a button nobody is holding, and that full duplex remains the no-gate
/// case.
final class IntercomVectorTests: XCTestCase {
    private let expectedMinimumRows = 58

    private func document() throws -> [String: Any] {
        // swiftlint:disable:next force_cast
        try Vectors.loadJSON("intercom/intercom_vectors.json") as! [String: Any]
    }

    func testEveryRowOfTheSharedIntercomTableHolds() throws {
        let doc = try document()
        var checked = 0
        for element in doc.array("rows") {
            // swiftlint:disable:next force_cast
            let row = element as! [String: Any]
            let name = row.str("name")
            let before = state(row.dict("state"))
            let outcome = IntercomTransmission.reduce(state: before, input: input(row.dict("input")))
            let expect = row.dict("expect")

            XCTAssertEqual(state(expect.dict("state")), outcome.state, "vector \(name) resulting state")
            XCTAssertEqual(
                expect.array("actions").map { label($0 as! [String: Any]) }, // swiftlint:disable:this force_cast
                outcome.actions.map(label),
                "vector \(name) actions"
            )
            XCTAssertEqual(expect.boolVal("transmitting"), outcome.state.transmitting, "vector \(name) transmitting")
            checked += 1
        }
        XCTAssertGreaterThanOrEqual(checked, expectedMinimumRows, "expected at least \(expectedMinimumRows) rows")
    }

    /// The five REQUIREMENTS §8 modes, field for field, against the file — including the two wire
    /// vocabularies, which differ by one value on purpose (PROTOCOL §4.4 versus §7.4, ADR-021 §3).
    func testTheFiveModePresetsMatchTheSharedFileExactly() throws {
        let doc = try document()
        var checked = 0
        for element in doc.array("presets") {
            // swiftlint:disable:next force_cast
            let spec = element as! [String: Any]
            let id = IntercomModeId(rawValue: spec.str("id"))!
            let policy = IntercomPolicy.byId(id)!

            XCTAssertEqual(spec.boolVal("mic_always_open"), policy.micAlwaysOpen, "\(id) mic_always_open")
            XCTAssertEqual(gate(spec.dict("gate")), policy.gate, "\(id) gate")
            XCTAssertEqual(onSpeech(spec.dict("on_speech")), policy.onSpeech, "\(id) on_speech")
            XCTAssertEqual(
                musicQualityPriority(spec.str("music_quality_priority")),
                policy.musicQualityPriority,
                "\(id) music_quality_priority"
            )
            XCTAssertEqual(voiceMode(spec.str("voice_wire_mode")), policy.voiceWireMode, "\(id) voice_wire_mode")
            XCTAssertEqual(
                intercomMode(spec.str("intercom_wire_mode")),
                policy.intercomWireMode,
                "\(id) intercom_wire_mode"
            )
            XCTAssertEqual(spec.boolVal("full_duplex"), policy.fullDuplex, "\(id) full_duplex")
            XCTAssertEqual(spec.boolVal("intercom_enabled"), policy.intercomEnabled, "\(id) intercom_enabled")
            checked += 1
        }
        XCTAssertEqual(IntercomPolicy.all.count, checked, "every preset must appear in the shared file")
    }

    /// **The default is an architecture default, not a measurement.** ARCHITECTURE §6.3 and ADR-008 §4
    /// name Mode C until `docs/PHASE0_RESULTS.md` is filled in, so this asserts the file and the code
    /// agree about which mode that is — and the assertion is what changes when a real measurement
    /// exists, exactly as the route mappers' `confidence: assumed` assertions are.
    func testTheDefaultPolicyIsTheOneTheSharedFileNames() throws {
        let doc = try document()
        XCTAssertEqual(
            IntercomModeId(rawValue: doc.str("default_policy_id")),
            IntercomPolicy.default.id,
            "the default policy must match the shared file"
        )
        XCTAssertEqual(IntercomModeId.modeC, IntercomPolicy.default.id, "ARCHITECTURE §6.3's documented default")
    }

    /// The VOX starting points are shared too, so a tuning change cannot land on one platform only.
    func testTheVoxDefaultsMatchTheSharedFile() throws {
        let doc = try document()
        let defaults = doc.dict("vox_defaults")
        guard case .vox(let thresholdDbfs, let hangoverMs) = IntercomPolicy.modeB.gate else {
            return XCTFail("Mode B must be a VOX gate")
        }
        XCTAssertEqual(defaults.doubleVal("threshold_dbfs"), thresholdDbfs, "vox threshold")
        XCTAssertEqual(defaults.int64("hangover_ms"), hangoverMs, "vox hangover")
    }

    /// ARCHITECTURE §6.3's central invariant, asserted as a property of the whole file rather than row
    /// by row: **the transmission gate has no capture action to emit.** TEST_PLAN A-10 is the hardware
    /// test of the same property; this is the one that can run on a laptop.
    func testNoActionInTheWholeFileTouchesTheCaptureDevice() throws {
        let doc = try document()
        let permitted: Set<String> = ["SetTransmitting", "AnnounceVoiceMode", "PublishAudioState"]
        for element in doc.array("rows") {
            // swiftlint:disable:next force_cast
            let row = element as! [String: Any]
            for action in row.dict("expect").array("actions") {
                // swiftlint:disable:next force_cast
                let kind = (action as! [String: Any]).str("kind")
                XCTAssertTrue(
                    permitted.contains(kind),
                    "row \(row.str("name")) emitted a non-transmission action"
                )
            }
        }
    }

    /// Transmission can never precede an open capture path, from any row (ARCHITECTURE §6.4).
    func testNoRowTransmitsWithoutAnOpenCapturePathAMuteOrThroughAnInterruption() throws {
        let doc = try document()
        for element in doc.array("rows") {
            // swiftlint:disable:next force_cast
            let row = element as! [String: Any]
            let after = state(row.dict("expect").dict("state"))
            guard after.transmitting else { continue }
            XCTAssertTrue(after.captureOpen, "row \(row.str("name")) transmits with capture closed")
            XCTAssertFalse(after.userMuted, "row \(row.str("name")) transmits while muted")
            XCTAssertFalse(after.interrupted, "row \(row.str("name")) transmits through an interruption")
            XCTAssertNotEqual(after.policy.gate, .disabled, "row \(row.str("name")) transmits in Mode E")
        }
    }

    /// A `SetTransmitting` action is a diff: present iff the value changed, and equal to the new value.
    func testEverySetTransmittingActionAgreesWithTheResultingStateAndIsEmittedOnlyOnChange() throws {
        let doc = try document()
        for element in doc.array("rows") {
            // swiftlint:disable:next force_cast
            let row = element as! [String: Any]
            let before = state(row.dict("state"))
            let expect = row.dict("expect")
            let after = state(expect.dict("state"))
            let emitted = expect.array("actions")
                .compactMap { $0 as? [String: Any] }
                .filter { $0.str("kind") == "SetTransmitting" }
            if before.transmitting == after.transmitting {
                XCTAssertTrue(emitted.isEmpty, "row \(row.str("name")) restated an unchanged value")
            } else {
                XCTAssertEqual(emitted.count, 1, "row \(row.str("name")) must emit exactly one SetTransmitting")
                XCTAssertEqual(
                    after.transmitting,
                    emitted.first?.boolVal("transmitting"),
                    "row \(row.str("name")) SetTransmitting disagrees with the state"
                )
            }
        }
    }

    /// A policy switch must never inherit a gate that was open under the previous policy.
    func testNoPolicySwitchLeavesAHeldButtonOrAnOpenVoxGate() throws {
        let doc = try document()
        var covered = 0
        for element in doc.array("rows") {
            // swiftlint:disable:next force_cast
            let row = element as! [String: Any]
            guard row.dict("input").str("kind") == "PolicySelected" else { continue }
            let after = state(row.dict("expect").dict("state"))
            XCTAssertFalse(after.pttHeld, "row \(row.str("name")) inherited a held PTT button")
            XCTAssertFalse(after.voxOpen, "row \(row.str("name")) inherited an open VOX gate")
            XCTAssertNil(after.voxHangoverUntilMonoUs, "row \(row.str("name")) inherited a hangover deadline")
            covered += 1
        }
        XCTAssertGreaterThan(covered, 0, "the file must contain PolicySelected rows for this to mean anything")
    }

    // MARK: - vector decoding

    private func state(_ spec: [String: Any]) -> TransmissionState {
        TransmissionState(
            policy: IntercomPolicy.byId(IntercomModeId(rawValue: spec.str("policy_id"))!)!,
            captureOpen: spec.boolVal("capture_open"),
            userMuted: spec.boolVal("user_muted"),
            pttHeld: spec.boolVal("ptt_held"),
            voxOpen: spec.boolVal("vox_open"),
            voxHangoverUntilMonoUs: spec.int64Opt("vox_hangover_until_mono_us"),
            interrupted: spec.boolVal("interrupted")
        )
    }

    private func input(_ spec: [String: Any]) -> IntercomInput {
        switch spec.str("kind") {
        case "PolicySelected":
            return .policySelected(IntercomPolicy.byId(IntercomModeId(rawValue: spec.str("policy_id"))!)!)
        case "UserMuted":
            return .userMuted(spec.boolVal("muted"))
        case "PttHeld":
            return .pttHeld(spec.boolVal("held"))
        case "CaptureOpen":
            return .captureOpen(spec.boolVal("open"))
        case "Interrupted":
            return .interrupted(spec.boolVal("interrupted"))
        case "SpeechLevel":
            return .speechLevel(levelDbfs: spec.doubleVal("level_dbfs"), atMonoUs: spec.int64("at_mono_us"))
        case "VoxTick":
            return .voxTick(atMonoUs: spec.int64("at_mono_us"))
        default:
            preconditionFailure("unknown input kind in vectors: \(spec.str("kind"))")
        }
    }

    private func gate(_ spec: [String: Any]) -> TransmissionGate {
        switch spec.str("kind") {
        case "none": return .none
        case "vox": return .vox(thresholdDbfs: spec.doubleVal("threshold_dbfs"), hangoverMs: spec.int64("hangover_ms"))
        case "ptt": return .ptt
        case "disabled": return .disabled
        default: preconditionFailure("unknown gate kind in vectors: \(spec.str("kind"))")
        }
    }

    private func onSpeech(_ spec: [String: Any]) -> OnSpeech {
        switch spec.str("kind") {
        case "duck": return .duck(toPercent: spec.int("to_percent"))
        case "pause": return .pause
        default: preconditionFailure("unknown on_speech kind in vectors: \(spec.str("kind"))")
        }
    }

    // The vectors name enum values in upper case, matching the Kotlin constant names, so both platforms
    // read one file rather than two spellings of one table.
    private func musicQualityPriority(_ raw: String) -> MusicQualityPriority {
        raw == "HIGH" ? .high : .yieldToVoice
    }

    private func voiceMode(_ raw: String) -> VoiceMode {
        VoiceMode.allCases.first { $0.rawValue.uppercased() == raw }!
    }

    private func intercomMode(_ raw: String) -> IntercomMode {
        IntercomMode.allCases.first { $0.rawValue.uppercased() == raw }!
    }

    private func label(_ spec: [String: Any]) -> String {
        switch spec.str("kind") {
        case "SetTransmitting": return "SetTransmitting(\(spec.boolVal("transmitting")))"
        case "AnnounceVoiceMode": return "AnnounceVoiceMode(\(spec.str("mode")))"
        case "PublishAudioState": return "PublishAudioState"
        default: preconditionFailure("unknown action kind in vectors: \(spec.str("kind"))")
        }
    }

    private func label(_ action: IntercomAction) -> String {
        switch action {
        case .setTransmitting(let transmitting): return "SetTransmitting(\(transmitting))"
        case .announceVoiceMode(let mode): return "AnnounceVoiceMode(\(mode.rawValue.uppercased()))"
        case .publishAudioState: return "PublishAudioState"
        }
    }
}
