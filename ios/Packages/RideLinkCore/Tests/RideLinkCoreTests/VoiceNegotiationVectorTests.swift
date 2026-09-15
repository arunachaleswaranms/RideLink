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
    /// The vectors pin the **reducer**, which reads neither of the two provenance fields the mailbox
    /// added in ADR-020 Amendment A7 (STATUS §4 problem 60). A constant is therefore the honest
    /// encoding: the vector files are unchanged, and that is itself the assertion that control-lifetime
    /// identity is a receiver-local concern and not a wire one.
    private let vectorControlGeneration: Int64 = 1

    private let expectedMinimumRows = 92
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
                let outcome = VoiceNegotiation.reduce(state: before, input: .controlLinkLost(retiredControlGeneration: vectorControlGeneration))
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

    /// **Every value that holds negotiation state names the lifetime that owns it, and every value
    /// that holds none names nobody** (STATUS §4 problem 61, ADR-020 Amendment A8).
    ///
    /// Run over the resulting state of every row, this is what turns the generator's "a row that does
    /// not say otherwise is about one control lifetime" from a convenience into a checked invariant.
    /// A negotiation with no owner would be un-retirable by any boundary that names one; an owner
    /// left behind on a state that holds nothing would let a later boundary be judged against a
    /// negotiation that no longer exists.
    ///
    /// "Holds negotiation state" is spelled out rather than read off `isNegotiationLive`, because the
    /// two come apart in both directions: an answerer's intent-to-talk is live with no
    /// `voice_session_id` (§7.3), and a peer's `failed` leaves a `.failed` status owning nothing.
    func testNegotiationStateAndItsOwningControlLifetimeArePresentTogetherOrNotAtAll() throws {
        for element in try rows() {
            guard let row = element as? [String: Any] else { return XCTFail("row is not an object") }
            let outcome = VoiceNegotiation.reduce(state: state(row.dict("state")), input: input(row.dict("input")))
            let after = outcome.state
            let holdsNegotiation =
                after.status.isNegotiationLive || after.voiceSessionId != nil || after.heldRemoteOffer != nil
            XCTAssertEqual(
                holdsNegotiation,
                after.negotiationControlGeneration != nil,
                """
                row \(row.str("name")) holds negotiation state = \(holdsNegotiation) but names owner \
                \(String(describing: after.negotiationControlGeneration))
                """
            )
        }
    }

    /// **The pending gap-press intent is a one-shot that only an explicit event consumes** (STATUS §4
    /// problem 69, ADR-020 Amendment A11), as three properties over the whole vector file:
    ///
    /// 1. An intent never coexists with the negotiation state it requests — the transition that
    ///    establishes a negotiation consumes it, so `pendingStartIntent == true` in a *resulting*
    ///    state means that state holds no negotiation.
    /// 2. A `StartRequested` whose `controlGeneration` is nil establishes a negotiation only when
    ///    the state it reduced against carried a lifetime an input delivered — the recorded
    ///    `authenticatedControlGeneration` — or a held offer whose own owner supplies the authority.
    ///    Nil never invents a generation.
    /// 3. A `NegotiationSendFailed` result never carries a manufactured intent, and a
    ///    `ControlAuthenticated` result never leaves an intent standing beside a live negotiation.
    func testThePendingGapPressIntentIsAOneShotConsumedByExplicitEvents() throws {
        var consumed = 0
        for element in try rows() {
            guard let row = element as? [String: Any] else { return XCTFail("row is not an object") }
            let name = row.str("name")
            let before = state(row.dict("state"))
            let inputSpec = row.dict("input")
            let after = VoiceNegotiation.reduce(state: before, input: input(inputSpec)).state

            // 1. An intent and the thing it requests are mutually exclusive.
            if after.pendingStartIntent {
                let holdsNegotiation =
                    after.status.isNegotiationLive || after.voiceSessionId != nil || after.heldRemoteOffer != nil
                XCTAssertFalse(
                    holdsNegotiation,
                    "row \(name) holds negotiation state the pending intent requested without consuming it"
                )
            }

            // 2. A nil press establishes only under delivered authority.
            if inputSpec.str("kind") == "StartRequested", inputSpec.requiredInt64Opt("control_generation") == nil,
                after.status.isNegotiationLive {
                let authority = before.authenticatedControlGeneration ?? before.heldRemoteOffer.map { _ in
                    before.negotiationControlGeneration
                }
                XCTAssertNotNil(
                    authority,
                    "row \(name) established a negotiation from a nil press with no lifetime the table had seen"
                )
                consumed += 1
            }

            // 3. Neither a send failure nor an authentication may leave an intent beside live work,
            //    and a send failure may not manufacture one.
            if inputSpec.str("kind") == "NegotiationSendFailed" {
                XCTAssertFalse(
                    after.pendingStartIntent && !before.pendingStartIntent,
                    "row \(name) manufactured a pending intent from a send failure"
                )
            }
            if inputSpec.str("kind") == "ControlAuthenticated", after.pendingStartIntent {
                XCTAssertFalse(
                    after.status.isNegotiationLive,
                    "row \(name) left a pending intent standing beside the negotiation it just established"
                )
            }
        }
        XCTAssertGreaterThan(consumed, 0, "the file must contain nil-press resumption rows for this to mean anything")
    }

    /// **Every action that puts a frame on the wire names the control lifetime whose connection it may
    /// be written to, and that lifetime is one the table was already holding** (STATUS §4 problem 64,
    /// ADR-020 Amendment A9).
    ///
    /// Two halves, and both matter. **Non-nil**, because `VoiceSignalTransport.send` treats a nil
    /// authorisation as a refusal — a transition that produced one would silently stop voice sending
    /// anything at all. And **one of the two owners the row mentions**, because the alternative is a
    /// table that invents a generation, which is the inbound defect (ADR-024 Amendment A7) pointing
    /// outwards: the value has to come from state the table already held, never from anywhere else.
    ///
    /// The exact value per row is pinned by the rows themselves; this is what stops a *new* branch
    /// being added without one. The mirror is Kotlin's
    /// `every outbound action names a control lifetime the table already held`.
    func testEveryOutboundActionNamesAControlLifetimeTheTableAlreadyHeld() throws {
        var covered = 0
        for element in try rows() {
            guard let row = element as? [String: Any] else { return XCTFail("row is not an object") }
            let before = state(row.dict("state"))
            let outcome = VoiceNegotiation.reduce(state: before, input: input(row.dict("input")))
            let permitted = Set([before.negotiationControlGeneration, outcome.state.negotiationControlGeneration]
                .compactMap { $0 })
            for action in outcome.actions where action.isOutbound {
                guard let owner = action.controlGeneration else {
                    XCTFail("""
                    row \(row.str("name")) would send \(action) authorised by nobody,                     which can never be written
                    """)
                    continue
                }
                XCTAssertTrue(
                    permitted.contains(owner),
                    """
                    row \(row.str("name")) sends \(action) under \(owner),                     which the table never held (it held \(permitted))
                    """
                )
                covered += 1
            }
        }
        XCTAssertGreaterThan(covered, 0, "the file must contain outbound rows for this to mean anything")
    }

    /// The ownership rule as a property over every role and status rather than the seven rows that
    /// name it: **a boundary older than the owner is inert, and every other boundary tears down.**
    ///
    /// The `owner < retired` half is the one worth stating twice. A boundary naming a *newer* lifetime
    /// than the owner must still tear down, because `ControlSessionManager` authenticates one
    /// connection at a time and allocates strictly increasing generations — so a newer lifetime having
    /// existed proves the owner's already ended. Making that case inert instead is exactly the
    /// "suppress a superseded boundary" fix that was implemented and rejected: it leaves a dead
    /// lifetime's negotiation standing, which then refuses every offer the successor sends.
    func testOnlyABoundaryOlderThanTheOwnerIsInert() {
        let owner: Int64 = 5
        for role in VoiceRole.allCases {
            for status in VoiceStatus.allCases where status != .idle {
                let before = VoiceNegotiationState(
                    role: role,
                    status: status,
                    voiceSessionId: VoiceSessionId(vsidA),
                    localAudioOpen: true,
                    negotiationControlGeneration: owner
                )

                let superseded = VoiceNegotiation.reduce(
                    state: before, input: .controlLinkLost(retiredControlGeneration: owner - 1)
                )
                XCTAssertEqual(superseded.state, before, "\(role)/\(status): a predecessor's boundary changed state")
                XCTAssertFalse(
                    superseded.actions.contains(.stopMediaTransport),
                    "\(role)/\(status): a predecessor's boundary stopped the successor's media"
                )

                for retired: Int64? in [owner, owner + 1, nil] {
                    let outcome = VoiceNegotiation.reduce(
                        state: before, input: .controlLinkLost(retiredControlGeneration: retired)
                    )
                    XCTAssertTrue(
                        outcome.actions.contains(.stopMediaTransport),
                        "\(role)/\(status): a boundary naming \(String(describing: retired)) failed to retire \(owner)"
                    )
                    XCTAssertNil(outcome.state.negotiationControlGeneration, "\(role)/\(status): owner survived")
                }
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
                .startRequested(freshVoiceSessionId: VoiceSessionId(vsidFresh), controlGeneration: vectorControlGeneration),
                .signalReceived(
                    signal: .state(voiceSessionId: nil, state: .negotiating, micMuted: false, mode: .continuous),
                    controlGeneration: vectorControlGeneration, freshVoiceSessionId: VoiceSessionId(vsidFresh)
                ),
                .signalReceived(
                    signal: .state(
                        voiceSessionId: VoiceSessionId(vsidA), state: .negotiating, micMuted: false, mode: .continuous
                    ),
                    controlGeneration: vectorControlGeneration, freshVoiceSessionId: VoiceSessionId(vsidFresh)
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
            controlGeneration: vectorControlGeneration, freshVoiceSessionId: fresh
        )
        let orders: [[VoiceInput]] = [
            [.startRequested(freshVoiceSessionId: fresh, controlGeneration: vectorControlGeneration), peerIntent],
            [peerIntent, .startRequested(freshVoiceSessionId: fresh, controlGeneration: vectorControlGeneration)],
            [peerIntent, peerIntent, .startRequested(freshVoiceSessionId: fresh, controlGeneration: vectorControlGeneration), peerIntent],
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
            mode: mode(spec.str("mode")),
            negotiationControlGeneration: spec.requiredInt64Opt("negotiation_control_generation"),
            pendingStartIntent: spec.boolVal("pending_start_intent"),
            authenticatedControlGeneration: spec.requiredInt64Opt("authenticated_control_generation")
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
            return .startRequested(
                freshVoiceSessionId: VoiceSessionId(spec.str("fresh_voice_session_id")),
                controlGeneration: spec.requiredInt64Opt("control_generation")
            )
        case "StopRequested":
            return .stopRequested
        case "ControlLinkLost":
            // Read from the file, never defaulted here. ADR-020 Amendment A7 could encode these as a
            // constant because the reducer ignored them; Amendment A8 made the control lifetime part
            // of what the table decides, so the vectors now carry it and a row that omits it fails
            // rather than quietly meaning "the only lifetime there is". Still **not** a wire field:
            // this is receiver-local provenance in a receiver-local table.
            return .controlLinkLost(retiredControlGeneration: spec.requiredInt64Opt("retired_control_generation"))
        case "NegotiationSendFailed":
            return .negotiationSendFailed(voiceSessionId: spec.strOpt("voice_session_id").map(VoiceSessionId.init))
        case "ControlAuthenticated":
            // ADR-020 Amendment A11: the successor-lifetime availability event. `control_generation`
            // is required (never defaulted) — it is the authority for whatever the input consumes,
            // exactly the property the row is about.
            return .controlAuthenticated(
                controlGeneration: spec.requiredInt64("control_generation"),
                freshVoiceSessionId: VoiceSessionId(spec.str("fresh_voice_session_id"))
            )
        case "MuteRequested":
            return .muteRequested(muted: spec.boolVal("muted"))
        case "ModeSelected":
            return .modeSelected(mode: mode(spec.str("mode")))
        case "SignalReceived":
            return .signalReceived(
                signal: signal(spec.dict("signal")),
                controlGeneration: spec.requiredInt64("control_generation"),
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
    /// Kotlin renders a null `Long?` as "null"; Swift's own interpolation of `Optional<Int64>` would
    /// render "nil" and, worse, differently again for `.some`. One spelling, so the two platforms'
    /// labels are the same strings for the same actions.
    private func describe(_ generation: Int64?) -> String {
        generation.map(String.init) ?? "null"
    }

    // swiftlint:disable:next cyclomatic_complexity
    private func actionLabel(_ spec: [String: Any]) -> String {
        let kind = spec.str("kind")
        switch kind {
        case "StartLocalAudio", "DrainQueuedCandidates", "StopMediaTransport",
             "ReleaseLocalAudio", "SurfacePeerVoiceRequest":
            return kind
        case "CreateOffer", "CreateAnswer":
            return "\(kind)(\(spec.str("voice_session_id")))"
        case "ApplyRemoteOffer", "ApplyRemoteAnswer":
            return "\(kind)(\(spec.str("voice_session_id")),\(spec.str("sdp")))"
        // Every outbound kind below carries `control_generation`, read with `requiredInt64Opt` so a
        // row that forgets it fails rather than quietly meaning "whichever lifetime is around" —
        // which is the defect ADR-020 Amendment A9 closes.
        case "SendOffer", "SendAnswer":
            return "\(kind)(\(spec.str("voice_session_id")),\(spec.str("sdp")),"
                + "\(describe(spec.requiredInt64Opt("control_generation"))))"
        case "SendVoiceState":
            let id = spec.strOpt("voice_session_id") ?? "nil"
            return "SendVoiceState(\(id),\(spec.str("state")),\(spec.boolVal("mic_muted")),\(spec.str("mode")),"
                + "\(describe(spec.requiredInt64Opt("control_generation"))))"
        case "ApplyRemoteCandidate", "QueueRemoteCandidate":
            let mid = spec.strOpt("sdp_mid") ?? "nil"
            return "\(kind)(\(spec.str("voice_session_id")),\(spec.str("candidate")),"
                + "\(mid),\(spec.int("sdp_mline_index")))"
        case "SendCandidate":
            let mid = spec.strOpt("sdp_mid") ?? "nil"
            return "SendCandidate(\(spec.str("voice_session_id")),\(spec.str("candidate")),"
                + "\(mid),\(spec.int("sdp_mline_index")),\(describe(spec.requiredInt64Opt("control_generation"))))"
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
        case .sendOffer(let id, let sdp, let owner): return "SendOffer(\(id.value),\(sdp),\(describe(owner)))"
        case .sendAnswer(let id, let sdp, let owner): return "SendAnswer(\(id.value),\(sdp),\(describe(owner)))"
        case .sendVoiceState(let id, let state, let micMuted, let mode, let owner):
            return "SendVoiceState(\(id?.value ?? "nil"),\(state.wire),\(micMuted),"
                + "\(mode.rawValue.uppercased()),\(describe(owner)))"
        case .applyRemoteCandidate(let id, let candidate, let mid, let index):
            return "ApplyRemoteCandidate(\(id.value),\(candidate),\(mid ?? "nil"),\(index))"
        case .queueRemoteCandidate(let id, let candidate, let mid, let index):
            return "QueueRemoteCandidate(\(id.value),\(candidate),\(mid ?? "nil"),\(index))"
        case .sendCandidate(let id, let candidate, let mid, let index, let owner):
            return "SendCandidate(\(id.value),\(candidate),\(mid ?? "nil"),\(index),\(describe(owner)))"
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
