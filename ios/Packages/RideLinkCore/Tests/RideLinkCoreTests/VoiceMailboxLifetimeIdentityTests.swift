import XCTest
@testable import RideLinkCore

/// **STATUS §4 problem 60 — semantic voice work is scoped by the control lifetime that admitted it,
/// never by the order it happened to arrive in.**
///
/// Problem 50's fix gave `.controlLinkLost` ownership of the remote signals queued below it, and took
/// *offer time* as the instant at which that ownership was least wrong. It is least wrong; it is not
/// right. A discard with no lifetime identity is wrong in both directions, and this file is the pure
/// half of the proof that neither direction remains:
///
/// - **retired work admitted late** — `VoiceSignalRelay.deliver` reads the live generation and then
///   calls `sink.submit`, with nothing spanning the two, so a frame can pass the liveness check and be
///   overtaken by the whole teardown before it is queued;
/// - **live work discarded early** — `.linkLost` reaches `VoiceController` through
///   `SessionCoordinator`'s event consumer and then one further `Task`, while an inbound promotion
///   authenticates a **successor** through `ControlSessionManager.promote`, which waits on nothing that
///   consumer does.
///
/// Every case below is a straight-line sequence of `offer` calls with no scheduling in it at all. That
/// is the point: if the answer depended on *when* anything ran, this file could not exist.
///
/// The mirror is `com.ridelink.core.voice.VoiceMailboxLifetimeIdentityTest`.
final class VoiceMailboxLifetimeIdentityTests: XCTestCase {
    /// Three **control authentication** generations, as `activateAuthenticatedSession` allocates them:
    /// strictly increasing, one per trust-gate pass, never reused. A different identity from the
    /// `voice_session_id`s below, which own a WebRTC negotiation rather than a control lifetime.
    private let controlA: Int64 = 1
    private let controlB: Int64 = 2
    private let controlC: Int64 = 3

    private let vsidA = VoiceSessionId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private let vsidFresh = VoiceSessionId("ffffffffffffffffffffffffffffffff")
    private let sdp = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\n"
    private let candidate = "candidate:1 1 udp 1 192.0.2.11 51234 typ host"

    private var offerA: VoiceSignal { .offer(voiceSessionId: VoiceSessionId(String(repeating: "1", count: 32)), sdp: sdp) }
    private var offerB: VoiceSignal { .offer(voiceSessionId: VoiceSessionId(String(repeating: "2", count: 32)), sdp: sdp) }
    private var offerC: VoiceSignal { .offer(voiceSessionId: VoiceSessionId(String(repeating: "3", count: 32)), sdp: sdp) }

    private func signal(_ signal: VoiceSignal, _ controlGeneration: Int64) -> VoiceInput {
        .signalReceived(signal: signal, controlGeneration: controlGeneration, freshVoiceSessionId: vsidFresh)
    }

    /// **P60-3 — queued A and queued B, then A retires.**
    ///
    /// The case the old blanket discard got exactly backwards: it removed both. A's is the retired
    /// lifetime's and must go; B's was admitted by a lifetime that is still live and is the only copy of
    /// a peer offer that will never be sent again, so deleting it wedges voice for the ride segment
    /// exactly as problem 56 did.
    func testRetiringADiscardsAsQueuedSignalAndKeepsBs() {
        var mailbox = VoiceInputMailbox()
        mailbox.offer(signal(offerA, controlA))
        mailbox.offer(signal(offerB, controlB))

        mailbox.offer(.controlLinkLost(retiredControlGeneration: controlA))

        XCTAssertEqual(mailbox.discardedRetiredSignalCount, 1, "exactly one signal belonged to the retired lifetime")
        XCTAssertEqual(mailbox.retiredControlGenerationFloor, controlA)
        guard case .controlLinkLost? = mailbox.poll() else {
            return XCTFail("the teardown still applies first, and is never suppressed")
        }
        guard case .signalReceived(let survivor, let generation, _)? = mailbox.poll() else {
            return XCTFail("B's offer must still be queued")
        }
        XCTAssertEqual(survivor, offerB)
        XCTAssertEqual(generation, controlB)
        XCTAssertNil(mailbox.poll(), "and nothing else")
    }

    /// **P60-4 — the same two signals, offered in the *opposite* order relative to the retirement.**
    ///
    /// A is retired first, and only then do both signals arrive. Nothing about the verdict changes,
    /// which is the whole claim: `A` is refused because its lifetime is retired, not because it was
    /// sitting in a queue when something ran.
    func testAfterAIsRetiredALateASignalIsRefusedAndALateBSignalIsAccepted() {
        var mailbox = VoiceInputMailbox()
        mailbox.offer(.controlLinkLost(retiredControlGeneration: controlA))
        _ = mailbox.poll() // the consumer applies the teardown; the mailbox is now empty

        XCTAssertEqual(mailbox.offer(signal(offerA, controlA)), .retiredGeneration)
        XCTAssertEqual(mailbox.offer(signal(offerB, controlB)), .accepted(lane: .critical))

        XCTAssertEqual(mailbox.refusedRetiredSignalCount, 1, "the retired lifetime's late signal is counted, not silent")
        XCTAssertEqual(mailbox.discardedRetiredSignalCount, 0, "nothing was queued to discard")
        guard case .signalReceived(_, let generation, _)? = mailbox.poll() else { return XCTFail("B survives") }
        XCTAssertEqual(generation, controlB)
        XCTAssertNil(mailbox.poll())
    }

    /// A refusal must not look like an overflow: nothing was lost that still mattered.
    func testARefusedRetiredSignalIsNotCountedAsAnOverflow() {
        var mailbox = VoiceInputMailbox()
        mailbox.offer(.controlLinkLost(retiredControlGeneration: controlA))
        for _ in 0..<100 { mailbox.offer(signal(offerA, controlA)) }

        XCTAssertEqual(mailbox.overflowCount, 0, "a retired signal occupies no lane, so it can overflow none")
        XCTAssertEqual(mailbox.refusedRetiredSignalCount, 100)
    }

    /// Every lane a peer signal can land in, not just the critical one.
    func testRetirementReachesAllFourLanesAndNoLocalInput() {
        var mailbox = VoiceInputMailbox()
        let peerSignals: [VoiceSignal] = [
            .offer(voiceSessionId: vsidA, sdp: sdp),
            .iceCandidate(voiceSessionId: vsidA, candidate: candidate, sdpMid: nil, sdpMlineIndex: 0),
            .state(voiceSessionId: vsidA, state: .closed, micMuted: false, mode: .continuous),
            .state(voiceSessionId: vsidA, state: .active, micMuted: false, mode: .continuous),
        ]
        for peerSignal in peerSignals { mailbox.offer(signal(peerSignal, controlA)) }
        let localInputs: [VoiceInput] = [
            .startRequested(freshVoiceSessionId: vsidFresh),
            .localOfferCreated(voiceSessionId: vsidA, sdp: sdp),
            .localCandidateGathered(voiceSessionId: vsidA, candidate: candidate, sdpMid: nil, sdpMlineIndex: 0),
            .muteRequested(muted: true),
            .modeSelected(mode: .ptt),
            .remoteTrackChanged(voiceSessionId: vsidA, present: true),
        ]
        for local in localInputs { mailbox.offer(local) }

        mailbox.offer(.controlLinkLost(retiredControlGeneration: controlA))

        XCTAssertEqual(mailbox.discardedRetiredSignalCount, 4, "one per lane the retired lifetime occupied")
        for peerSignal in peerSignals {
            XCTAssertNotEqual(mailbox.offer(signal(peerSignal, controlB)), .retiredGeneration)
        }

        var drained: [VoiceInput] = []
        while let next = mailbox.poll() { drained.append(next) }
        let survivors = drained.compactMap { input -> Int64? in
            if case .signalReceived(_, let generation, _) = input { return generation }
            return nil
        }
        XCTAssertEqual(survivors.count, 4, "every one of B's four signals survives")
        XCTAssertTrue(survivors.allSatisfy { $0 == controlB }, "and every survivor is B's")
        // `VoiceInput` is deliberately not `Equatable` (it carries a `VoiceSignal`), so identity here
        // is the case's own description — enough to distinguish six different local inputs.
        let drainedDescriptions = Set(drained.map { String(describing: $0) })
        for local in localInputs {
            XCTAssertTrue(
                drainedDescriptions.contains(String(describing: local)),
                "a local input is never a retired peer's to withdraw: \(local)"
            )
        }
    }

    /// **The window a boundary alone cannot close.** An A-generation signal that passed
    /// `VoiceSignalRelay`'s liveness check an instant before the teardown can be offered while B's work
    /// is already queued and **before** `.controlLinkLost(A)` has been delivered at all — that gap is
    /// Window 1, and neither this type nor its callers serialise it.
    ///
    /// The existence of B's admitted work is itself the proof that A ended: `ControlSessionManager`
    /// holds one authenticated connection at a time and allocates a strictly greater generation for
    /// each. So A's late signal is refused here with no boundary in sight.
    ///
    /// The `.coalesced` lane is why this matters rather than merely being tidy: one slot per kind,
    /// latest wins, and PROTOCOL §7.3's `negotiating` intent-to-talk lives in it. A retired lifetime's
    /// peer state overwriting the successor's intent loses the one message that starts the successor's
    /// negotiation, and voice is wedged for the ride segment.
    func testALateRetiredSignalCannotOverwriteASuccessorsWithNoLinkLossDelivered() {
        var mailbox = VoiceInputMailbox()
        let successorIntent = VoiceSignal.state(voiceSessionId: nil, state: .negotiating, micMuted: false, mode: .continuous)
        let retiredState = VoiceSignal.state(voiceSessionId: vsidA, state: .idle, micMuted: false, mode: .continuous)

        mailbox.offer(signal(successorIntent, controlB))
        let outcome = mailbox.offer(signal(retiredState, controlA))

        XCTAssertEqual(outcome, .retiredGeneration, "B's admitted work proves A has ended")
        XCTAssertNil(mailbox.retiredControlGenerationFloor, "and no .controlLinkLost has been delivered at all")
        XCTAssertEqual(mailbox.newestAdmittedControlGeneration, controlB)
        guard case .signalReceived(let held, _, _)? = mailbox.poll() else { return XCTFail("the slot still holds one") }
        XCTAssertEqual(held, successorIntent, "the successor's intent-to-talk is still the slot's value")
    }

    /// The same implication applied to work already queued: admitting a newer generation's signal
    /// retires the older one's on the spot, without waiting for its boundary.
    func testAdmittingANewerGenerationDiscardsTheOlderOnesQueuedWorkImmediately() {
        var mailbox = VoiceInputMailbox()
        mailbox.offer(signal(offerA, controlA))

        mailbox.offer(signal(offerB, controlB))

        XCTAssertEqual(mailbox.discardedRetiredSignalCount, 1)
        guard case .signalReceived(_, let generation, _)? = mailbox.poll() else { return XCTFail("B survives") }
        XCTAssertEqual(generation, controlB)
        XCTAssertNil(mailbox.poll())
    }

    /// The other half: coalescing *within* one live lifetime is untouched — latest still wins.
    func testTwoPeerStatesFromTheSameGenerationStillCoalesceToTheNewest() {
        var mailbox = VoiceInputMailbox()
        let older = VoiceSignal.state(voiceSessionId: vsidA, state: .connecting, micMuted: false, mode: .continuous)
        let newer = VoiceSignal.state(voiceSessionId: vsidA, state: .active, micMuted: false, mode: .continuous)

        XCTAssertEqual(mailbox.offer(signal(older, controlB)), .accepted(lane: .coalesced))
        XCTAssertEqual(mailbox.offer(signal(newer, controlB)), .coalesced)

        guard case .signalReceived(let held, _, _)? = mailbox.poll() else { return XCTFail("one slot, newest value") }
        XCTAssertEqual(held, newer)
        XCTAssertEqual(mailbox.refusedRetiredSignalCount, 0, "same lifetime, so nothing is stale")
    }

    /// **The teardown itself is never suppressed**, and this is the regression that keeps it that way.
    ///
    /// Suppressing a boundary that a newer lifetime appeared to have superseded was tried while
    /// building these regressions and **rejected** — see STATUS §4 problem 61. Admission is not
    /// application: a successor's admitted offer can be dropped by `offerReceived`'s
    /// `.generationMismatch` against a still-live predecessor negotiation, so "a newer generation
    /// admitted something" does not imply its negotiation is live, and suppressing on that premise
    /// leaves a dead lifetime's negotiation standing with nothing able to replace it. The residue — a
    /// boundary applied after a successor's work has already been *reduced* — needs the pure table to
    /// know which control lifetime owns a negotiation, and is recorded rather than half-fixed.
    func testALinkLossIsDeliveredToTheReducerWhateverElseTheMailboxHasAdmitted() {
        var mailbox = VoiceInputMailbox()
        mailbox.offer(signal(offerB, controlB))
        _ = mailbox.poll() // the consumer applies B's offer

        XCTAssertEqual(mailbox.offer(.controlLinkLost(retiredControlGeneration: controlA)), .accepted(lane: .teardown))

        guard case .controlLinkLost? = mailbox.poll() else {
            return XCTFail("the reducer must still be told to stop the media")
        }
        XCTAssertEqual(mailbox.retiredControlGenerationFloor, controlA, "and A is retired by it")
        XCTAssertEqual(mailbox.offer(signal(offerA, controlA)), .retiredGeneration)
    }

    /// **P60-5 — A -> B -> C.** Retiring A and then B must leave C untouched, and a late signal from
    /// either retired lifetime must stay inert however long afterwards it arrives.
    func testRepeatedReconnectsRetireOnlyWhatHasActuallyEnded() {
        var mailbox = VoiceInputMailbox()

        mailbox.offer(.controlLinkLost(retiredControlGeneration: controlA))
        _ = mailbox.poll()
        mailbox.offer(.controlLinkLost(retiredControlGeneration: controlB))
        _ = mailbox.poll()

        XCTAssertEqual(mailbox.offer(signal(offerA, controlA)), .retiredGeneration, "A after B")
        XCTAssertEqual(mailbox.offer(signal(offerB, controlB)), .retiredGeneration, "B after B")
        XCTAssertEqual(mailbox.offer(signal(offerC, controlC)), .accepted(lane: .critical))

        // C is live; A and C both arrive again, in that order, and the verdicts are unchanged.
        XCTAssertEqual(mailbox.offer(signal(offerA, controlA)), .retiredGeneration, "A after C")
        XCTAssertEqual(mailbox.offer(signal(offerC, controlC)), .accepted(lane: .critical))

        var survivors: [Int64] = []
        while let next = mailbox.poll() {
            if case .signalReceived(_, let generation, _) = next { survivors.append(generation) }
        }
        XCTAssertEqual(survivors, [controlC, controlC], "only the live lifetime's work survives")
        XCTAssertEqual(mailbox.refusedRetiredSignalCount, 3)
    }

    /// The floor rises and never falls. A link loss for an **older** lifetime arriving after a newer one
    /// has already been retired is stale news, and must not un-retire the newer one.
    ///
    /// This is the case ADR-024 Amendment A7 makes non-hypothetical: generation *arrival* is
    /// deliberately non-monotonic, so `retire(B)` then `retire(A)` is a real ordering.
    func testALateLinkLossForAnOlderGenerationCannotLowerTheRetiredFloor() {
        var mailbox = VoiceInputMailbox()
        mailbox.offer(.controlLinkLost(retiredControlGeneration: controlB))
        _ = mailbox.poll()

        mailbox.offer(.controlLinkLost(retiredControlGeneration: controlA))

        XCTAssertEqual(mailbox.retiredControlGenerationFloor, controlB, "B stays retired; A's late notice adds nothing")
        XCTAssertEqual(mailbox.offer(signal(offerB, controlB)), .retiredGeneration)
        XCTAssertEqual(mailbox.offer(signal(offerC, controlC)), .accepted(lane: .critical))
    }

    /// **P60-6 — a genuinely new ride session.** A fresh mailbox has no floor, so nothing a previous
    /// session retired can poison it.
    ///
    /// That this is the *right* reset point rather than an arbitrary one is a fact about the producers,
    /// and `ControlSessionGenerationMonotonicityTests` (RideLinkPlatform) proves the half this file
    /// cannot see: `authenticationGeneration` strictly increases across a `shutdown()`/`startListening()`
    /// cycle and is never reset, so even a mailbox that *did* survive a session could only ever hold a
    /// floor below every generation the next session will use.
    func testANewSessionsMailboxStartsWithNothingRetired() {
        var first = VoiceInputMailbox()
        first.offer(.controlLinkLost(retiredControlGeneration: controlC))
        XCTAssertEqual(first.retiredControlGenerationFloor, controlC)

        var second = VoiceInputMailbox()
        XCTAssertNil(second.retiredControlGenerationFloor, "a new VoiceController's mailbox retires nothing")
        XCTAssertNil(second.newestAdmittedControlGeneration)
        XCTAssertEqual(second.offer(signal(offerA, controlA)), .accepted(lane: .critical))
    }

    /// **P60-7 — a `.stopRequested` is not a lifetime boundary.** The user pressed End Voice; the link is
    /// still up. It retires nothing and discards nothing, and — problem 57 — it is never displaced by a
    /// link loss, because it is the only input `shutdown()` can complete on (ADR-026 / rule 21).
    func testAStopRetiresNoGenerationAndIsNeverErasedByALinkLoss() {
        var mailbox = VoiceInputMailbox()
        mailbox.offer(signal(offerA, controlA))
        mailbox.offer(.stopRequested)

        XCTAssertNil(mailbox.retiredControlGenerationFloor, "End Voice ends no control lifetime")
        XCTAssertEqual(mailbox.discardedRetiredSignalCount, 0)

        mailbox.offer(.controlLinkLost(retiredControlGeneration: controlA))

        guard case .stopRequested? = mailbox.poll() else { return XCTFail("the stop survives; nothing may replace it") }
        XCTAssertEqual(mailbox.discardedRetiredSignalCount, 1, "the link loss still owned A's queued offer")
        XCTAssertEqual(mailbox.retiredControlGenerationFloor, controlA)
    }

    /// A link loss that names **no** generation retires nothing. There are exactly two producers: a
    /// connection that died before it ever authenticated, and the mailbox-overflow degrade. Neither is a
    /// control-lifetime boundary, so neither owns anybody's queued work — which is a deliberate
    /// narrowing of what CLAUDE.md rule 22's parenthetical used to license, made for the reason problem
    /// 60 exists: deleting live work because something else went wrong is the defect.
    func testALinkLossNamingNoGenerationTearsDownWithoutDiscardingAnything() {
        var mailbox = VoiceInputMailbox()
        mailbox.offer(signal(offerA, controlA))

        mailbox.offer(.controlLinkLost(retiredControlGeneration: nil))

        XCTAssertNil(mailbox.retiredControlGenerationFloor)
        XCTAssertEqual(mailbox.discardedRetiredSignalCount, 0)
        guard case .controlLinkLost? = mailbox.poll() else { return XCTFail("the degrade is unchanged") }
        guard case .signalReceived(_, let generation, _)? = mailbox.poll() else {
            return XCTFail("the live lifetime's work is not its to take")
        }
        XCTAssertEqual(generation, controlA)
    }

    /// **P60-8 — a send failure is negotiation-scoped and stays that way** (problem 57). It names a
    /// `voice_session_id`, never a control generation, and it must retire nothing: doing otherwise would
    /// let a `Bool` that came back late speak for a lifetime, which is the defect problem 57 closed and
    /// problem 60 must not reopen.
    func testASendFailureRetiresNoControlGenerationAndDiscardsNoSuccessorWork() {
        var mailbox = VoiceInputMailbox()
        mailbox.offer(signal(offerB, controlB))

        mailbox.offer(.negotiationSendFailed(voiceSessionId: vsidA))

        XCTAssertNil(mailbox.retiredControlGenerationFloor, "a send failure is not a control-lifetime boundary")
        XCTAssertEqual(mailbox.discardedRetiredSignalCount, 0)
        guard case .negotiationSendFailed? = mailbox.poll() else { return XCTFail("it still outranks the critical lane") }
        guard case .signalReceived(_, let generation, _)? = mailbox.poll() else { return XCTFail("B survives") }
        XCTAssertEqual(generation, controlB, "the successor's offer is untouched")
    }
}
