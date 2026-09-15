import XCTest
@testable import RideLinkCore

/// Sequential proofs of ADR-020 A11. No scheduler or elapsed-time assumptions.
final class VoicePendingStartIntentTests: XCTestCase {
    func testBothOrdersConsumeOnceForBothRoles() {
        for role in VoiceRole.allCases {
            for connectedFirst in [false, true] {
                var h = Trace(role)
                if connectedFirst { h.apply(.controlAuthenticated(controlGeneration: 2, freshVoiceSessionId: id(2))) }
                h.apply(.startRequested(freshVoiceSessionId: id(1), controlGeneration: nil))
                if !connectedFirst {
                    XCTAssertTrue(h.state.pendingStartIntent)
                    XCTAssertTrue(h.state.localAudioOpen)
                    XCTAssertNil(h.state.negotiationControlGeneration)
                    XCTAssertNil(h.state.voiceSessionId)
                    XCTAssertEqual(h.actions, [.startLocalAudio])
                    h.apply(.controlAuthenticated(controlGeneration: 2, freshVoiceSessionId: id(2)))
                }
                XCTAssertEqual(h.state.negotiationControlGeneration, 2)
                XCTAssertFalse(h.state.pendingStartIntent)
                XCTAssertEqual(h.state.status, .negotiating)
                XCTAssertEqual(h.state.voiceSessionId, role == .offerer ? id(connectedFirst ? 1 : 2) : nil)
                XCTAssertTrue(h.actions.filter(\.isOutbound).allSatisfy { $0.controlGeneration == 2 })
                let before = h.state
                let effects = h.actions
                h.apply(.controlAuthenticated(controlGeneration: 2, freshVoiceSessionId: id(3)))
                h.apply(.startRequested(freshVoiceSessionId: id(4), controlGeneration: 2))
                XCTAssertEqual(h.state, before)
                XCTAssertEqual(h.actions, effects, "duplicate availability and explicit Start(B) are idempotent")
                h.apply(.controlLinkLost(retiredControlGeneration: 1))
                XCTAssertEqual(h.state, before)
                h.apply(.controlLinkLost(retiredControlGeneration: 2))
                XCTAssertEqual(h.state.status, .idle)
                XCTAssertNil(h.state.negotiationControlGeneration)
                XCTAssertNil(h.state.authenticatedControlGeneration)
                XCTAssertTrue(h.state.localAudioOpen)
                XCTAssertFalse(h.state.pendingStartIntent)
            }
        }
    }

    func testFailureNeverManufacturesIntentOrRetriesOnDuplicateAvailability() {
        for role in VoiceRole.allCases {
            var h = Trace(role)
            h.apply(.startRequested(freshVoiceSessionId: id(1), controlGeneration: nil))
            h.apply(.controlAuthenticated(controlGeneration: 2, freshVoiceSessionId: id(2)))
            h.apply(.negotiationSendFailed(voiceSessionId: h.state.voiceSessionId))
            XCTAssertFalse(h.state.pendingStartIntent)
            XCTAssertTrue(h.state.localAudioOpen)
            XCTAssertEqual(h.state.status, .idle)
            let effects = h.actions
            for _ in 0..<5 { h.apply(.controlAuthenticated(controlGeneration: 2, freshVoiceSessionId: id(3))) }
            XCTAssertEqual(h.actions, effects)
            XCTAssertEqual(h.state.status, .idle)
        }
    }

    func testStopAndSessionStopClearIntent() {
        for role in VoiceRole.allCases {
            var h = Trace(role)
            h.apply(.startRequested(freshVoiceSessionId: id(1), controlGeneration: nil))
            h.apply(.stopRequested) // End Voice and session ENDING both reduce this input.
            XCTAssertFalse(h.state.pendingStartIntent)
            XCTAssertFalse(h.state.localAudioOpen)
            XCTAssertEqual(h.actions, [.startLocalAudio, .stopMediaTransport, .releaseLocalAudio])
            h.apply(.controlAuthenticated(controlGeneration: 2, freshVoiceSessionId: id(2)))
            XCTAssertEqual(h.state.status, .idle)
            XCTAssertEqual(h.actions.count, 3)
        }
    }

    func testQueuedBAvailabilityRetiredBeforeConsumptionLeavesIntentForC() {
        for role in VoiceRole.allCases {
            var h = Trace(role)
            h.apply(.startRequested(freshVoiceSessionId: id(1), controlGeneration: nil))
            var mailbox = VoiceInputMailbox()
            mailbox.offer(.controlAuthenticated(controlGeneration: 2, freshVoiceSessionId: id(2)))
            mailbox.offer(.controlLinkLost(retiredControlGeneration: 2))
            mailbox.offer(.controlAuthenticated(controlGeneration: 3, freshVoiceSessionId: id(3)))
            while let input = mailbox.poll() { h.apply(input) }
            XCTAssertEqual(h.state.negotiationControlGeneration, 3)
            XCTAssertEqual(h.state.voiceSessionId, role == .offerer ? id(3) : nil)
            XCTAssertFalse(h.state.pendingStartIntent)
            XCTAssertTrue(h.actions.filter(\.isOutbound).allSatisfy { $0.controlGeneration == 3 })
            XCTAssertEqual(mailbox.discardedRetiredAvailabilityCount, 1)
            XCTAssertEqual(mailbox.discardedRetiredSignalCount, 0)
            XCTAssertEqual(mailbox.offer(.controlAuthenticated(controlGeneration: 2, freshVoiceSessionId: id(4))), .retiredGeneration)
            XCTAssertEqual(mailbox.refusedRetiredAvailabilityCount, 1)
            XCTAssertEqual(mailbox.refusedRetiredSignalCount, 0)
        }
    }

    func testNewAvailabilityRetiresOldQueuedAuthorityWithoutDiscardingLocalStart() {
        var mailbox = VoiceInputMailbox()
        mailbox.offer(.controlAuthenticated(controlGeneration: 2, freshVoiceSessionId: id(2)))
        mailbox.offer(.startRequested(freshVoiceSessionId: id(1), controlGeneration: nil))
        mailbox.offer(.controlAuthenticated(controlGeneration: 3, freshVoiceSessionId: id(3)))
        var h = Trace(.offerer)
        while let input = mailbox.poll() { h.apply(input) }
        XCTAssertEqual(h.state.negotiationControlGeneration, 3)
        XCTAssertFalse(h.state.pendingStartIntent)
        XCTAssertEqual(h.state.voiceSessionId, id(3))
        XCTAssertEqual(mailbox.discardedRetiredAvailabilityCount, 1)
        XCTAssertEqual(mailbox.discardedRetiredSignalCount, 0)
    }

    func testRecordedBAvailabilitySurvivesLateAWhileIdle() {
        var h = Trace(.offerer)
        h.apply(.controlAuthenticated(controlGeneration: 2, freshVoiceSessionId: id(2)))
        h.apply(.controlLinkLost(retiredControlGeneration: 1))
        XCTAssertEqual(h.state.authenticatedControlGeneration, 2)
        h.apply(.startRequested(freshVoiceSessionId: id(1), controlGeneration: nil))
        XCTAssertEqual(h.state.negotiationControlGeneration, 2)
    }

    func testHeldBAloneAuthorisesNilConsent() {
        var h = Trace(.answerer)
        h.apply(.signalReceived(signal: .offer(voiceSessionId: id(2), sdp: "v=0\r\n"), controlGeneration: 2, freshVoiceSessionId: id(3)))
        h.apply(.startRequested(freshVoiceSessionId: id(1), controlGeneration: nil))
        XCTAssertEqual(h.state.negotiationControlGeneration, 2)
        XCTAssertEqual(h.state.voiceSessionId, id(2))
        XCTAssertFalse(h.state.pendingStartIntent)
        XCTAssertEqual(h.actions.filter { if case .createAnswer = $0 { true } else { false } }.count, 1)
    }

    func testExplicitBStartBeforeAvailabilityAlsoConsumesPendingIntent() {
        var h = Trace(.offerer)
        h.apply(.startRequested(freshVoiceSessionId: id(1), controlGeneration: nil))
        h.apply(.startRequested(freshVoiceSessionId: id(2), controlGeneration: 2))
        h.apply(.controlAuthenticated(controlGeneration: 2, freshVoiceSessionId: id(3)))
        XCTAssertEqual(h.state.voiceSessionId, id(2))
        XCTAssertFalse(h.state.pendingStartIntent)
        XCTAssertEqual(h.actions.filter { if case .createOffer = $0 { true } else { false } }.count, 1)
    }

    func testCoalescedLossCannotForgetThatBEnded() {
        var h = Trace(.offerer)
        h.apply(.controlAuthenticated(controlGeneration: 2, freshVoiceSessionId: id(2)))
        var mailbox = VoiceInputMailbox()
        mailbox.offer(.controlLinkLost(retiredControlGeneration: 2))
        mailbox.offer(.controlLinkLost(retiredControlGeneration: 1))
        while let input = mailbox.poll() { h.apply(input) }
        h.apply(.startRequested(freshVoiceSessionId: id(1), controlGeneration: nil))
        XCTAssertTrue(h.state.pendingStartIntent)
        XCTAssertNil(h.state.negotiationControlGeneration)
    }

    func testConnectedBBeforeDelayedLossARebuildsWithoutReowningA() {
        var h = Trace(.offerer)
        h.apply(.controlAuthenticated(controlGeneration: 1, freshVoiceSessionId: id(1)))
        h.apply(.startRequested(freshVoiceSessionId: id(2), controlGeneration: 1))
        h.apply(.controlAuthenticated(controlGeneration: 2, freshVoiceSessionId: id(3)))
        h.apply(.controlLinkLost(retiredControlGeneration: 1))
        XCTAssertEqual(h.state.negotiationControlGeneration, 2)
        XCTAssertEqual(h.state.voiceSessionId, id(3))
        XCTAssertEqual(h.actions.filter { if case .stopMediaTransport = $0 { true } else { false } }.count, 1)
    }

    private struct Trace {
        var state: VoiceNegotiationState
        var actions: [VoiceAction] = []
        init(_ role: VoiceRole) { state = VoiceNegotiationState(role: role) }
        mutating func apply(_ input: VoiceInput) {
            let outcome = VoiceNegotiation.reduce(state: state, input: input)
            state = outcome.state
            actions += outcome.actions
        }
    }

    private func id(_ n: Int) -> VoiceSessionId { VoiceSessionId(String(repeating: String(n), count: 32)) }
}
