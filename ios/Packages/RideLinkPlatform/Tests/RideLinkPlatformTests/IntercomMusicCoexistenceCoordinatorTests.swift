import RideLinkCore
@testable import RideLinkPlatform
import XCTest

@MainActor
final class IntercomMusicCoexistenceCoordinatorTests: XCTestCase {
    func testFiftyPttCyclesKeepOnePlayerLifetimeAndRestoreExactGain() async {
        let port = FakeMusicPort(state: playing("00000000-0000-0000-0000-000000000001"))
        let coordinator = IntercomMusicCoexistenceCoordinator(music: port, sleeper: { _ in })
        let generation = coordinator.beginLifetime(policy: .modeC)
        await coordinator.awaitEffectsSettled()

        for _ in 0..<50 {
            coordinator.updateVoice(
                generation: generation,
                available: true,
                localTransmitting: true,
                peerTransmitting: false,
                routeState: .stable,
                interrupted: false,
                transitionTimedOut: false
            )
            await coordinator.awaitEffectsSettled()
            XCTAssertEqual(port.gains.last, 350)
            coordinator.updateVoice(
                generation: generation,
                available: true,
                localTransmitting: false,
                peerTransmitting: false,
                routeState: .stable,
                interrupted: false,
                transitionTimedOut: false
            )
            await coordinator.awaitEffectsSettled()
            XCTAssertEqual(port.gains.last, 1_000)
        }

        XCTAssertEqual(port.lifetimes, [generation])
        XCTAssertEqual(coordinator.diagnostics.appliedVolumePermille, 1_000)
        XCTAssertTrue(port.pauseCalls.isEmpty)
        XCTAssertTrue(port.resumeCalls.isEmpty)
    }

    func testBlockedPredecessorRampCannotMutateSuccessorLifetime() async {
        let latch = AsyncLatch()
        let port = FakeMusicPort(state: playing("00000000-0000-0000-0000-000000000001"))
        let coordinator = IntercomMusicCoexistenceCoordinator(music: port, sleeper: { _ in await latch.wait() })
        let first = coordinator.beginLifetime(policy: .modeC)
        coordinator.updateVoice(
            generation: first,
            available: true,
            localTransmitting: true,
            peerTransmitting: false,
            routeState: .stable,
            interrupted: false,
            transitionTimedOut: false
        )
        await Task.yield()

        let second = coordinator.beginLifetime(policy: .modeA)
        coordinator.updateVoice(
            generation: second,
            available: true,
            localTransmitting: false,
            peerTransmitting: false,
            routeState: .stable,
            interrupted: false,
            transitionTimedOut: false
        )
        await latch.release()
        await coordinator.awaitEffectsSettled()

        XCTAssertEqual(port.lifetimes, [first, second])
        XCTAssertFalse(Array(port.appliedGenerations.drop { $0 == first }).contains(first))
        XCTAssertEqual(port.gains.last, 1_000)
    }

    func testTerminalLifetimeWaitJoinsInProgressRestoreRamp() async {
        let latch = AsyncLatch()
        let port = FakeMusicPort(state: playing("00000000-0000-0000-0000-000000000001"))
        let coordinator = IntercomMusicCoexistenceCoordinator(music: port, sleeper: { _ in await latch.wait() })
        let generation = coordinator.beginLifetime(policy: .modeC)
        coordinator.updateVoice(
            generation: generation,
            available: true,
            localTransmitting: true,
            peerTransmitting: false,
            routeState: .stable,
            interrupted: false,
            transitionTimedOut: false
        )
        coordinator.endLifetime(generation)

        var returned = false
        let waiter = Task { @MainActor in
            await coordinator.awaitLifetimeEnded()
            returned = true
        }
        await Task.yield()
        XCTAssertFalse(returned, "teardown must not return while its restore ramp is suspended")

        await latch.release()
        await waiter.value
        XCTAssertTrue(returned)
        XCTAssertEqual(port.gains.last, 1_000)
    }

    func testModeDDoesNotResumeAfterUserPauseOrResumeReplacedTrack() async {
        let first = "00000000-0000-0000-0000-000000000002"
        let second = "00000000-0000-0000-0000-000000000003"
        let third = "00000000-0000-0000-0000-000000000004"
        let port = FakeMusicPort(state: playing(first))
        let coordinator = IntercomMusicCoexistenceCoordinator(music: port, sleeper: { _ in })
        let generation = coordinator.beginLifetime(policy: .modeD)
        coordinator.updateVoice(
            generation: generation,
            available: true,
            localTransmitting: true,
            peerTransmitting: false,
            routeState: .stable,
            interrupted: false,
            transitionTimedOut: false
        )
        await coordinator.awaitEffectsSettled()
        XCTAssertEqual(port.pauseCalls, [first])

        coordinator.onPlaybackIntent(playing: false)
        coordinator.updateVoice(
            generation: generation,
            available: true,
            localTransmitting: false,
            peerTransmitting: false,
            routeState: .stable,
            interrupted: false,
            transitionTimedOut: false
        )
        await coordinator.awaitEffectsSettled()
        XCTAssertTrue(port.resumeCalls.isEmpty)

        port.emit(playing(second))
        coordinator.updateVoice(
            generation: generation,
            available: true,
            localTransmitting: true,
            peerTransmitting: false,
            routeState: .stable,
            interrupted: false,
            transitionTimedOut: false
        )
        await coordinator.awaitEffectsSettled()
        let replaced = port.pauseCalls.last
        port.emit(playing(third))
        coordinator.updateVoice(
            generation: generation,
            available: true,
            localTransmitting: false,
            peerTransmitting: false,
            routeState: .stable,
            interrupted: false,
            transitionTimedOut: false
        )
        await coordinator.awaitEffectsSettled()
        XCTAssertFalse(port.resumeCalls.contains(replaced ?? ""))
    }

    private final class FakeMusicPort: MusicCoexistencePort {
        var coexistencePlayerState: PlayerState
        var coexistenceBaseVolumePermille = 1_000
        weak var coexistenceEvents: (any CoexistenceEventSink)?
        var lifetimes: [Int64] = []
        var gains: [Int] = []
        var appliedGenerations: [Int64] = []
        var pauseCalls: [String] = []
        var resumeCalls: [String] = []
        private var generation: Int64 = 0

        init(state: PlayerState) { coexistencePlayerState = state }

        func beginCoexistenceLifetime(_ generation: Int64) async {
            self.generation = generation
            lifetimes.append(generation)
        }

        func applyCoexistenceGain(generation: Int64, volumePermille: Int) async -> Bool {
            guard generation == self.generation else { return false }
            appliedGenerations.append(generation)
            gains.append(volumePermille)
            return true
        }

        func pauseForVoice(generation: Int64, trackToken: String) async -> Bool {
            guard generation == self.generation, coexistencePlayerState.localEntryId?.value == trackToken else { return false }
            pauseCalls.append(trackToken)
            return true
        }

        func resumeAfterVoice(generation: Int64, trackToken: String) async -> Bool {
            guard generation == self.generation, coexistencePlayerState.localEntryId?.value == trackToken else { return false }
            resumeCalls.append(trackToken)
            return true
        }

        func emit(_ state: PlayerState) {
            coexistencePlayerState = state
            coexistenceEvents?.onMusicChanged(state)
        }
    }

    private actor AsyncLatch {
        private var isOpen = false
        private var continuations: [CheckedContinuation<Void, Never>] = []

        func wait() async {
            if isOpen { return }
            await withCheckedContinuation { continuations.append($0) }
        }

        func release() {
            isOpen = true
            continuations.forEach { $0.resume() }
            continuations.removeAll()
        }
    }

    private func playing(_ token: String) -> PlayerState {
        PlayerState(localEntryId: LocalEntryId(token), durationMs: 10_000, playing: true)
    }
}
