@testable import RideLinkCore
import XCTest

/// STATUS §4 problem 126 / ADR-012 Amendment A1: the two pure pieces *Forget peer* rests on.
/// The mirror of Android's `ForgetPeerTest`.
final class ForgetPeerTests: XCTestCase {
    private let pinned = SpkiHash("sha256:" + String(repeating: "11", count: 32))
    private let other = SpkiHash("sha256:" + String(repeating: "22", count: 32))

    func testForgettingIsPermittedOnlyWhenNoSessionExists() {
        let statuses: [SessionStatus] = [.idle, .discovering, .pairing, .connecting, .connected, .rideActive,
                                         .reconnecting, .disconnected, .ending, .error]
        for status in statuses {
            XCTAssertEqual(ForgetPeerPolicy.permits(status), status == .idle, "\(status)")
        }
    }

    /// The re-check at the trust gate: a forgotten pin can only ever lead to the six-digit code.
    func testThePinRecheckSendsAForgottenPeerToPairingAndKeepsEveryOtherVerdict() {
        XCTAssertEqual(PeerTrust.recheck(storedPin: nil, presentedSpki: pinned), .pairingRequired)
        XCTAssertEqual(PeerTrust.recheck(storedPin: pinned, presentedSpki: pinned), .trusted)
        XCTAssertEqual(PeerTrust.recheck(storedPin: other, presentedSpki: pinned), .pinMismatch)
    }

    /// The re-check agrees with `decide` on the pin wherever the certificate checks passed.
    func testTheRecheckIsThePinHalfOfDecide() {
        for stored in [nil, pinned, other] {
            XCTAssertEqual(
                PeerTrust.recheck(storedPin: stored, presentedSpki: pinned),
                PeerTrust.decide(storedPin: stored, presentedSpki: pinned, helloAdvertisedSpki: pinned,
                                 certificateStructurallyValid: true))
        }
    }

    func testForgetRemovesOnlyTheSelectedRecord() throws {
        let a = PeerId("aaaaaaaaaaaaaaaa"), b = PeerId("bbbbbbbbbbbbbbbb")
        let store = InMemoryTrustedPeerStore([
            TrustedPeer(peerId: a, identitySpkiSha256: pinned, displayName: "A", pairedAtEpochSeconds: 1, lastSeenAtEpochSeconds: 2),
            TrustedPeer(peerId: b, identitySpkiSha256: other, displayName: "B", pairedAtEpochSeconds: 1, lastSeenAtEpochSeconds: 2),
        ])

        try store.forget(a)

        XCTAssertNil(store.byPeerId(a))
        XCTAssertNil(store.bySpki(pinned))
        XCTAssertEqual(store.byPeerId(b)?.identitySpkiSha256, other)
        try store.forget(PeerId("cccccccccccccccc"))
        XCTAssertEqual(store.all().map(\.peerId), [b])
    }
}
