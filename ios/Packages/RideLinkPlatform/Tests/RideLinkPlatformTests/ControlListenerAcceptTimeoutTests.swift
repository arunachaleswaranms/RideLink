import Foundation
import RideLinkCore
import XCTest
@testable import RideLinkPlatform

/// ADR-023 Amendment A4 Finding W — `ControlListener.accept(timeoutMs:)`'s bound, proven with a
/// short timeout rather than the production 30 s one, and proven to be *opt-in*: the control
/// plane's own unbounded `accept()` must stay unbounded, because a control listener legitimately
/// waits indefinitely for its peer to appear while a bulk listener is answering an offer whose
/// `bulk_token` has a TTL.
///
/// The Kotlin mirror is `com.ridelink.network.transfer.ControlListenerAcceptTimeoutTest`.
final class ControlListenerAcceptTimeoutTests: XCTestCase {
    func testABoundedAcceptGivesUpOnceItsBoundExpiresInsteadOfParkingForever() async throws {
        let identity = try TestTlsSupport.freshIdentity()
        let listener = try await TestTlsSupport.channel(identity).bind()
        defer { listener.close() }

        let startedAt = DispatchTime.now().uptimeNanoseconds
        do {
            _ = try await listener.accept(timeoutMs: Self.timeoutMs)
            XCTFail("a bounded accept with nobody dialling must throw, not return a connection")
        } catch {
            // Whatever the error type, `TransferManager.serve`'s own `try?` reduces it to
            // `.ioError` -- no new failure path anywhere.
        }
        let elapsedMs = Int((DispatchTime.now().uptimeNanoseconds - startedAt) / 1_000_000)

        XCTAssertGreaterThanOrEqual(elapsedMs, Self.timeoutMs, "must have actually waited its bound, not failed instantly")
        XCTAssertLessThan(elapsedMs, Self.timeoutMs * 20, "must not have waited far beyond it")
    }

    /// The bound must belong to the one waiter that asked for it. A second, unbounded `accept()`
    /// parked on the same listener must still be waiting after the first one's bound has expired --
    /// this is the property the per-waiter id exists for, and the reason the control plane can keep
    /// sharing this class with the bulk plane.
    func testABoundedAcceptTimingOutDoesNotDisturbAnUnboundedWaiterOnTheSameListener() async throws {
        let identity = try TestTlsSupport.freshIdentity()
        let listener = try await TestTlsSupport.channel(identity).bind()

        let unboundedFinished = UnboundedWaiterFlag()
        let unboundedParked = UnboundedWaiterFlag()
        let unbounded = Task {
            await unboundedParked.markFinished()
            _ = try? await listener.accept() // no bound: must outlive the bounded waiter below
            await unboundedFinished.markFinished()
        }
        // The unbounded waiter must be parked *first*, or the bounded one below would be the only
        // entry in the queue and a naive "resume whoever is first" timeout would look correct.
        while await !unboundedParked.isFinished { await Task.yield() }
        try? await Task.sleep(nanoseconds: UInt64(Self.settleMs) * 1_000_000)

        do {
            _ = try await listener.accept(timeoutMs: Self.timeoutMs)
            XCTFail("the bounded accept must throw")
        } catch {
            // expected
        }
        // Give the runtime a moment to run the unbounded waiter's continuation if it were (wrongly)
        // resumed by the bounded waiter's timeout.
        try? await Task.sleep(nanoseconds: UInt64(Self.settleMs) * 1_000_000)

        let finished = await unboundedFinished.isFinished
        XCTAssertFalse(finished, "the unbounded waiter must still be parked -- one waiter's bound is not another's")

        // close() is what legitimately ends every waiter, bounded or not.
        listener.close()
        _ = await unbounded.value
        let finishedAfterClose = await unboundedFinished.isFinished
        XCTAssertTrue(finishedAfterClose, "close() must end even an unbounded waiter")
    }

    private actor UnboundedWaiterFlag {
        private var finished = false
        var isFinished: Bool { finished }
        func markFinished() { finished = true }
    }

    private static let timeoutMs = 300
    private static let settleMs = 300
}
