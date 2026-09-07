package com.ridelink.core.transfer

import com.ridelink.core.model.SpkiHash
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-023 Amendment A3 — pure proofs for the re-authorisation check every suspension point in
 * [com.ridelink.app.library.SharedLibraryCoordinator.serveTransferRequest] must pass before
 * touching [BulkOperationGate], minting a bulk token, or sending a `TRANSFER_OFFER`.
 */
class ProviderSessionContextTest {
    private val peerA = SpkiHash("sha256:" + "aa".repeat(32))
    private val peerB = SpkiHash("sha256:" + "bb".repeat(32))

    @Test
    fun `still current when live generation and peer both match what authorised the operation`() {
        val context = ProviderSessionContext(authorisingGeneration = 10L, authorisedPeerSpki = peerA)
        assertTrue(context.isStillCurrent(liveGeneration = 10L, livePeerSpki = peerA))
    }

    @Test
    fun `not current once a reconnect bumps the live generation, even to the same peer`() {
        val context = ProviderSessionContext(authorisingGeneration = 10L, authorisedPeerSpki = peerA)
        assertFalse(context.isStillCurrent(liveGeneration = 11L, livePeerSpki = peerA))
    }

    @Test
    fun `not current once the live peer differs, even if the generation number were somehow unchanged`() {
        val context = ProviderSessionContext(authorisingGeneration = 10L, authorisedPeerSpki = peerA)
        assertFalse(context.isStillCurrent(liveGeneration = 10L, livePeerSpki = peerB))
    }

    @Test
    fun `not current when the live peer is null, meaning no session is authenticated right now`() {
        val context = ProviderSessionContext(authorisingGeneration = 10L, authorisedPeerSpki = peerA)
        assertFalse(context.isStillCurrent(liveGeneration = 10L, livePeerSpki = null))
    }

    @Test
    fun `not current when both generation and peer have moved on to a new session`() {
        val context = ProviderSessionContext(authorisingGeneration = 10L, authorisedPeerSpki = peerA)
        assertFalse(context.isStillCurrent(liveGeneration = 11L, livePeerSpki = peerB))
    }
}
