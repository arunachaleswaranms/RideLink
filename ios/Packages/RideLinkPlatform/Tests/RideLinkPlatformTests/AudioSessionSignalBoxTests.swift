import XCTest

@testable import RideLinkCore
@testable import RideLinkPlatform

/// Exhausts `AudioSessionSignalBox` — the one ordered, bounded path from `NotificationCenter` into
/// `IosVoiceAudioSession`.
///
/// This phase's brief §38 requires every new input stream to have an explicit finite buffering policy,
/// and §39 forbids a `Task` per event as the ordering mechanism. Both are properties of this type, so
/// both are tested here rather than inferred from the class that uses it — which is just as well, since
/// `AVAudioSession` cannot be executed on macOS at all.
final class AudioSessionSignalBoxTests: XCTestCase {
    /// The bound, demonstrated rather than asserted from a constant: ten thousand route changes cannot
    /// put more than one route-change element in flight.
    func testAFloodOfOneKindYieldsAtMostOneElement() async {
        let box = AudioSessionSignalBox()
        for _ in 0..<10_000 { box.offer(.routeChanged(reason: .newDeviceAvailable)) }
        box.finish()

        var delivered: [AudioSessionSignal] = []
        for await signal in box.stream { delivered.append(signal) }
        XCTAssertEqual(1, delivered.count, "one slot per kind, whatever the arrival rate")
    }

    /// Draining the way the real consumer does: take the newest value for each delivered element's kind,
    /// **without** finishing first.
    ///
    /// `finish` deliberately clears the pending slots — they describe an audio session that no longer
    /// exists — so a test that finished before draining would be asserting against the teardown rather
    /// than against the coalescing. Iterating the stream directly is what the consumer actually does.
    private func drain(_ box: AudioSessionSignalBox, expecting count: Int) async -> [AudioSessionSignal] {
        var iterator = box.stream.makeAsyncIterator()
        var applied: [AudioSessionSignal] = []
        for _ in 0..<count {
            guard let delivered = await iterator.next() else { break }
            if let newest = box.take(delivered) { applied.append(newest) }
        }
        return applied
    }

    /// **The correctness half of the coalescing.** The element the stream hands over may have been
    /// superseded while it waited, so the consumer takes the newest value for that kind rather than
    /// trusting the delivery — otherwise a flood would deliver a stale route reason.
    func testTakeReturnsTheNewestValueForTheDeliveredKind() async {
        let box = AudioSessionSignalBox()
        box.offer(.routeChanged(reason: .newDeviceAvailable))
        box.offer(.routeChanged(reason: .oldDeviceUnavailable))
        box.offer(.routeChanged(reason: .categoryChange))

        let applied = await drain(box, expecting: 1)
        XCTAssertEqual([.routeChanged(reason: .categoryChange)], applied, "the newest value survives")
    }

    /// A second `take` for the same kind finds nothing: the slot is emptied, not merely read.
    func testTakingTwiceForOneKindYieldsNothingTheSecondTime() {
        let box = AudioSessionSignalBox()
        box.offer(.interruptionBegan)
        XCTAssertEqual(.interruptionBegan, box.take(.interruptionBegan))
        XCTAssertNil(box.take(.interruptionBegan))
    }

    /// **A media-services reset has its own slot and can never be coalesced away.** It is the one signal
    /// whose loss would leave the app holding invalid audio objects and believing they were fine.
    func testAResetIsNeverCoalescedAwayByARouteChange() async {
        let box = AudioSessionSignalBox()
        box.offer(.mediaServicesReset)
        box.offer(.routeChanged(reason: .categoryChange))
        box.offer(.routeChanged(reason: .override))

        let applied = await drain(box, expecting: 2)
        XCTAssertTrue(applied.contains(.mediaServicesReset), "the reset must survive")
        XCTAssertEqual(2, applied.count, "one reset and one (newest) route change")
    }

    /// An interruption's `shouldResume` is carried, not flattened — it is the whole difference between
    /// reactivating and staying inactive (TEST_PLAN IA-06).
    func testAnInterruptionCarriesItsShouldResumeOption() {
        let box = AudioSessionSignalBox()
        box.offer(.interruptionEnded(shouldResume: true))
        XCTAssertEqual(.interruptionEnded(shouldResume: true), box.take(.interruptionBegan))

        box.offer(.interruptionEnded(shouldResume: false))
        XCTAssertEqual(.interruptionEnded(shouldResume: false), box.take(.interruptionBegan))
    }

    /// Begin and end share one slot, because both answer "is an interruption in force now?".
    func testInterruptionBeganAndEndedShareOneSlot() async {
        let box = AudioSessionSignalBox()
        box.offer(.interruptionBegan)
        box.offer(.interruptionEnded(shouldResume: true))

        let applied = await drain(box, expecting: 1)
        XCTAssertEqual([.interruptionEnded(shouldResume: true)], applied)
    }

    /// `finish` makes every later offer a silent no-op, so a stale notification from an already-closed
    /// session cannot restart a finished consumer.
    func testOffersAfterFinishAreSilentlyDropped() async {
        let box = AudioSessionSignalBox()
        box.finish()
        box.offer(.mediaServicesReset)

        var delivered: [AudioSessionSignal] = []
        for await signal in box.stream { delivered.append(signal) }
        XCTAssertTrue(delivered.isEmpty, "a finished box must accept nothing")
        XCTAssertNil(box.take(.mediaServicesReset))
    }

    /// **`finish` discards what is pending, deliberately.** A signal still in the box describes an audio
    /// session that is being torn down, so applying it afterwards would act on a route that no longer
    /// exists — the same reasoning that makes a stale generation's callback inert.
    func testFinishDiscardsPendingSignals() {
        let box = AudioSessionSignalBox()
        box.offer(.routeChanged(reason: .categoryChange))
        box.offer(.mediaServicesReset)
        box.finish()
        XCTAssertNil(box.take(.routeChanged(reason: .categoryChange)))
        XCTAssertNil(box.take(.mediaServicesReset))
    }

    /// Every signal is classified, so none can escape the bound by being unslotted.
    func testEverySignalKindIsReachable() {
        let signals: [(AudioSessionSignal, AudioSessionSignal.Kind)] = [
            (.routeChanged(reason: .unknown), .routeChanged),
            (.interruptionBegan, .interruption),
            (.interruptionEnded(shouldResume: true), .interruption),
            (.mediaServicesReset, .mediaServicesReset),
        ]
        for (signal, kind) in signals {
            XCTAssertEqual(kind, signal.kind, "\(signal) classification")
        }
        XCTAssertEqual(
            Set(AudioSessionSignal.Kind.allCases),
            Set(signals.map(\.1)),
            "every kind must be reachable from some signal"
        )
    }

    /// Concurrent producers — which is what `NotificationCenter` on arbitrary queues actually is — cannot
    /// grow the box past one element per kind.
    func testConcurrentProducersCannotGrowTheBox() async {
        let box = AudioSessionSignalBox()
        await withTaskGroup(of: Void.self) { group in
            for index in 0..<200 {
                group.addTask {
                    switch index % 3 {
                    case 0: box.offer(.routeChanged(reason: .override))
                    case 1: box.offer(.interruptionBegan)
                    default: box.offer(.mediaServicesReset)
                    }
                }
            }
        }
        box.finish()

        var delivered = 0
        for await _ in box.stream { delivered += 1 }
        XCTAssertLessThanOrEqual(
            delivered,
            AudioSessionSignal.Kind.allCases.count,
            "at most one element per kind can be in flight"
        )
    }
}
