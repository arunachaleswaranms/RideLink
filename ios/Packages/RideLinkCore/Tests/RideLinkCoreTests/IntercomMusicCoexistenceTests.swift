import XCTest

@testable import RideLinkCore

final class IntercomMusicCoexistenceTests: XCTestCase {
    private let generation: Int64 = 1
    private let track = "track-a"

    func testModeADucksAndRestoresExactly() {
        var state = started(.modeA)
        let down = voice(state, local: true)
        XCTAssertEqual([.rampMusicVolume(targetPermille: 250, durationMs: 200)], down.actions)
        state = down.state
        XCTAssertEqual(
            [.rampMusicVolume(targetPermille: 1_000, durationMs: 200)],
            voice(state, local: false).actions
        )
    }

    func testModeCMultipliesRatherThanOverwritingUserVolume() {
        var state = started(.modeC, baseVolumePermille: 800)
        let down = voice(state, local: true)
        XCTAssertEqual(800, down.state.baseVolumePermille)
        XCTAssertEqual([.rampMusicVolume(targetPermille: 280, durationMs: 200)], down.actions)
        state = down.state
        let up = voice(state, local: false)
        XCTAssertEqual(800, up.state.baseVolumePermille)
        XCTAssertEqual([.rampMusicVolume(targetPermille: 800, durationMs: 200)], up.actions)
    }

    func testDuplicateActivityIsIdempotentAndRapidReversalConverges() {
        var state = started(.modeC)
        state = voice(state, local: true).state
        XCTAssertTrue(voice(state, local: true).actions.isEmpty)
        let reverse = voice(state, local: false)
        XCTAssertEqual([.rampMusicVolume(targetPermille: 1_000, durationMs: 200)], reverse.actions)
        XCTAssertEqual(1_000, reverse.state.targetVolumePermille)
    }

    func testModeDNeverResumesUserPauseEndedOrReplacementTrack() {
        var state = started(.modeD)
        let paused = voice(state, local: true)
        XCTAssertEqual([.pauseMusicForVoice(trackToken: track)], paused.actions)
        state = paused.state
        state = reduce(state, .userPlaybackIntent(generation: generation, playing: false)).state
        XCTAssertTrue(voice(state, local: false).actions.isEmpty)

        state = started(.modeD)
        state = voice(state, local: true).state
        state = reduce(
            state,
            .musicChanged(generation: generation, available: true, trackToken: track, playing: false, ended: true)
        ).state
        XCTAssertTrue(voice(state, local: false).actions.isEmpty)

        state = started(.modeD)
        state = voice(state, local: true).state
        let replacement = reduce(
            state,
            .musicChanged(generation: generation, available: true, trackToken: "replacement", playing: true, ended: false)
        )
        XCTAssertEqual(
            [.pauseMusicForVoice(trackToken: "replacement"), .rampMusicVolume(targetPermille: 1_000, durationMs: 200)],
            replacement.actions
        )
        XCTAssertEqual(
            [.resumeMusicAfterVoice(trackToken: "replacement")],
            voice(replacement.state, local: false).actions
        )
    }

    func testModeDResumesOnlyItsOwnExactTemporaryPause() {
        var state = started(.modeD)
        state = voice(state, local: true).state
        state = reduce(
            state,
            .musicChanged(generation: generation, available: true, trackToken: track, playing: false, ended: false)
        ).state
        let resumed = voice(state, local: false)
        XCTAssertEqual([.resumeMusicAfterVoice(trackToken: track)], resumed.actions)
        XCTAssertFalse(resumed.state.pausedByVoice)
    }

    func testModeAndAvailabilityChangesClearStaleEffects() {
        var state = started(.modeC)
        state = voice(state, local: true).state
        let modeE = reduce(state, .policySelected(generation: generation, policy: .modeE))
        XCTAssertEqual([.rampMusicVolume(targetPermille: 1_000, durationMs: 200)], modeE.actions)

        state = started(.modeD)
        state = voice(state, local: true).state
        let unavailable = voice(state, local: false, available: false)
        XCTAssertEqual([.resumeMusicAfterVoice(trackToken: track)], unavailable.actions)
        XCTAssertEqual(.voiceUnavailable, unavailable.state.fallback)
    }

    func testStalePredecessorCannotAlterSuccessor() {
        var state = started(.modeC)
        state = voice(state, local: true).state
        state = reduce(state, .lifetimeStarted(generation: generation + 1, policy: .modeC)).state
        let stale = reduce(
            state,
            .voiceChanged(generation: generation, available: true, localTransmitting: false, peerTransmitting: false)
        )
        XCTAssertTrue(stale.actions.isEmpty)
        XCTAssertEqual(generation + 1, stale.state.generation)
        XCTAssertEqual(1, stale.state.staleInputCount)
    }

    func testSuccessorReconcilesPredecessorModeDPauseBeforeRejectingStaleInput() {
        var state = started(.modeD)
        state = voice(state, local: true).state

        let successor = reduce(state, .lifetimeStarted(generation: generation + 1, policy: .modeC))

        XCTAssertEqual(successor.actions, [
            .resumeMusicAfterVoice(trackToken: track),
            .rampMusicVolume(targetPermille: 1_000, durationMs: 200),
        ])
        XCTAssertFalse(successor.state.pausedByVoice)
        XCTAssertFalse(successor.state.localTransmitting)
        XCTAssertFalse(successor.state.peerTransmitting)
    }

    func testTeardownRestoresDuckAndTemporaryPause() {
        var ducked = started(.modeC)
        ducked = voice(ducked, local: true).state
        XCTAssertEqual(
            [.rampMusicVolume(targetPermille: 1_000, durationMs: 200)],
            reduce(ducked, .lifetimeEnded(generation: generation)).actions
        )

        var paused = started(.modeD)
        paused = voice(paused, local: true).state
        paused = reduce(
            paused,
            .musicChanged(generation: generation, available: true, trackToken: track, playing: false, ended: false)
        ).state
        XCTAssertEqual(
            [.resumeMusicAfterVoice(trackToken: track)],
            reduce(paused, .lifetimeEnded(generation: generation)).actions
        )
    }

    private func started(_ policy: IntercomPolicy, baseVolumePermille: Int = 1_000) -> CoexistenceState {
        var state = CoexistenceState(
            baseVolumePermille: baseVolumePermille,
            targetVolumePermille: baseVolumePermille
        )
        state = reduce(state, .lifetimeStarted(generation: generation, policy: policy)).state
        return reduce(
            state,
            .musicChanged(generation: generation, available: true, trackToken: track, playing: true, ended: false)
        ).state
    }

    private func voice(
        _ state: CoexistenceState,
        local: Bool,
        available: Bool = true
    ) -> CoexistenceOutcome {
        reduce(
            state,
            .voiceChanged(
                generation: generation,
                available: available,
                localTransmitting: local,
                peerTransmitting: false
            )
        )
    }

    private func reduce(_ state: CoexistenceState, _ input: CoexistenceInput) -> CoexistenceOutcome {
        IntercomMusicCoexistence.reduce(state: state, input: input)
    }
}
