import Foundation

/// The Phase 4 transfer state machine (ADR-023): `(status, event) -> (status, actions, error)`.
/// Pure and mirrored, pinned by `protocol/vectors/transfer-fsm/`, following the same discipline as
/// `SessionGate` (ADR-019) and `IntercomTransmission` (ADR-021) — one pure reducer, no scattered
/// booleans, terminal states that stay terminal.
///
/// Models the requester side, since that is what drives the UI. The provider side has no
/// comparable state machine of its own: it only ever serves one active transfer at a time (brief
/// §20), which the coordinator that owns this reducer's state already bounds.
///
/// The Kotlin mirror is `com.ridelink.core.transfer.TransferReducer`.
public enum TransferReducer {
    public static func apply(status: TransferStatus, event: TransferEvent) -> TransferTransition {
        if status.isTerminal {
            // Brief §43: terminal states are terminal for that transfer generation. A late event
            // (a stray chunk after cancellation, a delayed HashVerified after a fresh retry began
            // its own new instance) is a no-op, never a resurrection.
            return TransferTransition(status: status, actions: [], error: nil)
        }
        switch event {
        case .enqueued:
            return fromOnly(status, required: .idle, next: .queued) { [.notifyUi(status: .queued)] }
        case .dequeued:
            return fromOnly(status, required: .queued, next: .negotiating) {
                [.sendTransferRequest, .notifyUi(status: .negotiating)]
            }
        case .offerReceived:
            return fromOnly(status, required: .negotiating, next: .transferring) {
                [.openBulkConnection, .notifyUi(status: .transferring)]
            }
        case .offerRejected(let error):
            return failFrom(status, allowedFrom: [.negotiating], error: error, cleanup: false)
        case .bytesReceived(let bytes):
            guard status == .transferring else { return noOp(status) }
            return TransferTransition(
                status: .transferring,
                actions: [.writeChunkToPart, .reportProgress(bytes: bytes)],
                error: nil
            )
        case .sizeMismatchDetected:
            return failFrom(status, allowedFrom: [.transferring], error: .sizeMismatch, cleanup: true)
        case .allBytesReceived:
            return fromOnly(status, required: .transferring, next: .verifying) {
                [.computeHashFromDisk, .notifyUi(status: .verifying)]
            }
        case .hashVerified(let matches):
            guard status == .verifying else { return noOp(status) }
            if matches {
                return TransferTransition(
                    status: .complete,
                    actions: [.promoteCacheEntry, .closeBulkConnection, .notifyUi(status: .complete)],
                    error: nil
                )
            }
            return failFrom(status, allowedFrom: [.verifying], error: .hashMismatch, cleanup: true)
        case .ioErrorDetected:
            return failFrom(status, allowedFrom: [.transferring, .verifying], error: .ioError, cleanup: true)
        case .diskFullDetected:
            return failFrom(status, allowedFrom: [.transferring], error: .diskFull, cleanup: true)
        case .connectionLost:
            return failFrom(
                status,
                allowedFrom: [.negotiating, .transferring, .verifying],
                error: .connectionLost,
                cleanup: status == .transferring || status == .verifying
            )
        case .sessionInvalidated:
            return failFrom(
                status,
                allowedFrom: [.queued, .negotiating, .transferring, .verifying],
                error: .notAuthorized,
                cleanup: status == .transferring || status == .verifying
            )
        case .cancelled:
            guard status != .idle else { return noOp(status) }
            let cleanup = status == .transferring || status == .verifying
            var actions: [TransferAction] = []
            if cleanup { actions += [.deletePartFile, .closeBulkConnection] }
            actions.append(.notifyUi(status: .cancelled))
            return TransferTransition(status: .cancelled, actions: actions, error: nil)
        }
    }

    private static func noOp(_ status: TransferStatus) -> TransferTransition {
        TransferTransition(status: status, actions: [], error: nil)
    }

    private static func fromOnly(
        _ status: TransferStatus,
        required: TransferStatus,
        next: TransferStatus,
        actions: () -> [TransferAction]
    ) -> TransferTransition {
        guard status == required else { return noOp(status) }
        return TransferTransition(status: next, actions: actions(), error: nil)
    }

    private static func failFrom(
        _ status: TransferStatus,
        allowedFrom: Set<TransferStatus>,
        error: TransferError,
        cleanup: Bool
    ) -> TransferTransition {
        guard allowedFrom.contains(status) else { return noOp(status) }
        var actions: [TransferAction] = []
        if cleanup { actions += [.deletePartFile, .closeBulkConnection] }
        actions.append(.notifyUi(status: .failed, error: error))
        return TransferTransition(status: .failed, actions: actions, error: error)
    }
}
