import XCTest

@testable import RideLinkCore

/// Runs the same Phase 6 coexistence scenarios as Android's `CoexistenceVectorTest`.
final class CoexistenceVectorTests: XCTestCase {
    private let generation: Int64 = 1
    private let track = "track-a"

    func testBothPlatformsImplementTheSharedCoexistenceTable() throws {
        // swiftlint:disable:next force_cast
        let document = try Vectors.loadJSON("coexistence/coexistence_vectors.json") as! [String: Any]
        var checked = 0
        for element in document.array("scenarios") {
            // swiftlint:disable:next force_cast
            let scenario = element as! [String: Any]
            let name = scenario.str("name")
            let base = scenario.int("base_volume_permille")
            var state = CoexistenceState(baseVolumePermille: base, targetVolumePermille: base)
            state = IntercomMusicCoexistence.reduce(
                state: state,
                input: .lifetimeStarted(generation: generation, policy: policy(scenario.str("policy_id")))
            ).state
            state = IntercomMusicCoexistence.reduce(
                state: state,
                input: .musicChanged(generation: generation, available: true, trackToken: track, playing: true, ended: false)
            ).state

            var actions: [String] = []
            for event in scenario.array("events") {
                // swiftlint:disable:next force_cast
                let spec = event as! [String: Any]
                let outcome = IntercomMusicCoexistence.reduce(
                    state: state,
                    input: input(spec, currentGeneration: state.generation)
                )
                state = outcome.state
                actions.append(contentsOf: outcome.actions.map(label))
            }

            XCTAssertEqual(scenario.array("expect_actions") as? [String], actions, "vector \(name) actions")
            XCTAssertEqual(scenario.int("expect_target_permille"), state.targetVolumePermille, "vector \(name) target")
            XCTAssertEqual(scenario.int("expect_base_permille"), state.baseVolumePermille, "vector \(name) base")
            XCTAssertEqual(fallback(scenario.str("expect_fallback")), state.fallback, "vector \(name) fallback")
            XCTAssertEqual(scenario.int("expect_stale_count"), state.staleInputCount, "vector \(name) stale count")
            checked += 1
        }
        XCTAssertGreaterThanOrEqual(checked, 21, "shared coverage unexpectedly shrank")
        XCTAssertEqual(CoexistenceAction.rampDurationMs, document.int64("ramp_duration_ms"))
    }

    private func input(_ spec: [String: Any], currentGeneration: Int64) -> CoexistenceInput {
        let owner = spec.int64Opt("generation") ?? currentGeneration
        switch spec.str("kind") {
        case "LifetimeStarted":
            return .lifetimeStarted(generation: owner, policy: policy(spec.str("policy_id")))
        case "LifetimeEnded":
            return .lifetimeEnded(generation: owner)
        case "PolicySelected":
            return .policySelected(generation: owner, policy: policy(spec.str("policy_id")))
        case "VoiceChanged":
            return .voiceChanged(
                generation: owner,
                available: spec.boolVal("available"),
                localTransmitting: spec.boolVal("local_transmitting"),
                peerTransmitting: spec.boolVal("peer_transmitting")
            )
        case "MusicChanged":
            return .musicChanged(
                generation: owner,
                available: spec.boolVal("available"),
                trackToken: spec.strOpt("track_token"),
                playing: spec.boolVal("playing"),
                ended: spec.boolVal("ended")
            )
        case "UserPlaybackIntent":
            return .userPlaybackIntent(generation: owner, playing: spec.boolVal("playing"))
        case "RouteChanged":
            return .routeChanged(
                generation: owner,
                routeState: spec.str("route_state") == "TRANSITIONING" ? .transitioning : .stable,
                interrupted: spec.boolVal("interrupted"),
                transitionTimedOut: spec.boolVal("transition_timed_out")
            )
        case "SyncAvailabilityChanged":
            return .syncAvailabilityChanged(generation: owner, available: spec.boolVal("available"))
        default:
            preconditionFailure("unknown coexistence vector input \(spec.str("kind"))")
        }
    }

    private func policy(_ raw: String) -> IntercomPolicy {
        IntercomPolicy.byId(IntercomModeId(rawValue: raw)!)!
    }

    private func fallback(_ raw: String) -> CoexistenceFallback {
        switch raw {
        case "NONE": return .none
        case "VOICE_UNAVAILABLE": return .voiceUnavailable
        case "MUSIC_UNAVAILABLE": return .musicUnavailable
        case "ROUTE_TRANSITION_TIMEOUT": return .routeTransitionTimeout
        case "INTERRUPTED": return .interrupted
        case "SYNC_UNAVAILABLE": return .syncUnavailable
        default: preconditionFailure("unknown fallback \(raw)")
        }
    }

    private func label(_ action: CoexistenceAction) -> String {
        switch action {
        case .rampMusicVolume(let target, let duration): return "Ramp(\(target),\(duration))"
        case .pauseMusicForVoice(let track): return "Pause(\(track))"
        case .resumeMusicAfterVoice(let track): return "Resume(\(track))"
        }
    }
}
