import Foundation
import RideLinkCore
import XCTest
@testable import RideLinkPlatform

/// Real `AVAudioEngine`, real AAC decode, real `test-media/synthetic/normal.m4a` — the mirror of
/// Android's `ExoPlayerMusicPlayerTest`, run under `swift test` on macOS rather than only on a
/// device/simulator, since neither `AVAudioEngine` nor `AVAudioPlayerNode` is iOS-only (see
/// `AVAudioEnginePlayer`'s own doc comment). A fake `Player` (used elsewhere for pure
/// queue/coordinator tests) proves none of what this proves.
final class AVAudioEnginePlayerTests: XCTestCase {
    private var player: AVAudioEnginePlayer!
    private var states: AsyncStream<PlayerState>!
    private var statesContinuation: AsyncStream<PlayerState>.Continuation!

    override func setUp() async throws {
        player = AVAudioEnginePlayer()
        let (stream, continuation) = AsyncStream<PlayerState>.makeStream()
        states = stream
        statesContinuation = continuation
        await player.setStateSink { [statesContinuation] state in statesContinuation?.yield(state) }
    }

    override func tearDown() async throws {
        await player.release()
        statesContinuation.finish()
    }

    private func location(_ filename: String) throws -> LocalTrackLocation {
        LocalTrackLocation(uri: try TestMedia.url(filename).absoluteString)
    }

    private func firstState(timeout: TimeInterval = 15, where predicate: @escaping @Sendable (PlayerState) -> Bool) async throws -> PlayerState {
        let stream = states!
        return try await withTimeout(timeout) {
            for await state in stream where predicate(state) {
                return state
            }
            throw TimeoutError.exhausted
        }
    }

    func testLoadingARealTrackReportsARealDuration() async throws {
        let id = LocalEntryId("00000000-0000-4000-8000-a1a1a1a1a1a1")
        await player.execute(.load(localEntryId: id, location: try location("normal.m4a")))
        let ready = try await firstState { $0.durationMs > 0 }
        XCTAssertEqual(ready.localEntryId, id)
        XCTAssertGreaterThan(ready.durationMs, 0, "a real AAC file must report a real, positive duration")
    }

    func testPlayPauseAndPositionAdvancement() async throws {
        await player.execute(.load(localEntryId: LocalEntryId("00000000-0000-4000-8000-b2b2b2b2b2b2"), location: try location("normal.m4a")))
        _ = try await firstState { $0.durationMs > 0 }
        await player.execute(.play)
        let playing = try await firstState { $0.playing }
        XCTAssertTrue(playing.playing)
        let advanced = try await firstState { $0.positionMs > 0 }
        XCTAssertGreaterThan(advanced.positionMs, 0, "position must actually advance while playing")
        await player.execute(.pause)
        let paused = try await firstState { !$0.playing }
        XCTAssertFalse(paused.playing)
    }

    func testSeekMovesPosition() async throws {
        let seekTargetMs: Int64 = 200
        await player.execute(.load(localEntryId: LocalEntryId("00000000-0000-4000-8000-c3c3c3c3c3c3"), location: try location("normal.m4a")))
        _ = try await firstState { $0.durationMs > 0 }
        await player.execute(.seek(positionMs: seekTargetMs))
        let sought = try await firstState { $0.positionMs >= seekTargetMs }
        XCTAssertGreaterThanOrEqual(sought.positionMs, seekTargetMs)
    }

    func testStopResetsPositionAndPlayingState() async throws {
        await player.execute(.load(localEntryId: LocalEntryId("00000000-0000-4000-8000-d4d4d4d4d4d4"), location: try location("normal.m4a")))
        _ = try await firstState { $0.durationMs > 0 }
        await player.execute(.play)
        _ = try await firstState { $0.playing }
        await player.execute(.stop)
        let stopped = try await firstState { !$0.playing && $0.positionMs == 0 }
        XCTAssertFalse(stopped.playing)
        XCTAssertEqual(stopped.positionMs, 0)
    }

    func testLoadingAMissingFileReportsFileMissing() async throws {
        let missingUrl = FileManager.default.temporaryDirectory.appendingPathComponent("does-not-exist-\(UUID().uuidString).m4a")
        await player.execute(
            .load(localEntryId: LocalEntryId("00000000-0000-4000-8000-e5e5e5e5e5e5"), location: LocalTrackLocation(uri: missingUrl.absoluteString))
        )
        let failed = try await firstState { $0.error != nil }
        XCTAssertEqual(failed.error, .fileMissing)
    }

    func testLoadingUnparseableContentReportsAFailureRatherThanCrashing() async throws {
        // Deliberately not test-media/synthetic/unsupported.xyz — that fixture's bytes are a real,
        // valid M4A (only its extension is wrong); genuinely non-media bytes are what exercises the
        // player's own failure path (mirrors the same reasoning in ExoPlayerMusicPlayerTest).
        let garbageUrl = FileManager.default.temporaryDirectory.appendingPathComponent("garbage-\(UUID().uuidString).m4a")
        try Data(repeating: 0x2A, count: 256).write(to: garbageUrl)
        defer { try? FileManager.default.removeItem(at: garbageUrl) }
        await player.execute(
            .load(localEntryId: LocalEntryId("00000000-0000-4000-8000-f6f6f6f6f6f6"), location: LocalTrackLocation(uri: garbageUrl.absoluteString))
        )
        let failed = try await firstState { $0.error != nil }
        XCTAssertTrue(
            failed.error == .unsupportedFormat || failed.error == .decodeFailed,
            "expected an unsupported/decode failure, got \(String(describing: failed.error))"
        )
    }

    func testEndOfTrackIsReportedAsEnded() async throws {
        // normal.m4a is ~0.5s — short enough to reach the real end of track within the test's own
        // timeout rather than needing a fake clock, proving the real AVAudioPlayerNode completion
        // callback path end to end.
        await player.execute(.load(localEntryId: LocalEntryId("00000000-0000-4000-8000-070707070707"), location: try location("normal.m4a")))
        _ = try await firstState { $0.durationMs > 0 }
        await player.execute(.play)
        let ended = try await firstState(timeout: 15) { $0.ended }
        XCTAssertTrue(ended.ended)
    }
}

private enum TimeoutError: Error { case exhausted }

private func withTimeout<T: Sendable>(_ seconds: TimeInterval, operation: @escaping @Sendable () async throws -> T) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask { try await operation() }
        group.addTask {
            try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            throw TimeoutError.exhausted
        }
        let result = try await group.next()!
        group.cancelAll()
        return result
    }
}
