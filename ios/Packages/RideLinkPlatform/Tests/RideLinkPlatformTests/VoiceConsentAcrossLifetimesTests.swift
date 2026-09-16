import Foundation
import RideLinkCore
import XCTest

@testable import RideLinkPlatform

/// Production-shaped consent delivery across control lifetimes (ADR-020 A10/A11).
/// The controller, diagnostics channel and transport binding are real production seams. The host
/// controls only when session-owned Start and Connected continuations reach the controller.
/// The app has no XCTest bundle; the source check below pins the exact coordinator decisions.
final class VoiceConsentAcrossLifetimesTests: XCTestCase {
    // MARK: - the production ordering

    /// A tap captures A, but B's explicit availability reduces before delivery; no held offer exists.
    @MainActor
    func testADeferredPredecessorPressUsesRecordedSuccessorAuthorityWithoutAHeldOffer() async throws {
        for isLocalLeader in [true, false] {
            let harness = try await Harness(isLocalLeader: isLocalLeader)
            defer { harness.finish() }
            let host = CoordinatorShapedVoiceHost(controller: harness.controller)
            defer { host.finish() }
            await host.attachVoice(authGeneration: Self.controlA, diagnostics: harness.diagnosticsChannel())
            host.liveAuthenticatedGeneration = Self.controlA
            host.startIntercom()
            XCTAssertEqual(host.capturedStartGenerations, [Self.controlA])
            await harness.controller.onControlLinkLost(retiredControlGeneration: Self.controlA)
            host.liveAuthenticatedGeneration = nil
            await harness.setLiveGeneration(Self.controlB)
            host.liveAuthenticatedGeneration = Self.controlB
            await host.attachVoice(authGeneration: Self.controlB, diagnostics: harness.diagnosticsChannel())
            try await harness.settleViaDiagnostics()
            XCTAssertFalse(host.voiceDiagnostics.localAudioOpen)
            await host.runDeferredWork()
            try await harness.expect("consent is published") { await $0.currentDiagnostics().localAudioOpen }
            if isLocalLeader {
                try await harness.expect("one offer is created") { _ in
                    await harness.engine.recordedCalls().contains("createOffer")
                }
                await harness.engine.emit(.offerCreated(voiceSessionId: Self.expectedResumeVsid, sdp: Self.sdpB))
            }
            try await harness.settleViaDiagnostics()
            let diagnostics = await harness.controller.currentDiagnostics()
            let attempted = await harness.transport.attemptedSends()
            let accepted = await harness.transport.sentGenerations()
            print("delayed Start(A), leader=\(isLocalLeader): status=\(diagnostics.status), id=\(String(describing: diagnostics.voiceSessionPrefix)), capture=\(diagnostics.localAudioOpen), attempted=\(attempted.map { $0.1 }), accepted=\(accepted)")
            XCTAssertEqual(diagnostics.status, .negotiating)
            XCTAssertEqual(diagnostics.voiceSessionPrefix, isLocalLeader ? Self.expectedResumeVsid.description : nil)
            XCTAssertFalse(attempted.isEmpty)
            XCTAssertTrue(attempted.allSatisfy { $0.1 == Self.controlB }, "A supplies consent only")
            XCTAssertEqual(accepted.count, attempted.count, "all sends must be accepted by the B-bound transport")
            let sent = await harness.transport.sentSignals()
            if isLocalLeader {
                XCTAssertTrue(sent.contains { if case .offer(let id, _) = $0 { id == Self.expectedResumeVsid } else { false } })
            } else {
                XCTAssertTrue(sent.contains { if case .state(nil, .negotiating, _, _) = $0 { true } else { false } })
            }
            let calls = await harness.engine.recordedCalls()
            XCTAssertEqual(calls.filter { $0 == "createOffer" }.count, isLocalLeader ? 1 : 0)
            let before = attempted.count
            try await harness.settleViaDiagnostics()
            let after = await harness.transport.attemptedSends().count
            XCTAssertEqual(after, before, "no second tap, Connected, held offer or retry event")
            await harness.controller.shutdown()
        }
    }

    /// A stale explicit Start(A) supplies consent to B's held offer, without a second press (P66).
    @MainActor
    func testAPressDeliveredAfterItsLifetimeDiedStillAnswersTheSuccessorsHeldOffer() async throws {
        let harness = try await Harness(isLocalLeader: false)
        defer { harness.finish() }
        let host = CoordinatorShapedVoiceHost(controller: harness.controller)
        defer { host.finish() }
        await host.attachVoice(authGeneration: Self.controlA, diagnostics: harness.diagnosticsChannel())

        // 1-2. The press, with A live. Delivery is held exactly where the session-owned task holds it.
        host.liveAuthenticatedGeneration = Self.controlA
        host.startIntercom()
        XCTAssertFalse(host.voiceDiagnostics.localAudioOpen, "precondition: the press has not been reduced")

        // B authenticates with no consent yet. Its event records authority without starting media.
        await harness.setLiveGeneration(Self.controlB)
        host.liveAuthenticatedGeneration = Self.controlB
        await host.attachVoice(authGeneration: Self.controlB, diagnostics: harness.diagnosticsChannel())
        XCTAssertFalse(host.voiceDiagnostics.localAudioOpen, "the press has not reduced")

        // 5. B's only offer, admitted under B, held for want of consent.
        harness.controller.submit(.offer(voiceSessionId: Self.bOffer, sdp: Self.sdpB), controlGeneration: Self.controlB)
        try await harness.expect("B's offer is held") { await $0.currentDiagnostics().peerRequestedVoice }
        var calls = await harness.engine.recordedCalls()
        XCTAssertFalse(calls.contains("applyRemote(OFFER)"), "a peer's offer never opens the microphone")

        // 6. The deferred press finally reaches the controller. This is the last event production produces.
        await host.runDeferredWork()

        // Progress, from that press alone.
        try await harness.expect("the held offer is answered") { _ in
            await harness.engine.recordedCalls().contains("createAnswer")
        }
        calls = await harness.engine.recordedCalls()
        XCTAssertTrue(calls.contains("applyRemote(OFFER)"), "B's held offer must be applied; calls=\(calls)")

        // `diagnostics.localAudioOpen` is `state.localAudioOpen && transmission.captureOpen`, and the
        // second half arrives through the *intercom* mailbox — `startLocalAudio` offers `.captureOpen`
        // rather than writing it — so it is published one drain later than the engine call. Waiting on
        // the observable is the claim; reading straight after `createAnswer` was a 2-in-25 flake in
        // this test's own first draft, not a production ordering.
        try await harness.expect("consent is published") { await $0.currentDiagnostics().localAudioOpen }
        let diagnostics = await harness.controller.currentDiagnostics()
        XCTAssertEqual(diagnostics.status, .negotiating)
        XCTAssertEqual(diagnostics.voiceSessionPrefix, Self.bOffer.description, "the negotiation is B's own")
        XCTAssertTrue(diagnostics.localAudioOpen, "consent is consent")
        XCTAssertNil(
            diagnostics.droppedSignals[.supersededStartLifetime],
            "the press was not refused — its consent was used and only its control authority ignored"
        )
        XCTAssertNil(diagnostics.droppedSignals[.retiredHeldOffer], "and B's offer was not discarded")

        // And the answer it produces is written on B, never on the lifetime that pressed.
        await harness.engine.emit(.answerCreated(voiceSessionId: Self.bOffer, sdp: Self.sdpB))
        try await harness.expect("the answer is written") { _ in
            await harness.transport.sentSignals().contains { if case .answer = $0 { true } else { false } }
        }
        let sent = await harness.transport.sentSignals()
        let generations = await harness.transport.sentGenerations()
        XCTAssertTrue(generations.allSatisfy { $0 == Self.controlB }, "generations=\(generations)")
        XCTAssertTrue(
            sent.contains { if case .answer(let id, _) = $0 { id == Self.bOffer } else { false } },
            "sent=\(sent)"
        )
        let counts = await harness.audio.captureCounts()
        XCTAssertEqual(counts.opened, 1, "capture opened once")
        XCTAssertEqual(counts.closed, 0, "and was never closed by any of it")
        await harness.controller.shutdown()
    }

    /// P69-B: A dies; a press captures nil; Connected(B) runs with published capture still false;
    /// only then is Start(nil) delivered. Production's Connected input must make this progress.
    @MainActor
    func testAGapPressDeliveredAfterTheSuccessorsConnectedStillStartsExactlyOneNegotiation() async throws {
        let harness = try await Harness(isLocalLeader: true)
        defer { harness.finish() }
        let host = CoordinatorShapedVoiceHost(controller: harness.controller)
        defer { host.finish() }
        await host.attachVoice(authGeneration: Self.controlA, diagnostics: harness.diagnosticsChannel())

        // 1-2. A dies. The boundary is offered — and `await`ed to its mailbox entry — before the
        //      press is queued, and `VoiceInputMailbox`'s own lane order (`.teardown` outranks
        //      `.critical`) keeps it ahead of the press regardless, which is what makes the gap a
        //      gap: the press reduces against a table whose recorded lifetime A has already left.
        await harness.controller.onControlLinkLost(retiredControlGeneration: Self.controlA)
        await harness.setLiveGeneration(nil)
        host.liveAuthenticatedGeneration = nil

        // The press happens in the gap. Delivery is held where the session-owned task holds it, and
        // the generation it read is nil — the honest tap-time value.
        host.startIntercom()

        // B authenticates before the press reduces. The published projection is still false;
        // Connected now supplies explicit authority instead of making a one-time projection decision.
        await harness.setLiveGeneration(Self.controlB)
        host.liveAuthenticatedGeneration = Self.controlB
        await host.attachVoice(authGeneration: Self.controlB, diagnostics: harness.diagnosticsChannel())
        XCTAssertFalse(host.voiceDiagnostics.localAudioOpen, "Connected ran before consent was published")
        XCTAssertEqual(host.capturedStartGenerations.count, 1)
        XCTAssertNil(host.capturedStartGenerations[0], "the tap captured nil and is never relabelled")

        // 4. The deferred press finally reaches the controller, carrying nil. No second press is
        //    supplied — that was problem 66's regression mistake, and this test must not repeat it.
        await host.runDeferredWork()

        // Progress, from that press and the authentication event alone.
        try await harness.expect("the gap press resumes under B") { _ in
            await harness.engine.recordedCalls().contains("createOffer")
        }

        try await harness.expect("capture is published") { await $0.currentDiagnostics().localAudioOpen }
        let diagnostics = await harness.controller.currentDiagnostics()
        XCTAssertEqual(diagnostics.status, .negotiating)
        XCTAssertEqual(diagnostics.voiceSessionPrefix, Self.expectedResumeVsid.description, "a fresh voice_session_id, minted by the resume")
        XCTAssertTrue(diagnostics.localAudioOpen, "consent is consent")

        // The engine's own offer callback — the event sink production's real engine fires, and the
        // fake records the call and leaves the callback to the test — so the written offer names B
        // and only B.
        await harness.engine.emit(.offerCreated(voiceSessionId: Self.expectedResumeVsid, sdp: Self.sdpB))
        try await harness.expect("the resume's offer is written") { _ in
            await harness.transport.sentSignals().contains { if case .offer = $0 { true } else { false } }
        }
        let generations = await harness.transport.sentGenerations()
        XCTAssertEqual(
            Set(generations.compactMap { $0 }),
            [Self.controlB],
            "the whole negotiation is B's; the nil press wrote nothing; generations=\(generations)"
        )

        // And B's own boundary can retire it — the resume is genuinely B-owned, not owned by nobody.
        await harness.controller.onControlLinkLost(retiredControlGeneration: Self.controlB)
        try await harness.expect("B retires what B established") { await $0.currentDiagnostics().status == .idle }
        let final = await harness.controller.currentDiagnostics()
        XCTAssertEqual(final.status, .idle)
        let counts = await harness.audio.captureCounts()
        XCTAssertEqual(counts.opened, 1, "capture opened once")
        XCTAssertEqual(counts.closed, 0, "and the boundary never closes it")
        await harness.controller.shutdown()
    }

    /// P69-A/F: the gap press reduces first; duplicate Connected(B) creates one negotiation.
    @MainActor
    func testADuplicateAvailabilityEventConsumesThePendingIntentAtMostOnce() async throws {
        let harness = try await Harness(isLocalLeader: true)
        defer { harness.finish() }
        let host = CoordinatorShapedVoiceHost(controller: harness.controller)
        defer { host.finish() }
        await host.attachVoice(authGeneration: Self.controlA, diagnostics: harness.diagnosticsChannel())

        // A dies first — the gap press must reduce against a table whose recorded lifetime has
        // gone, or it would resolve against stale A rather than recording the pending intent.
        await harness.controller.onControlLinkLost(retiredControlGeneration: Self.controlA)
        host.liveAuthenticatedGeneration = nil
        host.startIntercom()
        await host.runDeferredWork()
        try await harness.expect("consent is recorded") { await $0.currentDiagnostics().localAudioOpen }
        var diagnostics = await harness.controller.currentDiagnostics()
        XCTAssertEqual(diagnostics.status, .idle, "the gap press starts no negotiation")

        // Two availability events for the same successor.
        await harness.setLiveGeneration(Self.controlB)
        await host.attachVoice(authGeneration: Self.controlB, diagnostics: harness.diagnosticsChannel())
        await host.attachVoice(authGeneration: Self.controlB, diagnostics: harness.diagnosticsChannel())

        try await harness.expect("the pending intent resumes once") { _ in
            await harness.engine.recordedCalls().filter { $0 == "createOffer" }.count == 1
        }
        await harness.controller.start(controlGeneration: Self.controlB)
        try await harness.settleViaDiagnostics()
        diagnostics = await harness.controller.currentDiagnostics()
        XCTAssertEqual(diagnostics.status, .negotiating)
        let offers = await harness.engine.recordedCalls().filter { $0 == "createOffer" }
        XCTAssertEqual(offers.count, 1, "exactly one offer, no double resume; calls=\(offers)")
        XCTAssertNil(diagnostics.droppedSignals[.authenticatedDuringLiveNegotiation])
        await harness.controller.shutdown()
    }

    /// P69-D: Stop withdraws the pending intent and releases capture before B arrives.
    @MainActor
    func testAnExplicitStopBeforeTheSuccessorClearsThePendingIntent() async throws {
        let harness = try await Harness(isLocalLeader: true)
        defer { harness.finish() }
        let host = CoordinatorShapedVoiceHost(controller: harness.controller)
        defer { host.finish() }
        await host.attachVoice(authGeneration: Self.controlA, diagnostics: harness.diagnosticsChannel())

        // A dies first — the same gap as P69-B and P69-F, or the press would resolve against A.
        await harness.controller.onControlLinkLost(retiredControlGeneration: Self.controlA)
        host.liveAuthenticatedGeneration = nil
        host.startIntercom()
        await host.runDeferredWork()
        try await harness.expect("consent is recorded") { await $0.currentDiagnostics().localAudioOpen }

        // The user withdrew the request with the same action that withdrew consent.
        await harness.controller.stop()
        try await harness.expect("capture is released") { await !$0.currentDiagnostics().localAudioOpen }

        // The successor authenticates. There is nothing to resume, and nothing starts.
        await harness.setLiveGeneration(Self.controlB)
        await host.attachVoice(authGeneration: Self.controlB, diagnostics: harness.diagnosticsChannel())
        try await harness.settleViaDiagnostics()
        let diagnostics = await harness.controller.currentDiagnostics()
        XCTAssertEqual(diagnostics.status, .idle, "no negotiation: the stop consumed the intent")
        let calls = await harness.engine.recordedCalls()
        XCTAssertFalse(calls.contains("createOffer"), "nothing was offered; calls=\(calls)")
        let sent = await harness.transport.sentSignals()
        XCTAssertTrue(sent.isEmpty, "nothing was sent; sent=\(sent)")
        await harness.controller.shutdown()
    }

    /// P69-A, C, F: consume once for either role; refusal preserves consent without retrying.
    @MainActor
    func testPendingIntentResumesBothRolesAndSendFailureDoesNotRetry() async throws {
        for leader in [true, false] {
            let h = try await Harness(isLocalLeader: leader)
            defer { h.finish() }
            let host = CoordinatorShapedVoiceHost(controller: h.controller)
            defer { host.finish() }
            await host.attachVoice(authGeneration: Self.controlA, diagnostics: h.diagnosticsChannel())
            await h.controller.onControlLinkLost(retiredControlGeneration: Self.controlA)
            await h.setLiveGeneration(nil)
            host.liveAuthenticatedGeneration = nil
            host.startIntercom()
            await host.runDeferredWork()
            try await h.expect("gap consent") { await $0.currentDiagnostics().localAudioOpen }
            let gap = await h.controller.currentDiagnostics()
            XCTAssertEqual(gap.status, .idle)
            XCTAssertNil(gap.voiceSessionPrefix)
            let gapCalls = await h.engine.recordedCalls()
            XCTAssertFalse(gapCalls.contains("createOffer"))

            await h.setLiveGeneration(Self.controlB)
            // The answerer's only critical send is intent-to-talk. Offerer failure is the offer.
            if !leader { await h.transport.setAccept(false) }
            await host.attachVoice(authGeneration: Self.controlB, diagnostics: h.diagnosticsChannel())
            if leader {
                try await h.expect("one offer") { _ in await h.engine.recordedCalls().contains("createOffer") }
                await h.transport.setAccept(false)
                await h.engine.emit(.offerCreated(voiceSessionId: Self.expectedResumeVsid, sdp: Self.sdpB))
            }
            try await h.expect("critical send failure degrades") { controller in
                let d = await controller.currentDiagnostics()
                let attempts = await h.transport.attemptedSends()
                return d.status == .idle && !attempts.isEmpty
            }
            try await h.settleViaDiagnostics()
            let attempts = await h.transport.attemptedSends()
            XCTAssertEqual(attempts.count, leader ? 2 : 1)
            XCTAssertTrue(attempts.allSatisfy { $0.1 == Self.controlB })
            let calls = await h.engine.recordedCalls()
            XCTAssertEqual(calls.filter { $0 == "createOffer" }.count, leader ? 1 : 0)
            for _ in 0..<3 {
                await host.attachVoice(authGeneration: Self.controlB, diagnostics: h.diagnosticsChannel())
                await host.runDeferredWork()
                try await h.settleViaDiagnostics()
                let count = await h.transport.attemptedSends().count
                let currentCalls = await h.engine.recordedCalls()
                XCTAssertEqual(count, attempts.count, "duplicate Connected cannot retry a failed send")
                XCTAssertEqual(currentCalls.filter { $0 == "createOffer" }.count, leader ? 1 : 0)
            }
            let failed = await h.controller.currentDiagnostics()
            XCTAssertTrue(failed.localAudioOpen)
            // A legitimate new control successor still owns the normal §7.8 reconnect.
            await h.transport.setAccept(true)
            await h.setLiveGeneration(3)
            await host.attachVoice(authGeneration: 3, diagnostics: h.diagnosticsChannel())
            try await h.expect("new successor can rebuild") { await $0.currentDiagnostics().status == .negotiating }
            await h.controller.shutdown()
        }
    }

    /// P69-H: nil supplies only consent; the authenticated held offer supplies B's identity.
    @MainActor
    func testHeldSuccessorOfferUsesNilPressConsentOnce() async throws {
        let h = try await Harness(isLocalLeader: false)
        defer { h.finish() }
        let host = CoordinatorShapedVoiceHost(controller: h.controller)
        defer { host.finish() }
        await host.attachVoice(authGeneration: Self.controlA, diagnostics: h.diagnosticsChannel())
        await h.controller.onControlLinkLost(retiredControlGeneration: Self.controlA)
        host.liveAuthenticatedGeneration = nil
        host.startIntercom()
        await h.setLiveGeneration(Self.controlB)
        await host.attachVoice(authGeneration: Self.controlB, diagnostics: h.diagnosticsChannel())
        h.controller.submit(.offer(voiceSessionId: Self.bOffer, sdp: Self.sdpB), controlGeneration: Self.controlB)
        try await h.expect("held B") { await $0.currentDiagnostics().peerRequestedVoice }
        await host.runDeferredWork()
        try await h.expect("answered B") { _ in await h.engine.recordedCalls().contains("createAnswer") }
        await h.engine.emit(.answerCreated(voiceSessionId: Self.bOffer, sdp: Self.sdpB))
        try await h.settleViaDiagnostics()
        let answers = await h.transport.sentSignals().filter { if case .answer = $0 { true } else { false } }
        let generations = await h.transport.sentGenerations()
        XCTAssertEqual(answers.count, 1)
        XCTAssertTrue(generations.allSatisfy { $0 == Self.controlB })
        await h.controller.onControlLinkLost(retiredControlGeneration: Self.controlA)
        try await h.expect("A cannot retire B") { await $0.currentDiagnostics().droppedSignals[.supersededControlLifetime] == 1 }
        let calls = await h.engine.recordedCalls()
        XCTAssertEqual(calls.filter { $0 == "createAnswer" }.count, 1)
        await h.controller.onControlLinkLost(retiredControlGeneration: Self.controlB)
        try await h.expect("B retires B") { await $0.currentDiagnostics().status == .idle }
        await h.controller.shutdown()
    }

    /// P69-E: B's authorisation is checked again after an actual suspended transport send.
    @MainActor
    func testResumedBOfferCannotBeWrittenThroughC() async throws {
        let h = try await Harness(isLocalLeader: true)
        defer { h.finish() }
        await h.controller.start(controlGeneration: nil)
        try await h.expect("gap consent") { await $0.currentDiagnostics().localAudioOpen }
        await h.setLiveGeneration(Self.controlB)
        await h.controller.controlAuthenticated(controlGeneration: Self.controlB)
        try await h.expect("offer created") { _ in await h.engine.recordedCalls().contains("createOffer") }
        await h.transport.armSendGate(.offerOrAnswer)
        let id = VoiceSessionId(String(repeating: "f", count: 31) + "2")
        await h.engine.emit(.offerCreated(voiceSessionId: id, sdp: Self.sdpB))
        // The park is an actor observable. Yielding schedules work; the watchdog alone uses time.
        let parked = expectation(description: "send parked")
        let observer = Task {
            while !(await h.transport.isSendGateHolding()) { await Task.yield() }
            parked.fulfill()
        }
        await fulfillment(of: [parked], timeout: 5)
        observer.cancel()
        await h.setLiveGeneration(3)
        await h.controller.onControlLinkLost(retiredControlGeneration: Self.controlB)
        await h.controller.controlAuthenticated(controlGeneration: 3)
        await h.transport.releaseSendGate(result: true)
        try await h.expect("C rebuild") { _ in await h.engine.recordedCalls().filter { $0 == "createOffer" }.count == 2 }
        try await h.settleViaDiagnostics()
        let sent = await h.transport.sentSignals()
        XCTAssertFalse(sent.contains { if case .offer(let session, _) = $0 { session == id } else { false } })
        let attempted = await h.transport.attemptedSends()
        XCTAssertEqual(attempted.filter { if case .offer = $0.0 { true } else { false } }.map { $0.1 }, [Self.controlB])
        await h.controller.shutdown()
    }

    @MainActor
    func testAnswerersDelayedGapPressStatesIntentUnderBWithoutOffering() async throws {
        let h = try await Harness(isLocalLeader: false)
        defer { h.finish() }
        let host = CoordinatorShapedVoiceHost(controller: h.controller)
        defer { host.finish() }
        await host.attachVoice(authGeneration: Self.controlA, diagnostics: h.diagnosticsChannel())
        await h.controller.onControlLinkLost(retiredControlGeneration: Self.controlA)
        host.liveAuthenticatedGeneration = nil
        host.startIntercom()
        await h.setLiveGeneration(Self.controlB)
        await host.attachVoice(authGeneration: Self.controlB, diagnostics: h.diagnosticsChannel())
        XCTAssertFalse(host.voiceDiagnostics.localAudioOpen)
        await host.runDeferredWork()
        try await h.expect("answerer intent") { await $0.currentDiagnostics().status == .negotiating }
        try await h.settleViaDiagnostics()
        let signals = await h.transport.sentSignals()
        let generations = await h.transport.sentGenerations()
        let calls = await h.engine.recordedCalls()
        XCTAssertTrue(signals.contains { if case .state(nil, .negotiating, _, _) = $0 { true } else { false } })
        XCTAssertTrue(generations.allSatisfy { $0 == Self.controlB })
        XCTAssertFalse(calls.contains("createOffer"))
        await h.controller.shutdown()
    }

    @MainActor
    func testSessionShutdownClearsPendingConsentAndReleasesCapture() async throws {
        let h = try await Harness(isLocalLeader: true)
        defer { h.finish() }
        await h.controller.start(controlGeneration: nil)
        try await h.expect("gap consent") { await $0.currentDiagnostics().localAudioOpen }
        await h.controller.shutdown()
        let diagnostics = await h.controller.currentDiagnostics()
        let counts = await h.audio.captureCounts()
        XCTAssertEqual(diagnostics.status, .idle)
        XCTAssertFalse(diagnostics.localAudioOpen)
        XCTAssertEqual(counts.closed, 1)
    }

    // MARK: - the mirror is checked against the real source

    /// Fails if `SessionCoordinator` stops making the decisions `CoordinatorShapedVoiceHost` mirrors.
    ///
    /// Whitespace is normalised and only the load-bearing fragments are matched, so reformatting does not
    /// break it. Removing session ownership, dropping availability, or restoring projection-based
    /// Start emission does. The mirrored press remains deferred.
    func testTheMirroredCoordinatorDecisionsAreStillTheOnesProductionMakes() throws {
        let source = try Self.sessionCoordinatorSource()
        let normalised = Self.squash(source)
        let attach = source.components(separatedBy: "private func attachVoice")[1]
        let reconnect = attach.components(separatedBy: "let manager = controlSessionManager")[0]
        XCTAssertEqual(reconnect.components(separatedBy: "voice.controlAuthenticated(").count - 1, 1)
        XCTAssertFalse(reconnect.contains("voice.start("))
        XCTAssertFalse(reconnect.contains("voiceDiagnostics.localAudioOpen"))
        for fragment in [
            // 1. the press reads the live generation synchronously …
            "let generation = controlSessionManager.liveAuthenticatedGeneration()",
            // 2. … and delivers the start on a later turn, which is what reorders it against inbound
            //    frames. Session-owned since STATUS §4 problem 67, which changes who joins it and not
            //    when it runs — `VoiceController.start` is actor-isolated, so the hop is unavoidable.
            "launchInSession { _ in await voice.start(controlGeneration: generation) }",
            // 3. Connected supplies its own lifetime in session-owned work. There is one event,
            //    with no separate projection-gated Start that could retry a consumed intent.
            "launchInSession { _ in await voice.controlAuthenticated(controlGeneration: authGeneration) }",
            // 4. The initial installation supplies its lifetime through the same API.
            "await controller.controlAuthenticated(controlGeneration: authGeneration)",
            "launchInSession { _ in await voice.stop() }",
            "launchInSession { _ in await voice.setMicrophoneMuted(muted) }",
            "for task in endingWork.values { task.cancel() }",
            "for task in endingWork.values { await task.value }",
        ] {
            XCTAssertTrue(
                normalised.contains(Self.squash(fragment)),
                """
                SessionCoordinator no longer contains:
                    \(fragment)
                VoiceConsentAcrossLifetimesTests mirrors that decision. Re-derive the mirror — and check \
                whether the ordering it reproduces is still reachable — rather than deleting this assertion.
                """
            )
        }
    }

    private static func squash(_ text: String) -> String {
        text.split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }

    private static func sessionCoordinatorSource() throws -> String {
        // …/ios/Packages/RideLinkPlatform/Tests/RideLinkPlatformTests/<this file>
        let ios = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()  // RideLinkPlatformTests
            .deletingLastPathComponent()  // Tests
            .deletingLastPathComponent()  // RideLinkPlatform
            .deletingLastPathComponent()  // Packages
            .deletingLastPathComponent()  // ios
        return try String(contentsOf: ios.appendingPathComponent("RideLink/SessionCoordinator.swift"), encoding: .utf8)
    }

    // MARK: - the coordinator's three voice-start decisions, mirrored

    /// A faithful replica of `SessionCoordinator`'s voice-start wiring — and **only** that wiring.
    ///
    /// Two things are deliberately real rather than modelled: the `VoiceController` it drives is
    /// production's, and `voiceDiagnostics` is published through production's `OrderedEventChannel`, fed by
    /// the same `setOnDiagnosticsChanged` callback `attachVoice` installs. The one thing that is not real
    /// is *when* the session-owned task runs — production leaves that to the scheduler, and a test that
    /// left it there would be a race. `runDeferredWork` names the instant instead.
    @MainActor
    private final class CoordinatorShapedVoiceHost {
        private let controller: VoiceController
        private var deferred: [@Sendable () async -> Void] = []

        /// `SessionCoordinator.voiceDiagnostics` — written only by the channel consumer.
        private(set) var voiceDiagnostics = VoiceDiagnostics()
        /// `ControlSessionManager.liveAuthenticatedGeneration()`, as the press reads it.
        var liveAuthenticatedGeneration: Int64?
        private(set) var capturedStartGenerations: [Int64?] = []

        private var attached = false
        private var consumer: Task<Void, Never>?

        init(controller: VoiceController) { self.controller = controller }

        /// `SessionCoordinator.attachVoice`. The first call installs the diagnostics consumer; a later one
        /// is the reconnect branch. Both supply the authenticated event; the pure table decides
        /// whether there is pending intent or existing consent to consume.
        func attachVoice(authGeneration: Int64, diagnostics: AsyncStream<VoiceDiagnostics>) async {
            if !attached {
                attached = true
                consumer = Task { @MainActor [weak self] in
                    for await next in diagnostics { self?.voiceDiagnostics = next }
                }
            }
            // Execute the Connected continuation before or after the separately deferred user press.
            // Production schedules this through launchInSession, with the event's immutable generation.
            await controller.controlAuthenticated(controlGeneration: authGeneration)
        }

        /// `SessionCoordinator.startIntercom`, minus the `RideStartPolicy` gate — which decides whether the
        /// press happens at all, never which lifetime it carries.
        func startIntercom() {
            let generation = liveAuthenticatedGeneration
            capturedStartGenerations.append(generation)
            let controller = self.controller
            deferred.append { await controller.start(controlGeneration: generation) }
        }

        /// Runs what the deferred tasks would have run, in creation order.
        func runDeferredWork() async {
            let work = deferred
            deferred.removeAll()
            for item in work { await item() }
        }

        func finish() { consumer?.cancel() }
    }

    // MARK: - harness

    private final class Harness: @unchecked Sendable {
        let controller: VoiceController
        let engine: FakeVoiceEngine
        let audio: FakeVoiceAudioSession
        let transport: RecordingVoiceTransport
        private let fanout = DiagnosticsFanout()

        init(isLocalLeader: Bool) async throws {
            engine = FakeVoiceEngine()
            audio = FakeVoiceAudioSession()
            transport = RecordingVoiceTransport()
            let counter = Counter()
            controller = VoiceController(
                engine: engine,
                audioSession: audio,
                transport: transport,
                isLocalLeader: isLocalLeader,
                localTrackId: "ridelink-voice",
                newVoiceSessionId: { VoiceSessionId(String(repeating: "f", count: 31) + String(counter.next() % 10)) }
            )
            await controller.attach()
            // One installed handler for the whole harness, fanned out: `setOnDiagnosticsChanged` holds a
            // single callback, so a waiter that installed its own would silently unhook the coordinator's.
            let fanout = fanout
            await controller.setOnDiagnosticsChanged { fanout.publish($0) }
            await transport.setLiveGeneration(VoiceConsentAcrossLifetimesTests.controlA)
        }

        /// A new subscriber to the controller's published diagnostics. Every reduction publishes, so an
        /// edge on this stream *is* the proof that the previous input was applied — no polling, no sleep.
        func diagnosticsChannel() -> AsyncStream<VoiceDiagnostics> { fanout.subscribe() }

        func setLiveGeneration(_ generation: Int64?) async {
            await transport.setLiveGeneration(generation)
        }

        /// Waits for `condition` to hold, driven by diagnostics edges rather than by elapsed time.
        ///
        /// The deadline is a **failure** deadline and never a sequencing device: nothing here is ordered by
        /// it, no assertion depends on it, and a run that reaches it has already failed. It exists so a
        /// regression names the claim that stopped holding instead of hanging the suite.
        func expect(
            _ what: String,
            file: StaticString = #filePath,
            line: UInt = #line,
            _ condition: @escaping @Sendable (VoiceController) async -> Bool
        ) async throws {
            if await condition(controller) { return }
            let edges = fanout.subscribe()
            if await condition(controller) { return }
            let expired = Flag()
            let fanout = fanout
            let watchdog = Task<Void, Never> {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                guard !Task.isCancelled else { return }
                expired.set()
                fanout.publish(VoiceDiagnostics())
            }
            defer { watchdog.cancel() }
            for await _ in edges {
                if await condition(controller) { return }
                if expired.isSet { break }
            }
            XCTFail("never observed: \(what)", file: file, line: line)
        }

        /// A deliberately stale, coalesced engine callback drains after critical voice work and
        /// send failures. Its drop counter proves preceding effects completed, with no time settling.
        func settleViaDiagnostics() async throws {
            let count = await controller.currentDiagnostics().droppedSignals[.staleEngineCallback] ?? 0
            await engine.emit(.remoteTrackChanged(
                voiceSessionId: VoiceSessionId(String(repeating: "0", count: 32)), present: false
            ))
            try await expect("mailbox barrier") {
                await ($0.currentDiagnostics().droppedSignals[.staleEngineCallback] ?? 0) > count
            }
        }

        func finish() { fanout.finish() }
    }

    /// `VoiceController.setOnDiagnosticsChanged` holds one callback. This turns it into many streams.
    private final class DiagnosticsFanout: @unchecked Sendable {
        private let lock = NSLock()
        private var continuations: [AsyncStream<VoiceDiagnostics>.Continuation] = []

        func subscribe() -> AsyncStream<VoiceDiagnostics> {
            AsyncStream { continuation in
                lock.lock()
                continuations.append(continuation)
                lock.unlock()
            }
        }

        func publish(_ diagnostics: VoiceDiagnostics) {
            lock.lock()
            let targets = continuations
            lock.unlock()
            for target in targets { target.yield(diagnostics) }
        }

        func finish() {
            lock.lock()
            let targets = continuations
            continuations.removeAll()
            lock.unlock()
            for target in targets { target.finish() }
        }
    }

    /// A one-way latch the watchdog sets and the waiter reads.
    private final class Flag: @unchecked Sendable {
        private let lock = NSLock()
        private var value = false
        var isSet: Bool {
            lock.lock()
            defer { lock.unlock() }
            return value
        }

        func set() {
            lock.lock()
            value = true
            lock.unlock()
        }
    }

    private final class Counter: @unchecked Sendable {
        private let lock = NSLock()
        private var value = 0
        func next() -> Int {
            lock.lock()
            defer { lock.unlock() }
            value += 1
            return value
        }
    }

    private static let sdpB = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\na=x:B\r\n"
    private static let bOffer = VoiceSessionId(String(repeating: "b", count: 32))
    private static let controlA: Int64 = 1
    private static let controlB: Int64 = 2
    /// P69-B: availability A, availability B, then the deferred press. P69-A: availability A,
    /// gap press, then availability B. In either ordering the establishing input receives id 3.
    private static let expectedResumeVsid = VoiceSessionId(String(repeating: "f", count: 31) + "3")
}
