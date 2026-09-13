import RideLinkCore
import XCTest

@testable import RideLinkPlatform

/// STATUS §4 problems 50 and 56 — semantic `VOICE_*` work that was admitted by one control lifetime
/// and is still queued when that lifetime ends, and an offer that could not be sent.
///
/// This is **not** ADR-025 frame provenance. Every frame here was read while its control generation
/// was genuinely live, and `VoiceSignalRelay` was right to admit it. The question settled here is
/// the one that comes *after* admission: once `.controlLinkLost` has been applied, may work the
/// retired lifetime queued still **begin or advance** a voice negotiation?
///
/// The Android mirror is `VoiceControllerLinkLossOrderingTest`, which uses a `ManualDispatcher`.
/// `VoiceController` is an actor here with a doorbell-driven consumer task and no injectable
/// dispatcher, so determinism comes instead from `FakeVoiceAudioSession.armOpenGate()`: the consumer
/// parks inside `startLocalAudio`, and because a Swift actor is reentrant both `submit`
/// (nonisolated) and `onControlLinkLost` (isolated) still reach the mailbox while it is parked.
final class VoiceControllerLinkLossOrderingTests: XCTestCase {
    /// P50-A — a remote `VOICE_OFFER` queued before the link is lost. The answerer is the side that
    /// may legally receive an offer, so it is the side where this matters.
    func testQueuedOfferCannotBeginNegotiationAfterControlLinkLost() async throws {
        let harness = try await Harness(isLocalLeader: false)
        await harness.audio.armOpenGate()
        await harness.controller.start()
        // The consumer is now parked inside `startLocalAudio`, before `open` is recorded.
        try await harness.awaitCondition { await harness.audio.isGateHolding() }

        // Admitted while the control lifetime was genuinely live (ADR-025 is satisfied).
        harness.controller.submit(.offer(voiceSessionId: Self.genAt(900), sdp: Self.sdp))
        // ...and now that lifetime ends. `.teardown` outranks `.critical`, so this applies first.
        await harness.controller.onControlLinkLost()

        await harness.audio.releaseOpenGate()
        try await harness.awaitEngineCall("stop")
        try await harness.settle()

        let calls = await harness.engine.recordedCalls()
        let stopAt = try XCTUnwrap(calls.firstIndex(of: "stop"), "the link loss must stop the media transport")
        let afterTeardown = Array(calls[(stopAt + 1)...])

        XCTAssertFalse(
            afterTeardown.contains { $0.hasPrefix("start(") },
            "a retired lifetime's offer must not rebuild the peer connection; after stop=\(afterTeardown)"
        )
        XCTAssertFalse(
            afterTeardown.contains("applyRemote(OFFER)"),
            "a retired lifetime's offer must not be applied; after stop=\(afterTeardown)"
        )
        XCTAssertFalse(
            afterTeardown.contains("createAnswer"),
            "a retired lifetime's offer must not be answered; after stop=\(afterTeardown)"
        )
        let status = await harness.controller.currentDiagnostics().status
        XCTAssertEqual(status, .idle, "the controller must not believe it is negotiating with an unreachable peer")
        let sent = await harness.transport.sentSignals()
        XCTAssertFalse(
            sent.contains { if case .answer = $0 { return true } else { return false } },
            "no answer may be produced for a retired lifetime's offer"
        )
        await harness.controller.shutdown()
    }

    /// P50-E — the coalesced lane. A peer `VOICE_STATE { negotiating }` is §7.3's intent-to-talk, and
    /// on the **offerer** it starts a whole negotiation of its own.
    func testQueuedPeerIntentCannotStartNegotiationAfterControlLinkLost() async throws {
        let harness = try await Harness(isLocalLeader: true)
        await harness.audio.armOpenGate()
        await harness.controller.start()
        try await harness.awaitCondition { await harness.audio.isGateHolding() }

        harness.controller.submit(.state(voiceSessionId: nil, state: .negotiating, micMuted: false, mode: .continuous))
        await harness.controller.onControlLinkLost()

        await harness.audio.releaseOpenGate()
        try await harness.awaitEngineCall("stop")
        try await harness.settle()

        let calls = await harness.engine.recordedCalls()
        let stopAt = try XCTUnwrap(calls.firstIndex(of: "stop"))
        let afterTeardown = Array(calls[(stopAt + 1)...])
        XCTAssertFalse(
            afterTeardown.contains { $0.hasPrefix("start(") },
            "a retired lifetime's peer intent must not rebuild the peer connection; after stop=\(afterTeardown)"
        )
        XCTAssertFalse(
            afterTeardown.contains("createOffer"),
            "a retired lifetime's peer intent must not create an offer; after stop=\(afterTeardown)"
        )
        let status = await harness.controller.currentDiagnostics().status
        XCTAssertEqual(status, .idle)
        await harness.controller.shutdown()
    }

    /// STATUS §4 problem 56, and it needs **no interleaving at all**.
    ///
    /// `VoiceSignalRelay.send` returns false whenever there is no authenticated writer — the whole
    /// window between a link loss and the §10 ladder reconnecting. An offer created in that window
    /// used to advance the table to `.negotiating` anyway, and `start`'s idempotence then made the
    /// reconnect rebuild a no-op, wedging voice for the rest of the ride segment.
    func testOfferThatCouldNotBeSentDoesNotWedgeVoice() async throws {
        let harness = try await Harness(isLocalLeader: true)
        await harness.controller.start()
        try await harness.awaitEngineCall("createOffer")
        await harness.engine.emit(.offerCreated(voiceSessionId: Self.genAt(1), sdp: Self.sdp))
        try await harness.awaitSent { if case .offer = $0 { return true } else { return false } }

        await harness.controller.onControlLinkLost()
        try await harness.awaitEngineCall("stop")

        // The link is down: the relay finds no authenticated writer and `send` returns false. The
        // user presses Start Voice again while the ladder is still reconnecting.
        await harness.transport.setAccept(false)
        await harness.controller.start()
        try await harness.settle()
        await harness.engine.emit(.offerCreated(voiceSessionId: Self.genAt(2), sdp: Self.sdp))
        try await harness.settle()

        // The ladder reconnects; `attachVoice` rebuilds voice as a fresh negotiation (§7.8).
        await harness.transport.setAccept(true)
        let offersBefore = await harness.transport.sentSignals()
            .filter { if case .offer = $0 { return true } else { return false } }.count
        await harness.controller.start()
        try await harness.settle()
        // The rebuild's offer only reaches the wire once the engine reports it created one, and it
        // reports the **third** generation -- not the stranded second one.
        await harness.engine.emit(.offerCreated(voiceSessionId: Self.genAt(3), sdp: Self.sdp))
        try await harness.awaitCondition { await self.offerCount(harness) > offersBefore }

        let after = await offerCount(harness)
        XCTAssertGreaterThan(
            after, offersBefore,
            "after a reconnect the rebuild must put a new offer on the wire"
        )
        let counts = await harness.audio.captureCounts()
        XCTAssertEqual(counts.closed, 0, "no part of this degrade may close the capture device")
        await harness.controller.shutdown()
    }

    /// The other half of the invariant: a genuinely fresh offer after the link is back is still
    /// answered, and an ordinary blip never touches the capture device.
    func testFreshOfferAfterReconnectIsStillAnswered() async throws {
        let harness = try await Harness(isLocalLeader: false)
        await harness.controller.start()
        try await harness.awaitAudioCall("open")
        await harness.controller.onControlLinkLost()
        try await harness.awaitEngineCall("stop")
        let before = await harness.audio.captureCounts()

        await harness.controller.start()
        try await harness.settle()
        harness.controller.submit(.offer(voiceSessionId: Self.genAt(901), sdp: Self.sdp))
        try await harness.awaitEngineCall("applyRemote(OFFER)")
        try await harness.awaitEngineCall("createAnswer")

        let after = await harness.audio.captureCounts()
        XCTAssertEqual(before.opened, after.opened, "an ordinary control-link blip must not reopen capture")
        XCTAssertEqual(before.closed, after.closed, "an ordinary control-link blip must not close capture")
        await harness.controller.shutdown()
    }

    /// A-1 — STATUS §4 problem 57. **A send that failed is not a control lifetime that ended.**
    ///
    /// Problem 56's fix turned `transport.send(...) == false` into `.controlLinkLost`, which is the
    /// input problem 50 gave *lifetime-boundary* semantics: offering it discards every queued
    /// `.signalReceived`. A send failure is not that event. On this platform `VoiceSignalRelay.send`
    /// releases the `VoiceController` actor at `await transport.send(...)` and then suspends three more
    /// times inside the relay, so its `Bool` can arrive after the §10 ladder has authenticated a
    /// **successor** generation whose own `VOICE_OFFER` is already in the mailbox — `submit` is
    /// `nonisolated` and needs none of this actor's time to put one there.
    func testStaleSendFailureCannotDiscardASuccessorLifetimesQueuedOffer() async throws {
        let harness = try await Harness(isLocalLeader: false)
        await harness.controller.start()
        try await harness.settle()

        // Lifetime A: the peer offered and this side is answering.
        harness.controller.submit(.offer(voiceSessionId: Self.genAt(900), sdp: Self.sdp))
        try await harness.awaitEngineCall("createAnswer")
        await harness.transport.armSendGate(.offerOrAnswer)
        await harness.engine.emit(.answerCreated(voiceSessionId: Self.genAt(900), sdp: Self.sdp))
        try await harness.awaitCondition { await harness.transport.isSendGateHolding() }

        // Lifetime A ends. The consumer is parked inside the send, so nothing drains yet.
        await harness.controller.onControlLinkLost()

        // The ladder reconnects, lifetime B authenticates, and B's peer offers. `VoiceSignalRelay`
        // admitted this frame against a live generation, so ADR-025 is satisfied: it is genuinely the
        // successor's work.
        harness.controller.submit(.offer(voiceSessionId: Self.genAt(901), sdp: Self.sdp))

        // Only now does lifetime A's write report that it failed.
        await harness.transport.releaseSendGate(result: false)
        try await harness.awaitEngineCall("start(\(Self.genAt(901).value))")
        try await harness.settle()

        let calls = await harness.engine.recordedCalls()
        let stopAt = try XCTUnwrap(calls.lastIndex(of: "stop"), "the degrade must stop the retired media transport")
        let afterTeardown = Array(calls[(stopAt + 1)...])
        XCTAssertTrue(
            afterTeardown.contains("applyRemote(OFFER)"),
            "the successor lifetime's offer must survive a retired lifetime's send failure; after stop=\(afterTeardown)"
        )
        XCTAssertTrue(
            afterTeardown.contains("createAnswer"),
            "the successor lifetime's offer must still be answered; after stop=\(afterTeardown)"
        )
        let status = await harness.controller.currentDiagnostics().status
        XCTAssertEqual(status, .negotiating, "a retired send failure may not retire the successor's negotiation")
        await harness.controller.shutdown()
    }

    /// A-2 — the same problem reaching rule 21 rather than voice. `.teardown` is one slot, latest wins,
    /// so a degrade offered from the consumer's own resume **replaces** a `.stopRequested` that
    /// `shutdown()` is waiting on. Nothing then ever applies that stop: capture is never released and
    /// `SessionCoordinator.retireSession` — which awaits `shutdown()` with no timeout of its own by
    /// design (ADR-021 Amendment A4) — can never emit `.teardownComplete`, so the session can never
    /// reach `.idle` (ADR-026).
    func testStaleSendFailureCannotEraseAPendingStop() async throws {
        let harness = try await Harness(isLocalLeader: true)
        await harness.controller.start()
        try await harness.settle()

        await harness.transport.armSendGate(.offerOrAnswer)
        await harness.engine.emit(.offerCreated(voiceSessionId: Self.genAt(1), sdp: Self.sdp))
        try await harness.awaitCondition { await harness.transport.isSendGateHolding() }

        // The ride is ending: `retireSession` asks for the release it must prove happened.
        await harness.controller.stop()
        await harness.transport.releaseSendGate(result: false)

        try await harness.awaitAudioCall("close")
        let counts = await harness.audio.captureCounts()
        XCTAssertEqual(counts.closed, 1, "the pending stop must still be applied; a failed send may not replace it")
        await harness.controller.shutdown()
    }

    /// P56-1 from the **answerer's** side — STATUS §4 problem 59, and the half of problem 56 its own
    /// fix left open.
    ///
    /// An answerer never offers (PROTOCOL §7.3). Its `start()` produces exactly one wire effect: a
    /// `VOICE_STATE { negotiating }` with **no** `voice_session_id`, which is the whole of its
    /// intent-to-talk — and the table advances to `.negotiating` regardless of whether that frame
    /// reached anything. Problem 56's fix exempted `.sendVoiceState` because "a lost state update is
    /// carried by the next one". That is true of every `VOICE_STATE` except this one: there is no next
    /// one, `VoiceNegotiation.start` is idempotent against the live `.negotiating` it just entered, so
    /// `attachVoice`'s reconnect rebuild is a no-op — and if the leader has not itself consented,
    /// `attachVoice` does not call `start()` there either, so nothing on either side ever asks again.
    func testAnAnswerersUnsentIntentDoesNotWedgeVoiceForTheSegment() async throws {
        let harness = try await Harness(isLocalLeader: false)

        // The link is down: `VoiceSignalRelay.send` finds no authenticated writer.
        await harness.transport.setAccept(false)
        await harness.controller.start()
        try await harness.settle()

        // The ladder reconnects and `attachVoice` rebuilds voice as a fresh negotiation (§7.8).
        await harness.transport.setAccept(true)
        await harness.controller.start()
        try await harness.awaitSent { signal in
            if case .state(_, let wire, _, _) = signal { return wire == .negotiating }
            return false
        }

        let counts = await harness.audio.captureCounts()
        XCTAssertEqual(counts.closed, 0, "no part of this degrade may close the capture device (ARCHITECTURE §6.3/§6.4)")
        await harness.controller.shutdown()
    }

    private func offerCount(_ harness: Harness) async -> Int {
        await harness.transport.sentSignals()
            .filter { if case .offer = $0 { return true } else { return false } }.count
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
            // A **fresh** id per negotiation, exactly as `VoiceSessionIdGenerator` mints one in
            // production (ADR-020 / PROTOCOL §7.2). A harness that hands out one id for every call
            // hides this whole class of bug: a stale `NEGOTIATING` state would accept the *next*
            // negotiation's engine callback as its own, because the ids happen to be equal.
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
        }

        func awaitEngineCall(_ call: String) async throws {
            try await awaitCondition { await self.engine.recordedCalls().contains(call) }
        }

        func awaitAudioCall(_ call: String) async throws {
            try await awaitCondition { await self.audio.recordedCalls().contains(call) }
        }

        func awaitSent(_ predicate: @escaping @Sendable (VoiceSignal) -> Bool) async throws {
            try await awaitCondition { await self.transport.sentSignals().contains(where: predicate) }
        }

        /// Lets the consumer drain whatever is queued. There is no "queue is empty" signal to wait on,
        /// so this yields generously rather than asserting a condition -- every assertion that matters
        /// is an `awaitCondition` on an actual observable.
        func settle() async throws {
            for _ in 0..<20 { try await Task.sleep(nanoseconds: 5_000_000) }
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

    private static let sdp = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\n"
    /// A trivially thread-safe counter; `newVoiceSessionId` is a `@Sendable` closure.
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
