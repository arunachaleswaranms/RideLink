import XCTest
import RideLinkCore
@testable import RideLinkPlatform

/// This phase's final hardening pass, Issue 4: `VoiceController.attach()` used to forward every
/// route snapshot through its own unstructured `Task`, which — like every other `Task`-per-event
/// shape this codebase has already fixed (STATUS §2h) — only preserves the order the tasks were
/// *created* in, not the order they *run* in. `routeChannel` (an `OrderedEventChannel`, the same
/// primitive `SessionCoordinator.voiceDiagnosticsChannel` already uses) replaces it. These tests
/// prove the ordering guarantee directly, at the actor boundary, without a real `AVAudioSession`.
///
/// Each test identifies a published snapshot by a distinct `lastTransitionDurationUs` marker rather
/// than by counting `onDiagnosticsChanged` callbacks: `VoiceController.publishRoute` also forwards
/// every route through the intercom mailbox (`.interrupted`), whose own consumer republishes
/// diagnostics again once it runs — a real, pre-existing, harmless duplication unrelated to Issue 4
/// that would make a raw callback count fragile. A monotonic sequence of markers is not fooled by an
/// extra, same-valued republish; it is exactly what "never reordered" means.
final class VoiceControllerRouteOrderingTests: XCTestCase {
    /// 100 snapshots, in order, must never be *observed* out of order — the recorded marker sequence
    /// must be non-decreasing, and every one of the 100 distinct markers must eventually appear.
    func testOneHundredRouteSnapshotsAreProcessedInExactOrder() async throws {
        let engine = FakeVoiceEngine()
        let audio = FakeVoiceAudioSession()
        let controller = VoiceController(
            engine: engine,
            audioSession: audio,
            transport: RecordingVoiceTransport(),
            isLocalLeader: true,
            localTrackId: "test-track"
        )
        await controller.attach()

        let observed = ObservedMarkers()
        // A plain, lock-guarded recorder called synchronously from the (non-async) diagnostics
        // callback — not a `Task` per callback, which would reintroduce inside this test exactly the
        // ordering hazard Issue 4 is about, defeating the point of the assertion below.
        await controller.setOnDiagnosticsChanged { diagnostics in
            if let marker = diagnostics.route.lastTransitionDurationUs {
                observed.append(marker)
            }
        }

        for index in 0..<Self.snapshotCount {
            let state: RouteState = index.isMultiple(of: 2) ? .transitioning : .stable
            await audio.publish(AudioRouteSnapshot(routeState: state, lastTransitionDurationUs: Int64(index)))
        }

        try await awaitCondition { observed.last() == Int64(Self.snapshotCount - 1) }
        let markers = observed.values()
        XCTAssertEqual(markers, markers.sorted(), "a later snapshot must never be observed before an earlier one")
        XCTAssertEqual(Set(markers), Set((0..<Int64(Self.snapshotCount))), "every published snapshot must eventually be observed, none dropped")

        await controller.shutdown()
    }

    /// A route snapshot published to an old controller's audio session *after* `shutdown()` must be a
    /// no-op for that controller — the channel is finished, so a late `send` is silently dropped
    /// (`OrderedEventChannel`'s own documented contract) rather than reaching a torn-down consumer or,
    /// worse, a replacement session's.
    func testAStaleRouteSnapshotAfterShutdownCannotReachThisController() async throws {
        let engine = FakeVoiceEngine()
        let audio = FakeVoiceAudioSession()
        let controller = VoiceController(
            engine: engine,
            audioSession: audio,
            transport: RecordingVoiceTransport(),
            isLocalLeader: true,
            localTrackId: "test-track"
        )
        await controller.attach()

        let observed = ObservedMarkers()
        await controller.setOnDiagnosticsChanged { diagnostics in
            if let marker = diagnostics.route.lastTransitionDurationUs {
                observed.append(marker)
            }
        }

        await audio.publish(AudioRouteSnapshot(routeState: .stable, lastTransitionDurationUs: Self.beforeShutdownMarker))
        try await awaitCondition { observed.values().contains(Self.beforeShutdownMarker) }
        // Let any pre-existing, harmless mailbox-driven republish of the same marker settle before
        // shutting down, so it cannot be mistaken for the post-shutdown publish below arriving late.
        try await Task.sleep(nanoseconds: Self.settleNanoseconds)

        await controller.shutdown()
        // `audio`'s own sink closure still exists (nothing unregisters it — the fake, like the real
        // sessions, is expected to be discarded along with the controller) and still calls
        // `routeChannel.send`, but the channel is finished, so this marker must never be observed.
        await audio.publish(AudioRouteSnapshot(routeState: .transitioning, lastTransitionDurationUs: Self.afterShutdownMarker))
        try await Task.sleep(nanoseconds: Self.settleNanoseconds)

        XCTAssertFalse(
            observed.values().contains(Self.afterShutdownMarker),
            "a route snapshot published after shutdown() must not reach this controller"
        )
    }

    // MARK: - harness

    /// `@unchecked Sendable`, confined to state only ever touched under `lock` — the same discipline
    /// `AudioSessionSignalBox` and `VoiceInputMailboxBox` already use, and for the same reason: the
    /// diagnostics callback that calls `append` is synchronous and non-isolated.
    private final class ObservedMarkers: @unchecked Sendable {
        private let lock = NSLock()
        private var markers: [Int64] = []

        func append(_ marker: Int64) {
            lock.lock()
            markers.append(marker)
            lock.unlock()
        }

        func last() -> Int64? {
            lock.lock()
            defer { lock.unlock() }
            return markers.last
        }

        func values() -> [Int64] {
            lock.lock()
            defer { lock.unlock() }
            return markers
        }
    }

    private func awaitCondition(
        timeoutNanoseconds: UInt64 = 2_000_000_000,
        _ condition: @Sendable () async -> Bool
    ) async throws {
        let deadline = DispatchTime.now().uptimeNanoseconds + timeoutNanoseconds
        while !(await condition()) {
            if DispatchTime.now().uptimeNanoseconds > deadline {
                XCTFail("condition not met in time")
                return
            }
            try await Task.sleep(nanoseconds: 2_000_000)
        }
    }

    private static let snapshotCount = 100
    private static let beforeShutdownMarker: Int64 = 111
    private static let afterShutdownMarker: Int64 = 222
    private static let settleNanoseconds: UInt64 = 50_000_000
}
