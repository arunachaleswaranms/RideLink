import XCTest
@testable import RideLinkCore
@testable import RideLinkPlatform

/// Deterministic, synchronous coverage for `PingRequestRegistry` — the CI-stabilization fix for
/// GitHub Actions run 33033917411 (`PingRaceAndReconnectTests.testRepeatedClockBurstsAllCompleteQuickly`
/// failed with `ControlTransportError.notReady` under real scheduling contention, root-caused to
/// orphaned timeout `Task`s piling up after fast PONGs). These tests exercise the registry
/// directly — including genuinely concurrent access, since the registry is internally lock-
/// protected and safe on its own — so every invariant is provable in milliseconds rather than
/// only probabilistically against real sockets.
/// Free functions, not instance methods: a `Task { ... }` created inside a test method must not
/// capture `self` (the `XCTestCase`, which is not `Sendable`) merely to call a helper — Swift 6
/// strict concurrency correctly flags that as a potential data race.
private func sample(_ t1: Int64) -> ClockSync.Sample {
    ClockSync.Sample(t1MonoUs: t1, t2MonoUs: t1 + 1, t3MonoUs: t1 + 2, t4MonoUs: t1 + 3)
}

/// A timeout task that never fires on its own within a test's lifetime — its `isCancelled` flag
/// is the only thing under test, never its actual firing.
private func neverFiringTimeoutTask() -> Task<Void, Never> {
    Task {
        try? await Task.sleep(nanoseconds: 60_000_000_000)
    }
}

final class PingRequestRegistryTests: XCTestCase {
    // MARK: - Waiter exists before write / immediate PONG succeeds / success cancels the timeout

    func testRegisterMakesTheIdImmediatelyPendingAndSucceedResolvesAndCancelsTheTimeout() async throws {
        let registry = PingRequestRegistry()
        var capturedId: Int64?

        let result = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<ClockSync.Sample, Error>) in
            let id = registry.reserveAndRegister(startingFrom: 100, continuation: continuation)
            capturedId = id
            XCTAssertTrue(registry.isPending(id), "the waiter must exist the instant reserveAndRegister returns, before any write")
            XCTAssertEqual(registry.count, 1)
            registry.succeed(id: id, with: sample(id)) // models an immediate PONG, before a timeout is even attached
        }

        let timeoutTask = neverFiringTimeoutTask()
        registry.attachTimeout(id: capturedId!, timeoutTask: timeoutTask) // already resolved — must cancel immediately

        XCTAssertEqual(result.t1MonoUs, capturedId)
        XCTAssertFalse(registry.isPending(capturedId!))
        XCTAssertTrue(timeoutTask.isCancelled, "attaching a timeout to an already-resolved request must cancel it immediately")
    }

    func testAttachedTimeoutIsCancelledWhenTheRequestSucceedsAfterwards() async throws {
        let registry = PingRequestRegistry()
        let timeoutTask = neverFiringTimeoutTask()

        _ = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<ClockSync.Sample, Error>) in
            let id = registry.reserveAndRegister(startingFrom: 200, continuation: continuation)
            registry.attachTimeout(id: id, timeoutTask: timeoutTask)
            registry.succeed(id: id, with: sample(id))
        }

        XCTAssertTrue(timeoutTask.isCancelled, "a resolved request must not leave its timeout task alive")
    }

    // MARK: - Write failure cleans the waiter

    func testFailResolvesTheContinuationWithTheGivenErrorAndCancelsTheTimeout() async {
        let registry = PingRequestRegistry()
        let timeoutTask = neverFiringTimeoutTask()

        do {
            _ = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<ClockSync.Sample, Error>) in
                let id = registry.reserveAndRegister(startingFrom: 3, continuation: continuation)
                registry.attachTimeout(id: id, timeoutTask: timeoutTask)
                registry.fail(id: id, with: ControlTransportError.notReady) // models a write failure
            }
            XCTFail("expected a thrown error")
        } catch {
            XCTAssertTrue(error is ControlTransportError)
        }
        XCTAssertFalse(registry.isPending(3))
        XCTAssertTrue(timeoutTask.isCancelled)
    }

    // MARK: - Timeout cleans the waiter, and a stray double-resolution is a safe no-op

    func testResolvingTwiceForTheSameIdIsASafeNoOpTheSecondTime() async throws {
        // Models a timeout task firing after a PONG already resolved the request — a benign race
        // this type must absorb without a double-resume crash.
        let registry = PingRequestRegistry()
        var capturedId: Int64?

        _ = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<ClockSync.Sample, Error>) in
            let id = registry.reserveAndRegister(startingFrom: 4, continuation: continuation)
            capturedId = id
            registry.succeed(id: id, with: sample(id))
        }
        registry.fail(id: capturedId!, with: ControlTransportError.notReady) // must not crash; must not resolve anything
        XCTAssertFalse(registry.isPending(capturedId!))
    }

    // MARK: - Shutdown cleans every waiter

    func testFailAllResolvesEveryOutstandingRequestAndCancelsEveryTimeout() async {
        let registry = PingRequestRegistry()
        let proposedIds = Array(0..<5).map(Int64.init)

        let tasks = proposedIds.map { proposed in
            Task<Result<ClockSync.Sample, Error>, Never> {
                let timeoutTask = neverFiringTimeoutTask()
                do {
                    let sample = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<ClockSync.Sample, Error>) in
                        let id = registry.reserveAndRegister(startingFrom: proposed, continuation: continuation)
                        registry.attachTimeout(id: id, timeoutTask: timeoutTask)
                    }
                    return .success(sample)
                } catch {
                    return .failure(error)
                }
            }
        }

        // Wait until every registration's synchronous body has actually run.
        while registry.count < proposedIds.count { await Task.yield() }

        registry.failAll(with: ControlTransportError.notReady)

        for task in tasks {
            switch await task.value {
            case .success: XCTFail("failAll must fail every request, not succeed any of them")
            case .failure(let error): XCTAssertTrue(error is ControlTransportError)
            }
        }
        XCTAssertEqual(registry.count, 0)
    }

    // MARK: - Concurrent PINGs cannot overwrite each other

    func testReserveAndRegisterNeverCollidesWithAnAlreadyPendingId() async throws {
        let registry = PingRequestRegistry()

        // Register the first request under 1_000 and leave it pending (no resolution yet) while
        // a second caller proposes the exact same monotonic value — e.g. the keepalive loop and
        // the clock-sync burst both reading the clock in the same microsecond.
        _ = try await withCheckedThrowingContinuation { (firstContinuation: CheckedContinuation<ClockSync.Sample, Error>) in
            let firstId = registry.reserveAndRegister(startingFrom: 1_000, continuation: firstContinuation)
            XCTAssertEqual(firstId, 1_000)
            XCTAssertTrue(registry.isPending(1_000))

            Task {
                try! await withCheckedThrowingContinuation { (secondContinuation: CheckedContinuation<ClockSync.Sample, Error>) in
                    let secondId = registry.reserveAndRegister(startingFrom: 1_000, continuation: secondContinuation)
                    XCTAssertNotEqual(secondId, 1_000, "a colliding proposal must be bumped, never reused while the original is still pending")
                    XCTAssertTrue(registry.isPending(1_000), "the original request must be untouched by the second reservation")
                    XCTAssertEqual(registry.count, 2)
                    registry.succeed(id: secondId, with: sample(secondId))
                    registry.succeed(id: firstId, with: sample(firstId))
                }
            }
        }

        XCTAssertEqual(registry.count, 0)

        // With both requests now resolved, a fresh reservation is free to reuse 1_000.
        _ = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<ClockSync.Sample, Error>) in
            let id = registry.reserveAndRegister(startingFrom: 1_000, continuation: continuation)
            XCTAssertEqual(id, 1_000, "a freed id must be reusable")
            registry.succeed(id: id, with: sample(id))
        }
    }

    func testTwoGenuinelyConcurrentReservationsForTheSameProposedIdBothSucceedIndependently() async {
        let registry = PingRequestRegistry()
        let ids = IdBox()

        let taskA = Task<ClockSync.Sample, Never> {
            try! await withCheckedThrowingContinuation { (continuation: CheckedContinuation<ClockSync.Sample, Error>) in
                let id = registry.reserveAndRegister(startingFrom: 5_000, continuation: continuation)
                ids.a = id
            }
        }
        let taskB = Task<ClockSync.Sample, Never> {
            try! await withCheckedThrowingContinuation { (continuation: CheckedContinuation<ClockSync.Sample, Error>) in
                let id = registry.reserveAndRegister(startingFrom: 5_000, continuation: continuation) // same proposal as A
                ids.b = id
            }
        }

        while ids.a == nil || ids.b == nil { await Task.yield() }

        XCTAssertNotEqual(ids.a, ids.b, "two requests proposing the same monotonic value must never collide")
        XCTAssertEqual(registry.count, 2)

        registry.succeed(id: ids.a!, with: sample(ids.a!))
        registry.succeed(id: ids.b!, with: sample(ids.b!))

        let resultA = await taskA.value
        let resultB = await taskB.value
        XCTAssertEqual(resultA.t1MonoUs, ids.a)
        XCTAssertEqual(resultB.t1MonoUs, ids.b, "resolving A must never have silently resolved or dropped B")
        XCTAssertEqual(registry.count, 0)
    }

    /// Genuinely hammers `reserveAndRegister` from many concurrent tasks proposing the *same*
    /// starting value, the way the keepalive loop and clock-sync burst can both propose "now" at
    /// once. Every id handed out must be unique and every request must resolve independently —
    /// proving the reserve-then-register step really is atomic under real concurrency, not just
    /// in the two-task case above.
    func testManyConcurrentReservationsForTheSameProposedIdAllReceiveDistinctIdsAndAllResolve() async {
        let registry = PingRequestRegistry()
        let concurrency = 50
        let collectedIds = IdCollector()

        let tasks = (0..<concurrency).map { _ in
            Task<ClockSync.Sample, Never> {
                try! await withCheckedThrowingContinuation { (continuation: CheckedContinuation<ClockSync.Sample, Error>) in
                    let id = registry.reserveAndRegister(startingFrom: 9_000, continuation: continuation)
                    collectedIds.add(id)
                }
            }
        }

        while collectedIds.count < concurrency { await Task.yield() }

        let ids = collectedIds.snapshot()
        XCTAssertEqual(Set(ids).count, concurrency, "every concurrently-reserved id must be unique: \(ids)")
        XCTAssertEqual(registry.count, concurrency)

        for id in ids { registry.succeed(id: id, with: sample(id)) }

        var resolvedIds: [Int64] = []
        for task in tasks { resolvedIds.append(await task.value.t1MonoUs) }
        XCTAssertEqual(Set(resolvedIds), Set(ids), "every request must resolve with its own id, none dropped or cross-completed")
        XCTAssertEqual(registry.count, 0)
    }

    // MARK: - Rapid sequential PINGs cannot cross-complete

    func testResolvingAnEarlierRequestNeverAffectsALaterRequestThatReusesTheSameNumericId() async throws {
        // A later request is only allowed to reuse a numeric id once the earlier one has fully
        // resolved (production only reuses an id after `reserveAndRegister` finds it free again).
        // This proves that reuse is safe: the second registration is unaffected by the first
        // having already resolved under the same key.
        let registry = PingRequestRegistry()
        let firstTimeout = neverFiringTimeoutTask()
        _ = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<ClockSync.Sample, Error>) in
            let id = registry.reserveAndRegister(startingFrom: 42, continuation: continuation)
            registry.attachTimeout(id: id, timeoutTask: firstTimeout)
            registry.succeed(id: id, with: sample(id))
        }
        XCTAssertTrue(firstTimeout.isCancelled)

        let secondTimeout = neverFiringTimeoutTask()
        let result = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<ClockSync.Sample, Error>) in
            let id = registry.reserveAndRegister(startingFrom: 42, continuation: continuation)
            XCTAssertEqual(id, 42, "the id must be reusable once fully resolved")
            registry.attachTimeout(id: id, timeoutTask: secondTimeout)
            registry.succeed(id: id, with: sample(id))
        }
        XCTAssertEqual(result.t1MonoUs, 42)
        XCTAssertTrue(secondTimeout.isCancelled)
    }
}

private final class IdBox: @unchecked Sendable {
    var a: Int64?
    var b: Int64?
}

private final class IdCollector: @unchecked Sendable {
    private let lock = NSLock()
    private var ids: [Int64] = []

    func add(_ id: Int64) {
        lock.lock(); defer { lock.unlock() }
        ids.append(id)
    }

    var count: Int {
        lock.lock(); defer { lock.unlock() }
        return ids.count
    }

    func snapshot() -> [Int64] {
        lock.lock(); defer { lock.unlock() }
        return ids
    }
}
