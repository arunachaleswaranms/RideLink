import XCTest

@testable import RideLinkCore

/// Exhausts `IntercomCommandMailbox` in isolation — no controller, no actor.
///
/// This phase's brief §38 requires every new input stream to have an explicit finite buffering policy.
/// This mailbox's is "one slot per kind", so the tests below are about proving the bound is structural
/// rather than a capacity check that could be raised later. The mirror is
/// `com.ridelink.core.audiopolicy.IntercomCommandMailboxTest`.
final class IntercomCommandMailboxTests: XCTestCase {
    private let flood = 10_000
    private let factorial5 = 120

    func testAnEmptyMailboxPollsToNil() {
        var mailbox = IntercomCommandMailbox()
        XCTAssertTrue(mailbox.isEmpty)
        XCTAssertNil(mailbox.poll())
        XCTAssertEqual(0, mailbox.count)
    }

    /// The bound, demonstrated rather than asserted from the constant: ten thousand PTT edges cannot
    /// make the mailbox hold more than one of them.
    func testTenThousandPttEdgesHoldExactlyOneSlot() {
        var mailbox = IntercomCommandMailbox()
        for index in 0..<flood { mailbox.offer(.pttHeld(index % 2 == 0)) }
        XCTAssertEqual(1, mailbox.count, "one slot per kind, whatever the arrival rate")
        XCTAssertEqual(IntercomInput.pttHeld(flood % 2 != 0), mailbox.poll(), "the newest value survives")
        XCTAssertTrue(mailbox.isEmpty)
    }

    /// Every kind at once is the whole capacity, and there is no seventh slot to overflow into.
    func testOneOfEveryKindFillsTheMailboxToItsStructuralCapacity() {
        var mailbox = IntercomCommandMailbox()
        let everyKind: [IntercomInput] = [
            .policySelected(.modeA),
            .captureOpen(true),
            .interrupted(false),
            .userMuted(false),
            .pttHeld(true),
            .speechLevel(levelDbfs: -10.0, atMonoUs: 1_000),
        ]
        for input in everyKind { XCTAssertEqual(.accepted, mailbox.offer(input)) }
        XCTAssertEqual(IntercomCommandMailbox.capacity, mailbox.count, "one slot per kind, all six")
        XCTAssertEqual(
            IntercomCommandKind.allCases.count,
            IntercomCommandMailbox.capacity,
            "the constant must match reality"
        )

        // Flooding every kind again cannot grow it.
        for _ in 0..<flood { for input in everyKind { mailbox.offer(input) } }
        XCTAssertEqual(IntercomCommandMailbox.capacity, mailbox.count, "still bounded after a flood of every kind")
    }

    /// The drain order is the safety property. Policy and capture come first because they reset the
    /// gate's transient state; the two overrides that can only ever *stop* transmission come next; the
    /// gate inputs come last.
    func testPollReturnsKindsInTheDeclaredSafetyOrder() {
        var mailbox = IntercomCommandMailbox()
        // Offered in the reverse of the expected drain order, so the order cannot be an accident of
        // insertion.
        mailbox.offer(.speechLevel(levelDbfs: -10.0, atMonoUs: 1))
        mailbox.offer(.pttHeld(true))
        mailbox.offer(.userMuted(true))
        mailbox.offer(.interrupted(true))
        mailbox.offer(.captureOpen(true))
        mailbox.offer(.policySelected(.modeC))

        var drained: [IntercomCommandKind] = []
        while let next = mailbox.poll() { drained.append(IntercomCommandMailbox.kind(for: next)) }
        XCTAssertEqual(IntercomCommandKind.allCases, drained, "drain order must be the declared kind order")
    }

    /// **The guarantee the fixed drain order buys.** `IntercomTransmission` is deliberately not
    /// commutative across kinds — a policy switch and a capture close reset the gate's transient state —
    /// so what callers need is not an order-blind reducer but an order-blind *pipeline*. Every arrival
    /// permutation of one batch drains to the same state, because the mailbox imposes the order rather
    /// than inheriting it from whichever thread got there first.
    func testEveryArrivalOrderOfOneBatchDrainsToTheSameState() {
        let batch: [IntercomInput] = [
            .policySelected(.modeA),
            .captureOpen(true),
            .interrupted(false),
            .userMuted(false),
            .pttHeld(true),
        ]
        var results: [TransmissionState] = []
        for order in permutations(batch) {
            var mailbox = IntercomCommandMailbox()
            for input in order { mailbox.offer(input) }
            var state = TransmissionState(policy: .modeC)
            while let next = mailbox.poll() {
                state = IntercomTransmission.reduce(state: state, input: next).state
            }
            results.append(state)
        }
        XCTAssertEqual(factorial5, results.count, "every permutation must be exercised")
        XCTAssertTrue(results.allSatisfy { $0 == results[0] }, "arrival order must not change the drained state")
        XCTAssertTrue(results[0].transmitting, "and this particular batch ends up transmitting")
    }

    /// The safety half of the same property: a batch that pairs a PTT press with **any one** reason not
    /// to transmit drains with transmission off, whatever order the two arrived in.
    ///
    /// Each stopper is a different `IntercomCommandKind` from the press, which is the case that matters
    /// — two values of the *same* kind coalesce and the newest legitimately wins, so a batch holding both
    /// `.captureOpen(true)` and `.captureOpen(false)` is a contradiction the mailbox is right to resolve
    /// by recency rather than by pessimism.
    func testAPttPressBatchedWithAnySingleReasonNotToTransmitNeverDrainsToTransmitting() {
        let stoppers: [IntercomInput] = [
            .userMuted(true),
            .interrupted(true),
            .captureOpen(false),
            .policySelected(.modeE),
        ]
        for stopper in stoppers {
            for order in permutations([IntercomInput.pttHeld(true), stopper]) {
                var mailbox = IntercomCommandMailbox()
                for input in order { mailbox.offer(input) }
                var state = TransmissionState(policy: .modeC, captureOpen: true)
                while let next = mailbox.poll() {
                    state = IntercomTransmission.reduce(state: state, input: next).state
                }
                XCTAssertFalse(state.transmitting, "\(stopper) arriving in order \(order) left transmission on")
            }
        }
    }

    /// And the converse, so the test above is not passing vacuously.
    func testAPttPressWithNoStopperInTheBatchDrainsToTransmitting() {
        var mailbox = IntercomCommandMailbox()
        mailbox.offer(.pttHeld(true))
        var state = TransmissionState(policy: .modeC, captureOpen: true)
        while let next = mailbox.poll() {
            state = IntercomTransmission.reduce(state: state, input: next).state
        }
        XCTAssertTrue(state.transmitting)
    }

    /// A press *and* its release inside one drain window coalesce to "not held". That is lossy and it is
    /// the safe direction of the loss: the alternative rounding would leave transmission stuck on after a
    /// release, which this phase's brief §25 forbids outright.
    func testAPressAndItsReleaseInsideOneWindowCoalesceToNotHeld() {
        var mailbox = IntercomCommandMailbox()
        XCTAssertEqual(.accepted, mailbox.offer(.pttHeld(true)))
        XCTAssertEqual(.coalesced, mailbox.offer(.pttHeld(false)))

        var state = TransmissionState(policy: .modeC, captureOpen: true)
        while let next = mailbox.poll() {
            state = IntercomTransmission.reduce(state: state, input: next).state
        }
        XCTAssertFalse(state.transmitting, "a coalesced press/release must never leave transmission on")
    }

    /// A level and a tick share a slot: both answer "where is the VOX gate now".
    func testAVoxTickAndASpeechLevelShareOneSlot() {
        var mailbox = IntercomCommandMailbox()
        XCTAssertEqual(.accepted, mailbox.offer(.speechLevel(levelDbfs: -10.0, atMonoUs: 1_000)))
        XCTAssertEqual(.coalesced, mailbox.offer(.voxTick(atMonoUs: 2_000)))
        XCTAssertEqual(1, mailbox.count)
        XCTAssertEqual(IntercomInput.voxTick(atMonoUs: 2_000), mailbox.poll())
    }

    func testCoalescingIsCountedSoTheDiagnosticsCanSayItHappened() {
        var mailbox = IntercomCommandMailbox()
        mailbox.offer(.pttHeld(true))
        mailbox.offer(.pttHeld(false))
        mailbox.offer(.pttHeld(true))
        XCTAssertEqual(2, mailbox.coalescedCount, "two replacements of an undelivered value")
    }

    func testClearEmptiesEverySlot() {
        var mailbox = IntercomCommandMailbox()
        mailbox.offer(.pttHeld(true))
        mailbox.offer(.userMuted(true))
        mailbox.clear()
        XCTAssertTrue(mailbox.isEmpty)
        XCTAssertNil(mailbox.poll())
    }

    /// Every input has exactly one kind, so no input can escape the bound by being unclassified.
    func testEveryInputKindIsClassified() {
        let inputs: [(IntercomInput, IntercomCommandKind)] = [
            (.policySelected(.modeA), .policy),
            (.captureOpen(true), .capture),
            (.interrupted(true), .interrupted),
            (.userMuted(true), .userMuted),
            (.pttHeld(true), .pttHeld),
            (.speechLevel(levelDbfs: 0.0, atMonoUs: 0), .voxLevel),
            (.voxTick(atMonoUs: 0), .voxLevel),
        ]
        for (input, kind) in inputs {
            XCTAssertEqual(kind, IntercomCommandMailbox.kind(for: input), "\(input) classification")
        }
        XCTAssertEqual(
            Set(IntercomCommandKind.allCases),
            Set(inputs.map(\.1)),
            "every kind must be reachable from some input"
        )
    }

    private func permutations<T>(_ items: [T]) -> [[T]] {
        guard items.count > 1 else { return [items] }
        return items.indices.flatMap { index -> [[T]] in
            var rest = items
            let picked = rest.remove(at: index)
            return permutations(rest).map { [picked] + $0 }
        }
    }
}
