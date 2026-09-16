import Foundation
import RideLinkCore
import XCTest

@testable import RideLinkPlatform

final class VoiceControllerShutdownTests: XCTestCase {
    @MainActor
    func testShutdownJoinsAParkedSendBeforeCleanupAndClosesAdmission() async throws {
        let gate = ShutdownGate()
        let transport = ParkedVoiceTransport(gate: gate)
        let engine = FakeVoiceEngine()
        let audio = FakeVoiceAudioSession()
        let controller = VoiceController(
            engine: engine, audioSession: audio, transport: transport,
            isLocalLeader: true, localTrackId: "shutdown-test",
            newVoiceSessionId: { Self.id }
        )
        let publications = DiagnosticsPublications()
        await controller.setOnDiagnosticsChanged { _ in publications.record() }
        await controller.attach()
        await controller.start(controlGeneration: 1)
        await fulfillment(of: [gate.entered], timeout: 5)
        let shutdown = Task { await controller.shutdown() }
        await fulfillment(of: [gate.cancelled], timeout: 5)
        let beforeRelease = await engine.recordedCalls()
        XCTAssertTrue(beforeRelease.isEmpty, "cleanup must wait for the owned send: \(beforeRelease)")
        let audioBefore = await audio.recordedCalls()
        XCTAssertFalse(audioBefore.contains("close"), "capture cannot close ahead of the consumer")
        // A second caller must join the same terminal operation, never apply another Stop.
        let secondShutdown = Task { await controller.shutdown() }
        secondShutdown.cancel()
        gate.release()
        await shutdown.value
        await secondShutdown.value
        let finalCalls = await engine.recordedCalls()
        XCTAssertEqual(finalCalls, ["stop", "release"], "no CreateOffer from the cancelled Start")
        let audioCalls = await audio.recordedCalls()
        XCTAssertEqual(audioCalls, ["open", "close"])
        let diagnostics = await controller.currentDiagnostics()
        XCTAssertEqual(diagnostics.status, .idle)
        XCTAssertFalse(diagnostics.localAudioOpen)
        let sendCount = await transport.attemptCount()
        let publicationCount = publications.count
        // Exercise both nonisolated producers and actor entry points after terminal shutdown.
        for _ in 0..<100 {
            controller.submit(.offer(voiceSessionId: Self.id, sdp: "v=0\r\n"), controlGeneration: 2)
            controller.setPushToTalkHeld(true)
            await engine.emit(.offerCreated(voiceSessionId: Self.id, sdp: "v=0\r\n"))
        }
        await audio.publish(AudioRouteSnapshot())
        await controller.start(controlGeneration: 2)
        await controller.controlAuthenticated(controlGeneration: 2)
        await controller.onControlLinkLost(retiredControlGeneration: 2)
        await controller.setMicrophoneMuted(false)
        await controller.stop()
        await controller.attach()
        await controller.setOnDiagnosticsChanged { _ in publications.record() }
        await controller.shutdown()
        let afterCalls = await engine.recordedCalls()
        let afterAudio = await audio.recordedCalls()
        let afterDiagnostics = await controller.currentDiagnostics()
        let afterSendCount = await transport.attemptCount()
        XCTAssertEqual(afterCalls, finalCalls)
        XCTAssertEqual(afterAudio, audioCalls)
        XCTAssertEqual(afterDiagnostics, diagnostics)
        XCTAssertEqual(afterSendCount, sendCount)
        XCTAssertEqual(publications.count, publicationCount, "no late route, callback or handler installation publishes")
    }

    @MainActor
    func testAnAlreadyReducedStopCompletesCleanupExactlyOnce() async throws {
        let gate = ShutdownGate()
        let engine = FakeVoiceEngine()
        let audio = FakeVoiceAudioSession()
        let controller = VoiceController(
            engine: engine, audioSession: audio, transport: ParkedVoiceTransport(gate: gate, parkClosed: true),
            isLocalLeader: true, localTrackId: "shutdown-test", newVoiceSessionId: { Self.id }
        )
        let ready = expectation(description: "capture and negotiation published")
        let readyPublications = DiagnosticsPublications()
        await controller.setOnDiagnosticsChanged { d in
            if d.localAudioOpen && d.status == .negotiating && readyPublications.record() == 1 { ready.fulfill() }
        }
        await controller.attach()
        await controller.start(controlGeneration: 1)
        await fulfillment(of: [ready], timeout: 5)
        await controller.stop()
        await fulfillment(of: [gate.entered], timeout: 5)
        let shutdown = Task { await controller.shutdown() }
        await fulfillment(of: [gate.cancelled], timeout: 5)
        let parkedCalls = await engine.recordedCalls()
        XCTAssertFalse(parkedCalls.contains("stop"))
        gate.release()
        await shutdown.value
        let calls = await engine.recordedCalls()
        let audioCalls = await audio.recordedCalls()
        XCTAssertEqual(calls.filter { $0 == "stop" }.count, 1)
        XCTAssertEqual(calls.filter { $0 == "release" }.count, 1)
        XCTAssertEqual(audioCalls, ["open", "close"])
        let d = await controller.currentDiagnostics()
        XCTAssertEqual(d.status, .idle)
        XCTAssertFalse(d.localAudioOpen)
    }

    @MainActor
    func testShutdownJoinsAnInFlightDiagnosticsPoll() async throws {
        let gate = ShutdownGate()
        let engine = ParkedVoiceEngine(refreshGate: gate)
        let audio = FakeVoiceAudioSession()
        let controller = VoiceController(
            engine: engine, audioSession: audio, transport: RecordingVoiceTransport(),
            isLocalLeader: true, localTrackId: "shutdown-test", newVoiceSessionId: { Self.id }
        )
        await controller.attach()
        await controller.start(controlGeneration: 1)
        // Observe the production poll entering refresh; no test sleep schedules this ordering.
        await fulfillment(of: [gate.entered], timeout: 10)
        let shutdown = Task { await controller.shutdown() }
        await fulfillment(of: [gate.cancelled], timeout: 5)
        let before = await engine.base.recordedCalls()
        XCTAssertFalse(before.contains("stop"), "poll must join before final cleanup")
        gate.release()
        await shutdown.value
        let calls = await engine.base.recordedCalls()
        XCTAssertEqual(calls.filter { $0 == "stop" }.count, 1)
        XCTAssertEqual(calls.filter { $0 == "release" }.count, 1)
        let d = await controller.currentDiagnostics()
        XCTAssertEqual(d.engine.transportState, .closed)
        XCTAssertFalse(d.localAudioOpen)
        await controller.shutdown()
        let after = await engine.base.recordedCalls()
        XCTAssertEqual(after, calls)
    }

    @MainActor
    func testShutdownJoinsAttachmentAndCannotBeReattached() async throws {
        let gate = ShutdownGate()
        let engine = ParkedVoiceEngine(attachGate: gate)
        let audio = FakeVoiceAudioSession()
        let controller = VoiceController(
            engine: engine, audioSession: audio, transport: RecordingVoiceTransport(),
            isLocalLeader: true, localTrackId: "shutdown-test", newVoiceSessionId: { Self.id }
        )
        let attachment = Task { await controller.attach() }
        await fulfillment(of: [gate.entered], timeout: 5)
        await controller.start(controlGeneration: 1) // queued before a consumer exists
        let shutdown = Task { await controller.shutdown() }
        await fulfillment(of: [gate.cancelled], timeout: 5)
        gate.release()
        await shutdown.value
        await attachment.value
        await controller.attach()
        await controller.start(controlGeneration: 2)
        await engine.base.emit(.offerCreated(voiceSessionId: Self.id, sdp: "v=0\r\n"))
        await controller.shutdown()
        let calls = await engine.base.recordedCalls()
        let audioCalls = await audio.recordedCalls()
        XCTAssertTrue(calls.isEmpty)
        XCTAssertTrue(audioCalls.isEmpty)
        let d = await controller.currentDiagnostics()
        XCTAssertEqual(d.status, .idle)
        XCTAssertFalse(d.localAudioOpen)
    }

    private static let id = VoiceSessionId(String(repeating: "a", count: 32))
}

/// Cancellation is observable but deliberately does not release the operation: joining is necessary.
private final class ShutdownGate: @unchecked Sendable {
    let entered = XCTestExpectation(description: "owned operation entered")
    let cancelled = XCTestExpectation(description: "owned operation cancelled")
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Void, Never>?

    func park() async {
        await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                lock.lock()
                self.continuation = continuation
                lock.unlock()
                entered.fulfill()
            }
        } onCancel: {
            self.cancelled.fulfill()
        }
    }

    func release() {
        lock.lock()
        let saved = continuation
        continuation = nil
        lock.unlock()
        saved?.resume()
    }
}

private actor ParkedVoiceTransport: VoiceSignalTransport {
    private let gate: ShutdownGate
    private var attempts = 0
    private var armed = true
    private let parkClosed: Bool
    init(gate: ShutdownGate, parkClosed: Bool = false) {
        self.gate = gate
        self.parkClosed = parkClosed
    }
    func attemptCount() -> Int { attempts }
    func send(_ signal: VoiceSignal, controlGeneration: Int64?) async -> Bool {
        attempts += 1
        let matches: Bool
        if case .state(_, .closed, _, _) = signal { matches = true } else { matches = false }
        if armed && (!parkClosed || matches) {
            armed = false
            await gate.park()
        }
        return true
    }
}

private final class DiagnosticsPublications: @unchecked Sendable {
    private let lock = NSLock()
    private var value = 0
    var count: Int { lock.lock(); defer { lock.unlock() }; return value }
    @discardableResult
    func record() -> Int {
        lock.lock()
        defer { lock.unlock() }
        value += 1
        return value
    }
}

/// The operations are real controller awaits; gates only choose when their fixtures complete.
private actor ParkedVoiceEngine: VoiceEngine {
    let base = FakeVoiceEngine()
    private let refreshGate: ShutdownGate?
    private let attachGate: ShutdownGate?
    init(refreshGate: ShutdownGate? = nil, attachGate: ShutdownGate? = nil) {
        self.refreshGate = refreshGate
        self.attachGate = attachGate
    }
    func start(config: VoiceEngineConfig) async -> Result<Void, VoiceEngineError> { await base.start(config: config) }
    func createOffer() async -> Result<Void, VoiceEngineError> { await base.createOffer() }
    func createAnswer() async -> Result<Void, VoiceEngineError> { await base.createAnswer() }
    func applyRemoteDescription(kind: SdpKind, sdp: String) async -> Result<Void, VoiceEngineError> {
        await base.applyRemoteDescription(kind: kind, sdp: sdp)
    }
    func addRemoteCandidate(candidate: String, sdpMid: String?, sdpMlineIndex: Int) async -> Result<Void, VoiceEngineError> {
        await base.addRemoteCandidate(candidate: candidate, sdpMid: sdpMid, sdpMlineIndex: sdpMlineIndex)
    }
    func setMicrophoneMuted(_ muted: Bool) async { await base.setMicrophoneMuted(muted) }
    func stop() async { await base.stop() }
    func release() async { await base.release() }
    func refreshDiagnostics() async {
        if let refreshGate { await refreshGate.park() }
        await base.refreshDiagnostics()
    }
    func diagnostics() async -> VoiceEngineDiagnostics { await base.diagnostics() }
    func setEventSink(_ sink: @escaping @Sendable (VoiceEngineEvent) -> Void) async {
        if let attachGate { await attachGate.park() }
        await base.setEventSink(sink)
    }
}
