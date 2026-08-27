import Foundation
import RideLinkCore
import XCTest
@testable import RideLinkPlatform

/// PROTOCOL §4.5 pairing as a state machine, exhausted without a socket in the way. The mirror of
/// Android's `PairingExchangeTest`.
///
/// The properties under test are the ones that make the six-digit code mean something. A pin
/// written on one user's say-so, a code that was never bound to the TLS session, or a replayed
/// `PAIR_RESULT` writing a second pin would each turn the confirmation into decoration — and none
/// of them would be visible in an end-to-end test that only checks "pairing succeeded".
final class PairingExchangeTests: XCTestCase {
    private let remotePeerId = PeerId("bbbbbbbbbbbbbbbb")
    private let peerSpki = SpkiHash("sha256:" + String(repeating: "ab", count: 32))
    private let otherSpki = SpkiHash("sha256:" + String(repeating: "cd", count: 32))
    private let sas = "042137"
    private let now: Int64 = 1_787_832_000

    private func exchange(isInitiator: Bool, store: InMemoryTrustedPeerStore = InMemoryTrustedPeerStore()) -> PairingExchange {
        PairingExchange(
            remotePeerId: remotePeerId,
            peerIdentitySpkiSha256: peerSpki,
            isInitiator: isInitiator,
            trustedPeers: store,
            nowEpochSeconds: { self.now }
        )
    }

    func testNoPinIsWrittenUntilBothSidesHaveConfirmed() {
        let store = InMemoryTrustedPeerStore()
        let acceptor = exchange(isInitiator: false, store: store)
        _ = acceptor.begin(derivedSas6: sas)
        _ = acceptor.onPairRequest(displayName: "Rider", advertisedSpki: peerSpki)

        // This user says yes. The peer has not.
        XCTAssertEqual(.wait, acceptor.onLocalDecision(accepted: true))
        XCTAssertNil(store.byPeerId(remotePeerId), "one screen's confirmation is not a pairing")

        // Now the peer's confirmation arrives.
        XCTAssertEqual(.sendPairResultAccepted, acceptor.onPairConfirm(accepted: true))
        XCTAssertEqual(peerSpki, store.byPeerId(remotePeerId)?.identitySpkiSha256)
    }

    func testThePeerConfirmingFirstStillWaitsForThisUser() {
        let store = InMemoryTrustedPeerStore()
        let acceptor = exchange(isInitiator: false, store: store)
        _ = acceptor.begin(derivedSas6: sas)

        XCTAssertEqual(.wait, acceptor.onPairConfirm(accepted: true))
        XCTAssertNil(store.byPeerId(remotePeerId), "the remote user cannot pair on this user's behalf")

        XCTAssertEqual(.sendPairResultAccepted, acceptor.onLocalDecision(accepted: true))
        XCTAssertNotNil(store.byPeerId(remotePeerId))
    }

    func testTheInitiatorSendsPairConfirmAndSettlesOnPairResult() {
        let store = InMemoryTrustedPeerStore()
        let initiator = exchange(isInitiator: true, store: store)
        _ = initiator.begin(derivedSas6: sas)

        XCTAssertEqual(.sendPairConfirm, initiator.onLocalDecision(accepted: true))
        XCTAssertNil(store.byPeerId(remotePeerId))

        guard case .succeeded(let peer) = initiator.onPairResult(accepted: true, advertisedSpki: peerSpki) else {
            return XCTFail("expected .succeeded")
        }
        XCTAssertEqual(peerSpki, peer.identitySpkiSha256)
        XCTAssertEqual(now, peer.pairedAtEpochSeconds)
    }

    func testEitherSideRejectingFailsTheExchangeAndWritesNothing() {
        let store = InMemoryTrustedPeerStore()

        let localReject = exchange(isInitiator: true, store: store)
        _ = localReject.begin(derivedSas6: sas)
        XCTAssertEqual(.failed(code: errorCodePairingRejected), localReject.onLocalDecision(accepted: false))

        let remoteReject = exchange(isInitiator: false, store: store)
        _ = remoteReject.begin(derivedSas6: sas)
        _ = remoteReject.onLocalDecision(accepted: true)
        XCTAssertEqual(.failed(code: errorCodePairingRejected), remoteReject.onPairConfirm(accepted: false))

        XCTAssertNil(store.byPeerId(remotePeerId), "a rejected pairing must leave no trace")
    }

    func testAPeerWhosePairRequestContradictsItsCertificateIsRefused() {
        let sut = exchange(isInitiator: false)
        _ = sut.begin(derivedSas6: sas)
        // PROTOCOL §4.1's rule applied to §4.5: the certificate is authoritative, the field is not.
        XCTAssertEqual(.failed(code: errorCodeIdentityMismatch),
                       sut.onPairRequest(displayName: "Rider", advertisedSpki: otherSpki))
    }

    func testAPairResultAdvertisingADifferentIdentityIsRefused() {
        let sut = exchange(isInitiator: true)
        _ = sut.begin(derivedSas6: sas)
        _ = sut.onLocalDecision(accepted: true)
        XCTAssertEqual(.failed(code: errorCodeIdentityMismatch),
                       sut.onPairResult(accepted: true, advertisedSpki: otherSpki))
    }

    func testAMissingExporterFailsTheExchangeRatherThanShowingAnUnboundCode() {
        // ADR-007 Amendment A1: without a channel binding the six digits prove nothing, and the
        // response is to stop — never to display something that looks like a verification.
        let sut = exchange(isInitiator: true)
        XCTAssertEqual(.failed(code: errorCodeInternal), sut.begin(derivedSas6: nil))
        XCTAssertNil(sut.sas6, "no code may be displayed when the exporter is unavailable")
    }

    func testAReplayedPairResultCannotPairTwice() {
        let store = InMemoryTrustedPeerStore()
        let initiator = exchange(isInitiator: true, store: store)
        _ = initiator.begin(derivedSas6: sas)
        _ = initiator.onLocalDecision(accepted: true)

        guard case .succeeded = initiator.onPairResult(accepted: true, advertisedSpki: peerSpki) else {
            return XCTFail("expected .succeeded")
        }
        // A duplicated or replayed frame must not re-enter the success path.
        XCTAssertEqual(.wait, initiator.onPairResult(accepted: true, advertisedSpki: peerSpki))
        XCTAssertEqual(1, store.all().count)
    }

    func testPairingNeverOverwritesAnExistingPinForTheSamePeer() {
        // The concrete shape of ADR-012's "never auto re-pair": if a record already exists for this
        // peer_id under a different key, pairing must refuse rather than substitute.
        let store = InMemoryTrustedPeerStore([
            TrustedPeer(peerId: remotePeerId, identitySpkiSha256: otherSpki,
                        displayName: "old", pairedAtEpochSeconds: now, lastSeenAtEpochSeconds: now),
        ])
        let sut = exchange(isInitiator: true, store: store)
        _ = sut.begin(derivedSas6: sas)
        _ = sut.onLocalDecision(accepted: true)

        XCTAssertEqual(.failed(code: errorCodePinMismatch),
                       sut.onPairResult(accepted: true, advertisedSpki: peerSpki))
        XCTAssertEqual(otherSpki, store.byPeerId(remotePeerId)?.identitySpkiSha256)
    }
}
