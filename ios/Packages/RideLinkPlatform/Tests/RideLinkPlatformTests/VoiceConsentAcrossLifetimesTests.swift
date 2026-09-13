import Foundation
import RideLinkCore
import XCTest

@testable import RideLinkPlatform

/// **The production ordering STATUS §4 problem 63's first fix left open, at the seam that produces it.**
///
/// `VoiceCrossLifetimeAuthorityTests.testAStartAuthorisedByARetiredLifetimeKeepsANewerLifetimesHeldOfferIntact`
/// proves the *table's* answer to "a stale press meets a newer lifetime's held offer". It then supplies a
/// second `start(controlGeneration: B)` by hand — and that hand-supplied press is the whole problem: it is
/// an event **production never sends**. Nothing in `SessionCoordinator` presses Start a second time, the
/// user has already consented, and the offerer sends its `VOICE_OFFER` exactly once per `voice_session_id`
/// (PROTOCOL §7.4). So the held offer stayed held for the rest of the ride segment with capture already
/// open. That is a liveness defect rather than a safety one, which is why every safety assertion passed.
///
/// This file asserts progress from the events production actually produces, and from nothing else.
///
/// ## Why the coordinator is mirrored rather than executed
///
/// `SessionCoordinator` lives in the **app target** (`ios/RideLink/SessionCoordinator.swift`).
/// `RideLink.xcodeproj` has exactly one native target — the application — and CI *builds* it but runs no
/// tests against it (`.github/workflows/ci.yml`: two `xcodebuild … build` steps, no `xcodebuild test`).
/// There is therefore no bundle from which `SessionCoordinator` can be instantiated, and adding an XCTest
/// bundle plus a simulator test step to CI is a much larger change than this defect warrants.
///
/// So `CoordinatorShapedVoiceHost` below reproduces the coordinator's three voice-start decision points,
/// and `testTheMirroredCoordinatorDecisionsAreStillTheOnesProductionMakes` **reads the real source file**
/// and fails if any of them stops being what is mirrored here. The mirror is checked, not asserted: if
/// someone makes `startIntercom` offer synchronously, that test fails and this one must be re-derived
/// rather than quietly becoming a test of nothing.
///
/// The Android mirror is `VoiceConsentAcrossLifetimesTest`.
final class VoiceConsentAcrossLifetimesTests: XCTestCase {
    // MARK: - the production ordering

    /// **P63-C — reachable on unmodified iOS production, with no second press anywhere.**
    ///
    /// 1. A is authenticated. The user presses Start Intercom.
    /// 2. `SessionCoordinator.startIntercom` reads `liveAuthenticatedGeneration()` — A — and hands the call
    ///    to a deferred, session-owned task, because `VoiceController.start` is actor-isolated (it stamps
    ///    `VoiceSetupTimeline`) and a press therefore cannot reach the bounded mailbox without a hop. The
    ///    press has not reached the controller's mailbox yet.
    /// 3. A dies. B authenticates and `.connected(B)` is delivered.
    /// 4. `attachVoice`'s §7.8 rebuild is skipped: it is gated on `voiceDiagnostics.localAudioOpen`, which
    ///    is published through `OrderedEventChannel` *after* the controller reduces something — and the
    ///    only thing that would have set it is still sitting in step 2's `Task`.
    /// 5. B's `VOICE_OFFER` is admitted under B, reduced first, and held for want of consent. It is the
    ///    only copy the offerer will ever send.
    /// 6. Step 2's `Task` finally runs: `.startRequested(controlGeneration: A)` meets a held offer owned
    ///    by B.
    ///
    /// There is no second `.connected(B)` and no second press, so step 6 is the last event production
    /// produces. The assertion is therefore about **progress**: the answer must be created, and it must go
    /// out on B, naming B's own `voice_session_id`.
    @MainActor
    func testAPressDeliveredAfterItsLifetimeDiedStillAnswersTheSuccessorsHeldOffer() async throws {
        let harness = try await Harness(isLocalLeader: false)
        defer { harness.finish() }
        let host = CoordinatorShapedVoiceHost(controller: harness.controller)
        defer { host.finish() }
        host.attachVoice(authGeneration: Self.controlA, diagnostics: harness.diagnosticsChannel())

        // 1-2. The press, with A live. Delivery is held exactly where the unstructured `Task` holds it.
        host.liveAuthenticatedGeneration = Self.controlA
        host.startIntercom()
        XCTAssertFalse(host.voiceDiagnostics.localAudioOpen, "precondition: the press has not been reduced")

        // 3-4. B authenticates. The §7.8 rebuild is skipped, for the reason production skips it.
        await harness.setLiveGeneration(Self.controlB)
        host.liveAuthenticatedGeneration = Self.controlB
        host.attachVoice(authGeneration: Self.controlB, diagnostics: harness.diagnosticsChannel())
        XCTAssertEqual(host.rebuildStartsIssued, 0, "attachVoice saw no open capture, so it issued no start")

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

    // MARK: - the mirror is checked against the real source

    /// Fails if `SessionCoordinator` stops making the decisions `CoordinatorShapedVoiceHost` mirrors.
    ///
    /// Whitespace is normalised and only the load-bearing fragments are matched, so reformatting does not
    /// break it — but removing the deferral, or gating the §7.8 rebuild on something other than the
    /// published diagnostics projection, does.
    func testTheMirroredCoordinatorDecisionsAreStillTheOnesProductionMakes() throws {
        let source = try Self.sessionCoordinatorSource()
        let normalised = Self.squash(source)
        for fragment in [
            // 1. the press reads the live generation synchronously …
            "let generation = controlSessionManager.liveAuthenticatedGeneration()",
            // 2. … and delivers the start on a later turn, which is what reorders it against inbound
            //    frames. Session-owned since STATUS §4 problem 67, which changes who joins it and not
            //    when it runs — `VoiceController.start` is actor-isolated, so the hop is unavoidable.
            "launchInSession { _ in await voice.start(controlGeneration: generation) }",
            // 3. the §7.8 rebuild is gated on the asynchronously published projection rather than on the
            //    controller's own state, which is why a press still in flight is invisible to it
            "if voiceDiagnostics.localAudioOpen {",
            "await voice.start(controlGeneration: authGeneration)",
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
    /// is *when* the unstructured `Task` runs — production leaves that to the scheduler, and a test that
    /// left it there would be a race. `runDeferredWork` names the instant instead.
    @MainActor
    private final class CoordinatorShapedVoiceHost {
        private let controller: VoiceController
        private var deferred: [@Sendable () async -> Void] = []

        /// `SessionCoordinator.voiceDiagnostics` — written only by the channel consumer.
        private(set) var voiceDiagnostics = VoiceDiagnostics()
        /// `ControlSessionManager.liveAuthenticatedGeneration()`, as the press reads it.
        var liveAuthenticatedGeneration: Int64?
        /// How many times `attachVoice`'s §7.8 rebuild decided to start. The defect is that it stays zero.
        private(set) var rebuildStartsIssued = 0

        private var attached = false
        private var consumer: Task<Void, Never>?

        init(controller: VoiceController) { self.controller = controller }

        /// `SessionCoordinator.attachVoice`. The first call installs the diagnostics consumer; a later one
        /// is the reconnect branch, which rebuilds **only** when the published projection says capture is
        /// open.
        func attachVoice(authGeneration: Int64, diagnostics: AsyncStream<VoiceDiagnostics>) {
            if attached {
                if voiceDiagnostics.localAudioOpen {
                    rebuildStartsIssued += 1
                    let controller = self.controller
                    deferred.append { await controller.start(controlGeneration: authGeneration) }
                }
                return
            }
            attached = true
            consumer = Task { @MainActor [weak self] in
                for await next in diagnostics { self?.voiceDiagnostics = next }
            }
        }

        /// `SessionCoordinator.startIntercom`, minus the `RideStartPolicy` gate — which decides whether the
        /// press happens at all, never which lifetime it carries.
        func startIntercom() {
            let generation = liveAuthenticatedGeneration
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

        func setLiveGeneration(_ generation: Int64) async {
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
}
