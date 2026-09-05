package com.ridelink.core.transfer

/**
 * ADR-023 / PROTOCOL §8.2 — one transfer's lifecycle. `COMPLETE`, `FAILED` and `CANCELLED` are
 * terminal: [TransferReducer] never transitions out of them again (brief §43).
 */
enum class TransferStatus {
    IDLE,
    QUEUED,
    NEGOTIATING,
    TRANSFERRING,
    VERIFYING,
    COMPLETE,
    FAILED,
    CANCELLED,
    ;

    val isTerminal: Boolean get() = this == COMPLETE || this == FAILED || this == CANCELLED
}

/** Machine-readable transfer failures — never a human string parsed to decide behaviour. */
enum class TransferError {
    NOT_FOUND,
    NOT_AUTHORIZED,
    INVALID_REQUEST,
    UNSUPPORTED,
    FILE_CHANGED,
    SIZE_MISMATCH,
    HASH_MISMATCH,
    DISK_FULL,
    IO_ERROR,
    CONNECTION_LOST,
    CANCELLED,
    PROTOCOL_ERROR,
}

/** One input to [TransferReducer]. */
sealed class TransferEvent {
    object Enqueued : TransferEvent()

    object Dequeued : TransferEvent()

    data class OfferReceived(
        val sizeBytes: Long,
        val chunkCount: Int,
    ) : TransferEvent()

    data class OfferRejected(
        val error: TransferError,
    ) : TransferEvent()

    data class BytesReceived(
        val bytes: Long,
    ) : TransferEvent()

    object SizeMismatchDetected : TransferEvent()

    object AllBytesReceived : TransferEvent()

    data class HashVerified(
        val matches: Boolean,
    ) : TransferEvent()

    object IoErrorDetected : TransferEvent()

    object DiskFullDetected : TransferEvent()

    /** The bulk connection or the control session it is bound to (ADR-023) dropped. */
    object ConnectionLost : TransferEvent()

    /** ADR-023 §3 — the control session/generation that authorised this transfer no longer matches. */
    object SessionInvalidated : TransferEvent()

    object Cancelled : TransferEvent()
}

/** One side-effect [TransferReducer] asks the caller to perform. A diff, never a restatement. */
sealed class TransferAction {
    object SendTransferRequest : TransferAction()

    object OpenBulkConnection : TransferAction()

    object WriteChunkToPart : TransferAction()

    data class ReportProgress(
        val bytes: Long,
    ) : TransferAction()

    object ComputeHashFromDisk : TransferAction()

    /** The only path to a verified cache entry (ADR-023 §6) — atomic `.part` -> final rename. */
    object PromoteCacheEntry : TransferAction()

    object DeletePartFile : TransferAction()

    object CloseBulkConnection : TransferAction()

    data class NotifyUi(
        val status: TransferStatus,
        val error: TransferError? = null,
    ) : TransferAction()
}

data class TransferTransition(
    val status: TransferStatus,
    val actions: List<TransferAction>,
    val error: TransferError?,
)
