import Foundation
import XCTest
@testable import RideLinkCore

/// `VoiceEngineGeneration` exhausted directly, because neither real `VoiceEngine` can be constructed
/// in a host unit test to exercise this rule end to end (`WebRtcVoiceEngine` needs the Apple audio
/// stack; its Android mirror needs an Android `Context`) — see both classes' own
/// "REAL-DEVICE AUDIO GATE PENDING" notes. This is the pure logic those two `emit` methods now
/// delegate to, so the rule itself is proven here even though the wiring around it is not.
final class VoiceEngineGenerationTests: XCTestCase {
    private let genA = VoiceSessionId("11111111111111111111111111111111")
    private let genB = VoiceSessionId("22222222222222222222222222222222")

    func testACallbackNamingTheActiveGenerationIsAccepted() {
        XCTAssertTrue(VoiceEngineGeneration.accepts(active: genA, expected: genA))
    }

    func testACallbackNamingADifferentGenerationThanTheActiveOneIsRejected() {
        XCTAssertFalse(VoiceEngineGeneration.accepts(active: genA, expected: genB))
    }

    /// The exact bug this replaces: `if let generation, generation != expected` skips the check
    /// (accepts) the moment `generation` is `nil` — which is precisely the torn-down state. A stopped
    /// engine's `active` is `nil`, and every callback from its closed peer connection must be inert
    /// then, not just the ones naming a still-remembered id.
    func testACallbackArrivingAfterTheEngineHasBeenStoppedIsRejectedNotAcceptedByDefault() {
        XCTAssertFalse(VoiceEngineGeneration.accepts(active: nil, expected: genA))
    }

    func testALateCallbackFromGenerationNCannotAffectGenerationNPlusOne() {
        // The engine has moved on to genB; a delegate call from the genA peer connection, queued or
        // delayed by the platform, arrives afterward.
        XCTAssertFalse(VoiceEngineGeneration.accepts(active: genB, expected: genA))
    }
}
