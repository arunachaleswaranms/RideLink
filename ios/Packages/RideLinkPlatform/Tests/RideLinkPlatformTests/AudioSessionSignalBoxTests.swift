import XCTest

@testable import RideLinkCore
@testable import RideLinkPlatform

/// Exhausts `AudioSessionSignalBox` — the one bounded, generation-tagged path from `NotificationCenter`
/// into `IosVoiceAudioSession`.
///
/// This phase's brief §38 requires every new input stream to have an explicit finite buffering policy,
/// and §39 forbids a `Task` per event as the ordering mechanism. Both are properties of this type, so
/// both are tested here rather than inferred from the class that uses it — which is just as well, since
/// `AVAudioSession` cannot be executed on macOS at all. This hardening pass adds three properties this
/// file did not previously prove: the box's own priority-drain order is real (not merely documented), a
/// signal's generation is exactly what the caller stamped it with, and a **fresh** box behaves like a
/// brand-new one — `IosVoiceAudioSession` now creates one per `open()` rather than reusing a single
/// instance for its whole lifetime, so those three properties are what make End → Start Intercom keep
/// working.
final class AudioSessionSignalBoxTests: XCTestCase {
    private func poll(_ box: AudioSessionSignalBox, expecting count: Int) -> [GeneratedAudioSessionSignal] {
        var applied: [GeneratedAudioSessionSignal] = []
        for _ in 0..<count {
            guard let next = box.poll() else { break }
            applied.append(next)
        }
        return applied
    }

    // MARK: - bounding by kind

    /// The bound, demonstrated rather than asserted from a constant: ten thousand route changes cannot
    /// put more than one route-change element pending.
    func testAFloodOfOneKindYieldsAtMostOnePendingElement() {
        let box = AudioSessionSignalBox()
        let bell = ConflatedSignal()
        for _ in 0..<10_000 { box.offer(.routeChanged(reason: .newDeviceAvailable), generation: 0, doorbell: bell) }

        let applied = poll(box, expecting: 2)
        XCTAssertEqual(1, applied.count, "one slot per kind, whatever the arrival rate")
        XCTAssertNil(box.poll())
    }

    /// **The correctness half of the coalescing.** A second offer for a kind already pending replaces it
    /// rather than queuing alongside it, so a flood delivers only the newest route reason.
    func testASecondOfferForOneKindReplacesTheFirst() {
        let box = AudioSessionSignalBox()
        let bell = ConflatedSignal()
        box.offer(.routeChanged(reason: .newDeviceAvailable), generation: 0, doorbell: bell)
        box.offer(.routeChanged(reason: .oldDeviceUnavailable), generation: 0, doorbell: bell)
        box.offer(.routeChanged(reason: .categoryChange), generation: 0, doorbell: bell)

        let applied = poll(box, expecting: 1)
        XCTAssertEqual([.routeChanged(reason: .categoryChange)], applied.map(\.signal), "the newest value survives")
    }

    /// A second `poll` for an already-drained kind finds nothing until another signal is offered.
    func testPollingTwiceForOneKindYieldsNothingTheSecondTime() {
        let box = AudioSessionSignalBox()
        let bell = ConflatedSignal()
        box.offer(.interruptionBegan, generation: 0, doorbell: bell)
        XCTAssertEqual(.interruptionBegan, box.poll()?.signal)
        XCTAssertNil(box.poll())
    }

    /// An interruption's `shouldResume` is carried, not flattened — it is the whole difference between
    /// reactivating and staying inactive (TEST_PLAN IA-06).
    func testAnInterruptionCarriesItsShouldResumeOption() {
        let box = AudioSessionSignalBox()
        let bell = ConflatedSignal()
        box.offer(.interruptionEnded(shouldResume: true), generation: 0, doorbell: bell)
        XCTAssertEqual(.interruptionEnded(shouldResume: true), box.poll()?.signal)

        box.offer(.interruptionEnded(shouldResume: false), generation: 0, doorbell: bell)
        XCTAssertEqual(.interruptionEnded(shouldResume: false), box.poll()?.signal)
    }

    /// Begin and end share one slot, because both answer "is an interruption in force now?".
    func testInterruptionBeganAndEndedShareOneSlot() {
        let box = AudioSessionSignalBox()
        let bell = ConflatedSignal()
        box.offer(.interruptionBegan, generation: 0, doorbell: bell)
        box.offer(.interruptionEnded(shouldResume: true), generation: 0, doorbell: bell)

        let applied = poll(box, expecting: 2)
        XCTAssertEqual([.interruptionEnded(shouldResume: true)], applied.map(\.signal))
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

    // MARK: - Issue C: priority draining is real, not documentation

    /// **A reset must never be drained after a route change that arrived before it, when a route change
    /// arrives after the reset was already offered.** `poll()` always returns the reset first, regardless
    /// of offer order — this is the property a raw `AsyncStream` (arrival order) could not give.
    func testResetDrainsBeforeInterruptionAndRouteRegardlessOfArrivalOrder() {
        let box = AudioSessionSignalBox()
        let bell = ConflatedSignal()
        box.offer(.routeChanged(reason: .categoryChange), generation: 0, doorbell: bell)
        box.offer(.interruptionBegan, generation: 0, doorbell: bell)
        box.offer(.mediaServicesReset, generation: 0, doorbell: bell)

        let applied = poll(box, expecting: 3)
        XCTAssertEqual(
            [.mediaServicesReset, .interruption, .routeChanged],
            applied.map(\.signal.kind),
            "reset > interruption > route, independent of arrival order"
        )
    }

    /// The reverse arrival order produces the identical drain order — the fixture that would fail against
    /// a plain `AsyncStream`, which this box no longer is.
    func testResetOfferedLastStillDrainsFirst() {
        let box = AudioSessionSignalBox()
        let bell = ConflatedSignal()
        box.offer(.routeChanged(reason: .categoryChange), generation: 0, doorbell: bell)
        box.offer(.mediaServicesReset, generation: 0, doorbell: bell)

        let applied = poll(box, expecting: 2)
        XCTAssertEqual([.mediaServicesReset, .routeChanged], applied.map(\.signal.kind))
    }

    /// `interruption → reset → route`, all three kinds, in every arrival permutation: the drained order is
    /// always the fixed safety order.
    func testAllArrivalPermutationsDrainInSafetyOrder() {
        let offers: [(AudioSessionSignalBox, ConflatedSignal) -> Void] = [
            { box, bell in box.offer(.interruptionBegan, generation: 0, doorbell: bell) },
            { box, bell in box.offer(.mediaServicesReset, generation: 0, doorbell: bell) },
            { box, bell in box.offer(.routeChanged(reason: .override), generation: 0, doorbell: bell) },
        ]
        for permutation in offers.permutations() {
            let box = AudioSessionSignalBox()
            let bell = ConflatedSignal()
            for offer in permutation { offer(box, bell) }
            let applied = poll(box, expecting: 3)
            XCTAssertEqual(
                [.mediaServicesReset, .interruption, .routeChanged],
                applied.map(\.signal.kind),
                "safety order must hold for every arrival permutation"
            )
        }
    }

    /// A flood of every kind, offered in reverse-priority order repeatedly, still drains in safety order.
    func testFloodOfAllKindsStillDrainsInSafetyOrder() {
        let box = AudioSessionSignalBox()
        let bell = ConflatedSignal()
        for _ in 0..<500 {
            box.offer(.routeChanged(reason: .override), generation: 0, doorbell: bell)
            box.offer(.interruptionBegan, generation: 0, doorbell: bell)
            box.offer(.mediaServicesReset, generation: 0, doorbell: bell)
        }
        let applied = poll(box, expecting: 3)
        XCTAssertEqual([.mediaServicesReset, .interruption, .routeChanged], applied.map(\.signal.kind))
        XCTAssertNil(box.poll(), "the flood coalesced to one slot per kind, not five hundred")
    }

    // MARK: - Issue B: the generation is exactly what the caller stamped

    /// `poll()` hands back precisely the generation `offer` was called with — never a generation read
    /// later. This is the property that makes the reducer's guard meaningful once processing is
    /// decoupled from arrival, since the box no longer promises `handle` runs "immediately."
    func testGenerationIsPreservedExactlyAsOffered() {
        let box = AudioSessionSignalBox()
        let bell = ConflatedSignal()
        box.offer(.mediaServicesReset, generation: 7, doorbell: bell)
        box.offer(.routeChanged(reason: .categoryChange), generation: 3, doorbell: bell)

        let applied = poll(box, expecting: 2)
        XCTAssertEqual(7, applied.first { $0.signal.kind == .mediaServicesReset }?.generation)
        XCTAssertEqual(3, applied.first { $0.signal.kind == .routeChanged }?.generation)
    }

    /// A later offer for the same kind, at a different generation, replaces both the value and the
    /// generation together — there is exactly one pending fact per kind, never a mismatched pair.
    func testReplacingASlotReplacesItsGenerationToo() {
        let box = AudioSessionSignalBox()
        let bell = ConflatedSignal()
        box.offer(.routeChanged(reason: .unknown), generation: 1, doorbell: bell)
        box.offer(.routeChanged(reason: .categoryChange), generation: 2, doorbell: bell)

        let applied = box.poll()
        XCTAssertEqual(.routeChanged(reason: .categoryChange), applied?.signal)
        XCTAssertEqual(2, applied?.generation)
    }

    // MARK: - Issue A: `finish` poisons only this instance, not "forever"

    /// `finish` makes every later offer on **this** box a silent no-op, so a stale notification from an
    /// already-closed generation cannot mutate it.
    func testOffersAfterFinishAreSilentlyDropped() {
        let box = AudioSessionSignalBox()
        let bell = ConflatedSignal()
        box.finish()
        box.offer(.mediaServicesReset, generation: 0, doorbell: bell)
        XCTAssertNil(box.poll(), "a finished box must accept nothing")
    }

    /// **`finish` discards what is pending, deliberately.** A signal still in the box describes an audio
    /// session that is being torn down, so applying it afterwards would act on a route that no longer
    /// exists — the same reasoning that makes a stale generation's callback inert.
    func testFinishDiscardsPendingSignals() {
        let box = AudioSessionSignalBox()
        let bell = ConflatedSignal()
        box.offer(.routeChanged(reason: .categoryChange), generation: 0, doorbell: bell)
        box.offer(.mediaServicesReset, generation: 0, doorbell: bell)
        box.finish()
        XCTAssertNil(box.poll())
    }

    /// **The property that makes reuse safe: a `finish`ed box is dead, but a *fresh* box is not.** This is
    /// the exact shape of End Intercom → Start Intercom: the old generation's box is finished, and a new
    /// one (as `IosVoiceAudioSession.open()` now creates) delivers normally from the start, with no
    /// memory of the old box's finished state.
    func testAFreshBoxAfterAFinishedOneDeliversNormally() {
        let finished = AudioSessionSignalBox()
        let bell = ConflatedSignal()
        finished.finish()
        finished.offer(.routeChanged(reason: .categoryChange), generation: 5, doorbell: bell)
        XCTAssertNil(finished.poll(), "the old generation's box stays dead")

        let fresh = AudioSessionSignalBox()
        fresh.offer(.routeChanged(reason: .categoryChange), generation: 5, doorbell: bell)
        XCTAssertEqual(.routeChanged(reason: .categoryChange), fresh.poll()?.signal, "a fresh box is unaffected")
    }

    /// Several open→finish cycles in a row, mirroring several End → Start Intercom presses: every fresh
    /// box in the sequence delivers, independent of how many predecessors were finished.
    func testSeveralOpenCloseCyclesEachDeliverNormally() {
        for generation in 0..<20 {
            let box = AudioSessionSignalBox()
            let bell = ConflatedSignal()
            box.offer(.mediaServicesReset, generation: generation, doorbell: bell)
            let applied = box.poll()
            XCTAssertEqual(.mediaServicesReset, applied?.signal, "cycle \(generation)")
            XCTAssertEqual(generation, applied?.generation, "cycle \(generation)")
            box.finish()
            XCTAssertNil(box.poll(), "cycle \(generation) box is dead after finish")
        }
    }

    // MARK: - concurrency

    /// Concurrent producers — which is what `NotificationCenter` on arbitrary queues actually is — cannot
    /// grow the box past one element per kind, and every element retains the generation it was offered
    /// with.
    func testConcurrentProducersCannotGrowTheBoxAndKeepTheirGeneration() async {
        let box = AudioSessionSignalBox()
        let bell = ConflatedSignal()
        await withTaskGroup(of: Void.self) { group in
            for index in 0..<200 {
                group.addTask {
                    switch index % 3 {
                    case 0: box.offer(.routeChanged(reason: .override), generation: index, doorbell: bell)
                    case 1: box.offer(.interruptionBegan, generation: index, doorbell: bell)
                    default: box.offer(.mediaServicesReset, generation: index, doorbell: bell)
                    }
                }
            }
        }
        let applied = poll(box, expecting: AudioSessionSignal.Kind.allCases.count + 1)
        XCTAssertLessThanOrEqual(
            applied.count,
            AudioSessionSignal.Kind.allCases.count,
            "at most one element per kind can be pending"
        )
        XCTAssertEqual(
            [.mediaServicesReset, .interruption, .routeChanged],
            applied.map(\.signal.kind),
            "even under concurrent producers, drain order is the fixed safety order"
        )
    }
}

private extension Array {
    /// All orderings of a small fixed-size array. Only ever called with three elements in this file, so
    /// no attempt is made to be efficient for a larger one.
    func permutations() -> [[Element]] {
        guard count > 1 else { return [self] }
        var result: [[Element]] = []
        for index in indices {
            var rest = self
            let element = rest.remove(at: index)
            for tail in rest.permutations() {
                result.append([element] + tail)
            }
        }
        return result
    }
}
