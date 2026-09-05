package com.ridelink.core.transfer

/**
 * The Phase 4 transfer state machine (ADR-023): `(status, event) -> (status, actions, error)`.
 * Pure and mirrored, pinned by `protocol/vectors/transfer-fsm/`, following the same discipline as
 * `SessionGate` (ADR-019) and `IntercomTransmission` (ADR-021) — one pure reducer, no scattered
 * booleans, terminal states that stay terminal.
 *
 * Models the requester side, since that is what drives the UI. The provider side has no
 * comparable state machine of its own: it only ever serves one active transfer at a time (brief
 * §20), which the coordinator that owns [TransferReducer] instances already bounds.
 */
object TransferReducer {
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun apply(
        status: TransferStatus,
        event: TransferEvent,
    ): TransferTransition {
        if (status.isTerminal) {
            // Brief §43: terminal states are terminal for that transfer generation. A late event
            // (a stray chunk after cancellation, a delayed HashVerified after a fresh retry began
            // its own new instance) is a no-op, never a resurrection.
            return TransferTransition(status, emptyList(), null)
        }
        return when (event) {
            TransferEvent.Enqueued ->
                fromOnly(status, TransferStatus.IDLE, TransferStatus.QUEUED) {
                    listOf(TransferAction.NotifyUi(TransferStatus.QUEUED))
                }
            TransferEvent.Dequeued ->
                fromOnly(status, TransferStatus.QUEUED, TransferStatus.NEGOTIATING) {
                    listOf(TransferAction.SendTransferRequest, TransferAction.NotifyUi(TransferStatus.NEGOTIATING))
                }
            is TransferEvent.OfferReceived ->
                fromOnly(status, TransferStatus.NEGOTIATING, TransferStatus.TRANSFERRING) {
                    listOf(TransferAction.OpenBulkConnection, TransferAction.NotifyUi(TransferStatus.TRANSFERRING))
                }
            is TransferEvent.OfferRejected ->
                failFrom(status, setOf(TransferStatus.NEGOTIATING), event.error, cleanup = false)
            is TransferEvent.BytesReceived ->
                if (status != TransferStatus.TRANSFERRING) {
                    noOp(status)
                } else {
                    TransferTransition(
                        TransferStatus.TRANSFERRING,
                        listOf(TransferAction.WriteChunkToPart, TransferAction.ReportProgress(event.bytes)),
                        null,
                    )
                }
            TransferEvent.SizeMismatchDetected ->
                failFrom(status, setOf(TransferStatus.TRANSFERRING), TransferError.SIZE_MISMATCH, cleanup = true)
            TransferEvent.AllBytesReceived ->
                fromOnly(status, TransferStatus.TRANSFERRING, TransferStatus.VERIFYING) {
                    listOf(TransferAction.ComputeHashFromDisk, TransferAction.NotifyUi(TransferStatus.VERIFYING))
                }
            is TransferEvent.HashVerified ->
                if (status != TransferStatus.VERIFYING) {
                    noOp(status)
                } else if (event.matches) {
                    TransferTransition(
                        TransferStatus.COMPLETE,
                        listOf(
                            TransferAction.PromoteCacheEntry,
                            TransferAction.CloseBulkConnection,
                            TransferAction.NotifyUi(TransferStatus.COMPLETE),
                        ),
                        null,
                    )
                } else {
                    failFrom(status, setOf(TransferStatus.VERIFYING), TransferError.HASH_MISMATCH, cleanup = true)
                }
            TransferEvent.IoErrorDetected ->
                failFrom(status, setOf(TransferStatus.TRANSFERRING, TransferStatus.VERIFYING), TransferError.IO_ERROR, cleanup = true)
            TransferEvent.DiskFullDetected ->
                failFrom(status, setOf(TransferStatus.TRANSFERRING), TransferError.DISK_FULL, cleanup = true)
            TransferEvent.ConnectionLost ->
                failFrom(
                    status,
                    setOf(TransferStatus.NEGOTIATING, TransferStatus.TRANSFERRING, TransferStatus.VERIFYING),
                    TransferError.CONNECTION_LOST,
                    cleanup = status == TransferStatus.TRANSFERRING || status == TransferStatus.VERIFYING,
                )
            TransferEvent.SessionInvalidated ->
                failFrom(
                    status,
                    setOf(TransferStatus.QUEUED, TransferStatus.NEGOTIATING, TransferStatus.TRANSFERRING, TransferStatus.VERIFYING),
                    TransferError.NOT_AUTHORIZED,
                    cleanup = status == TransferStatus.TRANSFERRING || status == TransferStatus.VERIFYING,
                )
            TransferEvent.Cancelled ->
                if (status == TransferStatus.IDLE) {
                    noOp(status)
                } else {
                    val cleanup = status == TransferStatus.TRANSFERRING || status == TransferStatus.VERIFYING
                    val actions =
                        buildList {
                            if (cleanup) {
                                add(TransferAction.DeletePartFile)
                                add(TransferAction.CloseBulkConnection)
                            }
                            add(TransferAction.NotifyUi(TransferStatus.CANCELLED))
                        }
                    TransferTransition(TransferStatus.CANCELLED, actions, null)
                }
        }
    }

    private fun noOp(status: TransferStatus) = TransferTransition(status, emptyList(), null)

    private inline fun fromOnly(
        status: TransferStatus,
        required: TransferStatus,
        next: TransferStatus,
        actions: () -> List<TransferAction>,
    ): TransferTransition = if (status != required) noOp(status) else TransferTransition(next, actions(), null)

    private fun failFrom(
        status: TransferStatus,
        allowedFrom: Set<TransferStatus>,
        error: TransferError,
        cleanup: Boolean,
    ): TransferTransition {
        if (status !in allowedFrom) return noOp(status)
        val actions =
            buildList {
                if (cleanup) {
                    add(TransferAction.DeletePartFile)
                    add(TransferAction.CloseBulkConnection)
                }
                add(TransferAction.NotifyUi(TransferStatus.FAILED, error))
            }
        return TransferTransition(TransferStatus.FAILED, actions, error)
    }
}
