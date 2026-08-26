import Foundation
import XCTest
@testable import RideLinkCore

/// Regression tests with fabricated planted secrets (CLAUDE.md step 18): prove that redaction
/// happens structurally, not by remembering to call a function. Crypto isn't implemented yet, so
/// these plant fake-shaped values rather than real key material.
final class RedactionTests: XCTestCase {
    func testPeerIdIsRedactedToFirst6Chars() {
        let planted = "b7c1e0d9a4f28356"
        let redacted = Redactor.peerId(planted)
        XCTAssertTrue(redacted.hasPrefix("peer:b7c1e0"))
        XCTAssertFalse(redacted.contains(planted), "the full peer_id must never appear in a redacted log field")
    }

    func testSpkiHashIsRedactedToFirst6Hex() {
        let planted = "sha256:2488a4e8a6347f0ca5e9befd679f5fe0d293de2f2cc28caf98392dfdc98aea1a"
        let redacted = Redactor.spkiHash(planted)
        XCTAssertTrue(redacted.hasPrefix("spki:2488a4"))
        XCTAssertFalse(redacted.contains(String(planted.dropFirst("sha256:".count))), "the full SPKI hex must never appear")
    }

    func testConnTiebreakIsRedactedToFirst6Hex() {
        let planted = "5e2a9c40b7f13d86e0a4c95b28f7d613"
        let redacted = Redactor.connTiebreak(planted)
        XCTAssertTrue(redacted.hasPrefix("tiebreak:5e2a9c"))
        XCTAssertFalse(redacted.contains(planted))
    }

    func testPathsAreReducedToBasenameOnlyDroppingAnyPlantedUsernameSegment() {
        let plantedUsername = "totally-not-a-real-user-p4ssw0rd-lookalike"
        let fullPath = "/Users/\(plantedUsername)/Music/library/track.mp3"
        let redacted = Redactor.path(fullPath)
        XCTAssertEqual(redacted, "track.mp3")
        XCTAssertFalse(redacted.contains(plantedUsername))
    }

    func testEveryEventEmittedThroughStructuredLoggerIsCapturedAndInspectable() {
        let sink = InMemoryLogSink()
        let logger = StructuredLogger(sink: sink, monotonicNowUs: { 42 })
        logger.info("test", "hello")
        XCTAssertEqual(sink.events.count, 1)
        XCTAssertEqual(sink.events.first?.message, "hello")
        XCTAssertEqual(sink.events.first?.monotonicTimestampUs, 42)
    }
}
