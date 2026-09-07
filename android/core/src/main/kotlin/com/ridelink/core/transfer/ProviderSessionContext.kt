package com.ridelink.core.transfer

import com.ridelink.core.model.SpkiHash

/**
 * ADR-023 Amendment A3 — the session that authorised a provider operation, captured once at
 * `TRANSFER_REQUEST` dispatch time, and the pure decision of whether that authorisation still
 * holds.
 *
 * Amendment A2's Finding B fix (the live-generation check at the top of `handleTransferMessage`)
 * only proves the request was dispatched under the session that is current *at that instant*. It
 * proves nothing about whatever suspends between then and the moment the provider operation
 * actually acquires [BulkOperationGate], mints a bulk token, or emits a `TRANSFER_OFFER` onto the
 * wire — `contentResolver.resolve()`, `ensureListening()`, and the control-channel `send()` call
 * are all real suspension points a session boundary can run inside. [isStillCurrent] is the
 * re-check every one of those transitions must pass before it may proceed.
 *
 * Comparing [authorisedPeerSpki] as well as [authorisingGeneration] is deliberate defence in depth
 * rather than a second independent signal: both platforms' `activateAuthenticatedSession`
 * strictly increases the generation counter on **every** activation, including a reconnect that
 * re-authenticates the *same* peer, so the live peer identity cannot in fact change without the
 * live generation also changing. [isStillCurrent] checks both anyway because the comparison is
 * free and the ADR calls for it explicitly — this class does not rely on the invariant holding to
 * be correct, it is simply never able to observe it fail.
 */
data class ProviderSessionContext(
    val authorisingGeneration: Long,
    val authorisedPeerSpki: SpkiHash,
) {
    fun isStillCurrent(
        liveGeneration: Long,
        livePeerSpki: SpkiHash?,
    ): Boolean = authorisingGeneration == liveGeneration && authorisedPeerSpki == livePeerSpki
}
