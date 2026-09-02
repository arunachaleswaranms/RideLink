import Foundation
import XCTest
@testable import RideLinkCore

/// Runs `protocol/vectors/voice-fsm/voice_fsm_vectors.json` against `VoiceNegotiation`.
///
/// The mirror is `com.ridelink.core.voice.VoiceNegotiationVectorTest`, running the **same file**. What
/// the table encodes is exactly what would otherwise be discovered on a ride: which side offers, that
/// two simultaneous Start Voice presses produce one negotiation, that a stale callback cannot touch the
/// next session, and that a link blip does not close a microphone Android would not let us reopen.
final class VoiceNegotiationVectorTests: XCTestCase {
    private let expectedMinimumRows = 52
    private let vsidA = "5e2a9c40b7f13d86e0a4c95b28f7d613"
    private let vsidFresh = "ffeeddccbbaa99887766554433221100"

    /// Both are the PROTOCOL §7.2 generation guard, and the distinction between them is deliberate
    /// rather than incidental: a foreign generation arriving on the **wire** is a peer talking about a
    /// negotiation we no longer have, while the same from the **media stack's own callback** is a
    /// delegate call from a peer connection we already closed. They are diagnosed separately because
    /// they point at different faults.
    private let generationGuardReasons: Set<VoiceSignalDropReason> = [.generationMismatch, .staleEngineCallback]

    private func rows() throws -> [Any] {
        // swiftlint:disable:next force_cast
        let doc = try Vectors.loadJSON("voice-fsm/voice_fsm_vectors.json") as! [String: Any]
        return doc.array("rows")
    }

    func testEveryRowOfTheSharedNegotiationTableHolds() throws {
        var checked = 0
        for element in try rows() {
            guard let row = element as? [String: Any] else { return XCTFail("row is not an object") }
            let name = row.str("name")
            let before = state(row.dict("state"))
            let outcome = VoiceNegotiation.reduce(state: before, input: input(row.dict("input")))
            let expect = row.dict("expect")

            XCTAssertEqual(
                expect.array("actions").map { actionLabel($0 as! [String: Any]) }, // swiftlint:disable:this force_cast
                outcome.actions.map(actionLabel),
                "vector \(name) actions"
            )
            XCTAssertEqual(state(expect.dict("state")), outcome.state, "vector \(name) resulting state")
            checked += 1
        }
        XCTAssertGreaterThanOrEqual(checked, expectedMinimumRows, "expected at least \(expectedMinimumRows) rows")
    }

    /// The §7.2 generation guard, as a property over the whole file rather than row by row: whenever an
    /// input names a generation this side does not hold, the **only** permitted action is recording the
    /// drop. Anything else would be a path by which a stale frame or callback reaches the media stack.
    func testAnInputNamingAForeignGenerationCanOnlyEverBeDropped() throws {
        var covered = 0
        for element in try rows() {
            guard let row = element as? [String: Any] else { continue }
            let before = state(row.dict("state"))
            let inputSpec = row.dict("input")
            let inputId = inputSpec.strOpt("voice_session_id")
                ?? inputSpec.dictOpt("signal")?.strOpt("voice_session_id")
            guard let inputId, let held = before.voiceSessionId?.value, inputId != held else { continue }

            let outcome = VoiceNegotiation.reduce(state: before, input: input(inputSpec))
            guard outcome.actions.count == 1,
                  case .recordDroppedSignal(let reason) = outcome.actions[0],
                  generationGuardReasons.contains(reason)
            else {
                return XCTFail("row \(row.str("name")) must drop a foreign generation and do nothing else")
            }
            XCTAssertEqual(before, outcome.state, "row \(row.str("name")) must not change state")
            covered += 1
        }
        XCTAssertGreaterThan(covered, 0, "the file must contain generation-mismatch rows for this to mean anything")
    }

    /// ARCHITECTURE §6.3/§6.4 as a property: a control-plane blip may drop the media transport but must
    /// never release the capture device, because on Android there is no second legal opportunity to open
    /// a microphone once the screen is locked — and the two platforms share this table, so the property
    /// is asserted here too.
    func testNoControlLinkLossEverReleasesLocalAudio() {
        for role in VoiceRole.allCases {
            for status in VoiceStatus.allCases {
                let before = VoiceNegotiationState(
                    role: role,
                    status: status,
                    voiceSessionId: status == .idle ? nil : VoiceSessionId(vsidA),
                    localAudioOpen: true,
                    remoteDescriptionApplied: status == .active
                )
                let outcome = VoiceNegotiation.reduce(state: before, input: .controlLinkLost)
                XCTAssertFalse(
                    outcome.actions.contains(.releaseLocalAudio),
                    "\(role)/\(status) released capture on a link loss"
                )
                XCTAssertFalse(
                    outcome.actions.contains { if case .sendVoiceState = $0 { return true } else { return false } },
                    "\(role)/\(status) tried to send on a link that is gone"
                )
                XCTAssertTrue(outcome.state.localAudioOpen, "\(role)/\(status) forgot the user's consent")
            }
        }
    }

    /// PROTOCOL §7.3, exhaustively: an answerer never authors an offer, from any status.
    func testAnAnswererNeverOffersFromAnyStatus() {
        for status in VoiceStatus.allCases {
            let before = VoiceNegotiationState(
                role: .answerer,
                status: status,
                voiceSessionId: status == .idle ? nil : VoiceSessionId(vsidA),
                localAudioOpen: true
            )
            let inputs: [VoiceInput] = [
                .startRequested(freshVoiceSessionId: VoiceSessionId(vsidFresh)),
                .signalReceived(
                    signal: .state(voiceSessionId: nil, state: .negotiating, micMuted: false, mode: .continuous),
                    freshVoiceSessionId: VoiceSessionId(vsidFresh)
                ),
                .signalReceived(
                    signal: .state(
                        voiceSessionId: VoiceSessionId(vsidA), state: .negotiating, micMuted: false, mode: .continuous
                    ),
                    freshVoiceSessionId: VoiceSessionId(vsidFresh)
                ),
            ]
            for input in inputs {
                let outcome = VoiceNegotiation.reduce(state: before, input: input)
                let offered = outcome.actions.contains {
                    if case .createOffer = $0 { return true }
                    if case .sendOffer = $0 { return true }
                    return false
                }
                XCTAssertFalse(offered, "answerer in \(status) offered")
            }
        }
    }

    /// PROTOCOL §7.3 glare, as a property rather than one row: whichever order the two presses and the
    /// peer's intent arrive in, exactly **one** `createOffer` is produced and it names one generation.
    func testSimultaneousStartOnBothSidesProducesExactlyOneOffer() {
        let fresh = VoiceSessionId(vsidFresh)
        let peerIntent = VoiceInput.signalReceived(
            signal: .state(voiceSessionId: nil, state: .negotiating, micMuted: false, mode: .continuous),
            freshVoiceSessionId: fresh
        )
        let orders: [[VoiceInput]] = [
            [.startRequested(freshVoiceSessionId: fresh), peerIntent],
            [peerIntent, .startRequested(freshVoiceSessionId: fresh)],
            [peerIntent, peerIntent, .startRequested(freshVoiceSessionId: fresh), peerIntent],
        ]
        for order in orders {
            var current = VoiceNegotiationState(role: .offerer, localAudioOpen: true)
            var offers: [VoiceSessionId] = []
            for input in order {
                let outcome = VoiceNegotiation.reduce(state: current, input: input)
                current = outcome.state
                for action in outcome.actions {
                    if case .createOffer(let id) = action { offers.append(id) }
                }
            }
            XCTAssertEqual(offers.count, 1, "an order produced \(offers.count) offers")
            XCTAssertEqual(current.voiceSessionId, offers.first, "the offer must name the live generation")
        }
    }

    // MARK: - vector decoding

    private func state(_ spec: [String: Any]) -> VoiceNegotiationState {
        VoiceNegotiationState(
            role: VoiceRole(rawValue: spec.str("role"))!,
            status: VoiceStatus(rawValue: spec.str("status"))!,
            voiceSessionId: spec.strOpt("voice_session_id").map(VoiceSessionId.init),
            localAudioOpen: spec.boolVal("local_audio_open"),
            remoteDescriptionApplied: spec.boolVal("remote_description_applied"),
            peerVoiceEnabled: spec.boolVal("peer_voice_enabled"),
            peerReportedState: wireState(spec.str("peer_reported_state")),
            heldRemoteOffer: spec.dictOpt("held_remote_offer").map {
                HeldRemoteOffer(voiceSessionId: VoiceSessionId($0.str("voice_session_id")), sdp: $0.str("sdp"))
            },
            micMuted: spec.boolVal("mic_muted"),
            mode: mode(spec.str("mode"))
        )
    }

    // The vectors name states and modes in upper case, matching the Kotlin enum constant names, so both
    // platforms read one file rather than two spellings of one table.
    private func wireState(_ raw: String) -> VoiceWireState {
        VoiceWireState.allCases.first { $0.rawValue.uppercased() == raw }!
    }

    private func mode(_ raw: String) -> VoiceMode {
        VoiceMode.allCases.first { $0.rawValue.uppercased() == raw }!
    }

    // swiftlint:disable:next cyclomatic_complexity
    private func input(_ spec: [String: Any]) -> VoiceInput {
        switch spec.str("kind") {
        case "StartRequested":
            return .startRequested(freshVoiceSessionId: VoiceSessionId(spec.str("fresh_voice_session_id")))
        case "StopRequested":
            return .stopRequested
        case "ControlLinkLost":
            return .controlLinkLost
        case "MuteRequested":
            return .muteRequested(muted: spec.boolVal("muted"))
        case "SignalReceived":
            return .signalReceived(
                signal: signal(spec.dict("signal")),
                freshVoiceSessionId: VoiceSessionId(spec.str("fresh_voice_session_id"))
            )
        case "LocalOfferCreated":
            return .localOfferCreated(
                voiceSessionId: VoiceSessionId(spec.str("voice_session_id")), sdp: spec.str("sdp")
            )
        case "LocalAnswerCreated":
            return .localAnswerCreated(
                voiceSessionId: VoiceSessionId(spec.str("voice_session_id")), sdp: spec.str("sdp")
            )
        case "LocalCandidateGathered":
            return .localCandidateGathered(
                voiceSessionId: VoiceSessionId(spec.str("voice_session_id")),
                candidate: spec.str("candidate"),
                sdpMid: spec.strOpt("sdp_mid"),
                sdpMlineIndex: spec.int("sdp_mline_index")
            )
        case "RemoteTrackChanged":
            return .remoteTrackChanged(
                voiceSessionId: VoiceSessionId(spec.str("voice_session_id")), present: spec.boolVal("present")
            )
        case "MediaConnectivityChanged":
            return .mediaConnectivityChanged(
                voiceSessionId: VoiceSessionId(spec.str("voice_session_id")),
                connected: spec.boolVal("connected"),
                failed: spec.boolVal("failed")
            )
        default:
            fatalError("unknown input kind in vectors: \(spec.str("kind"))")
        }
    }

    private func signal(_ spec: [String: Any]) -> VoiceSignal {
        switch spec.str("kind") {
        case "Offer":
            return .offer(voiceSessionId: VoiceSessionId(spec.str("voice_session_id")), sdp: spec.str("sdp"))
        case "Answer":
            return .answer(voiceSessionId: VoiceSessionId(spec.str("voice_session_id")), sdp: spec.str("sdp"))
        case "IceCandidate":
            return .iceCandidate(
                voiceSessionId: VoiceSessionId(spec.str("voice_session_id")),
                candidate: spec.str("candidate"),
                sdpMid: spec.strOpt("sdp_mid"),
                sdpMlineIndex: spec.int("sdp_mline_index")
            )
        case "State":
            return .state(
                voiceSessionId: spec.strOpt("voice_session_id").map(VoiceSessionId.init),
                state: wireState(spec.str("state")),
                micMuted: spec.boolVal("mic_muted"),
                mode: mode(spec.str("mode"))
            )
        default:
            fatalError("unknown signal kind in vectors: \(spec.str("kind"))")
        }
    }

    /// Compares actions as a canonical label rather than by constructing an expected value per case. A
    /// label keeps the failure message readable — `sendVoiceState(nil,connecting,…)` says what went
    /// wrong; a structural diff of two enum payloads does not.
    // swiftlint:disable:next cyclomatic_complexity
    private func actionLabel(_ spec: [String: Any]) -> String {
        let kind = spec.str("kind")
        switch kind {
        case "StartLocalAudio", "DrainQueuedCandidates", "StopMediaTransport",
             "ReleaseLocalAudio", "SurfacePeerVoiceRequest":
            return kind
        case "CreateOffer", "CreateAnswer":
            return "\(kind)(\(spec.str("voice_session_id")))"
        case "ApplyRemoteOffer", "ApplyRemoteAnswer", "SendOffer", "SendAnswer":
            return "\(kind)(\(spec.str("voice_session_id")),\(spec.str("sdp")))"
        case "SendVoiceState":
            let id = spec.strOpt("voice_session_id") ?? "nil"
            return "SendVoiceState(\(id),\(spec.str("state")),\(spec.boolVal("mic_muted")),\(spec.str("mode")))"
        case "ApplyRemoteCandidate", "QueueRemoteCandidate", "SendCandidate":
            let mid = spec.strOpt("sdp_mid") ?? "nil"
            return "\(kind)(\(spec.str("voice_session_id")),\(spec.str("candidate")),"
                + "\(mid),\(spec.int("sdp_mline_index")))"
        case "SetMicrophoneMuted":
            return "SetMicrophoneMuted(\(spec.boolVal("muted")))"
        case "RecordDroppedSignal":
            return "RecordDroppedSignal(\(spec.str("reason")))"
        default:
            fatalError("unknown action kind in vectors: \(kind)")
        }
    }

    // swiftlint:disable:next cyclomatic_complexity
    private func actionLabel(_ action: VoiceAction) -> String {
        switch action {
        case .startLocalAudio: return "StartLocalAudio"
        case .drainQueuedCandidates: return "DrainQueuedCandidates"
        case .stopMediaTransport: return "StopMediaTransport"
        case .releaseLocalAudio: return "ReleaseLocalAudio"
        case .surfacePeerVoiceRequest: return "SurfacePeerVoiceRequest"
        case .createOffer(let id): return "CreateOffer(\(id.value))"
        case .createAnswer(let id): return "CreateAnswer(\(id.value))"
        case .applyRemoteOffer(let id, let sdp): return "ApplyRemoteOffer(\(id.value),\(sdp))"
        case .applyRemoteAnswer(let id, let sdp): return "ApplyRemoteAnswer(\(id.value),\(sdp))"
        case .sendOffer(let id, let sdp): return "SendOffer(\(id.value),\(sdp))"
        case .sendAnswer(let id, let sdp): return "SendAnswer(\(id.value),\(sdp))"
        case .sendVoiceState(let id, let state, let micMuted, let mode):
            return "SendVoiceState(\(id?.value ?? "nil"),\(state.wire),\(micMuted),\(mode.rawValue.uppercased()))"
        case .applyRemoteCandidate(let id, let candidate, let mid, let index):
            return "ApplyRemoteCandidate(\(id.value),\(candidate),\(mid ?? "nil"),\(index))"
        case .queueRemoteCandidate(let id, let candidate, let mid, let index):
            return "QueueRemoteCandidate(\(id.value),\(candidate),\(mid ?? "nil"),\(index))"
        case .sendCandidate(let id, let candidate, let mid, let index):
            return "SendCandidate(\(id.value),\(candidate),\(mid ?? "nil"),\(index))"
        case .setMicrophoneMuted(let muted): return "SetMicrophoneMuted(\(muted))"
        case .recordDroppedSignal(let reason): return "RecordDroppedSignal(\(reason.rawValue))"
        }
    }
}

/// The bounded trickle-ICE queue (PROTOCOL §7.4). Small, deterministic, and the same expectations as
/// Kotlin's `VoiceControllerTest` asserts — a queue that silently truncated on one platform and
/// dropped the newest on the other would be a real behavioural difference in a ride.
final class PendingCandidatesTests: XCTestCase {
    private let genA = VoiceSessionId("11111111111111111111111111111111")
    private let foreign = VoiceSessionId("abababababababababababababababab")

    func testTheQueueIsBoundedAndEveryDropIsCounted() {
        var queue = PendingCandidates(capacity: 4)
        for i in 0..<4 {
            queue.offer(RemoteCandidate(voiceSessionId: genA, candidate: "c\(i)", sdpMid: nil, sdpMlineIndex: 0))
        }
        XCTAssertEqual(queue.count, 4)
        XCTAssertEqual(queue.droppedCount, 0)

        for i in 0..<3 {
            queue.offer(RemoteCandidate(voiceSessionId: genA, candidate: "over\(i)", sdpMid: nil, sdpMlineIndex: 0))
        }
        XCTAssertEqual(queue.count, 4, "capacity is a hard bound")
        XCTAssertEqual(queue.droppedCount, 3, "silent truncation would read as 'we saw everything'")

        // The oldest go first, so the newest — most likely still reachable — survive.
        XCTAssertEqual(queue.drain(voiceSessionId: genA).map(\.candidate), ["c3", "over0", "over1", "over2"])
    }

    func testDrainingDiscardsCandidatesQueuedForAnotherGeneration() {
        var queue = PendingCandidates()
        queue.offer(RemoteCandidate(voiceSessionId: foreign, candidate: "stale", sdpMid: nil, sdpMlineIndex: 0))
        queue.offer(RemoteCandidate(voiceSessionId: genA, candidate: "current", sdpMid: nil, sdpMlineIndex: 0))

        XCTAssertEqual(queue.drain(voiceSessionId: genA).map(\.candidate), ["current"])
        XCTAssertEqual(queue.count, 0, "a foreign-generation candidate is discarded, never left to be drained later")
    }

    func testTheQueuesDefaultCapacityIsTheProtocolBound() {
        var queue = PendingCandidates()
        for i in 0..<(VoiceBounds.maxQueuedCandidates + 5) {
            queue.offer(RemoteCandidate(voiceSessionId: genA, candidate: "c\(i)", sdpMid: nil, sdpMlineIndex: 0))
        }
        XCTAssertEqual(queue.count, VoiceBounds.maxQueuedCandidates)
        XCTAssertEqual(queue.droppedCount, 5)
    }
}
