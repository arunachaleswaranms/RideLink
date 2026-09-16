import RideLinkCore
import XCTest

@testable import RideLinkPlatform

/// **STATUS §4 problem 61 — the *state* half of a control-lifetime boundary.**
///
/// Problem 60 (ADR-020 Amendment A7) closed the queue half: a retired lifetime can no longer discard
/// or refuse a successor's **inputs**. This file is about what happens once a successor's input has
/// already been *reduced* — at which point it is no longer an input at all, but ordinary negotiation
/// state, with nothing on it to say which control lifetime it belongs to.
///
/// The ordering needs no race and is production's own. `ControlEvent.linkLost` reaches
/// `VoiceController` through `SessionCoordinator`'s event consumer — deferred once more into
/// `launchInSession` here — while `ControlSessionManager.promote` authenticates a successor and
/// admits its frames without waiting on that consumer at all (`VoiceLifetimeProvenanceTests` pins
/// exactly that over two real TLS sessions). So a successor's `VOICE_OFFER` can be admitted, drained
/// and applied while the predecessor's boundary is still sitting unconsumed — and before ADR-020
/// Amendment A8, applying it then returned the successor's live negotiation to `.idle`.
///
/// **Why the obvious fix is not the fix.** Suppressing a boundary that a newer generation appears to
/// have superseded was implemented, mirrored, tested and rejected, because *admission is not
/// application*: a successor's admitted offer can be dropped by `offerReceived`'s
/// `.generationMismatch` against a still-live predecessor negotiation, so "a newer generation
/// admitted something" does not imply its negotiation is live. Suppressing on that premise leaves a
/// **dead** lifetime's negotiation standing, which then refuses every offer the successor sends.
/// Both orderings wedge; only an owner recorded on the state itself tells them apart.
/// `testABoundaryStillRetiresAPredecessorASuccessorOnlyTriedToTakeOverFrom` is that ordering, and it
/// is the test the suppression fails.
///
/// Determinism, and why there is no sleep in any assertion here: unlike the problem-50/56 suites,
/// nothing needs to be *queued* at a chosen instant. Everything is sequenced on an observable that
/// proves the previous step was reduced — `awaitEngineCall` for work, and the
/// `.supersededControlLifetime` counter for a boundary that was correctly ignored, which exists
/// precisely so a preserved successor leaves evidence rather than nothing.
///
/// The Android mirror is `VoiceControlLifetimeOwnershipTest`.
final class VoiceControlLifetimeOwnershipTests: XCTestCase {
    /// **P61-A — the defect itself.** B authenticates, offers, and its offer is *fully reduced*; only
    /// then does A's boundary arrive. B's negotiation must survive it untouched.
    func testADelayedBoundaryForAPredecessorCannotRetireASuccessorsReducedNegotiation() async throws {
        let harness = try await Harness(isLocalLeader: false)
        await harness.controller.start(controlGeneration: Self.controlA)

        harness.controller.submit(.offer(voiceSessionId: Self.genAt(901), sdp: Self.sdp), controlGeneration: Self.controlB)
        try await harness.awaitEngineCall("createAnswer")
        let status = await harness.controller.currentDiagnostics().status
        XCTAssertEqual(status, .negotiating, "precondition: B's offer is not merely admitted but applied")
        let callsBefore = await harness.engine.recordedCalls()

        await harness.controller.onControlLinkLost(retiredControlGeneration: Self.controlA)
        // The counter is the evidence the boundary was reduced — no sleep needed to know it ran.
        try await harness.awaitSuperseded(1)

        let after = await harness.controller.currentDiagnostics().status
        let calls = await harness.engine.recordedCalls()
        XCTAssertEqual(after, .negotiating, "a predecessor's boundary may not retire the successor's negotiation")
        XCTAssertEqual(calls, callsBefore, "and may not touch the media transport at all; calls=\(calls)")
        await harness.controller.shutdown()
    }

    /// **P61-B — the ordering that killed the naïve suppression.**
    ///
    /// B is authenticated and its offer *is* admitted — but the reducer refuses it, because A's
    /// negotiation is still live and names a different `voice_session_id`. B therefore never becomes
    /// the owner, so A's boundary must still tear A's negotiation down. A rule keyed on "has a newer
    /// generation been admitted?" cannot distinguish this from P61-A and leaves a dead lifetime's
    /// negotiation standing forever.
    func testABoundaryStillRetiresAPredecessorASuccessorOnlyTriedToTakeOverFrom() async throws {
        let harness = try await Harness(isLocalLeader: false)
        await harness.controller.start(controlGeneration: Self.controlA)
        harness.controller.submit(.offer(voiceSessionId: Self.genAt(900), sdp: Self.sdp), controlGeneration: Self.controlA)
        try await harness.awaitEngineCall("createAnswer")

        // B's own offer, admitted by a live lifetime and reduced — and refused, because A's
        // negotiation is live and names a different generation.
        harness.controller.submit(.offer(voiceSessionId: Self.genAt(901), sdp: Self.sdp), controlGeneration: Self.controlB)
        try await harness.awaitCondition {
            (await harness.controller.currentDiagnostics().droppedSignals[.generationMismatch] ?? 0) > 0
        }

        await harness.controller.onControlLinkLost(retiredControlGeneration: Self.controlA)
        try await harness.awaitEngineCall("stop")

        let status = await harness.controller.currentDiagnostics().status
        XCTAssertEqual(status, .idle, "A still owns the negotiation, so A's boundary must still retire it")
        let superseded = await harness.controller.currentDiagnostics().droppedSignals[.supersededControlLifetime]
        XCTAssertNil(superseded, "nothing was superseded: admission is not application")
        let audioOpen = await harness.audio.isOpen()
        XCTAssertTrue(audioOpen, "capture survives a link loss (ARCHITECTURE §6.3/§6.4)")
        await harness.controller.shutdown()
    }

    /// **P61-C — more than an A/B pair.** Two reconnects on, a boundary for the long-dead first
    /// lifetime is no less inert. The rule is a comparison against the owner, not a memory of the
    /// previous generation — and C's own boundary still works, which is what stops this being
    /// inertness rather than ownership.
    func testABoundaryForALongDeadLifetimeCannotRetireAThirdGenerationsNegotiation() async throws {
        let harness = try await Harness(isLocalLeader: false)
        await harness.controller.start(controlGeneration: Self.controlA)
        harness.controller.submit(.offer(voiceSessionId: Self.genAt(902), sdp: Self.sdp), controlGeneration: Self.controlC)
        try await harness.awaitEngineCall("createAnswer")

        // Drained one at a time on purpose: `VoiceMailboxLane.teardown` is a single latest-wins slot,
        // so offering both before either is polled would coalesce them and only ever test B's.
        await harness.controller.onControlLinkLost(retiredControlGeneration: Self.controlA)
        try await harness.awaitSuperseded(1)
        await harness.controller.onControlLinkLost(retiredControlGeneration: Self.controlB)
        try await harness.awaitSuperseded(2)

        var status = await harness.controller.currentDiagnostics().status
        var calls = await harness.engine.recordedCalls()
        XCTAssertEqual(status, .negotiating, "neither dead lifetime owns C's negotiation")
        XCTAssertFalse(calls.contains("stop"), "and neither may stop C's media; calls=\(calls)")

        await harness.controller.onControlLinkLost(retiredControlGeneration: Self.controlC)
        try await harness.awaitEngineCall("stop")
        status = await harness.controller.currentDiagnostics().status
        calls = await harness.engine.recordedCalls()
        XCTAssertEqual(status, .idle, "C's own boundary still retires C's negotiation; calls=\(calls)")
        await harness.controller.shutdown()
    }

    /// **P61-D — the fix must not make link losses inert.** The owning lifetime's own boundary is
    /// PROTOCOL §7.8 in full: media stops, capture stays, nothing is sent and nothing is retried.
    func testTheOwningLifetimesOwnBoundaryStillPerformsTheWholeOfProtocol78() async throws {
        let harness = try await Harness(isLocalLeader: false)
        await harness.controller.start(controlGeneration: Self.controlB)
        harness.controller.submit(.offer(voiceSessionId: Self.genAt(901), sdp: Self.sdp), controlGeneration: Self.controlB)
        try await harness.awaitEngineCall("createAnswer")
        let sentBefore = await harness.transport.sentSignals().count

        await harness.controller.onControlLinkLost(retiredControlGeneration: Self.controlB)
        try await harness.awaitEngineCall("stop")
        try await harness.settle()

        let status = await harness.controller.currentDiagnostics().status
        let calls = await harness.engine.recordedCalls()
        XCTAssertEqual(status, .idle, "calls=\(calls)")
        XCTAssertFalse(calls.contains("release"), "capture is NOT released by a link loss; calls=\(calls)")
        let audioOpen = await harness.audio.isOpen()
        XCTAssertTrue(audioOpen, "and the audio session stays open for the ride segment")
        let sentAfter = await harness.transport.sentSignals().count
        XCTAssertEqual(sentBefore, sentAfter, "nothing is sent on a link that is gone, and nothing is retried (§7.8)")
        XCTAssertFalse(calls.contains("createOffer"), "VoiceNegotiation never retries by itself; calls=\(calls)")
        await harness.controller.shutdown()
    }

    /// **P61-E — a locally started negotiation is owned too.** The press carries the lifetime it was
    /// authorised by, so a predecessor's boundary cannot retire it and its own can. The offerer is the
    /// side that matters: a local Start is what authors its offer.
    func testALocallyStartedNegotiationIsOwnedByTheLifetimeThatAuthorisedThePress() async throws {
        let harness = try await Harness(isLocalLeader: true)
        await harness.controller.start(controlGeneration: Self.controlB)
        try await harness.awaitEngineCall("createOffer")
        let callsBefore = await harness.engine.recordedCalls()

        await harness.controller.onControlLinkLost(retiredControlGeneration: Self.controlA)
        try await harness.awaitSuperseded(1)

        var status = await harness.controller.currentDiagnostics().status
        let calls = await harness.engine.recordedCalls()
        XCTAssertEqual(status, .negotiating, "a predecessor cannot retire a negotiation a later lifetime's press started")
        XCTAssertEqual(calls, callsBefore, "and touches nothing")

        await harness.controller.onControlLinkLost(retiredControlGeneration: Self.controlB)
        try await harness.awaitEngineCall("stop")
        status = await harness.controller.currentDiagnostics().status
        XCTAssertEqual(status, .idle, "its own lifetime's boundary still retires it")
        let audioOpen = await harness.audio.isOpen()
        XCTAssertTrue(audioOpen, "capture still survives it")
        await harness.controller.shutdown()
    }

    /// **P61-F — Start pressed in the gap between two control lifetimes.**
    ///
    /// A user can press Start Intercom after one link has died and before PROTOCOL §10's ladder has
    /// restored the next. The press must not be refused — ARCHITECTURE §6.4 requires capture to be
    /// opened while the app is foreground-visible, and this may be the last such moment — but it must
    /// not create a negotiation either, because there is no link to negotiate over and, worse, no
    /// lifetime to own one. A negotiation owned by nobody is the single state no boundary can retire.
    ///
    /// The press records pending intent and consent. Production `attachVoice` delivers explicit
    /// authenticated availability; that event consumes the intent under the successor (A11).
    func testAStartPressedBetweenTwoLifetimesOpensCaptureAndIsRebuiltByTheSuccessor() async throws {
        let harness = try await Harness(isLocalLeader: true)
        await harness.controller.start(controlGeneration: nil)
        try await harness.awaitCondition { await harness.controller.currentDiagnostics().localAudioOpen }

        var status = await harness.controller.currentDiagnostics().status
        var calls = await harness.engine.recordedCalls()
        XCTAssertEqual(status, .idle, "no negotiation exists, because no lifetime could own one")
        XCTAssertFalse(calls.contains("createOffer"), "nothing was offered on a link that is not there; calls=\(calls)")
        let sent = await harness.transport.sentSignals()
        XCTAssertTrue(sent.isEmpty, "nor sent: sent=\(sent)")
        let localAudioOpen = await harness.controller.currentDiagnostics().localAudioOpen
        XCTAssertTrue(localAudioOpen, "consent is recorded, so `attachVoice` will rebuild on the next Connected")

        // A boundary arriving in the gap finds nothing to retire and must not disturb consent.
        await harness.controller.onControlLinkLost(retiredControlGeneration: Self.controlA)
        try await harness.settle()
        var audioOpen = await harness.audio.isOpen()
        XCTAssertTrue(audioOpen, "a boundary in the gap may not close capture")

        // The same authenticated-availability event that production attachVoice emits.
        await harness.controller.controlAuthenticated(controlGeneration: Self.controlB)
        try await harness.awaitEngineCall("createOffer")
        status = await harness.controller.currentDiagnostics().status
        XCTAssertEqual(status, .negotiating, "the successor rebuilds what the gap press could not start")

        // ...and it is genuinely the successor's, not a negotiation owned by nobody.
        await harness.controller.onControlLinkLost(retiredControlGeneration: Self.controlA)
        try await harness.awaitSuperseded(1)
        status = await harness.controller.currentDiagnostics().status
        XCTAssertEqual(status, .negotiating, "A cannot retire B's rebuild")

        await harness.controller.onControlLinkLost(retiredControlGeneration: Self.controlB)
        try await harness.awaitEngineCall("stop")
        status = await harness.controller.currentDiagnostics().status
        calls = await harness.engine.recordedCalls()
        XCTAssertEqual(status, .idle, "B's own boundary can; calls=\(calls)")
        audioOpen = await harness.audio.isOpen()
        XCTAssertTrue(audioOpen, "and still never closes capture")
        await harness.controller.shutdown()
    }

    /// A held remote offer is negotiation state too — it is what a later consent answers (§7.3) — so
    /// it is owned by the lifetime that delivered it, and a predecessor's boundary may not discard it.
    ///
    /// This matters more than it looks: the held offer is the *only* copy. A peer never re-sends one,
    /// so discarding a successor's held offer wedges voice for the ride segment exactly as problem 56
    /// did.
    func testAPredecessorsBoundaryCannotDiscardASuccessorsHeldRemoteOffer() async throws {
        let harness = try await Harness(isLocalLeader: false)
        // No local consent yet, so the offer is held rather than answered (ARCHITECTURE §6.4).
        harness.controller.submit(.offer(voiceSessionId: Self.genAt(901), sdp: Self.sdp), controlGeneration: Self.controlB)
        try await harness.awaitCondition { await harness.controller.currentDiagnostics().peerRequestedVoice }
        let audioOpen = await harness.audio.isOpen()
        XCTAssertFalse(audioOpen, "precondition: a peer's offer never opens the microphone")

        await harness.controller.onControlLinkLost(retiredControlGeneration: Self.controlA)
        try await harness.awaitSuperseded(1)

        // Consent arrives under the successor: the held offer must still be there to answer.
        await harness.controller.start(controlGeneration: Self.controlB)
        try await harness.awaitEngineCall("createAnswer")

        let calls = await harness.engine.recordedCalls()
        XCTAssertTrue(calls.contains("applyRemote(OFFER)"), "the held offer survived and was answered; calls=\(calls)")
        let status = await harness.controller.currentDiagnostics().status
        XCTAssertEqual(status, .negotiating)
        await harness.controller.shutdown()
    }

    /// The degrade `VoiceController` forces when a bounded lane overflows names **no** lifetime, and
    /// must therefore retire whatever is live regardless of who owns it: it is a local safety valve,
    /// not a statement about a control lifetime.
    ///
    /// Driven through the production input rather than by overflowing a lane, because what is under
    /// test is the reducer's response to a `nil` generation — the shape both the degrade and a
    /// connection that died before authenticating produce.
    func testABoundaryNamingNoLifetimeRetiresWhateverIsLive() async throws {
        let harness = try await Harness(isLocalLeader: false)
        await harness.controller.start(controlGeneration: Self.controlA)
        harness.controller.submit(.offer(voiceSessionId: Self.genAt(902), sdp: Self.sdp), controlGeneration: Self.controlC)
        try await harness.awaitEngineCall("createAnswer")

        await harness.controller.onControlLinkLost(retiredControlGeneration: nil)
        try await harness.awaitEngineCall("stop")

        let status = await harness.controller.currentDiagnostics().status
        XCTAssertEqual(status, .idle, "the safe degrade has to work whoever owns the negotiation")
        let audioOpen = await harness.audio.isOpen()
        XCTAssertTrue(audioOpen, "and it is still only a media teardown")
        await harness.controller.shutdown()
    }

    /// A peer's own `closed` returns the table to a state owning nothing, whoever owned it before —
    /// after which a boundary for *any* lifetime is the pre-existing empty-table no-op rather than a
    /// supersession.
    func testATerminalPeerStateClearsOwnershipAlongWithTheNegotiation() async throws {
        let harness = try await Harness(isLocalLeader: false)
        await harness.controller.start(controlGeneration: Self.controlA)
        harness.controller.submit(.offer(voiceSessionId: Self.genAt(901), sdp: Self.sdp), controlGeneration: Self.controlB)
        try await harness.awaitEngineCall("createAnswer")

        harness.controller.submit(
            .state(voiceSessionId: Self.genAt(901), state: .closed, micMuted: false, mode: .continuous),
            controlGeneration: Self.controlB
        )
        try await harness.awaitEngineCall("stop")
        var status = await harness.controller.currentDiagnostics().status
        XCTAssertEqual(status, .idle)

        await harness.controller.onControlLinkLost(retiredControlGeneration: Self.controlB)
        try await harness.settle()
        status = await harness.controller.currentDiagnostics().status
        XCTAssertEqual(status, .idle)
        let superseded = await harness.controller.currentDiagnostics().droppedSignals[.supersededControlLifetime]
        XCTAssertNil(superseded, "an empty table is a no-op, not a supersession")
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
        }

        func awaitEngineCall(_ call: String) async throws {
            try await awaitCondition { await self.engine.recordedCalls().contains(call) }
            try await settle()
        }

        /// Waits until `count` boundaries have been *reduced* and correctly ignored.
        ///
        /// This is the whole reason `.supersededControlLifetime` is recorded rather than left as a
        /// silent no-op: preserving a successor produces no other observable, so without it the only
        /// way to know the boundary had been applied would be to sleep and hope.
        func awaitSuperseded(_ count: Int) async throws {
            try await awaitCondition {
                (await self.controller.currentDiagnostics().droppedSignals[.supersededControlLifetime] ?? 0) >= count
            }
        }

        /// A stale callback in the coalesced lane proves higher-priority work has reduced.
        func settle() async throws {
            let count = await controller.currentDiagnostics().droppedSignals[.staleEngineCallback] ?? 0
            await engine.emit(.remoteTrackChanged(
                voiceSessionId: VoiceSessionId(String(repeating: "0", count: 32)), present: false
            ))
            try await awaitCondition {
                await (self.controller.currentDiagnostics().droppedSignals[.staleEngineCallback] ?? 0) > count
            }
        }

        func awaitCondition(_ condition: @escaping () async -> Bool) async throws {
            let (edges, continuation) = AsyncStream<Void>.makeStream(bufferingPolicy: .bufferingNewest(1))
            await controller.setOnDiagnosticsChanged { _ in continuation.yield(()) }
            let watchdog = Task {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                if !Task.isCancelled { continuation.finish() }
            }
            defer { watchdog.cancel(); continuation.finish() }
            if await condition() { return }
            for await _ in edges {
                if await condition() { return }
            }
            XCTFail("condition not met within the timeout")
        }
    }

    private static let sdp = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\n"

    /// Three **control authentication** generations — a different identity from the
    /// `voice_session_id`s above, and from each other only in being strictly increasing, which is the
    /// one property `ControlSessionManager.activateAuthenticatedSession` guarantees.
    private static let controlA: Int64 = 1
    private static let controlB: Int64 = 2
    private static let controlC: Int64 = 3

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
