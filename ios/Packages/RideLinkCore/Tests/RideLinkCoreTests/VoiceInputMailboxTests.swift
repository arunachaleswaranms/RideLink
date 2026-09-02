import Foundation
import XCTest
@testable import RideLinkCore

/// `VoiceInputMailbox` exhausted with no actor, no controller, no WebRTC -- the mailbox policy itself
/// is what is under test here. Behavioural proof that flooding it cannot grow `VoiceController`'s
/// memory without bound lives in `RideLinkPlatformTests.VoiceControllerMailboxTests`; this file is the
/// pure classification-and-capacity logic those tests rely on. Mirror of
/// `com.ridelink.core.voice.VoiceInputMailboxTest` on Android.
final class VoiceInputMailboxTests: XCTestCase {
    private let genA = VoiceSessionId("11111111111111111111111111111111")
    private let genB = VoiceSessionId("22222222222222222222222222222222")
    private let sdp = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\n"
    private let candidate = "candidate:1 1 udp 1 192.0.2.11 51234 typ host"

    // MARK: - lanes are classified correctly

    func testStopAndControlLinkLostLandInTheAlwaysAcceptingTeardownLane() {
        var mailbox = VoiceInputMailbox()
        XCTAssertEqual(mailbox.offer(.stopRequested), .accepted(lane: .teardown))
        XCTAssertEqual(mailbox.offer(.controlLinkLost), .accepted(lane: .teardown))
    }

    func testStartEngineOfferAnswerCallbacksConnectivityAndPeerOfferAnswerAreCritical() {
        var mailbox = VoiceInputMailbox()
        XCTAssertEqual(mailbox.offer(.startRequested(freshVoiceSessionId: genA)), .accepted(lane: .critical))
        XCTAssertEqual(mailbox.offer(.localOfferCreated(voiceSessionId: genA, sdp: sdp)), .accepted(lane: .critical))
        XCTAssertEqual(mailbox.offer(.localAnswerCreated(voiceSessionId: genA, sdp: sdp)), .accepted(lane: .critical))
        XCTAssertEqual(
            mailbox.offer(.mediaConnectivityChanged(voiceSessionId: genA, connected: true, failed: false)),
            .accepted(lane: .critical)
        )
        XCTAssertEqual(
            mailbox.offer(.signalReceived(signal: .offer(voiceSessionId: genA, sdp: sdp), freshVoiceSessionId: genA)),
            .accepted(lane: .critical)
        )
        XCTAssertEqual(
            mailbox.offer(.signalReceived(signal: .answer(voiceSessionId: genA, sdp: sdp), freshVoiceSessionId: genA)),
            .accepted(lane: .critical)
        )
    }

    func testCandidateShapedInputsAreTheBoundedIceLane() {
        var mailbox = VoiceInputMailbox()
        XCTAssertEqual(
            mailbox.offer(.localCandidateGathered(voiceSessionId: genA, candidate: candidate, sdpMid: nil, sdpMlineIndex: 0)),
            .accepted(lane: .ice)
        )
        let iceSignal = VoiceSignal.iceCandidate(voiceSessionId: genA, candidate: candidate, sdpMid: nil, sdpMlineIndex: 0)
        XCTAssertEqual(
            mailbox.offer(.signalReceived(signal: iceSignal, freshVoiceSessionId: genA)),
            .accepted(lane: .ice)
        )
    }

    func testMuteRemoteTrackAndPeerStateUpdatesAreCoalesced() {
        var mailbox = VoiceInputMailbox()
        XCTAssertEqual(mailbox.offer(.muteRequested(muted: true)), .accepted(lane: .coalesced))
        XCTAssertEqual(
            mailbox.offer(.remoteTrackChanged(voiceSessionId: genA, present: true)),
            .accepted(lane: .coalesced)
        )
        let stateSignal = VoiceSignal.state(voiceSessionId: genA, state: .active, micMuted: false, mode: .continuous)
        XCTAssertEqual(
            mailbox.offer(.signalReceived(signal: stateSignal, freshVoiceSessionId: genA)),
            .accepted(lane: .coalesced)
        )
    }

    // MARK: - priority: teardown > critical > ice > coalesced

    func testPollDrainsTeardownBeforeAnythingElseRegardlessOfArrivalOrder() {
        var mailbox = VoiceInputMailbox()
        _ = mailbox.offer(.startRequested(freshVoiceSessionId: genA))
        _ = mailbox.offer(.localCandidateGathered(voiceSessionId: genA, candidate: candidate, sdpMid: nil, sdpMlineIndex: 0))
        _ = mailbox.offer(.muteRequested(muted: true))
        _ = mailbox.offer(.controlLinkLost)

        guard case .controlLinkLost? = mailbox.poll() else { return XCTFail("expected controlLinkLost first") }
        guard case .startRequested? = mailbox.poll() else { return XCTFail("expected startRequested next") }
        guard case .localCandidateGathered? = mailbox.poll() else { return XCTFail("expected the ICE item next") }
        guard case .muteRequested? = mailbox.poll() else { return XCTFail("expected the coalesced mute last") }
        XCTAssertNil(mailbox.poll())
    }

    func testOnlyTheLatestTeardownRequestSurvivesButItIsNeverLost() {
        var mailbox = VoiceInputMailbox()
        _ = mailbox.offer(.controlLinkLost)
        _ = mailbox.offer(.stopRequested)

        guard case .stopRequested? = mailbox.poll() else { return XCTFail("expected the latest teardown request") }
        XCTAssertNil(mailbox.poll())
    }

    func testCriticalInputsAreFifoWithinTheirOwnLane() {
        var mailbox = VoiceInputMailbox()
        _ = mailbox.offer(.localOfferCreated(voiceSessionId: genA, sdp: "first"))
        _ = mailbox.offer(.localAnswerCreated(voiceSessionId: genA, sdp: "second"))

        guard case .localOfferCreated(_, let firstSdp)? = mailbox.poll(), firstSdp == "first" else {
            return XCTFail("expected the offer first")
        }
        guard case .localAnswerCreated(_, let secondSdp)? = mailbox.poll(), secondSdp == "second" else {
            return XCTFail("expected the answer second")
        }
    }

    // MARK: - bounded critical lane, and the forced degrade it implies

    func testFloodingTheCriticalLanePastCapacityRefusesNewEntriesAndCountsAnOverflow() {
        var mailbox = VoiceInputMailbox(criticalCapacity: 4, iceCapacity: VoiceBounds.maxQueuedCandidates)
        for _ in 0..<4 {
            XCTAssertEqual(mailbox.offer(.startRequested(freshVoiceSessionId: genA)), .accepted(lane: .critical))
        }
        XCTAssertEqual(mailbox.offer(.startRequested(freshVoiceSessionId: genA)), .criticalOverflow)
        XCTAssertEqual(mailbox.overflowCount, 1)

        var drained = 0
        while mailbox.poll() != nil { drained += 1 }
        XCTAssertEqual(drained, 4, "the overflowing input must not have been silently accepted anyway")
    }

    func testAnAuthenticatedFloodOfOffersCannotGrowTheMailboxPastTheCriticalBound() {
        var mailbox = VoiceInputMailbox(criticalCapacity: 32, iceCapacity: VoiceBounds.maxQueuedCandidates)
        var overflowed = 0
        for _ in 0..<10_000 {
            let signal = VoiceSignal.offer(voiceSessionId: genA, sdp: sdp)
            if mailbox.offer(.signalReceived(signal: signal, freshVoiceSessionId: genA)) == .criticalOverflow {
                overflowed += 1
            }
        }
        XCTAssertGreaterThan(overflowed, 0, "10,000 offers must eventually overflow a 32-deep lane")
        XCTAssertEqual(mailbox.count, 32, "size must never exceed the configured capacity")
        XCTAssertEqual(overflowed, mailbox.overflowCount)
    }

    // MARK: - bounded ICE lane, oldest evicted, matching PendingCandidates' own policy

    func testFloodingTheIceLaneEvictsTheOldestCandidateAndCountsItNeverGrowingPastCapacity() {
        var mailbox = VoiceInputMailbox(criticalCapacity: VoiceInputMailbox.criticalCapacity, iceCapacity: 3)
        for i in 0..<3 {
            _ = mailbox.offer(.localCandidateGathered(voiceSessionId: genA, candidate: "c\(i)", sdpMid: nil, sdpMlineIndex: 0))
        }
        let outcome = mailbox.offer(
            .localCandidateGathered(voiceSessionId: genA, candidate: "c3", sdpMid: nil, sdpMlineIndex: 0)
        )
        XCTAssertEqual(outcome, .iceEvicted)
        XCTAssertEqual(mailbox.overflowCount, 1)

        var remaining: [String] = []
        while case .localCandidateGathered(_, let c, _, _)? = mailbox.poll() { remaining.append(c) }
        XCTAssertEqual(remaining, ["c1", "c2", "c3"], "the oldest candidate is discarded so the newest survive")
    }

    func testTheIceLanesDefaultCapacityIsTheSameProtocolBoundPendingCandidatesEnforces() {
        var mailbox = VoiceInputMailbox()
        for i in 0..<(VoiceBounds.maxQueuedCandidates + 10) {
            _ = mailbox.offer(.localCandidateGathered(voiceSessionId: genA, candidate: "c\(i)", sdpMid: nil, sdpMlineIndex: 0))
        }
        var drained = 0
        while mailbox.poll() != nil { drained += 1 }
        XCTAssertEqual(drained, VoiceBounds.maxQueuedCandidates)
    }

    func testFloodingIceCannotStarveOrEvictACriticalOffer() {
        var mailbox = VoiceInputMailbox(criticalCapacity: VoiceInputMailbox.criticalCapacity, iceCapacity: 4)
        _ = mailbox.offer(.signalReceived(signal: .offer(voiceSessionId: genA, sdp: sdp), freshVoiceSessionId: genA))
        for i in 0..<1_000 {
            _ = mailbox.offer(.localCandidateGathered(voiceSessionId: genA, candidate: "c\(i)", sdpMid: nil, sdpMlineIndex: 0))
        }
        guard case .signalReceived(let signal, _)? = mailbox.poll(), case .offer = signal else {
            return XCTFail("expected the offer to be drained first")
        }
    }

    // MARK: - coalescing: latest wins, nothing unbounded accumulates

    func testRepeatedPeerStateUpdatesConvergeToOnlyTheNewestValue() {
        var mailbox = VoiceInputMailbox()
        for i in 0..<500 {
            let wire: VoiceWireState = i % 2 == 0 ? .active : .connecting
            let signal = VoiceSignal.state(voiceSessionId: genA, state: wire, micMuted: false, mode: .continuous)
            _ = mailbox.offer(.signalReceived(signal: signal, freshVoiceSessionId: genA))
        }
        guard case .signalReceived(let signal, _)? = mailbox.poll(), case .state(_, let wire, _, _) = signal else {
            return XCTFail("expected the coalesced peer-state entry")
        }
        XCTAssertEqual(wire, .connecting, "the 500th (index 499, odd) update is the newest")
        XCTAssertNil(mailbox.poll(), "only one coalesced entry was ever held")
    }

    func testMuteRemoteTrackAndPeerStateEachCoalesceIndependently() {
        var mailbox = VoiceInputMailbox()
        _ = mailbox.offer(.muteRequested(muted: true))
        _ = mailbox.offer(.remoteTrackChanged(voiceSessionId: genA, present: true))
        let stateSignal = VoiceSignal.state(voiceSessionId: genA, state: .active, micMuted: false, mode: .continuous)
        _ = mailbox.offer(.signalReceived(signal: stateSignal, freshVoiceSessionId: genA))
        _ = mailbox.offer(.muteRequested(muted: false))

        var drained: [VoiceInput] = []
        while let next = mailbox.poll() { drained.append(next) }
        XCTAssertEqual(drained.count, 3, "three distinct coalesced kinds, not one shared slot")
        XCTAssertTrue(drained.contains { if case .muteRequested(let m) = $0 { return m == false } else { return false } })
        XCTAssertTrue(drained.contains { if case .remoteTrackChanged = $0 { return true } else { return false } })
        XCTAssertTrue(drained.contains { if case .signalReceived = $0 { return true } else { return false } })
    }

    func testCoalescingNeverOverflowsRegardlessOfVolume() {
        var mailbox = VoiceInputMailbox()
        for i in 0..<50_000 {
            _ = mailbox.offer(.muteRequested(muted: i % 2 == 0))
        }
        XCTAssertEqual(mailbox.count, 1)
        XCTAssertEqual(mailbox.overflowCount, 0)
    }

    // MARK: - teardown, restart, clear

    func testStopRemainsOfferableAndDrainableEvenWhileEveryOtherLaneIsCompletelyFull() {
        var mailbox = VoiceInputMailbox(criticalCapacity: 2, iceCapacity: 2)
        for _ in 0..<2 { _ = mailbox.offer(.startRequested(freshVoiceSessionId: genA)) }
        for i in 0..<2 {
            _ = mailbox.offer(.localCandidateGathered(voiceSessionId: genA, candidate: "c\(i)", sdpMid: nil, sdpMlineIndex: 0))
        }
        _ = mailbox.offer(.muteRequested(muted: true))

        XCTAssertEqual(mailbox.offer(.stopRequested), .accepted(lane: .teardown))
        guard case .stopRequested? = mailbox.poll() else {
            return XCTFail("stop must be drained first no matter how full the rest is")
        }
    }

    func testClearEmptiesEveryLaneAndAFreshSessionOffersCleanlyAfterward() {
        var mailbox = VoiceInputMailbox()
        _ = mailbox.offer(.startRequested(freshVoiceSessionId: genA))
        _ = mailbox.offer(.localCandidateGathered(voiceSessionId: genA, candidate: candidate, sdpMid: nil, sdpMlineIndex: 0))
        _ = mailbox.offer(.muteRequested(muted: true))
        _ = mailbox.offer(.controlLinkLost)

        mailbox.clear()

        XCTAssertTrue(mailbox.isEmpty)
        XCTAssertNil(mailbox.poll())
        XCTAssertEqual(mailbox.offer(.startRequested(freshVoiceSessionId: genB)), .accepted(lane: .critical))
    }

    func testIsEmptyAndCountAgreeWithWhatHasActuallyBeenDrained() {
        var mailbox = VoiceInputMailbox()
        XCTAssertTrue(mailbox.isEmpty)
        XCTAssertEqual(mailbox.count, 0)

        _ = mailbox.offer(.startRequested(freshVoiceSessionId: genA))
        XCTAssertFalse(mailbox.isEmpty)
        XCTAssertEqual(mailbox.count, 1)

        _ = mailbox.poll()
        XCTAssertTrue(mailbox.isEmpty)
        XCTAssertEqual(mailbox.count, 0)
    }
}
