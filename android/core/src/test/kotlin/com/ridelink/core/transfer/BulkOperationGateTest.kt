package com.ridelink.core.transfer

import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.SpkiHash
import com.ridelink.core.model.TransferId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Closure-audit Finding A (provider-side transfer ownership) and its cross-role counterpart
 * (brief §17/§18): the pure proofs behind "a second request cannot overwrite active ownership,"
 * "a wrong-transfer cancel cannot reach the real active operation," and "a stale cleanup cannot
 * clear a newer operation." The coordinator integration itself is exercised by
 * [com.ridelink.app.library.SharedLibraryCoordinator]'s own usage, exactly like [OperationFenceTest]
 * documents for [OperationFence].
 */
class BulkOperationGateTest {
    private val hashA = ContentHash("sha256:" + "a".repeat(64))
    private val hashB = ContentHash("sha256:" + "b".repeat(64))
    private val spki = SpkiHash("sha256:" + "cd".repeat(32))
    private val transferA = TransferId("01ARZ3NDEKTSV4RRFFQ69G5FAV")
    private val transferB = TransferId("01BXAZ3NDEKTSV4RRFFQ69G5FB")

    @Test
    fun `an empty gate has no current owner`() {
        val gate = BulkOperationGate()
        assertNull(gate.current)
        assertFalse(gate.isOwner(transferA))
    }

    @Test
    fun `acquiring a free gate succeeds and records the owner`() {
        val gate = BulkOperationGate()
        val owner = BulkOperationOwner.Provider(transferA, hashA, spki, sessionGeneration = 1L)
        assertTrue(gate.tryAcquire(owner))
        assertTrue(gate.isOwner(transferA))
        assertTrue(gate.current == owner)
    }

    @Test
    fun `finding A- a second provider request cannot overwrite the active owner`() {
        val gate = BulkOperationGate()
        val ownerA = BulkOperationOwner.Provider(transferA, hashA, spki, sessionGeneration = 1L)
        val ownerB = BulkOperationOwner.Provider(transferB, hashB, spki, sessionGeneration = 1L)
        assertTrue(gate.tryAcquire(ownerA), "A must win the free slot")
        assertFalse(gate.tryAcquire(ownerB), "B must not overwrite A's ownership while A is active")
        assertTrue(gate.isOwner(transferA), "A must remain the owner after B's denied attempt")
        assertFalse(gate.isOwner(transferB))
    }

    @Test
    fun `a cancel naming the non-active transfer is not routed to the active operation`() {
        val gate = BulkOperationGate()
        gate.tryAcquire(BulkOperationOwner.Provider(transferA, hashA, spki, sessionGeneration = 1L))
        // CANCEL B arrives while A is active: isOwner(B) must be false, so a caller wired correctly
        // never calls cancelActive(transferId) for it.
        assertFalse(gate.isOwner(transferB))
        assertTrue(gate.isOwner(transferA), "CANCEL B must never disturb A's ownership")
    }

    @Test
    fun `a cancel naming the active transfer is routed correctly and clears the slot`() {
        val gate = BulkOperationGate()
        gate.tryAcquire(BulkOperationOwner.Provider(transferA, hashA, spki, sessionGeneration = 1L))
        assertTrue(gate.isOwner(transferA))
        gate.releaseIfOwner(transferA)
        assertNull(gate.current, "the slot must become available once its owner is released")
    }

    @Test
    fun `a stale release from a superseded operation cannot clear a newer operation`() {
        val gate = BulkOperationGate()
        val ownerA = BulkOperationOwner.Provider(transferA, hashA, spki, sessionGeneration = 1L)
        assertTrue(gate.tryAcquire(ownerA))
        // A's own cleanup runs late, after A already relinquished conceptually and B has taken over.
        gate.releaseIfOwner(transferA)
        val ownerB = BulkOperationOwner.Provider(transferB, hashB, spki, sessionGeneration = 1L)
        assertTrue(gate.tryAcquire(ownerB))
        // A's delayed defer/finally now runs — it must not clear B.
        gate.releaseIfOwner(transferA)
        assertTrue(gate.isOwner(transferB), "A's stale cleanup must not clear B's active ownership")
    }

    @Test
    fun `session boundary invalidation frees the slot unconditionally`() {
        val gate = BulkOperationGate()
        gate.tryAcquire(BulkOperationOwner.Provider(transferA, hashA, spki, sessionGeneration = 1L))
        gate.invalidate()
        assertNull(gate.current)
        assertFalse(gate.isOwner(transferA))
        // A fresh operation may now acquire normally.
        assertTrue(gate.tryAcquire(BulkOperationOwner.Provider(transferB, hashB, spki, sessionGeneration = 2L)))
    }

    @Test
    fun `requester and provider roles are mutually exclusive over the same slot`() {
        val gate = BulkOperationGate()
        val requester = BulkOperationOwner.Requester(transferA, hashA)
        assertTrue(gate.tryAcquire(requester), "a requester download may start when the slot is free")
        val provider = BulkOperationOwner.Provider(transferB, hashB, spki, sessionGeneration = 1L)
        assertFalse(gate.tryAcquire(provider), "an inbound provider request must not steal the slot from an active requester download")
        assertTrue(gate.isOwner(transferA), "the requester's download must remain the owner")

        gate.releaseIfOwner(transferA)
        assertTrue(gate.tryAcquire(provider), "once the requester download finishes, the provider role may acquire the now-free slot")
    }

    @Test
    fun `an active provider operation blocks a local requester download from starting`() {
        val gate = BulkOperationGate()
        val provider = BulkOperationOwner.Provider(transferA, hashA, spki, sessionGeneration = 1L)
        assertTrue(gate.tryAcquire(provider))
        val requester = BulkOperationOwner.Requester(transferB, hashB)
        assertFalse(gate.tryAcquire(requester), "a local download must not start while this device is actively serving a peer")
        assertTrue(gate.isOwner(transferA), "the provider operation must remain correct and undisturbed")
    }

    // --- ADR-023 Amendment A5: gate ownership is only half of "still authorised" -----------------

    private fun heldGate(): BulkOperationGate =
        BulkOperationGate().also { it.tryAcquire(BulkOperationOwner.Provider(transferA, hashA, spki, sessionGeneration = 7L)) }

    private val authorisationA = ProviderSessionContext(authorisingGeneration = 7L, authorisedPeerSpki = spki)

    @Test
    fun `still authorised while the slot is held and the authorising session is still live`() {
        assertTrue(heldGate().stillAuthorises(transferA, authorisationA, liveGeneration = 7L, livePeerSpki = spki))
    }

    /**
     * **The A5 Finding C property.** This is the exact state iOS's `onSessionBoundary()` is in
     * while it `await`s `TransferManager.close()`: the session epoch has already been bumped, and
     * `bulkGate.invalidate()` has not run yet — so gate ownership alone still says "yes". It must
     * not, because the operation is already authorised by a session that no longer exists.
     */
    @Test
    fun `gate ownership alone is not authorisation once the live generation has moved on`() {
        val gate = heldGate()
        assertTrue(gate.isOwner(transferA), "the precondition: the gate has NOT been invalidated yet")
        assertFalse(gate.stillAuthorises(transferA, authorisationA, liveGeneration = 8L, livePeerSpki = spki))
    }

    /** The same, for the peer half of the context — defence in depth per [ProviderSessionContext]. */
    @Test
    fun `gate ownership alone is not authorisation once the live peer has changed`() {
        val gate = heldGate()
        val otherPeer = SpkiHash("sha256:" + "ef".repeat(32))
        assertTrue(gate.isOwner(transferA))
        assertFalse(gate.stillAuthorises(transferA, authorisationA, liveGeneration = 7L, livePeerSpki = otherPeer))
    }

    /** A disconnected session has no live peer at all; that is not "still current" either. */
    @Test
    fun `a null live peer is never still current`() {
        assertFalse(heldGate().stillAuthorises(transferA, authorisationA, liveGeneration = 7L, livePeerSpki = null))
    }

    /** The gate half is not dropped: a live session does not authorise an operation that has since
     *  lost the slot to a fresher one, which is precisely what A3's check was protecting. */
    @Test
    fun `a live session does not authorise an operation that no longer owns the slot`() {
        val gate = heldGate()
        gate.invalidate()
        assertFalse(gate.stillAuthorises(transferA, authorisationA, liveGeneration = 7L, livePeerSpki = spki))
        gate.tryAcquire(BulkOperationOwner.Requester(transferB, hashB))
        assertFalse(gate.stillAuthorises(transferA, authorisationA, liveGeneration = 7L, livePeerSpki = spki))
    }
}
