import Foundation
import RideLinkCore
import XCTest

@testable import RideLinkPlatform

/// `ResyncRelay`-level provenance: PROTOCOL §10's `STATE_REQUEST`/`STATE_SNAPSHOT`, mirroring
/// `ManifestRelay`/`AudioStateRelay`/`VoiceSignalRelay`'s already-audited shape exactly.
///
/// **The single most important property in this file** (this phase's brief §13): a frame read while
/// generation A was live but delivered to `deliver` after B has authenticated must never reach the
/// sink — this is the exact bug class ADR-024/ADR-025 closed repeatedly for every other message
/// family, checked here for the family Phase 7 adds.
final class ResyncRelayTests: XCTestCase {
    private final class CapturedSink: ResyncSink, @unchecked Sendable {
        private(set) var received: [(ResyncMessage, Int64)] = []

        func submit(_ message: ResyncMessage, generation: Int64) {
            received.append((message, generation))
        }
    }

    private func makeRelay(
        live: @escaping @Sendable () -> Int64?,
        writer: @escaping @Sendable () async -> AuthenticatedFrameWriter?
    ) -> ResyncRelay {
        ResyncRelay(
            localPeerId: SyncTestValues.leaderPeerId,
            monotonicNowUs: { 0 },
            nextSeq: { 1 },
            activeSessionId: { SessionId("session") },
            authenticatedWriter: writer,
            liveGeneration: live
        )
    }

    func testADeliveredFrameWhoseGenerationMatchesTheLiveOneReachesTheSink() async {
        let relay = makeRelay(live: { 5 }, writer: { nil })
        let sink = CapturedSink()
        await relay.setSink(sink)
        await relay.deliver(type: ResyncMessageTypes.stateRequest, payload: [:], generation: 5)
        XCTAssertEqual(1, sink.received.count)
        XCTAssertEqual(5, sink.received.first?.1)
    }

    /// The regression this file exists for: a `STATE_SNAPSHOT` frame read under a predecessor
    /// generation (A) that reaches `deliver` after a successor (B) is live must be refused before
    /// the sink is ever called — never applied as though it were B's.
    func testAFrameDeliveredAfterASuccessorGenerationIsLiveIsRefusedAndNeverReachesTheSink() async {
        let relay = makeRelay(live: { 7 }, writer: { nil })
        let sink = CapturedSink()
        await relay.setSink(sink)
        await relay.deliver(type: ResyncMessageTypes.stateRequest, payload: [:], generation: 5)
        XCTAssertEqual(0, sink.received.count, "a retired-generation frame must never reach the sink")
        let dropped = await relay.droppedRetiredGeneration()
        XCTAssertEqual(1, dropped)
    }

    /// The mirror case: once B is live, B's own frames still reach the sink — a fix for the A/B
    /// problem must not also refuse the successor.
    func testAFrameDeliveredForTheCurrentlyLiveGenerationStillReachesTheSinkAfterAPriorRefusal() async {
        let relay = makeRelay(live: { 7 }, writer: { nil })
        let sink = CapturedSink()
        await relay.setSink(sink)
        await relay.deliver(type: ResyncMessageTypes.stateRequest, payload: [:], generation: 5)
        await relay.deliver(type: ResyncMessageTypes.stateRequest, payload: [:], generation: 7)
        XCTAssertEqual(1, sink.received.count)
        XCTAssertEqual(7, sink.received.first?.1)
    }

    func testANoLiveSessionMatchesNoFrame() async {
        let relay = makeRelay(live: { nil }, writer: { nil })
        let sink = CapturedSink()
        await relay.setSink(sink)
        await relay.deliver(type: ResyncMessageTypes.stateRequest, payload: [:], generation: 1)
        XCTAssertEqual(0, sink.received.count)
    }

    func testAMalformedPayloadIsCountedAndNeverReachesTheSink() async {
        let relay = makeRelay(live: { 1 }, writer: { nil })
        let sink = CapturedSink()
        await relay.setSink(sink)
        await relay.deliver(type: ResyncMessageTypes.stateSnapshot, payload: [:], generation: 1)
        XCTAssertEqual(0, sink.received.count)
        let rejections = await relay.rejectionCounts()
        XCTAssertEqual(1, rejections[.missingField])
    }

    func testSendReturnsFalseWithNoAuthenticatedWriter() async {
        let relay = makeRelay(live: { 1 }, writer: { nil })
        let sent = await relay.send(.stateRequest)
        XCTAssertFalse(sent)
    }

    func testSendWritesThroughTheAuthenticatedWriterWhenOneExists() async {
        final class WriteLog: @unchecked Sendable {
            private(set) var writes: [Envelope] = []
            func record(_ envelope: Envelope) { writes.append(envelope) }
        }
        let log = WriteLog()
        let relay = makeRelay(live: { 1 }) {
            { envelope in
                log.record(envelope)
                return true
            }
        }
        let sent = await relay.send(.stateRequest)
        XCTAssertTrue(sent)
        XCTAssertEqual(1, log.writes.count)
        XCTAssertEqual(ResyncMessageTypes.stateRequest, log.writes.first?.type)
    }

    func testPreAuthenticationDropsAreCountedSeparatelyFromRetiredGenerationDrops() async {
        let relay = makeRelay(live: { nil }, writer: { nil })
        await relay.countPreAuthenticationDrop()
        await relay.countPreAuthenticationDrop()
        let preAuth = await relay.droppedPreAuthentication()
        let retired = await relay.droppedRetiredGeneration()
        XCTAssertEqual(2, preAuth)
        XCTAssertEqual(0, retired)
    }

    func testResetCountersClearsCountersButNeverTheSink() async {
        let relay = makeRelay(live: { 9 }, writer: { nil })
        let sink = CapturedSink()
        await relay.setSink(sink)
        await relay.deliver(type: ResyncMessageTypes.stateRequest, payload: [:], generation: 1) // retired
        await relay.countPreAuthenticationDrop()
        await relay.resetCounters()
        let retired = await relay.droppedRetiredGeneration()
        let preAuth = await relay.droppedPreAuthentication()
        XCTAssertEqual(0, retired)
        XCTAssertEqual(0, preAuth)
        await relay.deliver(type: ResyncMessageTypes.stateRequest, payload: [:], generation: 9)
        XCTAssertEqual(1, sink.received.count, "resetCounters must not detach the sink")
    }
}
