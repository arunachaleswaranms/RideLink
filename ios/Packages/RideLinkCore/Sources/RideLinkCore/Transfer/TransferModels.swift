import Foundation

/// ADR-023 / PROTOCOL §8.2 — one transfer's lifecycle. `.complete`, `.failed` and `.cancelled` are
/// terminal: `TransferReducer` never transitions out of them again (brief §43).
///
/// Raw values match the Kotlin enum constant names exactly, since `protocol/vectors/transfer-fsm/`
/// names them that way and both platforms read the same file.
public enum TransferStatus: String, Sendable, Equatable, CaseIterable {
    case idle = "IDLE"
    case queued = "QUEUED"
    case negotiating = "NEGOTIATING"
    case transferring = "TRANSFERRING"
    case verifying = "VERIFYING"
    case complete = "COMPLETE"
    case failed = "FAILED"
    case cancelled = "CANCELLED"

    public var isTerminal: Bool { self == .complete || self == .failed || self == .cancelled }
}

/// Machine-readable transfer failures — never a human string parsed to decide behaviour.
public enum TransferError: String, Sendable, Equatable, CaseIterable {
    case notFound = "NOT_FOUND"
    case notAuthorized = "NOT_AUTHORIZED"
    case invalidRequest = "INVALID_REQUEST"
    case unsupported = "UNSUPPORTED"
    case fileChanged = "FILE_CHANGED"
    case sizeMismatch = "SIZE_MISMATCH"
    case hashMismatch = "HASH_MISMATCH"
    case diskFull = "DISK_FULL"
    case ioError = "IO_ERROR"
    case connectionLost = "CONNECTION_LOST"
    case cancelled = "CANCELLED"
    case protocolError = "PROTOCOL_ERROR"
}

/// One input to `TransferReducer`.
public enum TransferEvent: Sendable, Equatable {
    case enqueued
    case dequeued
    case offerReceived(sizeBytes: Int64, chunkCount: Int)
    case offerRejected(error: TransferError)
    case bytesReceived(bytes: Int64)
    case sizeMismatchDetected
    case allBytesReceived
    case hashVerified(matches: Bool)
    case ioErrorDetected
    case diskFullDetected
    /// The bulk connection or the control session it is bound to (ADR-023) dropped.
    case connectionLost
    /// ADR-023 §3 — the control session/generation that authorised this transfer no longer matches.
    case sessionInvalidated
    case cancelled
}

/// One side-effect `TransferReducer` asks the caller to perform. A diff, never a restatement.
public enum TransferAction: Sendable, Equatable {
    case sendTransferRequest
    case openBulkConnection
    case writeChunkToPart
    case reportProgress(bytes: Int64)
    case computeHashFromDisk
    /// The only path to a verified cache entry (ADR-023 §6) — atomic `.part` -> final rename.
    case promoteCacheEntry
    case deletePartFile
    case closeBulkConnection
    case notifyUi(status: TransferStatus, error: TransferError? = nil)
}

public struct TransferTransition: Sendable, Equatable {
    public let status: TransferStatus
    public let actions: [TransferAction]
    public let error: TransferError?

    public init(status: TransferStatus, actions: [TransferAction], error: TransferError?) {
        self.status = status
        self.actions = actions
        self.error = error
    }
}
