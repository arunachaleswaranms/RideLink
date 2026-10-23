package com.ridelink.network.control

import com.ridelink.core.model.PeerId
import com.ridelink.core.security.PeerTrust
import com.ridelink.core.security.PinDecision
import com.ridelink.core.security.TrustedPeerStore

/**
 * Serialises a user's *Forget peer* with the instant a trusted connection passes the trust gate
 * (ADR-012 Amendment A1, STATUS §4 problem 126). Owned by [ControlSessionManager], one per manager.
 *
 * A `Trusted` verdict is read from the store inside the `HELLO` exchange, and the connection is
 * promoted only after duplicate resolution's grace period. A forget that lands in between must win.
 * So [admit] re-asks the pin ([PeerTrust.recheck]) and, only if it still matches, runs the caller's
 * activation **on the same lock** [forget] takes — without suspending. A forget is therefore either
 * before the re-check (and the peer is offered PROTOCOL §4.5's code instead of a silent connect) or
 * after the activation (which only a live session can contain, and the coordinator refuses to forget a
 * live session's peer — [com.ridelink.core.security.ForgetPeerPolicy]).
 *
 * A plain monitor rather than a coroutine mutex: neither side suspends while holding it, and [forget]
 * is called from the UI thread, which must not wait on a mutex.
 */
class TrustRevocationGate internal constructor(
    private val trustedPeers: TrustedPeerStore,
) {
    private val lock = Any()

    /**
     * Removes [peerId]'s pin, and only that record. A dial that starts later reads the store afresh in
     * its own handshake and is offered PROTOCOL §4.5's code.
     *
     * It deliberately does **not** end an already-authenticated connection: ending a live session has
     * one owner (ADR-026), so the coordinator refuses to forget outside `IDLE` instead of this growing
     * a second teardown path.
     *
     * @throws java.io.IOException if the store could not make the removal durable; the peer is then
     *   still trusted, and the caller must say so rather than report success.
     */
    fun forget(peerId: PeerId) {
        synchronized(lock) { trustedPeers.forget(peerId) }
    }

    /**
     * The decision that actually applies to [outcome] now. For a `Trusted` handshake verdict the pin is
     * re-read, and [activate] runs under the lock exactly when the answer is still `Trusted`; any other
     * verdict is returned unchanged and [activate] is not run. [activate] must not suspend.
     */
    internal fun admit(
        outcome: HandshakeOutcome.Success,
        activate: () -> Unit,
    ): PinDecision =
        synchronized(lock) {
            val decision =
                if (outcome.pinDecision == PinDecision.Trusted) {
                    PeerTrust.recheck(trustedPeers.byPeerId(outcome.remotePeerId)?.identitySpkiSha256, outcome.peerIdentitySpkiSha256)
                } else {
                    outcome.pinDecision
                }
            if (decision == PinDecision.Trusted) activate()
            decision
        }
}
