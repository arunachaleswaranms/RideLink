import RideLinkCore
import XCTest

@testable import RideLinkPlatform

/// **STATUS §4 problems 63 and 64 — the two ways work authorised by one control lifetime could still
/// reach another one's wire** (ADR-020 Amendment A9).
///
/// Amendment A8 (problem 61) gave a *negotiation* an owner, so a predecessor's delayed boundary can no
/// longer retire a successor's reduced state. It did not ask the two questions this file asks, and both
/// have production paths:
///
/// - **Problem 63 — a held remote offer could cross lifetimes.** A `VOICE_OFFER` admitted under A and
///   held for want of local consent (§7.3) was answered by whatever `.startRequested` arrived next, and
///   the answer branch then set the owner to the **press's** lifetime. Consent under a successor
///   therefore adopted a dead lifetime's SDP, reused its `voice_session_id` — which the offerer had
///   already discarded when *its* copy of that link died — and moved ownership to B, leaving A's own
///   boundary inert. PROTOCOL §7.8 wants a reconnect to rebuild voice as a **fresh** negotiation; this
///   was the one path that quietly did the opposite.
///
/// - **Problem 64 — an authorised send could be written on a successor's connection.** Everything
///   between the press and the write suspends: the mailbox's single consumer, `createOffer`'s engine
///   callback, the actor hop, the write lock, the flush. `VoiceSignalRelay.send` asked for "the
///   authenticated writer" at the moment of the *write*, so a `VOICE_OFFER` authorised by A was written
///   to B's connection, where the peer accepted it as current — and A's boundary, arriving afterwards,
///   then tore this side's media down while the peer was still negotiating.
///
/// Neither is a race, and nothing here is sequenced on a sleep: every step waits on an observable that
/// proves the previous one was *reduced* — an engine call, or one of the two new drop counters, which
/// exist precisely so a refusal leaves evidence rather than nothing.
///
/// The Android mirror is `VoiceCrossLifetimeAuthorityTest`.
final class VoiceCrossLifetimeAuthorityTests: XCTestCase {
    // MARK: - problem 63: a held offer may not cross a control lifetime

    /// **P63-A — the defect itself, and the whole of the recovery after it.**
    ///
    /// A's offer is held (no consent yet). A dies, B authenticates, and — before A's delayed boundary is
    /// consumed — this user taps Start. A's SDP must not be applied, A's `voice_session_id` must not be
    /// answered, and what goes out on B must be §7.3's intent-to-talk. B's own fresh offer is then
    /// answered normally, under a `voice_session_id` that is not A's.
    func testAHeldOfferFromAPredecessorIsNeverAnsweredByASuccessorsConsent() async throws {
        let harness = try await Harness(isLocalLeader: false)

        harness.controller.submit(.offer(voiceSessionId: Self.aOffer, sdp: Self.sdpA), controlGeneration: Self.controlA)
        try await harness.awaitPeerRequest()
        var counts = await harness.audio.captureCounts()
        XCTAssertEqual(counts.opened, 0, "precondition: a peer's offer never opens the microphone")

        // A dies; B authenticates; A's `.controlLinkLost` has not been consumed yet.
        await harness.setLiveGeneration(Self.controlB)
        await harness.controller.start(controlGeneration: Self.controlB)
        try await harness.awaitDrop(.retiredHeldOffer, 1)

        let calls = await harness.engine.recordedCalls()
        XCTAssertFalse(calls.contains("applyRemote(OFFER)"), "a dead lifetime's SDP was applied; calls=\(calls)")
        XCTAssertFalse(calls.contains("createAnswer"), "and answered; calls=\(calls)")
        let sent = await harness.transport.sentSignals()
        XCTAssertTrue(sent.allSatisfy { !Self.names(Self.aOffer, $0) }, "nothing may name A's generation; sent=\(sent)")
        XCTAssertTrue(sent.contains { Self.isIntentToTalk($0) }, "§7.3's intent-to-talk is what B sends; sent=\(sent)")
        let generations = await harness.transport.sentGenerations()
        XCTAssertTrue(generations.allSatisfy { $0 == Self.controlB }, "on B's link; generations=\(generations)")
        counts = await harness.audio.captureCounts()
        XCTAssertEqual(counts.opened, 1, "consent still opened capture")
        XCTAssertEqual(counts.closed, 0, "and never closed it")

        // A's delayed boundary finally arrives. B owns the intent, so it is inert.
        await harness.controller.onControlLinkLost(retiredControlGeneration: Self.controlA)
        try await harness.awaitDrop(.supersededControlLifetime, 1)
        var status = await harness.controller.currentDiagnostics().status
        XCTAssertEqual(status, .negotiating)

        // The offerer, rebuilt under B, offers again. A fresh generation, answered normally.
        harness.controller.submit(.offer(voiceSessionId: Self.bOffer, sdp: Self.sdpB), controlGeneration: Self.controlB)
        try await harness.awaitEngineCall("createAnswer")
        await harness.engine.emit(.answerCreated(voiceSessionId: Self.bOffer, sdp: Self.sdpB))
        try await harness.awaitSent { signals in signals.contains { if case .answer = $0 { true } else { false } } }

        let answers = await harness.transport.sentSignals().compactMap { signal -> VoiceSessionId? in
            if case .answer(let id, _) = signal { id } else { nil }
        }
        XCTAssertEqual(answers, [Self.bOffer], "B's rebuild answers B's own fresh generation")
        status = await harness.controller.currentDiagnostics().status
        XCTAssertEqual(status, .connecting)
        counts = await harness.audio.captureCounts()
        XCTAssertEqual(counts.opened, 1, "capture was never reopened across any of it")
        XCTAssertEqual(counts.closed, 0)
        await harness.controller.shutdown()
    }

    /// **P63-B — the opposite ordering, which the same rule has to get right in the other direction.**
    ///
    /// The press is the stale thing: the user tapped Start while A was live, the tap sat in the mailbox,
    /// and B's offer was admitted and reduced first. Answering B's offer *under A* would send an answer
    /// no link could carry and destroy the only copy of that offer. Consent is honoured; the negotiation
    /// is not started; B's held offer survives for B's own consent to answer.
    func testAStartAuthorisedByARetiredLifetimeKeepsANewerLifetimesHeldOfferIntact() async throws {
        let harness = try await Harness(isLocalLeader: false)

        harness.controller.submit(.offer(voiceSessionId: Self.bOffer, sdp: Self.sdpB), controlGeneration: Self.controlB)
        try await harness.awaitPeerRequest()

        await harness.setLiveGeneration(Self.controlB)
        await harness.controller.start(controlGeneration: Self.controlA)
        try await harness.awaitDrop(.supersededStartLifetime, 1)

        let status = await harness.controller.currentDiagnostics().status
        XCTAssertEqual(status, .idle, "a dead lifetime starts no negotiation")
        let sent = await harness.transport.sentSignals()
        XCTAssertTrue(sent.isEmpty, "and sends nothing; sent=\(sent)")
        let calls = await harness.engine.recordedCalls()
        XCTAssertFalse(calls.contains("createAnswer"), "and touches no media; calls=\(calls)")
        let counts = await harness.audio.captureCounts()
        XCTAssertEqual(counts.opened, 1, "but consent is still consent (ARCHITECTURE §6.4)")

        // B's own consent answers B's own held offer — the only copy, still there.
        await harness.controller.start(controlGeneration: Self.controlB)
        try await harness.awaitEngineCall("createAnswer")
        let after = await harness.engine.recordedCalls()
        XCTAssertTrue(after.contains("applyRemote(OFFER)"), "calls=\(after)")
        let openedAgain = await harness.audio.captureCounts()
        XCTAssertEqual(openedAgain.opened, 1, "capture opened once across both presses")
        await harness.controller.shutdown()
    }

    /// A held offer answered by **its own** lifetime's consent is untouched — A8's behaviour, kept.
    func testAHeldOfferIsStillAnsweredByTheLifetimeThatDeliveredIt() async throws {
        let harness = try await Harness(isLocalLeader: false)
        harness.controller.submit(.offer(voiceSessionId: Self.aOffer, sdp: Self.sdpA), controlGeneration: Self.controlA)
        try await harness.awaitPeerRequest()
        await harness.controller.start(controlGeneration: Self.controlA)
        try await harness.awaitEngineCall("createAnswer")

        let calls = await harness.engine.recordedCalls()
        XCTAssertTrue(calls.contains("applyRemote(OFFER)"), "calls=\(calls)")
        let dropped = await harness.controller.currentDiagnostics().droppedSignals[.retiredHeldOffer]
        XCTAssertNil(dropped, "nothing was discarded")
        await harness.controller.shutdown()
    }

    // MARK: - problem 64: an authorised send may not be written on a successor's connection

    /// **P64-A — the defect itself, offerer side.** The press is authorised by A and drained after B has
    /// authenticated. Every frame it produces names A, so every one is refused; the negotiation degrades
    /// through `.negotiationSendFailed`; and **nothing reaches B**.
    func testAnOfferersStartAuthorisedByARetiredLifetimePutsNothingOnTheSuccessorsWire() async throws {
        let harness = try await Harness(isLocalLeader: true)

        await harness.setLiveGeneration(Self.controlB)
        await harness.controller.start(controlGeneration: Self.controlA)
        try await harness.awaitEngineCall("createOffer")
        await harness.engine.emit(.offerCreated(voiceSessionId: Self.genAt(1), sdp: Self.sdpA))
        try await harness.awaitEngineCall("stop")

        let sent = await harness.transport.sentSignals()
        XCTAssertTrue(sent.isEmpty, "A's work reached B's wire; sent=\(sent)")
        let attempted = await harness.transport.attemptedSends()
        XCTAssertTrue(
            attempted.allSatisfy { $0.1 == Self.controlA },
            "every attempt named its own authorising lifetime; attempted=\(attempted.map(\.1))"
        )
        XCTAssertTrue(
            attempted.contains { if case .offer = $0.0 { true } else { false } },
            "the offer was attempted and refused, not silently skipped"
        )
        var status = await harness.controller.currentDiagnostics().status
        XCTAssertEqual(status, .idle, "a negotiation whose offer cannot be placed degrades rather than wedging")
        var counts = await harness.audio.captureCounts()
        XCTAssertEqual(counts.opened, 1, "capture does not go with it (ARCHITECTURE §6.3/§6.4)")
        XCTAssertEqual(counts.closed, 0)

        // And the rebuild under B is a **fresh** negotiation, on B's wire.
        await harness.controller.start(controlGeneration: Self.controlB)
        try await harness.awaitSent { !$0.isEmpty }
        await harness.engine.emit(.offerCreated(voiceSessionId: Self.genAt(2), sdp: Self.sdpB))
        try await harness.awaitSent { signals in signals.contains { if case .offer = $0 { true } else { false } } }

        let offers = await harness.transport.sentSignals().compactMap { signal -> VoiceSessionId? in
            if case .offer(let id, _) = signal { id } else { nil }
        }
        XCTAssertEqual(offers, [Self.genAt(2)], "B gets a fresh voice_session_id")
        let generations = await harness.transport.sentGenerations()
        XCTAssertTrue(generations.allSatisfy { $0 == Self.controlB }, "generations=\(generations)")
        status = await harness.controller.currentDiagnostics().status
        XCTAssertEqual(status, .negotiating)
        counts = await harness.audio.captureCounts()
        XCTAssertEqual(counts.opened, 1, "capture was never reopened")
        await harness.controller.shutdown()
    }

    /// **P64-B — the answerer's half**, which problem 59 already showed is the one `VOICE_STATE` no later
    /// one replaces. Its refusal has to degrade, or the table sits in `.negotiating` forever and
    /// `attachVoice`'s rebuild finds a live negotiation and does nothing.
    func testAnAnswerersIntentToTalkAuthorisedByARetiredLifetimeDegradesRatherThanWedging() async throws {
        let harness = try await Harness(isLocalLeader: false)

        await harness.setLiveGeneration(Self.controlB)
        await harness.controller.start(controlGeneration: Self.controlA)
        try await harness.awaitEngineCall("stop")

        let sent = await harness.transport.sentSignals()
        XCTAssertTrue(sent.isEmpty, "sent=\(sent)")
        let attempted = await harness.transport.attemptedSends()
        XCTAssertTrue(attempted.allSatisfy { $0.1 == Self.controlA }, "attempted=\(attempted.map(\.1))")
        var status = await harness.controller.currentDiagnostics().status
        XCTAssertEqual(status, .idle, "problem 59's wedge stays closed")

        await harness.controller.start(controlGeneration: Self.controlB)
        try await harness.awaitSent { !$0.isEmpty }
        status = await harness.controller.currentDiagnostics().status
        XCTAssertEqual(status, .negotiating)
        let generations = await harness.transport.sentGenerations()
        XCTAssertTrue(generations.allSatisfy { $0 == Self.controlB }, "B's intent goes out on B")
        await harness.controller.shutdown()
    }

    /// **P64-C — the send parked at the transport, released after the boundary.** This is the shape
    /// problem 57 established: name the instant rather than race for it. The offer is suspended *inside*
    /// `perform`, the lifetime is replaced underneath it, and the write then fails closed.
    ///
    /// It also pins the two things that failure must **not** do: speak as `.controlLinkLost` (which would
    /// discard a successor's queued work and erase a pending stop, problems 57 and 59), and displace the
    /// `.stopRequested` waiting in the one-slot teardown lane.
    func testASendParkedAcrossALifetimeBoundaryFailsClosedWithoutSpeakingForTheLifetime() async throws {
        let harness = try await Harness(isLocalLeader: true)
        await harness.setLiveGeneration(Self.controlA)
        await harness.controller.start(controlGeneration: Self.controlA)
        try await harness.awaitEngineCall("createOffer")

        await harness.transport.armSendGate(.offerOrAnswer)
        await harness.engine.emit(.offerCreated(voiceSessionId: Self.genAt(1), sdp: Self.sdpA))
        try await harness.awaitCondition { await harness.transport.isSendGateHolding() }

        // The lifetime is replaced while the write is parked, and a stop is queued behind it.
        await harness.setLiveGeneration(Self.controlB)
        await harness.controller.stop()
        await harness.transport.releaseSendGate(result: true)
        try await harness.awaitEngineCall("release")

        let sent = await harness.transport.sentSignals()
        XCTAssertFalse(
            sent.contains { if case .offer = $0 { true } else { false } },
            "the parked offer must not land on the successor even though the write itself succeeded"
        )
        let status = await harness.controller.currentDiagnostics().status
        XCTAssertEqual(status, .idle)
        let counts = await harness.audio.captureCounts()
        XCTAssertEqual(counts.closed, 1, "a deliberate stop is the one thing that may close capture")
        await harness.controller.shutdown()
    }

    /// **P64-D — an engine continuation created by A cannot produce a B-authorised wire effect.**
    /// `createOffer` was dispatched under A; its callback resumes after B is live. The `voice_session_id`
    /// guard alone would have let it through — the negotiation it names *is* still the live one — so what
    /// refuses it is the owner the `.sendOffer` carries.
    func testALocalOfferCallbackFromARetiredLifetimeCannotBeSentOnTheSuccessor() async throws {
        let harness = try await Harness(isLocalLeader: true)
        await harness.setLiveGeneration(Self.controlA)
        await harness.controller.start(controlGeneration: Self.controlA)
        try await harness.awaitEngineCall("createOffer")
        let before = await harness.transport.sentSignals().count

        // The engine answers late: A is gone, B is live, and A's boundary has not arrived.
        await harness.setLiveGeneration(Self.controlB)
        await harness.engine.emit(.offerCreated(voiceSessionId: Self.genAt(1), sdp: Self.sdpA))
        try await harness.awaitEngineCall("stop")

        let sent = await harness.transport.sentSignals()
        XCTAssertEqual(sent.count, before, "sent=\(sent)")
        let offerAttempt = await harness.transport.attemptedSends().last { if case .offer = $0.0 { true } else { false } }
        XCTAssertEqual(offerAttempt?.1, Self.controlA, "the offer named A, the only lifetime entitled to carry it")
        let status = await harness.controller.currentDiagnostics().status
        XCTAssertEqual(status, .idle, "and its loss degrades the negotiation")
        await harness.controller.shutdown()
    }

    /// **P64-E — a refused send degrades, and the rebuild that follows is clean.**
    ///
    /// A's offer is parked at the transport, the lifetime is replaced underneath it, and the write is
    /// then refused. What follows is the whole recovery: the degrade, a boundary that finds nothing left
    /// to retire, and a rebuild under B with a **fresh** `voice_session_id`.
    ///
    /// **What this test deliberately does *not* claim**, because building it proved the ordering
    /// unreachable: a stale `.negotiationSendFailed` cannot be applied *after* a successor's rebuild has
    /// been reduced. `VoiceMailboxLane.sendFailure` outranks `.critical` by design (problem 57), and the
    /// controller has one consumer — which is parked inside `perform` for as long as the write is — so
    /// the failure is always reduced **before** any rebuild queued behind it. The reducer's guard against
    /// the other ordering is real and is pinned where it belongs, in `protocol/vectors/voice-fsm/`'s
    /// `negotiation-send-failed-from-a-retired-generation-is-inert`; asserting it here would have been a
    /// test of a state no production ordering can produce.
    func testARefusedSendDegradesAndTheSuccessorsRebuildIsClean() async throws {
        let harness = try await Harness(isLocalLeader: true)
        await harness.setLiveGeneration(Self.controlA)
        await harness.controller.start(controlGeneration: Self.controlA)
        try await harness.awaitEngineCall("createOffer")

        await harness.transport.armSendGate(.offerOrAnswer)
        await harness.engine.emit(.offerCreated(voiceSessionId: Self.genAt(1), sdp: Self.sdpA))
        try await harness.awaitCondition { await harness.transport.isSendGateHolding() }

        // The lifetime is replaced while the write is parked; the write then reports.
        await harness.setLiveGeneration(Self.controlB)
        await harness.transport.releaseSendGate(result: true)
        try await harness.awaitEngineCall("stop")
        var status = await harness.controller.currentDiagnostics().status
        XCTAssertEqual(status, .idle, "the refused offer degraded the table")

        // A's boundary arrives to find nothing left to retire, and B rebuilds fresh.
        await harness.controller.onControlLinkLost(retiredControlGeneration: Self.controlA)
        await harness.controller.start(controlGeneration: Self.controlB)
        try await harness.awaitCondition {
            await harness.engine.recordedCalls().filter { $0 == "createOffer" }.count >= 2
        }
        await harness.engine.emit(.offerCreated(voiceSessionId: Self.genAt(2), sdp: Self.sdpB))
        try await harness.awaitSent { signals in signals.contains { if case .offer = $0 { true } else { false } } }

        status = await harness.controller.currentDiagnostics().status
        XCTAssertEqual(status, .negotiating, "B's rebuild must survive everything A left behind")
        let sent = await harness.transport.sentSignals()
        let generations = await harness.transport.sentGenerations()
        let offers = sent.compactMap { signal -> VoiceSessionId? in
            if case .offer(let id, _) = signal { id } else { nil }
        }
        XCTAssertEqual(offers, [Self.genAt(2)], "only B's offer was ever written")
        // A's own `negotiating` state did go out, on A's link, while A was live — that is correct and is
        // not what is under test. What may never appear is an SDP authorised by A at all.
        XCTAssertFalse(
            zip(generations, sent).contains { generation, signal in
                guard generation == Self.controlA else { return false }
                if case .offer = signal { return true }
                if case .answer = signal { return true }
                return false
            },
            "an SDP authorised by A was written; generations=\(generations)"
        )
        let counts = await harness.audio.captureCounts()
        XCTAssertEqual(counts.opened, 1)
        XCTAssertEqual(counts.closed, 0)
        await harness.controller.shutdown()
    }

    // MARK: - harness

    private final class Harness {
        let controller: VoiceController
        let engine: FakeVoiceEngine
        let audio: FakeVoiceAudioSession
        let transport: RecordingVoiceTransport

        init(isLocalLeader: Bool) async throws {
            engine = FakeVoiceEngine()
            audio = FakeVoiceAudioSession()
            transport = RecordingVoiceTransport()
            let counter = ManagedAtomicCounter()
            controller = VoiceController(
                engine: engine,
                audioSession: audio,
                transport: transport,
                isLocalLeader: isLocalLeader,
                localTrackId: "ridelink-voice",
                newVoiceSessionId: { VoiceSessionId(String(format: "%032d", counter.next())) }
            )
            await controller.attach()
            // The default is "refuse nothing"; every test here is about two lifetimes, so production's
            // outbound rule is on from the start and only the live value moves.
            await transport.setLiveGeneration(VoiceCrossLifetimeAuthorityTests.controlA)
        }

        func setLiveGeneration(_ generation: Int64) async {
            await transport.setLiveGeneration(generation)
        }

        func awaitEngineCall(_ call: String) async throws {
            try await awaitCondition { await self.engine.recordedCalls().contains(call) }
        }

        func awaitPeerRequest() async throws {
            try await awaitCondition { await self.controller.currentDiagnostics().peerRequestedVoice }
        }

        func awaitDrop(_ reason: VoiceSignalDropReason, _ count: Int) async throws {
            try await awaitCondition {
                (await self.controller.currentDiagnostics().droppedSignals[reason] ?? 0) >= count
            }
        }

        func awaitSent(_ predicate: @escaping ([VoiceSignal]) -> Bool) async throws {
            try await awaitCondition { predicate(await self.transport.sentSignals()) }
        }

        func awaitCondition(_ condition: @escaping () async -> Bool) async throws {
            let deadline = Date().addingTimeInterval(5.0)
            while Date() < deadline {
                if await condition() { return }
                try await Task.sleep(nanoseconds: 5_000_000)
            }
            XCTFail("condition not met within the timeout")
        }
    }

    private static func names(_ id: VoiceSessionId, _ signal: VoiceSignal) -> Bool {
        switch signal {
        case .offer(let other, _): other == id
        case .answer(let other, _): other == id
        case .iceCandidate(let other, _, _, _): other == id
        case .state(let other, _, _, _): other == id
        }
    }

    private static func isIntentToTalk(_ signal: VoiceSignal) -> Bool {
        if case .state(let id, let wire, _, _) = signal { id == nil && wire == .negotiating } else { false }
    }

    private static let sdpA = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\na=x:A\r\n"
    private static let sdpB = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\na=x:B\r\n"

    private static let aOffer = VoiceSessionId(String(format: "%032d", 910))
    private static let bOffer = VoiceSessionId(String(format: "%032d", 920))

    private static let controlA: Int64 = 1
    private static let controlB: Int64 = 2

    private final class ManagedAtomicCounter: @unchecked Sendable {
        private let lock = NSLock()
        private var value = 0

        func next() -> Int {
            lock.lock()
            defer { lock.unlock() }
            value += 1
            return value
        }
    }

    private static func genAt(_ n: Int) -> VoiceSessionId {
        VoiceSessionId(String(format: "%032d", n))
    }
}
