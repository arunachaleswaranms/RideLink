import XCTest
@testable import RideLinkCore

final class ReleaseLifecyclePropertyTests: XCTestCase {
    func testThousandSessionsWithThreeRidesRecoveryEndingAndRestart() {
        var state = FsmState(status: .idle)
        var releases = 0
        func step(_ event: SessionEvent, file: StaticString = #filePath, line: UInt = #line) {
            guard case .transitioned(let next, let effects) = SessionFsm.transition(state, event) else {
                return XCTFail("rejected \(event) from \(state)", file: file, line: line)
            }
            releases += effects.filter { if case .releaseAudioAndStopForegroundService = $0 { true } else { false } }.count
            state = next
        }
        for cycle in 0 ..< 1_000 {
            step(.startDiscovery)
            step(.peerSelected)
            step(.pairingSucceeded)
            step(.connectionEstablished)
            for ride in 0 ..< 3 {
                step(.startRide)
                step(.linkLost(reason: .network))
                if (cycle + ride) % 2 == 0 {
                    step(.endRide)
                    XCTAssertEqual(state, FsmState(status: .reconnecting, returnTo: .connected))
                    if case .rejected = SessionFsm.transition(state, .endRide) {} else {
                        XCTFail("duplicate End Ride must not retire a second lifetime")
                    }
                    step(.reconnectSucceeded)
                } else {
                    step(.reconnectSucceeded)
                    XCTAssertEqual(state.status, .rideActive)
                    step(.endRide)
                }
                XCTAssertEqual(state.status, .connected)
                XCTAssertEqual(releases, cycle)
            }
            step(.startRide)
            step(.linkLost(reason: .network))
            step(.reconnectBudgetExhausted)
            step(.endRide)
            XCTAssertEqual(state.status, .ending)
            step(.teardownComplete)
            XCTAssertEqual(state.status, .idle)
            XCTAssertEqual(releases, cycle + 1)
        }
    }
}
