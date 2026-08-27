package com.ridelink.network.control

import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SpkiHash
import com.ridelink.core.security.TrustedPeer
import com.ridelink.core.security.TrustedPeerStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PROTOCOL §4.5 pairing, on the surviving connection only.
 *
 * ```
 * A                                                                  B
 * │─ PAIR_REQUEST { display_name, platform, identity_spki_sha256 } ──►│
 * │  both derive sas6 from the TLS exporter (§4.5.1) and display it   │
 * │  both users confirm the two screens match                         │
 * │─ PAIR_CONFIRM { sas6_accepted: true } ───────────────────────────►│
 * │◄─ PAIR_RESULT { accepted: true, peer_id, identity_spki_sha256 } ───│
 * ```
 *
 * Three properties this type exists to hold, all of which are easy to get subtly wrong:
 *
 * 1. **The SAS never leaves the device.** It is derived independently on each side from the TLS
 *    exporter and compared by two humans looking at two screens. `PAIR_CONFIRM` carries a boolean.
 *    Sending the digits would let a man-in-the-middle forward them and the check would prove
 *    nothing (PROTOCOL §4.5.1, ARCHITECTURE §11 — the SAS has no log path either).
 * 2. **Trust is persisted only when both sides have confirmed.** A local confirmation alone is
 *    "this user says the codes match"; it takes the peer's too before a pin is written.
 * 3. **Nothing is persisted twice.** [completed] latches, so a duplicated or replayed
 *    `PAIR_RESULT` cannot re-enter the success path.
 *
 * State is deliberately small and owned by one connection: a fresh instance per pairing attempt,
 * discarded with the socket.
 */
class PairingExchange(
    private val remotePeerId: PeerId,
    private val peerIdentitySpkiSha256: SpkiHash,
    private val isInitiator: Boolean,
    private val trustedPeers: TrustedPeerStore,
    private val nowEpochSeconds: () -> Long,
) {
    /** What the user must compare against the other screen. Never logged, never sent, never stored. */
    @Volatile
    var sas6: String? = null
        private set

    @Volatile
    var peerDisplayName: String = ""
        private set

    private val localConfirmed = AtomicBoolean(false)
    private val remoteConfirmed = AtomicBoolean(false)
    private val completed = AtomicBoolean(false)

    /** What the caller should do next. The transport sends the frames; this decides which. */
    sealed interface Step {
        /** Nothing to send yet — still waiting on a human or on the peer. */
        data object Wait : Step

        data object SendPairConfirm : Step

        data object SendPairResultAccepted : Step

        /** Both sides confirmed and the trusted-peer record is now written. */
        data class Succeeded(
            val peer: TrustedPeer,
        ) : Step

        /** Someone said no, or the exchange is unusable. [code] is a PROTOCOL §4.6 code. */
        data class Failed(
            val code: String,
        ) : Step
    }

    /**
     * Called once the connection has been promoted and the pin decision was `PairingRequired`.
     *
     * A null [derivedSas6] is a **hard failure**, not a degraded mode: without the exporter there
     * is no channel binding, so the six digits would not be bound to this TLS session and the
     * confirmation would be theatre. ADR-007 Amendment A1 forbids substituting something weaker.
     */
    fun begin(derivedSas6: String?): Step {
        if (derivedSas6 == null) return Step.Failed(ERROR_CODE_INTERNAL)
        sas6 = derivedSas6
        return Step.Wait
    }

    fun onPairRequest(
        displayName: String,
        advertisedSpki: SpkiHash,
    ): Step {
        // The certificate is authoritative (PROTOCOL §4.1); this field is advisory and is
        // cross-checked for the same reason HELLO's is.
        if (advertisedSpki != peerIdentitySpkiSha256) return Step.Failed(ERROR_CODE_IDENTITY_MISMATCH)
        peerDisplayName = displayName
        return Step.Wait
    }

    /** The user tapped confirm or reject on **this** device. */
    fun onLocalDecision(accepted: Boolean): Step {
        if (!accepted) return Step.Failed(ERROR_CODE_PAIRING_REJECTED)
        if (sas6 == null) return Step.Failed(ERROR_CODE_INTERNAL)
        localConfirmed.set(true)
        // Only the initiator sends PAIR_CONFIRM; the acceptor's local decision is combined with
        // the initiator's confirmation when that frame arrives.
        return if (isInitiator) Step.SendPairConfirm else settleIfBothConfirmed()
    }

    fun onPairConfirm(accepted: Boolean): Step {
        if (!accepted) return Step.Failed(ERROR_CODE_PAIRING_REJECTED)
        remoteConfirmed.set(true)
        return settleIfBothConfirmed()
    }

    fun onPairResult(
        accepted: Boolean,
        advertisedSpki: SpkiHash,
    ): Step {
        if (!accepted) return Step.Failed(ERROR_CODE_PAIRING_REJECTED)
        if (advertisedSpki != peerIdentitySpkiSha256) return Step.Failed(ERROR_CODE_IDENTITY_MISMATCH)
        remoteConfirmed.set(true)
        return settleIfBothConfirmed()
    }

    /**
     * Writes the pin, but only once both humans have agreed and only once overall.
     *
     * The acceptor additionally answers `PAIR_RESULT`; the initiator, which reaches this point by
     * *receiving* that frame, has nothing left to send.
     */
    private fun settleIfBothConfirmed(): Step {
        if (!localConfirmed.get() || !remoteConfirmed.get()) return Step.Wait
        if (!completed.compareAndSet(false, true)) return Step.Wait
        val now = nowEpochSeconds()
        val peer =
            TrustedPeer(
                peerId = remotePeerId,
                identitySpkiSha256 = peerIdentitySpkiSha256,
                displayName = peerDisplayName.ifEmpty { remotePeerId.value },
                pairedAtEpochSeconds = now,
                lastSeenAtEpochSeconds = now,
            )
        trustedPeers.remember(peer)
        return if (isInitiator) Step.Succeeded(peer) else Step.SendPairResultAccepted
    }

    /** The acceptor's second half of [settleIfBothConfirmed]: the record is already written. */
    fun completedPeer(): TrustedPeer? =
        if (completed.get()) {
            trustedPeers.byPeerId(remotePeerId)
        } else {
            null
        }
}
