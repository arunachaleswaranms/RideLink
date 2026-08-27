import Foundation
import RideLinkCore

/// PROTOCOL §4.5 pairing, on the surviving connection only. The mirror of Android's
/// `PairingExchange`.
///
/// ```
/// A                                                                  B
/// │─ PAIR_REQUEST { display_name, platform, identity_spki_sha256 } ──►│
/// │  both derive sas6 from the TLS exporter (§4.5.1) and display it   │
/// │  both users confirm the two screens match                         │
/// │─ PAIR_CONFIRM { sas6_accepted: true } ───────────────────────────►│
/// │◄─ PAIR_RESULT { accepted: true, peer_id, identity_spki_sha256 } ───│
/// ```
///
/// Three properties this type exists to hold, all of which are easy to get subtly wrong:
///
/// 1. **The SAS never leaves the device.** It is derived independently on each side from the TLS
///    exporter and compared by two humans looking at two screens. `PAIR_CONFIRM` carries a
///    boolean. Sending the digits would let a man-in-the-middle forward them and the check would
///    prove nothing (PROTOCOL §4.5.1, ARCHITECTURE §11 — the SAS has no log path either).
/// 2. **Trust is persisted only when both sides have confirmed.** A local confirmation alone is
///    "this user says the codes match"; it takes the peer's too before a pin is written.
/// 3. **Nothing is persisted twice.** `completed` latches, so a duplicated or replayed
///    `PAIR_RESULT` cannot re-enter the success path.
///
/// State is deliberately small and owned by one connection: a fresh instance per pairing attempt,
/// discarded with the connection. Confined to `ControlSessionManager`'s actor, so it needs no lock
/// of its own.
final class PairingExchange {
    /// What the caller should do next. The transport sends the frames; this decides which.
    enum Step: Equatable {
        /// Nothing to send yet — still waiting on a human or on the peer.
        case wait
        case sendPairConfirm
        case sendPairResultAccepted
        /// Both sides confirmed and the trusted-peer record is now written.
        case succeeded(TrustedPeer)
        /// Someone said no, or the exchange is unusable. The value is a PROTOCOL §4.6 code.
        case failed(code: String)
    }

    /// What the user must compare against the other screen. Never logged, never sent, never stored.
    private(set) var sas6: String?
    private(set) var peerDisplayName = ""

    private let remotePeerId: PeerId
    private let peerIdentitySpkiSha256: SpkiHash
    private let isInitiator: Bool
    private let trustedPeers: any TrustedPeerStore
    private let nowEpochSeconds: () -> Int64

    private var localConfirmed = false
    private var remoteConfirmed = false
    private var completed = false

    init(
        remotePeerId: PeerId,
        peerIdentitySpkiSha256: SpkiHash,
        isInitiator: Bool,
        trustedPeers: any TrustedPeerStore,
        nowEpochSeconds: @escaping () -> Int64
    ) {
        self.remotePeerId = remotePeerId
        self.peerIdentitySpkiSha256 = peerIdentitySpkiSha256
        self.isInitiator = isInitiator
        self.trustedPeers = trustedPeers
        self.nowEpochSeconds = nowEpochSeconds
    }

    /// Called once the connection has been promoted and the pin decision was `.pairingRequired`.
    ///
    /// A nil `derivedSas6` is a **hard failure**, not a degraded mode: without the exporter there
    /// is no channel binding, so the six digits would not be bound to this TLS session and the
    /// confirmation would be theatre. ADR-007 Amendment A1 forbids substituting something weaker.
    func begin(derivedSas6: String?) -> Step {
        guard let derivedSas6 else { return .failed(code: errorCodeInternal) }
        sas6 = derivedSas6
        return .wait
    }

    func onPairRequest(displayName: String, advertisedSpki: SpkiHash) -> Step {
        // The certificate is authoritative (PROTOCOL §4.1); this field is advisory and is
        // cross-checked for the same reason HELLO's is.
        guard advertisedSpki == peerIdentitySpkiSha256 else { return .failed(code: errorCodeIdentityMismatch) }
        peerDisplayName = displayName
        return .wait
    }

    /// The user tapped confirm or reject on **this** device.
    func onLocalDecision(accepted: Bool) -> Step {
        guard accepted else { return .failed(code: errorCodePairingRejected) }
        guard sas6 != nil else { return .failed(code: errorCodeInternal) }
        localConfirmed = true
        // Only the initiator sends PAIR_CONFIRM; the acceptor's local decision is combined with the
        // initiator's confirmation when that frame arrives.
        return isInitiator ? .sendPairConfirm : settleIfBothConfirmed()
    }

    func onPairConfirm(accepted: Bool) -> Step {
        guard accepted else { return .failed(code: errorCodePairingRejected) }
        remoteConfirmed = true
        return settleIfBothConfirmed()
    }

    func onPairResult(accepted: Bool, advertisedSpki: SpkiHash) -> Step {
        guard accepted else { return .failed(code: errorCodePairingRejected) }
        guard advertisedSpki == peerIdentitySpkiSha256 else { return .failed(code: errorCodeIdentityMismatch) }
        remoteConfirmed = true
        return settleIfBothConfirmed()
    }

    /// The acceptor's second half of `settleIfBothConfirmed`: the record is already written.
    func completedPeer() -> TrustedPeer? {
        completed ? trustedPeers.byPeerId(remotePeerId) : nil
    }

    /// Writes the pin, but only once both humans have agreed and only once overall.
    ///
    /// The acceptor additionally answers `PAIR_RESULT`; the initiator, which reaches this point by
    /// *receiving* that frame, has nothing left to send.
    private func settleIfBothConfirmed() -> Step {
        guard localConfirmed, remoteConfirmed else { return .wait }
        guard !completed else { return .wait }
        completed = true
        let now = nowEpochSeconds()
        let peer = TrustedPeer(
            peerId: remotePeerId,
            identitySpkiSha256: peerIdentitySpkiSha256,
            displayName: peerDisplayName.isEmpty ? remotePeerId.value : peerDisplayName,
            pairedAtEpochSeconds: now,
            lastSeenAtEpochSeconds: now
        )
        do {
            try trustedPeers.remember(peer)
        } catch {
            // ADR-012: a stored pin is never silently replaced. Reaching here means a record
            // already exists for this peer_id under a different key, which is a `pin_mismatch`
            // situation the user has to resolve with an explicit forget — not something pairing
            // may paper over.
            completed = false
            return .failed(code: errorCodePinMismatch)
        }
        return isInitiator ? .succeeded(peer) : .sendPairResultAccepted
    }
}
